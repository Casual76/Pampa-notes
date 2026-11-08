package dev.pampa.pampanotes.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import dev.antigravity.fluidengine.ui.fluid.LocalFluidBottomInset
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.page.FluidBackSwipe
import dev.antigravity.fluidengine.ui.page.FluidPageTransition
import dev.pampa.pampanotes.ui.editor.EditorRoute
import dev.pampa.pampanotes.ui.folder.FolderRoute
import dev.pampa.pampanotes.ui.folders.FoldersRoute
import dev.pampa.pampanotes.ui.home.HomeRoute
import dev.pampa.pampanotes.ui.importing.ImportRoute
import dev.pampa.pampanotes.ui.jobs.JobsRoute
import dev.pampa.pampanotes.ui.more.MoreRoute
import dev.pampa.pampanotes.ui.note.NoteRoute
import dev.pampa.pampanotes.ui.recordings.RecordingsRoute
import dev.pampa.pampanotes.ui.search.SearchRoute
import dev.pampa.pampanotes.ui.session.SessionRoute
import dev.pampa.pampanotes.ui.settings.SettingsRoute
import dev.pampa.pampanotes.ui.settings.SettingsSectionRoute

/**
 * Le azioni di navigazione dell'app, con due padroni di casa.
 *
 * Su una pagina larga la lista e il dettaglio hanno un `FluidNavHost` ciascuno: aprire una nota dalla
 * lista mette la nota nel dettaglio e la lista resta dov'e'. Su una pagina stretta il dettaglio
 * non c'e' e tutto passa dalla lista, come sempre. Chi chiama non lo sa: chiede «apri questa
 * nota», e dove finisce dipende da quanto e' larga la finestra in quel momento.
 */
