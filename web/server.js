'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8080);
const PUBLIC = path.join(__dirname, 'public');
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_TRANSFER_BYTES = 128 * 1024 * 1024;
const MAX_SHARE_BYTES = 128 * 1024 * 1024;
const SHARE_HOSTS = new Set(
  (process.env.PAGERAPP_SHARE_HOSTS || 'share.h4ck.io')
    .split(',').map((host) => host.trim().toLowerCase()).filter(Boolean),
);
const sessions = new Map();

// Optional direct HTTPS. Provide PEM inline or a path, or mount Docker/Swarm secrets at the
// default locations. If only one half is present the process refuses to start: silently falling
// back to plaintext would be the wrong failure mode for a key-bearing service.
function readTlsMaterial(value, file, fallbackFile) {
  if (value) {
    return String(value).includes('BEGIN ') ? String(value) : fs.readFileSync(value, 'utf8');
  }
  if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8');
  if (fallbackFile && fs.existsSync(fallbackFile)) return fs.readFileSync(fallbackFile, 'utf8');
  return null;
}

const TLS_CERT = readTlsMaterial(
  process.env.PAGERAPP_TLS_CERT || process.env.TLS_CERT,
  process.env.PAGERAPP_TLS_CERT_FILE || process.env.TLS_CERT_FILE || '',
  '/run/secrets/pagerapp_tls_cert',
);
const TLS_KEY = readTlsMaterial(
  process.env.PAGERAPP_TLS_KEY || process.env.TLS_KEY,
  process.env.PAGERAPP_TLS_KEY_FILE || process.env.TLS_KEY_FILE || '',
  '/run/secrets/pagerapp_tls_key',
);
if (Boolean(TLS_CERT) !== Boolean(TLS_KEY)) {
  console.error('TLS_CERT and TLS_KEY must both be configured (inline, file or /run/secrets)');
  process.exit(1);
}
const TLS_ENABLED = Boolean(TLS_CERT && TLS_KEY);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
};

