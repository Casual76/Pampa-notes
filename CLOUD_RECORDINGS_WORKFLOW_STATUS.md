# Cloud and recordings independent workflow

## Configuration and boundaries

- Coordinator: GPT-6.1 Sol, xhigh, verified by live orchestrator_capabilities 2026-11-08.
- Implementers and reviewers: codex / gpt-6.1-sol / reasoningEffort=xhigh.
- Maximum three active implementation delegates; three distinct, source-read-only reviewers per review round. Backend limits take precedence; queue where necessary. Fresh delegate for each new subject and each review/recheck.
- One integrated delivery phase, with sequenced work packages below. Initial review plus at most three correction rounds; no reset of the counter by renaming work.
- Own T3-prepared checkout only, base ea4e0a2, app 1.3.1/code20. Parent PN local docs 7086f2b are not needed for this codebase.
- Do not modify engine/, engine.properties, app/build.gradle, versions, store manifests, signing, deployment, main checkout/cache/stashes, or PN_WORKFLOW_STATUS.md. No publication or main merge.
- PN is an equal independent workflow, not a child of this workflow. Parent integration thread: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/00b50fba-f46f-43dc-b672-bdfbcaa2b731>.
- Shared Gradle/device/real WhisperX GPU resources require an explicit lease. Currently free per parent ACK, no lease held. Light standalone checks allowed.
- No live cloud inference/user audio/keys, deployment, or credential probes in this work. Preserve same-account and privacy checks; real user-only credentials go through request_secret if ever necessary.

## Observable approval criteria and common review rubric

Scores 0-10: 0-3 requirement fundamentally broken; 4-6 material gaps; 7-8 working with concrete nonblocking limitations; 9 requirement met with relevant evidence; 10 strong evidence across adversarial cases. A numeric score is never a substitute for requirement satisfaction. Any reproducible mandatory failure blocks delivery regardless of average. NV/NA/provisional are excluded from aggregates. All three reviewers use these IDs and distinguish executed checks, source inference, and unavailable live evidence.

| ID | Required behavior and evidence |
|---|---|
| C1 | Mistral direct API follows current primary docs, documented models, segment-only timestamps; setup, encrypted keys, manual overrides, result persistence, IT/EN UX and provider-specific error behavior are coherent. HTTP fixtures cover requests and parsing without paid calls. |
| C2 | First automatic transcription pins exactly one chosen provider/model; no automatic cross-provider fallback on rate, credit, auth, capability or connectivity failure. Manual 1.3.1 per-job choices remain explicit and survive retries. Tests exercise settings changes after enqueue. |
| C3 | Rate waits honor Retry-After, persist deadlines/provider scope across jobs and restart, avoid early wake/busy loops, retain completed paid chunks, and preserve cancellation/account/privacy fences. Tests cover multiple jobs, long/date headers, restart and cancellation. |
| C4 | Existing cloud RAW text, punctuation, language and provenance survive forced alignment; no paid retranscription. Durable source identity includes RAW version and part hashes; changed/selected transcript and changed audio reject stale results. Offline PC defers alignment only. Tests cover these guards. |
| C5 | Companion forced alignment is capability-negotiated, authenticated and owner/account bounded; original text stays immutable; bounded coarse windows/VAD prevent unbounded alignment of long untimed input. Missing/estimated words remain honest. Pure/fixture tests required; real WhisperX accuracy/GPU evidence reported separately as NV unless actually run. |
| C6 | Recordings displays individual sessions and opens player/transcript directly, with optional folders. Existing school Notes, legacy personal text/metadata, moves, archives, privacy and sync behavior remain intact. Real Fluid components and IT/EN text; source/pure checks required, device evidence reported separately. |
| C7 | Optional export speech clock uses durable nullable per-part origin, provenance, precision and zone; preserves gaps, midnight and timezone; never infers exact start from createdAt/import or date-only input. Tests cover each case. |
| C8 | Room auto-migration/schema is coordinator-owned; defaults preserve old rows. Sync/backup/native generic serialization retain public metadata and all text, with no credentials/relay/account state in portable exports. PN compatibility contract/tests handed to parent; no duplicate PN codec. |
| C9 | Integrated core/app and companion checks appropriate to changes pass from frozen SHA; disjoint writes, protected paths and build-root constraints hold. Any unavailable device/live provider/GPU proof is explicit, not claimed. Three independent score tables and coordinator verified-only mean/min aggregate required. |

## Work packages and ownership

