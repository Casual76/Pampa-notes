# C4/C7/C8 native source portability integration

Implementation base: `c6eec80cb749511cff44e78cb7202505dcc059a1`, public entity contract
`5c91d57`, adopted schema 15 `3e7a997`. This unit implements the existing ZIP adapters;
it does not implement or import the independent PN codec.

## Changed files

All paths below are relative to the checkout. Only these ten files belong to this unit:

- `core/src/main/kotlin/dev/pampa/pampanotes/core/alignment/CloudSources.kt`
- `core/src/main/kotlin/dev/pampa/pampanotes/core/export/ExportSessionSelection.kt`
- `core/src/main/kotlin/dev/pampa/pampanotes/core/export/ExportService.kt`
- `core/src/main/kotlin/dev/pampa/pampanotes/core/export/PampaBundleExport.kt`
- `core/src/main/kotlin/dev/pampa/pampanotes/core/importing/PampaBundleCloudSources.kt` (new)
- `core/src/main/kotlin/dev/pampa/pampanotes/core/importing/PampaBundleImporter.kt`
- `core/src/test/kotlin/dev/pampa/pampanotes/core/alignment/CloudPortableProjectionTest.kt`
- `core/src/test/kotlin/dev/pampa/pampanotes/core/export/PampaBundleCloudPortabilityTest.kt` (new)
- `core/src/test/kotlin/dev/pampa/pampanotes/core/importing/PampaBundleRekeyPersistenceTest.kt`
- `C4_NATIVE_PORTABILITY_INTEGRATION.md` (new)

No entity/DAO/database/schema, engine, worker, repository, scheduler, settings, UI,
startup hooks, versions or shared workflow status were edited by this unit. Other
concurrent modifications remain owned by their respective implementer/coordinator.
No git staging or commits were performed.

## Transfer and scope contract

`CloudSources.portableProjection` retains its original first five parameters and
adds defaulted `retainOriginals=false` and
`authorizedOriginalParts=selectedParts`. Call it with the SOURCE RAW and SOURCE
audio rows, before changing identities. Consume BOTH returned `text` and
`cloudSourceJson`, and update word count only if text changed.

- `selectedParts` is the ordered current audio graph for this RAW's destination
  session, represented with SOURCE IDs/hashes/session identity. Exact current text
  is preserved when the ordered source projection is unchanged. A subset/reorder
  uses exact per-part text; unknown multipart boundaries fail instead of guessing.
- For `ExportScope.Sessions`, `authorizedOriginalParts` is the union of audio rows
  across the selected sessions of this note. Complete originals/reported text are
  retained when their complete part identities fit that authorized graph. Other
  originals are projected to known selected parts, with `portable_projection`, a
  new text hash, selected provider/model/language, and no mixed aggregate report.
  Originals wholly outside the authorized graph are omitted. Unprovable boundaries
  or conflicting historical audio identities fail clearly.
- Whole-note/folder/native transfers use `retainOriginals=true`. Complete public
  original text, provenance, runner reports and deleted/pre-split history remain
  available. Do not reuse this policy for a restricted session export.
- Original RAW/version identifiers retain their historical meaning. Explicit part
  aliases also update original-part references, so a later restricted export can
  still scope a copied document. Current RAW/part/overlay identities use the actual
  destination graph and `CloudSources.version(destinationRaw)`.
- Valid measured AND missing overlay entries survive transfer, including exact
  UTF16 offsets, text/hash/language and part-relative times. Current source hashes
  must match actual destination audio rows. Hashing included attachment bytes is
  followed by validation, so replacement bytes cannot certify old timing.
- All transferred typed documents have `alignmentBinding="portable_unbound"`.
  `validatePortable(raw, parts)` validates stored evidence for display; it never
  authorizes new alignment. `bindSource` and alignment application still reject
  unbound documents, including after recomposition. Missing times stay null.
- Null and empty metadata remain distinct and unchanged. Nonempty unsupported,
  opaque, malformed, stale or unknown-field metadata causes an explicit failure;
  it is never replaced, downgraded to null, or silently stripped. Private
  account/job/key/relay/queue extensions are not serialized through this boundary.
- Nullable legacy discovery now yields an unbound projection with unknown audio
  hashes. This deliberately also defers new alignment for app-local legacy RAWs
  without saved source identity. Current/replacement rows cannot prove historical
  audio ownership. Exact legacy text remains recoverable; empty/opaque metadata
  is not adopted. Fresh trusted source snapshots retain the existing save path.

## Existing ZIP call points

`ExportService.gatherNote` scopes the selected RAW/refinement parent before it
supplies display text. `PampaBundleExport.build` keeps that selection policy,
filters selected versions before projecting native metadata, and verifies that
display/native RAW text agree before writing bytes. Unselected RAW metadata cannot
replace or block the chosen version. Note-level associated material and existing
opaque-source attachment restrictions remain in place.

`PampaBundleImporter.remap` calls
`PampaBundleCloudSources.remap(sourceNote, destinationNote, partIds, rawIds)` for
both origin updates and copies. `persist` normalizes incoming typed data, adapts
the original local before-image after legacy alias reconciliation, then calls
`PampaBundleCloudSources.merge` after actual retained/staged audio rows are known.

