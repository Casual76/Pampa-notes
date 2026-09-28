package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.repo.HolesOffer
import dev.pampa.pampanotes.core.repo.RetranscribeOffers
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * L'offerta di ritrascrivere le lezioni fatte prima della correzione dei buchi (vedi
 * `RetranscribeOffer`), per Lavori e per la home: la stessa scheda nei due posti.
 */
@HiltViewModel
class HolesOfferViewModel @Inject constructor(
  private val offers: RetranscribeOffers,
  private val scheduler: WorkScheduler,
) : ViewModel() {

  val offer: StateFlow<HolesOffer?> = offers.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  init {
    // Chi apre la pagina prima di qualunque trascrizione deve vederla lo stesso: si chiede al
    // computer se e' gia' quello nuovo, una volta per processo.
    viewModelScope.launch { offers.probe() }
  }

  /** Tutte in fila sul computer di casa, e la sua coda sveglia. */
  fun retranscribe(offer: HolesOffer) {
    viewModelScope.launch {
      if (offers.retranscribe(offer.sessionIds) > 0) scheduler.kick(OpenAiCompatProvider.ID)
    }
  }

  fun dismiss() {
    viewModelScope.launch { offers.dismiss() }
  }
}

/**
 * «N lezioni trascritte prima della correzione dei buchi · X h», con «Ritrascrivi» e «Non ora»: la
 * forma della scheda di un aggiornamento, perche' e' la stessa domanda — c'e' qualcosa di nuovo, lo
 * vuoi adesso? Prima di mettere in fila chiede, con le ore e il computer: sono lezioni intere.
 */
@Composable
fun HolesOfferCard(offer: HolesOffer, onRetranscribe: () -> Unit, onDismiss: () -> Unit) {
  var confirming by remember { mutableStateOf(false) }
  val count = offer.sessionIds.size
  val duration = Formats.durationShort(offer.durationMs)

  FluidCard {
    FluidListRow(
      title = pluralStringResource(R.plurals.holes_offer_title, count, count),
      subtitle = stringResource(R.string.holes_offer_detail, duration),
      eyebrow = stringResource(R.string.holes_offer_eyebrow),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      FluidButton(
        text = stringResource(R.string.holes_offer_later),
        onClick = onDismiss,
        style = FluidButtonStyle.Plain,
        modifier = Modifier.weight(1f),
      )
      FluidButton(
        text = stringResource(R.string.session_retranscribe),
        onClick = { confirming = true },
        style = FluidButtonStyle.Tinted,
        modifier = Modifier.weight(1f),
      )
    }
  }

  if (confirming) {
    FluidAlert(
      onDismissRequest = { confirming = false },
      title = pluralStringResource(R.plurals.holes_confirm_title, count, count),
      message = if (offer.computerName.isNotBlank()) {
        stringResource(R.string.holes_confirm_message_named, duration, offer.computerName)
      } else {
        stringResource(R.string.holes_confirm_message, duration)
      },
      actions = listOf(
        FluidAlertAction(
          label = stringResource(R.string.session_retranscribe),
          emphasis = FluidAlertAction.Emphasis.Preferred,
          onClick = {
            confirming = false
            onRetranscribe()
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirming = false }),
      ),
    )
  }
}
