package dev.pampa.pampanotes.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme

/**
 * Il colore di Pampa Notes: un petrolio profondo, il colore dell'inchiostro su carta. Distinto
 * dal blu Fluid, dal viola dello Store, dal magenta di PampAI e dal verde di Glass, e lo stesso
 * dell'icona. Da ritoccare a occhio sul telefono.
 */
val PampaNotesBrand: AccentPreset = AccentPreset(
  name = "pampanotes",
  label = "Pampa Notes",
  light = Color(0xFF1F6F8B),
  dark = Color(0xFF6CC5E6),
)

/** Il tema dell'app: il design system dell'engine con il brand di Pampa Notes. */
@Composable
fun PampaTheme(settings: EngineSettings, content: @Composable () -> Unit) {
  FluidTheme(settings = settings, brand = PampaNotesBrand, content = content)
}
