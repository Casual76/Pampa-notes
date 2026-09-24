package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.FastForward
import dev.pampa.pampanotes.core.playback.SilenceSkipper
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.pampa.pampanotes.core.transcription.TranscriptSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidPillTabs
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.common.OverflowMenuButton
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.repo.FailedJobs
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.db.TranscriptStatus
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.settings.RefinementPreset
import dev.pampa.pampanotes.player.PlaybackState
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.RunText
import dev.pampa.pampanotes.ui.common.CloseWhenGone
import dev.pampa.pampanotes.ui.common.JobProgressBars
import dev.pampa.pampanotes.ui.common.jobErrorText
import dev.pampa.pampanotes.ui.common.jobPhaseText
import dev.pampa.pampanotes.ui.common.MarkdownText
import androidx.compose.ui.geometry.Rect
import dev.antigravity.fluidengine.ui.fluid.fluidExpandOrigin
import dev.pampa.pampanotes.ui.common.ReportSubject
import dev.pampa.pampanotes.ui.common.asSubject
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSpokenText
import dev.antigravity.fluidengine.ui.fluid.FluidSpokenWord
import dev.pampa.pampanotes.core.transcription.TranscriptParagraphs
import dev.pampa.pampanotes.core.transcription.Chapters
import androidx.compose.material.icons.automirrored.rounded.Toc
import dev.pampa.pampanotes.core.transcription.WordSource
import dev.pampa.pampanotes.core.transcription.WordTimings
import dev.pampa.pampanotes.core.transcription.VoiceNames
import dev.antigravity.fluidengine.ui.fluid.fluidRowPressable
import androidx.compose.ui.text.style.TextAlign

@Composable
fun SessionRoute(
  onBack: () -> Unit,
  onOpenSession: (String) -> Unit,
  viewModel: SessionViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // Lo stato, non il valore: la posizione cambia cinque volte al secondo, e letta qui farebbe
  // ricomporre tutta la pagina a ogni battito. La leggono solo il lettore e il paragrafo acceso.
  val playback = viewModel.playback.collectAsStateWithLifecycle()
  val following by viewModel.followPlayback.collectAsStateWithLifecycle()
  val refineDefaults by viewModel.refineDefaults.collectAsStateWithLifecycle()
  val skipSilence by viewModel.skipSilence.collectAsStateWithLifecycle()
  val skipNotice by viewModel.skipNotice.collectAsStateWithLifecycle()

  SessionScreen(
    state = state,
    playback = playback,
    following = following,
    onBack = onBack,
    onPlayPause = viewModel::playPause,
    onSkip = viewModel::skip,
    onSeek = viewModel::seekTo,
    onCycleSpeed = viewModel::cycleSpeed,
    onUserScrolled = viewModel::stopFollowing,
    onTranscribe = viewModel::transcribe,
    onCancelJob = viewModel::cancelJob,
    onDismissJob = { viewModel.dismissJob(it) },
    onFetchMissing = viewModel::fetchMissing,
    onRename = viewModel::rename,
    onRenameVoice = viewModel::renameVoice,
    onShowTranscript = viewModel::showTranscript,
    onMovePart = viewModel::movePart,
    onMovePartTo = viewModel::movePartTo,
    onSplitAt = { partId -> viewModel.splitAt(partId, onOpenSession) },
    onMerge = { viewModel.mergeIntoPrevious(onOpenSession) },
    onDeletePart = { partId -> viewModel.deletePart(partId, onBack) },
    onDeleteSession = { viewModel.deleteSession(onBack) },
    refineDefaults = refineDefaults,
    onPrepareRefinement = viewModel::prepareRefinement,
    onRefine = viewModel::refine,
    skipSilence = skipSilence,
    onSkipSilence = { viewModel.setSkipSilence(it) },
    skipNotice = skipNotice,
  )
}

