package dev.pampa.pampanotes.ui.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SessionWithParts
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.WorkScheduler
import dev.pampa.pampanotes.core.repo.NoteRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.files.AppFiles
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

enum class NoteTab { TEXT, AUDIO, SOURCES }

data class NoteUiState(
  val note: NoteEntity? = null,
  /** La materia: e' quella che colora la schermata. */
  val folder: FolderEntity? = null,
  val folderPath: String = "",
  val tags: List<String> = emptyList(),
  val sessions: List<SessionWithParts> = emptyList(),
  val sources: List<SourceEntity> = emptyList(),
  /** I lavori attivi di questa nota, per sessione: la riga mostra a che punto sono. */
  val activeJobs: Map<String, JobEntity> = emptyMap(),
  /** La trascrizione mostrata di ogni sessione. */
  val transcripts: Map<String, TranscriptEntity> = emptyMap(),
  val loading: Boolean = true,
) {
  val audioDurationMs: Long get() = sessions.sumOf { it.durationMs }
  val partCount: Int get() = sessions.sumOf { it.parts.size }
}

@HiltViewModel
class NoteViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val notes: NoteRepository,
  private val folders: FolderRepository,
  private val sessionDao: SessionDao,
  sources: SourceDao,
  private val transcriptDao: TranscriptDao,
  private val transcription: TranscriptionRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
  private val files: AppFiles,
) : ViewModel() {

  private val noteId: String = savedStateHandle.get<String>("noteId").orEmpty()
  private val folderPath = MutableStateFlow("")

  @OptIn(ExperimentalCoroutinesApi::class)
  private val folderFlow: Flow<FolderEntity?> = notes.observe(noteId).flatMapLatest { note ->
    note?.let { folders.observe(it.folderId) } ?: flowOf(null)
  }

  /**
   * Le trascrizioni mostrate, una per sessione.
   *
   * Ricaricate quando le sessioni cambiano — cioe' anche quando un lavoro ne scrive una nuova e
   * aggiorna `activeTranscriptId`, che e' il segnale con cui la schermata si accorge che il testo
   * e' arrivato senza dover osservare ogni trascrizione una per una.
   */
  private val transcriptTexts: Flow<Map<String, TranscriptEntity>> = sessionDao.observeByNote(noteId)
    .map { sessions ->
      sessions.mapNotNull { session ->
        val id = session.session.activeTranscriptId ?: return@mapNotNull null
        transcriptDao.get(id)?.let { session.session.id to it }
      }.toMap()
    }

  val uiState: StateFlow<NoteUiState> = combine(
    notes.observe(noteId),
    notes.observeTags(noteId),
    sessionDao.observeByNote(noteId),
    sources.observeByNote(noteId),
    folderPath,
    transcription.observeActive(),
    transcriptTexts,
    folderFlow,
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    NoteUiState(
      note = values[0] as NoteEntity?,
      folder = values[7] as FolderEntity?,
      tags = values[1] as List<String>,
      sessions = values[2] as List<SessionWithParts>,
      sources = values[3] as List<SourceEntity>,
      folderPath = values[4] as String,
      activeJobs = (values[5] as List<JobEntity>).associateBy { it.sessionId },
      transcripts = values[6] as Map<String, TranscriptEntity>,
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteUiState())


  init {
    viewModelScope.launch {
      notes.get(noteId)?.let { folderPath.value = folders.pathString(it.folderId) }
    }
  }

  fun rename(title: String) = viewModelScope.launch { notes.setTitle(noteId, title) }

  fun setTags(values: List<String>) = viewModelScope.launch { notes.setTags(noteId, values) }

  fun togglePinned() = viewModelScope.launch {
    val current = notes.get(noteId) ?: return@launch
    notes.setPinned(noteId, !current.pinned)
  }

  fun delete(onDone: () -> Unit) = viewModelScope.launch {
    notes.delete(noteId)
    onDone()
  }

  /**
   * Mette in coda la trascrizione di una sessione.
   *
   * Il provider e' quello scelto nelle impostazioni: chiederlo ogni volta sarebbe una domanda a cui
   * la risposta e' sempre la stessa.
   */
  fun transcribe(sessionId: String) = viewModelScope.launch {
    val provider = settingsStore.current().preferredProvider
    transcription.enqueue(sessionId, provider)
    scheduler.kick(provider.id)
  }

  fun cancelJob(jobId: String) = viewModelScope.launch { transcription.requestCancel(jobId) }

  /** L'originale conservato di una fonte, da riaprire; null per il testo incollato, che un file non l'ha. */
  fun sourceFile(source: SourceEntity): File? = source.storedFileName?.let(files::sourceFile)
}
