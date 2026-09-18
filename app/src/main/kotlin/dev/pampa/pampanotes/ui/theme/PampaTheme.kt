package dev.pampa.pampanotes.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.theme.AccentPoles
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.pampanotes.ui.common.SubjectIdentity
import dev.pampa.pampanotes.ui.common.subjectAccent

/**
 * L'ametista di Pampa Notes.
 *
 * Da questa coppia l'engine deriva l'intera scala di superfici: cambiare qui cambia l'app in modo
 * coerente. I poli servono perche' un viola sta a un passo dall'ancora storica del secondary
 * (l'indaco iOS): senza, l'anello a sette toni collassa in un viola solo. Con l'indaco e il rosa
 * come parenti, le famiglie restano imparentate ma distinguibili.
 */
val PampaNotesBrand: AccentPreset = AccentPreset(
  name = "pampanotes",
  label = "Ametista",
  light = Color(0xFF8B3FD6),
  dark = Color(0xFFC79BFF),
  poles = AccentPoles(
    secondaryLight = Color(0xFF5856D6),
    secondaryDark = Color(0xFF7D7AFF),
    tertiaryLight = Color(0xFFFF2D9B),
    tertiaryDark = Color(0xFFFF6FC0),
    secondaryBlend = 0.45f,
    tertiaryBlend = 0.40f,
  ),
)

/** Gli accenti fra cui si puo' scegliere, oltre a quello del marchio e a quello dello sfondo. */
val pampaAccentPresets: List<AccentPreset> = listOf(
  AccentPreset("ametista", "Ametista", Color(0xFF8B3FD6), Color(0xFFC79BFF)),
  AccentPreset("indaco", "Indaco", Color(0xFF5856D6), Color(0xFF7D7AFF)),
  AccentPreset("petrolio", "Petrolio", Color(0xFF1F6F8B), Color(0xFF6CC5E6)),
  AccentPreset("verde", "Verde", Color(0xFF34C759), Color(0xFF30D158)),
  AccentPreset("ambra", "Ambra", Color(0xFFFF9500), Color(0xFFFF9F0A)),
  AccentPreset("rosa", "Rosa", Color(0xFFFF2D9B), Color(0xFFFF6FC0)),
)

/**
 * Il tema dell'app: il design system dell'engine con l'ametista di Pampa Notes — o con il colore
 * della materia in cui ci si trova.
 *
 * La materia vince **solo** con [AccentMode.BRAND]: chi ha scelto Material You o una tinta dal
 * selettore ha gia' detto di che colore vuole l'app, e sovrascriverlo e' rubargli la scelta.
 */
@Composable
fun PampaTheme(
  settings: EngineSettings,
  subject: SubjectIdentity? = null,
  content: @Composable () -> Unit,
) {
  val target = if (settings.accentMode == AccentMode.BRAND && subject != null) subjectAccent(subject.tone) else PampaNotesBrand
  FluidTheme(
    settings = settings,
    brand = rememberAnimatedAccent(target),
    presets = pampaAccentPresets,
    content = content,
  )
}

/**
 * L'accento che si muove da un colore all'altro invece di scattare.
 *
 * Il passaggio da una materia all'altra sul tablet non ha una transizione di rotta — la barra
 * laterale cambia solo il pannello di fianco — quindi l'accento si anima da solo, e con lui tutta
 * la scala che l'engine ne deriva. I poli invece scattano: influiscono solo su secondary e
 * tertiary, e un'interpolazione fra due sistemi di poli non e' un colore in mezzo, e' rumore.
 */
@Composable
private fun rememberAnimatedAccent(target: AccentPreset): AccentPreset {
  val light by animateColorAsState(target.light, tween(FluidMotion.DurationExpand), label = "pampaAccentLight")
  val dark by animateColorAsState(target.dark, tween(FluidMotion.DurationExpand), label = "pampaAccentDark")
  return target.copy(light = light, dark = dark)
}