1. Cloud provider/pinning/retry: transcription provider/settings/repository/worker/setup UI and cloud-specific tests/resources. May edit Job portions of Entities/Daos only; coordinate before schema edits. PampaDatabase and generated schemas coordinator-owned.
2. Recordings-first UX: ui/recordings, personal FolderScreen/navigation and recording-only view/domain helpers/tests/resources. No cloud/settings/worker/companion/entity/schema edits. Export adaptations sequenced later with PN interfaces.
3. Python forced-alignment API: companion source/tests/docs only, excluding tracked __pycache__. No Android writes or real GPU/model loading. Send endpoint/capability/identity contract before Android bridge implementation.
4. Fresh Android alignment integration agent after cloud ownership frees. Coordinator owns final persistence/migration integration.
5. Fresh per-part clock/export unit after a slot frees; coordinator agrees fields and migration with PN before commits.

Implementers return changed files, actual checks, limitations and API contracts; they do not commit shared files or run Gradle/devices without coordinator lease. Coordinator commits stable integrated snapshots.

## Evidence and state

- Clean initial checkout at ea4e0a2, no applicable AGENTS.md found in checkout ancestry or app/core/companion trees.
- Read current agentic-workflow SKILL and runtime, fluid-engine SKILL/regole, material3-expressive; relevant CLAUDE project instructions read and continuing scoped reference pass.
- Current primary docs checked: https://docs.mistral.ai/studio/audio/speech-to-text/transcribe-files and https://docs.mistral.ai/studio/audio/specs-and-limits/limits document voxtral-mini-transcribe-3 / voxtral-small-transcribe-3, timestamp_granularities=segment and rejected word timestamps.
- Upstream https://github.com/m-bain/whisperX/blob/main/whisperx/alignment.py exposes align(transcript, model, align_model_metadata, audio, device, ...); feasibility basis only, not a live accuracy result.
- Review round: not started. Correction rounds consumed: 0/3. No feature implementation or test success claimed yet.

## Delegate ledger

Initial three delegates launched, all actual codex / gpt-6.1-sol / reasoningEffort=xhigh, shared checkout with disjoint source ownership. T3 returned running for each, no backend exception or extra slot assumed. Each next instruction says completion wakes coordinator; end turn without polling.

| Unit | Task suffix | State |
|---|---|---|
| Cloud/pinning/retry | 6041ac2a-8dfd-4b5a-920d-ae8c1c6ef2c0 | completed |
| Recordings-first UX | d883446a-3153-48a8-857c-8d9ac1ee4944 | completed |
| Python alignment API | 0f90a2ea-4831-4a5f-ba6f-89d1686b5a93 | completed |
| Android exact source / alignment | 6c6c919c-38ab-41d4-8da8-fa600c359921 | completed |
| Per-part clock / session export | c7ae6aae-2896-4caf-9d3c-26ea4f5af7e5 | completed |
| Exact cloud UI adapter | 7d965bb2-dda0-4574-afc7-cbe7ba9fad6b | completed |
| Portable source adapters | e9c5ab5e-e70e-42f8-9a0e-895a576047fe | completed |

Full task/child id prefix is node:delegated-task:command%3Amcp%3Aeb1c4ba6-cbfc-4f0c-bac9-1b3c1c7a2dc7%3Adelegate-task%3A plus suffix (child thread uses thread: in place of node:).
No Gradle/device/GPU lease held. Timestamp/Android alignment agents remain queued until a slot frees and contracts are stable. PN export milestone 11a7cbc1750eb04e5e23d94950be0d34f453c2f5 acknowledged as unintegrated; future export adaptations must include PAMPA enum/options and relay attachment planning.

## Coordinator public metadata contract (2026-11-08)

Parent ACK before source edits: five AudioPartEntity nullable fields, all default null / migrated SQL NULL: recordedAtEpochMs (UTC recording-start instant only when source supplies one), recordedAtLocal (source ISO local date or date-time), recordedAtZoneId (explicit source IANA zone/offset only), recordedAtSource (media_metadata/samsung_recording/filename/file_modified, last-modified at most date-only), recordedAtPrecision (day/minute/second/millisecond). Unknown old rows stay null, no inferred backfill, no creation/import/noon time reuse. Per-part origin is independent of session offsets and gaps.

TranscriptEntity.cloudSourceJson:String?=null is public immutable exact-source provenance only, never credentials/jobs/relay/account data. Fresh Android alignment integration owns source codec and SessionRepository/RawTimelinePlan behavior. Cloud unit exposes exact sourceParts and cache data; segment rows alone currently normalize whitespace. Empty/null source remain distinct. PN coordinator will externalize large source JSON into a hashed text blob and retain exact null/empty/string state.

