/** Offline helper + actual production SQL. No server, D1 service, network or cloud runtime.
 * node --single-threaded --v8-pool-size=1 --max-old-space-size=64 --test --test-concurrency=1 --test-isolation=none tests/combined-source-wire-b-regression.test.mjs
 */
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { DatabaseSync } from "node:sqlite";
import { deflateSync, inflateSync } from "node:zlib";
import { after, test } from "node:test";
import { retainWirePublicEvidence, retainPublicEvidence } from "../src/sync-public-evidence.ts";

const syncUrl = new URL("../src/sync.ts", import.meta.url).href;
registerHooks({ resolve(specifier, context, nextResolve) {
  return nextResolve(context.parentURL === syncUrl && ["./sync-public-evidence", "./sync-voice-clocks"].includes(specifier) ? `${specifier}.ts` : specifier, context);
} });
const { push, pull } = await import(syncUrl);
const schema = readFileSync(new URL("../schema.sql", import.meta.url), "utf8");
after(() => console.log(`Combined B peak RSS: ${process.resourceUsage().maxRSS} KiB`));
const sha = (text) => createHash("sha256").update(text).digest("hex");
const prefix = "pampa-cloud-source-sync:1:";
const text = '  Exact,\tcloud! 😀\r\n';
const second = " Second part. ";
const part = (id, text) => ({ id, sha256: sha(`audio-${id}`), text, language: "Italian", provider: "openrouter", model: "pinned",
  responses: [{ index: 0, startMs: 0, endMs: 3000, text, language: "Italian", audioSha256: sha(`audio-${id}`), provider: "openrouter", model: "pinned" }] });
const parts = [part("a", text), part("b", second)];
const raw = [text, second].join("\n\n");
const document = { version: 1, rawId: "t", rawVersion: 42, textSha256: sha(raw), parts,
  originals: [{ rawId: "t", rawVersion: 42, text: raw, textSha256: sha(raw), provider: "openrouter", model: "pinned", language: "Italian",
    parts, reportedText: "  Distinct runner report.\n", contentScope: "complete_original" }], overlays: [], alignmentBinding: "source_snapshot" };
const source = JSON.stringify(document);
const row = (sourceJson = source) => ({ id: "t", sessionId: "s", kind: "RAW", text: raw, createdAt: 42, cloudSourceJson: sourceJson });
const overlay = (id, measured = 1, start = 100) => {
  const p = parts.find((p) => p.id === id);
  return { partId: id, textSha256: sha(p.text), language: "it", timings: [...p.text.matchAll(/\S+/gu)].map((m, i) => ({
    text_start: m.index, text_end: m.index + m[0].length, start_ms: i < measured ? start + i * 200 : null,
    end_ms: i < measured ? start + i * 200 + 100 : null, status: i < measured ? "measured" : "missing", reason: null,
  })) };
};
const withOverlays = (...overlays) => JSON.stringify({ ...document, overlays });

