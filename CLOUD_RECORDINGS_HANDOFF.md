# CLOUD/RECORDINGS consolidated handoff

**Overall decision: CORRECT; not release approved.** All nine independent ROUND3 reports are complete. Six mandatory defects remain (two P1, four P2), plus three P3 follow-ups. A successful build does not override these failures. The user directed this single reviewed handoff and residual ledger into the root's already-authorized combined integration/final review/stable release. No fourth standalone CLOUD correction, main merge or publication was performed here.

## Frozen source and range

- Reviewed code: `351e3fd44bf64b07d6437b1d1c8b533a7bf9c79f`.
- Base: `ea4e0a2acc23f8208e943e12513195f6d1eab6dc` (origin/main when prepared).
- Branch: `t3/cloud-recordings-parallel`.
- Worktree: `C:/Users/casua/.t3/worktrees/Pampa notes/t3-cloud-recordings-parallel`.
- The reviewed range is `ea4e0a2..351e3fd`,42 local commits. The final delivery commit adds only this handoff, verbatim reviews, their source probes and workflow status. Its SHA is reported in the coordinator's delivery message. No production source changes after the frozen review.
- [Exact reviewed commit list](workflow-reviews/round3/reviewed-commit-range.txt), [changed paths](workflow-reviews/round3/changed-paths.txt), [nine reports and aggregate](workflow-reviews/round3/README.md).
- Public entity contract `5c91d57`, adopted generated schema15 `3e7a997`. Parent already integrated equivalent entity/schema work; keep one coherent schema15, do not duplicate columns or invent a migration run.

Engine3.0.5, app1.3.1/code20, Gradle/build/signing/store configuration and entire Worker tree match base. No main/cache/stash writes. Parent's pre-existing main bytecode-cache deletion remains unrelated and untouched. No heavy lease or process remains here.

## Review aggregate

R1=C1 through R9=C9; all actual GPT-6.1 Sol/xhigh, fresh independent critics. Nine distinct critics ran in waves within three actual child slots+coordinator. Each mean/minimum uses1/1assigned verified comparable score; the eight NA outside-assignment entries are excluded. No cross-round average or coordinator replacement score.

| Criterion | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Mean | Minimum | Decision |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | 6 | NA | NA | NA | NA | NA | NA | NA | NA | 6 | 6 | CORRECT |
| C2 | NA | 6 | NA | NA | NA | NA | NA | NA | NA | 6 | 6 | CORRECT |
| C3 | NA | NA | 6 | NA | NA | NA | NA | NA | NA | 6 | 6 | CORRECT |
| C4 | NA | NA | NA | 6 | NA | NA | NA | NA | NA | 6 | 6 | CORRECT |
| C5 | NA | NA | NA | NA | 9 | NA | NA | NA | NA | 9 | 9 | PASS |
| C6 | NA | NA | NA | NA | NA | 6 | NA | NA | NA | 6 | 6 | CORRECT |
| C7 | NA | NA | NA | NA | NA | NA | 8 | NA | NA | 8 | 8 | PASS |
| C8 | NA | NA | NA | NA | NA | NA | NA | 9 | NA | 9 | 9 | PASS |
| C9 | NA | NA | NA | NA | NA | NA | NA | NA | 9 | 9 | 9 | PASS |

C8 passes its bounded transfer/sync contract; C4's distinct overlay-mutation failure remains open. C9 approves evidence/protected boundaries only. C7 passes with a minor parser limitation. Combined PN/Worker/older-client compatibility remains external UNVERIFIED.

## Deduplicated mandatory residual ledger

