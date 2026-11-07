/** Run existing HTTP regressions with fresh real Worker/D1/R2 state, never .dev.vars. */
import { spawn } from 'node:child_process';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { build } from 'esbuild';
import { Miniflare } from 'miniflare';

const bundle = await build({ entryPoints: ['src/index.ts'], bundle: true, write: false, format: 'esm', target: 'es2022', platform: 'browser' });
const schema = (await readFile('schema.sql', 'utf8')).replace(/--[^\n]*/g, '').split(';').map(s => s.trim()).filter(Boolean);
const suites = process.argv.slice(2);
if (!suites.length) suites.push('test_protocol.py', 'test_share.py', 'test_pull_parents.py', 'test_pull_limits.py', 'test_concurrent_push.py', 'test_runs.py', 'test_guests.py');
for (let i = 0; i < suites.length; i++) {
  const directory = resolve('.wrangler', `g${process.pid}`, String(i));
  await mkdir(directory, { recursive: true });
  const mf = new Miniflare({ workers: [{ config: { name: 'regression',
    compatibilityDate: '2025-09-01', compatibilityFlags: ['nodejs_compat'],
    manifest: { mainModule: 'index.js', modules: { 'index.js': { type: 'esm', contents: bundle.outputFiles[0].text } } },
    env: { DB: { type: 'd1', id: 'regression-db' }, AUDIO: { type: 'r2', name: 'regression-audio' },
      AUTH_DEV_TOKENS: { type: 'text', value: 'dev-alessio:dev-owner-1,dev-amico:dev-owner-2,dev-google:google:12345' },
      GOOGLE_CLIENT_ID: { type: 'text', value: '' }, PROTOCOL_VERSION: { type: 'text', value: '1' },
      TOMBSTONE_RETENTION_DAYS: { type: 'text', value: '90' } } } }],
    resourcePersistencePath: directory });
  try {
    const db = await mf.getD1Database('DB');
    for (const sql of schema) await db.prepare(sql).run();
    const origin = (await mf.ready).origin;
    console.log(`\n${suites[i]} (isolated local Worker)`);
    const exitCode = await new Promise((resolveExit, reject) => {
      const child = spawn('python', [suites[i], origin], { stdio: 'inherit', env: { ...process.env, PYTHONIOENCODING: 'utf-8' } });
      child.once('error', reject); child.once('exit', resolveExit);
    });
    if (exitCode !== 0) throw new Error(`${suites[i]} failed (${exitCode})`);
  } finally { await mf.dispose(); }
}
console.log(`\n${suites.length} existing regression suites passed.`);
