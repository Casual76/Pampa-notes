package dev.pampa.pampanotes.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.antigravity.fluidengine.ui.fluid.FluidBarFold
import dev.antigravity.fluidengine.ui.fluid.FluidChromeController
import dev.antigravity.fluidengine.ui.fluid.FluidFoldAlignment
import dev.antigravity.fluidengine.ui.fluid.FluidFoldingTabBar
import dev.antigravity.fluidengine.ui.fluid.FluidFoldingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicyProvider
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationHost
import dev.antigravity.fluidengine.ui.fluid.FluidScrollToTopBus
import dev.antigravity.fluidengine.ui.fluid.FluidTabItem
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.ProvideFluidChrome
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.rememberFluidBarFold
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeController
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeScrollConnection
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidRouteMotion
import dev.antigravity.fluidengine.ui.theme.FluidRouteMotionHost
import dev.antigravity.fluidengine.ui.theme.FluidScreenSurface
import dev.antigravity.fluidengine.ui.theme.LocalRouteMotionSignals
import dev.antigravity.fluidengine.ui.theme.MotionOrigin
import dev.antigravity.fluidengine.ui.theme.RouteMotionSignals
import dev.antigravity.fluidengine.ui.theme.fluidTouchOriginTracker
import dev.antigravity.fluidengine.ui.theme.rememberFluidTouchOrigin
import dev.antigravity.fluidengine.ui.theme.rememberRouteMotionSignals
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.editor.EditorRoute
import dev.pampa.pampanotes.ui.folder.FolderRoute
import dev.pampa.pampanotes.ui.home.HomeRoute
import dev.pampa.pampanotes.ui.importing.ImportRequest
import dev.pampa.pampanotes.ui.importing.ImportRoute
import dev.pampa.pampanotes.ui.folders.FoldersRoute
import dev.pampa.pampanotes.ui.jobs.JobsRoute
import dev.pampa.pampanotes.ui.more.MoreRoute
import dev.pampa.pampanotes.ui.note.NoteRoute
import dev.pampa.pampanotes.ui.nav.RouteMotionDecision
import dev.pampa.pampanotes.ui.nav.RouteMotionKind
import dev.pampa.pampanotes.ui.nav.Routes
import dev.pampa.pampanotes.ui.nav.decideRouteMotion
import dev.pampa.pampanotes.ui.nav.readMotionKind
import dev.pampa.pampanotes.ui.nav.readMotionOrigin
import dev.pampa.pampanotes.ui.nav.writeExpandMotion
import dev.pampa.pampanotes.ui.nav.writePeerMotion
import dev.pampa.pampanotes.ui.search.SearchRoute
import dev.pampa.pampanotes.ui.settings.SettingsRoute
import dev.pampa.pampanotes.ui.theme.PampaTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * La radice: tema, host dei modali e delle notifiche, barra di navigazione e grafo delle rotte.
 *
 * Tutto quello che sta sopra la navigazione vive qui, una volta sola: due host di notifiche in
 * due schermate diverse sono due code che non si vedono fra loro.
 */
@Composable
fun MainApp(
  viewModel: MainViewModel = hiltViewModel(),
  incomingIntents: Flow<Intent> = emptyFlow(),
) {
  val engineSettings by viewModel.engineSettings.collectAsStateWithLifecycle()
  val notificationHostState = rememberFluidNotificationHostState()
  val glassModalHostState = rememberFluidGlassModalHostState()
  val routeMotionSignals = rememberRouteMotionSignals()

  PampaTheme(settings = engineSettings) {
    val chromeController = rememberFluidChromeController()
    CompositionLocalProvider(
      LocalFluidNotificationHostState provides notificationHostState,
      LocalFluidGlassModalHostState provides glassModalHostState,
      LocalRouteMotionSignals provides routeMotionSignals,
    ) {
      FluidScreenSurface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
          // La pagina sotto un modale di vetro resta visibile — e' il senso del materiale — quindi
          // va tolta all'accessibilita' a mano, o TalkBack cammina dentro allo scrim.
          Box(modifier = Modifier.fillMaxSize().fluidGlassModalObscured()) {
            AppShell(
              chromeController = chromeController,
              incomingIntents = incomingIntents,
              onIntent = viewModel::onIntent,
              onPickFiles = viewModel::onFilesPicked,
            )
          }
          FluidGlassModalHost(
            state = glassModalHostState,
            backdrop = chromeController.activeBackdrop.value,
          )
          FluidNotificationHost(
            state = notificationHostState,
            backdrop = chromeController.activeBackdrop.value,
            modifier = Modifier.align(Alignment.TopCenter),
          )
        }
      }
    }
  }
}

