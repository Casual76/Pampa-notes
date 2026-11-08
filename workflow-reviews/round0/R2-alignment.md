# Independent CLOUD/RECORDINGS review — R2

Reviewer: R2 / Codex, actual GPT-6.1 Sol, xhigh reasoning.
Phase: integrated delivery; initial review round0.
Frozen HEAD: 03db73dac6d3c5aabcfcb78c6436996d68592d31.
Base: ea4e0a2. Branch: t3/cloud-recordings-parallel.
Workspace: C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel.

Decision: CORRECT for the reviewed scope. Two confirmed mandatory C4 failures block approval. The standalone companion C5 contract passed the source/offline review. The typed C8 assessment is provisional and excluded from aggregation. This is not a verdict on unexamined criteria or combined PN integration.

HEAD and clean state were checked before and after review. No source/status/staging/commit changes, no delegation or parent messages. The premise that source writers were inactive was supplied in the brief; process ownership was not independently audited. Workflow status and other reviewers' verdicts were not consulted. Instructions read: agentic-workflow SKILL and runtime reference, applicable CLAUDE, Fluid Engine SKILL/regole, Material 3 Expressive SKILL. Runtime/device gates were explicitly waived by the user.

|Criterion|Score|Verification status|Reason and evidence|Issues and severity|
|---|---:|---|---|---|
|C1|NV|NV|Mistral setup, credentials, persistence, HTTP fixtures and official-doc parity were outside this R2 deep review. Incidental provider reads do not establish the whole criterion.|No score invented.|
|C2|NV|NV|Automatic provider/model pinning and manual retry settings were not traced end to end.|No score invented.|
|C3|NV|NV|Paid-provider cooldown, spending and chunk recovery were not independently examined. The private alignment ledger is a separate path.|No score invented.|
|C4|5|VERIFIED SOURCE + EXECUTED FIXTURE + ARCHIVED CHECK|Snapshot/source/audio guards and exact RAW presentation are substantial: AlignmentRepository.kt:109-141,153-195,230-241; CloudSources.kt:116-122,244-261; CloudTranscript.kt:104-111; SearchRepository.kt:98-103. The new probes reproduce language application failure and legacy export divergence.|F1 P1: supported named language cannot persist an overlay. F2 P1: nullable legacy cloud metadata bypasses exact export and emits segment text/unqualified times. Mandatory failures.|
|C5|9|VERIFIED SOURCE + EXECUTED FIXTURE + ARCHIVED CHECK; real runtime NOT EXECUTED|AuthGate rejects before body, owner/account fences surround model work, fresh jobs cannot overwrite existing alignment state, original bytes are rehashed, matrices are capped, UTF16/character/score/bounds guards keep missing evidence null. whisperx_server.py:1884-1898,2635-2702,2712-2848,2876-2937. Eleven fresh AST-extracted production-code fixtures passed. Archived companion suite: 286 pass.|No confirmed standalone companion blocker. These checks do not establish actual model availability, timing accuracy or latency. F1 is at Android application/storage.|
|C6|NV|NV|Session-first recordings, moves/archive/privacy/sync and full IT/EN rendered flows were outside R2 scope. Cloud reader use of real Fluid components was inspected only for evidence handling.|Rendered UX NOT EXECUTED; gate waived.|
|C7|NV|NV|Per-part clock/import/origin rules were not independently reviewed end to end.|Runtime/device clock behavior NOT EXECUTED; gate waived.|
|C8|8 provisional|VERIFIED SOURCE + EXECUTED FIXTURE + ARCHIVED CHECK; typed portion only|Typed portable projection, strict unsupported-field rejection, selected-history scoping, explicit alias/copy maps and unbound imported evidence were traced: CloudSources.kt:134-221; PampaBundleCloudSources.kt:12-37,42-92; ExportService.kt:510-522; PampaBundleExport.kt:27-68. Relevant projection/save/timeline fixtures passed. Entity defaults/schema/generated migration and null-hash stripping were inspected.|Excluded from coordinator score aggregation. Combined PN codec/blob/relay integration and older-client Worker preservation are external dependencies, not verified here.|
|C9|9|VERIFIED SOURCE + EXECUTED FIXTURE + ARCHIVED CHECK|Frozen HEAD/branch/clean state and diff whitespace checked; worker/, engine/ and tools/ unchanged from base. Raw integrated XML/log/lint/APK evidence inspected; 67 selected JVM tests and 11 fresh companion fixtures executed without forbidden resources.|Build outputs are archived supplied artifacts, not a newly compiled frozen checkout. Three-review collection/aggregation is coordinator-owned and is not penalized or claimed complete by R2.|

