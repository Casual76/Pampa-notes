# Independent CLOUD/RECORDINGS review R3

Reviewer: R3 (new independent reviewer; no authorship, no delegation).
Actual runtime: GPT-6.1 Sol / xhigh, through Codex in T3 Code.
Phase: integrated delivery. Initial round0.
Frozen HEAD: `03db73dac6d3c5aabcfcb78c6436996d68592d31`.
Base: `ea4e0a2`. Branch: `t3/cloud-recordings-parallel`.
Workspace: `C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel`.
Decision: **CORRECT** for the examined scope: two reproduced material failures of required preservation/scope behavior. No fixes authored.

HEAD and porcelain status were checked before review and again at completion: requested SHA and clean checkout. The brief states writers are stopped; this review did not orchestrate other processes. Source and protected boundaries were not modified. Only this unique TEMP directory contains new reproduction/report artifacts. `worker/`, `engine/`, `engine.properties`, release manifest, Gradle build files and version catalog have no diff from the base. `git diff --check ea4e0a2..HEAD` produces no output.

Read agentic-workflow SKILL.md and references/runtime.md, applicable root CLAUDE.md (no AGENTS.md found in this checkout or its ancestor search), fluid-engine SKILL.md and references/regole.md, and material3-expressive SKILL.md. The workflow-status file and other reviewers' verdicts were not read. The two integration documents were used only as API/ownership contracts, not as evidence for their implementation self-assessments.

Evidence labels: VERIFIED SOURCE = inspection of frozen source; EXECUTED FIXTURE = this review's offline JVM/fixture execution; ARCHIVED CHECK = coordinator artifacts inspected, not rerun; INFERENCE = consequence traced from code; NV = unverified, not zero. No provisional numerical scores are used. C1-C5 are NV because this specialization did not examine each complete criterion; partial source observations and archived test counts do not establish those criteria.

All production paths below are relative to the stated workspace. `core/?/` means `core/src/main/kotlin/dev/pampa/pampanotes/core/`; `app/?/` means `app/src/main/kotlin/dev/pampa/pampanotes/`. Test paths use the corresponding `src/test/kotlin` roots.

|Criterion|Score|Verification status|Reason and evidence|Issues and severity|
|---|---|---|---|---|
|C1|NV|NV|No independent end-to-end audit of Mistral documentation, provider HTTP, key setup and error UI. No live inference.|Outside complete examined scope; no invented score.|
|C2|NV|NV|Provider pinning/manual overrides and full dispatch/retry graph were not fully audited.|Outside complete examined scope.|
|C3|NV|NV; partial VERIFIED SOURCE|Inspected CloudCredentials.kt:16-46 and CloudRetry.kt:44-110 for noBackup/private fences, but did not audit all timer, cached-chunk and cancellation behavior.|No complete cooldown score.|
|C4|NV|Partial VERIFIED SOURCE + EXECUTED FIXTURE; overall NV|Audited exact-session portability and RAW evidence through CloudSources.kt:134-221, ExportService.kt:487-576, PampaBundleExport.kt:27-76, PampaBundleCloudSources.kt:12-92; projection/ZIP tests executed. Full background stale-result/apply/search/queue behavior not covered.|Complete C4 approval remains another review's responsibility.|
|C5|NV|ARCHIVED CHECK only; overall NV|Read raw companion log ending `Ran 286 tests ? OK`; did not independently audit the complete authenticated forced-alignment endpoint.|Real model/GPU/audio/accuracy NOT EXECUTED, user-waived.|
|C6|6|VERIFIED SOURCE + EXECUTED FIXTURE|Session identities, legacy access, direct navigation, optional folders and bounded move plans are present. RecordingCatalogTest passes. RecordingActions.kt:36-44 validates delete scope before multiple suspension points; SessionRepository.kt:226-235 then deletes without scope revalidation. DeleteRace reproduces deletion after move to school.|F2, P2: destructive stale-scope failure. Rendered UX and real Room/file lifecycle NOT EXECUTED.|
|C7|6|VERIFIED SOURCE + EXECUTED FIXTURE|RecordingOrigin.kt:28-86, AudioImporter.kt:129-135 and SpeechClock.kt:17-60 separate actual origin from sorting/import values, retain precision and avoid invented zones. Clock/origin tests pass, including hour gaps, midnight, DST, date-only and unknown values. However a native update can erase all five known origin fields.|F1, P2: origin retention failure in update path; same finding as C8, not a separate defect.|
|C8|6|VERIFIED SOURCE + EXECUTED FIXTURE + ARCHIVED CHECK|Six nullable defaults, schema15 and generated 14?15 ALTER statements inspected; null-hash and typed projection/alias/copy/ZIP fixtures pass. PampaBundleImporter.kt:170-175 retains incoming null origin fields for same audio; PampaBundleMerge.kt:20-25 overwrites local parts; PampaBundleCloudSources.kt:42-47 repairs RAWs only. Production merge fixture loses all five fields.|F1, P2. Parent PN/PAMPA/source-blob/relay integration and PN-owned Worker compatibility fix remain external, unverified dependencies.|
|C9|9|VERIFIED SOURCE + EXECUTED FIXTURE + ARCHIVED CHECK|Frozen clean SHA verified; protected diff/whitespace checks pass. Parsed 140 raw XML suites: 1,140 cases, 1,139 pass, 1 optional Worker interop skip, zero failure/error. Build success, APK presence, lint counts and compiled instrumentation tasks inspected. This reviewer independently executed 62 pure tests and two adversarial repros.|Coordinator's three-review aggregation is outside this reviewer; no circular penalty for reports still being collected. Runtime migration/instrumentation/device/provider evidence NOT EXECUTED, explicitly waived.|

