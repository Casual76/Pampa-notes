package dev.pampa.pampanotes.core.importing

import android.media.MediaMetadataRetriever
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.model.Ids
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dove va a finire un audio che si importa.
 *
 * E' la distinzione che questa app esiste per fare: una registrazione interrotta e ripresa dopo
 * dieci secondi e' la **stessa lezione** e deve diventare una trascrizione sola; una registrazione
 * di un altro giorno e' un'altra cosa. Nessuna euristica puo' indovinarlo — due file a due minuti
 * di distanza possono essere entrambe le cose — quindi lo chiede, con la risposta piu' probabile
 * gia' scelta.
 */
sealed interface AudioPlacement {
  /** Una sessione nuova: il caso normale, e il default quando la nota non ne ha ancora. */
  data class NewSession(val date: String = Dates.today(), val title: String = "") : AudioPlacement

  /** In coda a una sessione che c'e' gia': la registrazione che riprende dopo l'interruzione. */
  data class Append(val sessionId: String) : AudioPlacement
}

@Singleton
class AudioImporter @Inject constructor(
  private val files: AppFiles,
  private val sessions: SessionDao,
  private val parts: AudioPartDao,
) {

  /**
   * La durata in millisecondi, o 0 se il file non si legge.
   *
   * Vale la pena sapersela prima di importare: e' il numero che dice all'utente se ha scelto la
   * registrazione giusta, ed e' quello su cui si calcola in quanti pezzi andra' tagliata.
   */
  fun probeDuration(file: File): Long = runCatching {
    MediaMetadataRetriever().use { retriever ->
      retriever.setDataSource(file.absolutePath)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    }
  }.getOrDefault(0L)

  suspend fun importAll(
    candidates: List<ImportCandidate>,
    noteId: String,
    placement: AudioPlacement,
  ): List<ImportedItem> = withContext(Dispatchers.IO) {
    if (candidates.isEmpty()) return@withContext emptyList()

    // Accodare vale solo dentro la stessa nota. Una sessione di un'altra nota — rimasta scelta nel
    // wizard dopo aver cambiato destinazione — farebbe finire la registrazione in una nota diversa
    // da quella che si vede: la si rifiuta, e l'audio entra in una sessione nuova della nota giusta.
    val sessionId = when (placement) {
      is AudioPlacement.Append ->
        placement.sessionId.takeIf { sessions.get(it)?.noteId == noteId }
          ?: createSession(noteId, AudioPlacement.NewSession())
      is AudioPlacement.NewSession -> createSession(noteId, placement)
    }

    val results = mutableListOf<ImportedItem>()
    var position = parts.nextPosition(sessionId)

    // In ordine di nome: un registratore numera i file, e "parte 2" viene dopo "parte 1" anche
    // quando il selettore li consegna al contrario.
    candidates.sortedBy { it.displayName.lowercase() }.forEach { candidate ->
      val temp = candidate.file
      if (temp == null || !temp.exists()) {
        results += ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.FAILED, summary = ImportSummary.FileUnavailable)
        return@forEach
      }
      val partId = Ids.newId()
      val fileName = files.newAudioName(partId, candidate.displayName, candidate.mime)
      val stored = files.audioFile(fileName)
      runCatching {
        temp.copyTo(stored, overwrite = true)
        temp.delete()
        val duration = candidate.durationMs.takeIf { it > 0 } ?: probeDuration(stored)
        parts.upsert(
          AudioPartEntity(
            id = partId,
            sessionId = sessionId,
            position = position++,
            fileName = fileName,
            originalName = candidate.displayName,
            mime = candidate.mime,
            sizeBytes = candidate.sizeBytes,
            durationMs = duration,
            sha256 = candidate.sha256,
            createdAt = System.currentTimeMillis(),
          ),
        )
        results += ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.OK)
      }.onFailure { error ->
        stored.delete()
        results += ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.FAILED, error.message)
      }
    }

    sessions.get(sessionId)?.let { sessions.upsert(it.copy(updatedAt = System.currentTimeMillis())) }
    results
  }

  private suspend fun createSession(noteId: String, placement: AudioPlacement.NewSession): String {
    val now = System.currentTimeMillis()
    val session = SessionEntity(
      id = Ids.newId(),
      noteId = noteId,
      title = placement.title.trim(),
      date = placement.date,
      position = sessions.nextPosition(noteId),
      createdAt = now,
      updatedAt = now,
    )
    sessions.upsert(session)
    return session.id
  }
}
