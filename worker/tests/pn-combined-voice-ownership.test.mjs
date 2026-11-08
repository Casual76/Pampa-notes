/** Combined integration fixtures: production push/pull SQL, entirely offline.
 * PN bytes and Kotlin/Room consumers are covered by the companion prepared Kotlin tests.
 * Run with one CPU, --single-threaded --v8-pool-size=1 --max-old-space-size=48,
 * --test --test-concurrency=1 --test-isolation=none. No D1 runtime claim.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { DatabaseSync } from "node:sqlite";
import { after, test } from "node:test";

const syncUrl = new URL("../src/sync.ts", import.meta.url).href;
registerHooks({ resolve(specifier, context, next) {
  return next(context.parentURL === syncUrl && ["./sync-public-evidence", "./sync-voice-clocks"].includes(specifier) ? `${specifier}.ts` : specifier, context);
} });
const { push, pull } = await import(syncUrl);
const schema = readFileSync(new URL("../schema.sql", import.meta.url), "utf8");
const names = (name, clock = 100) => JSON.stringify({ ...(name === null ? {} : { "p|SPEAKER_00": name }), _pampaVoiceClocks: { p: clock } });
const marker = JSON.stringify({ _pampaVoiceClocks: {} });
const source = { id: "source", noteId: "n", title: "Source", date: "2026-11-08", updatedAt: 10, voiceNames: names("Alice") };
const target = { ...source, id: "target", title: "Target", updatedAt: 50, voiceNames: null };
const audio = { id: "p", sessionId: "source", sizeBytes: 1, sha256: "a".repeat(64) };
const change = (tbl, payload, hash, baseHash = "") => ({ tbl, id: payload.id, op: "U", updatedAt: payload.updatedAt ?? 1, hash, baseHash, payload });
const parsed = (row) => row.voiceNames === null ? null : JSON.parse(row.voiceNames);

class Statement {
  constructor(db, sql, args = []) { this.db = db; this.sql = sql; this.args = args; }
  bind(...args) { assert.ok(args.length <= 100); return new Statement(this.db, this.sql, args); }
  execute() {
    const s = this.db.prepare(this.sql);
    const results = s.columns().length ? s.all(...this.args) : (s.run(...this.args), []);
    return { results, meta: { changes: this.db.prepare("SELECT changes() n").get().n } };
  }
  async first() { return this.execute().results[0] ?? null; }
  async all() { return this.execute(); }
  async run() { return this.execute(); }
}
function fixture(t) {
  const db = new DatabaseSync(":memory:"); db.exec(schema); t.after(() => db.close());
  const env = { DB: { prepare: (sql) => new Statement(db, sql), async batch(statements) {
    db.exec("BEGIN");
    try { const result = statements.map((s) => s.execute()); db.exec("COMMIT"); return result; }
    catch (error) { db.exec("ROLLBACK"); throw error; }
  } } };
  let serial = 0;
  const send = async (changes) => {
    const result = await push(env, "combined-owner", { protocolVersion: 1, deviceId: "writer", batchId: `combined-${++serial}`, changes });
    assert.deepEqual(result.rejected, []); assert.equal(result.applied, changes.length); return result;
  };
  const read = async (id) => (await pull(env, "combined-owner", "reader", 0, 200)).changes.find((c) => c.id === id);
  const seed = () => send([change("sessions", source, "source"), change("sessions", target, "target"), change("audio_parts", audio, "audio")]);
  return { send, read, seed, env };
}

test("combined PN source-first marker positive and null negative controls exercise real Worker SQL", async (t) => {
  for (const structured of [true, false]) {
    const f = fixture(t); await f.seed();
    await f.send([change("sessions", { ...source, updatedAt: 600, voiceNames: structured ? marker : null }, "source-after-pn", "source")]);
    const firstPage = await f.read("source");
    assert.equal(firstPage.hash, "source-after-pn"); assert.equal(firstPage.updatedAt, 600);
    assert.deepEqual(parsed(firstPage.payload), structured ? parsed(source) : { _pampaVoiceClocks: { p: 600 } });
    await f.send([change("sessions", { ...target, updatedAt: 600, voiceNames: names("Alice") }, "target-after-pn", "target")]);
    await f.send([change("audio_parts", { ...audio, sessionId: "target" }, "audio-moved", "audio")]);
    assert.deepEqual(parsed((await f.read("target")).payload), parsed(source));
    // The null fixture is intentionally poisonous to a recording-clock merge: p600 beats p100.
    assert.equal(parsed(firstPage.payload)._pampaVoiceClocks.p > 100, !structured);
  }
});

test("combined named edit and empty removal remain authoritative in all six separate push orders", async (t) => {
  for (const name of ["Giulia", null]) for (const order of ["STA", "SAT", "TSA", "TAS", "AST", "ATS"]) {
    const f = fixture(t); await f.seed();
    const changes = {
      S: change("sessions", { ...source, updatedAt: 600, voiceNames: marker }, "moved-source", "source"),
      T: change("sessions", { ...target, updatedAt: 600, voiceNames: names(name, 300) }, "future-target", "target"),
      A: change("audio_parts", { ...audio, sessionId: "target" }, "moved-audio", "audio"),
    };
    for (const step of order) await f.send([changes[step]]);
    const got = await f.read("target");
    assert.equal(got.hash, "future-target"); assert.equal(got.updatedAt, 600);
    assert.deepEqual(parsed(got.payload), JSON.parse(names(name, 300)), `${order}/${name}`);
    const sourceMap = parsed((await f.read("source")).payload);
    assert.ok(sourceMap?._pampaVoiceClocks?.p === undefined || sourceMap._pampaVoiceClocks.p === 100);
  }
});

test("combined new-client bounded pulls retain all evidence until actual audio ownership is first learned", async (t) => {
  const f = fixture(t); await f.seed();
  await f.send([change("audio_parts", { id: "q", sessionId: "source", sizeBytes: 1, sha256: "b".repeat(64) }, "stationary-q")]);
  await f.send([change("sessions", { ...source, updatedAt: 600, voiceNames: marker }, "moved-source", "source")]);
  await f.send([change("sessions", { ...target, updatedAt: 600, voiceNames: names("Alice") }, "moved-target", "target")]);
  await f.send([change("audio_parts", { ...audio, sessionId: "target" }, "moved-audio", "audio")]);
  const pages = []; const seen = new Set(); let since = 0;
  for (;;) {
    const page = await pull(f.env, "combined-owner", "brand-new-client", since, 1);
    if (!page.changes.length) break;
    // Production pull can append a later parent to the requested one-row base page. Advance
    // with its cursor, not the appended parent's seq, and retain first-seen rows for this fixture.
    for (const change of page.changes) {
      const key = `${change.tbl}/${change.id}`;
      if (!seen.has(key)) { pages.push(change); seen.add(key); }
    }
    assert.ok(page.seq > since); since = page.seq;
    if (!page.more) break;
  }
  assert.deepEqual(pages.filter((c) => c.tbl === "sessions").map((c) => [c.id, parsed(c.payload)]), [
    ["source", parsed(source)], ["target", parsed(source)],
  ]);
  assert.equal(pages.find((c) => c.id === "p").payload.sessionId, "target");
  assert.ok(pages.findIndex((c) => c.id === "source") < pages.findIndex((c) => c.id === "p"));
  // The paired Kotlin/Room regression consumes precisely this stale source + first p owner shape.
});

test("a cleanup push while local audio is pending must be structured, preserving the server's newer map", async (t) => {
  for (const name of ["Giulia", null]) for (const structured of [true, false]) {
    const f = fixture(t); await f.seed();
    await f.send([change("sessions", { ...target, updatedAt: 600, voiceNames: names(name, 300) }, "future-target", "target")]);
    await f.send([change("audio_parts", { ...audio, sessionId: "target" }, "moved-audio", "audio")]);
    // Another client has pulled target but not the audio page. Its projection is safe only
    // when it carries the same explicit omission marker generated by the Kotlin helper.
    await f.send([change("sessions", { ...target, updatedAt: 600, voiceNames: structured ? marker : null }, "local-projection", "future-target")]);
    const published = parsed((await f.read("target")).payload);
    // An already empty tombstone is unchanged even for a legacy null; a named map would be
    // falsely removed at metadata600 without the explicit ownership-projection marker.
    assert.deepEqual(published, structured || name === null ? JSON.parse(names(name, 300)) : { _pampaVoiceClocks: { p: 600 } });
  }
});

after(() => {
  const peakKiB = process.resourceUsage().maxRSS;
  console.log(`Combined voice fixture peak RSS: ${peakKiB} KiB`);
  assert.ok(peakKiB <= 128 * 1024, `128 MiB fixture budget exceeded: ${peakKiB} KiB`);
});
