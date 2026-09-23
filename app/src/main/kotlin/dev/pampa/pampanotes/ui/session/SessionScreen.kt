package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.db.TranscriptStatus
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.settings.RefinementPreset
import dev.pampa.pampanotes.player.PlaybackState
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.RunText
import dev.pampa.pampanotes.ui.common.JobProgressBars
import dev.pampa.pampanotes.ui.common.jobPhaseText
import dev.pampa.pampanotes.ui.common.MarkdownText
import androidx.compose.ui.geometry.Rect
import dev.antigravity.fluidengine.ui.fluid.fluidExpandOrigin
import dev.pampa.pampanotes.ui.common.ReportSubject
import dev.pampa.pampanotes.ui.common.asSubject
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSpokenText
import dev.antigravity.fluidengine.ui.fluid.FluidSpokenWord
import dev.pampa.pampanotes.core.transcription.WordSource
import dev.pampa.pampanotes.core.transcription.WordTimings

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
    onFetchMissing = viewModel::fetchMissing,
    onRename = viewModel::rename,
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
  onFetchMissing: () -> Unit,
  onRename: (String, String) -> Unit,
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
) {
  val listState = rememberLazyListState()
  var renaming by remember { mutableStateOf(false) }
  var confirmingDelete by remember { mutableStateOf(false) }
  var confirmingRetranscribe by remember { mutableStateOf(false) }
  var refining by remember { mutableStateOf(false) }
  // Dove sta il tasto «altro»: i pop-up di rinomina e ripulitura nascono da li'.
  var moreOrigin by remember { mutableStateOf<Rect?>(null) }

  val paragraphs = remember(state.segments) { paragraphsOf(state.segments) }
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

  val renameLabel = stringResource(R.string.session_rename)
  val refineLabel = stringResource(R.string.refine_action)
  val retranscribeLabel = stringResource(R.string.session_retranscribe)
  val mergeLabel = stringResource(R.string.session_merge)
  val deleteLabel = stringResource(R.string.session_delete)
  val moreLabel = stringResource(R.string.action_more)
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
    extraBottomPadding = if (state.playable) PlayerBarHeight else 0.dp,
    actions = {
      // Un tocco apre il menu: prima apriva «Rinomina», e «Ritrascrivi» si trovava solo tenendo premuto.
      OverflowMenuButton(
        contentDescription = moreLabel,
        modifier = Modifier.fluidExpandOrigin(open = { renaming || refining }, onMeasured = { moreOrigin = it }),
        actions = {
          buildList {
            add(FluidContextAction(label = renameLabel) { renaming = true })
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
            if (state.raw != null && state.job == null && state.parts.isNotEmpty() && state.transcribableHere) {
              add(FluidContextAction(label = retranscribeLabel) { confirmingRetranscribe = true })
            }
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
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    },
  ) {
    jobItem(state, onCancelJob)
    remoteAudioItem(state, onFetchMissing)
    partsSection(state, onSeek, onMovePart, onMovePartTo, onSplitAt, onDeletePart)
    transcriptSection(state, onTranscribe, onShowTranscript) {
      onPrepareRefinement()
      refining = true
    }
    transcriptBody(state, paragraphs, activeParagraph, { playback.value.positionMs }, onSeek, runSummary)
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

private fun LazyListScope.jobItem(state: SessionUiState, onCancelJob: (String) -> Unit) {
  val job = state.job ?: return
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
      FluidInlineMessage(
        title = stringResource(R.string.session_partial_title),
        message = pluralStringResource(
          R.plurals.session_partial_message,
          state.untranscribed.size,
          state.untranscribed.size,
        ),
        tone = FluidTone.Warning,
      )
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
      val labels = state.transcripts.map { transcriptLabel(it.kind, it.model) }
      val selected = state.activeTranscript?.let { transcriptLabel(it.kind, it.model) } ?: labels.first()
      FluidPillTabs(
        options = labels,
        selected = selected,
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

  if (state.activeTranscript == null && !state.loading) {
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
    if (state.parts.isNotEmpty() && state.job == null && state.transcribableHere) {
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
) {
  val active = state.activeTranscript ?: return

  if (active.kind != TranscriptKind.RAW || paragraphs.isEmpty()) {
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

  itemsIndexed(items = paragraphs, key = { index, _ -> "$PARAGRAPH_KEY$index" }) { index, paragraph ->
    val isActive = index == activeParagraph
    ParagraphCard(
      paragraph = paragraph,
      isActive = isActive,
      // Una lambda e non un valore: la posizione cambia cinque volte al secondo, e passandola come
      // parametro ogni battito rimisurerebbe il paragrafo. Cosi' cambia solo il disegno.
      positionMs = { if (isActive) positionMs() else 0L },
      onSeek = onSeek,
    )
  }
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
private fun ParagraphCard(
  paragraph: Paragraph,
  isActive: Boolean,
  positionMs: () -> Long,
  onSeek: (Long) -> Unit,
) {
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  val scheme = MaterialTheme.colorScheme

  FluidCard(highlighted = isActive, onClick = null, animateContent = false) {
    val spoken = Formats.timestamp(paragraph.startMs)
    val atLabel = stringResource(R.string.session_at, spoken)
    Text(
      text = spoken,
      style = MaterialTheme.typography.labelMedium,
      color = if (isActive) scheme.primary else scheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.semantics { contentDescription = atLabel },
    )
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

/** Un blocco di frasi senza pause lunghe in mezzo, tutte della stessa parte. */
private class Paragraph(
  val segments: List<SegmentEntity>,
  /** Dove comincia e finisce ogni segmento dentro il testo del paragrafo. */
  private val ranges: List<IntRange>,
  val text: String,
) {
  val startMs: Long get() = segments.first().sessionStartMs
  val endMs: Long get() = segments.last().sessionEndMs

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
 * Da segmenti a paragrafi.
 *
 * Si va a capo per due motivi: una pausa lunga, che e' quasi sempre un cambio di argomento, e il
 * confine fra due registrazioni, che e' un fatto e non un'interpretazione. C'e' anche un tetto al
 * numero di frasi, perche' chi parla senza mai fermarsi produrrebbe altrimenti una card alta come
 * dieci schermi, e una card alta dieci schermi non si scorre: si subisce.
 */
private fun paragraphsOf(segments: List<SegmentEntity>): List<Paragraph> {
  if (segments.isEmpty()) return emptyList()
  val result = mutableListOf<Paragraph>()
  var current = mutableListOf<SegmentEntity>()

  fun flush() {
    if (current.isEmpty()) return
    val builder = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    current.forEachIndexed { index, segment ->
      if (index > 0) builder.append(' ')
      val start = builder.length
      builder.append(segment.text.trim())
      ranges += start until builder.length
    }
    result += Paragraph(current.toList(), ranges, builder.toString())
    current = mutableListOf()
  }

  segments.forEachIndexed { index, segment ->
    val previous = segments.getOrNull(index - 1)
    val breaks = previous != null && (
      segment.partId != previous.partId ||
        segment.sessionStartMs - previous.sessionEndMs >= PARAGRAPH_GAP_MS ||
        current.size >= MAX_SEGMENTS_PER_PARAGRAPH
      )
    if (breaks) flush()
    current += segment
  }
  flush()
  return result
}

private const val PARAGRAPH_GAP_MS = 2_000L
private const val MAX_SEGMENTS_PER_PARAGRAPH = 10

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
    runCatching {
      val offset = paragraphOffset(listState)
      if (offset != null) {
        listState.animateScrollToItem(targetIndex + offset)
      } else {
        // Nessun paragrafo a schermo: stanno in fondo alla lista, dopo tutto il resto. Un salto
        // all'ultimo elemento ne mette qualcuno a schermo, e al fotogramma dopo si misura.
        listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        withFrameNanos { }
        val measured = paragraphOffset(listState) ?: return@runCatching
        listState.animateScrollToItem(targetIndex + measured)
      }
    }
  }
}

/** Di quanto l'indice nella lista e' spostato rispetto al numero del paragrafo, se se ne vede uno. */
private fun paragraphOffset(listState: LazyListState): Int? =
  listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
    val number = (info.key as? String)?.takeIf { it.startsWith(PARAGRAPH_KEY) }?.removePrefix(PARAGRAPH_KEY)?.toIntOrNull()
    number?.let { info.index - it }
  }

private const val PARAGRAPH_KEY = "paragraph-"

// -------------------------------------------------------------------------------------------------
// Etichette
// -------------------------------------------------------------------------------------------------

@Composable
private fun sessionHeading(title: String, date: String): String {
  val pretty = Dates.parseOrNull(date)?.let { Formats.relativeDate(it) } ?: date
  return if (title.isBlank()) pretty else "$title · $pretty"
}

@Composable
private fun transcriptLabel(kind: TranscriptKind, model: String): String = when (kind) {
  TranscriptKind.RAW -> stringResource(R.string.transcript_raw)
  TranscriptKind.REFINED -> stringResource(R.string.transcript_refined, model)
}
