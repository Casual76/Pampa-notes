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
      // Quello che dice il contenitore e' una prima ipotesi: il decoder puo' uscire con un'altra
      // frequenza (l'HE-AAC dichiara 24 kHz e ne esce a 48), un altro numero di canali, o in
      // virgola mobile. Quello che conta e' il formato d'uscita, e arriva con
      // INFO_OUTPUT_FORMAT_CHANGED prima del primo buffer.
      var outRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      var outChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      var outEncoding = PcmFrames.ENCODING_PCM_16BIT
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
        var resampler = Resampler(outRate, TARGET_SAMPLE_RATE)
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
                val mono = PcmFrames.toMono16(buffer.order(ByteOrder.nativeOrder()), outEncoding, outChannels)
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

            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              val format = codec.outputFormat
              val rate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(outRate)
              outChannels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(outChannels).coerceAtLeast(1)
              outEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
              } else {
                PcmFrames.ENCODING_PCM_16BIT
              }
              if (!PcmFrames.supports(outEncoding)) throw TranscriptionError.Decode("formato PCM $outEncoding non gestito")
              // Un ricampionatore nuovo solo se la frequenza cambia davvero: quello vecchio porta
              // con se' il resto del blocco precedente, e buttarlo senza motivo farebbe un clic.
              if (rate != outRate) {
                outRate = rate
                resampler = Resampler(outRate, TARGET_SAMPLE_RATE)
              }
            }
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
}

/**
 * Da un buffer PCM qualsiasi a campioni 16 bit mono.
 *
 * Puro — niente Android — perche' la conversione si prova in JVM: i valori delle codifiche sono
 * quelli di `AudioFormat`, ricopiati qui.
 */
object PcmFrames {
  const val ENCODING_PCM_16BIT = 2
  const val ENCODING_PCM_8BIT = 3
  const val ENCODING_PCM_FLOAT = 4
  const val ENCODING_PCM_24BIT_PACKED = 21
  const val ENCODING_PCM_32BIT = 22

  fun supports(encoding: Int): Boolean = encoding in setOf(
    ENCODING_PCM_16BIT, ENCODING_PCM_8BIT, ENCODING_PCM_FLOAT, ENCODING_PCM_24BIT_PACKED, ENCODING_PCM_32BIT,
  )

  private fun bytesPerSample(encoding: Int): Int = when (encoding) {
    ENCODING_PCM_8BIT -> 1
    ENCODING_PCM_16BIT -> 2
    ENCODING_PCM_24BIT_PACKED -> 3
    ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> 4
    else -> throw IllegalArgumentException("codifica PCM $encoding")
  }

  /**
   * Legge tutto quello che resta in [buffer] (gia' nell'ordine di byte giusto) e restituisce un
   * campione mono per fotogramma.
   *
   * La media dei canali e non il primo: in una registrazione fatta con un telefono appoggiato al
   * banco i due canali non sono lo stesso segnale, e buttarne uno butta meta' della stanza. I
   * campioni in virgola mobile si tagliano a ±1 prima di scalare: un decoder che esce di poco oltre
   * il fondo scala altrimenti produrrebbe un salto di segno, cioe' un clic.
   */
  fun toMono16(buffer: java.nio.ByteBuffer, encoding: Int, channels: Int): ShortArray {
    val width = bytesPerSample(encoding)
    val channelCount = channels.coerceAtLeast(1)
    val frames = buffer.remaining() / (width * channelCount)
    val out = ShortArray(frames)
    for (frame in 0 until frames) {
      var sum = 0L
      for (c in 0 until channelCount) sum += readSample16(buffer, encoding)
      out[frame] = (sum / channelCount).toInt().toShort()
    }
    return out
  }

  /** Un campione, gia' portato a 16 bit con segno. */
  private fun readSample16(buffer: java.nio.ByteBuffer, encoding: Int): Int = when (encoding) {
    ENCODING_PCM_16BIT -> buffer.short.toInt()
    // Otto bit senza segno, con lo zero a 128.
    ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) shl 8
    ENCODING_PCM_FLOAT -> (buffer.float.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
    ENCODING_PCM_32BIT -> buffer.int shr 16
    ENCODING_PCM_24BIT_PACKED -> {
      // Tre byte; quello piu' significativo porta il segno, e `toInt()` su un Byte lo estende.
      val first = buffer.get().toInt()
      val middle = buffer.get().toInt() and 0xFF
      val last = buffer.get().toInt()
      val value = if (buffer.order() == java.nio.ByteOrder.LITTLE_ENDIAN) {
        (last shl 16) or (middle shl 8) or (first and 0xFF)
      } else {
        (first shl 16) or (middle shl 8) or (last and 0xFF)
      }
      value shr 8
    }
    else -> throw IllegalArgumentException("codifica PCM $encoding")
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

    // Un array di Short e non una lista: una lista di Short sono un oggetto per campione, cioe'
    // sedici milioni di oggetti per un'ora di audio a 44,1 kHz, e il garbage collector li sente.
    val out = ShortArray((input.size / ratio).toInt() + 2)
    var count = 0
    var index = position
    while (index < smoothed.size) {
      val i = index.toInt()
      val frac = index - i
      val a = if (i == 0) previousSample else smoothed[i - 1]
      val b = smoothed[i]
      out[count++] = (a + (b - a) * frac).toInt().toShort()
      index += ratio
    }
    // Quello che avanza passa al blocco successivo: senza, ogni blocco ricomincerebbe da zero e
    // l'audio acquisterebbe un clic ogni pochi millisecondi.
    position = index - smoothed.size
    previousSample = smoothed.last()
    return if (count == out.size) out else out.copyOf(count)
  }
}
