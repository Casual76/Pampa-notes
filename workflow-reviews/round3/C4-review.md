# Independent C4 review ? integrated delivery, correction ROUND3

Reviewer: **C4 Independent Critic Sol R3**. Actual model/effort: **GPT-6.1 Sol / xhigh**. Independent reviewer; no authorship, source changes, commits, delegation, or parent messages.

Frozen HEAD: **351e3fd44bf64b07d6437b1d1c8b533a7bf9c79f**. Branch: `t3/cloud-recordings-parallel`. Base: `ea4e0a2`. Workspace: `C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel` only. This is correction ROUND3, third of the stated maximum three correction rounds; this report does not reset that count.

**Decision: CORRECT.** Two reproducible mandatory C4 failures remain. The legacy composition fixes examined here pass their bounded tests; that does not compensate for loss or misrepresentation of original source evidence at two other boundaries.

| Criterion | Score | Verification status | Reason and evidence | Issues and severity |
|---|---:|---|---|---|
| C4 | **6/10** | **VERIFIED SOURCE + EXECUTED FIXTURE**, using archived production/test classes; Android runtime remains NV and USER-WAIVED | 138 archived compiled tests replayed successfully; independent composition fixture passed 522 assertions over 30 plans and 60 expected rejections. However real OpenRouter parser ? runner/cache ? save loses whitespace-only response text and certifies absent text as exact; alignment application rewrites unsupported nonempty source metadata and strips unknown public evidence. See `OpenRouterTranscriptionProvider.kt:89?94`, `TranscriptionRunner.kt:573?582,638?642`, `CloudSavePlan.kt:14?24`, `CloudSources.kt:70?76`, `AlignmentApplication.kt:11?21`, and the standalone repro logs below. | **P2 mandatory blocker:** response text/knowledge loss. **P2 mandatory blocker:** unsupported metadata silently rewritten by overlay application. |

## Prioritized findings

### 1. P2 ? OpenRouter blank/absent full text becomes reconstructed segment text certified as an exact original

**Trigger:** A successful OpenRouter response includes usable segments and either (a) a present string `text` containing only whitespace, or (b) no top-level `text`. The parser explicitly accepts the latter shape at `core/src/main/kotlin/dev/pampa/pampanotes/core/transcription/OpenRouterTranscriptionProvider.kt:85`.

**Root and evidence:** At `OpenRouterTranscriptionProvider.kt:89?94`, `orEmpty()` collapses absence to empty and `text.ifBlank { parsed.text }` replaces a present blank response with segment-derived text. `VerboseJson.kt:31,64` supplies normalized/reassembled segment text. `TranscriptionRunner.kt:638?642` stores this replacement in `sourceText`; `TranscriptionRunner.kt:573?582` then sets `exactTextKnown=true`. `CloudSavePlan.kt:14?24` persists it as `complete_original`, with `source_snapshot` and a valid original input hash. This is not merely a display fallback.

**Executed repro:** `ProviderBoundaryProbe.java` calls the real compiled OpenRouter parser, then the real compiled `TranscriptionRunner`, durable cache, and `CloudSavePlan`. Only the transport provider, Context and unused archive fetcher are test doubles; input consists of synthetic bytes with no media decoding. No HTTP listener or service is created. Exact JSON examples:

```json
{"language":"Italian","text":" \t\r\n","segments":[{"start":0,"end":1,"text":"  derived,  segment! "}]}
```

```json
{"language":"Italian","segments":[{"start":0,"end":1,"text":"  derived,  segment! "}]}
```

Both produce cached and saved text `derived,  segment!`, `exactKnown=true`, `contentScope=complete_original`, and `bindSource(...) != null`. The first loses the exact reported whitespace string; the second invents knowledge of an exact full response that was never supplied. A control with nonblank text `  derived,  segment! \t\r\n` remains exact through the same path. The final probe exits **2**, with **two deliberately failing C4 requirement assertions**, not a harness exception.

Artifacts: `provider-boundary-probe.log`, `provider-probe-command.json`, `provider-probe-exit.json`, `provider-case-0.json`, `provider-case-1.json`, `provider-case-2.json` in this report directory.

**Consequence:** C4 requires exact full/per-part response strings and honest missing evidence. These cases overwrite source text or promote a derived presentation into an attested original that can authorize alignment. The current legacy segment-cache regression does not cover fresh segment-only provider responses: `core/src/test/kotlin/dev/pampa/pampanotes/core/transcription/CloudProvenanceRecoveryTest.kt:156` passes for old caches, while the new repro fails for fresh parser output.

**Required correction:** Keep present response strings exact, including blank strings; distinguish missing full text from a string and carry unknown/derived source evidence through the runner and save plan, or reject an unsupported response shape explicitly. Add these provider-boundary cases without weakening the existing full-text, null-language, or no-rebilling assertions. No production correction was made by this reviewer.

### 2. P2 ? Overlay application silently strips unsupported nonempty metadata

**Trigger:** An otherwise valid v1 cloud source document has an additional public field, e.g. `"futureEvidence":"IMMUTABLE_PUBLIC_CONTENT"`, and a valid alignment overlay is applied.