| ID | Severity / criteria | Reproduced failure | Source boundary / required integrated regression |
|---|---|---|---|
| B01 | P1; C3 P1 + C1 P2 | Definitive OpenRouter401/402/429 leaves an unsettled spending reservation. A legitimate capped retry fails before HTTP despite key/credit correction or elapsed cooldown; completed paid A must remain charged correctly. | `CloudSpending.kt:153-170`, `CloudCosts.kt:194-212`, guard/HTTP outcome callbacks. Reconcile the EXACT attempt durably/cancellation-safely, preserving reported actual cost and unknown-request barriers. Full guard+ledger+runner budget controls before/after paid A. [C1 report](workflow-reviews/round3/C1-review.md), [C3 report](workflow-reviews/round3/C3-review.md). Synthetic prices only; no actual billing claim. |
| B02 | P2; C2 | After cloud binding while `partsOf` suspends, privacy migration changes row to custom/QUEUED. Pre-start metadata validation throws CompanionOnly outside recovery; stale generic fail changes migrated row to custom/FAILED. No upload/fallback, but manual retry becomes necessary. | `TranscriptionQueueWorker.kt:164,372-412`, `JobDao.fail` at `Daos.kt:686`. Cover pre-start suspension/error disposition and authoritative provider/state ownership, preserve/wake current queue. Pin/start CAS alone is insufficient. [C2 report](workflow-reviews/round3/C2-review.md). |
| B03 | P2; C3 | Status429 is already read, then cancellation plus header IO failure prevents cooldown and rejected-outcome persistence. Fresh runner sees pending intent forever; another same-scope job has no saved wait. | `TranscriptionHttp.kt:198,235-238`, private upload storage. Preserve known refusal separately from fallible headers; durable honest missing-header policy must not pretend an unreadable long Retry-After was honored. Keep uncertain barrier for UNKNOWN status/storage failure. [C3 report](workflow-reviews/round3/C3-review.md). |
| B04 | P2; C4 | OpenRouter top-level text absent or whitespace-only is replaced by reconstructed segment text, then cached/saved as exact complete_original/source_snapshot and alignment-bound. Present whitespace is lost; absent text becomes invented exact knowledge. | `OpenRouterTranscriptionProvider.kt:89-94` →runner/cache→`CloudSavePlan`. Preserve present exact strings and distinguish absent/derived text, or reject unsupported shape. Test actual provider/parser→cache→save with both cases and nonblank control. [C4 report](workflow-reviews/round3/C4-review.md). |
| B05 | P2; C4 | Valid v1 source with an extra public field is accepted by lenient alignment decoding; overlay application re-encodes and silently drops the unknown field. Timeline/portable strictness does not cover this writer. | `AlignmentProtocol.kt:62`, `CloudSources.kt:70-76`, `AlignmentRepository.kt:115,132,237`, `AlignmentApplication.kt:11-21`. Strict supported metadata validation before mutating, or lossless supported merge; unsupported source must defer alignment and preserve usable RAW. [C4 report](workflow-reviews/round3/C4-review.md). |
| B06 | P1; C6 | Final eviction snapshot reads reference rows BEFORE suspendable protection reads. New unarchived alias added during that suspension is missing from snapshot, so final hold lock still deletes bytes. Four repros: audio/source ×archived/computer-only. | `StorageRepository.kt:186-200,251-261`. Coordinate row writers and authoritative reference/policy snapshot through physical deletion; retain canonical path+hold lock. Compose PN lifecycle/sweep/copy token rather than replacing it. [C6 report](workflow-reviews/round3/C6-review.md). Local byte loss proved, permanent remote loss not claimed. |

[Verbatim independent probe sources](workflow-reviews/round3/repro/README.md) are included for these failures. Logs, precise commands and supporting cached hashes remain at paths in the corresponding reports. Reuse requires adapting root/classpath to the combined snapshot; copies are not new successful regressions.

Nonblocking follow-ups:

1. N01 P3/C1: IT/EN `onboarding_provider_detail` lists Groq/OpenRouter and omits Mistral although setup/selector exist. Update both introductions.
2. N02 P3/C6 inherited: English shell uses Italian engine FluidLabels defaults. Choose labels at the app root; no engine edit needed. Parent PN has independent FluidLabels work; verify combined resolution instead of duplicating it.
3. N03 P3/C7 inherited: `RecordingOrigin.fromFileName("2025.09.22")` loses the known day because `RecordingDate.kt:224` strips numeric suffix as extension. Real-extension handling should preserve date-only form. No fabricated exact clock occurs.

