# C6 spending and folder permissions integration

Implementation unit ownership: policy/accounting/transcription guards, new cost/folder UI, and the coordinator-authorized final Session preflight and runner/relay integration. The workflow status file belongs to the coordinator. Gradle was initially prohibited; the coordinator subsequently granted exclusive ownership for integrated verification. No engine edits, relay implementation edits, commits, pushes, release or deployment were performed by this unit. The preexisting deletion of `companion/tools/__pycache__/holes.cpython-311.pyc` is preserved.

## Completed Session integration and reusable bulk API

The component and enforcement are separate: displaying a summary never sends audio or creates a reservation. All production enqueue/upload paths are centrally guarded without UI participation.

`SessionRoute` now collects `SessionViewModel.cloudPreflight`, `transcribeChecking` and `transcribeError`, and passes them through the real Session screen. Both `onTranscribe` and `onTranscribeWith` reach `SessionViewModel.transcribe`. The screen shows a compact inline preflight next to the transcription action and an action opening the same cost-control sheet. Primary transcription, retry and per-job options share `SessionCloudTranscription.enqueue`: obtain fresh policy/budget preflight, refresh stale OpenRouter pricing when a cap needs it, recheck, preserve overrides, and enqueue/wake only an accepted queue. Duplicate taps are serialized by the ViewModel. Errors remain localized inline. The per-job sheet receives the actual `sessionId` and has its own preflight ViewModel key. Completed relay availability/status behavior is preserved. **No Session hook remains for another integrator.**

The optional presentation API for other bulk/selection surfaces is:

```kotlin
CloudPreflightSummary(sessionIds = selectedSessionIds, provider = explicitProvider?.id)
```

Pass **session IDs**, not note IDs. For note-wide presentation, derive these from `state.sessions.map { it.session.id }`, filtered to the sessions actually being enqueued. The summary combines predicted costs against available budget; one unknown price keeps the total unknown. It shows at most three individual details and all distinct blocking reasons. Actual import/background/bulk enforcement is central even where no summary is presented.

For custom presentation, obtain `CloudPreflightViewModel` with `hiltViewModel()`, collect `state`, and call `observe(sessionIds, provider)` from `LaunchedEffect`. Use separate ViewModel keys for simultaneous independent selections. `CloudPreflightUiState` exposes `items`, `cloudItems`, `loading`, `failed`, `blocked`, `predictedMaxUsd`, `availableUsd`, `batchOverBudget`. Each `CloudPreflight` contains `provider`, `model`, `durationMs`, `policy` (effective rule/source/model source), `estimate`, `totals`, `blockedReason`. Do not interpret absent estimates as zero or as approval. A stale/unavailable UI snapshot must never replace central upload checks.

`CloudSpending.observePreflight(sessionId, provider)` also provides a core `Flow<CloudPreflight>` for another ViewModel. It follows session/part changes, note moves, folder hierarchy/rules, settings and ledger changes. Preflight reads cached pricing; the cost sheet refresh button fetches live metadata, and the guard refreshes expired metadata before an upload. The UI states explicitly that retries/overlap can add costs beyond the original duration estimate.

## Implemented behavior and public APIs

