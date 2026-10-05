package dev.pampa.pampanotes.core.transcription

import java.net.URI

/**
 * Quando ricordare di accendere Tailscale: il computer di casa non risponde ne' sulla rete di casa
 * ne' all'indirizzo di fuori, e su questo dispositivo Tailscale e' spento. E' il caso piu' comune di
 * «In attesa del computer di casa» fuori casa, e l'unico che si risolve con un tocco.
 *
 * Con Tailscale acceso e il computer muto il promemoria non c'e': il computer e' spento, o Tailscale
 * e' spento dall'altra parte, e accendere qualcosa qui non cambierebbe niente.
 *
 * Puro: l'app guarda le reti del telefono ([isTailscaleAddress] sugli indirizzi della VPN) e chiede
 * al computer, poi decide qui.
 */
object TailscaleHint {

  /** Il pacchetto dell'app di Tailscale per Android. */
  const val PACKAGE = "com.tailscale.ipn"

  /**
   * Il computer si raggiunge passando da Tailscale: c'e' un indirizzo di fuori (nell'app e' sempre
   * quello di Tailscale), o quello di casa e' gia' un indirizzo di Tailscale (100.x, `*.ts.net`).
   */
  fun usesTailscale(lanUrl: String, remoteUrl: String): Boolean =
    remoteUrl.isNotBlank() || isTailscaleHost(hostOf(lanUrl))

  /** Ricordarlo adesso? [computerReachable] e' la risposta di `/health`, dopo casa e fuori. */
  fun shouldRemind(lanUrl: String, remoteUrl: String, tailscaleUp: Boolean, computerReachable: Boolean): Boolean =
    usesTailscale(lanUrl, remoteUrl) && !tailscaleUp && !computerReachable

  /**
   * Un indirizzo che Tailscale da' ai suoi dispositivi: IPv4 in 100.64.0.0/10 o IPv6 in
   * fd7a:115c:a1e0::/48. Da solo il 100.64/10 non basta (e' anche quello del NAT degli operatori
   * mobili): l'app lo guarda solo sulle reti VPN.
   */
  fun isTailscaleAddress(address: ByteArray): Boolean = when (address.size) {
    4 -> (address[0].toInt() and 0xFF) == 100 && (address[1].toInt() and 0xC0) == 64
    16 -> TAILSCALE_V6_PREFIX.indices.all { address[it] == TAILSCALE_V6_PREFIX[it] }
    else -> false
  }

  private val TAILSCALE_V6_PREFIX = byteArrayOf(0xFD.toByte(), 0x7A, 0x11, 0x5C, 0xA1.toByte(), 0xE0.toByte())

  private fun isTailscaleHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    if (host.endsWith(".ts.net", ignoreCase = true)) return true
    val octets = host.split('.')
    if (octets.size != 4) return false
    val numbers = octets.map { it.toIntOrNull() ?: return false }
    if (numbers.any { it !in 0..255 }) return false
    return isTailscaleAddress(numbers.map { it.toByte() }.toByteArray())
  }

  private fun hostOf(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    return runCatching { URI(withScheme).host }.getOrNull()
  }
}
