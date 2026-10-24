'use strict';

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

const SESSION_DAYS = 30;
const RENEW_BEFORE_MS = 3 * 24 * 60 * 60 * 1000;

const state = {
  server: sessionStorage.getItem('pagerapp.server') || 'https://pager.ofloo.io',
  uuid: sessionStorage.getItem('pagerapp.uuid') || '',
  pages: [],
  transfer: null,
};

const STATUS_NAMES = {
  0: 'Success', 1: 'Warning', 2: 'Failed', 3: 'Critical',
  4: 'In Progress', 5: 'Queued', 6: 'Acknowledged', 7: 'Debug',
};

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function toast(message) {
  $('#toastBody').textContent = message;
  bootstrap.Toast.getOrCreateInstance($('#toast')).show();
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

function statusTone(status) {
  if (status === 2 || status === 3) return 'critical';
  if (status === 1) return 'warning';
  if (status === 0) return 'ok';
  return '';
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
  if (name === 'pages') loadPages();
}

function updateConnection() {
  $('#mailboxChip').textContent = state.uuid ? 'paired' : 'waiting for app';
  $('#mailboxChip').className = `chip ${state.uuid ? 'ok' : ''}`;
  $('#connectNote').textContent = state.uuid
    ? 'Paired through PagerAPP. This tab only.'
    : 'Not paired. Open PagerAPP and scan the code.';
  if (state.uuid) loadPages();
}

function pageRow(page) {
  const status = pageStatus(page);
  const row = document.createElement('div');
  row.className = 'page-row';
  const dot = document.createElement('span');
  dot.className = `page-dot ${statusTone(status)}`;
  const main = document.createElement('div');
  main.className = 'page-main';
  const title = document.createElement('div');
  title.className = 'page-title';
  title.textContent = page.JOB_ID || page.PROJECT || 'page';
  const message = document.createElement('div');
  message.className = 'page-msg';
  message.textContent = page.message || '(no message)';
  main.append(title, message);
  const meta = document.createElement('div');
  meta.className = 'page-meta';
  meta.textContent = `${status} ${STATUS_NAMES[status] || 'Status'} · ${timeLabel(page.created_at)}`;
  const ack = document.createElement('button');
  ack.className = 'btn btn-sm btn-outline-secondary';
  ack.textContent = 'ACK';
  ack.addEventListener('click', () => ackPage(page.id));
  row.append(dot, main, meta, ack);
  return row;
}

function renderPages() {
  const pages = state.pages.filter((page) => pageStatus(page) !== 10);
  $('#pagesCount').textContent = String(pages.length);
  $('#queuedMetric').textContent = String(pages.length);
  const recent = $('#recentPages');
  const list = $('#pagesList');
  recent.replaceChildren();
  list.replaceChildren();
  if (!state.uuid) {
    recent.textContent = 'Pair with PagerAPP to load pages.';
    list.textContent = 'Pair with PagerAPP to load pages.';
    return;
  }
  if (!pages.length) {
    recent.innerHTML = '<div class="empty-state">No queued pages. Nice and quiet.</div>';
    list.innerHTML = '<div class="empty-state">No queued pages. Nice and quiet.</div>';
    return;
  }
  pages.slice(0, 5).forEach((page) => recent.appendChild(pageRow(page)));
  pages.forEach((page) => list.appendChild(pageRow(page)));
}

async function loadPages() {
  if (!state.uuid) return;
  $('#refreshPages').disabled = true;
  $('#refreshPages2').disabled = true;
  try {
    const response = await fetch(
      `/api/mailbox/${encodeURIComponent(state.uuid)}/messages?server=${encodeURIComponent(state.server)}`,
      { headers: { Authorization: `Bearer ${state.uuid}` } },
    );
    if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error || `HTTP ${response.status}`);
    const data = await response.json();
    state.pages = Array.isArray(data.messages) ? data.messages : [];
    renderPages();
    $('#connectNote').textContent = `Synced ${state.pages.length} message(s).`;
    const statusResponse = await fetch(
      `/api/mailbox/${encodeURIComponent(state.uuid)}/status?server=${encodeURIComponent(state.server)}`,
      { headers: { Authorization: `Bearer ${state.uuid}` } },
    );
    if (statusResponse.ok) {
      const status = await statusResponse.json();
      const ttl = status?.retention?.message_ttl_seconds;
      if (ttl) $('#retentionMetric').textContent = `${Math.round(ttl / 86400)}d`;
    }
  } catch (error) {
    state.pages = [];
    renderPages();
    $('#connectNote').textContent = `Could not read mailbox: ${error.message}`;
    toast(`Mailbox error: ${error.message}`);
  } finally {
    $('#refreshPages').disabled = false;
    $('#refreshPages2').disabled = false;
  }
}

async function ackPage(id) {
  try {
    const response = await fetch(
      `/api/mailbox/${encodeURIComponent(state.uuid)}/ack?server=${encodeURIComponent(state.server)}`,
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${state.uuid}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      },
    );
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    toast('Page acknowledged');
    await loadPages();
  } catch (error) {
    toast(`ACK failed: ${error.message}`);
  }
}

function sessionFromStorage() {
  try {
    return JSON.parse(sessionStorage.getItem('pagerapp.transfer') || 'null');
  } catch { return null; }
}

function saveTransfer(transfer) {
  state.transfer = transfer;
  if (transfer) sessionStorage.setItem('pagerapp.transfer', JSON.stringify(transfer));
  else sessionStorage.removeItem('pagerapp.transfer');
}

function renewalDue() {
  return Boolean(state.transfer) && Date.now() >= state.transfer.expires - RENEW_BEFORE_MS;
}

