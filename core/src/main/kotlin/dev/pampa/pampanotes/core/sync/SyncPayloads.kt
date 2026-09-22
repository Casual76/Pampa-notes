package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.ExportPresetDao
import dev.pampa.pampanotes.core.db.ExportPresetEntity
import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.NoteTagDao
import dev.pampa.pampanotes.core.db.SegmentDao
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.TranscriptEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement

/** Una riga locale codificata per il filo: il payload, la sua impronta, il suo tempo, e i segmenti se e' una trascrizione. */
data class Encoded(
  val payload: JsonElement,
  val hash: String,
  val updatedAt: Long,
  val segments: List<SegmentEntity>? = null,
)

/**
 * Da una riga del database al suo payload, e ritorno. Lo stesso codice per il push e per il
 * confronto in ricezione: l'impronta di una riga deve venire uguale da tutte e due le parti, e
 * l'unico modo sicuro e' calcolarla in un posto solo.
 */
@Singleton
class SyncPayloads @Inject constructor(
  private val folders: FolderDao,
  private val notes: NoteDao,
  private val tags: NoteTagDao,
  private val sessions: SessionDao,
  private val audioParts: AudioPartDao,
  private val transcripts: TranscriptDao,
  private val segments: SegmentDao,
  private val sources: SourceDao,
  private val presets: ExportPresetDao,
) {
  private val json = SyncCodec.json

  /** Null quando la riga non c'e' (piu'). */
  suspend fun encode(table: String, id: String): Encoded? = when (table) {
    "folders" -> folders.get(id)?.let { encode(FolderEntity.serializer(), it, it.updatedAt) }
    "notes" -> notes.get(id)?.let { note ->
      encode(NotePayload.serializer(), NotePayload(note, tags.tags(id)), note.updatedAt)
    }
    "sessions" -> sessions.get(id)?.let { encode(SessionEntity.serializer(), it, it.updatedAt) }
    "audio_parts" -> audioParts.get(id)?.let { encode(AudioPartEntity.serializer(), it, it.createdAt) }
    "transcripts" -> transcripts.get(id)?.let { t ->
      // I segmenti viaggiano accanto al payload, non dentro: cosi' l'impronta della trascrizione
      // resta quella del suo testo, e il server puo' tenerli a blocchi.
      val parts = segments.byTranscript(id).map { it.copy(id = 0) }
      encode(TranscriptEntity.serializer(), t, t.createdAt).copy(segments = parts)
    }
    "sources" -> sources.get(id)?.let { encode(SourceEntity.serializer(), it, it.importedAt) }
    "export_presets" -> presets.get(id)?.let { encode(ExportPresetEntity.serializer(), it, it.lastUsedAt) }
    else -> null
  }

  private fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T, updatedAt: Long): Encoded {
    val element = json.encodeToJsonElement(serializer, value)
    return Encoded(payload = element, hash = SyncCodec.hash(element), updatedAt = updatedAt)
  }
}
