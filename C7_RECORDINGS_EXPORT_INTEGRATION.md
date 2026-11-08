# C7 recording origins and exact-session export

Implementation against CLOUD/RECORDINGS base `191c11c2823a5e12482ae228a94844fe518e8779`.
No PN codec, migration, entity/DAO/schema, engine, release, deployment or shared-status edits.
No staging or commits. Concurrent cloud/alignment changes belong to their other implementers.

## Interfaces and behavior

- `ImportCandidate.recordingOrigin: RecordingOrigin? = null` is independent of the legacy
  `recordedAtMillis` sorting value. Only this new evidence populates the five frozen nullable
  `AudioPartEntity.recordedAt*` fields. Existing rows are neither inferred nor backfilled.
- `RecordingOrigin.fromMetadata` retains local source time, explicit offset/IANA zone, source
  and day/minute/second/millisecond precision. A UTC instant requires an explicit offset or an
  unambiguous explicit IANA zone. DST overlaps/gaps without an offset cannot establish an instant.
  Unzoned metadata and recognized filename times remain local; filename days remain days.
  Generic last-modified evidence is date-only, never an epoch start. The existing import
  day/group/sorting APIs retain their purpose; source parsing now also recognizes minute metadata.
- Samsung candidates use `SdocxPairing.startFromEntryName` directly for the durable origin.
  `SdocxRecording.createdAtMillis` can contain a `mediaInfo.dat` rewrite fallback; it continues
  to serve existing grouping/sorting but is never promoted to a precise speech start. When the
  embedded recording epoch is absent, the actual origin remains unknown. Epoch-only Samsung
  evidence leaves source local time/zone null; export renders its instant explicitly in UTC with
  the original source zone marked unknown.
- `ExportOptions.speechClock: Boolean = false` is separate from `timestamps`. Codec serialization,
  old defaults and `withTarget` retain the new option. Relative timestamps keep their existing
  session timeline behavior. `ExportPart.origin` carries source evidence into pure writers.
- `SpeechClock` adds a part-relative segment/first-word offset to that part's own origin. It uses
  genuine first-word timing only when present, bounded, matching the first text and not estimated.
  Paragraphs retain part boundaries. No timestamp comes from note/session creation, import time,
  a single session origin or concatenated session offsets. UTC offsets remain explicit, with the
  IANA zone when supplied; minute values have no seconds and are marked approximate. Unknown
  zone/instant/time and date-only values remain qualified. Refined text receives no inherited RAW
  phrase clock; a separate part-origin table explains that limitation.
- `ExportScope.Sessions(sessionIds: List<String>, label: String)` selects exact current IDs.
  Gather resolves their backing notes, then filters `SessionWithParts` before reading versions or
  segments. The native graph is fenced again by selected session/part/transcript IDs.
  `RawTranscripts.forSelection` chooses the selected RAW or selected refinement's RAW parent,
  even when a newer unrelated RAW history exists. Native selection retains that parent lineage.
- `ExportNote.selectedSessionIds` is null for whole-note/folder scope and non-null for an exact
  session selection. Associated body text, tags, language, dates, sources and derived handwritten
  pages remain available. A selection notice explains their note-level ownership and explicitly
  excludes sibling recordings. `ExportSet.personal`/`ExportNote.personal` choose recording/personal
  wording in the plain file, ZIP, loose files, index, front matter and AI instructions. Real legacy
  body text is preserved even when its own wording is older. Machine-native manifest keys stay
  compatible; personal Markdown uses collection/material/session-date keys.
- The frozen schema has no source-to-session association. To prevent an original source from
  carrying sibling audio inside a container, exact-session exports preserve source metadata but
  omit opaque DOCX/SDOCX/OTHER original bytes. An AUDIO source is attachable only when its nonblank
  hash matches a selected part. Other associated material remains attachable. This conservative
  rule is explicit in IT/EN selection text and leaves whole-note/folder exports unchanged.
  `ExportSource.attachmentAllowed` is honored by layout, fetch planning, estimates, counts and
  native attachment paths. It is an export-only property, not a DB field or inferred association.
- Recordings toolbar/context actions construct `Sessions` from the exact selected/current session
  IDs. Fluid controls expose the independent clock toggle; localized resources live only in
  `values/export_clock.xml` and `values-en/export_clock.xml` (prefix `ec_`). Export settings reuse
  the existing `ExportChoices` composable without editing settings-owned files.

## PN coordinator merge points

These are additive changes on the cloud checkout. PN base `8ebfda8` is intentionally not present.
Do not replace PN's newer files wholesale with these versions.

1. `ExportModel.kt`: merge only `Sessions`, `speechClock`, `ExportPart.origin`, the selection/personal
   fields, and `ExportSource.attachmentAllowed`. Keep PN's `PAMPA` format/target, `isPampa`,
   `wantsAudio`, `wantsSources` and native defaults. Add `speechClock` to the fields preserved by
   PN's `withTarget`; do not revert its target/format branches.
2. `ExportService.gather/gatherNote`: preserve the exact session filter before collecting any
   version/part, the five-field origin mapping, associated material and source-attachment policy.
   PN's native gather must retain full histories belonging to selected sessions when its policy
   requests them. Text exports continue to use `RawTranscripts.forSelection`.
