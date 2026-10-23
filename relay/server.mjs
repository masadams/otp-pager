import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { mkdirSync, readFileSync, openSync, writeFileSync, fsyncSync, closeSync, renameSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const MAX_BODY = 16 * 1024;
const ROOM = /^[a-f0-9]{64}$/;

// One process per data volume. Synchronous commits serialize writes and acknowledge
// only after file + directory fsync. Never persist authentication/encryption keys.
export function makeServer(options = {}) {
  const token = options.token ?? process.env.RELAY_TOKEN;
  if (!token || !/^[A-Za-z0-9_-]{43,128}$/.test(token))
    throw new Error('RELAY_TOKEN must be 43–128 URL-safe characters (generate 32 random bytes)');
  const expected = Buffer.from('Bearer ' + token);
  const dataDir = options.dataDir ?? process.env.DATA_DIR ?? './data';
  const ttl = options.ttl ?? 86400000;
  const maxMessages = options.maxMessages ?? 2000;
  const maxRoom = options.maxRoom ?? 200;
  const now = options.now ?? Date.now;
  mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  const file = join(dataDir, 'mailboxes.json');
  let state = { version: 1, seq: 0, messages: [] };
  try { state = JSON.parse(readFileSync(file, 'utf8')); }
  catch (e) { if (e.code !== 'ENOENT') throw e; }
  if (state.version !== 1 || !Number.isSafeInteger(state.seq) || state.seq < 0 ||
      !Array.isArray(state.messages) || state.messages.some(m => !ROOM.test(m.room) ||
        !Number.isSafeInteger(m.seq) || m.seq <= 0 || m.seq > state.seq ||
        !Number.isFinite(m.time) || typeof m.packet !== 'string' || m.packet.length > 12000))
    throw new Error('Invalid mailbox state; refusing to reset cursors');
  function commit(next) {
    const fd = openSync(file + '.tmp', 'w', 0o600);
    try { writeFileSync(fd, JSON.stringify(next)); fsyncSync(fd); } finally { closeSync(fd); }
    renameSync(file + '.tmp', file);
    const dir = openSync(dataDir, 'r');
    try { fsyncSync(dir); } finally { closeSync(dir); }
    state = next;
  }
  function clean() {
    const live = state.messages.filter(m => now() - m.time < ttl);
    if (live.length !== state.messages.length) commit({ ...state, messages: live });
  }
  function json(res, status, body) {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store',
      'x-content-type-options': 'nosniff' });
    res.end(JSON.stringify(body));
  }
  function readBody(req) {
    return new Promise((resolve, reject) => {
      let size = 0; const chunks = [];
      req.on('data', chunk => {
        size += chunk.length;
        if (size > MAX_BODY) { reject(Object.assign(new Error(), { status: 413 })); return; }
        chunks.push(chunk);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
      req.on('aborted', reject);
    });
  }
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname === '/health' && req.method === 'GET') return json(res, 200, { status: 'ok' });
      const actual = Buffer.from(req.headers.authorization || '');
      if (actual.length !== expected.length || !timingSafeEqual(actual, expected))
        return json(res, 401, { error: 'unauthorized' });
      const match = /^\/v1\/mailbox\/([a-f0-9]{64})$/.exec(url.pathname);
      if (!match) return json(res, 404, { error: 'not found' });
      const room = match[1];
      clean();
      if (req.method === 'GET') {
        const after = Number(url.searchParams.get('after') || 0);
        if (!Number.isSafeInteger(after) || after < 0) return json(res, 400, { error: 'bad cursor' });
        return json(res, 200, { messages: state.messages.filter(m => m.room === room && m.seq > after)
          .map(({ seq, packet }) => ({ seq, packet })) });
      }
      if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
      let data;
      try { data = JSON.parse(await readBody(req)); }
      catch (e) { return json(res, e?.status || 400, { error: 'invalid request' }); }
      if (!data || typeof data.packet !== 'string' || data.packet.length > 12000 ||
          !/^[A-Za-z0-9+/_=-]+$/.test(data.packet)) return json(res, 400, { error: 'invalid packet' });
      clean();
      if (state.messages.filter(m => m.room === room).length >= maxRoom || state.messages.length >= maxMessages)
        return json(res, 429, { error: 'mailbox capacity reached' });
      if (state.seq === Number.MAX_SAFE_INTEGER) return json(res, 503, { error: 'cursor exhausted' });
      const seq = state.seq + 1;
      commit({ ...state, seq, messages: [...state.messages, { room, seq, packet: data.packet, time: now() }] });
      return json(res, 201, { seq });
    } catch {
      // Deliberately omit URL, tokens, bodies and exception details from logs.
      return json(res, 503, { error: 'relay unavailable' });
    }
  });
  const timer = setInterval(() => { try { clean(); } catch { console.error('Mailbox cleanup failed'); } }, 60000);
  timer.unref();
  server.on('close', () => clearInterval(timer));
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.maxRequestsPerSocket = 100;
  return server;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const server = makeServer();
  server.listen(Number(process.env.PORT || 8080), process.env.HOST || '0.0.0.0', () => console.log('Relay ready'));
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => server.close(() => process.exit(0)));
}
