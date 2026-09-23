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
  /**
   * Mai con Groq: ogni trascrizione, anche quella automatica all'import, va al computer di casa e
   * lo aspetta se non risponde. E' la garanzia che serve per tenere accesa la trascrizione
   * automatica senza il rischio che una lezione finisca nel cloud per sbaglio.
   */
  val customOnly: Boolean = false,
  /** Trascrivi subito quando importi un audio, senza chiederlo. */
  val autoTranscribeOnImport: Boolean = true,
  /** L'indirizzo di casa, sulla rete locale. */
  val endpointUrl: String = "",
  /** L'indirizzo che vale anche da fuori (Tailscale). Vuoto se non lo si usa. */
  val endpointRemoteUrl: String = "",
  val endpointName: String = "",
  val endpointModel: String = "",
  val endpointHasToken: Boolean = false,
  /** Quanto aspettare una risposta del server personale prima di arrendersi. */
  val endpointTimeoutMinutes: Int = 180,
  /**
   * Il computer segue l'account: quando e' stato scritto l'ultima volta (orologio di chi l'ha
   * scritto, qui o altrove) e se la modifica e' nata qui e non e' ancora salita. Vince l'ultimo
   * che ha scritto; `ComputerSync` fa il resto a ogni giro di sincronizzazione.
   */
  val endpointUpdatedAt: Long = 0L,
  val endpointDirty: Boolean = false,
  /** Arrivato dall'account e non ancora toccato qui: il primo avvio lo dice invece di chiederlo. */
  val endpointFromAccount: Boolean = false,
  /** Caricare registrazioni e originali sul computer di casa, quando lo si raggiunge. */
  val archiveEnabled: Boolean = false,
  /** Solo su Wi-Fi: un `.sdocx` da mezzo giga sulla rete dati e' un errore che si paga in bolletta. */
  val archiveOnlyUnmetered: Boolean = true,
  val lastArchiveAt: Long = 0L,
  /**
   * Tieni tutto anche qui: i file registrati o importati su un altro dispositivo si scaricano dal
   * computer di casa appena l'indice li porta, non solo quando servono. Segue «solo su Wi-Fi»
   * dell'archivio: e' lo stesso volume nel verso opposto.
   */
  val mirrorEnabled: Boolean = false,
  /** L'indice in cloud: acceso, dove sta, e come si e' andati l'ultima volta. */
  val syncEnabled: Boolean = false,
  val syncServerUrl: String = "",
  val syncHasToken: Boolean = false,
  /** Un id per questo dispositivo, nato qui e mai nel backup: e' come il server ci distingue dal tablet. */
  val syncDeviceId: String = "",
  val syncDeviceName: String = "",
  /** L'account Google con cui si e' aperta la sessione, per dirlo in pagina. Vuoto con un codice. */
  val syncAccount: String = "",
  val lastSyncAt: Long = 0L,
  val lastSyncError: String = "",
  val refinementEnabled: Boolean = false,
  val refinementModel: String = "",
  val refinementPreset: RefinementPreset = RefinementPreset.CLEAN,
  val refinementCustomPrompt: String = "",
  /** L'URI SAF della cartella di backup ed export, quando l'utente ne ha scelta una. */
  val backupFolderUri: String = "",
  val autoBackup: Boolean = false,
  val lastBackupAt: Long = 0L,
  val lastExportPresetId: String = "",
  /**
   * Le opzioni con cui si esporta, in JSON.
   *
   * Si riscrivono da sole dopo ogni export riuscito: chi esporta due volte di fila vuole quasi
   * sempre le stesse cose dentro, e ritoccare cinque interruttori ogni volta e' il tipo di attrito
   * per cui una funzione smette di essere usata.
   */
  val exportDefaultsJson: String = "",
  /** Il provider finto, solo nelle build di debug: la UI si prova senza spendere quota. */
  val fakeProviderEnabled: Boolean = false,
) {
  val hasEndpoint: Boolean get() = endpointUrl.isNotBlank() || endpointRemoteUrl.isNotBlank()

  /** Chi trascrive davvero: con «solo il computer di casa» la preferenza non conta piu'. */
  val transcriptionProvider: TranscriptionProviderId
    get() = if (customOnly) TranscriptionProviderId.CUSTOM else preferredProvider
  val languageOrNull: String? get() = language.takeIf { it != "auto" && it.isNotBlank() }
}