function json(res, status, value, extra = {}) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    ...extra,
  });
  res.end(body);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    req.on('data', (chunk) => {
      length += chunk.length;
      if (length > limit) {
        reject(Object.assign(new Error('payload too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function allowedShareUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password &&
      SHARE_HOSTS.has(url.hostname.toLowerCase());
  } catch {
    return false;
  }
}

function randomToken() {
  return crypto.randomBytes(32).toString('base64url');
}

function hash(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function newSession(publicKey = '') {
  const id = crypto.randomBytes(18).toString('base64url');
  const token = randomToken();
  const expires = Date.now() + SESSION_TTL_MS;
  sessions.set(id, {
    tokenHash: hash(token),
    expires,
    blob: null,
    uploadedAt: null,
    publicKey,
  });
  return { id, token, expires };
}

function sessionFor(req, id) {
  const session = sessions.get(id);
  if (!session) return null;
  if (session.expires <= Date.now()) {
    sessions.delete(id);
    return null;
  }
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  if (!token || hash(token) !== session.tokenHash) return null;
  return session;
}

function sessionIdFromPath(pathname, suffix = '') {
  const prefix = '/api/session/';
  if (!pathname.startsWith(prefix)) return null;
  const rest = pathname.slice(prefix.length);
  if (suffix) {
    if (!rest.endsWith(suffix)) return null;
    return rest.slice(0, -suffix.length) || null;
  }
  return rest || null;
}

async function proxyJson(req, res, targetUrl, method, body) {
  let url;
  try {
    url = new URL(targetUrl);
  } catch {
    return json(res, 400, { error: 'invalid server URL' });
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    return json(res, 400, { error: 'invalid server URL' });
  }
  const headers = {
    accept: 'application/json',
    authorization: req.headers.authorization || '',
    'user-agent': 'PagerAPP-web',
  };
  if (body !== undefined) headers['content-type'] = 'application/json';
  const response = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error',
  });
  const text = await response.text();
  res.writeHead(response.status, {
    'content-type': response.headers.get('content-type') || 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  });
  res.end(text);
}

async function routeApi(req, res, url) {
  if (url.pathname === '/api/client-error' && req.method === 'POST') {
    const body = await readBody(req, 16 * 1024);
    try {
      const payload = JSON.parse(body.toString('utf8'));
      console.error('[client]', payload.message || 'client error', payload.source || '');
    } catch {
      console.error('[client] invalid error report');
    }
    return json(res, 204, {});
  }

  if (url.pathname === '/api/health') {
    return json(res, 200, {
      ok: true,
      service: 'pagerapp-web',
      sessions: sessions.size,
      uptime_seconds: Math.floor(process.uptime()),
    });
  }

  if (url.pathname === '/api/session' && req.method === 'POST') {
    const body = await readBody(req, 8192);
    let publicKey = '';
    if (body.length) {
      try {
        publicKey = String(JSON.parse(body.toString('utf8')).publicKey || '');
      } catch {
        publicKey = '';
      }
    }
    if (publicKey.length > 4096) return json(res, 400, { error: 'public key too large' });
    const created = newSession(publicKey);
    return json(res, 201, {
      id: created.id,
      token: created.token,
      expires: created.expires,
      expires_at: new Date(created.expires).toISOString(),
    });
  }

  const renewId = sessionIdFromPath(url.pathname, '/renew');
  if (renewId && req.method === 'POST') {
    const session = sessionFor(req, renewId);
    if (!session) return json(res, 404, { error: 'unknown or expired session' });
    session.expires = Date.now() + SESSION_TTL_MS;
    return json(res, 200, { id: renewId, expires: session.expires, expires_at: new Date(session.expires).toISOString() });
  }

  const publicId = sessionIdFromPath(url.pathname, '/public');
  if (publicId && req.method === 'GET') {
    const session = sessionFor(req, publicId);
    if (!session) return json(res, 404, { error: 'unknown or expired session' });
    if (!session.publicKey) return json(res, 404, { error: 'no public key for session' });
    return json(res, 200, { publicKey: session.publicKey });
  }

  const statusId = sessionIdFromPath(url.pathname, '/status');
  if (statusId && req.method === 'GET') {
    const session = sessionFor(req, statusId);
    if (!session) return json(res, 404, { error: 'unknown or expired session' });
    return json(res, 200, {
      id: statusId,
      expires: session.expires,
      uploaded: Boolean(session.blob),
      bytes: session.blob ? session.blob.length : 0,
    });
  }

  const uploadId = sessionIdFromPath(url.pathname, '/upload');
  if (uploadId && req.method === 'POST') {
    const session = sessionFor(req, uploadId);
    if (!session) return json(res, 404, { error: 'unknown or expired session' });
    const body = await readBody(req, MAX_TRANSFER_BYTES);
    if (!body.length) return json(res, 400, { error: 'empty transfer' });
    session.blob = body;
    session.uploadedAt = Date.now();
    return json(res, 200, { ok: true, bytes: body.length });
  }

  const downloadId = sessionIdFromPath(url.pathname, '/download');
  if (downloadId && req.method === 'GET') {
    const session = sessionFor(req, downloadId);
    if (!session) return json(res, 404, { error: 'unknown or expired session' });
    if (!session.blob) return json(res, 404, { error: 'no transfer uploaded' });
    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-length': session.blob.length,
      'cache-control': 'no-store',
      'content-disposition': 'attachment; filename="pagerapp-transfer.bin"',
    });
    return res.end(session.blob);
  }

  const deleteId = sessionIdFromPath(url.pathname);
  if (deleteId && req.method === 'DELETE') {
    const session = sessionFor(req, deleteId);
    if (!session) return json(res, 404, { error: 'unknown or expired session' });
    sessions.delete(deleteId);
    return json(res, 200, { ok: true });
  }

  if (url.pathname === '/api/share/upload' && req.method === 'POST') {
    const hostValue = url.searchParams.get('host') || `https://${[...SHARE_HOSTS][0] || 'share.h4ck.io'}`;
    let host;
    try { host = new URL(hostValue); } catch { return json(res, 400, { error: 'invalid share host' }); }
    if (!allowedShareUrl(host.origin + '/')) {
      return json(res, 400, { error: 'share host is not allowed' });
    }
    const days = Math.max(1, Math.min(365, Number(url.searchParams.get('days')) || 3));
    const body = await readBody(req, MAX_SHARE_BYTES);
    if (!body.length) return json(res, 400, { error: 'empty attachment' });
    const name = `pagerapp-${crypto.randomUUID()}.asc`;
    const response = await fetch(`${host.origin}/${name}`, {
      method: 'PUT',
      headers: {
        'content-type': 'application/octet-stream',
        'max-days': String(days),
        'user-agent': 'PagerAPP-web',
      },
      body,
      redirect: 'error',
    });
    if (!response.ok) {
      return json(res, 502, { error: `share upload HTTP ${response.status}` });
    }
    const publicUrl = (await response.text()).trim();
    if (!allowedShareUrl(publicUrl)) {
      return json(res, 502, { error: 'share host returned an invalid URL' });
    }
    const deleteUrl = response.headers.get('x-url-delete') || null;
    return json(res, 200, {
      url: publicUrl,
      deleteUrl: allowedShareUrl(deleteUrl) ? deleteUrl : null,
      days,
    });
  }

  if (url.pathname === '/api/share/download' && req.method === 'GET') {
    const target = url.searchParams.get('url') || '';
    if (!allowedShareUrl(target)) return json(res, 400, { error: 'share URL is not allowed' });
    const response = await fetch(target, {
      method: 'GET',
      headers: { 'user-agent': 'PagerAPP-web', accept: 'application/octet-stream' },
      redirect: 'error',
    });
    if (!response.ok) return json(res, 502, { error: `share download HTTP ${response.status}` });
    const length = Number(response.headers.get('content-length') || 0);
    if (length > MAX_SHARE_BYTES) return json(res, 413, { error: 'attachment too large' });
    const body = Buffer.from(await response.arrayBuffer());
    if (body.length > MAX_SHARE_BYTES) return json(res, 413, { error: 'attachment too large' });
    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-length': body.length,
      'cache-control': 'no-store',
    });
    return res.end(body);
  }

  if (url.pathname === '/api/settings' && req.method === 'GET') {
    const server = url.searchParams.get('server') || 'https://pager.ofloo.io';
    return proxyJson(req, res, `${server.replace(/\/+$/, '')}/settings`, 'GET', undefined);
  }

  const mailboxMatch = url.pathname.match(/^\/api\/mailbox\/([^/]+)\/(messages|ack|status|mode|notify)$/);
  if (mailboxMatch) {
    const uuid = decodeURIComponent(mailboxMatch[1]);
    const action = mailboxMatch[2];
    const server = url.searchParams.get('server') || 'https://pager.ofloo.io';
    const base = server.replace(/\/+$/, '');
    if (!/^[0-9a-f-]{36}$/i.test(uuid)) return json(res, 400, { error: 'invalid mailbox' });
    if (action === 'mode' && req.method !== 'GET') {
      return json(res, 405, { error: 'the phone owns the webclient mode' });
    }
    const target = action === 'notify'
      ? `${base}/notify`
      : `${base}/mailboxes/${uuid}/${action}`;
    const upstream = new URL(target);
    const order = url.searchParams.get('order');
    if (order === 'newest' || order === 'oldest') upstream.searchParams.set('order', order);
    const body = req.method === 'GET' || req.method === 'HEAD'
      ? undefined
      : await readBody(req, 128 * 1024).then((b) => b.length ? JSON.parse(b.toString('utf8')) : undefined);
    return proxyJson(req, res, upstream.toString(), req.method, body);
  }

  return json(res, 404, { error: 'unknown API route' });
}

