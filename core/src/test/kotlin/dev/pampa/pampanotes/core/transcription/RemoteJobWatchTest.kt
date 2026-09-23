package dev.pampa.pampanotes.core.transcription

import dev.antigravity.fluidengine.ai.net.AiError
import dev.pampa.pampanotes.core.testing.TinyHttpServer
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Quando le domande sullo stato possono dire «la POST aspetta per niente», e soprattutto quando no:
 * abbandonare un lavoro buono costa una lezione intera da rifare, quindi ogni sospetto si conferma
 * con `/health` (una sonda finta qui, su un orologio virtuale e monotono).
 */
class RemoteJobWatchTest {

  private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
  private fun snapshot(state: String, instance: String? = "A"): JsonElement =
    json(if (instance == null) """{"state":"$state","fraction":0.5}""" else """{"state":"$state","fraction":0.5,"instance":"$instance"}""")

  private fun notFound(): Nothing = throw TranscriptionError.Parse("Not Found", AiError.BadRequest(404, "Not Found"))
  private fun mute(): Nothing = throw TranscriptionError.Network("connect failed", IOException("no route to host"))

  /** Risposte in fila; finita la fila, l'ultima si ripete. */
  private class Answers<T>(vararg answers: () -> T) {
    private val queue = ArrayDeque(answers.toList())
    var calls = 0
    suspend fun next(): T {
      calls++
      val answer = if (queue.size > 1) queue.removeFirst() else queue.first()
      return answer()
    }
  }

  private fun alive(instance: String?): CompanionPulse = CompanionPulse(instance)

  private suspend fun lostOf(block: suspend () -> Unit): RemoteJobLost? =
    runCatching { block() }.exceptionOrNull() as? RemoteJobLost

  // --- il silenzio ---

  @Test
  fun `novanta secondi muti, e muta anche la sonda - il computer e' perso`() = runTest {
    val fetch = Answers<JsonElement?>({ mute() })
    val probe = Answers<CompanionPulse?>({ null })
    val lost = lostOf {
      RemoteJobPoller(fetch::next, 1_000, { testScheduler.currentTime }, probe = probe::next) {}.run()
    }
    assertEquals(RemoteJobLost.Reason.SILENT, lost?.reason)
    assertEquals(1, probe.calls)
    assertEquals(91_000L, testScheduler.currentTime)
  }

  @Test
  fun `novanta secondi muti ma la sonda risponde - il PC c'e', si aspetta la POST`() = runTest {
    var calls = 0
    val probe = Answers<CompanionPulse?>({ alive("A") })
    RemoteJobPoller(
      fetch = { if (++calls >= 200) snapshot("done", instance = null) else mute() },
      intervalMs = 1_000,
      clock = { testScheduler.currentTime },
      probe = probe::next,
    ) {}.run()
    // Due conferme, a 91 e a 182 secondi: nessuna ha abbandonato il lavoro.
    assertEquals(2, probe.calls)
    assertEquals(200, calls)
  }

  @Test
  fun `prima dell'ultimo byte il silenzio non si misura - un caricamento lento non e' un PC spento`() = runTest {
    val fetch = Answers<JsonElement?>({ mute() })
    val start = testScheduler.currentTime
    var readyAt = -1L
    val lost = lostOf {
      RemoteJobPoller(fetch::next, 1_000, { testScheduler.currentTime }) {}.run(
        ready = { (testScheduler.currentTime - start >= 600_000).also { if (it && readyAt < 0) readyAt = testScheduler.currentTime } },
      )
    }
    assertEquals(RemoteJobLost.Reason.SILENT, lost?.reason)
    // Dieci minuti di caricamento, poi i novanta secondi: contati dall'ultimo byte.
    assertEquals(readyAt + RemoteJobPoller.LOST_AFTER_SILENCE_MS, testScheduler.currentTime)
  }

  // --- il lavoro dimenticato ---

