'use strict';

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));
const DAY_MS = 24 * 60 * 60 * 1000;
const SIGNATURE_REQUIRED_FROM = 1793491200;

const state = {
  server: '',
  uuid: '',
  pages: [],
  transfer: null,
  transferKey: null,
  pairPurpose: 'handoff',
  imported: false,
  contacts: [],
  history: [],
  config: {},
  pageMailboxes: [],
  photoShare: 'https://share.h4ck.io',
  attachmentBusy: false,
  ownName: '',
  ownPublic: '',
  ownSecret: '',
  ownKm: null,
  contactKms: new Map(),
  seen: new Set(),
  syncSeen: new Set(),
  modes: new Map(),
  pendingAcks: new Map(),
  activeContact: null,
  replyTarget: null,
  syncTimer: null,
  modeTimer: null,
  renewalTimer: null,
  checkingMode: false,
  pagesRequest: 0,
  renewalSnoozedUntil: 0,
  transferErrorShown: false,
  transferFailures: 0,
};
let transferPoll = null;
let pairModal = null;

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function toast(message) {
  const body = $('#toastBody');
  const el = $('#toast');
  if (!body || !el || !window.bootstrap) return;
  body.textContent = message;
  bootstrap.Toast.getOrCreateInstance(el).show();
}

function statusNumber(raw) {
  if (typeof raw === 'number') return raw;
  if (typeof raw === 'string' && /^\d+$/.test(raw)) return Number(raw);
  const text = String(raw || '').toLowerCase();
  if (['success', 'ok', 'done', 'complete', 'completed'].includes(text)) return 0;
  if (['warning', 'warn'].includes(text)) return 1;
  if (['failed', 'error', 'fail'].includes(text)) return 2;
  if (['critical', 'fatal', 'urgent'].includes(text)) return 3;
  if (['in progress', 'running'].includes(text)) return 4;
  if (['queued', 'pending'].includes(text)) return 5;
  return 1;
}

function pageStatus(page) {
  const prio = String(page.prio || '').toLowerCase();
  if (['debug', 'info', 'warning', 'alarm', 'critical'].includes(prio)) {
    return { debug: 7, info: 0, warning: 1, alarm: 2, critical: 3 }[prio];
  }
  return statusNumber(page.status);
}

function timeLabel(epoch) {
  if (!epoch) return '—';
  const date = new Date(epoch * 1000);
  const now = new Date();
  const sameDay = date.toDateString() === now.toDateString();
  return sameDay
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

function dayLabel(epoch) {
  if (!epoch) return '—';
  return new Date(epoch * 1000).toLocaleDateString([], {
    weekday: 'short', year: 'numeric', month: 'short', day: 'numeric',
  });
}

function setTheme(theme, persist = true) {
  document.documentElement.setAttribute('data-bs-theme', theme);
  $('#themeIcon').textContent = theme === 'dark' ? '◐' : '☀';
  if (persist) localStorage.setItem('pagerapp.theme', theme);
}

function initTheme() {
  const stored = localStorage.getItem('pagerapp.theme');
  const preferred = stored || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
  setTheme(preferred, false);
}

function showView(name) {
  $$('.view').forEach((view) => view.classList.add('d-none'));
  const view = document.getElementById(`view-${name}`);
  if (view) view.classList.remove('d-none');
  $$('#mainNav .nav-link').forEach((button) => button.classList.toggle('active', button.dataset.view === name));
  if (name === 'chats') renderChats();
}

function updateConnection() {
  const paired = Boolean(state.uuid && state.imported);
  $('#mailboxChip').textContent = paired ? 'paired' : 'waiting for app';
  $('#mailboxChip').className = `chip ${paired ? 'ok' : ''}`;
  $('#connectNote').textContent = paired
    ? 'Paired through PagerAPP. This tab only.'
    : 'Not paired. Open PagerAPP and scan the code.';
  $('#chatModeChip').textContent = paired
    ? (modeActive(state.server, state.uuid) ? 'webclient active' : 'renew needed')
    : 'no session';
  $('#chatModeChip').className = `chip ${paired && modeActive(state.server, state.uuid) ? 'ok' : 'bad'}`;
  const renew = $('#chatRenew');
  if (renew) renew.classList.toggle('d-none', !paired || allModesActive());
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, { cache: 'no-store', ...options });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!response.ok) throw new Error((body && body.error) || `HTTP ${response.status}`);
  return body;
}

// --- Webclient mode / lease -------------------------------------------------

function modeKey(server, mailbox) {
  return `${String(server || '').replace(/\/+$/, '')}|${mailbox}`;
}

function setMode(server, mailbox, active, expires = null) {
  state.modes.set(modeKey(server, mailbox), {
    active: Boolean(active),
    expires: expires || null,
    checkedAt: Date.now(),
  });
}

function modeActive(server, mailbox) {
  return Boolean(state.modes.get(modeKey(server, mailbox))?.active);
}

async function refreshMode(server, mailbox) {
  try {
    const mode = await fetchJson(
      `/api/mailbox/${encodeURIComponent(mailbox)}/mode?server=${encodeURIComponent(server)}`,
      { headers: { Authorization: `Bearer ${mailbox}` } },
    );
    setMode(server, mailbox, Boolean(mode?.webclient), mode?.expires);
    return Boolean(mode?.webclient);
  } catch {
    setMode(server, mailbox, false);
    return false;
  }
}

async function ensureMode(server, mailbox) {
  const cached = state.modes.get(modeKey(server, mailbox));
  if (cached && Date.now() - cached.checkedAt < 10_000) return cached.active;
  return refreshMode(server, mailbox);
}

async function refreshModes() {
  if (state.checkingMode || !state.uuid) return;
  state.checkingMode = true;
  try {
    await refreshMode(state.server, state.uuid);
    for (const contact of state.contacts) {
      if (!contact.inbox) continue;
      await refreshMode(contact.server, contact.inbox);
    }
    updateConnection();
    if (allModesActive()) {
      state.renewalSnoozedUntil = 0;
      await flushPendingAcks();
    }
  } finally {
    state.checkingMode = false;
  }
}

function ackKey(server, mailbox, id) {
  return `${modeKey(server, mailbox)}|${id}`;
}

async function queueAck(server, mailbox, id) {
  const key = ackKey(server, mailbox, id);
  if (!(await ensureMode(server, mailbox))) {
    state.pendingAcks.set(key, { server, mailbox, id });
    return false;
  }
  try {
    await fetchJson(
      `/api/mailbox/${encodeURIComponent(mailbox)}/ack?server=${encodeURIComponent(server)}`,
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${mailbox}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      },
    );
    state.pendingAcks.delete(key);
    return true;
  } catch {
    state.pendingAcks.set(key, { server, mailbox, id });
    return false;
  }
}

