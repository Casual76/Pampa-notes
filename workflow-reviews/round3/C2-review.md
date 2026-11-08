# Independent C2 review — integrated delivery, correction ROUND3

Reviewer: C2-Independent-Sol-R3 (new independent critic; no authorship and no subagents).
Actual model / effort: GPT-6.1 Sol / xhigh.
Frozen SHA: 351e3fd44bf64b07d6437b1d1c8b533a7bf9c79f.
Branch: t3/cloud-recordings-parallel. Base: ea4e0a2.
Workspace: C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel.
Assigned scope: C2 only. Decision: **CORRECT**.

| Criterion | Score | Verification status | Reason and evidence | Issues and severity |
|---|---|---|---|---|
| C2 | 6/10 | VERIFIED SOURCE + EXECUTED FIXTURE + CACHED COMPILED REPLAY + ARCHIVED CHECK; runtime gates USER-WAIVED / NOT EXECUTED | Exact cloud provider/model and explicit overrides are retained in enqueue, pin, retry and restart paths. The folder/global/legacy precedence and provider CAS corrections pass current pure replays and production SQL fixtures. However, a reproduced pre-start privacy interleaving lets the obsolete cloud worker fail the newly migrated companion job. Sources: TranscriptionRepository.kt:115,226,234,329,501,534,600,658; TranscriptionQueueWorker.kt:164,343,351,372,382,412; Daos.kt:660,668,686. Tests: CloudJobPinningTest.kt:53,68,117,129,147,175,194,206; independent fixtures and logs in this report directory. | **P2 / material / blocking C2:** migrated custom/QUEUED job becomes custom/FAILED from a stale OpenRouter pre-start privacy error; automatic companion delivery is lost until manual retry. |

## Prioritized finding

### F1 — P2: a pre-start cloud error can fail an already migrated companion job

**Trigger.** An automatic OpenRouter job has a fully pinned model/options. After the second privacy check and provider binding, the worker suspends at `partsOf` (TranscriptionQueueWorker.kt:351-372). The user enables “only the computer”; SettingsViewModel.kt:389-392 migrates the still-QUEUED row to `custom` and wakes that queue. The old worker resumes before the companion queue takes the row.

**Reproduction / executed evidence.** `C2PreStartMigrationFixture.kt` invokes the **actual cached compiled** private `transcribe` body with the actual compiled TranscriptionRepository and CloudTranscriptionGuard. Only dependencies and the settings/DAO interleaving are simulated. The AudioPart entity is a mock: no audio is read. The `parts.bySession` pause point changes privacy and replaces the authoritative row with the exact migration result (`custom/QUEUED/model=null`). The next OpenRouter validation invokes the guard's `listModels`, which throws `CompanionOnly` at CloudTranscriptionGuard.kt:16. It escapes `transcribe` at TranscriptionQueueWorker.kt:382 because its recovery `try` begins only at line 412; the existing CompanionOnly handler is at 469. The fixture asserts the escaped error, authoritative row identity, zero calls to `start`, and zero HTTP metadata calls. See `independent-fixtures.log` and `worker-fixture.log`.

The outer `doWork` catch (TranscriptionQueueWorker.kt:164-174) handles this exception with generic `fail(job, translated)`; `lostComputer` returns null for the stale OpenRouter identity (line 232). `fail` at 718 delegates by ID to TranscriptionRepository.kt:472-474. Production `JobDao.fail` (Daos.kt:686-690) has no expected-provider/dispatch-ownership predicate, so it may update the migrated queued row.

`stale_prestart_disposition.py` executes the **exact production SQL extracted from Daos.kt**, using the schema-15 jobs table in in-process SQLite. Observed:

```text
AFTER PRIVACY MIGRATION {'provider': 'custom', 'state': 'QUEUED', 'model': None}
AFTER STALE WORKER FAIL {'provider': 'custom', 'state': 'FAILED', 'model': None, 'errorCode': 'companion_only'}
CUSTOM QUEUE HAS NO JOB; process restart does not recover FAILED row
```

