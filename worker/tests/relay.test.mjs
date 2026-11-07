import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { build } from 'esbuild';
import { Miniflare } from 'miniflare';

const TOKEN_A = 'a'.repeat(43), TOKEN_A2 = 'c'.repeat(43), TOKEN_B = 'b'.repeat(43);
const START = 1_794_000_000_000, TTL = 600_000, CHUNK = 8 * 1024 * 1024;
const prefix = '__relay/v1/';
const sha = data => createHash('sha256').update(data).digest('hex');
let prod, harness, db, bucket, pdb;
let control, controlUrl;
const putGates = new Map();
async function runtime(entry, directory) {
  const bundle = await build({ entryPoints: [entry], bundle: true, write: false, format: 'esm', target: 'es2022', platform: 'browser',
    define: { TEST_PUT_CONTROL: JSON.stringify(controlUrl) } });
  await mkdir(directory, { recursive: true });
  const mf = new Miniflare({ workers: [{ config: { name: 'relay',
    compatibilityDate: '2025-09-01', compatibilityFlags: ['nodejs_compat'],
    manifest: { mainModule: 'index.js', modules: { 'index.js': { type: 'esm', contents: bundle.outputFiles[0].text } } },
    env: { DB: { type: 'd1', id: 'relay-test-db' }, AUDIO: { type: 'r2', name: 'relay-audio' },
      AUTH_DEV_TOKENS: { type: 'text', value: '' }, GOOGLE_CLIENT_ID: { type: 'text', value: '' },
      PROTOCOL_VERSION: { type: 'text', value: '1' } } } }], resourcePersistencePath: directory });
  const d1 = await mf.getD1Database('DB');
  // Execute real schema, not a SQL mock. D1 exec() expects one statement per line.
  const schema = (await readFile('schema.sql', 'utf8')).replace(/--[^\n]*/g, '');
  for (const sql of schema.split(';').map(s => s.trim()).filter(Boolean)) await d1.prepare(sql).run();
  for (const [token, owner, device] of [[TOKEN_A, 'account-a', 'tablet'], [TOKEN_A2, 'account-a', 'phone'], [TOKEN_B, 'account-b', 'other']]) {
    await d1.prepare('INSERT INTO sessions (token, ownerId, deviceId, deviceName, createdAt, lastSeenAt) VALUES (?, ?, ?, ?, ?, ?)')
      .bind(token, owner, device, device, Date.now(), Date.now()).run();
  }
  return [mf, d1];
}
before(async () => {
  control = createServer((request, response) => {
    const gate = putGates.get(request.url.slice(1));
    if (!gate) { response.writeHead(404).end(); return; }
    gate.arrive(response);
  });
  await new Promise((resolveListen, reject) => { control.once('error', reject); control.listen(0, '127.0.0.1', resolveListen); });
  controlUrl = `http://127.0.0.1:${control.address().port}`;
  const root = resolve('.wrangler', `r${process.pid}`);
  [prod, pdb] = await runtime('src/index.ts', resolve(root, 'p'));
  [harness, db] = await runtime('tests/relay-harness.ts', resolve(root, 'h'));
  bucket = await harness.getR2Bucket('AUDIO');
});
after(async () => {
  for (const gate of putGates.values()) gate.release(START + TTL);
  await Promise.all([prod?.dispose(), harness?.dispose()]);
  control?.closeAllConnections();
  if (control) await new Promise(resolveClose => control.close(resolveClose));
});

