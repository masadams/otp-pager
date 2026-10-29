import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createCipheriv, createDecipheriv, randomBytes, createHash, createHmac } from 'node:crypto';
import { makeServer } from './server.mjs';

const token = 'x'.repeat(43);
const path = '/v1/mailbox/' + 'a'.repeat(64);
async function launch(options) {
  const server = makeServer({ token, ...options });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  return { server, base: 'http://127.0.0.1:' + server.address().port };
}
const stop = server => new Promise(r => { server.close(r); server.closeAllConnections(); });
const headers = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
const post = (base, packet, route = path) => fetch(base + route, { method: 'POST', headers, body: JSON.stringify({ packet }) });
const get = (base, after = 0) => fetch(base + path + '?after=' + after, { headers }).then(r => r.json());

test('authenticated opaque messages persist and cursors never reset after expiry/restart', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-')); let clock = 1000;
  let { server, base } = await launch({ dataDir, ttl: 100, now: () => clock });
  try {
    assert.equal((await fetch(base + '/health')).status, 200);
    assert.equal((await fetch(base + path)).status, 401);
    assert.equal((await fetch(base + path, { headers: { Authorization: 'Bearer wrong' } })).status, 401);
    assert.equal((await post(base, 'YWJjZA==')).status, 201);
    await stop(server);
    ({ server, base } = await launch({ dataDir, ttl: 100, now: () => clock }));
    assert.deepEqual((await get(base)).messages, [{ seq: 1, packet: 'YWJjZA==' }]);
    clock += 101;
    assert.deepEqual((await get(base)).messages, []);
    assert.equal((await post(base, 'ZWZnaA==')).status, 201);
    assert.equal((await get(base, 1)).messages[0].seq, 2);
    assert.equal((await fetch(base + path + '?after=-1', { headers })).status, 400);
    assert.equal((await post(base, '')).status, 400);
    assert.equal((await post(base, 'x'.repeat(17000))).status, 413);
    assert.equal((await fetch(base + path, { method: 'POST', headers, body: 'null' })).status, 400);
    assert.equal((await get(base, 2)).messages.length, 0);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('global and per-mailbox bounds reject excess traffic without evicting messages', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  const { server, base } = await launch({ dataDir, maxRoom: 1, maxMessages: 2 });
  try {
    assert.equal((await post(base, 'YQ==')).status, 201);
    assert.equal((await post(base, 'Yg==')).status, 429);
    assert.equal((await post(base, 'Yg==', '/v1/mailbox/' + 'b'.repeat(64))).status, 201);
    assert.equal((await post(base, 'Yw==', '/v1/mailbox/' + 'c'.repeat(64))).status, 429);
    assert.equal((await get(base)).messages.length, 1);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('station A/B AES-GCM packets match Android framing and detect tampering', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  const { server, base } = await launch({ dataDir });
  const master = randomBytes(32);
  const key = role => createHash('sha256').update(Buffer.concat([Buffer.from('key:' + role), master])).digest();
  try {
    let cursor = 0;
    for (const role of ['A', 'B']) {
      const text = 'RENDEZVOUS AT 2200 — ' + role;
      const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', key(role), iv);
      cipher.setAAD(Buffer.from(role));
      const packet = Buffer.concat([Buffer.from(role), iv, cipher.update(text), cipher.final(), cipher.getAuthTag()]);
      assert.equal((await post(base, packet.toString('base64'))).status, 201);
      const message = (await get(base, cursor)).messages[0]; cursor = message.seq;
      const wire = Buffer.from(message.packet, 'base64');
      const decode = buf => {
        const decipher = createDecipheriv('aes-256-gcm', key(role), buf.subarray(1, 13));
        decipher.setAAD(buf.subarray(0, 1)); decipher.setAuthTag(buf.subarray(-16));
        return Buffer.concat([decipher.update(buf.subarray(13, -16)), decipher.final()]).toString();
      };
      assert.equal(decode(wire), text);
      wire[14] ^= 1; assert.throws(() => decode(wire));
    }
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('missing token or corrupt disk state fails closed', () => {
  assert.throws(() => makeServer({ token: '' }), /RELAY_TOKEN/);
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  try {
    writeFileSync(join(dataDir, 'mailboxes.json'), 'broken');
    assert.throws(() => makeServer({ token, dataDir }));
  } finally { rmSync(dataDir, { recursive: true }); }
});

test('retries deduplicate across restart, normalize encoding, and bypass full-mailbox rejection', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  let { server, base } = await launch({ dataDir, maxRoom: 1 });
  try {
    const first = await (await post(base, 'YQ==')).json();
    assert.equal(first.seq, 1);
    assert.equal((await post(base, 'YQ')).status, 200);
    await stop(server); ({ server, base } = await launch({ dataDir, maxRoom: 1 }));
    const duplicate = await (await post(base, 'YQ==')).json();
    assert.deepEqual(duplicate, { seq: 1, duplicate: true });
    assert.equal((await get(base)).messages.length, 1);
    assert.equal((await post(base, 'Yg==')).status, 429);
    assert.equal((await post(base, 'Y')).status, 400);
    assert.equal((await post(base, 'YQ=garbage')).status, 400);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('pagination, storage epoch migration, and per-peer request budgets', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  writeFileSync(join(dataDir, 'mailboxes.json'), JSON.stringify({ version: 1, seq: 42, messages: [] }));
  let { server, base } = await launch({ dataDir });
  try {
    for (let i = 0; i < 55; i++) assert.equal((await post(base, Buffer.from('packet-' + i).toString('base64'))).status, 201);
    const page = await get(base);
    assert.equal(page.protocol, 2); assert.equal(page.messages.length, 50); assert.equal(page.messages[0].seq, 43);
    assert.equal((await get(base, page.messages.at(-1).seq)).messages.length, 5);
    const epoch = page.epoch;
    await stop(server); ({ server, base } = await launch({ dataDir, rateLimit: 2 }));
    assert.equal((await get(base)).epoch, epoch);
    assert.equal((await fetch(base + path, { headers })).status, 200);
    assert.equal((await fetch(base + path, { headers })).status, 429);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('write failure refuses acknowledgement and fails health until restart', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  const { server, base } = await launch({ dataDir });
  try {
    mkdirSync(join(dataDir, 'mailboxes.json.tmp'));
    rmSync(join(dataDir, 'journal.log')); mkdirSync(join(dataDir, 'journal.log')); // Journal appends fail too.
    assert.equal((await post(base, 'YQ==')).status, 503);
    assert.equal((await fetch(base + '/health')).status, 503);
    assert.equal((await fetch(base + path, { headers })).status, 503);
    assert.equal(JSON.parse(readFileSync(join(dataDir, 'mailboxes.json'))).seq, 0);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('shared protocol-v2 fixture decrypts after relay round trip', async () => {
  const fixture = Object.fromEntries(readFileSync(new URL('../test-vectors/protocol-v2.properties', import.meta.url), 'utf8')
    .split('\n').filter(l => l && !l.startsWith('#')).map(l => [l.slice(0,l.indexOf('=')), l.slice(l.indexOf('=')+1)]));
  const { createHmac } = await import('node:crypto');
  const prk = createHmac('sha256', 'OTP Pager / protocol v2').update(Buffer.from(fixture.master, 'hex')).digest();
  const key = createHmac('sha256', prk).update(Buffer.concat([Buffer.from('message:A'), Buffer.from([1])])).digest();
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  const { server, base } = await launch({ dataDir });
  try {
    assert.equal((await post(base, fixture.wire)).status, 201);
    const wire = Buffer.from((await get(base)).messages[0].packet, 'base64');
    const decipher = createDecipheriv('aes-256-gcm', key, wire.subarray(26,38));
    decipher.setAAD(wire.subarray(0,38)); decipher.setAuthTag(wire.subarray(-16));
    assert.equal(Buffer.concat([decipher.update(wire.subarray(38,-16)),decipher.final()]).toString(),fixture.payload);
    assert.equal(Number(wire.readBigInt64BE(2)),7);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});


test('finite-pad v3 reference frame survives relay persistence and exact retries', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-pad-'));
  let { server, base } = await launch({ dataDir });
  try {
    // Independent Node implementation of the Kotlin/Python known-answer frame.
    const pad = Buffer.alloc(1048576);
    for (let i = 0; i < pad.length; i++) pad[i] = (i * 37 + Math.floor(i / 255)) & 255;
    const header = Buffer.alloc(26); header[0] = 3; header[1] = 65; header.writeBigInt64BE(1n, 2);
    createHash('sha256').update(pad).digest().copy(header, 10, 0, 16);
    const clear = Buffer.alloc(4096); const text = Buffer.from('Hello 世界');
    clear.writeUInt16BE(text.length); text.copy(clear, 2);
    const cipher = Buffer.from(clear.map((b, i) => b ^ pad[i]));
    const body = Buffer.concat([header, cipher]);
    const tag = createHmac('sha256', pad.subarray(4096, 4128)).update(body).digest();
    const bytes = Buffer.concat([body, tag]);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), 'cb826c251bc79df335b2f00f4dbd81e35a1df011f552315040a8e9e56dc4ea5a');
    const packet = bytes.toString('base64');
    assert.equal((await post(base, packet)).status, 201);
    await stop(server); ({ server, base } = await launch({ dataDir }));
    assert.equal((await post(base, packet)).status, 200);
    const received = (await get(base)).messages;
    assert.equal(received.length, 1); assert.equal(received[0].packet, packet);
    pad.fill(0); clear.fill(0);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('chat claims isolate credentials, preserve traffic, persist only hashes, and cannot be replaced', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-scope-'));
  let { server, base } = await launch({ dataDir });
  const cap = 'c'.repeat(43), other = 'd'.repeat(43), second = '/v1/mailbox/' + 'b'.repeat(64);
  const scoped = value => ({ Authorization: 'Bearer ' + value, 'Content-Type': 'application/json' });
  const claim = (route, capability) => fetch(base + route + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability }) });
  try {
    assert.equal((await post(base, 'YQ==')).status, 201);
    assert.equal((await claim(path, cap)).status, 201);
    assert.equal((await claim(path, cap)).status, 200); // Peer/retry is idempotent.
    assert.equal((await claim(path, other)).status, 403);
    assert.equal((await fetch(base + path, { headers })).status, 401); // Shared setup token is no longer traffic access.
    assert.equal((await post(base, 'Yg==')).status, 401);
    assert.equal((await fetch(base + path, { headers: scoped(other) })).status, 401);
    let page = await (await fetch(base + path, { headers: scoped(cap) })).json();
    assert.equal(page.access, 'chat'); assert.deepEqual(page.messages, [{ seq: 1, packet: 'YQ==' }]);
    assert.equal((await claim(second, other)).status, 201);
    assert.equal((await fetch(base + second, { headers: scoped(cap) })).status, 401);
    assert.equal((await fetch(base + path, { method: 'POST', headers: scoped(cap), body: JSON.stringify({ packet: 'Yg==' }) })).status, 201);
    const disk = readFileSync(join(dataDir, 'mailboxes.json'), 'utf8');
    assert.equal(JSON.parse(disk).version, 2);
    assert.equal(disk.includes(cap), false); assert.equal(disk.includes(token), false);
    await stop(server); ({ server, base } = await launch({ dataDir }));
    page = await (await fetch(base + path, { headers: scoped(cap) })).json();
    assert.equal(page.messages.length, 2);
    assert.equal((await fetch(base + path, { headers })).status, 401);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('revocation survives expiry/restart and cannot be reopened with the setup token', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-revoke-')); let clock = 1000;
  let { server, base } = await launch({ dataDir, now: () => clock, ttl: 10 });
  const cap = 'r'.repeat(43), ch = { Authorization: 'Bearer ' + cap, 'Content-Type': 'application/json' };
  const claim = () => fetch(base + path + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability: cap }) });
  try {
    assert.equal((await claim()).status, 201);
    assert.equal((await fetch(base + path, { method: 'POST', headers: ch, body: JSON.stringify({ packet: 'YQ==' }) })).status, 201);
    assert.equal((await fetch(base + path, { method: 'DELETE', headers })).status, 401);
    assert.equal((await fetch(base + path, { method: 'DELETE', headers: ch })).status, 200);
    assert.deepEqual(JSON.parse(readFileSync(join(dataDir, 'mailboxes.json'))).messages, []);
    clock += 100;
    await stop(server); ({ server, base } = await launch({ dataDir, now: () => clock, ttl: 10 }));
    assert.equal((await fetch(base + path, { headers: ch })).status, 410);
    assert.equal((await fetch(base + path, { method: 'POST', headers: ch, body: JSON.stringify({ packet: 'Yg==' }) })).status, 410);
    assert.equal((await claim()).status, 410);
    assert.equal((await fetch(base + path, { headers })).status, 401);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('claim capacity and malformed protected storage fail closed without erasing claims', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-claim-bound-'));
  const { server, base } = await launch({ dataDir, maxClaims: 1 });
  try {
    for (const [route, expected] of [[path, 201], ['/v1/mailbox/' + 'b'.repeat(64), 429]]) {
      assert.equal((await fetch(base + route + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability: 'q'.repeat(43) }) })).status, expected);
    }
    await stop(server);
    const s = JSON.parse(readFileSync(join(dataDir, 'mailboxes.json'))); delete s.access;
    writeFileSync(join(dataDir, 'mailboxes.json'), JSON.stringify(s));
    assert.throws(() => makeServer({ token, dataDir }), /access state/);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('a slow POST cannot cross a concurrent claim or revocation', async () => {
  const { request } = await import('node:http');
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-claim-race-'));
  const { server, base } = await launch({ dataDir });
  const cap = 'k'.repeat(43), ch = { Authorization: 'Bearer ' + cap, 'Content-Type': 'application/json' };
  async function slowPost(auth) {
    const body = JSON.stringify({ packet: 'YQ==' });
    const accepted = new Promise(r => server.once('request', r));
    let resolve;
    const result = new Promise(r => { resolve = r; });
    const req = request(base + path, { method: 'POST', headers: { ...auth, 'Content-Length': Buffer.byteLength(body) } }, res => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
    req.on('error', error => resolve(error)); req.write(body.slice(0, 5));
    await accepted;
    return () => { req.end(body.slice(5)); return result; };
  }
  try {
    const finishLegacy = await slowPost(headers);
    assert.equal((await fetch(base + path + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability: cap }) })).status, 201);
    assert.equal(await finishLegacy(), 401);
    const finishScoped = await slowPost(ch);
    assert.equal((await fetch(base + path, { method: 'DELETE', headers: ch })).status, 200);
    assert.equal(await finishScoped(), 410);
    assert.equal(JSON.parse(readFileSync(join(dataDir, 'mailboxes.json'))).messages.length, 0);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('strict claims: the setup token can create claims but never touch mailbox traffic', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  const { server, base } = await launch({ dataDir, strictClaims: true });
  const capability = 'c'.repeat(43);
  const chat = { Authorization: 'Bearer ' + capability, 'Content-Type': 'application/json' };
  try {
    // Unclaimed mailbox: no legacy shared access at all.
    assert.equal((await fetch(base + path, { headers })).status, 401);
    assert.equal((await post(base, 'YWJjZA==')).status, 401);
    assert.equal((await fetch(base + path, { method: 'DELETE', headers })).status, 401);
    // The setup token still provisions a claim; the chat credential then works.
    const claim = await fetch(base + path + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability }) });
    assert.equal(claim.status, 201);
    assert.equal((await fetch(base + path, { method: 'POST', headers: chat, body: JSON.stringify({ packet: 'YWJjZA==' }) })).status, 201);
    const page = await fetch(base + path, { headers: chat }).then(r => r.json());
    assert.equal(page.access, 'chat'); assert.equal(page.strict, true);
    assert.deepEqual(page.messages, [{ seq: 1, packet: 'YWJjZA==' }]);
    // The setup token is still refused on the claimed mailbox, and a second claim cannot replace it.
    assert.equal((await fetch(base + path, { headers })).status, 401);
    const other = await fetch(base + path + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability: 'd'.repeat(43) }) });
    assert.equal(other.status, 403);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('strict claims is off by default and reported to clients', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  const { server, base } = await launch({ dataDir });
  try {
    const page = await fetch(base + path, { headers }).then(r => r.json());
    assert.equal(page.access, 'shared'); assert.equal(page.strict, false);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('strict-chat shares: device-signed unlocks get short signed grants and lapse without check-in', async () => {
  const { generateKeyPairSync, sign: signWith, verify: verifyWith, createPublicKey } = await import('node:crypto');
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-')); let clock = 1_800_000_000_000;
  let { server, base } = await launch({ dataDir, now: () => clock });
  const room = 'd'.repeat(64), capability = 'c'.repeat(43), share = Buffer.alloc(32, 7).toString('base64url');
  const scoped = { Authorization: 'Bearer ' + capability, 'Content-Type': 'application/json' };
  const deviceKeys = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const device = deviceKeys.publicKey.export({ type: 'spki', format: 'der' });
  const sha = b => createHash('sha256').update(b).digest();
  const enroll = (body, h = scoped) => fetch(base + '/v1/share/' + room, { method: 'PUT', headers: h, body: JSON.stringify(body) });
  const authority = async () => Buffer.from((await (await fetch(base + '/v1/strict/authority')).json()).key, 'base64');
  const frame = (auth, { issued = clock, expires = clock + 60000, nonce = randomBytes(32), kind = 2, dev = device } = {}) => {
    const f = Buffer.alloc(178); f.write('OTPSTR01', 0, 'latin1'); f[8] = kind; f[9] = 65;
    randomBytes(16).copy(f, 10); sha(dev).copy(f, 26); sha(auth).copy(f, 58); f.writeBigInt64BE(1n, 90);
    nonce.copy(f, 98); f.writeBigInt64BE(BigInt(issued), 130); f.writeBigInt64BE(BigInt(expires), 138); return f;
  };
  const unlock = (f, key = deviceKeys.privateKey) => fetch(base + '/v1/share/' + room + '/unlock', { method: 'POST', headers: scoped,
    body: JSON.stringify({ frame: f.toString('base64'), signature: signWith('sha256', f, key).toString('base64') }) });
  try {
    const auth = await authority();
    assert.equal(auth.length > 60, true);
    const body = { share, deadline: 2 * 3600000, device: device.toString('base64') };
    assert.equal((await enroll(body, headers)).status, 403); // The setup token never enrolls or unlocks.
    assert.equal((await fetch(base + '/v1/mailbox/' + room + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability }) })).status, 201);
    assert.equal((await enroll({ ...body, deadline: 1000 })).status, 400);
    assert.equal((await enroll({ ...body, device: 'bm90IGEga2V5' })).status, 400);
    assert.equal((await enroll(body)).status, 201);
    assert.equal((await enroll(body)).status, 200);
    assert.equal((await enroll({ ...body, share: Buffer.alloc(32, 8).toString('base64url') })).status, 409);
    assert.ok(!readFileSync(join(dataDir, 'mailboxes.json'), 'utf8').includes(capability));

    // A valid device-signed request returns the share inside a grant signed by the authority.
    const request = frame(auth);
    const reply = await unlock(request); assert.equal(reply.status, 200);
    const { frame: g, signature } = await reply.json(); const grant = Buffer.from(g, 'base64');
    assert.ok(verifyWith('sha256', grant, createPublicKey({ key: auth, format: 'der', type: 'spki' }), Buffer.from(signature, 'base64')));
    assert.equal(grant[8], 3); assert.deepEqual(grant.subarray(9, 146), request.subarray(9, 146));
    assert.deepEqual(grant.subarray(146), Buffer.alloc(32, 7));
    // Replay, wrong signer, long lease, clock mismatch, wrong authority or device, and tampering all fail.
    assert.equal((await unlock(request)).status, 409);
    assert.equal((await unlock(frame(auth), generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey)).status, 403);
    assert.equal((await unlock(frame(auth, { expires: clock + 60001 }))).status, 400);
    assert.equal((await unlock(frame(auth, { issued: clock - 400000, expires: clock - 340000 }))).status, 409);
    assert.equal((await unlock(frame(Buffer.from('other')))).status, 400);
    assert.equal((await unlock(frame(auth, { kind: 3 }))).status, 400);
    const tampered = frame(auth); const sig = signWith('sha256', tampered, deviceKeys.privateKey); tampered[100] ^= 1;
    assert.equal((await fetch(base + '/v1/share/' + room + '/unlock', { method: 'POST', headers: scoped,
      body: JSON.stringify({ frame: tampered.toString('base64'), signature: sig.toString('base64') }) })).status, 403);

    // Unlocks are check-ins; the authority key survives restart; silence past the deadline discards the share.
    clock += 90 * 60000; assert.equal((await unlock(frame(auth))).status, 200);
    await stop(server); ({ server, base } = await launch({ dataDir, now: () => clock }));
    assert.deepEqual(await authority(), auth);
    clock += 90 * 60000; assert.equal((await unlock(frame(auth))).status, 200);
    // Silence past the deadline retires the share: gone from every live relay file, answered with
    // a signed REVOKED that echoes the request, and never re-enrolled.
    clock += 2 * 3600000 + 1;
    const late = frame(auth); const gone = await unlock(late); assert.equal(gone.status, 410);
    const revoked = await gone.json(); const rf = Buffer.from(revoked.frame, 'base64');
    assert.ok(verifyWith('sha256', rf, createPublicKey({ key: auth, format: 'der', type: 'spki' }), Buffer.from(revoked.signature, 'base64')));
    assert.equal(rf[8], 5); assert.deepEqual(rf.subarray(9, 130), late.subarray(9, 130)); assert.deepEqual(rf.subarray(130), Buffer.alloc(48));
    const files = () => readFileSync(join(dataDir, 'mailboxes.json'), 'utf8') + readFileSync(join(dataDir, 'journal.log'), 'utf8');
    assert.ok(!files().includes(share), 'expired share still in a live relay file');
    assert.equal((await enroll(body)).status, 410);
    assert.equal((await unlock(frame(auth), generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey)).status, 403); // REVOKED only for the enrolled device.
    await stop(server); ({ server, base } = await launch({ dataDir, now: () => clock }));
    assert.equal((await unlock(frame(auth))).status, 410); // The tombstone survives restart.
    assert.equal((await enroll(body)).status, 410);
    assert.equal((await fetch(base + '/v1/share/' + room, { method: 'GET', headers: scoped })).status, 405);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('journal: posts append without rewriting the snapshot, survive restart and torn tails, and compact', async () => {
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-'));
  let { server, base } = await launch({ dataDir, compactEvery: 5 });
  const snapshot = () => JSON.parse(readFileSync(join(dataDir, 'mailboxes.json'), 'utf8'));
  try {
    assert.equal((await post(base, 'YQ==')).status, 201);
    assert.equal((await post(base, 'Yg==')).status, 201);
    assert.equal(snapshot().seq, 0); // Only the journal grew.
    writeFileSync(join(dataDir, 'journal.log'), readFileSync(join(dataDir, 'journal.log'), 'utf8') + '{"t":"msg","m":{"room":"'); // Torn append.
    await stop(server); ({ server, base } = await launch({ dataDir, compactEvery: 5 }));
    assert.deepEqual((await get(base)).messages.map(m => m.seq), [1, 2]);
    assert.equal((await post(base, 'aA==')).status, 201); await stop(server); ({ server, base } = await launch({ dataDir, compactEvery: 5 }));
    assert.deepEqual((await get(base)).messages.map(m => m.seq), [1, 2, 3]); // Nothing appended after the torn line was lost.
    for (const p of ['Yw==', 'ZA==', 'ZQ==', 'Zg==', 'aQ==']) assert.equal((await post(base, p)).status, 201);
    assert.ok(snapshot().seq >= 4); // Compacted into a new snapshot generation.
    // A journal older than the snapshot is never replayed (crash between snapshot and journal reset).
    const stale = JSON.stringify({ gen: snapshot().journal - 1 }) + '\n' + JSON.stringify({ t: 'msg', m: { room: 'a'.repeat(64), seq: 999, packet: 'eA==', time: Date.now() } }) + '\n';
    await stop(server); writeFileSync(join(dataDir, 'journal.log'), stale);
    ({ server, base } = await launch({ dataDir, compactEvery: 5 }));
    assert.ok((await get(base)).messages.every(m => m.seq !== 999));
    assert.equal((await post(base, 'Zw==')).status, 201);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});

test('strict-chat shares: deletion retires for good, replay protection never evicts a live nonce, unknown rooms stay 404', async () => {
  const { generateKeyPairSync, sign: signWith } = await import('node:crypto');
  const dataDir = mkdtempSync(join(tmpdir(), 'pager-')); let clock = 1_800_000_000_000;
  const { server, base } = await launch({ dataDir, now: () => clock });
  const room = 'e'.repeat(64), capability = 'f'.repeat(43), share = Buffer.alloc(32, 9).toString('base64url');
  const scoped = { Authorization: 'Bearer ' + capability, 'Content-Type': 'application/json' };
  const keys = generateKeyPairSync('ec', { namedCurve: 'P-256' }); const device = keys.publicKey.export({ type: 'spki', format: 'der' });
  const sha = b => createHash('sha256').update(b).digest();
  const auth = Buffer.from((await (await fetch(base + '/v1/strict/authority')).json()).key, 'base64');
  const frame = (nonce = randomBytes(32)) => {
    const f = Buffer.alloc(178); f.write('OTPSTR01', 0, 'latin1'); f[8] = 2; f[9] = 65; randomBytes(16).copy(f, 10);
    sha(device).copy(f, 26); sha(auth).copy(f, 58); f.writeBigInt64BE(1n, 90); nonce.copy(f, 98);
    f.writeBigInt64BE(BigInt(clock), 130); f.writeBigInt64BE(BigInt(clock + 60000), 138); return f;
  };
  const unlock = (f, r = room) => fetch(base + '/v1/share/' + r + '/unlock', { method: 'POST', headers: scoped,
    body: JSON.stringify({ frame: f.toString('base64'), signature: signWith('sha256', f, keys.privateKey).toString('base64') }) });
  const enroll = () => fetch(base + '/v1/share/' + room, { method: 'PUT', headers: scoped, body: JSON.stringify({ share, deadline: 2 * 3600000, device: device.toString('base64') }) });
  try {
    assert.equal((await fetch(base + '/v1/mailbox/' + room + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability }) })).status, 201);
    assert.equal((await enroll()).status, 201);
    // 32 live nonces fill the window; the 33rd is refused rather than evicting the first, so the
    // first accepted request still can't be replayed.
    const first = frame(); assert.equal((await unlock(first)).status, 200);
    for (let i = 0; i < 31; i++) assert.equal((await unlock(frame())).status, 200);
    assert.equal((await unlock(frame())).status, 429);
    assert.equal((await unlock(first)).status, 409);
    clock += 6 * 60000; assert.equal((await unlock(frame())).status, 200); // Expired nonces free the window.
    // Deleting retires the room: no share left in live files, signed REVOKED, no re-enrollment.
    assert.equal((await fetch(base + '/v1/share/' + room, { method: 'DELETE', headers: scoped })).status, 200);
    assert.ok(!(readFileSync(join(dataDir, 'mailboxes.json'), 'utf8') + readFileSync(join(dataDir, 'journal.log'), 'utf8')).includes(share));
    assert.equal((await unlock(frame())).status, 410);
    assert.equal((await enroll()).status, 410);
    // A room the relay never knew is 404, which phones must not treat as retirement.
    const other = 'b'.repeat(64);
    assert.equal((await fetch(base + '/v1/mailbox/' + other + '/claim', { method: 'POST', headers, body: JSON.stringify({ capability: 'g'.repeat(43) }) })).status, 201);
    assert.equal((await fetch(base + '/v1/share/' + other + '/unlock', { method: 'POST', headers: { ...scoped, Authorization: 'Bearer ' + 'g'.repeat(43) },
      body: JSON.stringify({ frame: frame().toString('base64'), signature: 'AAAAAAAAAAA=' }) })).status, 404);
  } finally { await stop(server); rmSync(dataDir, { recursive: true }); }
});