## Prioritized confirmed findings

### F1 ? P2 ? Existing real recording origins are erased by an older native ZIP update

Affected requirements: C7 per-part origin retention and C8 native public metadata retention/legacy adapters.

Code:
- `core/?/importing/PampaBundleImporter.kt:164-175`: `sameAudio` compares nonblank hashes, but its branch uses incoming `part.copy(...)`. It preserves file/archive/name/duration/size properties and does not preserve the five existing origin fields when incoming fields are null.
- `core/?/importing/PampaBundleMerge.kt:20-25`: incoming part rows replace the local row for a matching ID.
- `core/?/importing/PampaBundleCloudSources.kt:42-47`: the new merge wrapper delegates this graph merge and repairs transcripts, not audio-part origin fields.

Reproduction:
1. Have a valid local note/session/part with part ID `p`, hash `a` repeated 64 times, and known epoch/local/zone/source/precision.
2. Import/update an older native ZIP graph for the same note/session/part IDs and same audio hash, with all five new origin fields absent (therefore decoded as null).
3. The same-audio importer branch produces the incoming null fields, and the production typed merge replaces the local part.

`ReviewFixture.java` invokes the archived compiled production `PampaBundleCloudSources.merge` through reflection, with synthetic valid entity values and no Android/Room/media/provider execution. `fixture.log` records:

```
recordedAtEpochMs=[1793523600000, null]
recordedAtLocal=[2026-11-01T10:00:00, null]
recordedAtZoneId=[+01:00, null]
recordedAtSource=[media_metadata, null]
recordedAtPrecision=[second, null]
REPRODUCED: all five known local origin fields become null after older incoming merge
```

VERIFIED SOURCE establishes how the actual importer reaches that merge with null values. EXECUTED FIXTURE establishes the merge result. The complete SAF/Room import UI was NOT EXECUTED. Consequence (INFERENCE): a later speech-clock export loses the known real origin and reports unknown time. This is a local ZIP-adapter failure independent of the acknowledged PN Worker dependency.

Correction boundary: preserve compatible existing origin evidence for a proven same-audio identity when an old/unknown incoming row contributes no origin; do not invent an origin or transfer one across conflicting hashes. Exercise this through actual importer persistence, not just initial entity serialization.

### F2 ? P2 ? Personal recording delete can act after its validated scope has changed

Affected requirements: C6 personal-only actions, stale selection/scope safety and preservation of ordinary school material.

Code:
- `app/?/ui/recordings/RecordingActions.kt:36-44`: captures `valid` from the current personal/folder scope, then suspends for alignment cancellation, Flow.first and job cancellation before calling delete. There is no enclosing scope-check/delete transaction.
- `core/?/repo/SessionRepository.kt:226-235`: reads the session and deletes its parts/session without checking whether its current note/folder still matches the earlier personal scope.
- Contrast the move path at `RecordingActions.kt:53-61`, where current scope and move planning are inside `DatabaseTransactions.run` (`core/?/repo/DatabaseTransactions.kt:9`).

Reproduction:
1. Select session `s` while note `n` is in the personal folder and begin `delete(setOf("s"), "personal")`.
2. After `valid` has been calculated, let a concurrent sync/move place note `n` in a school folder. In the fixture this is deterministically injected when `jobs.observeActive()` is called.
3. `delete` continues with the old valid list and calls `SessionDao.delete("s")` after `n.folderId` is already `school`.

`DeleteRace.java` runs the compiled production RecordingActions, FolderRepository, TranscriptionRepository, SessionRepository, AlignmentScheduler and the alignment cancellation path. DAO collaborators are in-memory dynamic proxies; cancellation uses a private empty TEMP queue and real coroutine primitives. Constructors are bypassed for dependencies irrelevant to the exercised methods. No Android context, actual database, audio, network or server is used. `delete-race.log` records:

