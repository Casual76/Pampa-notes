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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * sorgenti — sync della riga, archivio sul computer di casa, export — e se ne vanno col `.sdocx`
 * quando la nota si aggiorna da una versione nuova.
 *
 * Le chiamate al computer di casa ([ArchiveRepository.forget]) non si fanno mai col lucchetto
 * preso: sono richieste di rete da due secondi l'una, e con il lucchetto in mano fermerebbero
 * l'import che aspetta di disegnare le sue pagine.
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
   * Lo scope dei giri chiesti da una schermata. «Ricava le pagine a mano» su un quaderno lungo dura
   * decine di secondi, e chi lo chiede puo' uscire dalla nota prima: col ViewModel se ne andrebbe
   * anche il giro, a meta', con le pagine vecchie gia' tolte e le nuove non ancora scritte.
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** I `.sdocx` gia' provati dal giro unico: un segnaposto per file, scritto prima di provarlo. */
  private val triedDir: File get() = File(files.root, TRIED_DIR)

  /**
   * Ricava le pagine da un `.sdocx` gia' salvato e torna quante ne ha fatte.
   *
   * Non fallisce mai: un file che non si legge vuol dire zero pagine, e l'import va avanti. Le
   * pagine che lo stesso `.sdocx` aveva prima e che adesso non ci sono piu' se ne vanno.
   */
  suspend fun derive(sdocx: SourceEntity, file: File, title: String): Int = withContext(Dispatchers.IO) {
    val stale = mutableListOf<String>()
    val pages = oneAtATime.withLock { deriveLocked(sdocx, file, title, stale) }
    forgetRemotely(stale)
    pages
  }

  /**
   * Rifa' le pagine di una nota: i `.sdocx` si rileggono e le pagine si riscrivono al loro posto.
   * Se un originale sta solo sul computer di casa lo si scarica prima.
   *
   * Gira nello scope di questa classe, non in quello di chi chiama: uscire dalla nota non lo
   * interrompe, e chi chiama riceve il conto solo se e' ancora li' ad aspettarlo.
   *
   * @return quante pagine ci sono adesso
   */
  suspend fun rederive(noteId: String, title: String): Int = scope.async {
    val stale = mutableListOf<String>()
    val count = oneAtATime.withLock {
      sources.byNote(noteId).filter { it.kind == SourceKind.SDOCX }.sumOf { sdocx ->
        val file = runCatching { fetcher.fetchSource(sdocx) }.getOrNull() ?: return@sumOf 0
        deriveLocked(sdocx, file, title, stale)
      }
    }
    forgetRemotely(stale)
    count
  }.await()

  /**
   * Le note importate prima che l'app sapesse leggere l'inchiostro: un giro solo, sui `.sdocx` che
   * stanno sul dispositivo e non hanno ancora pagine. Quelli che stanno solo sul computer si
   * lasciano al tasto della nota: scaricare gigabyte all'apertura per cercare della penna non si fa.
   *
   * Ogni file si segna come provato **prima** di provarlo: se un file fatto male fa morire l'app a
   * meta' disegno, al prossimo avvio si salta invece di morire di nuovo, per sempre. Il tasto della
   * nota lo riprova quando lo si chiede.
   *
   * @return quante pagine ha ricavato
   */
  suspend fun backfill(titleOf: suspend (noteId: String) -> String?): Int = withContext(Dispatchers.IO) {
    oneAtATime.withLock {
      triedDir.mkdirs()
      sources.byKind(SourceKind.SDOCX).sumOf { sdocx ->
        val stored = sdocx.storedFileName ?: return@sumOf 0
        val file = files.sourceFile(stored)
        val marker = File(triedDir, sdocx.id)
        if (marker.exists() || !file.exists() || sources.derivedFrom(sdocx.id).isNotEmpty()) return@sumOf 0
        runCatching { marker.createNewFile() }
        // Nel giro unico non c'e' niente da togliere: si tocca solo chi non ha pagine.
        deriveLocked(sdocx, file, titleOf(sdocx.noteId) ?: sdocx.originalName.substringBeforeLast('.'), mutableListOf())
      }
    }
  }

  /**
   * Toglie le pagine ricavate da un `.sdocx`: righe e file.
   *
   * Torna le impronte dei blob da togliere dal computer di casa, invece di toglierli: chi chiama
   * lo fa quando ha finito, fuori da ogni lucchetto, tutte insieme ([forgetRemotely]).
   */
  suspend fun forgetPages(sdocxId: String): List<String> = withContext(Dispatchers.IO) {
    val stale = mutableListOf<String>()
    oneAtATime.withLock { removeLocked(sources.derivedFrom(sdocxId), stale) }
    stale
  }

  /** Toglie dal computer di casa i blob che nessuna riga cita piu'. Un errore non ferma niente. */
  suspend fun forgetRemotely(shas: Collection<String>) {
    shas.distinct().forEach { sha ->
      if (sources.findBySha(sha) != null) return@forEach
      try {
        archive.forget(sha)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Throwable) {
      }
    }
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Le pagine si leggono, si tagliano e si disegnano una alla volta: di un quaderno lungo in
   * memoria c'e' una pagina sola. Ogni passo sta nel suo `try`, perche' una pagina che non si
   * disegna non deve portarsi dietro le altre, ne' l'import che le ha chieste.
   *
   * Le pagine vecchie dello stesso `.sdocx` si tolgono **dopo** aver scritto le nuove, e solo
   * quelle che il nuovo giro non ha riscritto: fino all'ultimo passo la nota ha le sue pagine.
   */
  private suspend fun deriveLocked(sdocx: SourceEntity, file: File, title: String, stale: MutableList<String>): Int {
    val before = runCatching { sources.derivedFrom(sdocx.id) }.getOrDefault(emptyList())
    val written = mutableSetOf<String>()
    val now = System.currentTimeMillis()
    var number = 0
    try {
      ZipFile(file).use { zip ->
        for (page in SdocxInk.pages(zip)) {
          val slices = runCatching { InkLayout.slices(page) }.getOrDefault(emptyList())
          for (slice in slices) {
            // Un id che dipende solo dal `.sdocx` e dal numero della pagina: se due dispositivi con
            // lo stesso originale ricavano le pagine tutti e due — il giro unico gira su ognuno —
            // escono le stesse righe, e il sync le tratta come una sola invece di portarle doppie.
            val id = pageId(sdocx.id, number + 1)
            val saved = writePage(sdocx, page, slice, id, title, number + 1, now, before.firstOrNull { it.id == id }, stale) ?: continue
            number++
            written += saved
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      // Il file non si apre come ZIP, o si rompe a meta': si tiene quello che si e' fatto.
    }
    removeLocked(before.filter { it.id !in written }, stale)
    return number
  }

  /** Disegna e salva una pagina; torna il suo id, o null se non e' andata. */
  private suspend fun writePage(
    sdocx: SourceEntity,
    page: InkPage,
    slice: InkSlice,
    id: String,
    title: String,
    number: Int,
    now: Long,
    previous: SourceEntity?,
    stale: MutableList<String>,
  ): String? {
    val stored = "$id.png"
    val target = files.sourceFile(stored)
    // Si disegna in un file a parte e solo alla fine si prende il nome buono: rifacendo le pagine,
    // un disegno fallito a meta' non deve lasciare al posto della pagina vecchia un PNG troncato.
    val temp = files.tempFile(prefix = "ink", suffix = ".png")
    return try {
      val ok = InkRenderer.render(page, slice, temp)
      if (!ok || !temp.exists() || temp.length() == 0L) return null
      if (!temp.renameTo(target)) {
        temp.copyTo(target, overwrite = true)
        temp.delete()
      }
      val sha = target.inputStream().use { Hashing.sha256(it) }
      // La stessa pagina ridisegnata uguale e' gia' sul computer: non si rimanda. Se invece e'
      // cambiata, il blob vecchio non lo cita piu' nessuno.
      val archivedAt = if (previous != null && previous.sha256 == sha) previous.archivedAt else 0L
      if (previous != null && previous.sha256 != sha && previous.archivedAt > 0) stale += previous.sha256
      sources.upsert(
        SourceEntity(
          id = id,
          noteId = sdocx.noteId,
          kind = SourceKind.IMAGE,
          // «p.» si legge in italiano e in inglese: il nome sta nel database e viaggia col sync fra
          // dispositivi che possono parlare due lingue diverse.
          originalName = "$title · p. $number.png",
          mime = "image/png",
          sizeBytes = target.length(),
          sha256 = sha,
          storedFileName = stored,
          status = SourceStatus.OK,
          archivedAt = archivedAt,
          // Un millisecondo di scarto fra una pagina e l'altra: l'ordine del quaderno e' l'ordine
          // di importazione, e due righe con lo stesso istante non ne hanno uno.
          importedAt = now + number,
          derivedFromId = sdocx.id,
        ),
      )
      id
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      null
    } finally {
      temp.delete()
    }
  }

  companion object {
    /** La cartella dei segnaposto del giro unico, sotto `filesDir`: il ripristino di un backup la svuota. */
    const val TRIED_DIR = "handwriting-tried"
  }

  private fun pageId(sdocxId: String, number: Int): String =
    UUID.nameUUIDFromBytes("$sdocxId/pagina/$number".toByteArray()).toString()

  private suspend fun removeLocked(pages: List<SourceEntity>, stale: MutableList<String>) {
    pages.forEach { page ->
      runCatching {
        page.storedFileName?.let { files.sourceFile(it).delete() }
        sources.delete(page.id)
        if (page.archivedAt > 0) stale += page.sha256
      }
    }
  }
}
