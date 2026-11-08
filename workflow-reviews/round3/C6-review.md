# C6 independent critic — integrated delivery / ROUND3

Reviewer: **C6-R3-Sol-independent**. Actual model/effort: **GPT-6.1 Sol / xhigh**, as exposed by this runtime. New critic, never an author of this change. Worked alone; no subagents or parent messages.

Decision: **CORRECT**. **C6 = 6/10**, verified source/offline scope under the user's waived runtime gates. One reproduced mandatory retention failure blocks C6. This is not combined PN/CLOUD approval.

Frozen version: `351e3fd44bf64b07d6437b1d1c8b533a7bf9c79f`, branch `t3/cloud-recordings-parallel`, base `ea4e0a2`. Phase: integrated delivery; correction review **ROUND3 (third of max3)**. Workspace: `C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel` only.

|Criterion|Score|Verification status|Reason and evidence|Issues and severity|
|---|---|---|---|---|
|C6|6/10|VERIFIED SOURCE; EXECUTED FIXTURE; archived compiled-test REPLAY; ARCHIVED CHECK. Runtime/Room/device/rendered UX USER-WAIVED, NOT EXECUTED.|Real Fluid session rows and direct exact-session resume are wired (`RecordingsScreen.kt:160-181`, `PampaGraph.kt:238-246`); legacy material remains reachable (`RecordingCatalog.kt:28-31`). Post-cancellation delete scope and writes share a transaction (`SessionRepository.kt:236-247`). 94 compiled checks replayed successfully. However `StorageRepository.kt:186-200` reads rows before additional suspension, and `:251-261` deletes using that stale list. Independent fixture reproduced 4 failures across both eviction modes and both file kinds.|**F1 P1 / blocking:** final eviction snapshot can miss an unarchived alias added during its own suspendable guard read. **F2 P3 / nonblocking, inherited:** English engine-owned accessibility labels remain Italian.|

## Prioritized findings

### F1 — P1, blocking: final suspendable snapshot can authorize eviction after a new unarchived reference exists

**Requirement consequence:** C6 requires shared paths and current ownership to preserve unrelated bytes. The final `FilesInUse.withCurrent` lock excludes a new *hold*, but it does not serialize database reference writers. The supposedly refreshed all-reference snapshot can already be stale when the lock is entered.

**Trigger and source evidence:**

- `core/src/main/kotlin/dev/pampa/pampanotes/core/repo/StorageRepository.kt:186-190` reads computer-only scope, then audio rows, then source rows, then `busySessions()` and `protectedSessionIds()`. Each is suspendable; no database transaction spans these reads or the file removal.
- `StorageRepository.kt:251-261` obtains that list, then enters `inUse.withCurrent`. `evictableReferences` at `:209-215` re-resolves physical paths and holds, but groups the existing list without rereading database references. A newly committed alias therefore cannot veto deletion.
- This is compatible with the actual application wiring: `app/src/main/kotlin/dev/pampa/pampanotes/PampaNotesApp.kt:59-61` implements the last guard with `settingsStore.lastListened.first()`, a suspendable DataStore flow read. Actual sync row upserts at `core/.../sync/SyncApplier.kt:466` and `:476-479` do not acquire the `FilesInUse` monitor.

**Executed reproduction:** `C6BoundaryAdversary.java:38-55` uses the current compiled `StorageRepository` and the checked-in `StorageArchiveIdentityTest` DAO/policy/archive mock setup. The independent Java harness adds the interleaving; it does not replace production methods.

1. Store 15 synthetic bytes under `./shared.wav` (or `./shared.pdf`) and one archived row with hash `h`.
2. Let the mock archive confirm `h`. The final snapshot reads the row lists.
3. On the **second** protection read, return `COROUTINE_SUSPENDED`. A separate local thread adds an unarchived row (`archivedAt=0`) addressing `shared.wav` / `shared.pdf`, then resumes the continuation.
4. Production code enters its final hold/physical lock and deletes the file.
5. The fixture contains **two live references**, but reports `exists=false`, `SizeTotal(count=1, bytes=15)`.

All four combinations reproduce: `evictComputerOnly` / `evictArchived`, audio / source. `adversary.log` records all four and ends with Java process exit **1**, `AssertionError: 4 mandatory shared-byte retention checks failed`. It also records the actual loaded classes from `build-cloud-recordings/core/tmp/kotlin-classes/debug` and `debugUnitTest`; no production overlay precedes them.

**Control:** adding the same unarchived reference while HEAD itself is suspended retains the file in both modes. Thus the issue is specifically the additional suspension *inside the final refresh*, beyond the existing HEAD regressions. Existing canonical alias/hold tests passing do not falsify this later window.