function daysRemaining() {
  if (!state.transfer) return 0;
  return Math.max(0, Math.ceil((state.transfer.expires - Date.now()) / 86400000));
}

function renderTransfer() {
  const transfer = state.transfer;
  const info = $('#sessionInfo');
  const chip = $('#sessionChip');
  $('#renewSession').disabled = !renewalDue();
  $('#deleteSession').disabled = !transfer;
  if (!transfer) {
    info.classList.add('d-none');
    chip.textContent = 'none';
    chip.className = 'chip';
    $('#createSession').classList.remove('d-none');
    $('#pairAction').classList.remove('d-none');
    return;
  }
  const due = renewalDue();
  info.classList.toggle('d-none', !due);
  $('#createSession').classList.toggle('d-none', !due);
  $('#pairAction').classList.toggle('d-none', !due);
  chip.textContent = due ? `renew · ${daysRemaining()}d` : `active · ${daysRemaining()}d`;
  chip.className = `chip ${due ? 'bad' : 'ok'}`;
  $('#sessionExpiry').textContent = `expires ${new Date(transfer.expires).toLocaleString()}`;
  $('#sessionHint').textContent = 'Scan with PagerAPP to hand data to this tab. Private keys stay here.';
  const url = `${location.origin}/pair?session=${encodeURIComponent(transfer.id)}&token=${encodeURIComponent(transfer.token)}`;
  const qr = qrcode(0, 'M');
  qr.addData(url);
  qr.make();
  const svg = qr.createSvgTag({ cellSize: 3, margin: 2 });
  $('#qrCode').innerHTML = svg;
  const pairQr = $('#pairQrCode');
  if (pairQr) pairQr.innerHTML = svg;
  const pairExpiry = $('#pairExpiry');
  if (pairExpiry) pairExpiry.textContent = `expires ${new Date(transfer.expires).toLocaleString()}`;
}

let pairModal = null;
function showPairing(mode = 'initial') {
  if (state.uuid) return;
  const title = document.querySelector('#pairTitle');
  const intro = document.querySelector('#pairModal p');
  if (mode === 'renew') {
    title.textContent = 'Renew this browser session';
    intro.textContent = 'This session reaches day 27. Scan the code with PagerAPP to extend it by 30 days, or close this dialog and let it expire.';
  } else {
    title.textContent = 'Link this browser to your phone';
    intro.textContent = 'Open PagerAPP on your phone and scan this code. The app supplies the mailbox, server and session; the private key stays on the phone.';
  }
  pairModal = pairModal || new bootstrap.Modal($('#pairModal'), { backdrop: 'static', keyboard: false });
  pairModal.show();
}

async function createSession() {
  try {
    const response = await fetch('/api/session', { method: 'POST' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const transfer = await response.json();
    saveTransfer(transfer);
    renderTransfer();
    showPairing('initial');
    toast('Pairing code ready');
  } catch (error) {
    toast(`Could not create session: ${error.message}`);
  }
}

async function renewSession() {
  if (!state.transfer) return;
  try {
    const response = await fetch(`/api/session/${encodeURIComponent(state.transfer.id)}/renew`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${state.transfer.token}` },
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const renewed = await response.json();
    saveTransfer({ ...state.transfer, expires: renewed.expires });
    renderTransfer();
    toast('Session extended by 30 days');
  } catch (error) {
    toast(`Renew failed: ${error.message}`);
  }
}

async function deleteSession() {
  if (!state.transfer) return;
  try {
    await fetch(`/api/session/${encodeURIComponent(state.transfer.id)}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${state.transfer.token}` },
    });
    saveTransfer(null);
    renderTransfer();
    toast('Session discarded');
  } catch (error) {
    toast(`Discard failed: ${error.message}`);
  }
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

function importBackupFile(file) {
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const root = JSON.parse(String(reader.result || ''));
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
      sessionStorage.setItem('pagerapp.server', server);
      sessionStorage.setItem('pagerapp.uuid', uuid);
      if (pairModal) pairModal.hide();
      updateConnection();
      $('#connectNote').textContent = 'Imported locally. The file never went to the Docker server.';
      toast('Backup imported locally');
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
  $('#refreshPages').addEventListener('click', loadPages);
  $('#refreshPages2').addEventListener('click', loadPages);
  $('#createSession').addEventListener('click', createSession);
  $('#pairAction').addEventListener('click', createSession);
  $('#pairAgain').addEventListener('click', createSession);
  $$('[data-import-backup]').forEach((button) => button.addEventListener('click', () => $('#backupFile').click()));
  $('#backupFile').addEventListener('change', (event) => {
    const file = event.target.files && event.target.files[0];
    if (file) importBackupFile(file);
    event.target.value = '';
  });
  $('#renewSession').addEventListener('click', renewSession);
  $('#deleteSession').addEventListener('click', deleteSession);
  $('#clearLocal').addEventListener('click', () => {
    sessionStorage.clear();
    state.uuid = '';
    state.pages = [];
    state.transfer = null;
    updateConnection();
    renderPages();
    renderTransfer();
    toast('This tab was cleared');
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
state.transfer = sessionFromStorage();
updateConnection();
renderTransfer();
if (!state.transfer) {
  createSession();
} else if (!state.uuid && renewalDue()) {
  showPairing('renew');
}
checkHealth();
setInterval(() => {
  if (!state.transfer || state.uuid) return;
  if (renewalDue()) {
    renderTransfer();
    showPairing('renew');
  }
}, 60 * 60 * 1000);
setInterval(checkHealth, 30000);