Room source migration14->15 adds only these six nullable columns. Coordinator owns generation of core/schemas/15.json once shared Gradle lease is released. Source contract commit is not a completed or verified migration until generated schema and checks land. New JVM payload/hash tests and14->15 instrumented migration test prepared, NOT YET EXECUTED. Scoped git diff --check passed.

Current resource reservations: PN owns Gradle until explicit release; no compute/GPU lease requested here. PN released the device lease after device_open plus its advised single manual-start retry failed with emulator exit1 / FATAL Running multiple emulators with same AVD. Runtime device work stopped; no lock/session workaround and no device proof. This workflow has not attempted a device session.

PN received/cherry-picks5c91d57, will generate schema15 alongside hashed cloudSourceJson/blob/null-empty-day compatibility tests and send artifact/commit for adoption. No additional DB15 changes planned; Android alignment queue should stay private in noBackupFilesDir unless fresh implementer identifies a specific blocking need. Generated schema is not executed migration evidence. Three implementers remain running. Companion first25 mocked/pure forced-alignment tests reported pass by implementer, not yet independently executed or reviewed; existing suite/API doc still in progress. Review round not started;0/3 correction rounds used.

## User steering: runtime QA waiver and consolidated handoff

User explicitly prohibits all emulator/device/GPU QA/plans because the PC lacks resources; no physical-device substitute. Device/runtime evidence gates are waived for this task and must be labelled NOT EXECUTED. Approval uses independent source reviews plus proportionate standalone/local tests. No live-model accuracy claim. Necessary Gradle only, coordinator-controlled, --max-workers=2 --no-parallel, after actual lease request/release. Three independent reviewers remain mandatory.

Public entity/source contract frozen at5c91d57, no incremental schema/field redesign. Parent sends one consolidated source-blob/schema commit after PN compilation. Parent integrates this workflow once at stable delivery. Send parent only blocker, actual lease request, or final snapshot, avoiding repeated partial handoffs. Each implementer provides one final consolidated report; no new topic on an existing agent.

C6 audit found copied source rows cannot share local paths across partial moves/computer-only eviction; implementer now allocates independent destination filenames/bytes under FilesInUse holds with rollback cleanup. C5 mocked audit reports account-bound ticket-cache verdicts and align-model-only unload lifecycle fixes. Both are still implementer findings pending final source inspection/review, not approval.

## C5 consolidated unit delivery

Changed companion/whisperx_server.py, test_forced_alignment.py, test_companion.py and FORCED_ALIGNMENT_API.md only. Capability forced_alignment_v1, POST /v1/audio/alignments, UTF16 offsets / part-relative ms, exact text/source identity, uploaded/archive hashes, owner-only account-bound jobs, existing status/cancel endpoints. Windows bounded30s/400UTF16units, max1000; long untimed input defers. Missing/unreliable evidence stays null; no ASR replacement or paid transcription.

Implementer executed286 tests (257existing+29new),49.040s, all pass; log %LOCALAPPDATA%/Temp/pampa-forced-alignment-tests-final.txt. Coordinator inspected actual log footer (Ran286 tests / OK), source API contract, declared file list and scoped diff --check. This is archived/executed implementer test evidence, not an independent acoustic/runtime accuracy review. Legacy cancellation fixtures emitted JobCancelled task warnings without failed tests. AST/syntax checks reported pass. Gradle/device/emulator/GPU/real-model accuracy NOT EXECUTED. No forbidden paths changed or commits made by implementer.

Committed C5 snapshot925a646. Fresh Android exact-source/alignment delegate6c6c919c launched Sol/xhigh, shared checkout; owns new core/alignment helpers, SessionRepository/RawTimelinePlan and NEW AlignmentWorker/AlignmentScheduler only. Cloud-owned transcription/settings/existing workers remain reserved until cloud implementer finishes; coordinator wires small hooks afterward. No additional DB15 fields/tables planned. Active implementers: cloud, C6 finishing, Android alignment (three total). Clock/export queued until a slot frees. T3 next requires ending turn without polling; automatic completion will resume coordinator.

## C6 consolidated unit delivery

Completed session-first recordings UI/state, RecordingCatalog/Library/Actions, personal-only FolderScreen branch, PampaGraph callbacks, personal SessionScreen associated-material action, IT/EN recordings_v2.xml and RecordingCatalogTest. Individual session IDs flow to resumeSession; legacy material retained. Exact-session selection/deletion. Partial moves copy body/tags/metadata/sources with derived-link remapping and independent source filenames/bytes under FilesInUse holds; rollback removes only new copies. Whole-note moves only if all sibling sessions selected. Existing school behavior and retranscription options retained.

