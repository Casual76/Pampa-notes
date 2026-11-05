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
class PampaBundleImporter @Inject constructor(
  private val db: PampaDatabase,
  private val files: AppFiles,
  private val audioProbe: PampaBundleAudioProbe = PampaBundleAudioProbe(),
) {
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
    onProgress: (Float, PampaBundleProgress) -> Unit = { _, _ -> },
  ): PampaBundleImportResult = withContext(Dispatchers.IO) {
    val ctx = currentCoroutineContext()
    val checkpoint = { ctx.ensureActive() }
    onProgress(0f, PampaBundleProgress(PampaBundlePhase.VALIDATING))
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
          onProgress(0.1f + 0.6f * (index + 1) / attachments.size.coerceAtLeast(1), PampaBundleProgress(PampaBundlePhase.STAGING_ATTACHMENTS))
        }
      }
      checkpoint()
      val measured = completeAudioMetadata(data, stored, checkpoint)
      // Keep the commit result and cleanup decision in one cancellation-safe boundary.
      withContext(NonCancellable) {
        val result = db.withTransaction {
          persist(measured, intoFolderId, stored, checkpoint) { p, title -> onProgress(0.7f + 0.3f * p, title) }
        }
        committed = true
        result.copy(warnings = read.warnings)
      }
    } finally {
      if (!committed) staged.forEach { it.delete() }
    }
  }

  /** Legacy files have no duration metadata. Probe only verified, included files, before Room
   * writes. Keep known native durations so exact exported timing is never replaced by a rounded
   * container estimate. PampaBundleMerge rebases segments against the completed part sequence. */
  internal fun completeAudioMetadata(
    data: PampaBundleData,
    stored: Map<Pair<Boolean, String>, String>,
    checkpoint: () -> Unit = {},
  ): PampaBundleData = data.copy(notes = data.notes.map { note ->
    note.copy(parts = note.parts.map { part ->
      checkpoint()
      val name = stored[true to part.id]
      if (name == null || part.durationMs > 0) part else {
        val info = audioProbe.probe(files.audioFile(name))
        checkpoint()
        require(info.durationMs > 0) { "Cannot determine included audio duration: ${part.id}" }
        part.copy(durationMs = info.durationMs, mime = info.mime?.takeIf { it.startsWith("audio/") || it == "application/ogg" } ?: "application/octet-stream")
      }
    })
  })

  internal suspend fun persist(
    data: PampaBundleData, intoFolderId: String?, stored: Map<Pair<Boolean, String>, String>,
    checkpoint: () -> Unit = {}, onProgress: (Float, PampaBundleProgress) -> Unit = { _, _ -> },
  ): PampaBundleImportResult {
    require(intoFolderId == null || db.folders().get(intoFolderId) != null) { "Destination folder missing" }
    val importedFolders = data.folders.mapTo(HashSet()) { it.id }
    val ancestors = mutableSetOf<String>()
    var ancestor = intoFolderId
    while (ancestor != null) {
      require(ancestor !in importedFolders && ancestors.add(ancestor)) { "Destination cannot be inside the imported hierarchy" }
      ancestor = db.folders().get(ancestor)?.parentId
    }
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
      val oldSessions = db.sessions().plainByNote(n.note.id)
      val oldParts = oldSessions.flatMap { db.audioParts().bySession(it.id) }
      val oldTranscripts = oldSessions.flatMap { db.transcripts().bySession(it.id) }
      val local = oldNote?.let { PampaBundleNote(it, db.tags().tags(it.id), oldSessions, oldParts,
        oldTranscripts, oldTranscripts.flatMap { t -> db.segments().byTranscript(t.id) }, db.sources().byNote(it.id)) }
      suspend fun sameNote(sessionId: String): Boolean = db.sessions().get(sessionId)?.noteId == n.note.id
      n.sessions.forEach { s -> require(db.sessions().get(s.id)?.noteId.let { it == null || it == n.note.id }) { "Session belongs to another note" } }
      val incomingParts = n.parts.map { part ->
        val old = db.audioParts().get(part.id)
        require(old == null || sameNote(old.sessionId)) { "Audio belongs to another note" }
        val name = stored[true to part.id]
        if (name == null && old != null) {
          require(part.sha256.isBlank() || old.sha256.isBlank() || part.sha256.equals(old.sha256, true)) { "Omitted audio conflicts with local content" }
          old.copy(sessionId = part.sessionId, position = part.position)
        } else part.copy(fileName = name ?: "missing-${UUID.nameUUIDFromBytes(part.id.toByteArray(Charsets.UTF_8))}", archivedAt = 0)
      }
      val incomingSources = n.sources.map { source ->
        val old = db.sources().get(source.id)
        require(old == null || old.noteId == n.note.id) { "Source belongs to another note" }
        val name = stored[false to source.id]
        if (name == null && old != null) old.copy(derivedFromId = source.derivedFromId)
        else source.copy(storedFileName = name, archivedAt = 0)
      }
      val merged = PampaBundleMerge.merge(local, n.copy(parts = incomingParts, sources = incomingSources))
      // Includes synthesized RAW histories; reject any global ID collision before graph writes.
      merged.transcripts.forEach { t ->
        val old = db.transcripts().get(t.id)
        require(old == null || sameNote(old.sessionId)) { "Transcript belongs to another note" }
        require(old == null || old.kind == t.kind) { "Transcript kind identity conflict" }
      }
      db.notes().upsert(merged.note.copy(updatedAt = maxOf(merged.note.updatedAt, oldNote?.updatedAt ?: 0)))
      db.tags().insertAll(merged.tags.map { NoteTagEntity(n.note.id, it) })
      merged.sessions.forEach { db.sessions().upsert(it) }
      merged.parts.forEach { db.audioParts().upsert(it) }
      merged.sources.forEach { db.sources().upsert(it) }
      merged.transcripts.forEach { t ->
        checkpoint()
        db.transcripts().upsert(t)
        db.segments().deleteByTranscript(t.id)
        db.segments().insertAll(merged.segments.filter { it.transcriptId == t.id }.map { it.copy(id = 0) })
      }
      onProgress((index + 1f) / data.notes.size.coerceAtLeast(1), PampaBundleProgress(PampaBundlePhase.IMPORTING_NOTES, n.note.title))
    }
    checkpoint()
    return PampaBundleImportResult(data.notes.map { it.note.id }, imported, updated, data.notes.sumOf { n -> n.attachments.count { it.audio && it.path != null } })
  }

  companion object {
    internal fun originId(id: String): String = id
    private fun mapped(namespace: String, kind: String, id: String): String = UUID.nameUUIDFromBytes("pampa-bundle:$namespace:$kind:$id".toByteArray(Charsets.UTF_8)).toString()

    internal fun remap(data: PampaBundleData, namespace: String): PampaBundleData {
      fun id(kind: String, value: String) = if (namespace == "origin") value else mapped(namespace, kind, value)
      return data.copy(folders = data.folders.map { it.copy(id = id("folder", it.id), parentId = it.parentId?.let { p -> id("folder", p) }) }, notes = data.notes.map { n ->
        n.copy(note = n.note.copy(id = id("note", n.note.id), folderId = id("folder", n.note.folderId), bundleBodyBase = null),
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
