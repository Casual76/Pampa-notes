package dev.pampa.pampanotes.core.importing

import javax.inject.Inject
import javax.inject.Singleton

/**
 * I giri che sistemano quello che l'app di prima ha importato male, nell'ordine giusto: prima le
 * registrazioni dei `.sdocx` al loro posto ([SdocxRepairer]), poi le date vere ([RealDatesBackfill]),
 * che leggono i giorni delle sessioni appena rimesse a posto.
 *
 * Col sync acceso girano dopo il pull ([dev.pampa.pampanotes.work.SyncWorker]): un dispositivo che
 * ha gia' ricevuto la riparazione fatta da un altro trova tutto a posto e non riscrive niente.
 */
@Singleton
class StartupRepairs @Inject constructor(
  private val repairer: SdocxRepairer,
  private val realDates: RealDatesBackfill,
) {
  /** Vero se qualcosa e' cambiato: allora serve un giro di sync che lo porti agli altri. */
  suspend fun run(): Boolean {
    val repaired = runCatching { repairer.run().anyChange }
      .onFailure { android.util.Log.w("PampaNotes", "riparazione dei .sdocx: giro fallito", it) }
      .getOrDefault(false)
    val redated = runCatching { realDates.run().changed }
      .onFailure { android.util.Log.w("PampaNotes", "date vere: giro fallito", it) }
      .getOrDefault(false)
    return repaired || redated
  }
}