Implementer executed10 standalone Kotlin/JUnit tests plus production actions/library fixtures with fake DAOs/transactions (exact delete, subset/whole move, material preservation, file independence, rollback and school-scope guards), all reported pass; source/resource/diff checks pass. Coordinator inspected stable actions/library/navigation source and scoped diff --check. Real Room execution and full app compile NOT EXECUTED, independent review still required. Device/emulator/physical/GPU NOT EXECUTED, user waived. No protected files/commits/shared status edits by implementer.

Per-session export remains intentionally absent until fresh clock/export unit adds ExportScope.Sessions(sessionIds,label), constrained native/text collection and explicit associated-material policy. Existing whole-folder export remains.

C6 committed snapshot191c11c. New clock/export implementer launched Sol/xhigh after C6 completed, keeping three active implementations total (cloud, Android alignment, clock/export). Owns origin-import hunks, export models/writers/gather/UI and recordings export hooks; entities/schema remain frozen. Must preserve future PN PAMPA enum/options and relay adaptations at parent integration without duplicating codec. T3 next requires ending turn without polling.

## PN consolidated handoff and schema adoption

Parent PN frozen implementation b52e45c; reported combined build/app-core JVM/Android test compilation/lint pass (1031pass,1opt-in Worker skip,0failures; lint0errors123warnings). This is PN-only reported evidence, NOT this workflow's integrated verification. Parent Gradle lease released; device/GPU none held. This workflow must request an actual Gradle lease before necessary --max-workers=2 --no-parallel checks; no device/GPU execution allowed.

Adopted only generated schema15 from commit220854ef6cd996f244f2f18c74ae2bb6c246c72b as local3e7a997, touching no active implementer files. Coordinator compared schema14/15 JSON: exactly the six agreed nullable columns added, existing fields/indices/foreign keys unchanged, no tables added or removed. Host SQLite created the generated audio_parts/transcripts DDL and PRAGMA verified all6 nullable with implicit NULL. Comparison script initially assumed explicit false/empty schema keys (notNull/indices) and failed twice; corrected for Room's omitted defaults, final rerun PASS. Android Room/runtime migration NOT EXECUTED/user-waived. Parent PN hashed cloudSourceJson text-blob preserves null/empty/full strings, but final integration/reviews MUST assess typed RAW/part identity remapping on copies/aliases/moves; opaque string retention does not prove safe alignment applicability.

PN integration commits retained for final combined parent work:11a7cbc export;65c80dc codec;8ebfda8 import;b52e45c source-blob/clock regressions(depends5c91d57);220854e schema. No PN implementation files cherry-picked into active shared writers. No incremental parent message; final stable delivery/real blocker/actual resource request only.

## Reserved final integration hooks

C4 saveTranscription gains optional originalParts:List<AudioPartEntity> snapshot in Android unit's owned SessionRepository, default source-compatible. Coordinator MUST forward the worker's original parts snapshot through cloud-owned TranscriptionRepository.saveTranscript after cloud ownership frees. Do not re-read current rows and attest them as original. Absent/unverified original hashes defer alignment while preserving text. Same-ID replaced bytes/SHA, move/delete during cloud request require tests. This is an internal API addition, no public entity/schema change. Android unit returns final worker/scheduler/startup/save hook list; coordinator wires only after cloud final.

## C1-C3 consolidated unit delivery

Cloud unit completed and released ownership. Mistral direct segment-only API, documented Voxtral models, encrypted installation-only key storage, model/provider pinned at enqueue, explicit manual overrides retained, no automatic cross-cloud fallback. Private noBackup cooldown/account-key contexts honor seconds/date Retry-After across jobs/restarts, generic Room quota phase, workers yield while waiting; completed paid chunks retained. Setup/onboarding/manual/cost/status IT/EN hooks included. Exact full/per-part source text/language cached and exposed through SessionTranscript.sourceParts.

Implementer executed standalone compilation and94 JVM tests plus JobDao cancellation/retry SQL fixtures/resource/diff checks, all reported pass. Coordinator inspected actual log containing OK(94tests), relevant worker/save APIs, cooldown source and declared scope. No Gradle/full app/live inference or device/GPU/runtime checks executed; independent review remains open. Coordinator must add new global Groq/Mistral model preferences to backup mapping and reserved originalParts forwarding. Only Android alignment remains active after clock/export completed; no extra implementer launched.

PN has temporarily claimed a short targeted core JVM Gradle batch2workers/no-parallel; coordinator confirmed no Gradle acquired/running here. Await explicit release before any own necessary Gradle batch. No device/GPU allowed.

## C7 consolidated unit delivery

