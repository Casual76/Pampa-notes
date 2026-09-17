package dev.pampa.pampanotes.core.audio

import android.media.MediaMetadataRetriever
import java.io.File

/** Quello che si riesce a sapere di un file audio senza decodificarlo. */
data class AudioInfo(
  val durationMs: Long,
  val sizeBytes: Long,
  val mime: String?,
  val sampleRate: Int?,
  val channels: Int?,
  val bitrate: Int?,
) {
  val isEmpty: Boolean get() = durationMs <= 0
}

object AudioProbe {

  /**
   * Legge durata e formato. Non lancia: un file che non si legge qui e' un file che non si
   * trascrivera', e lo si scopre con un errore piu' chiaro piu' avanti.
   */
  fun probe(file: File): AudioInfo = runCatching {
    MediaMetadataRetriever().use { retriever ->
      retriever.setDataSource(file.absolutePath)
      AudioInfo(
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
        sizeBytes = file.length(),
        mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
        sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
        channels = null,
        bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
      )
    }
  }.getOrElse { AudioInfo(0, file.length(), null, null, null, null) }
}
