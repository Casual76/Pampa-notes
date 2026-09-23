package dev.pampa.pampanotes.core.transcription

import dev.antigravity.fluidengine.ai.net.AiError
import dev.pampa.pampanotes.core.testing.TinyHttpServer
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Il racconto del computer di casa: chi lo chiede non deve mai far fallire una trascrizione, e un
 * companion vecchio (o un altro server compatibile) deve solo smettere di essere interrogato.
 */
class RemoteProgressTest {

  private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

  /** Un fetch finto che risponde in fila, e conta quante volte e' stato chiamato. */
  private class Script(vararg answers: () -> JsonElement?) {
    private val queue = ArrayDeque(answers.toList())
    var calls = 0
    val fetch: suspend () -> JsonElement? = {
      calls++
      (queue.removeFirstOrNull() ?: error("chiamato una volta di troppo")).invoke()
    }
  }

  private fun notFound(): Nothing = throw TranscriptionError.Parse("Not Found", AiError.BadRequest(404, "Not Found"))

  @Test
  fun `un companion vecchio risponde 404 e si smette in silenzio`() = runTest {
    val script = Script({ notFound() }, { notFound() })
    val seen = mutableListOf<RemoteProgress>()
    RemoteJobPoller(script.fetch, intervalMs = 10) { seen += it }.run()
    assertEquals(2, script.calls)
    assertTrue(seen.isEmpty())
  }

  @Test
  fun `gli stadi arrivano in ordine e all'ultimo si smette`() = runTest {
    val script = Script(
      { notFound() }, // la prima domanda arriva un soffio prima che il lavoro sia registrato
      { json("""{"state":"queued","fraction":0,"position":2}""") },
      { throw IOException("rete che singhiozza") },
      { json("""{"state":"loading_model","fraction":0,"device":"cuda"}""") },
      { json("""{"state":"transcribing","fraction":0.7,"eta_s":42.5,"audio_s":3600}""") },
      { json("""{"state":"aligning","fraction":0.32}""") },
      { json("""{"state":"done","fraction":1}""") },
    )
    val seen = mutableListOf<RemoteProgress>()
    RemoteJobPoller(script.fetch, intervalMs = 10) { seen += it }.run()
    assertEquals(7, script.calls)
    assertEquals(
      listOf(RemoteStage.QUEUED, RemoteStage.LOADING_MODEL, RemoteStage.TRANSCRIBING, RemoteStage.ALIGNING, RemoteStage.DONE),
      seen.map { it.stage },
    )
    assertEquals(2, seen[0].position)
    assertEquals("cuda", seen[1].device)
    assertEquals(0.7f, seen[2].fraction, 0.001f)
    assertEquals(42.5, seen[2].etaSeconds!!, 0.001)
    assertEquals(3600.0, seen[2].audioSeconds!!, 0.001)
  }

  @Test
  fun `un 404 isolato dopo una risposta buona non basta per dare il lavoro per perso`() = runTest {
    val script = Script(
      { json("""{"state":"transcribing","fraction":0.5}""") },
      { notFound() },
      { json("""{"state":"aligning","fraction":0.1}""") },
      { notFound() },
      { json("""{"state":"done","fraction":1}""") },
    )
    RemoteJobPoller(script.fetch, intervalMs = 10) {}.run()
    assertEquals(5, script.calls)
  }

  @Test
  fun `due 404 di fila dopo averlo visto il lavoro e' perso - il companion e' ripartito`() = runTest {
    val script = Script(
      { json("""{"state":"transcribing","fraction":0.5}""") },
      { notFound() },
      { notFound() },
    )
    val lost = runCatching { RemoteJobPoller(script.fetch, intervalMs = 10) {}.run() }.exceptionOrNull()
    assertTrue("era $lost", lost is RemoteJobLost && lost.reason == RemoteJobLost.Reason.FORGOTTEN)
    assertEquals(3, script.calls)
  }

  /** Un fetch che non riceve mai una risposta HTTP: il PC spento, la rete sparita. */
  private val silent: suspend () -> JsonElement? = { throw TranscriptionError.Network("connect failed", IOException("no route to host")) }

