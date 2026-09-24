package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.archive.ComputerOnlyScope
import dev.pampa.pampanotes.core.repo.ComputerOnlyPreview
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import dev.pampa.pampanotes.core.repo.PersonalScope
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Le regole «solo sul computer» di questo dispositivo, come le guardano menu e righe.
 *
 * [folders] e [notes] sono le regole scritte; [coveredFolders] le cartelle che ne sono coperte,
 * sottocartelle comprese, e fra queste [personalFolders], le Registrazioni, che lo sono di serie
 * finche' questo dispositivo non chiede di tenerle qui. Senza un computer collegato ([available]
 * falso) la regola non avrebbe dove tenere i file, e la voce e' spenta.
 */
data class ComputerOnlyUi(
  val folders: Set<String> = emptySet(),
  val notes: Set<String> = emptySet(),
  val coveredFolders: Set<String> = emptySet(),
  val personalFolders: Set<String> = emptySet(),
  val available: Boolean = false,
) {
  fun folderCovered(id: String): Boolean = id in coveredFolders
  fun noteCovered(id: String, folderId: String?): Boolean = id in notes || (folderId != null && folderId in coveredFolders)
}

/** Una cartella o una nota con la regola accesa, per l'elenco di Archiviazione. */
data class ComputerOnlyRule(val id: String, val name: String, val isFolder: Boolean)

/** L'elenco delle regole e quanto pesa quello che tengono sul computer. */
data class ComputerOnlySummary(val rules: List<ComputerOnlyRule> = emptyList(), val bytes: Long = 0L) {
  val folderCount: Int get() = rules.count { it.isFolder }
  val noteCount: Int get() = rules.count { !it.isFolder }
}

/** Su cosa si accende (o si spegne) la regola. */
sealed interface ComputerOnlyTarget {
  data class Folder(val id: String, val name: String) : ComputerOnlyTarget
  data class Notes(val ids: Set<String>, val title: String?) : ComputerOnlyTarget
}

/** La conferma aperta: cosa, e cosa toglierebbe da qui. */
data class ComputerOnlyAsk(val target: ComputerOnlyTarget, val preview: ComputerOnlyPreview)

/**
 * Accendere e spegnere «solo sul computer», da qualunque schermata.
 *
 * Una schermata sola la userebbe poco; sei — tessere, cartella, sottocartelle, note, home, nota —
 * con la stessa conferma e la stessa pulizia dopo, sono il motivo per cui sta qui e non nei loro
 * ViewModel. Accendere chiede conferma, perche' toglie file da qui; spegnere no, perche' non toglie
 * niente: i file torneranno quando serviranno, o al prossimo «tieni tutto anche qui».
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ComputerOnlyViewModel @Inject constructor(
  private val settingsStore: PampaSettingsStore,
  private val folders: FolderRepository,
  private val notes: NoteRepository,
  private val storage: StorageRepository,
  private val scheduler: WorkScheduler,
) : ViewModel() {

  val state: StateFlow<ComputerOnlyUi> = combine(
    settingsStore.computerOnlyFolders,
    settingsStore.computerOnlyNotes,
    folders.observeAll(),
    settingsStore.settings.map { it.hasEndpoint }.distinctUntilChanged(),
    settingsStore.keepPersonalHere,
  ) { folderRules, noteRules, all, available, keepPersonal ->
    // Le Registrazioni come le vede `ComputerOnlyScope.current`: sul computer di serie.
    val personal = if (keepPersonal) emptySet() else PersonalScope.folderIds(all)
    ComputerOnlyUi(
      folders = folderRules,
      notes = noteRules,
      coveredFolders = (if (folderRules.isEmpty()) emptySet() else ComputerOnlyScope.closure(folderRules, all)) + personal,
      personalFolders = personal,
      available = available,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComputerOnlyUi())

  /**
   * Per Archiviazione. Una regola il cui oggetto non c'e' piu' — la cartella cancellata qui o
   * altrove — non si mostra: non copre niente, e un nome non ce l'ha.
   */
  val summary: StateFlow<ComputerOnlySummary> = combine(
    settingsStore.computerOnlyFolders,
    settingsStore.computerOnlyNotes,
    folders.observeAll(),
  ) { folderRules, noteRules, all -> Triple(folderRules, noteRules, all) }
    .mapLatest { (folderRules, noteRules, all) ->
      val folderRows = all.filter { it.id in folderRules }.sortedBy { it.name.lowercase() }
        .map { ComputerOnlyRule(it.id, it.name, isFolder = true) }
      val noteRows = runCatching { notes.getAll(noteRules.toList()) }.getOrDefault(emptyList())
        .sortedBy { it.title.lowercase() }
        .map { ComputerOnlyRule(it.id, it.title, isFolder = false) }
      val bytes = runCatching { storage.computerOnlyTotal().bytes }.getOrDefault(0L)
      ComputerOnlySummary(folderRows + noteRows, bytes)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComputerOnlySummary())

  private val _asking = MutableStateFlow<ComputerOnlyAsk?>(null)
  val asking: StateFlow<ComputerOnlyAsk?> = _asking.asStateFlow()

  /** Prima di accendere: si conta quello che se ne andrebbe, e si chiede. */
  fun ask(target: ComputerOnlyTarget) {
    viewModelScope.launch {
      val preview = runCatching {
        when (target) {
          is ComputerOnlyTarget.Folder -> storage.computerOnlyPreview(setOf(target.id), emptySet())
          is ComputerOnlyTarget.Notes -> storage.computerOnlyPreview(emptySet(), target.ids)
        }
      }.getOrDefault(ComputerOnlyPreview())
      _asking.value = ComputerOnlyAsk(target, preview)
    }
  }

  fun dismiss() {
    _asking.value = null
  }

  /** Confermato: la regola si scrive, e quello che il computer ha gia' se ne va subito. */
  fun confirm() {
    val ask = _asking.value ?: return
    _asking.value = null
    viewModelScope.launch {
      when (val target = ask.target) {
        is ComputerOnlyTarget.Folder -> settingsStore.setComputerOnlyFolder(target.id, true)
        is ComputerOnlyTarget.Notes -> settingsStore.setComputerOnlyNotes(target.ids, true)
      }
      runCatching { storage.evictComputerOnly() }
    }
  }

  /**
   * «Tieni anche qui»: la regola si toglie. Con «tieni tutto anche qui» acceso quello che manca
   * torna subito; senza, torna quando serve, come ogni file che sta sul computer.
   */
  fun keepHere(target: ComputerOnlyTarget) {
    viewModelScope.launch {
      when (target) {
        is ComputerOnlyTarget.Folder -> settingsStore.setComputerOnlyFolder(target.id, false)
        is ComputerOnlyTarget.Notes -> settingsStore.setComputerOnlyNotes(target.ids, false)
      }
      val settings = settingsStore.current()
      if (settings.mirrorEnabled) scheduler.fetchNow(settings.archiveOnlyUnmetered)
    }
  }
}

