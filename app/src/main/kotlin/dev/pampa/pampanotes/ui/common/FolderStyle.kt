package dev.pampa.pampanotes.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Biotech
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Work
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidVividColors
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R

/**
 * Le icone fra cui si sceglie quando si crea una cartella.
 *
 * Un elenco chiuso invece di un selettore di emoji: un'emoji e' disegnata dal font di sistema, cioe'
 * cambia faccia fra un telefono e l'altro e non segue il colore della tessera. Queste sono le stesse
 * forme del resto dell'app, e prendono il colore che gli si da'.
 *
 * L'ordine e' quello in cui compaiono nel selettore, e comincia dalle materie che si incontrano per
 * prime in una scuola italiana.
 */
enum class FolderIcon(val key: String, val icon: ImageVector) {
  Folder("folder", Icons.Rounded.Folder),
  Book("book", Icons.AutoMirrored.Rounded.MenuBook),
  History("history", Icons.Rounded.AccountBalance),
  Philosophy("philosophy", Icons.Rounded.Psychology),
  Maths("maths", Icons.Rounded.Functions),
  Calculate("calculate", Icons.Rounded.Calculate),
  Science("science", Icons.Rounded.Science),
  Biology("biology", Icons.Rounded.Biotech),
  Geography("geography", Icons.Rounded.Public),
  Language("language", Icons.Rounded.Language),
  Art("art", Icons.Rounded.Brush),
  Music("music", Icons.Rounded.MusicNote),
  Theatre("theatre", Icons.Rounded.TheaterComedy),
  Law("law", Icons.Rounded.Gavel),
  Code("code", Icons.Rounded.Code),
  Sport("sport", Icons.Rounded.SportsBasketball),
  Work("work", Icons.Rounded.Work);

  companion object {
    fun fromKey(key: String?): FolderIcon = entries.firstOrNull { it.key == key } ?: Folder

    /**
     * Un'icona indovinata dal nome, proposta mentre si scrive.
     *
     * Non decide niente: riempie il selettore con qualcosa di sensato invece che con la cartella
     * generica, e chi vuole cambia. Le parole sono quelle che compaiono davvero su un orario
     * scolastico italiano, con gli inglesismi accanto.
     */
    fun guessFrom(name: String): FolderIcon {
      val n = name.lowercase().trim()
      val guesses = listOf(
        History to listOf("storia", "history", "epoca", "civilt"),
        Philosophy to listOf("filosof", "philosoph", "pensier", "etica"),
        Maths to listOf("matemat", "math", "analisi", "algebra", "geometr"),
        Calculate to listOf("fisica", "physics", "statistic", "econom"),
        Science to listOf("scienz", "science", "chimic", "chemistry"),
        Biology to listOf("biolog", "biology", "anatom", "natural"),
        Geography to listOf("geograf", "geography", "terra", "mondo"),
        Language to listOf("ingles", "english", "frances", "spagnol", "tedesc", "latino", "greco", "lingua"),
        Book to listOf("italian", "letterat", "literature", "poesia", "narrativ"),
        Art to listOf("arte", "art", "disegn", "storia dell"),
        Music to listOf("music", "canto", "solfeggi"),
        Theatre to listOf("teatro", "theatre", "recita", "dramma"),
        Law to listOf("dirit", "law", "giurid", "legal"),
        Code to listOf("informat", "coding", "program", "computer", "tecnolog"),
        Sport to listOf("motoria", "ginnast", "sport", "educazione fisica"),
        Work to listOf("lavoro", "stage", "tirocin", "progetto", "riunion"),
      )
      return guesses.firstOrNull { (_, words) -> words.any { it in n } }?.first ?: Folder
    }
  }
}

/** L'icona di una cartella, con il ripiego quando la chiave salvata non esiste piu'. */
fun folderIconOf(key: String?): ImageVector = FolderIcon.fromKey(key).icon

/**
 * Il colore pieno di una cartella.
 *
 * Sei tinte davvero diverse, non sei sfumature dello stesso viola. Prendere i ruoli della palette
 * (`primary`, `secondary`, `tertiary`) avrebbe tenuto tutto imparentato all'accento, ma l'engine
 * deriva quei tre da un colore solo: con un'ametista di marchio uscivano tre viola a un passo l'uno
 * dall'altro, e due materie vicine nella griglia diventavano indistinguibili. Qui le tinte sono
 * fisse — sono la stessa famiglia degli accenti selezionabili — e restano riconoscibili qualunque
 * accento l'utente scelga, che e' esattamente quello che serve a un'icona di materia.
 *
 * Il secondo colore della sfumatura e' lo stesso piu' scuro: una sfumatura verso un altro colore
 * farebbe leggere due materie in una tessera sola.
 *
 * Nel tema chiaro tutte e sei stanno sotto la soglia oltre la quale [FluidVividColors.from] sceglie
 * il testo scuro, e nel tema scuro tutte e sei ci stanno sopra. Non e' pignoleria: il verde e
 * l'arancio erano gli unici due abbastanza chiari da prendersi il testo nero, e in una griglia dove
 * le altre quattro lo avevano bianco quella differenza si legge come un errore, non come una scelta.
 */
