C3 independent review — integrated delivery, correction ROUND2

Reviewer: Codex C3 independent critic, fresh and not an author.
Actual model/effort: GPT-6.1 Sol / xhigh.
Frozen HEAD: 3f88762955de1d761b2eaa126eea35694e14cad7.
Branch: t3/cloud-recordings-parallel; comparison base: ea4e0a2.
Workspace: C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel.
Scope: C3 only. No other criterion receives a score.

| Criterion | Score | Verification status | Reason and evidence | Issues and severity |
|---|---:|---|---|---|
| C3 | 6/10 | VERIFIED SOURCE; EXECUTED FIXTURE; ARCHIVED CHECK. Android scheduling/runtime NV, USER-WAIVED. | Durable provider/key/account waits, direct metadata gates, cancellation SQL and ordinary 429/cache recovery are supported by source and passing offline checks. A new in-memory fixture using the archived production runner/HTTP gate reproduces a known 429 being converted into an unrecoverable pending-upload marker when its coroutine is cancelled. | P1/high, blocking: automatic resume fails after system cancellation at an observed/persisted transcription 429. Decision: CORRECT. |

P1/high — cancellation after observing 429 strands an otherwise resumable request.

Trigger: an OS/WorkManager-style coroutine cancellation immediately after an actual transcription HTTP status 429 is observed, before that rate-limit exception reaches TranscriptionRunner. This is distinct from the user intentionally cancelling the job.

Executed reproduction: Adversarial.kt uses the existing compiled TranscriptionRunner, MistralTranscriptionProvider, TranscriptionHttp, CloudRequestGate and DurableCloudCooldowns, with an in-memory HttpURLConnection and three dummy bytes. There is no network connection/listener, real audio, model or Android worker. At getResponseCode(), return 429/Retry-After:3600 while cancelling the request coroutine. The cooldown is durably present, but whole.json.sending remains. Advance only the injected gate clock past its deadline; restore a successful mock response and reopen the runner on the identical job/input. The runner throws IOException("Uncertain previous paid request; results retained, automatic rerun refused") before constructing another request. Calls remain 1. The control case without cancellation resumes successfully (calls 2) and a third run reuses the completed result (still calls 2).

Observed output, adversarial-replay.log:
- control: first=RateLimited, intent=false; resume=SUCCESS, calls=2.
- cancelled known 429: first=CancellationException, durable deadline present, intent=true; resume=IOException, calls=1, intent=true.
- fixture exit 0 means that both the working control and the defect assertions reproduced; it does not mean C3 passed.

Code evidence:
- core/src/main/kotlin/dev/pampa/pampanotes/core/transcription/TranscriptionRunner.kt:631 writes the pending-upload marker; :634 only removes it on RateLimited. The equivalent chunk path is :695 and :702.
- core/src/main/kotlin/dev/pampa/pampanotes/core/transcription/TranscriptionHttp.kt:191 observes the HTTP status and :193 persists its quota before body IO.
- core/src/main/kotlin/dev/pampa/pampanotes/core/transcription/CloudRequestGate.kt:61 performs persistence in NonCancellable; cancellation nevertheless propagates back to the caller.
- core/src/main/kotlin/dev/pampa/pampanotes/core/transcription/CancellableConnection.kt:43 preserves coroutine cancellation.
- core/src/main/kotlin/dev/pampa/pampanotes/core/transcription/TranscriptionWorkStorage.kt:62 rejects the leftover marker on resume.
- app/src/main/kotlin/dev/pampa/pampanotes/work/TranscriptionQueueWorker.kt:570 handles a system stop by requeueStopped(:577), preserving the work directory. The subsequent worker reaches the runner failure; :469 maps it to a failed job. This Android worker consequence is VERIFIED SOURCE / INFERENCE, not an executed WorkManager run.
- core/src/main/kotlin/dev/pampa/pampanotes/core/repo/FailedJobs.kt:67 preserves cloud work on manual retry, so merely retrying the same row also leaves this marker.

Requirement consequence: the observed server wait survives, but the job cannot automatically resume after that wait. No duplicate billing was observed; the failure is a permanent conservative refusal on a response that the noncancelled path treats as retryable. Persist a distinguishable known-rate-limit outcome and settle its upload intent despite cancellation, while preserving refusal for genuinely unknown paid outcomes. Add a runner+HTTP regression for the cancellation boundary, including the chunked path.

Positive evidence and adversarial coverage:

- Seconds, HTTP-date, fractional round-up, 30-day and numeric-overflow deadlines: CloudRetry.kt:28 and CloudCooldownTest.kt:16. A zero/past deadline has a minimum one-second yield at CloudRetry.kt:40. DurableCloudCooldowns at :49 extends monotonically, reopens from disk and isolates provider, key and account. CloudCredentials.kt:48 locates the store in noBackupFilesDir. A changed account is fenced at TranscriptionRepository.kt:240 and its guard at :660.
- Direct setup/health/catalogue/quota/pricing HTTP shares the gate: TranscriptionHttp.kt:38, :64, :125, :145; CoreModule.kt:54 wires the actual application client. CloudDirectMetadataTest exercises cooldowns across reopened clients, key/account changes, cancellation after 429, corrupt storage and all HTTP methods before connection. CloudDirectRefreshTest covers /key, /models and /endpoints 429, unknown-cost ALLOW, and quota persistence failure without a reservation/upload.
- Preparation and beforeUpload are inside CloudTranscriptionGuard's rate-limit translation (:15, :28, :35). CloudSpending.kt:140 rethrows metadata 429 and storage/control failure; :153 persists accounting before upload. The new preparation test at CloudCooldownTest.kt:101 passes. The older CloudSpendingGuardTest has a TinyHttpServer in setup, so it was inspected as source/archived evidence only and deliberately not replayed under the no-listeners rule.
- Cloud requests have no in-worker sleep/retry: TranscriptionRunner.kt:768 dispatches cloud once; the delay loop at :781 is for companion dispatch only. Worker quotaWait(:485) durably requeues and returns ResumeAt; WorkScheduler.kt:61 schedules a delayed wake with overflow-safe delay. waitUntil at TranscriptionRepository.kt:209 rechecks shared scope/deadline before foreground/probes. WorkManager delivery timing itself remains NV/waived.
- Completed paid responses survive a later 429 and are reused. CloudChunkRecoveryTest's multipart control passes. CloudWorkStorageFailureTest's ten cases pass for options/stamp/input/intent/plan failures, completed-result save failure, interrupted/unknown paid outcome, immutable options and partial-chunk recovery with mocked codecs. Prior paid bytes remain unchanged. No actual codec/audio execution occurred.
- I reproduced the old two-statement cancellation gap using current extracted SQL: cancelIfQueued=0, requeue, requestCancelIfRunning=0 leaves QUEUED. Substituting the current atomic requestCancel makes CANCELLED with no watcher/restart. sql-adversarial.log records this and late markDone/fail/publishProgress/requeue/requeueWithOptions all affecting zero cancelled rows. Daos.kt:703 and :785 close the gap. The nine source-extracted SQLite checks also pass, including result-save cancellation. Repository.markDone at :447 calls finishCancellation after markDone loses to cancellation; no success callback then runs in worker :439. SQLite state interleavings are executed; Room invalidation/worker runtime are not.

Execution record:
- Replayed 30 existing compiled JVM tests: CloudCooldownTest 6, CloudDirectMetadataTest 6, CloudDirectRefreshTest 5, CloudWorkStorageFailureTest 10, CloudChunkRecoveryTest 3. All pass, logs jvm-pure-replay.log and jvm-mocked-replay.log. These are additional executions of archived compiled classes, not a fresh production compilation and not additional integrated XML totals.
- Executed source-extracted SQLite suite: 9/9 pass; sql-adversarial.py separately reproduces old/current gap and terminal callbacks.
- Compiled one isolated temporary Kotlin reproduction against the archived classes (384 MiB heap, approximately four seconds); no Gradle or production Kotlin compilation. Ran two offline scenarios plus a paid-cache reuse control in that fixture.

Archives inspected, not re-executed builds:
- correction2-3f88762/source-manifest-before.json and verification-manifest.json. Independently rehashed all 523 listed source inputs: 0 mismatches. Independently verified hashes of the five C3 test XMLs and gradle.log against the manifest.
- Raw XML totals independently counted: core 1177 pass / 1 skip, app 67 pass; total 1244 pass / 1 skip / 0 failures / 0 errors. The selected five C3 XML suites contain the same 30 passing tests replayed above.
- Hash-matched gradle.log contains BUILD SUCCESSFUL in 8m42s. Manifest records exit 0, debug assembly, unit tests, AndroidTest Kotlin compilation and lint. This is ARCHIVED CHECK, not my own build/instrumentation execution. lint-diff.json's extra unused legacy credit string is unrelated to C3.
- No round0/round1 reviewer conclusions or implementation verdicts were used. No C3 approval is inferred from the integrated test count.

Limits: actual Android runtime, Room migration/invalidation, WorkManager scheduling/stopReason, instrumentation, rendering, hardware, acoustic accuracy, real models, provider calls and companion service execution are NV, explicitly USER-WAIVED and NOT EXECUTED. No Gradle, devices, GPU, credentials, services/listeners, deployment/publication, other worktrees, source edits, commits, subagents or parent messages. External PN/Worker and older-client compatibility remain outside C3; this is not combined approval.

Checkout integrity: before review HEAD was exactly the frozen SHA, git status --porcelain=v1 and staging diff empty. After all tests/report creation HEAD is unchanged and status/staging are again empty. Sources and review/status documents were read-only; all generated artifacts are confined to this unique TEMP directory.

Decision: CORRECT for C3 due to the reproduced mandatory resume failure. No other score is assigned.