**Bound:** this is an EXECUTED offline fixture with real suspension and actual production file deletion, mocked DAO/archive reads, and synthetic bytes. It is not a Room/device reproduction, and it does not establish permanent loss of the remote archive. It does establish unauthorized removal of the local bytes needed by the new unarchived owner. The actual frequency of this interleaving is NV.

**Correction boundary:** coordinate the final authoritative reference/policy check with row writers through the destructive boundary, while preserving the current hold/path lock. Simply moving a refresh after HEAD leaves this window. This report requests no source edits and does not prescribe or implement the external PN copy/sweep token.

### F2 — P3, nonblocking and inherited: English shell still uses Italian engine accessibility words

**Trigger:** use the English app and open a personal folder or session with the Fluid back button.

**VERIFIED SOURCE:** `engine/.../ui/fluid/FluidLabels.kt:36-46` defaults to `Chiudi`, `Azioni`, `Indietro` and `Torna all'inizio`; `:174` installs that Italian default. `engine/.../ui/fluid/FluidScreen.kt:1652-1660` takes the back button's content description directly from `LocalFluidLabels.current.back`. The app's main sources contain no `LocalFluidLabels provides` binding; `app/.../ui/theme/PampaTheme.kt:63-68` installs the theme without labels. The only English label provider found in app source is inside the Android test `CloudRelayPanelTest.kt:43`.

The 17 Recordings v2 resource entries have matching IT/EN keys, plurals and format placeholders, and selection-mark descriptions are resource-backed (`app/.../ui/common/Selection.kt:37-44`). The remaining issue is the inherited engine label binding, not missing Recordings translations. `PampaTheme.kt` and `MainApp.kt` are unchanged from the review base. Actual TalkBack speech/rendering is NOT EXECUTED; the defect is the deterministic source fallback. No engine modification is required to choose English labels at the app root.

## Positive C6 evidence and limits

- **Session-first root and personal folders — VERIFIED SOURCE.** `app/.../ui/recordings/RecordingLibrary.kt:64-93` observes session rows and sorts independent targets. `RecordingCatalog.kt:17-31` keeps body-only, source-only and empty legacy material reachable. `RecordingsScreen.kt:160-181` opens the exact session ID, retains an associated-note action and constructs exact `ExportScope.Sessions`; `:191-221` keeps legacy materials and optional folders. `RecordingsViewModel.kt:188-197` prepares a personal import destination without requiring a user-created folder. `FolderScreen.kt:78-87` routes personal folders to the same library; school folders retain their existing Note-based screen.
- **Actual Fluid/navigation/accessibility source — VERIFIED SOURCE.** `RecordingsScreen.kt:113-119` uses `FluidScreen`, `:160` / `:193` / `:204` use real `fluidGlassGroups` and `FluidListRow`; toolbar/alerts/actions use Fluid controls. `MainApp.kt:394` installs the motion policy; `:444` / `:458` use `FluidNavHost`. `PampaGraph.kt:57` uses exact-session `play=true`, and `:245` / `:287` connect root and folder playback to `resumeSession`. `SessionViewModel.kt:253`, `:522-524` consume the request and resume that session. Source-level row/button roles and localized selection descriptions exist; perceptual quality, focus behavior and TalkBack operation remain NV/waived.
- **Post-cancellation destructive fence — VERIFIED SOURCE plus compiled replay.** `RecordingActions.kt:36-46` waits for cancellation operations and delegates exact IDs/current-folder scope to core. `SessionRepository.kt:238-247` resolves current folder ancestry, session owner note and membership inside the same transaction as session deletion. `:252-270` retains unselected sessions/material and canonical sibling references; physical cleanup follows transaction success (`:247`, `:273-281`). `RecordingActionsDeleteTest` and all 11 `SessionDeletionTest` cases replayed successfully, covering changed note folder, session owner, root kind/ancestry, absent rows, rollback, ordinary school deletion and outside-root names. These fixtures mock Room transactions; real Room locking/FKs were not executed.
- **Delete then orphan cleanup — EXECUTED archived-fixture REPLAY.** `C6IndependentFixture.aliasDeletionThenOrphanSweep` changes the selected row to `shared.wav`, its retained sibling to `./shared.wav`, runs actual compiled `deletePersonalSessions`, then actual `AppFiles.sweepOrphans` over the same bytes. The shared bytes survive both operations. The fixture explicitly rebinds the AppFiles view to the seeded test directory. Relevant current source: `SessionRepository.kt:265-281`; `AppFiles.kt:56-66`.
- **Partial moves/material independence — VERIFIED SOURCE plus archived-fixture REPLAY.** `RecordingActions.kt:55-103` reads scope/siblings and writes moves inside `DatabaseTransactions.run`; a partial selection creates its own backing note, copies body/language/tags, allocates new source IDs/names, remaps derived links, holds source/destination paths, and reparents only selected sessions. `:105-110` removes created files after rollback and releases holds. The independent archived fixture verifies source and derived bytes survive deletion of the originals, the unselected sibling and original material remain exact, and injected failure after the first copy restores the mock DB and removes the copy. No real Room rollback was claimed.
- **Exact scope/export/sync — VERIFIED SOURCE plus replay where named.** `ExportService.kt:173-175`, `:485-492` filters selected session IDs before versions/segments; `ExportSessionSelection.kt:23-24` and its native fence preserve that boundary. The 3 `RecordingSessionGatherTest` cases replayed. `SyncApplier.kt:163-168` checks canonical retained references inside a transaction before quarantine, and `:398` / `:491-502` limits a session tombstone's subtree to that ID. Live multi-device sync, complete native import integration and older-client behavior were not exercised.