@Composable
fun folderVividColors(tone: FluidTone): FluidVividColors {
  val dark = isSystemInDarkTheme()
  val (start, end) = when (tone) {
    FluidTone.Primary -> if (dark) Color(0xFF9B5DE5) to Color(0xFF6D4AD6) else Color(0xFF8B3FD6) to Color(0xFF6236C4)
    FluidTone.Info -> if (dark) Color(0xFF4F8DF5) to Color(0xFF3D63E0) else Color(0xFF2F6FE4) to Color(0xFF2448BE)
    FluidTone.Success -> if (dark) Color(0xFF34C77B) to Color(0xFF1FA36B) else Color(0xFF0E8050) to Color(0xFF0A6640)
    FluidTone.Warning -> if (dark) Color(0xFFF2A73B) to Color(0xFFDE7B2A) else Color(0xFFB05E0C) to Color(0xFF8F4A08)
    FluidTone.Danger -> if (dark) Color(0xFFF4607E) to Color(0xFFD93F63) else Color(0xFFDC3A5E) to Color(0xFFB62549)
    FluidTone.Neutral -> if (dark) Color(0xFF7C8A99) to Color(0xFF5C6875) else Color(0xFF5E6B78) to Color(0xFF44505C)
  }
  return FluidVividColors.from(start = start, end = end)
}

/** Lo stesso colore, per la piastrella dell'icona di una riga o di una card. */
@Composable
fun folderAccent(tone: FluidTone): Color = folderVividColors(tone).start

/** Il tono del fondale di una schermata che appartiene a una cartella: la pagina e la tessera si somigliano. */
fun ambientToneOf(tone: FluidTone): FluidHeroTone = when (tone) {
  FluidTone.Primary -> FluidHeroTone.Primary
  FluidTone.Info -> FluidHeroTone.Secondary
  FluidTone.Success -> FluidHeroTone.Tertiary
  FluidTone.Warning -> FluidHeroTone.SecondaryToTertiary
  FluidTone.Danger -> FluidHeroTone.Alert
  FluidTone.Neutral -> FluidHeroTone.PrimaryToSecondary
}

/** Il motivo del fondale, scelto dall'icona: due cartelle vicine non si somigliano per caso. */
fun ambientMotifOf(icon: FolderIcon): FluidHeroMotif = when (icon) {
  FolderIcon.History, FolderIcon.Law -> FluidHeroMotif.Bars
  FolderIcon.Philosophy, FolderIcon.Theatre -> FluidHeroMotif.Figures
  FolderIcon.Maths, FolderIcon.Calculate, FolderIcon.Code -> FluidHeroMotif.Ticks
  FolderIcon.Science, FolderIcon.Biology -> FluidHeroMotif.Dots
  FolderIcon.Geography, FolderIcon.Music -> FluidHeroMotif.Ripples
  FolderIcon.Book, FolderIcon.Language, FolderIcon.Art -> FluidHeroMotif.Cards
  else -> FluidHeroMotif.Glow
}

/** Il nome dell'icona, per chi non la vede. */
@Composable
fun FolderIcon.label(): String = stringResource(
  when (this) {
    FolderIcon.Folder -> R.string.icon_folder
    FolderIcon.Book -> R.string.icon_book
    FolderIcon.History -> R.string.icon_history
    FolderIcon.Philosophy -> R.string.icon_philosophy
    FolderIcon.Maths -> R.string.icon_maths
    FolderIcon.Calculate -> R.string.icon_calculate
    FolderIcon.Science -> R.string.icon_science
    FolderIcon.Biology -> R.string.icon_biology
    FolderIcon.Geography -> R.string.icon_geography
    FolderIcon.Language -> R.string.icon_language
    FolderIcon.Art -> R.string.icon_art
    FolderIcon.Music -> R.string.icon_music
    FolderIcon.Theatre -> R.string.icon_theatre
    FolderIcon.Law -> R.string.icon_law
    FolderIcon.Code -> R.string.icon_code
    FolderIcon.Sport -> R.string.icon_sport
    FolderIcon.Work -> R.string.icon_work
  },
)