async function call(mf, method, path, { token = TOKEN_A, now, body, headers = {} } = {}) {
  if (token) headers = { ...headers, authorization: `Bearer ${token}` };
  if (now !== undefined) headers = { ...headers, 'x-test-now': String(now) };
  if (body && !ArrayBuffer.isView(body) && typeof body !== 'string') {
    body = JSON.stringify(body); headers['content-type'] = 'application/json';
  }
  const response = await mf.dispatchFetch(`http://relay.test${path}`, { method, body, headers });
  const bytes = Buffer.from(await response.arrayBuffer());
  return { status: response.status, headers: response.headers, bytes,
    json: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(bytes.toString()) : null };
}
const checked = (result, code = 200) => { assert.equal(result.status, code, JSON.stringify(result.json)); return result.json; };
function meta(data, now, extra = {}) {
  return { requestId: randomUUID(), requestedAt: now, kind: 'audio', entityId: randomUUID(), sha256: sha(data),
    bytes: data.length, mime: 'audio/mp4', originalName: 'Lezione.m4a', deviceId: 'tablet', deviceName: 'Tablet', ...extra };
}
async function begin(mf, data, now, extra = {}) {
  const m = meta(data, now, extra);
  const row = checked(await call(mf, 'POST', '/v1/relay', { now, body: m }), 201);
  return [row, m];
}
async function put(mf, row, index, data, now, extra = {}) {
  return call(mf, 'PUT', `/v1/relay/${row.transferId}/chunks/${index}`,
    { now, body: data, headers: { 'x-chunk-sha256': sha(data), ...(extra.headers ?? {}) }, ...extra });
}
async function finish(mf, row, now) {
  return call(mf, 'POST', `/v1/relay/${row.transferId}/commit`, { now });
}
async function clean(now, extra = {}) { return call(harness, 'POST', '/__test/cleanup', { now, ...extra }); }
async function reset() {
  await db.batch([db.prepare('DELETE FROM relay_chunks'), db.prepare('DELETE FROM relay_transfers')]);
  const list = await bucket.list({ prefix });
  if (list.objects.length) await bucket.delete(list.objects.map(o => o.key));
}
function pausedPut(row, data, now, phase) {
  const id = randomUUID();
  let arrived, response;
  const entered = new Promise(resolveEntered => { arrived = resolveEntered; });
  const gate = {
    arrive(r) { response = r; arrived(); },
    release(at) { response?.end(JSON.stringify({ now: at })); putGates.delete(id); },
  };
  putGates.set(id, gate);
  const result = put(harness, row, 0, data, now, { headers: {
    'x-chunk-sha256': sha(data), 'x-test-pause-put': phase, 'x-test-put-gate': id,
  } });
  return { entered, result, release: gate.release };
}

test('production router: same-account tablet -> phone, multi-chunk original bytes, integrity and resumability', async () => {
  const now = checked(await call(prod, 'GET', '/v1/relay')).serverTime;
  const data = Buffer.alloc(CHUNK + 31); for (let i = 0; i < data.length; i++) data[i] = i % 251;
  const [row, m] = await begin(prod, data, now);
  assert.equal(row.expiresAt - row.createdAt, TTL);
  assert.equal(row.chunkCount, 2);
  const retry = checked(await call(prod, 'POST', '/v1/relay', { body: m }));
  assert.equal(retry.transferId, row.transferId); assert.equal(retry.expiresAt, row.expiresAt);
  checked(await put(prod, row, 0, data.subarray(0, CHUNK)));
  const status = checked(await call(prod, 'GET', `/v1/relay/${row.transferId}`, { token: TOKEN_A2 }));
  assert.deepEqual(status.chunks.map(c => c.index), [0]);
  assert.equal(status.deviceId, 'tablet');
  checked(await call(prod, 'GET', `/v1/relay/${row.transferId}/chunks/0`, { token: TOKEN_A2 }), 409);
  checked(await finish(prod, row), 409);
  // Resume after interruption, and retry an acknowledged chunk without changing its expiry.
  checked(await put(prod, row, 0, data.subarray(0, CHUNK)));
  checked(await put(prod, row, 1, data.subarray(CHUNK)));
  const ready = checked(await finish(prod, row)); assert.equal(ready.state, 'ready');
  assert.equal(checked(await finish(prod, row)).expiresAt, row.expiresAt);
  const listed = checked(await call(prod, 'GET', `/v1/relay?kind=audio&sha256=${m.sha256}`, { token: TOKEN_A2 }));
  assert.equal(listed.transfers.length, 1); assert.equal(listed.transfers[0].entityId, m.entityId);
  const received = [];
  for (const part of ready.chunks) {
    const r = await call(prod, 'GET', `/v1/relay/${row.transferId}/chunks/${part.index}`, { token: TOKEN_A2 });
    assert.equal(r.status, 200); assert.equal(r.headers.get('cache-control'), 'private, no-store');
    assert.equal(r.headers.get('content-length'), String(part.bytes));
    assert.equal(r.headers.get('x-chunk-sha256'), part.sha256);
    assert.equal(r.headers.get('x-file-sha256'), m.sha256);
    assert.equal(sha(r.bytes), part.sha256); assert.equal(r.bytes.length, part.bytes); received.push(r.bytes);
  }
  const result = Buffer.concat(received); assert.deepEqual(result, data); assert.equal(sha(result), m.sha256);
  const state = await pdb.prepare('SELECT COUNT(*) AS n FROM state').first(); assert.equal(state.n, 0);
  assert.equal((await pdb.prepare('SELECT COUNT(*) AS n FROM shares').first()).n, 0);
});

