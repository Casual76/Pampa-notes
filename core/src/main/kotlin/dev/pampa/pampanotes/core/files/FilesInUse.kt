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
  /**
   * Per ogni nome, chi lo tiene e fino a quando. Piu' di un padrone: la sessione aperta e un export
   * della stessa nota tengono lo stesso file, e chi rilascia il suo non deve liberare quello
   * dell'altro.
   */
  private val until = mutableMapOf<String, MutableMap<Any, Long>>()

  /** Tiene [names] fino a [ttlMillis] da adesso, o piu' a lungo se [owner] li teneva gia'. */
  @Synchronized
  fun hold(names: Collection<String>, ttlMillis: Long, now: Long = System.currentTimeMillis(), owner: Any = DEFAULT_OWNER) {
    val expiry = now + ttlMillis
    names.forEach { name ->
      val holders = until.getOrPut(name) { mutableMapOf() }
      holders[owner] = maxOf(holders[owner] ?: 0L, expiry)
    }
  }

  /** Lascia la presa di [owner]: se qualcun altro teneva lo stesso file, resta tenuto. */
  @Synchronized
  fun release(names: Collection<String>, owner: Any = DEFAULT_OWNER) {
    names.forEach { name ->
      val holders = until[name] ?: return@forEach
      holders.remove(owner)
      if (holders.isEmpty()) until.remove(name)
    }
  }

  /** Quelli ancora tenuti adesso, da chiunque. */
  @Synchronized
  fun current(now: Long = System.currentTimeMillis()): Set<String> {
    until.values.forEach { holders -> holders.entries.removeAll { it.value <= now } }
    until.entries.removeAll { it.value.isEmpty() }
    return until.keys.toSet()
  }

  companion object {
    /** Il padrone di chi non ne dice uno: l'export, che tiene e rilascia sempre per conto suo. */
    private val DEFAULT_OWNER = Any()

    fun audio(fileName: String): String = "audio/$fileName"

    fun source(storedFileName: String): String = "sources/$storedFileName"
  }
}
