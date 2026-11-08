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

  @Test fun `public model preferences travel without installation account or device state`() {
    val settings = PampaSettings(
      preferredProvider = TranscriptionProviderId.MISTRAL,
      mistralTranscriptionModel = "public-transcription-model",
      refinementModel = "public-refinement-model",
      syncDeviceId = "SYNTHETIC_PRIVATE_DEVICE_418",
      syncDeviceName = "SYNTHETIC_PRIVATE_LABEL_419",
      syncAccount = "SYNTHETIC_PRIVATE_ACCOUNT_420",
      syncServerUrl = "SYNTHETIC_PRIVATE_SYNC_ENDPOINT_421",
      lastSyncError = "SYNTHETIC_PRIVATE_PENDING_ERROR_422",
      syncHasToken = true,
      endpointHasToken = true,
    )
    val encoded = json.encodeToString(BackupSettings.serializer(), settings.toBackup())
    assertFalse(encoded.contains("SYNTHETIC_PRIVATE_"))
    assertFalse(encoded.contains("syncHasToken"))
    assertFalse(encoded.contains("endpointHasToken"))
    val restored = json.decodeFromString(BackupSettings.serializer(), encoded)
    assertEquals("public-transcription-model", restored.mistralTranscriptionModel)
    assertEquals("public-refinement-model", restored.refinementModel)
    assertEquals(TranscriptionProviderId.MISTRAL, restored.providerId())
  }
}
