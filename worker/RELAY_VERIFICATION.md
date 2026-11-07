# Relay implementation verification - 2026-11-07

Scope: `worker/` only. No deployment, commit, push, engine edit, Android edit or Gradle invocation.
The preexisting deletion of `companion/tools/__pycache__/holes.cpython-311.pyc` was preserved.
Other agents' Android changes in the shared checkout were not edited by this unit.

## Commands and observed results

Run from `worker/`:

| Command | Observed result |
|---|---|
| `npm ci --no-audit --no-fund` | Success; existing dev dependencies installed |
| `npm install --save-dev --save-exact esbuild@0.28.1 miniflare@5.20260921.0-alpha --no-audit --no-fund` | Success; existing Wrangler transitive versions made explicit for tests |
| `npm run typecheck` | Success, no TypeScript errors (final invocation after implementation/tests) |
| `npm run test:relay` | **11 passed, 0 failed**, final run 51421.7054 ms |
| `npm run test:regressions` | **7 existing suites passed** against separately initialized local Workers |
| `git diff --check -- worker` (repository root) | Success; no whitespace errors |

Real workerd/Miniflare instances, real local D1 SQL and R2 storage, short unique paths under
`worker/.wrangler/r<PID>/{p,h}` and `worker/.wrangler/g<PID>/<suite>`. No `.dev.vars` loaded; session
fixtures or the existing explicit development-token mechanism were used. No production clock
or authentication bypass was added.

Relay coverage:

- Same-account tablet/phone sessions upload, discover, resume and download matching original bytes;
  manifest and complete-file SHA-256 validated; no sync state/public share rows created.
- Missing, invalid, guest/public-style tokens, revoked sessions and another owner denied. Another
  owner's DELETE has no effect. Production ignores clock-test headers and has no cleanup route.
- Production `scheduled()` invoked through the Worker binding: observed log
  `relay cleanup { removed: 1, orphans: 0, failed: 0 }`; D1 transfer/chunk rows and R2 bytes removed.
- Injectable clock only in separate test entry: ready/pending payloads inaccessible exactly at
  creation + 600000 ms; one millisecond before still downloadable; discovery hides expiry.
- Cleanup deletes payloads/metadata and preserves persistent-share keys; unchanged begin cannot
  recreate expired/cleaned data, while explicit fresh re-send works.
- Malformed and oversized metadata, invalid paths/names/IDs/hashes, wrong byte counts, streaming
  bodies without Content-Length, chunk hash mismatch/conflict and final-file mismatch rejected.
- Atomic count/byte quota reservations under parallel begins; identical concurrent begin/chunk
  retries return one transfer/object and never extend expiry.
- Abandoned uploads, R2-delete outage/backoff/retry, rowless orphan objects, write completion across
  expiry, deletion revocation and idempotency receipts exercised.
- **256 MiB maximum file** uploaded as 32 x 8 MiB chunks, committed by streaming hash, downloaded
  again with matching total SHA-256/length, then physically removed. Final maximum-file case took
  39346.9239 ms locally; this is test wall time, not deployed Worker CPU measurement.

Existing suites: `test_protocol.py`, `test_share.py`, `test_pull_parents.py`, `test_pull_limits.py`,
`test_concurrent_push.py`, `test_runs.py`, `test_guests.py`. The share suite includes existing whole
and multipart uploads, public page/audio byte equality, ranges, headers, account isolation and
revocation. Each suite ran against a fresh production entry point/schema and separate local state.

## Integration and remaining limits

Authoritative Android contract: [RELAY_API.md](RELAY_API.md). Core routes are `/v1/relay`, status,
`chunks/{index}`, commit and DELETE. Begin requires a fresh serverTime from discovery, UUID v4
requestId and persistent retry metadata. Match synchronized kind + file sha256 + bytes; keep local
source originals and never set archivedAt. Resume at verified chunk boundaries and verify final
file before atomic placement.

Apply additive `schema.sql` to the existing D1 database before enabling the new Worker. Preserve
the AUDIO bucket's private access and install the once-per-minute trigger from `wrangler.toml`.
Authentication and exact ten-minute security expiry do not depend on cron. Physical deletion is
eventual: four due transfers plus 128 expiry-prefixed objects per tick, retained metadata and
backoff on failure. Cron jitter/backlog/outages can delay physical deletion without extending
access; no finite outage-time deletion SLA is claimed. No R2 multipart sessions are created.

Not run: deployed Cloudflare scheduling/bucket configuration/CPU-budget verification, real Google
sign-in, Android transfer/UI/device integration, paid external APIs. Large-file CPU suitability
must be validated on the deployment plan; Free Worker CPU is not guaranteed sufficient. No remote
schema command or deployment was executed. Independent integration reviews belong to the parent.
