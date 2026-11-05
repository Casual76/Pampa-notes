package dev.pampa.pampanotes.core.importing

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*

@Singleton
class PampaBundleImporter @Inject constructor(private val db: PampaDatabase, private val files: AppFiles) {
  suspend fun inspect(file: File): PampaBundlePreview = withContext(Dispatchers.IO) {
    val ctx = currentCoroutineContext()
    val read = PampaBundleArchive.read(file) { ctx.ensureActive() }
    PampaBundlePreview(read.data.notes.size,
      read.data.notes.count { db.notes().get(originId(it.note.id)) != null },
      read.data.notes.sumOf { n -> n.attachments.count { it.audio && it.path != null } },
      read.data.notes.sumOf { n -> n.sources.count { s -> s.kind == SourceKind.IMAGE && n.attachments.any { it.id == s.id && !it.audio && it.path != null } } },
      read.data.notes.map { it.note.title }, read.precise, read.warnings)
  }

  suspend fun import(
    file: File,
    updateExisting: Boolean = true,
    intoFolderId: String? = null,
    onProgress: (Float, String) -> Unit = { _, _ -> },
  ): PampaBundleImportResult = withContext(Dispatchers.IO) {
    val ctx = currentCoroutineContext()
    val checkpoint = { ctx.ensureActive() }
    onProgress(0f, "Validating bundle")
    val read = PampaBundleArchive.read(file, checkpoint)
    val namespace = if (updateExisting) "origin" else UUID.randomUUID().toString()
    val data = remap(read.data, namespace)
    val staged = mutableListOf<File>()
    val stored = mutableMapOf<Pair<Boolean, String>, String>()
    var committed = false
    try {
      val attachments = data.notes.flatMap { it.attachments }.filter { it.path != null }
      ZipFile(file).use { zip ->
        attachments.forEachIndexed { index, a ->
          checkpoint()
          // Fresh immutable files: failure never overwrites an existing attachment.
          val ext = a.path!!.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
          val target = File(if (a.audio) files.audio else files.sources, "${UUID.randomUUID()}.$ext")
          staged += target
          target.outputStream().use { out -> zip.getInputStream(zip.getEntry(read.root + a.path)).use { PampaBundleArchive.verify(it, a.bytes, a.sha256, checkpoint, out) } }
          stored[a.audio to a.id] = target.name
          onProgress(0.1f + 0.6f * (index + 1) / attachments.size.coerceAtLeast(1), "Staging attachments")
        }
      }
      checkpoint()
      // Keep the commit result and cleanup decision in one cancellation-safe boundary.
      withContext(NonCancellable) {
        val result = db.withTransaction {
          persist(data, intoFolderId, stored, checkpoint) { p, title -> onProgress(0.7f + 0.3f * p, title) }
        }
        committed = true
        result.copy(warnings = read.warnings)
      }
    } finally {
      if (!committed) staged.forEach { it.delete() }
    }
  }

