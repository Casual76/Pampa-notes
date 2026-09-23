package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Il computer di casa segue l'account: indirizzi, nome, modello e token del companion salgono
 * al Worker (`/v1/account/computer`) e scendono sugli altri dispositivi. Chi entra con Google su
 * un telefono nuovo trova il PC gia' collegato, e chi lo ricollega col QR su un dispositivo lo
 * ritrova ricollegato sugli altri.
 *
 * Gira dentro ogni giro di sincronizzazione, dopo che il token si e' dimostrato buono. Un giro
 * qui costa una GET, e una PUT solo quando qualcosa e' cambiato. La chiave di Groq non passa di
 * qui: e' dell'utente e del suo account Groq, non del computer di casa.
 */
@Singleton
class ComputerSync @Inject constructor(
  private val api: SyncApi,
  private val settingsStore: PampaSettingsStore,
) {

  enum class Outcome { UNCHANGED, PUSHED, APPLIED }

  suspend fun sync(baseUrl: String, token: String, deviceId: String): Outcome {
    val settings = settingsStore.current()
    val local = ComputerMerge.Local(
      hasEndpoint = settings.hasEndpoint,
      updatedAt = settings.endpointUpdatedAt,
      dirty = settings.endpointDirty,
    )
    val remote = api.getComputer(baseUrl, token)
    return when (ComputerMerge.decide(local, remote)) {
      ComputerMerge.Decision.NOTHING -> Outcome.UNCHANGED
      ComputerMerge.Decision.APPLY -> if (apply(requireNotNull(remote))) Outcome.APPLIED else Outcome.UNCHANGED
      ComputerMerge.Decision.PUSH -> {
        // Un computer configurato prima che seguisse l'account non ha una data: prende quella di adesso.
        val at = local.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val secret = runCatching { settingsStore.endpointToken() }.getOrNull()
        val response = api.putComputer(
          baseUrl,
          token,
          PutComputerRequest(
            url = settings.endpointUrl,
            remoteUrl = settings.endpointRemoteUrl,
            name = settings.endpointName,
            model = settings.endpointModel,
            // Senza token qui: se il computer non c'e' piu' lo si toglie anche la', altrimenti resta
            // quello che l'account ha. Un token illeggibile (Keystore di un altro telefono) non deve
            // cancellare quello buono.
            token = secret ?: if (settings.hasEndpoint) null else "",
            updatedAt = at,
            deviceId = deviceId,
          ),
        )
        if (response.accepted) {
          settingsStore.markEndpointSynced(expectedUpdatedAt = local.updatedAt, syncedAt = at)
          Outcome.PUSHED
        } else {
          // Qualcuno ha scritto dopo di noi: la sua versione e' nella risposta.
          if (apply(response.computer)) Outcome.APPLIED else Outcome.UNCHANGED
        }
      }
    }
  }

  private suspend fun apply(remote: AccountComputer): Boolean = settingsStore.applyRemoteEndpoint(
    url = remote.url,
    remoteUrl = remote.remoteUrl,
    name = remote.name,
    model = remote.model,
    token = remote.token,
    updatedAt = remote.updatedAt,
  )
}