3. `PampaBundleExport`: keep PN's native-format/history/complete-attachment branches. Apply the
   selected-session graph fence before serializing/collecting native attachments, and retain the
   five origin fields in the fallback graph. Honor `attachmentAllowed` in PN's source attachment
   collector as well as its human-readable layout; a direct native source scan must not bypass it.
   This checkout has not changed any PN codec/archive importer or generic entity serializer.
4. `ExportPlanning`, `BundleLayout`, `BundleWriter`, attachment counts: merge the source allow flag
   and selected-session filtering into PN's `wantsAudio/wantsSources` paths. Keep PN's estimates,
   complete native attachment behavior and `ExportAttachmentFetch` relay helper. The existing
   cloud-checkout `plan`/`fetchMissing` relay logic was left intact; do not overwrite PN's newer relay
   implementation with it. Excluded container sources remain metadata/omissions, never fetch jobs.
5. `ExportChoices`/`ExportSheet`: keep PN's PAMPA choices, labels and enum branches. Add the clock
   toggle, summary and exact-selection material notice; retain the new `ec_` resources alongside
   PN resources. `RecordingsScreen` hooks already emit the agreed `Sessions` API.
6. `ImportCoordinator.kt`: exactly two origin-population additions, one beside ordinary audio
   `recordedAtMillis`, one beside extracted Samsung `recordedAtMillis`. Preserve PN import changes.
7. `TranscriptEntity.cloudSourceJson` is passed through unchanged, including null, empty and exact
   string states. PN owns its hashed public-source blob externalization and schema15 artifact.
   This unit adds no credentials, relay, account or private alignment state to portable data.

## Executed checks and limits

- Standalone Kotlin 2.2.20 compiler plus serialization plugin; separate output directory outside
  the repo. Compiled current export sources, origin/import sources (including `ImportCoordinator`),
  frozen entities/DAO/projections and the relevant pure transcript/model helpers. Unchanged
  dependencies came from read-only cached dependency/previous library jars. This is not an Android
  application build, KSP/Hilt generation or real Room migration execution.
- JUnit: **104 tests passed**, including **23 new tests** in `RecordingOriginTest`,
  `RecordingOriginImportTest`, `RecordingClockExportTest`, `RecordingSessionGatherTest`; existing
  `RecordingDateTest`, `SdocxPairingTest`, `ExportWritersTest`, `ExportPlanningTest`,
  `PampaBundleSelectionTest` supplied 81 regressions. DAO/files/network-facing collaborators are
  fixtures/mocks; audio bytes are synthetic. Coverage includes per-part hour gaps, midnight,
  explicit offsets and IANA DST changes, unknown/date-only/minute precision, source origin parsing,
  no noon/last-modified promotion, actual import field persistence, independent toggles, genuine
  vs estimated word offsets, adjacent part boundaries, refined-parent history, exact sibling
  exclusion, source-container omission, JSON options/entity/source-state roundtrips and personal
  plain/ZIP/loose output with legacy material retained.
- IT/EN XML parsed; all 19 `ec_` keys are unique in their locales, keys/placeholders match, and
  Kotlin resource references resolve by source inspection. Scoped `git diff --check` passed.
- Logs/harness: `%TEMP%/pampa-recording-clock-c7-tests/compile.log`, `tests.log`, `args.txt`,
  `classpath.txt`, `owned-files.json`; runner `%TEMP%/run-recording-clock-c7.py`.
- **NOT EXECUTED:** Gradle/app compilation, generated Room schema/migration, Compose rendering,
  emulator/device/physical/GPU/runtime QA, real models/providers/cloud, user audio/keys,
  deployment/publication, combined PN integration. Runtime gates are waived by user instruction.
  The coordinator retains the later necessary Gradle check under its lease, two workers,
  no parallel builds. Source-only UI/resource checks are not visual evidence.

## Changed files

- Import: `RecordingDate.kt`, new `RecordingOrigin.kt`, `Importer.kt`, `AudioImporter.kt`,
  `ImportCoordinator.kt` (two additions only).
- Export: `ExportModel.kt`, `ExportService.kt`, new `ExportSessionSelection.kt`, new `SpeechClock.kt`,
  `ExportLabels.kt`, `BundleLayout.kt`, `BundleWriter.kt`, `ExportPlanning.kt`, `IndexWriter.kt`,
  `MarkdownWriter.kt`, `PampaBundleExport.kt`, new `PersonalExportInstructions.kt`, `SkillWriter.kt`.
- UI: `ui/export/ExportChoices.kt`, `ExportLabelsRes.kt`, `ExportSheet.kt`,
  `ui/recordings/RecordingsScreen.kt`; the two new `export_clock.xml` resource files.
- Tests: the four new test files named above.
- Documentation: this file. Total owned files: 29. No shared workflow status edits or commits.
## Correction2 integration notes

Preserve canonical file-identity checks both in session deletion and `AppFiles.sweepOrphans`: stored names such as `shared.wav` and `./shared.wav` must retain the same bytes. Combine these checks with PN's storage sweep tokens/guards during final integration; neither protection replaces the other. Cleanup remains confined to the audio root for session deletion, follows committed row deletion, and preserves independently referenced files.

Origin consistency/ranking is shared through `RecordingOrigin.evidenceQuality()`. Fractional local metadata compares at stored millisecond precision; contradictory tuples remain public evidence but cannot print precise clocks. Dotted numeric timestamp suffixes are not stripped as file extensions. Exact cloud export slices use `TranscriptBlock.separatorBefore` to preserve original whitespace, with legacy paragraphs retaining their existing separator behavior.