**Root and evidence:** `AlignmentProtocol.kt:62` configures `ignoreUnknownKeys=true`; `CloudSources.kt:70?76` uses that lenient decoder. `AlignmentApplication.kt:11` accepts the decoded document and `AlignmentApplication.kt:21` re-encodes the known fields, dropping the unknown field. `AlignmentRepository.kt:115,132` also decodes and normalizes the document for its snapshot, so the admission/snapshot path does not impose the strict `transferDocument` boundary. At `AlignmentRepository.kt:237?241` the returned value is the entity passed to the database upsert.

The strict timeline/portable boundary correctly rejects the same unsupported shape (`CloudSources.kt:230?236,263?264`), but that protection is not used for this source-writing operation.

**Executed repro:** `FutureEvidenceProbe.java` starts from the compiled language/application test's valid source and bounded partial overlay response, appends the field above, and invokes the production `AlignmentApplication.plan` with matching expected/current snapshots and account. Output:

```text
input decodes=true alignment writes=true unknown evidence retained=false
FAIL unsupported nonempty source metadata silently rewritten, future public evidence stripped
```

The final probe exits **2** on its deliberate preservation assertion. `future-evidence-before.json` contains the additional field; `future-evidence-after.json` does not. This executes the pure mutation decision; a Room transaction and real companion request were **not executed**. The production Room adapter call chain was inspected in source.

**Consequence:** An unsupported nonempty source document is silently downgraded and public evidence is lost. This contradicts the C4 strict nonempty-metadata requirement even though RAW text and the known original fields remain unchanged in this example.

**Required correction:** Use a strict, preservation-safe metadata boundary for every source-writing alignment path, or retain unknown content losslessly while refusing unsupported alignment semantics. Defer without modifying the stored document if unsupported metadata cannot be safely processed. Add an overlay-application regression as well as the existing strict composition/portable tests. No source fix was made here.

## Evidence that passed, and its scope

**EXECUTED NOW ? archived compiled test replay:** `replay.log` reports **OK (138 tests)** across the 19 classes listed in `replay-command.json`. The classpath uses the integrated external `debug` / `debugUnitTest` outputs; the earlier standalone `current.jar` override was explicitly removed. These production/test classes were **not freshly compiled by this reviewer**. Current classpath hashes were recorded in `compiled-input-hashes.json` (**4,069 class/JAR inputs**); helper fixture classes were compiled separately in this isolated directory.

Relevant replayed checks include:

- Cache A paid / B429 / same-ID A byte replacement, no second A upload, save with old A input SHA, rejection of replacement binding, cleanup, and explicit new manual job: `CloudProvenanceRecoveryTest.kt:56`. Original identities are taken from runner/cache input evidence, not replacement worker rows (`CloudSavePlan.kt:14?17`).
- Exact overlapping cached response strings survive cleanup, save, reorder and restricted portable histories: `CloudProvenanceRecoveryTest.kt:88`. Source strings are stored independently of stitched display segments (`TranscriptStitcher.kt:80?99`).
- Reported null versus request-language hint, fresh/resumed whole/chunk responses and exact reported language labels: `CloudProvenanceRecoveryTest.kt:41,121,150`; named/uppercase/ISO2/ISO3 application, display, exportable overlays and wrong wire-language rejection: `AlignmentLanguageApplicationRegressionTest.kt:28?87`. Canonical codes are used for alignment; exact provider labels remain in source/originals.
- Null/EMPTY legacy provenance, incomplete and different coarse wording, tabs/CRLF/supplementary Unicode, reorder/split/merge/delete, absent segment coverage, original history retention, unknown historical hashes and strict mutation failures: `LegacyCloudTimelineRetentionRegressionTest.kt:54,73,89,112,124,151,169,184,202,213,228`.
- Legacy null and empty export uses RAW instead of different coarse segments; long and multipart plain Markdown adds no source whitespace: `CloudTranscriptExportTest.kt:43,61,82`. Existing exact coarse segment anchors are explicitly approximate (`CloudTranscriptExportTest.kt:98`, `MarkdownWriter.kt:261?266`), rather than exact word timing.
- Selected/refinement parent scope and native whole/restricted histories: `PampaBundleCloudPortabilityTest` and `SelectedRefinementExportRegressionTest`. These exercise this checkout's ZIP/type boundary, not the parent's PN integration.
- Selected RAW/id/version/full-hash/selected-refinement-text, part SHA/order/placement, account/settings and cancellation stale rejection: `AlignmentApplicationTest.kt:34?47`. Reader/search missing evidence and stale identity stay nullable: `CloudTranscriptTest`, `AlignmentReadingBlocksTest` and `AlignmentPresentation.kt:27?55`.

**EXECUTED NOW ? new independent composition matrix:** `IndependentC4.java` passes **522 assertions**, **30 mutation plans**, **60 expected rejections**, over three cloud providers and null versus EMPTY legacy metadata. It uses actual tab/CRLF and Unicode text whose exact words differ from coarse segment words. It checks unchanged null/empty transfer distinction, reordered/moved/split/deleted branches, lossless immutable full originals, unknown part hashes, no new alignment authority, reader/export equality to each resulting current RAW, and nullable global-search/reader/export times. Whole-authorized transfer retains originals; restricted ambiguous multipart transfer rejects instead of leaking siblings. Unsupported/malformed/future/stale nonempty metadata rejects timeline mutation. This is a pure planner/adapter fixture, not Android runtime proof.

