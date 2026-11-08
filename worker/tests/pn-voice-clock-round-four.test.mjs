/** Offline production helper + production push/pull SQL, Node 24, no dependencies or services.
 * Run together with pn-public-evidence.test.mjs using its documented bounded Node flags.
 * This local SQLite adapter does not establish Cloudflare D1/distributed runtime behavior.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { DatabaseSync } from "node:sqlite";
import { after, test } from "node:test";
import { retainSessionVoiceClocks as retain } from "../src/sync-voice-clocks.ts";

const syncUrl = new URL("../src/sync.ts", import.meta.url).href;
registerHooks({ resolve(specifier, context, nextResolve) {
  return nextResolve(context.parentURL === syncUrl && ["./sync-public-evidence", "./sync-voice-clocks"].includes(specifier) ? `${specifier}.ts` : specifier, context);
} });
const { push, pull } = await import(syncUrl);
const schema = readFileSync(new URL("../schema.sql", import.meta.url), "utf8");
after(() => console.log(`PN voice clocks peak RSS: ${process.resourceUsage().maxRSS} KiB; CPU affinity inherited from bounded runner`));
const key = (part, speaker = "SPEAKER_00") => `${part}|${speaker}`;
const raw = (names, clocks) => JSON.stringify({ ...names, ...(clocks ? { _pampaVoiceClocks: clocks } : {}) });
const session = (voiceNames, updatedAt = 500) => ({ id: "s", title: "Session", date: "2026-11-08", updatedAt, voiceNames });
const parsed = (payload) => payload.voiceNames === null ? null : JSON.parse(payload.voiceNames);
const changed = (payload, hash, baseHash = "", extra = {}) => ({ tbl: "sessions", id: payload.id, op: "U", updatedAt: payload.updatedAt, hash, baseHash, payload, ...extra });
const applied = (result, n = 1) => { assert.equal(result.applied, n); assert.deepEqual(result.rejected, []); };

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
  const env = { DB: {
    prepare: (sql) => new Statement(db, sql),
    async batch(statements) {
      db.exec("BEGIN");
      try { const result = statements.map((s) => s.execute()); db.exec("COMMIT"); return result; }
      catch (error) { db.exec("ROLLBACK"); throw error; }
    },
  } };
  let serial = 0;
  return { db, env,
    send: (deviceId, changes, batchId = `voice-${++serial}`) => push(env, "voice-owner", { protocolVersion: 1, deviceId, changes, batchId }),
    read: async (id = "s", deviceId = "C-new") => (await pull(env, "voice-owner", deviceId, 0, 200)).changes.find((c) => c.id === id),
  };
}

test("legacy edit clocks only the changed complete recording map; metadata stays independent", () => {
  const previous = session(raw({ [key("p")]: "Marco", [key("q")]: "Anna" }, { p: 100, q: 500 }));
  const incoming = { ...session(raw({ [key("p")]: "Marco", [key("q")]: "Giulia" }), 600), title: "New title", activeTranscriptId: "selected" };
  const result = retain(incoming, previous, 900);
  assert.deepEqual(parsed(result), { [key("p")]: "Marco", [key("q")]: "Giulia", _pampaVoiceClocks: { p: 100, q: 600 } });
  assert.deepEqual({ ...result, voiceNames: null }, { ...incoming, voiceNames: null });
  assert.equal(retain(result, { voiceNames: result.voiceNames, updatedAt: result.updatedAt }, 900), result);
});

test("legacy last-name null removal retains untouched empty maps and clocks the actual removal", () => {
  const previous = session(raw({ [key("q")]: "Anna" }, { p: 100, q: 500 }));
  assert.deepEqual(parsed(retain(session(null, 600), previous, 600)), { _pampaVoiceClocks: { p: 100, q: 600 } });
  // A legacy null -> null metadata edit is not a fresh deletion of either empty map.
  const empty = session(raw({}, { p: 100, q: 600 }), 600);
  assert.deepEqual(parsed(retain(session(null, 900), empty, 900)), { _pampaVoiceClocks: { p: 100, q: 600 } });
  const omitted = { id: "s", title: "Legacy title", updatedAt: 1000 };
  assert.equal(retain(omitted, empty, 1000).voiceNames, empty.voiceNames);
  assert.equal(retain(session(null), session(raw({ [key("q")]: "Anna" })), 500).voiceNames, null);
});

test("new clients merge concurrent recordings and tombstones without borrowing metadata clocks", () => {
  const previous = session(raw({ [key("q")]: "Anna" }, { p: 200, q: 10 }), 10);
  const incoming = session(raw({ [key("p")]: "Stale Marco", [key("q")]: "Giulia" }, { p: 100, q: 300 }), 300);
  assert.deepEqual(parsed(retain(incoming, previous, 300)), { [key("q")]: "Giulia", _pampaVoiceClocks: { p: 200, q: 300 } });
  const distinct = session(raw({ [key("p")]: "Alice", [key("q")]: "Anna" }, { p: 200, q: 10 }), 10);
  assert.deepEqual(parsed(retain(incoming, distinct, 300)), { [key("p")]: "Alice", [key("q")]: "Giulia", _pampaVoiceClocks: { p: 200, q: 300 } });
  const newPart = session(raw({ [key("p")]: "Marco" }, { p: 100 }), 20);
  assert.equal(retain(newPart, session(null, 900), 20), newPart);
});

test("complete-map edits remove missing speaker keys; stored maps win equal explicit clocks", () => {
  const previous = session(raw({ [key("p")]: "Marco", [key("p", "SPEAKER_01")]: "Anna" }, { p: 100 }));
  const incoming = session(raw({ [key("p")]: "Giulia" }, { p: 200 }), 20);
  assert.equal(retain(incoming, previous, 20), incoming);
  assert.deepEqual(parsed(retain(session(raw({}, { p: 100 }), 600), previous, 600)), parsed(previous));
  const legacy = session(raw({ [key("p")]: "Giulia" }), 700);
  assert.deepEqual(parsed(retain(legacy, previous, 700)), { [key("p")]: "Giulia", _pampaVoiceClocks: { p: 700 } });
});

test("A new -> old B edits q -> new C retains p authority, opaque hashes, bases and retry seq", async (t) => {
  const f = fixture(t);
  applied(await f.send("A-new", [changed(session(raw({ [key("p")]: "Marco", [key("q")]: "Anna" }, { p: 100, q: 500 })), "opaque-a")]));
  const b = (await f.read("s", "B-old")).payload;
  // Released legacy decoder ignores the nested object; rename re-encodes string entries only.
  const names = Object.fromEntries(Object.entries(JSON.parse(b.voiceNames)).filter(([, value]) => typeof value === "string"));
  names[key("q")] = "Giulia";
  const edit = changed({ ...b, title: "B's title", updatedAt: 600, voiceNames: JSON.stringify(names) }, "opaque-b", "opaque-a");
  const accepted = await f.send("B-old", [edit], "old-edit"); applied(accepted);
  const c = await f.read();
  assert.deepEqual(parsed(c.payload)._pampaVoiceClocks, { p: 100, q: 600 });
  assert.equal(c.hash, "opaque-b"); assert.equal(c.updatedAt, 600); assert.equal(c.payload.title, "B's title");
  assert.deepEqual(await f.send("B-old", [edit], "old-edit"), accepted);
  const duplicate = await f.send("B-old", [edit]); applied(duplicate, 0); assert.equal(duplicate.seq, accepted.seq);
  applied(await f.send("C-new", [changed(c.payload, "canonical-c", c.hash)]));
  const canonical = await f.read("s", "D-new"); assert.equal(canonical.payload.voiceNames, c.payload.voiceNames);
  const retry = await f.send("C-new", [changed(c.payload, "canonical-c", c.hash)]); applied(retry, 0); assert.equal(retry.seq, canonical.seq);
});

test("SQL new/new concurrent distinct maps and tombstones preserve newer per-recording authority", async (t) => {
  const f = fixture(t);
  const a = session(raw({ [key("p")]: "Alice", [key("q")]: "Anna" }, { p: 200, q: 10 }), 10);
  applied(await f.send("A", [changed(a, "a")]));
  const b = session(raw({ [key("p")]: "Stale Alice", [key("q")]: "Giulia" }, { p: 100, q: 300 }), 300);
  applied(await f.send("B", [changed(b, "b", "a")]));
  assert.deepEqual(parsed((await f.read()).payload), { [key("p")]: "Alice", [key("q")]: "Giulia", _pampaVoiceClocks: { p: 200, q: 300 } });
  const removal = session(raw({ [key("q")]: "Giulia" }, { p: 400, q: 300 }), 20);
  applied(await f.send("A", [changed(removal, "removed", "b")]));
  applied(await f.send("B", [changed({ ...b, updatedAt: 700, voiceNames: raw({ [key("p")]: "Stale Alice", [key("q")]: "Newest Giulia" }, { p: 100, q: 700 }) }, "b2", "removed")]));
  assert.deepEqual(parsed((await f.read()).payload), { [key("q")]: "Newest Giulia", _pampaVoiceClocks: { p: 400, q: 700 } });
});

test("SQL legacy null removal and omitted field retain empty recording clocks before A returns", async (t) => {
  const f = fixture(t);
  applied(await f.send("A", [changed(session(raw({ [key("q")]: "Anna" }, { p: 100, q: 500 })), "a")]));
  applied(await f.send("B-old", [changed(session(null, 600), "b", "a")]));
  assert.deepEqual(parsed((await f.read()).payload), { _pampaVoiceClocks: { p: 100, q: 600 } });
  const omitted = { id: "s", title: "Legacy", updatedAt: 900 };
  applied(await f.send("B-old", [changed(omitted, "c", "b")]));
  assert.deepEqual(parsed((await f.read()).payload), { _pampaVoiceClocks: { p: 100, q: 600 } });
});

test("SQL tombstone recreation, stale bases and identical opaque hashes retain protocol guards", async (t) => {
  const f = fixture(t);
  const a = session(raw({ [key("p")]: "Marco" }, { p: 100 }));
  applied(await f.send("A", [changed(a, "a")]));
  const stale = await f.send("B", [changed(session(null, 900), "bad", "unseen")]);
  assert.deepEqual(stale.rejected, [{ tbl: "sessions", id: "s", reason: "stale" }]);
  applied(await f.send("B", [changed(session(null, 900), "a", "unseen")]), 0);
  assert.equal((await f.read()).payload.voiceNames, a.voiceNames);
  const deletion = { tbl: "sessions", id: "s", op: "D", updatedAt: 800, hash: "deleted", baseHash: "a" };
  applied(await f.send("B", [deletion]));
  applied(await f.send("C", [changed(session(null, 900), "recreated", "deleted")]));
  assert.equal((await f.read()).payload.voiceNames, null);
});

test("voice snapshot and seq share one read; concurrent replacement cannot receive stale clocks", async (t) => {
  const f = fixture(t);
  applied(await f.send("A", [changed(session(raw({ [key("p")]: "Marco" }, { p: 100 })), "a")]));
  const realBatch = f.env.DB.batch;
  const replacement = session(raw({ [key("p")]: "Racer" }, { p: 800 }), 800);
  let raced = false;
  f.env.DB.batch = async (statements) => {
    if (!raced && statements[0].sql.startsWith("UPDATE owners SET seq = seq +")) {
      raced = true;
      f.db.exec("UPDATE owners SET seq = seq + 1");
      f.db.prepare("UPDATE state SET payload = ?, hash = 'racer', deviceId = 'racer', seq = (SELECT seq FROM owners)").run(JSON.stringify(replacement));
    }
    return realBatch(statements);
  };
  const result = await f.send("B", [changed(session(raw({ [key("p")]: "Marco" }), 900), "b", "a")]);
  assert.equal(raced, true); assert.deepEqual(result.rejected, [{ tbl: "sessions", id: "s", reason: "stale" }]);
  assert.deepEqual((await f.read()).payload, replacement);
});

test("moving a recording is not a removal clock when the source session precedes its audio page", async (t) => {
  const f = fixture(t);
  const source = session(raw({ [key("p")]: "Alice", [key("q")]: "Anna" }, { p: 100, q: 500 }));
  const audio = { id: "p", sessionId: "s", sha256: "a".repeat(64), sizeBytes: 1 };
  const audioChange = (payload, hash, baseHash = "") => ({ tbl: "audio_parts", id: "p", op: "U", updatedAt: 1, hash, baseHash, payload });
  applied(await f.send("A", [changed(source, "source"), audioChange(audio, "audio")]), 2);
  const target = { ...session(raw({ [key("p")]: "Alice" }, { p: 100 }), 600), id: "target" };
  applied(await f.send("B", [changed(session(null, 600), "moved-source", "source"), changed(target, "target"), audioChange({ ...audio, sessionId: "target" }, "moved-audio", "audio")]), 3);
  const result = await pull(f.env, "voice-owner", "C", 0, 1);
  assert.equal(result.changes[0].id, "s");
  // p moved, q was actually removed. A reader seeing only this source page must not learn a
  // fake p:600 tombstone, which would later defeat the correct destination p:100 map.
  assert.deepEqual(parsed(result.changes[0].payload), { [key("p")]: "Alice", _pampaVoiceClocks: { p: 100, q: 600 } });
  assert.equal((await f.read("target")).payload.voiceNames, target.voiceNames);
  // The Android page helper later removes p from the actual former owner. Republishing that
  // enrichment must clean the old map, not stamp a fake p:600 tombstone and echo forever.
  const canonicalSource = session(raw({}, { q: 600 }), 600);
  applied(await f.send("C", [changed(canonicalSource, "canonical-source", "moved-source")]));
  assert.equal((await f.read("s", "D")).payload.voiceNames, canonicalSource.voiceNames);
});

test("cleanup of the last moved source map converges to null without manufacturing a tombstone", async (t) => {
  const f = fixture(t);
  const audio = { id: "p", sessionId: "target", sha256: "a".repeat(64), sizeBytes: 1 };
  applied(await f.send("A", [changed(session(raw({ [key("p")]: "Alice" }, { p: 100 })), "source"),
    { tbl: "audio_parts", id: "p", op: "U", updatedAt: 1, hash: "audio", payload: audio }]), 2);
  applied(await f.send("C", [changed(session(null, 500), "clean", "source")]));
  assert.equal((await f.read()).payload.voiceNames, null);
  const duplicate = await f.send("C", [changed(session(null, 500), "clean", "source")]);
  applied(duplicate, 0);
});

test("ownership retention is sliced at ninety identities and keeps unrelated recording clocks", async (t) => {
  const f = fixture(t);
  const ids = Array.from({ length: 95 }, (_, i) => `recording-${i}`);
  const names = Object.fromEntries(ids.map((id) => [key(id), "Alice"]));
  const clocks = Object.fromEntries(ids.map((id) => [id, 100]));
  const audio = ids.map((id) => ({ tbl: "audio_parts", id, op: "U", updatedAt: 1, hash: id,
    payload: { id, sessionId: "s", sha256: "a".repeat(64), sizeBytes: 1 } }));
  applied(await f.send("A", [changed(session(raw(names, clocks)), "a"), ...audio]), 96);
  names[key(ids[94])] = "Giulia";
  applied(await f.send("B", [changed(session(raw(names), 600), "b", "a")]));
  const got = parsed((await f.read()).payload);
  assert.deepEqual(got._pampaVoiceClocks, { ...clocks, [ids[94]]: 600 });
  assert.equal(got[key(ids[94])], "Giulia");
});

test("modern last-recording omission is preserved across separate bounded push batches", async (t) => {
  const f = fixture(t);
  const original = raw({ [key("p")]: "Alice" }, { p: 100 });
  const audio = { id: "p", sessionId: "s", sha256: "a".repeat(64), sizeBytes: 1 };
  const audioChange = (payload, hash, baseHash = "") => ({ tbl: "audio_parts", id: "p", op: "U", updatedAt: 1, hash, baseHash, payload });
  applied(await f.send("A", [changed(session(original), "a"), audioChange(audio, "audio")]), 2);
  // VoiceNames.carry emits this empty clock object after moving its last recording. In contrast,
  // a real legacy null removal above clocks the removed map at its incoming edit timestamp.
  applied(await f.send("B", [changed(session(raw({}, {}), 600), "source-marker", "a")]));
  assert.equal((await f.read()).payload.voiceNames, original);
  applied(await f.send("B", [changed({ ...session(original, 600), id: "target" }, "target"), audioChange({ ...audio, sessionId: "target" }, "moved-audio", "audio")]), 2);
  assert.equal(parsed((await f.read("target")).payload)._pampaVoiceClocks.p, 100);
  applied(await f.send("C", [changed(session(null, 600), "canonical-source", "source-marker")]));
  assert.equal((await f.read("s", "D")).payload.voiceNames, null);
});
