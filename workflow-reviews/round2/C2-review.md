Reviewer: C2 independent critic (Codex), new independent reviewer, no authorship and no subagents. Actual model/effort: GPT-6.1 Sol / xhigh.

Phase: integrated delivery, correction review ROUND2 (second of maximum three correction rounds after initial review).
Frozen HEAD: 3f88762955de1d761b2eaa126eea35694e14cad7.
Branch: t3/cloud-recordings-parallel. Base: ea4e0a2.
Workspace: C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel.
Decision: CORRECT. One confirmed mandatory C2 functional defect remains.

|Criterion|Score|Verification status|Reason and evidence|Issues and severity|
|---|---|---|---|---|
|C2|6/10|VERIFIED SOURCE + EXECUTED FIXTURE; compiled-code replay is ARCHIVED CHECK; worker/device execution NV and user-waived|New cloud jobs snapshot provider/model/options at TranscriptionRepository.kt:123-151, legacy folder/global/work-plan/manual precedence at :233-257, retry retains identity at :496-510 and Daos.kt:724. Cloud dispatch and error handling contain no cross-cloud fallback. Forty archived JVM tests and nine source-extracted SQL checks passed independently. An additional deterministic repository fixture and production-SQL interleaving demonstrate that a stale cloud pin can corrupt a row already moved to the companion.|P2 / medium: pinTranscription does not compare provider identity; a privacy migration can be followed by an obsolete cloud model pin. With legacy OpenRouter plus an empty global model, the companion row acquires a blank model and is rejected before transcription. Blocks C2 companion-only preservation.|

Confirmed finding C2-R2-01 — P2 / medium, stale cloud pin crosses a completed privacy migration.

Trigger: a pre-upgrade queued OpenRouter transcription has model=NULL, optionsJson=NULL (old options with missing pin fields are also exposed), and no global OpenRouter model. The worker has passed effectiveProvider(job) while privacy was off. The user then enables “only home computer”; its migration moves this same queued row to custom before the original worker finishes pinForRun. The companion queue has not yet claimed the row.

Reproduction and actual evidence:

1. SettingsViewModel.kt:389-392 writes customOnly and calls moveCloudTranscriptionsToComputer. TranscriptionRepository.kt:324-329 moves ordinary jobs while preserving explicit cloud exceptions.
2. Daos.kt:660-661 changes provider from openrouter to custom and clears model, retaining the original options.
3. The old worker still enters pinForRun with its former cloud row (TranscriptionQueueWorker.kt:321-342). pinForRun computes a model from that stale provider and submits the old model/options as its compare values (TranscriptionRepository.kt:252-256).
4. Daos.kt:582-583 compares id, QUEUED state, old model and old options, but does not compare provider. For a legacy NULL model/options row both old compare values still match after the privacy migration.
5. The executed source-extracted SQLite fixture applied the actual moveTranscription and pinTranscription SQL against schema 15. Both updates affected one row. The final record was provider=custom, model="", state=QUEUED.
6. A tiny Java fixture called the actual already-compiled TranscriptionRepository.pinForRun, reusing CloudJobPinningTest's existing DAO mock (which uses the same production model/options comparison). It reproduced before=custom/null, after=custom/[] and failed an explicit invariant assertion, exit 1.
7. The worker re-reads that result, binds the custom provider, then rejects its non-null blank model at TranscriptionQueueWorker.kt:342-370. Its outer handler fails the job at :164-173. This last worker consequence is VERIFIED SOURCE / INFERENCE from the reproduced record; no Android worker or companion was executed.

Requirement consequence: the privacy switch can turn a migratable pending transcription into an avoidable UnknownModel failure. This does not demonstrate a cloud upload, a cross-cloud fallback, or a charge. It violates C2's requirement to preserve companion-only handling through enqueue/start/settings changes and to reject stale identity changes during pinning.

Correction boundary for the author: include expected provider in the pin compare-and-set and use the current authoritative row when the provider changes; re-evaluate the provider/model together before dispatch. Add a regression interleaving privacy migration with a legacy null-model pin, including empty-global OpenRouter. Do not merely special-case the blank string, since a nonblank cloud model can otherwise be written into the companion row too.

The established same-provider stale-pin test passes: CloudJobPinningTest.kt:129-144 and test_cloud_requeue.py:119-125 verify that an already-saved model cannot be replaced by another stale pin. Those tests do not change the provider between read and pin, so they do not cover this finding.

Other C2 evidence independently inspected:

