package dev.pampa.pampanotes.ui.recordings

import dev.pampa.pampanotes.core.db.JobDao
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.repo.ComputerOnlyPreview
import dev.pampa.pampanotes.core.repo.FailedJobs
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import dev.pampa.pampanotes.core.repo.PersonalScope
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.repo.StatsRepository
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.stats.TranscriptionStats
import dev.pampa.pampanotes.core.transcription.NoteTranscribingElsewhere
import dev.pampa.pampanotes.core.transcription.TranscribingMarker
import dev.pampa.pampanotes.work.WorkScheduler
import dev.pampa.pampanotes.ui.common.NoteBulkActions
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una registrazione dell'elenco: la nota, la cartella da cui viene, e a che punto e' la sua trascrizione. */
data class RecordingItem(
  val row: NoteRow,
  val folder: FolderEntity?,
  val job: JobEntity? = null,
  val elsewhere: NoteTranscribingElsewhere? = null,
  /** L'ultimo tentativo di trascrizione di una sua sessione, se e' fallito: il badge lo dice. */
  val failed: JobEntity? = null,
  /** Le sue sessioni il cui ultimo tentativo ha risposto «niente parole»: rifarle darebbe lo stesso. */
  val silent: Int = 0,
) {
  /** Le sessioni senza trascrizione che nessuno sta trascrivendo, ne' qui ne' altrove. */
  val toTranscribe: Int get() = row.untranscribedSessions - (elsewhere?.untranscribed ?: 0)

  /** Quelle che «Trascrivi tutte» manderebbe davvero: non le mute. */
  val worthTranscribing: Int get() = toTranscribe - silent
}

/**
 * I numeri della sezione, contati solo su di lei: le ore registrate, quelle trascritte, le parole, la
 * velocita'. Sono gli stessi conti della home ([TranscriptionStats.aggregate]) sull'altra meta'
 * dell'archivio.
 */
data class RecordingsStats(
  val recordedMs: Long = 0,
  val words: Long = 0,
  val transcription: TranscriptionStats = TranscriptionStats(),
)

/** Dove stanno i file della sezione su questo dispositivo: la nota in fondo alla scheda lo dice. */
enum class RecordingsWhere {
  /** Questo dispositivo le tiene anche qui. */
  HERE,

  /** «Tieni tutto anche qui»: questo dispositivo scarica tutto, loro comprese. */
  MIRROR,

  /** Solo sul computer: se ne vanno da qui quando ci sono arrivate. */
  COMPUTER,

  /** Sul computer di serie, ma l'archivio e' spento: non ci arrivano, e restano qui. */
  NO_ARCHIVE,

  /** Nessun computer: restano qui. */
  NO_COMPUTER,
}

data class RecordingsUiState(
  /** Le cartelle di primo livello della sezione. */
  val folders: List<FolderRow> = emptyList(),
  /** Le registrazioni di tutte le cartelle della sezione, dalla piu' recente. */
  val recordings: List<RecordingItem> = emptyList(),
  val stats: RecordingsStats = RecordingsStats(),
  /** Questo dispositivo tiene le Registrazioni anche qui, invece che solo sul computer. */
  val keepHere: Boolean = false,
  /** C'e' un computer di casa: senza, «solo sul computer» non ha dove tenere i file. */
  val hasComputer: Boolean = false,
  /** «Tieni tutto anche qui» e' acceso: la scelta della sezione qui non cambia niente. */
  val mirror: Boolean = false,
  val archiveEnabled: Boolean = false,
  val loading: Boolean = true,
) {
  val isEmpty: Boolean get() = !loading && folders.isEmpty()

  /** C'e' qualcosa da trascrivere che non e' gia' in coda e non e' muto: «Trascrivi tutte» serve. */
  val canTranscribeAll: Boolean get() = recordings.any { it.worthTranscribing > 0 && it.job == null }

  val where: RecordingsWhere
    get() = when {
      mirror -> RecordingsWhere.MIRROR
      keepHere -> RecordingsWhere.HERE
      !hasComputer -> RecordingsWhere.NO_COMPUTER
      !archiveEnabled -> RecordingsWhere.NO_ARCHIVE
      else -> RecordingsWhere.COMPUTER
    }
}

/** «Trascrivi tutte», prima di partire: quante, quanto audio, e con chi. */
data class TranscribeAllAsk(
  val sessionIds: List<String>,
  val durationMs: Long,
  val provider: TranscriptionProviderId,
  /** Sessioni mute lasciate fuori: la conferma lo dice. */
  val skippedSilent: Int,
)

