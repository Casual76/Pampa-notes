package dev.pampa.pampanotes.core.settings

import java.net.URI
import java.net.URLDecoder

/**
 * Il link che configura l'indice in cloud senza scrivere niente:
 * `pampanotes://sync?url=...&token=...&name=...`.
 *
 * Stessa forma di [EndpointLink], stesso motivo: un token lungo scritto a mano su un telefono e'
 * un errore che aspetta di succedere, e un codice QR o un link inviato a se stessi no. `name` e'
 * facoltativo: come questo dispositivo si presentera' agli altri.
 */
data class SyncLink(val url: String, val token: String?, val name: String?) {

  companion object {
    const val SCHEME = "pampanotes"
    const val HOST = "sync"

    fun parse(raw: String?): SyncLink? {
      val uri = runCatching { URI(raw?.trim().orEmpty()) }.getOrNull() ?: return null
      if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return null
      val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
        decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
      }
      val url = params["url"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
      if (!url.startsWith("http://") && !url.startsWith("https://")) return null
      return SyncLink(
        url = url,
        token = params["token"]?.trim()?.takeIf { it.isNotEmpty() },
        name = params["name"]?.trim()?.takeIf { it.isNotEmpty() },
      )
    }

    private fun decode(value: String): String =
      runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
  }
}