`nextQueued(custom)` finds no job after the stale failure, and `requeueInterrupted` affects zero rows. This is a source-verified and fixture-reproduced state transition; actual WorkManager scheduling was not run. The asynchronous order is deliberately controlled, rather than claimed as a device observation.

**Requirement consequence.** The provider CAS prevents an old cloud model being written into the companion row, and cloud privacy remains closed. Nevertheless, the requirement to honor a privacy migration and deliver the job through the correct queue is not met for this interleaving. Enabling companion-only can strand the first transcription in FAILED until explicit retry. This finding does not allege an upload, a charge, or cross-cloud fallback.

**Correction boundary (recommendation only).** Cover the pre-start suspended steps with privacy/migration recovery and reject obsolete worker error dispositions against a changed authoritative provider/state; preserve or wake the current queue. A start-only provider CAS does not protect a failure occurring before start. No source change was made by this reviewer.

## C2 trace and evidence that passed

- **Automatic enqueue, all cloud providers — VERIFIED SOURCE / EXECUTED FIXTURE.** ImportViewModel.kt:592-600 passes the selected provider; personal automatic imports remain companion-only. TranscriptionRepository.kt:123-151 stores the selected provider, model, upload plan and overrides. `modelAtEnqueue` at 226-231 selects Groq/Mistral saved STT models and OpenRouter explicit model, then folder choice, then global choice. The independent matrix exercised Groq/OpenRouter/Mistral with automatic and explicit per-job selection.
- **Legacy upgrade / stale callers — CACHED COMPILED REPLAY + EXECUTED SQL.** `pinForRun` at 237-262 reads the current row, returns null for deleted rows, respects migrated/already-pinned rows, recovers an established work-plan model before consulting folder/global defaults, and uses expected provider/model/options CAS. CloudJobPinningTest.kt:129-172 covers folder A with global B and empty global; known row model, work-plan model and manual override; repeated stale callers. Lines 175-210 cover migration during policy lookup, already migrated/deleted rows, and expected-provider start forwarding. Production SQL fixtures independently cover migration before and after pin, cancellation and stale pin/start rejection. These corrections passed; F1 is a distinct later pre-start disposition gap.
- **Worker ownership / start — VERIFIED SOURCE.** TranscriptionQueueWorker.kt:343-354 uses nullable `pinForRun`, stops when no longer queued, kicks the current provider when migrated, and reevaluates privacy. At 391-394 it uses expected-provider start and wakes a newly migrated queue when start rejects ownership. After start, the watcher and CompanionOnly handler at 469-473 and 555-603 requeue/migrate; F1 occurs before those handlers.
- **Retry / restart / default changes — VERIFIED SOURCE / EXECUTED FIXTURE.** Repository retry at 501-515 retains overrides; Daos.kt:723-727 retains the model when the provider stays the same and clears it for privacy-driven migration. Requeue and interrupted-restart SQL do not replace provider/model/options. `sendWithRetry` at TranscriptionRunner.kt:775-786 uses the same bound cloud provider and does not enter companion retry/fallback selection. The independent matrix passed **42 combinations**: 3 providers x automatic/manual x rate/auth/credit/network/timeout/model-capability/parse failures, with global provider/model/chunk defaults changed before retry. DAO storage is mocked in that matrix; actual transition SQL was separately executed.
- **Keys / accounts — VERIFIED SOURCE / EXECUTED FIXTURE.** `pinForRun` checks durable account context, and `guardCloud` at 658-676 checks the bound account/key before metadata/upload. The independent matrix passed all **6 provider x key/account-change** cases before metadata, with zero HTTP calls. Existing pinning replays cover account changes across resume and key/account cooldown scoping.
- **Manual 1.3.1 controls — VERIFIED SOURCE / CACHED COMPILED REPLAY.** The additive STT model field leaves language/vocabulary/diarization/pieces/computer override application intact (TranscribeOverrides.kt:15-53; diff against ea4e0a2 inspected). Explicit cloud consent is tied to the exact chosen provider, including Mistral (31-32). The UI emits provider/model and existing controls into per-job overrides (TranscribeOptionsSheet.kt:79-89,139-158), and repository retry preserves them. Rendered behavior is not claimed.
- **No automatic cross-cloud fallback — VERIFIED SOURCE.** `bind` is an explicit provider switch (Repository.kt:600-655); missing credentials return null, model validation rejects unsupported choices, and worker rate/error paths retain/requeue/fail the same job. There is no automatic cloud-provider substitution on rate/auth/credit/network/metadata/capability errors in the inspected enqueue/worker/runner paths. This is control-flow evidence, not a pricing or provider-billing claim.

