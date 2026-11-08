# Existing cloud transcript alignment: protocol 1

Capability: `forced_alignment_v1` in open `GET /health` → `features`. This is additive;
existing transcription, owner options, archive, guest and pairing APIs retain their contracts.
Capability means the API exists, **not** that a language model is cached, the PC is online, or
real-model accuracy has been verified. Android keeps the cloud text usable while alignment is
pending/offline/deferred. An old companion without this feature must never receive a replacement
transcription request as an alignment fallback.

## Request

`POST /v1/audio/alignments`, multipart/form-data:

- `Authorization: Bearer <owner ticket or existing local owner token>`.
- `X-Pampa-Job: <fresh id>`: 8–64 ASCII letters/digits/underscore/hyphen, required.
- `alignment`: JSON string described below.
- `file`: optional **original selected-part audio bytes**, streamed through SHA-256 to an OS temp
  file. Without it, the selected part's hash addresses an existing owned archive blob. An uploaded
  file is never silently replaced by another archived blob, even if that blob exists. Alignment
  uploads are not archived implicitly.

Example shape (hashes must be actual lowercase 64-character SHA-256 values):

```json
{
  "protocol_version": 1,
  "source": {
    "raw_id": "immutable-raw-id",
    "raw_version": 1794130000123,
    "raw_sha256": "<SHA-256 of exact complete RAW text, UTF-8>",
    "parts": [
      {"id": "part-1", "sha256": "<SHA-256 of original part-1 audio bytes>"},
      {"id": "part-2", "sha256": "<SHA-256 of original part-2 audio bytes>"}
    ]
  },
  "part_id": "part-1",
  "text": "  Hello,\tworld!\n",
  "text_sha256": "<SHA-256 of exact text field, UTF-8>",
  "language": "en",
  "segments": [
    {"text_start": 0, "text_end": 16, "start_ms": 1000, "end_ms": 8000}
  ]
}
```

`raw_version` is a positive integer immutable version/creation identity; it is not the request
protocol version. `source.parts` is the ordered **complete RAW source snapshot**, with unique
part IDs, not just the currently processed part. One request aligns one part. IDs are nonempty,
at most 128 characters. Maximum 1000 parts. The selected `part_id` must occur in that snapshot.
The server hashes the selected audio before and after alignment; mismatch rejects the response.
The exact part text hash is independently verified before audio/model work. No Unicode NFC/NFKC,
case, whitespace, punctuation, line-ending or language normalization occurs.

The companion has no Android database. Therefore it cannot independently attest that `raw_id`,
`raw_version`, full RAW text hash, selection and other part hashes still match the current local
database. It echoes the supplied snapshot exactly. **The client must atomically compare current
selected RAW ID/version/full-text hash and the complete ordered part IDs/audio hashes before
applying the timing overlay.** A changed source/version/selection/audio discards the result. A
same-ID retry is rejected, including with different identity; use a fresh request ID after
validating the latest snapshot. Never apply results to a different RAW or overwrite RAW text.

## Offsets, bounded windows and unsupported inputs

- `text_start`/`text_end`: zero-based, half-open UTF-16 code-unit offsets into exact `text` (Android
  String indices). Boundaries splitting a surrogate pair and unpaired surrogates are rejected.
- `start_ms`/`end_ms`: integer milliseconds relative to the **original selected part**, not session
  time. Add the current part placement on Android only after validating identity.
- Existing cloud segment timestamps bound the acoustic work. Supply monotonic, nonoverlapping
  text spans and monotonic, nonoverlapping audio intervals. Gaps are allowed. Use offsets derived
  from the authoritative text; do not reconstruct text by joining normalized segments.
- At most **30,000 ms audio and 400 UTF-16 units per alignment matrix**, **1000 windows per
  request** and **200,000 UTF-16 units total part text**. Segment spans exceeding the per-window
  cap remain missing (`window_limit`); they are not split by distributing text evenly over time.
  More than 1000 submitted windows or total text beyond the request cap is rejected (400).
