# Pampa Notes portable document (.pn), version 1

The document MIME type is `application/vnd.pampa.notes`; its extension is `pn`.
Recognition uses content, independently of the filename. This format carries native note
content; the existing AI ZIP, loose Markdown and single Markdown choices are separate.

## Integration contract

```kotlin
PampaNoteWriter(audioDir, sourcesDir, checkpoint).write(set, output, onProgress)
PampaBundleArchive.isBundle(file)
PampaBundleArchive.read(file, verifyAttachments = false, checkpoint = checkpoint)
PampaBundleArchive.read(file, checkpoint) // compatible full-verification overload
```

The writer requires every `ExportNote.native` to contain its **complete** graph. Missing native
content is an error, rather than permission to reconstruct a partial graph from the visible
transcript. The existing collector already gathers all native histories. `set.folders` must
contain the selected notes' complete ancestor chains. Only those ancestors are exported;
unrelated folders are excluded. No database schema or public importer/preview/result change is needed.

The reader returns the existing `PampaBundleData`, attachment declarations, warnings and precision
flag. Native document attachment paths are relative to the ZIP root (`Read.root == ""`). All old
positional/trailing-lambda `read(file, checkpoint)` calls remain valid.

## ZIP layout

All entries use standard, lossless ZIP DEFLATE at maximum compression. There is no audio decode,
resampling, re-encoding, image conversion, Markdown copy or AI instructions payload.

```text
records/00000000.jsonl
records/00000001.jsonl       # only when necessary
blobs/<lowercase SHA-256>    # unique byte content, without a filename extension
pampa-note.json             # small header, written last
```

ZIP's central directory permits finding the header without traversing media. The header contains
required `format: "pampa-notes"`, `version: 1`, `exportedAtMillis`, `chunks`, `records`, `blobs`,
`notes`, and `folders`. Future versions are recognized as Pampa documents and rejected with an
explicit unsupported-version error. Version 1 metadata is strict about unknown fields and types.

Each metadata chunk is at most 1 MiB of UTF-8. It contains complete JSON records, one per line,
with a final newline. A record never crosses chunks. Records use a `type` discriminator and a
`value` entity. Folder records come first. A `note` record starts a note group; its subsequent
`tag`, `session`, `part`, `transcript`, `segment`, `source` and `attachment` records belong to it.
Groups may cross chunk boundaries. No global JSON array contains the entire semester.

Note bodies, transcript texts and exact cloud source provenance use a separate `body`/`text`/`cloudSource` reference with `bytes` and `sha256`.
Their corresponding inline entity fields are empty (`cloudSourceJson` is null inline and its
absent/null, empty and nonempty values remain distinct).
Recording-origin instant/local time/explicit zone/source/precision remain nullable and are preserved
per part; import never derives an instant or zone from the device clock or a date-only origin.
Ordinary segment text and word timing strings
stay in the metadata chunk so neighboring phrases share a DEFLATE dictionary. When their combined
length exceeds 32,768 UTF-16 code units, they also use `text`/`words` blob references. A reference
and conflicting inline value are rejected. A null word timing string stays null; an empty string
stays empty. No timing is generated.

Blob paths derive only from SHA-256, never entity IDs or remote/local filenames. Identical bytes
can serve multiple audio/source entities or text fields. Every entity keeps its own ID, position,
relationships, dates, MIME and original name. Deduplication does not merge recordings, sources,
sessions, transcripts or notes. ZIP64 is provided by the standard ZIP implementation when needed.

## Preserved and excluded data

The native graph retains note text, pin/language, tags, folder hierarchy/style/dates, sessions and
selection, **all** RAW/refined versions and parent links, exact transcript text, presets/prompt
hashes, segment identity/order/text/probabilities/times, original word timing strings and estimated
flags, speaker labels and names, source relationships/status/details/intrinsic Samsung creation
identity, audio/source sizes and hashes, MIME types and all content dates present in the graph.

