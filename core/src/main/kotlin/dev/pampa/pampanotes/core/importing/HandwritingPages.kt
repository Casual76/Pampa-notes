package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Le pagine scritte a mano di un `.sdocx`, come immagini attaccate alla nota.
 *
 * Il testo battuto di Samsung Notes entra nel corpo della nota; l'inchiostro no, perche' non e'
 * testo. Ma sono appunti anche quelli — spesso i piu' importanti, scritti a lezione con la penna — e
 * un assistente che riceve il pacchetto li sa leggere, se glieli si da' come immagini. Qui si
 * disegnano ([SdocxInk], [InkLayout], [InkRenderer]) e si salvano come sorgenti `IMAGE` con
 * [SourceEntity.derivedFromId] che punta al `.sdocx`: cosi' seguono da sole tutte le strade delle
 * sorgenti — sync della riga, archivio sul computer di casa, «Libera spazio», export — e se ne vanno
 * col `.sdocx` quando la nota si aggiorna da una versione nuova.
 */
@Singleton
class HandwritingPages @Inject constructor(
  private val sources: SourceDao,
  private val files: AppFiles,
  private val fetcher: ArchiveFetcher,
  private val archive: ArchiveRepository,
) {
  /** Due giri sullo stesso `.sdocx` insieme farebbero le pagine due volte. */
  private val oneAtATime = Mutex()

  /**
   * Ricava le pagine da un `.sdocx` gia' salvato e torna quante ne ha fatte.
   *
   * Non fallisce mai: un file che non si legge vuol dire zero pagine, e l'import va avanti.
   */
  suspend fun derive(sdocx: SourceEntity, file: File, title: String): Int = withContext(Dispatchers.IO) {
    oneAtATime.withLock { deriveLocked(sdocx, file, title) }
  }

  /**
   * Rifa' le pagine di una nota: quelle di prima se ne vanno, i `.sdocx` si rileggono. Se un
   * originale sta solo sul computer di casa lo si scarica prima.
   *
   * @return quante pagine ci sono adesso
   */
  suspend fun rederive(noteId: String, title: String): Int = withContext(Dispatchers.IO) {
    oneAtATime.withLock {
      sources.byNote(noteId).filter { it.kind == SourceKind.SDOCX }.sumOf { sdocx ->
        val file = runCatching { fetcher.fetchSource(sdocx) }.getOrNull() ?: return@sumOf 0
        forgetLocked(sdocx.id)
        deriveLocked(sdocx, file, title)
      }
    }
  }

  /**
   * Le note importate prima che l'app sapesse leggere l'inchiostro: un giro solo, sui `.sdocx` che
   * stanno sul dispositivo e non hanno ancora pagine. Quelli che stanno solo sul computer si
   * lasciano al tasto della nota: scaricare gigabyte all'apertura per cercare della penna non si fa.
   *
   * @return quante pagine ha ricavato
   */
  suspend fun backfill(titleOf: suspend (noteId: String) -> String?): Int = withContext(Dispatchers.IO) {
    oneAtATime.withLock {
      sources.byKind(SourceKind.SDOCX).sumOf { sdocx ->
        val stored = sdocx.storedFileName ?: return@sumOf 0
        val file = files.sourceFile(stored)
        if (!file.exists() || sources.derivedFrom(sdocx.id).isNotEmpty()) return@sumOf 0
        deriveLocked(sdocx, file, titleOf(sdocx.noteId) ?: sdocx.originalName.substringBeforeLast('.'))
      }
    }
  }

  /** Toglie le pagine ricavate da un `.sdocx`: righe, file, e il blob sul computer se nessuno lo cita. */
  suspend fun forget(sdocxId: String) = withContext(Dispatchers.IO) {
    oneAtATime.withLock { forgetLocked(sdocxId) }
  }

  // -----------------------------------------------------------------------------------------------

  private suspend fun deriveLocked(sdocx: SourceEntity, file: File, title: String): Int {
    val pages = runCatching { ZipFile(file).use { SdocxInk.read(it) } }.getOrDefault(emptyList())
    val now = System.currentTimeMillis()
    var number = 0
    pages.forEach { page ->
      InkLayout.slices(page).forEach { slice ->
        // Un id che dipende solo dal `.sdocx` e dal numero della pagina: se due dispositivi con lo
        // stesso originale ricavano le pagine tutti e due — il giro unico gira su ognuno — escono
        // le stesse righe, e il sync le tratta come una sola invece di portarle doppie.
        val id = pageId(sdocx.id, number + 1)
        val stored = "$id.png"
        val target = files.sourceFile(stored)
        val written = runCatching { InkRenderer.render(page, slice, target) }.getOrDefault(false)
        if (!written || !target.exists()) {
          target.delete()
          return@forEach
        }
        number++
        sources.upsert(
          SourceEntity(
            id = id,
            noteId = sdocx.noteId,
            kind = SourceKind.IMAGE,
            originalName = "$title · pagina $number.png",
            mime = "image/png",
            sizeBytes = target.length(),
            sha256 = target.inputStream().use { Hashing.sha256(it) },
            storedFileName = stored,
            status = SourceStatus.OK,
            // Un millisecondo di scarto fra una pagina e l'altra: l'ordine del quaderno e' l'ordine
            // di importazione, e due righe con lo stesso istante non ne hanno uno.
            importedAt = now + number,
            derivedFromId = sdocx.id,
          ),
        )
      }
    }
    return number
  }

  private fun pageId(sdocxId: String, number: Int): String =
    UUID.nameUUIDFromBytes("$sdocxId/pagina/$number".toByteArray()).toString()

  private suspend fun forgetLocked(sdocxId: String) {
    sources.derivedFrom(sdocxId).forEach { page ->
      page.storedFileName?.let { files.sourceFile(it).delete() }
      sources.delete(page.id)
      if (page.archivedAt > 0 && sources.findBySha(page.sha256) == null) runCatching { archive.forget(page.sha256) }
    }
  }
}