@Composable
private fun SessionScreen(
  state: SessionUiState,
  playback: State<PlaybackState>,
  following: Boolean,
  onBack: () -> Unit,
  onPlayPause: () -> Unit,
  onSkip: (Long) -> Unit,
  onSeek: (Long) -> Unit,
  onCycleSpeed: () -> Unit,
  onUserScrolled: () -> Unit,
  onTranscribe: () -> Unit,
  onCancelJob: (String) -> Unit,
  onDismissJob: (String) -> Unit,
  onFetchMissing: () -> Unit,
  onRename: (String, String) -> Unit,
  onRenameVoice: (key: String, name: String?) -> Unit,
  onShowTranscript: (String) -> Unit,
  onMovePart: (String, Int) -> Unit,
  onMovePartTo: (String, String) -> Unit,
  onSplitAt: (String) -> Unit,
  onMerge: () -> Unit,
  onDeletePart: (String) -> Unit,
  onDeleteSession: () -> Unit,
  refineDefaults: RefineDefaults,
  onPrepareRefinement: () -> Unit,
  onRefine: (RefinementPreset, String) -> Unit,
  skipSilence: Boolean,
  onSkipSilence: (Boolean) -> Unit,
  skipNotice: SkipNotice?,
) {
  val listState = rememberLazyListState()
  var renaming by remember { mutableStateOf(false) }
  var confirmingDelete by remember { mutableStateOf(false) }
  // Una registrazione tolta puo' essere l'unica copia: si chiede, come per la sessione intera.
  var confirmingPartDelete by remember { mutableStateOf<String?>(null) }
  var confirmingRetranscribe by remember { mutableStateOf(false) }
  var refining by remember { mutableStateOf(false) }
  // Dove sta il tasto «altro»: i pop-up di rinomina e ripulitura nascono da li'.
  var moreOrigin by remember { mutableStateOf<Rect?>(null) }
  // «Rinomina le voci»: la voce toccata, e dove sta la sua etichetta (il pop-up nasce da li').
  var editingVoice by remember { mutableStateOf<VoiceLabel?>(null) }
  var voiceOrigin by remember { mutableStateOf<Rect?>(null) }

  val paragraphs = remember(state.segments) { paragraphsOf(state.segments) }
  val voiceNames = remember(state.session?.voiceNames) { VoiceNames.decode(state.session?.voiceNames) }
  val resources = LocalContext.current.resources
  val runSummary = remember(resources, state.runOfRaw, state.raw, state.segments, state.parts) {
    RunText.sessionLine(resources, state.runOfRaw, state.pace)
  }
  // Quale paragrafo si sta ascoltando: l'ultimo cominciato.
  //
  // Derivato dallo stato della posizione, letto dentro il calcolo: la pagina si ricompone quando
  // cambia il paragrafo, cioe' ogni qualche decina di secondi, e non a ogni battito del lettore.
  // Con la posizione passata come valore (com'era prima) derivedStateOf non vedeva cambiare niente,
  // e il paragrafo attivo restava il primo per tutta la lezione.
  val activeParagraph by remember(paragraphs) {
    derivedStateOf { paragraphs.indexOfLast { it.startMs <= playback.value.positionMs }.coerceAtLeast(0) }
  }
  val playing by remember { derivedStateOf { playback.value.playing } }

  ScrollFollower(
    listState = listState,
    following = following,
    playing = playing,
    targetIndex = activeParagraph,
    onUserScrolled = onUserScrolled,
  )

  // --- Cerca dentro la registrazione -------------------------------------------------------------
  //
  // Sulla grezza si cerca nei paragrafi che si vedono, e un'occorrenza e' anche un momento: «dopo» e
  // «prima» portano il lettore alla parola. Sulla raffinata si cerca nel testo e basta — non ha tempi —
  // e mentre la ricerca e' aperta la si mostra a blocchi di testo semplice, gli unici in cui si puo'
  // evidenziare (vedi `TranscriptSearch.plainBlocks`).
  val search = rememberTranscriptSearchState()
  val scope = rememberCoroutineScope()
  val active = state.activeTranscript
  val searchOnRaw = active?.kind == TranscriptKind.RAW && paragraphs.isNotEmpty()
  // I blocchi della raffinata si preparano fuori dal thread della UI e solo a ricerca aperta: servono
  // solo a lei, e spezzare il testo di una registrazione di ore costava un fotogramma a ogni apertura
  // della pagina.
  val refinedBlocks by produceState(emptyList<String>(), active?.id, active?.text, searchOnRaw, search.open) {
    value = if (search.open && active != null && !searchOnRaw) {
      withContext(Dispatchers.Default) { TranscriptSearch.plainBlocks(active.text) }
    } else {
      emptyList()
    }
  }
  val searchBlocks = remember(paragraphs, refinedBlocks, searchOnRaw) {
    if (searchOnRaw) paragraphs.map { it.text } else refinedBlocks
  }
  // L'indice si prepara fuori dal thread della UI: diciannove ore sono un milione di caratteri.
  // Prima si butta quello vecchio: fino al nuovo non c'e' niente in cui cercare.
  val searchIndex by produceState<TranscriptSearch.Index?>(null, searchBlocks, search.open) {
    value = null
    value = if (search.open) withContext(Dispatchers.Default) { TranscriptSearch.Index(searchBlocks) } else null
  }
  // Cambiati i blocchi (un'altra scheda, un testo arrivato), le occorrenze di prima indicano posti
  // che non ci sono piu': restavano evidenziate sulla scheda nuova finche' la ricerca non ripartiva.
  LaunchedEffect(searchBlocks) { search.clearMatches() }

  fun revealMatch(match: TranscriptSearch.Match, seek: Boolean) {
    scope.launch { scrollToKeyed(listState, if (searchOnRaw) PARAGRAPH_KEY else REFINED_BLOCK_KEY, match.block) }
    val paragraph = paragraphs.getOrNull(match.block)
    if (seek && searchOnRaw && paragraph != null) {
      // Il lettore va alla parola; la lista la segue da se' (seekTo riaccende l'inseguimento).
      onSeek(TranscriptSearch.timeOf(paragraph.base, match.start))
    } else {
      // Mentre si scrive si guarda, non si ascolta: la lista si ferma sull'occorrenza e non torna
      // al paragrafo che suona finche' non si tocca una frase.
      onUserScrolled()
    }
  }

  fun stepSearch(delta: Int) {
    val total = search.matches.size
    if (total == 0) return
    search.current = (search.current + delta).mod(total)
    search.currentMatch?.let { revealMatch(it, seek = true) }
  }

  LaunchedEffect(searchIndex, search.query) {
    val index = searchIndex ?: return@LaunchedEffect
    val query = search.query
    // Un attimo di respiro fra una lettera e l'altra: cercare «k», «ka», «kan» e «kant» e' tre ricerche
    // buttate su una lezione intera.
    delay(SEARCH_DEBOUNCE_MS)
    val found = withContext(Dispatchers.Default) { TranscriptSearch.find(index, query) }
    val before = search.currentMatch
    search.onResults(query, found)
    search.currentMatch?.takeIf { it != before }?.let { revealMatch(it, seek = false) }
  }

  // Indietro chiude la ricerca prima della pagina, e chiuderla la cancella.
  BackHandler(enabled = search.open) { search.close() }

  // --- Capitoli -----------------------------------------------------------------------------------
  //
  // I tratti di parlato fra i silenzi lunghi (vedi `Chapters`): confini e prime parole, mai
  // riassunti. Si calcolano fuori dal thread della UI — diciannove ore sono ventimila segmenti — e
  // solo sulla grezza a schermo, l'unica in cui un capitolo si puo' ritrovare nel testo.
  val chapters by produceState(emptyList<Chapters.Chapter>(), state.segments, state.durationMs, searchOnRaw) {
    value = if (searchOnRaw) {
      withContext(Dispatchers.Default) { Chapters.index(state.segments, state.durationMs.takeIf { it > 0 }) }
    } else {
      emptyList()
    }
  }
  // Il capitolo in ascolto, derivato come il paragrafo attivo: la pagina si ricompone quando cambia
  // capitolo, non a ogni battito. -1 finche' il lettore non e' partito: «adesso» sul primo capitolo
  // di una registrazione mai ascoltata sarebbe falso.
  val currentChapter by remember(chapters) {
    derivedStateOf {
      val now = playback.value
      if (chapters.isEmpty() || (!now.playing && now.positionMs <= 0L)) -1 else Chapters.currentIndex(chapters, now.positionMs)
    }
  }
  var showingChapters by remember { mutableStateOf(false) }

  fun openChapter(chapter: Chapters.Chapter) {
    showingChapters = false
    // Il lettore all'inizio del capitolo, e il testo con lui: l'inizio di un capitolo e' sempre
    // l'inizio di un paragrafo, perche' un silenzio di un minuto va sempre a capo.
    onSeek(chapter.startMs)
    val paragraph = paragraphs.indexOfFirst { it.startMs >= chapter.startMs }.takeIf { it >= 0 } ?: return
    scope.launch { scrollToKeyed(listState, PARAGRAPH_KEY, paragraph) }
  }

  val renameLabel = stringResource(R.string.session_rename)
  val refineLabel = stringResource(R.string.refine_action)
  val retranscribeLabel = stringResource(R.string.session_retranscribe)
  val mergeLabel = stringResource(R.string.session_merge)
  val deleteLabel = stringResource(R.string.session_delete)
  val moreLabel = stringResource(R.string.action_more)
  val chaptersLabel = stringResource(R.string.session_chapters)
  // Lo stato nel nome, perche' il menu dell'engine non ha una spunta; e la soglia, perche' le righe
  // della trascrizione dicono solo i silenzi di un minuto e piu', e un salto di 14 secondi senza
  // la soglia sembrerebbe un errore.
  val skipSeconds = (SilenceSkipper.MIN_GAP_MS / 1_000L).toInt()
  val skipSilenceLabel = stringResource(if (skipSilence) R.string.session_skip_silence_active else R.string.session_skip_silence_inactive, skipSeconds)
  val noticeText = skipNotice?.let { skippedLabel(it.skippedMs) }
  CloseWhenGone(gone = !state.loading && state.session == null, onBack = onBack)
  // La lezione e' della sua materia: l'app prende quel colore.
  ReportSubject(state.folder?.asSubject())

  FluidScreen(
    title = state.session?.let { sessionHeading(it.title, it.date) } ?: stringResource(R.string.session_loading),
    subtitle = state.note?.title,
    onBack = onBack,
    listState = listState,
    ambient = FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Ripples),
    titleFacets = buildList {
      if (state.durationMs > 0) add(Formats.durationShort(state.durationMs))
      state.activeTranscript?.let { add(pluralStringResource(R.plurals.session_words, it.wordCount, it.wordCount)) }
    },
    // Il lettore galleggia sopra la lista, quindi la lista deve finire prima di lui: senza questo
    // spazio l'ultimo paragrafo di una lezione non si riesce a leggere.
    extraBottomPadding = (if (state.playable) PlayerBarHeight else 0.dp) + (if (search.open) SearchBarHeight else 0.dp),
    actions = {
      // La lente apre e chiude la ricerca dentro la registrazione: c'e' solo quando c'e' un testo.
      if (active != null && active.text.isNotBlank()) {
        FluidBarAction(
          icon = Icons.Rounded.Search,
          contentDescription = stringResource(R.string.session_search),
          onClick = { if (search.open) search.close() else search.show() },
        )
      }
      // Un tocco apre il menu: prima apriva «Rinomina», e «Ritrascrivi» si trovava solo tenendo premuto.
      OverflowMenuButton(
        contentDescription = moreLabel,
        modifier = Modifier.fluidExpandOrigin(open = { renaming || refining }, onMeasured = { moreOrigin = it }),
        actions = {
          buildList {
            // In cima, perche' e' quello che si tocca mentre si ascolta; poi il testo (ripulirlo,
            // rifarlo), poi la sessione (titolo, unione), e in fondo l'unica voce distruttiva.
            //
            // «Salta i silenzi» sta qui e non nella capsula del lettore, che su un telefono e' gia'
            // piena: si accende una volta per le registrazioni lunghe e resta acceso (per dispositivo),
            // e acceso la capsula lo dice con un segno accanto al tempo. Solo dove ha senso: serve la
            // grezza coi suoi tempi, ed e' da lei che si sa dove si tace.
            if (state.playable && state.segments.isNotEmpty()) {
              add(FluidContextAction(label = skipSilenceLabel, icon = Icons.Rounded.FastForward) { onSkipSilence(!skipSilence) })
            }
            // I capitoli dal menu, raggiungibile anche a meta' di diciannove ore, dove la riga sopra
            // il testo e' lontana. Non come terzo tasto della barra: con tre, il titolo compatto non
            // ci stava piu' («3486 parol…»).
            if (chapters.isNotEmpty()) {
              add(FluidContextAction(label = chaptersLabel, icon = Icons.AutoMirrored.Rounded.Toc) { showingChapters = true })
            }
            if (state.raw != null && state.job == null) {
              add(
                FluidContextAction(label = refineLabel) {
                  onPrepareRefinement()
                  refining = true
                },
              )
            }
            // Rifare da capo: una grezza venuta male da Groq si rifa' col computer di casa, o con un
            // vocabolario migliore. La conferma c'e' perche' si porta via anche le versioni ripulite.
            if (state.canRetranscribe) {
              add(FluidContextAction(label = retranscribeLabel) { confirmingRetranscribe = true })
            }
            add(FluidContextAction(label = renameLabel) { renaming = true })
            if (state.canMerge) add(FluidContextAction(label = mergeLabel) { onMerge() })
            add(FluidContextAction(label = deleteLabel, destructive = true) { confirmingDelete = true })
          }
        },
      )
    },
    overlay = { backdrop ->
      if (state.playable) {
        PlayerBar(
          state = playback.value,
          backdrop = backdrop,
          onPlayPause = onPlayPause,
          onSkip = onSkip,
          onSeek = onSeek,
          onCycleSpeed = onCycleSpeed,
          notice = noticeText?.takeIf { skipSilence },
          skippingSilence = skipSilence && state.segments.isNotEmpty(),
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
      if (search.open) {
        // Sopra il lettore, o sopra la tastiera quando c'e': il lettore resta sotto la tastiera, e
        // lasciargli lo spazio vorrebbe dire una barra sospesa a mezz'aria.
        @OptIn(ExperimentalLayoutApi::class)
        val keyboard = WindowInsets.isImeVisible
        TranscriptSearchBar(
          state = search,
          backdrop = backdrop,
          onPrevious = { stepSearch(-1) },
          onNext = { stepSearch(1) },
          onClose = { search.close() },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = 16.dp)
            .padding(bottom = if (state.playable && !keyboard) PlayerBarHeight + 16.dp else 12.dp),
        )
      }
    },
  ) {
    jobItem(state, onCancelJob, onRetryJob = { if (it.type == JobType.REFINE) refining = true else onTranscribe() }, onDismissJob = onDismissJob)
    // «Il testo che arriva a pezzi», sotto la scheda del lavoro finche' il lavoro va (PartialTranscriptBlock.kt).
    if (state.job != null) partialTranscriptItems(state.partial, onSeek)
    remoteAudioItem(state, onFetchMissing)
    partsSection(state, onSeek, onMovePart, onMovePartTo, onSplitAt, onRetranscribe = { confirmingRetranscribe = true }) { confirmingPartDelete = it }
    transcriptSection(state, onTranscribe, onShowTranscript) {
      onPrepareRefinement()
      refining = true
    }
    transcriptBody(
      state, paragraphs, activeParagraph, { playback.value.positionMs }, onSeek, runSummary,
      search = search,
      refinedBlocks = refinedBlocks.takeIf { search.open },
      chapters = chapters,
      currentChapter = currentChapter,
      onOpenChapters = { showingChapters = true },
      voiceNames = voiceNames,
      onVoiceClick = { voice, origin ->
        voiceOrigin = origin
        editingVoice = voice
      },
    )
  }

  if (showingChapters && chapters.isNotEmpty()) {
    // I capitoli contano le voci per numero; i nomi dati si ritrovano dalla chiave di ciascuna.
    val namesByNumber = remember(state.segments, voiceNames) {
      TranscriptParagraphs.voices(state.segments).mapNotNull { (key, number) -> voiceNames[key]?.let { number to it } }.toMap()
    }
    ChaptersSheet(
      chapters = chapters,
      current = currentChapter,
      voiceName = { namesByNumber[it] },
      onPick = { openChapter(it) },
      onDismiss = { showingChapters = false },
    )
  }

  editingVoice?.let { voice ->
    val session = state.session
    // I nomi gia' dati in questa nota: di solito sono le stesse persone di sessione in sessione.
    val suggestions = remember(voice, session?.voiceNames, state.siblings) {
      val columns = (listOfNotNull(session) + state.siblings).distinctBy { it.id }.map { it.voiceNames }
      VoiceNames.suggestions(columns, exclude = voice.name)
    }
    VoiceRenameSheet(
      voice = voice,
      suggestions = suggestions,
      origin = { voiceOrigin },
      onDismiss = { editingVoice = null },
      onConfirm = { name ->
        editingVoice = null
        onRenameVoice(voice.key, name)
      },
    )
  }

  if (renaming && state.session != null) {
    SessionRenameSheet(
      title = state.session.title,
      date = state.session.date,
      origin = { moreOrigin },
      onDismiss = { renaming = false },
      onConfirm = { title, date ->
        renaming = false
        onRename(title, date)
      },
    )
  }

  if (refining) {
    RefineSheet(
      initialPreset = refineDefaults.preset,
      initialCustomPrompt = refineDefaults.customPrompt,
      hasKey = refineDefaults.hasKey,
      origin = { moreOrigin },
      onDismiss = { refining = false },
      onConfirm = { preset, prompt ->
        refining = false
        onRefine(preset, prompt)
      },
    )
  }

  if (confirmingRetranscribe) {
    FluidAlert(
      onDismissRequest = { confirmingRetranscribe = false },
      title = stringResource(R.string.session_retranscribe_title),
      message = stringResource(R.string.session_retranscribe_message),
      actions = listOf(
        FluidAlertAction(
          label = retranscribeLabel,
          emphasis = FluidAlertAction.Emphasis.Preferred,
          onClick = {
            confirmingRetranscribe = false
            onTranscribe()
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirmingRetranscribe = false }),
      ),
    )
  }

  confirmingPartDelete?.let { partId ->
    val part = state.parts.firstOrNull { it.id == partId }
    FluidAlert(
      onDismissRequest = { confirmingPartDelete = null },
      title = stringResource(R.string.part_delete_title),
      message = stringResource(
        R.string.part_delete_message,
        part?.originalName.orEmpty(),
        Formats.duration(part?.durationMs ?: 0L),
      ),
      actions = listOf(
        FluidAlertAction(
          label = stringResource(R.string.part_delete),
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            confirmingPartDelete = null
            onDeletePart(partId)
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirmingPartDelete = null }),
      ),
    )
  }

  if (confirmingDelete) {
    FluidAlert(
      onDismissRequest = { confirmingDelete = false },
      title = stringResource(R.string.session_delete_title),
      message = pluralStringResource(R.plurals.session_delete_message, state.parts.size, state.parts.size),
      actions = listOf(
        FluidAlertAction(
          label = deleteLabel,
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            confirmingDelete = false
            onDeleteSession()
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirmingDelete = false }),
      ),
    )
  }
}