class PampaNavActions(
  val listNav: NavHostController,
  val detailNav: NavHostController,
  private val twoPane: () -> Boolean,
  /** Apre il selettore di file; quello che sceglie finisce nel wizard di import. */
  val pickFiles: () -> Unit,
  /** Lo stesso, ma dentro una nota gia' esistente. */
  val pickFilesInto: (String) -> Unit,
  /** Lo stesso, con la cartella gia' scelta: «Importa» dentro Registrazioni. */
  val pickFilesIntoFolder: (String) -> Unit,
  /** Lo stesso, dalla scheda Registrazioni con piu' cartelle: il wizard sceglie la prima di loro, non una materia. */
  val pickFilesPersonal: () -> Unit,
) {
  fun openNote(id: String, tab: String? = null) = openDetail(Routes.note(id, tab), fresh = true)
  fun openSession(id: String) = openDetail(Routes.session(id))

  /** La sessione da «Riprendi ad ascoltare»: si apre e riparte dal punto in cui ci si era fermati. */
  fun resumeSession(id: String) = openDetail(Routes.session(id, play = true), fresh = true)

  /**
   * La sessione dalla ricerca: il lettore pronto nel momento trovato e la ricerca dentro la sessione
   * gia' scritta. Come una nota aperta da un elenco, sul tablet prende il posto di quello che era
   * aperto invece di impilarsi; sul telefono va sopra la ricerca, e indietro ci riporta.
   */
  fun openSessionAt(id: String, atMs: Long?, query: String) =
    openDetail(Routes.session(id, atMs = atMs, query = query), fresh = true)

  /**
   * La sessione a un momento, dalla nota (il punto piu' forte, nelle statistiche): il lettore pronto
   * li', senza ricerca. Dentro la nota come una sessione qualunque, e indietro ci riporta.
   */
  fun openSessionAtMoment(id: String, atMs: Long) = openDetail(Routes.session(id, atMs = atMs))
  fun openEditor(noteId: String) = openDetail(Routes.editor(noteId))
  fun openImport(fresh: Boolean = true) = openDetail(Routes.IMPORT, fresh = fresh)

  /**
   * Il wizard ha finito e apre la nota: se sotto c'e' gia' lei (si stava aggiungendo un file a
   * questa nota) basta toglierlo, o sul telefono ci sarebbero due volte la stessa nota e servirebbero
   * due «indietro».
   */
  fun finishImport(host: NavHostController, noteId: String) {
    host.popBackStack()
    val under = host.currentBackStackEntry
    if (under?.destination?.route == Routes.NOTE && under.arguments?.getString("noteId") == noteId) return
    if (host === detailNav) openDetail(Routes.note(noteId)) else push(listNav, Routes.note(noteId))
  }
  fun openFolder(id: String) = openList(Routes.folder(id))
  fun openSearch() = openList(Routes.SEARCH)
  fun openJobs() = openList(Routes.JOBS)
  fun openSettings() = openList(Routes.SETTINGS)

  /** Una sezione delle impostazioni: sul tablet a destra dell'indice, sul telefono sopra. */
  fun openSettingsSection(section: SettingsSection) = openDetail(Routes.settingsSection(section.route), fresh = true)

  /** Una scheda principale: si scambia con quella di prima, non si impila. */
  fun switchTopLevel(route: String) {
    closeDetail()
    listNav.navigateTopLevelRoute(route)
  }

  /** Dalla barra laterale: una materia al posto di quella di prima, sopra la Home. */
  fun showFolder(id: String) {
    closeDetail()
    listNav.navigate(Routes.folder(id)) {
      popUpTo(Routes.HOME)
      launchSingleTop = true
    }
  }

  /**
   * Chiude quello che e' aperto, quando si chiede di guardare qualcos'altro.
   *
   * Su una finestra larga l'elenco e la cosa aperta si danno il cambio nello stesso posto: scegliere
   * una materia dalla barra laterale mentre una nota e' aperta, senza questo, cambierebbe l'elenco
   * che in quel momento nessuno vede — un tocco che non fa niente.
   */
  private fun closeDetail() {
    if (!twoPane()) return
    if (detailNav.currentBackStackEntry?.destination?.route == Routes.DETAIL_EMPTY) return
    runCatching { detailNav.popBackStack(Routes.DETAIL_EMPTY, inclusive = false) }
  }

  /**
   * Il dettaglio che ne sostituisce un altro: separare una sessione apre quella nuova al posto di
   * questa, e il wizard di import lascia il posto alla nota che ha creato.
   */
  fun replaceWith(host: NavHostController, route: String) {
    host.popBackStack()
    if (host === detailNav) {
      openDetail(route)
    } else {
      push(listNav, route)
    }
  }

  private fun openList(route: String) {
    closeDetail()
    push(listNav, route)
  }

  private fun openDetail(route: String, fresh: Boolean = false) {
    if (!twoPane()) {
      push(listNav, route)
      return
    }
    // Una nota nuova dalla lista prende il posto di quella aperta: due note impilate nel dettaglio
    // sono una pila che nessuno ha chiesto. Sessione ed editor invece si aprono dentro la nota, e
    // il back li richiude su di lei.
    detailNav.navigate(route) {
      if (fresh) popUpTo(Routes.DETAIL_EMPTY)
      launchSingleTop = true
    }
  }

  // Il punto da cui si e' aperta una pagina che cresce lo prende da solo l'host delle pagine (il
  // dito che e' sceso), e il ritorno disfa lo stesso movimento: qui non c'e' piu' niente da scrivere.
  private fun push(host: NavHostController, route: String) {
    host.navigate(route)
  }
}

/**
 * Sposta quello che e' aperto nel padrone di casa giusto quando la finestra cambia regime.
 *
 * Ruotando un tablet la lista e il dettaglio si fondono in un pannello solo, o si separano: la
 * nota che si stava leggendo deve restare davanti in entrambi i casi. Da largo a stretto, il
 * dettaglio aperto passa in cima alla lista; da stretto a largo, le rotte di dettaglio in cima alla
 * lista passano nel dettaglio, nell'ordine in cui stavano. Ricostruire la rotta dagli argomenti e'
 * l'unico modo: una voce dello stack non si sposta da un controller all'altro.
 */
fun syncPanes(listNav: NavHostController, detailNav: NavHostController, twoPane: Boolean) {
  if (twoPane) {
    val moved = ArrayList<String>()
    while (true) {
      val entry = listNav.currentBackStackEntry ?: break
      if (!Routes.isDetail(entry.destination.route)) break
      val route = detailRouteOf(entry) ?: break
      moved.add(0, route)
      listNav.popBackStack()
    }
    moved.forEach { route -> detailNav.navigate(route) { launchSingleTop = true } }
  } else {
    val open = detailNav.currentBackStackEntry?.takeIf { it.destination.route != Routes.DETAIL_EMPTY }
    val route = open?.let(::detailRouteOf)
    if (open != null) detailNav.popBackStack(Routes.DETAIL_EMPTY, inclusive = false)
    if (route != null) listNav.navigate(route) { launchSingleTop = true }
  }
}