test('production auth: missing, forged, guest/public token, revoked sessions and other owners cannot access payloads', async () => {
  const row = (await pdb.prepare('SELECT * FROM relay_transfers LIMIT 1').first());
  const base = `/v1/relay/${row.transferId}`;
  for (const token of [null, 'unknown', `pg_${'x'.repeat(32)}`, 'sometokenfrompublicshare']) {
    for (const [method, path] of [['GET', '/v1/relay'], ['GET', base], ['GET', `${base}/chunks/0`], ['POST', `${base}/commit`], ['PUT', `${base}/chunks/0`], ['DELETE', base], ['POST', '/v1/relay']]) {
      checked(await call(prod, method, path, { token }), 401);
    }
  }
  assert.deepEqual(checked(await call(prod, 'GET', '/v1/relay', { token: TOKEN_B })).transfers, []);
  for (const [method, path] of [['GET', base], ['GET', `${base}/chunks/0`], ['PUT', `${base}/chunks/0`], ['POST', `${base}/commit`]]) {
    checked(await call(prod, method, path, { token: TOKEN_B }), 404);
  }
  checked(await call(prod, 'DELETE', base, { token: TOKEN_B }), 202);
  assert.equal(checked(await call(prod, 'GET', base)).state, 'ready');
  await pdb.prepare('UPDATE sessions SET revokedAt = ? WHERE token = ?').bind(Date.now(), TOKEN_A2).run();
  checked(await call(prod, 'GET', base, { token: TOKEN_A2 }), 401);
});

test('production ignores test clock headers; only isolated harness can inject time', async () => {
  const r = checked(await call(prod, 'GET', '/v1/relay', { now: 1 }));
  assert.ok(r.serverTime > START);
  assert.equal((await call(prod, 'POST', '/__test/cleanup', { now: 1 })).status, 404);
});

test('production scheduled handler actually deletes expired D1/R2 data with no caller clock', async () => {
  const id = randomUUID(), expiry = Date.now() - 10_000, data = Buffer.from('scheduled original');
  await pdb.prepare(`INSERT INTO relay_transfers
    (transferId, ownerId, requestId, requestedAt, kind, entityId, sha256, bytes, mime, originalName,
      deviceId, deviceName, createdAt, expiresAt, state)
    VALUES (?, 'account-a', ?, ?, 'source', 'row-scheduled', ?, ?, 'text/plain', 'original.txt',
      'tablet', 'Tablet', ?, ?, 'ready')`)
    .bind(id, randomUUID(), expiry - TTL, sha(data), data.length, expiry - TTL, expiry).run();
  await pdb.prepare('INSERT INTO relay_chunks (transferId, chunkIndex, bytes, sha256, uploaded) VALUES (?, 0, ?, ?, 1)').bind(id, data.length, sha(data)).run();
  const r2 = await prod.getR2Bucket('AUDIO'), objectKey = `${prefix}${expiry}/${id}/0-${sha(data)}`;
  await r2.put(objectKey, data);
  const result = await (await prod.getWorker()).scheduled({ cron: '* * * * *' });
  assert.equal(result.outcome, 'ok');
  assert.equal(await pdb.prepare('SELECT * FROM relay_transfers WHERE transferId = ?').bind(id).first(), null);
  assert.equal(await pdb.prepare('SELECT * FROM relay_chunks WHERE transferId = ?').bind(id).first(), null);
  assert.equal(await r2.get(objectKey), null);
});

