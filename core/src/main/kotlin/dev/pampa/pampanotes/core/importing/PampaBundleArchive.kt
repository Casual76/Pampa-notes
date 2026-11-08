package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.ExportManifest
import java.util.Locale
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

/** ZIP reader shared by preview and persistence. Never extracts an untrusted path. */
object PampaBundleArchive {
  internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private const val TEXT_LIMIT = 64 * 1024 * 1024

  fun isBundle(file: File): Boolean = runCatching {
    ZipFile(file).use { zip ->
      if (PampaNoteContainer.isNative(zip)) {
        // Recognize even future versions so the importer can explain an unsupported version.
        val tree = json.parseToJsonElement(PampaNoteContainer.utf8(PampaNoteContainer.readEntry(zip,
          PampaNoteContainer.HEADER, PampaNoteContainer.CHUNK_BYTES.toLong(), {}))).jsonObject
        return@use tree["format"]?.jsonPrimitive?.content == PampaNoteContainer.MAGIC
      }
      val manifest = rootManifest(zip) ?: return@use false
      val tree = json.parseToJsonElement(readText(zip, manifest)).jsonObject
      tree["schema"]?.jsonPrimitive?.intOrNull != null && tree["notes"] is JsonArray &&
        tree["generator"]?.jsonPrimitive?.content?.startsWith("Pampa Notes") == true
    }
  }.getOrDefault(false)

  internal data class Read(val data: PampaBundleData, val root: String, val warnings: List<PampaBundleWarning>, val precise: Boolean)

  internal fun read(file: File, checkpoint: () -> Unit = {}): Read = read(file, true, checkpoint)

  internal fun read(file: File, verifyAttachments: Boolean, checkpoint: () -> Unit = {}): Read = readChecked(file, verifyAttachments) {
    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Pampa note import interrupted")
    checkpoint()
  }

  private fun readChecked(file: File, verifyAttachments: Boolean, checkpoint: () -> Unit): Read = ZipFile(file).use { zip ->
    checkpoint()
    val names = mutableSetOf<String>()
    val folded = mutableSetOf<String>()
    var totalBytes = 0L
    zip.entries().asSequence().forEach { entry ->
      checkpoint()
      safePath(entry.name.removeSuffix("/"))
      require(names.size < PampaNoteContainer.MAX_ENTRIES && names.add(entry.name) &&
        folded.add(entry.name.removeSuffix("/").lowercase(Locale.ROOT))) { "Duplicate/too many ZIP entries: ${entry.name}" }
      require(entry.size >= 0 && entry.compressedSize >= 0 && entry.crc in 0..0xffffffffL &&
        entry.method in setOf(java.util.zip.ZipEntry.STORED, java.util.zip.ZipEntry.DEFLATED)) { "Invalid ZIP entry metadata" }
      require(entry.size <= PampaNoteContainer.MAX_TOTAL_BYTES - totalBytes) { "ZIP declared size limit exceeded" }
      totalBytes += entry.size
    }
    if (PampaNoteContainer.isNative(zip)) {
      val data = PampaNoteContainer.read(zip, verifyAttachments, checkpoint)
      val warnings = if (data.notes.any { n -> n.attachments.any { it.path == null && !it.fileless } }) listOf(PampaBundleWarning.OMITTED_ATTACHMENTS) else emptyList()
      return@use Read(data, "", warnings, hasPreciseWordCoverage(data, checkpoint))
    }
    val manifestPath = rootManifest(zip) ?: error("Missing Pampa manifest")
    val root = manifestPath.removeSuffix("manifest.json")
    val manifest = json.decodeFromString(ExportManifest.serializer(), readText(zip, manifestPath, checkpoint))
    require(manifest.generator.startsWith("Pampa Notes")) { "Not a Pampa bundle" }
    require(manifest.schema in 1..3) { "Unsupported Pampa schema ${manifest.schema}" }
    fun required(path: String) { safePath(path); require(zip.getEntry(root + path)?.isDirectory == false) { "Missing required entry: $path" } }
    manifest.notes.forEach { n ->
      required(n.file)
      n.files.forEach { required(it.path) }
      n.sessions.flatMap { it.audio }.forEach(::required)
      n.sources.mapNotNull { it.file }.forEach(::required)
    }
    val data = if (manifest.schema == 3) {
      val path = manifest.nativeFile ?: error("Missing native payload reference")
      required(path)
      json.decodeFromString(PampaBundleData.serializer(), readText(zip, root + path, checkpoint)).also { require(it.version == 1) { "Unsupported native payload" } }
    } else legacy(manifest, zip, root, checkpoint)
    validate(data, checkpoint)
    val verified = mutableMapOf<String, Pair<Long, String?>>()
    data.notes.flatMap { it.attachments }.forEach { a ->
      checkpoint()
      a.path?.let { path ->
        required(path)
        require(a.bytes >= 0 && a.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "Invalid attachment metadata" }
        val entry = zip.getEntry(root + path)
        require(entry.size == a.bytes) { "Attachment size mismatch: $path" }
        val metadata = a.bytes to a.sha256?.lowercase(Locale.ROOT)
        val previous = verified.putIfAbsent(path, metadata)
        require(previous == null || previous == metadata) { "Conflicting attachment references: $path" }
        // Schema 1/2 already stream/hash here to derive stable content identity in legacy().
        if (manifest.schema == 3 && verifyAttachments && previous == null) zip.getInputStream(entry).use { verify(it, a.bytes, a.sha256, checkpoint, expectedCrc = entry.crc) }
      }
    }
    val warnings = buildList {
      if (manifest.schema < 3) add(PampaBundleWarning.LEGACY_FORMAT)
      if (data.notes.any { n -> n.attachments.any { it.path == null && !it.fileless } }) add(PampaBundleWarning.OMITTED_ATTACHMENTS)
    }
    Read(data, root, warnings, manifest.schema == 3 && hasPreciseWordCoverage(data, checkpoint))
  }

