# Private temporary file relay (API v1)

Authoritative contract for the Android transfer client. This is an opt-in transport for original
audio/source bytes between devices of one account, independent of the companion. It is **not an
archive**. Text sync, Room identities, sync cursors and persistent public shares keep their existing
behavior. Never set `archivedAt`, evict a local original, or infer a durable remote copy from a relay
upload. The source device must retain its copy. The receiver verifies bytes before atomic placement.

## Authentication and clock

Every endpoint below requires `Authorization: Bearer <existing account session token>`. The router
uses the existing `ownerOf`: existing Google credentials/development configuration have the same
semantics as sync; production `AUTH_DEV_TOKENS` remains empty. No guest token, public share token,
anonymous path or presigned R2 URL grants access. Two sessions with the same owner may upload,
discover, resume, download and delete. Device fields are caller-provided descriptive metadata, not
authorization. Cross-owner reads/mutations return 404 (DELETE always returns 202 to avoid disclosure).

All times are epoch milliseconds. `expiresAt = createdAt + 600000`, assigned by the server when begin
is accepted. **At `serverNow >= expiresAt`, every payload route denies access**, including pending
uploads, commit and retry. List omits expired/deleted entries. No activity extends expiry. Streaming
downloads check expiry again while forwarding data; bytes already delivered or buffered in transit
cannot be recalled. No production clock override exists.

## Fixed limits

`GET /v1/relay` returns these same numeric `limits`, plus `serverTime` and account reservation totals:

| Field | Value | Meaning |
|---|---:|---|
| `ttlMs` | 600000 | Ten minutes from begin, including upload time |
| `chunkBytes` | 8388608 | 8 MiB, fixed except the last chunk |
| `maxFileBytes` | 268435456 | 256 MiB (32 chunks maximum); zero bytes rejected |
| `maxOwnerBytes` | 536870912 | 512 MiB reserved declared bytes across all transfers |
| `maxOwnerTransfers` | 8 | Includes pending, expired awaiting GC, deletion receipts |
| `beginFreshnessMs` | 180000 | Freshness fence for new begin requests, described below |
| `maxJsonBytes` | 4096 | Hard bound on begin JSON, independent of Content-Length |

Quota reservation is a single conditional D1 INSERT: concurrent begins cannot bypass count/byte
limits. Reservations remain charged until physical cleanup removes the transfer metadata. Each
chunk request has an enforced byte bound even without Content-Length. Memory is bounded to a chunk;
commit streams R2 objects through native SHA-256 without retaining the full file. There is no R2
multipart session or assembled duplicate. Each immutable chunk is a normal R2 object.