- `core.spending.FolderCloudPolicy.resolve`: nearest explicit `INHERIT/ALLOW/DENY` rule, independent inherited OpenRouter model, effective source names, fail closed for missing folders/ancestors/cycles. Global `customOnly` overrides every folder. Companion is unaffected.
- `CloudSpending.policyForSession(sessionId, partId)`: resolves a queued part's **current** session and note, so moves are rechecked instead of trusting old job metadata.
- `CloudSpending.observeFolder(folderId)`: provides `FolderCloudSnapshot` for the UI without exposing Room/database dependencies to the app module.
- `TranscriptionRepository.enqueue`: persists a failed job with a localized actionable policy error if the current folder denies cloud. Import, home, recordings, note, bulk and selection all call this repository. Retry/background paths encounter the guard again.
- `TranscriptionRepository.bind`: both Groq and OpenRouter are wrapped in `CloudTranscriptionGuard`. OpenRouter's folder model is prepared before the runner creates its cache signature. If a folder model changes between chunks, the next attempt stops with `cloud_model_changed`; retry resolves it before cache reuse. No paid-provider fallback is added.
- `TranscriptionRunner`: supplies job/part/session IDs and the actual submitted chunk interval, including overlap, to the guard. Each retry gets a new reservation. Cached chunks bypass network/reservation creation. A whole-file duration of zero cannot bypass a cap.
- Missing audio now always goes through the existing relay-first `ArchiveFetcher.fetchPart`, even when `archivedAt == 0`; failure wording is no longer companion-specific. Relay retrieval does not mark a durable archive or trigger `onArchived`.
- `TranscriptionHttp.postAudio(onResponse = ...)`: captures raw response `usage.cost` before error/transcript parsing, including valid JSON error responses. Every request receives a unique local attempt ID. Settlement is idempotent by that ID and independent of transcript persistence. Unparseable/truncated responses retain their reservations.
- `CloudSpending.beforeUpload`: policy and key recheck, fresh-price lookup, atomic budget check/reservation persisted before network. Changing/removing the bound key stops the next attempt and keeps old/new charges attributed to separate SHA-256 fingerprints; no plaintext key is written into accounting. Installation totals continue across key changes.
- `DurableCloudCostStore`: process-wide monitor plus OS file lock, fsynced replacement file, atomic rename with recovery copy, schema validation, conservative recovery on corruption. Transactions serialize concurrent providers/store instances. A corrupt ledger does not become an empty/free ledger. It is stored under `Context.noBackupFilesDir/cloud-transcription/costs.json`, intentionally independent of note restore, sync and portable export.
- `SpendingLedger`: decimal USD arithmetic, daily/monthly periods in the stored local timezone, optional per-transcription limit, actual/reserved/unknown totals, in-flight reservations included. Unknown-price outstanding requests prevent enabling a cap from bypassing old uncertainty. Outstanding reservations survive period rollover and a local confirmed-total reset.
- `CloudSpending.configure`, `setFolder`, `data`, `currentQuota`, `refresh`, `reconcile`, `resetConfirmedTotals`: settings and accounting APIs used by the new UI. Reconciliation needs an explicit verified USD amount (including an explicit zero), is refused while the request is in progress, and does not alter provider billing. Reset restarts only confirmed local-period totals and retains history and unresolved reservations.
- Fresh-install `autoTranscribeOnImport` is false. Explicitly saved true/false preferences survive reopening. Backup decode preserves explicit old values; missing legacy consent fields default to false. Local cost settings/rules/accounting are neither replaced nor reset by restoring a notes backup.

## Existing surfaces wired by this unit

- One compact `CloudCostsEntry` in Settings → Transcription. `CloudCostsSheet` uses the existing Fluid modal host and grouped rows; detailed options are collapsed under Advanced. It includes daily/monthly budget, optional per-transcription budget, pricing/freshness, normal-key quota, actual/reserved totals, unknown-price policy, per-model user-estimated rate, reconciliation and explicit local reset.
- Folder overflow menu → `FolderCloudSheet`: inherited/allowed/blocked policy, effective source and optional model ID. No navigation graph change.
- `TranscribeOptionsSheet(sessionId = ...)` accepts optional session context; the real Session call now supplies it and displays the preflight. The primary Session action has an inline summary and control-sheet access too.
- All new Italian/English strings live in `values/cloud_costs.xml` and `values-en/cloud_costs.xml`; shared `strings.xml` is untouched by this unit. `JobText` maps C6 errors for both UI and existing notification consumers.

## Pricing and scope limits

USD is the billing currency. Inputs accept decimal comma, are finite/nonnegative and exact to six decimal places; raw reported actual costs retain their original decimal precision. Reservations round upward to microunits.

Metadata uses normal bearer authorization for `GET /api/v1/key`, filtered `GET /models?output_modalities=transcription`, and each selected model's `/endpoints` catalogue. No management key or external limit mutation is requested. Quota snapshots are keyed by fingerprint and expose limit, remaining, reset, all-time/daily/weekly/monthly usage and available effective-budget fields. UI links to `https://openrouter.ai/settings/keys`.

The pricing parser does **not** infer a per-second unit from `pricing.prompt`, `tokenizer`, context length or zero completion price. The documented Whisper IDs can use live duration rates; known token models retain token metadata but no invented duration-to-token maximum. Unrecognized units/models remain unknown. Every routing endpoint must have valid compatible rates and no unhandled fee for a duration reservation. There is no provider forcing in STT. The cache expires after 24 hours; missing/malformed/stale/future-dated prices are not free.

Unknown cost can be blocked, permitted without a cap, or displayed using an explicit per-model user estimate. With either local cap enabled, only fresh verified duration estimates are admitted; token/unknown/stale/user-estimated costs are blocked. Direct Groq pricing is currently unknown and therefore blocked by enabled caps; folder authorization still applies to Groq. A user estimate is never a guaranteed maximum.

Local controls constrain **predicted** spending on this installation, not a hard account-wide maximum. Real provider rates/fees or billed duration can differ from metadata and an already-running request cannot be recalled. Reported actual overruns are retained and stop later attempts. Configure OpenRouter's own key limits for provider-side controls; OpenRouter has its own in-flight semantics. Unresolved network/cancel/crash outcomes are not silently refunded.

Official sources checked during implementation:

- [OpenRouter STT, pricing strategies, usage.cost and multipart](https://openrouter.ai/docs/guides/overview/multimodal/stt)
- [Current normal API key status](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key)
- [Provider credit/budget/in-flight limits](https://openrouter.ai/docs/api_reference/limits)

## Verification

Before exclusive Gradle ownership, direct Kotlin/JVM compilation and JUnit ran outside the checkout using cached Kotlin 2.2.20, serialization compiler plugin and existing build dependency classes: **114 tests passed** (41 new C6 core cases plus 73 targeted regressions). New Compose components and their existing settings/folder/options consumers also compiled directly using a temporary R symbol surface. Harness and logs: `%LOCALAPPDATA%/PampaNotes/c6-jvm/` (`run.py`, `compile.log`, `tests.log`, `compile_ui.py`, `ui-compile.log`).

After exclusive Gradle ownership, the full integrated command succeeded on the final production source, including the actual Session integration and relay audio regression:

```powershell
.\gradlew.bat --no-daemon --no-watch-fs :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Evidence: `%LOCALAPPDATA%/PampaNotes/c6-integrated.log`. Core: **923 tests, zero failures/errors, one opt-in `RelayWorkerInteropTest` skipped** (922 passed). App: **40 tests passed, zero failures/errors/skips**, including the final two selection-summary cases; final app-only test/assemble run also succeeded (`c6-app-final.log`). New coverage comprises 41 core spending/policy/consent cases, five real Session action coordinator cases, the relay-first runner regression and the two selection-summary cases. Hilt/KSP, Android resource linking and debug APK assembly passed. APK: `%LOCALAPPDATA%/PampaNotes/build/app/outputs/apk/debug/app-debug.apk`, 43,978,890 bytes.

Static checks: both resource locales parse, **91 new keys and placeholders match**, all 91 C6 references resolve, no duplicate C6 names; `git diff --check` and `engine-doctor.ps1 -AppRoot .` pass (engine 3.0.5, clean). Loopback cancellation tests may print the expected test-server socket-reset exception after the client disconnects; JUnit records no failures.

No device visual QA, real paid API, management-key call, deployment or real provider billing was performed. The coordinator owns execution of the opt-in actual Android-client-to-local-Worker interoperability test and independent reviews; those are not claimed by this unit.

All implementation and verification processes from this unit have finished; exclusive Gradle ownership is released to the coordinator for that interop check and final freeze.

## Files changed by this unit

New:

- `core/src/main/kotlin/dev/pampa/pampanotes/core/spending/{CloudCosts,DurableCloudCostStore,OpenRouterCostMetadata,CloudSpending}.kt`
- `core/src/test/kotlin/dev/pampa/pampanotes/core/spending/{CloudPolicyPricingTest,SpendingLedgerTest,CloudSpendingGuardTest,CloudConsentTest}.kt`
- `app/src/main/kotlin/dev/pampa/pampanotes/ui/cloudcost/{CloudCostsViewModel,FolderCloudViewModel,CloudCostSheets,CloudPreflightSummary,SessionCloudTranscription}.kt`
- `app/src/test/kotlin/dev/pampa/pampanotes/ui/cloudcost/{SessionCloudTranscriptionTest,CloudPreflightSummaryTest}.kt`
- `app/src/main/res/values/cloud_costs.xml`, `app/src/main/res/values-en/cloud_costs.xml`
- `OPENROUTER_SPENDING_INTEGRATION.md`

Existing files extended, preserving the already-completed provider/coherence changes:

- `core/.../repo/TranscriptionRepository.kt`
- `core/.../settings/PampaSettingsStore.kt`
- `core/.../backup/BackupModel.kt`
- `core/.../transcription/{CloudTranscriptionGuard,TranscriptionProvider,TranscriptionHttp,TranscriptionError,OpenRouterTranscriptionProvider,GroqWhisperProvider,TranscriptionRunner}.kt`
- `app/.../ui/settings/SettingsScreen.kt` (one entry), `ui/folder/FolderScreen.kt` (menu/sheet), `ui/common/TranscribeOptionsSheet.kt` (optional context), `ui/common/JobText.kt` (localized errors).
- `app/.../ui/session/{SessionScreen,SessionViewModel}.kt` (authorized final main-action/options preflight integration, preserving relay behavior).
- `core/src/test/.../transcription/TranscriptionRunnerTest.kt` (unarchived relay audio fetch regression); `OpenRouterWiringTest.kt` and `repo/SelectedTranscriptConsumersTest.kt` (constructor fixtures for mandatory spending enforcement).

No existing Note/Sync/Storage/ArchiveFetcher/startup/navigation/WorkScheduler/relay/Worker/Gradle/shared-strings files are modified by this unit.