/**
 * Il segno, sulla voce dello stack, che la sessione ha gia' usato il momento e la parola della
 * ricerca: [syncPanes] la ricostruisce senza, e la pagina nel pannello nuovo non salta di nuovo.
 */
private const val SESSION_JUMP_USED = "sessionJumpUsed"

/** La rotta di dettaglio di una voce dello stack, ricostruita dai suoi argomenti. */
private fun detailRouteOf(entry: NavBackStackEntry): String? {
  val args = entry.arguments
  return when (entry.destination.route) {
    Routes.NOTE -> Routes.note(args?.getString("noteId") ?: return null, args.getString("tab"))
    Routes.SESSION -> {
      val id = args?.getString("sessionId") ?: return null
      // Il momento e la parola della ricerca viaggiano solo finche' la pagina non li ha usati: dopo,
      // la pagina nuova ripartirebbe da li' e porterebbe via chi nel frattempo si e' spostato.
      if (runCatching { entry.savedStateHandle.get<Boolean>(SESSION_JUMP_USED) }.getOrNull() == true) {
        Routes.session(id)
      } else {
        Routes.session(id, atMs = args.getString("at")?.toLongOrNull(), query = args.getString("q"))
      }
    }
    Routes.EDITOR -> Routes.editor(args?.getString("noteId") ?: return null)
    Routes.SETTINGS_SECTION -> Routes.settingsSection(args?.getString("section") ?: return null)
    Routes.IMPORT -> Routes.IMPORT
    else -> null
  }
}

/** Le pagine che elencano: le schede principali, le cartelle, cerca, lavori, impostazioni. */
fun NavGraphBuilder.listDestinations(actions: PampaNavActions, host: NavHostController) {
  composable(Routes.HOME) {
    TabPage {
      HomeRoute(
        onOpenNote = actions::openNote,
        onImport = actions.pickFiles,
        onOpenJobs = actions::openJobs,
        onResumeSession = actions::resumeSession,
        onSearch = actions::openSearch,
      )
    }
  }
  composable(Routes.FOLDERS) {
    TabPage {
      FoldersRoute(
        onOpenFolder = actions::openFolder,
        onImport = actions.pickFiles,
      )
    }
  }
  composable(Routes.RECORDINGS) {
    TabPage {
      RecordingsRoute(
        onOpenFolder = actions::openFolder,
        onOpenNote = actions::openNote,
        // Come «Riprendi» della home: al posto di quello che era aperto, e il lettore parte. Con
        // `openSession` sul tablet le registrazioni si impilavano una sull'altra nel dettaglio.
        onListenSession = actions::resumeSession,
        onImportInto = actions.pickFilesIntoFolder,
      )
    }
  }
  composable(Routes.MORE) {
    TabPage {
      MoreRoute(
        onOpenSearch = actions::openSearch,
        onOpenJobs = actions::openJobs,
        onOpenSettings = actions::openSettings,
        onImport = actions.pickFiles,
        onOpenAbout = { actions.openSettingsSection(SettingsSection.ABOUT) },
      )
    }
  }
  composable(Routes.SEARCH) {
    SearchRoute(
      onBack = { host.popBackStack() },
      onOpenNote = actions::openNote,
      onOpenMoment = actions::openSessionAt,
    )
  }
  composable(Routes.JOBS) {
    JobsRoute(onBack = { host.popBackStack() }, onOpenSession = actions::openSession)
  }
  composable(Routes.SETTINGS) {
    SettingsRoute(onBack = { host.popBackStack() }, onOpenSection = actions::openSettingsSection)
  }
  composable(
    route = Routes.FOLDER,
    arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
    transition = FluidPageTransition.Expand,
  ) { entry ->
    FolderRoute(
      folderId = entry.arguments?.getString("folderId").orEmpty(),
      onBack = { host.popBackStack() },
      onOpenFolder = actions::openFolder,
      onOpenNote = actions::openNote,
      onImport = actions.pickFiles,
      onOpenEditor = actions::openEditor,
      onImportInto = actions.pickFilesIntoFolder,
      onListenSession = actions::resumeSession,
    )
  }
}

