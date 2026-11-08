C7 independent review — integrated delivery, correction ROUND3 (third of maximum three correction rounds after initial review)

Reviewer: C7-Sol-R3-5f337eb2, a new independent critic, never an author of this change. Actual model/effort: GPT-6.1 Sol / xhigh. Assigned criterion: C7 only. No subagents, source fixes, commits, parent messages, devices, services or network calls.

Frozen branch: t3/cloud-recordings-parallel
Full SHA: 351e3fd44bf64b07d6437b1d1c8b533a7bf9c79f
Base: ea4e0a2
Only reviewed workspace: C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel

Decision: PASS for C7 in the authorized source/offline-fixture scope, with one low-severity nonblocking parser limitation. No reproducible mandatory C7 failure was found. This is not combined CLOUD/PN approval.

|Criterion|Score|Verification status|Reason and evidence|Issues and severity|
|---|---|---|---|---|
|C7|8/10|VERIFIED SOURCE; EXECUTED FIXTURE using archived compiled classes; ARCHIVED CHECK independently rehashed; runtime gates USER-WAIVED / NOT EXECUTED|Five nullable origin fields come only from per-part source evidence, independently of sorting/import time (AudioImporter.kt:128, ImportCoordinator.kt:224, :533). Complete-tuple native retention is identity/hash-gated (PampaBundleRecordingOrigin.kt:11), contradictions cannot print precise clocks and fractional metadata compares at stored milliseconds (RecordingOrigin.kt:20; SpeechClock.kt:38). Per-part offsets, gaps, midnight, explicit DST/zone and uncertainty passed 27 independent assertions in three device-zone configurations, plus 98 distinct replayed JVM test cases. Exact-session gather/native fencing and personal/school writer regressions passed.|P3/Low: inherited extensionless dotted date-only filename parsing loses known day evidence (RecordingDate.kt:224; RecordingOrigin.kt:93). No fabricated time, paid call or sibling disclosure resulted.|

All source/test paths below are relative to the stated workspace. Full reproduction files and logs are in this report's directory.

**Prioritized finding — C7-P3-1: extensionless dotted date-only filename loses its date (P3 / Low, nonblocking).**

Trigger: an audio file recognized through its MIME/container has display name 2025.09.22, without a filename extension or separate metadata date. RecordingOrigin.fromFileName falls through to RecordingDate.fromFileName at core/src/main/kotlin/dev/pampa/pampanotes/core/importing/RecordingOrigin.kt:93. The latter unconditionally strips the final dot-delimited component at RecordingDate.kt:224. It changes 2025.09.22 to 2025.09, so neither the legacy day parser nor the durable origin retains the known date.

Executed reproduction: DottedDateProbe.java against the archived main classes, with stdout in dotted-date-repro.log:
- 2025.09.22 -> day=null; origin=null.
- 2025.09.22.m4a -> day=2025-09-22; origin=(local=2025-09-22, source=filename, precision=day, epoch=null, zone=null).
- 2025.09.22_10.15 -> day=2025-09-22; origin retains minute-local evidence with null epoch/zone.

Consequence: this naming edge can lose source-derived date evidence and fall back to the existing grouping/date-unknown behavior. It does not invent an exact clock. The explicitly requested extensionless dotted time forms work. A read-only git show of ea4e0a2 confirmed the unconditional substringBeforeLast('.') behavior already exists in the base; this snapshot inherits it through the new origin fallback. Recommended small follow-up: use the real-extension recognition already present at RecordingOrigin.kt:78 for the legacy day parser as well. No source was changed.

**Verified source behavior and executed coverage.**

1. Durable origin population and absence of import-time inference:
   - core/src/main/kotlin/dev/pampa/pampanotes/core/db/Entities.kt:165 defines the five nullable, default-null fields: epoch, local, zone, source and precision.
   - ImportCoordinator.kt:224 resolves ordinary audio origin from the chosen source; :533 uses the Samsung embedded recording token directly. It does not promote mediaInfo rewrite fallback timestamps.
   - AudioImporter.kt:128 keeps createdAt separate; :129-133 writes only candidate.recordingOrigin. Legacy recordedAtMillis/noon sorting is not used for these columns.
   - RecordingOrigin.kt:48, :77, :97 and :105 preserve metadata/filename/Samsung evidence; last-modified is only a day and TODAY supplies no origin.
   - Replayed RecordingOriginImportTest.kt:16 executes candidate-to-DAO persistence with mocked DAOs and synthetic text files, deliberately giving sorting noon to both sourced and unsourced candidates. Only the sourced candidate receives an origin. This is not real audio/container probing or Room evidence.

