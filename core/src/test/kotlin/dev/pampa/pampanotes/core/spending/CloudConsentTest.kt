package dev.pampa.pampanotes.core.spending

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.antigravity.fluidengine.ai.keys.SecretCipher
import dev.pampa.pampanotes.core.backup.BackupSettings
import dev.pampa.pampanotes.core.backup.toBackup
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CloudConsentTest {
  @Test fun `fresh preferences default off while an existing saved consent survives reopening and backup`() = runBlocking {
    val prefs = object : DataStore<Preferences> {
      override val data = MutableStateFlow(emptyPreferences())
      override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }
    val cipher = object : SecretCipher {
      override fun encrypt(plain: String) = plain
      override fun decrypt(blob: String) = blob
    }
    assertFalse(PampaSettings().autoTranscribeOnImport)
    val first = PampaSettingsStore(prefs, cipher, cipher)
    assertFalse(first.current().autoTranscribeOnImport)
    first.setAutoTranscribeOnImport(true)
    val reopened = PampaSettingsStore(prefs, cipher, cipher).current()
    assertTrue(reopened.autoTranscribeOnImport)
    assertTrue(reopened.toBackup().autoTranscribeOnImport)
    first.setAutoTranscribeOnImport(false)
    assertFalse(PampaSettingsStore(prefs, cipher, cipher).current().autoTranscribeOnImport)
  }

  @Test fun `old backup omission grants no upload consent while explicit true and false remain compatible`() {
    assertFalse(Json.decodeFromString<BackupSettings>("{}").autoTranscribeOnImport)
    assertTrue(Json.decodeFromString<BackupSettings>("""{"autoTranscribeOnImport":true}""").autoTranscribeOnImport)
    assertFalse(Json.decodeFromString<BackupSettings>("""{"autoTranscribeOnImport":false}""").autoTranscribeOnImport)
  }
}
