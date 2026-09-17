package dev.pampa.pampanotes.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** Un pezzo pronto da mandare: il file, cosa contiene, e quanto pesa. */
data class EncodedChunk(
  val spec: ChunkSpec,
  val file: File,
  val mime: String,
) {
  val sizeBytes: Long get() = file.length()
}

/**
 * Ritaglia il PCM e lo ricodifica in pezzi piccoli.
 *
 * AAC a 48 kbps mono: dieci minuti stanno in tre megabyte e mezzo, contro i diciannove del WAV e i
 * venticinque del limite di Groq. La differenza non e' cosmetica — e' cio' che permette di mandare
 * un pezzo da dieci minuti invece che da tre, cioe' sei richieste invece di venti per una lezione.
 *
 * Se il telefono non ha un encoder AAC utilizzabile si ripiega sul WAV, che non ha bisogno di
 * codec: piu' grosso ma sempre sotto il limite, e meglio di un lavoro che non parte.
 */
object ChunkEncoder {

  private const val AAC_MIME = "audio/mp4a-latm"
  private const val AAC_BITRATE = 48_000
  private const val DEQUEUE_TIMEOUT_US = 10_000L

  const val WAV_MIME = "audio/wav"
  const val M4A_MIME = "audio/mp4"

  /**
   * @param pcm il file prodotto da [PcmDecoder], 16 bit mono alla frequenza indicata.
   * @param outputDir dove finiscono i pezzi. Chi chiama la svuota quando il lavoro e' finito.
   */
  fun encode(
    pcm: File,
    sampleRate: Int,
    spec: ChunkSpec,
    outputDir: File,
    onProgress: (fraction: Float) -> Unit = {},
  ): EncodedChunk {
    outputDir.mkdirs()
    val startByte = (spec.startMs * sampleRate / 1000L) * 2L
    val endByte = minOf((spec.endMs * sampleRate / 1000L) * 2L, pcm.length())
    if (endByte <= startByte) throw TranscriptionError.Decode("pezzo ${spec.index} vuoto")

    val aacTarget = File(outputDir, "chunk-${spec.index}.m4a")
    return runCatching {
      encodeAac(pcm, sampleRate, startByte, endByte, aacTarget, onProgress)
      EncodedChunk(spec, aacTarget, M4A_MIME)
    }.getOrElse {
      aacTarget.delete()
      val wavTarget = File(outputDir, "chunk-${spec.index}.wav")
      writeWav(pcm, sampleRate, startByte, endByte, wavTarget, onProgress)
      EncodedChunk(spec, wavTarget, WAV_MIME)
    }
  }

  private fun encodeAac(
    pcm: File,
    sampleRate: Int,
    startByte: Long,
    endByte: Long,
    target: File,
    onProgress: (Float) -> Unit,
  ) {
    val format = MediaFormat.createAudioFormat(AAC_MIME, sampleRate, 1).apply {
      setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
    }
    val codec = MediaCodec.createEncoderByType(AAC_MIME)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()

    val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var trackIndex = -1
    var muxerStarted = false

    RandomAccessFile(pcm, "r").use { input ->
      input.seek(startByte)
      val total = endByte - startByte
      var read = 0L
      var presentationUs = 0L
      val bufferInfo = MediaCodec.BufferInfo()
      var sawInputEnd = false
      var sawOutputEnd = false

      try {
        while (!sawOutputEnd) {
          if (!sawInputEnd) {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
              val buffer = codec.getInputBuffer(inputIndex)!!
              buffer.clear()
              val wanted = minOf(buffer.capacity().toLong(), total - read).toInt()
              if (wanted <= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                sawInputEnd = true
              } else {
                val bytes = ByteArray(wanted)
                val got = input.read(bytes)
                if (got <= 0) {
                  codec.queueInputBuffer(inputIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                  sawInputEnd = true
                } else {
                  buffer.put(bytes, 0, got)
                  codec.queueInputBuffer(inputIndex, 0, got, presentationUs, 0)
                  read += got
                  // Due byte per campione: il tempo di presentazione e' quello, non una stima.
                  presentationUs += got * 1_000_000L / (2L * sampleRate)
                  onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
              }
            }
          }

          val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
          when {
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              // Il formato vero arriva solo adesso: il muxer non si puo' avviare prima.
              trackIndex = muxer.addTrack(codec.outputFormat)
              muxer.start()
              muxerStarted = true
            }

            outputIndex >= 0 -> {
              val encoded = codec.getOutputBuffer(outputIndex)!!
              val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
              if (!isConfig && bufferInfo.size > 0 && muxerStarted) {
                encoded.position(bufferInfo.offset)
                encoded.limit(bufferInfo.offset + bufferInfo.size)
                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
              }
              codec.releaseOutputBuffer(outputIndex, false)
              if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
            }
          }
        }
      } finally {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
      }
    }

    if (!target.exists() || target.length() == 0L) {
      throw TranscriptionError.Decode("la codifica AAC non ha prodotto niente")
    }
    onProgress(1f)
  }

  /**
   * Il ripiego: un WAV, cioe' il PCM con quarantaquattro byte di intestazione davanti.
   *
   * Nessun codec, quindi non puo' fallire per mancanza di un encoder. Dieci minuti sono diciannove
   * megabyte, che stanno ancora sotto il limite di Groq.
   */
  fun writeWav(
    pcm: File,
    sampleRate: Int,
    startByte: Long,
    endByte: Long,
    target: File,
    onProgress: (Float) -> Unit = {},
  ) {
    val dataSize = (endByte - startByte).toInt()
    target.outputStream().buffered(256 * 1024).use { out ->
      out.write(wavHeader(dataSize, sampleRate))
      RandomAccessFile(pcm, "r").use { input ->
        input.seek(startByte)
        val buffer = ByteArray(256 * 1024)
        var remaining = dataSize
        while (remaining > 0) {
          val got = input.read(buffer, 0, minOf(buffer.size, remaining))
          if (got <= 0) break
          out.write(buffer, 0, got)
          remaining -= got
          onProgress(1f - remaining.toFloat() / dataSize)
        }
      }
    }
    onProgress(1f)
  }

  /** L'intestazione RIFF di un WAV PCM 16 bit mono. */
  fun wavHeader(dataSize: Int, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    return java.nio.ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray(Charsets.US_ASCII))
      putInt(36 + dataSize)
      put("WAVE".toByteArray(Charsets.US_ASCII))
      put("fmt ".toByteArray(Charsets.US_ASCII))
      putInt(16)
      putShort(1)
      putShort(channels.toShort())
      putInt(sampleRate)
      putInt(byteRate)
      putShort(blockAlign.toShort())
      putShort(bitsPerSample.toShort())
      put("data".toByteArray(Charsets.US_ASCII))
      putInt(dataSize)
    }.array()
  }
}
