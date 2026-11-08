package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.importing.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lossless native export. File I/O uses fixed buffers; no audio decoding or transcoding. */
class PampaNoteWriter(
  private val audioDir: File,
  private val sourcesDir: File,
  private val checkpoint: () -> Unit = {},
) {
  fun write(set: ExportSet, out: OutputStream, onProgress: (Float) -> Unit = {}) {
    check()
    onProgress(0f)
    val blobs = linkedMapOf<String, Blob>()
    val textRefs = hashMapOf<String, PampaNoteBlob>()
    val native = set.notes.map { requireNotNull(it.native) { "Pampa note export requires the complete native graph" } }
    var estimated = 0L
    fun weigh(bytes: Long) {
      require(bytes >= 0 && bytes <= PampaNoteContainer.MAX_TOTAL_BYTES - estimated) { "Pampa note export too large or invalid size" }
      estimated += bytes
    }
    native.forEach { note ->
      check()
      note.parts.forEach { weigh(it.sizeBytes) }
      note.sources.forEach { weigh(it.sizeBytes) }
      weigh(note.note.body.length * 3L)
      note.transcripts.forEach { weigh(it.text.length * 3L) }
      note.segments.forEach { weigh(it.text.length * 3L); weigh((it.wordsJson?.length ?: 0) * 3L) }
    }
    estimated = estimated.coerceAtLeast(1)
    var hashed = 0L
    fun hashed(size: Int) {
      hashed += size
      onProgress((0.45 * hashed.toDouble() / estimated).toFloat().coerceIn(0f, 0.45f))
    }
    fun register(blob: Blob): PampaNoteBlob {
      val ref = blob.digest(::hashed)
      val previous = blobs.putIfAbsent(ref.path, blob.copy(ref = ref))
      require(previous == null || previous.ref == ref) { "Conflicting content digest" }
      return ref
    }
    fun text(value: String): PampaNoteBlob = textRefs.getOrPut(value) {
      register(Blob(text = value)).also { require(it.bytes <= PampaNoteContainer.MAX_TEXT_BYTES) { "Text blob too large" } }
    }
    fun attachment(id: String, audio: Boolean, name: String?, size: Long, hash: String): PampaBundleAttachment {
      check()
      require(size in 0..PampaNoteContainer.MAX_TOTAL_BYTES && (hash.isBlank() || hash.matches(Regex("[a-fA-F0-9]{64}")))) { "Invalid local attachment metadata" }
      val file = localFile(if (audio) audioDir else sourcesDir, name)
      if (file == null || !file.exists()) return PampaBundleAttachment(id, audio, bytes = size, sha256 = hash.takeIf { it.isNotBlank() })
      require(file.isFile && file.length() == size) { "Local attachment size mismatch: $id" }
      val ref = register(Blob(file = file))
      require(hash.isBlank() || hash.equals(ref.sha256, true)) { "Local attachment integrity mismatch: $id" }
      return PampaBundleAttachment(id, audio, ref.path, ref.bytes, ref.sha256)
    }
    val notes = native.mapIndexed { index, value ->
      check()
      require(value.note.id == set.notes[index].note.id && value.note.folderId == set.notes[index].note.folderId) { "Native note identity mismatch" }
      val attachments = value.parts.map { attachment(it.id, true, it.fileName, it.sizeBytes, it.sha256) } +
        value.sources.map { attachment(it.id, false, it.storedFileName, it.sizeBytes, it.sha256) }
      val audio = attachments.filter { it.audio }.associateBy { it.id }
      val sources = attachments.filterNot { it.audio }.associateBy { it.id }
      value.copy(
        note = value.note.copy(bundleBodyBase = null, bundleNativeAdopted = null),
        sessions = value.sessions.map { it.copy(transcribingOn = null, transcribingSince = null) },
        parts = value.parts.map { it.copy(fileName = "", archivedAt = 0, sha256 = audio.getValue(it.id).sha256 ?: it.sha256) },
        sources = value.sources.map { it.copy(storedFileName = null, archivedAt = 0, sha256 = sources.getValue(it.id).sha256 ?: it.sha256) },
        attachments = attachments,
      )
    }
    val folders = set.folders.associateBy { it.id }
    require(folders.size == set.folders.size) { "Duplicate folder ID" }
    val usedFolders = linkedSetOf<String>()
    notes.forEach { note ->
      val chain = mutableSetOf<String>()
      var id: String? = note.note.folderId
      while (id != null) {
        check()
        require(chain.add(id)) { "Folder cycle" }
        usedFolders += id
        id = (folders[id] ?: error("Missing native ancestor folder: $id")).parentId
      }
    }
    val data = PampaBundleData(folders = set.folders.filter { it.id in usedFolders }, notes = notes)
    PampaBundleArchive.validate(data, ::check)
    var recordCount = 0
    fun records(): Sequence<PampaNoteRecord> = sequence {
      data.folders.forEach { yield(PampaNoteRecord.Folder(it)) }
      data.notes.forEach { note ->
        yield(PampaNoteRecord.Note(note.note.copy(body = ""), text(note.note.body)))
        note.tags.forEach { yield(PampaNoteRecord.Tag(it)) }
        note.sessions.forEach { yield(PampaNoteRecord.Session(it)) }
        note.parts.forEach { yield(PampaNoteRecord.Part(it)) }
        note.transcripts.forEach { yield(PampaNoteRecord.Transcript(it.copy(text = ""), text(it.text))) }
        note.segments.forEach {
          // Small segment text/words share the chunk's DEFLATE dictionary. One tiny ZIP entry per
          // phrase would cost more in filenames/headers than the text it was meant to compress.
          if (it.text.length.toLong() + (it.wordsJson?.length ?: 0) <= 32768) yield(PampaNoteRecord.Segment(it))
          else yield(PampaNoteRecord.Segment(it.copy(text = "", wordsJson = null), text(it.text), it.wordsJson?.let(::text)))
        }
        note.sources.forEach { yield(PampaNoteRecord.Source(it)) }
        note.attachments.forEach { yield(PampaNoteRecord.Attachment(it)) }
      }
    }
    // Plan text hashes before opening the ZIP. No metadata/text duplication is kept in memory.
    records().forEach { check(); require(++recordCount <= PampaNoteContainer.MAX_RECORDS) { "Too many metadata records" } }
    onProgress(0.45f)
    var chunks = 0
    var writtenRecords = 0
    var writtenBytes = 0L
    val blobBytes = blobs.values.sumOf { it.ref!!.bytes }.coerceAtLeast(1)
    require(blobBytes <= PampaNoteContainer.MAX_TOTAL_BYTES) { "Pampa note export too large" }
    ZipOutputStream(out.buffered()).use { zip ->
      zip.setLevel(Deflater.BEST_COMPRESSION)
      val chunk = ByteArrayOutputStream(PampaNoteContainer.CHUNK_BYTES)
      fun flushChunk() {
        if (chunk.size() == 0) return
        require(++chunks + blobs.size + 1 <= PampaNoteContainer.MAX_ENTRIES) { "Too many Pampa note entries" }
        zip.putNextEntry(ZipEntry(PampaNoteContainer.chunk(chunks - 1)))
        chunk.writeTo(zip)
        zip.closeEntry()
        chunk.reset()
      }
      records().forEach { record ->
        check()
        val line = PampaNoteContainer.utf8(PampaNoteContainer.json.encodeToString(PampaNoteRecord.serializer(), record))
        require(line.size < PampaNoteContainer.CHUNK_BYTES) { "Metadata record too large" }
        if (chunk.size() + line.size + 1 > PampaNoteContainer.CHUNK_BYTES) flushChunk()
        chunk.write(line)
        chunk.write('\n'.code)
        onProgress(0.45f + 0.1f * ++writtenRecords / recordCount.coerceAtLeast(1))
      }
      flushChunk()
      blobs.values.forEach { blob ->
        check()
        zip.putNextEntry(ZipEntry(blob.ref!!.path))
        val actual = blob.copyTo(zip) { size ->
          writtenBytes += size
          onProgress((0.55 + 0.44 * writtenBytes.toDouble() / blobBytes).toFloat().coerceAtMost(0.99f))
        }
        require(actual == blob.ref) { "Attachment changed during export" }
        zip.closeEntry()
      }
      val header = PampaNoteHeader(format = PampaNoteContainer.MAGIC, version = 1, exportedAtMillis = set.exportedAtMillis, chunks = chunks, records = recordCount,
        blobs = blobs.size, notes = data.notes.size, folders = data.folders.size)
      zip.putNextEntry(ZipEntry(PampaNoteContainer.HEADER))
      zip.write(PampaNoteContainer.json.encodeToString(PampaNoteHeader.serializer(), header).toByteArray(Charsets.UTF_8))
      zip.closeEntry()
      check()
    }
    onProgress(1f)
  }

  private fun localFile(directory: File, name: String?): File? {
    if (name.isNullOrBlank()) return null
    require(name != "." && name != ".." && '/' !in name && '\\' !in name && ':' !in name && name.none { it.isISOControl() }) { "Unsafe local attachment name" }
    val file = File(directory, name)
    require(file.canonicalFile.parentFile == directory.canonicalFile) { "Attachment outside storage directory" }
    return file
  }

  private fun check() {
    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Pampa note export interrupted")
    checkpoint()
  }

  private inner class Blob(val file: File? = null, val text: String? = null, val ref: PampaNoteBlob? = null) {
    fun copy(ref: PampaNoteBlob) = Blob(file, text, ref)
    fun digest(progress: (Int) -> Unit): PampaNoteBlob = copyTo(null, progress)
    fun copyTo(output: OutputStream?, progress: (Int) -> Unit): PampaNoteBlob {
      val digest = MessageDigest.getInstance("SHA-256")
      var bytes = 0L
      val sink = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
          check()
          bytes += length
          require(bytes <= (ref?.bytes ?: PampaNoteContainer.MAX_TOTAL_BYTES)) { "Attachment changed during export" }
          digest.update(buffer, offset, length)
          output?.write(buffer, offset, length)
          progress(length)
        }
      }
      if (file != null) {
        file.inputStream().use { input ->
          val buffer = ByteArray(65536)
          while (true) {
            check()
            val size = input.read(buffer)
            if (size < 0) break
            sink.write(buffer, 0, size)
          }
        }
      } else {
        // REPORT avoids replacing malformed UTF-16 with '?' and calling that lossless.
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val writer = OutputStreamWriter(sink, encoder)
        val value = requireNotNull(text)
        var offset = 0
        while (offset < value.length) {
          check()
          val size = minOf(8192, value.length - offset)
          writer.write(value, offset, size)
          offset += size
        }
        writer.close()
      }
      return PampaNoteBlob(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }
  }
}