## Confirmed findings, in priority order

### F1 — P1 — Supported provider language labels make every alignment application fail

Reproduction uses existing compiled pure production classes and a small new Java reflection fixture in R2Probe.java. Start with the valid AlignmentApplicationTest snapshot/RAW/response. Change the authoritative RAW/part/original language to English, Italian or IT, and use AlignmentLanguage.code for the request/response language. Keep source text/hash/order/audio/account/selection unchanged. Call AlignmentApplication.plan with identical expected/current snapshots.

Observed:
- en -> en: overlay applied.
- English -> en: IllegalArgumentException at CloudSources.kt:91.
- Italian -> it: same exception.
- IT -> it: same exception.
- eng -> en: overlay applied.

Cause: AlignmentLanguage.code explicitly supports language names/case variants (AlignmentLanguage.kt:7-17; AlignmentLanguageTest.kt:7-14), and AlignmentRepository.snapshot canonicalizes the wire language (AlignmentRepository.kt:121-128). AlignmentApplication then stores part.language verbatim in CloudOverlay (AlignmentApplication.kt:19-21). CloudSources.validate requires both exact part/overlay equality AND a lowercase two/three-letter language (CloudSources.kt:89-92; AlignmentProtocol.kt:64,79). These conditions are incompatible for the supported names/case variants.

Consequence: valid companion results never acquire a persisted overlay for those inputs; AlignmentRepository catches the exception as invalid_alignment and defers non-retryably (AlignmentRepository.kt:203-209). RAW text remains usable and no paid fallback occurs, but online/capable/supported-language alignment fails for an avoidable local schema reason. The source language must remain immutable while overlay/wire language validation accepts its canonical equivalent. Required regression coverage must cross conversion, plan/application, encode/decode and presentation; the existing tests separately cover conversion and en-only application and miss their composition.

Status: VERIFIED SOURCE and EXECUTED FIXTURE. The modeled acoustic response is synthetic; no timing-accuracy claim.

### F2 — P1 — Legacy cloud RAW exports substitute segment text and restore unqualified times

Reproduction in R2Probe.java uses the valid CloudTranscriptExportTest session shape. Set provider/model to legacy Groq/Whisper and cloudSourceJson to null. The authoritative RAW is "  Cloud,\\ttext!\\n  "; the independently stored coarse segment text is "DIFFERENT SEGMENT WORDS". Export through TranscriptPieces.blocks with timestamps=true.

Observed: the exported body is DIFFERENT SEGMENT WORDS, startMs=1000, instead of the exact RAW. This is executed production export code, not a translated implementation. Source also establishes that this branch leaves clockEstimated at its false default (BundleLayout.kt:65-68).

Cause: CloudTranscriptExport.applies requires nonnull cloudSourceJson (CloudTranscriptExport.kt:11); the legacy null therefore falls through to TranscriptPieces' segment/paragraph reconstruction (BundleLayout.kt:59-72), despite provider=groq. Portable projection preserves null (CloudSources.kt:139). Legacy adoption is not guaranteed: discovery returns before adopting metadata when account/owner authentication is absent (AlignmentRepository.kt:80-94). In contrast, the reader accepts cloud providers regardless of metadata and retains RAW with no measured spans (CloudTranscript.kt:104-111); global search also refuses segment fallback (CloudTranscriptMoments.kt:9-18; SearchRepository.kt:101-103).