No majority or high build count cancels these requirements. No extra standalone round was opened after3/3; this explicit residual ledger is for the already-authorized combined phase.

## Bound build and evidence

External evidence directory:
`C:/Users/casua/AppData/Local/PampaNotes/cloud-recordings-verification/correction3-351e3fd4`.

[Small bound evidence index](workflow-reviews/round3/build-evidence.json) includes the manifest/log hashes and all nine report identities. Primary full manifests retain537source inputs and166artifact hashes:

- `source-manifest-before.json` SHA256 `34e5d9dbe812bfebdb432a25068c536ce357148d00e75cdff1661861b70821b3`.
- `verification-manifest.json` SHA256 `63103ba9349b8c79d8cb67a9541082adb5fa111c8292458a958416a42097f19f`.
- `gradle.log` SHA256 `d524cb6383cbc3187f096dff41ccc88e90a64e98f868932449c1a7f26dd2ad22`.
- Actual process exit0, BUILD SUCCESSFUL3m04s,422actionable tasks21executed401up-to-date. Same clean frozen HEAD before/after, all537input hashes unchanged.
- JVM: core1239pass/1optionalWorker skip; app67pass accepted UP-TO-DATE. Total1306pass/1skip/0failures/errors, not additional independent replay totals.
- Lint0errors127warnings. App debug assembly/APK accepted UP-TO-DATE; core/app AndroidTest Kotlin COMPILED ONLY. No runtime/instrumentation execution.
- APK45,261,018bytes,1.3.1-debug/code20. SHA256 `022b0043133c45154a5c133ec730313634a731bfe8a8f322d572da653745acc2`. External build root `C:/Users/casua/AppData/Local/PampaNotes/build-cloud-recordings`; no binary/cache output committed.
- Flags: `--no-daemon --no-watch-fs --max-workers=2 --no-parallel -Pkotlin.compiler.execution.strategy=in-process -Ppampa.buildRoot=C:/Users/casua/AppData/Local/PampaNotes/build-cloud-recordings`.
- Targets: `:app:assembleDebug :core:testDebugUnitTest :app:testDebugUnitTest :core:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug`.
- The current companion archive has88guarded mock/offline tests; ROUND3C5 independently executed those88+21additional=109tests. No acoustic evidence. Historical286suite predates authority corrections and is not a current full-suite pass.

Historical failures remain distinct immutable archives: `correction3-4d50cf6` failed AndroidTest call arity BEFORE core JVM execution, and `correction3-7cae3c37` failed one exception-object-identity assertion. The final test checks exact class/message, original cause chain, IO/cancellation branches, and unchanged request count after restart; C9 independently replayed10tests in ordinary and forced debug/stack-recovery modes. Do not add repeated modes as new unique tests. Older ROUND0/ROUND1/ROUND2 successes/failures remain bound to their own commits; no stale XML was claimed current success.

**NOT EXECUTED / USER-WAIVED:** any emulator/device/physical/GPU/model/actual audio/provider/credential/live server/cloud calls; Room migration/runtime, WorkManager runtime, Android instrumentation execution, Compose rendering/TalkBack/acoustic accuracy. Code/mock feasibility is not precision or deployed-runtime proof.

## Integration boundaries and merge notes

This checkout deliberately contains NO PN codec/Worker implementation. Integrate once against the parent's final snapshot, retaining both feature sets rather than replacing files wholesale.