/** Una voce da barra (un'icona), per la selezione multipla. */
data class ComputerOnlyBarToggle(val label: String, val enabled: Boolean, val onClick: () -> Unit)

/**
 * Quello che una schermata usa: le voci dei menu, gia' con l'etichetta giusta per lo stato, e i
 * segni «sul computer». La conferma la disegna [rememberComputerOnly] da solo.
 */
@Stable
class ComputerOnlyControls internal constructor(
  val ui: ComputerOnlyUi,
  private val viewModel: ComputerOnlyViewModel,
  private val labels: Labels,
) {
  internal class Labels(val on: String, val off: String, val unavailable: String, val inherited: String, val marker: String, val personal: String)

  /** «sul computer», per il sottotitolo di una tessera o di una riga. */
  val marker: String get() = labels.marker

  fun folderMarked(id: String): Boolean = ui.folderCovered(id)
  fun noteMarked(id: String, folderId: String?): Boolean = ui.noteCovered(id, folderId)

  /** Aggiunge « · sul computer» a un sottotitolo, se la regola lo copre. */
  fun folderSubtitle(id: String, subtitle: String): String = if (folderMarked(id)) "$subtitle · $marker" else subtitle
  fun noteSubtitle(id: String, folderId: String?, subtitle: String): String = if (noteMarked(id, folderId)) "$subtitle · $marker" else subtitle

  fun folderAction(id: String, name: String): FluidContextAction = when {
    id in ui.folders -> FluidContextAction(label = labels.off) { viewModel.keepHere(ComputerOnlyTarget.Folder(id, name)) }
    // Registrazioni: la regola e' della sezione, e si cambia dalla sua scheda.
    id in ui.personalFolders -> FluidContextAction(label = labels.personal, enabled = false) {}
    // Una cartella dentro una esclusa: la regola e' quella di sopra, e si toglie da li'.
    ui.folderCovered(id) -> FluidContextAction(label = labels.inherited, enabled = false) {}
    !ui.available -> FluidContextAction(label = labels.unavailable, enabled = false) {}
    else -> FluidContextAction(label = labels.on) { viewModel.ask(ComputerOnlyTarget.Folder(id, name)) }
  }

  fun noteAction(id: String, folderId: String?, title: String): FluidContextAction = when {
    id in ui.notes -> FluidContextAction(label = labels.off) { viewModel.keepHere(ComputerOnlyTarget.Notes(setOf(id), title)) }
    folderId != null && folderId in ui.personalFolders -> FluidContextAction(label = labels.personal, enabled = false) {}
    folderId != null && ui.folderCovered(folderId) -> FluidContextAction(label = labels.inherited, enabled = false) {}
    !ui.available -> FluidContextAction(label = labels.unavailable, enabled = false) {}
    else -> FluidContextAction(label = labels.on) { viewModel.ask(ComputerOnlyTarget.Notes(setOf(id), title)) }
  }

  /**
   * Le note selezionate in una cartella: se hanno gia' tutte la regola la toglie, altrimenti la
   * accende su tutte. Spenta dentro una cartella gia' esclusa, o senza computer.
   */
  fun selectionToggle(ids: Set<String>, folderId: String?, afterClick: () -> Unit): ComputerOnlyBarToggle {
    val allOn = ids.isNotEmpty() && ids.all { it in ui.notes }
    val inherited = folderId != null && ui.folderCovered(folderId)
    return ComputerOnlyBarToggle(
      label = if (allOn) labels.off else labels.on,
      enabled = ids.isNotEmpty() && !inherited && (allOn || ui.available),
      onClick = {
        if (allOn) viewModel.keepHere(ComputerOnlyTarget.Notes(ids, null)) else viewModel.ask(ComputerOnlyTarget.Notes(ids - ui.notes, null))
        afterClick()
      },
    )
  }
}