test('exact server expiry: ready and pending inaccessible at T+600000, cron removes metadata/bytes and preserves shares', async () => {
  await reset();
  const data = Buffer.from('source original PDF bytes');
  const [row, m] = await begin(harness, data, START, { kind: 'source', mime: 'application/pdf', originalName: 'Originale.pdf' });
  checked(await put(harness, row, 0, data, START)); checked(await finish(harness, row, START));
  const [pending] = await begin(harness, data, START);
  await bucket.put('account-a/permanent-share/audio', 'persistent');
  checked(await call(harness, 'GET', `/v1/relay/${row.transferId}/chunks/0`, { now: row.expiresAt - 1, token: TOKEN_A2 }));
  for (const target of [row, pending]) {
    for (const [method, path] of [['GET', ''], ['GET', '/chunks/0'], ['POST', '/commit'], ['PUT', '/chunks/0']]) {
      const r = await call(harness, method, `/v1/relay/${target.transferId}${path}`, { now: row.expiresAt });
      assert.equal(checked(r, 410).code, 'expired');
    }
  }
  assert.deepEqual(checked(await call(harness, 'GET', '/v1/relay', { now: row.expiresAt })).transfers, []);
  assert.equal(checked(await call(harness, 'POST', '/v1/relay', { now: row.expiresAt, body: m }), 410).code, 'expired');
  const gc = checked(await clean(row.expiresAt)); assert.equal(gc.removed, 2);
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM relay_transfers').first()).n, 0);
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM relay_chunks').first()).n, 0);
  assert.equal((await bucket.list({ prefix })).objects.length, 0);
  assert.equal(await (await bucket.get('account-a/permanent-share/audio')).text(), 'persistent');
  assert.equal(checked(await call(harness, 'POST', '/v1/relay', { now: row.expiresAt + 1, body: m }), 410).code, 'stale_begin');
  const [resent] = await begin(harness, data, row.expiresAt + 1); assert.notEqual(resent.transferId, row.transferId);
});

test('bounded JSON/chunks, names, ids, lengths, hashes, chunk conflicts and full-file mismatch', async () => {
  await reset(); const data = Buffer.from('1234'), m = meta(data, START);
  for (const bad of [[], null, 'bad-json']) checked(await call(harness, 'POST', '/v1/relay', { now: START, body: JSON.stringify(bad) }), 400);
  checked(await call(harness, 'POST', '/v1/relay', { now: START, body: 'x'.repeat(4097) }), 413);
  checked(await call(harness, 'POST', '/v1/relay', { now: START, body: 'x'.repeat(4097), headers: { 'x-test-streaming': '1' } }), 413);
  for (const change of [{ originalName: '../file' }, { originalName: 'C:\\file' }, { originalName: 'a\u0000b' },
    { deviceId: '../device' }, { entityId: '../../row' }, { sha256: 'no' }, { mime: 'text/plain\r\nx: a' },
    { bytes: 0 }, { bytes: 1.2 }, { kind: 'other' }, { requestId: 'bad' }, { requestedAt: START + 1 }]) {
    const r = await call(harness, 'POST', '/v1/relay', { now: START, body: { ...m, ...change } }); assert.ok([400, 410].includes(r.status), JSON.stringify(change));
  }
  checked(await call(harness, 'POST', '/v1/relay', { now: START, body: { ...m, bytes: 268435457 } }), 413);
  const [row, original] = await begin(harness, data, START);
  checked(await call(harness, 'POST', '/v1/relay', { now: START, body: { ...original, originalName: 'different.m4a' } }), 409);
  for (const n of ['-1', '1', '01', 'a', '9999999999999999999999']) checked(await call(harness, 'PUT', `/v1/relay/${row.transferId}/chunks/${n}`, { now: START }), 400);
  checked(await call(harness, 'PUT', `/v1/relay/${row.transferId}/chunks/0`, { now: START, body: data }), 400);
  checked(await put(harness, row, 0, Buffer.from('123'), START), 400);
  checked(await put(harness, row, 0, Buffer.from('12345'), START), 413);
  checked(await put(harness, row, 0, Buffer.from('12345'), START, { headers: { 'x-chunk-sha256': sha(Buffer.from('12345')), 'x-test-streaming': '1' } }), 413);
  checked(await put(harness, row, 0, data, START, { headers: { 'x-chunk-sha256': '0'.repeat(64) } }), 422);
  checked(await put(harness, row, 0, data, START));
  checked(await put(harness, row, 0, Buffer.from('abcd'), START), 409);
  const [wrong] = await begin(harness, data, START, { sha256: '0'.repeat(64) });
  checked(await put(harness, wrong, 0, data, START));
  checked(await finish(harness, wrong, START), 422);
  checked(await call(harness, 'GET', `/v1/relay/${wrong.transferId}/chunks/0`, { now: START }), 409);
});

test('concurrent begin reserves count and total bytes atomically; expired reservations await GC', async () => {
  await reset(); const data = Buffer.from('x');
  const results = await Promise.all(Array.from({ length: 12 }, () => call(harness, 'POST', '/v1/relay', { now: START, body: meta(data, START) })));
  assert.equal(results.filter(r => r.status === 201).length, 8); assert.equal(results.filter(r => r.status === 429).length, 4);
  checked(await call(harness, 'POST', '/v1/relay', { now: START + TTL, body: meta(data, START + TTL) }), 429);
  checked(await clean(START + TTL)); checked(await clean(START + TTL));
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM relay_transfers').first()).n, 0);
  const quota = await Promise.all(Array.from({ length: 3 }, () => call(harness, 'POST', '/v1/relay', { now: START, body: meta(data, START, { bytes: 268435456 }) })));
  assert.equal(quota.filter(r => r.status === 201).length, 2); assert.equal(quota.filter(r => r.status === 429).length, 1);
});

