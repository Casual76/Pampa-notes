package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.ExportManifest
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

/** ZIP reader shared by preview and persistence. Never extracts an untrusted path. */
object PampaBundleArchive {
  internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private const val TEXT_LIMIT = 64 * 1024 * 1024

  fun isBundle(file: File): Boolean = runCatching {
    ZipFile(file).use { zip ->
      val manifest = rootManifest(zip) ?: return@use false
      val tree = json.parseToJsonElement(readText(zip, manifest)).jsonObject
      tree["schema"]?.jsonPrimitive?.intOrNull != null && tree["notes"] is JsonArray &&
        tree["generator"]?.jsonPrimitive?.content?.startsWith("Pampa Notes") == true
    }
  }.getOrDefault(false)

  internal data class Read(val data: PampaBundleData, val root: String, val warnings: List<String>, val precise: Boolean)

  internal fun read(file: File, checkpoint: () -> Unit = {}): Read = ZipFile(file).use { zip ->
    val names = mutableSetOf<String>()
    val folded = mutableSetOf<String>()
    zip.entries().asSequence().forEach { entry ->
      checkpoint()
      safePath(entry.name.removeSuffix("/"))
      require(names.add(entry.name) && folded.add(entry.name.lowercase())) { "Duplicate ZIP entry: ${entry.name}" }
    }
    val manifestPath = rootManifest(zip) ?: error("Missing Pampa manifest")
    val root = manifestPath.removeSuffix("manifest.json")
    val manifest = json.decodeFromString(ExportManifest.serializer(), readText(zip, manifestPath))
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
      json.decodeFromString(PampaBundleData.serializer(), readText(zip, root + path)).also { require(it.version == 1) { "Unsupported native payload" } }
    } else legacy(manifest, zip, root, checkpoint)
    validate(data)
    data.notes.flatMap { it.attachments }.forEach { a ->
      checkpoint()
      a.path?.let { path ->
        required(path)
        require(a.bytes >= 0 && a.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "Invalid attachment metadata" }
        val entry = zip.getEntry(root + path)
        require(entry.size == a.bytes) { "Attachment size mismatch: $path" }
        zip.getInputStream(entry).use { verify(it, a.bytes, a.sha256, checkpoint) }
      }
    }
    val warnings = buildList {
      if (manifest.schema < 3) add("Legacy bundle: original body boundaries, IDs and exact word/audio timings were not exported; Markdown is preserved without inventing timestamps.")
      if (data.notes.any { n -> n.attachments.any { it.path == null } }) add("Some attachments were omitted or unavailable. Their audio is not playable unless already stored locally.")
    }
    Read(data, root, warnings, manifest.schema == 3 && data.notes.any { it.segments.isNotEmpty() })
  }

  internal fun safePath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path &&
      path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe ZIP path: $path" }
  }

  private fun rootManifest(zip: ZipFile): String? {
    val candidates = zip.entries().asSequence().filter { !it.isDirectory && (it.name == "manifest.json" || it.name.count { c -> c == '/' } == 1 && it.name.endsWith("/manifest.json")) }.map { it.name }.toList()
    return candidates.singleOrNull()
  }

  private fun readText(zip: ZipFile, path: String): String {
    val entry = zip.getEntry(path) ?: error("Missing $path")
    require(entry.size in 0..TEXT_LIMIT.toLong()) { "Text entry too large: $path" }
    return zip.getInputStream(entry).use { input ->
      val out = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size().toLong() + n <= TEXT_LIMIT) { "Text entry too large" }; out.write(buffer, 0, n) }
      out.toString("UTF-8")
    }
  }

  internal fun verify(input: InputStream, bytes: Long, hash: String?, checkpoint: () -> Unit, output: java.io.OutputStream? = null) {
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var size = 0L
    while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; size += n; require(size <= bytes) { "Attachment size mismatch" }; digest.update(buffer, 0, n); output?.write(buffer, 0, n) }
    require(size == bytes && digest.digest().joinToString("") { "%02x".format(it) }.equals(hash, true)) { "Attachment integrity mismatch" }
  }

  private fun validate(data: PampaBundleData) {
    fun unique(ids: List<String>) { require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Duplicate/empty content ID" } }
    unique(data.folders.map { it.id }); unique(data.notes.map { it.note.id })
    unique(data.notes.flatMap { it.sessions }.map { it.id }); unique(data.notes.flatMap { it.parts }.map { it.id })
    unique(data.notes.flatMap { it.transcripts }.map { it.id }); unique(data.notes.flatMap { it.sources }.map { it.id })
    val folders = data.folders.associateBy { it.id }
    data.folders.forEach { f ->
      val chain = mutableSetOf(f.id); var parent = f.parentId
      while (parent != null) { require(chain.add(parent)) { "Folder cycle" }; parent = (folders[parent] ?: error("Missing parent folder")).parentId }
    }
    data.notes.forEach { n ->
      require(n.note.folderId in folders) { "Missing note folder" }
      val sessions = n.sessions.associateBy { it.id }; val parts = n.parts.associateBy { it.id }; val transcripts = n.transcripts.associateBy { it.id }
      require(n.sessions.all { it.noteId == n.note.id && (it.activeTranscriptId == null || transcripts[it.activeTranscriptId]?.sessionId == it.id) }) { "Invalid session reference" }
      require(n.parts.all { it.sessionId in sessions && it.durationMs >= 0 && it.sizeBytes >= 0 }) { "Invalid audio reference" }
      require(n.parts.groupBy { it.sessionId }.values.all { rows -> rows.map { it.position }.distinct().size == rows.size }) { "Duplicate part position" }
      require(n.transcripts.all { it.sessionId in sessions && (it.parentId == null || transcripts[it.parentId]?.let { p -> p.sessionId == it.sessionId && p.kind == TranscriptKind.RAW && it.kind == TranscriptKind.REFINED } == true) }) { "Invalid transcript reference" }
      require(n.sources.all { it.noteId == n.note.id && (it.derivedFromId == null || n.sources.any { parent -> parent.id == it.derivedFromId && parent.id != it.id }) }) { "Invalid source reference" }
      n.segments.forEach { s ->
        val p = parts[s.partId] ?: error("Missing segment part")
        require(transcripts[s.transcriptId]?.sessionId == p.sessionId && s.partStartMs >= 0 && s.partEndMs >= s.partStartMs && s.partEndMs <= p.durationMs) { "Invalid segment timing/reference" }
        val offset = n.parts.filter { it.sessionId == p.sessionId && it.position < p.position }.sumOf { it.durationMs }
        require(s.sessionStartMs == offset + s.partStartMs && s.sessionEndMs == offset + s.partEndMs) { "Inconsistent session offsets" }
        s.wordsJson?.let { encoded ->
          val words = dev.pampa.pampanotes.core.transcription.WordTimings.decode(encoded, 0)
          require(words.size == encoded.lineSequence().count { it.isNotBlank() } && words.all { it.startMs >= 0 && it.endMs >= it.startMs }) { "Invalid word timings" }
        }
      }
      n.sessions.forEach { session ->
        val keys = dev.pampa.pampanotes.core.transcription.VoiceNames.decode(session.voiceNames).keys
        require(keys.all { key -> parts[key.substringBefore('|')]?.sessionId == session.id }) { "Invalid voice part reference" }
      }
      n.sources.forEach { source ->
        val seen = mutableSetOf(source.id); var parent = source.derivedFromId
        while (parent != null) { require(seen.add(parent)) { "Source cycle" }; parent = n.sources.first { it.id == parent }.derivedFromId }
      }
      unique(n.attachments.map { "${it.audio}:${it.id}" })
      require(n.attachments.filter { it.audio }.map { it.id }.toSet() == parts.keys && n.attachments.filter { !it.audio }.map { it.id }.toSet() == n.sources.map { it.id }.toSet()) { "Missing attachment declaration" }
      n.attachments.filter { it.path != null }.forEach { a ->
        val size = if (a.audio) parts.getValue(a.id).sizeBytes else n.sources.first { it.id == a.id }.sizeBytes
        val hash = if (a.audio) parts.getValue(a.id).sha256 else n.sources.first { it.id == a.id }.sha256
        require(a.bytes == size && (hash.isBlank() || hash.equals(a.sha256, true))) { "Attachment metadata conflict" }
      }
    }
  }

  private fun legacy(manifest: ExportManifest, zip: ZipFile, root: String, checkpoint: () -> Unit): PampaBundleData {
    fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    val folders = linkedMapOf<String, FolderEntity>()
    val notes = manifest.notes.map { n ->
      fun date(value: String) = runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0)
      var parent: String? = null; var path = ""
      n.path.split('/').filter { it.isNotBlank() }.ifEmpty { listOf(n.folder.ifBlank { "Imported" }) }.forEach { name ->
        path += "/$name"; val key = id("legacy-folder:$path")
        folders.putIfAbsent(key, FolderEntity(key, name, parent, createdAt = date(n.created), updatedAt = date(n.updated))); parent = key
      }
      // Keep the entire document: stripping localized headings can destroy user Markdown.
      val body = readText(zip, root + n.file)
      val sources = mutableListOf<SourceEntity>(); val attachments = mutableListOf<PampaBundleAttachment>()
      fun attach(key: String, audio: Boolean, path: String?): PampaBundleAttachment {
        if (path == null) return PampaBundleAttachment(key, audio)
        val entry = zip.getEntry(root + path) ?: error("Missing legacy attachment")
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { input -> val b = ByteArray(65536); while (true) { checkpoint(); val count = input.read(b); if (count < 0) break; digest.update(b, 0, count) } }
        return PampaBundleAttachment(key, audio, path, entry.size, digest.digest().joinToString("") { "%02x".format(it) })
      }
      n.sources.forEachIndexed { i, s -> val key = id("${n.id}:source:$i"); sources += SourceEntity(key, n.id, runCatching { SourceKind.valueOf(s.kind.uppercase()) }.getOrDefault(SourceKind.OTHER), s.name, "application/octet-stream", s.bytes, s.sha256, importedAt = date(n.created)); attachments += attach(key, false, s.file) }
      n.files.filter { it.kind == "handwriting" }.forEach { f -> val key = id("${n.id}:page:${f.path}"); sources += SourceEntity(key, n.id, SourceKind.IMAGE, f.path.substringAfterLast('/'), "image/png", zip.getEntry(root + f.path).size, "", importedAt = date(n.created)); attachments += attach(key, false, f.path) }
      val sessions = n.sessions.mapIndexed { i, s -> SessionEntity(id("${n.id}:session:$i"), n.id, s.title.orEmpty(), s.date, i, createdAt = date(n.created), updatedAt = date(n.updated)) }
      val parts = n.sessions.flatMapIndexed { i, s -> s.audio.mapIndexed { j, path -> val key = id("${sessions[i].id}:part:$j"); val a = attach(key, true, path); attachments += a; AudioPartEntity(key, sessions[i].id, j, "", path.substringAfterLast('/'), "audio/mp4", a.bytes, 0, a.sha256.orEmpty(), date(n.created)) } }
      val transcripts = n.sessions.mapIndexedNotNull { i, s ->
        val pieces = n.files.filter { it.kind == "transcript" && it.session == i + 1 }.sortedBy { it.piece ?: 0 }
        if (pieces.isEmpty()) null else TranscriptEntity(id("${sessions[i].id}:text"), sessions[i].id, if (s.transcript == "refined") TranscriptKind.REFINED else TranscriptKind.RAW, s.provider.orEmpty(), s.model.orEmpty(), text = pieces.joinToString("\n\n") { readText(zip, root + it.path) }, wordCount = s.words, createdAt = date(n.created))
      }
      PampaBundleNote(NoteEntity(n.id, parent!!, n.title, body, language = n.language, createdAt = date(n.created), updatedAt = date(n.updated)), n.tags, sessions.map { s -> s.copy(activeTranscriptId = transcripts.find { it.sessionId == s.id }?.id) }, parts, transcripts, sources = sources, attachments = attachments)
    }
    return PampaBundleData(folders = folders.values.toList(), notes = notes)
  }
}