2. Native updates, contradictions and precision:
   - core/src/main/kotlin/dev/pampa/pampanotes/core/importing/PampaBundleRecordingOrigin.kt:11-18 admits inheritance only for the same final ID and matching nonblank audio SHA; it chooses a complete tuple rather than mixing fields.
   - PampaBundleImporter.kt:141 reconciles identity before retention; :157 selects the rekeyed snapshot during swaps; :173 applies the same origin helper before persistence. PampaBundleMerge.kt:21 uses it for the pure merge.
   - RecordingOrigin.kt:20-33 rejects unusable/contradictory representations for precise clocks and compares source fractions at millisecond storage precision; SpeechClock.kt:38-40 enforces that quality gate.
   - Independent C7Probe.java assertions 1-5 imported an epoch/local/Europe-Rome mismatch against an empty origin and against a known origin. Empty-state provenance remained exact but quality was zero and the label contained time-unknown; the known coherent tuple survived the conflicting update and old null fields.
   - Assertions 6-10 upgraded a second-resolution tuple with .123456789 local evidence. The local string retained its fraction, the epoch advanced 123 ms, and +1 ms printed .124 without downgrading the incoming tuple.
   - Assertions 25-26 verified changed hashes/IDs cannot inherit old local evidence.
   - Replayed PampaBundleRecordingOriginTest.kt:18, :29, :40, :54, :64 covers old serialization defaults, partial/day/minute downgrades, whole-tuple corrections, identity changes and legacy aliases. PampaBundleOriginPersistenceTest.kt:68, :75, :82, :89, :95 executes production normalization/persistence through mocked DAOs, including staged recovery and simultaneous legacy ID swaps. Room transactions/FKs remain unexecuted.

3. Per-part speech clocks and uncertainty:
   - core/src/main/kotlin/dev/pampa/pampanotes/core/export/SpeechClock.kt:17 uses genuine, bounded, matching first-word timing; estimated/missing/nonmatching words fall back to the segment's part offset.
   - SpeechClock.kt:31-62 keeps date-only/minute/unknown-zone values qualified, uses explicit IANA-zone rules for known instants, rejects invalid offsets/overflow, and uses UTC with a source-zone-unknown qualifier for epoch-only evidence. No device zone is filled in.
   - ExportService.kt:496-509 carries each part's own origin. BundleLayout.kt:64-70 attaches each paragraph's part-relative offset. CloudTranscriptExport.kt:45-52 converts measured/coarse session timing back to its matched part; coarse timing is marked estimated. MarkdownWriter.kt:263-265 uses the block's part ID to find its origin.
   - Independent assertions 11-24 and 27 covered extensionless dotted minute/second filenames, midnight, a one-hour inter-part gap, spring/fall Europe/Rome transitions, unresolved overlap/gap instants, date-only, unknown, unzoned and mtime evidence, plus epoch-only Samsung-style evidence.
   - The same 27 independent assertions passed under default JVM zone and explicitly Pacific/Honolulu and Pacific/Auckland. These are 27 distinct assertions replayed in three configurations, not 81 different cases.
   - RecordingClockExportTest.kt:34, :42, :54, :62, :75, :87, :93, :101 passed on replay: per-part hour gaps/midnight, independent toggles/default off, DST, word offsets, uncertainty, refined-text non-attribution and option persistence. Refined/untimed text receives a separate origin table and disclaimer (MarkdownWriter.kt:352-360), without inherited sentence clocks.