**VERIFIED SOURCE ? queue and presentation:** `AlignmentRepository.kt:105?141,153?195,230?242` snapshots the selected RAW/refinement, original ordered parts, full text/version/hash, segment identity, note/folder placement, account and settings/policy; checks currentness around suspended work; rehashes present audio; and updates only an overlay through the guarded application path. `AlignmentClient` submits existing text to forced alignment; this route contains no paid transcription queue or ASR replacement. Offline/capability absence is deferred by the separate durable queue (`AlignmentQueue.kt:65?84`) while text stays stored and usable. These source controls do not cure findings 1?2.

`CloudTranscript.kt:105?111` falls back to the exact stored RAW with no measured spans. `SessionScreen.kt:250?259` rejects prepared text for a stale selection/part graph; `CloudTranscriptCard.kt:44?83` passes exact block text to actual Fluid components, paints only measured ranges, and seeks only when `timeAt` is nonnull. `SearchRepository.kt:98?103` routes cloud moments through `CloudTranscriptMoments`, with no legacy word interpolation fallback. `Daos.kt:846?855` restricts FTS hits to the selected transcript/current fallback; it does not search alternative retained histories as the visible selection. Global FTS SQL correctness was inspected, not executed in Room.

A bounded limitation is intentional: unknown multipart legacy boundaries cannot yield an exact new selected-part text after arbitrary destructive composition. Current presentation may use coarse words, while the immutable complete before-image remains recoverable and unbound; restricted transfers fail where exact scope cannot be proved. No invented historical audio SHA or word-level timing was observed in the passing matrix.

## Coordinator archives inspected, without promoting them to new execution

`source-manifest-before.json`, `verification-manifest.json`, `process-exit.json`, and the final `gradle.log` were inspected. I independently rehashed **537/537 source inputs** and **166/166 declared artifacts**, both before the replay and at the final check; **zero mismatches**. See `rehash.json` and `identity-and-hashes-after.json`.

The archived process reports exit **0**, `BUILD SUCCESSFUL in 3m 4s`, **422 tasks: 21 executed / 401 up-to-date**. Independent raw XML recount (`archived-xml-recount.json`) gives core **1,240 cases / 1,239 passes / 1 skip**, app **67 passes**, combined **1,306 passes / 1 skip / zero failures or errors**. The archived manifest lists debug assemble, core/app JVM tests, AndroidTest Kotlin compilation, and app lint; lint has **0 errors / 127 warnings**. Android instrumentation was **COMPILED ONLY**. I ran no Gradle task, and do not call UP-TO-DATE work fresh execution. Replays and standalone assertions are not added to those integrated totals.

Historical failed attempts, old standalone counts, earlier correction results and other critics' verdicts were not used as approval evidence. The integration Markdown documents were consulted for contracts/hooks, not their author-reported verdicts or counts. No workflow-reviews/round0/round1/round2 conclusions were used.

## NV / USER-WAIVED and external boundaries

**USER-WAIVED, NOT EXECUTED:** Android/device/emulator/physical hardware, Room migration/runtime, WorkManager/instrumentation, rendered UX, GPU, models, actual audio/acoustic evidence, credentials, live cloud/provider/account services, companion/Worker startup or deployment/publication. Model accuracy, real timing accuracy and rendered Fluid behavior are **NV**, not inferred from mocks. No new network listener or service was created. No score penalty is assigned merely for those waived gates or pending other critics.

The parent's PN codec/options/blob/alias/copy/relay, full native history integration, older-client Worker retention of compacted source JSON with matching RAW, and final combined build/review remain **external UNVERIFIED**. This report approves no combined PN snapshot and requests no server/Worker deployment. Other criteria receive no score.

## Integrity and reproduction files

HEAD, index/staging and worktree were clean before review and clean after all checks. Final HEAD and branch still match the frozen inputs. `identity-and-hashes-after.json` records `status=""`, `staged_diff_exit=0`, `working_diff_exit=0`, and zero source/artifact mismatches. No source or review/status document in the checkout was written.

All reviewer outputs are confined to this unique directory. Reproduction inputs/commands are `classpath.txt`, `replay-command.json`, `IndependentC4.java` / `independent-command.json`, `ProviderBoundaryProbe.java` / `provider-probe-command.json`, and `FutureEvidenceProbe.java` / `future-probe-command.json`, with separate logs and process exits. Tiny Java harness compilation was allowed and used; no Kotlin or Gradle compilation ran. Initial harness enum/argument-quoting setup mistakes were corrected before the final runs; final compile logs and probe exits are retained. The two failing probes report requirement failures on the reviewed production binaries, not compilation or setup failures.

**C4 final disposition: CORRECT, 6/10, with the two P2 mandatory blockers above.**
