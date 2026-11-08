# Released Pampa Notes 1.2.0 writer compatibility fixture

`released120-synthetic.zip` was produced on 2026-11-08 by compiling and calling the
**actual, unmodified** `BundleWriter` and its portable production source dependencies from
local Git tag `stable-pampa-notes-v1.2.0`. Its resolved commit is
`e8c9ba4f2d0b7cc4a5f138e622be62da61979845`; that commit's `app/build.gradle` declares
`versionName '1.2.0'`, `versionCode 18`.

This is release-source-generated evidence with synthetic inputs. It is not an archive supplied
by a user, an export invoked through the released Android APK, or evidence of the cause of the
reported friend's failure. It does not execute Android, Room, SAF, sharing, media decoding,
cloud services, or inference.

## Files and integrity

| Item | Bytes | SHA-256 |
|---|---:|---|
| Untouched writer output ZIP | 28,681 | `25114f4834dc3b59bfc90b2d8c50a25d3361f9c492dcf214b3d2bb8078701481` |
| Synthetic AAC/M4A used by each of three distinct parts | 5,370 | `1ba6b5929c65382d145c0887726a3deba6d81f528dcf590aed88f29642556452` |
| UTF-8 Markdown used by each of two distinct sources | 60 | `4c4c61dd1c19616665f3c818615834e21adac7efa946ccc93476013edf5fa7e4` |

The tone is copied from `../pn/tone.m4a`, the existing nonpersonal 440 Hz, one-second fixture.
No speech is present. Its generation command is documented in `PN_FORMAT.md`.
The Markdown input is exactly the following, including its final LF:

```text
# Fonte sintetica
Energia: E = mc².
Nessun dato personale.
```

`provenance.json` records every compiled released path, its Git blob ID and SHA-256, the release
metadata blobs, cached dependency coordinates/hashes, Java version, exact commands and the
uncompressed length/hash of every nondirectory ZIP entry. `Released120Fixture.kt.txt` is only the
synthetic caller/input data. It contains no replacement production classes. Its source hash is
recorded with UTF-8/LF line endings. The `.kt.txt` suffix lets Android Gradle package it as test
data; generation copies the exact text into temporary `Released120Fixture.kt` before compilation.
`generate.py` verifies the tag target and tone hash before generating anything, and extracts
sources directly with `git show COMMIT:path` into a fresh TEMP
directory. There is no checkout, worktree, source rewriting, codec adapter, ZIP postprocessing,
or invocation of the current writer in this generation step.

## Released source set

All paths below have prefix `core/src/main/kotlin/dev/pampa/pampanotes/core/`.
These are the complete 16 released files compiled for generation, alongside the synthetic caller:

| Relative path | Git blob ID |
|---|---|
| `db/Entities.kt` | `34d6214a31d6978b6dea11e7d844b329b317e5d3` |
| `model/Ids.kt` | `5bc5f431619cc5e10e2f4e0a4658b950298441e4` |
| `importing/PampaBundleModel.kt` | `0f53e4aa450078baa0395130d75650b466313568` |
| `export/BundleLayout.kt` | `a5200404e366c9804d49d1a0c924db5ccbe8566c` |
| `export/BundleWriter.kt` | `65323147d5d05f4afb62915951d00e9d2df3685e` |
| `export/ChapterLines.kt` | `5ab27f41c75afa3f53fabde277b0e6e56ce9b2a6` |
| `export/ExportLabels.kt` | `51993afe1f7c0e13bce34da86867fbc84910e9d7` |
| `export/ExportManifest.kt` | `85464441395deff4e7fb9b8c38e65da24cbc47fb` |
| `export/ExportModel.kt` | `76c1a302a3d7fd9f51b84d134812763568700482` |
| `export/IndexWriter.kt` | `609783a47d47d195f64f1d66fc10a34883856949` |
| `export/MarkdownWriter.kt` | `f52b1f30bd3f9df923200bed096a61e6f4189791` |
| `export/PampaBundleExport.kt` | `8eb1d0f794fb4d22d4040ed0162a79951dddfbf1` |
| `export/SkillWriter.kt` | `81d2c1a5ab77baf470a5207cbce26a71637e1d28` |
| `transcription/TranscriptParagraphs.kt` | `971ace5766cec149d768f30d4c9a55d6f8a81a08` |
| `transcription/Chapters.kt` | `c357b7d8ebf6b55981612289e2cb0733b5c1d39f` |
| `transcription/VoiceNames.kt` | `be5d099d7eecd1bedc9a554dd765b3e0d81f736e` |

Generation uses JDK 17.0.18, Kotlin compiler/serialization compiler plugin 2.2.20,
serialization core/JSON 1.6.3 and Room common JVM 2.8.4, matching the release catalog.
Room supplies its real annotation definitions; no Room processor or database is needed by these
portable writer sources. All libraries were already cached; no dependency was added or downloaded.
The compiler runs with `-Xmx384m -XX:ActiveProcessorCount=2`; generation uses `-Xmx192m` and two
active processors. The Java processes exit after compilation/generation.

From the integration checkout, the executed recipe is:

