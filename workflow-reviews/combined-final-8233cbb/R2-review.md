Reviewer **R2**, actual model **GPT-6.1 Sol**, reasoning effort **xhigh**. Phase: authorized **COMBINED FINAL INTEGRATION**, PN plus CLOUD/RECORDINGS. Assigned existing IDs: **PN2, PN7**. Frozen branch `main`, SHA `8233cbbd71be08ecc8659fb2a1372ed203a73197`. This is the combined final review, not a new standalone correction round. No delegation, memory registry, workflow status document, historical review or other critic conclusion was consulted.

| Criterion | Score | Verification status | Reason/evidence | Findings/severity |
|---|---:|---|---|---|
| PN2 ? Lossless compression, resource and integrity boundaries; byte dedup without identity merging | **9** | Source plus executed bounded JVM checks on the supplied compiled production/test classes | Writer registers content-addressed blobs but serializes independent entities; staging expands shared blobs to distinct filenames. Native chunks, strict UTF-8, CRC, SHA, entry/count/size limits and cancellation are exercised. Independent fixture shares one 168,000-byte UTF-8 blob across body, histories and two distinct audio entities; all identities, histories, selection and timing rows survive. Malformed SHA, CRC, UTF-8, case aliases, extra entries, traversal, future version and record counts reject. Sources: PampaNoteWriter.kt:59, :82, :143, :155, :163, :178; PampaNoteContainer.kt:51, :90, :162, :194, :214; PampaBundleArchive.kt:48, :145; PampaBundleStaging.kt:55. | No concrete P1/P2/P3 found **within PN2**. Limits are finite contracts, not a measured device memory/disk envelope; legacy native.json remains limited to 64 MiB. |
| PN7 ? Integrity, transactions and composed file lifecycle | **6** | Source/offline verification; **executed file-layer counterexample**. Full concurrent Room outcome is inference; Room runtime remains USER-WAIVED / NOT EXECUTED | Ordinary storage eviction has a real DatabaseTransactions/Room boundary around final DB policy/reference reads through physical deletion, plus canonical aliases, FilesInUse and owner/generation checks. Relevant compiled JVM tests pass. Sync cleanup checks canonical committed references under a transaction. However PN rollback can unlink a file just adopted by a different live sync owner; see R2-F1. Sources: StorageRepository.kt:273?293; DatabaseTransactions.kt:11?12; AppFileWriteProtection.kt:30?70; SyncApplier.kt:194?203; PampaBundleImporter.kt:69?71; PampaBundleStaging.kt:97?98. | **R2-F1 / P2, conditional cross-feature lifecycle failure.** The mandatory ownership/cleanup invariant is not fully met. |

All IDs outside PN2 and PN7 are **NA** for this reviewer. There is no average, invented approval threshold or global approval vote. No P1 or P3 was found in the assigned scope; one P2 is reported below. Scores evaluate authorized source/offline evidence, not waived runtime performance/readiness.

**R2-F1 ? PN rollback deletes bytes adopted by a live sync writer (P2).**

Concrete sites in `core/src/main/kotlin/dev/pampa/pampanotes/core/`:

- `sync/SyncLocalFiles.kt:97?99` selects a verified direct remote filename. At `:136?144`, verification adds the sync owner even if another writer already owns that canonical file; it does not reject unpublished staging paths. `sync/SyncApplier.kt:553?557` passes the remote name into that helper and writes the returned filename into the audio row; `:579?584` does the same for sources.
- `importing/PampaBundleStaging.kt:97?98` calls `files.forEach { it.delete() }` directly. This bypasses the common registry, foreign holders and any current DB-reference check. `importing/PampaBundleImporter.kt:69?71` invokes this cleanup on rollback. The postcommit deletion at `:62?64` also uses direct filename-based deletion outside a DB transaction; that separate DB race was inspected by source, not independently executed.

Minimal input/interleaving: stage a valid PN attachment into fresh basename T. An incoming new sync audio/source row names T and carries its exact length/SHA, with an already existing destination session/note. No existing DB row occupies T. Sync selects T, verifies the bytes and holds it under its own WriteOwner. Before that owner closes, the PN import fails or is cancelled and its rollback calls staging.cleanup(). The cleanup unlinks T despite the sync hold. A sync row committed just before/after this deletion can then refer to a missing file. **The filename-match condition is explicit:** the probe obtains the fresh T and supplies it to the sync helper; this review does not demonstrate that ordinary clients expose T before PN commit or establish a naturally occurring incident.

Executed reproduction uses actual frozen compiled `PampaBundleArchive`, `PampaBundleStaging`, `SyncLocalFiles` and `AppFileWriteProtection`, a 168 KB synthetic fixture and real TEMP file I/O. No production sources were compiled, no Room instance ran and no DB commit is claimed. The log records:

```text
PN staged target exists=true
Sync selected exact PN target=true; sync created files=0
Ordinary guarded sweep deletion=false
After actual PN rollback cleanup: sync-selected file exists=false; sync owner still open=true
REPRODUCED: PN rollback cleanup unlinks the existing file adopted by a still-live sync owner.
```

Thus the file-layer ownership violation is executed evidence; mapping it to the full concurrent sync/import transaction is source inference from the caller lines above. The practical consequence is invalidated local-byte adoption and a dangling local reference, conditionally including the last selected local copy. This is not evidence of a deployed/cloud/device occurrence.

