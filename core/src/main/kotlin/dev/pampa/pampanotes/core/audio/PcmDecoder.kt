package dev.pampa.pampanotes.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Il risultato della decodifica: il PCM su disco e l'energia di ogni finestra, per il pianificatore. */
data class DecodedAudio(
  val pcmFile: File,
  val sampleRate: Int,
  val frameEnergies: FloatArray,
  val frameMs: Long,
  val durationMs: Long,
) {
  /** Dove comincia, in byte, il campione di un certo istante. Due byte per campione, un canale. */
  fun byteOffsetOf(timeMs: Long): Long = (timeMs * sampleRate / 1000L) * 2L
}

/**
 * Da un file audio qualsiasi a PCM 16 bit, mono, 16 kHz, su disco.
 *
 * Tre trasformazioni, e ognuna ha una ragione precisa:
 *
 *  - **Mono**: Whisper lavora su un canale solo, e due canali raddoppiano i byte da caricare per
 *    niente.
 *  - **16 kHz**: e' la frequenza a cui Whisper ricampiona comunque tutto quello che riceve. Farlo
 *    qui significa mandare un file tre volte piu' piccolo che dice esattamente le stesse cose.
 *  - **Su disco, mai in memoria**: un'ora a 16 kHz mono sono 115 MB di PCM. Tenerli in un array
 *    significa morire di memoria sul primo file lungo.
 *
 * Nello stesso passaggio si calcola l'energia di ogni finestra da 20 ms: il pianificatore ne ha
 * bisogno per scegliere dove tagliare, e rileggere il PCM una seconda volta per ottenerla sarebbe
 * un giro di I/O gratuito su cento megabyte.
 */
object PcmDecoder {

  const val TARGET_SAMPLE_RATE = 16_000
  const val FRAME_MS = ChunkPlanner.DEFAULT_FRAME_MS

  /** Oltre questo il decoder e' bloccato su qualcosa: meglio un errore che un lavoro fermo per sempre. */
  private const val DEQUEUE_TIMEOUT_US = 10_000L

  fun decodeToPcm(
    source: File,
    target: File,
    onProgress: (fraction: Float) -> Unit = {},
  ): DecodedAudio {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
      extractor.setDataSource(source.absolutePath)
      val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      } ?: throw TranscriptionError.Decode("il file non contiene una traccia audio")

      val inputFormat = extractor.getTrackFormat(trackIndex)
      extractor.selectTrack(trackIndex)

      val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
      val sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      val totalUs = runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

      codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrElse {
        throw TranscriptionError.Decode("questo telefono non sa decodificare $mime", it)
      }
      codec.configure(inputFormat, null, null, 0)
      codec.start()

      val energies = ArrayList<Float>(estimateFrames(totalUs))
      val samplesPerFrame = TARGET_SAMPLE_RATE * FRAME_MS / 1000L
      var frameSum = 0.0
      var frameCount = 0L
      var writtenSamples = 0L