async function flushPendingAcks() {
  for (const [key, item] of Array.from(state.pendingAcks.entries())) {
    if (!(await ensureMode(item.server, item.mailbox))) return;
    try {
      await fetchJson(
        `/api/mailbox/${encodeURIComponent(item.mailbox)}/ack?server=${encodeURIComponent(item.server)}`,
        {
          method: 'POST',
          headers: { Authorization: `Bearer ${item.mailbox}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: item.id }),
        },
      );
      state.pendingAcks.delete(key);
    } catch {
      return;
    }
  }
}

// --- Chat import and live sync ---------------------------------------------

function parseJson(value, fallback) {
  if (!value) return fallback;
  if (typeof value === 'object') return value;
  try { return JSON.parse(value); } catch { return fallback; }
}

function contactKey(contact) {
  return `${contact.server}|${contact.uuid}`;
}

function contactInbox(contact) {
  return contact.receive_on || contact.uuid;
}

function contactOutbox(contact) {
  return contact.send_to || contact.uuid;
}

function personContacts() {
  return state.contacts
    .filter((contact) => (contact.kind || 'PERSON') === 'PERSON')
    .sort((a, b) => lastMessageAt(b) - lastMessageAt(a));
}

function threadHistory(contact) {
  return state.history
    .filter((entry) => (contact.sender_id && entry.from === contact.sender_id) ||
      (contact.my_sender_id && entry.from === contact.my_sender_id))
    .sort((a, b) => Number(a.created_at || 0) - Number(b.created_at || 0));
}

function lastMessageAt(contact) {
  const thread = threadHistory(contact);
  return thread.length ? Number(thread[thread.length - 1].created_at || 0) : 0;
}

function lastPreview(contact) {
  const thread = threadHistory(contact);
  const entry = thread[thread.length - 1];
  if (!entry) return 'No messages';
  const mine = entry.from && entry.from === contact.my_sender_id;
  const text = entry.notice ? entry.message : `${mine ? 'You: ' : ''}${entry.message || 'Attachment'}`;
  return String(text).replace(/\s+/g, ' ').slice(0, 100);
}

const MAIL_GLYPHS = {
  sent: '<svg viewBox="0 0 16 16" aria-hidden="true"><g transform="rotate(-14 8 8)"><rect x="4" y="5" width="9" height="7" rx="1"/><path d="M4 5l4.5 3.5L13 5"/></g><path d="M.5 5.5h2.2M.5 8.5h1.4"/></svg>',
  delivered: '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="3" y="4.5" width="10" height="7" rx="1"/><path d="M3 4.5l5 3.8 5-3.8"/></svg>',
  read: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 6.2v5.6h10V6.2"/><path d="M3 6.2L8 1.5l5 4.7"/><path d="M3 6.2h10"/></svg>',
};

function mailState(entry, mine) {
  if (entry.acked) return 'read';
  if (mine) return entry.remoteDelivered ? 'delivered' : 'sent';
  return 'delivered';
}

function mailGlyph(entry, mine) {
  const state = mailState(entry, mine);
  const label = state === 'read' ? 'Read' : (state === 'delivered' ? 'Delivered' : 'Under way');
  const span = document.createElement('span');
  span.className = `mail-glyph ${state}`;
  span.title = label;
  span.setAttribute('aria-label', label);
  span.innerHTML = MAIL_GLYPHS[state];
  return span;
}

const CRYPTO_GLYPHS = {
  lock: '<svg viewBox="0 0 12 14" aria-hidden="true"><rect x="1.5" y="6" width="9" height="7" rx="1"/><path d="M3.5 6V4a2.5 2.5 0 0 1 5 0v2"/><path d="M6 9v1.2"/></svg>',
  lockFeather: '<svg viewBox="0 0 21 14" aria-hidden="true"><g><rect x="1.5" y="6" width="9" height="7" rx="1"/><path d="M3.5 6V4a2.5 2.5 0 0 1 5 0v2"/><path d="M6 9v1.2"/></g><g transform="translate(12 0)"><path d="M1.8 12.2c.2-3.1 2-5.6 5.4-7.4-.4 3-2.2 5.5-5.4 7.4Z"/><path d="M2.2 11.8 6 7"/></g></svg>',
  feather: '<svg viewBox="0 0 10 14" aria-hidden="true"><path d="M1.8 12.2c.2-3.1 2-5.6 5.4-7.4-.4 3-2.2 5.5-5.4 7.4Z"/><path d="M2.2 11.8 6 7"/></svg>',
  warning: '<svg viewBox="0 0 14 14" aria-hidden="true"><path d="M7 1.3 13 12H1Z"/><path d="M7 5v3.2"/><circle cx="7" cy="10.4" r=".8"/></svg>',
};

function cryptoGlyph(entry, mine = false) {
  const signed = entry.signatureValid === true || (mine && Boolean(entry.sig));
  if (!entry.encrypted && !signed) return null;
  const state = entry.encrypted
    ? (signed ? 'lockFeather' : 'lock')
    : 'feather';
  const label = entry.encrypted
    ? (signed ? 'Encrypted and signed' : 'Encrypted')
    : 'Signed';
  const span = document.createElement('span');
  span.className = `crypto-glyph ${state}${entry.acked ? ' read' : ''}`;
  span.title = label;
  span.setAttribute('aria-label', label);
  span.innerHTML = CRYPTO_GLYPHS[state];
  return span;
}

function appendLinkified(parent, text) {
  const value = String(text ?? '');
  const pattern = /(?:https?:\/\/|www\.)[^\s<>"']+/gi;
  let last = 0;
  let match;
  while ((match = pattern.exec(value)) !== null) {
    let raw = match[0];
    // Keep sentence punctuation outside the anchor, but leave a closing parenthesis in a
    // URL alone when it belongs to a Wikipedia-style path.
    let trailing = '';
    while (/[.,!?;:]$/.test(raw)) {
      trailing = raw.slice(-1) + trailing;
      raw = raw.slice(0, -1);
    }
    if (!raw) continue;
    if (match.index > last) {
      parent.appendChild(document.createTextNode(value.slice(last, match.index)));
    }
    const href = raw.toLowerCase().startsWith('www.') ? `https://${raw}` : raw;
    const link = document.createElement('a');
    link.className = 'chat-link';
    link.href = href;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.textContent = raw;
    parent.appendChild(link);
    if (trailing) parent.appendChild(document.createTextNode(trailing));
    last = match.index + match[0].length;
  }
  if (last < value.length) parent.appendChild(document.createTextNode(value.slice(last)));
}

function signatureWarning(entry, mine = false) {
  if (mine) return false;
  const required = Number(entry.created_at || 0) >= SIGNATURE_REQUIRED_FROM;
  return required && (entry.signatureValid === false || !entry.sig);
}

function warningGlyph() {
  const span = document.createElement('span');
  span.className = 'crypto-glyph warning';
  span.title = 'Signature missing or invalid';
  span.setAttribute('aria-label', 'Signature missing or invalid');
  span.innerHTML = CRYPTO_GLYPHS.warning;
  return span;
}

function replyPreviewOfWeb(entry) {
  if (entry.attachmentName) {
    return String(entry.attachmentMime || '').startsWith('image/')
      ? 'Photo'
      : `Attachment: ${entry.attachmentName}`;
  }
  if (entry.image_path) return 'Photo (on phone)';
  if (entry.file_path) return `Attachment: ${entry.file_name || 'file'}`;
  return String(entry.message || 'Message').replace(/\s+/g, ' ').slice(0, 160);
}

function replyNameOfWeb(entry, contact) {
  if (entry.from_name) return entry.from_name;
  if (entry.from && contact && entry.from === contact.my_sender_id) return state.ownName || 'You';
  return contact?.name || 'Reply';
}

function setReply(entry) {
  const contact = state.activeContact ? findContact(state.activeContact) : null;
  if (!contact || !entry || entry.notice) return;
  state.replyTarget = entry;
  $('#chatReplyName').textContent = replyNameOfWeb(entry, contact);
  $('#chatReplyPreview').textContent = replyPreviewOfWeb(entry);
  $('#chatReplyBar').classList.remove('d-none');
  $('#chatInput').focus();
}

function clearReply() {
  state.replyTarget = null;
  $('#chatReplyBar')?.classList.add('d-none');
  if ($('#chatReplyName')) $('#chatReplyName').textContent = '';
  if ($('#chatReplyPreview')) $('#chatReplyPreview').textContent = '';
}

function renderChats() {
  const list = $('#chatList');
  if (!list) return;
  const contacts = personContacts();
  $('#chatCount').textContent = String(contacts.length);
  const metric = $('#chatMetric');
  if (metric) metric.textContent = String(contacts.length);
  list.replaceChildren();
  list.className = 'chat-list';
  if (!state.imported) {
    list.className = 'chat-list empty-state';
    list.textContent = 'Pair with PagerAPP to load chats.';
    $('#chatListPanel').classList.remove('d-none');
    $('#chatThreadPanel').classList.add('d-none');
    return;
  }
  if (!contacts.length) {
    list.className = 'chat-list empty-state';
    list.textContent = 'No conversations in this backup yet.';
    return;
  }
  contacts.forEach((contact) => {
    const button = document.createElement('button');
    button.className = 'chat-contact';
    button.type = 'button';
    button.innerHTML = `<span class="chat-avatar">${escapeHtml((contact.name || '?').slice(0, 1).toUpperCase())}</span>
      <span class="chat-copy"><span class="chat-name">${escapeHtml(contact.name || 'Contact')}</span>
      <span class="chat-preview">${escapeHtml(lastPreview(contact))}</span></span>
      <span class="chat-time">${escapeHtml(timeLabel(lastMessageAt(contact)))}</span>`;
    button.title = `uuid: ${contact.uuid}${contact.send_to ? ` · outbox: ${contact.send_to}` : ''}`;
    button.addEventListener('click', () => openChat(contactKey(contact)));
    list.appendChild(button);
  });
  if (state.activeContact) {
    const contact = findContact(state.activeContact);
    if (contact) renderThread(contact);
  }
}

function findContact(key) {
  return state.contacts.find((contact) => contactKey(contact) === key) || null;
}

function openChat(key) {
  const contact = findContact(key);
  if (!contact) return;
  state.activeContact = key;
  clearReply();
  markThreadRead(contact);
  $('#chatListPanel').classList.add('d-none');
  $('#chatThreadPanel').classList.remove('d-none');
  $('#chatTitle').textContent = contact.name || 'Chat';
  $('#chatTitle').title = `uuid: ${contact.uuid}`;
  const uuidLine = $('#chatUuid');
  if (uuidLine) {
    uuidLine.textContent = contact.uuid;
    uuidLine.classList.add('d-none');
  }
  renderThread(contact);
  pollContact(contact);
}

function closeChat() {
  state.activeContact = null;
  clearReply();
  $('#chatThreadPanel').classList.add('d-none');
  $('#chatListPanel').classList.remove('d-none');
  renderChats();
}

function renderThread(contact) {
  const box = $('#chatMessages');
  box.replaceChildren();
  const thread = threadHistory(contact);
  let lastDay = '';
  thread.forEach((entry) => {
    const day = dayLabel(entry.created_at);
    if (day !== lastDay) {
      lastDay = day;
      const divider = document.createElement('div');
      divider.className = 'chat-day';
      divider.textContent = day;
      box.appendChild(divider);
    }
    const mine = Boolean(entry.from && contact.my_sender_id && entry.from === contact.my_sender_id);
    const bubble = document.createElement('div');
    bubble.className = `chat-bubble${mine ? ' mine' : ''}${entry.notice ? ' notice' : ''}`;
    if (entry.replyPreview || entry.replyTo) {
      const quote = document.createElement('div');
      quote.className = 'chat-quote';
      const quoteName = document.createElement('span');
      quoteName.className = 'chat-quote-name';
      quoteName.textContent = entry.replyFromName || 'Reply';
      const quoteText = document.createElement('span');
      quoteText.textContent = entry.replyPreview || `Message ${entry.replyTo || ''}`;
      quote.append(quoteName, quoteText);
      bubble.appendChild(quote);
    }
    let text = entry.message || '';
    if (entry.image_path) text = 'Photo (stored on the phone)';
    else if (entry.file_path) text = `Attachment: ${entry.file_name || 'file'} (stored on the phone)`;
    if (entry.attachmentUrl) {
      if (String(entry.attachmentMime || '').startsWith('image/')) {
        const image = document.createElement('img');
        image.className = 'chat-image';
        image.src = entry.attachmentUrl;
        image.alt = entry.attachmentName || 'Photo';
        image.addEventListener('click', () => window.open(entry.attachmentUrl, '_blank'));
        bubble.appendChild(image);
      } else {
        const file = document.createElement('a');
        file.className = 'chat-file';
        file.href = entry.attachmentUrl;
        file.download = entry.attachmentName || 'attachment';
        file.textContent = `📎 ${entry.attachmentName || 'attachment'}`;
        bubble.appendChild(file);
      }
    } else {
      appendLinkified(bubble, text || '(empty)');
    }
    const meta = document.createElement('span');
    meta.className = 'chat-meta';
    const icons = document.createElement('span');
    icons.className = 'chat-icons';
    if (signatureWarning(entry, mine)) icons.append(warningGlyph());
    const crypto = cryptoGlyph(entry, mine);
    if (crypto) icons.append(crypto);
    icons.append(mailGlyph(entry, mine));
    meta.append(document.createTextNode(`${timeLabel(entry.created_at)} `), icons);
    bubble.appendChild(meta);
    if (!entry.notice) {
      const reply = document.createElement('button');
      reply.type = 'button';
      reply.className = 'chat-reply-action';
      reply.textContent = '↩';
      reply.title = 'Reply';
      reply.addEventListener('click', (event) => {
        event.stopPropagation();
        setReply(entry);
      });
      bubble.appendChild(reply);
    }
    box.appendChild(bubble);
  });
  if (!thread.length) {
    box.innerHTML = '<div class="empty-state">This conversation is empty.</div>';
  }
  box.scrollTop = box.scrollHeight;
  updateConnection();
}

function addHistory(entry) {
  const existing = state.history.findIndex((item) => item.id === entry.id);
  if (existing >= 0) state.history[existing] = { ...state.history[existing], ...entry };
  else state.history.push(entry);
  const contact = state.contacts.find((item) =>
    (entry.from && entry.from === item.sender_id) ||
    (entry.from && entry.from === item.my_sender_id));
  if (contact && state.activeContact === contactKey(contact)) renderThread(contact);
  renderChats();
}

async function importArmored(armored) {
  if (!window.kbpgp) throw new Error('OpenPGP library did not load');
  return new Promise((resolve, reject) => {
    kbpgp.KeyManager.import_from_armored_pgp({ armored }, (error, keyManager) => {
      if (error) reject(error); else resolve(keyManager);
    });
  });
}

async function ensureOwnKey() {
  if (state.ownKm) return state.ownKm;
  if (!state.ownSecret) throw new Error('the backup has no private key');
  state.ownKm = await importArmored(state.ownSecret);
  return state.ownKm;
}

async function contactKeyManager(contact) {
  if (!contact.public_key) throw new Error('the contact has no public key');
  const cached = state.contactKms.get(contactKey(contact));
  if (cached) return cached;
  const km = await importArmored(contact.public_key);
  state.contactKms.set(contactKey(contact), km);
  return km;
}

async function verifyDetached(km, clear, armoredSig) {
  if (!armoredSig) return false;
  const [errA, decoded] = kbpgp.armor.decode(armoredSig);
  if (errA || !decoded) return false;
  const [errP, packets] = kbpgp.parser.parse(decoded.body);
  if (errP) return false;
  return new Promise((resolve) => {
    kbpgp.verify({ data: kbpgp.Buffer.from(clear, 'utf8'), packets, keyfetch: km }, (err) => {
      resolve(!err);
    });
  });
}

async function checkSignature(contact, clear, page, own = false) {
  const required = Number(page.created_at || 0) >= SIGNATURE_REQUIRED_FROM;
  if (!required) return null;
  if (!page.sig) return false;
  try {
    const km = own ? await ensureOwnKey() : await contactKeyManager(contact);
    return await verifyDetached(km, clear, page.sig);
  } catch (error) {
    return false;
  }
}

async function decryptArmored(armored) {
  if (!armored) return null;
  const own = await ensureOwnKey();
  return new Promise((resolve, reject) => {
    kbpgp.unbox({ keyfetch: own, armored }, (error, literals) => {
      if (error) reject(error);
      else if (!literals || !literals.length) reject(new Error('empty encrypted message'));
      else resolve(literals[0].toString());
    });
  });
}

async function encryptFor(contact, text) {
  if (!contact.public_key) return { message: text, encrypted: false };
  const recipient = await contactKeyManager(contact);
  // Deliberately encrypt-only, like the Android client: BouncyCastle's decrypt path expects
  // literal data first, so adding an OpenPGP signature packet would break Android reception.
  const options = { msg: text, encrypt_for: recipient };
  const armored = await new Promise((resolve, reject) => {
    kbpgp.box(options, (error, result) => {
      if (error) reject(error);
      else resolve(result);
    });
  });
  return { enc: armored, encrypted: true };
}

async function signWithOwn(text) {
  const own = await ensureOwnKey();
  const signingKey = own.find_signing_pgp_key();
  if (!signingKey) throw new Error('no signing key');
  return new Promise((resolve, reject) => {
    kbpgp.detachsign(
      { data: kbpgp.Buffer.from(text, 'utf8'), signing_key: signingKey },
      (error, signature) => {
        if (error) reject(error); else resolve(signature);
      },
    );
  });
}

async function encryptBytesFor(publicArmored, bytes) {
  const recipient = await importArmored(publicArmored);
  return new Promise((resolve, reject) => {
    kbpgp.box({ msg: new Uint8Array(bytes), encrypt_for: recipient }, (error, result) => {
      if (error) reject(error); else resolve(result);
    });
  });
}

function literalBytes(literal) {
  if (literal?.data && typeof literal.data.length === 'number') {
    return new Uint8Array(literal.data);
  }
  const binary = literal.toString('binary');
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i) & 0xff;
  return out;
}

async function decryptBytesArmor(armored) {
  const own = await ensureOwnKey();
  return new Promise((resolve, reject) => {
    kbpgp.unbox({ keyfetch: own, armored }, (error, literals) => {
      if (error) reject(error);
      else if (!literals || !literals.length) reject(new Error('empty encrypted attachment'));
      else resolve(literalBytes(literals[0]));
    });
  });
}

async function sha256Hex(bytes) {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

async function shareUpload(armor) {
  const response = await fetch(
    `/api/share/upload?host=${encodeURIComponent(state.photoShare)}&days=3`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: armor,
    },
  );
  if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error || `share HTTP ${response.status}`);
  return response.json();
}

async function shareDownload(url) {
  const response = await fetch(`/api/share/download?url=${encodeURIComponent(url)}`, { cache: 'no-store' });
  if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error || `share HTTP ${response.status}`);
  return new Uint8Array(await response.arrayBuffer());
}

function makeAttachmentLink(kind, { url, sha256, days = 3, deleteUrl = null, name = '', mime = '' }) {
  const params = new URLSearchParams();
  params.set('u', url);
  params.set('h', sha256);
  params.set('d', String(days));
  if (deleteUrl) params.set('x', deleteUrl);
  if (kind === 'file') {
    params.set('n', name || 'attachment');
    params.set('t', mime || 'application/octet-stream');
  }
  return `pagerapp://${kind}?${params.toString()}`;
}

function parseAttachmentLink(text) {
  const raw = String(text || '').trim();
  const kind = raw.startsWith('pagerapp://photo?') ? 'photo'
    : raw.startsWith('pagerapp://file?') ? 'file' : null;
  if (!kind) return null;
  const params = new URLSearchParams(raw.slice(`pagerapp://${kind}?`.length));
  const url = params.get('u');
  const sha = params.get('h')?.toLowerCase();
  if (!url || !/^https:\/\//i.test(url) || !sha || !/^[0-9a-f]{64}$/.test(sha)) return null;
  const days = Math.max(1, Math.min(365, Number(params.get('d')) || 3));
  const deleteUrl = params.get('x') || null;
  if (kind === 'photo') return { kind, url, sha256: sha, days, deleteUrl, name: 'Photo', mime: 'image/jpeg' };
  return {
    kind,
    url,
    sha256: sha,
    days,
    deleteUrl,
    name: params.get('n') || 'attachment',
    mime: params.get('t') || 'application/octet-stream',
  };
}

async function hydrateAttachment(text) {
  const link = parseAttachmentLink(text);
  if (!link) return null;
  const armor = await shareDownload(link.url);
  const armorText = new TextDecoder().decode(armor);
  if (await sha256Hex(armor) !== link.sha256) throw new Error('attachment checksum failed');
  const bytes = await decryptBytesArmor(armorText);
  const blob = new Blob([bytes], { type: link.mime });
  return {
    url: URL.createObjectURL(blob),
    name: link.name,
    mime: link.mime,
    photo: link.kind === 'photo' || link.mime.startsWith('image/'),
  };
}

async function sendWebAttachment(file) {
  const contact = state.activeContact ? findContact(state.activeContact) : null;
  if (!contact || !file || state.attachmentBusy) return;
  if (!contact.public_key) {
    toast('Send a text message first so their key is known; attachments are encrypted.');
    return;
  }
  if (file.size === 0) return;
  state.attachmentBusy = true;
  toast('Encrypting and uploading attachment…');
  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const armored = await encryptBytesFor(contact.public_key, bytes);
    const auth = new TextEncoder().encode(armored);
    const sha = await sha256Hex(auth);
    const upload = await shareUpload(armored);
    const photo = String(file.type || '').startsWith('image/');
    const link = makeAttachmentLink(photo ? 'photo' : 'file', {
      url: upload.url,
      sha256: sha,
      days: upload.days || 3,
      deleteUrl: upload.deleteUrl,
      name: file.name || 'attachment',
      mime: file.type || 'application/octet-stream',
    });
    await sendChatMessage(link, {
      url: URL.createObjectURL(file),
      name: file.name || 'attachment',
      mime: file.type || 'application/octet-stream',
      photo,
    });
  } catch (error) {
    toast(`Attachment failed: ${error.message}`);
  } finally {
    state.attachmentBusy = false;
  }
}

async function postNotify(server, outbox, payload) {
  return fetchJson(
    `/api/mailbox/${encodeURIComponent(outbox)}/notify?server=${encodeURIComponent(server)}`,
    {
      method: 'POST',
      headers: { Authorization: `Bearer ${outbox}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
  );
}

async function sendChatMessage(text, localAttachment = null) {
  const contact = state.activeContact ? findContact(state.activeContact) : null;
  if (!contact) return;
  const message = text.trim();
  if (!message) return;
  const replyingTo = state.replyTarget;
  try {
    const encrypted = await encryptFor(contact, message);
    const mid = crypto.randomUUID();
    const payload = {
      status: 10,
      from: contact.my_sender_id,
      from_name: state.ownName,
      mid,
      source: contact.my_sender_id,
      destination: contactOutbox(contact),
    };
    if (replyingTo) {
      payload.reply_to = replyingTo.mid || replyingTo.id;
      payload.reply_preview = replyPreviewOfWeb(replyingTo);
      payload.reply_from = replyingTo.from;
      payload.reply_from_name = replyNameOfWeb(replyingTo, contact);
    }
    if (state.ownPublic) payload.pub = state.ownPublic;
    try { payload.sig = await signWithOwn(message); }
    catch (error) { console.warn('message signing failed', error); }
    if (encrypted.enc) payload.enc = encrypted.enc;
    else payload.message = encrypted.message;
    await postNotify(contact.server, contactOutbox(contact), payload);

    // The peer copy is encrypted to the peer's key. Keep a second, self-addressed copy in the
    // phone's private mailbox encrypted to *our* key, so the phone can store it as our own
    // outgoing message instead of being unable to decrypt the peer copy.
    try {
      const selfPayload = {
        status: 10,
        from: contact.my_sender_id,
        from_name: state.ownName,
        mid,
        source: contact.my_sender_id,
        destination: state.uuid,
        self_copy: true,
      };
      if (replyingTo) {
        selfPayload.reply_to = replyingTo.mid || replyingTo.id;
        selfPayload.reply_preview = replyPreviewOfWeb(replyingTo);
        selfPayload.reply_from = replyingTo.from;
        selfPayload.reply_from_name = replyNameOfWeb(replyingTo, contact);
      }
      try { selfPayload.sig = await signWithOwn(message); }
      catch (error) { console.warn('self-copy signing failed', error); }
      if (state.ownPublic) {
        try {
          const own = await encryptFor({
            public_key: state.ownPublic,
            server: state.server,
            uuid: state.uuid,
          }, message);
          if (own.enc) selfPayload.enc = own.enc;
        } catch (error) {
          console.warn('own-key encryption failed; using a plaintext self copy', error);
        }
      }
      if (!selfPayload.enc) selfPayload.message = message;
      await postNotify(state.server, state.uuid, selfPayload);
    } catch (error) {
      console.warn('own-copy sync failed', error);
      toast('Message sent, but the phone copy could not be queued');
    }

    state.seen.add(mid);
    addHistory({
      id: `local-${mid}`,
      status: 10,
      message: localAttachment
        ? (localAttachment.photo ? 'Photo' : `Attachment: ${localAttachment.name}`)
        : message,
      created_at: Date.now() / 1000,
      from: contact.my_sender_id,
      from_name: state.ownName,
      mid,
      mailbox: contactOutbox(contact),
      server: contact.server,
      encrypted: Boolean(encrypted.encrypted),
      delivered: true,
      acked: false,
      remoteDelivered: false,
      attachmentUrl: localAttachment?.url,
      attachmentName: localAttachment?.name,
      attachmentMime: localAttachment?.mime,
      replyTo: replyingTo?.mid || replyingTo?.id,
      replyPreview: replyingTo ? replyPreviewOfWeb(replyingTo) : null,
      replyFrom: replyingTo?.from,
      replyFromName: replyingTo ? replyNameOfWeb(replyingTo, contact) : null,
      sig: payload.sig,
      signatureValid: payload.sig ? true : null,
    });
    clearReply();
    toast(localAttachment ? 'Attachment sent' : 'Message sent');
  } catch (error) {
    toast(`Send failed: ${error.message}`);
  }
}

async function sendReadReceipt(contact, mid) {
  if (!mid || !contact.my_sender_id) return;
  try {
    await postNotify(contact.server, contactOutbox(contact), {
      status: 10,
      from: contact.my_sender_id,
      from_name: state.ownName,
      read: mid,
    });
  } catch (error) {
    console.warn('read receipt failed', error);
  }
}

function markThreadRead(contact) {
  let changed = false;
  for (const entry of state.history) {
    if (entry.from !== contact.sender_id || entry.acked || entry.notice) continue;
    entry.acked = true;
    changed = true;
    if (entry.mid) sendReadReceipt(contact, entry.mid);
  }
  if (changed) renderThread(contact);
}

async function sendDeliveryReceipt(contact, mid) {
  if (!mid || !contact.my_sender_id) return;
  try {
    await postNotify(contact.server, contactOutbox(contact), {
      status: 10,
      from: contact.my_sender_id,
      from_name: state.ownName,
      delivered: mid,
    });
  } catch (error) {
    console.warn('delivery receipt failed', error);
  }
}

async function attachmentForMessage(clear) {
  if (!parseAttachmentLink(clear)) return null;
  try {
    return await hydrateAttachment(clear);
  } catch (error) {
    console.warn('attachment download failed', error);
    const link = parseAttachmentLink(clear);
    return { error: true, photo: link?.kind === 'photo', name: link?.name || 'attachment', mime: link?.mime };
  }
}

async function pollContact(contact) {
  if (!contact) return;
  try {
    const data = await fetchJson(
      `/api/mailbox/${encodeURIComponent(contactInbox(contact))}/messages?server=${encodeURIComponent(contact.server)}&order=newest`,
      { headers: { Authorization: `Bearer ${contactInbox(contact)}` } },
    );
    const messages = Array.isArray(data.messages) ? data.messages : [];
    for (const page of messages) {
      if (!page || !page.id) continue;
      // Newest-first: everything after the first known id is older and already handled.
      if (state.seen.has(page.id)) break;
      if (pageStatus(page) !== 10) continue;
      if (page.from && contact.my_sender_id && page.from === contact.my_sender_id) continue;
      state.seen.add(page.id);
      if (page.delivered) {
        const sent = state.history.find((entry) =>
          entry.mid === page.delivered && entry.from === contact.my_sender_id);
        if (sent) sent.remoteDelivered = true;
        await queueAck(contact.server, contactInbox(contact), page.id);
        continue;
      }
      if (page.read) {
        const sent = state.history.find((entry) => entry.mid === page.read && entry.from === contact.my_sender_id);
        if (sent) sent.acked = true;
        await queueAck(contact.server, contactInbox(contact), page.id);
        continue;
      }
      let clear = page.message || '';
      let decrypted = false;
      if (page.enc) {
        try {
          clear = await decryptArmored(page.enc);
          decrypted = true;
        } catch {
          clear = '🔒 encrypted — no usable key';
        }
      }
      const attachment = await attachmentForMessage(clear);
      const signatureValid = decrypted
        ? await checkSignature(contact, clear, page)
        : (page.sig ? false : null);
      addHistory({
        id: page.id,
        status: 10,
        message: attachment
          ? (attachment.error
            ? (attachment.photo ? 'Photo (unavailable)' : `Attachment: ${attachment.name} (unavailable)`)
            : (attachment.photo ? 'Photo' : `Attachment: ${attachment.name}`))
          : clear,
        created_at: Number(page.created_at) || Date.now() / 1000,
        from: page.from,
        from_name: page.from_name,
        mid: page.mid,
        mailbox: contactInbox(contact),
        server: contact.server,
        encrypted: decrypted,
        received_at: Date.now() / 1000,
        delivered: true,
        acked: false,
        remoteDelivered: false,
        attachmentUrl: attachment?.url,
        attachmentName: attachment?.name,
        attachmentMime: attachment?.mime,
        replyTo: page.reply_to,
        replyPreview: page.reply_preview,
        replyFrom: page.reply_from,
        replyFromName: page.reply_from_name,
        sig: page.sig,
        signatureValid,
      });
      if (page.mid) sendDeliveryReceipt(contact, page.mid);
      if (state.activeContact === contactKey(contact)) markThreadRead(contact);
      // The browser may ACK only while the phone-owned webclient lease is active; otherwise keep
      // the id pending and flush it after the renewal QR.
      await queueAck(contact.server, contactInbox(contact), page.id);
    }
    if (state.activeContact === contactKey(contact)) renderThread(contact);
    renderChats();
  } catch (error) {
    // A mailbox can be temporarily unavailable; the next pass retries without losing state.
    console.warn('chat poll failed', error);
  }
}

async function pollOwnCopies() {
  if (!state.imported || !state.uuid) return;
  try {
    const data = await fetchJson(
      `/api/mailbox/${encodeURIComponent(state.uuid)}/messages?server=${encodeURIComponent(state.server)}&order=newest`,
      { headers: { Authorization: `Bearer ${state.uuid}` } },
    );
    const messages = Array.isArray(data.messages) ? data.messages : [];
    for (const page of messages) {
      if (!page || !page.id) continue;
      // Newest-first; stop at the first id already handled by this tab.
      if (state.syncSeen.has(page.id)) break;
      state.syncSeen.add(page.id);
      if (pageStatus(page) !== 10 || !page.self_copy) continue;
      const contact = state.contacts.find((item) => item.my_sender_id === page.from);
      if (!contact) continue;
      // A local web send already added this message; only the server copy of a phone send is new.
      const duplicate = page.mid &&
        state.history.some((entry) => entry.mid === page.mid && entry.from === page.from);
      if (!duplicate) {
        let clear = page.message || '';
        let decrypted = false;
        if (page.enc) {
          try { clear = await decryptArmored(page.enc); decrypted = true; }
          catch { clear = '🔒 encrypted — no usable key'; }
        }
        const attachment = await attachmentForMessage(clear);
        const signatureValid = decrypted
          ? await checkSignature(contact, clear, page, true)
          : (page.sig ? false : null);
        addHistory({
          id: page.id,
          status: 10,
          message: attachment
            ? (attachment.error
              ? (attachment.photo ? 'Photo (unavailable)' : `Attachment: ${attachment.name} (unavailable)`)
              : (attachment.photo ? 'Photo' : `Attachment: ${attachment.name}`))
            : clear,
          created_at: Number(page.created_at) || Date.now() / 1000,
          from: page.from,
          from_name: page.from_name,
          mid: page.mid,
          mailbox: state.uuid,
          server: state.server,
          encrypted: decrypted,
          received_at: Date.now() / 1000,
          delivered: false,
          acked: false,
          remoteDelivered: false,
          sig: page.sig,
          signatureValid,
          attachmentUrl: attachment?.url,
          attachmentName: attachment?.name,
          attachmentMime: attachment?.mime,
          replyTo: page.reply_to,
          replyPreview: page.reply_preview,
          replyFrom: page.reply_from,
          replyFromName: page.reply_from_name,
        });
      }
      await queueAck(state.server, state.uuid, page.id);
    }
  } catch (error) {
    console.warn('own-copy sync failed', error);
  }
}

function startSync() {
  stopSync();
  if (!state.imported) return;
  state.syncTimer = setInterval(() => {
    if (state.activeContact) {
      const contact = findContact(state.activeContact);
      if (contact) pollContact(contact);
    }
    pollOwnCopies();
  }, 4000);
  state.modeTimer = setInterval(() => {
    if (state.imported) refreshModes();
  }, 30_000);
}

function stopSync() {
  if (state.syncTimer) clearInterval(state.syncTimer);
  if (state.modeTimer) clearInterval(state.modeTimer);
  state.syncTimer = null;
  state.modeTimer = null;
}

// --- Browser handoff / renewal ---------------------------------------------

function renewalDue() {
  return Boolean(state.transfer) && state.transfer.expires <= Date.now();
}

function renderTransfer() {
  const transfer = state.transfer;
  const info = $('#sessionInfo');
  const chip = $('#sessionChip');
  const renewing = state.pairPurpose === 'renew';
  $('#renewSession').disabled = !state.imported;
  $('#deleteSession').disabled = !transfer;
  if (!transfer) {
    info.classList.add('d-none');
    chip.textContent = 'none';
    chip.className = 'chip';
    $('#createSession').classList.remove('d-none');
    return;
  }
  info.classList.remove('d-none');
  chip.textContent = renewing ? 'renew' : 'active';
  chip.className = `chip ${renewing ? 'bad' : 'ok'}`;
  $('#sessionExpiry').textContent = `expires ${new Date(transfer.expires).toLocaleString()}`;
  $('#sessionHint').textContent = renewing
    ? 'Scan with PagerAPP to renew the webclient lease; no backup is uploaded.'
    : 'Scan with PagerAPP to hand data to this tab. Private keys stay here.';
  const params = new URLSearchParams({
    session: transfer.id,
    token: transfer.token,
    expires: String(Math.floor(transfer.expires / 1000)),
  });
  if (renewing) params.set('action', 'renew');
  const url = `${location.origin}/pair?${params.toString()}`;
  const qr = qrcode(0, 'L');
  qr.addData(url);
  qr.make();
  const svg = qr.createSvgTag({ cellSize: 3, margin: 2 });
  $('#qrCode').innerHTML = svg;
  const pairQr = $('#pairQrCode');
  if (pairQr) pairQr.innerHTML = svg;
  const pairExpiry = $('#pairExpiry');
  if (pairExpiry) pairExpiry.textContent = `expires ${new Date(transfer.expires).toLocaleString()}`;
  $('#clearCacheWrap').classList.toggle('d-none', !renewing);
  $('#pairFootnote').textContent = renewing
    ? 'Renewal uses the same QR protocol; the phone only refreshes the lease.'
    : 'The code expires after 7 days and starts a full handoff.';
}

function showPairing(mode = 'handoff') {
  if (mode !== 'renew' && state.uuid) return;
  state.pairPurpose = mode;
  const title = $('#pairTitle');
  const intro = $('#pairModal p');
  if (mode === 'renew') {
    title.textContent = 'Renew this browser lease';
    intro.textContent = 'The webclient lease has lapsed while this tab still has the key and history. Scan the code with PagerAPP to renew the lease, or tick “Clear cache” to force a full transfer.';
  } else {
    title.textContent = 'Link this browser to your phone';
    intro.textContent = 'Open PagerAPP on your phone and scan this code. The app supplies the full backup; the long-term key stays in this tab memory.';
  }
  $('#clearCache').checked = false;
  renderTransfer();
  if (!pairModal) {
    pairModal = new bootstrap.Modal($('#pairModal'), { backdrop: true, keyboard: true });
  }
  pairModal.show();
}

function randomTransferPassphrase() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function generateTransferKey() {
  return new Promise((resolve, reject) => {
    if (!window.kbpgp) return reject(new Error('OpenPGP library did not load'));
    const flags = kbpgp.const.openpgp;
    kbpgp.KeyManager.generate(
      {
        userid: 'PagerAPP web transfer <web@pagerapp>',
        primary: {
          nbits: 1024,
          flags: flags.certify_keys | flags.sign_data | flags.encrypt_comm |
            flags.encrypt_storage,
          expire_in: 0,
        },
        subkeys: [],
      },
      (err, km) => {
        if (err) return reject(err);
        km.sign({}, (signErr) => {
          if (signErr) return reject(signErr);
          const passphrase = randomTransferPassphrase();
          km.export_pgp_private({ passphrase }, (privateErr, privateArmor) => {
            if (privateErr) return reject(privateErr);
            km.export_pgp_public({}, (publicErr, publicArmor) => {
              if (publicErr) return reject(publicErr);
              resolve({ km, privateArmor, passphrase, publicArmor });
            });
          });
        });
      },
    );
  });
}

function stopTransferPoll() {
  if (transferPoll) clearInterval(transferPoll);
  transferPoll = null;
}

function stopRenewalPoll() {
  if (state.renewalTimer) clearInterval(state.renewalTimer);
  state.renewalTimer = null;
}

function startTransferPoll() {
  stopTransferPoll();
  transferPoll = setInterval(pollTransfer, 2000);
}

function startRenewalPoll() {
  stopRenewalPoll();
  state.renewalTimer = setInterval(async () => {
    if (state.pairPurpose !== 'renew' || !state.uuid) return;
    // The phone can activate the private mailbox before the page-address/contact leases are
    // ready. Do not hide the renewal QR until *all* leases are active, otherwise the modal
    // closes and immediately reopens in a loop.
    const active = await refreshMode(state.server, state.uuid);
    if (!active) return;
    await refreshModes();
    if (!allModesActive()) return;
    stopRenewalPoll();
    pairModal?.hide();
    toast('Browser lease renewed');
    updateConnection();
  }, 2000);
}

async function pollTransfer() {
  if (!state.transfer || !state.transferKey || state.pairPurpose !== 'handoff') return;
  try {
    const statusResponse = await fetch(
      `/api/session/${encodeURIComponent(state.transfer.id)}/status`,
      { headers: { Authorization: `Bearer ${state.transfer.token}` }, cache: 'no-store' },
    );
    if (statusResponse.status === 404) {
      // The session was lost (container restart/cleanup or an old QR was scanned). Retrying this
      // id every two seconds only creates a toast loop; stop and let the user create a new code.
      stopTransferPoll();
      state.transferFailures = 0;
      if (!state.transferErrorShown) {
        state.transferErrorShown = true;
        const expiry = document.querySelector('#pairExpiry');
        if (expiry) expiry.textContent = 'session expired — create a new code';
        toast('This pairing session expired. Tap “New code” and scan again.');
      }
      return;
    }
    if (!statusResponse.ok) {
      state.transferFailures += 1;
      if (state.transferFailures >= 3 && !state.transferErrorShown) {
        state.transferErrorShown = true;
        toast(`Pairing relay error: HTTP ${statusResponse.status}`);
      }
      return;
    }
    state.transferFailures = 0;
    state.transferErrorShown = false;
    const status = await statusResponse.json();
    if (!status.uploaded) return;
    stopTransferPoll();
    const response = await fetch(
      `/api/session/${encodeURIComponent(state.transfer.id)}/download`,
      { headers: { Authorization: `Bearer ${state.transfer.token}` }, cache: 'no-store' },
    );
    if (!response.ok) throw new Error(`download HTTP ${response.status}`);
    const armored = await response.text();
    const plain = await new Promise((resolve, reject) => {
      kbpgp.unbox({ keyfetch: state.transferKey.km, armored }, (err, literals) => {
        if (err) return reject(err);
        if (!literals || !literals.length) return reject(new Error('empty transfer'));
        resolve(literals[0].toString());
      });
    });
    applyBackupText(plain);
    pairModal?.hide();
    toast('Phone backup received and imported');
    // The phone enables the leases after uploading the backup. Do not immediately replace the
    // successfully scanned QR with a renewal dialog while that activation is still in flight.
    await refreshModes();
  } catch (error) {
    toast(`Transfer failed: ${error.message}`);
  }
}

async function createSession() {
  try {
    state.transferErrorShown = false;
    state.transferFailures = 0;
    document.querySelector('#pairTitle').textContent = 'Generating transfer key…';
    const transferKey = await generateTransferKey();
    const transfer = await fetchJson('/api/session', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ publicKey: transferKey.publicArmor }),
    });
    state.transferKey = transferKey;
    transfer.publicKey = transferKey.publicArmor;
    state.transfer = transfer;
    state.pairPurpose = 'handoff';
    renderTransfer();
    showPairing('handoff');
    startTransferPoll();
    toast('Pairing code ready');
  } catch (error) {
    toast(`Could not create session: ${error.message}`);
  }
}

async function createRenewalSession() {
  try {
    if (!state.transfer || renewalDue()) {
      state.transfer = await fetchJson('/api/session', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
    }
    state.transferKey = null;
    state.pairPurpose = 'renew';
    renderTransfer();
    showPairing('renew');
    startRenewalPoll();
    toast('Renewal code ready');
  } catch (error) {
    toast(`Could not create renewal code: ${error.message}`);
  }
}

function allModesActive() {
  if (!modeActive(state.server, state.uuid)) return false;
  return state.contacts.every((contact) => modeActive(contact.server, contactInbox(contact)));
}


function clearMemory() {
  stopTransferPoll();
  stopRenewalPoll();
  stopSync();
  state.server = '';
  state.uuid = '';
  state.pages = [];
  state.imported = false;
  state.contacts = [];
  state.history = [];
  state.config = {};
  state.pageMailboxes = [];
  state.ownName = '';
  state.ownPublic = '';
  state.ownSecret = '';
  state.ownKm = null;
  state.contactKms.clear();
  state.seen.clear();
  state.syncSeen.clear();
  state.modes.clear();
  state.pendingAcks.clear();
  state.activeContact = null;
  state.replyTarget = null;
  state.renewalSnoozedUntil = 0;
  state.transferErrorShown = false;
  state.transferFailures = 0;
  state.transferKey = null;
  state.transfer = null;
  state.pairPurpose = 'handoff';
  renderChats();
  renderTransfer();
  updateConnection();
  showView('overview');
}

async function startFresh() {
  const old = state.transfer;
  if (old) {
    await fetch(`/api/session/${encodeURIComponent(old.id)}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${old.token}` },
    }).catch(() => null);
  }
  clearMemory();
  await createSession();
}

async function discardSession() {
  if (!state.transfer) return;
  await fetch(`/api/session/${encodeURIComponent(state.transfer.id)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${state.transfer.token}` },
  }).catch(() => null);
  state.transfer = null;
  state.transferKey = null;
  state.pairPurpose = 'handoff';
  stopTransferPoll();
  stopRenewalPoll();
  renderTransfer();
  toast('Session discarded');
}

async function checkHealth() {
  try {
    const response = await fetch('/api/health', { cache: 'no-store' });
    const health = await response.json();
    $('#healthDot').className = 'status-dot online';
    $('#healthText').textContent = `relay online · ${health.sessions} session(s)`;
  } catch {
    $('#healthDot').className = 'status-dot offline';
    $('#healthText').textContent = 'relay offline';
  }
}

function applyBackupText(text) {
  const root = JSON.parse(text);
  if (root.format !== 'pagerapp-backup' || Number(root.version) !== 1) {
    throw new Error('not a PagerAPP backup');
  }
  const data = root.data || {};
  const server = String(data.server_url || '').trim().replace(/\/+$/, '');
  const uuid = String(data.pager_uuid || '').trim().toLowerCase();
  if (!/^https?:\/\//.test(server) || !/^[0-9a-f-]{36}$/.test(uuid)) {
    throw new Error('backup has no valid server/mailbox');
  }
  state.server = server;
  state.uuid = uuid;
  state.imported = true;
  state.contacts = parseJson(data.contacts, []);
  state.history = parseJson(data.history, []).map((entry) => ({
    ...entry,
    replyTo: entry.replyTo ?? entry.reply_to,
    replyPreview: entry.replyPreview ?? entry.reply_preview,
    replyFrom: entry.replyFrom ?? entry.reply_from,
    replyFromName: entry.replyFromName ?? entry.reply_from_name,
    sig: entry.sig,
    signatureValid: entry.signatureValid ?? entry.signature_valid ?? null,
    remoteDelivered: entry.remoteDelivered ?? entry.remote_delivered ?? false,
  }));
  state.config = parseJson(data.config, {});
  state.ownName = String(data.display_name || '');
  state.ownPublic = String(data.pgp_public || '');
  state.ownSecret = String(data.pgp_secret || '');
  state.pageMailboxes = parseJson(data.page_mailboxes, []);
  state.photoShare = String(data.photo_share_url || 'https://share.h4ck.io').trim().replace(/\/+$/, '');
  state.ownKm = null;
  state.contactKms.clear();
  state.seen = new Set(state.history.map((entry) => entry.id).filter(Boolean));
  state.syncSeen = new Set(state.history.map((entry) => entry.id).filter(Boolean));
  state.pages = [];
  state.activeContact = null;
  state.renewalSnoozedUntil = Date.now() + 30_000;
  loadOwnKey().catch((error) => toast(`Chat key: ${error.message}`));
  if (pairModal) pairModal.hide();
  updateConnection();
  renderChats();
  startSync();
  showView('chats');
  $('#connectNote').textContent = 'Imported from the phone. Key, contacts and history exist only in this tab.';
  return { server, uuid };
}

async function loadOwnKey() {
  if (!state.ownSecret) return null;
  state.ownKm = await importArmored(state.ownSecret);
  return state.ownKm;
}

function importBackupFile(file) {
  const reader = new FileReader();
  reader.onload = () => {
    try {
      applyBackupText(String(reader.result || ''));
      toast('Backup imported locally');
      refreshModes();
    } catch (error) {
      toast(`Backup import failed: ${error.message}`);
    }
  };
  reader.onerror = () => toast('Could not read the selected backup');
  reader.readAsText(file);
}

function bind() {
  $('#themeToggle').addEventListener('click', () => {
    const current = document.documentElement.getAttribute('data-bs-theme');
    setTheme(current === 'dark' ? 'light' : 'dark');
  });
  $$('[data-theme-choice]').forEach((button) => button.addEventListener('click', () => setTheme(button.dataset.themeChoice)));
  $$('#mainNav .nav-link').forEach((button) => button.addEventListener('click', () => showView(button.dataset.view)));
  $$('.brand').forEach((link) => link.addEventListener('click', (event) => { event.preventDefault(); showView('overview'); }));
  $('#createSession').addEventListener('click', createSession);
  $('#pairAction').addEventListener('click', createSession);
  $('#pairAgain').addEventListener('click', async () => {
    if (state.pairPurpose === 'renew') {
      if ($('#clearCache').checked) await startFresh();
      else await createRenewalSession();
    } else {
      await createSession();
    }
  });
  $$('[data-import-backup]').forEach((button) => button.addEventListener('click', () => $('#backupFile').click()));
  $('#backupFile').addEventListener('change', (event) => {
    const file = event.target.files && event.target.files[0];
    if (file) importBackupFile(file);
    event.target.value = '';
  });
  $('#renewSession').addEventListener('click', () => {
    if (!state.imported) return;
    createRenewalSession();
  });
  $('#deleteSession').addEventListener('click', discardSession);
  $('#chatBack').addEventListener('click', closeChat);
  $('#chatReplyCancel').addEventListener('click', clearReply);
  $('#chatRenew').addEventListener('click', () => {
    if (state.imported) createRenewalSession();
  });
  $('#chatTitle').addEventListener('click', () => {
    $('#chatUuid')?.classList.toggle('d-none');
  });
  $('#chatAttach').addEventListener('click', () => {
    if (!state.activeContact) {
      toast('Open a chat before attaching a file');
      return;
    }
    $('#chatFile').click();
  });
  $('#chatFile').addEventListener('change', async (event) => {
    const file = event.target.files && event.target.files[0];
    event.target.value = '';
    if (file) await sendWebAttachment(file);
  });
  $('#chatForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const input = $('#chatInput');
    const value = input.value;
    input.value = '';
    await sendChatMessage(value);
  });
  $('#clearLocal').addEventListener('click', () => {
    clearMemory();
    toast('This tab was cleared');
  });
  $('#clearCache').addEventListener('change', async () => {
    if (state.pairPurpose !== 'renew') {
      $('#clearCache').checked = false;
      return;
    }
    if ($('#clearCache').checked) {
      // The checkbox must change the code the user is about to scan. Merely leaving it ticked
      // while the renewal QR is on screen would renew the lease and never transfer the backup.
      toast('Clearing this tab and preparing a full transfer…');
      await startFresh();
    }
  });
  $('#pairModal').addEventListener('hidden.bs.modal', () => {
    if (state.pairPurpose === 'renew') {
      state.renewalSnoozedUntil = Date.now() + 30_000;
    }
  });
}

window.addEventListener('error', (event) => {
  fetch('/api/client-error', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: event.message, source: event.filename, line: event.lineno }),
  }).catch(() => {});
});
window.addEventListener('unhandledrejection', (event) => {
  fetch('/api/client-error', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: String(event.reason || 'unhandled rejection') }),
  }).catch(() => {});
});

initTheme();
bind();
updateConnection();
renderChats();
renderTransfer();
// A browser session is deliberately memory-only: a reload is a fresh full handoff.
createSession();
checkHealth();
setInterval(checkHealth, 30000);
