package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.ui.fluid.FluidPaneRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidPaneRole
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.antigravity.fluidengine.ui.theme.LocalFluidRouteFront
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.ui.theme.PampaNotesBrand

/**
 * La materia in cui ci si trova: e' quello che colora l'app.
 *
 * Nessun'altra app della famiglia ha un concetto forte come «la materia» da cui prendere il
 * colore, ed e' questo che fa riconoscere Pampa Notes a colpo d'occhio: dentro Storia l'accento e'
 * quello di Storia — tasti, pillola, selezioni, tinte del vetro — e uscendo si torna all'ametista.
 */
@Immutable
data class SubjectIdentity(
  val folderId: String,
  val name: String,
  val tone: FluidTone,
  val icon: FolderIcon,
)

fun FolderEntity.asSubject(): SubjectIdentity =
  SubjectIdentity(folderId = id, name = name, tone = toneFromName(tone), icon = FolderIcon.fromKey(icon))

/**
 * Chi sta dichiarando una materia, e quale vince.
 *
 * Con due pannelli sulla stessa materia vince il ruolo [FluidPaneRole.Detail]: e' quello che si
 * sta leggendo. Con nessun dettaglio, l'ultima iscritta. Le schermate senza materia — Home, Cerca,
 * Lavori, Altro, Impostazioni, Import — non si iscrivono, e si *sente* di essere usciti da Storia.
 */
@Stable
class SubjectRegistry {
  private class Entry(val key: Any, val role: FluidPaneRole?, val subject: SubjectIdentity)

  private val entries = mutableStateListOf<Entry>()

  val current: SubjectIdentity? by derivedStateOf {
    (entries.lastOrNull { it.role == FluidPaneRole.Detail } ?: entries.lastOrNull())?.subject
  }

  fun register(key: Any, role: FluidPaneRole?, subject: SubjectIdentity) {
    entries.removeAll { it.key === key }
    entries.add(Entry(key, role, subject))
  }

  fun unregister(key: Any) {
    entries.removeAll { it.key === key }
  }
}

val LocalSubjectRegistry: ProvidableCompositionLocal<SubjectRegistry?> = staticCompositionLocalOf { null }

/**
 * Dichiara la materia di questa schermata finche' la schermata e' davanti.
 *
 * «Davanti» e non «composta»: durante un back predittivo la pagina che se ne va e' ancora in
 * composizione, e se restasse iscritta l'accento cambierebbe a gesto gia' finito. Si iscrive e si
 * cancella con un `DisposableEffect`, cosi' una schermata che sparisce non lascia una materia
 * fantasma a colorare quella dopo.
 */
@Composable
fun ReportSubject(subject: SubjectIdentity?) {
  val registry = LocalSubjectRegistry.current ?: return
  val role = LocalFluidPaneRole.current
  val front by LocalFluidRouteFront.current
  val key = remember { Any() }
  DisposableEffect(registry, subject, role, front) {
    if (subject != null && front) registry.register(key, role, subject) else registry.unregister(key)
    onDispose { registry.unregister(key) }
  }
}

/**
 * L'accento di una materia: le stesse sei tinte di [folderVividColors], come coppia chiaro/scuro.
 *
 * L'ametista resta il marchio, con i suoi poli; le altre cinque sono tinte piene da cui l'engine
 * deriva la scala intera. Tenute uguali alle tessere apposta: la tessera di Storia e l'app dentro
 * Storia devono essere dello stesso colore, o la materia non si riconosce.
 */
fun subjectAccent(tone: FluidTone): AccentPreset = when (tone) {
  FluidTone.Primary -> PampaNotesBrand
  FluidTone.Info -> AccentPreset("materia-blu", "Blu", Color(0xFF2F6FE4), Color(0xFF4F8DF5))
  FluidTone.Success -> AccentPreset("materia-verde", "Verde", Color(0xFF0E8050), Color(0xFF34C77B))
  FluidTone.Warning -> AccentPreset("materia-ambra", "Ambra", Color(0xFFB05E0C), Color(0xFFF2A73B))
  FluidTone.Danger -> AccentPreset("materia-rosa", "Rosa", Color(0xFFDC3A5E), Color(0xFFF4607E))
  FluidTone.Neutral -> AccentPreset("materia-ardesia", "Ardesia", Color(0xFF5E6B78), Color(0xFF7C8A99))
}