Consequence: existing cloud content can differ between reader and export, lose RAW whitespace/punctuation or any untimed remainder, and regain timestamps from unrelated/unsupported coarse rows without an estimated marker. Null is the explicit old-row default, so this is a supported legacy state. Exact human-readable cloud export must cover provider-owned legacy rows as well as typed metadata; provenance/timing must stay unknown when it cannot be established. The existing exact-export tests always create nonnull metadata (CloudTranscriptExportTest.kt:14-27), which misses this route.

Status: VERIFIED SOURCE and EXECUTED FIXTURE. No source row was changed and no export was sent anywhere.

## Positive evidence within scope

- Paid transcription is marked done and cleaned before the additive alignment hook; hook failure cannot repeat the completed paid request (TranscriptionQueueWorker.kt:429-450). AlignmentClient only posts existing text to /audio/alignments and has no paid provider fallback (AlignmentClient.kt:84-99).
- Snapshots include selected/refined text, RAW ID/version/hash/language, ordered source hashes, all placement fields, serialized coarse segments, account, endpoint/settings/privacy/spending policy and backing note/folder. Application is transactionally fenced, with local byte rehash before send and again before write (AlignmentRepository.kt:109-141,168-195,230-241; AlignmentAudio.kt:7-16).
- The durable private ledger uses noBackupFilesDir; cancellation tombstones survive identical rediscovery. Logout/restore/session delete hooks stop alignment, and source/account changes are watched reactively (AlignmentRepository.kt:35,177-189,246-247; AlignmentQueue.kt:60-81; AlignmentScheduler.kt:38-45; SyncViewModel.kt:160-167; SessionViewModel.kt:732-743).
- Companion authentication rejects guest/anonymous owners before multipart parsing. Account fences, cancellation, replay conflicts, selected-audio hashes and bounded decode/model windows are explicit. Word/sentence/interpolated outputs do not replace supplied text. Literal nonwhitespace character order, dictionary membership, finite scores >=0.5, monotonic intervals and actual decoded duration gate measured tokens (whisperx_server.py:1884-1898,2640-2643,2712-2848).
- Long untimed audio/text safely produces missing evidence without loading a model or invoking decode. Known coarse windows preserve gaps and remain <=30 seconds/400 UTF16 per matrix, with <=1000 submitted windows. This was exercised with synthetic dependencies, never real audio.
- Reader/search receive nullable evidence directly; the cloud card uses FluidCard/FluidSpokenText with sparse measured-range highlighting, plus explicit IT/EN model-derived/unavailable labels (CloudTranscriptCard.kt:36-83; SessionScreen.kt:1123-1144; values[-en]/alignment_ui.xml). This is source evidence, not rendered UX.
- Portability rejects unknown/nonempty unsupported metadata instead of stripping it, remaps actual source/destination identities, preserves selected authorized historical content, and marks imports portable_unbound so they cannot authorize new alignment. Local split/reorder/combine preserve complete originals separately from current projections (CloudSources.kt:134-261; PampaBundleCloudSources.kt:12-92).
- The six public additions default null in Entities.kt:165-174,207-208. Schema 14->15 adds exactly those fields. The archived generated PampaDatabase_AutoMigration_14_15_Impl.kt issues six nullable ADD COLUMN ... DEFAULT NULL statements. SyncDto.kt:253-255 excludes all-null additions from content hashes. Room migration execution was NOT EXECUTED.

Upstream primary source inspected: https://raw.githubusercontent.com/m-bain/whisperX/v3.8.6/whisperx/alignment.py . Its supplied-transcript align API, interpolate_method and character output match the adapter's call. This inspection is API compatibility evidence only.

## Checks and artifacts

