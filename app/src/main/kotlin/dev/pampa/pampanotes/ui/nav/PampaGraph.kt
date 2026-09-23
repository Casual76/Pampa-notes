package dev.pampa.pampanotes.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.theme.FluidRouteMotion
import dev.antigravity.fluidengine.ui.theme.FluidRouteMotionHost
import dev.antigravity.fluidengine.ui.theme.FluidTouchOriginState
import dev.antigravity.fluidengine.ui.theme.LocalRouteMotionSignals
import dev.antigravity.fluidengine.ui.theme.MotionOrigin
import dev.antigravity.fluidengine.ui.theme.RouteMotionSignals
import dev.pampa.pampanotes.ui.editor.EditorRoute
import dev.pampa.pampanotes.ui.folder.FolderRoute
import dev.pampa.pampanotes.ui.folders.FoldersRoute
import dev.pampa.pampanotes.ui.home.HomeRoute
import dev.pampa.pampanotes.ui.importing.ImportRoute
import dev.pampa.pampanotes.ui.jobs.JobsRoute
import dev.pampa.pampanotes.ui.more.MoreRoute
import dev.pampa.pampanotes.ui.note.NoteRoute
import dev.pampa.pampanotes.ui.search.SearchRoute
import dev.pampa.pampanotes.ui.session.SessionRoute
import dev.pampa.pampanotes.ui.settings.SettingsRoute
import dev.pampa.pampanotes.ui.settings.SettingsSectionRoute
import kotlin.math.roundToInt

/**
 * Le azioni di navigazione dell'app, con due padroni di casa.
 *
 * Su una pagina larga la lista e il dettaglio hanno un `NavHost` ciascuno: aprire una nota dalla
 * lista mette la nota nel dettaglio e la lista resta dov'e'. Su una pagina stretta il dettaglio
 * non c'e' e tutto passa dalla lista, come sempre. Chi chiama non lo sa: chiede «apri questa
 * nota», e dove finisce dipende da quanto e' larga la finestra in quel momento.
 */