```powershell
python core/src/test/resources/released120/generate.py --repo .
```

The script prints its fresh TEMP output directory. The committed ZIP/provenance were copied from
that directory after successful compilation/generation and a full ZIP CRC check. Exact expanded
compiler/runtime arguments from that invocation are in `provenance.json`.

The manifest uses schema 3 and root `pampa-notes-released120/`. The old writer sets current wall
clock timestamps on its text/directory ZIP entries. Consequently a regenerated ZIP need not have
the pinned whole-file hash; the content hashes of all nondirectory entries can be compared instead.
The fixture was not repacked to force reproducible ZIP headers. Audio/source modification times
and all input entity/export dates are fixed independently of those ZIP header timestamps.

## Synthetic content and selection

`Released120Fixture.kt.txt` specifies all identities and dates explicitly. The base time is
`2026-01-02T03:04:05Z` (`1767323045000` ms); the manifest's export time is ten seconds later.
The hierarchy is `Laboratorio` (`r120-root`) / `Fisica` (`r120-child`), with style/order and separate
created/updated dates. `r120-note` contains accented/Unicode Markdown, tags and pin/language state;
`r120-plain` is a second text-only note in the root.

The lesson has two sessions. The first selects `r120-refined`, an untimed refinement with preset,
prompt hash and parent `r120-ref-raw`. The second selects `r120-raw`, with two ordered audio parts.
All three parts are distinct entities containing the same tone bytes and nominal 1,000 ms durations.
Two source entities likewise carry identical Markdown bytes. The released writer maps their
attachments to the same source path when their local source filename is shared; both source IDs
remain present. Staging must give each entity its own local file lifetime.

Three manually authored RAW segments retain IDs 101/102/103, part intervals 100..900 ms,
session intervals 100..900, 100..900 and 1100..1900 ms, exact word timing strings, probability fields,
speaker labels and synthetic voice names. Word intervals within each segment are 0..300 and
400..800 ms. `wordsEstimated=false` is intentional synthetic metadata, not a claim of measured
phonetic alignment to a tone. The refinement has no segment/word timing of its own.

The input also supplies an unselected alternative RAW and an unselected refinement. The actual
1.2.0 `PampaBundleExport` removes those branches and their segments. This archive therefore tests
the selected RAW/refinement and the refinement's RAW parent, **not preservation of all historical
versions by the released format**. New nullable clock/cloud provenance fields did not exist in
these released sources; their update preservation is covered by separate parent tests.

## Executed compatibility checks

`ReleasedPampa120CompatibilityTest` contains seven tests that consume the fixed ZIP resource:

1. Whole ZIP/provenance/entry integrity and released schema/manifest.
2. Full current reader and metadata preflight agreement, exact text/hierarchy/dates/source metadata.
3. Selected branches, RAW parent, segment IDs, part/session/word times, speaker names and flags.
4. Actual staging under update and copy entity keys, exact original bytes and independent file deletion.
5. Actual importer remapping: stable origin IDs, independent copy IDs and consistent graph/voice references.
6. Repeated update merge retaining a locally appended body and a local refinement, without duplicate entities.
7. Conversion through the current PN writer/reader with identical portable metadata and attachment bytes;
   five attachment entities share exactly two media/source blob paths without merging their identities.

Both standalone modes of `verify.py` **passed all seven tests** on 2026-11-08, with no compiler
errors/warnings. They are two executions of the same seven cases, not fourteen distinct tests:

- `parent-reader`: actual coordinator-compiled reader/codec classes from
  `%LOCALAPPDATA%/PampaNotes/build-pn-workflow/core/tmp/kotlin-classes/debug`, with current merge,
  metadata merge, staging and file-copy production sources freshly compiled. JUnit: 0.361 seconds.
- `current-codec-source`: seventeen current production files plus the test freshly compiled,
  including entities/models, archive reader, PN container/writer, selection/timing helpers and merge/staging.
  Other supporting classes, including the actual importer `remap` implementation, come from the
  same parent production output. JUnit: 0.340 seconds.

`verification.json` records source fingerprints, parent reader/importer/entity class fingerprints,
expanded commands and actual compile/JUnit output. It describes that tested snapshot; later parent
changes still require integrated validation. No production stubs or mocked modules were used.
The SDK `android.jar` and the real cached Room runtime JAR resolve the existing importer's types;
no Android method, database transaction, media probe, provider or network call is executed.

Reproduction command used in the integration checkout:

```powershell
python core/src/test/resources/released120/verify.py --repo . --parent-classes "$env:LOCALAPPDATA\PampaNotes\build-pn-workflow\core\tmp\kotlin-classes\debug" --android-jar C:/Android/Sdk/platforms/android-36/android.jar
```

Verification compilers use a 384 MiB heap/two active processors; the short JUnit processes use
256 MiB/two active processors. No Gradle/build task, device/emulator, long-lived server, model or
cloud/deployed endpoint was invoked. Final integrated Gradle compilation/test discovery remains
the parent's responsibility after all source writers finish. Android end-to-end behavior and the
unprovided incident archive remain unverified.