Executed independently:
- git rev-parse HEAD / git branch --show-current / git status --porcelain=v1 before and after.
- git diff --check ea4e0a2..HEAD and protected path diff against base.
- 67 existing selected pure JVM tests via JUnitCore against the supplied archived core production/test class files: AlignmentApplicationTest, AlignmentLanguageTest, AlignmentProtocolTest, AlignmentQueueTest, AlignmentReadingBlocksTest, CloudPortableProjectionTest, CloudSavePlanTest, CloudSourceTimelineTest, CloudTranscriptMomentsTest, CloudTranscriptExportTest. All passed in 0.765 s. No Kotlin/Gradle compilation.
- R2Probe.java, Java source launcher against those archived classes: five language variants and the null-legacy export route. It reproduces the two findings above.
- 11 new offline companion fixtures execute unchanged functions/classes extracted by AST from the frozen whisperx_server.py. These cover literal text/UTF16, invalid hashes/surrogates, 30-second/400-unit/1000-window caps, no-model long-input deferral, coarse gaps, character-score/mapping/bounds nulls, changed audio/account, cancellation and auth/replay rejection before body. All passed in 0.077 s. Dependencies are synthetic; the server module, FastAPI server, WhisperX model, ffmpeg and network were not started.
- Schema 14/15 JSON and the archived generated migration inspected. Initial JSON inspection expected an optional notNull key; corrected read printed all six additions and generated DEFAULT NULL SQL. No migration was run.

Archived raw evidence inspected:
- C:\Users\casua\AppData\Local\PampaNotes\cloud-recordings-verification\gradle-integrated.log: compileDebugAndroidTestKotlin for core at line430 and app at line557; BUILD SUCCESSFUL in 4m49s at line634.
- C:\Users\casua\AppData\Local\PampaNotes\build-cloud-recordings\core\test-results\testDebugUnitTest\TEST-*.xml: 1075 tests, 1 skipped, 0 failures/errors.
- App equivalent: 65 tests, 0 skipped/failures/errors. Combined: 1139 passed, 1 skipped. Skip is RelayWorkerInteropTest, the optional Worker test.
- app/reports/lint-results-debug.xml: 127 Warning entries, no Error/Fatal entries. HTML report exists.
- app/outputs/apk/debug/app-debug.apk exists, 44,555,136 bytes.
- C:\Users\casua\AppData\Local\Temp\pampa-forced-alignment-tests-final.txt: raw result Ran 286 tests in 49.040s, OK. The complete server/HTTP suite was not rerun; it would start prohibited listening servers.

Archive status is distinct from my execution. The supplied build outputs do not independently embed an immutable link to this SHA; source conclusions were checked against the frozen checkout and reproduced on the supplied matching-code paths.

NOT EXECUTED: Gradle/heavy Kotlin compilation, Android instrumentation execution, emulator/device/physical device, rendered UI, GPU, real model loading/download, real audio/acoustic accuracy, provider inference/keys/credentials, server startup, deployment/signing/publication, or work in any other checkout. These gates were waived or prohibited, not replaced by mocks.

## Combined-integration dependency

C4_NATIVE_PORTABILITY_INTEGRATION.md:94 and C7_RECORDINGS_EXPORT_INTEGRATION.md:58 define parent-owned integration hooks. The parent must merge ExportScope.Sessions/speechClock/attachmentAllowed with its PAMPA options/full native codec; externalize and hash public source JSON; preserve actual source/destination alias/copy mappings and authorized history; and honor relay attachment planning. There is no PN codec in this checkout and no duplicate codec is requested.

worker/ remains baseline. PN exclusively owns the older-client public-evidence preservation fix; rewriting a payload from an older client can otherwise drop recordedAt*/cloudSourceJson. Old-client compatibility and combined PN roundtrips are not verified by this review, and no Worker deploy is authorized. This dependency is not a request for R2 to implement a fix.

The coordinator still owns the independent three-review collection and aggregate decision. Missing later reports have not been treated as a circular failure of this frozen scope.

