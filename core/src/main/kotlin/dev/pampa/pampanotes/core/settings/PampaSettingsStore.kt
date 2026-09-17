package dev.pampa.pampanotes.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.antigravity.fluidengine.ai.keys.KeystoreCipher
import dev.antigravity.fluidengine.ai.keys.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pampaSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "pampa_settings")

/** Quale servizio trascrive di default: Groq nel cloud, o il server personale in LAN. */
enum class TranscriptionProviderId(val id: String) {
  GROQ("groq"),
  CUSTOM("custom");

  companion object {
    fun fromId(id: String?): TranscriptionProviderId = entries.firstOrNull { it.id == id } ?: GROQ
  }
}

enum class RefinementPreset { CLEAN, STRUCTURED, CUSTOM }

/**
 * Le preferenze dell'app. Quelle del tema stanno nell'engine (`EngineSettingsStore`), le chiavi dei
 * provider in `AiKeyStore`: qui c'e' solo cio' che riguarda note, trascrizioni ed export.
 */
data class PampaSettings(
  val onboardingDone: Boolean = false,
  /** ISO 639-1 oppure "auto". */
  val language: String = "auto",
  /** Nomi propri, termini tecnici: il prompt di Whisper, uno per riga. */
  val vocabulary: String = "",
  val chunkMinutes: Int = 10,
  /** Il tetto di upload di Groq: 25 MB sul piano gratuito, 100 sul dev tier. */
  val groqMaxUploadMb: Int = 25,
  val preferredProvider: TranscriptionProviderId = TranscriptionProviderId.GROQ,
  /** Trascrivi subito quando importi un audio, senza chiederlo. */
  val autoTranscribeOnImport: Boolean = true,
  val endpointUrl: String = "",
  val endpointName: String = "",
  val endpointModel: String = "",
  val endpointHasToken: Boolean = false,
  /** Quanto aspettare una risposta del server personale prima di arrendersi. */
  val endpointTimeoutMinutes: Int = 180,
  val refinementEnabled: Boolean = false,
  val refinementModel: String = "",
  val refinementPreset: RefinementPreset = RefinementPreset.CLEAN,
  val refinementCustomPrompt: String = "",
  /** L'URI SAF della cartella di backup ed export, quando l'utente ne ha scelta una. */
  val backupFolderUri: String = "",
  val autoBackup: Boolean = false,
  val lastBackupAt: Long = 0L,
  val lastExportPresetId: String = "",
  /** Il provider finto, solo nelle build di debug: la UI si prova senza spendere quota. */
  val fakeProviderEnabled: Boolean = false,
) {
  val hasEndpoint: Boolean get() = endpointUrl.isNotBlank()
  val languageOrNull: String? get() = language.takeIf { it != "auto" && it.isNotBlank() }
}

