package dev.pampa.pampanotes.ui.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
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
import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import dev.pampa.pampanotes.core.repo.StatsRepository
import dev.pampa.pampanotes.core.stats.TranscriptionStats
import dev.pampa.pampanotes.core.stats.wordsPerMinute
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
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.repo.FolderRepository

/** Cosa mostrare nel pannello di raffinamento quando si apre. */
data class RefineDefaults(
  val preset: RefinementPreset = RefinementPreset.CLEAN,
  val customPrompt: String = "",
  val hasKey: Boolean = false,
)

/** Un prelievo dal computer di casa in corso, o finito male. */
data class FetchState(
  val done: Int,
  val total: Int,
  val label: String,
  val fraction: Float,
  val error: String? = null,
)

data class SessionUiState(
  val session: SessionEntity? = null,
  val note: NoteEntity? = null,
  /** La materia della nota: e' quella che colora la schermata. */
  val folder: FolderEntity? = null,
  val parts: List<AudioPartEntity> = emptyList(),
  /** Tutte le versioni: la grezza e le raffinate che ne discendono. */
  val transcripts: List<TranscriptEntity> = emptyList(),
  val activeTranscript: TranscriptEntity? = null,
  val segments: List<SegmentEntity> = emptyList(),
  val job: JobEntity? = null,
  /** Le altre sessioni della stessa nota: dove una parte puo' andare. */
  val siblings: List<SessionEntity> = emptyList(),
  /**
   * Le parti il cui file non e' su questo dispositivo: righe arrivate dall'indice in cloud. Null
   * finche' non si e' guardato su disco — il lettore non parte su una lista che non si conosce.
   */
  val missing: List<AudioPartEntity>? = null,
  val fetch: FetchState? = null,
  /** L'ultima trascrizione fatta da questo dispositivo per questa sessione, coi suoi numeri. */
  val lastRun: TranscriptionRunEntity? = null,
  val loading: Boolean = true,
) {
  val durationMs: Long get() = parts.sumOf { it.durationMs }

  /** Tutti i file ci sono: il lettore ha qualcosa da suonare. */
  val playable: Boolean get() = parts.isNotEmpty() && missing?.isEmpty() == true

  /** Quello che manca sta sul computer di casa, tutto: si puo' scaricare. */
  val fetchable: Boolean get() = !missing.isNullOrEmpty() && missing.all { it.archivedAt > 0 }
  val raw: TranscriptEntity? get() = transcripts.firstOrNull { it.kind == TranscriptKind.RAW }

  /** Le parti di cui la trascrizione non dice niente: importate dopo, o arrivate da un'altra sessione. */
  val untranscribed: List<AudioPartEntity>
    get() {
      if (segments.isEmpty()) return emptyList()
      val covered = segments.mapTo(mutableSetOf()) { it.partId }
      return parts.filterNot { it.id in covered }
    }

  val canMerge: Boolean get() = session != null && siblings.any { it.position < session.position }

  /**
   * I numeri della grezza che si vede, se e' nata qui. Una grezza arrivata dopo — ritrascritta da un
   * altro dispositivo e scesa col sync — e' piu' recente dell'ultima corsa, e quei numeri non sono
   * suoi.
   */
  val runOfRaw: TranscriptionRunEntity?
    get() {
      val raw = raw ?: return null
      return lastRun?.takeIf { it.finishedAt >= raw.createdAt }
    }

  /**
   * Le parole al minuto della lezione, sull'audio che la trascrizione copre. Dalla trascrizione e
   * non dalle statistiche: vale anche per una lezione trascritta altrove.
   */
  val pace: Int?
    get() {
      val raw = raw ?: return null
      if (segments.isEmpty()) return null
      val covered = segments.mapTo(mutableSetOf()) { it.partId }
      val ms = parts.filter { it.id in covered }.sumOf { it.durationMs }.takeIf { it > 0 }
        ?: segments.maxOf { it.sessionEndMs }
      // Sotto il minuto il ritmo e' una frase sola, e una frase sola non ha un ritmo.
      if (ms < 60_000) return null
      return wordsPerMinute(raw.wordCount, ms)?.takeIf { it <= TranscriptionStats.MAX_PLAUSIBLE_WPM }
    }
}

