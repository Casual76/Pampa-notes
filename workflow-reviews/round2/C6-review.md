# C6 independent review — integrated delivery / correction ROUND2

Reviewer: C6 Independent Critic (Codex).
Actual model/effort: GPT-6.1 Sol / xhigh.
Phase: integrated delivery, correction ROUND2 (second of maximum three correction rounds).
Frozen HEAD: 3f88762955de1d761b2eaa126eea35694e14cad7.
Branch: t3/cloud-recordings-parallel. Base: ea4e0a2.
Workspace: C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel.
Assigned scope and only score: C6.
Decision: **CORRECT**. One reproduced mandatory preservation failure remains in the archive eviction path.

|Criterion|Score|Verification status|Reason and evidence|Issues and severity|
|---|---:|---|---|---|
|C6|6/10|VERIFIED SOURCE + EXECUTED FIXTURE + ARCHIVED CHECK; runtime/rendered gates USER-WAIVED, NOT EXECUTED|Session-first root/personal-folder routing, legacy material access, exact session operations, final transactional cancellation fence, canonical session-delete/orphan retention, independent source copying/rollback, IT/EN and accessibility source are supported. Sixty-one archived JVM tests replayed successfully and three new fixture cases passed. A fourth new fixture executes the archived StorageRepository implementation and proves computer-only eviction deletes audio still needed by a protected sibling using an equivalent path.|F1, P2 / mandatory C6 blocker: archive eviction uses literal stored names and candidate-session guards, bypassing canonical sibling and in-use protection.|

**Prioritized finding F1 — P2: computer-only eviction can remove a protected sibling's shared audio. CONFIRMED.**

Trigger: a supported legacy graph contains two parts addressing the same bytes as shared.wav and ./shared.wav. One session is retained at school after a partial move; its sibling remains personal and is eligible for computer-only eviction. The school session is protected and holds audio/shared.wav in FilesInUse. The personal sibling addresses ./shared.wav and its archive-presence result is true.

Reproduction: C6IndependentFixture.kt:135-163, class C6ArchiveAdversary, method evictionMustRetainSharedSiblingAudio. The fixture creates synthetic text bytes (no actual audio), two AudioPartEntity rows, a school sibling outside ComputerOnlyItems, a protected school session, and the actual FilesInUse hold used by the player. It invokes the actual compiled StorageRepository.evictComputerOnly with only DAOs, scope resolution and ArchiveRepository.presence mocked. There is no network or service.

Expected: retain the shared physical file while the noncandidate school sibling or an in-use holder needs it. Actual final output:
    ARCHIVE ALIAS result=1, school/held shared.wav exists=false, retained raw key=[audio/shared.wav]
    AssertionError: Evicting personal ./shared.wav must retain the school sibling's shared.wav
    Tests run: 4, Failures: 1

Evidence:
- core/src/main/kotlin/dev/pampa/pampanotes/core/repo/StorageRepository.kt:166-180 builds candidates only from the captured computer-only scope and uses literal FilesInUse.audio(part.fileName).
- StorageRepository.kt:203-212 checks archive presence, literal item.key membership and only the candidate's sessionId before deleting the physical file. It does not check canonical sibling references or protection of another session sharing the path.
- core/src/main/kotlin/dev/pampa/pampanotes/core/files/FilesInUse.kt:59-61 builds raw audio/source keys; the whole hold/current implementation does not canonicalize them.
- app/src/main/kotlin/dev/pampa/pampanotes/ui/session/SessionViewModel.kt:576-580 holds the player's raw stored filename, exactly as represented in the fixture.
- app/src/main/kotlin/dev/pampa/pampanotes/ui/recordings/RecordingActions.kt:98-100 moves the selected session's noteId, retaining its parts and stored paths. Thus a partial move can separate two pre-existing shared-path sessions into personal/school scopes.
- app/src/main/kotlin/dev/pampa/pampanotes/work/ArchiveWorker.kt:83 invokes computer-only eviction after an archive pass.

