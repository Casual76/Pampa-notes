// Opt-in Android JVM interoperability fixture. Production router, real workerd D1/R2,
// real authentication and clock. Never loads wrangler.toml or .dev.vars.
// From worker/: node tests/android-relay-server.mjs
// Set PAMPA_RELAY_FIXTURE_URL to the printed loopback URL before running RelayWorkerInteropTest.
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { build } from 'esbuild';
import { Miniflare } from 'miniflare';

const root = fileURLToPath(new URL('..', import.meta.url));
const directory = resolve(root, '.wrangler', `a${process.pid}${randomBytes(2).toString('hex')}`);
await mkdir(directory, { recursive: true });
const bundle = await build({ entryPoints: [resolve(root, 'src/index.ts')], bundle: true, write: false,
  format: 'esm', target: 'es2022', platform: 'browser' });
const runtime = new Miniflare({ workers: [{ config: { name: 'android-relay',
  compatibilityDate: '2025-09-01', compatibilityFlags: ['nodejs_compat'],
  manifest: { mainModule: 'index.js', modules: { 'index.js': { type: 'esm', contents: bundle.outputFiles[0].text } } },
  env: { DB: { type: 'd1', id: 'android-relay-db' }, AUDIO: { type: 'r2', name: 'android-relay-audio' },
    AUTH_DEV_TOKENS: { type: 'text', value: '' }, GOOGLE_CLIENT_ID: { type: 'text', value: '' },
    PROTOCOL_VERSION: { type: 'text', value: '1' } } } }], resourcePersistencePath: directory });
const db = await runtime.getD1Database('DB');
const schema = (await readFile(resolve(root, 'schema.sql'), 'utf8')).replace(/--[^\n]*/g, '');
for (const statement of schema.split(';').map(s => s.trim()).filter(Boolean)) await db.prepare(statement).run();
// Fixture-only, non-secret session tokens, checked by production ownerOf like real sessions.
for (const [token, owner, device] of [['a'.repeat(43), 'account-a', 'sender'], ['c'.repeat(43), 'account-a', 'receiver'], ['b'.repeat(43), 'account-b', 'other']]) {
  await db.prepare('INSERT INTO sessions (token, ownerId, deviceId, deviceName, createdAt, lastSeenAt) VALUES (?, ?, ?, ?, ?, ?)')
    .bind(token, owner, device, device, Date.now(), Date.now()).run();
}
const server = createServer(async (req, res) => {
  try {
    const body = []; let length = 0;
    for await (const chunk of req) {
      length += chunk.length;
      if (length > 8 * 1024 * 1024 + 4096) { res.writeHead(413); res.end(); return; }
      body.push(chunk);
    }
    const response = await runtime.dispatchFetch(`http://relay.test${req.url}`, {
      method: req.method, headers: req.headers,
      ...(['GET', 'HEAD'].includes(req.method) ? {} : { body: Buffer.concat(body) }),
    });
    res.writeHead(response.status, Object.fromEntries(response.headers));
    if (response.body) for await (const chunk of response.body) res.write(chunk);
    res.end();
  } catch (error) { console.error(error.name); if (!res.headersSent) res.writeHead(500); res.end(); }
});
await new Promise((resolveListen, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolveListen); });
console.log(`ANDROID_RELAY_FIXTURE_URL=http://127.0.0.1:${server.address().port}`);
console.log(`Fixture state: ${directory}`);
let stopping = false;
async function close() {
  if (stopping) return; stopping = true;
  server.closeAllConnections();
  await new Promise(resolveClose => server.close(resolveClose));
  await runtime.dispose();
  process.exit(0);
}
process.on('SIGINT', close); process.on('SIGTERM', close);
process.stdin.resume();
process.stdin.on('data', input => { if (input.toString().trim() === 'stop') void close(); });