/**
 * La scheda Registrazioni: l'audio che non e' una lezione, con i suoi numeri.
 *
 * Le cartelle sono quelle di primo livello con `kind = personal` (vedi `PersonalScope`); le
 * registrazioni sono le note di tutte, sottocartelle comprese, perche' qui si cerca «quella di
 * ieri» e non «quella nella cartella giusta».
 */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
  private val folders: FolderRepository,
  private val notes: NoteRepository,
  private val sessions: SessionRepository,
  private val transcription: TranscriptionRepository,
  private val storage: StorageRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
  noteDao: NoteDao,
  audioParts: AudioPartDao,
  transcripts: TranscriptDao,
  stats: StatsRepository,
  jobDao: JobDao,
  private val bulk: NoteBulkActions,
) : ViewModel() {

  private val sectionStats: Flow<RecordingsStats> = combine(
    audioParts.observeDurationIn(personal = true),
    transcripts.observeWordTotalIn(personal = true),
    stats.observe(personal = true),
  ) { recorded, words, transcriptionStats -> RecordingsStats(recorded, words, transcriptionStats) }

  /** L'ultimo tentativo fallito di ogni sessione, con la nota a cui appartiene. */
  private val latestFailed: Flow<List<Pair<String, JobEntity>>> = jobDao.observeLatestFailed().map { failed ->
    failed.mapNotNull { job -> sessions.get(job.sessionId)?.noteId?.let { it to job } }
  }

  /** Lavori di qui e segni degli altri dispositivi, per nota: il badge dice «in coda» o dove. */
  private val work: Flow<Triple<Map<String, JobEntity>, Map<String, NoteTranscribingElsewhere>, List<Pair<String, JobEntity>>>> = combine(
    transcription.observeActive().map { active ->
      active.mapNotNull { job -> sessions.get(job.sessionId)?.noteId?.let { it to job } }.toMap()
    },
    transcription.observeElsewhere().map { TranscribingMarker.byNote(it.values) },
    latestFailed,
  ) { jobs, elsewhere, failed -> Triple(jobs, elsewhere, failed) }

  private val content = combine(
    folders.observeChildren(null).map { rows -> rows.filter { it.folder.isPersonal } },
    noteDao.observePersonalRows(),
    folders.observeAll(),
    work,
  ) { roots, rows, all, (jobs, elsewhere, failed) ->
    val byId = all.associateBy { it.id }
    val failedByNote = failed.toMap()
    val silentByNote = failed.filter { it.second.errorCode == FailedJobs.NO_SPEECH }.groupingBy { it.first }.eachCount()
    RecordingsUiState(
      folders = roots,
      recordings = rows.map {
        RecordingItem(it, byId[it.note.folderId], jobs[it.note.id], elsewhere[it.note.id], failedByNote[it.note.id], silentByNote[it.note.id] ?: 0)
      },
      loading = false,
    )
  }

  val uiState: StateFlow<RecordingsUiState> = combine(
    content,
    sectionStats,
    settingsStore.keepPersonalHere,
    settingsStore.settings.map { Triple(it.hasEndpoint, it.mirrorEnabled, it.archiveEnabled) }.distinctUntilChanged(),
  ) { state, sectionStats, keepHere, (hasComputer, mirror, archive) ->
    state.copy(stats = sectionStats, keepHere = keepHere, hasComputer = hasComputer, mirror = mirror, archiveEnabled = archive)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsUiState())

  private val _transcribeAllAsk = MutableStateFlow<TranscribeAllAsk?>(null)
  val transcribeAllAsk: StateFlow<TranscribeAllAsk?> = _transcribeAllAsk.asStateFlow()

  private val _computerOnlyAsk = MutableStateFlow<ComputerOnlyPreview?>(null)

  /** «Registrazioni solo sul computer», prima di togliere: quanto se ne andrebbe da qui. */
  val computerOnlyAsk: StateFlow<ComputerOnlyPreview?> = _computerOnlyAsk.asStateFlow()

  /** «Nuova cartella» qui e' una cartella di Registrazioni. */
  fun createFolder(name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.create(name = name, tone = tone, icon = icon, kind = FolderEntity.KIND_PERSONAL) }
  }

  fun updateFolder(id: String, name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.update(id, name, tone, icon) }
  }

  fun deleteFolder(id: String) {
    viewModelScope.launch { withContext(NonCancellable) { folders.delete(id) } }
  }

  /** «Sposta fra le materie»: la cartella torna una tessera di Cartelle, con tutto quello che ha dentro. */
  fun moveToSchool(id: String) {
    viewModelScope.launch { folders.setKind(id, FolderEntity.KIND_SCHOOL) }
  }

  fun deleteNote(id: String) {
    viewModelScope.launch { notes.delete(id) }
  }

  /** La selezione: le registrazioni scelte se ne vanno, con audio e trascrizioni. */
  fun deleteNotes(ids: Collection<String>) {
    viewModelScope.launch { bulk.delete(ids) }
  }

  /**
   * «Ascolta»: si riprende da dove ci si era fermati, se l'ultima cosa ascoltata qui e' una sessione
   * di questa nota e non e' finita; altrimenti la sessione piu' recente, dall'inizio. In tutti e due
   * i casi il lettore parte da solo: un tocco su «Ascolta» che apre una pagina ferma a 0:00 e' un
   * tocco in piu'. Una nota senza audio (un testo finito qui) si apre come nota.
   *
   * @param onSession la sessione da aprire e far suonare (la strada di «Riprendi» della home).
   */
  fun listen(noteId: String, onSession: (String) -> Unit, onNote: (String) -> Unit) {
    viewModelScope.launch {
      val withAudio = sessions.byNote(noteId).filter { it.parts.isNotEmpty() }.map { it.session }
      if (withAudio.isEmpty()) {
        onNote(noteId)
        return@launch
      }
      val last = settingsStore.lastListened.first()
      val resume = last?.takeIf { !it.finished && withAudio.any { s -> s.id == it.sessionId } }?.sessionId
      val latest = withAudio.maxWith(compareBy({ it.date }, { it.createdAt })).id
      onSession(resume ?: latest)
    }
  }

  /** Le sessioni senza trascrizione delle note date, in coda col servizio delle impostazioni. */
  fun transcribe(noteIds: Collection<String>) {
    viewModelScope.launch {
      val provider = settingsStore.current().transcriptionProvider
      enqueue(pendingSessions(noteIds).map { it.first }, provider)
    }
  }

  /**
   * «Trascrivi tutte»: prima si conta, poi si chiede. Una sezione che ha diciannove ore di audio non
   * parte con un tocco solo — e con Groq quelle ore andrebbero nel cloud.
   */
  fun askTranscribeAll() = askTranscribe(null)

  /**
   * La stessa domanda per le registrazioni scelte a mano: diciannove ore scelte una per una sono
   * sempre diciannove ore, e con Groq finirebbero nel cloud.
   *
   * @param only le note fra cui scegliere; `null` e' «tutte».
   */
  fun askTranscribe(only: Set<String>?) {
    viewModelScope.launch {
      val ids = uiState.value.recordings
        .filter { it.worthTranscribing > 0 && it.job == null && (only == null || it.row.note.id in only) }
        .map { it.row.note.id }
      val all = pendingSessions(ids)
      val silent = silentSessionIds()
      val wanted = all.filter { it.first !in silent }
      if (wanted.isEmpty()) return@launch
      _transcribeAllAsk.value = TranscribeAllAsk(
        sessionIds = wanted.map { it.first },
        durationMs = wanted.sumOf { it.second },
        provider = settingsStore.current().transcriptionProvider,
        skippedSilent = all.size - wanted.size,
      )
    }
  }

  fun confirmTranscribeAll() {
    val ask = _transcribeAllAsk.value ?: return
    _transcribeAllAsk.value = null
    viewModelScope.launch { enqueue(ask.sessionIds, ask.provider) }
  }

  fun dismissTranscribeAll() {
    _transcribeAllAsk.value = null
  }

  /** Le sessioni con audio e senza trascrizione delle note date, con quanto durano. */
  private suspend fun pendingSessions(noteIds: Collection<String>): List<Pair<String, Long>> =
    noteIds.flatMap { noteId ->
      sessions.byNote(noteId)
        .filter { it.parts.isNotEmpty() && it.session.activeTranscriptId == null }
        .map { it.session.id to it.parts.sumOf { part -> part.durationMs } }
    }

  /** Le sessioni il cui ultimo tentativo ha risposto «niente parole». */
  private suspend fun silentSessionIds(): Set<String> =
    latestFailed.first().filter { it.second.errorCode == FailedJobs.NO_SPEECH }.mapTo(HashSet()) { it.second.sessionId }

  private suspend fun enqueue(sessionIds: List<String>, provider: TranscriptionProviderId) {
    // Quelle che un altro dispositivo sta trascrivendo le salta `enqueue`.
    val any = sessionIds.map { transcription.enqueue(it, provider) }.any { it != null }
    if (any) scheduler.kick(provider.id)
  }

  /**
   * «Tieni le registrazioni anche qui»: la regola di serie non vale piu' su questo dispositivo, e
   * quello che sta solo sul computer torna subito — un giro di scarico della sola sezione, anche con
   * «tieni tutto anche qui» spento.
   */
  fun keepHere() {
    viewModelScope.launch {
      settingsStore.setKeepPersonalHere(true)
      val settings = settingsStore.current()
      if (settings.hasEndpoint) scheduler.fetchPersonal(settings.archiveOnlyUnmetered)
    }
  }

  /**
   * «Registrazioni solo sul computer»: prima si conta quello che se ne andrebbe da qui, e si chiede,
   * come per la regola su una cartella.
   */
  fun askComputerOnly() {
    viewModelScope.launch {
      val roots = PersonalScope.rootIds(folders.all())
      _computerOnlyAsk.value = runCatching { storage.computerOnlyPreview(roots, emptySet()) }.getOrDefault(ComputerOnlyPreview())
    }
  }

  /** Confermato: la regola di serie torna a valere, e quello che il computer ha gia' se ne va adesso. */
  fun confirmComputerOnly() {
    _computerOnlyAsk.value = null
    viewModelScope.launch {
      settingsStore.setKeepPersonalHere(false)
      runCatching { storage.evictComputerOnly() }
    }
  }

  fun dismissComputerOnly() {
    _computerOnlyAsk.value = null
  }
}
