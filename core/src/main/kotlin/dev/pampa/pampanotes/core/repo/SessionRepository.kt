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
import dev.pampa.pampanotes.core.importing.SdocxRepair
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.model.wordCount
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import dev.pampa.pampanotes.core.transcription.TranscriptPlacement
import dev.pampa.pampanotes.core.transcription.VoiceNames
import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.alignment.CloudSavePlan
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Le sessioni e le loro parti: come si raggruppano, si riordinano, si separano e si uniscono.
 *
 * I tempi dentro una parte appartengono al file; parole e segmenti appartengono anche alla
 * specifica versione RAW. La trascrizione di una sessione e' quello che si ottiene mettendo in fila i
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

  /**
   * «Rinomina le voci»: dà un nome alla voce [key] di questa sessione, o lo toglie con un nome vuoto
   * (torna «Voce N»). E' una modifica dell'utente come il titolo: alza `updatedAt`, sale col sync e
   * vince per ultimo-che-scrive. Un nome uguale a quello che c'era non scrive niente.
   */
  suspend fun renameVoice(sessionId: String, key: String, name: String?) {
    val changed = db.withTransaction {
      val session = sessions.get(sessionId) ?: return@withTransaction false
      val updated = VoiceNames.rename(session.voiceNames, key, name)
      if (updated == session.voiceNames) return@withTransaction false
      sessions.setVoiceNames(sessionId, updated, System.currentTimeMillis())
      true
    }
    if (changed) touchNote(sessionId)
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
  suspend fun movePart(partId: String, delta: Int): Boolean = db.withTransaction {
    if (delta == 0) return@withTransaction false
    val part = parts.get(partId) ?: return@withTransaction false
    val ordered = parts.bySession(part.sessionId)
    val from = ordered.indexOfFirst { it.id == partId }
    val to = from + delta
    if (from < 0 || to !in ordered.indices) return@withTransaction false
    val before = captureTimelines(setOf(part.sessionId))
    val reordered = ordered.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    renumber(reordered)
    rebuildTimelines(before, setOf(part.sessionId))
    touchNote(part.sessionId)
    true
  }

  /**
   * Porta una parte in un'altra sessione, in fondo.
   *
   * Tutte e due le sessioni si ricompongono: quella che la perde perche' il suo testo si accorcia,
   * quella che la prende perche' il suo si allunga e i tempi di tutto quello che viene dopo si
   * spostano in avanti.
   */
  suspend fun movePartTo(partId: String, targetSessionId: String) = db.withTransaction {
    val part = parts.get(partId) ?: return@withTransaction
    if (part.sessionId == targetSessionId) return@withTransaction
    require(sessions.get(targetSessionId) != null) { "Missing destination session" }
    val source = part.sessionId
    val before = captureTimelines(setOf(source, targetSessionId))
    parts.move(partId, targetSessionId, parts.nextPosition(targetSessionId))
    carryVoiceNames(source, targetSessionId, listOf(partId))
    renumber(parts.bySession(source))
    renumber(parts.bySession(targetSessionId))
    rebuildTimelines(before, setOf(source, targetSessionId))
    before.sessions.forEach { notes.touch(it.noteId, System.currentTimeMillis()) }
    deleteIfEmpty(source)
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
  suspend fun splitAt(partId: String): SessionEntity? = db.withTransaction {
    val part = parts.get(partId) ?: return@withTransaction null
    val source = sessions.get(part.sessionId) ?: return@withTransaction null
    val ordered = parts.bySession(part.sessionId)
    val index = ordered.indexOfFirst { it.id == partId }
    if (index <= 0) return@withTransaction null
    val before = captureTimelines(setOf(source.id))
    val moving = ordered.drop(index)
    val created = insertAfter(source, title = "", date = source.date)
    moving.forEachIndexed { position, item -> parts.move(item.id, created.id, position) }
    carryVoiceNames(source.id, created.id, moving.map { it.id })
    renumber(parts.bySession(source.id))
    rebuildTimelines(before, setOf(source.id, created.id))
    touchNote(source.id)
    sessions.get(created.id)
  }

  /**
   * Unisce questa sessione a quella che la precede: le sue parti si accodano, lei sparisce.
   *
   * L'operazione inversa di [splitAt], per quando le registrazioni sono state importate una alla
   * volta e sono finite in sessioni diverse pur essendo la stessa lezione.
   *
   * @return la sessione che le ha assorbite, o null se questa e' la prima.
   */
  suspend fun mergeIntoPrevious(sessionId: String): SessionEntity? = db.withTransaction {
    val session = sessions.get(sessionId) ?: return@withTransaction null
    val previous = sessions.previous(session.noteId, session.position) ?: return@withTransaction null
    val before = captureTimelines(setOf(sessionId, previous.id))
    var position = parts.nextPosition(previous.id)
    val moving = parts.bySession(sessionId)
    moving.forEach { part -> parts.move(part.id, previous.id, position++) }
    carryVoiceNames(sessionId, previous.id, moving.map { it.id })
    rebuildTimelines(before, setOf(sessionId, previous.id), relocatedSessions = mapOf(sessionId to previous.id))
    // Every history and refinement is reparented before the source's FK cascade runs.
    sessions.delete(sessionId)
    renumberSessions(session.noteId)
    touchNote(previous.id)
    sessions.get(previous.id)
  }

  /** Cancella una parte, il suo file e i suoi segmenti; la sessione si ricompone senza di lei. */
  suspend fun deletePart(partId: String) {
    val name = db.withTransaction {
      val part = parts.get(partId) ?: return@withTransaction null
      val session = sessions.get(part.sessionId) ?: return@withTransaction null
      val before = captureTimelines(setOf(part.sessionId))
      parts.delete(partId)
      val kept = VoiceNames.forget(session.voiceNames, listOf(partId))
      if (kept != session.voiceNames) sessions.setVoiceNames(session.id, kept, System.currentTimeMillis())
      renumber(parts.bySession(session.id))
      rebuildTimelines(before, setOf(session.id))
      deleteIfEmpty(session.id)
      notes.touch(session.noteId, System.currentTimeMillis())
      part.fileName
    }
    name?.let { runCatching { files.audioFile(it).delete() } }
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
  // La riparazione
  // ---------------------------------------------------------------------------------------------

  /**
   * Applica il piano di [SdocxRepair]: nomi giusti alle parti, ogni parte nella sessione del giorno
   * in cui e' stata registrata, date delle sessioni. I segmenti seguono le parti come in ogni
   * spostamento, quindi non si ritrascrive niente.
   *
   * Tutto in una transazione, e senza toccare la nota: non e' una modifica di chi la scrive, e alzare
   * `updatedAt` la farebbe sembrare cambiata oggi — la riportava in cima alla home, e le date vere
   * ([dev.pampa.pampanotes.core.importing.RealDatesBackfill]) non l'avrebbero piu' riconosciuta.
   * Sessioni e grezze nuove hanno id deterministici, cosi' due dispositivi che riparano la stessa
   * nota scrivono le stesse righe.
   *
   * Le ricomposizioni sono due passate: nella prima ogni sessione toccata adotta i segmenti delle
   * parti che ha adesso senza cancellare niente; nella seconda si rifa' tutto come sempre. Con due
   * sessioni che si scambiano parti non esiste un ordine «prima chi riceve» che salvi tutti i
   * segmenti; cosi' nessuno li perde.
   */
  suspend fun applyRepair(noteId: String, plan: SdocxRepair.Plan) {
    if (plan.isEmpty) return
    db.withTransaction {
      val now = System.currentTimeMillis()
      val before = sessions.plainByNote(noteId)
      val timelineIds = (plan.layout.keys + plan.layout.values.flatten().mapNotNull { parts.get(it)?.sessionId }).toSet()
      val timelines = captureTimelines(timelineIds)
      val wasByDate = before.sortedBy { it.position }.zipWithNext().all { (a, b) -> a.date <= b.date }

      plan.newSessions.forEach { (id, date) ->
        if (sessions.get(id) == null) {
          sessions.upsert(
            SessionEntity(
              id = id,
              noteId = noteId,
              title = "",
              date = date,
              position = sessions.nextPosition(noteId),
              createdAt = now,
              updatedAt = now,
            ),
          )
        }
      }
      plan.renames.forEach { (partId, name) -> parts.rename(partId, name) }

      val losers = mutableSetOf<String>()
      plan.layout.forEach { (sessionId, partIds) ->
        partIds.forEachIndexed { position, partId ->
          val part = parts.get(partId) ?: return@forEachIndexed
          if (part.sessionId != sessionId) {
            losers += part.sessionId
            carryVoiceNames(part.sessionId, sessionId, listOf(partId))
          }
          if (part.sessionId != sessionId || part.position != position) parts.move(partId, sessionId, position)
        }
      }
      losers.filter { it !in plan.layout }.forEach { renumber(parts.bySession(it)) }

      val touched = (plan.layout.keys + losers).toList()
      rebuildTimelines(timelines, touched.toSet(), touched.associateWith { SdocxRepair.rawId(it) })
      losers.forEach { deleteIfEmpty(it) }

      plan.redates.forEach { (sessionId, date) ->
        sessions.get(sessionId)?.let { sessions.rename(sessionId, it.title, date, now) }
      }
      // Le sessioni erano in ordine di data (il caso normale): ci restano, con quelle nuove al loro
      // posto. Altrimenti qualcuno le ha messe in un altro ordine, e quelle nuove vanno in fondo.
      if (wasByDate) {
        sessions.plainByNote(noteId)
          .sortedWith(compareBy<SessionEntity> { it.date }.thenBy { it.position })
          .forEachIndexed { position, session -> if (session.position != position) sessions.setPosition(session.id, position, now) }
      } else {
        renumberSessions(noteId)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // La ricomposizione
  // ---------------------------------------------------------------------------------------------

  /** Rebase every RAW independently. Selection follows its own version or refinement parent. */
  suspend fun rebuildRaw(sessionId: String) = db.withTransaction { rebuildRawNow(sessionId) }

  private suspend fun rebuildRawNow(sessionId: String) {
    // Some callers already moved parts. Capture their actual RAW owners as well, but never
    // concatenate the unpartitioned byParts result into a single transcript.
    val ids = parts.bySession(sessionId).map { it.id }
    val owners = if (ids.isEmpty()) emptyList() else segments.byParts(ids).map { it.transcriptId }.distinct()
      .mapNotNull { transcripts.get(it)?.sessionId }
    val sessions = (owners + sessionId).toSet()
    rebuildTimelines(captureTimelines(sessions), sessions)
  }

  private suspend fun captureTimelines(ids: Set<String>): RawTimelineSnapshot {
    val rows = ids.mapNotNull { sessions.get(it) }
    val versions = rows.flatMap { transcripts.bySession(it.id) }
    return RawTimelineSnapshot(rows, rows.flatMap { parts.bySession(it.id) }, versions,
      versions.flatMap { segments.byTranscript(it.id) })
  }

  private suspend fun rebuildTimelines(before: RawTimelineSnapshot, ids: Set<String>, newRawIds: Map<String, String> = emptyMap(), relocatedSessions: Map<String, String> = emptyMap()) {
    val current = ids.mapNotNull { sessions.get(it) }
    val plan = RawTimelinePlan.build(before, current, current.flatMap { parts.bySession(it.id) }, System.currentTimeMillis(), newRawIds, relocatedSessions)
    val originalIds = before.transcripts.mapTo(HashSet()) { it.id }
    plan.transcripts.filter { it.id !in originalIds }.forEach { require(transcripts.get(it.id) == null) { "RAW identity conflict" } }
    // Upsert every destination first. Only then remove/reinsert each version's own segments.
    // No deleteByPart and no parent REPLACE: alternative histories remain separate.
    plan.transcripts.forEach { transcripts.upsert(it) }
    before.transcripts.forEach { segments.deleteByTranscript(it.id) }
    segments.insertAll(plan.segments)
    plan.active.forEach { (sessionId, id) ->
      if (sessions.get(sessionId)?.activeTranscriptId != id) sessions.setActiveTranscript(sessionId, id, System.currentTimeMillis())
    }
  }

  /**
   * Salva il risultato di una trascrizione, parte per parte, nelle sessioni in cui le parti stanno
   * **adesso** (vedi [TranscriptPlacement]).
   *
   * Il lavoro e' partito con una fotografia delle parti ([partIds]) e nel frattempo la sessione puo'
   * essere cambiata. Per ogni sessione che ha ancora almeno una di quelle parti: una grezza nuova,
   * con provider, modello e lingua del risultato, che prende i segmenti nuovi delle parti del lavoro
   * e **riadotta** quelli delle altre parti della sessione (arrivate nel frattempo, o gia' trascritte
   * altrove). La singola grezza precedente si sostituisce come prima; se ci sono cronologie
   * alternative si conservano, ciascuna con le sue parole. [rebuildRaw] rifa' testo e tempi
   * dall'ordine di adesso. Una parte cancellata
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
    /** Worker before-image, including ORIGINAL audio hashes even if rows moved/disappeared. */
    originalParts: List<AudioPartEntity> = emptyList(),
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
      // Only the selected version contributes retained words to the new result. Other RAW
      // histories must never be concatenated or destroyed by a partial in-flight result.
      val selected = RawTranscripts.forSelection(transcripts.bySession(target.sessionId), sessions.get(target.sessionId)?.activeTranscriptId)
      val otherIds = others.mapTo(HashSet()) { it.id }
      val kept = selected?.let { segments.byTranscript(it.id).filter { row -> row.partId in otherIds } }.orEmpty()
      // Tutte le grezze di prima, non solo la piu' recente: due dispositivi che l'hanno trascritta
      // insieme ne lasciavano due, e la schermata mostrava la piu' vecchia (vedi [RawTranscripts]).
      val previous = transcripts.bySession(target.sessionId).filter { it.kind == TranscriptKind.RAW }

      var transcript = TranscriptEntity(
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
      val preserveSources = CloudSources.cloud(result.provider) || previous.any { it.cloudSourceJson != null }
      if (CloudSources.cloud(result.provider)) {
        val composed = CloudSavePlan.build(transcript, partIds, result, originalParts, inSession, written, selected, kept)
        transcript = transcript.copy(text = composed.first, wordCount = composed.first.wordCount(), cloudSourceJson = CloudSources.encode(composed.second))
      }
      if (!preserveSources && previous.size <= 1) inSession.forEach { segments.deleteByPart(it.id) }
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
              speaker = segment.speaker,
            )
          },
      )
      if (!preserveSources && previous.size <= 1) previous.forEach {
        transcripts.deleteChildren(it.id)
        transcripts.delete(it.id)
      }
      val currentActive = sessions.get(target.sessionId)?.activeTranscriptId?.let { transcripts.get(it) }
      if (!preserveSources || currentActive?.kind != TranscriptKind.REFINED) sessions.setActiveTranscript(target.sessionId, transcript.id, now)
      // Le parti appena trascritte hanno una separazione delle voci nuova, con etichette ridate da
      // capo: i nomi di prima potrebbero finire sulle frasi di un altro (vedi `VoiceNames.forget`).
      sessions.get(target.sessionId)?.let { session ->
        val kept = VoiceNames.forget(session.voiceNames, written)
        if (kept != session.voiceNames) sessions.setVoiceNames(target.sessionId, kept, now)
      }
      rebuildRawNow(target.sessionId)
    }
    // La nota non si tocca: una trascrizione finita non e' una modifica di chi l'ha scritta, e la
    // data della nota e' quella vera (vedi `NoteDates`), non l'ora in cui il computer ha finito.

    // Quella da raccontare: la sessione del lavoro se c'e' ancora, altrimenti quella che ne ha preso
    // le parti (un'unione fatta mentre si trascriveva).
    val main = targets.last().sessionId
    SavedTranscription(
      sessionId = main,
      transcript = RawTranscripts.forSelection(transcripts.bySession(main), sessions.get(main)?.activeTranscriptId)?.takeIf { it.text.isNotBlank() },
      sessionIds = targets.map { it.sessionId },
    )
  }

  /** Le parti che la trascrizione mostrata non copre: quelle importate dopo, o arrivate da altrove. */
  suspend fun untranscribedParts(sessionId: String): List<AudioPartEntity> {
    val ordered = parts.bySession(sessionId)
    if (ordered.isEmpty()) return emptyList()
    val selected = RawTranscripts.forSelection(transcripts.bySession(sessionId), sessions.get(sessionId)?.activeTranscriptId)
    val covered = selected?.let {
      (segments.byTranscript(it.id).map { row -> row.partId } + CloudSources.decode(it.cloudSourceJson)?.parts.orEmpty()
        .filter { p -> p.text != null }.map { p -> p.id }).toSet()
    }.orEmpty()
    return ordered.filterNot { it.id in covered }
  }

  // ---------------------------------------------------------------------------------------------

  /** Una sessione rimasta senza parti e senza parole non serve piu' a niente: se ne va da sola. */
  private suspend fun deleteIfEmpty(sessionId: String) {
    if (parts.countIn(sessionId) > 0) return
    // Deleting the final audio part must not cascade away recoverable cloud originals.
    if (transcripts.bySession(sessionId).any { it.cloudSourceJson != null }) return
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

  /**
   * I nomi delle voci seguono le parti che cambiano sessione: la chiave e' della parte, e chi parla
   * in quella registrazione resta la stessa persona (vedi `VoiceNames.carry`).
   */
  private suspend fun carryVoiceNames(fromSessionId: String, toSessionId: String, partIds: List<String>) {
    val from = sessions.get(fromSessionId) ?: return
    val to = sessions.get(toSessionId) ?: return
    val (newFrom, newTo) = VoiceNames.carry(from.voiceNames, to.voiceNames, partIds)
    val now = System.currentTimeMillis()
    if (newTo != to.voiceNames) sessions.setVoiceNames(toSessionId, newTo, now)
    if (newFrom != from.voiceNames) sessions.setVoiceNames(fromSessionId, newFrom, now)
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