Clock/export unit completed29 owned files, report C7_RECORDINGS_EXPORT_INTEGRATION.md lists exact PN merge points. ImportCandidate.recordingOrigin populates frozen fields independently of sorting/noon dates; actual Samsung embedded epoch only, no mediaInfo fallback promotion. ExportOptions.speechClock=false independently enables per-part clock including gaps/midnight/explicit offsets/DST/precision and unknown qualifications. Sessions scope filters graph/audio/history to chosen IDs and retains documented associated material. Source attachmentAllowed omits opaque originals that can carry embedded sibling audio, retains metadata. Personal bundle/plain/loose/index/AI labels reflect recordings and preserve real legacy content.

Standalone compiler+104 JUnit tests (23new), source/resources/diff checks reported PASS; coordinator inspected report, actual OK104tests log and key selection/clock/native source. No Gradle/real Room/Compose/device/GPU/runtime/provider/deployment execution. Full integrated verification/reviews outstanding. Future parent integration preserves PAMPA/native full-history branches, wantsAudio/wantsSources and relay helper; no codec copied here.

Coordinator identified a remaining interaction for Android source codec: app immutable originals retain pre-split/full text, so exact-session exports must project/redact source metadata to prevent sibling text leaking through originals/reportedText. Copies/aliases also remap graph IDs while opaque source JSON still references prior IDs. Android unit requested typed pure portable projection/remap/validation helper + tests; coordinator wires adapters only after final. App-local originals stay intact. Safe alignment inapplicability must be explicit when identity cannot be honestly rebound.

Stable cloud snapshot aca893a; stable clock/session-export snapshot e005527. Coordinator forwarded original worker parts through TranscriptionRepository.saveTranscript to optional SessionRepository snapshot argument (no current-row substitution). Added Groq/Mistral public STT model preferences to BackupSettings.toBackup and restoreSettings, with legacy/default/model roundtrip JVM tests prepared. These new coordinator tests and integrated app compile remain NOT EXECUTED until final necessary Gradle batch. Only Android alignment unit still writing; no initial review until integrated snapshot frozen.

## C4 consolidated unit delivery

Android unit completed SessionRepository/RawTimelinePlan,9new core/alignment files,2new worker/scheduler files and7tests. Exact cloud text/immutable originals retained through timeline operations; original hashes only worker before-image, unknown hashes defer. Private durable snapshot/deadline/cancel/retry queue in noBackupFilesDir. Companion alignment endpoint only, no transcription fallback. Nullable timing overlays separate from WordTimings.complete. Pure portable helpers project selected content, remove sibling originals/reportedText, mark portable_unbound, safe invalidation survives recomposition.

Executed by implementer:58pure/fixture tests (incl RAW regressions)+6in-memory HTTP tests=64pass; changed Android sources standalone compilation and scoped whitespace checks pass. Coordinator inspected actual OK58/OK6 logs and scheduler/presentation/source APIs. Gradle/Hilt/Room/device/GPU/real audio/provider/accuracy NOT EXECUTED. All unit writes finished; remaining substantial adapters use fresh context implementers with separate UI and portable-source ownership. Coordinator owns startup/endpoint/save/cancel/restore/logout hooks. No initial review until those adapters and integrated checks are complete.

Portable follow-through must preserve existing measured timing overlays through native roundtrip while source identity remains explicitly unbound for future alignment. Full native public provenance/history must not be silently dropped; exact-session exports cannot leak sibling originals. UI must show exact text with nullable missing timings and avoid coarse interpolation; these two ready work units are independent.

C4 committed c6eec80. Two fresh Sol/xhigh implementation delegates launched (two active total): UI7d965bb2 owns SessionScreen/AlignmentPresentation/new reading-block helpers/resources/tests; portable e9c5ab5e owns CloudSources + native ZIP export/import adapters/tests. No source overlap. Coordinator retains startup/save/endpoint/cancel/restore/logout hooks, outside both scopes. New UI may use validatePortable to show retained measured overlays while portable_unbound still blocks new alignment. No additional schema/entity contract changes.

PN targeted Gradle batch released per parent (70pass,0fail), no device/GPU/server held. No Gradle acquired/running here. Coordinator must request lease for final necessary2workers/no-parallel batch after source writers finish. Pending coordinator hooks: after paid save call alignment.onCloudSaved for each saved.sessionIds, isolate scheduling exceptions; application and successful endpoint wake; session cancellation and backup restore/logout stop; inspect account/privacy fences. T3 delegates' next says end turn without polling; resume automatically on completion.

## Exact cloud UI consolidated delivery