- Automatic Groq, OpenRouter and Mistral selection is stored at enqueue. OpenRouter uses manual model, then folder model, then global model; Groq/Mistral use manual model then their respective configured model (TranscriptionRepository.kt:226-230). Changes to the global preference after enqueue do not reselect an existing cloud job.
- Pre-upgrade OpenRouter rows with folder A and global B or empty global recover folder A. Known row models, known work-plan models and manual overrides are retained (TranscriptionRepository.kt:241-257; CloudJobPinningTest.kt:129-172). The focused archived tests were independently replayed.
- Worker binding selects exactly the row's provider, and capability validation rejects an unavailable model instead of replacing it (TranscriptionRepository.kt:595-649, :718-725; TranscriptionQueueWorker.kt:348-372). A missing key fails that provider. OpenRouter metadata remains attached to the chosen OpenRouter model.
- CloudSpending.beforeUpload uses request.model unchanged after live policy checks (CloudSpending.kt:134-165). It does not switch service or model to resolve pricing, quota, auth or credit problems.
- Cloud sendWithRetry dispatches once through the supplied cloud provider; bounded transient retry is reserved for the companion (TranscriptionRunner.kt:768-800). Rate limits requeue the same job/provider, while other errors fail it (TranscriptionQueueWorker.kt:465-497). No automatic path from a Groq/Mistral error to paid OpenRouter was found.
- Explicit cloud overrides remain a per-job exception to customOnly, and their options survive retry (TranscriptionRepository.kt:338-341, :496-510; TranscribeOverrides.kt:31-34). Ordinary cloud jobs migrate to the companion.
- Account changes fence resumed cloud jobs (TranscriptionRepository.kt:237-240); account/key changes are checked before requests (:653-671 and CloudRequestGate.kt:35-59). Successful HTTP responses check identity before returning (TranscriptionHttp.kt:190-206). This is source/offline evidence only.

Executed independently, all within the authorized offline scope:

- Replayed existing compiled JVM suites using JUnitCore and cached dependencies, without Gradle or Kotlin compilation: CloudJobPinningTest 9, OpenRouterWiringTest 6, TranscribeOverridesTest 7, CloudDirectMetadataTest 6, CloudCooldownTest 6 = 34 passed; SessionCloudTranscriptionTest 6 passed. These are archived compiled classes, not a fresh build. The HTTP fixture supplies in-memory HttpURLConnection objects and opens no sockets.
- Ran the production source-extracted DAO/schema SQL suite: 9 passed, native Python exit 0. This is SQLite evidence, not Room/WorkManager runtime.
- Executed the additional production-SQL privacy/pin interleaving: obsolete pin accepted after migration, confirming the defect.
- Compiled and ran only the tiny isolated Java reproduction fixture against the supplied archived application classes: expected invariant failure, exit 1. Production sources were not compiled or changed.
- Checked SHA-256 of all 523 source-manifest entries against this workspace: zero mismatches. Checked the six relevant XML artifacts and gradle.log against verification-manifest.json: seven matches, zero mismatches. Recorded current replay class/jar/classpath hashes in replay-input-hashes.json.

Archived checks inspected, not newly executed:

- The supplied verification manifest names the same clean full SHA and reports process exit 0. gradle.log:663 contains BUILD SUCCESSFUL in 8m 42s.
- The six directly inspected XML suites contain 40 tests, zero failures/errors/skips, matching the independent archived-class replay. The manifest's whole-build 1,244-pass/1-skip totals are not added to the replay count.
- Debug assembly, lint and AndroidTest compilation remain archived build evidence. No fresh integrated build, Gradle task, or instrumentation execution was performed. Old reviewer reports and round0/round1 approval conclusions were not consulted.

Limits: runtime/device/emulator/physical hardware/GPU/real-model/acoustic/Room-migration/instrumentation/rendered-UX evidence is NV, explicitly USER-WAIVED and NOT EXECUTED. No actual audio, credentials, live provider/service calls, companion/Worker startup, listener, deployment, publication, other worktree, source fix, commit or parent message was used. PN/ZIP, Worker backward-client behavior and combined compatibility remain external UNVERIFIED and receive no score here.

Workspace integrity: initial and final git status --porcelain=v1 were empty; initial and final HEAD were exactly 3f88762955de1d761b2eaa126eea35694e14cad7 on t3/cloud-recordings-parallel. Final working-tree and staged diffs both returned exit 0. Repository sources, review documents and staging were unchanged. All persistent review artifacts are under this unique TEMP directory.

Artifacts in this directory: core-replay.log, app-replay.log, production-sql-suite.log, race-replay.log, sql-race-and-hashes.log, C2PrivacyPinRace.java, sql_race_and_hashes.py, replay-input-hashes.json, and the Java/JUnit argument files. One initial reproduction launch used an unsuitable boot classpath and failed before exercising the repository; the corrected application-classpath launch produced the reported invariant failure. No result from that initial setup failure is used as defect evidence.