- Missing `segments`/`[]`: the entire part may form one window only if the probed original audio
  is at most 30 s and text at most 400 units. Long untimed OpenRouter text/audio is explicitly
  deferred (`no_bounded_window`). Unknown duration with no supplied bounded windows also defers.
  There is no local coarse-ASR/VAD-to-text mapper in protocol 1; no unreliable boundary guessing.
- Known audio duration is checked against every supplied interval (409 if outside). Every decode
  is explicitly window-limited; returned character times must fit the actual decoded audio too.
  A token crossing a supplied text boundary stays missing rather than borrowing partial timings.
- Language is mandatory, lowercase 2–3-letter code, and never guessed. Unsupported languages
  preserve that code and return missing timings. Current allowed languages follow the companion's
  existing `ALIGN_LANGUAGES`; a model load failure also leaves timings missing.

## Response and timing honesty

The POST stays open until finished, following the existing transcription API lifecycle. It returns
200 for complete, partial or safely deferred overlays:

```json
{
  "protocol_version": 1,
  "source": {"raw_id": "...", "raw_version": 1, "raw_sha256": "...", "parts": []},
  "part_id": "part-1",
  "text": "original text unchanged",
  "text_sha256": "...",
  "language": "en",
  "offset_unit": "utf16",
  "time_unit": "ms",
  "audio_sha256": "...",
  "job_id": "alignment-job-123",
  "instance": "current companion process identity",
  "status": "partial",
  "timings": [
    {"text_start": 0, "text_end": 8, "start_ms": 1200, "end_ms": 1680,
     "status": "measured", "reason": null},
    {"text_start": 9, "text_end": 13, "start_ms": null, "end_ms": null,
     "status": "missing", "reason": "unknown_character"}
  ]
}
```

`text`, `text_sha256`, `source`, `part_id` and `language` echo the request. `timings` covers each
maximal non-whitespace span (`\S+`), in text order; whitespace is preserved solely in `text`.
Punctuation stays attached to the same token. For Japanese/Chinese without whitespace a whole
run is one overlay token; character-level UI word segmentation is not provided in this version.

`measured` means model-derived, **not proven exact, ground-truth or calibrated confidence**.
WhisperX receives only the supplied text and bounded original audio. The path never loads a
Whisper transcription model, calls ASR, guesses language, calls paid cloud inference, or uses
WhisperX output text as the transcript. Its `align(..., interpolate_method="ignore",
return_char_alignments=True)` output is used only as raw character evidence. Word and sentence
timestamps are never trusted. The exact ordered non-whitespace character sequence must match
the excerpt; extra, reordered, dropped or substituted characters invalidate the whole window.
Every lexical character must belong to the actual aligner dictionary (no unknown/wildcard
substitution), have finite, monotonic, nonoverlapping times inside the decoded interval, and a
CTC character score >=0.5. This filter is conservative and **has not been calibrated on real
audio**. Ordinary punctuation need not have phonetic evidence; its token boundaries follow the
measured lexical characters. Punctuation-only tokens remain missing. No Unicode normalization
is used even when a decomposed accent consequently remains unalignable.

`missing` always has both times `null`. No estimated time distribution is emitted. Reasons:
`no_bounded_window`, `window_limit`, `language_unsupported`, `unknown_character`,
`character_mapping_mismatch`, `token_mapping_mismatch`, `unreliable_character_timing`,
`aligner_unavailable`, `alignment_failed`. Clients must retain coarse source segment timings
separately if desired, with their original provenance; they must not relabel them as measured
word timings. Overall `aligned` means all tokens measured, `partial` some, `deferred` none.

## Authentication, cancellation, restart and retention

