package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.export.SpeechClock
import dev.pampa.pampanotes.core.sync.SyncMetadataMerge
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The Worker runs these same observations through its production retention helper. */
class PampaRecordingOriginTupleTest {
  private val base = AudioPartEntity("p", "s", 0, "p.wav", "p.wav", "audio/wav", 7, 1000, "a".repeat(64), 1)
  private val cases = javaClass.getResourceAsStream("/recording-origin-merge-cases.json").use {
    Json.parseToJsonElement(requireNotNull(it).bufferedReader().readText()).jsonArray
  }

  private fun part(value: JsonObject) = base.copy(
    recordedAtEpochMs = value["recordedAtEpochMs"]?.jsonPrimitive?.longOrNull,
    recordedAtLocal = value["recordedAtLocal"]?.jsonPrimitive?.contentOrNull,
    recordedAtZoneId = value["recordedAtZoneId"]?.jsonPrimitive?.contentOrNull,
    recordedAtSource = value["recordedAtSource"]?.jsonPrimitive?.contentOrNull,
    recordedAtPrecision = value["recordedAtPrecision"]?.jsonPrimitive?.contentOrNull,
  )

  private fun origin(value: AudioPartEntity) = RecordingOrigin(value.recordedAtEpochMs,
    value.recordedAtLocal, value.recordedAtZoneId, value.recordedAtSource, value.recordedAtPrecision)

  @Test fun `shared observations select intact tuples in portable Android and native merge policies`() {
    val mergers = listOf<(AudioPartEntity?, AudioPartEntity) -> AudioPartEntity>(
      PampaBundleMetadataMerge::audio, SyncMetadataMerge::audio, PampaBundleRecordingOrigin::retain)
    for (entry in cases) {
      val item = entry.jsonObject
      if (item["conflict"]?.jsonPrimitive?.booleanOrNull == true) continue
      for (reverse in listOf(false, true)) {
        val previous = part(item[if (reverse) "incoming" else "previous"]!!.jsonObject)
        val incoming = part(item[if (reverse) "previous" else "incoming"]!!.jsonObject)
          .copy(sessionId = "moved", position = 2)
        val expectedKey = (if (reverse) item["reverseExpected"] ?: item.getValue("expected") else item.getValue("expected"))
          .jsonPrimitive.content
        val expected = origin(part(item.getValue(expectedKey).jsonObject))
        val context = "${item.getValue("name").jsonPrimitive.content}, reverse=$reverse"
        for (merge in mergers) {
          val result = merge(previous, incoming)
          assertEquals(context, expected, origin(result))
          assertEquals(context, incoming.copy(recordedAtEpochMs = expected.epochMs, recordedAtLocal = expected.local,
            recordedAtZoneId = expected.zoneId, recordedAtSource = expected.source, recordedAtPrecision = expected.precision), result)
          assertEquals(context, expected, origin(merge(result, incoming)))
        }
      }
    }
  }

  @Test fun `sync and portable conflict checks still reject contradictory observations in both directions`() {
    for (entry in cases) {
      val item = entry.jsonObject
      if (item["conflict"]?.jsonPrimitive?.booleanOrNull != true) continue
      val previous = part(item.getValue("previous").jsonObject)
      val incoming = part(item.getValue("incoming").jsonObject)
      for ((local, next) in listOf(previous to incoming, incoming to previous)) {
        assertThrows(IllegalArgumentException::class.java) { PampaBundleMetadataMerge.audio(local, next) }
        assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.audio(local, next) }
      }
    }
  }

  @Test fun `native graph import and subsequent sync leave a minute speech label unchanged`() {
    val minute = part(cases.first().jsonObject.getValue("previous").jsonObject)
    val partial = part(cases.first().jsonObject.getValue("incoming").jsonObject)
    val note = NoteEntity("n", "f", "Recording", createdAt = 1, updatedAt = 2)
    val session = SessionEntity("s", "n", date = "2025-09-22", position = 0, createdAt = 1, updatedAt = 2)
    fun graph(audio: AudioPartEntity) = PampaBundleNote(note, sessions = listOf(session), parts = listOf(audio))
    val label = SpeechClock.label(origin(minute), 0)
    for ((local, next) in listOf(minute to partial, partial to minute)) {
      val imported = PampaBundleMerge.merge(graph(local), graph(next)).parts.single()
      val synced = SyncMetadataMerge.audio(imported, partial)
      assertEquals(origin(minute), origin(synced))
      assertEquals(label, SpeechClock.label(origin(synced), 0))
      assertEquals(label, SpeechClock.label(origin(SyncMetadataMerge.audio(partial, synced)), 0))
    }
  }

  @Test fun `sync identity and hash guards and new nullable partial origins remain unchanged`() {
    val minute = part(cases.first().jsonObject.getValue("previous").jsonObject)
    val partial = part(cases.first().jsonObject.getValue("incoming").jsonObject)
    for (local in listOf(null, minute.copy(id = "other"), minute.copy(sha256 = "b".repeat(64)),
      minute.copy(sha256 = ""), minute.copy(sha256 = "opaque"), minute.copy(sizeBytes = 8))) {
      assertEquals(partial, SyncMetadataMerge.audio(local, partial))
    }
    assertEquals(base, SyncMetadataMerge.audio(null, base))
    assertEquals(base, SyncMetadataMerge.audio(base, base))
    assertEquals(origin(minute), origin(SyncMetadataMerge.audio(minute.copy(sha256 = minute.sha256.uppercase()), partial)))
  }
}