  @Test
  fun `novanta secondi senza nessuna risposta e il computer e' perso`() = runTest {
    var calls = 0
    val poller = RemoteJobPoller(
      fetch = { calls++; silent() },
      intervalMs = 1_000,
      clock = { testScheduler.currentTime },
    ) {}
    val lost = runCatching { poller.run() }.exceptionOrNull()
    assertTrue("era $lost", lost is RemoteJobLost && lost.reason == RemoteJobLost.Reason.SILENT)
    // La prima domanda muta a un secondo, l'ultima novanta secondi dopo: non trenta tentativi e basta.
    assertEquals(RemoteJobPoller.LOST_AFTER_SILENCE_MS / 1_000 + 1, calls.toLong())
    assertEquals(RemoteJobPoller.LOST_AFTER_SILENCE_MS + 1_000, testScheduler.currentTime)
  }

  @Test
  fun `una risposta qualunque, anche un 500, azzera il silenzio`() = runTest {
    var calls = 0
    // Muto per 80 secondi, poi un 500, poi muto per altri 80, poi il lavoro finisce: niente di perso.
    val poller = RemoteJobPoller(
      fetch = {
        calls++
        when {
          calls == 81 -> throw TranscriptionError.Server(500, "boom")
          calls == 162 -> json("""{"state":"done","fraction":1}""")
          else -> silent()
        }
      },
      intervalMs = 1_000,
      clock = { testScheduler.currentTime },
    ) {}
    poller.run()
    assertEquals(162, calls)
  }

  @Test
  fun `un companion vecchio che poi si ammutolisce non trattiene niente, ma il silenzio conta lo stesso`() = runTest {
    // 404 prima di ogni risposta buona: si smette subito, senza errori. Il silenzio non arriva mai.
    val old = Script({ notFound() }, { notFound() })
    RemoteJobPoller(old.fetch, intervalMs = 10, clock = { testScheduler.currentTime }) {}.run()
    assertEquals(2, old.calls)

    // Un PC spento fin dalla prima domanda: la POST e' partita, il lavoro non lo si e' mai visto.
    val lost = runCatching {
      RemoteJobPoller(silent, intervalMs = 1_000, clock = { testScheduler.currentTime }) {}.run()
    }.exceptionOrNull()
    assertTrue("era $lost", lost is RemoteJobLost)
  }

  @Test
  fun `credenziali rifiutate o un JSON che non e' il nostro fermano le domande`() = runTest {
    val unauthorized = Script({ throw TranscriptionError.Unauthorized("no") })
    RemoteJobPoller(unauthorized.fetch, intervalMs = 10) {}.run()
    assertEquals(1, unauthorized.calls)

    val stranger = Script({ json("""{"object":"list"}""") }, { json("[]") })
    RemoteJobPoller(stranger.fetch, intervalMs = 10) {}.run()
    assertEquals(2, stranger.calls)
  }

  @Test
  fun `prima dell'ultimo byte non si chiede niente`() = runTest {
    var ready = false
    var ticks = 0
    val script = Script({ json("""{"state":"done","fraction":1}""") })
    RemoteJobPoller(script.fetch, intervalMs = 10) {}.run(ready = { (++ticks > 3).also { ready = it } })
    assertTrue(ready)
    assertEquals(1, script.calls)
  }

  // --- contro un server vero ---

  private lateinit var server: TinyHttpServer
  private lateinit var audio: File

  @Before
  fun start() {
    server = TinyHttpServer()
    audio = File.createTempFile("pampa", ".m4a").apply { writeBytes(ByteArray(4096)); deleteOnExit() }
  }

  @After
  fun stop() {
    server.close()
  }

  private val answer = """{"text":"ciao","language":"it","duration":1.0,"segments":[{"start":0.0,"end":1.0,"text":"ciao"}]}"""

