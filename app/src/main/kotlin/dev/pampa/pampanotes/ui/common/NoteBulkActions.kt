package dev.pampa.pampanotes.ui.common

import dev.pampa.pampanotes.core.repo.NoteRepository
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Quello che la selezione multipla fa a un gruppo di note, uguale da ogni elenco (cartella, home,
 * Registrazioni): trascrivere quelle da fare ed eliminarle. Stava copiato in ogni ViewModel.
 */
class NoteBulkActions @Inject constructor(
  private val notes: NoteRepository,
  private val sessions: SessionRepository,
  private val transcription: TranscriptionRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
) {
  /**
   * Le sessioni con audio e senza trascrizione delle note date vanno in coda, col servizio delle
   * impostazioni. Quelle che un altro dispositivo sta trascrivendo, o gia' in coda qui, le salta
   * `enqueue`.
   */
  suspend fun transcribePending(noteIds: Collection<String>) {
    val provider = settingsStore.current().transcriptionProvider
    var any = false
    noteIds.forEach { noteId ->
      sessions.byNote(noteId)
        .filter { it.parts.isNotEmpty() && it.session.activeTranscriptId == null }
        .forEach { if (transcription.enqueue(it.session.id, provider) != null) any = true }
    }
    if (any) scheduler.kick(provider.id)
  }

  /** Elimina le note, fino in fondo anche se la pagina che l'ha chiesto se ne va nel frattempo. */
  suspend fun delete(noteIds: Collection<String>) {
    withContext(NonCancellable) { noteIds.forEach { notes.delete(it) } }
  }
}
