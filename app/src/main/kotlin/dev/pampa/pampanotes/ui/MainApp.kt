package dev.pampa.pampanotes.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.antigravity.fluidengine.ui.fluid.FluidBarFold
import dev.antigravity.fluidengine.ui.fluid.FluidChromeController
import dev.antigravity.fluidengine.ui.fluid.FluidFoldAlignment
import dev.antigravity.fluidengine.ui.fluid.FluidFoldingTabBar
import dev.antigravity.fluidengine.ui.fluid.FluidFoldingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicyProvider
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationHost
import dev.antigravity.fluidengine.ui.fluid.FluidPaneScaffold
import dev.antigravity.fluidengine.ui.fluid.FluidScrollToTopBus
import dev.antigravity.fluidengine.ui.fluid.FluidTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidTabItem
import dev.antigravity.fluidengine.ui.fluid.FluidTabRail
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.ProvideFluidChrome
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.fluidPaneLayout
import dev.antigravity.fluidengine.ui.fluid.rememberFluidBarFold
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeController
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeScrollConnection
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidScreenSurface
import dev.antigravity.fluidengine.ui.theme.LocalRouteMotionSignals
import dev.antigravity.fluidengine.ui.theme.fluidTouchOriginTracker
import dev.antigravity.fluidengine.ui.theme.rememberFluidTouchOrigin
import dev.antigravity.fluidengine.ui.theme.rememberRouteMotionSignals
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.common.LocalSubjectRegistry
import dev.pampa.pampanotes.ui.common.SubjectRegistry
import dev.pampa.pampanotes.ui.importing.ImportRequest
import dev.pampa.pampanotes.ui.nav.PampaNavActions
import dev.pampa.pampanotes.ui.nav.PampaNavHost
import dev.pampa.pampanotes.ui.nav.PampaSidebar
import dev.pampa.pampanotes.ui.nav.Routes
import dev.pampa.pampanotes.ui.nav.detailDestinations
import dev.pampa.pampanotes.ui.nav.listDestinations
import dev.pampa.pampanotes.ui.nav.syncPanes
import dev.pampa.pampanotes.ui.theme.PampaTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidAmbientSurface
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.pampa.pampanotes.ui.common.ambientToneOf

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
  // La materia in cui ci si trova colora l'app: le schermate si iscrivono qui, e il tema legge.
  val subjects = remember { SubjectRegistry() }

  PampaTheme(settings = engineSettings, subject = subjects.current) {
    val chromeController = rememberFluidChromeController()
    CompositionLocalProvider(
      LocalFluidNotificationHostState provides notificationHostState,
      LocalFluidGlassModalHostState provides glassModalHostState,
      LocalRouteMotionSignals provides routeMotionSignals,
      LocalSubjectRegistry provides subjects,
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

/**
 * La shell: i pannelli, la chrome che li affianca, e i due padroni di casa della navigazione.
 *
 * I controller stanno qui e non dentro i pannelli: in una finestra stretta il dettaglio non si
 * emette, il suo `NavController` conserva lo stack, e una rotazione e' un cambio di layout e
 * basta — con [syncPanes] che sposta la nota aperta nel pannello giusto.
 */
@Composable
private fun AppShell(
  chromeController: FluidChromeController,
  incomingIntents: Flow<Intent>,
  onIntent: (Intent) -> Boolean,
  onPickFiles: (List<android.net.Uri>, String?) -> Unit,
) {
  val listNav = rememberNavController()
  val detailNav = rememberNavController()
  val scrollToTop = remember { FluidScrollToTopBus() }
  val touchOrigin = rememberFluidTouchOrigin()
  // La materia che le schermate hanno dichiarato: da' il colore al fondale della finestra.
  val subject = LocalSubjectRegistry.current?.current

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val layout = remember(maxWidth) { fluidPaneLayout(maxWidth, hasSide = true, hasRail = true) }
    val twoPane = rememberUpdatedState(layout.twoPane)

    // Il selettore file e le azioni si conoscono a vicenda: il selettore, finito, apre il wizard;
    // le azioni lanciano il selettore. Il rimando passa da uno stato perche' i due nascono in
    // ordine e nessuno dei due puo' nascere per primo.
    val launchPicker = remember { mutableStateOf<() -> Unit>({}) }
    val actions = remember(listNav, detailNav, touchOrigin) {
      PampaNavActions(
        listNav = listNav,
        detailNav = detailNav,
        twoPane = { twoPane.value },
        touchOrigin = touchOrigin,
        pickFiles = { launchPicker.value() },
        pickFilesInto = { noteId ->
          onPickFiles(emptyList(), noteId)
          launchPicker.value()
        },
      )
    }
    // Il selettore file: la stessa lista di tipi che il manifest dichiara per la condivisione.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      if (uris.isNotEmpty()) {
        onPickFiles(uris, null)
        actions.openImport()
      }
    }
    launchPicker.value = { pickFiles.launch(ImportRequest.PICKER_MIME_TYPES) }

    LaunchedEffect(layout.twoPane) { syncPanes(listNav, detailNav, layout.twoPane) }

    LaunchedEffect(listNav, incomingIntents) {
      incomingIntents.collect { intent ->
        // Prima la condivisione, poi i deep link: un intent di SEND non e' un link e non ha una rotta.
        if (onIntent(intent)) actions.openImport() else listNav.handleDeepLink(intent)
      }
    }

    val listEntry by listNav.currentBackStackEntryAsState()
    val listRoute = listEntry?.destination?.route?.substringBefore("?")
    val selectedFolderId = listEntry?.takeIf { it.destination.route == Routes.FOLDER }?.arguments?.getString("folderId")
    // La pillola in basso solo dove non c'e' altra chrome: col rail o con la barra laterale
    // sarebbe la stessa cosa detta due volte.
    val showTabBar = !layout.showRail && !layout.twoPane && listRoute in Routes.topLevelSet

    val tabItems = listOf(
      FluidTabItem(Routes.HOME, stringResource(R.string.tab_home), Icons.Rounded.Home),
      FluidTabItem(Routes.FOLDERS, stringResource(R.string.tab_folders), Icons.Rounded.GridView),
      FluidTabItem(Routes.MORE, stringResource(R.string.tab_more), Icons.Rounded.MoreHoriz),
    )

    // La policy avvolge tutta la shell: anche la chrome in cima e' movimento, non solo il contenuto.
    FluidMotionPolicyProvider {
      TabBarScaffold(
        items = tabItems,
        currentRoute = listRoute,
        showTabBar = showTabBar,
        chromeController = chromeController,
        scrollToTop = scrollToTop,
        onSelect = { item -> actions.switchTopLevel(item.route) },
        onReselect = { scrollToTop.request() },
      ) { backdrop ->
        // Un fondale solo sotto tutta la finestra quando i pannelli sono piu' di uno, del colore
        // della materia in cui ci si trova. Senza, ogni pannello dipinge il suo e l'app si legge
        // come tre telefoni appoggiati uno accanto all'altro.
        FluidAmbientSurface(ambient = if (layout.panes.size > 1) windowAmbient(subject) else null) {
        FluidPaneScaffold(
          layout = layout,
          rail = {
            Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
              FluidTabRail(
                items = tabItems,
                selectedRoute = listRoute,
                onSelect = { item -> actions.switchTopLevel(item.route) },
                onReselect = { scrollToTop.request() },
                backdrop = backdrop,
                modifier = Modifier
                  .windowInsetsPadding(WindowInsets.systemBars)
                  .padding(horizontal = FluidTabBarDefaults.HorizontalMargin)
                  .height(FluidTabBarDefaults.Height * tabItems.size),
              )
            }
          },
          side = {
            PampaSidebar(
              selectedRoute = listRoute,
              selectedFolderId = selectedFolderId,
              backdrop = backdrop,
              onHome = { actions.switchTopLevel(Routes.HOME) },
              onFolder = actions::showFolder,
              onAllFolders = { actions.switchTopLevel(Routes.FOLDERS) },
              onSearch = actions::openSearch,
              onMore = { actions.switchTopLevel(Routes.MORE) },
            )
          },
          list = {
            PampaNavHost(
              controller = listNav,
              startDestination = Routes.HOME,
              modifier = Modifier.fillMaxSize().fluidTouchOriginTracker(touchOrigin),
            ) {
              listDestinations(actions, listNav)
              // Su una pagina stretta il dettaglio sta qui, sopra chi lo ha aperto.
              detailDestinations(actions, listNav)
            }
          },
          detail = {
            PampaNavHost(
              controller = detailNav,
              startDestination = Routes.DETAIL_EMPTY,
              modifier = Modifier.fillMaxSize().fluidTouchOriginTracker(touchOrigin),
            ) {
              composable(Routes.DETAIL_EMPTY) { EmptyDetail() }
              detailDestinations(actions, detailNav)
            }
          },
        )
        }
      }
    }
  }
}

