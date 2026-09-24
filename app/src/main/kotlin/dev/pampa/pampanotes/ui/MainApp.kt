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
import androidx.compose.material.icons.rounded.GraphicEq
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
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidBarFold
import dev.antigravity.fluidengine.ui.fluid.FluidChromeController
import dev.antigravity.fluidengine.ui.fluid.FluidFoldAlignment
import dev.antigravity.fluidengine.ui.fluid.FluidFoldingTabBar
import dev.antigravity.fluidengine.ui.fluid.FluidFoldingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicyProvider
import dev.antigravity.fluidengine.ui.fluid.FluidNotification
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationHost
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationTone
import dev.pampa.pampanotes.ui.nav.SettingsSection
import kotlinx.coroutines.launch
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
import dev.pampa.pampanotes.work.NotificationPermissionGate
import dev.pampa.pampanotes.ui.common.LocalSubjectRegistry
import dev.pampa.pampanotes.ui.common.SubjectRegistry
import dev.pampa.pampanotes.ui.importing.ImportRequest
import dev.pampa.pampanotes.ui.onboarding.OnboardingRoute
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
  val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
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
            // Finche' non si sa se il primo avvio e' stato fatto non si disegna niente: mezzo
            // secondo di pagina vuota e' meno peggio di un lampo di benvenuto a ogni apertura.
            when (onboardingDone) {
              null -> Unit
              false -> {
                OnboardingLinks(
                  incomingIntents = incomingIntents,
                  onLinkIntent = viewModel::onLinkIntent,
                  linkApplied = viewModel.linkApplied,
                )
                OnboardingRoute(onDone = viewModel::completeOnboarding)
              }
              true -> {
                AppShell(
                  chromeController = chromeController,
                  incomingIntents = incomingIntents,
                  onIntent = viewModel::onIntent,
                  linkApplied = viewModel.linkApplied,
                  onPickFiles = viewModel::onFilesPicked,
                  onPickIntoFolder = viewModel::onPickIntoFolder,
                  onPickPersonal = viewModel::onPickPersonal,
                  onPickerCancelled = viewModel::onPickerCancelled,
                )
                // Il permesso delle notifiche si chiede la prima volta che c'e' un lavoro in coda:
                // prima non si capirebbe a cosa serve, e senza «fatto» e «non riuscita» non arrivano.
                NotificationPermissionGate()
              }
            }
          }
          val pendingLink by viewModel.pendingLink.collectAsStateWithLifecycle()
          pendingLink?.let { link ->
            PendingLinkAlert(
              link = link,
              onConfirm = viewModel::confirmPendingLink,
              onDismiss = viewModel::dismissPendingLink,
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
 * La conferma di un link di configurazione: dice a chi andranno le note, o le registrazioni, prima
 * che ci vadano. L'host sta nel titolo del messaggio perche' e' la cosa da riconoscere: il proprio
 * computer, il proprio Worker, o un nome che non si e' mai visto.
 */
@Composable
private fun PendingLinkAlert(
  link: PendingLink,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  val host = remember(link.url) {
    runCatching { java.net.URI(link.url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: link.url
  }
  val (title, message) = when (link) {
    is PendingLink.Sync -> stringResource(R.string.link_confirm_sync_title) to stringResource(R.string.link_confirm_sync_message, host, link.url)
    is PendingLink.Endpoint -> stringResource(R.string.link_confirm_endpoint_title) to stringResource(R.string.link_confirm_endpoint_message, host, link.url)
  }
  FluidAlert(
    onDismissRequest = onDismiss,
    title = title,
    message = message,
    actions = listOf(
      FluidAlertAction(label = stringResource(R.string.link_confirm_connect), onClick = onConfirm, emphasis = FluidAlertAction.Emphasis.Preferred),
      FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = onDismiss),
    ),
  )
}

/**
 * I link di configurazione durante il primo avvio: il QR del companion e il link dell'indice si
 * possono collegare subito — con la stessa conferma della shell — e il passo «Chi trascrive» li
 * mostra gia' scritti. Tutto il resto — una condivisione, un «Apri con» — resta nel flusso (che ha
 * replay) e lo raccoglie la shell quando il primo avvio finisce. Un link gia' preso qui ha perso il
 * suo `data`, quindi la shell non lo richiede.
 */
@Composable
private fun OnboardingLinks(
  incomingIntents: Flow<Intent>,
  onLinkIntent: (Intent) -> IntentOutcome?,
  linkApplied: Flow<IntentOutcome>,
) {
  val notifications = LocalFluidNotificationHostState.current
  val syncLinkedTitle = stringResource(R.string.sync_linked_title)
  val syncLinkedMessage = stringResource(R.string.sync_linked)
  val endpointNotice = rememberEndpointLinkedNotice()
  LaunchedEffect(incomingIntents) {
    incomingIntents.collect { intent -> onLinkIntent(intent) }
  }
  LaunchedEffect(linkApplied) {
    linkApplied.collect { outcome ->
      val notice = when (outcome) {
        is IntentOutcome.EndpointLinked -> endpointNotice(outcome)
        is IntentOutcome.SyncLinked -> FluidNotification(
          id = "sync-linked",
          title = syncLinkedTitle,
          message = syncLinkedMessage.format(outcome.url),
          tone = FluidNotificationTone.Success,
        )
        else -> return@collect
      }
      launch { notifications?.show(notice) }
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
  onIntent: (Intent) -> IntentOutcome,
  linkApplied: Flow<IntentOutcome>,
  onPickFiles: (List<android.net.Uri>, String?) -> Unit,
  onPickIntoFolder: (String) -> Unit,
  onPickPersonal: () -> Unit,
  onPickerCancelled: () -> Unit,
) {
  val listNav = rememberNavController()
  val detailNav = rememberNavController()
  val scrollToTop = remember { FluidScrollToTopBus() }
  val touchOrigin = rememberFluidTouchOrigin()
  // La materia che le schermate hanno dichiarato: da' il colore al fondale della finestra.
  val subject = LocalSubjectRegistry.current?.current

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // Cosa c'e' aperto nel dettaglio: e' quello che decide se accanto alla barra laterale sta
    // l'elenco o la cosa aperta. Due pannelli, mai tre.
    val detailEntry by detailNav.currentBackStackEntryAsState()
    val detailOpen = detailEntry != null && detailEntry?.destination?.route != Routes.DETAIL_EMPTY
    val layout = remember(maxWidth, detailOpen) {
      fluidPaneLayout(maxWidth, hasSide = true, hasRail = true, showDetail = detailOpen)
    }
    val splits = rememberUpdatedState(layout.splits)

    // Il selettore file e le azioni si conoscono a vicenda: il selettore, finito, apre il wizard;
    // le azioni lanciano il selettore. Il rimando passa da uno stato perche' i due nascono in
    // ordine e nessuno dei due puo' nascere per primo.
    val launchPicker = remember { mutableStateOf<() -> Unit>({}) }
    // Scelti da dentro una nota: il wizard si apre sopra di lei invece di prenderne il posto.
    val pickingInto = remember { mutableStateOf(false) }
    val actions = remember(listNav, detailNav, touchOrigin) {
      PampaNavActions(
        listNav = listNav,
        detailNav = detailNav,
        twoPane = { splits.value },
        touchOrigin = touchOrigin,
        pickFiles = {
          pickingInto.value = false
          launchPicker.value()
        },
        pickFilesInto = { noteId ->
          pickingInto.value = true
          onPickFiles(emptyList(), noteId)
          launchPicker.value()
        },
        pickFilesIntoFolder = { folderId ->
          pickingInto.value = false
          onPickIntoFolder(folderId)
          launchPicker.value()
        },
        pickFilesPersonal = {
          pickingInto.value = false
          onPickPersonal()
          launchPicker.value()
        },
      )
    }
    // Il selettore file: la stessa lista di tipi che il manifest dichiara per la condivisione.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      if (uris.isNotEmpty()) {
        onPickFiles(uris, null)
        actions.openImport(fresh = !pickingInto.value)
      } else {
        onPickerCancelled()
      }
    }
    launchPicker.value = { pickFiles.launch(ImportRequest.PICKER_MIME_TYPES) }

    LaunchedEffect(layout.splits) { syncPanes(listNav, detailNav, layout.splits) }

    val notifications = LocalFluidNotificationHostState.current
    val endpointNotice = rememberEndpointLinkedNotice()
    val syncLinkedTitle = stringResource(R.string.sync_linked_title)
    val syncLinkedMessage = stringResource(R.string.sync_linked)
    LaunchedEffect(listNav, incomingIntents) {
      incomingIntents.collect { intent ->
        // Prima la condivisione, poi i deep link: un intent di SEND non e' un link e non ha una rotta.
        when (onIntent(intent)) {
          IntentOutcome.Import -> actions.openImport()
          // La conferma la mostra la radice; quello che segue a «Collega» arriva da `linkApplied`.
          IntentOutcome.LinkPending, is IntentOutcome.EndpointLinked, is IntentOutcome.SyncLinked -> Unit
          IntentOutcome.None -> listNav.handleDeepLink(intent)
        }
      }
    }
    LaunchedEffect(listNav, linkApplied) {
      linkApplied.collect { outcome ->
        when (outcome) {
          is IntentOutcome.EndpointLinked -> {
            // La pagina dei servizi, cosi' si vede cosa e' stato scritto e si prova la connessione subito.
            actions.openSettingsSection(SettingsSection.SERVICES)
            // In un ramo suo: `show` torna solo a scheda mostrata, e intanto non si bloccano gli intent.
            launch { notifications?.show(endpointNotice(outcome)) }
          }
          is IntentOutcome.SyncLinked -> {
            actions.openSettingsSection(SettingsSection.SYNC)
            launch {
              notifications?.show(
                FluidNotification(
                  id = "sync-linked",
                  title = syncLinkedTitle,
                  message = syncLinkedMessage.format(outcome.url),
                  tone = FluidNotificationTone.Success,
                ),
              )
            }
          }
          else -> Unit
        }
      }
    }

    val listEntry by listNav.currentBackStackEntryAsState()
    val listRoute = listEntry?.destination?.route?.substringBefore("?")
    val selectedFolderId = listEntry?.takeIf { it.destination.route == Routes.FOLDER }?.arguments?.getString("folderId")
    // La pillola in basso solo dove non c'e' altra chrome: col rail o con la barra laterale
    // sarebbe la stessa cosa detta due volte.
    val showTabBar = !layout.showRail && !layout.showSide && listRoute in Routes.topLevelSet

    val tabItems = listOf(
      FluidTabItem(Routes.HOME, stringResource(R.string.tab_home), Icons.Rounded.Home),
      FluidTabItem(Routes.FOLDERS, stringResource(R.string.tab_folders), Icons.Rounded.GridView),
      FluidTabItem(Routes.RECORDINGS, stringResource(R.string.tab_recordings), Icons.Rounded.GraphicEq),
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
              onRecordings = { actions.switchTopLevel(Routes.RECORDINGS) },
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
