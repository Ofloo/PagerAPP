'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8080);
const PUBLIC = path.join(__dirname, 'public');
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_TRANSFER_BYTES = 32 * 1024 * 1024;
const sessions = new Map();

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

  const mailboxMatch = url.pathname.match(/^\/api\/mailbox\/([^/]+)\/(messages|ack|status)$/);
  if (mailboxMatch) {
    const uuid = decodeURIComponent(mailboxMatch[1]);
    const action = mailboxMatch[2];
    const server = url.searchParams.get('server') || 'https://pager.ofloo.io';
    const base = server.replace(/\/+$/, '');
    if (!/^[0-9a-f-]{36}$/i.test(uuid)) return json(res, 400, { error: 'invalid mailbox' });
    const target = action === 'messages'
      ? `${base}/mailboxes/${uuid}/messages`
      : action === 'ack'
        ? `${base}/mailboxes/${uuid}/ack`
        : `${base}/mailboxes/${uuid}/status`;
    const body = req.method === 'GET' || req.method === 'HEAD'
      ? undefined
      : await readBody(req, 64 * 1024).then((b) => b.length ? JSON.parse(b.toString('utf8')) : undefined);
    return proxyJson(req, res, target, req.method, body);
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
      'cache-control': ext === '.html' ? 'no-store' : 'public, max-age=3600',
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer',
      'x-frame-options': 'DENY',
    };
    res.writeHead(200, headers);
    fs.createReadStream(file).pipe(res);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
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
});

const cleanup = setInterval(() => {
  const now = Date.now();
  for (const [id, session] of sessions) if (session.expires <= now) sessions.delete(id);
}, 60 * 1000);
cleanup.unref();

server.listen(PORT, '0.0.0.0', () => {
  console.log(`PagerAPP web listening on ${PORT}`);
});
