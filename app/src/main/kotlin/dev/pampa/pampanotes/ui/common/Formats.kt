package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Formati che compaiono in piu' schermate: durate, date, dimensioni. Niente di specifico di una pagina. */
object Formats {

  private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
  private val dayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

  /** "1:04:22" oppure "4:22": le ore compaiono solo quando ci sono. */
  fun duration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
  }

  /**
   * "128k" per un contatore che deve stare in una tessera: sotto i mille il numero intero, poi le
   * migliaia con una cifra decimale finche' serve ("12,4k"), poi tonde ("128k").
   */
  fun compact(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 10_000 -> String.format(Locale.getDefault(), "%.1fk", value / 1_000.0).replace(".0k", "k").replace(",0k", "k")
    value < 1_000_000 -> "${value / 1_000}k"
    else -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
  }

  /** "128 mila" / "1,2 milioni" per una frase, dove "128k" suonerebbe da tastiera. In inglese, "thousand". */
  fun spoken(value: Long): String {
    val italian = Locale.getDefault().language == "it"
    return when {
      value < 1_000 -> value.toString()
      value < 1_000_000 -> "${value / 1_000} " + if (italian) "mila" else "thousand"
      else -> String.format(Locale.getDefault(), "%.1f ", value / 1_000_000.0) + if (italian) "milioni" else "million"
    }
  }

  /** "42 min" oppure "1 h 18": quello che sta in un meta di riga. */
  fun durationShort(millis: Long): String {
    val totalMinutes = (millis / 60_000).coerceAtLeast(0)
    return when {
      totalMinutes < 1 -> "< 1 min"
      totalMinutes < 60 -> "$totalMinutes min"
      totalMinutes % 60 == 0L -> "${totalMinutes / 60} h"
      else -> "${totalMinutes / 60} h ${totalMinutes % 60}"
    }
  }

  /** "[04:12]" per i marcatori dentro una trascrizione. */
  fun timestamp(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
  }

  fun bytes(value: Long): String {
    if (value < 1024) return "$value B"
    val kb = value / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f kB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
  }

  /** "oggi", "ieri", "3 giorni fa", poi la data. Per il meta di una riga, dove lo spazio e' poco. */
  @Composable
  fun relativeDate(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return relativeDate(date)
  }

  @Composable
  fun relativeDate(date: LocalDate): String {
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
      days == 0L -> stringResource(R.string.date_today)
      days == 1L -> stringResource(R.string.date_yesterday)
      days in 2..6 -> stringResource(R.string.date_days_ago, days.toInt())
      date.year == today.year -> date.format(dayMonth)
      else -> date.format(dayMonthYear)
    }
  }

  fun isoDate(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
}

/** Il tono di una cartella, salvato per nome. Sconosciuto o assente vuol dire neutro. */
fun toneFromName(name: String?): FluidTone =
  FluidTone.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FluidTone.Neutral

/** I toni che il selettore di colore mostra, in un ordine che ha senso guardare. */
val selectableTones: List<FluidTone> = listOf(
  FluidTone.Primary,
  FluidTone.Info,
  FluidTone.Success,
  FluidTone.Warning,
  FluidTone.Danger,
  FluidTone.Neutral,
)

/** Il nome del tono per chi non vede il punto colorato. */
@Composable
fun FluidTone.label(): String = stringResource(
  when (this) {
    FluidTone.Primary -> R.string.tone_primary
    FluidTone.Success -> R.string.tone_success
    FluidTone.Warning -> R.string.tone_warning
    FluidTone.Danger -> R.string.tone_danger
    FluidTone.Info -> R.string.tone_info
    FluidTone.Neutral -> R.string.tone_neutral
  },
)

/** Il punto colorato del selettore: lo stesso colore che la tessera avra'. */
@Composable
fun FluidTone.dotColor(): Color = folderAccent(this)
