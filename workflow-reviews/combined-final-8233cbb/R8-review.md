**R8 — independent combined final integration review, PN + CLOUD/RECORDINGS**

Reviewer: R8. Actual model/effort: GPT-6.1 Sol / xhigh. Frozen source: `8233cbbd71be08ecc8659fb2a1372ed203a73197`, branch `main`. Assigned criterion: PN5 only. This is the authorized combined final integration review; it does not open another standalone PN or CLOUD round. No other reviewer conclusions, workflow status documents, or memory registries were consulted. No delegation.

| Criterion | Score | Verification status | Reason/evidence | Findings/severity |
|---|---:|---|---|---|
| PN5 | 6/10 | Source and bounded offline checks executed; scoped mandatory recovery failure reproduced. Runtime/hardware USER-WAIVED, NOT EXECUTED. | 66 selected current compiled JVM tests and 44 production Worker/SQLite tests pass. Thirteen personal relay checks pass. Personal serialized-journal checks preserve private per-recording evidence through a fresh DAO/helper, ownership resolution, deletion and account change. A real production export-planner call nevertheless excludes specific genuine null-reference originals from discovery. | PN5-F1, P2: non-fileless SHARE/CLIPBOARD originals with null references are excluded by the reservation/discovery policy. |

All other criterion IDs: NA, outside my assignment. The score evaluates authorized source/offline evidence, not deployment or runtime readiness. No average, new numerical threshold, or global approval is inferred.

**PN5-F1 — P2: known originals can be declared NO_FILE before relay discovery.**

The reservation predicate at [PampaBundleOmissions.kt:11](<C:/VibeCoded Projects/Pampa notes/core/src/main/kotlin/dev/pampa/pampanotes/core/importing/PampaBundleOmissions.kt:11>) rejects every SHARE and CLIPBOARD source. The positive fileless definition at [PampaBundleModel.kt:40](<C:/VibeCoded Projects/Pampa notes/core/src/main/kotlin/dev/pampa/pampanotes/core/importing/PampaBundleModel.kt:40>) requires text MIME, no archive evidence and no derived parent as well as a null reference. These policies disagree.

The disagreement reaches actual consumers: [AndroidRelay.kt:136](<C:/VibeCoded Projects/Pampa notes/core/src/main/kotlin/dev/pampa/pampanotes/core/relay/AndroidRelay.kt:136>) returns null instead of reserving a destination; [ExportService.kt:236](<C:/VibeCoded Projects/Pampa notes/core/src/main/kotlin/dev/pampa/pampanotes/core/export/ExportService.kt:236>) excludes the source from its null-reference discovery probes. [ExportPlanning.kt:285](<C:/VibeCoded Projects/Pampa notes/core/src/main/kotlin/dev/pampa/pampanotes/core/export/ExportPlanning.kt:285>) then reports the blank path as NO_FILE. A pull can also retain the null path without a local donor: [SyncLocalFiles.kt:110](<C:/VibeCoded Projects/Pampa notes/core/src/main/kotlin/dev/pampa/pampanotes/core/sync/SyncLocalFiles.kt:110>).

Minimal input: an existing source `id=source`, `noteId=note`, `kind=SHARE`, `mime=application/octet-stream`, `storedFileName=null`, `sizeBytes=8`, SHA-256 of UTF-8 `original`, `importedAt=1`, `archivedAt=0`; no local donor. Use normal PAMPA export options and a same-account relay-availability stub returning ready, as in the already compiled null-source fixture. Exact JSON is `PN5-F1-minimal-source.json`.

I executed production `PampaBundleSourceFiles.isFileless`, `PampaBundleOmissions.expectedSourceName`, and `ExportService.plan` on the existing compiled classes. The personal Java runner reuses only the already compiled test fixture's DAO/transport mocks; production logic is unchanged. Observed: `fileless=false`, reserved name `null`, `toFetch=0`, `unobtainable=NO_FILE`. The required-recovery assertion expects one fetchable original and fails with process exit 1 in `PN5-F1-required-recovery-failure.log`.

Controls executed: a null-reference PDF is discovered and planned (`toFetch=1`); actual fileless SHARE text makes no fetch request and has no missing-file result. The same exclusion reproduces for SHARE text with `archivedAt=7`, and for a derived CLIPBOARD source. Existing tests cover these non-fileless classes with a local donor, but do not cover their no-donor discovery path.

Consequence: a supplied legacy/synchronized source can remain unavailable and be omitted from the delivered PN attachment set despite a reachable matching relay transfer. The planner reports a missing original; this is not evidence of silent destruction of the source metadata or of failure for ordinary null-reference PDFs. The broader UI/on-device consequence is inference from the consumer paths, not an executed runtime claim.

Proportionate fix: exclude only positively fileless content from reservation/discovery, retain safe guarded independent destinations for other known originals, and add no-donor ready-relay regressions for these cases plus the genuine fileless negative control. No source fix was made by this reviewer.

Within PN5 I found no additional concrete P1 or P3. This statement does not apply to other IDs.

**Checks I executed.**