Requirement consequence: the personal archive operation widens its physical effect to an unselected, protected school sibling. This violates C6 sibling/file preservation across move/archive, even though exact selected-session DELETE and subsequent orphan cleanup are now canonical-safe. The mock archive says a remote copy exists, so the executed evidence proves local byte loss and guard bypass, not loss of all copies or successful real-world recovery.

Attribution: StorageRepository and FilesInUse have no diff from ea4e0a2. This is an existing integrated contract gap exposed by the required legacy shared-path case, not claimed as a newly introduced line-level regression.

Suggested correction boundary (no changes made): use a bounded canonical physical identity when deciding eviction, account for all retained/held/guarded references to that identity, and preserve any sibling that is not authorized for eviction. Add this case alongside the existing deletion and orphan-retention regressions.

**Supported C6 behavior and its limits.**

- VERIFIED SOURCE: RecordingsScreen.kt:113-184 uses FluidScreen, fluidGlassGroups, FluidListRow and localized Fluid actions; row click and export/delete/move identities are session IDs. PampaGraph.kt:57 and :238-246 use resumeSession/play=true with a fresh detail. FolderScreen.kt:79-88 delegates personal folders to the same RecordingsRoute and leaves the ordinary school FolderScreen branch available at :89 onward. Session playback behavior is source-verified, not device-tested.
- VERIFIED SOURCE + EXECUTED FIXTURE: RecordingLibrary.kt:48-76 observes session data under current personal/current-folder scope. RecordingCatalog.kt:16-26 retains body-only, source-only and empty legacy materials; session-associated material is exposed by RecordingsScreen.kt:174 and note rows at :191-199. RecordingCatalogTest replay: 10 tests pass, including multiple sessions, transcript-only sessions, legacy body/source/metadata, school exclusion and no sibling selection widening.
- VERIFIED SOURCE + EXECUTED FIXTURE: RecordingActions.kt:36-46 waits through alignment cancellation, active-job observation and transcription cancellation before using the final core fence. SessionRepository.kt:236-247 reads current folders, current session->note ownership and current note folder in the same db.withTransaction as deletion. SessionDeletionTest replay: 11 tests pass, including note moved to school, another personal folder, root kind/reparent change, session reparent, missing rows, rollback, school behavior, alias retention and ../keep-private.wav outside the audio root. RecordingActionsDeleteTest replays all three suspension interleavings in its one JUnit method. These are transactional DAO fixtures, not evidence of Room locking or real concurrent runtime behavior.
- EXECUTED FIXTURE: aliasDeletionThenOrphanSweep changes the selected part to shared.wav and its sibling to ./shared.wav, invokes actual compiled deletePersonalSessions, then actual AppFiles.sweepOrphans on the same directory. The shared bytes survive both operations. Source: SessionRepository.kt:265-281 and AppFiles.kt:56-66. The fixture reuses the archived SessionDeletionTest DAO seed and explicitly rebinds AppFiles.audio to that same seed directory.
- EXECUTED FIXTURE: independentPartialMove invokes actual compiled RecordingActions.move. It verifies only the chosen session moves, sibling and original note/body/language/tags stay intact, copied note body/tags/language survive, private bundle merge metadata resets, both original and derived source IDs/pathnames are new, derivedFromId points to the copied parent, and copied bytes remain after deleting both original source files. partialMoveRollback injects failure before copying the second source; its transaction fixture restores rows and actual first copied file is removed, with original bytes retained and FilesInUse released. Source: RecordingActions.kt:55-110. Room filesystem atomicity itself remains waived/NV.
- VERIFIED SOURCE: prepareImport at RecordingsViewModel.kt:189-199 selects or creates a personal backing folder without requiring a folder-creation interaction. ImportViewModel.kt:589-606 retains archive/sync scheduling and prevents automatic cloud transcription of personal notes, based on current note membership. No SAF import, live archive, real privacy upload or live sync was executed.
- VERIFIED SOURCE + ARCHIVED CHECK + EXECUTED REPLAY: PampaDatabase.kt:121-136 tracks exact row insert/update/delete and cascades through the outbox. Payload replay includes SessionPayloadTest (8), FolderPayloadTest (5) and SourceIdentityPayloadTest (3); source and derived identities retain their payload contracts. ComputerOnlyTest (19) also passes and does not cover F1. Real Room triggers, live sync convergence and older/external clients remain unverified.
- VERIFIED SOURCE: Italian/default and English recordings_v2.xml contain the same 17 resource keys, including plurals and destructive/move disclosure. Actions use localized content descriptions; Selection.kt:34-39 uses FluidSelectionMark and localized selected/unselected descriptions. Visual layout, focus order, screen-reader experience and animation quality are not inferred from this source inspection.

