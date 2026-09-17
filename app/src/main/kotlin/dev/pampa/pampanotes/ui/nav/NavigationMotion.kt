package dev.pampa.pampanotes.ui.nav

import androidx.lifecycle.SavedStateHandle
import dev.antigravity.fluidengine.ui.theme.MotionOrigin

/**
 * Come una destinazione lascia il posto alla successiva.
 *
 * Due forme di movimento, e sono tutto il vocabolario. Una navigazione che offre un'animazione
 * diversa per ogni coppia di schermate e' una navigazione che nessuno riesce a leggere.
 */
enum class RouteMotionKind {
  /**
   * Fra le schede della barra. Sono pari — nessuna sta "dentro" l'altra — quindi niente si espande
   * e niente arretra: le due pagine scorrono di lato insieme, nel verso che la barra implica.
   */
  TopLevelSwitch,

  /**
   * Qualsiasi cosa si apra *da* qualcos'altro: una riga, una card, un tasto della barra.
   *
   * La destinazione cresce da dove il dito e' sceso e la pagina coperta arretra appena sotto di lei.
   * Rovesciato identico al ritorno: e' quello che permette al back predittivo di scorrerlo a mano.
   */
  Expand,
}

/**
 * @param direction dove sta la scheda che arriva rispetto a quella che lascia: `+1` dopo, `-1`
 *   prima, `0` quando la coppia non ha un ordine. Serve solo a [RouteMotionKind.TopLevelSwitch].
 */
data class RouteMotionDecision(
  val kind: RouteMotionKind,
  val direction: Int = 0,
)

private const val MotionOriginXKey = "route-motion:origin-x"
private const val MotionOriginYKey = "route-motion:origin-y"
private const val MotionKindKey = "route-motion:kind"

/**
 * Registra che questa voce e' stata aperta toccando qualcosa, e da dove.
 *
 * Scrive solo primitivi da Bundle, cosi' uno stack ripristinato non puo' tenersi un oggetto di UI
 * ormai morto.
 */
fun SavedStateHandle.writeExpandMotion(origin: MotionOrigin) {
  this[MotionOriginXKey] = origin.fractionX
  this[MotionOriginYKey] = origin.fractionY
  this[MotionKindKey] = RouteMotionKind.Expand.name
}

/** Registra che questa voce e' stata raggiunta scorrendo di lato lungo la barra. */
fun SavedStateHandle.writePeerMotion() {
  remove<Float>(MotionOriginXKey)
  remove<Float>(MotionOriginYKey)
  this[MotionKindKey] = RouteMotionKind.TopLevelSwitch.name
}

/**
 * L'origine da cui una voce e' stata aperta, o il centro quando dietro non c'era un gesto: un deep
 * link, una notifica, o uno stack ripristinato dopo la morte del processo.
 */
fun SavedStateHandle.readMotionOrigin(): MotionOrigin {
  val x = get<Float>(MotionOriginXKey) ?: return MotionOrigin.Center
  val y = get<Float>(MotionOriginYKey) ?: return MotionOrigin.Center
  if (!x.isFinite() || !y.isFinite()) return MotionOrigin.Center
  return MotionOrigin(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** Come questa voce e' stata raggiunta, o null quando nessuno l'ha registrato. */
fun SavedStateHandle.readMotionKind(): RouteMotionKind? {
  val name = get<String>(MotionKindKey) ?: return null
  return RouteMotionKind.entries.firstOrNull { it.name == name }
}

fun normalizeRoute(route: String?): String? = route
  ?.substringBefore("?")
  ?.substringBefore("/")
  ?.takeIf { it.isNotBlank() }

/**
 * @param requestedKind come la voce che entra o esce dallo stack e' stata raggiunta. Due schede
 *   sono pari, ma *come* ci sei arrivato e' la cosa che deve muoversi: toccare la barra e' un passo
 *   di lato, toccare una card in Home e' quella card che si apre nella pagina dietro di lei.
 */
fun decideRouteMotion(
  fromRoute: String?,
  toRoute: String?,
  requestedKind: RouteMotionKind?,
): RouteMotionDecision {
  val from = normalizeRoute(fromRoute)
  val to = normalizeRoute(toRoute)
  if (from !in Routes.topLevelSet || to !in Routes.topLevelSet) {
    return RouteMotionDecision(RouteMotionKind.Expand)
  }
  if (requestedKind == RouteMotionKind.Expand) {
    return RouteMotionDecision(RouteMotionKind.Expand)
  }
  return RouteMotionDecision(
    kind = RouteMotionKind.TopLevelSwitch,
    direction = peerDirection(from, to),
  )
}

/**
 * In che verso viaggia un cambio di scheda, letto dall'ordine in cui le schede sono dichiarate.
 *
 * Dedotto invece che registrato apposta: un verso salvato su una voce dovrebbe essere giusto sia
 * per l'andata sia per il ritorno che la disfa, e non c'e' un momento in cui si possano scrivere
 * entrambi. L'ordine delle schede lo sa gia', e lo sa in tutte e due le direzioni.
 */
fun peerDirection(fromRoute: String?, toRoute: String?): Int {
  val fromIndex = Routes.topLevel.indexOf(fromRoute)
  val toIndex = Routes.topLevel.indexOf(toRoute)
  if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return 0
  return if (toIndex > fromIndex) 1 else -1
}