class PampaSettingsStore(
  private val store: DataStore<Preferences>,
  private val cipher: SecretCipher,
  /** Un alias suo per il token del cloud: una chiave del Keystore per segreto, come per l'endpoint. */
  private val syncCipher: SecretCipher = KeystoreCipher(alias = SYNC_ALIAS),
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
  suspend fun setCustomOnly(only: Boolean) = edit { it[CustomOnly] = only }
  suspend fun setMirrorEnabled(enabled: Boolean) = edit { it[MirrorEnabled] = enabled }
  suspend fun setAutoTranscribeOnImport(enabled: Boolean) = edit { it[AutoTranscribe] = enabled }
  /**
   * Il computer di casa, scritto da chi lo usa: se cambia qualcosa, la modifica e' nata qui e
   * deve salire all'account ([touchEndpoint]). `touch = false` per chi rimette un valore che non
   * e' una scelta di adesso — il ripristino di un backup, che puo' essere di marzo, non deve
   * vincere sull'indirizzo che l'account ha di oggi.
   */
  suspend fun setEndpoint(url: String, name: String, model: String, touch: Boolean = true) = edit {
    val changed = it.setIfChanged(EndpointUrl, url.trim().trimEnd('/')) or
      it.setIfChanged(EndpointName, name.trim()) or
      it.setIfChanged(EndpointModel, model.trim())
    if (changed && touch) touchEndpoint(it)
  }
  suspend fun setEndpointRemoteUrl(url: String) = edit {
    if (it.setIfChanged(EndpointRemoteUrl, url.trim().trimEnd('/'))) touchEndpoint(it)
  }

  /**
   * Il computer come l'ha scritto l'account. Non sporca: e' arrivato da li', non ha niente da
   * rimandare. Si rifiuta solo se nel frattempo qui e' nata una modifica piu' recente — quella
   * salira' al prossimo giro, e vincera' lei.
   *
   * `token` nullo vuol dire «l'account non ne ha uno» (o un server senza la chiave per
   * custodirlo): si tiene quello che c'e', tranne quando l'account dice che il computer non c'e'
   * piu' — senza indirizzi un token non apre niente.
   *
   * Se la preferenza del servizio non e' mai stata scelta, chi riceve un computer lo usa: e' il
   * caso del primo avvio, in cui l'accesso con Google viene prima della pagina «Chi trascrive».
   *
   * @return se e' stato applicato.
   */
  suspend fun applyRemoteEndpoint(
    url: String,
    remoteUrl: String,
    name: String,
    model: String,
    token: String?,
    updatedAt: Long,
  ): Boolean {
    var applied = false
    edit { prefs ->
      val dirty = prefs[EndpointDirty] ?: false
      if (dirty && (prefs[EndpointUpdatedAt] ?: 0L) > updatedAt) return@edit
      val present = url.isNotBlank() || remoteUrl.isNotBlank()
      prefs[EndpointUrl] = url.trim().trimEnd('/')
      prefs[EndpointRemoteUrl] = remoteUrl.trim().trimEnd('/')
      prefs[EndpointName] = name.trim()
      prefs[EndpointModel] = model.trim()
      when {
        !token.isNullOrBlank() -> prefs[EndpointTokenBlob] = cipher.encrypt(token.trim())
        !present -> prefs.remove(EndpointTokenBlob)
      }
      if (present && prefs[PreferredProvider] == null) prefs[PreferredProvider] = TranscriptionProviderId.CUSTOM.id
      prefs[EndpointUpdatedAt] = updatedAt
      prefs[EndpointDirty] = false
      prefs[EndpointFromAccount] = present
      applied = true
    }
    return applied
  }

  /**
   * L'account ha preso la versione scritta a `expectedUpdatedAt`, ed e' diventata quella di
   * `syncedAt`. Pulita solo se nel frattempo non e' cambiato niente: una modifica fatta mentre
   * la richiesta era in volo resta sporca e sale al prossimo giro.
   */
  suspend fun markEndpointSynced(expectedUpdatedAt: Long, syncedAt: Long) = edit {
    if ((it[EndpointUpdatedAt] ?: 0L) == expectedUpdatedAt) {
      it[EndpointUpdatedAt] = syncedAt
      it[EndpointDirty] = false
    }
  }

  /** Una modifica nata qui: ora (mai prima dell'ultima nota), sporca, e non piu' «dall'account». */
  private fun touchEndpoint(prefs: MutablePreferences) {
    prefs[EndpointUpdatedAt] = maxOf(System.currentTimeMillis(), (prefs[EndpointUpdatedAt] ?: 0L) + 1)
    prefs[EndpointDirty] = true
    prefs[EndpointFromAccount] = false
  }

  private fun MutablePreferences.setIfChanged(key: Preferences.Key<String>, value: String): Boolean {
    if ((this[key] ?: "") == value) return false
    this[key] = value
    return true
  }
  suspend fun setSyncEnabled(enabled: Boolean) = edit { it[SyncEnabled] = enabled }
  /**
   * Cambiare servizio vuol dire ricominciare: un altro server non ha le nostre righe e non sa
   * niente della sequenza a cui eravamo. Si buttano l'id del dispositivo, il token e l'account, e
   * `SyncRepository.ensureIdentity` al primo giro riparte da zero — impronte azzerate e tutto
   * l'archivio nell'outbox. Chi passa dal Worker di prova a quello vero ci arriva con tutto.
   */
  suspend fun setSyncServerUrl(url: String) = edit { prefs ->
    val next = url.trim().trimEnd('/')
    val current = prefs[SyncServerUrl] ?: ""
    if (current.isNotEmpty() && next != current) {
      prefs.remove(SyncDeviceId)
      prefs.remove(SyncTokenBlob)
      prefs.remove(SyncAccount)
    }
    prefs[SyncServerUrl] = next
  }
  suspend fun setSyncDeviceName(name: String) = edit { it[SyncDeviceName] = name.trim() }
  suspend fun setSyncAccount(account: String) = edit { it[SyncAccount] = account.trim() }
  suspend fun setLastSync(at: Long, error: String) = edit { it[LastSyncAt] = at; it[LastSyncError] = error }

  /** L'id di questo dispositivo, creato la prima volta che serve. Vive qui e non nel database: un backup ripristinato altrove non deve portarselo dietro. */
  suspend fun syncDeviceId(): String {
    val existing = store.data.first()[SyncDeviceId]
    if (!existing.isNullOrBlank()) return existing
    val fresh = java.util.UUID.randomUUID().toString()
    edit { it[SyncDeviceId] = fresh }
    return fresh
  }

  suspend fun syncToken(): String? {
    val blob = store.data.first()[SyncTokenBlob] ?: return null
    return runCatching { syncCipher.decrypt(blob) }.getOrNull()
  }

  suspend fun setSyncToken(token: String?) = edit { prefs ->
    val trimmed = token?.trim()
    if (trimmed.isNullOrEmpty()) prefs.remove(SyncTokenBlob) else prefs[SyncTokenBlob] = syncCipher.encrypt(trimmed)
  }

  suspend fun setArchiveEnabled(enabled: Boolean) = edit { it[ArchiveEnabled] = enabled }
  suspend fun setArchiveOnlyUnmetered(only: Boolean) = edit { it[ArchiveOnlyUnmetered] = only }
  suspend fun setLastArchiveAt(at: Long) = edit { it[LastArchiveAt] = at }
  suspend fun setEndpointTimeoutMinutes(minutes: Int) = edit { it[EndpointTimeout] = minutes.coerceIn(5, 720) }
  suspend fun setRefinementEnabled(enabled: Boolean) = edit { it[RefinementEnabled] = enabled }
  suspend fun setRefinementModel(model: String) = edit { it[RefinementModel] = model }
  suspend fun setRefinementPreset(preset: RefinementPreset) = edit { it[RefinementPresetKey] = preset.name }
  suspend fun setRefinementCustomPrompt(prompt: String) = edit { it[RefinementPrompt] = prompt }
  suspend fun setBackupFolderUri(uri: String) = edit { it[BackupFolder] = uri }
  suspend fun setAutoBackup(enabled: Boolean) = edit { it[AutoBackup] = enabled }
  suspend fun setLastBackupAt(atMillis: Long) = edit { it[LastBackupAt] = atMillis }
  suspend fun setLastExportPresetId(id: String) = edit { it[LastExportPreset] = id }
  suspend fun setExportDefaultsJson(json: String) = edit { it[ExportDefaults] = json }
  suspend fun setFakeProviderEnabled(enabled: Boolean) = edit { it[FakeProvider] = enabled }

  /** Il token in chiaro, decifrato al momento: non passa mai da un Flow. */
  suspend fun endpointToken(): String? {
    val blob = store.data.first()[EndpointTokenBlob] ?: return null
    return cipher.decrypt(blob)?.takeIf { it.isNotBlank() }
  }

  suspend fun setEndpointToken(token: String?) = edit { prefs ->
    val trimmed = token?.trim()
    val before = prefs[EndpointTokenBlob]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }.orEmpty()
    if (trimmed.isNullOrEmpty()) {
      prefs.remove(EndpointTokenBlob)
    } else {
      prefs[EndpointTokenBlob] = cipher.encrypt(trimmed)
    }
    if (before != trimmed.orEmpty()) touchEndpoint(prefs)
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
    customOnly = this[CustomOnly] ?: false,
    autoTranscribeOnImport = this[AutoTranscribe] ?: true,
    endpointUrl = this[EndpointUrl] ?: "",
    endpointRemoteUrl = this[EndpointRemoteUrl] ?: "",
    endpointName = this[EndpointName] ?: "",
    endpointModel = this[EndpointModel] ?: "",
    endpointHasToken = this[EndpointTokenBlob] != null,
    endpointTimeoutMinutes = this[EndpointTimeout] ?: 180,
    endpointUpdatedAt = this[EndpointUpdatedAt] ?: 0L,
    endpointDirty = this[EndpointDirty] ?: false,
    endpointFromAccount = this[EndpointFromAccount] ?: false,
    archiveEnabled = this[ArchiveEnabled] ?: false,
    archiveOnlyUnmetered = this[ArchiveOnlyUnmetered] ?: true,
    lastArchiveAt = this[LastArchiveAt] ?: 0L,
    mirrorEnabled = this[MirrorEnabled] ?: false,
    syncEnabled = this[SyncEnabled] ?: false,
    syncServerUrl = this[SyncServerUrl] ?: "",
    syncHasToken = this[SyncTokenBlob] != null,
    syncDeviceId = this[SyncDeviceId] ?: "",
    syncDeviceName = this[SyncDeviceName] ?: "",
    syncAccount = this[SyncAccount] ?: "",
    lastSyncAt = this[LastSyncAt] ?: 0L,
    lastSyncError = this[LastSyncError] ?: "",
    refinementEnabled = this[RefinementEnabled] ?: false,
    refinementModel = this[RefinementModel] ?: "",
    refinementPreset = this[RefinementPresetKey]?.let { runCatching { RefinementPreset.valueOf(it) }.getOrNull() } ?: RefinementPreset.CLEAN,
    refinementCustomPrompt = this[RefinementPrompt] ?: "",
    backupFolderUri = this[BackupFolder] ?: "",
    autoBackup = this[AutoBackup] ?: false,
    lastBackupAt = this[LastBackupAt] ?: 0L,
    lastExportPresetId = this[LastExportPreset] ?: "",
    exportDefaultsJson = this[ExportDefaults] ?: "",
    fakeProviderEnabled = this[FakeProvider] ?: false,
  )

  private companion object {
    const val ENDPOINT_ALIAS = "dev.pampa.pampanotes.endpoint"
    const val SYNC_ALIAS = "dev.pampa.pampanotes.sync"

    val OnboardingDone = booleanPreferencesKey("onboarding_done")
    val Language = stringPreferencesKey("language")
    val Vocabulary = stringPreferencesKey("vocabulary")
    val ChunkMinutes = intPreferencesKey("chunk_minutes")
    val GroqMaxUploadMb = intPreferencesKey("groq_max_upload_mb")
    val PreferredProvider = stringPreferencesKey("preferred_provider")
    val CustomOnly = booleanPreferencesKey("custom_only")
    val MirrorEnabled = booleanPreferencesKey("mirror_enabled")
    val AutoTranscribe = booleanPreferencesKey("auto_transcribe")
    val EndpointUrl = stringPreferencesKey("endpoint_url")
    val EndpointRemoteUrl = stringPreferencesKey("endpoint_remote_url")
    val EndpointName = stringPreferencesKey("endpoint_name")
    val EndpointModel = stringPreferencesKey("endpoint_model")
    val EndpointTokenBlob = stringPreferencesKey("endpoint_token")
    val EndpointTimeout = intPreferencesKey("endpoint_timeout_minutes")
    val EndpointUpdatedAt = longPreferencesKey("endpoint_updated_at")
    val EndpointDirty = booleanPreferencesKey("endpoint_dirty")
    val EndpointFromAccount = booleanPreferencesKey("endpoint_from_account")
    val ArchiveEnabled = booleanPreferencesKey("archive_enabled")
    val ArchiveOnlyUnmetered = booleanPreferencesKey("archive_only_unmetered")
    val LastArchiveAt = longPreferencesKey("last_archive_at")
    val SyncEnabled = booleanPreferencesKey("sync_enabled")
    val SyncServerUrl = stringPreferencesKey("sync_server_url")
    val SyncTokenBlob = stringPreferencesKey("sync_token")
    val SyncDeviceId = stringPreferencesKey("sync_device_id")
    val SyncDeviceName = stringPreferencesKey("sync_device_name")
    val SyncAccount = stringPreferencesKey("sync_account")
    val LastSyncAt = longPreferencesKey("last_sync_at")
    val LastSyncError = stringPreferencesKey("last_sync_error")
    val RefinementEnabled = booleanPreferencesKey("refinement_enabled")
    val RefinementModel = stringPreferencesKey("refinement_model")
    val RefinementPresetKey = stringPreferencesKey("refinement_preset")
    val RefinementPrompt = stringPreferencesKey("refinement_prompt")
    val BackupFolder = stringPreferencesKey("backup_folder")
    val AutoBackup = booleanPreferencesKey("auto_backup")
    val LastBackupAt = longPreferencesKey("last_backup_at")
    val LastExportPreset = stringPreferencesKey("last_export_preset")
    val ExportDefaults = stringPreferencesKey("export_defaults")
    val FakeProvider = booleanPreferencesKey("fake_provider")
  }
}