test('concurrent same begin/chunk retries are idempotent and never extend fixed expiry', async () => {
  await reset(); const data = Buffer.from('hello'), m = meta(data, START);
  const results = await Promise.all(Array.from({ length: 5 }, () => call(harness, 'POST', '/v1/relay', { now: START, body: m })));
  const ids = results.map(r => { assert.ok([200, 201].includes(r.status)); return r.json.transferId; });
  assert.equal(new Set(ids).size, 1);
  const row = results[0].json;
  const puts = await Promise.all(Array.from({ length: 3 }, () => put(harness, row, 0, data, START + 30_000)));
  puts.forEach(r => { checked(r); assert.equal(r.json.expiresAt, row.expiresAt); });
  checked(await finish(harness, row, START + 40_000));
  assert.equal((await bucket.list({ prefix })).objects.length, 1);
});

test('paused identical PUT acknowledges after fast PUT commits without deleting the published bytes', { timeout: 20_000 }, async () => {
  await reset();
  const data = Buffer.from('same immutable chunk'), [row] = await begin(harness, data, START);
  const base = `/v1/relay/${row.transferId}`, slow = pausedPut(row, data, START, 'after');
  try {
    await slow.entered; // The real R2 write completed; its D1 acknowledgement has not run.
    assert.equal((await db.prepare('SELECT uploaded FROM relay_chunks WHERE transferId = ?').bind(row.transferId).first()).uploaded, 0);
    checked(await put(harness, row, 0, data, START + 1_000));
    const ready = checked(await finish(harness, row, START + 2_000));
    assert.equal(ready.state, 'ready'); assert.equal(ready.expiresAt, row.expiresAt);
    checked(await call(harness, 'PUT', `${base}/chunks/0`, { now: START + 2_000, token: TOKEN_B }), 404);
    checked(await call(harness, 'DELETE', base, { now: START + 2_000, token: TOKEN_B }), 202);
    slow.release(START + 3_000);
    const ack = checked(await slow.result);
    assert.equal(ack.expiresAt, row.expiresAt); assert.equal(ack.sha256, sha(data));
    const downloaded = await call(harness, 'GET', `${base}/chunks/0`, { now: row.expiresAt - 1, token: TOKEN_A2 });
    assert.equal(downloaded.status, 200); assert.deepEqual(downloaded.bytes, data);
    assert.equal(downloaded.headers.get('content-length'), String(data.length));
    assert.equal(downloaded.headers.get('x-chunk-sha256'), sha(data));
    assert.equal(downloaded.headers.get('x-file-sha256'), sha(data));
    assert.equal(downloaded.headers.get('x-expires-at'), String(row.expiresAt));
    const status = checked(await call(harness, 'GET', base, { now: row.expiresAt - 1 }));
    assert.equal(status.state, 'ready'); assert.equal(status.expiresAt, row.createdAt + TTL);
    assert.equal(checked(await call(harness, 'GET', `${base}/chunks/0`, { now: row.expiresAt }), 410).code, 'expired');
  } finally { slow.release(START + 3_000); await slow.result; }
});

for (const cancelled of [false, true]) {
  test(`late R2 write after ${cancelled ? 'cancellation' : 'expiry and metadata cleanup'} stays inaccessible and is collected`, { timeout: 20_000 }, async () => {
    await reset();
    const data = Buffer.from('late immutable chunk'), [row, m] = await begin(harness, data, START);
    const base = `/v1/relay/${row.transferId}`, slow = pausedPut(row, data, START, 'before');
    const resumedAt = cancelled ? START + 3 : row.expiresAt;
    try {
      await slow.entered; // Reservation exists, but the real R2 write is still pending.
      if (cancelled) checked(await call(harness, 'DELETE', base, { now: START + 1 }), 202);
      checked(await clean(cancelled ? START + 2 : row.expiresAt));
      assert.equal((await bucket.list({ prefix })).objects.length, 0);
      slow.release(resumedAt);
      assert.equal(checked(await slow.result, cancelled ? 410 : 404).code, cancelled ? 'deleted' : 'not_found');
      checked(await call(harness, 'GET', `${base}/chunks/0`, { now: resumedAt }), cancelled ? 410 : 404);
      if (cancelled) assert.equal(checked(await call(harness, 'POST', '/v1/relay', { now: resumedAt, body: m }), 410).code, 'deleted');
      assert.equal((await bucket.list({ prefix })).objects.length, 1);
      if (cancelled) checked(await clean(row.expiresAt));
      else assert.equal(checked(await clean(row.expiresAt + 1)).orphans, 1);
      assert.equal((await bucket.list({ prefix })).objects.length, 0);
      assert.equal(await db.prepare('SELECT * FROM relay_transfers WHERE transferId = ?').bind(row.transferId).first(), null);
    } finally { slow.release(resumedAt); await slow.result; }
  });
}

