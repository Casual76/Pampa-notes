package dev.pampa.pampanotes.ui.nav

import android.net.Uri

/** Le rotte dell'app, in un posto solo: le stringhe non si scrivono a mano nelle schermate. */
object Routes {
  const val HOME = "home"
  const val SEARCH = "search"
  const val JOBS = "jobs"
  const val SETTINGS = "settings"
  const val ONBOARDING = "onboarding"

  const val FOLDER = "folder/{folderId}"
  const val NOTE = "note/{noteId}?tab={tab}"
  const val EDITOR = "editor/{noteId}"
  const val SESSION = "session/{sessionId}"
  const val SETTINGS_SECTION = "settings/{section}"

  fun folder(id: String) = "folder/${Uri.encode(id)}"
  fun note(id: String, tab: String? = null) = "note/${Uri.encode(id)}" + (tab?.let { "?tab=${Uri.encode(it)}" } ?: "")
  fun editor(noteId: String) = "editor/${Uri.encode(noteId)}"
  fun session(id: String) = "session/${Uri.encode(id)}"
  fun settingsSection(section: String) = "settings/${Uri.encode(section)}"

  /** Le quattro schede della barra, nell'ordine in cui stanno. */
  val topLevel: List<String> = listOf(HOME, SEARCH, JOBS, SETTINGS)
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
