package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Internal on-disk contract. Public consumers continue to receive PampaBundleData. */
@Serializable
internal data class PampaNoteHeader(
  val format: String,
  val version: Int,
  val exportedAtMillis: Long,
  val chunks: Int,
  val records: Int,
  val blobs: Int,
  val notes: Int,
  val folders: Int,
)

@Serializable
internal data class PampaNoteBlob(val bytes: Long, val sha256: String) {
  val path: String get() = "blobs/$sha256"
}

@Serializable
internal sealed class PampaNoteRecord {
  @Serializable @SerialName("folder") data class Folder(val value: FolderEntity) : PampaNoteRecord()
  @Serializable @SerialName("note") data class Note(val value: NoteEntity, val body: PampaNoteBlob) : PampaNoteRecord()
  @Serializable @SerialName("tag") data class Tag(val value: String) : PampaNoteRecord()
  @Serializable @SerialName("session") data class Session(val value: SessionEntity) : PampaNoteRecord()
  @Serializable @SerialName("part") data class Part(val value: AudioPartEntity) : PampaNoteRecord()
  @Serializable @SerialName("transcript") data class Transcript(val value: TranscriptEntity, val text: PampaNoteBlob, val cloudSource: PampaNoteBlob? = null) : PampaNoteRecord()
  @Serializable @SerialName("segment") data class Segment(val value: SegmentEntity, val text: PampaNoteBlob? = null, val words: PampaNoteBlob? = null) : PampaNoteRecord()
  @Serializable @SerialName("source") data class Source(val value: SourceEntity) : PampaNoteRecord()
  @Serializable @SerialName("attachment") data class Attachment(val value: PampaBundleAttachment) : PampaNoteRecord()
}

internal object PampaNoteContainer {
  const val MAGIC = "pampa-notes"
  const val HEADER = "pampa-note.json"
  const val CHUNK_BYTES = 1024 * 1024
  const val MAX_TEXT_BYTES = 64L * 1024 * 1024
  const val MAX_ENTRIES = 250_000
  const val MAX_RECORDS = 2_000_000
  const val MAX_TOTAL_BYTES = 256L * 1024 * 1024 * 1024
  val hashPattern = Regex("[0-9a-f]{64}")
  val json = Json { encodeDefaults = false }

  fun chunk(index: Int): String = "records/${index.toString().padStart(8, '0')}.jsonl"

  fun isNative(zip: ZipFile): Boolean = zip.getEntry(HEADER) != null

  fun header(zip: ZipFile, checkpoint: () -> Unit): PampaNoteHeader {
    val encoded = utf8(readEntry(zip, HEADER, CHUNK_BYTES.toLong(), checkpoint))
    val tree = json.parseToJsonElement(encoded).jsonObject
    require(tree["format"]?.jsonPrimitive?.content == MAGIC) { "Not a Pampa note document" }
    val version = tree["version"]?.jsonPrimitive?.intOrNull ?: error("Missing Pampa note version")
    require(version == 1) { "Unsupported Pampa note version $version" }
    // Check the version before decoding its body: a future header may have new required fields.
    val header = json.decodeFromString(PampaNoteHeader.serializer(), encoded)
    require(header.chunks in 0..MAX_ENTRIES && header.records in 0..MAX_RECORDS &&
      header.blobs in 0..MAX_ENTRIES && header.notes in 0..header.records && header.folders in 0..header.records &&
      header.chunks.toLong() + header.blobs + 1 <= MAX_ENTRIES &&
      (header.chunks == 0) == (header.records == 0)) { "Invalid Pampa note counts" }
    return header
  }

