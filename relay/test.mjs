import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createCipheriv, createDecipheriv, randomBytes, createHash } from 'node:crypto';
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
