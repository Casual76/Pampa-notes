package dev.pampa.pampanotes.core.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.repo.NoteRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dove finisce quello che si importa. */
sealed interface ImportTarget {
  /** Una nota nuova dentro una cartella. Il titolo viene dal nome del file, se non lo si scrive. */
  data class NewNote(val folderId: String, val title: String) : ImportTarget

  /** Una nota che c'e' gia': il testo si aggiunge in fondo, i file si agganciano. */
  data class ExistingNote(val noteId: String) : ImportTarget

  /**
   * La stessa nota, con la versione nuova del suo file di Samsung Notes: il testo si sostituisce,
   * le registrazioni che c'erano restano con le loro trascrizioni, le nuove entrano, l'archivio
   * vecchio se ne va. Per tutto quello che non e' una nota Samsung vale [ExistingNote].
   */
  data class UpdateNote(val noteId: String) : ImportTarget
}

/** Com'e' andata, elemento per elemento. */
data class ImportOutcome(
  val noteId: String,
  val imported: List<ImportedItem>,
) {
  val failures: List<ImportedItem> get() = imported.filter { it.status == SourceStatus.FAILED }
  val partials: List<ImportedItem> get() = imported.filter { it.status == SourceStatus.PARTIAL }
}

data class ImportedItem(
  val candidateId: String,
  val displayName: String,
  val kind: SourceKind,
  val status: SourceStatus,
  val detail: String? = null,
  val charsAdded: Int = 0,
  /** L'id della fonte salvata; null quando l'import e' fallito prima di salvarla. */
  val sourceId: String? = null,
  /**
   * Com'e' andata, in una forma che la schermata sa dire nella sua lingua. [detail] resta per quello
   * che arriva da un lettore (le pagine saltate di un PDF) e per la riga della fonte nel database;
   * quando c'e' questo, la schermata usa questo.
   */
  val summary: ImportSummary? = null,
)

/** Gli esiti che il wizard dice con le sue parole. */
sealed interface ImportSummary {
  /** Il file copiato all'ispezione non c'e' piu'. */
  data object FileUnavailable : ImportSummary

  /** Un `.sdocx` che non si e' riusciti a leggere. */
  data object Unreadable : ImportSummary

  /** Un tipo che nessun lettore capisce: resta allegato. */
  data object Unsupported : ImportSummary

  /** Una nota Samsung senza testo, registrazioni ne' inchiostro. */
  data object SamsungEmpty : ImportSummary

  /** Una nota Samsung nuova con delle pagine scritte a mano. */
  data class SamsungPages(val pages: Int) : ImportSummary

  /** Una nota Samsung aggiornata: registrazioni nuove, gia' presenti, pagine a mano. */
  data class SamsungUpdated(val newRecordings: Int, val kept: Int, val pages: Int) : ImportSummary
}

/**
 * Il passaggio fra "l'utente ha scelto dei file" e "la nota adesso contiene qualcosa".
 *
 * Diviso in due fasi apposta. [inspect] guarda i file e li copia in un posto nostro **subito**,
 * perche' l'URI di una condivisione vale finche' l'Activity che l'ha ricevuto e' viva, e il wizard
 * fa domande dopo. [importAll] scrive nel database quando le risposte ci sono.
 */
