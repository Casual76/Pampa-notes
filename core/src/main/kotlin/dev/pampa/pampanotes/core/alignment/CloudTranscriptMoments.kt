package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.transcription.TranscriptSearch

/** Exact cloud search positions, without a legacy segment/word interpolation fallback. */
object CloudTranscriptMoments {
  fun usesCloudEvidence(raw: TranscriptEntity): Boolean = raw.cloudSourceJson != null || CloudSources.cloud(raw.provider)

  fun moment(raw: TranscriptEntity, parts: List<AudioPartEntity>, query: String): TranscriptSearch.Moment? {
    val presentation = AlignmentPresentation.forRaw(raw, parts) ?: return null
    val found = TranscriptSearch.find(listOf(raw.text), query).firstOrNull()?.let { it to query }
      ?: TranscriptSearch.searchTerms(query).mapNotNull { term ->
        TranscriptSearch.find(listOf(raw.text), term).firstOrNull()?.let { it to term }
      }.minByOrNull { it.first.start }
      ?: return null
    return presentation.timeAt(found.first.start)?.let { TranscriptSearch.Moment(it, found.second) }
  }
}
