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

Pending initial three launches.