Untimed OpenRouter text remains a transcript with no invented segments or words. Precision uses
the selected RAW (or selected refinement's RAW parent), and requires actual, matching,
nonestimated words for every recording.

Private import merge bases/provenance (`bundleBodyBase`, `bundleNativeAdopted`), in-flight device
markers, archive receipt state and local storage filenames are stripped. Accounts, credentials,
endpoints, relay sessions/tokens, jobs, sync ownership and settings are outside the exported model.
There is no arbitrary extra-file area in the format.

Each audio/source row has one attachment declaration. A present blob has a canonical path, exact
size and SHA-256. A missing blob has `path: null`, its original entity ID, expected size and known
hash. Missing content yields `OMITTED_ATTACHMENTS`, and remains unavailable after import. Present
files with known wrong sizes/hashes cause export failure; they are not relabeled as omitted. A
blank local hash can be filled from verified bytes. Local storage paths cannot leave their roots.

An attachment declaration can additionally contain `fileless: true`, exclusively for textual
`CLIPBOARD`/`SHARE` provenance with no stored file, archive receipt or derived-parent relationship.
The source row, its text-content size/hash and all other metadata remain intact; the note body
still carries the imported text. This declaration has `path: null` but does not report an omitted
original and does not request relay/companion retrieval. `fileless` defaults to `false`, preserving
the conservative omission semantics of old PN and ZIP declarations. Audio, PDF, image, binary
share and derived-source declarations cannot use it. A clipboard/share source with an actual
stored attachment retains normal file hashing, retrieval and missing-file warnings; an existing
non-fileless declaration also prevents a stripped filename from being reclassified as text-only.
All rows still require one declaration and the same reference, count, size and hash validation.
Persistence must likewise reserve an expected local reference for an explicitly omitted actual
clipboard/share file (`fileless: false`), while keeping a truly fileless text source's reference
null. That distinction must survive Room import and the next export; an expected reference does
not create a blob or claim local availability.

Before a direct PN export is planned, known genuine originals whose old local reference is null
are looked up through the existing authenticated relay availability path. Its Room adapter reserves
an expected filename under a row guard; no bytes or remote filename are invented. Planning and file
holds then use the refreshed reference, with one availability lookup per missing original. The
service refreshes storage references again immediately before writing, including when the UI has
kept the original gathered snapshot while confirming partial failures. Only the identical gathered
source content/relationships may adopt a new local reference. Relay account, consent, expiry,
incremental transport and byte verification remain the responsibility of the existing relay.

## Integrity, resource bounds and cancellation

The reader rejects unsafe paths, duplicate names (including case aliases and file/directory name
collisions), unsupported methods/versions, inconsistent counts, missing/unreferenced entries,
conflicting blob references, invalid entity relationships/cycles, duplicate segment identities,
invalid word/segment timing or session offsets, malformed UTF-8 and truncated metadata/ZIPs.
Metadata/text entry lengths and ZIP CRCs are checked; referenced text additionally has SHA-256.
Full attachment verification checks exact byte count and SHA-256. Hashes establish byte integrity,
not a sender's identity.

Version 1 limits are 250,000 ZIP entries, 2,000,000 records, 256 GiB total declared uncompressed
entries, 1 MiB per metadata chunk/record, and 64 MiB per external text value. Other individual
entity fields must fit their metadata record. Writer progress planning conservatively bounds
declared attachment bytes plus up to three bytes per UTF-16 text unit by 256 GiB. These are explicit
limits; there is no 64 MiB limit on the combined native note graph.

Audio/source hashing and copying use 64 KiB buffers. Metadata chunk buffering is bounded at 1 MiB;
UTF-8 text writing is incremental. Media is never accumulated in memory. The blob index and native
graph use memory proportional to metadata. The existing import API materializes note/transcript
strings and entity lists; this is not a constant-memory database import. Oversized metadata fails
explicitly. Malformed UTF-16 is rejected instead of silently substituting replacement characters.

Checkpoints run during planning, graph validation, records, and media/text I/O; thread interruption
also aborts reads/writes. Progress is monotonic: hashing up to 45%, records through 55%, unique blob
bytes through 99%, then 100% only after successful ZIP completion. Deduplication can cause jumps.
As with the old writer, the ZIP writer closes the supplied stream. Callers must discard failed or
cancelled partial output files.

`read(file, false, checkpoint)` checks all structure, declared attachment sizes, metadata and text
integrity, while skipping media decompression/hashing. It is suitable for preview/preflight, **not**
permission to commit unchecked media. The existing staging path must call
`PampaBundleArchive.verify(input, bytes, sha256, checkpoint, output)` for every staged entity before
the Room transaction. Shared blob bytes still stage to independent entity files.

## Existing ZIP compatibility and reproduced limitation

Schemas 1, 2 and 3 retain their previous layout and `Read.root` behavior. Tests exercise actual
synthetic schema 1/2 ZIP bytes and schema 3 output from the existing `BundleWriter`. No archive from
the reported friend incident was supplied, so its cause remains unknown.

There is also a fixed synthetic schema-3 archive generated by compiling sixteen unmodified writer
and model sources from released tag `stable-pampa-notes-v1.2.0` (commit
`e8c9ba4f2d0b7cc4a5f138e622be62da61979845`). Its source/blob/entry hashes and offline recipe are in
`core/src/test/resources/released120/README.md`. Seven compatibility tests cover selected branches,
timings/speakers, byte integrity, independent staging, update/copy and conversion to PN. This is
release-source-generated evidence, not an Android export or an archive supplied by the friend.

Schema 1/2 audio has no portable hash. The reader must hash it even during metadata inspection to
derive stable content identity; using positional IDs instead would misidentify reorders. Within a
read, repeated references share the hash result. Schema 3 supports skipping attachment verification.
All legacy streams now also enforce declared lengths/CRCs; full reads do not hash schema 1/2 twice.

A reproducible writer/reader mismatch remains in the **legacy schema 3 format**: `BundleWriter`
has no size ceiling on its single `native.json`, while the legacy reader limits each text entry to
64 MiB. The regression creates 65 notes with 1 MiB bodies, writes the old AI ZIP successfully, then
observes `Text entry too large` on read. The equivalent PN roundtrips, shares the repeated body
bytes once, and uses bounded metadata entries. The legacy limit is retained rather than disabling
the reader's size protection. This is evidence of a format limitation, not proof of the friend's
failure or a claim that an unprovided released-1.2.0 fixture was tested.

## Measurements and executed checks

Standalone Kotlin 2.2.20/JDK 17 compilation and JUnit exercise the current codec/export/entity
sources with real cached dependencies, plus existing compiled supporting classes where required.
No production API stubs are used. The six test classes are `PampaNoteContainerTest`,
`PampaBundleRoundtripTest`, `PampaBundleLegacyArchiveTest`, `ExportWritersTest`,
`PampaBundleSelectionTest`, and `PampaBundleMergeTest`.

Executed result on 2026-11-08: **91 tests passed**, JUnit execution time 6.55 seconds. Compilation
reported one pre-existing redundant-`Json` warning in `ExportWritersTest`; no compilation errors.

Measured file bytes on deterministic fixtures (ZIP container overhead included):

| Fixture | Unique media bytes | AI ZIP bytes | PN bytes | Compressed unique media bytes |
|---|---:|---:|---:|---:|
| Repeated note text, 80,000 UTF-8 bytes | 0 | 6,563 | 1,158 | 0 |
| PCM WAV silence | 131,116 | 535,296 | 3,331 | 190 |
| Valid 1x1 PNG | 68 | 11,091 | 3,197 | 63 |
| AAC in M4A, generated 440 Hz/1 second | 5,370 | 32,310 | 8,024 | 4,881 |
| 1,500 timed segments / 3,000 words plus AAC | 5,370 | 60,992 | 46,147 | 4,881 |

The WAV/PNG/AAC fixtures each attach the same bytes under **four distinct entities** (two audio
parts and two sources); PN stores one shared blob. PN also retains more transcript histories than
the old AI ZIP in these fixtures. These are measured examples, not promised compression ratios.
Every attached byte sequence is compared after decompression. The timed-lecture fixture separately
checks that thousands of ordinary segment/word values stay compact without tiny per-phrase ZIP
entries. `tone.m4a` is a synthetic fixture, generated with:

```text
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=16000:duration=1 -c:a aac -b:a 32k -map_metadata -1 tone.m4a
```

Its SHA-256 is `1ba6b5929c65382d145c0887726a3deba6d81f528dcf590aed88f29642556452`.

Checks also cover empty/text-only/untimed notes, missing bytes, complete alternative histories,
selected refinements, private-state stripping, ancestor scope, multiple metadata chunks, mutation
during write, cancellation/interruption, corrupt data/CRC/UTF-8, duplicates and future versions.
The actual importer remapping and pure merge helpers are exercised for update/copy/multi-hop and
local-only history/body preservation. This is not a Room transaction or device execution.

The PN attachment graph and bytes are also checked against existing `RelayTransfer.validate` with
the current 8 MiB chunks, per-entity kind/ID/hash, chunk hashes and TTL. No account-wide package
wire protocol is introduced. Live account authentication, upload/download, expiry, cloud recovery
without a PC and file/Room rollback remain integration checks owned by the coordinator.

## Parent integration dependencies

- Add `ExportFormat.PAMPA`/`ExportTarget.PAMPA` and route the service/UI to the writer. Native export
  must ignore AI transcript-choice filtering and retain all native histories and source rows.
- Complete the existing `ArchiveFetcher`/relay preflight before writing; this codec performs no
  network calls. Explicit user-approved omissions remain attachment declarations and warnings.
- Use metadata-only reads for preview/import planning; verify every staged media stream before
  commit. Blob paths lack extensions; staging can choose an extension from the entity MIME/original
  name, or retain its existing safe `.bin` fallback. Paths never encode transport/account state.
- Wire picker/share/open-with MIME/extension handling and translated errors outside this scope.
- Run integrated Gradle/build/lint, Room/rollback/device flows, live relay interoperability and
  parent-managed independent reviews. None of those checks were run by this implementer.