@Composable
private fun AppShell(
  chromeController: FluidChromeController,
  incomingIntents: Flow<Intent>,
  onIntent: (Intent) -> Boolean,
  onPickFiles: (List<android.net.Uri>, String?) -> Unit,
) {
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route?.substringBefore("?")
  val showTabBar = currentRoute in Routes.topLevelSet

  val scrollToTop = remember { FluidScrollToTopBus() }
  val touchOrigin = rememberFluidTouchOrigin()
  val motionSignals = LocalRouteMotionSignals.current

  // La destinazione porta con se' sia il punto da cui e' stata aperta sia il fatto che sia stata
  // *aperta* invece che raggiunta di lato: tornare indietro disfa lo stesso movimento, comunque si
  // scelga di uscire.
  fun navigateRoute(route: String) {
    navController.navigate(route)
    navController.currentBackStackEntry?.savedStateHandle?.writeExpandMotion(touchOrigin.origin)
  }

  fun navigateTopLevel(route: String) {
    navController.navigateTopLevelRoute(route)
    navController.currentBackStackEntry?.savedStateHandle?.writePeerMotion()
  }

  // Il selettore file: la stessa lista di tipi che il manifest dichiara per la condivisione.
  val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    if (uris.isNotEmpty()) {
      onPickFiles(uris, null)
      navigateRoute(Routes.IMPORT)
    }
  }

  LaunchedEffect(navController, incomingIntents) {
    incomingIntents.collect { intent ->
      // Prima la condivisione, poi i deep link: un intent di SEND non e' un link e non ha una rotta.
      if (onIntent(intent)) {
        navController.navigate(Routes.IMPORT)
      } else {
        navController.handleDeepLink(intent)
      }
    }
  }

  val tabItems = listOf(
    FluidTabItem(Routes.HOME, stringResource(R.string.tab_home), Icons.Rounded.Home),
    FluidTabItem(Routes.FOLDERS, stringResource(R.string.tab_folders), Icons.Rounded.GridView),
    FluidTabItem(Routes.MORE, stringResource(R.string.tab_more), Icons.Rounded.MoreHoriz),
  )

  // La policy avvolge tutta la shell: anche la chrome in cima e' movimento, non solo il contenuto.
  FluidMotionPolicyProvider {
    TabBarScaffold(
      items = tabItems,
      currentRoute = currentRoute,
      showTabBar = showTabBar,
      chromeController = chromeController,
      scrollToTop = scrollToTop,
      onSelect = { item -> navigateTopLevel(item.route) },
      onReselect = { scrollToTop.request() },
    ) {
      NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier
          .fillMaxSize()
          .fluidTouchOriginTracker(touchOrigin),
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
      ) {
        composable(Routes.HOME) {
          FluidRouteMotionHost(this@composable) {
            HomeRoute(
              onOpenNote = { id -> navigateRoute(Routes.note(id)) },
              onImport = { pickFiles.launch(ImportRequest.PICKER_MIME_TYPES) },
              onOpenJobs = { navigateRoute(Routes.JOBS) },
            )
          }
        }
        composable(Routes.FOLDERS) {
          FluidRouteMotionHost(this@composable) {
            FoldersRoute(
              onOpenFolder = { id -> navigateRoute(Routes.folder(id)) },
              onImport = { pickFiles.launch(ImportRequest.PICKER_MIME_TYPES) },
            )
          }
        }
        composable(Routes.MORE) {
          FluidRouteMotionHost(this@composable) {
            MoreRoute(
              onOpenSearch = { navigateRoute(Routes.SEARCH) },
              onOpenJobs = { navigateRoute(Routes.JOBS) },
              onOpenSettings = { navigateRoute(Routes.SETTINGS) },
              onImport = { pickFiles.launch(ImportRequest.PICKER_MIME_TYPES) },
            )
          }
        }
        composable(Routes.SEARCH) {
          FluidRouteMotionHost(this@composable) {
            SearchRoute(
              onBack = { navController.popBackStack() },
              onOpenNote = { id -> navigateRoute(Routes.note(id)) },
            )
          }
        }
        composable(Routes.JOBS) {
          FluidRouteMotionHost(this@composable) { JobsRoute(onBack = { navController.popBackStack() }) }
        }
        composable(Routes.SETTINGS) {
          FluidRouteMotionHost(this@composable) { SettingsRoute(onBack = { navController.popBackStack() }) }
        }
        composable(
          route = Routes.FOLDER,
          arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
        ) { entry ->
          FluidRouteMotionHost(this@composable) {
            FolderRoute(
              folderId = entry.arguments?.getString("folderId").orEmpty(),
              onBack = { navController.popBackStack() },
              onOpenFolder = { id -> navigateRoute(Routes.folder(id)) },
              onOpenNote = { id -> navigateRoute(Routes.note(id)) },
              onImport = { pickFiles.launch(ImportRequest.PICKER_MIME_TYPES) },
            )
          }
        }
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
              onBack = { navController.popBackStack() },
              onEdit = { id -> navigateRoute(Routes.editor(id)) },
              onImportInto = { id ->
                onPickFiles(emptyList(), id)
                pickFiles.launch(ImportRequest.PICKER_MIME_TYPES)
              },
            )
          }
        }
        composable(
          route = Routes.EDITOR,
          arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
        ) {
          FluidRouteMotionHost(this@composable) {
            EditorRoute(onDone = { navController.popBackStack() })
          }
        }
        composable(Routes.IMPORT) {
          FluidRouteMotionHost(this@composable) {
            ImportRoute(
              onClose = { navController.popBackStack() },
              onOpenNote = { id ->
                navController.popBackStack()
                navigateRoute(Routes.note(id))
              },
            )
          }
        }
      }
    }
  }
}

