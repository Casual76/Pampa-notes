package dev.pampa.pampanotes.ui.session

import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.repo.RawTranscripts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map

/** A coherent selection snapshot: never render a newly selected text with the previous RAW timings. */
internal data class SessionTranscriptSelection(
  val sessionId: String?,
  val requestedActiveId: String?,
  val transcripts: List<TranscriptEntity>,
  val active: TranscriptEntity?,
  val raw: TranscriptEntity?,
  val segments: List<SegmentEntity> = emptyList(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun sessionTranscriptSelection(
  sessions: Flow<SessionEntity?>,
  transcripts: Flow<List<TranscriptEntity>>,
  observeSegments: (String) -> Flow<List<SegmentEntity>>,
): Flow<SessionTranscriptSelection> = combine(sessions, transcripts) { session, list ->
  val inSession = list.filter { it.sessionId == session?.id }
  val raw = RawTranscripts.forSelection(inSession, session?.activeTranscriptId)
  SessionTranscriptSelection(session?.id, session?.activeTranscriptId, inSession,
    inSession.firstOrNull { it.id == session?.activeTranscriptId } ?: raw, raw)
}.flatMapLatest { selected ->
  flow {
    emit(selected)
    selected.raw?.let { raw -> emitAll(observeSegments(raw.id).map { selected.copy(segments = it) }) }
  }
}