  @Test
  fun `due 404 dopo averlo visto e un instance nuovo - il companion e' ripartito`() = runTest {
    val fetch = Answers<JsonElement?>({ snapshot("transcribing") }, { notFound() })
    val probe = Answers<CompanionPulse?>({ alive("B") })
    val lost = lostOf { RemoteJobPoller(fetch::next, 10, { testScheduler.currentTime }, probe = probe::next) {}.run() }
    assertEquals(RemoteJobLost.Reason.FORGOTTEN, lost?.reason)
    assertEquals(3, fetch.calls)
    assertEquals(1, probe.calls)
  }

  @Test
  fun `due 404 ma lo stesso instance - non e' perso, si guarda solo che il PC resti acceso`() = runTest {
    val fetch = Answers<JsonElement?>({ snapshot("transcribing") }, { notFound() })
    // Lo stesso processo per un po', poi un riavvio vero: solo allora si abbandona.
    val probe = Answers<CompanionPulse?>({ alive("A") }, { alive("A") }, { alive("A") }, { alive("B") })
    val lost = lostOf {
      RemoteJobPoller(fetch::next, 10, { testScheduler.currentTime }, probe = probe::next, blindIntervalMs = 5_000) {}.run()
    }
    assertEquals(RemoteJobLost.Reason.FORGOTTEN, lost?.reason)
    assertEquals(4, probe.calls)
    // Dopo i due 404 non si chiede piu' del lavoro: solo la sonda.
    assertEquals(3, fetch.calls)
  }

  @Test
  fun `due 404 e la sonda muta - si misura il silenzio con la sonda, e una risposta lo azzera`() = runTest {
    val fetch = Answers<JsonElement?>({ snapshot("transcribing") }, { notFound() })
    val probeCalls = AtomicInteger()
    val lost = lostOf {
      RemoteJobPoller(
        fetch = fetch::next,
        intervalMs = 10,
        clock = { testScheduler.currentTime },
        probe = {
          // Muta alla conferma e per 80 secondi, poi una risposta, poi muta per sempre.
          if (probeCalls.incrementAndGet() == 9) alive("A") else null
        },
        blindIntervalMs = 10_000,
      ) {}.run()
    }
    assertEquals(RemoteJobLost.Reason.SILENT, lost?.reason)
    // La risposta alla nona sonda ha fatto ripartire il conto: la decima e' la prima muta, e il
    // silenzio scade nove sonde dopo.
    assertEquals(19, probeCalls.get())
  }

  @Test
  fun `un companion senza instance (1_0_0) - due 404 bastano, come prima, senza sonda`() = runTest {
    val fetch = Answers<JsonElement?>({ snapshot("transcribing", instance = null) }, { notFound() })
    val probe = Answers<CompanionPulse?>({ alive("A") })
    val lost = lostOf { RemoteJobPoller(fetch::next, 10, { testScheduler.currentTime }, probe = probe::next) {}.run() }
    assertEquals(RemoteJobLost.Reason.FORGOTTEN, lost?.reason)
    assertEquals(0, probe.calls)
  }

  @Test
  fun `un 404 isolato dopo averlo visto non chiede niente alla sonda`() = runTest {
    val fetch = Answers<JsonElement?>({ snapshot("transcribing") }, { notFound() }, { snapshot("aligning") }, { notFound() }, { snapshot("done", instance = null) })
    val probe = Answers<CompanionPulse?>({ alive("A") }, { alive("B") })
    // Finito con la sonda: si resta a guardare, e un riavvio adesso si vede ancora.
    val lost = lostOf { RemoteJobPoller(fetch::next, 10, { testScheduler.currentTime }, probe = probe::next, blindIntervalMs = 10) {}.run() }
    assertEquals(5, fetch.calls)
    assertEquals(RemoteJobLost.Reason.FORGOTTEN, lost?.reason)
    assertEquals(2, probe.calls)
  }

  // --- prima di vederlo ---