/**
 * Le pagine che mostrano una cosa sola: la nota, la sessione, l'editor, il wizard di import.
 *
 * Su una pagina larga stanno nel dettaglio; su una stretta nella lista, sopra chi le ha aperte.
 * Il grafo e' lo stesso, cambia [host]: il back di ognuna chiude il padrone di casa in cui vive.
 */
fun NavGraphBuilder.detailDestinations(actions: PampaNavActions, host: NavHostController) {
  composable(
    route = Routes.NOTE,
    arguments = listOf(
      navArgument("noteId") { type = NavType.StringType },
      navArgument("tab") { nullable = true; defaultValue = null },
    ),
    transition = FluidPageTransition.Expand,
  ) { entry ->
    NoteRoute(
      initialTab = entry.arguments?.getString("tab"),
      onBack = { host.popBackStack() },
      onEdit = actions::openEditor,
      onOpenSession = actions::openSession,
      onImportInto = actions.pickFilesInto,
      onOpenSessionAt = actions::openSessionAtMoment,
    )
  }
  composable(
    route = Routes.SESSION,
    arguments = listOf(
      navArgument("sessionId") { type = NavType.StringType },
      navArgument("play") { nullable = true; defaultValue = null },
      navArgument("at") { nullable = true; defaultValue = null },
      navArgument("q") { nullable = true; defaultValue = null },
    ),
    transition = FluidPageTransition.Expand,
  ) { entry ->
    SessionRoute(
      onOpenNote = actions::openNote,
      onBack = { host.popBackStack() },
      onJumpUsed = { entry.savedStateHandle[SESSION_JUMP_USED] = true },
      // Separare una sessione apre quella nuova al posto di questa: e' li' che si finisce il
      // lavoro, ed e' li' che si torna indietro da.
      onOpenSession = { id -> actions.replaceWith(host, Routes.session(id)) },
    )
  }
  composable(
    route = Routes.EDITOR,
    arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
    transition = FluidPageTransition.Expand,
    // Un campo di testo a pagina intera ha i suoi trascinamenti (le maniglie della selezione): il
    // back a dito parte solo dal bordo. Il `BackHandler` della pagina salva, in tutti e due i casi.
    backSwipe = FluidBackSwipe.EdgeOnly,
  ) {
    EditorRoute(onDone = { host.popBackStack() })
  }
  composable(Routes.IMPORT, transition = FluidPageTransition.Expand) {
    ImportRoute(
      onClose = { host.popBackStack() },
      onOpenNote = { id -> actions.finishImport(host, id) },
    )
  }
  composable(
    route = Routes.SETTINGS_SECTION,
    arguments = listOf(navArgument("section") { type = NavType.StringType }),
  ) { entry ->
    SettingsSectionRoute(
      section = SettingsSection.fromRoute(entry.arguments?.getString("section")) ?: SettingsSection.SERVICES,
      onBack = { host.popBackStack() },
      onOpenSection = actions::openSettingsSection,
    )
  }
}

/**
 * Quanto spazio si lascia la pagina di una scheda in fondo, per la pillola che la copre: la barra
 * sta fuori dall'host delle pagine, e una pagina che si apre sopra (la nota, le impostazioni) non
 * ne ha bisogno. Lo da' la shell: sui tablet, con il rail o la barra laterale, vale zero.
 */
val LocalTabBarInset = compositionLocalOf { 0.dp }

/** Una scheda: lo spazio della pillola vale per lei e non per le pagine che le si aprono sopra. */
@Composable
private fun TabPage(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalFluidBottomInset provides LocalTabBarInset.current, content = content)
}

/**
 * Passa a una scheda principale mantenendo lo stato delle altre.
 *
 * La Home resta sempre in fondo alla pila: `popUpTo` su di lei trova sempre qualcosa da togliere,
 * e il back da una scheda torna alla Home e da li' esce. Se la Home potesse uscire dalla pila (come
 * nelle app con una scheda di partenza che non e' la prima) servirebbe `popUpTo(graph.id)` senza
 * `saveState`, o le schede si accumulerebbero.
 */
private fun NavHostController.navigateTopLevelRoute(targetRoute: String) {
  val startDestination = graph.findStartDestination()
  val targetsStart = targetRoute.substringBefore('?') == startDestination.route?.substringBefore('?')
  navigate(targetRoute) {
    popUpTo(startDestination.id) { saveState = true }
    launchSingleTop = true
    restoreState = !targetsStart
  }
}
