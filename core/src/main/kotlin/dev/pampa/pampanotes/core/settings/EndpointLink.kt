package dev.pampa.pampanotes.core.settings

import java.net.URI
import java.net.URLDecoder

/**
 * Il link che il server companion mostra come QR: `pampanotes://endpoint?url=...&token=...`.
 *
 * Esiste perche' un indirizzo IP e un token scritti a mano su un telefono sono due occasioni di
 * sbagliare un carattere e poi andare a cercare il guasto dalla parte del firewall. Il QR li porta
 * dentro l'app senza passare da una tastiera.
 *
 * Il parser e' puro (`java.net.URI`, non `android.net.Uri`) e si prova in JVM. E' severo su una
 * cosa sola: l'indirizzo deve essere HTTP o HTTPS, perche' e' l'unica cosa che l'app sa chiamare, e
 * un link che portasse altro non sarebbe un errore dell'utente ma un QR di qualcun altro.
 */
data class EndpointLink(
  val url: String,
  val token: String?,
  /** L'indirizzo che vale fuori casa (Tailscale), quando il server ce l'ha. */
  val remoteUrl: String? = null,
) {

  companion object {
    const val SCHEME = "pampanotes"
    const val HOST = "endpoint"

    fun parse(raw: String?): EndpointLink? {
      val uri = runCatching { URI(raw?.trim().orEmpty()) }.getOrNull() ?: return null
      if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return null
      // `rawQuery` e non `query`: `query` decodifica gia', e un token con un `%` dentro verrebbe
      // decodificato due volte. Si divide sul grezzo e si decodifica ogni pezzo una volta sola.
      val params = uri.rawQuery.orEmpty()
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { pair ->
          val key = pair.substringBefore('=')
          val value = pair.substringAfter('=', "")
          decode(key) to decode(value)
        }
      val url = httpUrl(params["url"]) ?: return null
      val token = params["token"]?.trim()?.takeIf { it.isNotEmpty() }
      // Un indirizzo di fuori che non e' http si ignora e basta: quello di casa c'e', il link vale.
      return EndpointLink(url = url, token = token, remoteUrl = httpUrl(params["remote"]))
    }

    private fun httpUrl(raw: String?): String? {
      val url = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
      return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun decode(value: String): String =
      runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
  }
}