UI adapter finished9files: SessionScreen + CloudTranscript/Card, AlignmentPresentation/ReadingBlocks,2tests,IT/EN alignment_ui.xml. Exact whitespace/punctuation/UTF16/CRLF retained across1600unit/24line bounded blocks; measured-only highlighting/seek/autofollow, nullable missing evidence no nearby/interpolated fallback. Search crosses blocks and clips highlights. Invalid source metadata shows plain exact current RAW. Valid stored portable overlays display without granting new alignment authority. Legacy Paragraph/Card/paragraphsOf unchanged, no invented cloud chapters/voices.

Executed74 standalone tests(22new+52existing) plus Kotlin syntax parsing, XML/key/placeholder/legacy comparison/whitespace checks PASS; NOT Compose typechecking. Coordinator inspected actual log. No Gradle/Compose rendering/device/GPU/providers/runtime proof. Coordinator must fix global SearchRepository.momentOf legacy interpolation and SessionViewModel.seekIfAsked pre-render seek: cloud search uses exact raw text and nullable AlignmentPresentation.timeAt, missing evidence means no automatic seek. Portable writer still active; integrated checks wait.

PN claimed its next combined Gradle2worker/no-parallel build/JVM/lint batch; coordinator confirmed no lease/running batch here. No heavy resources acquired by this workflow.

UI snapshot committed548414b. Coordinator integration now wired: alignment discovery/scheduling isolated from paid completion for every saved session; application/foreground and successful endpoint/archive wake; stop private alignment queue/work alongside logout and backup restore; session cancellation/delete and explicit IT/EN stop action. WorkManager cancellation runs even if private queue read fails. Paid cancellation/logout/delete remain functional if alignment state is corrupt.

Global search now branches cloud sources to exact RAW text + nullable AlignmentPresentation.timeAt, never legacy interpolation. SessionViewModel revalidates a query jump against current RAW/parts before seeking and consumes missing-time jumps without seeking; a loaded-state collector handles playlist/state ordering. Computer legacy search unchanged. Added5 pure CloudTranscriptMoments tests; these coordinator tests and all new Hilt/Compose hook compilation still NOT EXECUTED pending integrated Gradle. Source diff checks pass; do not call this verified runtime integration. Only portable-source implementer remains active, no source overlap with coordinator hooks.

Coordinator found reported language labels were retained verbatim but the alignment protocol accepts ISO codes only. Added deterministic ISO2/ISO3/English-name conversion for the explicit provider-reported value, never language guessing from audio/text. Original RAW/source language remains unchanged; request/response uses canonical code, overlay retains source label. Unknown/auto stays unknown. Added3 pure tests, NOT EXECUTED yet. Source changes confined to new AlignmentLanguage, AlignmentRepository and AlignmentApplication, no CloudSources overlap.

Parent PN Gradle batch released (reported1079JVMpass,1optionalWorker skip,0failure; lint0errors123warnings); this is PN-only evidence, not this workflow's tests. No heavy resource held here. Final own lease request waits until portable unit stops writing.

## Portable source consolidated delivery and freeze

Last implementation unit completed10owned files; report C4_NATIVE_PORTABILITY_INTEGRATION.md. Typed transfer adapters now wired through native ZIP export/copy/update/aliases. Exact scoped text and measured/missing overlays retained, transfers stay portable_unbound. Restricted exports exclude sibling originals/reportedText; whole authorized exports retain complete public history/reports. Identity mapping uses real source before-images and destination graph; independent local RAW provenance/history retained on conflict. Unsupported nonempty source codecs fail explicitly; null/empty remain distinct. Legacy source with no attested original hashes stays unbound/missing instead of adopting replacement current bytes.

Standalone compilation+133fixture/regression tests+whitespace checks reported PASS; coordinator inspected actual OK133 log, source adapters and documented PN hooks. No Gradle/Hilt/Room/runtime/GPU/provider evidence from unit. All source writers now stopped. Full integrated source freeze follows this commit; no initial review launched before necessary checks.

Actual shared Gradle lease requested and explicitly GRANTED by PN coordinator until release notification. PN holds no heavy resource; its3reviewers are read-only and forbidden Gradle/devices. This workflow now holds Gradle for necessary integrated2worker/no-parallel debug/JVM/Android-test compilation/lint batch under %LOCALAPPDATA%/PampaNotes/build-cloud-recordings. Device/emulator/GPU/server/livecloud remain prohibited. No publication/main merge.

## Integrated verification and initial review freeze (2026-11-08)

All seven implementation units have stopped writing. Coordinator integration corrected the app/core Room classpath boundary with core-owned DatabaseTransactions, an app-test cross-module smart cast, and legacy export fixtures that supplied invalid nonempty source JSON. The codec remains strict. Human-readable cloud export now keeps exact RAW text instead of reconstructing segment text; measured evidence or explicitly approximate coarse anchors are used, per-part real clocks preserve gaps, and sparse evidence does not synthesize chapter quotations. Five focused export tests cover exact text, absent/stale timing, measured/coarse anchors and an hour gap.