  @Test
  fun `404 all'inizio ma la sonda ha un instance - e' un companion nuovo che non l'ha ancora registrato`() = runTest {
    val fetch = Answers<JsonElement?>({ notFound() }, { notFound() }, { notFound() }, { snapshot("queued") }, { snapshot("done") })
    // La prima dice chi e'; dopo il "done" la sola sonda guarda il PC finche' la POST non torna —
    // qui la fa finire un riavvio.
    val probe = Answers<CompanionPulse?>({ alive("A") }, { alive("A") }, { alive("B") })
    val seen = mutableListOf<RemoteStage>()
    val lost = lostOf {
      RemoteJobPoller(fetch::next, 10, { testScheduler.currentTime }, probe = probe::next, blindIntervalMs = 10) { seen += it.stage }.run()
    }
    // Non un companion vecchio: si e' continuato a chiedere, e gli stadi sono arrivati.
    assertEquals(listOf(RemoteStage.QUEUED, RemoteStage.DONE), seen)
    assertEquals(5, fetch.calls)
    assertEquals(RemoteJobLost.Reason.FORGOTTEN, lost?.reason)
  }

  @Test
  fun `404 per sempre da un companion nuovo - dopo un minuto si passa alla sola sonda`() = runTest {
    val fetch = Answers<JsonElement?>({ notFound() })
    val probe = Answers<CompanionPulse?>({ alive("A") }, { alive("A") }, { alive("B") })
    val lost = lostOf {
      RemoteJobPoller(fetch::next, 1_000, { testScheduler.currentTime }, probe = probe::next, blindIntervalMs = 10_000) {}.run()
    }
    assertEquals(RemoteJobLost.Reason.FORGOTTEN, lost?.reason)
    // Un minuto di 404 (piu' il primo), poi due sonde uguali e la terza diversa.
    assertEquals(RemoteJobPoller.NOT_REGISTERED_PATIENCE_MS / 1_000 + 1, fetch.calls.toLong())
  }

  @Test
  fun `404 all'inizio e una sonda senza instance - un server che non conosce i lavori, si smette`() = runTest {
    val fetch = Answers<JsonElement?>({ notFound() })
    val probe = Answers<CompanionPulse?>({ alive(null) })
    RemoteJobPoller(fetch::next, 10, { testScheduler.currentTime }, probe = probe::next) {}.run()
    assertEquals(2, fetch.calls)
    assertEquals(1, probe.calls)
  }

  @Test
  fun `un orologio che salta non conta - si misura col tempo monotono`() = runTest {
    // L'orologio monotono del poller e' quello virtuale; l'ora del telefono qui non entra mai.
    // Che il default sia davvero monotono: System.nanoTime, non currentTimeMillis.
    val a = RemoteJobPoller.MONOTONIC_MS()
    val b = RemoteJobPoller.MONOTONIC_MS()
    assertTrue(b >= a)
    val fetch = Answers<JsonElement?>({ mute() })
    val lost = lostOf { RemoteJobPoller(fetch::next, 1_000, { testScheduler.currentTime }) {}.run() }
    assertEquals(RemoteJobLost.Reason.SILENT, lost?.reason)
  }

  // --- il companion che si riavvia ---

  @Test
  fun `503 restarting si aspetta quanto dice, fra 5 e 120 secondi, e nient'altro`() {
    fun server(code: Int, message: String, retry: Double?) = TranscriptionError.Server(code, message, retry)
    assertEquals(5_000L, OpenAiCompatProvider.restartWait(server(503, """{"detail":"restarting"}""", 1.0)))
    assertEquals(30_000L, OpenAiCompatProvider.restartWait(server(503, """{"detail":"restarting"}""", 30.0)))
    assertEquals(120_000L, OpenAiCompatProvider.restartWait(server(503, """{"detail":"restarting"}""", 3_600.0)))
    assertEquals(10_000L, OpenAiCompatProvider.restartWait(server(503, """{"detail":"restarting"}""", null)))
    assertNull(OpenAiCompatProvider.restartWait(server(503, "Service Unavailable", 5.0)))
    assertNull(OpenAiCompatProvider.restartWait(server(500, """{"detail":"restarting"}""", 5.0)))
    assertNull(OpenAiCompatProvider.restartWait(IOException("restarting")))
  }

