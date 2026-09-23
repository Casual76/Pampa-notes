package dev.pampa.pampanotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidNotification
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.transcription.CompanionBindResult

/**
 * La scheda che segue a «Collega» sul QR del computer, detta com'e' andata.
 *
 * Il collegamento dell'indirizzo va sempre; quello dell'account (`CompanionBinder`) solo se il QR
 * portava il codice, cioe' se il computer non era ancora di nessuno. Quando non va, la frase dice
 * cosa fare: un computer che non riconosce l'account risponde 401 a ogni lezione, e scoprirlo li'
 * e' peggio che scoprirlo adesso.
 */
@Composable
internal fun rememberEndpointLinkedNotice(): (IntentOutcome.EndpointLinked) -> FluidNotification {
  val title = stringResource(R.string.settings_endpoint_linked_title)
  val linked = stringResource(R.string.settings_endpoint_linked)
  val bound = stringResource(R.string.install_bind_bound)
  val notSignedIn = stringResource(R.string.install_bind_not_signed_in)
  val taken = stringResource(R.string.install_bind_taken)
  val failed = stringResource(R.string.install_bind_failed)
  return { outcome ->
    val (message, tone) = when (val result = outcome.bound) {
      null -> linked.format(outcome.url) to FluidNotificationTone.Success
      is CompanionBindResult.Bound -> bound.format(outcome.url, result.owner) to FluidNotificationTone.Success
      CompanionBindResult.NotSignedIn -> notSignedIn.format(outcome.url) to FluidNotificationTone.Info
      CompanionBindResult.Taken -> taken.format(outcome.url) to FluidNotificationTone.Warning
      is CompanionBindResult.Rejected, is CompanionBindResult.Unreachable -> failed.format(outcome.url) to FluidNotificationTone.Warning
    }
    FluidNotification(id = "endpoint-linked", title = title, message = message, tone = tone)
  }
}
