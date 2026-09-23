package dev.pampa.pampanotes.ui.note

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.importing.HandwritingPages
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
import dev.pampa.pampanotes.core.repo.SessionRepository
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
  /** Le fonti importate, senza le pagine ricavate da loro: quelle stanno in [handwriting]. */
  val sources: List<SourceEntity> = emptyList(),
  /** Le pagine scritte a mano, come immagini, nell'ordine del quaderno. */
  val handwriting: List<SourceEntity> = emptyList(),
  /** I lavori attivi di questa nota, per sessione: la riga mostra a che punto sono. */
  val activeJobs: Map<String, JobEntity> = emptyMap(),
  /** La trascrizione mostrata di ogni sessione. */
  val transcripts: Map<String, TranscriptEntity> = emptyMap(),
  /** Le fonti con un file conservato che pero' non e' su questo dispositivo, per id. */
  val missingSources: Set<String> = emptySet(),
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
  private val sessionRepository: SessionRepository,
  sources: SourceDao,
  private val transcriptDao: TranscriptDao,
  private val transcription: TranscriptionRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
  private val files: AppFiles,
  private val fetcher: ArchiveFetcher,
  private val handwriting: HandwritingPages,
) : ViewModel() {

  private val noteId: String = savedStateHandle.get<String>("noteId").orEmpty()
  private val folderPath = MutableStateFlow("")
  private val missingSources = MutableStateFlow<Set<String>>(emptySet())

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
    missingSources,
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    NoteUiState(
      note = values[0] as NoteEntity?,
      folder = values[7] as FolderEntity?,
      tags = values[1] as List<String>,
      sessions = values[2] as List<SessionWithParts>,
      sources = (values[3] as List<SourceEntity>).filter { it.derivedFromId == null },
      handwriting = (values[3] as List<SourceEntity>).filter { it.derivedFromId != null }.sortedWith(compareBy({ it.importedAt }, { it.originalName })),
      folderPath = values[4] as String,
      activeJobs = (values[5] as List<JobEntity>).associateBy { it.sessionId },
      transcripts = values[6] as Map<String, TranscriptEntity>,
      missingSources = values[8] as Set<String>,
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteUiState())


  init {
    viewModelScope.launch {
      // Quali originali ci sono davvero: con l'indice in cloud una fonte puo' avere la riga e non il file.
      sources.observeByNote(noteId).distinctUntilChanged().collect { list ->
        missingSources.value = withContext(Dispatchers.IO) { list.filter { fetcher.isMissing(it) }.map { it.id }.toSet() }
      }
    }
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
    val provider = settingsStore.current().transcriptionProvider
    transcription.enqueue(sessionId, provider)
    scheduler.kick(provider.id)
  }

  fun cancelJob(jobId: String) = viewModelScope.launch { transcription.requestCancel(jobId) }

  /**
   * Rifa' da capo le trascrizioni di piu' sessioni. Ognuna sostituira' la sua grezza e le raffinate
   * che ne venivano ([TranscriptionRepository.saveTranscript]); una che ha gia' un lavoro in corso
   * non ne prende un secondo.
   */
  fun retranscribe(sessionIds: Collection<String>) = viewModelScope.launch {
    val provider = settingsStore.current().transcriptionProvider
    sessionIds.forEach { transcription.enqueue(it, provider) }
    scheduler.kick(provider.id)
  }

  fun deleteSessions(sessionIds: Collection<String>) = viewModelScope.launch {
    sessionIds.forEach { sessionRepository.deleteSession(it) }
  }

  /**
   * Rifa' le pagine scritte a mano dai `.sdocx` della nota: per una nota importata prima che l'app
   * sapesse disegnarle, o il cui originale stava solo sul computer quando e' passato il giro unico.
   */
  fun rederiveHandwriting(onDone: (Int) -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
    try {
      val title = notes.get(noteId)?.title.orEmpty()
      onDone(handwriting.rederive(noteId, title))
      if (settingsStore.current().syncEnabled) scheduler.syncNow()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      onError(error.message ?: error::class.java.simpleName)
    }
  }

  /**
   * L'originale conservato di una fonte, da riaprire; niente per il testo incollato, che un file
   * non l'ha. Se il file sta sul computer di casa e non qui, prima si scarica: [onReady] arriva
   * dopo, e [onError] se non si e' riusciti. Un originale che non e' mai stato archiviato non si
   * puo' chiedere a nessuno, e la riga non e' cliccabile.
   */
  fun openSource(source: SourceEntity, onReady: (File) -> Unit, onError: (String) -> Unit) {
    val file = source.storedFileName?.let(files::sourceFile) ?: return
    if (file.exists()) {
      onReady(file)
      return
    }
    if (source.archivedAt <= 0) return
    viewModelScope.launch {
      try {
        val got = fetcher.fetchSource(source)
        missingSources.update { it - source.id }
        onReady(got)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        onError(error.message ?: error::class.java.simpleName)
      }
    }
  }
}