class PampaSettingsStore(
  private val store: DataStore<Preferences>,
  private val cipher: SecretCipher,
) {
  constructor(context: Context) : this(context.pampaSettingsStore, KeystoreCipher(alias = ENDPOINT_ALIAS))

  val settings: Flow<PampaSettings> = store.data.map { it.toSettings() }

  suspend fun current(): PampaSettings = settings.first()

  suspend fun setOnboardingDone(done: Boolean) = edit { it[OnboardingDone] = done }
  suspend fun setLanguage(language: String) = edit { it[Language] = language }
  suspend fun setVocabulary(vocabulary: String) = edit { it[Vocabulary] = vocabulary }
  suspend fun setChunkMinutes(minutes: Int) = edit { it[ChunkMinutes] = minutes.coerceIn(3, 20) }
  suspend fun setGroqMaxUploadMb(mb: Int) = edit { it[GroqMaxUploadMb] = mb.coerceIn(5, 100) }
  suspend fun setPreferredProvider(provider: TranscriptionProviderId) = edit { it[PreferredProvider] = provider.id }
  suspend fun setAutoTranscribeOnImport(enabled: Boolean) = edit { it[AutoTranscribe] = enabled }
  suspend fun setEndpoint(url: String, name: String, model: String) = edit {
    it[EndpointUrl] = url.trim().trimEnd('/')
    it[EndpointName] = name.trim()
    it[EndpointModel] = model.trim()
  }
  suspend fun setEndpointTimeoutMinutes(minutes: Int) = edit { it[EndpointTimeout] = minutes.coerceIn(5, 720) }
  suspend fun setRefinementEnabled(enabled: Boolean) = edit { it[RefinementEnabled] = enabled }
  suspend fun setRefinementModel(model: String) = edit { it[RefinementModel] = model }
  suspend fun setRefinementPreset(preset: RefinementPreset) = edit { it[RefinementPresetKey] = preset.name }
  suspend fun setRefinementCustomPrompt(prompt: String) = edit { it[RefinementPrompt] = prompt }
  suspend fun setBackupFolderUri(uri: String) = edit { it[BackupFolder] = uri }
  suspend fun setAutoBackup(enabled: Boolean) = edit { it[AutoBackup] = enabled }
  suspend fun setLastBackupAt(atMillis: Long) = edit { it[LastBackupAt] = atMillis }
  suspend fun setLastExportPresetId(id: String) = edit { it[LastExportPreset] = id }
  suspend fun setFakeProviderEnabled(enabled: Boolean) = edit { it[FakeProvider] = enabled }

  /** Il token in chiaro, decifrato al momento: non passa mai da un Flow. */
  suspend fun endpointToken(): String? {
    val blob = store.data.first()[EndpointTokenBlob] ?: return null
    return cipher.decrypt(blob)?.takeIf { it.isNotBlank() }
  }

  suspend fun setEndpointToken(token: String?) = edit { prefs ->
    val trimmed = token?.trim()
    if (trimmed.isNullOrEmpty()) {
      prefs.remove(EndpointTokenBlob)
    } else {
      prefs[EndpointTokenBlob] = cipher.encrypt(trimmed)
    }
  }

  private suspend fun edit(block: (MutablePreferences) -> Unit) {
    store.edit(block)
  }

  private fun Preferences.toSettings(): PampaSettings = PampaSettings(
    onboardingDone = this[OnboardingDone] ?: false,
    language = this[Language] ?: "auto",
    vocabulary = this[Vocabulary] ?: "",
    chunkMinutes = this[ChunkMinutes] ?: 10,
    groqMaxUploadMb = this[GroqMaxUploadMb] ?: 25,
    preferredProvider = TranscriptionProviderId.fromId(this[PreferredProvider]),
    autoTranscribeOnImport = this[AutoTranscribe] ?: true,
    endpointUrl = this[EndpointUrl] ?: "",
    endpointName = this[EndpointName] ?: "",
    endpointModel = this[EndpointModel] ?: "",
    endpointHasToken = this[EndpointTokenBlob] != null,
    endpointTimeoutMinutes = this[EndpointTimeout] ?: 180,
    refinementEnabled = this[RefinementEnabled] ?: false,
    refinementModel = this[RefinementModel] ?: "",
    refinementPreset = this[RefinementPresetKey]?.let { runCatching { RefinementPreset.valueOf(it) }.getOrNull() } ?: RefinementPreset.CLEAN,
    refinementCustomPrompt = this[RefinementPrompt] ?: "",
    backupFolderUri = this[BackupFolder] ?: "",
    autoBackup = this[AutoBackup] ?: false,
    lastBackupAt = this[LastBackupAt] ?: 0L,
    lastExportPresetId = this[LastExportPreset] ?: "",
    fakeProviderEnabled = this[FakeProvider] ?: false,
  )

  private companion object {
    const val ENDPOINT_ALIAS = "dev.pampa.pampanotes.endpoint"

    val OnboardingDone = booleanPreferencesKey("onboarding_done")
    val Language = stringPreferencesKey("language")
    val Vocabulary = stringPreferencesKey("vocabulary")
    val ChunkMinutes = intPreferencesKey("chunk_minutes")
    val GroqMaxUploadMb = intPreferencesKey("groq_max_upload_mb")
    val PreferredProvider = stringPreferencesKey("preferred_provider")
    val AutoTranscribe = booleanPreferencesKey("auto_transcribe")
    val EndpointUrl = stringPreferencesKey("endpoint_url")
    val EndpointName = stringPreferencesKey("endpoint_name")
    val EndpointModel = stringPreferencesKey("endpoint_model")
    val EndpointTokenBlob = stringPreferencesKey("endpoint_token")
    val EndpointTimeout = intPreferencesKey("endpoint_timeout_minutes")
    val RefinementEnabled = booleanPreferencesKey("refinement_enabled")
    val RefinementModel = stringPreferencesKey("refinement_model")
    val RefinementPresetKey = stringPreferencesKey("refinement_preset")
    val RefinementPrompt = stringPreferencesKey("refinement_prompt")
    val BackupFolder = stringPreferencesKey("backup_folder")
    val AutoBackup = booleanPreferencesKey("auto_backup")
    val LastBackupAt = longPreferencesKey("last_backup_at")
    val LastExportPreset = stringPreferencesKey("last_export_preset")
    val FakeProvider = booleanPreferencesKey("fake_provider")
  }
}
