package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.antigravity.fluidengine.ui.fluid.FluidAppearanceStrings
import dev.antigravity.fluidengine.ui.fluid.FluidSearchLabels
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateStrings
import dev.pampa.pampanotes.R

/**
 * Le parole dei pezzi dell'engine, nella lingua dell'app.
 *
 * L'engine scrive in italiano di serie: un'app che e' anche in inglese le passa tutte, o in mezzo a
 * una pagina inglese resta una frase italiana. Le stringhe stanno nelle risorse come tutte le altre
 * (`values` e `values-en`), e qui si raccolgono in un oggetto solo per pezzo.
 */

/** «Aspetto»: i nomi dei temi, del nero assoluto, dell'accento e dell'aptica. */
@Composable
fun rememberAppearanceStrings(): FluidAppearanceStrings {
  val resources = LocalContext.current.resources
  return remember(resources) {
    FluidAppearanceStrings(
      title = resources.getString(R.string.settings_section_appearance),
      themeSystem = resources.getString(R.string.theme_system),
      themeSystemDetail = resources.getString(R.string.appearance_theme_system_detail),
      themeLight = resources.getString(R.string.theme_light),
      themeDark = resources.getString(R.string.theme_dark),
      pureBlack = resources.getString(R.string.settings_amoled),
      pureBlackDetail = resources.getString(R.string.settings_amoled_detail),
      accent = resources.getString(R.string.appearance_accent),
      // «Colori dallo sfondo»: il pallino dei colori di sistema, quello che era l'interruttore di Material You.
      accentDynamic = resources.getString(R.string.settings_dynamic_color),
      haptics = resources.getString(R.string.settings_haptics),
      hapticsDetail = resources.getString(R.string.settings_haptics_detail),
    )
  }
}

/** Gli aggiornamenti: le righe delle impostazioni e l'avviso, con le frasi dell'installazione. */
@Composable
fun rememberUpdateStrings(): FluidUpdateStrings {
  val resources = LocalContext.current.resources
  return remember(resources) {
    FluidUpdateStrings(
      promptTitle = { resources.getString(R.string.update_card_title, it) },
      availableMessage = { size ->
        if (size != null) {
          resources.getString(R.string.update_available_message, size)
        } else {
          resources.getString(R.string.update_available_message_unsized)
        }
      },
      whatsNew = resources.getString(R.string.update_whats_new),
      install = resources.getString(R.string.update_install),
      retry = resources.getString(R.string.action_retry),
      later = resources.getString(R.string.update_later),
      skip = resources.getString(R.string.update_skip),
      close = resources.getString(R.string.action_close),
      updating = resources.getString(R.string.update_updating),
      openSettings = resources.getString(R.string.update_permission_open),
      permission = resources.getString(R.string.update_permission),
      installed = resources.getString(R.string.update_installed),
      failedTitle = resources.getString(R.string.update_failed_title),
      verifying = resources.getString(R.string.update_verifying),
      installing = resources.getString(R.string.update_installing),
      awaitingUser = resources.getString(R.string.update_awaiting),
      downloading = { percent, _, total ->
        when {
          percent != null && total != null -> resources.getString(R.string.update_downloading, percent, total)
          else -> resources.getString(R.string.update_downloading_unknown)
        }
      },
      sectionTitle = resources.getString(R.string.update_title),
      currentVersion = resources.getString(R.string.settings_version),
      betaToggle = resources.getString(R.string.update_beta),
      betaToggleDetail = resources.getString(R.string.update_beta_detail),
      checkNow = resources.getString(R.string.update_check_now),
      checking = resources.getString(R.string.update_checking),
      upToDate = resources.getString(R.string.update_none),
      upToDateDetail = resources.getString(R.string.update_up_to_date_detail),
      checkFailed = resources.getString(R.string.update_check_failed_title),
      checkFailedDetail = resources.getString(R.string.update_check_failed),
      available = { resources.getString(R.string.update_card_title, it) },
      installVersion = { resources.getString(R.string.update_install_version, it) },
      newBadge = resources.getString(R.string.update_badge_new),
      unsupported = resources.getString(R.string.update_debug_title),
      unsupportedDetail = resources.getString(R.string.update_debug_detail),
      lastCheckedNever = resources.getString(R.string.update_never_checked),
      lastCheckedNow = resources.getString(R.string.update_checked_now),
      lastCheckedMinutes = { resources.getQuantityString(R.plurals.update_checked_minutes, it, it) },
      lastCheckedHours = { resources.getQuantityString(R.plurals.update_checked_hours, it, it) },
      lastCheckedDays = {
        if (it == 1) {
          resources.getString(R.string.update_checked_yesterday)
        } else {
          resources.getQuantityString(R.plurals.update_checked_days, it, it)
        }
      },
    )
  }
}

/** La ricerca dentro una pagina: «3/12», le frecce, la crocetta. */
@Composable
fun rememberSearchLabels(): FluidSearchLabels {
  val resources = LocalContext.current.resources
  return remember(resources) {
    FluidSearchLabels(
      search = resources.getString(R.string.session_search),
      clear = resources.getString(R.string.session_search_clear),
      close = resources.getString(R.string.session_search_close),
      previous = resources.getString(R.string.session_search_previous),
      next = resources.getString(R.string.session_search_next),
      noResults = resources.getString(R.string.session_search_none),
      counterSpoken = { current, total -> resources.getString(R.string.session_search_counter_spoken, current, total) },
    )
  }
}
