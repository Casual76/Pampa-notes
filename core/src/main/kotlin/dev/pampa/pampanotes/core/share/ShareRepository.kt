package dev.pampa.pampanotes.core.share

import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.sync.SyncRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Cosa comporta condividere questa nota, prima di farlo: e' la riga «120 MB» che si legge prima di toccare. */
data class SharePlan(
  /** L'indice in cloud e' configurato: senza, non c'e' dove pubblicare. */
  val configured: Boolean,
  val parts: Int,
  val bytes: Long,
  /** Registrazioni che non sono ne' qui ne' sul computer di casa: la pagina dira' che l'audio manca. */
  val unavailable: Int,
  /** Il link gia' vivo per questa nota, se c'e'. */
  val existing: ShareInfo? = null,
)

enum class ShareStage { SYNCING, CREATING, FETCHING, UPLOADING, DONE }

data class ShareProgress(val stage: ShareStage, val partName: String = "", val fraction: Float = 0f)

/**
 * Condividere una nota: un link che si apre e si ascolta.
 *
 * Il testo e la trascrizione sono gia' nell'indice in cloud, quindi la prima cosa e' un giro di
 * sincronizzazione — la pagina deve mostrare quello che c'e' *adesso*, non quello di sei ore fa.
 * Poi il server crea il link, e sale l'audio delle sessioni della nota: da qui se c'e', dal
 * computer di casa se sta solo la' ([ArchiveFetcher]), e se non e' in nessuno dei due posti la
 * pagina lo dice invece di fingere. Un `HEAD` prima di ogni parte rende tutto ripetibile: un
 * caricamento interrotto riparte da dove era.
 */
@Singleton
class ShareRepository @Inject constructor(
  private val settingsStore: PampaSettingsStore,
  private val notes: NoteDao,
  private val sessions: SessionDao,
  private val files: AppFiles,
  private val fetcher: ArchiveFetcher,
  private val api: ShareApi,
  private val sync: SyncRepository,
) {

  private suspend fun server(): Pair<String, String>? {
    val url = settingsStore.current().syncServerUrl.takeIf { it.isNotBlank() } ?: return null
    val token = settingsStore.syncToken() ?: return null
    return url to token
  }

  private suspend fun partsOf(noteId: String): List<AudioPartEntity> =
    sessions.byNote(noteId).sortedBy { it.session.position }.flatMap { it.partsSorted }

  suspend fun plan(noteId: String): SharePlan {
    val server = server()
    val parts = partsOf(noteId)
    val unavailable = parts.count { !files.audioFile(it.fileName).exists() && it.archivedAt <= 0 }
    val existing = server?.let { (url, token) -> runCatching { api.list(url, token).firstOrNull { it.noteId == noteId } }.getOrNull() }
    return SharePlan(configured = server != null, parts = parts.size, bytes = parts.sumOf { it.sizeBytes }, unavailable = unavailable, existing = existing)
  }

  suspend fun share(noteId: String, onProgress: (ShareProgress) -> Unit = {}): ShareInfo {
    val (url, token) = server() ?: throw ShareException(0, "l'indice in cloud non e' configurato")

    onProgress(ShareProgress(ShareStage.SYNCING, fraction = 0.02f))
    val report = sync.syncNow()
    if (!report.ok) throw ShareException(0, report.error ?: "la sincronizzazione non e' andata")

    onProgress(ShareProgress(ShareStage.CREATING, fraction = 0.05f))
    val title = notes.get(noteId)?.title.orEmpty().ifBlank { "Nota" }
    val info = api.create(url, token, noteId, title)

    val parts = partsOf(noteId)
    val total = parts.sumOf { it.sizeBytes }.coerceAtLeast(1L)
    var done = 0L
    for (part in parts) {
      val base = 0.05f + 0.95f * done / total
      try {
        if (api.hasAudio(url, token, info.shareId, part.id)) {
          done += part.sizeBytes
          continue
        }
        val file = files.audioFile(part.fileName)
        if (!file.exists()) {
          // Non e' qui e non e' mai arrivata al computer: la pagina dira' che manca. Non si blocca
          // tutto per una registrazione rimasta su un altro telefono.
          if (part.archivedAt <= 0) {
            done += part.sizeBytes
            continue
          }
          onProgress(ShareProgress(ShareStage.FETCHING, part.originalName, base))
          fetcher.fetchPart(part) { received, size ->
            val within = if (size > 0) received.toFloat() / size else 0f
            onProgress(ShareProgress(ShareStage.FETCHING, part.originalName, 0.05f + 0.95f * (done + within * part.sizeBytes / 2) / total))
          }
        }
        onProgress(ShareProgress(ShareStage.UPLOADING, part.originalName, base))
        api.uploadAudio(url, token, info.shareId, part.id, file, part.mime) { sent, _ ->
          onProgress(ShareProgress(ShareStage.UPLOADING, part.originalName, 0.05f + 0.95f * (done + sent) / total))
        }
        done += part.sizeBytes
      } catch (cancelled: CancellationException) {
        throw cancelled
      }
    }
    onProgress(ShareProgress(ShareStage.DONE, fraction = 1f))
    // Com'e' adesso, dopo i caricamenti: la risposta della creazione diceva «senza audio».
    return runCatching { api.list(url, token).firstOrNull { it.shareId == info.shareId } }.getOrNull() ?: info
  }

  suspend fun list(): List<ShareInfo> {
    val (url, token) = server() ?: return emptyList()
    return api.list(url, token)
  }

  suspend fun revoke(shareId: String) {
    val (url, token) = server() ?: throw ShareException(0, "l'indice in cloud non e' configurato")
    api.revoke(url, token, shareId)
  }

  suspend fun configured(): Boolean = server() != null
}
