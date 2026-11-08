package dev.pampa.pampanotes.core.backup

import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupCloudPreferencesTest {
  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  @Test fun `selected provider and its independent STT models survive backup serialization`() {
    val settings = PampaSettings(
      preferredProvider = TranscriptionProviderId.MISTRAL,
      groqTranscriptionModel = "whisper-large-v3",
      mistralTranscriptionModel = "voxtral-small-transcribe-3",
      openRouterTranscriptionModel = "chosen/transcription-model",
    )
    val saved = settings.toBackup()
    val restored = json.decodeFromString(BackupSettings.serializer(), json.encodeToString(BackupSettings.serializer(), saved))
    assertEquals(TranscriptionProviderId.MISTRAL, restored.providerId())
    assertEquals("whisper-large-v3", restored.groqTranscriptionModel)
    assertEquals("voxtral-small-transcribe-3", restored.mistralTranscriptionModel)
    assertEquals("chosen/transcription-model", restored.openRouterTranscriptionModel)
  }

  @Test fun `legacy backups retain safe model defaults and grant no automatic upload consent`() {
    val restored = json.decodeFromString(BackupSettings.serializer(), "{}")
    assertEquals("whisper-large-v3-turbo", restored.groqTranscriptionModel)
    assertEquals("voxtral-mini-transcribe-3", restored.mistralTranscriptionModel)
    assertFalse(restored.autoTranscribeOnImport)
  }
}