      DataOutputStream(BufferedOutputStream(target.outputStream(), 256 * 1024)).use { out ->
        val resampler = Resampler(sourceRate, TARGET_SAMPLE_RATE)
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false

        while (!sawOutputEnd) {
          if (!sawInputEnd) {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
              val buffer = codec.getInputBuffer(inputIndex)!!
              val size = extractor.readSampleData(buffer, 0)
              if (size < 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                sawInputEnd = true
              } else {
                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                extractor.advance()
              }
            }
          }

          val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
          when {
            outputIndex >= 0 -> {
              val buffer = codec.getOutputBuffer(outputIndex)!!
              if (bufferInfo.size > 0) {
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val mono = downmix(shorts, sourceChannels)
                val resampled = resampler.process(mono)
                for (sample in resampled) {
                  out.writeByte(sample.toInt() and 0xFF)
                  out.writeByte((sample.toInt() shr 8) and 0xFF)
                  val normalized = sample / 32768f
                  frameSum += (normalized * normalized).toDouble()
                  frameCount++
                  writtenSamples++
                  if (frameCount >= samplesPerFrame) {
                    energies += sqrt(frameSum / frameCount).toFloat()
                    frameSum = 0.0
                    frameCount = 0
                  }
                }
              }
              codec.releaseOutputBuffer(outputIndex, false)
              if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
              if (totalUs > 0) onProgress((bufferInfo.presentationTimeUs.toFloat() / totalUs).coerceIn(0f, 1f))
            }

            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
            outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
          }
        }
        // L'ultima finestra incompleta conta lo stesso: senza, l'ultimo mezzo secondo di un audio
        // non avrebbe energia e il pianificatore lo leggerebbe come silenzio.
        if (frameCount > 0) energies += sqrt(frameSum / frameCount).toFloat()
      }

      onProgress(1f)
      return DecodedAudio(
        pcmFile = target,
        sampleRate = TARGET_SAMPLE_RATE,
        frameEnergies = energies.toFloatArray(),
        frameMs = FRAME_MS,
        durationMs = writtenSamples * 1000L / TARGET_SAMPLE_RATE,
      )
    } catch (e: TranscriptionError) {
      target.delete()
      throw e
    } catch (t: Throwable) {
      target.delete()
      throw TranscriptionError.Decode(t.message ?: "audio illeggibile", t)
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor.release() }
    }
  }

  private fun estimateFrames(totalUs: Long): Int =
    if (totalUs <= 0) 1024 else (totalUs / 1000 / FRAME_MS).toInt().coerceAtLeast(1024)

  /**
   * Da N canali a uno, facendo la media.
   *
   * La media e non il primo canale: in una registrazione fatta con un telefono appoggiato al banco
   * i due canali non sono lo stesso segnale, e buttarne uno butta meta' della stanza.
   */
  private fun downmix(shorts: java.nio.ShortBuffer, channels: Int): ShortArray {
    val total = shorts.remaining()
    if (channels <= 1) {
      val out = ShortArray(total)
      shorts.get(out)
      return out
    }
    val frames = total / channels
    val out = ShortArray(frames)
    val scratch = ShortArray(total)
    shorts.get(scratch)
    for (i in 0 until frames) {
      var sum = 0
      for (c in 0 until channels) sum += scratch[i * channels + c]
      out[i] = (sum / channels).toShort()
    }
    return out
  }
}

/**
 * Ricampionamento lineare con un filtro a due prese.
 *
 * L'interpolazione lineare da sola introduce alias sopra gli 8 kHz quando si scende da 44,1: si
 * sente come un sibilo, e il modello lo trascrive come rumore. Una media mobile corta prima di
 * ricampionare toglie la parte del segnale che a 16 kHz non ci sta piu', ed e' tutto quello che
 * serve — un filtro vero qui sarebbe cento righe per una differenza che il modello non sente.
 */
class Resampler(private val fromRate: Int, private val toRate: Int) {
  private var position = 0.0
  private var previousSample: Short = 0
  private var carry: Short? = null

  fun process(input: ShortArray): ShortArray {
    if (fromRate == toRate) return input
    if (input.isEmpty()) return input

    val ratio = fromRate.toDouble() / toRate
    // Media con il campione precedente: il filtro passa-basso piu' corto che esista.
    val smoothed = ShortArray(input.size)
    var prev = carry ?: input[0]
    for (i in input.indices) {
      smoothed[i] = ((prev + input[i]) / 2).toShort()
      prev = input[i]
    }
    carry = prev

    val out = ArrayList<Short>((input.size / ratio).toInt() + 2)
    var index = position
    while (index < smoothed.size) {
      val i = index.toInt()
      val frac = index - i
      val a = if (i == 0) previousSample else smoothed[i - 1]
      val b = smoothed[i]
      out += (a + (b - a) * frac).toInt().toShort()
      index += ratio
    }
    // Quello che avanza passa al blocco successivo: senza, ogni blocco ricomincerebbe da zero e
    // l'audio acquisterebbe un clic ogni pochi millisecondi.
    position = index - smoothed.size
    previousSample = smoothed.last()
    return out.toShortArray()
  }
}