// -------------------------------------------------------------------------------------------------
// Le sezioni
// -------------------------------------------------------------------------------------------------

private fun LazyListScope.jobItem(
  state: SessionUiState,
  onCancelJob: (String) -> Unit,
  onRetryJob: (JobEntity) -> Unit,
  onDismissJob: (String) -> Unit,
) {
  val job = state.job
  val failed = state.failedJob
  if (job == null && failed != null && state.elsewhere == null) {
    item(key = "job-failed") {
      // Una registrazione muta non e' un guasto: e' la risposta. Niente rosso, e il tasto dice che
      // rifarla da' con ogni probabilita' lo stesso risultato.
      val silent = failed.errorCode == FailedJobs.NO_SPEECH
      // Una ripulitura non riuscita, con la grezza li' sotto, non ha perso niente: la lezione si
      // legge lo stesso. Una scheda quieta, non l'allarme in cima alla pagina.
      val minor = failed.type == JobType.REFINE && state.raw != null
      FluidCard(highlighted = !minor) {
        Text(
          text = stringResource(
            when {
              silent -> R.string.job_no_speech_title
              failed.type == JobType.REFINE -> R.string.job_failed_refine
              else -> R.string.job_failed_transcribe
            },
          ),
          style = MaterialTheme.typography.titleSmall,
          color = if (silent || minor) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Text(
          text = jobErrorText(failed.errorCode ?: "unknown", failed.errorMessage, failed.provider),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // «Nascondi» toglie la riga del lavoro: senza, un fallimento restava in cima alla sessione
        // per sempre, anche deciso di lasciar perdere.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          FluidButton(
            text = stringResource(R.string.job_dismiss),
            onClick = { onDismissJob(failed.id) },
            style = FluidButtonStyle.Plain,
            fillWidth = true,
            modifier = Modifier.weight(1f),
          )
          FluidButton(
            text = stringResource(if (silent) R.string.job_no_speech_retry else R.string.job_retry),
            onClick = { onRetryJob(failed) },
            style = FluidButtonStyle.Plain,
            fillWidth = true,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
    return
  }
  if (job == null) {
    // Il lavoro sta su un altro dispositivo: qui non ci sono barre da mostrare, solo dove, e che
    // il testo arrivera' da solo.
    val remote = state.elsewhere ?: return
    item(key = "job-elsewhere") {
      FluidInlineMessage(
        title = stringResource(R.string.transcribing_elsewhere, remote.device),
        message = stringResource(R.string.transcribing_elsewhere_detail),
        tone = FluidTone.Info,
      )
    }
    return
  }
  item(key = "job") {
    FluidCard {
      Text(
        text = jobPhaseText(job),
        style = MaterialTheme.typography.titleSmall,
      )
      JobProgressBars(job)
      FluidButton(
        text = stringResource(R.string.action_cancel),
        onClick = { onCancelJob(job.id) },
        style = FluidButtonStyle.Plain,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/**
 * Le registrazioni che non sono su questo dispositivo.
 *
 * Una sessione arrivata dall'indice in cloud ha le righe e non i file: si dice dove sono e si
 * offre di prenderli, con il peso davanti, perche' da fuori casa passano da Tailscale e sessanta
 * megabyte l'ora sono una scelta da fare sapendolo. Quando invece l'altro dispositivo non le ha
 * ancora archiviate, non c'e' niente da chiedere a nessuno, e lo si dice.
 */
private fun LazyListScope.remoteAudioItem(state: SessionUiState, onFetch: () -> Unit) {
  val missing = state.missing?.takeIf { it.isNotEmpty() } ?: return
  item(key = "remote-audio") {
    val fetch = state.fetch
    val bytes = missing.sumOf { it.sizeBytes }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      when {
        fetch != null && fetch.error == null -> {
          FluidInlineMessage(
            title = stringResource(R.string.session_remote_fetching, (fetch.done + 1).coerceAtMost(fetch.total), fetch.total),
            message = fetch.label,
            tone = FluidTone.Info,
          )
          FluidProgressBar(progress = { fetch.fraction }, modifier = Modifier.padding(horizontal = 4.dp))
        }
        state.fetchable -> {
          val detail = pluralStringResource(R.plurals.session_remote_detail, missing.size, missing.size, Formats.bytes(bytes))
          FluidInlineMessage(
            title = stringResource(R.string.session_remote_title),
            message = fetch?.error?.let { detail + "\n" + stringResource(R.string.session_remote_error, it) } ?: detail,
            tone = if (fetch?.error != null) FluidTone.Danger else FluidTone.Warning,
          )
          FluidButton(text = stringResource(R.string.session_remote_fetch), onClick = onFetch, fillWidth = true)
        }
        else -> FluidInlineMessage(
          title = stringResource(R.string.session_remote_unavailable_title),
          message = stringResource(R.string.session_remote_unavailable_detail),
          tone = FluidTone.Warning,
        )
      }
    }
  }
}

private fun LazyListScope.partsSection(
  state: SessionUiState,
  onSeek: (Long) -> Unit,
  onMovePart: (String, Int) -> Unit,
  onMovePartTo: (String, String) -> Unit,
  onSplitAt: (String) -> Unit,
  onRetranscribe: () -> Unit,
  onDeletePart: (String) -> Unit,
) {
  if (state.parts.isEmpty()) return
  item(key = "parts") {
    val up = stringResource(R.string.part_move_up)
    val down = stringResource(R.string.part_move_down)
    val split = stringResource(R.string.part_split)
    val delete = stringResource(R.string.action_delete)
    val moveTo = stringResource(R.string.part_move_to)
    val siblingLabels = state.siblings.associateWith { sessionHeading(it.title, it.date) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = pluralStringResource(R.plurals.session_parts, state.parts.size, state.parts.size),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
      )
      FluidListGroup {
        var offset = 0L
        state.parts.forEachIndexed { index, part ->
          val start = offset
          offset += part.durationMs
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = part.originalName,
            subtitle = Formats.duration(part.durationMs),
            eyebrow = stringResource(R.string.note_part_number, index + 1),
            // Il tempo a cui comincia nella sessione: e' cosi' che due registrazioni separate si
            // leggono come un nastro solo.
            meta = stringResource(R.string.part_starts_at, Formats.timestamp(start)),
            tone = if (part in state.untranscribed) FluidTone.Warning else FluidTone.Neutral,
            onClick = { onSeek(start) },
            contextActions = {
              buildList {
                if (index > 0) add(FluidContextAction(label = up) { onMovePart(part.id, -1) })
                if (index < state.parts.lastIndex) add(FluidContextAction(label = down) { onMovePart(part.id, 1) })
                if (index > 0) add(FluidContextAction(label = split) { onSplitAt(part.id) })
                siblingLabels.forEach { (session, label) ->
                  add(FluidContextAction(label = "$moveTo $label") { onMovePartTo(part.id, session.id) })
                }
                add(FluidContextAction(label = delete, destructive = true) { onDeletePart(part.id) })
              }
            },
          )
        }
      }
    }
  }

  if (state.untranscribed.isNotEmpty()) {
    item(key = "untranscribed") {
      // L'avviso dice «ritrascrivi la sessione», e il tasto sta qui sotto: prima stava solo nel menu
      // in alto, e chi leggeva l'avviso doveva andarlo a cercare. Stessa conferma del menu.
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidInlineMessage(
          title = stringResource(R.string.session_partial_title),
          message = pluralStringResource(
            R.plurals.session_partial_message,
            state.untranscribed.size,
            state.untranscribed.size,
          ),
          tone = FluidTone.Warning,
        )
        if (state.canRetranscribe) {
          FluidButton(
            text = stringResource(R.string.session_retranscribe),
            onClick = onRetranscribe,
            style = FluidButtonStyle.Tinted,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

private fun LazyListScope.transcriptSection(
  state: SessionUiState,
  onTranscribe: () -> Unit,
  onShowTranscript: (String) -> Unit,
  onRefine: () -> Unit,
) {
  if (state.transcripts.size > 1) {
    item(key = "versions") {
      // Per preset, e unici: due raffinate con lo stesso modello avevano la stessa etichetta, e la
      // scheda si cercava per etichetta — la seconda non si apriva mai.
      val labels = distinctLabels(state.transcripts.map { transcriptLabel(it) })
      val activeIndex = state.transcripts.indexOfFirst { it.id == state.activeTranscript?.id }.coerceAtLeast(0)
      FluidPillTabs(
        options = labels,
        selected = labels[activeIndex],
        onSelect = { label ->
          val index = labels.indexOf(label)
          state.transcripts.getOrNull(index)?.let { onShowTranscript(it.id) }
        },
      )
    }
  }

  // Offerto solo quando non esiste ancora una versione ripulita: rifarla sta nel menu in alto, dove
  // vanno le cose che si fanno una volta ogni tanto.
  if (state.raw != null && state.transcripts.size == 1 && state.job == null) {
    item(key = "refine") {
      FluidButton(
        text = stringResource(R.string.refine_action),
        onClick = onRefine,
        style = FluidButtonStyle.Plain,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }

  if (state.activeTranscript?.status == TranscriptStatus.SUSPICIOUS) {
    item(key = "suspicious") {
      FluidInlineMessage(
        title = stringResource(R.string.transcript_suspicious_title),
        message = stringResource(R.string.transcript_suspicious_message),
        tone = FluidTone.Warning,
      )
    }
  }

  // Con l'avviso del tentativo fallito, che ha gia' il suo tasto, questo ripeterebbe la stessa cosa.
  val failedShown = state.job == null && state.failedJob?.type == JobType.TRANSCRIBE && state.elsewhere == null
  if (state.activeTranscript == null && !state.loading && !failedShown) {
    item(key = "empty") {
      FluidEmptyState(
        title = stringResource(
          if (state.parts.isEmpty()) R.string.session_no_audio_title else R.string.session_no_transcript_title,
        ),
        detail = stringResource(
          when {
            state.parts.isEmpty() -> R.string.session_no_audio_detail
            !state.transcribableHere -> R.string.session_no_transcript_elsewhere
            else -> R.string.session_no_transcript_detail
          },
        ),
      )
    }
    if (state.parts.isNotEmpty() && state.job == null && state.elsewhere == null && state.transcribableHere) {
      item(key = "transcribe") {
        FluidButton(
          text = stringResource(R.string.note_transcribe),
          onClick = onTranscribe,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * Il testo.
 *
 * Una raffinata non ha segmenti — i tempi vengono dal modello che ha ascoltato, e un testo ripulito
 * da un LLM non ha piu' un cronometro suo — quindi si mostra come markdown e basta. La grezza invece
 * si legge a paragrafi, e ogni frase e' un punto in cui saltare.
 */
private fun LazyListScope.transcriptBody(
  state: SessionUiState,
  paragraphs: List<Paragraph>,
  activeParagraph: Int,
  positionMs: () -> Long,
  onSeek: (Long) -> Unit,
  runSummary: String?,
  search: TranscriptSearchState,
  /** La raffinata a blocchi di testo semplice, mentre si cerca; null quando la ricerca e' chiusa. */
  refinedBlocks: List<String>?,
  /** I capitoli della grezza (vuota sotto tre): una riga sopra il testo apre l'elenco. */
  chapters: List<Chapters.Chapter> = emptyList(),
  currentChapter: Int = -1,
  onOpenChapters: () -> Unit = {},
  /** «Rinomina le voci»: i nomi dati alle voci di questa sessione (vedi `VoiceNames`). */
  voiceNames: Map<String, String> = emptyMap(),
  /** Un tocco sull'etichetta di una voce, con dove sta: apre «Chi e' Voce 2?». */
  onVoiceClick: ((VoiceLabel, Rect?) -> Unit)? = null,
) {
  val active = state.activeTranscript ?: return

  if (active.kind != TranscriptKind.RAW || paragraphs.isEmpty()) {
    if (refinedBlocks != null) {
      itemsIndexed(items = refinedBlocks, key = { index, _ -> "$REFINED_BLOCK_KEY$index" }) { index, block ->
        FluidCard {
          SearchableText(
            text = block,
            highlights = search.byBlock[index].orEmpty(),
            current = search.currentMatch?.takeIf { it.block == index }?.let { it.start until it.end },
          )
        }
      }
      return
    }
    if (active.text.isNotBlank()) {
      item(key = "refined-${active.id}") {
        FluidCard {
          MarkdownText(markdown = active.text, modifier = Modifier.fillMaxWidth())
        }
      }
    }
    return
  }

  // Com'e' andata la trascrizione e quanto svelto si parla: una riga, sotto le schede e sopra il
  // testo, dove si guarda una volta e poi si legge.
  runSummary?.let { line ->
    item(key = "run-summary") { FluidSectionFootnote(text = line) }
  }

  // Stimati anche quando le parole non ci sono affatto: una trascrizione di prima che il database
  // le salvasse viene comunque interpolata a schermo, e dirlo e' l'unica cosa onesta.
  if (state.segments.any { it.wordsEstimated || it.wordsJson == null }) {
    item(key = "words-estimated") {
      FluidSectionFootnote(text = stringResource(R.string.session_words_estimated))
    }
  }

  // «12 capitoli»: una riga sola sopra il testo, che apre l'elenco (vedi `ChaptersSheet`).
  if (chapters.isNotEmpty()) {
    item(key = "chapters") { ChaptersEntry(chapters = chapters, current = currentChapter, onOpen = onOpenChapters) }
  }

  itemsIndexed(items = paragraphs, key = { index, _ -> "$PARAGRAPH_KEY$index" }) { index, paragraph ->
    val isActive = index == activeParagraph
    // Il silenzio sta nello stesso elemento della lista del paragrafo che viene dopo, non in uno
    // suo: ScrollFollower ritrova i paragrafi per chiave e conta su un elemento per paragrafo.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      paragraph.silenceBeforeMs?.let { silence ->
        SilenceRow(silenceMs = silence, resumeMs = paragraph.startMs, onSeek = onSeek)
      }
      ParagraphCard(
        paragraph = paragraph,
        // «Chi parla»: la voce si dice dove cambia, non su ogni card; col nome, se gliel'hanno dato.
        voice = paragraph.voice?.takeIf { it != paragraphs.getOrNull(index - 1)?.voice }?.let { number ->
          paragraph.voiceKey?.let { key -> VoiceLabel(key, number, voiceNames[key]) }
        },
        onVoiceClick = onVoiceClick,
        isActive = isActive,
        // Una lambda e non un valore: la posizione cambia cinque volte al secondo, e passandola come
        // parametro ogni battito rimisurerebbe il paragrafo. Cosi' cambia solo il disegno.
        positionMs = { if (isActive) positionMs() else 0L },
        onSeek = onSeek,
        highlights = search.byBlock[index].orEmpty(),
        currentHighlight = search.currentMatch?.takeIf { it.block == index }?.let { it.start until it.end },
      )
    }
  }
}

/**
 * «— 16 min di silenzio —» fra due paragrafi.
 *
 * Senza, una pausa di sedici minuti e una di due secondi si vedevano uguali: un paragrafo nuovo e
 * basta, e la frase dopo sembrava la risposta a quella prima. E' una riga quieta — piccola, nel
 * colore secondario, senza card — perche' non e' testo della lezione; un tocco porta dove si
 * ricomincia a parlare, che e' quello che si vuole fare davanti a un quarto d'ora di niente.
 */
@Composable
private fun SilenceRow(silenceMs: Long, resumeMs: Long, onSeek: (Long) -> Unit) {
  val duration = TranscriptParagraphs.silenceDuration(
    silenceMs,
    hours = stringResource(R.string.session_silence_hours),
    minutes = stringResource(R.string.export_label_minutes),
  )
  val label = stringResource(R.string.session_silence, duration)
  val description = stringResource(R.string.session_silence_resume, label, Formats.timestamp(resumeMs))
  Text(
    text = label,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    // Alta come un dito anche se il testo e' piccolo: il bersaglio e' la riga, il testo sta al centro.
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .fluidRowPressable(onClick = { onSeek(resumeMs) })
      .semantics { contentDescription = description }
      .wrapContentHeight(Alignment.CenterVertically)
      .padding(vertical = 6.dp),
  )
}

// -------------------------------------------------------------------------------------------------
// Il paragrafo
// -------------------------------------------------------------------------------------------------

/**
 * Un paragrafo della trascrizione: testo che scorre, ma dove ogni frase resta un punto in cui saltare.
 *
 * Una riga per segmento sarebbe stata piu' semplice da scrivere e illeggibile da leggere: trecento
 * frasi incolonnate non sono una trascrizione, sono un registro. Qui il testo va a capo da solo e il
 * tocco si traduce in un carattere, e dal carattere nel segmento che lo contiene.
 */
@Composable
internal fun ParagraphCard(
  paragraph: Paragraph,
  /** La voce da dire sopra il paragrafo (solo dove cambia), o null. */
  voice: VoiceLabel?,
  isActive: Boolean,
  positionMs: () -> Long,
  onSeek: (Long) -> Unit,
  /** Le occorrenze della ricerca in questo paragrafo, e quella corrente se e' qui. */
  highlights: List<IntRange> = emptyList(),
  currentHighlight: IntRange? = null,
  /** Un tocco sulla voce: «Chi e' Voce 2?». */
  onVoiceClick: ((VoiceLabel, Rect?) -> Unit)? = null,
) {
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  val scheme = MaterialTheme.colorScheme

  FluidCard(highlighted = isActive, onClick = null, animateContent = false) {
    val time = Formats.timestamp(paragraph.startMs)
    val labelColor = if (isActive) scheme.primary else scheme.onSurfaceVariant
    if (voice == null) {
      val atLabel = stringResource(R.string.session_at, time)
      Text(
        text = time,
        style = MaterialTheme.typography.labelMedium,
        color = labelColor,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { contentDescription = atLabel },
      )
    } else {
      VoiceTimeLabel(time = time, voice = voice, color = labelColor, onVoiceClick = onVoiceClick)
    }
    // Il paragrafo che si sta ascoltando si accende parola per parola; gli altri stanno nel colore
    // pieno, perche' un testo velato che nessuno sta ascoltando e' solo un testo sbiadito.
    FluidSpokenText(
      text = paragraph.text,
      words = if (isActive) paragraph.words else emptyList(),
      positionMs = positionMs,
      style = MaterialTheme.typography.bodyLarge,
      spokenColor = scheme.onSurface,
      pendingColor = if (isActive) scheme.onSurfaceVariant.copy(alpha = 0.55f) else scheme.onSurface,
      onTextLayout = { layout = it },
      modifier = Modifier
        .fillMaxWidth()
        .searchHighlights(
          layout = { layout },
          ranges = highlights,
          current = currentHighlight,
          color = scheme.primary.copy(alpha = HIGHLIGHT_ALPHA),
          currentColor = scheme.primary.copy(alpha = CURRENT_HIGHLIGHT_ALPHA),
        )
        .pointerInput(paragraph) {
          detectTapGestures { position ->
            val result = layout ?: return@detectTapGestures
            val offset = result.getOffsetForPosition(position)
            paragraph.segmentAt(offset)?.let { onSeek(it.sessionStartMs) }
          }
        },
    )
  }
}

/**
 * Un paragrafo di [TranscriptParagraphs], con quello che serve solo alla schermata: le parole da
 * accendere e il segmento sotto un tocco. La divisione e' quella del core, la stessa dell'export:
 * prima la pagina ne aveva una copia sua, e una regola in due copie e' due regole.
 */
internal class Paragraph(val base: TranscriptParagraphs.Paragraph) {
  val segments: List<SegmentEntity> get() = base.segments
  val text: String get() = base.text
  private val ranges: List<IntRange> get() = base.ranges
  val startMs: Long get() = base.startMs
  val endMs: Long get() = base.endMs

  /** Il silenzio lungo che lo precede, da dire in una riga sua; null se non c'e'. */
  val silenceBeforeMs: Long? get() = base.silenceBeforeMs

  /** «Chi parla»: il numero della voce, o null senza voci separate. */
  val voice: Int? get() = base.voice

  /** La chiave della voce, a cui si attacca il nome dato dall'utente (`VoiceNames`). */
  val voiceKey: String? get() = base.voiceKey

  /**
   * Le parole con i loro tempi, nel tempo della sessione.
   *
   * Calcolate una volta per paragrafo e non a ogni battito del cronometro: cambiano solo quando
   * cambiano i segmenti. I tempi salvati sono relativi all'inizio del segmento dentro la parte —
   * quello che non cambia mai — e qui si riportano alla sessione.
   */
  val words: List<FluidSpokenWord> by lazy {
    val sources = segments.mapIndexed { index, segment ->
      WordSource(
        range = ranges[index],
        startMs = segment.sessionStartMs,
        endMs = segment.sessionEndMs,
        words = WordTimings.decode(segment.wordsJson, originMs = segment.sessionStartMs),
      )
    }
    WordTimings.spans(text, sources).map { FluidSpokenWord(it.start, it.end, it.startMs, it.endMs) }
  }

  fun segmentAt(offset: Int): SegmentEntity? {
    val index = ranges.indexOfFirst { offset in it }
    return segments.getOrNull(if (index >= 0) index else ranges.lastIndex)
  }
}

/**
 * Da segmenti a paragrafi, con la regola del core: a capo a una pausa, al confine fra due
 * registrazioni, e dopo [TranscriptParagraphs.MAX_SEGMENTS_ON_SCREEN] frasi, perche' una card alta
 * dieci schermi non si scorre: si subisce.
 */
internal fun paragraphsOf(segments: List<SegmentEntity>): List<Paragraph> =
  TranscriptParagraphs.split(segments, TranscriptParagraphs.MAX_SEGMENTS_ON_SCREEN).map(::Paragraph)

// -------------------------------------------------------------------------------------------------

/**
 * Porta la lista dove si sta ascoltando, e smette appena l'utente scorre da solo.
 *
 * Una lista che insegue il cronometro mentre qualcuno sta leggendo un altro punto e' una lista che
 * strappa la pagina di mano. Il dito vince: da quel momento il testo sta fermo finche' non si tocca
 * una frase, che e' il modo esplicito di dire "riportami qui".
 */
@Composable
private fun ScrollFollower(
  listState: LazyListState,
  following: Boolean,
  playing: Boolean,
  targetIndex: Int,
  onUserScrolled: () -> Unit,
) {
  LaunchedEffect(listState) {
    listState.interactionSource.interactions.collect { interaction ->
      if (interaction is DragInteraction.Start) onUserScrolled()
    }
  }

  // Il paragrafo si trova per chiave, non contando quello che gli sta sopra. Il conto a mano
  // (lavoro, parti, versioni, avviso...) sbagliava ogni volta che sopra compariva una riga nuova —
  // la nota «tempi stimati», l'intestazione della pagina — e il lettore portava la lista un
  // paragrafo o due fuori posto. Da un paragrafo visibile qualunque si ricava di quanto sono
  // spostati tutti; se non se ne vede nessuno, ci si avvicina e si corregge.
  LaunchedEffect(targetIndex, following, playing) {
    if (!following || !playing) return@LaunchedEffect
    scrollToKeyed(listState, PARAGRAPH_KEY, targetIndex)
  }
}

/**
 * Porta a schermo il blocco numero [index] fra quelli la cui chiave comincia con [prefix]: i
 * paragrafi della grezza per il lettore e per la ricerca, i blocchi della raffinata per la ricerca.
 */
private suspend fun scrollToKeyed(listState: LazyListState, prefix: String, index: Int) {
  runCatching {
    val offset = keyedOffset(listState, prefix)
    if (offset != null) {
      listState.animateScrollToItem(index + offset)
    } else {
      // Nessun paragrafo a schermo: stanno in fondo alla lista, dopo tutto il resto. Un salto
      // all'ultimo elemento ne mette qualcuno a schermo, e al fotogramma dopo si misura.
      listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
      withFrameNanos { }
      val measured = keyedOffset(listState, prefix) ?: return@runCatching
      listState.animateScrollToItem(index + measured)
    }
  }
}

/** Di quanto l'indice nella lista e' spostato rispetto al numero del blocco, se se ne vede uno. */
private fun keyedOffset(listState: LazyListState, prefix: String): Int? =
  listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
    val number = (info.key as? String)?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
    number?.let { info.index - it }
  }

private const val PARAGRAPH_KEY = "paragraph-"
private const val REFINED_BLOCK_KEY = "refined-block-"

/** Quanto aspetta la ricerca dopo l'ultima lettera prima di cercare. */
private const val SEARCH_DEBOUNCE_MS = 150L

/** Il velo delle occorrenze, e quello piu' pieno della corrente: nel colore della materia. */
private const val HIGHLIGHT_ALPHA = 0.20f
private const val CURRENT_HIGHLIGHT_ALPHA = 0.45f

/** Un blocco della raffinata mentre si cerca: testo semplice, con le occorrenze dietro. */
@Composable
private fun SearchableText(text: String, highlights: List<IntRange>, current: IntRange?) {
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  val scheme = MaterialTheme.colorScheme
  Text(
    text = text,
    style = MaterialTheme.typography.bodyLarge,
    color = scheme.onSurface,
    onTextLayout = { layout = it },
    modifier = Modifier
      .fillMaxWidth()
      .searchHighlights(
        layout = { layout },
        ranges = highlights,
        current = current,
        color = scheme.primary.copy(alpha = HIGHLIGHT_ALPHA),
        currentColor = scheme.primary.copy(alpha = CURRENT_HIGHLIGHT_ALPHA),
      ),
  )
}

/** «Saltati 14 s», «Saltati 16 min», «Saltati 1 h 20 min». */
@Composable
private fun skippedLabel(skippedMs: Long): String =
  if (skippedMs < 60_000L) {
    stringResource(R.string.player_skipped_seconds, (skippedMs / 1_000L).toInt().coerceAtLeast(1))
  } else {
    stringResource(
      R.string.player_skipped,
      TranscriptParagraphs.silenceDuration(
        skippedMs,
        hours = stringResource(R.string.session_silence_hours),
        minutes = stringResource(R.string.export_label_minutes),
      ),
    )
  }

// -------------------------------------------------------------------------------------------------
// Etichette
// -------------------------------------------------------------------------------------------------

@Composable
private fun sessionHeading(title: String, date: String): String {
  val pretty = Dates.parseOrNull(date)?.let { Formats.relativeDate(it) } ?: date
  return if (title.isBlank()) pretty else "$title · $pretty"
}

@Composable
private fun transcriptLabel(transcript: TranscriptEntity): String = when (transcript.kind) {
  TranscriptKind.RAW -> stringResource(R.string.transcript_raw)
  TranscriptKind.REFINED -> when (transcript.preset) {
    "CLEAN" -> stringResource(R.string.refine_preset_clean)
    "STRUCTURED" -> stringResource(R.string.refine_preset_structured)
    "CUSTOM" -> stringResource(R.string.refine_preset_custom)
    else -> stringResource(R.string.transcript_refined, transcript.model)
  }
}

/** «Personalizzata», «Personalizzata 2»: le schede si scelgono per etichetta, e devono essere diverse. */
private fun distinctLabels(labels: List<String>): List<String> {
  val seen = mutableMapOf<String, Int>()
  return labels.map { label ->
    val count = (seen[label] ?: 0) + 1
    seen[label] = count
    if (count == 1) label else "$label $count"
  }
}
