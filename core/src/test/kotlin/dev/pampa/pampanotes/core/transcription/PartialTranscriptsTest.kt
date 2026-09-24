package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.testing.TinyHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * «Il testo che arriva a pezzi»: quando si chiede, come si legge, dove si mette nel tempo della
 * sessione, e che un companion che non lo sa fare non riceve nessuna domanda in piu'.
 */
class PartialTranscriptsTest {

  private lateinit var server: TinyHttpServer

  @Before
  fun start() {
    server = TinyHttpServer()
  }

  @After
  fun stop() {
    server.close()
  }

  // --- quando ce n'e' uno nuovo ---

  @Test
  fun `i pezzi pronti sono quelli prima del pezzo di adesso, tutti mentre si separano le voci`() {
    assertEquals(0, PartialPieces.ready(null))
    assertEquals(0, PartialPieces.ready(RemoteProgress(RemoteStage.TRANSCRIBING)))
    assertEquals(0, PartialPieces.ready(RemoteProgress(RemoteStage.TRANSCRIBING, chunk = 1, chunks = 1)))
    assertEquals(0, PartialPieces.ready(RemoteProgress(RemoteStage.TRANSCRIBING, chunk = 1, chunks = 4)))
    assertEquals(2, PartialPieces.ready(RemoteProgress(RemoteStage.ALIGNING, chunk = 3, chunks = 4)))
    assertEquals(4, PartialPieces.ready(RemoteProgress(RemoteStage.DIARIZING, chunk = 4, chunks = 4)))
    assertEquals(0, PartialPieces.ready(RemoteProgress(RemoteStage.DONE, chunk = 4, chunks = 4)))
  }

  // --- come si legge ---

  @Test
  fun `la risposta del companion si legge, e una risposta strana vale niente`() {
    val partial = VerboseJson.parsePartial(
      Json.parseToJsonElement(
        """{"pieces_done":2,"pieces_total":5,"from":1,"segments":[{"start":1.5,"end":2.0,"text":" ciao ","words":[{"word":"ciao","start":1.5,"end":1.9}]},{"start":3,"text":""}]}""",
      ),
    )!!
    assertEquals(RemotePartial(1, 2, 5, listOf(RawSegment(1_500, 2_000, "ciao", words = listOf(RawWord(1_500, 1_900, "ciao"))))), partial)
    assertNull(VerboseJson.parsePartial(Json.parseToJsonElement("""{"state":"transcribing"}""")))
    assertNull(VerboseJson.parsePartial(null))
  }

  // --- dove si mette ---

  private val parts = listOf(SessionAssembler.Part("a", 60_000), SessionAssembler.Part("b", 60_000))

  private data class Line(override val startMs: Long, override val endMs: Long, override val text: String) : TimedText

  @Test
  fun `i pezzi stanno nel tempo della sessione, dopo le parti che vengono prima`() {
    val collector = PartialCollector(parts)
    val first = collector.offer("b", 1, RemotePartial(0, 1, 3, listOf(RawSegment(1_000, 2_000, "bi"))))
    assertEquals(listOf(61_000L), first.segments.map { it.sessionStartMs })
    assertEquals(listOf(1_000L), first.segments.map { it.partStartMs })
    assertEquals(Triple(1, 3, 2), Triple(first.piecesDone, first.piecesTotal, first.partCount))

    // La parte prima, finita, va davanti col suo testo; i pezzi dopo si aggiungono.
    collector.complete("a", 0, listOf(Line(0, 5_000, "a")))
    val later = collector.offer("b", 1, RemotePartial(1, 2, 3, listOf(RawSegment(30_000, 31_000, "bis"))))
    assertEquals(listOf("a", "bi", "bis"), later.segments.map { it.text })
    assertEquals(listOf(0L, 61_000L, 90_000L), later.segments.map { it.sessionStartMs })
    assertEquals(later.segments.size, later.segments.map { it.id }.toSet().size)

    // Da capo (un secondo tentativo): quello che c'era della parte se ne va.
    val again = collector.offer("b", 1, RemotePartial(0, 1, 3, listOf(RawSegment(1_000, 2_000, "bi"))))
    assertEquals(listOf("a", "bi"), again.segments.map { it.text })
  }

  @Test
  fun `il provvisorio si pubblica per sessione e se ne va quando lo si toglie`() {
    val store = PartialTranscripts()
    val partial = PartialCollector(parts).offer("a", 0, RemotePartial(0, 1, 2, listOf(RawSegment(0, 1_000, "x"))))
    store.publish("s1", partial)
    assertEquals(partial, store.bySession.value["s1"])
    store.clear("s1")
    assertTrue(store.bySession.value.isEmpty())
  }

  // --- il provider ---

  private val answer = """{"text":"uno due","language":"it","duration":1.0,"segments":[{"start":0.0,"end":1.0,"text":"uno due"}],"chunks":3}"""

  private fun companion(features: String) {
    server.respond { request ->
      when {
        request.path == "/health" -> TinyHttpServer.Response(200, """{"features":[$features],"instance":"i1"}""")
        request.method == "POST" -> {
          Thread.sleep(1_500)
          TinyHttpServer.Response(200, answer)
        }
        request.path.contains("/partial?from=0") -> TinyHttpServer.Response(
          200, """{"pieces_done":1,"pieces_total":3,"from":0,"segments":[{"start":1.0,"end":2.0,"text":"uno"}]}""",
        )
        request.path.contains("/partial?from=1") -> TinyHttpServer.Response(
          200, """{"pieces_done":2,"pieces_total":3,"from":1,"segments":[{"start":1300.0,"end":1301.0,"text":"due"}]}""",
        )
        request.path.startsWith("/v1/jobs/") ->
          TinyHttpServer.Response(200, """{"state":"transcribing","fraction":0.2,"chunk":3,"chunks":3,"instance":"i1"}""")
        else -> TinyHttpServer.Response(404, "{}")
      }
    }
  }

  private fun provider() = OpenAiCompatProvider(
    TranscriptionHttp("test"), server.url(""), CompanionAuth.fixed("segreto"), pollIntervalMs = 50, partialIntervalMs = 50,
  )

  @Test
  fun `col companion che li sa dare, i pezzi finiti arrivano mentre la POST aspetta`() = runBlocking {
    companion(""""by_ref","server_chunks","partial"""")
    val provider = provider()
    provider.features()
    val seen = mutableListOf<RemotePartial>()
    val result = provider.transcribeByRef("abc", TranscribeRequest("m"), maxMinutes = 30, onPartial = { synchronized(seen) { seen += it } })

    assertEquals("uno due", result.text)
    val got = synchronized(seen) { seen.toList() }
    assertEquals(listOf(0 to 1, 1 to 2), got.map { it.from to it.piecesDone })
    assertEquals(listOf("uno", "due"), got.flatMap { p -> p.segments.map { it.text } })
    // Due pezzi pronti, due domande: una volta ricevuti non si richiedono.
    assertEquals(2, server.requests.count { it.path.contains("/partial") })
    assertTrue(server.requests.filter { it.path.contains("/partial") }.all { it.header("Authorization") == "Bearer segreto" })
  }

  @Test
  fun `un companion che non li sa dare non riceve domande in piu'`() = runBlocking {
    companion(""""by_ref","server_chunks"""")
    val provider = provider()
    provider.features()
    val seen = mutableListOf<RemotePartial>()
    provider.transcribeByRef("abc", TranscribeRequest("m"), maxMinutes = 30, onPartial = { synchronized(seen) { seen += it } })
    assertTrue(seen.isEmpty())
    assertEquals(0, server.requests.count { it.path.contains("/partial") })
  }
}
