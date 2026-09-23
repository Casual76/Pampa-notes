package dev.pampa.pampanotes.core.files

import javax.inject.Inject
import javax.inject.Singleton

/**
 * I file che qualcuno sta per leggere, e che «Libera spazio» e «solo sul computer» non devono
 * togliere nel frattempo.
 *
 * Il caso vero e' l'export: il pannello scarica dal computer quello che manca, poi — se qualcosa
 * non e' arrivato — aspetta che si scelga «Esporta senza». In quell'attesa un giro d'archivio in
 * background poteva togliere di nuovo proprio i file appena scaricati, e il pacchetto usciva senza.
 *
 * Ogni presa ha una scadenza: un pannello chiuso senza esportare non rilascia niente, e un file non
 * deve restare intoccabile per sempre per questo. I nomi sono quelli su disco (`audio/<nome>` e
 * `sources/<nome>`), gli stessi che [AppFiles] risolve.
 */
@Singleton
class FilesInUse @Inject constructor() {
  private val until = mutableMapOf<String, Long>()

  /** Tiene [names] fino a [ttlMillis] da adesso, o piu' a lungo se qualcun altro li teneva gia'. */
  @Synchronized
  fun hold(names: Collection<String>, ttlMillis: Long, now: Long = System.currentTimeMillis()) {
    val expiry = now + ttlMillis
    names.forEach { name -> until[name] = maxOf(until[name] ?: 0L, expiry) }
  }

  @Synchronized
  fun release(names: Collection<String>) {
    names.forEach { until.remove(it) }
  }

  /** Quelli ancora tenuti adesso. */
  @Synchronized
  fun current(now: Long = System.currentTimeMillis()): Set<String> {
    until.entries.removeAll { it.value <= now }
    return until.keys.toSet()
  }

  companion object {
    fun audio(fileName: String): String = "audio/$fileName"

    fun source(storedFileName: String): String = "sources/$storedFileName"
  }
}
