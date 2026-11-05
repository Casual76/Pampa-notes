package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.audio.AudioInfo
import dev.pampa.pampanotes.core.audio.AudioProbe
import java.io.File
import javax.inject.Inject

/** Injectable boundary around the real Android media probe; no binary is loaded into memory. */
class PampaBundleAudioProbe @Inject constructor() {
  fun probe(file: File): AudioInfo = AudioProbe.probe(file)
}