class PampaNavActions(
  val listNav: NavHostController,
  val detailNav: NavHostController,
  private val twoPane: () -> Boolean,
  private val touchOrigin: FluidTouchOriginState,
  /** Apre il selettore di file; quello che sceglie finisce nel wizard di import. */
  val pickFiles: () -> Unit,
  /** Lo stesso, ma dentro una nota gia' esistente. */
  val pickFilesInto: (String) -> Unit,
) {
  fun openNote(id: String, tab: String? = null) = openDetail(Routes.note(id, tab), fresh = true)
  fun openSession(id: String) = openDetail(Routes.session(id))

  /** La sessione da «Riprendi ad ascoltare»: si apre e riparte dal punto in cui ci si era fermati. */
  fun resumeSession(id: String) = openDetail(Routes.session(id, play = true), fresh = true)
  fun openEditor(noteId: String) = openDetail(Routes.editor(noteId))
  fun openImport() = openDetail(Routes.IMPORT, fresh = true)
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
    listNav.currentBackStackEntry?.savedStateHandle?.writePeerMotion()
  }

  /** Dalla barra laterale: una materia al posto di quella di prima, sopra la Home. */
  fun showFolder(id: String) {
    closeDetail()
    listNav.navigate(Routes.folder(id)) {
      popUpTo(Routes.HOME)
      launchSingleTop = true
    }
    listNav.currentBackStackEntry?.savedStateHandle?.writePeerMotion()
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
      navigateExpanding(listNav, route)
    }
  }

  private fun openList(route: String) {
    closeDetail()
    navigateExpanding(listNav, route)
  }

  private fun openDetail(route: String, fresh: Boolean = false) {
    if (!twoPane()) {
      navigateExpanding(listNav, route)
      return
    }
    // Una nota nuova dalla lista prende il posto di quella aperta: due note impilate nel dettaglio
    // sono una pila che nessuno ha chiesto. Sessione ed editor invece si aprono dentro la nota, e
    // il back li richiude su di lei.
    detailNav.navigate(route) {
      if (fresh) popUpTo(Routes.DETAIL_EMPTY)
      launchSingleTop = true
    }
    detailNav.currentBackStackEntry?.savedStateHandle?.writeExpandMotion(touchOrigin.origin)
  }

  // La destinazione porta con se' sia il punto da cui e' stata aperta sia il fatto che sia stata
  // *aperta* invece che raggiunta di lato: tornare indietro disfa lo stesso movimento.
  private fun navigateExpanding(host: NavHostController, route: String) {
    host.navigate(route)
    host.currentBackStackEntry?.savedStateHandle?.writeExpandMotion(touchOrigin.origin)
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

/** La rotta di dettaglio di una voce dello stack, ricostruita dai suoi argomenti. */
private fun detailRouteOf(entry: NavBackStackEntry): String? {
  val args = entry.arguments
  return when (entry.destination.route) {
    Routes.NOTE -> Routes.note(args?.getString("noteId") ?: return null, args.getString("tab"))
    Routes.SESSION -> Routes.session(args?.getString("sessionId") ?: return null)
    Routes.EDITOR -> Routes.editor(args?.getString("noteId") ?: return null)
    Routes.SETTINGS_SECTION -> Routes.settingsSection(args?.getString("section") ?: return null)
    Routes.IMPORT -> Routes.IMPORT
    else -> null
  }
}

/** Un `NavHost` con il movimento di rotta dell'app: uno solo, per la lista e per il dettaglio. */
@Composable
fun PampaNavHost(
  controller: NavHostController,
  startDestination: String,
  modifier: Modifier = Modifier,
  builder: NavGraphBuilder.() -> Unit,
) {
  val motionSignals = LocalRouteMotionSignals.current
  NavHost(
    navController = controller,
    startDestination = startDestination,
    modifier = modifier,
    enterTransition = {
      routeEnterTransition(
        decision = motionSignals.resolve(initialState, targetState, isPop = false),
        origin = targetState.savedStateHandle.readMotionOrigin(),
        isPop = false,
      )
    },
    exitTransition = {
      routeExitTransition(
        decision = motionSignals.resolve(initialState, targetState, isPop = false),
        origin = targetState.savedStateHandle.readMotionOrigin(),
        isPop = false,
      )
    },
    popEnterTransition = {
      routeEnterTransition(
        decision = motionSignals.resolve(initialState, targetState, isPop = true),
        origin = initialState.savedStateHandle.readMotionOrigin(),
        isPop = true,
      )
    },
    popExitTransition = {
      routeExitTransition(
        decision = motionSignals.resolve(initialState, targetState, isPop = true),
        origin = initialState.savedStateHandle.readMotionOrigin(),
        isPop = true,
      )
    },
    builder = builder,
  )
}

/** Le pagine che elencano: le schede principali, le cartelle, cerca, lavori, impostazioni. */
fun NavGraphBuilder.listDestinations(actions: PampaNavActions, host: NavHostController) {
  composable(Routes.HOME) {
    FluidRouteMotionHost(this@composable) {
      HomeRoute(
        onOpenNote = actions::openNote,
        onImport = actions.pickFiles,
        onOpenJobs = actions::openJobs,
        onResumeSession = actions::resumeSession,
      )
    }
  }
  composable(Routes.FOLDERS) {
    FluidRouteMotionHost(this@composable) {
      FoldersRoute(
        onOpenFolder = actions::openFolder,
        onImport = actions.pickFiles,
      )
    }
  }
  composable(Routes.MORE) {
    FluidRouteMotionHost(this@composable) {
      MoreRoute(
        onOpenSearch = actions::openSearch,
        onOpenJobs = actions::openJobs,
        onOpenSettings = actions::openSettings,
        onImport = actions.pickFiles,
      )
    }
  }
  composable(Routes.SEARCH) {
    FluidRouteMotionHost(this@composable) {
      SearchRoute(
        onBack = { host.popBackStack() },
        onOpenNote = actions::openNote,
      )
    }
  }
  composable(Routes.JOBS) {
    FluidRouteMotionHost(this@composable) { JobsRoute(onBack = { host.popBackStack() }) }
  }
  composable(Routes.SETTINGS) {
    FluidRouteMotionHost(this@composable) {
      SettingsRoute(onBack = { host.popBackStack() }, onOpenSection = actions::openSettingsSection)
    }
  }
  composable(
    route = Routes.FOLDER,
    arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
  ) { entry ->
    FluidRouteMotionHost(this@composable) {
      FolderRoute(
        folderId = entry.arguments?.getString("folderId").orEmpty(),
        onBack = { host.popBackStack() },
        onOpenFolder = actions::openFolder,
        onOpenNote = actions::openNote,
        onImport = actions.pickFiles,
      )
    }
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
  ) { entry ->
    FluidRouteMotionHost(this@composable) {
      NoteRoute(
        initialTab = entry.arguments?.getString("tab"),
        onBack = { host.popBackStack() },
        onEdit = actions::openEditor,
        onOpenSession = actions::openSession,
        onImportInto = actions.pickFilesInto,
      )
    }
  }
  composable(
    route = Routes.SESSION,
    arguments = listOf(
      navArgument("sessionId") { type = NavType.StringType },
      navArgument("play") { nullable = true; defaultValue = null },
    ),
  ) {
    FluidRouteMotionHost(this@composable) {
      SessionRoute(
        onBack = { host.popBackStack() },
        // Separare una sessione apre quella nuova al posto di questa: e' li' che si finisce il
        // lavoro, ed e' li' che si torna indietro da.
        onOpenSession = { id -> actions.replaceWith(host, Routes.session(id)) },
      )
    }
  }
  composable(
    route = Routes.EDITOR,
    arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
  ) {
    FluidRouteMotionHost(this@composable) {
      EditorRoute(onDone = { host.popBackStack() })
    }
  }
  composable(Routes.IMPORT) {
    FluidRouteMotionHost(this@composable) {
      ImportRoute(
        onClose = { host.popBackStack() },
        onOpenNote = { id -> actions.replaceWith(host, Routes.note(id)) },
      )
    }
  }
  composable(
    route = Routes.SETTINGS_SECTION,
    arguments = listOf(navArgument("section") { type = NavType.StringType }),
  ) { entry ->
    FluidRouteMotionHost(this@composable) {
      SettingsSectionRoute(
        section = SettingsSection.fromRoute(entry.arguments?.getString("section")) ?: SettingsSection.SERVICES,
        onBack = { host.popBackStack() },
        onOpenSection = actions::openSettingsSection,
      )
    }
  }
}

/**
 * Passa a una scheda principale mantenendo lo stato delle altre.
 *
 * Ripristinare la rotta di partenza qui ripristinerebbe anche la scheda appena tolta da sotto, e il
 * back dalla Home riaprirebbe quella scheda. Il suo stato resta comunque salvato per dopo.
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

/**
 * Un solo movimento gerarchico per tutta l'app: la destinazione cresce dal punto toccato mentre la
 * pagina che copre arretra appena sotto di lei.
 *
 * Tutto a durata, e non e' una preferenza di stile: il back predittivo guida queste transizioni
 * *scorrendole*, e solo una curva finita e monotona si puo' scorrere.
 */
private fun expandSpec() = tween<Float>(durationMillis = FluidMotion.DurationExpand, easing = FluidMotion.EaseEmphasized)

private fun collapseSpec() = tween<Float>(durationMillis = FluidMotion.DurationCollapse, easing = FluidMotion.EaseEmphasized)

private fun peerSlideSpec() = tween<IntOffset>(durationMillis = FluidMotion.DurationPeer, easing = FluidMotion.EaseEmphasized)

/** La pagina che viene coperta: lascia il passo mentre la nuova sta ancora diventando opaca. */
private fun coveredFadeOut() = tween<Float>(durationMillis = FluidMotion.DurationRouteFadeOut, easing = FluidMotion.EaseIn)

/**
 * La pagina che viene congedata: resta solida mentre si stringe e sparisce solo alla fine. Una
 * pagina che comincia a dissolversi appena inizia il gesto si legge come una pagina che si sfalda.
 */
private fun dismissedFadeOut() = tween<Float>(durationMillis = 170, delayMillis = 90, easing = FluidMotion.EaseIn)

private fun peerSlidePx(width: Int, direction: Int): Int =
  (width * FluidMotion.PeerSlideFraction * direction).roundToInt()

/**
 * Risolve il movimento fra due voci e registra, per i livelli che stanno per disegnare, se la
 * pagina che se ne va deve perdere il fuoco mentre esce.
 */
private fun RouteMotionSignals.resolve(
  initialState: NavBackStackEntry,
  targetState: NavBackStackEntry,
  isPop: Boolean,
): RouteMotionDecision {
  val carrier = if (isPop) initialState else targetState
  val decision = decideRouteMotion(
    fromRoute = initialState.destination.route,
    toRoute = targetState.destination.route,
    requestedKind = carrier.savedStateHandle.readMotionKind(),
  )
  hierarchical = decision.kind == RouteMotionKind.Expand
  return decision
}

private fun routeEnterTransition(
  decision: RouteMotionDecision,
  origin: MotionOrigin,
  isPop: Boolean,
): EnterTransition = when (decision.kind) {
  // Le pari scorrono di lato insieme. Opache dal primo fotogramma: una dissolvenza incrociata
  // tenuta ferma a meta' da un back trascinato e' due schede stampate una sopra l'altra.
  RouteMotionKind.TopLevelSwitch -> if (decision.direction == 0) {
    fadeIn(tween(FluidMotion.DurationPeerFadeIn, easing = FluidMotion.EaseOut))
  } else {
    slideInHorizontally(peerSlideSpec()) { width -> peerSlidePx(width, decision.direction) }
  }

  RouteMotionKind.Expand -> if (isPop) {
    // Tornando indietro, la pagina sotto viene scoperta: rientra da poco troppo vicino, che e'
    // esattamente dove l'andata l'aveva lasciata.
    scaleIn(animationSpec = collapseSpec(), initialScale = FluidRouteMotion.ExpandParentScale, transformOrigin = origin.toTransformOrigin())
  } else {
    scaleIn(animationSpec = expandSpec(), initialScale = FluidRouteMotion.ExpandInitialScale, transformOrigin = origin.toTransformOrigin())
  }
}

private fun routeExitTransition(
  decision: RouteMotionDecision,
  origin: MotionOrigin,
  isPop: Boolean,
): ExitTransition = when (decision.kind) {
  RouteMotionKind.TopLevelSwitch -> {
    val fade = fadeOut(tween(FluidMotion.DurationPeerFadeOut, easing = FluidMotion.EaseIn))
    if (decision.direction == 0) fade else fade + slideOutHorizontally(peerSlideSpec()) { width -> peerSlidePx(width, -decision.direction) }
  }

  RouteMotionKind.Expand -> if (isPop) {
    fadeOut(dismissedFadeOut()) + scaleOut(animationSpec = collapseSpec(), targetScale = FluidRouteMotion.ExpandInitialScale, transformOrigin = origin.toTransformOrigin())
  } else {
    fadeOut(coveredFadeOut()) + scaleOut(animationSpec = expandSpec(), targetScale = FluidRouteMotion.ExpandParentScale, transformOrigin = origin.toTransformOrigin())
  }
}