@HiltViewModel
class SessionViewModel @Inject constructor(
  @ApplicationContext context: Context,
  savedStateHandle: SavedStateHandle,
  private val repository: SessionRepository,
  private val notes: NoteRepository,
  private val folders: FolderRepository,
  private val transcription: TranscriptionRepository,
  private val refinement: RefinementRepository,
  private val keys: AiKeyStore,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
  private val files: AppFiles,
  private val fetcher: ArchiveFetcher,
  private val stats: StatsRepository,
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

  /** Le parti senza file qui; null finche' il disco non e' stato guardato. */
  private val _missing = MutableStateFlow<List<AudioPartEntity>?>(null)
  private val _fetch = MutableStateFlow<FetchState?>(null)

  private val sessionFlow: Flow<SessionWithParts?> = repository.observe(sessionId)

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val noteFlow: Flow<NoteEntity?> = sessionFlow.flatMapLatest { withParts ->
    withParts?.session?.noteId?.let { notes.observe(it) } ?: flowOf(null)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val folderFlow: Flow<FolderEntity?> = noteFlow.flatMapLatest { note ->
    note?.let { folders.observe(it.folderId) } ?: flowOf(null)
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
    folderFlow,
    _missing,
    _fetch,
    stats.observeLatest(sessionId),
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    val withParts = values[0] as SessionWithParts?
    val transcripts = values[2] as List<TranscriptEntity>
    val session = withParts?.session
    SessionUiState(
      session = session,
      note = values[1] as NoteEntity?,
      folder = values[6] as FolderEntity?,
      parts = withParts?.partsSorted.orEmpty(),
      transcripts = transcripts,
      activeTranscript = transcripts.firstOrNull { it.id == session?.activeTranscriptId }
        ?: transcripts.firstOrNull { it.kind == TranscriptKind.RAW },
      segments = values[3] as List<SegmentEntity>,
      job = (values[4] as List<JobEntity>).firstOrNull { it.state.isActive },
      siblings = values[5] as List<SessionEntity>,
      missing = values[7] as List<AudioPartEntity>?,
      fetch = values[8] as FetchState?,
      lastRun = values[9] as TranscriptionRunEntity?,
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionUiState())

  init {
    viewModelScope.launch {
      // Quali file ci sono davvero: si guarda su disco ogni volta che le parti cambiano, e finche'
      // non si e' guardato il lettore aspetta — caricare un file che non c'e' e' un errore a schermo.
      // Si riguarda anche quando i lavori cambiano: la coda di trascrizione scarica da sola il file
      // che le manca, e la scheda «sta sul computer» deve sparire senza riaprire la pagina.
      combine(sessionFlow.map { it?.partsSorted.orEmpty() }, transcription.observeBySession(sessionId)) { parts, _ -> parts }
        .collect { parts -> refreshMissing(parts) }
    }
    viewModelScope.launch {
      // La playlist segue le parti: riordinarle mentre si ascolta non ferma l'ascolto.
      uiState.collect { state ->
        if (!state.playable) return@collect
        player.load(
          state.parts.map { PlayablePart(id = it.id, file = files.audioFile(it.fileName), durationMs = it.durationMs) },
        )
      }
    }
  }

  private suspend fun refreshMissing(parts: List<AudioPartEntity>) {
    _missing.value = withContext(Dispatchers.IO) { fetcher.missing(parts) }
  }

  /** Prende dal computer di casa le parti che qui non ci sono; alla fine il lettore parte da solo. */
  fun fetchMissing() = viewModelScope.launch {
    val wanted = uiState.value.missing.orEmpty().filter { it.archivedAt > 0 }
    val current = _fetch.value
    if (wanted.isEmpty() || (current != null && current.error == null)) return@launch
    _fetch.value = FetchState(0, wanted.size, wanted.first().originalName, 0f)
    try {
      fetcher.fetchParts(wanted) { p -> _fetch.value = FetchState(p.done, p.total, p.label, p.fraction) }
      _fetch.value = null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      _fetch.value = FetchState(0, wanted.size, "", 0f, error = error.message ?: error::class.java.simpleName)
    }
    refreshMissing(uiState.value.parts)
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
    val provider = settingsStore.current().transcriptionProvider
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