4. Exact session exports, wording and controls:
   - ExportService.kt:487 filters requested session rows before histories/segments are gathered. Its :508-509 mapping retains all five fields.
   - RecordingSessionGatherTest.kt:56, :91 and :98 passed on replay: exact selected IDs, selected refinement's RAW parent, no sibling history reads/audio, native graph fencing and associated-material retention. They use mocked collaborators; no live service calls occur.
   - RecordingClockExportTest.kt:109, :136, :150 passed on replay for personal single/ZIP/loose output, legacy material preservation, fallback native origins and old-null serialization.
   - ExportOptions.speechClock defaults false at ExportModel.kt:121 and survives target changes at :139. app/src/main/kotlin/dev/pampa/pampanotes/ui/export/ExportChoices.kt:112-125 exposes two independent real FluidToggleRow controls.
   - ExportLabelsRes.kt:67-74 maps localized clock labels. values/export_clock.xml:3 and values-en/export_clock.xml:3 contain the IT/EN option, uncertainty and selected-session wording; both XML files parse and have matching keys.
   - PersonalExportInstructions.kt:28-32 explicitly distinguishes concatenated mm:ss from each part's real origin plus offset, prohibits inference from session dates/created/updated, and gives the rule in IT and EN. ExportLabels.kt:85 switches wording only for personal material; SkillWriter.kt:77 routes personal instructions separately.
   - RecordingsScreen.kt:123 and :179 build exact-session export scopes; :379 shows the session's date/folder/duration without inventing a precise recording clock. Source inspection establishes the control/label wiring, not rendered UX.
   - Existing ExportWritersTest regressions passed in the second replay, including the ordinary school-oriented writer behavior. No school-wide runtime claim is made.

**Evidence executed now versus archives.**

EXECUTED NOW, no Gradle:
- Rehashed all 537 declared source inputs and all 166 declared artifacts before and after the review: zero mismatches. manifest-rehash-before.json and manifest-rehash-after.json contain individual hashes.
- Replayed six archived compiled JVM test classes: 47 cases, zero failures (junit-replay.log; replay-summary.json).
- Replayed four more archived compiled JVM test classes: 51 cases, zero failures (mock-writer-replay.log; mock-writer-summary.json). DAO/persistence paths are mocks; files are synthetic. Total 98 distinct replayed cases. The OpenJDK class-sharing warning is incidental; JUnit exits were zero.
- Compiled only two tiny Java reproduction files into this unique TEMP directory and invoked archived Kotlin production classes: C7Probe.java (27 assertions, default/Honolulu/Auckland variants) and DottedDateProbe.java. No Kotlin or Android application compilation occurred.
- Parsed/recounted the archived core/app JUnit XML: 1,307 cases = 1,306 passed, 1 skipped, zero failures/errors (archived-xml-totals.json). These are ARCHIVED CHECK totals, not 1,306 tests executed by this reviewer.
- Checked git branch/HEAD, empty porcelain status and empty staging before and after. Final HEAD remains the full frozen SHA; final-integrity.json records it.
- Recorded 47 scoped consumed compiled-class hashes in consumed-class-hashes.json. Source/hash binding and replay provide evidence at the archived binary/offline level; they are not a fresh compilation of every class.

ARCHIVED CHECK INSPECTED:
C:\Users\casua\AppData\Local\PampaNotes\cloud-recordings-verification\correction3-351e3fd4\source-manifest-before.json, verification-manifest.json, process-exit.json and gradle.log. The current final log/process result reports exit 0, BUILD SUCCESSFUL in 3m04s, 422 tasks / 21 executed / 401 up-to-date; the manifest reports core 1,239 passed + 1 skipped and app 67 passed, lint 0 errors / 127 warnings. AndroidTest Kotlin was compiled only. I did not rerun those tasks or treat up-to-date work as freshly executed. Historical failed attempts and older totals were not used as successful-current evidence.

NV / USER-WAIVED, NOT EXECUTED:
Android/Room migration runtime, WorkManager, instrumentation, devices/emulators/physical hardware, rendered UI, acoustic/actual-audio behavior, models/GPU, provider or companion/Worker live services, credentials, deployment/publication. These waived gates are not zero scores and no accuracy/rendering claim is inferred from mocks. Partial external compatibility is bounded: parent PN integration, older clients and Worker retention/rewrite compatibility remain external UNVERIFIED; only the CLOUD/RECORDINGS snapshot above is reviewed.

Independence and read-only record: CLAUDE.md and the requested agentic-workflow/runtime, Fluid Engine/regole and Material 3 guidance were read. No applicable AGENTS.md was found in the workspace/ancestor chain. Other critics' conclusions and workflow round0/round1/round2 verdicts were not read or used. Integration documentation was treated only as an interface claim to inspect in source. No writes occurred in the repository, staging, other worktrees or review/status documents. All reviewer-created files are isolated in this unique TEMP directory.

Final C7 decision: PASS, 8/10; one P3 follow-up, no mandatory blocker. Coordinator aggregation and combined PN approval are outside this review.
