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
          persist(measured, intoFolderId, stored, checkpoint, publicLegacy = PampaBundleWarning.LEGACY_FORMAT in read.warnings) { p, title -> onProgress(0.7f + 0.3f * p, title) }
        }
        committed = true
        // Reconciliation can reuse an existing canonical file instead of a newly staged legacy copy.
        runCatching {
          val usedAudio = db.audioParts().all().mapTo(HashSet()) { it.fileName }
          val usedSources = db.sources().all().mapNotNullTo(HashSet()) { it.storedFileName }
          staged.filter { if (it.parentFile == files.audio) it.name !in usedAudio else it.name !in usedSources }.forEach { it.delete() }
        }.onFailure { android.util.Log.w("PampaNotes", "Unused bundle staging cleanup", it) }
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
    checkpoint: () -> Unit = {}, publicLegacy: Boolean? = null,
    onProgress: (Float, PampaBundleProgress) -> Unit = { _, _ -> },
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
      // Public folder IDs are synthesized from their original paths. A native adoption or a
      // local rename/move may retain those IDs; an older public path cannot overwrite that work.
      if (old == null || publicLegacy != true) db.folders().upsert(next.copy(parentId = if (next.parentId == null) intoFolderId else next.parentId,
        updatedAt = maxOf(next.updatedAt, old?.updatedAt ?: 0)))
      done += next.id; pending.remove(next)
    }
    val cleanupFolders = data.folders.filter { PampaBundleLegacyReconcile.isFolder(it.id) }.mapTo(HashSet()) { it.id }
    var imported = 0; var updated = 0
    data.notes.forEachIndexed { index, n ->
      checkpoint()
      val oldNote = db.notes().get(n.note.id)
      if (oldNote == null) imported++ else updated++
      var previousFolder = oldNote?.folderId?.let { db.folders().get(it) }
      while (previousFolder != null && PampaBundleLegacyReconcile.isFolder(previousFolder.id)) {
        cleanupFolders += previousFolder.id
        previousFolder = previousFolder.parentId?.let { db.folders().get(it) }
      }
      val oldSessions = db.sessions().plainByNote(n.note.id)
      val oldParts = oldSessions.flatMap { db.audioParts().bySession(it.id) }
      val oldTranscripts = oldSessions.flatMap { db.transcripts().bySession(it.id) }
      val originalLocal = oldNote?.let { PampaBundleNote(it, db.tags().tags(it.id), oldSessions, oldParts,
        oldTranscripts, oldTranscripts.flatMap { t -> db.segments().byTranscript(t.id) }, db.sources().byNote(it.id)) }
      val reconciliation = PampaBundleLegacyReconcile.plan(originalLocal, n, publicLegacy)
      val local = reconciliation.local
      val incoming = reconciliation.incoming
      val rekeyedPartSessions = originalLocal?.parts.orEmpty().filter { it.id in reconciliation.parts }.map { it.sessionId }
      (reconciliation.sessions.keys + rekeyedPartSessions + if (reconciliation.legacyUpdate) incoming.sessions.map { it.id } else emptyList()).forEach { oldId ->
        require(db.jobs().activeForSession(oldId) == null) { "Finish the active legacy session job before upgrading" }
      }
      suspend fun sameNote(sessionId: String): Boolean = db.sessions().get(sessionId)?.noteId == n.note.id
      incoming.sessions.forEach { s -> require(db.sessions().get(s.id)?.noteId.let { it == null || it == n.note.id }) { "Session belongs to another note" } }
      val incomingParts = incoming.parts.map { part ->
        val persisted = db.audioParts().get(part.id)
        require(persisted == null || sameNote(persisted.sessionId)) { "Audio belongs to another note" }
        // The rekeyed snapshot owns the content at a destination ID during swaps. The DB still
        // contains the *previous* occupant until all final graph rows have been written.
        val old = if (part.id in reconciliation.parts.values) local?.parts?.firstOrNull { it.id == part.id } ?: persisted
          else persisted ?: local?.parts?.firstOrNull { it.id == part.id }
        val name = if (reconciliation.preserveLocalNote && old != null) null
          else stored[true to (reconciliation.incomingPartKeys[part.id] ?: part.id)]
        if (old != null && PampaBundleLegacyReconcile.isSynthetic(part.id)) {
          require(part.sha256.isNotBlank() && old.sha256.isNotBlank() && part.sha256.equals(old.sha256, true)) { "Marked audio identity conflicts with retained content" }
        }
        val sameAudio = old != null && part.sha256.isNotBlank() && part.sha256.equals(old.sha256, true)
        if (reconciliation.legacyUpdate && old != null && PampaBundleLegacyReconcile.isSynthetic(part.id)) {
          val localFile = files.audioFile(old.fileName).isFile
          old.copy(sessionId = part.sessionId, position = part.position,
            fileName = if (localFile) old.fileName else name ?: old.fileName,
            durationMs = old.durationMs.takeIf { it > 0 } ?: part.durationMs)
        } else if (sameAudio) {
          // Keep local identity/archive metadata even when recovering an evicted blob from staging.
          // Native duration/timing metadata takes precedence over an old MMR rounding estimate.
          part.copy(fileName = if (files.audioFile(old.fileName).isFile) old.fileName else name ?: old.fileName,
            archivedAt = old.archivedAt, originalName = old.originalName,
            durationMs = part.durationMs.takeIf { it > 0 } ?: old.durationMs, sizeBytes = old.sizeBytes)
        } else if (name == null && old != null) {
          require(part.sha256.isBlank() || old.sha256.isBlank() || part.sha256.equals(old.sha256, true)) { "Omitted audio conflicts with local content" }
          old.copy(sessionId = part.sessionId, position = part.position)
        } else part.copy(fileName = name ?: "missing-${UUID.nameUUIDFromBytes(part.id.toByteArray(Charsets.UTF_8))}", archivedAt = 0)
      }
      val incomingSources = incoming.sources.map { source ->
        val persisted = db.sources().get(source.id)
        require(persisted == null || persisted.noteId == n.note.id) { "Source belongs to another note" }
        val old = if (source.id in reconciliation.sources.values) local?.sources?.firstOrNull { it.id == source.id } ?: persisted
          else persisted ?: local?.sources?.firstOrNull { it.id == source.id }
        val name = if (old != null && (reconciliation.preserveLocalNote || source.sha256.isNotBlank() && source.sha256.equals(old.sha256, true) && old.storedFileName?.let { files.sourceFile(it).isFile } == true)) null
          else stored[false to (reconciliation.incomingSourceKeys[source.id] ?: source.id)]
        if (old != null && PampaBundleLegacyReconcile.isSynthetic(source.id)) {
          val placeholder = source.id.startsWith("pampa-legacy-placeholder-") && old.kind == SourceKind.OTHER && source.kind == SourceKind.OTHER &&
            old.sizeBytes == 0L && source.sizeBytes == 0L && old.sha256.isBlank() && source.sha256.isBlank() && old.storedFileName == null
          require(placeholder || old.kind == source.kind && old.sha256.isNotBlank() && source.sha256.isNotBlank() && old.sha256.equals(source.sha256, true)) { "Marked source identity conflicts with retained content" }
        }
        val sameSource = old != null && source.sha256.isNotBlank() && source.sha256.equals(old.sha256, true)
        if (old != null && (name == null || sameSource || reconciliation.legacyUpdate && PampaBundleLegacyReconcile.isSynthetic(source.id))) {
          require(old.kind == source.kind) { "Omitted source kind conflicts with local content" }
          val sameHash = old.sha256.isNotBlank() && source.sha256.isNotBlank() && old.sha256.equals(source.sha256, true)
          require(old.sha256.isBlank() || source.sha256.isBlank() || sameHash) { "Omitted source conflicts with local content" }
          source.sdocxCreatedAtMillis?.let { created ->
            require(source.kind == SourceKind.SDOCX && created > 0) { "Invalid Samsung identity evidence" }
            require(old.sdocxCreatedAtMillis == null || old.sdocxCreatedAtMillis == created) { "Conflicting Samsung identity evidence" }
            require(old.sdocxCreatedAtMillis != null || sameHash) { "Cannot identify omitted Samsung original" }
          }
          old.copy(derivedFromId = if (reconciliation.legacyUpdate && old.derivedFromId != null) old.derivedFromId else source.derivedFromId,
            storedFileName = if (old.storedFileName?.let { files.sourceFile(it).isFile } == true) old.storedFileName else name ?: old.storedFileName,
            sdocxCreatedAtMillis = old.sdocxCreatedAtMillis ?: source.sdocxCreatedAtMillis)
        } else source.copy(storedFileName = name, archivedAt = 0)
      }
      val merged = PampaBundleMerge.merge(local, incoming.copy(parts = incomingParts, sources = incomingSources), reconciliation.preserveLocalNote)
      // Includes fresh identities for retained omitted content as well as incoming children.
      // A deterministic rekey must not overwrite a row belonging to another note.
      merged.sessions.forEach { s -> require(db.sessions().get(s.id)?.noteId.let { it == null || it == n.note.id }) { "Reconciled session belongs to another note" } }
      merged.parts.forEach { part -> require(db.audioParts().get(part.id)?.let { sameNote(it.sessionId) } != false) { "Reconciled audio belongs to another note" } }
      merged.sources.forEach { source -> require(db.sources().get(source.id)?.noteId.let { it == null || it == n.note.id }) { "Reconciled source belongs to another note" } }
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
      // Snapshot references by row ID: sequential sessionId UPDATEs would feed a swapped
      // destination back into the next alias and move both sessions' statistics to one side.
      if (reconciliation.sessions.isNotEmpty()) {
        val jobs = db.jobs().all().filter { it.sessionId in reconciliation.sessions }
        val runs = mutableListOf<Pair<String, String>>()
        db.openHelper.writableDatabase.query("SELECT id, sessionId FROM transcription_runs").use { cursor ->
          while (cursor.moveToNext()) reconciliation.sessions[cursor.getString(1)]?.let { runs += cursor.getString(0) to it }
        }
        jobs.forEach { job -> db.jobs().upsert(job.copy(sessionId = reconciliation.sessions.getValue(job.sessionId))) }
        runs.forEach { (runId, sessionId) ->
          db.openHelper.writableDatabase.execSQL("UPDATE transcription_runs SET sessionId = ? WHERE id = ?", arrayOf(sessionId, runId))
        }
      }
      // An alias key may also be a live destination in a swap/cycle. Retire only absent keys,
      // after children, terminal jobs and statistics have all adopted the simultaneous map.
      val finalParts = merged.parts.mapTo(HashSet()) { it.id }
      val finalSources = merged.sources.mapTo(HashSet()) { it.id }
      val finalSessions = merged.sessions.mapTo(HashSet()) { it.id }
      reconciliation.parts.keys.filterNot { it in finalParts }.forEach { db.audioParts().delete(it) }
      reconciliation.sources.keys.filterNot { it in finalSources }.forEach { db.sources().delete(it) }
      reconciliation.sessions.keys.filterNot { it in finalSessions }.forEach { oldId ->
        require(db.audioParts().bySession(oldId).isEmpty() && db.transcripts().bySession(oldId).isEmpty()) { "Legacy children were not fully adopted" }
        db.sessions().delete(oldId)
      }
      onProgress((index + 1f) / data.notes.size.coerceAtLeast(1), PampaBundleProgress(PampaBundlePhase.IMPORTING_NOTES, n.note.title))
    }
    removeEmptyLegacyFolders(ancestors, cleanupFolders)
    checkpoint()
    return PampaBundleImportResult(data.notes.map { it.note.id }, imported, updated, data.notes.sumOf { n -> n.attachments.count { it.audio && it.path != null } })
  }

  /** Reserved, unedited synthetic folders only; never cascade through notes or children. */
  private suspend fun removeEmptyLegacyFolders(protected: Set<String>, candidates: Set<String>) {
    if (candidates.isEmpty()) return
    do {
      var removed = false
      val folders = db.folders().all().associateBy { it.id }
      folders.values.filter { it.id in candidates && PampaBundleLegacyReconcile.isFolder(it.id) && it.id !in protected &&
        it.tone == null && it.icon == null && it.sortOrder == 0 && it.kind == FolderEntity.KIND_SCHOOL }.forEach { folder ->
        val names = mutableListOf<String>()
        var current: FolderEntity? = folder
        while (current != null && PampaBundleLegacyReconcile.isFolder(current.id)) {
          names += current.name; current = current.parentId?.let { folders[it] }
        }
        val expected = PampaBundleLegacyReconcile.id("folder", "legacy-folder:/" + names.reversed().joinToString("/"))
        if (expected == folder.id && db.notes().byFolder(folder.id).isEmpty() && db.folders().children(folder.id).isEmpty()) {
          db.folders().delete(folder.id); removed = true
        }
      }
    } while (removed)
  }

  companion object {
    internal fun originId(id: String): String = id
    private fun mapped(namespace: String, kind: String, id: String): String = UUID.nameUUIDFromBytes("pampa-bundle:$namespace:$kind:$id".toByteArray(Charsets.UTF_8)).toString()

    internal fun remap(data: PampaBundleData, namespace: String): PampaBundleData {
      fun id(kind: String, value: String) = if (namespace == "origin") value else mapped(namespace, kind, value)
      return data.copy(folders = data.folders.map { it.copy(id = id("folder", it.id), parentId = it.parentId?.let { p -> id("folder", p) }) }, notes = data.notes.map { n ->
        n.copy(note = n.note.copy(id = id("note", n.note.id), folderId = id("folder", n.note.folderId), bundleBodyBase = null, bundleNativeAdopted = null),
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
