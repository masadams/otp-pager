import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeServer } from './server.mjs';

test('relay accepts opaque packets and returns ordered cursors', async () => {
  const server = makeServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const base = 'http://127.0.0.1:' + server.address().port;
    const path = '/v1/mailbox/' + 'a'.repeat(64);
    const send = await fetch(base + path, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ packet: 'YWJjZA==' }) });
    assert.equal(send.status, 201);
    const received = await (await fetch(base + path + '?after=0')).json();
    assert.equal(received.messages.length, 1);
    assert.equal(received.messages[0].packet, 'YWJjZA==');
    const empty = await (await fetch(base + path + '?after=1')).json();
    assert.deepEqual(empty.messages, []);
    assert.equal((await fetch(base + '/v1/mailbox/not-a-room')).status, 404);
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
});
