package dev.pampa.pampanotes.ui.nav

import android.net.Uri

/** Le rotte dell'app, in un posto solo: le stringhe non si scrivono a mano nelle schermate. */
object Routes {
  const val HOME = "home"
  const val FOLDERS = "folders"
  const val MORE = "more"
  const val SEARCH = "search"
  const val JOBS = "jobs"
  const val SETTINGS = "settings"
  const val ONBOARDING = "onboarding"

  const val FOLDER = "folder/{folderId}"
  const val NOTE = "note/{noteId}?tab={tab}"
  const val EDITOR = "editor/{noteId}"
  const val SESSION = "session/{sessionId}"
  const val SETTINGS_SECTION = "settings/{section}"

  /**
   * Il wizard di import.
   *
   * Gli URI non passano dalla rotta: sono lunghi, vanno codificati due volte e un back stack
   * ripristinato dopo la morte del processo se li ritroverebbe scaduti. La richiesta vive in
   * [dev.pampa.pampanotes.ui.importing.ImportRequestHolder]; la rotta dice solo "apri il wizard".
   */
  const val IMPORT = "import"

  fun folder(id: String) = "folder/${Uri.encode(id)}"
  fun note(id: String, tab: String? = null) = "note/${Uri.encode(id)}" + (tab?.let { "?tab=${Uri.encode(it)}" } ?: "")
  fun editor(noteId: String) = "editor/${Uri.encode(noteId)}"
  fun session(id: String) = "session/${Uri.encode(id)}"
  fun settingsSection(section: String) = "settings/${Uri.encode(section)}"

  /**
   * Le tre schede della barra, nell'ordine in cui stanno.
   *
   * Tre e non sei: Home e' quello che si e' caricato per ultimo, Cartelle l'archivio per materia, e
   * il resto — cerca, lavori, export, impostazioni — sta dietro Altro. Una barra con sei voci
   * costringe a leggerle ogni volta invece di riconoscerle.
   */
  val topLevel: List<String> = listOf(HOME, FOLDERS, MORE)
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
  ABOUT("about");

  companion object {
    fun fromRoute(route: String?): SettingsSection? = entries.firstOrNull { it.route == route }
  }
}