  /** Every recording needs its selected RAW's words; one timed segment cannot describe a bundle. */
  private fun hasPreciseWordCoverage(data: PampaBundleData, checkpoint: () -> Unit): Boolean {
    if (data.notes.none { it.parts.isNotEmpty() }) return false
    return data.notes.all { note ->
      val sessions = note.sessions.associateBy { it.id }
      val histories = note.transcripts.groupBy { it.sessionId }
      val byPart = note.segments.groupBy { it.partId }
      note.parts.all { part ->
        checkpoint()
        val session = sessions.getValue(part.sessionId)
        val raw = dev.pampa.pampanotes.core.repo.RawTranscripts.forSelection(
          histories[session.id].orEmpty(), session.activeTranscriptId)
        val segments = byPart[part.id].orEmpty().filter { it.transcriptId == raw?.id }
        segments.isNotEmpty() && segments.all { segment ->
          checkpoint()
          val words = dev.pampa.pampanotes.core.transcription.WordTimings.decode(segment.wordsJson, 0)
          val tokens = segment.text.split(Regex("\\s+")).filter { it.isNotBlank() }
          !segment.wordsEstimated && words.isNotEmpty() && tokens.size == words.size &&
            tokens.zip(words).all { (token, word) ->
              // Ignore punctuation/case, but never claim precise coverage for unrelated words.
              val expected = java.text.Normalizer.normalize(token, java.text.Normalizer.Form.NFC).filter { it.isLetterOrDigit() }
              val actual = java.text.Normalizer.normalize(word.text, java.text.Normalizer.Form.NFC).filter { it.isLetterOrDigit() }
              expected.isNotEmpty() && expected.equals(actual, true)
            }
        }
      }
    }
  }

