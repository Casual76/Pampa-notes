package dev.pampa.pampanotes.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.pampa.pampanotes.core.transcription.SessionAssembler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Una parte da suonare: il file su disco e quanto dura. */
data class PlayablePart(
  val id: String,
  val file: File,
  val durationMs: Long,
)

data class PlaybackState(
  val playing: Boolean = false,
  /** Millisecondi dall'inizio della **sessione**, non della parte: e' il tempo che la UI conosce. */
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val partIndex: Int = 0,
  val speed: Float = 1f,
  val ready: Boolean = false,
  val error: Boolean = false,
) {
  val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Le parti di una sessione suonate come se fossero un file solo.
 *
 * Tutto quello che entra ed esce e' in tempo di sessione. Dentro ci sono N file e un indice di
 * playlist, ma chi guarda la pagina ha visto una trascrizione con un cronometro unico, e toccando
 * la riga del minuto quaranta si aspetta di sentire il minuto quaranta — non il minuto quaranta
 * della terza registrazione.
 *
 * La posizione si legge a intervalli invece di arrivare da sola: ExoPlayer non emette il tempo che
 * scorre, lo tiene e lo si chiede. Si chiede solo mentre suona, perche' un lettore in pausa che
 * sveglia la UI cinque volte al secondo e' batteria buttata.
 *
 * `@OptIn` e non `@UnstableApi`: l'API sperimentale di media3 la usa questa classe, e se ne prende
 * lei la responsabilita'. Marcata `@UnstableApi` passava l'obbligo a chiunque la toccasse — il
 * ViewModel, la barra del lettore — e il lint rifiutava la build di release per ognuno di loro.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(
  context: Context,
  private val scope: CoroutineScope,
) {

  private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
    // Con il fuoco audio gestito, una telefonata mette in pausa e non si sovrappone; dichiararsi
    // parlato invece che musica dice al sistema come trattare l'equalizzazione e il ducking.
    setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build(),
      true,
    )
    // Le cuffie staccate mettono in pausa: senza, la lezione continua dall'altoparlante in aula.
    setHandleAudioBecomingNoisy(true)
  }

  private val _state = MutableStateFlow(PlaybackState())
  val state: StateFlow<PlaybackState> = _state.asStateFlow()

  private var parts: List<SessionAssembler.Part> = emptyList()
  private var ticker: Job? = null

  private val listener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      _state.update { it.copy(playing = isPlaying) }
      if (isPlaying) startTicking() else stopTicking().also { publishPosition() }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      _state.update { it.copy(ready = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) }
      if (playbackState == Player.STATE_ENDED) publishPosition()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publishPosition()

    override fun onPlayerError(error: PlaybackException) {
      _state.update { it.copy(error = true, playing = false) }
    }
  }

  init {
    player.addListener(listener)
  }

  /**
   * Carica la playlist, se e' cambiata.
   *
   * Confronta prima: la schermata ricompone a ogni battito del cronometro, e ricaricare la playlist
   * a ogni ricomposizione vorrebbe dire un lettore che riparte da capo cinque volte al secondo.
   */
  fun load(playable: List<PlayablePart>) {
    val next = playable.map { SessionAssembler.Part(it.id, it.durationMs) }
    if (next == parts && player.mediaItemCount == playable.size) return

    parts = next
    val position = _state.value.positionMs
    player.setMediaItems(playable.map { MediaItem.fromUri(Uri.fromFile(it.file)) })
    player.prepare()
    _state.update {
      it.copy(durationMs = SessionAssembler.totalDurationMs(parts), error = false)
    }
    // Una parte spostata mentre si ascoltava non deve riportare il lettore all'inizio: il punto in
    // cui si era resta valido, e' la playlist sotto che e' cambiata.
    if (position > 0) seekTo(position)
  }

  fun playPause() {
    if (player.isPlaying) player.pause() else player.play()
  }

  fun pause() = player.pause()

  /** Parte, se non sta gia' suonando: «Riprendi ad ascoltare» dalla home. */
  fun play() = player.play()

  /** Salta al millisecondo della sessione: la parte giusta e il punto giusto dentro di lei. */
  fun seekTo(sessionMs: Long) {
    val target = SessionAssembler.locate(parts, sessionMs) ?: return
    player.seekTo(target.partIndex, target.offsetInPartMs)
    publishPosition()
  }

  /** Avanti o indietro di qualche secondo, attraversando i confini fra parti. */
  fun skip(deltaMs: Long) = seekTo((_state.value.positionMs + deltaMs).coerceIn(0L, _state.value.durationMs))

  fun setSpeed(speed: Float) {
    player.setPlaybackSpeed(speed)
    _state.update { it.copy(speed = speed) }
  }

  fun release() {
    stopTicking()
    player.removeListener(listener)
    player.release()
  }

  private fun startTicking() {
    ticker?.cancel()
    ticker = scope.launch {
      while (true) {
        publishPosition()
        delay(TICK_MS)
      }
    }
  }

  private fun stopTicking() {
    ticker?.cancel()
    ticker = null
  }

  private fun publishPosition() {
    val index = player.currentMediaItemIndex
    val offset = parts.take(index).sumOf { it.durationMs }
    val position = offset + player.currentPosition.coerceAtLeast(0L)
    _state.update {
      it.copy(
        positionMs = position.coerceIn(0L, it.durationMs.coerceAtLeast(0L)),
        partIndex = index,
      )
    }
  }

  companion object {
    /** Cinque volte al secondo: sotto non si vede, sopra si sente sulla batteria e non si vede lo stesso. */
    private const val TICK_MS = 200L

    /** Quanto salta un tocco sulle frecce. Quindici secondi e' la frase che non si e' capita. */
    const val SKIP_MS = 15_000L

    /** Le velocita' che si possono scegliere: una lezione a 1,5 si ascolta, a 3 non si capisce. */
    val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)
  }
}