The upper limits are a protocol/storage bound, **not a measured guarantee on a Free Worker CPU
budget**. For large files use Workers Paid or validate CPU limits before deployment: hashing a large
file may exceed Free's per-request CPU budget. Commit performs at most 32 sequential R2 reads plus
bounded D1 operations, rather than an unbounded fan-out. There is no new CPU-limit configuration
that silently changes the deployment plan. See [Worker limits](https://developers.cloudflare.com/workers/platform/limits/)
and [native streaming hashes](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/#constructors).

## Routes

| Method and path | Result |
|---|---|
| `GET /v1/relay[?kind=audio|source][&sha256=<hex>]` | 200 discovery, limits, server time, reservations; at most 8 entries |
| `POST /v1/relay` | 201 begin; 200 exact idempotent retry |
| `GET /v1/relay/{transferId}` | 200 metadata plus acknowledged chunk manifest (resumption/status) |
| `PUT /v1/relay/{transferId}/chunks/{index}` | 200 validated chunk acknowledgement |
| `POST /v1/relay/{transferId}/commit` | 200 complete file verified; repeatable |
| `GET /v1/relay/{transferId}/chunks/{index}` | 200 binary, only after commit; download/resume at chunk boundaries |
| `DELETE /v1/relay/{transferId}` | 202 `{ "deleted": true }`; immediate revocation, scheduled deletion |

Chunk indices are decimal, zero based, no leading zeroes except `0`. There is no byte Range API:
retain verified complete chunks and retry the current chunk. The chunk download response is always
`application/octet-stream` and has `Content-Length`, `X-Chunk-SHA256`, `X-File-SHA256`, `X-Expires-At`,
`Cache-Control: private, no-store`, and `X-Content-Type-Options: nosniff`. All relay JSON is no-store.
The authenticated status endpoint is the manifest/discovery source; R2 is not public.

## 1. Discover, then begin

First GET discovery to obtain a recent **server** time (no dependency on the device wall clock):

```json
{
  "serverTime": 1794000000000,
  "limits": { "ttlMs": 600000, "chunkBytes": 8388608, "maxFileBytes": 268435456,
    "maxOwnerBytes": 536870912, "maxOwnerTransfers": 8, "beginFreshnessMs": 180000,
    "maxJsonBytes": 4096 },
  "reserved": { "transfers": 0, "bytes": 0 },
  "transfers": []
}
```

POST `/v1/relay`, `Content-Type: application/json` (example file: the three UTF-8 bytes `abc`):

```json
{
  "requestId": "246b6bbe-5361-4143-a27b-a02700aec343",
  "requestedAt": 1794000000000,
  "kind": "source",
  "entityId": "606f3145-6020-49b1-8f27-77a349970566",
  "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  "bytes": 3,
  "mime": "text/plain",
  "originalName": "appunti.txt",
  "deviceId": "tablet-a",
  "deviceName": "Tablet"
}
```

All fields required; `deviceName` may be empty. `requestId` must be a lowercase random UUID v4.
`requestedAt` is the previously returned `serverTime` and must be no more than 180000 ms old and
not in the future on a **new** request. The actual expiry always uses current server time, never
`requestedAt`. Persist this exact begin body before sending; repeat it unchanged if the response
is lost. Existing same-owner `requestId` plus identical fields returns the same transfer/expiry
even after the begin freshness window (until transfer expiry). Different fields return 409.

The freshness fence ensures that after expired metadata is deleted, a delayed unchanged begin
retry cannot recreate the payload with a fresh TTL. On `stale_begin`, `expired`, or an expired
transfer's 404, stop automatic retries. An **explicit re-send** performs GET again and creates a
new requestId/requestedAt. A retry must never silently refresh either field.

`kind=audio` maps `entityId` to synchronized `audio_parts.id`; `kind=source` maps it to `sources.id`.
`sha256` is the synchronized **file bytes SHA-256**, not a sync-row hash. Match kind+sha256+bytes on
receipt, allowing another local row ID for identical synchronized content; entityId is a hint for
the originating row. Pending transfers may precede text-sync delivery; defer local matching until
the rows arrive. Do not create Room rows or write cloud archive markers from this metadata.

IDs/deviceId allow `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. Hashes must be 64 lowercase hexadecimal
characters. MIME is a lowercase `type/subtype` token, at most 128 characters. originalName is
display-only, 1-255 characters, excluding separators, colon, controls and `.`/`..`; deviceName has
at most 128 characters without controls. No supplied name/id is used as an R2 path. Android must
use its own validated Room/storage path for final placement, never concatenate originalName.

Begin response (201; idempotent retries return 200), also the shape of GET status:

```json
{
  "transferId": "7d866cd1-27e9-470a-9353-e0d76161f438",
  "requestId": "246b6bbe-5361-4143-a27b-a02700aec343",
  "kind": "source", "entityId": "606f3145-6020-49b1-8f27-77a349970566",
  "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  "bytes": 3, "mime": "text/plain", "originalName": "appunti.txt",
  "deviceId": "tablet-a", "deviceName": "Tablet", "state": "uploading",
  "createdAt": 1794000000000, "expiresAt": 1794000600000, "serverTime": 1794000000000,
  "chunkBytes": 8388608, "chunkCount": 1, "chunks": []
}
```

Discovery entries use the same fields except `chunks` (get status for a manifest). State is
`uploading` or `ready`; deleted entries are hidden. Device metadata describes the origin even when
a second device resumes the upload. The original TTL covers the entire exchange, so begin only
when ready to send and receive.

## 2. Upload and commit

PUT `/v1/relay/{id}/chunks/0` with the raw bytes `abc`, `Content-Type: application/octet-stream`,
`X-Chunk-SHA256: ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`.
Content-Length is recommended but not trusted. Chunk size must equal
`min(chunkBytes, bytes - index*chunkBytes)`. The server checks length/SHA-256 before any R2 write
and supplies the checksum to R2 too. Upload sequentially to bound device/Worker concurrency.

```json
{ "index": 0, "bytes": 3,
  "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  "expiresAt": 1794000600000, "serverTime": 1794000001000 }
```

An index is immutable once its validated hash is reserved. Same bytes/hash retry is idempotent;
different hash returns `chunk_conflict` without replacing bytes. A crash after reservation/before
acknowledgement is recovered by resending that same chunk. GET status lists only acknowledged
chunks, in order: `"chunks":[{"index":0,"bytes":3,"sha256":"..."}]`. After a lost response,
check this list before resuming. Do not PUT after `ready` (409); a successful commit is enough.

POST `/v1/relay/{id}/commit` needs no body. The server checks every declared chunk, streams the
entire file through SHA-256 in order, checks the total byte count, then conditionally publishes
`ready` only if still live. Incomplete uploads return 409. A wrong whole-file digest returns 422
and leaves it pending/inaccessible: delete and explicitly start over. Commit retry returns the
same ready manifest and fixed expiry. The server never serves a pending chunk, even to its owner.

## 3. Receive and resume

GET discovery with the receiver's own account session; GET status for a ready transfer's chunk
manifest. For each missing chunk, GET `/v1/relay/{id}/chunks/{index}` and verify exact length and
SHA-256 against both the manifest and response headers before retaining it in a private staging
file. Concatenate in index order, verify the final declared bytes and synchronized SHA-256, then
atomically place the file at the existing validated local path. Re-check the current Room row and
account before placement; row deletion/replacement during download must not resurrect data.

Persist transferId/expiry and verified chunk progress across process death. Retry transient I/O
or 5xx using bounded backoff within the remaining server window; re-check status after uncertain
upload/commit responses. A receiving device may keep its already verified local bytes after the
server window closes; they are local copies, not an extension of cloud storage. If remaining
chunks expire, request an explicit re-send from a device still holding the original. Never delete
the shared transfer automatically after one receiver finishes (there may be other devices).

## Errors

Relay errors are JSON `{ "error": "<Italian diagnostic>", "code": "<stable machine code>" }`.
Translate UI text using existing Android Italian/English resources; branch on `code`, not error.
Malformed percent encoding may use the existing router's generic 400 format. Unexpected internal
failures use the existing generic 500 `{ "error": "errore interno" }`.

| Status | Codes | Client action |
|---|---|---|
| 400 | `invalid_metadata`, `invalid_id`, `invalid_filter`, `invalid_chunk`, `invalid_hash`, `size_mismatch` | Fix request; do not retry unchanged |
| 401 | `unauthorized` | Restore account session; no guest/public bypass |
| 404 | `not_found` | Wrong account/id, or metadata already cleaned; check discovery/re-send explicitly |
| 409 | `incomplete`, `not_ready`, `not_uploading`, `idempotency_conflict`, `chunk_conflict` | Check status; resume missing chunks or explicitly restart |
| 410 | `expired`, `deleted`, `stale_begin` | Stop retries; explicit fresh re-send required |
| 413 | `too_large`, `file_too_large` | Respect body/file limits |
| 422 | `hash_mismatch`, `file_hash_mismatch`, `size_mismatch` | Recheck source; pending content is unavailable |
| 429 | `owner_quota` | Wait for scheduled cleanup; no TTL extension or quota evasion |
| 500 | Existing generic internal error | Backoff, then status/retry before original expiry |

Examples: `410 {"error":"Trasferimento scaduto: invialo di nuovo","code":"expired"}`;
`409 {"error":"Caricamento incompleto: controlla i chunk ricevuti","code":"incomplete"}`;
`422 {"error":"SHA-256 chunk diverso","code":"hash_mismatch"}`.

## Physical deletion, retries and rollout

Apply the additive `schema.sql` to the existing D1 database **before** enabling the new Worker:
`relay_transfers`, `relay_chunks` and their indexes are new. `CREATE IF NOT EXISTS` preserves the
existing sync/share tables; no Android database migration is implied. The existing AUDIO bucket
is reused and must remain private (no public `r2.dev` endpoint or public bucket custom domain):
only the authenticated Worker may serve relay objects. Public bucket access would bypass Worker
authentication/expiry and is outside the guarantees of this protocol. Keys are server-generated
`__relay/v1/{expiresAt}/{transferId}/{index}-{sha256}`; the
sweeper never touches persistent `{ownerId}/{shareId}/...` keys. No lifecycle-days workaround,
public access, R2 multipart or persistent assembled file is introduced.

`wrangler.toml` installs a once-per-minute Cron Trigger. `scheduled()` awaits `cleanupRelay`:
each run processes at most 4 due transfers (128 keys per prefix page), deletes bytes before their
metadata, and retries failures without losing the queue. Per-row backoff is 1, 2, 4, then 5 minutes
(capped). Failed scheduled runs surface as failures in Worker logs. A separate oldest-first scan
of 128 R2 keys per run removes expired rowless/staging objects, including writes racing metadata
deletion or a terminated isolate. Failed orphan deletion retries next cron; no user access is
required. No multipart uploads exist to leak or need aborting. Canceled transfers lose access
immediately, bytes are scheduled for deletion, and a private idempotency receipt/reservation remains
until original expiry, then is collected. Late writes after cancellation remain inaccessible and
are collected no later than the subsequent successful expired-prefix sweep.

**Physical deletion is eventual, not guaranteed at precisely ten minutes.** In normal low load it
starts on the next minute tick; backlog, R2/D1 outages, scheduling jitter and bounded batches add
latency, without a finite hard upper bound during an outage. Trigger configuration propagation can
take up to 15 minutes ([Cloudflare Cron documentation](https://developers.cloudflare.com/workers/configuration/cron-triggers/)).
Security expiry is checked in every operation and does not wait for cron. Monitor scheduled errors
and backlog before a production rollout. This change has not been deployed.

## Verification

`npm ci` then `npm run typecheck`, `npm run test:relay` and `npm run test:regressions`. Tests run actual workerd/Miniflare D1/R2
under unique short `worker/.wrangler/r<PID>/{p,h}` paths. They never load `.dev.vars`. The normal
entry verifies session authentication/router/byte equality and its scheduled handler; a separate **test-only** entry injects
clock and R2 faults for exact-boundary, orphan and retry tests. Production ignores test headers and
has no cleanup HTTP endpoint. Test fixtures do not bypass `ownerOf`. These are local protocol tests,
not proof of deployed Cloudflare scheduling, bucket access configuration, quotas/CPU budget, or an
Android/device end-to-end run.