Existing `AuthGate` authenticates headers **before reading multipart bodies**. Tickets use the
existing Worker verification against the configured owner account; local owner tokens retain
their established offline role. Guests and anonymous access cannot create, inspect or cancel
owner alignment jobs or probe archive hashes through this API. No new account secret is stored.
Cached ticket verdicts are scoped to owner and index URL; an old owner's cached ticket cannot
authorize a new owner's request after configuration changes.
The verifying owner **and index URL** are retained immutably by the authenticated caller and
alignment job. They are captured before credential verification, never reconstructed from
configuration after an asynchronous authentication handoff. Admission rejects a changed authority
with 409 `account_changed` before registering a job or reading multipart. Guest/anonymous
alignment still receives 403 before body parsing. Verification also sends its request to the
captured index, including if configuration changes before request construction.

The snapshot is checked at endpoint entry, before/after upload reads and archive lookup, before
queue admission and worker entry, at hash/decode/alignment callbacks, after work and before
return. Changing the configured owner **or rebinding the same owner to a different index**
invalidates results and old-job visibility/status/partial/cancellation. Neither the old caller
nor a caller freshly authenticated under the new authority may access the old alignment job.
Refreshed tickets and the local owner token can still read/cancel jobs for the same bound
owner/index. Local-token callers must also retain a currently valid local token; unbound PCs
bind job authority to the local token hash as well as the owner/index snapshot. Binding an
unbound PC or rotating its token invalidates that old authority. No credentials, authority or
index configuration are added to multipart, source echoes, results or progress responses.

Internal hooks (not additions to protocol 1): frozen `AuthAuthority` and `Caller.authority`;
`authentication_authority()` captures configuration and `caller_authority_current()` checks the
retained identity. `identify()` passes that exact snapshot through the optional keyword
`authority` on `verify_ticket()`/`verify_guest()`; both still return their existing bool/name
verdicts. `_post_worker(..., index_url=...)` uses the verifying index explicitly. Guest/ticket
cache entries include owner/index. `alignment_account(caller)` now returns the caller's frozen
authority, with no current-state fallback; the in-memory `job.alignment_account` retains it.
`alignment_authorized()` is shared by visibility and `alignment_fence()`. Ordinary ASR retains
its existing registration/visibility/cancellation behavior; shared header admission also rejects
authentication from an authority changed during the handoff.

- `GET /v1/jobs/{id}`: existing progress states/fraction/instance; once request validation has
  completed it also echoes `protocol_version`, `source` and `part_id`.
- `DELETE /v1/jobs/{id}`: existing cancellation. Stops queued work; admitted acoustic work stops
  cooperatively at a decode/hash/callback/window checkpoint. No forced thread kill or early GPU
  gate release. CTC inference in one bounded window is not interruptible mid-kernel.
- Disconnect cancels the waiting request and then its work; temp audio lives only until the
  actual worker settles, then is removed. No shared ASR work can be cancelled by alignment.
- A source identity cannot be replaced by a duplicate job ID (409); old transcription requests
  also cannot overwrite registered alignment state. Owner priority and the existing shared GPU
  gate serialize transcription/alignment/unload. Alignment never changes saved job overrides.
  An alignment-only cached model also participates in health loaded state and idle/manual unload.
- Jobs stay queryable ten minutes after completion; never-started uploads expire under the
  existing six-hour stale policy. Jobs/results are not durable across companion restart. The
  existing `instance` changes on restart; 404 plus a changed instance permits a fresh request
  after client identity revalidation. A lost POST response can be recomputed locally; no paid
  retranscription occurs. Completed timing overlays are not stored as job partial transcripts.
- Original audio transport remains the existing owned archive upload/by-reference path. Manual
  relay chunk/hash/TTL behavior elsewhere is untouched; this endpoint never fetches arbitrary
  relay URLs, bypasses relay authorization or changes relay expiry.

Errors use existing `{"detail":"..."}` responses: 401 invalid/missing auth or wrong account
ticket; 403 `owner_only`; 400 invalid protocol/source/text checksum/Unicode/windows/missing job
ID; 404 `blob_missing` (or unknown/invisible job); 409 `sha_mismatch`, `audio_bounds_mismatch`,
`job_id_conflict`, `account_changed`; 499 `annullata`; 503 `restarting` with Retry-After. Malformed
multipart/missing required form fields can return FastAPI 422. Unexpected I/O/server failures
may return 500. Never automatically retry these as a cloud transcription request.

