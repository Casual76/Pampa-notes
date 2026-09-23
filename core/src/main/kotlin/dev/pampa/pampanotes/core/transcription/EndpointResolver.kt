package dev.pampa.pampanotes.core.transcription

import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Dove si e' deciso di andare, e per quale strada. */
data class ResolvedEndpoint(val url: String, val viaLan: Boolean)

/**
 * Sceglie fra l'indirizzo di casa e quello di Tailscale, provando prima quello di casa.
 *
 * Il server e' lo stesso computer, ma da casa lo si raggiunge sulla rete locale in un millisecondo
 * e da fuori solo attraverso Tailscale. Tenere un indirizzo solo vorrebbe dire scegliere fra due
 * difetti: con quello di casa, da scuola il tablet non trova niente; con quello di Tailscale, anche
 * a casa ogni lezione passa dalla VPN. Quindi si prova la rete locale con **due secondi** di
 * pazienza — su una LAN o risponde subito o non c'e' — e solo se non risponde si va da fuori.
 *
 * La risposta si tiene per mezzo minuto: la trascrizione fa piu' chiamate di fila (modelli, salute,
 * upload) e pagare due secondi di attesa a ognuna, fuori casa, si sentirebbe. Mezzo minuto e' anche
 * quanto ci si mette a uscire di casa, quindi una cache piu' lunga sbaglierebbe strada.
 *
 * La sonda e' un parametro perche' la decisione — quale, quando riprovare — si prova in JVM senza
 * una rete; quella vera batte `/health`, che il companion espone senza token apposta.
 *
 * Il mezzo minuto si misura con un orologio **monotono**, non con l'ora del telefono: un'ora che
 * torna indietro (la rete che la corregge, un fuso) teneva buona per sempre la strada di prima, e
 * una che salta avanti la buttava a ogni chiamata. Anche l'orologio e' un parametro, per i test.
 */
@Singleton
class EndpointResolver internal constructor(
  private val probe: suspend (baseUrl: String) -> Boolean,
  private val clock: () -> Long,
) {
  @Inject constructor() : this(probe = ::reachable, clock = { System.nanoTime() / 1_000_000 })

  private val lock = Mutex()
  private var cached: Cached? = null

  /**
   * L'indirizzo da usare adesso. Null solo se non ce n'e' nessuno.
   *
   * Senza un indirizzo di casa si va dritti a quello di fuori; senza uno di fuori si torna quello
   * di casa anche se la sonda ha detto di no, perche' un errore di rete chiaro e' meglio di un
   * «server non configurato» che non spiega niente.
   */
  suspend fun resolve(lanUrl: String, remoteUrl: String): ResolvedEndpoint? {
    val lan = lanUrl.trim()
    val remote = remoteUrl.trim()
    if (lan.isEmpty() && remote.isEmpty()) return null
    if (lan.isEmpty()) return ResolvedEndpoint(remote, viaLan = false)
    if (remote.isEmpty()) return ResolvedEndpoint(lan, viaLan = true)

    lock.withLock {
      val now = clock()
      cached?.takeIf { it.lan == lan && it.remote == remote && now - it.at < CACHE_MS }?.let { return it.result }
      val lanOk = probe(lan)
      val result = if (lanOk) ResolvedEndpoint(lan, viaLan = true) else ResolvedEndpoint(remote, viaLan = false)
      cached = Cached(lan, remote, result, now)
      return result
    }
  }

  /** Dimentica l'ultima risposta: dopo un cambio di rete o un «prova la connessione». */
  suspend fun invalidate() = lock.withLock { cached = null }

  private data class Cached(val lan: String, val remote: String, val result: ResolvedEndpoint, val at: Long)

  companion object {
    const val CACHE_MS = 30_000L
    const val PROBE_TIMEOUT_MS = 2_000

    /**
     * `/health` risponde? Due secondi per connettersi e due per la risposta, poi si lascia perdere.
     * Non passa da `TranscriptionHttp`, che aspetta quindici secondi per connettersi: quindici
     * secondi sono giusti per un upload, e sbagliati per una domanda la cui risposta e' quasi
     * sempre «no» quando si e' fuori casa.
     */
    suspend fun reachable(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
      val base = OpenAiCompatProvider.normalize(baseUrl).removeSuffix("/v1")
      runCatching {
        val connection = URL("$base/health").openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = PROBE_TIMEOUT_MS
          connection.readTimeout = PROBE_TIMEOUT_MS
          connection.requestMethod = "GET"
          connection.responseCode in 200..299
        } finally {
          connection.disconnect()
        }
      }.getOrDefault(false)
    }
  }
}