  // --- i lavori lasciati indietro ---

  @Test
  fun `l'elenco dei lavori lasciati indietro - pochi, recenti, e chi non ha risposto torna`() {
    var now = 0L
    val ledger = AbandonedCompanionJobs(clock = { now })
    repeat(AbandonedCompanionJobs.MAX_ENTRIES + 2) { ledger.record("job-$it", mapOf("Authorization" to "Bearer x")) }
    val first = ledger.drain()
    assertEquals(AbandonedCompanionJobs.MAX_ENTRIES, first.size)
    assertEquals("job-2", first.first().jobId)
    assertTrue(ledger.drain().isEmpty())
    ledger.putBack(first.first())
    now += AbandonedCompanionJobs.MAX_AGE_MS
    assertTrue("troppo vecchio: si lascia andare", ledger.drain().isEmpty())
  }

  // --- contro un server vero ---

  private lateinit var server: TinyHttpServer

  @Before
  fun start() {
    server = TinyHttpServer()
  }

  @After
  fun stop() {
    server.close()
  }

  private val answer = """{"text":"ciao","language":"it","duration":1.0,"segments":[{"start":0.0,"end":1.0,"text":"ciao"}]}"""
  private val sha = "ab".repeat(32)

  private fun provider(ledger: AbandonedCompanionJobs = AbandonedCompanionJobs()) = OpenAiCompatProvider(
    TranscriptionHttp("test"), server.url(""), CompanionAuth.fixed("segreto"),
    pollIntervalMs = 100, blindIntervalMs = 100, abandoned = ledger,
  )

  @Test
  fun `lo stesso instance dopo i 404 - la POST lenta arriva e il lavoro non si abbandona`() = runBlocking {
    val polls = AtomicInteger()
    server.respond { request ->
      when {
        request.method == "POST" -> {
          Thread.sleep(2_000)
          TinyHttpServer.Response(200, answer)
        }
        request.path == "/health" -> TinyHttpServer.Response(200, """{"status":"ok","instance":"A"}""")
        request.path.startsWith("/v1/jobs/") ->
          if (polls.incrementAndGet() == 1) TinyHttpServer.Response(200, """{"state":"transcribing","fraction":0.4,"instance":"A"}""")
          else TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
        else -> TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
      }
    }
    val result = provider().transcribeByRef(sha, TranscribeRequest("m"), null) {}
    assertEquals("ciao", result.text)
    // Dopo la conferma, niente piu' domande sul lavoro: solo `/health`.
    assertTrue(polls.get() <= 4)
    assertTrue(server.requests.count { it.path == "/health" } >= 2)
  }