  /** Metadata is read one bounded chunk at a time; media is never materialized. */
  fun read(zip: ZipFile, verifyAttachments: Boolean, checkpoint: () -> Unit): PampaBundleData {
    val header = header(zip, checkpoint)
    val references = linkedMapOf<String, PampaNoteBlob>()
    val texts = hashMapOf<String, String>()
    fun reference(ref: PampaNoteBlob) {
      require(ref.bytes in 0..MAX_TOTAL_BYTES && hashPattern.matches(ref.sha256)) { "Invalid blob reference" }
      val entry = zip.getEntry(ref.path) ?: error("Missing blob: ${ref.path}")
      require(!entry.isDirectory && entry.size == ref.bytes) { "Blob size mismatch: ${ref.path}" }
      val old = references.putIfAbsent(ref.path, ref)
      require(old == null || old == ref) { "Conflicting blob reference" }
    }
    fun text(ref: PampaNoteBlob): String {
      reference(ref)
      require(ref.bytes <= MAX_TEXT_BYTES) { "Text blob too large" }
      return texts.getOrPut(ref.path) {
        val bytes = readEntry(zip, ref.path, MAX_TEXT_BYTES, checkpoint)
        bytes.inputStream().use { PampaBundleArchive.verify(it, ref.bytes, ref.sha256, checkpoint) }
        utf8(bytes)
      }
    }
    val folders = mutableListOf<FolderEntity>()
    val notes = mutableListOf<PampaBundleNote>()
    var current: NoteBuilder? = null
    var count = 0
    repeat(header.chunks) { index ->
      checkpoint()
      val bytes = readEntry(zip, chunk(index), CHUNK_BYTES.toLong(), checkpoint)
      require(bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) { "Truncated metadata chunk" }
      utf8(bytes).lineSequence().dropLastEmpty().forEach { line ->
        checkpoint()
        require(++count <= header.records && line.isNotBlank()) { "Invalid metadata record count" }
        when (val record = json.decodeFromString(PampaNoteRecord.serializer(), line)) {
          is PampaNoteRecord.Folder -> {
            require(current == null && notes.isEmpty()) { "Folder after note records" }
            folders += record.value
          }
          is PampaNoteRecord.Note -> {
            current?.let { notes += it.build() }
            require(record.value.body.isEmpty() && record.value.bundleBodyBase == null && record.value.bundleNativeAdopted == null) { "Nonportable note state" }
            current = NoteBuilder(record.value.copy(body = text(record.body)))
          }
          else -> {
            val note = current ?: error("Orphan metadata record")
            when (record) {
              is PampaNoteRecord.Tag -> note.tags += record.value
              is PampaNoteRecord.Session -> {
                require(record.value.transcribingOn == null && record.value.transcribingSince == null) { "Nonportable session state" }
                note.sessions += record.value
              }
              is PampaNoteRecord.Part -> {
                require(record.value.fileName.isEmpty() && record.value.archivedAt == 0L) { "Nonportable audio state" }
                note.parts += record.value
              }
              is PampaNoteRecord.Transcript -> {
                require(record.value.text.isEmpty()) { "Inline transcript text" }
                require(record.value.cloudSourceJson == null) { "Inline cloud source text" }
                note.transcripts += record.value.copy(text = text(record.text), cloudSourceJson = record.cloudSource?.let(::text))
              }
              is PampaNoteRecord.Segment -> {
                val content = record.text?.let { require(record.value.text.isEmpty()) { "Conflicting segment text" }; text(it) } ?: record.value.text
                val words = record.words?.let { require(record.value.wordsJson == null) { "Conflicting word timings" }; text(it) } ?: record.value.wordsJson
                note.segments += record.value.copy(text = content, wordsJson = words)
              }
              is PampaNoteRecord.Source -> {
                require(record.value.storedFileName == null && record.value.archivedAt == 0L) { "Nonportable source state" }
                note.sources += record.value
              }
              is PampaNoteRecord.Attachment -> {
                val a = record.value
                if (a.path != null) {
                  val ref = PampaNoteBlob(a.bytes, a.sha256 ?: error("Missing blob digest"))
                  require(a.path == ref.path) { "Invalid attachment blob path" }
                  reference(ref)
                }
                note.attachments += a
              }
              else -> error("Invalid metadata record")
            }
          }
        }
      }
    }
    current?.let { notes += it.build() }
    require(count == header.records && notes.size == header.notes && folders.size == header.folders && references.size == header.blobs) { "Pampa note count mismatch" }
    val expected = references.keys + (0 until header.chunks).map(::chunk) + HEADER
    require(zip.entries().asSequence().map { it.name }.toSet() == expected.toSet()) { "Unexpected or missing Pampa note entry" }
    val data = PampaBundleData(folders = folders, notes = notes)
    PampaBundleArchive.validate(data, checkpoint)
    // Missing bytes still have an exact declaration, without promising a playable attachment.
    data.notes.forEach { note ->
      val parts = note.parts.associateBy { it.id }
      val sources = note.sources.associateBy { it.id }
      note.attachments.forEach { a ->
        val size = if (a.audio) parts.getValue(a.id).sizeBytes else sources.getValue(a.id).sizeBytes
        val hash = if (a.audio) parts.getValue(a.id).sha256 else sources.getValue(a.id).sha256
        require(a.bytes == size && (hash.isBlank() || hash.equals(a.sha256, true))) { "Missing attachment metadata conflict" }
      }
    }
    if (verifyAttachments) references.values.filter { it.path !in texts }.forEach { ref ->
      checkpoint()
      val entry = zip.getEntry(ref.path)
      zip.getInputStream(entry).use { PampaBundleArchive.verify(it, ref.bytes, ref.sha256, checkpoint, expectedCrc = entry.crc) }
    }
    return data
  }

  private fun Sequence<String>.dropLastEmpty(): Sequence<String> = sequence {
    val iterator = this@dropLastEmpty.iterator()
    while (iterator.hasNext()) {
      val line = iterator.next()
      if (line.isNotEmpty() || iterator.hasNext()) yield(line)
    }
  }

  /** ZipFile streams do not guarantee CRC checking; check both declared length and CRC here. */
  fun readEntry(zip: ZipFile, path: String, limit: Long, checkpoint: () -> Unit): ByteArray {
    val entry = zip.getEntry(path) ?: error("Missing $path")
    require(!entry.isDirectory && entry.size in 0..limit) { "Text entry too large: $path" }
    val output = ByteArrayOutputStream(minOf(entry.size, 65536L).toInt())
    val crc = CRC32()
    zip.getInputStream(entry).use { input ->
      val buffer = ByteArray(65536)
      while (true) {
        checkpoint()
        val size = input.read(buffer)
        if (size < 0) break
        require(output.size().toLong() + size <= entry.size) { "Entry size mismatch: $path" }
        output.write(buffer, 0, size)
        crc.update(buffer, 0, size)
      }
    }
    require(output.size().toLong() == entry.size && crc.value == entry.crc) { "Entry integrity mismatch: $path" }
    return output.toByteArray()
  }

  fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

  fun utf8(value: String): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
  }

  private class NoteBuilder(val note: NoteEntity) {
    val tags = mutableListOf<String>()
    val sessions = mutableListOf<SessionEntity>()
    val parts = mutableListOf<AudioPartEntity>()
    val transcripts = mutableListOf<TranscriptEntity>()
    val segments = mutableListOf<SegmentEntity>()
    val sources = mutableListOf<SourceEntity>()
    val attachments = mutableListOf<PampaBundleAttachment>()
    fun build() = PampaBundleNote(note, tags, sessions, parts, transcripts, segments, sources, attachments)
  }
}