// A spec-generated v1 fixture, NOT an assertion that Kotlin executed. The prepared Kotlin
// CombinedSourceSyncGateBTest consumes these exact literal/RAW/PREVIOUS command fixtures too.
function envelope(source, raw, references = false) {
  const bytes = [], byte = (n) => bytes.push(n), int = (n) => bytes.push((n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255);
  int(source.length);
  for (const hash of [sha(source), sha(raw)]) { byte(0); byte(64); for (const c of hash) byte(c.charCodeAt(0)); }
  const dictionary = JSON.stringify(raw).slice(1, -1);
  let cursor = 0, from = 0; const seen = new Map();
  const literal = (end) => { while (from < end) {
    let to = Math.min(end, from + 4096); if (to < end && /[\ud800-\udbff]/.test(source[to - 1])) to--;
    const literal = Buffer.from(source.slice(from, to)); byte(0); int(literal.length); for (const b of literal) byte(b); from = to;
  } };
  if (references) while (cursor < source.length) {
    if (source[cursor++] !== '"') continue; const start = cursor - 1;
    while (cursor < source.length) { const c = source[cursor++]; if (c === "\\" && cursor < source.length) cursor++; else if (c === '"') break; }
    const token = source.slice(start, cursor); if (token.length < 64) continue;
    const previous = seen.get(token), rawAt = dictionary.indexOf(token.slice(1, -1));
    if (previous !== undefined || rawAt >= 0) { literal(start); byte(previous !== undefined ? 2 : 1); int(previous ?? rawAt); int(previous !== undefined ? token.length : token.length - 2); from = cursor; }
    if (!seen.has(token)) seen.set(token, start);
  }
  literal(source.length); byte(3);
  return prefix + deflateSync(Buffer.from(bytes)).toString("base64");
}
function expandFixture(encoded, raw) {
  const bytes = inflateSync(Buffer.from(encoded.slice(prefix.length), "base64")); let cursor = 0;
  const int = () => { const n = bytes.readInt32BE(cursor); cursor += 4; return n; };
  const length = int(); const hash = () => { assert.equal(bytes.readUInt16BE(cursor), 64); cursor += 2; const s = bytes.toString("ascii", cursor, cursor + 64); cursor += 64; return s; };
  const expected = hash(); assert.equal(hash(), sha(raw)); const dictionary = JSON.stringify(raw).slice(1, -1); let result = "";
  while (true) { const op = bytes[cursor++]; if (op === 3) break;
    if (op === 0) { const n = int(); result += bytes.toString("utf8", cursor, cursor + n); cursor += n; }
    else { const start = int(), n = int(); result += op === 1 ? `"${dictionary.slice(start, start + n)}"` : result.slice(start, start + n); }
  }
  assert.equal(cursor, bytes.length); assert.equal(result.length, length); assert.equal(sha(result), expected); return result;
}
class LocalStatement {
  constructor(db, sql, args = []) { Object.assign(this, { db, sql, args }); }
  bind(...args) { assert.ok(args.length <= 100); return new LocalStatement(this.db, this.sql, args); }
  execute() { const stmt = this.db.prepare(this.sql); const results = stmt.columns().length ? stmt.all(...this.args) : (stmt.run(...this.args), []);
    return { results, meta: { changes: this.db.prepare("SELECT changes() AS n").get().n } }; }
  async first() { return this.execute().results[0] ?? null; } async all() { return this.execute(); } async run() { return this.execute(); }
}
function fixture(t) {
  const db = new DatabaseSync(":memory:"); db.exec(schema); t.after(() => db.close());
  const env = { DB: { prepare: (sql) => new LocalStatement(db, sql), async batch(statements) {
    db.exec("BEGIN"); try { const result = statements.map((s) => s.execute()); db.exec("COMMIT"); return result; } catch (e) { db.exec("ROLLBACK"); throw e; }
  } } }; let batch = 0;
  return { db, env, send: (deviceId, changes, account = "owner") => push(env, account, { protocolVersion: 1, deviceId, batchId: `combined-b-${++batch}`, changes }),
    read: async (account = "owner") => (await pull(env, account, "reader", 0, 200)).changes.find((c) => c.id === "t") };
}
const change = (payload, hash, baseHash = "", extra = {}) => ({ tbl: "transcripts", id: "t", op: "U", updatedAt: 42, hash, baseHash, payload, ...extra });
const applied = (result, n = 1) => { assert.equal(result.applied, n); assert.deepEqual(result.rejected, []); };
const stale = (result) => { assert.equal(result.applied, 0); assert.deepEqual(result.rejected, [{ tbl: "transcripts", id: "t", reason: "stale" }]); };

test("v1 command fixture expands before immutable comparisons and repacked union validates commands", async () => {
  const encoded = envelope(source, raw);
  const result = await retainWirePublicEvidence("transcripts", row(withOverlays(overlay("a"))), row(encoded));
  assert.equal(result.conflict, false); assert.deepEqual(JSON.parse(result.payload.cloudSourceJson), JSON.parse(withOverlays(overlay("a"))));
  const merged = await retainWirePublicEvidence("transcripts", row(envelope(withOverlays(overlay("b")), raw)), row(envelope(withOverlays(overlay("a")), raw)));
  assert.equal(merged.conflict, false); assert.ok(merged.payload.cloudSourceJson.startsWith(prefix));
  assert.deepEqual(JSON.parse(expandFixture(merged.payload.cloudSourceJson, raw)).overlays, [overlay("a"), overlay("b")]);
});
test("RAW witness rejects both omitted and null envelope rewrites before production SQL changes row or segments", async (t) => {
  const f = fixture(t), stored = row(envelope(source, raw));
  applied(await f.send("A", [change(stored, "first", "", { segments: [{ partId: "a", indexInPart: 0, text: "exact" }] })]));
  for (const cloudSourceJson of [undefined, null, stored.cloudSourceJson]) {
    stale(await f.send("A", [change({ ...row(cloudSourceJson), cloudSourceJson, text: "Changed RAW" }, `bad-${cloudSourceJson}`, "first", { segments: [] })]));
    assert.deepEqual((await f.read()).payload, stored); assert.equal((await f.read()).segments.length, 1);
  }
  stale(await f.send("A", [change({ ...stored, cloudSourceJson: null, createdAt: 43 }, "changed-version", "first")]));
  for (const cloudSourceJson of [undefined, null]) {
    applied(await f.send("B", [change({ ...row(), cloudSourceJson, status: "metadata edit" }, `legacy-${cloudSourceJson}`, (await f.read()).hash)]));
    assert.equal((await f.read()).payload.cloudSourceJson, stored.cloudSourceJson); assert.equal((await f.read()).payload.text, raw);
  }
});
test("expanded typed and future-typed sources cannot move onto changed RAW; opaque expanded legacy evidence remains exact", async () => {
  for (const known of [source, JSON.stringify({ ...document, futureEvidence: "EXACT" })]) {
    for (const cloudSourceJson of [undefined, null, known]) assert.equal((await retainWirePublicEvidence("transcripts", { ...row(), text: "Changed", cloudSourceJson }, row(known))).conflict, true);
  }
  const result = await retainWirePublicEvidence("transcripts", { ...row(), text: "Edited", cloudSourceJson: null }, row(" opaque legacy source "));
  assert.equal(result.conflict, false); assert.equal(result.payload.cloudSourceJson, " opaque legacy source ");
});
test("same-original overlay enrichment converges via actual SQL, retains immutable responses and opaque client hashes", async (t) => {
  const f = fixture(t), a = row(envelope(withOverlays(overlay("a")), raw)), b = row(envelope(withOverlays(overlay("b")), raw));
  applied(await f.send("A", [change(a, "opaque-A")]));
  applied(await f.send("B", [change(b, "opaque-B", "opaque-A")]));
  let current = await f.read(); assert.equal(current.hash, "opaque-B");
  const merged = JSON.parse(expandFixture(current.payload.cloudSourceJson, raw));
  assert.deepEqual(merged.overlays, [overlay("a"), overlay("b")]); assert.deepEqual(merged.originals, document.originals);
  applied(await f.send("C", [change(current.payload, "canonical-expanded-hash", "opaque-B")]));
  current = await f.read(); applied(await f.send("C", [change(current.payload, current.hash, "opaque-B")]), 0);
  stale(await f.send("D", [change(a, "stale", "wrong-base")]));
  assert.equal((await f.read()).hash, "canonical-expanded-hash");
});
test("more measured tokens and independent parts merge deterministically in both directions", async () => {
  const a = row(withOverlays(overlay("a", 1))), b = row(withOverlays(overlay("a", 2), overlay("b", 2)));
  const ab = await retainWirePublicEvidence("transcripts", b, a), ba = await retainWirePublicEvidence("transcripts", a, b);
  assert.equal(ab.conflict, false); assert.equal(ba.conflict, false); assert.equal(ab.payload.cloudSourceJson, ba.payload.cloudSourceJson);
  const concurrent = row(withOverlays(overlay("a", 1, 200)));
  const left = await retainWirePublicEvidence("transcripts", concurrent, a), right = await retainWirePublicEvidence("transcripts", a, concurrent);
  assert.equal(left.payload.cloudSourceJson, right.payload.cloudSourceJson);
});
test("immutable source response language window model and exact whitespace conflicts stay rejected", async () => {
  for (const mutate of [
    (d) => { d.originals[0].reportedText += " "; },
    (d) => { d.parts[0].responses[0].text += " "; },
    (d) => { d.parts[0].responses[0].language = null; },
    (d) => { d.parts[0].responses[0].endMs++; },
    (d) => { d.parts[0].responses[0].model = "other"; },
    (d) => { d.parts[0].sha256 = sha("replacement"); },
  ]) { const doc = structuredClone(document); mutate(doc); assert.equal((await retainWirePublicEvidence("transcripts", row(JSON.stringify(doc)), row(source))).conflict, true); }
});
test("unknown root nested part original response or timing metadata is retained byte-for-byte and rejects lossy overlay rewrite", async () => {
  for (const mutate of [
    (d) => { d.futureEvidence = "EXACT"; }, (d) => { d.parts[0].futureEvidence = "EXACT"; },
    (d) => { d.originals[0].futureEvidence = "EXACT"; }, (d) => { d.parts[0].responses[0].futureEvidence = "EXACT"; },
    (d) => { d.overlays = [overlay("a")]; d.overlays[0].timings[0].futureEvidence = "EXACT"; },
  ]) {
    const d = structuredClone(document); mutate(d); const future = ` \n${JSON.stringify(d)}\t`;
    const kept = await retainWirePublicEvidence("transcripts", { ...row(), cloudSourceJson: null }, row(future));
    assert.equal(kept.conflict, false); assert.equal(kept.payload.cloudSourceJson, future);
    assert.equal((await retainWirePublicEvidence("transcripts", row(withOverlays(overlay("a"))), row(future))).conflict, true);
  }
});
test("delete stale resurrection recreate and account separation preserve protocol guards", async (t) => {
  const f = fixture(t); applied(await f.send("A", [change(row(envelope(source, raw)), "live")]));
  applied(await f.send("B", [{ ...change(undefined, "dead", "live"), op: "D" }]));
  stale(await f.send("C", [change(row(), "old", "live")]));
  applied(await f.send("C", [change({ ...row(), cloudSourceJson: null, text: "new recording" }, "new", "dead")]));
  assert.equal((await f.read()).payload.cloudSourceJson, null);
  applied(await f.send("C", [change(row(envelope(source, raw)), "other")], "other-account"));
  assert.equal((await f.read("other-account")).payload.cloudSourceJson, envelope(source, raw));
  assert.equal((await f.read()).payload.text, "new recording");
});
test("malformed version RAW hash checksum references and trailing compressed data reject without exposing contents", async () => {
  const encoded = envelope(source, raw), bytes = inflateSync(Buffer.from(encoded.slice(prefix.length), "base64"));
  const badHash = Buffer.from(bytes); badHash[6] = badHash[6] === 97 ? 98 : 97;
  for (const value of [encoded.replace(":1:", ":2:"), prefix + "!invalid!", envelope(source, "other raw"),
    prefix + deflateSync(badHash).toString("base64"), prefix + Buffer.concat([Buffer.from(encoded.slice(prefix.length), "base64"), Buffer.from([0])]).toString("base64")]) {
    assert.equal((await retainWirePublicEvidence("transcripts", row(value), undefined)).conflict, true);
  }
});
test("same seq snapshot protects the RAW witness against a concurrent changed-RAW replacement", async (t) => {
  const f = fixture(t); applied(await f.send("A", [change(row(envelope(source, raw)), "live")]));
  const realBatch = f.env.DB.batch; let raced = false;
  f.env.DB.batch = async (statements) => {
    if (!raced && statements[0].sql.startsWith("UPDATE owners SET seq = seq +")) {
      raced = true; f.db.prepare("UPDATE owners SET seq = seq + 1 WHERE ownerId = 'owner'").run();
      f.db.prepare("UPDATE state SET payload = ?, hash = 'raced', deviceId = 'racer', seq = (SELECT seq FROM owners WHERE ownerId = 'owner') WHERE ownerId = 'owner' AND tbl = 'transcripts' AND rowId = 't'").run(JSON.stringify({ ...row(), text: "replacement", cloudSourceJson: null }));
    } return realBatch(statements);
  };
  stale(await f.send("B", [change({ ...row(), cloudSourceJson: null }, "legacy", "live")]));
  assert.equal(raced, true); assert.equal((await f.read()).payload.text, "replacement"); assert.equal((await f.read()).payload.cloudSourceJson, null);
});
test("bounded comparison uses existing durable too_large outcome while unchanged known envelope can be retained", async (t) => {
  // Valid v1 RAW-independent PREVIOUS references double a literal without a huge fixture file.
  // This exercises the explicit Worker limit; Kotlin codec execution remains a parent gate.
  const count = 8 * 1024 * 1024 + 1, frames = [];
  const int = (n) => { const bytes = Buffer.alloc(4); bytes.writeInt32BE(n); return bytes; };
  frames.push(int(count));
  for (const hash of [sha("x".repeat(count)), sha(raw)]) frames.push(Buffer.from([0, 64]), Buffer.from(hash));
  frames.push(Buffer.from([0]), int(1), Buffer.from("x"));
  for (let size = 1; size < count;) { const n = Math.min(size, count - size); frames.push(Buffer.from([2]), int(0), int(n)); size += n; }
  frames.push(Buffer.from([3]));
  const encoded = prefix + deflateSync(Buffer.concat(frames)).toString("base64"), incoming = row(encoded);
  assert.equal((await retainWirePublicEvidence("transcripts", incoming, undefined)).tooLarge, true);
  const kept = await retainWirePublicEvidence("transcripts", { ...row(), cloudSourceJson: null }, incoming);
  assert.equal(kept.conflict, false); assert.equal(kept.payload.cloudSourceJson, encoded);
  const f = fixture(t), result = await f.send("A", [change(incoming, "bounded")]);
  assert.equal(result.applied, 0); assert.deepEqual(result.rejected, [{ tbl: "transcripts", id: "t", reason: "too_large" }]);
  assert.equal(await f.read(), undefined);
});
test("shared cross-codec fixture uses actual v1 bytes and lexical JSON including BOM Unicode and RAW references", async () => {
  const fixture = JSON.parse(readFileSync(new URL("../../core/src/test/resources/sync/combined-source-wire-v1.json", import.meta.url), "utf8"));
  for (const payload of [fixture.literalPayload, fixture.referencePayload]) {
    const result = await retainWirePublicEvidence("transcripts", payload, undefined);
    assert.equal(result.conflict, false); assert.equal(result.payload.cloudSourceJson, payload.cloudSourceJson);
    assert.equal(expandFixture(payload.cloudSourceJson, payload.text), fixture.sourceJson);
    const enriched = await retainWirePublicEvidence("transcripts", { ...payload, cloudSourceJson: fixture.overlaySourceJson }, payload);
    assert.equal(enriched.conflict, false); assert.equal(enriched.payload.cloudSourceJson, fixture.overlaySourceJson);
  }
  const result = await retainWirePublicEvidence("transcripts", fixture.workerMergedPayload, undefined);
  assert.equal(result.conflict, false);
  const merged = JSON.parse(expandFixture(result.payload.cloudSourceJson, result.payload.text));
  assert.deepEqual(merged.overlays.map((o) => o.partId), ["a", "b"]);
  assert.deepEqual({ ...merged, overlays: [] }, JSON.parse(fixture.sourceJson));
});
