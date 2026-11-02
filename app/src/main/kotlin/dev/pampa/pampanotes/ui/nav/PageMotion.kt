package dev.pampa.pampanotes.ui.nav

import androidx.navigation.NavBackStackEntry
import dev.antigravity.fluidengine.ui.page.FluidPeerMove

/**
 * Cio' che resta del movimento di rotta dell'app dopo `FluidNavHost`: l'ordine delle schede e il
 * posto della pillola. Il resto — crescita dal tocco, scorrimento, il back che segue il dito — e'
 * dell'host delle pagine (vedi `ADOZIONE.md`, «Migrare alla 3.0»).
 */

/**
 * In che verso viaggia un cambio di scheda, letto dall'ordine in cui le schede sono dichiarate.
 *
 * Dedotto invece che registrato apposta: un verso salvato su una voce dovrebbe essere giusto sia
 * per l'andata sia per il ritorno che la disfa, e non c'e' un momento in cui si possano scrivere
 * entrambi. L'ordine delle schede lo sa gia', e lo sa in tutte e due le direzioni. Zero quando la
 * coppia non e' fatta di due schede: allora valgono le regole delle pagine.
 */
fun peerDirection(fromRoute: String?, toRoute: String?): Int {
  val fromIndex = Routes.topLevel.indexOf(fromRoute)
  val toIndex = Routes.topLevel.indexOf(toRoute)
  if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return 0
  return if (toIndex > fromIndex) 1 else -1
}

/** Le schede sono pari: l'host le scambia con il passo laterale, senza pagina e senza gesto. */
val PampaPeerMove = FluidPeerMove<NavBackStackEntry> { from, to ->
  peerDirection(from.destination.route, to.destination.route)
}

/**
 * Quanto la pillola delle schede e' uscita dallo schermo: 0 e' al suo posto, 1 e' fuori.
 *
 * La pillola sta fuori dall'host delle pagine ed e' una sola, quindi non viaggia con nessuna pagina:
 * esce insieme a quella che si apre sopra (una nota, le impostazioni) e rientra insieme a quella che
 * si chiude, **col dito**. Per questo segue l'avanzamento dell'host e non un interruttore: un back a
 * scorrimento a meta' strada lascia la pillola a meta' strada, e un back annullato la riporta
 * dov'era.
 *
 * A riposo ([moving] falso) vale solo [restHidden], cioe' se la pagina davanti non ha la pillola.
 * In movimento si va da come stava la pagina che lascia il davanti ([fromHidden]) a come sta quella
 * che lo prende ([toHidden]), di quanto l'host dice che il movimento e' fatto ([progress], 0..1).
 * Fra due schede non cambia niente: tutte e due hanno la pillola.
 */
internal fun tabBarHidden(
  moving: Boolean,
  fromHidden: Boolean,
  toHidden: Boolean,
  progress: Float,
  restHidden: Boolean,
): Float {
  if (!moving) return if (restHidden) 1f else 0f
  val from = if (fromHidden) 1f else 0f
  val to = if (toHidden) 1f else 0f
  return from + (to - from) * progress.coerceIn(0f, 1f)
}

/** Una voce dello stack che non e' una scheda: la pillola non c'e'. */
internal fun Any?.hidesTabBar(): Boolean {
  val route = (this as? NavBackStackEntry)?.destination?.route ?: return false
  return route !in Routes.topLevelSet
}