```
DELETE_AFTER_SCOPE_MOVE currentFolder=school deleted=true
REPRODUCED: source RecordingActions.delete deleted session after its note moved from personal to school
```

The fixture executes the stale scope failure; it does not demonstrate a real-device race frequency or physical audio deletion. The latter is a direct consequence of the inspected repository code when parts exist. Revalidate and perform the destructive database operation atomically in the current scope after asynchronous cancellation; do not rely on the earlier UI/catalog intersection alone.

## Other examined source behavior and limits

C6:
- `RecordingCatalog.kt:17-30` projects session IDs directly, intersects selections and retains body/source-only or empty legacy notes. Tags/metadata-only notes that also have sessions remain accessible through the associated-material action (`RecordingsScreen.kt:174`, `SessionScreen.kt:492-496`).
- `RecordingsScreen.kt:171` opens the session ID directly. `PampaGraph.kt:57,245,287` routes through resumeSession; `FolderScreen.kt:78-90` uses the session library only for personal folders, retaining the ordinary school flow.
- `RecordingsViewModel.kt:189-196` supplies an existing/default personal import folder without a separate folder-creation step.
- `RecordingCatalog.kt:33-37` permits a whole-note move only for all explicitly selected siblings. `RecordingActions.kt:53-108` moves a subset under a Room transaction, copies note content/tags/sources, remaps derivedFromId, creates independent stored source paths, holds old/new paths against eviction and removes only created files if the transaction fails. Session/part identities stay stable; siblings remain on their original note. This lifecycle was inspected, not executed against Room/filesystem eviction.
- Existing computer-only/archive logic remains in use. `StorageRepository.kt:165-180,200-212` checks FilesInUse and busy/protected sessions again before deletion. Separate copied source filenames avoid the direct shared-path eviction hazard. No actual archive request/eviction was made.
- The new screen calls real FluidScreen, fluidGlassGroups/FluidListRow, FluidBarAction, FluidButton, FluidTextField and FluidAlert. IT/EN resource files `recordings_v2.xml`, `export_clock.xml`, `alignment_ui.xml`, `mistral.xml` were source-inspected; equal locale keys/placeholders were checked. No rendered quality, accessibility tree, gesture or haptic claim is made.

C7:
- `RecordingOrigin.resolve` uses independent evidence, not recordedAtMillis/createdAt. Date-only file modification becomes a day, TODAY becomes null; Samsung entry start uses the epoch token, not mediaInfo rewrite time. Unzoned metadata does not acquire device timezone. DST ambiguous/invalid local instants remain epoch-null.
- `SpeechClock.label` uses each part's origin plus its own part-relative offset, explicit offset/IANA zone, source precision and unknown qualifiers. `partOffset` rejects estimated/mismatching/out-of-bounds first-word evidence. Refinement clocks do not inherit RAW phrase timings.
- Clock tests cover hour gaps and midnight, spring/fall zone changes, minute/date-only/unknown origins, independent toggles, part boundaries and exact selected session export. Initial import-persistence source was checked; its mock-based archived test is separate from an actual runtime import.

