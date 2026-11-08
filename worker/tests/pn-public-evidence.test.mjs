/**
 * Tiny offline source regressions, Node 24's type stripping + built-in SQLite, no dependencies.
 * node --single-threaded --v8-pool-size=1 --max-old-space-size=64 --test --test-concurrency=1 --test-isolation=none tests/pn-public-evidence.test.mjs
 *
 * Helpers run directly; push/pull execute real production SQL against the unchanged schema via
 * the local adapter below. This is NOT Cloudflare D1/HTTP/workerd or distributed runtime proof.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { DatabaseSync } from "node:sqlite";
import { after, test } from "node:test";
import { RECORDING_ORIGIN_FIELDS, retainPublicEvidence } from "../src/sync-public-evidence.ts";

// TypeScript's bundler imports omit extensions; resolve only this production helper for Node.
const syncUrl = new URL("../src/sync.ts", import.meta.url).href;
registerHooks({ resolve(specifier, context, nextResolve) {
  return nextResolve(context.parentURL === syncUrl && ["./sync-public-evidence", "./sync-voice-clocks"].includes(specifier) ? `${specifier}.ts` : specifier, context);
} });
const { push, pull } = await import(syncUrl);
const schema = readFileSync(new URL("../schema.sql", import.meta.url), "utf8");
after(() => console.log(`PN evidence check peak RSS: ${process.resourceUsage().maxRSS} KiB`));
const owner = "pn-public-evidence-owner";
const exactSource = ' { "text" : "  Caffè,  two spaces!  ", "segments": [] }\n';
const clock = {
  recordedAtEpochMs: 1794178800123,
  recordedAtLocal: "2026-11-09T00:00:00.123",
  recordedAtZoneId: "Europe/Rome",
  recordedAtSource: "media_metadata",
  recordedAtPrecision: "millisecond",
};
const audio = { id: "p", sessionId: "s", position: 0, sha256: "a".repeat(64), sizeBytes: 1024, ...clock };
const transcript = { id: "t", sessionId: "s", kind: "RAW", text: "Original cloud text", cloudSourceJson: exactSource };
const segment = { partId: "p", indexInPart: 0, partStartMs: 0, partEndMs: 1000, sessionStartMs: 0, text: "Words", speaker: "SPEAKER_00" };
const omitClock = (value) => Object.fromEntries(Object.entries(value).filter(([field]) => !RECORDING_ORIGIN_FIELDS.includes(field)));
const originOf = (value) => Object.fromEntries(RECORDING_ORIGIN_FIELDS.map((field) => [field, value[field]]));
const merge = (table, previous, incoming) => retainPublicEvidence(table, incoming, previous);

// Test-only adapter for the D1 methods used by sync.ts. SQL is NOT simulated: SQLite executes
// each query and transaction. It enforces D1's parameter ceiling but cannot model D1 scheduling,
// service limits, network retries or Cloudflare's runtime. No production API replacements/stubs.
class LocalStatements {
  constructor(db, sql, args = []) { this.db = db; this.sql = sql; this.args = args; }
  bind(...args) {
    assert.ok(args.length <= 100, `D1 parameter ceiling: ${args.length}`);
    return new LocalStatements(this.db, this.sql, args);
  }
  execute() {
    const statement = this.db.prepare(this.sql);
    const results = statement.columns().length ? statement.all(...this.args) : (statement.run(...this.args), []);
    const changes = this.db.prepare("SELECT changes() AS n").get().n;
    return { results, meta: { changes } };
  }
  async first() { return this.execute().results[0] ?? null; }
  async all() { return this.execute(); }
  async run() { return this.execute(); }
}

function fixture(t) {
  const db = new DatabaseSync(":memory:");
  db.exec(schema);
  t.after(() => db.close());
  const env = { DB: {
    prepare: (sql) => new LocalStatements(db, sql),
    async batch(statements) {
      db.exec("BEGIN");
      try {
        const results = statements.map((statement) => statement.execute());
        db.exec("COMMIT");
        return results;
      } catch (error) { db.exec("ROLLBACK"); throw error; }
    },
  } };
  let batch = 0;
  return {
    env, db,
    send: (deviceId, changes, batchId = `pn-evidence-${++batch}`) => push(env, owner, { protocolVersion: 1, deviceId, batchId, changes }),
    read: async (id, device = "C-new") => (await pull(env, owner, device, 0, 200)).changes.find((change) => change.id === id),
  };
}
const update = (tbl, payload, hash, baseHash = "", extra = {}) => ({ tbl, id: payload.id, op: "U", updatedAt: 1, hash, baseHash, payload, ...extra });
const deletion = (tbl, id, hash, baseHash) => ({ tbl, id, op: "D", updatedAt: 2, hash, baseHash });
const assertApplied = (result, count) => { assert.equal(result.applied, count); assert.deepEqual(result.rejected, []); };
const assertRejected = (result, tbl, id, reason) => {
  assert.equal(result.applied, 0);
  assert.deepEqual(result.rejected, [{ tbl, id, reason }]);
};

test("pure helper preserves exact source including empty and whitespace, with null/omitted unknown", () => {
  for (const source of [exactSource, "", "  \n\t"]) {
    const known = { ...transcript, cloudSourceJson: source };
    for (const unknown of [{ id: "t", text: "Edited" }, { id: "t", text: "Edited", cloudSourceJson: null }]) {
      const result = merge("transcripts", known, unknown);
      assert.equal(result.conflict, false);
      assert.deepEqual(result.payload, { ...unknown, cloudSourceJson: source });
      assert.equal(merge("transcripts", unknown, known).payload.cloudSourceJson, source);
    }
    for (const conflict of [`${source} `, source === "" ? exactSource : ""]) {
      assert.equal(merge("transcripts", known, { ...known, cloudSourceJson: conflict }).conflict, true);
    }
  }
  for (const cloudSourceJson of [null, "", exactSource]) {
    const incoming = { ...transcript, cloudSourceJson };
    assert.deepEqual(merge("transcripts", undefined, incoming), { payload: incoming, conflict: false });
  }
});

test("pure helper matches Android clock strength, enrichment, conflicts and coherent zone representation", () => {
  const day = { ...omitClock(audio), recordedAtLocal: "2026-11-09", recordedAtPrecision: "day", recordedAtSource: "filename" };
  assert.deepEqual(originOf(merge("audio_parts", audio, day).payload), clock);
  assert.deepEqual(originOf(merge("audio_parts", day, audio).payload), clock);
  const partial = { ...omitClock(audio), recordedAtEpochMs: clock.recordedAtEpochMs };
  assert.deepEqual(originOf(merge("audio_parts", audio, partial).payload), clock);
  const utc = { ...audio, recordedAtLocal: "2026-11-08T23:00:00.123", recordedAtZoneId: "UTC" };
  assert.deepEqual(merge("audio_parts", audio, utc).payload, utc);
  const weakerUtc = { ...utc, recordedAtPrecision: "second" };
  assert.deepEqual(originOf(merge("audio_parts", audio, weakerUtc).payload), clock);
  for (const incoming of [
    { ...audio, recordedAtEpochMs: clock.recordedAtEpochMs + 1 },
    { ...day, recordedAtLocal: "2026-11-10" },
    { ...audio, recordedAtEpochMs: null, recordedAtZoneId: "UTC" },
  ]) assert.equal(merge("audio_parts", { ...audio, recordedAtEpochMs: incoming.recordedAtEpochMs === null ? null : clock.recordedAtEpochMs }, incoming).conflict, true);
});

test("partial clocks never borrow local time across different or unknown zones", () => {
  const rome = { ...audio, recordedAtEpochMs: Date.parse("2026-11-08T23:30:00Z"), recordedAtLocal: "2026-11-09T00:30", recordedAtPrecision: "minute" };
  const utc = { ...rome, recordedAtLocal: null, recordedAtZoneId: "UTC", recordedAtPrecision: "millisecond" };
  for (const other of [rome, { ...rome, recordedAtZoneId: null }]) {
    for (const [previous, incoming] of [[other, utc], [utc, other]]) {
      const result = merge("audio_parts", previous, incoming);
      assert.equal(result.conflict, false);
      assert.deepEqual(result.payload, utc);
    }
  }
  assert.deepEqual(merge("audio_parts", audio, { ...audio, recordedAtLocal: null }).payload, audio);
});

test("push and pull retain one coherent partial clock representation", async (t) => {
  const f = fixture(t);
  const rome = { ...audio, recordedAtEpochMs: Date.parse("2026-11-08T23:30:00Z"), recordedAtLocal: "2026-11-09T00:30", recordedAtPrecision: "minute" };
  const utc = { ...rome, recordedAtLocal: null, recordedAtZoneId: "UTC", recordedAtPrecision: "millisecond" };
  assertApplied(await f.send("A", [update("audio_parts", rome, "rome")]), 1);
  assertApplied(await f.send("B", [update("audio_parts", utc, "utc", "rome")]), 1);
  assert.deepEqual((await f.read("p")).payload, utc);
});

test("minute origin survives partial metadata push and legacy replay before a real second upgrade", async (t) => {
  const minute = { ...omitClock(audio), recordedAtEpochMs: null, recordedAtLocal: "2025-09-22T10:15",
    recordedAtZoneId: null, recordedAtSource: "filename", recordedAtPrecision: "minute" };
  const partial = { ...minute, recordedAtLocal: null, recordedAtSource: "media_metadata", recordedAtPrecision: "second" };
  for (const [previous, incoming] of [[minute, partial], [partial, minute]]) {
    const f = fixture(t);
    assertApplied(await f.send("A", [update("audio_parts", previous, "first")]), 1);
    const edit = { ...incoming, position: 2, sessionId: "moved" };
    assertApplied(await f.send("B", [update("audio_parts", edit, "legacy-edit", "first")]), 1);
    const retained = await f.read("p");
    assert.deepEqual(retained.payload, { ...edit, ...originOf(minute) });
    assert.equal(retained.hash, "legacy-edit");
    assertApplied(await f.send("B", [update("audio_parts", edit, "legacy-edit", "first")]), 0);
    assertApplied(await f.send("C", [update("audio_parts", omitClock(edit), "omitted", "legacy-edit")]), 1);
    assert.deepEqual(originOf((await f.read("p")).payload), originOf(minute));
    const better = { ...edit, recordedAtLocal: "2025-09-22T10:15:07",
      recordedAtSource: "media_metadata", recordedAtPrecision: "second" };
    assertApplied(await f.send("C", [update("audio_parts", better, "measured-second", "omitted")]), 1);
    assert.deepEqual((await f.read("p")).payload, better);
  }
});

test("pure helper requires matching valid audio identity and never retains unrelated fields", () => {
  for (const changed of [
    { id: "other" }, { sha256: "b".repeat(64) }, { sizeBytes: 1025 }, { sha256: "" }, { sha256: "opaque" },
    { sizeBytes: Number.MAX_SAFE_INTEGER + 1 },
  ]) {
    const incoming = { ...omitClock(audio), ...changed };
    assert.deepEqual(merge("audio_parts", audio, incoming), { payload: incoming, conflict: false });
  }
  const incoming = { ...omitClock(audio), position: 3, sessionId: "moved", sha256: audio.sha256.toUpperCase() };
  assert.deepEqual(merge("audio_parts", { ...audio, privateField: "old secret", arbitrary: "old" }, incoming).payload, { ...incoming, ...clock });
  assert.deepEqual(merge("sources", audio, incoming).payload, incoming);
  for (const opaque of [null, "opaque legacy payload", [], 42]) assert.deepEqual(merge("audio_parts", audio, opaque).payload, opaque);
});

test("A new -> B legacy edit -> C new retains evidence before A returns, with opaque protocol-1 hashes", async (t) => {
  const f = fixture(t);
  assertApplied(await f.send("A-new", [update("audio_parts", audio, "opaque-a"), update("transcripts", transcript, "opaque-t", "", { segments: [segment] })]), 2);
  const bAudio = omitClock((await f.read("p", "B-legacy")).payload);
  const { cloudSourceJson: _ignored, ...bTranscript } = (await f.read("t", "B-legacy")).payload;
  bAudio.position = 2;
  bAudio.sessionId = "moved";
  bTranscript.text = "Legacy transcript edit";
  const legacySegment = { ...segment, sessionStartMs: 3000 };
  delete legacySegment.speaker;
  const changes = [update("audio_parts", bAudio, "legacy-a", "opaque-a"), update("transcripts", bTranscript, "legacy-t", "opaque-t", { segments: [legacySegment] })];
  const result = await f.send("B-legacy", changes, "legacy-edit");
  assertApplied(result, 2);
  const cAudio = await f.read("p");
  const cTranscript = await f.read("t");
  assert.deepEqual(cAudio.payload, { ...bAudio, ...clock });
  assert.equal(cTranscript.payload.cloudSourceJson, exactSource);
  assert.equal(cTranscript.payload.text, bTranscript.text);
  assert.deepEqual(cTranscript.segments, [{ ...legacySegment, speaker: segment.speaker }]);
  assert.equal(cAudio.hash, "legacy-a");
  assert.equal(cTranscript.hash, "legacy-t");
  assert.deepEqual(await f.send("B-legacy", changes, "legacy-edit"), result); // recorded batch
  const duplicate = await f.send("B-legacy", changes); // same hashes, new batch
  assertApplied(duplicate, 0);
  assert.equal(duplicate.seq, result.seq);
  // New client may republish its enriched hash once, still based on the opaque legacy hash.
  assertApplied(await f.send("C-new", [update("audio_parts", cAudio.payload, "new-enriched-a", "legacy-a"), update("transcripts", cTranscript.payload, "new-enriched-t", "legacy-t", { segments: cTranscript.segments })]), 2);
  assertApplied(await f.send("C-new", [update("audio_parts", cAudio.payload, "new-enriched-a", "legacy-a")]), 0);
});

test("push explicit null remains unknown; exact empty source remains known; new null stays null", async (t) => {
  const f = fixture(t);
  assertApplied(await f.send("A", [update("audio_parts", audio, "a"), update("transcripts", { ...transcript, cloudSourceJson: "" }, "t")]), 2);
  const unknownAudio = { ...omitClock(audio), ...Object.fromEntries(RECORDING_ORIGIN_FIELDS.map((field) => [field, null])), position: 1 };
  assertApplied(await f.send("B", [update("audio_parts", unknownAudio, "b", "a"), update("transcripts", { ...transcript, cloudSourceJson: null, text: "Edited" }, "u", "t")]), 2);
  assert.deepEqual(originOf((await f.read("p")).payload), clock);
  assert.equal((await f.read("t")).payload.cloudSourceJson, "");
  assertApplied(await f.send("B", [update("transcripts", { id: "null", cloudSourceJson: null }, "null")]), 1);
  assert.equal((await f.read("null")).payload.cloudSourceJson, null);
});

test("known conflicts reject the entire row, even for current writer, without changing segments/seq", async (t) => {
  const f = fixture(t);
  const initial = await f.send("A", [update("audio_parts", audio, "a"), update("transcripts", transcript, "t", "", { segments: [segment] })]);
  for (const source of ["", JSON.stringify(JSON.parse(exactSource)), `${exactSource} `]) {
    const result = await f.send("A", [update("transcripts", { ...transcript, cloudSourceJson: source, text: "Must not replace" }, `conflict-${source}`, "t", { segments: [] })]);
    assertRejected(result, "transcripts", "t", "stale");
    assert.equal(result.seq, initial.seq);
    assert.equal((await f.read("t")).payload.cloudSourceJson, exactSource);
    assert.deepEqual((await f.read("t")).segments, [segment]);
  }
  assertRejected(await f.send("A", [update("audio_parts", { ...audio, recordedAtEpochMs: clock.recordedAtEpochMs + 1 }, "conflict-a", "a")]), "audio_parts", "p", "stale");
  assert.deepEqual(originOf((await f.read("p")).payload), clock);
  // An identical opaque hash remains the old protocol's no-op, not a payload replacement.
  assertApplied(await f.send("A", [update("transcripts", { ...transcript, cloudSourceJson: "not stored" }, "t")]), 0);
  assert.equal((await f.read("t")).payload.cloudSourceJson, exactSource);
});

test("changed audio content drops the old origin and accepts the new content's own clock", async (t) => {
  const f = fixture(t);
  assertApplied(await f.send("A", [update("audio_parts", audio, "a")]), 1);
  const replacement = { ...omitClock(audio), sha256: "b".repeat(64), recordedAtEpochMs: null };
  assertApplied(await f.send("B", [update("audio_parts", replacement, "b", "a")]), 1);
  assert.deepEqual((await f.read("p")).payload, replacement);
  const supplied = { ...replacement, ...clock, recordedAtEpochMs: clock.recordedAtEpochMs + 1000 };
  assertApplied(await f.send("B", [update("audio_parts", supplied, "c", "b")]), 1);
  assert.deepEqual((await f.read("p")).payload, supplied);
  const resized = { ...omitClock(supplied), sizeBytes: supplied.sizeBytes + 1 };
  assertApplied(await f.send("B", [update("audio_parts", resized, "d", "c")]), 1);
  assert.deepEqual((await f.read("p")).payload, resized);
});

test("deletion, stale resurrection and explicit recreation do not immortalize public evidence", async (t) => {
  const f = fixture(t);
  assertApplied(await f.send("A", [update("audio_parts", audio, "a"), update("transcripts", transcript, "t", "", { segments: [segment] })]), 2);
  assertApplied(await f.send("B", [deletion("audio_parts", "p", "da", "a"), deletion("transcripts", "t", "dt", "t")]), 2);
  assert.equal((await f.read("p")).op, "D");
  assert.equal((await f.read("t")).payload, undefined);
  assert.equal(f.db.prepare("SELECT COUNT(*) AS n FROM segment_chunks").get().n, 0);
  assertRejected(await f.send("A", [update("audio_parts", audio, "stale-a", "a")]), "audio_parts", "p", "stale");
  const newAudio = { ...omitClock(audio), recordedAtLocal: "2027-01-01", recordedAtPrecision: "day" };
  const newTranscript = { ...transcript, cloudSourceJson: "replacement source" };
  assertApplied(await f.send("C", [update("audio_parts", newAudio, "new-a", "da"), update("transcripts", newTranscript, "new-t", "dt")]), 2);
  assert.deepEqual((await f.read("p")).payload, newAudio);
  assert.deepEqual((await f.read("t")).payload, newTranscript);
  // A separate unknown recreation has no old clock or source to inherit either.
  assertApplied(await f.send("C", [deletion("audio_parts", "p", "da2", "new-a"), deletion("transcripts", "t", "dt2", "new-t")]), 2);
  const { cloudSourceJson: _ignored, ...noSource } = transcript;
  assertApplied(await f.send("C", [update("audio_parts", omitClock(audio), "unknown-a", "da2"), update("transcripts", noSource, "unknown-t", "dt2")]), 2);
  assert.deepEqual((await f.read("p")).payload, omitClock(audio));
  assert.deepEqual((await f.read("t")).payload, noSource);
});

test("wrong bases stay stale, same-device lost responses remain compatible, and other owners stay isolated", async (t) => {
  const f = fixture(t);
  assertApplied(await f.send("A", [update("audio_parts", audio, "a")]), 1);
  const legacy = { ...omitClock(audio), position: 1 };
  assertRejected(await f.send("B", [update("audio_parts", legacy, "b", "wrong")]), "audio_parts", "p", "stale");
  assert.deepEqual((await f.read("p")).payload, audio);
  assertApplied(await f.send("A", [update("audio_parts", legacy, "b", "old-lost-response-base")]), 1);
  assert.deepEqual(originOf((await f.read("p")).payload), clock);
  assertApplied(await push(f.env, "other-owner", { deviceId: "B", batchId: "other", changes: [update("audio_parts", legacy, "other")] }), 1);
  assert.deepEqual((await pull(f.env, "other-owner", "C", 0, 200)).changes[0].payload, legacy);
  // Old envelopes without protocolVersion and opaque primitive payloads remain accepted.
  assertApplied(await push(f.env, owner, { deviceId: "legacy", batchId: "opaque-payload", changes: [
    { tbl: "transcripts", id: "opaque", op: "U", updatedAt: 0, payload: "legacy primitive" },
  ] }), 1);
  assert.equal((await f.read("opaque")).payload, "legacy primitive");
});

test("retained UTF-8 source counts towards row limits; oversized rows/segments cannot discard evidence", async (t) => {
  const f = fixture(t);
  const longSource = "é".repeat(400_000); // 800 KB UTF-8, not 400 KB
  const initial = { ...transcript, cloudSourceJson: longSource };
  assertApplied(await f.send("A", [update("transcripts", initial, "t", "", { segments: [segment] })]), 1);
  const incoming = { id: "t", text: "x".repeat(1_100_000) };
  assertRejected(await f.send("B", [update("transcripts", incoming, "large", "t", { segments: [] })]), "transcripts", "t", "too_large");
  assert.equal((await f.read("t")).payload.cloudSourceJson, longSource);
  const giantSegment = { ...segment, text: "x".repeat(1_900_001) };
  assertRejected(await f.send("B", [update("transcripts", { id: "t", text: "OK" }, "big-segment", "t", { segments: [giantSegment] })]), "transcripts", "t", "too_large");
  assert.deepEqual((await f.read("t")).segments, [segment]);
  assertApplied(await f.send("B", [update("transcripts", { id: "t", text: "OK" }, "small", "t", { segments: [segment] })]), 1);
  assert.equal((await f.read("t")).payload.cloudSourceJson, longSource);
});

test("same-hash partial-batch segment repair retains source; a conflicting repair cannot overwrite it", async (t) => {
  const f = fixture(t);
  assertApplied(await f.send("A", [update("transcripts", transcript, "opaque", "", { segments: [segment] })]), 1);
  f.db.exec("DELETE FROM segment_chunks"); // historical partial write, not a new production behavior
  const legacy = { id: "t", sessionId: "s", text: transcript.text };
  assertApplied(await f.send("A", [update("transcripts", legacy, "opaque", "", { segments: [segment] })]), 1);
  assert.equal((await f.read("t")).payload.cloudSourceJson, exactSource);
  assert.deepEqual((await f.read("t")).segments, [segment]);
  f.db.exec("DELETE FROM segment_chunks");
  assertRejected(await f.send("A", [update("transcripts", { ...legacy, cloudSourceJson: "wrong" }, "opaque", "", { segments: [segment] })]), "transcripts", "t", "stale");
  assert.equal((await f.read("t")).payload.cloudSourceJson, exactSource);
});

test("existing folder kind/voice null/speaker rules, statement groups and chunk limits still apply", async (t) => {
  const f = fixture(t);
  const folder = { id: "f", kind: "personal", title: "Folder" };
  const session = { id: "s", voiceNames: '{"p|SPEAKER_00":"Marco"}' };
  const segments = Array.from({ length: 801 }, (_, indexInPart) => ({ ...segment, indexInPart }));
  assertApplied(await f.send("A", [update("folders", folder, "f"), update("sessions", session, "s"), update("transcripts", transcript, "t", "", { segments })]), 3);
  const legacySegments = segments.map(({ speaker: _ignored, ...value }) => ({ ...value, sessionStartMs: 5000 }));
  legacySegments[1].text = "Retranscribed";
  const many = Array.from({ length: 95 }, (_, index) => update("audio_parts", { ...audio, id: `audio-${index}` }, `audio-${index}`));
  assertApplied(await f.send("B", [update("folders", { id: "f", title: "Renamed" }, "f2", "f"), update("sessions", { id: "s" }, "s2", "s"), update("transcripts", { id: "t", text: "Reordered" }, "t2", "t", { segments: legacySegments }), ...many]), 98);
  assert.equal((await f.read("f")).payload.kind, "personal");
  assert.equal((await f.read("s")).payload.voiceNames, session.voiceNames);
  const got = await f.read("t");
  assert.equal(got.payload.cloudSourceJson, exactSource);
  assert.equal(got.segments.length, 801);
  assert.equal(got.segments[0].speaker, "SPEAKER_00");
  assert.equal(got.segments[1].speaker, undefined);
  assert.equal(f.db.prepare("SELECT COUNT(*) AS n FROM segment_chunks").get().n, 3);
  assertApplied(await f.send("B", [update("sessions", { id: "s", voiceNames: null }, "s3", "s2")]), 1);
  assert.equal((await f.read("s")).payload.voiceNames, null);
  // Stored-evidence projection is sliced with the existing <=90-id lookup, no extra bind params.
  assertApplied(await f.send("B", many.map((change) => update("audio_parts", { ...omitClock(change.payload), position: 9 }, `${change.hash}-legacy`, change.hash))), 95);
  assert.deepEqual(originOf((await f.read("audio-94")).payload), clock);
});

test("concurrent replacement after snapshot read loses the seq guard without copying stale provenance", async (t) => {
  const f = fixture(t);
  assertApplied(await f.send("A", [update("audio_parts", audio, "a")]), 1);
  const realBatch = f.env.DB.batch;
  let raced = false;
  const replacement = { ...omitClock(audio), sha256: "b".repeat(64) };
  f.env.DB.batch = async (statements) => {
    if (!raced && statements[0].sql.startsWith("UPDATE owners SET seq = seq +")) {
      raced = true;
      f.db.prepare("UPDATE owners SET seq = seq + 1 WHERE ownerId = ?").run(owner);
      f.db.prepare("UPDATE state SET payload = ?, hash = 'concurrent', deviceId = 'racer', seq = (SELECT seq FROM owners WHERE ownerId = ?) WHERE ownerId = ? AND rowId = 'p'").run(JSON.stringify(replacement), owner, owner);
    }
    return realBatch(statements);
  };
  assertRejected(await f.send("B", [update("audio_parts", { ...omitClock(audio), position: 1 }, "b", "a")]), "audio_parts", "p", "stale");
  assert.equal(raced, true);
  assert.deepEqual((await f.read("p")).payload, replacement);
});
