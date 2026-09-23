package dev.pampa.pampanotes.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.sync.GuestInfo
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.nav.SettingsSection

/**
 * Ospiti del computer: chi puo' trascrivere col tuo PC oltre a te.
 *
 * Si crea un ospite con un nome, si manda l'invito (che porta il suo token, una volta sola), e
 * qui si vede quanto ha trascritto e quando. Tenere premuto revoca. La riga in fondo dice cosa
 * deve esserci nel `config.json` del companion perche' gli ospiti esistano.
 */
@Composable
fun GuestsSectionRoute(
  onBack: () -> Unit,
  viewModel: GuestsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // Le stringhe dalle risorse osservabili, non dal contesto: cambiano con la lingua e il tema.
  val resources = LocalResources.current
  var name by remember { mutableStateOf("") }
  val copied = stringResource(R.string.share_copied)
  val revokeLabel = stringResource(R.string.guests_revoke)

  fun inviteText(invite: GuestInvite): String = resources.getString(
    R.string.guests_invite_text,
    state.owner.ifBlank { resources.getString(R.string.guests_invite_owner_fallback) },
    invite.computerUrl,
    invite.token,
    "pampanotes://endpoint?url=${invite.computerUrl}&token=${invite.token}",
  )

  fun copy(text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Pampa Notes", text))
    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
  }

  fun send(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
  }

  FluidScreen(
    title = SettingsSection.GUESTS.label(),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    item { FluidSectionFootnote(text = stringResource(R.string.guests_intro)) }

    when {
      !state.configured -> item {
        FluidInlineMessage(
          title = stringResource(R.string.share_not_configured_title),
          message = stringResource(R.string.guests_needs_sync),
          tone = FluidTone.Warning,
        )
      }

      !state.hasComputer -> item {
        FluidInlineMessage(
          title = stringResource(R.string.guests_no_computer_title),
          message = stringResource(R.string.guests_no_computer_detail),
          tone = FluidTone.Warning,
        )
      }

      else -> {
        state.invite?.let { invite ->
          item {
            FluidCard {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.guests_invite_title, invite.name), style = MaterialTheme.typography.titleMedium)
                Text(text = inviteText(invite), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FluidButton(text = stringResource(R.string.guests_invite_send), onClick = { send(inviteText(invite)) }, fillWidth = true, modifier = Modifier.fillMaxWidth())
                FluidButton(text = stringResource(R.string.share_copy), onClick = { copy(inviteText(invite)) }, style = FluidButtonStyle.Tinted, fillWidth = true, modifier = Modifier.fillMaxWidth())
                FluidButton(text = stringResource(R.string.action_done), onClick = viewModel::dismissInvite, style = FluidButtonStyle.Plain, fillWidth = true, modifier = Modifier.fillMaxWidth())
              }
            }
          }
          item { FluidSectionFootnote(text = stringResource(R.string.guests_invite_once)) }
        }

        item { FluidSectionHeader(title = stringResource(R.string.guests_new_header)) }
        item {
          FluidTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.guests_new_name),
            placeholder = stringResource(R.string.guests_new_name_hint),
            modifier = Modifier.fillMaxWidth(),
          )
        }
        item {
          FluidButton(
            text = stringResource(R.string.guests_new_create),
            onClick = {
              viewModel.invite(name)
              name = ""
            },
            enabled = name.isNotBlank() && !state.busy,
            loading = state.busy,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }

        item { FluidSectionHeader(title = stringResource(R.string.guests_list_header)) }
        if (!state.loading && state.guests.isEmpty()) {
          item { FluidEmptyState(title = stringResource(R.string.guests_empty_title), detail = stringResource(R.string.guests_empty_detail)) }
        } else if (state.guests.isNotEmpty()) {
          item {
            FluidListGroup {
              state.guests.forEachIndexed { index, guest ->
                if (index > 0) FluidListDivider()
                FluidListRow(
                  title = guest.name,
                  subtitle = guestStatus(guest),
                  eyebrow = stringResource(R.string.shares_created, Formats.relativeDate(guest.createdAt)),
                  contextActions = {
                    listOf(FluidContextAction(label = revokeLabel, destructive = true) { viewModel.revoke(guest.guestId) })
                  },
                )
              }
            }
          }
          item { FluidSectionFootnote(text = stringResource(R.string.guests_list_footnote)) }
        }

        item { FluidSectionHeader(title = stringResource(R.string.guests_companion_header)) }
        item {
          FluidCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(text = stringResource(R.string.guests_companion_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(text = "\"index_url\": \"${state.indexUrl}\"", style = MaterialTheme.typography.bodyMedium)
              Text(text = "\"owner\": \"${state.owner}\"", style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
        item { FluidSectionFootnote(text = stringResource(R.string.guests_tailscale_footnote)) }
      }
    }

    state.error?.let { error ->
      item { FluidInlineMessage(title = stringResource(R.string.share_error_title), message = error, tone = FluidTone.Danger) }
    }
  }
}

/** «3 trascrizioni · 2 h di audio · l'ultima oggi», o «Mai usato». */
@Composable
private fun guestStatus(guest: GuestInfo): String {
  if (guest.jobs == 0) return stringResource(R.string.guests_never_used)
  val jobs = pluralStringResource(R.plurals.guests_jobs, guest.jobs, guest.jobs)
  val audio = Formats.durationShort(guest.seconds * 1000L)
  val last = guest.lastUsedAt?.let { stringResource(R.string.guests_last_used, Formats.relativeDate(it)) }.orEmpty()
  return listOf(jobs, audio, last).filter { it.isNotBlank() }.joinToString(" · ")
}
