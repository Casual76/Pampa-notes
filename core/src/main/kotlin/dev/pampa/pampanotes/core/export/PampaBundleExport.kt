package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.io.File
import java.security.MessageDigest

internal object PampaBundleExport {
  fun build(set: ExportSet, layout: BundleLayout, options: ExportOptions, audio: File, sources: File, checkpoint: () -> Unit): PampaBundleData {
    val folders = set.folders.associateBy { it.id }.toMutableMap()
    val notes = set.notes.map { item ->
      val n = item.note
      if (n.folderId !in folders) folders[n.folderId] = FolderEntity(n.folderId, item.folderName, createdAt = n.createdAt, updatedAt = n.updatedAt,
        kind = if (set.personal) FolderEntity.KIND_PERSONAL else FolderEntity.KIND_SCHOOL)
      val nativeGraph = item.native ?: PampaBundleNote(
        note = n, tags = item.tags,
        sessions = item.sessions.map { SessionEntity(it.id, n.id, it.title, it.date, it.number - 1, it.transcript?.id, n.createdAt, n.updatedAt, voiceNames = VoiceNames.encode(it.voiceNames)) },
        parts = item.sessions.flatMap { session -> session.parts.mapIndexed { index, p -> AudioPartEntity(p.id, session.id, index, p.fileName, p.originalName, "audio/mp4", p.sizeBytes, p.durationMs, "", n.createdAt,
          recordedAtEpochMs = p.origin?.epochMs, recordedAtLocal = p.origin?.local,
          recordedAtZoneId = p.origin?.zoneId, recordedAtSource = p.origin?.source, recordedAtPrecision = p.origin?.precision) } },
        transcripts = item.sessions.flatMap { listOfNotNull(it.raw, it.transcript) }.distinctBy { it.id },
        segments = item.sessions.flatMap { it.segments },
        sources = item.sources.map { SourceEntity(it.id.ifBlank { "${n.id}:source:${it.originalName}" }, n.id, it.kind, it.originalName, "application/octet-stream", it.sizeBytes, it.sha256, it.storedFileName, it.extractedChars, it.status, importedAt = n.createdAt) } +
          item.handwriting.map { SourceEntity(it.id.ifBlank { "${n.id}:page:${it.page}" }, n.id, SourceKind.IMAGE, "Page ${it.page}", "image/png", it.sizeBytes, "", it.storedFileName, importedAt = n.createdAt, derivedFromId = item.sources.firstOrNull()?.id) },
      )
      val sessionIds = item.sessions.mapTo(HashSet()) { it.id }
      val byTranscript = nativeGraph.transcripts.filter { it.sessionId in sessionIds }.associateBy { it.id }
      val chosen = item.sessions.associate { session ->
        val requested = if (options.transcript == TranscriptChoice.RAW) session.raw else session.transcript
        val selected = requested?.let { byTranscript[it.id] }
        require(selected == null || selected.sessionId == session.id) { "Selected transcript belongs to another session" }
        if (selected?.kind == TranscriptKind.REFINED && selected.parentId != null) {
          val parent = byTranscript[selected.parentId]
          require(parent?.kind == TranscriptKind.RAW && parent.sessionId == selected.sessionId) { "Selected refinement has no valid RAW parent" }
        }
        session.id to selected
      }
      val includedIds = chosen.values.filterNotNull().flatMap { t -> listOfNotNull(t.id, t.parentId) }.toSet()
      // Scope only the selected RAW/refinement parent. Unselected histories must not change
      // selection or prevent exporting a valid selected version because their metadata is opaque.
      val native = ExportSessionSelection.native(nativeGraph.copy(transcripts = nativeGraph.transcripts.filter { it.id in includedIds }),
        sessionIds, retainOriginals = item.selectedSessionIds == null)
      item.sessions.forEach { session ->
        val raw = session.raw?.let { row -> native.transcripts.find { it.id == row.id } }
        require(raw == null || raw.text == session.raw?.text) { "Export text must consume the scoped RAW projection" }
        if (chosen[session.id]?.kind == TranscriptKind.RAW) {
          require(native.transcripts.single { it.id == chosen.getValue(session.id)!!.id }.text ==
            (if (options.transcript == TranscriptChoice.RAW) session.raw else session.transcript)?.text) { "Export text and native RAW projection disagree" }
        }
      }
      val paths = layout.of(item)
      fun attachment(id: String, isAudio: Boolean, name: String?, path: String?, enabled: Boolean): PampaBundleAttachment {
        val file = name?.let { File(if (isAudio) audio else sources, it) }
        if (!enabled || path == null || file?.isFile != true) return PampaBundleAttachment(id, isAudio)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return PampaBundleAttachment(id, isAudio, path, file.length(), digest.digest().joinToString("") { "%02x".format(it) })
      }
      val attachments = native.parts.map { attachment(it.id, true, it.fileName, paths.audio[it.id], options.includeAudio) } + native.sources.map { source ->
          val page = item.handwriting.indexOfFirst { it.id == source.id || it.storedFileName == source.storedFileName }
          val original = item.sources.indexOfFirst { it.id == source.id || it.storedFileName == source.storedFileName }
          attachment(source.id, false, source.storedFileName, if (page >= 0) paths.images[page] else paths.sources.getOrNull(original), page >= 0 || options.includeSources)
        }
      // Hashing bytes must never certify old timing evidence against a replacement file.
      val portableParts = native.parts.map { row -> val a = attachments.first { it.audio && it.id == row.id }; row.copy(archivedAt = 0, sizeBytes = if (a.path != null) a.bytes else row.sizeBytes, sha256 = a.sha256 ?: row.sha256) }
      native.transcripts.filter { it.kind == TranscriptKind.RAW }.forEach { raw ->
        require(dev.pampa.pampanotes.core.alignment.CloudSources.validatePortable(raw, portableParts.filter { it.sessionId == raw.sessionId })) { "Portable cloud source conflicts with exported audio" }
      }
      native.copy(
        note = native.note.copy(bundleBodyBase = null, bundleNativeAdopted = null),
        sessions = native.sessions.map { it.copy(activeTranscriptId = chosen[it.id]?.id, transcribingOn = null, transcribingSince = null) },
        transcripts = native.transcripts.filter { it.id in includedIds },
        segments = native.segments.filter { it.transcriptId in includedIds },
        parts = portableParts,
        sources = native.sources.map { row -> val a = attachments.first { !it.audio && it.id == row.id }; row.copy(archivedAt = 0, sizeBytes = if (a.path != null) a.bytes else row.sizeBytes, sha256 = a.sha256 ?: row.sha256) },
        attachments = attachments,
      )
    }
    // Export only ancestors of selected notes, never unrelated private folders.
    val used = mutableSetOf<String>()
    fun visit(id: String) { if (used.add(id)) folders[id]?.parentId?.let(::visit) }
    notes.forEach { visit(it.note.folderId) }
    return PampaBundleData(folders = folders.values.filter { it.id in used }, notes = notes)
  }
}
