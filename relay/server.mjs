import http from 'node:http';
import { timingSafeEqual, createHash, randomUUID, generateKeyPairSync, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { mkdirSync, readFileSync, openSync, writeFileSync, fsyncSync, closeSync, renameSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const MAX_BODY = 16 * 1024;
const ROOM = /^[a-f0-9]{64}$/;

// One process per data volume. Synchronous commits serialize writes and acknowledge
// only after file + directory fsync. Persist capability hashes, never raw credentials or encryption keys.
export function makeServer(options = {}) {
  const token = options.token ?? (process.env.RELAY_TOKEN_FILE ? readFileSync(process.env.RELAY_TOKEN_FILE, 'utf8').trim() : process.env.RELAY_TOKEN);
  if (!token || !/^[A-Za-z0-9_-]{43,128}$/.test(token))
    throw new Error('RELAY_TOKEN must be 43–128 URL-safe characters (generate 32 random bytes)');
  const expected = Buffer.from('Bearer ' + token);
  const dataDir = options.dataDir ?? process.env.DATA_DIR ?? './data';
  const ttl = options.ttl ?? 86400000;
  const maxMessages = options.maxMessages ?? 2000;
  const maxRoom = options.maxRoom ?? 200;
  const maxClaims = options.maxClaims ?? 1000;
  const maxShares = options.maxShares ?? 1000;
  // Strict chats: a phone stores one 32-byte share per chat here. It must check in within its
  // own deadline or the share is discarded; without it, that phone can no longer open the chat.
  const MIN_DEADLINE = 3600000, MAX_DEADLINE = 30 * 86400000, CHECK_IN_WRITE = 60000;
  // Unlock grants are signed OTPSTR01 frames (docs/STRICT_CHAT_PROTOCOL.md) valid for at most 60 s.
  const FRAME = 178, MAX_LEASE = 60000, MAX_SKEW = options.maxSkew ?? 300000, MAX_NONCES = 32;
  const now = options.now ?? Date.now;
  const rateLimit = options.rateLimit ?? 360;
  // Strict claims (finding F06): the setup token may only create chat claims; it can never
  // read, post or delete mailbox traffic. Off by default so legacy unclaimed chats keep working.
  const strictClaims = options.strictClaims ?? ['1', 'true', 'yes'].includes(String(process.env.RELAY_STRICT_CLAIMS || '').toLowerCase());
  const buckets = new Map();
  let poisoned = false;
  const credentialHash = value => createHash('sha256').update(value).digest('hex');
  const equalHash = (a, b) => timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'));
  const digest = packet => createHash('sha256').update(Buffer.from(packet, 'base64')).digest('hex');
  mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  const file = join(dataDir, 'mailboxes.json');
  // Strict-chat unlock authority: one P-256 key per relay volume, created once, never sent anywhere.
  const authorityFile = join(dataDir, 'authority-key.pem');
  let authorityKey;
  try { authorityKey = createPrivateKey(readFileSync(authorityFile, 'utf8')); }
  catch (e) {
    if (e.code !== 'ENOENT') throw e;
    const pem = generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey.export({ type: 'pkcs8', format: 'pem' });
    const fd = openSync(authorityFile + '.tmp', 'w', 0o600);
    try { writeFileSync(fd, pem); fsyncSync(fd); } finally { closeSync(fd); }
    renameSync(authorityFile + '.tmp', authorityFile);
    authorityKey = createPrivateKey(pem);
  }
  const authoritySpki = createPublicKey(authorityKey).export({ type: 'spki', format: 'der' });
  const authorityFingerprint = createHash('sha256').update(authoritySpki).digest();
  let state = { version: 1, seq: 0, messages: [] };
  try { state = JSON.parse(readFileSync(file, 'utf8')); }
  catch (e) { if (e.code !== 'ENOENT') throw e; }
  // Pipeline item 10: frequent changes (posts, expiry, unlock check-ins) are appended to a
  // journal instead of rewriting the whole snapshot. The journal's first line names the
  // snapshot generation it extends; a journal left over from before a newer snapshot is ignored,
  // so a crash between snapshot and journal reset can never replay stale operations.
  const journalFile = join(dataDir, 'journal.log');
  const compactEvery = options.compactEvery ?? 256;
  let journaled = 0, torn = false;
  function apply(s, op) {
    if (op.t === 'msg') return op.m.seq <= s.seq ? s : { ...s, seq: op.m.seq, messages: [...s.messages, op.m] };
    if (op.t === 'clean') {
      const drop = new Set(op.seqs), gone = new Set(op.rooms);
      return { ...s, messages: s.messages.filter(m => !drop.has(m.seq)),
        shares: Object.fromEntries(Object.entries(s.shares || {}).filter(([room]) => !gone.has(room))),
        retired: { ...(s.retired || {}), ...Object.fromEntries(op.rooms.filter(room => s.shares?.[room])
          .map(room => [room, { device: s.shares[room].device, at: 0 }])) } };
    }
    if (op.t === 'share') return { ...s, shares: { ...(s.shares || {}), [op.room]: op.value } };
    throw new Error('Unknown journal operation');
  }
  try {
    const lines = readFileSync(journalFile, 'utf8').split('\n');
    const head = (() => { try { return JSON.parse(lines[0]); } catch { return null; } })();
    if (head && head.gen === (state.journal ?? 0)) {
      journaled = 1;
      // A torn final line (crash mid-append) was never acknowledged; stop at the first bad line.
      for (const line of lines.slice(1)) { if (!line) continue; let op; try { op = JSON.parse(line); } catch { torn = true; break; } state = apply(state, op); journaled++; }
    }
  } catch (e) { if (e.code !== 'ENOENT') throw e; }
  if (![1, 2].includes(state.version) || !Number.isSafeInteger(state.seq) || state.seq < 0 ||
      !Array.isArray(state.messages) || state.messages.some(m => !ROOM.test(m.room) ||
        !Number.isSafeInteger(m.seq) || m.seq <= 0 || m.seq > state.seq ||
        !Number.isFinite(m.time) || typeof m.packet !== 'string' || m.packet.length > 12000))
    throw new Error('Invalid mailbox state; refusing to reset cursors');
  // Access claims are permanent metadata; expiry never reopens a protected/revoked room.
  if (state.access === undefined && state.version === 1) state.access = {};
  if (!state.access || typeof state.access !== 'object' || Array.isArray(state.access) ||
      Object.entries(state.access).some(([room, entry]) => !ROOM.test(room) || !entry ||
        typeof entry.hash !== 'string' || !/^[a-f0-9]{64}$/.test(entry.hash) || typeof entry.revoked !== 'boolean'))
    throw new Error('Invalid mailbox access state');
  if (state.shares === undefined) state.shares = {};
  if (!state.shares || typeof state.shares !== 'object' || Array.isArray(state.shares) ||
      Object.entries(state.shares).some(([room, s]) => !ROOM.test(room) || !s || !/^[A-Za-z0-9_-]{43}$/.test(s.share) ||
        !Number.isSafeInteger(s.deadline) || s.deadline < MIN_DEADLINE || s.deadline > MAX_DEADLINE || !Number.isFinite(s.checked) ||
        typeof s.device !== 'string' || !Array.isArray(s.nonces) || s.nonces.length > MAX_NONCES))
    throw new Error('Invalid share state');
  // Retired share rooms: permanent tombstones. A retired room is never re-enrolled, and its
  // unlock requests get a signed REVOKED answer, so a phone only wipes on authenticated evidence.
  if (state.retired === undefined) state.retired = {};
  if (!state.retired || typeof state.retired !== 'object' || Array.isArray(state.retired) ||
      Object.entries(state.retired).some(([room, r]) => !ROOM.test(room) || !r || typeof r.device !== 'string' || !Number.isFinite(r.at)))
    throw new Error('Invalid retired share state');
  function syncDir() { const dir = openSync(dataDir, 'r'); try { fsyncSync(dir); } finally { closeSync(dir); } }
  function writeJournalHead(gen) {
    const fd = openSync(journalFile + '.tmp', 'w', 0o600);
    try { writeFileSync(fd, JSON.stringify({ gen }) + '\n'); fsyncSync(fd); } finally { closeSync(fd); }
    renameSync(journalFile + '.tmp', journalFile); syncDir();
  }
  /** [op] journals a small change; without one (or when the journal is long) a snapshot is written. */
  function commit(next, op = null) {
    if (poisoned) throw new Error('Storage unavailable');
    try {
      if (op && journaled > 0 && journaled < compactEvery) {
        const fd = openSync(journalFile, 'a', 0o600);
        try { writeFileSync(fd, JSON.stringify(op) + '\n'); fsyncSync(fd); } finally { closeSync(fd); }
        journaled++; state = next; return;
      }
      const gen = (next.journal ?? 0) + 1, snapshot = { ...next, journal: gen };
      const fd = openSync(file + '.tmp', 'w', 0o600);
      try { writeFileSync(fd, JSON.stringify(snapshot)); fsyncSync(fd); } finally { closeSync(fd); }
      renameSync(file + '.tmp', file);
      syncDir();
      writeJournalHead(gen); journaled = 1; // Counts the head line, so the next small change appends.
      state = snapshot;
    } catch (e) { poisoned = true; throw e; }
  }
  // Never append after a torn line: fold everything replayed into a fresh snapshot first.
  if (torn) commit(state);
  // Migrate existing v1 volume in place, preserving all ciphertext and cursors.
  if (state.version === 1 && !state.epoch) commit({ ...state, epoch: randomUUID() });
  if (typeof state.epoch !== 'string' || !/^[a-f0-9-]{36}$/.test(state.epoch))
    throw new Error('Invalid storage epoch');
  const sequences = state.messages.map(m => m.seq);
  if (sequences.some((seq, i) => i > 0 && seq <= sequences[i - 1])) throw new Error('Invalid stored sequence ordering');
  /**
   * Retire share rooms: drop the shares, keep tombstones, and rewrite the snapshot (which also
   * resets the journal), so no live relay file still holds a retired share. Copies already in
   * backups, old disk blocks or replaced files are outside this; see docs/STRICT_CHAT_PROTOCOL.md.
   */
  function retiredState(base, rooms) {
    const gone = new Set(rooms), at = now();
    return { ...base, shares: Object.fromEntries(Object.entries(base.shares).filter(([room]) => !gone.has(room))),
      retired: { ...base.retired, ...Object.fromEntries(rooms.map(room => [room, { device: base.shares[room].device, at }])) } };
  }
  function clean() {
    const live = state.messages.filter(m => now() - m.time < ttl);
    const expired = Object.keys(state.shares).filter(room => now() - state.shares[room].checked > state.shares[room].deadline);
    if (expired.length) { commit({ ...retiredState(state, expired), messages: live }); return; }
    if (live.length !== state.messages.length) {
      const kept = new Set(live.map(m => m.seq));
      commit({ ...state, messages: live }, { t: 'clean', seqs: state.messages.filter(m => !kept.has(m.seq)).map(m => m.seq), rooms: [] });
    }
  }
  function json(res, status, body) {
    if (res.destroyed || res.writableEnded) return;
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store',
      'x-content-type-options': 'nosniff' });
    res.end(JSON.stringify(body));
  }
  function readBody(req) {
    return new Promise((resolve, reject) => {
      let size = 0; const chunks = [];
      const timer = setTimeout(() => { reject(Object.assign(new Error(), { status: 408 })); }, 10000);
      const fail = error => { clearTimeout(timer); reject(error); };
      req.on('data', chunk => {
        size += chunk.length;
        if (size > MAX_BODY) { fail(Object.assign(new Error(), { status: 413 })); return; }
        chunks.push(chunk);
      });
      req.on('end', () => { clearTimeout(timer); resolve(Buffer.concat(chunks).toString('utf8')); });
      req.on('error', fail);
      req.on('aborted', fail);
    });
  }
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname === '/health' && req.method === 'GET') return json(res, poisoned ? 503 : 200, { status: poisoned ? 'unavailable' : 'ok' });
      if (poisoned) return json(res, 503, { error: 'relay unavailable' });
      // Trust socket peer only, never arbitrary X-Forwarded-For. Behind Nginx
      // this is a shared proxy budget; configure per-public-IP limits at Nginx.
      const actual = Buffer.from(req.headers.authorization || '');
      const bootstrap = actual.length === expected.length && timingSafeEqual(actual, expected);
      if (url.pathname === '/v1/strict/authority' && req.method === 'GET')
        return json(res, 200, { key: authoritySpki.toString('base64') });
      const shareMatch = /^\/v1\/share\/([a-f0-9]{64})(\/unlock)?$/.exec(url.pathname);
      const match = /^\/v1\/mailbox\/([a-f0-9]{64})(\/claim)?$/.exec(url.pathname) ?? (shareMatch && [shareMatch[0], shareMatch[1], undefined]);
      const room = match?.[1];
      const claim = state.access[room];
      const bearer = /^Bearer ([A-Za-z0-9_-]{43,128})$/.exec(req.headers.authorization || '')?.[1];
      const scoped = !!(claim && bearer && equalHash(claim.hash, credentialHash(bearer)));
      const authorized = scoped || (bootstrap && !claim && !strictClaims);
      const peer = scoped ? 'room:' + room : (bootstrap ? 'setup:' : 'anon:') + req.socket.remoteAddress;
      const time = now();
      for (const [ip, bucket] of buckets) if (time - bucket.start >= 60000) buckets.delete(ip);
      if (!buckets.has(peer)) {
        if (buckets.size >= 1024) return json(res, 429, { error: 'busy' });
        buckets.set(peer, { start: time, count: 0 });
      }
      if (++buckets.get(peer).count > (authorized ? rateLimit : Math.min(rateLimit, 60))) {
        res.setHeader('Retry-After', '60');
        return json(res, 429, { error: 'rate limited' });
      }
      if (!match) return json(res, bootstrap ? 404 : 401, { error: 'not found' });
      if (match[2]) {
        if (!bootstrap) return json(res, 401, { error: 'unauthorized' });
        if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
        if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] || ''))
          return json(res, 415, { error: 'JSON content type required' });
        let data;
        try { data = JSON.parse(await readBody(req)); }
        catch (e) {
          if (e?.status) { res.setHeader('Connection', 'close'); res.once('finish', () => req.destroy()); }
          return json(res, e?.status || 400, { error: 'invalid request' });
        }
        if (!data || typeof data.capability !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(data.capability) || data.capability === token)
          return json(res, 400, { error: 'invalid capability' });
        const hash = credentialHash(data.capability);
        const existing = state.access[room]; // Recheck after async body read; claims cannot be replaced.
        if (existing) {
          if (!equalHash(existing.hash, hash)) return json(res, 403, { error: 'claim conflict' });
          if (existing.revoked) return json(res, 410, { error: 'chat revoked' });
          return json(res, 200, { access: 'chat' });
        }
        if (Object.keys(state.access).length >= maxClaims) return json(res, 429, { error: 'claim capacity reached' });
        commit({ ...state, version: 2, access: { ...state.access, [room]: { hash, revoked: false } } });
        return json(res, 201, { access: 'chat' });
      }
      if (!authorized) return json(res, 401, { error: 'unauthorized' });
      if (claim?.revoked) return json(res, 410, { error: 'chat revoked' });
      if (shareMatch) {
        // Shares are only ever reachable with a claimed capability, never the setup token.
        if (!scoped) return json(res, 403, { error: 'chat credential required' });
        clean();
        const held = state.shares[room];
        if (req.method === 'DELETE' && !shareMatch[2]) {
          if (held) commit(retiredState(state, [room]));
          return json(res, 200, { share: 'deleted' });
        }
        const expected = shareMatch[2] ? 'POST' : 'PUT';
        if (req.method !== expected) { res.setHeader('Allow', shareMatch[2] ? 'POST' : 'PUT, DELETE'); return json(res, 405, { error: 'method not allowed' }); }
        if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] || ''))
          return json(res, 415, { error: 'JSON content type required' });
        let data;
        try { data = JSON.parse(await readBody(req)); }
        catch (e) {
          if (e?.status) { res.setHeader('Connection', 'close'); res.once('finish', () => req.destroy()); }
          return json(res, e?.status || 400, { error: 'invalid request' });
        }
        if (shareMatch[2]) {
          // Unlock: an OTPSTR01 UNLOCK frame signed by the enrolled device key. Doubles as the check-in.
          const current = state.shares[room], retired = state.retired[room];
          if (!current && !retired) return json(res, 404, { error: 'no share' }); // Unknown: never evidence of retirement.
          if (!data || typeof data.frame !== 'string' || typeof data.signature !== 'string') return json(res, 400, { error: 'invalid unlock' });
          const frame = Buffer.from(data.frame, 'base64'), signature = Buffer.from(data.signature, 'base64');
          if (frame.length !== FRAME || frame.toString('base64') !== data.frame || signature.length < 8 || signature.length > 80)
            return json(res, 400, { error: 'invalid unlock' });
          const device = Buffer.from((current ?? retired).device, 'base64');
          let signed = false;
          try { signed = verify('sha256', frame, { key: device, format: 'der', type: 'spki' }, signature); } catch { signed = false; }
          if (!signed) return json(res, 403, { error: 'unlock signature rejected' });
          const issued = Number(frame.readBigInt64BE(130)), expires = Number(frame.readBigInt64BE(138));
          const nonce = frame.subarray(98, 130).toString('hex');
          if (frame.subarray(0, 8).toString('latin1') !== 'OTPSTR01' || frame[8] !== 2 || ![65, 66].includes(frame[9]) ||
              !frame.subarray(26, 58).equals(createHash('sha256').update(device).digest()) ||
              !frame.subarray(58, 90).equals(authorityFingerprint) || frame.readBigInt64BE(90) <= 0n ||
              !frame.subarray(146, 178).equals(Buffer.alloc(32)) || !(expires > issued && expires - issued <= MAX_LEASE))
            return json(res, 400, { error: 'invalid unlock' });
          if (Math.abs(time - issued) > MAX_SKEW || time >= expires + MAX_SKEW) return json(res, 409, { error: 'unlock clock mismatch' });
          if (!current) {
            // Retired: a signed REVOKED echoing this exact request (fresh nonce), with zero times and share.
            const revoked = Buffer.from(frame); revoked[8] = 5; revoked.fill(0, 130, 178);
            return json(res, 410, { error: 'share retired', frame: revoked.toString('base64'), signature: sign('sha256', revoked, authorityKey).toString('base64') });
          }
          if (current.nonces.some(n => n.nonce === nonce)) return json(res, 409, { error: 'unlock replayed' });
          // Replay protection must cover every nonce until its request window closes, so a full
          // set refuses new unlocks instead of evicting a still-valid nonce.
          const unexpired = current.nonces.filter(n => n.until > time);
          if (unexpired.length >= MAX_NONCES) { res.setHeader('Retry-After', '60'); return json(res, 429, { error: 'too many unlocks' }); }
          const nonces = [...unexpired, { nonce, until: expires + MAX_SKEW }];
          const value = { ...current, checked: time, nonces };
          commit({ ...state, shares: { ...state.shares, [room]: value } }, { t: 'share', room, value });
          const grant = Buffer.from(frame); grant[8] = 3; Buffer.from(current.share, 'base64url').copy(grant, 146);
          const response = { frame: grant.toString('base64'), signature: sign('sha256', grant, authorityKey).toString('base64') };
          grant.fill(0);
          return json(res, 200, response);
        }
        // Enrollment: store a share once, bound to the device key that may unlock it.
        if (!data || typeof data.share !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(data.share) ||
            !Number.isSafeInteger(data.deadline) || data.deadline < MIN_DEADLINE || data.deadline > MAX_DEADLINE ||
            typeof data.device !== 'string' || data.device.length > 200)
          return json(res, 400, { error: 'invalid share' });
        try { createPublicKey({ key: Buffer.from(data.device, 'base64'), format: 'der', type: 'spki' }); }
        catch { return json(res, 400, { error: 'invalid device key' }); }
        if (state.retired[room]) return json(res, 410, { error: 'share retired' }); // Never re-enrolled.
        const current = state.shares[room]; // Recheck after the async body read; a share is never replaced.
        if (current) {
          const same = current.share === data.share && current.device === data.device && current.deadline === data.deadline;
          return json(res, same ? 200 : 409, same ? { share: 'stored' } : { error: 'share exists' });
        }
        if (Object.keys(state.shares).length >= maxShares) return json(res, 429, { error: 'share capacity reached' });
        commit({ ...state, shares: { ...state.shares, [room]: { share: data.share, deadline: data.deadline, checked: now(), device: data.device, nonces: [] } } });
        return json(res, 201, { share: 'stored' });
      }
      if (req.method === 'DELETE') {
        if (!scoped) return json(res, 403, { error: 'chat credential required' });
        commit({ ...state, access: { ...state.access, [room]: { ...state.access[room], revoked: true } },
          messages: state.messages.filter(m => m.room !== room) });
        return json(res, 200, { access: 'revoked' });
      }
      clean();
      if (req.method === 'GET') {
        const after = Number(url.searchParams.get('after') || 0);
        if (!Number.isSafeInteger(after) || after < 0) return json(res, 400, { error: 'bad cursor' });
        const limit = Number(url.searchParams.get('limit') || 50);
        if (!Number.isInteger(limit) || limit < 1 || limit > 50) return json(res, 400, { error: 'bad page size' });
        return json(res, 200, { protocol: 2, access: scoped ? 'chat' : 'shared', strict: strictClaims, epoch: state.epoch,
          messages: state.messages.filter(m => m.room === room && m.seq > after).slice(0, limit)
            .map(({ seq, packet }) => ({ seq, packet })) });
      }
      if (req.method !== 'POST') { res.setHeader('Allow', 'GET, POST'); return json(res, 405, { error: 'method not allowed' }); }
      if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] || ''))
        return json(res, 415, { error: 'JSON content type required' });
      let data;
      try { data = JSON.parse(await readBody(req)); }
      catch (e) {
        if (e?.status) { res.setHeader('Connection', 'close'); res.once('finish', () => req.destroy()); }
        return json(res, e?.status || 400, { error: 'invalid request' });
      }
      if (!data || typeof data.packet !== 'string' || data.packet.length > 12000 ||
          !/^[A-Za-z0-9+/_=-]+$/.test(data.packet)) return json(res, 400, { error: 'invalid packet' });
      // The body read yielded: a concurrent claim/revocation must also block this write.
      const current = state.access[room];
      if (current && (!bearer || !equalHash(current.hash, credentialHash(bearer))))
        return json(res, 401, { error: 'unauthorized' });
      if (current?.revoked) return json(res, 410, { error: 'chat revoked' });
      // Reject permissive Node base64 decoding ambiguities before deduplication.
      const canonical = Buffer.from(data.packet, 'base64').toString('base64');
      if (canonical.replace(/=+$/, '') !== data.packet.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, ''))
        return json(res, 400, { error: 'invalid packet encoding' });
      data.packet = canonical;
      clean();
      const id = digest(data.packet);
      const existing = state.messages.find(m => m.room === room && digest(m.packet) === id);
      if (existing) return json(res, 200, { seq: existing.seq, duplicate: true });
      if (state.messages.filter(m => m.room === room).length >= maxRoom || state.messages.length >= maxMessages)
        return json(res, 429, { error: 'mailbox capacity reached' });
      if (state.seq === Number.MAX_SAFE_INTEGER) return json(res, 503, { error: 'cursor exhausted' });
      const seq = state.seq + 1;
      const message = { room, seq, packet: data.packet, time: now() };
      commit({ ...state, seq, messages: [...state.messages, message] }, { t: 'msg', m: message });
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
  server.maxConnections = 128;
  server.keepAliveTimeout = 5000;
  return server;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const server = makeServer();
  server.listen(Number(process.env.PORT || 8080), process.env.HOST || '0.0.0.0', () => console.log('Relay ready'));
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
    server.close(() => process.exit(0));
    setTimeout(() => { server.closeAllConnections(); process.exit(1); }, 5000).unref();
  });
}