  @Test
  fun `un instance nuovo dopo i 404 - la POST appesa torna subito, e la prossima ferma la vecchia dopo essersi agganciata`() = runBlocking {
    val ledger = AbandonedCompanionJobs()
    val posts = AtomicInteger()
    val jobsSeen = AtomicInteger()
    server.respond { request ->
      when {
        request.method == "POST" ->
          if (posts.incrementAndGet() == 1) {
            Thread.sleep(30_000) // il PC si e' riavviato senza chiudere la connessione
            TinyHttpServer.Response(200, answer)
          } else {
            Thread.sleep(1_000)
            TinyHttpServer.Response(200, answer)
          }
        request.method == "DELETE" -> TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
        request.path == "/health" -> TinyHttpServer.Response(200, """{"instance":"B"}""")
        request.path.startsWith("/v1/jobs/") ->
          if (posts.get() == 1) {
            if (jobsSeen.incrementAndGet() == 1) TinyHttpServer.Response(200, """{"state":"transcribing","instance":"A"}""")
            else TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
          } else {
            TinyHttpServer.Response(200, """{"state":"transcribing","instance":"B"}""")
          }
        else -> TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
      }
    }
    val provider = provider(ledger)
    val started = System.currentTimeMillis()
    val error = runCatching { withTimeout(10_000) { provider.transcribeByRef(sha, TranscribeRequest("m"), null) {} } }.exceptionOrNull()
    assertTrue("era $error", error is TranscriptionError.Network && error.retryable)
    assertTrue(System.currentTimeMillis() - started < 5_000)
    val firstJob = server.requests.first { it.method == "POST" }.header(OpenAiCompatProvider.JOB_HEADER)
    assertTrue("perso: nessuna DELETE subito", server.requests.none { it.method == "DELETE" })

    // Il runner riprova: la POST nuova parte, si aggancia, e solo dopo ferma la vecchia.
    val result = provider.transcribeByRef(sha, TranscribeRequest("m"), null) {}
    assertEquals("ciao", result.text)
    val requests = server.requests.toList()
    val secondPost = requests.indexOfLast { it.method == "POST" }
    val delete = requests.indexOfFirst { it.method == "DELETE" }
    assertTrue("nessuna DELETE del lavoro vecchio", delete >= 0)
    assertEquals("/v1/jobs/$firstJob", requests[delete].path)
    assertEquals("Bearer segreto", requests[delete].header("Authorization"))
    assertTrue("la DELETE deve venire dopo la POST nuova", delete > secondPost)
    // Il companion l'ha sentita (un 404 e' una risposta): non resta niente da fermare.
    assertTrue(ledger.drain().isEmpty())
  }

  @Test
  fun `503 restarting - si aspetta il Retry-After e si rimanda, senza errori`() = runBlocking {
    val posts = AtomicInteger()
    server.respond { request ->
      when {
        request.method == "POST" ->
          if (posts.incrementAndGet() == 1) {
            TinyHttpServer.Response(503, """{"detail":"restarting"}""", headers = mapOf("Retry-After" to "1"))
          } else {
            TinyHttpServer.Response(200, answer)
          }
        else -> TinyHttpServer.Response(404, """{"detail":"Not Found"}""")
      }
    }
    val started = System.currentTimeMillis()
    val result = provider().transcribeByRef(sha, TranscribeRequest("m"), null) {}
    val elapsed = System.currentTimeMillis() - started
    assertEquals("ciao", result.text)
    assertEquals(2, posts.get())
    // Un secondo chiesto, cinque aspettati: il minimo.
    assertTrue("ha aspettato $elapsed ms", elapsed >= 5_000)
    // Il 503 non e' un lavoro da fermare: il companion non l'ha registrato.
    assertTrue(server.requests.none { it.method == "DELETE" })
  }

  @Test
  fun `un annullamento che il PC non sente resta da fermare per la volta dopo`() = runBlocking {
    val ledger = AbandonedCompanionJobs()
    server.respond { request ->
      when (request.method) {
        "POST" -> {
          Thread.sleep(30_000)
          TinyHttpServer.Response(200, answer)
        }
        "DELETE" -> {
          Thread.sleep(10_000) // non risponde in tempo
          TinyHttpServer.Response(200, """{"cancelled":true}""")
        }
        else -> TinyHttpServer.Response(200, """{"state":"transcribing","instance":"A"}""")
      }
    }
    val pending = async { provider(ledger).transcribeByRef(sha, TranscribeRequest("m"), null) {} }
    withTimeout(5_000) { while (server.requests.none { it.method == "GET" }) delay(20) }
    pending.cancelAndJoin()
    val jobId = server.requests.first { it.method == "POST" }.header(OpenAiCompatProvider.JOB_HEADER)
    val left = ledger.drain()
    assertEquals(listOf(jobId), left.map { it.jobId })
    assertNotNull(left.first().headers["Authorization"])
  }
}