Necessary integrated Gradle batch PASSED with --max-workers=2 --no-parallel and external buildRoot C:/Users/casua/AppData/Local/PampaNotes/build-cloud-recordings. Tasks: :app:assembleDebug, :core:testDebugUnitTest, :app:testDebugUnitTest, :core:compileDebugAndroidTestKotlin, :app:compileDebugAndroidTestKotlin, :app:lintDebug. Actual XML totals: core1075 tests =1074passed/1optional Worker skipped/0failed; app65passed/0failed. Combined1139passed/1skipped/0failed. Lint0errors127warnings. Debug APK44555136bytes. Build completed in4m49s,422tasks(47executed/375up-to-date). Android tests were compiled only, NOT EXECUTED.

Artifacts: %LOCALAPPDATA%/PampaNotes/cloud-recordings-verification/gradle-integrated.log; external core/app test-results/testDebugUnitTest XML; app/reports/lint-results-debug.xml and HTML; app/outputs/apk/debug/app-debug.apk. Earlier failed attempts are retained under the verification directory: SDK environment missing (fixed process-local ANDROID_HOME), Room app classpath (core bridge), test smart cast, typed export fixture. Targeted corrected export batch passed before the final complete run. No failures are hidden or counted as successful runs. Companion286tests remain separate archived implementer execution, not part of the1139JVM count.

Engine doctor PASS for engine3.0.5. Protected source diff against ea4e0a2 is empty for engine/, engine.properties, app/build.gradle, storemanifest and worker/. Scoped git diff --check PASS. No schema changes beyond the frozen six nullable fields. Room migration execution, device/emulator/physical-device/GPU, live providers/credentials/audio and real WhisperX/acoustic accuracy: NOT EXECUTED, runtime gates explicitly user-waived. Compilation and mocks do not establish runtime accuracy.

Gradle lease RELEASED by actual notification to PN; no heavy resource held. PN exclusively owns the narrow Worker public-evidence backward-compatibility fix: older clients rewriting payloads can otherwise drop recordedAt*/cloudSourceJson. This checkout has no Worker writer or change. Final combined integration must include and verify PN's reviewed fix; no older-client retention claim is made here. PN source-blob/PAMPA integration likewise remains parent-owned per the two integration reports.

Initial independent review round0 is next, same frozen source SHA for all three Sol/xhigh reviewers, source-read-only, no Gradle/devices/GPU/live calls. Common C1-C9 rubric above. Three reports and verified-only comparable mean/min aggregate required before delivery. Correction rounds consumed0/3. Build success is not review approval.

## Round0 reviews and correction1 (current state)

User increased implementation ceiling3->4 and reviewer ceiling to category count9. Actual runtime has4total slots including coordinator, so at most3active children; no exception inferred. No quota-filling implementation. Next review round uses9fresh distinct critics, one per C1-C9, queued in groups within actual capacity. Current reviews were not interrupted. Round0 added a fourth independent data-boundary reviewer; all four complete, actual codex/gpt-6.1-sol/xhigh. Sources stayed frozen03db73dac6d3c5aabcfcb78c6436996d68592d31 throughout. Full verbatim reports preserved under workflow-reviews/round0.

| Criterion | R1 cloud | R2 alignment | R3 recordings | R4 data | Mean (valid/expected) | Minimum | Outcome/open issues |
|---|---|---|---|---|---|---|---|
| C1 | 9 | NV | NV | NV | 9 (1/4) | 9 | No confirmed defect in reviewed scope |
| C2 | 9 | NV | NV | NV | 9 (1/4) | 9 | No confirmed defect in reviewed scope |
| C3 | 6 | NV | NV | NV | 6 (1/4) | 6 | Preparation429 bypasses cooldown; cancel/requeue race |
| C4 | NV | 5 | NV | 6 provisional | 5 (1/4) | 5 | Language-label overlay rejected; legacy export replaces RAW; scoped-history gaps |
| C5 | NV | 9 | NV | NV | 9 (1/4) | 9 | Source/fixtures only, acoustic accuracy NV |
| C6 | NV | NV | 6 | NV | 6 (1/4) | 6 | Stale personal scope can delete moved school session |
| C7 | NV | NV | 6 | 6 provisional | 6 (1/4) | 6 | Older native update erases precise origin |
| C8 | 8 provisional | 8 provisional | 6 | 5 local | 5.5 (2/4) | 5 | Origin loss; refinement sibling leak; legacy export failure; long provenance sync limit |
| C9 | 9 | 9 | 9 | 9 local | 9 (4/4) | 9 | Archives inspected, runtime gates waived; no SHA attestation embedded in archives |

