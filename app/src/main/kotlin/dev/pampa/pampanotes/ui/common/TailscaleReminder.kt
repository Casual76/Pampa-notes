package dev.pampa.pampanotes.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.transcription.JobPhase
import dev.pampa.pampanotes.work.Tailscale
import dev.pampa.pampanotes.work.TailscaleReminder
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** La scheda: quante trascrizioni aspettano, e se Tailscale c'e' da accendere o da installare. */
data class TailscaleCardState(val waiting: Int, val installed: Boolean, val turningOn: Boolean = false)

/**
 * Il promemoria «accendi Tailscale» nelle schermate: c'e' solo mentre delle trascrizioni aspettano il
 * computer di casa («In attesa del computer di casa»), e solo se nessuna delle due strade risponde
 * con Tailscale spento ([TailscaleReminder.needed]). Si riguarda a ogni cambio di rete, cosi' accendere
 * Tailscale (da qui o dalla sua app) la toglie da solo.
 */
@HiltViewModel
class TailscaleReminderViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val reminder: TailscaleReminder,
  repository: TranscriptionRepository,
) : ViewModel() {

  private val turningOn = MutableStateFlow(false)
  private val refresh = MutableStateFlow(0)

  private val waiting = repository.observeActive().map { jobs ->
    jobs.count { it.state == JobState.QUEUED && JobPhase.parse(it.phase) == JobPhase.Endpoint }
  }.distinctUntilChanged()

  @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
  private val needed = combine(
    waiting.map { it > 0 }.distinctUntilChanged(),
    // Le reti cambiano a raffica (il Wi-Fi che si aggancia, Tailscale che sale): si guarda quando si fermano.
    Tailscale.changes(context).onStart { emit(Unit) }.debounce(800),
    refresh,
  ) { any, _, _ -> any }.mapLatest { any -> any && reminder.needed() }

  val card: StateFlow<TailscaleCardState?> = combine(waiting, needed, turningOn) { count, show, busy ->
    if (show && count > 0) TailscaleCardState(count, Tailscale.isInstalled(context), busy) else null
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  /** «Accendi»: lo si chiede a Tailscale; se non si accende si apre la sua app, che sa chiedere il permesso. */
  fun turnOn() {
    if (turningOn.value) return
    viewModelScope.launch {
      turningOn.value = true
      try {
        if (!reminder.turnOn()) Tailscale.open(context)
      } finally {
        turningOn.value = false
        refresh.value++
      }
    }
  }

  fun open() {
    Tailscale.open(context)
  }
}

/** «Il computer di casa non si vede · Tailscale è spento», con «Apri Tailscale» e «Accendi». */
@Composable
fun TailscaleReminderCard(state: TailscaleCardState, onTurnOn: () -> Unit, onOpen: () -> Unit) {
  FluidCard {
    FluidListRow(
      title = stringResource(R.string.tailscale_card_title),
      subtitle = pluralStringResource(R.plurals.tailscale_card_detail, state.waiting, state.waiting),
      eyebrow = stringResource(R.string.tailscale_card_eyebrow),
      tone = FluidTone.Warning,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      if (state.installed) {
        FluidButton(
          text = stringResource(R.string.tailscale_open),
          onClick = onOpen,
          style = FluidButtonStyle.Plain,
          modifier = Modifier.weight(1f),
        )
        FluidButton(
          text = stringResource(if (state.turningOn) R.string.tailscale_turning_on else R.string.tailscale_turn_on),
          onClick = onTurnOn,
          style = FluidButtonStyle.Tinted,
          enabled = !state.turningOn,
          modifier = Modifier.weight(1f),
        )
      } else {
        FluidButton(
          text = stringResource(R.string.tailscale_install),
          onClick = onOpen,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}
