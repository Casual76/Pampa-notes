# Portable bundle core handoff

Implementation 1, isolated worktree `t3-43922baa`, branch `t3/portable-note-bundles`.
No engine, version, app UI, existing import coordinator/parser, database schema, release or remote changes.

## Integration API

Package `dev.pampa.pampanotes.core.importing`:

```kotlin
PampaBundleArchive.isBundle(file: File): Boolean
PampaBundleImporter(db: PampaDatabase, files: AppFiles) // @Singleton, @Inject
suspend fun inspect(file: File): PampaBundlePreview
suspend fun import(file: File, updateExisting: Boolean = true,
  intoFolderId: String? = null,
  onProgress: (Float, String) -> Unit = { _, _ -> }): PampaBundleImportResult
```

Preview/result fields exactly match the agreed contract. Coordinator uses `SourceKind.OTHER`;
no new enum is necessary. `isBundle` returns false for non-ZIP, missing files and unrelated
manifests. Recognizes a flat root or one enclosing directory; unsupported Pampa schemas are
recognized as candidates and rejected during inspect/import.

## Behavior

- Schema 3 adds `nativeFile: "native.json"` beside the unchanged AI-readable layout.
- Native payload version 1 carries original body/tags, selected folders and ancestors including
  kind, all sessions/parts/transcripts/segments/sources, original IDs, millisecond durations,
  word payloads, speakers, voice names and derived handwriting relationships. Part positions
  and durations encode exact session offsets, validated against each segment.
- RAW data survives choosing REFINED export text. Account/jobs/remote archive flags are excluded.
- File declarations explicitly represent omission. Included audio/originals/pages have streamed
  SHA-256 and size validation; binary attachments are never loaded wholesale into memory.
- Entire archive content graph and file integrity are validated before creating durable files.
  ZIP traversal, duplicate/case-colliding entries, broken references, timing inconsistencies,
  unsupported schema/payload versions and missing required entries fail before persistence.
- Imported IDs use deterministic UUID namespaces per entity kind and origin ID. Renames update
  the same note; `updateExisting = false` creates a consistently remapped independent copy.
- One Room transaction uses existing upsert DAOs, preserving sync triggers. No parent REPLACE,
  deletes of local-only notes/sessions/parts/transcripts/sources, or copied owner credentials.
  Local-only segments under an imported RAW are retained and merged into its text.
- Fresh UUID file names protect existing files during rollback. An omitted attachment retains
  its existing local row/file. Successful replacements leave old files for the existing orphan
  sweep. Destination roots preserve hierarchy; destination inside the imported hierarchy fails.
- Schema 1/2 preserve complete Markdown rather than guessing localized body boundaries. Available
  transcript files, images and audio are imported; missing exact metadata/timings are warned.

## Validation

- Kotlin 2.2.20 standalone compiler, serialization compiler plugin, cached dependency jars and
  existing compiled core dependency classes: changed main sources and the two JVM suites compile.
- JUnitCore: `PampaBundleRoundtripTest` + `ExportWritersTest`, 51 tests passed.
  Covers real writer/reader multipart RAW+REFINED, exact words/speakers, handwriting, attachment
  options, stable rename/update/copy maps, DAO persistence retention (including local-only
  segments), legacy schemas, cancellation, traversal/missing entries/schema/hash rejection,
  flat roots, arbitrary ZIP and non-ZIP inputs.
- `git diff --check` passes.
- Added `PampaBundleRoomTest` for real transactional import/update/copy/local-only retention and
  injected rollback of DB plus files. Not executed: no running Android target.
- No Gradle invocation or full build: other Gradle processes were active and parent owns
  integrated build/Hilt/KSP/full `:core:testDebugUnitTest` and emulator validation.

## Limits / parent follow-up

- Run the normal Gradle target and real Room instrumentation after integration. Standalone JVM
  compilation does not verify Hilt/KSP or Android runtime behavior.
- Legacy export cannot recover precise timestamps, original body boundaries or omitted files.
  Audio durations absent from legacy metadata remain unknown (zero), never fabricated.
- Text entries are bounded at 64 MiB each; there is no arbitrary binary/audio size cap.
- The importer merges tags and preserves local-only descendants. It intentionally does not infer
  deletions from absence in a newer export. Files and DB are protected against exceptions and
  coroutine cancellation; abrupt process death can leave unreferenced new files for orphan sweep.