## Checks performed now versus archived evidence

**Executed now:**

1. Verified exact HEAD, branch and empty working tree/staging before review.
2. Read CLAUDE.md/applicable repository guidance and the requested current agentic-workflow/runtime and Fluid/material reviewer instructions. No applicable workspace AGENTS.md was found. No other critics' reports, workflow verdicts or round0/1/2 review conclusions were used.
3. Rehashed **537/537 declared source inputs** and **166/166 declared artifacts** from correction3-351e3fd4: no mismatches. See `manifest-rehash.json`.
4. Ran `python -B core/src/test/python/test_cloud_requeue.py`: **11 passed**. Source-extracted SQL, not Room/WorkManager runtime.
5. Replayed **19 existing tests** from cached compiled classes: CloudJobPinningTest (12) and TranscribeOverridesTest (7), **all passed**. No Gradle or source recompilation for these tests; patched temp classes from the prior recipe were explicitly excluded from the classpath.
6. Compiled only two tiny isolated Kotlin fixture files in this unique TEMP directory (256 MB compiler heap, no Gradle) and ran **3 fixture tests**, all assertions passed: the confirmed pre-start exception escape, 42 retry matrix cases, and 6 key/account fences. No network listener or service was created. Supporting application/core classes and dependencies were cached, not rebuilt. `replayed-artifact-hashes.json` records 89 input hashes; command arguments and logs are retained. An initial harness run caught only reflection-wrapped exceptions and failed when the expected error resumed directly; that harness-catch mistake is preserved in `worker-fixture-initial-harness-catch.log`, corrected only in the temporary fixture, and rerun successfully.
7. Ran the independent stale-disposition SQL fixture: confirmed the blocking migrated-row failure and lack of restart recovery.

**Coordinator archive inspected / independently parsed (ARCHIVED CHECK):** process-start/process-exit, raw Gradle log, source and verification manifests, and raw XML for correction3-351e3fd4. The archived run reports exit 0 / BUILD SUCCESSFUL in 3m04s, 422 tasks (21 executed, 401 up-to-date). Independent raw XML totals: core 1239 pass + 1 skip; app 67 pass; 1306 pass / 1 skip / 0 failures/errors. Lint manifest reports 0 errors and 127 warnings. These are archived integrated checks, not newly executed here; tasks marked UP-TO-DATE are not fresh execution. The historical failed attempts and standalone outcome totals are not counted as current successful evidence or added to these totals.

**NV / USER-WAIVED / NOT EXECUTED:** devices, emulators, hardware/GPU/models/actual audio, credentials, provider inference/live services, companion/Worker startup, Room migration runtime, WorkManager runtime, instrumentation execution and rendered UX/acoustic/model accuracy. AndroidTest in the coordinator archive is compilation only. No runtime, rendering, model-quality or billing conclusion is inferred from mocks.

**External compatibility boundary:** PN/ZIP integration, older-client/Worker retention and combined parent delivery remain external UNVERIFIED. This C2-only report grants no combined approval and assigns no other criterion scores. Pending peer reports are not a penalty.

## Decision and workspace integrity

**CORRECT for C2** because F1 is a concrete mandatory privacy/companion-queue delivery failure. The successful provider/model pinning and no-cross-cloud-fallback evidence does not remove it. Correction ROUND3 remains the third of the specified maximum; this reviewer does not reset the workflow counter or authorize another round.

No source/status/review document changes, commits, subagents, parent messages, service startup or publication. All new files are isolated under this unique TEMP directory. Final HEAD/staging/source-manifest recheck is recorded in `final-integrity.json` alongside this report.
