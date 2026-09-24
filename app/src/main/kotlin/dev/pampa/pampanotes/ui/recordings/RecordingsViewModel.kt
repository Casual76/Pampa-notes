package dev.pampa.pampanotes.ui.recordings

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
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.repo.StatsRepository
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.stats.TranscriptionStats
import dev.pampa.pampanotes.core.transcription.NoteTranscribingElsewhere
import dev.pampa.pampanotes.core.transcription.TranscribingMarker
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
) {
  /** Le sessioni senza trascrizione che nessuno sta trascrivendo, ne' qui ne' altrove. */
  val toTranscribe: Int get() = row.untranscribedSessions - (elsewhere?.untranscribed ?: 0)
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
  val loading: Boolean = true,
) {
  val isEmpty: Boolean get() = !loading && folders.isEmpty()

  /** C'e' qualcosa da trascrivere che non e' gia' in coda: «Trascrivi tutte» serve. */
  val canTranscribeAll: Boolean get() = recordings.any { it.toTranscribe > 0 && it.job == null }
}

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
) : ViewModel() {

  private val sectionStats: Flow<RecordingsStats> = combine(
    audioParts.observeDurationIn(personal = true),
    transcripts.observeWordTotalIn(personal = true),
    stats.observe(personal = true),
  ) { recorded, words, transcriptionStats -> RecordingsStats(recorded, words, transcriptionStats) }

  /** Lavori di qui e segni degli altri dispositivi, per nota: il badge dice «in coda» o dove. */
  private val work: Flow<Pair<Map<String, JobEntity>, Map<String, NoteTranscribingElsewhere>>> = combine(
    transcription.observeActive().map { active ->
      active.mapNotNull { job -> sessions.get(job.sessionId)?.noteId?.let { it to job } }.toMap()
    },
    transcription.observeElsewhere().map { TranscribingMarker.byNote(it.values) },
  ) { jobs, elsewhere -> jobs to elsewhere }

  private val content = combine(
    folders.observeChildren(null).map { rows -> rows.filter { it.folder.isPersonal } },
    noteDao.observePersonalRows(),
    folders.observeAll(),
    work,
  ) { roots, rows, all, (jobs, elsewhere) ->
    val byId = all.associateBy { it.id }
    RecordingsUiState(
      folders = roots,
      recordings = rows.map { RecordingItem(it, byId[it.note.folderId], jobs[it.note.id], elsewhere[it.note.id]) },
      loading = false,
    )
  }

  val uiState: StateFlow<RecordingsUiState> = combine(
    content,
    sectionStats,
    settingsStore.keepPersonalHere,
    settingsStore.settings.map { it.hasEndpoint }.distinctUntilChanged(),
  ) { state, sectionStats, keepHere, hasComputer ->
    state.copy(stats = sectionStats, keepHere = keepHere, hasComputer = hasComputer)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsUiState())

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

  /**
   * «Ascolta»: la sessione con l'audio piu' recente della nota. Una nota senza audio (un testo
   * finito qui) si apre come nota: e' comunque quello che c'e' da vedere.
   */
  fun listen(noteId: String, onSession: (String) -> Unit, onNote: (String) -> Unit) {
    viewModelScope.launch {
      val session = sessions.byNote(noteId).lastOrNull { it.parts.isNotEmpty() }?.session
      if (session != null) onSession(session.id) else onNote(noteId)
    }
  }

  /** Le sessioni senza trascrizione delle note date, in coda col servizio delle impostazioni. */
  fun transcribe(noteIds: Collection<String>) {
    viewModelScope.launch {
      val provider = settingsStore.current().transcriptionProvider
      var any = false
      noteIds.forEach { noteId ->
        sessions.byNote(noteId)
          .filter { it.parts.isNotEmpty() && it.session.activeTranscriptId == null }
          // Quelle che un altro dispositivo sta trascrivendo le salta `enqueue`.
          .forEach { if (transcription.enqueue(it.session.id, provider) != null) any = true }
      }
      if (any) scheduler.kick(provider.id)
    }
  }

  fun transcribeAll() = transcribe(uiState.value.recordings.filter { it.toTranscribe > 0 && it.job == null }.map { it.row.note.id })

  /**
   * «Tieni anche qui» per tutta la sezione, su questo dispositivo. Acceso, con «tieni tutto anche
   * qui» quello che manca torna subito; spento, quello che il computer ha gia' se ne va adesso,
   * come alla conferma di una regola «solo sul computer».
   */
  fun setKeepHere(keep: Boolean) {
    viewModelScope.launch {
      settingsStore.setKeepPersonalHere(keep)
      val settings = settingsStore.current()
      if (keep) {
        if (settings.mirrorEnabled) scheduler.fetchNow(settings.archiveOnlyUnmetered)
      } else {
        runCatching { storage.evictComputerOnly() }
      }
    }
  }
}