  @Test
  fun `il provider manda l'id e racconta il lavoro mentre aspetta`() = runBlocking {
    val states = ArrayDeque(listOf("loading_model", "transcribing", "aligning"))
    server.respond { request ->
      when {
        request.method == "POST" -> {
          Thread.sleep(1_500)
          TinyHttpServer.Response(200, answer)
        }
        request.path.startsWith("/v1/jobs/") -> {
          val state = synchronized(states) { states.removeFirstOrNull() } ?: "aligning"
          TinyHttpServer.Response(200, """{"state":"$state","fraction":0.5}""")
        }
        else -> TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
      }
    }
    val provider = OpenAiCompatProvider(
      TranscriptionHttp("test"), server.url(""), CompanionAuth.fixed("segreto"), pollIntervalMs = 200,
    )
    val seen = Collections.synchronizedList(mutableListOf<RemoteProgress>())
    val result = provider.transcribe(audio, "audio/mp4", TranscribeRequest("m"), onRemote = { seen += it })

    assertEquals("ciao", result.text)
    val post = server.requests.first { it.method == "POST" }
    val jobId = post.header(OpenAiCompatProvider.JOB_HEADER)
    assertNotNull("la POST deve portare l'id del lavoro", jobId)
    val polls = server.requests.filter { it.method == "GET" }
    assertTrue("nessuna domanda sullo stato", polls.isNotEmpty())
    assertTrue(polls.all { it.path == "/v1/jobs/$jobId" && it.header("Authorization") == "Bearer segreto" })
    assertEquals(RemoteStage.LOADING_MODEL, seen.first().stage)
    assertTrue(seen.map { it.stage }.contains(RemoteStage.TRANSCRIBING))
  }

  @Test
  fun `con un server che non sa niente dei lavori la trascrizione va lo stesso`() = runBlocking {
    server.respond { request ->
      if (request.method == "POST") {
        Thread.sleep(1_500)
        TinyHttpServer.Response(200, answer)
      } else {
        TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
      }
    }
    val provider = OpenAiCompatProvider(TranscriptionHttp("test"), server.url(""), pollIntervalMs = 100)
    val result = provider.transcribe(audio, "audio/mp4", TranscribeRequest("m"))
    assertEquals("ciao", result.text)
    // Due 404 e basta, non una domanda ogni decimo di secondo per un secondo e mezzo.
    assertEquals(RemoteJobPoller.MAX_MISSES, server.requests.count { it.method == "GET" })
  }

  @Test
  fun `il companion riavviato perde il lavoro e la POST appesa torna subito come errore di rete`() = runBlocking {
    val polls = java.util.concurrent.atomic.AtomicInteger()
    server.respond { request ->
      when {
        // La POST resta appesa: il PC si e' spento senza chiudere la connessione.
        request.method == "POST" -> {
          Thread.sleep(30_000)
          TinyHttpServer.Response(200, answer)
        }
        request.path.startsWith("/v1/jobs/") ->
          if (polls.incrementAndGet() == 1) {
            TinyHttpServer.Response(200, """{"state":"transcribing","fraction":0.4}""")
          } else {
            TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
          }
        else -> TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
      }
    }
    val provider = OpenAiCompatProvider(TranscriptionHttp("test"), server.url(""), CompanionAuth.fixed("segreto"), pollIntervalMs = 100)
    val started = System.currentTimeMillis()
    val error = runCatching {
      withTimeout(10_000) { provider.transcribeByRef("ab".repeat(32), TranscribeRequest("m"), null) {} }
    }.exceptionOrNull()
    assertTrue("era $error", error is TranscriptionError.Network)
    assertTrue((error as TranscriptionError).retryable)
    assertTrue("ci ha messo troppo", System.currentTimeMillis() - started < 5_000)
    // Perso: non c'e' niente da fermare, e nessuna DELETE.
    assertTrue(server.requests.none { it.method == "DELETE" })
  }

  @Test
  fun `annullare qui manda al computer la DELETE del lavoro, con le stesse credenziali`() = runBlocking {
    server.respond { request ->
      when {
        request.method == "POST" -> {
          Thread.sleep(30_000)
          TinyHttpServer.Response(200, answer)
        }
        request.method == "DELETE" -> TinyHttpServer.Response(200, """{"cancelled":true}""")
        request.path.startsWith("/v1/jobs/") -> TinyHttpServer.Response(200, """{"state":"transcribing","fraction":0.4}""")
        else -> TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
      }
    }
    val provider = OpenAiCompatProvider(TranscriptionHttp("test"), server.url(""), CompanionAuth.fixed("segreto"), pollIntervalMs = 100)
    val pending = async { provider.transcribe(audio, "audio/mp4", TranscribeRequest("m")) }
    // Aspetta che il lavoro sia partito e che se ne sia chiesto lo stato almeno una volta.
    withTimeout(5_000) { while (server.requests.none { it.method == "GET" }) delay(20) }
    val started = System.currentTimeMillis()
    pending.cancelAndJoin()
    assertTrue("l'annullamento ci ha messo troppo", System.currentTimeMillis() - started < OpenAiCompatProvider.CANCEL_TIMEOUT_MS + 1_000)

    val jobId = server.requests.first { it.method == "POST" }.header(OpenAiCompatProvider.JOB_HEADER)
    val delete = server.requests.firstOrNull { it.method == "DELETE" }
    assertNotNull("nessuna DELETE", delete)
    assertEquals("/v1/jobs/$jobId", delete!!.path)
    assertEquals("Bearer segreto", delete.header("Authorization"))
  }