  internal fun safePath(path: String) {
    require(path.isNotBlank() && path.length <= 4096 && !path.startsWith('/') && '\\' !in path && ':' !in path && path.none { it.isISOControl() } &&
      path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe ZIP path: $path" }
  }

  private fun rootManifest(zip: ZipFile): String? {
    val candidates = zip.entries().asSequence().filter { !it.isDirectory && (it.name == "manifest.json" || it.name.count { c -> c == '/' } == 1 && it.name.endsWith("/manifest.json")) }.map { it.name }.toList()
    return candidates.singleOrNull()
  }

  private fun readText(zip: ZipFile, path: String, checkpoint: () -> Unit = {}): String =
    PampaNoteContainer.utf8(PampaNoteContainer.readEntry(zip, path, TEXT_LIMIT.toLong(), checkpoint))

  internal fun verify(input: InputStream, bytes: Long, hash: String?, checkpoint: () -> Unit, output: java.io.OutputStream? = null, expectedCrc: Long? = null) {
    require(bytes in 0..PampaNoteContainer.MAX_TOTAL_BYTES && hash?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "Invalid attachment metadata" }
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var size = 0L
    val crc = expectedCrc?.let { java.util.zip.CRC32() }
    while (true) {
      if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Pampa attachment verification interrupted")
      checkpoint(); val n = input.read(buffer); if (n < 0) break; size += n; require(size <= bytes) { "Attachment size mismatch" }; digest.update(buffer, 0, n); crc?.update(buffer, 0, n); output?.write(buffer, 0, n)
    }
    require(size == bytes && digest.digest().joinToString("") { "%02x".format(it) }.equals(hash, true)) { "Attachment integrity mismatch" }
    require(crc == null || crc.value == expectedCrc) { "Attachment ZIP CRC mismatch" }
  }

  internal fun validate(data: PampaBundleData, checkpoint: () -> Unit = {}) {
    fun unique(ids: List<String>) { require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Duplicate/empty content ID" } }
    unique(data.folders.map { it.id }); unique(data.notes.map { it.note.id })
    unique(data.notes.flatMap { it.sessions }.map { it.id }); unique(data.notes.flatMap { it.parts }.map { it.id })
    unique(data.notes.flatMap { it.transcripts }.map { it.id }); unique(data.notes.flatMap { it.sources }.map { it.id })
    val folders = data.folders.associateBy { it.id }
    val checkedFolders = mutableSetOf<String>()
    data.folders.forEach { f ->
      checkpoint()
      val chain = mutableSetOf<String>(); var parent: String? = f.id
      while (parent != null && parent !in checkedFolders) {
        checkpoint(); require(chain.add(parent)) { "Folder cycle" }; parent = (folders[parent] ?: error("Missing parent folder")).parentId
      }
      checkedFolders += chain
    }
    data.notes.forEach { n ->
      checkpoint()
      require(n.note.folderId in folders) { "Missing note folder" }
      val sessions = n.sessions.associateBy { it.id }; val parts = n.parts.associateBy { it.id }; val transcripts = n.transcripts.associateBy { it.id }
      val sources = n.sources.associateBy { it.id }
      require(n.sessions.all { it.noteId == n.note.id && (it.activeTranscriptId == null || transcripts[it.activeTranscriptId]?.sessionId == it.id) }) { "Invalid session reference" }
      require(n.parts.all { it.sessionId in sessions && it.position >= 0 && it.durationMs >= 0 && it.sizeBytes >= 0 }) { "Invalid audio reference" }
      require(n.parts.groupBy { it.sessionId }.values.all { rows -> rows.map { it.position }.distinct().size == rows.size }) { "Duplicate part position" }
      require(n.transcripts.all { it.sessionId in sessions && (it.parentId == null || transcripts[it.parentId]?.let { p -> p.sessionId == it.sessionId && p.kind == TranscriptKind.RAW && it.kind == TranscriptKind.REFINED } == true) }) { "Invalid transcript reference" }
      require(n.sources.all { it.noteId == n.note.id && (it.derivedFromId == null || it.derivedFromId in sources && it.derivedFromId != it.id) }) { "Invalid source reference" }
      require(n.sources.all { it.sizeBytes >= 0 }) { "Invalid source size" }
      val offsets = mutableMapOf<String, Long>()
      n.parts.groupBy { it.sessionId }.values.forEach { rows ->
        var offset = 0L
        rows.sortedBy { it.position }.forEach { part -> offsets[part.id] = offset; offset = Math.addExact(offset, part.durationMs) }
      }
      require(n.segments.map { Triple(it.transcriptId, it.partId, it.indexInPart) }.distinct().size == n.segments.size) { "Duplicate segment identity" }
      n.segments.forEach { s ->
        checkpoint()
        val p = parts[s.partId] ?: error("Missing segment part")
        require(transcripts[s.transcriptId]?.sessionId == p.sessionId && s.indexInPart >= 0 && s.partStartMs >= 0 && s.partEndMs >= s.partStartMs && s.partEndMs <= p.durationMs) { "Invalid segment timing/reference" }
        val offset = offsets.getValue(p.id)
        require(s.sessionStartMs == offset + s.partStartMs && s.sessionEndMs == offset + s.partEndMs) { "Inconsistent session offsets" }
        s.wordsJson?.let { encoded ->
          val words = dev.pampa.pampanotes.core.transcription.WordTimings.decode(encoded, 0)
          val duration = s.partEndMs - s.partStartMs
          require(words.size == encoded.lineSequence().count { it.isNotBlank() } && words.all { it.startMs >= 0 && it.endMs >= it.startMs && it.endMs <= duration }) { "Invalid word timings" }
        }
      }
      n.sessions.forEach { session ->
        val keys = dev.pampa.pampanotes.core.transcription.VoiceNames.decode(session.voiceNames).keys
        require(keys.all { key -> parts[key.substringBefore('|')]?.sessionId == session.id }) { "Invalid voice part reference" }
      }
      val checkedSources = mutableSetOf<String>()
      n.sources.forEach { source ->
        val seen = mutableSetOf<String>(); var parent: String? = source.id
        while (parent != null && parent !in checkedSources) {
          checkpoint(); require(seen.add(parent)) { "Source cycle" }; parent = sources.getValue(parent).derivedFromId
        }
        checkedSources += seen
      }
      unique(n.attachments.map { "${it.audio}:${it.id}" })
      require(n.attachments.filter { it.audio }.map { it.id }.toSet() == parts.keys && n.attachments.filter { !it.audio }.map { it.id }.toSet() == n.sources.map { it.id }.toSet()) { "Missing attachment declaration" }
      n.attachments.filter { it.fileless }.forEach { a ->
        require(!a.audio && a.path == null && PampaBundleSourceFiles.isFileless(sources.getValue(a.id))) { "Invalid fileless source declaration" }
        val source = sources.getValue(a.id)
        require(a.bytes == source.sizeBytes && (source.sha256.isBlank() || source.sha256.equals(a.sha256, true))) { "Fileless source metadata conflict" }
      }
      n.attachments.filter { it.path != null }.forEach { a ->
        val size = if (a.audio) parts.getValue(a.id).sizeBytes else sources.getValue(a.id).sizeBytes
        val hash = if (a.audio) parts.getValue(a.id).sha256 else sources.getValue(a.id).sha256
        require(a.bytes == size && (hash.isBlank() || hash.equals(a.sha256, true))) { "Attachment metadata conflict" }
      }
      n.attachments.forEach { require(it.bytes >= 0 && (it.sha256 == null || it.sha256.matches(Regex("[a-fA-F0-9]{64}")))) { "Invalid attachment metadata" } }
    }
  }

  private fun legacy(manifest: ExportManifest, zip: ZipFile, root: String, checkpoint: () -> Unit): PampaBundleData {
    fun id(kind: String, value: String) = PampaBundleLegacyReconcile.id(kind, value)
    val folders = linkedMapOf<String, FolderEntity>()
    val attachmentCache = mutableMapOf<String, PampaBundleAttachment>()
    val notes = manifest.notes.map { n ->
      fun date(value: String) = runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0)
      var parent: String? = null; var path = ""
      n.path.split('/').filter { it.isNotBlank() }.ifEmpty { listOf(n.folder.ifBlank { "Imported" }) }.forEach { name ->
        path += "/$name"; val key = id("folder", "legacy-folder:$path")
        folders.putIfAbsent(key, FolderEntity(key, name, parent, createdAt = date(n.created), updatedAt = date(n.updated))); parent = key
      }
      // Keep the entire document: stripping localized headings can destroy user Markdown.
      val body = readText(zip, root + n.file, checkpoint)
      val sources = mutableListOf<SourceEntity>(); val attachments = mutableListOf<PampaBundleAttachment>()
      fun attach(key: String, audio: Boolean, path: String?): PampaBundleAttachment {
        if (path == null) return PampaBundleAttachment(key, audio)
        attachmentCache[path]?.let { return it.copy(id = key, audio = audio) }
        val entry = zip.getEntry(root + path) ?: error("Missing legacy attachment")
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = java.util.zip.CRC32()
        var size = 0L
        zip.getInputStream(entry).use { input -> val b = ByteArray(65536); while (true) {
          checkpoint(); val count = input.read(b); if (count < 0) break
          size += count; require(size <= entry.size) { "Legacy attachment size mismatch" }
          digest.update(b, 0, count); crc.update(b, 0, count)
        } }
        require(size == entry.size && crc.value == entry.crc) { "Legacy attachment integrity mismatch" }
        return PampaBundleAttachment(key, audio, path, entry.size, digest.digest().joinToString("") { "%02x".format(it) }).also { attachmentCache[path] = it }
      }
      n.sources.forEach { source ->
        val attachment = attach("", false, source.file)
        val sha = source.sha256.ifBlank { attachment.sha256.orEmpty() }
        val kind = runCatching { SourceKind.valueOf(source.kind.uppercase()) }.getOrDefault(SourceKind.OTHER)
        val key = id("source", "${n.id}:source:$kind:${sha.lowercase()}:${source.name}")
        sources += SourceEntity(key, n.id, kind, source.name, "application/octet-stream", source.bytes, sha, importedAt = date(n.created))
        attachments += attachment.copy(id = key)
      }
      val pages = n.files.filter { it.kind == "handwriting" }
      // Handwriting must remain derived content even when its original was not exported.
      val pageParent = if (pages.isEmpty()) null else {
        sources.filter { it.kind == SourceKind.SDOCX }.singleOrNull()?.id
          ?: id("placeholder", "${n.id}:handwriting-origin").also { key ->
            sources += SourceEntity(key, n.id, SourceKind.OTHER, n.title, "application/octet-stream", 0, "", importedAt = date(n.created))
            attachments += PampaBundleAttachment(key, false)
          }
      }
      pages.forEach { f ->
        val attachment = attach("", false, f.path)
        val key = id("page", "${n.id}:page:${attachment.sha256}:${f.path.substringAfterLast('/')}")
        sources += SourceEntity(key, n.id, SourceKind.IMAGE, f.path.substringAfterLast('/'), "image/png", zip.getEntry(root + f.path).size, attachment.sha256.orEmpty(), importedAt = date(n.created), derivedFromId = pageParent)
        attachments += attachment.copy(id = key)
      }
      val sessions = n.sessions.mapIndexed { i, s -> SessionEntity(id("session", "${n.id}:session:$i"), n.id, s.title.orEmpty(), s.date, i, createdAt = date(n.created), updatedAt = date(n.updated)) }
      val parts = n.sessions.flatMapIndexed { i, session -> session.audio.mapIndexed { position, path ->
        val attachment = attach("", true, path)
        val key = id("part", "${n.id}:audio:${attachment.sha256}:${path.substringAfterLast('/')}")
        attachments += attachment.copy(id = key)
        AudioPartEntity(key, sessions[i].id, position, "", path.substringAfterLast('/'), "audio/mp4", attachment.bytes, 0, attachment.sha256.orEmpty(), date(n.created))
      } }
      val transcripts = n.sessions.mapIndexedNotNull { i, s ->
        val pieces = n.files.filter { it.kind == "transcript" && it.session == i + 1 }.sortedBy { it.piece ?: 0 }
        val text = pieces.joinToString("\n\n") { readText(zip, root + it.path, checkpoint) }
        if (pieces.isEmpty()) null else TranscriptEntity(id("transcript", "${sessions[i].id}:text:${dev.pampa.pampanotes.core.files.Hashing.sha256(text)}"), sessions[i].id, if (s.transcript == "refined") TranscriptKind.REFINED else TranscriptKind.RAW, s.provider.orEmpty(), s.model.orEmpty(), text = text, wordCount = s.words, createdAt = date(n.created))
      }
      PampaBundleNote(NoteEntity(n.id, parent!!, n.title, body, language = n.language, createdAt = date(n.created), updatedAt = date(n.updated), bundleNativeAdopted = false), n.tags, sessions.map { s -> s.copy(activeTranscriptId = transcripts.find { it.sessionId == s.id }?.id) }, parts, transcripts, sources = sources, attachments = attachments)
    }
    return PampaBundleData(folders = folders.values.toList(), notes = notes)
  }
}