## Verification performed now

1. Initial `git status --porcelain=v1` and staged diff were empty; HEAD matched the frozen SHA. No tracked source, status/review document, staging entry or commit was written.
2. Independently rehashed **all 537 declared source inputs and all 166 declared verification artifacts** from `correction3-351e3fd4`: zero mismatches. Results: `manifest-rehash.json`.
3. Replayed **94 already-compiled tests**, Java exit 0 / `OK (94 tests)`: checked-in StorageArchiveIdentity, SessionDeletion, ComputerOnly, FilesInUse/lock, canonical orphan/age, RecordingActionsDelete, RecordingCatalog, PersonalScope, FolderKind and RecordingSessionGather checks, plus **3 archived independent C6 fixture methods**. `replay-current.log`, `replay.args` and `replayed-class-hashes.json` preserve the inputs/results. This is one scoped replay count, not an addition to integrated totals. No Kotlin or Gradle compilation was run.
4. Compiled only the new tiny temporary Java adversary using cached dependencies. Executed **14 scenarios**: **4 retention violations**, **2 HEAD-mutation controls pass**, **8 root-substitution guards pass** (both roots, before/during HEAD, both modes). The root tests substitute the AppFiles directory identity with another isolated temporary directory. They are not actual Android filesystem-link tests. An initial Windows symbolic-link attempt was unavailable with `Il privilegio richiesto non appartiene al client`; no real symlink/Android filesystem result is claimed. `adversary.log`, `C6BoundaryAdversary.java`, `run_adversary.py` and `javac.log` retain the reproduction.
5. Parsed IT/EN Recordings v2 XML: 17 entries each, no key/format differences. Checked source accessibility binding. Result: `resource-source-check.json`.
6. Final workspace check at `2026-11-08T18:21:07.583777+00:00`: same branch/full SHA, empty porcelain, empty staged and working diff; all 537 source hashes still match. Result: `final-workspace-state.json`.

## Coordinator archives inspected, separately from this execution

Inspected current `source-manifest-before.json`, `verification-manifest.json`, `process-exit.json` and the build log under `C:\Users\casua\AppData\Local\PampaNotes\cloud-recordings-verification\correction3-351e3fd4`. The archived process reports exit 0 and `BUILD SUCCESSFUL in 3m 4s`, 422 tasks (21 executed, 401 up-to-date). Its declared XML totals are core 1239 passed + 1 skipped and app 67 passed; lint reports 0 errors / 127 warnings. The relevant XML hashes were independently checked above. These are **ARCHIVED CHECKS**, not a Gradle build executed by this critic. AndroidTest was **COMPILED ONLY**. No stale failed-build XML or earlier reviewer verdict was used to approve C6.

## Independence, waived gates and external boundary

Read the applicable `CLAUDE.md` sections and required agentic-workflow SKILL/runtime, Fluid Engine SKILL/regole and Material 3 Expressive SKILL. No applicable AGENTS file was found. No other critic's report was opened or used as approval evidence. An accidentally broad search did surface two prior-review source-reference excerpts; they were ignored and provided no verdict/evidence for this report. The separate archived fixture source was inspected as executable evidence, not as a reviewer conclusion.

**USER-WAIVED / NOT EXECUTED:** actual Room migrations/transactions, instrumentation, devices/emulators/physical hardware, rendered UX/TalkBack, acoustic evidence, GPU/models/actual audio, real provider/service/credential calls. No new listener, service, companion/Worker startup, deployment/publication, heavy Gradle/Kotlin compilation or other worktree was used. These waived gates are not scored as zero and are not the cause of CORRECT.

PN integration is absent from this checkout. The parent-owned copy/sweep token composition, PN codec/native histories/alias/copy/relay/Worker/older-client compatibility and final combined build/review remain **external UNVERIFIED**. The C4/C7 integration documents were inspected as hooks only. F1 is entirely within this checkout's eviction read/delete sequence; it is not a duplicated demand to implement the external PN boundary.

**Final decision: CORRECT for C6, due to F1.** No source correction or commit was made. No scores are assigned to other criteria.
