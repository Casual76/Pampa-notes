/** Offline production-helper regression; shared cases also run against native/JVM merge code.
 * node --single-threaded --v8-pool-size=1 --max-old-space-size=64 --test --test-concurrency=1 --test-isolation=none tests/pn-recording-origin-tuple.test.mjs
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { RECORDING_ORIGIN_FIELDS, retainPublicEvidence } from "../src/sync-public-evidence.ts";

const cases = JSON.parse(readFileSync(new URL("../../core/src/test/resources/recording-origin-merge-cases.json", import.meta.url), "utf8"));
const base = { id: "p", sessionId: "s", position: 0, sha256: "a".repeat(64), sizeBytes: 7 };
const origin = (value) => Object.fromEntries(RECORDING_ORIGIN_FIELDS.map((field) => [field, value[field] ?? null]));

for (const item of cases) {
  test(item.name, () => {
    for (const reverse of [false, true]) {
      const previousOrigin = reverse ? item.incoming : item.previous;
      const incomingOrigin = reverse ? item.previous : item.incoming;
      const expected = item[reverse ? item.reverseExpected ?? item.expected : item.expected];
      // Both omitted and explicit null fields occur in older clients' public payloads.
      for (const explicitNulls of [false, true]) {
        const previous = { ...base, ...(explicitNulls ? origin(previousOrigin) : previousOrigin) };
        const incoming = { ...base, ...(explicitNulls ? origin(incomingOrigin) : incomingOrigin), sessionId: "moved", position: 2 };
        const result = retainPublicEvidence("audio_parts", incoming, previous);
        assert.equal(result.conflict, item.conflict === true, `reverse=${reverse}, nulls=${explicitNulls}`);
        if (item.conflict) {
          assert.deepEqual(result.payload, incoming);
          continue;
        }
        assert.deepEqual(origin(result.payload), origin(expected));
        assert.deepEqual({ ...result.payload, ...origin(result.payload) }, { ...incoming, ...origin(expected) });
        assert.equal(result.payload.sessionId, "moved");
        assert.equal(result.payload.position, 2);
        // Replaying a legacy snapshot cannot progressively manufacture an enriched tuple.
        const replay = retainPublicEvidence("audio_parts", incoming, result.payload);
        assert.equal(replay.conflict, false);
        assert.deepEqual(origin(replay.payload), origin(expected));
      }
    }
  });
}

test("new or replacement content retains its own nullable partial tuple unchanged", () => {
  const previous = { ...base, ...cases[0].previous };
  const incoming = { ...base, recordedAtLocal: null, ...cases[0].incoming };
  for (const stored of [undefined, { ...previous, id: "other" }, { ...previous, sha256: "b".repeat(64) },
    { ...previous, sha256: "opaque" }, { ...previous, sizeBytes: 8 }]) {
    assert.deepEqual(retainPublicEvidence("audio_parts", incoming, stored), { payload: incoming, conflict: false });
  }
  assert.deepEqual(retainPublicEvidence("audio_parts", { ...base }, { ...base }), { payload: { ...base }, conflict: false });
});