/**
 * Il fondale della finestra: il tono della materia in cui ci si trova, con un motivo calmo.
 *
 * Il motivo e' `Glow` e non quelli piu' marcati: coprendo tutta la finestra, un motivo con una
 * struttura diventa carta da parati, mentre su una pagina sola era una decorazione che si vedeva
 * appena. Il tono invece e' quello della materia — e' tutta la questione.
 */
@Composable
private fun windowAmbient(subject: dev.pampa.pampanotes.ui.common.SubjectIdentity?): FluidAmbient =
  FluidAmbient(
    tone = subject?.let { ambientToneOf(it.tone) } ?: FluidHeroTone.PrimaryToSecondary,
    motif = FluidHeroMotif.Glow,
  )

/** Il dettaglio quando non c'e' niente di aperto: una pagina calma, non una pagina vuota. */
@Composable
private fun EmptyDetail() {
  Box(
    // Niente fondo suo: lo dipinge la finestra, e un rettangolo opaco qui sarebbe la cucitura.
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    FluidEmptyState(
      title = stringResource(R.string.detail_empty_title),
      detail = stringResource(R.string.detail_empty_detail),
    )
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
  content: @Composable (GlassBackdropState) -> Unit,
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
      Box(modifier = Modifier.fillMaxSize()) { content(backdrop) }
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
 * La barra ha il suo tempo, piu' rapido della pagina: e' chrome, deve essersi gia' tolta di mezzo
 * quando la schermata nuova finisce di arrivare.
 */
private fun barSlideSpec() = FluidMotion.intOffset(
  dampingRatio = FluidMotion.DampingChrome,
  stiffness = FluidMotion.ResponseSnappy,
)