test('abandoned uploads, failed deletes/backoff, orphan/staging bytes and delayed writes are collected', async () => {
  await reset(); const data = Buffer.from('abandoned');
  const [row] = await begin(harness, data, START);
  checked(await put(harness, row, 0, data, START)); // never committed
  const r = await clean(row.expiresAt, { headers: { 'x-test-fail-delete': '1' } }); checked(r, 500);
  let stored = await db.prepare('SELECT * FROM relay_transfers WHERE transferId = ?').bind(row.transferId).first();
  assert.equal(stored.cleanupAttempts, 1); assert.equal(stored.nextCleanupAt, row.expiresAt + 60_000);
  assert.equal((await bucket.list({ prefix })).objects.length, 1);
  checked(await clean(stored.nextCleanupAt));
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM relay_transfers').first()).n, 0);
  // A rowless R2 write after cleanup is discoverable from its original expiry, without access.
  await bucket.put(`${prefix}${row.expiresAt}/${row.transferId}/0-${sha(data)}`, data);
  assert.equal(checked(await clean(row.expiresAt + 60_001)).orphans, 1);
  assert.equal((await bucket.list({ prefix })).objects.length, 0);
  const [late] = await begin(harness, data, START + TTL * 2);
  const delayed = await put(harness, late, 0, data, START + TTL * 2, { headers: { 'x-chunk-sha256': sha(data), 'x-test-expire-after-put': '1' } });
  checked(delayed, 410);
  checked(await clean(late.expiresAt));
  assert.equal((await bucket.list({ prefix })).objects.length, 0);
});

test('delete revokes immediately, cron removes bytes; receipt fences retries until original expiry', async () => {
  await reset(); const data = Buffer.from('delete me'), [row, m] = await begin(harness, data, START);
  checked(await put(harness, row, 0, data, START)); checked(await finish(harness, row, START));
  checked(await call(harness, 'DELETE', `/v1/relay/${row.transferId}`, { now: START + 1 }), 202);
  checked(await call(harness, 'GET', `/v1/relay/${row.transferId}/chunks/0`, { now: START + 1 }), 410);
  checked(await clean(START + 2)); assert.equal((await bucket.list({ prefix })).objects.length, 0);
  checked(await call(harness, 'POST', '/v1/relay', { now: START + 3, body: m }), 410);
  checked(await clean(row.expiresAt)); assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM relay_transfers').first()).n, 0);
  checked(await call(harness, 'POST', '/v1/relay', { now: row.expiresAt, body: m }), 410);
});

test('maximum 256 MiB file: 32 bounded chunks commit and verify without assembling a file in Worker memory', async () => {
  await reset();
  const data = Buffer.alloc(CHUNK, 0x39), hasher = createHash('sha256');
  for (let n = 0; n < 32; n++) hasher.update(data);
  const [row] = await begin(harness, data, START, { bytes: CHUNK * 32, sha256: hasher.digest('hex') });
  for (let n = 0; n < 32; n++) checked(await put(harness, row, n, data, START));
  const ready = checked(await finish(harness, row, START));
  assert.equal(ready.chunks.length, 32); assert.equal(ready.bytes, CHUNK * 32);
  const received = createHash('sha256'); let bytes = 0;
  for (let n = 0; n < 32; n++) {
    const r = await call(harness, 'GET', `/v1/relay/${row.transferId}/chunks/${n}`, { now: START, token: TOKEN_A2 });
    assert.equal(r.status, 200); assert.equal(sha(r.bytes), sha(data)); received.update(r.bytes); bytes += r.bytes.length;
  }
  assert.equal(bytes, ready.bytes); assert.equal(received.digest('hex'), ready.sha256);
  checked(await clean(row.expiresAt)); assert.equal((await bucket.list({ prefix })).objects.length, 0);
});