1. **DB/public metadata:** exactly five nullable AudioPart fields (`recordedAtEpochMs/Local/ZoneId/Source/Precision`) plus nullable Transcript.`cloudSourceJson`, all defaultNULL. No old-row backfill or invented epoch/zone. All-null sync hashes remain invariant. Parent must preserve nullable/empty distinctions and hashed source-JSON blob for >1MiB native metadata; schema15 runtime remains unexecuted.
2. **Export/import:** keep parent's `PAMPA` enums/options/defaults, writer/reader/full-version graph and relay-ready fetch helper. Add `ExportScope.Sessions(sessionIds,label)`, speechClock/origin/associated-material and `attachmentAllowed` policies. Filter selected sessions BEFORE histories/segments/attachments. Ordinary school Notes behavior remains separate. See [C7 hooks](C7_RECORDINGS_EXPORT_INTEGRATION.md).
3. **Typed source contract:** `CloudSources.portableProjection(...)` returns BOTH text+JSON. Consume both; remap RAWid/version and part IDs coherently, validate portable graph, keep `portable_unbound` even for valid stored overlays. `ExportSessionSelection.transcripts(...)` returns BOTH versions+activeId before BEST/native collection; unproven refinement uses its actual RAW parent. Whole-authorized exports retain complete public originals/reports/responses; restricted scope must remove siblings. See [C4 hooks](C4_NATIVE_PORTABILITY_INTEGRATION.md). These strict helpers do not by themselves fix B04/B05.
4. **Response/source history:** public optional `CloudTextPart.responses` / `CloudResponseText` retains exact per-response strings, nullable language, window/hash/provider/model; `derived_presentation` is not attested exact source. Preserve these through PN source blobs, copies/aliases/histories and selected scope. Original hashes come only from the worker's original audio snapshot and cached original input, never a replacement retry row.
5. **Wire sync:** `SyncCloudSourceWire.toLocal` expands `pampa-cloud-source-sync:1:<base64>` before typed processing/hashes/upserts. Local DB/backup/native JSON is exact expanded text. Envelope references its unchanged RAW: parent Worker/older-client retention must preserve BOTH or explicitly decode/rebase/reject an incompatible rewrite. Keep public VoiceNames ownership, PN private copy/write protection and incremental relay/chunk/hash/TTL transport; no whole-account bundle rewrite. Oversized independent histories can still exceed1.9MB and remain visibly/durably pending.
6. **Storage/reference safety:** combine canonical identity and final `FilesInUse.withCurrent` hold lock with PN lifecycle/sweep/copy tokens and row-writer exclusion, then prove B06 closed. Preserve independent source filenames/bytes on partial moves, rollback cleanup, exact-session deletes and ordinary school rows. AppFiles canonical orphan checks must not be reverted.
7. **Import reconciliation:** preserve PN AudioImporter atomic metadata touch, CRC/fileless/recovery and identity-rekey logic while populating nullable recording origins. `PampaBundleRecordingOrigin.retain` runs only after reconciled part ID and matching nonblank SHA; never mix different origin tuples. Native cloud copy/alias helper must consume text+source JSON together, preserving independently changed local RAW.
8. **Queue APIs/ownership:** `pinForRun(job):JobEntity?` reads authoritative row; `pinTranscription` now expects provider/model/options CAS, and `start(...,expectedProvider)` fences the final start. Update all combined callers/fixtures to the seeded provider. Preserve atomic cancellation/conditional terminal callbacks and explicit manual overrides. B02 still needs authoritative pre-start failure ownership.
9. **Application/DI/UI lifecycle:** retain new encrypted noBackup Mistral key and public model preference backup, CloudRequestGate across every production HTTP/setup path, exact-cloud reader/global search guards, and alignment saved/wake/cancel/restore/logout hooks. Merge MainActivity endpoint wake with PN intent routing; no unrelated manifest overwrite. New UI strings are isolated resources except removal of two obsolete credit strings.
10. **Companion:** `forced_alignment_v1`, multipart/source protocol1, supplied-text-only bounded alignment, verified owner/index/token fencing. [API contract](companion/FORCED_ALIGNMENT_API.md) unchanged externally. Offline PC defers alignment; current RAW remains usable. No new ASR or paid fallback, no credentials/account/relay state in portable metadata.
11. **Worker/release:** entire Worker tree is unchanged here; keep parent's backward-client public-evidence retention/RAW guards and rerun its offline tests against merged shapes. Main/version/signing/store/deploy/publication stay parent-owned. Main's pre-existing bytecode-cache deletion remains untouched.

The root should carry B01-B06 and N01-N03 through the combined fixes, build and fresh final independent review. This handoff establishes reviewed source and actual evidence limits, not final product correctness or permission to bypass residual verification.
