package dev.pampa.pampanotes.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.AccentPoles
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme

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

/** Il tema dell'app: il design system dell'engine con l'ametista di Pampa Notes. */
@Composable
fun PampaTheme(settings: EngineSettings, content: @Composable () -> Unit) {
  FluidTheme(
    settings = settings,
    brand = PampaNotesBrand,
    presets = pampaAccentPresets,
    content = content,
  )
}
