package dev.pampa.pampanotes.ui.session

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SessionWithParts
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.files.AppFiles
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.repo.NoteRepository
import dev.pampa.pampanotes.core.repo.RefinementOptions
import dev.pampa.pampanotes.core.repo.RefinementRepository
import dev.pampa.pampanotes.core.settings.RefinementPreset
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.player.PlayablePart
import dev.pampa.pampanotes.player.PlaybackState
import dev.pampa.pampanotes.player.SessionPlayer
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Cosa mostrare nel pannello di raffinamento quando si apre. */
data class RefineDefaults(
  val preset: RefinementPreset = RefinementPreset.CLEAN,
  val customPrompt: String = "",
  val hasKey: Boolean = false,
)

data class SessionUiState(
  val session: SessionEntity? = null,
  val note: NoteEntity? = null,
  val parts: List<AudioPartEntity> = emptyList(),
  /** Tutte le versioni: la grezza e le raffinate che ne discendono. */
  val transcripts: List<TranscriptEntity> = emptyList(),
  val activeTranscript: TranscriptEntity? = null,
  val segments: List<SegmentEntity> = emptyList(),
  val job: JobEntity? = null,
  /** Le altre sessioni della stessa nota: dove una parte puo' andare. */
  val siblings: List<SessionEntity> = emptyList(),
  val loading: Boolean = true,
) {
  val durationMs: Long get() = parts.sumOf { it.durationMs }
  val raw: TranscriptEntity? get() = transcripts.firstOrNull { it.kind == TranscriptKind.RAW }

  /** Le parti di cui la trascrizione non dice niente: importate dopo, o arrivate da un'altra sessione. */
  val untranscribed: List<AudioPartEntity>
    get() {
      if (segments.isEmpty()) return emptyList()
      val covered = segments.mapTo(mutableSetOf()) { it.partId }
      return parts.filterNot { it.id in covered }
    }

  val canMerge: Boolean get() = session != null && siblings.any { it.position < session.position }
}