- Verified initial HEAD, branch, staged diff, complete porcelain status, protected deletion and index blob. Verified all 701 source hashes, all 228 artifact hashes, and the source-manifest binding. The verification-manifest SHA-256 is `d79655f19823b76f451950732e7dfada1ce0390b087704fe1552437276a32a48`.
- Ran 34 pure and 32 mocked current compiled JVM tests, all passing, including the production applier with mocked DAO/transaction boundary, null-reference export from a stale snapshot, local-byte identity independence, persistent wire-filename projection, recording names/tombstones/structured markers and file owner/generation checks. These are not Room runtime tests. Current production/test directories precede cached dependency jars in the recorded classpath. Key production class hashes are recorded separately.
- Ran the four existing offline Worker suites: 44 passed. They call current production push/pull and public-evidence functions with SQLite in memory. Covered encoded/expanded source envelopes, RAW old-client coherence, immutable/unknown source conservation, overlay enrichment, per-recording names, bounded paging/batches, account separation and opaque hash/base/seq races.
- Ran 13 personal production relay checks with SQLite in memory and a small in-memory R2/stream adapter: owner isolation, idempotent begin/unchanged expiry, immutable chunk identity, wrong chunk and whole-file hashes, exact-byte receipt, expiration during stream consumption, and stale begin replay after cleanup. Authentication and Cloudflare services were not run; owner IDs enter at the production function boundary.
- Executed personal journal checks through the real journal and merge helpers: serialize a private row to TEMP, reload into a fresh DAO proxy, preserve the same-account pending map, apply it only after actual ownership, drain resolved/deleted evidence, and reject/remove foreign-account evidence. This simulates the persistence boundary and does not establish real process death or Room durability.
- Inspected the complete `c858061..HEAD` diff: nine test-only files, 54 additions/16 deletions. No production followups and no removed assertions. Changes repair constructor injection, mock receiver binding, actual fixture byte hashes/staged files, transaction mocking and required DAO stubs. The affected SyncCloudSourceApplier tests were also executed by me.

All personal JVM runs used `-XX:ActiveProcessorCount=1`, heap at most 192 MiB; the Byte Buddy agent was supplied at JVM startup. Node used a single V8 thread, test concurrency 1 and 96/128 MiB heap bounds. Processes ran sequentially. No listener, installation, dependency download, production compilation, Gradle run or external service call was used. Initial personal harness syntax/type-stripping setup errors were corrected in TEMP; those are not product findings. The final PN5-F1 assertion failure is a product-path result, not a classpath/setup failure.

**Parent artifacts only inspected.**

The archived current XML independently sums to 1,498 core cases (1,497 passed, one optional skip) and 73 app cases, with zero failures/errors. The archived Gradle log says BUILD SUCCESSFUL in 2m14s, 5 executed/460 UP-TO-DATE. I do not claim those tasks all executed fresh, or that I reran the full suite. The manifest records lint zero errors/136 warnings; I do not claim every warning is baseline.

Worker44, SQL17, TypeScript exit0 and companion88 logs are parent executions, not fresh executions merely because their logs are copied. I separately reran Worker44 as above. I compared the older/current source manifests: 65 worker/companion inputs and the two SQL fixtures plus DAO/schema inputs are byte-identical. Those comparison sets need not equal the parent's claimed 68-input reuse set. The companion harness and success log were inspected, not rerun. AndroidTest APKs supply compilation/package evidence only. Artifact counts and matching hashes alone do not prove function.

**Waived and not executed.**

Room/instrumentation/WorkManager runtime; devices/emulators/adb and UI rendering; live auth/accounts/providers/cloud/D1/R2/servers; real audio, torch/WhisperX/models/GPU. These are explicitly USER-WAIVED, not new required gates. The new Worker is assessed as offline source and has not been treated as deployed. No friend's unknown 1.2.0 incident is attributed. Released120 remains a synthetic release-source fixture; the declared legacy native.json limit remains 64 MiB. Neither is claimed as new PN5 evidence.

**Repository conservation and artifacts.**

Final check: HEAD/branch/status equal the initial snapshot, all 701 source inputs unchanged, staged diff empty, raw index SHA-256 unchanged (`58e8fc2444144c30bf935ac402f6cdc8f5b532c612444893921c7861532509fd`). The only status entry remains the preexisting unstaged deletion `companion/tools/__pycache__/holes.cpython-311.pyc`; it is absent and the index still holds blob `26dce80ebdac04bff7cda035459c033e74e0b34c`. No source/index/git mutation, commit, stash, worktree or restoration was performed.

All personal artifacts are under `C:/Users/casua/AppData/Local/Temp/pn-r8-8233cbb/`. Run `run-r8.ps1` from an explicitly selected project working directory; it verifies frozen HEAD and uses only cached/current classes. `R8PolicyProbe.java` contains the policy/journal reproduction, and `relay-offline-probe.mjs` contains the isolated relay checks. `artifacts.sha256` lists report/probe/log SHA-256 hashes. `git-initial.json`, `git-final.json`, `hash-verification.json`, `compiled-class-provenance.json`, `reused-source-comparison.json` and `parent-evidence-inspected.json` keep provenance and executed/inspected distinctions inspectable.