**Executed evidence.**

1. Git before: exact frozen HEAD/branch, empty git status --porcelain=v1. Git after: same full HEAD, empty porcelain, git diff --cached --quiet exit 0. No source fixes, commits, source/report writes in the checkout, delegation or other worktrees.
2. Read current CLAUDE.md and applicable instruction discovery (no AGENTS.md found in the checkout or searched ancestors); read requested agentic-workflow SKILL/runtime and Fluid/material3 instructions. No workflow status verdict or round0/round1 reviewer report was opened.
3. Replayed archived compiled JVM classes from the supplied external buildRoot: 26 tests in junit-replay.log plus 35 in archive-sync-replay.log = 61 passing tests. They are replayed compiled classes, not a new production build.
4. Compiled one small isolated Kotlin fixture file only, with cached dependencies; no Gradle or production Kotlin build. Final fixture-replay-final.log: three positive tests pass and one adversarial archive-retention test fails. Do not add these runs to the integrated XML totals.
5. Verified every one of the 523 source-manifest-before.json input hashes against the frozen checkout: zero differences. Nine inspected C6-relevant raw XML report hashes match verification-manifest.json.
6. Parsed the actual integrated XML: 1245 cases = 1244 pass / 1 skip / 0 failures/errors. Inspected UTF-16 gradle.log BUILD SUCCESSFUL in 8m 42s and the manifest's successful exit/tasks. This is ARCHIVED CHECK evidence, not an independently rerun build.
7. Inspected lint-diff.json: only added warning is the unused legacy error_credit_required resource. It does not establish a C6 accessibility or runtime failure.

Fixture-development provenance: an initial tiny-fixture compile rejected a mixed cached Kotlin 2.4 dependency on the 2.2 compiler; the classpath was filtered and final compilation passed. An initial archive fixture had fileName/originalName positionally reversed and therefore no eviction candidate; that run is explicitly discarded as fixture-replay-bad-precondition.log. The final fixture corrects the names and asserts candidate existence before invoking eviction. The confirmed red result above is from that corrected fixture.

**NV / NA / external boundaries.**

Runtime/device/real-model/Room-migration/instrumentation/rendered UX/acoustic evidence gates are explicitly USER-WAIVED, NOT EXECUTED; no zero score is assigned for them. No hardware, emulator, Gradle, heavy production compilation, model, actual audio, credentials, live provider/companion/Worker, deployment, publication, listener or service was used. Mock archive presence is not real storage proof. No PN/ZIP/Worker implementation or combined approval is claimed; parent integration and backward-client compatibility remain external UNVERIFIED. Other reviewer collection/aggregate is NA for this assigned C6 review.

**Final decision: CORRECT, C6 6/10.** F1 is a reproduced mandatory failure regardless of the passing archived suites. Other reviewed C6 paths have positive source/offline evidence under the stated user waivers.

Artifacts: C6IndependentFixture.kt; fixture-run-final.args; fixture-replay-final.log; junit-replay.log; archive-sync-replay.log; evidence.json in this report's unique TEMP directory.