Proportionate fix: make unpublished files held by another writer ineligible for direct sync adoption, atomically with registration; and/or make PN rollback/postcommit cleanup honor canonical foreign ownership and current committed references under the same real DB writer boundary used by sync cleanup. Preserve fail-closed cleanup on query failure. Add a combined regression for adoption overlapping PN cancellation/rollback and postcommit cleanup; existing isolated owner/sweep tests do not cover this cross-writer deletion.

**My executed checks.**

- Initial and final Git audit: exact frozen SHA and `main`; empty staged diff; identical index SHA256; only the required unstaged deletion `companion/tools/__pycache__/holes.cpython-311.pyc`, still physically absent. No project/index/source mutation, no worktree/stash/commit.
- Verification manifest SHA256 matches the supplied digest. Source-manifest SHA256 is `a9edc911b1627a15f12e929c80303b40ad9ede75236bc413b02e4e1cd7ecc362`. Independently hashed **699 permitted source inputs**; the two prohibited workflow status documents were excluded from content/hash inspection. All 699 match and remain unchanged at the end. All **228 artifact hashes** match; hashing an artifact is not functional validation.
- **59 + 109 = 168 existing scoped JUnit cases passed** in two sequential bounded JVM runs. These include container/legacy round trips, local-byte repair, file integrity, staging/cancellation, canonical aliases, adopted/copied sync ownership, stale generations and storage final-writer exclusion. Mocked/mutex-modeled transaction tests are not Room runtime proof.
- **26 independent checks passed** with the separate handwritten synthetic PN fixture and tiny personal Java runner. A second tiny Java runner reproduced R2-F1. Both compile only personal runner sources, not production Kotlin/Java.
- Sequential critic processes only; Java/javac use ActiveProcessorCount=1 and heap <=192 MiB. First personal attempts had a missing test-resource classpath entry (two failures) and a requested test classname that did not exist (initialization failure). Both are explicitly retained as harness logs, corrected, and excluded from product findings. The corrected runs pass.
- Inspected all nine test-only followups in `c858061..HEAD`. No assertions were removed or relaxed. Changes supply integrated constructors, explicit/stateful DAO stubs, reflection-compatible Unsafe access, and actual fixture bytes with matching digests. The origin/identity expectations remain intact.

**Parent artifacts inspected, not executed by me.**

Parsed the current, hash-verified 192 core and 15 app XML files: core 1,498 cases / 1,497 passed / one skipped, app 73 passed, zero failures/errors. Read the BOM-aware current Gradle log tail: BUILD SUCCESSFUL in 2m14s, **5 executed / 460 UP-TO-DATE**; this does not mean every task ran fresh. The manifest reports lint 0 errors / 136 warnings; I did not establish that every warning is baseline. AndroidTest APK hashes establish packaged artifacts only. Generated AudioPartDao_Impl.kt:130?135 uses the same DB writer path (`performSuspending(..., false, true)`), while DatabaseTransactions wraps `db.withTransaction`; those are source linkage checks, not executed Room serialization. Copied Worker/SQL/TypeScript/companion logs are not functional evidence for this scoped vote; no live deployment/readiness is inferred from them. The released-1.2.0 compatibility fixture remains synthetic from release sources, and the friend's unknown incident is not attributed.

**USER-WAIVED / NOT EXECUTED.** Android devices/emulators/adb, UI rendering, Gradle/Kotlin production compilation, installs/downloads, Room/instrumentation/WorkManager runtime, live providers/accounts/cloud/D1/R2/servers, acoustic/audio/model/torch/WhisperX/GPU execution. No new required gates are imposed. New Worker source is not treated as deployed.

**Reproduction artifacts (TEMP only).**

This report lives beside the runners, fixtures, exact classpath/argument files, initial/final Git state, hash checks and logs. With working directory `C:\VibeCoded Projects\Pampa notes`, rerun the already compiled failure probe using `java @"C:\Users\casua\AppData\Local\Temp\PN-R2-8233cbb-independent\ownership.args"`. It exits zero when the counterexample is successfully reproduced, so read `REPRODUCED` in the log; that exit is not a product pass. `ownership-compile.args` contains the optional tiny-runner javac invocation; no production rebuild is needed.

Selected SHA256:

| Artifact | SHA256 |
|---|---|
| cross-role-shared.pn | `9ae43227c4d7a3ae318590321e4e0a5d6aa76152636d243582e73c237078e26a` |
| R2OwnershipProbe.java | `f44b30c8781b43fec66b094b2039186e4106cbc234a63ac14f855b3fbf5d3661` |
| ownership.log | `960f955fd6c395a080995bd16ba6fe10f9388fa5512fe11e6005653adca92289` |
| R2Probe.java | `3789cd2cc6997dbcf33bfa496c869c77f06386a092f2aa6f9354f4a926e26ae9` |
| probe.log | `815631667c909b8f686ed87a76b34af9aa20969d99aa4f7cda12f8971a7662b7` |
| pure-tests-fixed.log | `d5807038622e986a79fd55d0337d8eff026d49c316c0322c4867d30b05847f08` |
| lifecycle-tests-fixed.log | `f6da24afd076813a5b1aba876064627fbdf467c14b03ff949d9684250794fda6` |

`artifact-hashes.json` supplies the full TEMP artifact inventory. End audit: SHA/branch/index/source unchanged; protected deletion remains absent and unstaged.
