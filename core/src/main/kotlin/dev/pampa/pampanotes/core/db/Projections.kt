package dev.pampa.pampanotes.core.db

import androidx.room.Embedded
import androidx.room.Relation

/** Una cartella con quello che la lista deve dire di lei senza aprire niente. */
data class FolderRow(
  @Embedded val folder: FolderEntity,
  val noteCount: Int,
  val childCount: Int,
)

/** Una nota nella lista di una cartella: i numeri che stanno nel badge e nel meta. */
data class NoteRow(
  @Embedded val note: NoteEntity,
  val sessionCount: Int,
  val audioCount: Int,
  val audioDurationMs: Long,
  val sourceCount: Int,
  /** Sessioni con audio e senza una trascrizione scelta: quello che "da trascrivere" conta. */
  val untranscribedSessions: Int,
)

data class SessionWithParts(
  @Embedded val session: SessionEntity,
  @Relation(parentColumn = "id", entityColumn = "sessionId")
  val parts: List<AudioPartEntity>,
) {
  val partsSorted: List<AudioPartEntity> get() = parts.sortedBy { it.position }
  val durationMs: Long get() = parts.sumOf { it.durationMs }
}

data class NoteHit(
  val noteId: String,
  val snippet: String,
)

data class TranscriptHit(
  val transcriptId: String,
  val sessionId: String,
  val noteId: String,
  val snippet: String,
)

/** Il totale di una categoria di file, per la pagina Archiviazione. */
data class SizeTotal(
  val count: Int,
  val bytes: Long,
)
