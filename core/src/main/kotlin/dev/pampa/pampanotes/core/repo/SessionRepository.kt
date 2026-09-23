package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SegmentDao
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SessionWithParts
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.model.wordCount
import dev.pampa.pampanotes.core.transcription.SessionAssembler
import dev.pampa.pampanotes.core.transcription.SessionSegment
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import dev.pampa.pampanotes.core.transcription.TranscriptPlacement
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Le sessioni e le loro parti: come si raggruppano, si riordinano, si separano e si uniscono.
 *
 * Una regola sola, e tutto il resto ne discende: **i segmenti appartengono alla parte, non alla
 * trascrizione**. La trascrizione di una sessione e' quello che si ottiene mettendo in fila i
 * segmenti delle parti che ha in quel momento, e si rifa' da capo dopo ogni cambiamento con
 * [rebuildRaw]. Per questo spostare una parte da una sessione all'altra non costa una richiesta di
 * rete: i tempi dentro il file non sono cambiati, e' cambiato solo chi viene prima.
 */
@Singleton
class SessionRepository @Inject constructor(
  private val sessions: SessionDao,
  private val parts: AudioPartDao,
  private val transcripts: TranscriptDao,
  private val segments: SegmentDao,
  private val notes: NoteDao,
  private val files: AppFiles,
  private val db: PampaDatabase,
) {

  fun observe(sessionId: String): Flow<SessionWithParts?> = sessions.observe(sessionId)

  fun observeByNote(noteId: String): Flow<List<SessionWithParts>> = sessions.observeByNote(noteId)

  suspend fun byNote(noteId: String): List<SessionWithParts> = sessions.byNote(noteId)

  suspend fun get(sessionId: String): SessionEntity? = sessions.get(sessionId)

  suspend fun withParts(sessionId: String): SessionWithParts? = sessions.getWithParts(sessionId)

  suspend fun transcriptsOf(sessionId: String): List<TranscriptEntity> = transcripts.bySession(sessionId)

  fun observeSegments(transcriptId: String): Flow<List<SegmentEntity>> = segments.observe(transcriptId)

  fun observeTranscripts(sessionId: String): Flow<List<TranscriptEntity>> = transcripts.observeBySession(sessionId)

  suspend fun rename(sessionId: String, title: String, date: String) {
    val clean = Dates.parseOrNull(date)?.let { date } ?: sessions.get(sessionId)?.date ?: Dates.today()
    sessions.rename(sessionId, title.trim(), clean, System.currentTimeMillis())
    touchNote(sessionId)
  }

  /** Quale trascrizione si mostra e si esporta: la grezza, o una delle sue raffinate. */
  suspend fun setActiveTranscript(sessionId: String, transcriptId: String?) {
    sessions.setActiveTranscript(sessionId, transcriptId, System.currentTimeMillis())
    touchNote(sessionId)
  }

  suspend fun createSession(noteId: String, date: String = Dates.today(), title: String = ""): SessionEntity {
    val now = System.currentTimeMillis()
    val session = SessionEntity(
      id = Ids.newId(),
      noteId = noteId,
      title = title,
      date = date,
      position = sessions.nextPosition(noteId),
      createdAt = now,
      updatedAt = now,
    )
    sessions.upsert(session)
    notes.touch(noteId, now)
    return session
  }

  // ---------------------------------------------------------------------------------------------
  // Le parti
  // ---------------------------------------------------------------------------------------------

  /**
   * Sposta una parte di un posto in su o in giu' dentro la sua sessione.
   *
   * @return true se si e' mossa: in cima non si sale, in fondo non si scende, e dirlo permette alla
   *   UI di non far vibrare un tasto che non ha fatto niente.
   */
  suspend fun movePart(partId: String, delta: Int): Boolean {
    if (delta == 0) return false
    val part = parts.get(partId) ?: return false
    val ordered = parts.bySession(part.sessionId)
    val from = ordered.indexOfFirst { it.id == partId }
    val to = from + delta
    if (from < 0 || to !in ordered.indices) return false

    val reordered = ordered.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    renumber(reordered)
    rebuildRaw(part.sessionId)
    touchNote(part.sessionId)
    return true
  }

  /**
   * Porta una parte in un'altra sessione, in fondo.
   *
   * Tutte e due le sessioni si ricompongono: quella che la perde perche' il suo testo si accorcia,
   * quella che la prende perche' il suo si allunga e i tempi di tutto quello che viene dopo si
   * spostano in avanti.
   */
  suspend fun movePartTo(partId: String, targetSessionId: String) {
    val part = parts.get(partId) ?: return
    if (part.sessionId == targetSessionId) return
    val source = part.sessionId
    parts.move(partId, targetSessionId, parts.nextPosition(targetSessionId))
    renumber(parts.bySession(source))
    renumber(parts.bySession(targetSessionId))
    // Prima chi riceve, poi chi perde: e' l'ordine che salva i segmenti. Ricomporre prima la
    // sessione svuotata cancellerebbe la sua trascrizione, e la cancellazione si porta dietro i
    // segmenti — compresi quelli della parte appena arrivata dall'altra parte.
    rebuildRaw(targetSessionId)
    rebuildRaw(source)
    deleteIfEmpty(source)
    touchNote(targetSessionId)
  }

  /**
   * Separa: questa parte e quelle dopo di lei diventano una sessione nuova.
   *
   * Il caso vero e' uno solo, e capita spesso: tre file importati insieme, di cui l'ultimo e' di un
   * altro giorno. La sessione nuova nasce con la stessa data della vecchia, che e' quasi sempre da
   * correggere — per questo la UI apre subito la rinomina.
   *
   * @return la sessione nuova, o null se non c'era niente da separare.
   */
  suspend fun splitAt(partId: String): SessionEntity? {
    val part = parts.get(partId) ?: return null
    val source = sessions.get(part.sessionId) ?: return null
    val ordered = parts.bySession(part.sessionId)
    val index = ordered.indexOfFirst { it.id == partId }
    // Separare dalla prima parte vorrebbe dire spostare tutto e lasciare un guscio vuoto.
    if (index <= 0) return null

    val moving = ordered.drop(index)
    val created = insertAfter(source, title = "", date = source.date)
    moving.forEachIndexed { position, item -> parts.move(item.id, created.id, position) }
    renumber(parts.bySession(source.id))
    rebuildRaw(created.id)
    rebuildRaw(source.id)
    touchNote(source.id)
    return created
  }

  /**
   * Unisce questa sessione a quella che la precede: le sue parti si accodano, lei sparisce.
   *
   * L'operazione inversa di [splitAt], per quando le registrazioni sono state importate una alla
   * volta e sono finite in sessioni diverse pur essendo la stessa lezione.
   *
   * @return la sessione che le ha assorbite, o null se questa e' la prima.
   */
  suspend fun mergeIntoPrevious(sessionId: String): SessionEntity? {
    val session = sessions.get(sessionId) ?: return null
    val previous = sessions.previous(session.noteId, session.position) ?: return null

    var position = parts.nextPosition(previous.id)
    parts.bySession(sessionId).forEach { part ->
      parts.move(part.id, previous.id, position)
      position++
    }
    // Prima la ricomposizione, poi la cancellazione, e non e' un dettaglio: cancellare la sessione
    // porta via le sue trascrizioni, e con loro i segmenti delle parti appena spostate. Ricomporre
    // adesso li riadotta sotto la trascrizione della sessione che resta.
    rebuildRaw(previous.id)
    sessions.delete(sessionId)
    renumberSessions(session.noteId)
    touchNote(previous.id)
    return previous
  }

  /** Cancella una parte, il suo file e i suoi segmenti; la sessione si ricompone senza di lei. */
  suspend fun deletePart(partId: String) {
    val part = parts.get(partId) ?: return
    val sessionId = part.sessionId
    segments.deleteByPart(partId)
    parts.delete(partId)
    runCatching { files.audioFile(part.fileName).delete() }
    renumber(parts.bySession(sessionId))
    rebuildRaw(sessionId)
    deleteIfEmpty(sessionId)
    touchNote(sessionId)
  }

  suspend fun deleteSession(sessionId: String) {
    val session = sessions.get(sessionId) ?: return
    parts.bySession(sessionId).forEach { part ->
      segments.deleteByPart(part.id)
      runCatching { files.audioFile(part.fileName).delete() }
    }
    sessions.delete(sessionId)
    renumberSessions(session.noteId)
    notes.touch(session.noteId, System.currentTimeMillis())
  }

  // ---------------------------------------------------------------------------------------------
  // La ricomposizione
  // ---------------------------------------------------------------------------------------------

  /**
   * Rifa' la trascrizione grezza di una sessione dai segmenti delle parti che ha adesso.
   *
   * Tre casi:
   *
   *  * **nessun segmento**: la trascrizione grezza sparisce, e con lei le raffinate che ne venivano.
   *    Una sessione rimasta senza parole non deve continuare a mostrarne.
   *  * **c'era gia' una grezza**: si aggiorna sul posto, cosi' le raffinate che la citano restano
   *    attaccate a un genitore che esiste. Se pero' il testo e' cambiato, le raffinate se ne vanno:
   *    un testo ripulito che descrive una versione precedente e' un testo di cui nessuno sa piu' da
   *    dove viene.
   *  * **non c'era**: ne nasce una, perche' i segmenti sono arrivati da un'altra sessione. Provider,
   *    modello e lingua li si eredita da dove venivano.
   */
  suspend fun rebuildRaw(sessionId: String) {
    // In una transazione sola: fra la cancellazione dei segmenti e la loro riscrittura la
    // trascrizione e' vuota, e un giro di sync (o il lettore) che la legge li' in mezzo manderebbe
    // agli altri dispositivi una lezione senza tempi. Chi la rilegge vede prima o dopo, mai durante.
    db.withTransaction { rebuildRawNow(sessionId) }
  }

  private suspend fun rebuildRawNow(sessionId: String) {
    val ordered = parts.bySession(sessionId)
    val existing = transcripts.rawForSession(sessionId)
    val stored = if (ordered.isEmpty()) emptyList() else segments.byParts(ordered.map { it.id })

    if (stored.isEmpty()) {
      existing?.let { dropTranscript(sessionId, it) }
      return
    }

    val assembled = SessionAssembler.reassemble(
      parts = ordered.map { SessionAssembler.Part(it.id, it.durationMs) },
      segments = stored.map(::toSessionSegment),
    )

    val target = existing ?: inheritedTranscript(sessionId, stored, assembled.text)
    val changed = existing == null || existing.text != assembled.text

    if (existing == null) {
      transcripts.upsert(target)
    } else if (changed) {
      transcripts.updateText(target.id, assembled.text, assembled.text.wordCount())
      transcripts.deleteChildren(target.id)
    }

    // I segmenti si riscrivono comunque: anche a testo uguale i tempi di sessione possono essere
    // cambiati, ed e' su quelli che il lettore salta.
    segments.deleteByTranscript(target.id)
    ordered.forEach { part -> segments.deleteByPart(part.id) }
    segments.insertAll(
      assembled.segments.map { segment ->
        SegmentEntity(
          transcriptId = target.id,
          partId = segment.partId,
          indexInPart = segment.indexInPart,
          partStartMs = segment.partStartMs,
          partEndMs = segment.partEndMs,
          sessionStartMs = segment.sessionStartMs,
          sessionEndMs = segment.sessionEndMs,
          text = segment.text,
          noSpeechProb = segment.noSpeechProb,
          avgLogProb = segment.avgLogProb,
          wordsJson = segment.wordsEncoded,
          wordsEstimated = segment.wordsEstimated,
        )
      },
    )

    val session = sessions.get(sessionId) ?: return
    val activeIsGone = session.activeTranscriptId == null ||
      (changed && session.activeTranscriptId != target.id)
    if (activeIsGone) sessions.setActiveTranscript(sessionId, target.id, System.currentTimeMillis())
  }

  /**
   * Salva il risultato di una trascrizione, parte per parte, nelle sessioni in cui le parti stanno
   * **adesso** (vedi [TranscriptPlacement]).
   *
   * Il lavoro e' partito con una fotografia delle parti ([partIds]) e nel frattempo la sessione puo'
   * essere cambiata. Per ogni sessione che ha ancora almeno una di quelle parti: una grezza nuova,
   * con provider, modello e lingua del risultato, che prende i segmenti nuovi delle parti del lavoro
   * e **riadotta** quelli delle altre parti della sessione (arrivate nel frattempo, o gia' trascritte
   * altrove); la grezza di prima se ne va con le sue raffinate, come ha sempre fatto una
   * ritrascrizione; poi [rebuildRaw] rifa' testo e tempi dall'ordine di adesso. Una parte cancellata
   * nel frattempo non riceve niente: i suoi segmenti sarebbero orfani, e prima facevano fallire il
   * salvataggio.
   *
   * Tutto in una transazione: fra la cancellazione della grezza vecchia e l'ultimo segmento della
   * nuova, un processo ucciso lasciava una sessione senza trascrizione, e il sync la portava cosi'
   * anche sugli altri dispositivi.
   *
   * @return null se nessuna delle parti esiste piu' (la sessione e' stata cancellata mentre si
   *   trascriveva): non c'e' niente da salvare, e non e' un errore.
   */
  suspend fun saveTranscription(
    jobSessionId: String,
    partIds: List<String>,
    result: SessionTranscript,
  ): SavedTranscription? = db.withTransaction {
    val alive = partIds.mapNotNull { parts.get(it) }.associate { it.id to it.sessionId }
    val targets = TranscriptPlacement.plan(jobSessionId, partIds, alive)
    if (targets.isEmpty()) return@withTransaction null

    val fresh = result.segments.groupBy { it.partId }
    val now = System.currentTimeMillis()
    targets.forEach { target ->
      val written = target.partIds.toSet()
      val inSession = parts.bySession(target.sessionId)
      val others = inSession.filterNot { it.id in written }
      // Le parole delle altre parti, da qualunque trascrizione vengano: vanno sotto la grezza nuova
      // prima che quella vecchia se ne vada, o la cascata le porterebbe via con lei.
      val kept = if (others.isEmpty()) emptyList() else segments.byParts(others.map { it.id })
      val previous = transcripts.rawForSession(target.sessionId)

      val transcript = TranscriptEntity(
        id = Ids.newId(),
        sessionId = target.sessionId,
        kind = TranscriptKind.RAW,
        provider = result.provider,
        model = result.model,
        language = result.language,
        // Il testo lo compone [rebuildRaw] qui sotto, dall'ordine delle parti di adesso.
        text = "",
        wordCount = 0,
        createdAt = now,
      )
      inSession.forEach { segments.deleteByPart(it.id) }
      transcripts.upsert(transcript)
      segments.insertAll(
        kept.map { it.copy(id = 0, transcriptId = transcript.id) } +
          target.partIds.flatMap { partId -> fresh[partId].orEmpty() }.map { segment ->
            SegmentEntity(
              transcriptId = transcript.id,
              partId = segment.partId,
              indexInPart = segment.indexInPart,
              partStartMs = segment.partStartMs,
              partEndMs = segment.partEndMs,
              sessionStartMs = segment.sessionStartMs,
              sessionEndMs = segment.sessionEndMs,
              text = segment.text,
              noSpeechProb = segment.noSpeechProb,
              avgLogProb = segment.avgLogProb,
              wordsJson = segment.wordsEncoded,
              wordsEstimated = segment.wordsEstimated,
            )
          },
      )
      previous?.let {
        transcripts.deleteChildren(it.id)
        transcripts.delete(it.id)
      }
      sessions.setActiveTranscript(target.sessionId, transcript.id, now)
      rebuildRawNow(target.sessionId)
    }
    // La nota non si tocca: una trascrizione finita non e' una modifica di chi l'ha scritta, e la
    // data della nota e' quella vera (vedi `NoteDates`), non l'ora in cui il computer ha finito.

    // Quella da raccontare: la sessione del lavoro se c'e' ancora, altrimenti quella che ne ha preso
    // le parti (un'unione fatta mentre si trascriveva).
    val main = targets.last().sessionId
    SavedTranscription(
      sessionId = main,
      transcript = transcripts.rawForSession(main),
      sessionIds = targets.map { it.sessionId },
    )
  }

  /** Le parti che la trascrizione mostrata non copre: quelle importate dopo, o arrivate da altrove. */
  suspend fun untranscribedParts(sessionId: String): List<AudioPartEntity> {
    val ordered = parts.bySession(sessionId)
    if (ordered.isEmpty()) return emptyList()
    val covered = segments.byParts(ordered.map { it.id }).mapTo(mutableSetOf()) { it.partId }
    return ordered.filterNot { it.id in covered }
  }

  // ---------------------------------------------------------------------------------------------

  private fun toSessionSegment(entity: SegmentEntity) = SessionSegment(
    partId = entity.partId,
    indexInPart = entity.indexInPart,
    partStartMs = entity.partStartMs,
    partEndMs = entity.partEndMs,
    sessionStartMs = entity.sessionStartMs,
    sessionEndMs = entity.sessionEndMs,
    text = entity.text,
    noSpeechProb = entity.noSpeechProb,
    avgLogProb = entity.avgLogProb,
    // Le parole restano com'erano: i loro tempi sono relativi al segmento dentro la parte, e
    // ricomporre una sessione non cambia nulla di quel riferimento.
    wordsEncoded = entity.wordsJson,
    wordsEstimated = entity.wordsEstimated,
  )

  /** Una grezza nuova che eredita l'anagrafica dalle trascrizioni da cui i segmenti vengono. */
  private suspend fun inheritedTranscript(
    sessionId: String,
    stored: List<SegmentEntity>,
    text: String,
  ): TranscriptEntity {
    val origins = stored.map { it.transcriptId }.distinct().mapNotNull { transcripts.get(it) }
    return TranscriptEntity(
      id = Ids.newId(),
      sessionId = sessionId,
      kind = TranscriptKind.RAW,
      provider = origins.firstOrNull()?.provider.orEmpty(),
      // Due modelli diversi in una sessione sola sono un fatto, e va detto invece di scegliere.
      model = origins.map { it.model }.distinct().filter { it.isNotBlank() }.joinToString(" + "),
      language = origins.firstNotNullOfOrNull { it.language },
      text = text,
      wordCount = text.wordCount(),
      createdAt = System.currentTimeMillis(),
    )
  }

  private suspend fun dropTranscript(sessionId: String, transcript: TranscriptEntity) {
    transcripts.deleteChildren(transcript.id)
    transcripts.delete(transcript.id)
    sessions.setActiveTranscript(sessionId, null, System.currentTimeMillis())
  }

  /** Una sessione rimasta senza parti e senza parole non serve piu' a niente: se ne va da sola. */
  private suspend fun deleteIfEmpty(sessionId: String) {
    if (parts.countIn(sessionId) > 0) return
    val session = sessions.get(sessionId) ?: return
    sessions.delete(sessionId)
    renumberSessions(session.noteId)
  }

  private suspend fun renumber(ordered: List<AudioPartEntity>) {
    val now = System.currentTimeMillis()
    ordered.forEachIndexed { position, part ->
      if (part.position != position) parts.move(part.id, part.sessionId, position)
    }
    if (ordered.isNotEmpty()) sessions.get(ordered.first().sessionId)?.let { sessions.upsert(it.copy(updatedAt = now)) }
  }

  private suspend fun renumberSessions(noteId: String) {
    val now = System.currentTimeMillis()
    sessions.plainByNote(noteId).forEachIndexed { position, session ->
      if (session.position != position) sessions.setPosition(session.id, position, now)
    }
  }

  /** Infila una sessione subito dopo un'altra, facendo posto a quelle che seguono. */
  private suspend fun insertAfter(after: SessionEntity, title: String, date: String): SessionEntity {
    val now = System.currentTimeMillis()
    sessions.plainByNote(after.noteId)
      .filter { it.position > after.position }
      .sortedByDescending { it.position }
      .forEach { sessions.setPosition(it.id, it.position + 1, now) }

    val created = SessionEntity(
      id = Ids.newId(),
      noteId = after.noteId,
      title = title,
      date = date,
      position = after.position + 1,
      createdAt = now,
      updatedAt = now,
    )
    sessions.upsert(created)
    return created
  }

  private suspend fun touchNote(sessionId: String) {
    sessions.get(sessionId)?.let { notes.touch(it.noteId, System.currentTimeMillis()) }
  }
}

/**
 * Dove e' finito il risultato di una trascrizione: [sessionId] e' la sessione del lavoro, o quella
 * che ne ha preso le parti se nel frattempo e' sparita; [transcript] la sua grezza (null se le parti
 * rimaste li' erano mute); [sessionIds] tutte quelle che si sono ricomposte.
 */
data class SavedTranscription(
  val sessionId: String,
  val transcript: TranscriptEntity?,
  val sessionIds: List<String>,
)