function serveStatic(req, res, url) {
  let pathname = decodeURIComponent(url.pathname);
  if (pathname === '/') pathname = '/index.html';
  if (pathname === '/pages') pathname = '/index.html';
  if (pathname === '/chats') pathname = '/index.html';
  if (pathname === '/settings') pathname = '/index.html';
  if (pathname === '/pair') pathname = '/pair.html';
  const file = path.normalize(path.join(PUBLIC, pathname));
  if (!file.startsWith(PUBLIC + path.sep)) {
    res.writeHead(403); res.end('forbidden'); return;
  }
  fs.stat(file, (statError, stat) => {
    if (statError || !stat.isFile()) {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('not found');
      return;
    }
    const ext = path.extname(file).toLowerCase();
    const headers = {
      'content-type': MIME[ext] || 'application/octet-stream',
      'cache-control': (ext === '.html' || ext === '.js' || ext === '.css')
        ? 'no-cache'
        : 'public, max-age=3600',
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer',
      'x-frame-options': 'DENY',
    };
    res.writeHead(200, headers);
    fs.createReadStream(file).pipe(res);
  });
}

const requestHandler = async (req, res) => {
  const url = new URL(req.url, `${TLS_ENABLED ? 'https' : 'http'}://${req.headers.host || 'localhost'}`);
  try {
    if (url.pathname.startsWith('/api/')) {
      await routeApi(req, res, url);
    } else {
      serveStatic(req, res, url);
    }
  } catch (error) {
    const status = Number(error.status) || 500;
    console.error(`[error] ${req.method} ${url.pathname}:`, error && (error.stack || error.message || error));
    if (!res.headersSent) json(res, status, { error: status === 500 ? 'internal error' : error.message });
    else res.destroy();
  }
};

const server = TLS_ENABLED
  ? https.createServer({ cert: TLS_CERT, key: TLS_KEY }, requestHandler)
  : http.createServer(requestHandler);

const cleanup = setInterval(() => {
  const now = Date.now();
  for (const [id, session] of sessions) if (session.expires <= now) sessions.delete(id);
}, 60 * 1000);
cleanup.unref();

server.listen(PORT, '0.0.0.0', () => {
  console.log(`PagerAPP web listening on ${PORT}${TLS_ENABLED ? ' (https)' : ''}`);
});
