package dev.pampa.pampanotes.ui.nav

import android.net.Uri

/** Le rotte dell'app, in un posto solo: le stringhe non si scrivono a mano nelle schermate. */
object Routes {
  const val HOME = "home"
  const val FOLDERS = "folders"
  const val RECORDINGS = "recordings"
  const val MORE = "more"
  const val SEARCH = "search"
  const val JOBS = "jobs"
  const val SETTINGS = "settings"
  const val ONBOARDING = "onboarding"

  const val FOLDER = "folder/{folderId}"
  const val NOTE = "note/{noteId}?tab={tab}"
  const val EDITOR = "editor/{noteId}"
  /**
   * `play=1`: aperta da «Riprendi ad ascoltare», riparte dal punto salvato. `at` (millisecondi di
   * sessione) e `q`: aperta dalla ricerca, il lettore pronto nel momento in cui si dice quello che si
   * e' cercato, e la ricerca dentro la sessione gia' scritta. Valgono una volta sola (vedi
   * `SessionViewModel` e [dev.pampa.pampanotes.ui.nav.syncPanes]).
   */
  const val SESSION = "session/{sessionId}?play={play}&at={at}&q={q}"
  const val SETTINGS_SECTION = "settings/{section}"

  /**
   * Il wizard di import.
   *
   * Gli URI non passano dalla rotta: sono lunghi, vanno codificati due volte e un back stack
   * ripristinato dopo la morte del processo se li ritroverebbe scaduti. La richiesta vive in
   * [dev.pampa.pampanotes.ui.importing.ImportRequestHolder]; la rotta dice solo "apri il wizard".
   */
  const val IMPORT = "import"

  /**
   * La partenza del pannello di dettaglio su una pagina larga: niente di aperto.
   *
   * Esiste perche' il back funzioni da solo: con questa sotto, lo stack del dettaglio ha sempre
   * due voci quando una nota e' aperta, il suo `NavHost` ha il back attivo e, componendosi per
   * ultimo, vince. Nessun back handler scritto a mano.
   */
  const val DETAIL_EMPTY = "detail"

  /**
   * Le rotte che su una pagina larga stanno nel dettaglio: la nota, la sessione, l'editor,
   * l'import, e una sezione delle impostazioni (non l'indice, che e' una lista).
   */
  fun isDetail(route: String?): Boolean =
    route != null && (route == IMPORT || detailPrefixes.any { route.startsWith(it) })

  private val detailPrefixes = listOf("note/", "session/", "editor/", "settings/")

  fun folder(id: String) = "folder/${Uri.encode(id)}"
  fun note(id: String, tab: String? = null) = "note/${Uri.encode(id)}" + (tab?.let { "?tab=${Uri.encode(it)}" } ?: "")
  fun editor(noteId: String) = "editor/${Uri.encode(noteId)}"
  fun session(id: String, play: Boolean = false, atMs: Long? = null, query: String? = null): String {
    val args = buildList {
      if (play) add("play=1")
      atMs?.let { add("at=$it") }
      query?.takeIf { it.isNotBlank() }?.let { add("q=${Uri.encode(it)}") }
    }
    return "session/${Uri.encode(id)}" + if (args.isEmpty()) "" else args.joinToString("&", prefix = "?")
  }
  fun settingsSection(section: String) = "settings/${Uri.encode(section)}"

  /**
   * Le quattro schede della barra, nell'ordine in cui stanno.
   *
   * Quattro e non sei: Home e' quello che si e' caricato per ultimo, Cartelle l'archivio per
   * materia, Registrazioni l'audio che non e' una lezione, e il resto — cerca, lavori, export,
   * impostazioni — sta dietro Altro. Registrazioni ha una scheda sua e non una voce in Altro perche'
   * e' un archivio, non un'impostazione: si apre per ascoltare, e tenerla accanto alle materie senza
   * mescolarla con loro e' tutto il suo senso. Una barra con sei voci costringe a leggerle ogni volta
   * invece di riconoscerle; quattro si riconoscono ancora.
   */
  val topLevel: List<String> = listOf(HOME, FOLDERS, RECORDINGS, MORE)
  val topLevelSet: Set<String> = topLevel.toSet()
}

/** Le sezioni della pagina Impostazioni, una rotta ciascuna. */
enum class SettingsSection(val route: String) {
  SERVICES("services"),
  TRANSCRIPTION("transcription"),
  REFINEMENT("refinement"),
  EXPORT("export"),
  BACKUP("backup"),
  APPEARANCE("appearance"),
  STORAGE("storage"),
  SYNC("sync"),
  SHARES("shares"),
  GUESTS("guests"),
  ABOUT("about"),

  /** «Installa sul tuo computer»: non sta nell'indice, ci si arriva da Servizi. */
  INSTALL("install");

  companion object {
    fun fromRoute(route: String?): SettingsSection? = entries.firstOrNull { it.route == route }
  }
}