@HiltViewModel
class SessionViewModel @Inject constructor(
  @ApplicationContext context: Context,
  savedStateHandle: SavedStateHandle,
  private val repository: SessionRepository,
  private val notes: NoteRepository,
  private val transcription: TranscriptionRepository,
  private val refinement: RefinementRepository,
  private val keys: AiKeyStore,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
  private val files: AppFiles,
) : ViewModel() {

  private val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()

  private val player = SessionPlayer(context, viewModelScope)

  val playback: StateFlow<PlaybackState> = player.state

  /** Il segmento che si sta ascoltando, se ce n'e' uno: la riga che si illumina e a cui la lista va. */
  private val _followPlayback = MutableStateFlow(true)
  val followPlayback: StateFlow<Boolean> = _followPlayback

  /** Quello che il pannello di raffinamento deve sapere prima di aprirsi. */
  private val _refineDefaults = MutableStateFlow(RefineDefaults())
  val refineDefaults: StateFlow<RefineDefaults> = _refineDefaults

  private val sessionFlow: Flow<SessionWithParts?> = repository.observe(sessionId)

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val noteFlow: Flow<NoteEntity?> = sessionFlow.flatMapLatest { withParts ->
    withParts?.session?.noteId?.let { notes.observe(it) } ?: flowOf(null)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val siblingsFlow: Flow<List<SessionEntity>> = sessionFlow.flatMapLatest { withParts ->
    val noteId = withParts?.session?.noteId ?: return@flatMapLatest flowOf(emptyList())
    repository.observeByNote(noteId).map { all -> all.map { it.session }.filter { it.id != sessionId } }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val transcriptsFlow: Flow<List<TranscriptEntity>> = repository.observeTranscripts(sessionId)

  /**
   * I segmenti della trascrizione mostrata.
   *
   * Solo la grezza ne ha: i tempi vengono dal modello che ha ascoltato l'audio, e un testo ripulito
   * da un LLM non ha piu' un cronometro suo. Per questo il lettore segue sempre la grezza, anche
   * quando a schermo c'e' la versione raffinata.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val segmentsFlow: Flow<List<SegmentEntity>> = transcriptsFlow.flatMapLatest { list ->
    val raw = list.firstOrNull { it.kind == TranscriptKind.RAW } ?: return@flatMapLatest flowOf(emptyList())
    repository.observeSegments(raw.id)
  }

  val uiState: StateFlow<SessionUiState> = combine(
    sessionFlow,
    noteFlow,
    transcriptsFlow,
    segmentsFlow,
    transcription.observeBySession(sessionId),
    siblingsFlow,
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    val withParts = values[0] as SessionWithParts?
    val transcripts = values[2] as List<TranscriptEntity>
    val session = withParts?.session
    SessionUiState(
      session = session,
      note = values[1] as NoteEntity?,
      parts = withParts?.partsSorted.orEmpty(),
      transcripts = transcripts,
      activeTranscript = transcripts.firstOrNull { it.id == session?.activeTranscriptId }
        ?: transcripts.firstOrNull { it.kind == TranscriptKind.RAW },
      segments = values[3] as List<SegmentEntity>,
      job = (values[4] as List<JobEntity>).firstOrNull { it.state.isActive },
      siblings = values[5] as List<SessionEntity>,
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionUiState())

  init {
    viewModelScope.launch {
      // La playlist segue le parti: riordinarle mentre si ascolta non ferma l'ascolto.
      uiState.collect { state ->
        if (state.parts.isEmpty()) return@collect
        player.load(
          state.parts.map { PlayablePart(id = it.id, file = files.audioFile(it.fileName), durationMs = it.durationMs) },
        )
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Il lettore
  // ---------------------------------------------------------------------------------------------

  fun playPause() = player.playPause()

  fun skip(deltaMs: Long) = player.skip(deltaMs)

  fun seekTo(sessionMs: Long) {
    player.seekTo(sessionMs)
    _followPlayback.value = true
  }

  fun cycleSpeed() {
    val speeds = SessionPlayer.SPEEDS
    val next = speeds[(speeds.indexOf(playback.value.speed).coerceAtLeast(0) + 1) % speeds.size]
    player.setSpeed(next)
  }

  /** L'utente ha scorso via da solo: la lista smette di inseguire finche' non tocca un segmento. */
  fun stopFollowing() {
    _followPlayback.value = false
  }

  // ---------------------------------------------------------------------------------------------
  // La sessione
  // ---------------------------------------------------------------------------------------------

  fun rename(title: String, date: String) = viewModelScope.launch { repository.rename(sessionId, title, date) }

  fun showTranscript(transcriptId: String) = viewModelScope.launch { repository.setActiveTranscript(sessionId, transcriptId) }

  fun transcribe() = viewModelScope.launch {
    val provider = settingsStore.current().preferredProvider
    transcription.enqueue(sessionId, provider)
    scheduler.kick(provider.id)
  }

  fun cancelJob(jobId: String) = viewModelScope.launch { transcription.requestCancel(jobId) }

  /** Prepara il pannello: il preset di default e se la chiave c'e'. */
  fun prepareRefinement() = viewModelScope.launch {
    val settings = settingsStore.current()
    _refineDefaults.value = RefineDefaults(
      preset = settings.refinementPreset,
      customPrompt = settings.refinementCustomPrompt,
      hasKey = !keys.key(ProviderId.GROQ).isNullOrBlank(),
    )
  }

  /**
   * Mette in coda la ripulitura.
   *
   * Il preset scelto qui diventa anche quello di default: chi ne sceglie uno diverso da quello
   * salvato quasi sempre sta correggendo la sua preferenza, non facendo un'eccezione.
   */
  fun refine(preset: RefinementPreset, customPrompt: String) = viewModelScope.launch {
    settingsStore.setRefinementPreset(preset)
    if (preset == RefinementPreset.CUSTOM) settingsStore.setRefinementCustomPrompt(customPrompt)
    val options = RefinementOptions(preset = preset.name, customPrompt = customPrompt)
    transcription.enqueueRefinement(sessionId, refinement.encode(options))
    scheduler.kick(dev.pampa.pampanotes.core.transcription.GroqWhisperProvider.ID)
  }

  fun movePart(partId: String, delta: Int) = viewModelScope.launch { repository.movePart(partId, delta) }

  fun movePartTo(partId: String, targetSessionId: String) = viewModelScope.launch { repository.movePartTo(partId, targetSessionId) }

  /** Separa da questa parte in poi; la sessione nuova si apre subito, perche' va quasi sempre datata. */
  fun splitAt(partId: String, onSplit: (String) -> Unit) = viewModelScope.launch {
    repository.splitAt(partId)?.let { onSplit(it.id) }
  }

  fun mergeIntoPrevious(onMerged: (String) -> Unit) = viewModelScope.launch {
    repository.mergeIntoPrevious(sessionId)?.let { onMerged(it.id) }
  }

  fun deletePart(partId: String, onEmpty: () -> Unit) = viewModelScope.launch {
    val remaining = uiState.value.parts.size - 1
    player.pause()
    repository.deletePart(partId)
    if (remaining <= 0) onEmpty()
  }

  fun deleteSession(onDone: () -> Unit) = viewModelScope.launch {
    player.pause()
    repository.deleteSession(sessionId)
    onDone()
  }

  override fun onCleared() {
    player.release()
    super.onCleared()
  }
}