/**
 * Le voci «Solo sul computer» / «Tieni anche qui» per una schermata, con la loro conferma.
 *
 * La conferma e' un `Dialog`, quindi il punto della composizione in cui la si chiama non conta: una
 * schermata aggiunge questa riga e le voci che le servono, e basta.
 */
@Composable
fun rememberComputerOnly(viewModel: ComputerOnlyViewModel = hiltViewModel()): ComputerOnlyControls {
  val ui by viewModel.state.collectAsStateWithLifecycle()
  val asking by viewModel.asking.collectAsStateWithLifecycle()
  val labels = ComputerOnlyControls.Labels(
    on = stringResource(R.string.computer_only_on),
    off = stringResource(R.string.computer_only_off),
    unavailable = stringResource(R.string.computer_only_unavailable),
    inherited = stringResource(R.string.computer_only_inherited),
    marker = stringResource(R.string.computer_only_marker),
    personal = stringResource(R.string.recordings_computer_only_inherited),
  )
  asking?.let { ComputerOnlyConfirm(it, onConfirm = viewModel::confirm, onDismiss = viewModel::dismiss) }
  return ComputerOnlyControls(ui, viewModel, labels)
}

@Composable
private fun ComputerOnlyConfirm(ask: ComputerOnlyAsk, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val title = when (val target = ask.target) {
    is ComputerOnlyTarget.Folder -> stringResource(R.string.computer_only_confirm_title, target.name)
    is ComputerOnlyTarget.Notes -> target.title?.let { stringResource(R.string.computer_only_confirm_title, it) }
      ?: pluralStringResource(R.plurals.computer_only_confirm_title_notes, target.ids.size, target.ids.size)
  }
  val preview = ask.preview
  val message = if (preview.here.count == 0) {
    stringResource(R.string.computer_only_confirm_nothing)
  } else {
    val what = buildList {
      if (preview.recordings.count > 0) add(pluralStringResource(R.plurals.computer_only_recordings, preview.recordings.count, preview.recordings.count))
      if (preview.originals.count > 0) add(pluralStringResource(R.plurals.computer_only_originals, preview.originals.count, preview.originals.count))
    }.joinToString(stringResource(R.string.computer_only_and))
    val size = Formats.bytes(preview.here.bytes)
    // Quelli gia' sul computer ma protetti (la lezione che si ascolta o si trascrive) non se ne vanno
    // «dopo l'archiviazione»: l'archiviazione li ha gia' presi. Lo si dice per quello che e'.
    val later = preview.here.count - preview.leavingNow.count - preview.kept.count
    val kept = if (preview.kept.count > 0) " " + stringResource(R.string.computer_only_confirm_kept) else ""
    when {
      later <= 0 && preview.leavingNow.count == 0 -> stringResource(R.string.computer_only_confirm_message_kept_only, what, size)
      preview.leavingNow.count == preview.here.count -> stringResource(R.string.computer_only_confirm_message_all, what, size)
      preview.leavingNow.count == 0 -> stringResource(R.string.computer_only_confirm_message_later, what, size) + kept
      else -> stringResource(R.string.computer_only_confirm_message, what, size, preview.leavingNow.count) + kept
    }
  }
  FluidAlert(
    onDismissRequest = onDismiss,
    title = title,
    message = message,
    actions = listOf(
      FluidAlertAction(label = stringResource(R.string.computer_only_on), emphasis = FluidAlertAction.Emphasis.Preferred, onClick = onConfirm),
      FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = onDismiss),
    ),
  )
}