C8 and export/portability:
- `Entities.kt:165-175,207-208`: five nullable origin fields plus nullable cloudSourceJson, defaults null. `PampaDatabase.kt:76-78`: schema15, AutoMigration14?15. `core/schemas/.../15.json` and archived generated `PampaDatabase_AutoMigration_14_15_Impl.kt` add nullable INTEGER/TEXT columns with DEFAULT NULL. Instrumented migration test `MigrationTest.kt:33-69` is present/compiled, NOT EXECUTED.
- `SyncDto.kt:235-239,253-255` excludes only null new fields from content hashing. Empty cloudSourceJson remains distinguishable; both payload test classes were independently rerun.
- `ExportService.kt:173-175,487-489` gathers exact session rows before reading transcript versions/segments. `RawTranscripts.forSelection` follows the selected RAW or selected refinement's parent. PampaBundleExport fences caller graphs and keeps selected-parent lineage; unselected RAW metadata does not substitute/block the selected version. Body/tags/language/sources/derived pages remain explicit note-level associated material.
- `ExportSessionSelection.kt:14-17`: opaque DOCX/SDOCX/OTHER original bytes are omitted in restricted session exports; AUDIO originals require a selected part's nonblank hash. Source metadata remains. `attachmentAllowed` propagates through planning/layout/native attachment paths; excluded containers do not become fetch jobs. Tests verify sibling text/ID/hash/reported-text exclusion.
- `CloudSources.kt:134-221`: typed portable projection preserves selected text/public originals, validates IDs/version/text/hash/part graph/overlay timing and marks data portable_unbound. `bindSource` rejects it for new alignment. Complete whole-note originals are retained only under retainOriginals; restricted selection uses its authorized original graph. Unknown/malformed nonempty metadata is rejected; null and empty retain their distinct semantics. Opaque unknown private fields are not silently accepted by the typed transfer decoder.
- `PampaBundleCloudSources.kt:12-37,68-92` carries source/destination graphs and simultaneous alias maps, retains conflicting local RAW public histories, and repairs each RAW from its own source after graph changes. The 62-test run covers projection/copies/aliases/merge/repeated imports/selected-parent/null-empty states and ZIP write/read using synthetic bytes.
- Backup snapshots preserve database public fields; new settings carry only public model preferences. Mistral key/cooldown/job-context and alignment queue state live in noBackup (`CloudCredentials.kt:16,46-48`, `AlignmentRepository.kt:35`), outside the backup file selection. Full restore was NOT EXECUTED.
- The parent PAMPA enum/options, full-native history codec, hashed public-source blobs, relay planning and any additional copy/alias paths must consume the hooks in C4_NATIVE_PORTABILITY_INTEGRATION.md and C7_RECORDINGS_EXPORT_INTEGRATION.md. This branch has no PN codec. Worker is unchanged baseline; the PN-owned older-client metadata preservation fix is absent. No combined compatibility, parent codec or deployment result is claimed.

## Executed and archived verification

Executed here (offline):
- Git HEAD/branch/clean-state checks at start and completion, base diff/protected-boundary and whitespace checks.
- XML aggregation, schema delta/generated SQL inspection, lint issue/path/diff-line classification and locale XML key/placeholder checks.
- Two small Java reflection wrappers against archived compiled production classes. These are independent adversarial fixtures; DAO proxies/synthetic metadata are not Android/Room/runtime or real provider evidence. javac compiled only the tiny wrappers under TEMP; no Gradle/Kotlin compilation was started.
- 62 passing JUnit tests in RecordingOriginTest, RecordingClockExportTest, CloudPortableProjectionTest, PampaBundleCloudPortabilityTest, AudioPartOriginPayloadTest, CloudSourcePayloadTest, CloudTranscriptExportTest and RecordingCatalogTest. Used archived JVM production/test classes and cached dependencies; no rebuild of frozen source. `pure-tests-command.json` records exact classpath/classes.

Archived raw evidence inspected:
- `C:\Users\casua\AppData\Local\PampaNotes\cloud-recordings-verification\gradle-integrated.log`: BUILD SUCCESSFUL in 4m49s; 422 actionable tasks, 47 executed and 375 up-to-date. This is the coordinator's run, not this review's.
- `...\build-cloud-recordings\core\test-results\testDebugUnitTest\TEST-*.xml`: 129 suites, 1,075 cases, 1 skipped, 0 failure/error. App: 11 suites, 65 cases, no skips/failures/errors. Combined: 1,139 passed, one skipped.
- Skip is RelayWorkerInteropTest.androidSenderAndReceiverInteroperateWithProductionWorker; it does not prove PN Worker interop.
- `...\app\reports\lint-results-debug.xml`: 127 Warning issues, zero Error/Fatal issues. Four warnings point into changed files: LocalContextResourcesRead in SessionScreen and three UseKtx instances. Comparison with the diff places none on newly added lines; no material new warning identified. Other unused legacy recording resource warnings were inspected as nonblocking cleanup, not rendered UX evidence.
- `...\app\outputs\apk\debug\app-debug.apk` exists, 44,555,136 bytes. Presence is not installation/runtime validation.
- Instrumentation Kotlin compilation tasks are in the successful log; Android instrumentation and Room migration execution did not occur.
- Companion raw log `C:\Users\casua\AppData\Local\Temp\pampa-forced-alignment-tests-final.txt`: Ran 286 tests in49.040s, OK. Archived mocked/fixture checks do not establish real alignment accuracy.

NOT EXECUTED (explicitly user-waived): Gradle/heavy Kotlin rebuild; Android instrumentation/Room runtime migration; emulator/physical/device QA; Compose rendering/GPU/gestures/haptics; model loading/WhisperX real alignment or acoustic accuracy; credentials/live cloud/inference/audio calls; any server, deployment, signing or publication; combined parent PN integration. No extra runtime approval gate is being imposed.

The two material repros warrant correction before passing this reviewed scope. Scores summarize the evidence; the passing archived suite and C9 score do not override mandatory failures. The coordinator owns other criteria, remaining independent reports, final PN merge and aggregate decision.