The merge wrapper repairs RAW text/JSON from each version's own source, including
coarse-offset changes and synthesized split histories. Distinct local histories
keep their own provenance/evidence. A same-ID incoming version that would erase
different local public source content/evidence preserves the old RAW under a
deterministic independent history ID, with local segments/refinement parents
following that history. Repeated identical imports do not keep adding histories.
Incoming selection remains authoritative under the existing merge policy.

## Parent PN merge instructions

Reconcile these hooks with parent `b52e45c` and its earlier `65c80dc`, `8ebfda8`,
`11a7cbc` work. Do not replace parent PAMPA enum/options or attachment relay logic.

1. In PN writer/native collection, select the current RAW or selected refinement's
   actual RAW parent first. Pass current-session source rows to
   `portableProjection`; pass the selected note-level union as
   `authorizedOriginalParts` for session scopes, or `retainOriginals=true` for
   whole authorized notes. Store BOTH returned fields before externalizing/hash-
   checking the resulting source JSON blob. Apply the same text to visible export
   content. Preserve full native public history where authorized.
2. The shared `PampaBundleExport.build` now provides this preparation for the ZIP
   graph. If PN bypasses that collector or serializes more native histories, apply
   the same typed preparation to every RAW it actually serializes. Do not scope an
   unrelated, unselected RAW and accidentally change the selected-parent policy.
3. PN reader must resolve/verify externalized text blobs before typed remapping.
   Keep null/empty/long strings distinct. A verified opaque blob is not proof of
   valid cloud provenance: unsupported nonempty source metadata still fails this
   typed transfer boundary without source loss or legacy adoption.
4. PN copy/origin remap must keep the
   `PampaBundleCloudSources.remap(source, destination, partIds, rawIds)` call after
   destination IDs/createdAt are chosen. This requires real SOURCE rows; never
   certify a source with the hash of an occupied replacement destination ID.
5. Preserve the importer local-before-image alias hook and the typed merge wrapper
   after final audio rows are chosen. If parent adds another alias/re-key path,
   route its explicit source/destination graph and simultaneous ID maps through
   the same helper. JSON-only remapping is insufficient.
6. Keep PN's relay attachment planning, fetching, integrity checks, omission
   declarations and file staging. This helper never fetches attachments or stores
   relay/account ownership. The five public recording-origin fields continue to
   travel with copied audio rows; no date-only origin is upgraded to an instant.
7. Merge the portable-source tests with the parent PN roundtrip/blob tests. Re-run
   PN copy/update/alias and attachment-relay fixtures on the combined snapshot.
   This unit's ZIP tests do not establish that combined PN result.

UI callers retain the existing `validatePortable` signature and can display
validated stored overlays while keeping new alignment blocked for unbound data.
No UI-side contract change is required.

## Executed evidence and limits

- Standalone Kotlin 2.2.20 source compilation passed using the installed JDK 17,
  cached compiler/dependency JARs and current production adapter/source files.
  Supporting runner data models came from the existing standalone fixture harness.
  One pre-existing `@ApplicationContext` annotation-target compiler warning remains.
- JUnit standalone run: **133 tests, 0 failures**, across 15 classes. This includes
  projection/UTF16/hash/timing guards, exact split/merge scopes, multi-session
  authorization, originals/reports, native ZIP write/read, copy/new-version/alias,
  fake-DAO persistence swaps, old RAW histories, repeated updates, opaque/null/empty
  distinctions, selected-parent regressions, recording clocks, RAW timeline/save
  plans, legacy reconciliation and omitted attachment behavior.
- Logs, compiler arguments/classpaths and exact test-class list are outside the
  repository in `C:\Users\casua\AppData\Local\Temp\pampa-native-portability-fixtures`:
  `compile.log`, `tests.log`, `compile.args`, `tests.json`.
- Initial standalone harness setup needed an additional existing export dependency;
  the first new ZIP fixtures also used an invalid generator label. Both fixture
  issues were corrected before the final passing run.
- Scoped whitespace checks passed. No Gradle task was run. Full AGP/Hilt/Room/app
  integration and combined parent PN validation remain coordinator work.
- **NOT EXECUTED (user-waived):** emulator/device/physical/runtime/audio/live cloud/
  keys/providers/real model/GPU/real alignment accuracy tests. No new paid
  transcription or forced-alignment request was made.
## Correction1 integration additions

Restricted exports must call `ExportSessionSelection.transcripts(versions, activeId, authorizedParts, retainOriginals)` before choosing BEST or collecting native histories, and consume BOTH `versions` and `activeId`. A refinement's parent ID alone cannot attest its part scope after split/reorder/delete. Immutable source boundaries must all be authorized; otherwise use that refinement's actual RAW parent. Partial/missing lineage conservatively falls back to RAW. Whole-note/folder native exports use `retainOriginals=true` and retain complete public histories. Caller-supplied unsafe native graphs fail before ZIP output rather than silently dropping native refinement while printing its human text. Parent PAMPA gather/planning must apply the same pre-collection rule.

Overlays now hold the validated canonical protocol language code while RAW/part/original provider labels remain exact. Display/codec compare canonical language identity, retaining existing lowercase ISO3 overlays. Unknown historical audio hashes permit provably bounded legacy text transfer but remain unknown and portable_unbound; known conflicting hashes and ambiguous multipart text still fail. No new original-audio alignment authority is granted by transfer.