## Evidence and real-model limitation

Independently inspected upstream on 2026-11-08:

- https://github.com/m-bain/whisperX/blob/main/whisperx/alignment.py
- https://github.com/m-bain/whisperX/blob/v3.8.6/whisperx/alignment.py (installed dependency pin)
- https://github.com/m-bain/whisperX/blob/v3.8.6/whisperx/utils.py (`ignore` interpolation)

Both inspected alignment versions expose the supplied-transcript API and character outputs.
Current main can assign wildcard character timings; dictionary filtering is therefore necessary
even when numeric word times exist. The pinned release can also return missing characters and
interpolated word/sentence outputs. Runtime calls use the existing language-specific aligner
loader and sentence-splitter support; language resources/models may need prior installation or
download on an actual PC. **No real model was loaded/downloaded, no GPU inference, no real audio
and no paid provider call was used during this implementation. Acoustic accuracy, model
availability and real-device performance remain NV.** Pure/mocked HTTP tests prove the contract
and guards, not alignment accuracy.

Initial implementation archive (not rerun in correction round 2): `PYTHONDONTWRITEBYTECODE=1` / `PYTHONUTF8=1`, existing companion
interpreter, `python -m unittest test_companion test_forced_alignment -q`: **286 tests passed**
(257 existing + 29 alignment fixtures), 49.040 s. AST parsing and `git diff --check -- companion`
passed. Output: `%TEMP%/pampa-forced-alignment-tests-final.txt`, outside the repository. The
legacy transcription cancellation fixtures emitted unretrieved `JobCancelled` task warnings;
the suite still passed. New alignment tasks explicitly retrieve settled exceptions.

Correction round 2, offline verification on 2026-11-08: `python -B -m unittest
test_forced_alignment -q` passed **46 tests**. The alignment HTTP fixtures now use actual
in-process ASGI routing/multipart parsing with mocked Worker responses, without a listener or
application lifespan. A scoped guarded harness passed **88 tests**: the 46 alignment fixtures,
existing authentication tests with an in-memory Worker (unreachable-Worker case uses a synthetic
OSError), and existing pure queue/progress/registry/cancel/prompt/mock-ASR checks. Actual torch
and WhisperX imports, external connections, HTTP server construction, uvicorn execution and
subprocess work were forbidden by that harness. The real torch-specific OOM test and full
legacy server suite were not run. Synthetic ASR compatibility exercises the old endpoint using
a mocked `_transcribe`; it performs no transcription inference.

Four targeted regressions were also run against the exact server source from
`2de6e0be198c9944129f992475f4e876c49138cb`: all four fail (seven subtest/assertion failures).
The same four pass on the corrected source. These cover ticket/local-token auth handoff,
same-owner index and owner changes blocking old-job access, pre-work rejection and rejection
of stale model-callback results. Additional fixtures cover captured-index verification/cache
scope, upload/archive handoffs, queued admission, post-work result fences, refreshed tickets,
local-token rotation, guest/anonymous denial, replay, cancellation and registry restart loss.
Evidence/harnesses are outside the repo in `%TEMP%/pampa-c5-round2-f80e1010086d4249926a55f99811c2c6/`
(`offline_suite.py`, `offline-suite-final.txt`, `regression_at_commit.py`,
`regressions-exact-baseline.txt`, `regressions-corrected-final.txt`, `verification.json`). An initial harness-only socket
guard error is retained separately; Python's internal asyncio socketpair is not an application
listener and was allowed in the corrected harness. These results are implementation evidence,
not independent review approval or an account-transfer/runtime exploit claim.

Per user steering, emulator/device/physical-device/GPU/real-model/runtime accuracy evidence:
**NOT EXECUTED; gates waived by the user.** No such QA or substitute is planned by this unit.
Gradle was **NOT EXECUTED** (coordinator resource ownership). No app/public entity contract,
versions, installer, signing, deployment, shared status, tools/cache files, or commits were changed.
