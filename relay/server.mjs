import http from 'node:http';
import { randomUUID } from 'node:crypto';

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const MAX_BODY = 16 * 1024;
const TTL = 24 * 60 * 60 * 1000;
const MAX_ROOM = 200;
const rooms = new Map();

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store',
    'x-content-type-options': 'nosniff' });
  res.end(JSON.stringify(body));
}
function clean(now = Date.now()) {
  for (const [room, messages] of rooms) {
    const live = messages.filter(m => now - m.time < TTL);
    if (live.length) rooms.set(room, live);
    else rooms.delete(room);
  }
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => {
      body += chunk;
      if (body.length > MAX_BODY) { reject(new Error('too large')); req.destroy(); }
    });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}
export function makeServer() {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/health' && req.method === 'GET') return json(res, 200, { status: 'ok' });
    const match = /^\/v1\/mailbox\/([a-f0-9]{64})$/.exec(url.pathname);
    if (!match) return json(res, 404, { error: 'not found' });
    const room = match[1];
    clean();
    if (req.method === 'GET') {
      const after = Number(url.searchParams.get('after') || 0);
      if (!Number.isSafeInteger(after) || after < 0) return json(res, 400, { error: 'bad cursor' });
      return json(res, 200, { messages: (rooms.get(room) || [])
        .filter(m => m.seq > after).map(({ seq, packet }) => ({ seq, packet })) });
    }
    if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
    try {
      const data = JSON.parse(await readBody(req));
      if (typeof data.packet !== 'string' || data.packet.length > 12000 ||
          !/^[A-Za-z0-9+/_=-]+$/.test(data.packet))
        return json(res, 400, { error: 'invalid packet' });
      const messages = rooms.get(room) || [];
      if (messages.length >= MAX_ROOM) return json(res, 429, { error: 'mailbox full' });
      const seq = (messages.at(-1)?.seq || 0) + 1;
      messages.push({ seq, packet: data.packet, time: Date.now(), id: randomUUID() });
      rooms.set(room, messages);
      return json(res, 201, { seq });
    } catch {
      return json(res, 400, { error: 'invalid request' });
    }
  });
}
if (process.argv[1] && import.meta.url === new URL('file://' + process.argv[1]).href) {
  makeServer().listen(PORT, HOST, () => console.log('Relay listening on ' + PORT));
}