  internal suspend fun persist(
    data: PampaBundleData, intoFolderId: String?, stored: Map<Pair<Boolean, String>, String>,
    checkpoint: () -> Unit = {}, onProgress: (Float, String) -> Unit = { _, _ -> },
  ): PampaBundleImportResult {
    require(intoFolderId == null || db.folders().get(intoFolderId) != null) { "Destination folder missing" }
    require(intoFolderId == null || data.folders.none { it.id == intoFolderId }) { "Destination cannot be inside the imported hierarchy" }
    val pending = data.folders.toMutableList()
    val done = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
      checkpoint()
      val next = pending.firstOrNull { it.parentId == null || it.parentId in done } ?: error("Folder cycle")
      val old = db.folders().get(next.id)
      db.folders().upsert(next.copy(parentId = if (next.parentId == null) intoFolderId else next.parentId,
        updatedAt = maxOf(next.updatedAt, old?.updatedAt ?: 0)))
      done += next.id; pending.remove(next)
    }
    var imported = 0; var updated = 0
    data.notes.forEachIndexed { index, n ->
      checkpoint()
      val oldNote = db.notes().get(n.note.id)
      if (oldNote == null) imported++ else updated++
      db.notes().upsert(n.note)
      // Merge tags and descendants: local-only work must survive subsequent exports.
      db.tags().insertAll(n.tags.distinct().map { NoteTagEntity(n.note.id, it) })
      n.sessions.forEach { s ->
        val old = db.sessions().get(s.id)
        require(old == null || old.noteId == n.note.id) { "Session identity conflict" }
        db.sessions().upsert(s.copy(activeTranscriptId = s.activeTranscriptId ?: old?.activeTranscriptId,
          transcribingOn = old?.transcribingOn, transcribingSince = old?.transcribingSince,
          voiceNames = VoiceNames.encode(VoiceNames.decode(old?.voiceNames) + VoiceNames.decode(s.voiceNames))))
      }
      n.parts.forEach { p ->
        val old = db.audioParts().get(p.id)
        require(old == null || old.sessionId == p.sessionId) { "Audio identity conflict" }
        val name = stored[true to p.id]
        // An omitted attachment never replaces the existing local file or its metadata.
        db.audioParts().upsert(if (name == null && old != null) old.copy(position = p.position) else p.copy(fileName = name ?: "missing-${p.id}", archivedAt = 0))
      }
      n.sources.forEach { s ->
        val old = db.sources().get(s.id)
        require(old == null || old.noteId == n.note.id) { "Source identity conflict" }
        val name = stored[false to s.id]
        db.sources().upsert(if (name == null && old != null) old else s.copy(storedFileName = name, archivedAt = 0))
      }
      n.transcripts.forEach { t ->
        checkpoint()
        val old = db.transcripts().get(t.id)
        require(old == null || old.sessionId == t.sessionId) { "Transcript identity conflict" }
        val incomingParts = n.parts.mapTo(HashSet()) { it.id }
        val localSegments = db.segments().byTranscript(t.id).filter { it.partId !in incomingParts }
        val incomingSegments = n.segments.filter { it.transcriptId == t.id }
        val combined = (incomingSegments + localSegments).sortedBy { it.sessionStartMs }
        val row = if (t.kind == TranscriptKind.RAW && localSegments.isNotEmpty()) {
          val text = combined.joinToString(" ") { it.text }
          t.copy(text = text, wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() })
        } else t
        db.transcripts().upsert(row)
        // Keep segments of local-only parts even when they share the imported RAW version.
        db.segments().deleteByTranscript(t.id)
        db.segments().insertAll(combined.map { it.copy(id = 0) })
      }
      onProgress((index + 1f) / data.notes.size.coerceAtLeast(1), n.note.title)
    }
    checkpoint()
    return PampaBundleImportResult(data.notes.map { it.note.id }, imported, updated, data.notes.sumOf { n -> n.attachments.count { it.audio && it.path != null } })
  }

  companion object {
    internal fun originId(id: String): String = mapped("origin", "note", id)
    private fun mapped(namespace: String, kind: String, id: String): String = UUID.nameUUIDFromBytes("pampa-bundle:$namespace:$kind:$id".toByteArray(Charsets.UTF_8)).toString()

    internal fun remap(data: PampaBundleData, namespace: String): PampaBundleData {
      fun id(kind: String, value: String) = mapped(namespace, kind, value)
      return data.copy(folders = data.folders.map { it.copy(id = id("folder", it.id), parentId = it.parentId?.let { p -> id("folder", p) }) }, notes = data.notes.map { n ->
        n.copy(note = n.note.copy(id = id("note", n.note.id), folderId = id("folder", n.note.folderId)),
          sessions = n.sessions.map { s -> s.copy(id = id("session", s.id), noteId = id("note", s.noteId), activeTranscriptId = s.activeTranscriptId?.let { id("transcript", it) }, transcribingOn = null, transcribingSince = null,
            voiceNames = VoiceNames.encode(VoiceNames.decode(s.voiceNames).mapKeys { (key, _) -> id("part", key.substringBefore('|')) + "|" + key.substringAfter('|') })) },
          parts = n.parts.map { it.copy(id = id("part", it.id), sessionId = id("session", it.sessionId), fileName = "", archivedAt = 0) },
          transcripts = n.transcripts.map { it.copy(id = id("transcript", it.id), sessionId = id("session", it.sessionId), parentId = it.parentId?.let { p -> id("transcript", p) }) },
          segments = n.segments.map { it.copy(id = 0, transcriptId = id("transcript", it.transcriptId), partId = id("part", it.partId)) },
          sources = n.sources.map { it.copy(id = id("source", it.id), noteId = id("note", it.noteId), storedFileName = null, archivedAt = 0, derivedFromId = it.derivedFromId?.let { p -> id("source", p) }) },
          attachments = n.attachments.map { it.copy(id = id(if (it.audio) "part" else "source", it.id)) })
      })
    }
  }
}