NV/NA/provisional/missing excluded; C8 and C9 compare local checkout scope only, combined PN integration remains unverified. Scores faithfully transcribed; no majority/mean overrides confirmed mandatory defects. Decision CORRECT. Nine unique confirmed/reproduced findings, origin loss appears under both C7/C8 but counted once:

1. R1-F1 metadata/preparation429 is swallowed or escapes durable callback; C3.
2. R1-F2 cancel during429 exit strands CANCEL_REQUESTED; C3.
3. R2-F1 canonical request language but original-label overlay fails validation; C4.
4. R2-F2 legacy cloud RAW/null source export reconstructs segment text; C4.
5. R3-F1 same-audio older native update erases five known origin values; C7/C8.
6. R3-F2 scope checked before suspension, moved-to-school session still deleted; C6.
7. R4-01 selected session BEST/native can retain pre-split refinement with sibling text; C4/C7/C8.
8. R4-02 honest unknown legacy hash incorrectly blocks restricted text export; C8.
9. R4-03 valid500k RAW provenance duplicates push payload beyond1.9MB, rejected outbox silently cleared/tooLarge omitted from WorkData; C8.

Correction1 of maximum3 now begins. Fresh disjoint units: typed source/selected history (R2-F1,R4-01,R4-02); Android sync long evidence/visible failures (R4-03, no Worker writes); destructive recording scope/native origin preservation (R3-F1/F2). Coordinator handles small cloud retry fixes and legacy human export. No new public entity/schema fields. PN holds current Gradle lease; source/light fixtures only until explicit release and own new lease. Protected paths and no live/device/GPU/server rules unchanged.

Correction1 delegate task suffixes, all actual Sol/xhigh: typed source04071687-8281-4147-bc09-884bf529a7ef; incremental sync c6f5333e-439e-4cd0-a2e0-654459732e31; recording scope/origin32e1a9bf-65b1-45d7-8776-282279deb04e. Runtime accepted all3running, no exception invented. Last unit now completed, first two still own their files; no replacement/quota unit launched. PN explicitly RELEASED Gradle after its own bounded exit0 batch; no heavy lease currently held here.

Recording corrections committed e7fdd68 after coordinator source/log inspection. Final personal/current-folder scope and deletion share core transaction after asynchronous cancellation; generic session deletion is transactional too, removes files only after commit and protects paths still referenced by siblings. Same-audio importer/pure merge retain one coherent origin tuple: reconciled identical partID+nonblank matching SHA required, higher usable precision wins, incoming wins ties. No mixed provenance/createdAt inference. Implementer standalone57/57tests including20new regressions passed, eight expected baseline origin failures confirm regressions; evidence %TEMP%/pampa-recordings-r1-9c0d266bd8014dc594f1f26b185774ca. Real Room/Gradle NOT EXECUTED for this correction yet.

Coordinator corrections committed b827b5e. Guard preparation/beforeUpload metadata now inside durable429 callback, spending refresh propagates429 immediately before further metadata/reservation/upload. Atomic DAO requeue settles CANCEL_REQUESTED as CANCELLED; worker avoids delayed resume for terminal/missing jobs and cleans private chunks. Legacy cloud RAW with null/empty provenance takes exact text export with no segment replacement/unsupported timing. Added focused JVM regressions (NOT EXECUTED yet) plus source-extracted actual DAO/schema SQLite tests at core/src/test/python/test_cloud_requeue.py:4/4PASS, covering cancel before/after requeue, normal pinned wait and terminal/missing rows. Integrated Gradle and independent round1 remain outstanding. No schema/protected/Worker changes.

Typed language/history correction unit completed; coordinator inspected actual53/53test footer and source diff. Thirteen new regressions include actual gather/BEST/Markdown/native ZIP, name/uppercase/ISO language applications, scoped refinements, combined selected sessions and legacy import remapping. Five baseline failures across nine probes reported in archived baseline log. Overlay canonical identity now distinct from exact provider label. Restricted refinement collection consumes paired versions/activeId before human/native gathering; missing/partial lineage uses its actual RAW parent. Unsafe supplied native graphs refuse before output, whole-note/folder histories retained. Unknown historical SHA permits lossless text transfer without promoting audio evidence; known mismatch and ambiguous multipart subsets still fail. Integration hook documented in C4_NATIVE_PORTABILITY_INTEGRATION.md. Evidence %TEMP%/pampa-cloud-round1-scope-0dc9167cc5734e4798f9688153421730. No integrated Gradle/runtime execution yet. Only sync source unit remains active; no heavy lease held.
