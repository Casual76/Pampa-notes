package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.transcription.WordTimings

/**
 * Portable timings are observations, independently of the audio duration metadata. The latter
 * determines part offsets, not the accuracy of provider timestamps. Preserve both unchanged;
 * reject malformed intervals and any observation that cannot be represented in session time.
 */
internal object PampaBundleTimings {
  fun validate(segment: SegmentEntity, offsetMs: Long) {
    require(offsetMs >= 0 && segment.partStartMs >= 0 && segment.partEndMs >= segment.partStartMs &&
      segment.partEndMs <= Long.MAX_VALUE - offsetMs) { "Invalid segment timing/reference" }
    segment.wordsJson?.let { encoded ->
      val words = WordTimings.decode(encoded, 0)
      // Word offsets may cross coarse segment boundaries, including negative relative starts.
      // These subtraction bounds prove both part- and session-absolute additions are safe,
      // without first performing a potentially overflowing addition on untrusted values.
      val earliest = -segment.partStartMs
      val latest = Long.MAX_VALUE - offsetMs - segment.partStartMs
      require(words.size == encoded.lineSequence().count { it.isNotBlank() } && words.all {
        it.startMs >= earliest && it.endMs >= it.startMs && it.endMs <= latest
      }) { "Invalid word timings" }
    }
  }
}