/** La pillola in basso e lo spazio che la pagina le lascia. */
@Composable
private fun TabBarScaffold(
  items: List<FluidTabItem>,
  currentRoute: String?,
  showTabBar: Boolean,
  chromeController: FluidChromeController,
  scrollToTop: FluidScrollToTopBus,
  onSelect: (FluidTabItem) -> Unit,
  onReselect: (FluidTabItem) -> Unit,
  content: @Composable () -> Unit,
) {
  val fallbackBackdrop = rememberGlassBackdrop()
  val barFold: FluidBarFold = rememberFluidBarFold()
  val chromeScroll = rememberFluidChromeScrollConnection(controller = chromeController, enabled = showTabBar)

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .nestedScroll(chromeScroll)
      .then(if (showTabBar) Modifier.nestedScroll(barFold.connection) else Modifier),
  ) {
    val bottomInset = if (showTabBar) FluidFoldingTabBarDefaults.ContentInset else 0.dp
    val backdrop = chromeController.activeBackdrop.value ?: fallbackBackdrop

    ProvideFluidChrome(controller = chromeController, bottomInset = bottomInset, scrollToTop = scrollToTop) {
      Box(modifier = Modifier.fillMaxSize()) { content() }
    }

    AnimatedVisibility(
      visible = showTabBar,
      enter = slideInVertically(animationSpec = barSlideSpec()) { it },
      exit = slideOutVertically(animationSpec = barSlideSpec()) { it },
      modifier = Modifier
        // Su uno schermo largo la barra non si stira da bordo a bordo: resta larga come su un
        // telefono e sta dalla parte del pollice, dove poi si raccoglie ripiegandosi.
        .align(if (maxWidth >= 600.dp) Alignment.BottomStart else Alignment.BottomCenter)
        .widthIn(max = 500.dp)
        .navigationBarsPadding()
        .padding(
          horizontal = FluidFoldingTabBarDefaults.HorizontalMargin,
          vertical = FluidFoldingTabBarDefaults.BottomMargin,
        ),
    ) {
      FluidFoldingTabBar(
        items = items,
        selectedRoute = currentRoute,
        onSelect = onSelect,
        onReselect = onReselect,
        backdrop = backdrop,
        fold = { barFold.progress.value },
        onExpandRequest = barFold::unfold,
        foldAlignment = FluidFoldAlignment.Start,
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
 * La barra ha il suo tempo, piu' rapido della pagina: e' chrome, deve essersi gia' tolta di mezzo
 * quando la schermata nuova finisce di arrivare.
 */
private fun barSlideSpec() = FluidMotion.intOffset(
  dampingRatio = FluidMotion.DampingChrome,
  stiffness = FluidMotion.ResponseSnappy,
)

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
