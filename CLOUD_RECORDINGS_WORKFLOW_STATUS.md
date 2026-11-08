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
| Android exact source / alignment | 6c6c919c-38ab-41d4-8da8-fa600c359921 | running |
| Per-part clock / session export | c7ae6aae-2896-4caf-9d3c-26ea4f5af7e5 | completed |

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