@Singleton
class ImportCoordinator @Inject constructor(
  @ApplicationContext private val context: Context,
  private val files: AppFiles,
  private val notes: NoteRepository,
  private val noteDao: NoteDao,
  private val sources: SourceDao,
  private val audioParts: AudioPartDao,
  private val sessions: SessionDao,
  private val extractors: TextExtractorRegistry,
  private val audioImporter: AudioImporter,
  private val archive: ArchiveRepository,
  private val handwriting: HandwritingPages,
) {

  /**
   * Copia, riconosce e cerca i doppioni. Non tocca il database delle note.
   *
   * Un elemento che non si riesce nemmeno a leggere non finisce nella lista: se ne accorge chi
   * chiama, confrontando le dimensioni, e l'utente vede solo quello su cui puo' decidere.
   */
  suspend fun inspect(uris: List<Uri>): List<ImportCandidate> = withContext(Dispatchers.IO) {
    uris.mapNotNull { uri ->
      runCatching { inspectOne(uri) }
        // Il fallimento resta silenzioso per l'utente, che vede «niente da importare», ma non per
        // chi legge il log: un URI che non si apre e' quasi sempre un permesso, e senza la riga qui
        // sotto si passa un pomeriggio a cercare il difetto nel posto sbagliato.
        .onFailure { android.util.Log.w("PampaNotes", "import: non riesco a leggere $uri", it) }
        .getOrNull()
    }
  }

  /** Il testo incollato o condiviso come testo: niente file, niente copia. */
  suspend fun inspectText(text: String, name: String): ImportCandidate = withContext(Dispatchers.Default) {
    val normalized = PlainTextExtractor.normalizeNewlines(text)
    val sha = Hashing.sha256(normalized)
    val duplicate = sources.findBySha(sha)
    ImportCandidate(
      id = Ids.newId(),
      uri = null,
      file = null,
      displayName = name,
      kind = SourceKind.CLIPBOARD,
      mime = "text/plain",
      sizeBytes = normalized.toByteArray().size.toLong(),
      sha256 = sha,
      inlineText = normalized,
      duplicateOfNoteId = duplicate?.noteId,
      duplicateOfNoteTitle = duplicate?.let { noteDao.get(it.noteId)?.title },
    )
  }

  private suspend fun inspectOne(uri: Uri): ImportCandidate {
    val displayName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "importato"
    val declaredMime = context.contentResolver.getType(uri)

    val temp = files.tempFile(prefix = "import", suffix = ".part")
    val (sha, size) = context.contentResolver.openInputStream(uri)?.use { input ->
      Hashing.copyHashing(input, temp)
    } ?: throw IllegalStateException("non si apre: $uri")

    val head = temp.inputStream().use { input ->
      val buffer = ByteArray(512)
      val read = input.read(buffer)
      if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }
    var kind = MimeSniffer.sniff(displayName, declaredMime, head)
    // Uno ZIP senza estensione parlante: si guarda dentro per distinguere Word da Samsung Notes.
    if (kind == SourceKind.SDOCX && !displayName.endsWith(".sdocx", ignoreCase = true) && !displayName.endsWith(".sdoc", ignoreCase = true)) {
      kind = runCatching { ArchiveSniffer.classify(zipEntryNames(temp)) }.getOrDefault(kind)
    }

    // Un doppione: un documento gia' importato, oppure — per l'audio, che non ha una fonte — una
    // parte con lo stesso contenuto, da cui si risale alla nota passando per la sua sessione.
    val duplicateNoteId = sources.findBySha(sha)?.noteId
      ?: audioParts.findBySha(sha)?.let { part -> sessions.get(part.sessionId)?.noteId }

    val sdocx = if (kind == SourceKind.SDOCX) runCatching { SdocxParser.parse(temp) }.getOrNull() else null
    // Una nota Samsung con lo stesso titolo di una gia' importata da Samsung Notes: quasi sempre
    // e' la stessa nota, aggiornata. Si propone di aggiornarla, non si decide. Si segna anche quale
    // dei suoi `.sdocx` e' la versione vecchia: e' lui solo che l'aggiornamento sostituisce.
    var updateOfSource: SourceEntity? = null
    val updateOf = sdocx?.title?.trim()?.takeIf { it.isNotEmpty() }?.let { title ->
      noteDao.byExactTitle(title).firstOrNull { note ->
        val noteSources = sources.byNote(note.id)
        updateOfSource = previousVersion(noteSources, title)
        noteSources.any { it.kind == SourceKind.SDOCX }
      }
    }

    // Per l'audio, durata e giorno della registrazione in una lettura sola. La data di modifica si
    // chiede adesso al provider: dopo, l'unico file che resta e' la copia nostra, che e' di oggi.
    val probe = if (kind == SourceKind.AUDIO) audioImporter.probe(temp) else null
    val lastModified = if (probe != null) queryLastModified(uri) else null
    val recordedOn = probe?.let { RecordingDate.resolve(it.metadataDate, displayName, lastModified) }
    val recordedAt = recordedOn?.let { RecordingDate.momentOf(it, probe?.metadataDate, lastModified) }

    return ImportCandidate(
      id = Ids.newId(),
      uri = uri,
      file = temp,
      displayName = displayName,
      kind = kind,
      mime = MimeSniffer.mimeFor(kind, declaredMime),
      sizeBytes = size,
      sha256 = sha,
      duplicateOfNoteId = duplicateNoteId,
      duplicateOfNoteTitle = duplicateNoteId?.let { noteDao.get(it)?.title },
      durationMs = probe?.durationMs ?: 0,
      // Si legge subito, all'ispezione: un file che non si capisce resta un allegato, e uno che si
      // capisce diventa una nota con un titolo, prima ancora di premere niente.
      sdocx = sdocx,
      updateOfNoteId = updateOf?.id,
      updateOfNoteTitle = updateOf?.title,
      updateOfSourceId = updateOf?.let { updateOfSource?.id },
      recordedOn = recordedOn,
      recordedAtMillis = recordedAt,
    )
  }

  /**
   * Il `.sdocx` fra [noteSources] di cui [title] e' la versione nuova ([SdocxUpdate.pick]), o null
   * se non se ne riconosce uno. Il titolo si rilegge dal file quando e' qui; quando sta solo sul
   * computer resta il nome del file.
   */
  private fun previousVersion(noteSources: List<SourceEntity>, title: String): SourceEntity? =
    SdocxUpdate.pick(noteSources, title) { source -> textOf(source)?.first ?: SdocxUpdate.titleFromName(source.originalName) }

  /** Titolo e testo di un `.sdocx` gia' importato, se il file e' su questo dispositivo. */
  private fun textOf(source: SourceEntity): Pair<String?, String>? =
    source.storedFileName?.let { files.sourceFile(it) }?.takeIf { it.exists() }?.let { SdocxParser.readText(it) }

  /**
   * Scrive davvero.
   *
   * @param audioPlacement dove vanno gli audio: nella sessione che c'e' gia' o in una nuova.
   */
  suspend fun importAll(
    candidates: List<ImportCandidate>,
    target: ImportTarget,
    audioPlacement: AudioPlacement = AudioPlacement.NewSession(),
    onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
  ): ImportOutcome = withContext(Dispatchers.IO) {
    val noteId = when (target) {
      is ImportTarget.ExistingNote -> target.noteId
      is ImportTarget.UpdateNote -> target.noteId
      is ImportTarget.NewNote -> notes.create(folderId = target.folderId, title = target.title).id
    }

    val results = mutableListOf<ImportedItem>()
    val audio = candidates.filter { it.isAudio }
    val samsung = candidates.filter { it.isSamsungNote }
    val documents = candidates.filterNot { it.isAudio || it.isSamsungNote }

    documents.forEachIndexed { index, candidate ->
      onProgress(index, candidates.size, candidate.displayName)
      results += importDocument(candidate, noteId)
    }

    // Una nota di Samsung Notes non e' un documento: e' testo *e* registrazioni insieme, e le
    // registrazioni vanno in una sessione loro, datata dal giorno in cui sono state fatte.
    samsung.forEachIndexed { index, candidate ->
      onProgress(documents.size + index, candidates.size, candidate.displayName)
      results += importSamsungNote(candidate, noteId, audioPlacement, replace = target is ImportTarget.UpdateNote)
    }

    if (audio.isNotEmpty()) {
      onProgress(documents.size + samsung.size, candidates.size, audio.first().displayName)
      results += audioImporter.importAll(audio, noteId, audioPlacement)
    }

    onProgress(candidates.size, candidates.size, "")
    applyDates(noteId, target, candidates, audioPlacement)
    ImportOutcome(noteId = noteId, imported = results)
  }

  /**
   * Le date della nota a import finito: quelle di dove e' stata scritta, non l'ora dell'import.
   *
   * Una nota nuova, o aggiornata da un `.sdocx` piu' nuovo, prende la nascita del `.sdocx` e come
   * ultima modifica la piu' recente fra la sua e l'ultima registrazione ([NoteDates.choose]); una
   * nota fatta solo di audio, i momenti delle registrazioni. Una nota che c'era gia' e riceve
   * qualcosa invece e' una nota cambiata adesso, e un PDF o un testo non portano una data di cui
   * fidarsi: in quei casi resta «adesso», come prima.
   */
  private suspend fun applyDates(noteId: String, target: ImportTarget, candidates: List<ImportCandidate>, placement: AudioPlacement) {
    val note = noteDao.get(noteId) ?: return
    val datable = target !is ImportTarget.ExistingNote && candidates.isNotEmpty() && candidates.all { it.isAudio || it.isSamsungNote }
    if (!datable) {
      notes.touch(noteId)
      return
    }
    val now = System.currentTimeMillis()
    val samsung = candidates.mapNotNull { candidate -> candidate.sdocx?.takeIf { candidate.isSamsungNote } }
    // Un giorno scelto a mano nel wizard vale anche qui: chi l'ha scelto ha corretto la registrazione.
    val chosenDay = (placement as? AudioPlacement.NewSession)?.date?.let { Dates.parseOrNull(it) }
    val recordings = samsung.flatMap { doc -> doc.recordings.mapNotNull { it.createdAtMillis } } +
      candidates.filter { it.isAudio }.mapNotNull { audio -> chosenDay?.let { RecordingDate.noonOf(it) } ?: audio.recordedAtMillis }
    val choice = NoteDates.choose(
      created = samsung.mapNotNull { it.dates?.createdAtMillis }.minOrNull(),
      modified = samsung.mapNotNull { it.dates?.modifiedAtMillis }.maxOrNull(),
      recordings = recordings,
      fallbackCreated = note.createdAt,
      fallbackUpdated = now,
      now = now,
    )
    noteDao.setDates(noteId, choice.createdAt, choice.updatedAt)
  }

  private suspend fun importDocument(candidate: ImportCandidate, noteId: String): ImportedItem {
    val sourceId = Ids.newId()

    // Il testo incollato non ha un file da conservare: e' gia' tutto nel corpo della nota.
    if (candidate.inlineText != null) {
      notes.appendBody(noteId, candidate.inlineText)
      val source = SourceEntity(
        id = sourceId,
        noteId = noteId,
        kind = candidate.kind,
        originalName = candidate.displayName,
        mime = candidate.mime,
        sizeBytes = candidate.sizeBytes,
        sha256 = candidate.sha256,
        storedFileName = null,
        extractedChars = candidate.inlineText.length,
        status = SourceStatus.OK,
        importedAt = System.currentTimeMillis(),
      )
      sources.upsert(source)
      return ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.OK, charsAdded = candidate.inlineText.length, sourceId = sourceId)
    }

    val temp = candidate.file ?: return ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.FAILED, summary = ImportSummary.FileUnavailable)

    // L'originale si conserva: per riestrarlo dopo, e per metterlo nel bundle di export.
    val storedName = files.newSourceName(sourceId, candidate.displayName, candidate.mime)
    val stored = files.sourceFile(storedName)
    temp.copyTo(stored, overwrite = true)
    temp.delete()

    val extracted = extractors.forKind(candidate.kind)?.extract(stored, candidate.displayName)
    val text = extracted?.text.orEmpty()
    if (text.isNotBlank()) {
      notes.appendBody(noteId, formatForNote(candidate, text))
    }

    val status = extracted?.status ?: SourceStatus.PARTIAL
    // Il `detail` resta scritto nella riga, che viaggia col sync: e' un testo breve, e per chi lo
    // legge nell'elenco delle fonti. Il wizard invece dice l'esito con le sue parole ([summary]).
    val detail = extracted?.detail ?: if (extracted == null) "Tipo non ancora supportato: il file resta allegato" else null
    val summary = if (extracted == null) ImportSummary.Unsupported else null

    sources.upsert(
      SourceEntity(
        id = sourceId,
        noteId = noteId,
        kind = candidate.kind,
        originalName = candidate.displayName,
        mime = candidate.mime,
        sizeBytes = candidate.sizeBytes,
        sha256 = candidate.sha256,
        storedFileName = storedName,
        extractedChars = text.length,
        status = status,
        detail = detail,
        importedAt = System.currentTimeMillis(),
      ),
    )
    return ImportedItem(candidate.id, candidate.displayName, candidate.kind, status, detail, text.length, sourceId, summary)
  }

  /**
   * Una nota di Samsung Notes: il testo nel corpo, le registrazioni in una sessione, l'archivio
   * conservato come fonte.
   *
   * Il testo entra senza l'intestazione con il nome del file: in una nota nuova *e'* la nota, e un
   * titolo «File samsung notes di test.sdocx» sopra gli appunti di Fichte e' rumore. L'intestazione
   * torna solo quando la nota aveva gia' un corpo, dove serve a dire dove finisce l'uno e comincia
   * l'altro.
   *
   * Le registrazioni si tirano fuori dallo ZIP una alla volta in un file temporaneo e passano per
   * [AudioImporter] come qualsiasi altro audio, con il nome che Samsung Notes gli dava — «Voce
   * 001» — e nell'ordine in cui sono state fatte. Una sessione per giorno di registrazione, datata
   * col giorno della lezione e non con quello dell'import.
   */
  private suspend fun importSamsungNote(
    candidate: ImportCandidate,
    noteId: String,
    placement: AudioPlacement,
    replace: Boolean = false,
  ): List<ImportedItem> {
    val doc = candidate.sdocx ?: return listOf(ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.FAILED, summary = ImportSummary.Unreadable))
    val temp = candidate.file ?: return listOf(ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.FAILED, summary = ImportSummary.FileUnavailable))
    val results = mutableListOf<ImportedItem>()

    // 1. L'archivio originale, per sempre. La riga si scrive **subito**, prima di tutto il resto:
    //    le pagine a mano la citano, e se l'import muore a meta' la nota ha comunque il suo
    //    originale da cui rifarle, invece di un file su disco che nessuna riga cita e che la
    //    pulizia porterebbe via. Lo stato vero si scrive alla fine.
    val sourceId = Ids.newId()
    val storedName = files.newSourceName(sourceId, candidate.displayName, candidate.mime)
    val stored = files.sourceFile(storedName)
    temp.copyTo(stored, overwrite = true)
    temp.delete()
    // In un aggiornamento se ne va **un** `.sdocx`: quello riconosciuto all'ispezione, o — se nel
    // frattempo non c'e' piu' — quello con lo stesso titolo. Gli altri della nota, arrivati con
    // «Importa qui», restano con le loro pagine e il loro testo; se non se ne riconosce nessuno non
    // se ne va niente, e il testo nuovo si aggiunge. Si guarda prima di scrivere la riga nuova, che
    // sarebbe altrimenti la piu' recente fra le candidate.
    val noteSources = if (replace) sources.byNote(noteId) else emptyList()
    val old: SourceEntity? = if (replace) {
      noteSources.firstOrNull { it.id == candidate.updateOfSourceId && it.kind == SourceKind.SDOCX }
        ?: previousVersion(noteSources, doc.title ?: candidate.displayName.substringBeforeLast('.'))
    } else {
      null
    }
    val oldText = old?.let { textOf(it) }
    val body = doc.body.trim()
    val entity = SourceEntity(
      id = sourceId,
      noteId = noteId,
      kind = SourceKind.SDOCX,
      originalName = candidate.displayName,
      mime = candidate.mime,
      sizeBytes = candidate.sizeBytes,
      sha256 = candidate.sha256,
      storedFileName = storedName,
      extractedChars = body.length,
      status = SourceStatus.PARTIAL,
      importedAt = System.currentTimeMillis(),
    )
    sources.upsert(entity)

    // 2. Il testo. In un aggiornamento si sostituisce, non si accoda: gli appunti si prendono in
    //    Samsung Notes, e la versione nuova del file *e'* la nota — tutta, se quel `.sdocx` era
    //    l'unico a darle del testo; altrimenti solo il pezzo che aveva portato ([SdocxUpdate.mergeBody]).
    if (replace) {
      val current = noteDao.get(noteId)?.body.orEmpty()
      val onlySource = SdocxUpdate.onlyTextSource(noteSources, old?.id.orEmpty())
      SdocxUpdate.mergeBody(current, oldText?.second, oldText?.first ?: doc.title, body, onlySource)
        ?.let { notes.setBody(noteId, it) }
    } else if (body.isNotEmpty()) {
      val hadBody = !noteDao.get(noteId)?.body.isNullOrBlank()
      notes.appendBody(noteId, if (hadBody) "## ${doc.title ?: candidate.displayName}\n\n$body" else body)
    }

    // 3. Le registrazioni. In un aggiornamento quelle che c'erano gia' — stessa impronta — restano
    //    con le loro trascrizioni; entrano solo le nuove, una sessione per giorno di registrazione.
    val known: Set<String> = if (replace) sessions.byNote(noteId).flatMap { it.parts }.map { it.sha256 }.toSet() else emptySet()
    var alreadyThere = 0
    val extracted = mutableListOf<ImportCandidate>()
    java.util.zip.ZipFile(stored).use { zip ->
      doc.recordings.forEachIndexed { index, recording ->
        val entry = zip.getEntry(recording.entryName) ?: return@forEachIndexed
        val extension = recording.entryName.substringAfterLast('.', "m4a")
        val audioTemp = files.tempFile(prefix = "sdocx", suffix = ".$extension")
        val (sha, size) = zip.getInputStream(entry).use { input -> Hashing.copyHashing(input, audioTemp) }
        if (sha in known) {
          audioTemp.delete()
          alreadyThere++
          return@forEachIndexed
        }
        // Un nome che ordina come Samsung Notes: «Voce 001» viene prima di «Voce 002» anche
        // quando i file dentro lo ZIP si chiamano al contrario.
        val name = recording.title ?: "Registrazione ${"%02d".format(index + 1)}"
        extracted += ImportCandidate(
          id = Ids.newId(),
          uri = null,
          file = audioTemp,
          displayName = "$name.$extension",
          kind = SourceKind.AUDIO,
          mime = MimeSniffer.mimeFor(SourceKind.AUDIO, null),
          sizeBytes = size,
          sha256 = sha,
          durationMs = audioImporter.probeDuration(audioTemp).takeIf { it > 0 } ?: recording.durationMs,
          // Il giorno in cui e' stata fatta, dal record di `mediaInfo.dat`: e' quello che divide le
          // registrazioni in lezioni ([RecordingDate.groupByDay]).
          recordedOn = recording.createdAtMillis
            ?.let { Dates.parseOrNull(Dates.fromMillis(it)) }
            ?.let { RecordedOn(it, RecordingDateSource.METADATA) },
        )
      }
    }

    if (extracted.isNotEmpty() && replace) {
      // Le lezioni nuove, una per giorno: e' cosi' che si leggono in Samsung Notes. Un giorno che
      // la nota ha gia' e' la stessa lezione, ripresa dopo l'ultima condivisione: la registrazione
      // va in coda a quella sessione, non in una seconda con la stessa data.
      val existing = sessions.byNote(noteId).map { it.session }
      RecordingDate.groupByDay(extracted, { it.recordedOn?.date }).forEach { (day, group) ->
        val sameDay = existing.filter { it.date == day.toString() }.maxByOrNull { it.position }
        val where = sameDay?.let { AudioPlacement.Append(it.id) } ?: AudioPlacement.NewSession(date = day.toString())
        results += audioImporter.importAll(group, noteId, where)
      }
    } else if (extracted.isNotEmpty()) {
      // Una nota nuova: senza un giorno scelto a mano, una sessione per giorno di registrazione
      // (e' [AudioImporter] a dividerle, dai [ImportCandidate.recordedOn] scritti qui sopra). Prima
      // finivano tutte nel giorno della prima, e le lezioni di una settimana erano una lezione sola.
      // Un giorno scelto nel wizard vale per tutte, come per qualunque audio.
      results += audioImporter.importAll(extracted, noteId, placement)
    }

    // 4. L'inchiostro: le pagine scritte a mano diventano immagini attaccate alla nota. Prima di
    //    togliere le vecchie: se questo passo si ferma, la nota ha ancora le pagine di prima.
    val pages = handwriting.derive(entity, stored, doc.title ?: candidate.displayName.substringBeforeLast('.'))

    // 5. La fonte vecchia, in un aggiornamento, se ne va: la riga, il file qui, e il blob sul
    //    computer di casa se nessun'altra fonte lo cita. Il sync porta il tombstone agli altri
    //    dispositivi, che mettono il loro file in quarantena. Le richieste al computer si fanno
    //    tutte alla fine, quando le righe sono gia' a posto: sono rete, e possono non rispondere.
    val forgetOnComputer = mutableListOf<String>()
    old?.takeIf { it.id != sourceId }?.let { old ->
      // Le pagine scritte a mano del file vecchio se ne vanno con lui: quelle del nuovo le
      // contengono gia', e magari piu' lunghe. Quelle degli altri `.sdocx` della nota restano.
      forgetOnComputer += handwriting.forgetPages(old.id)
      old.storedFileName?.let { files.sourceFile(it).delete() }
      sources.delete(old.id)
      forgetOnComputer += old.sha256
    }
    handwriting.forgetRemotely(forgetOnComputer)

    val status = if (body.isEmpty() && extracted.isEmpty() && alreadyThere == 0 && pages == 0) SourceStatus.PARTIAL else SourceStatus.OK
    val detail = when {
      status == SourceStatus.PARTIAL -> "Nella nota non c'era testo battuto, ne' registrazioni, ne' inchiostro da disegnare"
      replace -> "Aggiornata: ${extracted.size} registrazioni nuove, $alreadyThere gia' presenti" + if (pages > 0) ", ${pagesLabel(pages)}" else ""
      pages == 1 -> "1 pagina scritta a mano, attaccata alla nota come immagine"
      pages > 1 -> "$pages pagine scritte a mano, attaccate alla nota come immagini"
      else -> null
    }
    val summary = when {
      status == SourceStatus.PARTIAL -> ImportSummary.SamsungEmpty
      replace -> ImportSummary.SamsungUpdated(extracted.size, alreadyThere, pages)
      pages > 0 -> ImportSummary.SamsungPages(pages)
      else -> null
    }
    sources.upsert(entity.copy(status = status, detail = detail))
    results.add(0, ImportedItem(candidate.id, doc.title ?: candidate.displayName, SourceKind.SDOCX, status, detail, body.length, sourceId, summary))
    return results
  }

  private fun pagesLabel(pages: Int): String = if (pages == 1) "1 pagina scritta a mano" else "$pages pagine scritte a mano"

  /**
   * Il testo estratto, con sopra da dove viene.
   *
   * Un intestazione invece di niente perche' una nota che mette insieme tre PDF senza dire dove
   * finisce l'uno e comincia l'altro e' esattamente il pasticcio che l'app dovrebbe evitare.
   */
  private fun formatForNote(candidate: ImportCandidate, text: String): String =
    "## ${candidate.displayName}\n\n${text.trim()}"

  private fun queryDisplayName(uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }
  }.getOrNull()

  /**
   * Quando il file e' stato modificato l'ultima volta, secondo chi lo condivide.
   *
   * Non c'e' una colonna che tutti i provider abbiano: i documenti (SAF) hanno `last_modified` in
   * millisecondi, MediaStore `date_modified` in secondi, un file aperto per percorso ha il file.
   * Si prova in quest'ordine, e una colonna che il provider non conosce e' un'eccezione da
   * inghiottire, non un import fallito.
   */
  private fun queryLastModified(uri: Uri): Long? {
    if (uri.scheme == "file") return uri.path?.let { File(it).lastModified() }?.takeIf { it > 0 }
    val columns = listOf(
      android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED to 1L,
      android.provider.MediaStore.MediaColumns.DATE_MODIFIED to 1000L,
    )
    columns.forEach { (column, toMillis) ->
      runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 }?.times(toMillis) else null
        }
      }.getOrNull()?.let { return it }
    }
    return null
  }

  private fun zipEntryNames(file: File): List<String> =
    java.util.zip.ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
}

/** I lettori disponibili, per tipo. */
@Singleton
class TextExtractorRegistry @Inject constructor(
  plain: PlainTextExtractor,
  pdf: PdfTextExtractor,
  docx: DocxTextExtractor,
) {
  private val byKind: Map<SourceKind, TextExtractor> = mapOf(
    SourceKind.TEXT to plain,
    SourceKind.MARKDOWN to plain,
    SourceKind.CLIPBOARD to plain,
    SourceKind.SHARE to plain,
    SourceKind.PDF to pdf,
    SourceKind.DOCX to docx,
  )

  fun forKind(kind: SourceKind): TextExtractor? = byKind[kind]
}
