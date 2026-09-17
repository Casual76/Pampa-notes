package dev.pampa.pampanotes.core.repo

import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.provider.ChatProvider
import dev.antigravity.fluidengine.ai.provider.GroqProvider
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.refinement.RefinementService
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.RefinementPreset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Cosa chiedere, salvato dentro il lavoro: cosi' un raffinamento ripreso rifa' la stessa cosa. */
@Serializable
data class RefinementOptions(
  val preset: String = RefinementPreset.CLEAN.name,
  val customPrompt: String = "",
) {
  val presetOrDefault: RefinementPreset
    get() = runCatching { RefinementPreset.valueOf(preset) }.getOrDefault(RefinementPreset.CLEAN)
}

/**
 * Quello che serve per ripulire una trascrizione, messo insieme.
 *
 * Il servizio che parla col modello ([RefinementService]) non sa niente di chiavi, impostazioni e
 * database: prende un testo e torna un testo. Qui c'e' il resto.
 */
@Singleton
class RefinementRepository @Inject constructor(
  private val keys: AiKeyStore,
  private val http: AiHttp,
  private val settingsStore: PampaSettingsStore,
) {

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  val service = RefinementService()

  /**
   * Il servizio di chat da usare, o null se manca la chiave.
   *
   * Solo Groq: e' l'unico provider con cui questa app parla per il testo, ed e' quello su cui
   * gpt-oss-120b costa poco e risponde in fretta. Un endpoint personale qui non c'entra — WhisperX
   * trascrive, non riscrive.
   */
  suspend fun provider(): ChatProvider? {
    val key = keys.key(ProviderId.GROQ)?.takeIf { it.isNotBlank() } ?: return null
    return GroqProvider(http, key)
  }

  /** Il modello salvato, altrimenti il migliore fra quelli che il servizio dichiara. */
  suspend fun resolveModel(provider: ChatProvider, settings: PampaSettings): String? {
    settings.refinementModel.takeIf { it.isNotBlank() }?.let { return it }
    val available = runCatching { provider.listModels().chat.map { it.id } }.getOrDefault(emptyList())
    return RefinementService.pickModel(available)
  }

  /** Le opzioni di default, quelle delle impostazioni. */
  suspend fun defaultOptions(): RefinementOptions {
    val settings = settingsStore.current()
    return RefinementOptions(
      preset = settings.refinementPreset.name,
      customPrompt = settings.refinementCustomPrompt,
    )
  }

  fun encode(options: RefinementOptions): String = json.encodeToString(options)

  fun decode(optionsJson: String?): RefinementOptions =
    optionsJson?.let { runCatching { json.decodeFromString<RefinementOptions>(it) }.getOrNull() } ?: RefinementOptions()
}