  @Test
  fun `un companion che non conosce la DELETE, o un PC che non risponde, non rallentano l'annullamento`() = runBlocking {
    server.respond { request ->
      when (request.method) {
        "POST" -> {
          Thread.sleep(30_000)
          TinyHttpServer.Response(200, answer)
        }
        // Il companion 1.0.0 non ha la rotta, e qui per giunta ci pensa su.
        "DELETE" -> {
          Thread.sleep(10_000)
          TinyHttpServer.Response(405, """{"detail":"Method Not Allowed"}""")
        }
        else -> TinyHttpServer.Response(200, """{"state":"transcribing","fraction":0.4}""")
      }
    }
    val provider = OpenAiCompatProvider(TranscriptionHttp("test"), server.url(""), pollIntervalMs = 100)
    val pending = async { provider.transcribeByRef("ab".repeat(32), TranscribeRequest("m"), null) {} }
    withTimeout(5_000) { while (server.requests.none { it.method == "GET" }) delay(20) }
    val started = System.currentTimeMillis()
    pending.cancelAndJoin()
    assertTrue(pending.isCancelled)
    assertTrue("l'annullamento ci ha messo troppo", System.currentTimeMillis() - started < OpenAiCompatProvider.CANCEL_TIMEOUT_MS + 1_000)
  }

  // --- la barra dell'intera sessione ---

  @Test
  fun `le parti pesano per durata`() {
    val scale = ProgressScale(listOf(60 * 60_000L, 5 * 60_000L), ProgressScale.COMPUTER_UPLOAD_SHARE)
    val firstDone = scale.overall(0, 1f)
    assertEquals(60f / 65f * ProgressScale.MAX, firstDone, 0.001f)
    assertEquals(ProgressScale.MAX, scale.overall(1, 1f), 0.001f)
    // Durate sconosciute: tutte uguali.
    val unknown = ProgressScale(listOf(0L, 0L), ProgressScale.COMPUTER_UPLOAD_SHARE)
    assertEquals(0.5f * ProgressScale.MAX, unknown.overall(0, 1f), 0.001f)
  }

  @Test
  fun `dentro un pezzo il caricamento viene prima del lavoro del computer, e la barra non torna indietro`() {
    val scale = ProgressScale(listOf(60_000L), ProgressScale.COMPUTER_UPLOAD_SHARE)
    val steps = listOf(
      scale.uploading(0f),
      scale.uploading(1f),
      scale.remote(RemoteProgress(RemoteStage.QUEUED)),
      scale.remote(RemoteProgress(RemoteStage.LOADING_MODEL)),
      scale.remote(RemoteProgress(RemoteStage.TRANSCRIBING, 0.5f)),
      scale.remote(RemoteProgress(RemoteStage.TRANSCRIBING, 1f)),
      scale.remote(RemoteProgress(RemoteStage.ALIGNING, 0.5f)),
      scale.remote(RemoteProgress(RemoteStage.DONE, 1f)),
    ).map { scale.overall(0, scale.chunk(1, 1, it, chunked = false)) }
    assertEquals(steps, steps.sorted())
    assertEquals(0f, steps.first(), 0.001f)
    assertEquals(ProgressScale.MAX, steps.last(), 0.001f)
    // Tagliata in tre: il secondo pezzo a meta' sta dopo la decodifica e il primo pezzo.
    val second = scale.chunk(2, 3, 0.5f, chunked = true)
    assertEquals(ProgressScale.PREPARE_SHARE + (1 - ProgressScale.PREPARE_SHARE) * 1.5f / 3, second, 0.001f)
  }
}
