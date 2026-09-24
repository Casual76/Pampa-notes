"""
Le prove del companion, senza GPU e senza modelli: si lanciano con

    .venv\\Scripts\\python.exe -m unittest test_companion -v

dalla cartella `companion`. Niente pytest: nel venv non c'e', e un'installazione in piu' su un
ambiente che deve solo trascrivere e' un'occasione in piu' di romperlo.

Il Worker e' finto (un `http.server` su una porta a caso), il server vero gira con uvicorn su
un'altra porta a caso, e i modelli sono oggetti che finiscono la memoria quando glielo si dice.
"""

from __future__ import annotations

import asyncio
import dataclasses
import hashlib
import importlib.machinery
import importlib.util
import json
import math
import os
import socket
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import archive
import config
import fuori
import whisperx_server as server


def setUpModule() -> None:
    # I test non leggono la scheda vera: `nvidia-smi` direbbe quanta VRAM occupano le app aperte su
    # questo PC, e i piani cambierebbero da una macchina all'altra. Chi vuole il driver lo finge.
    patcher = mock.patch.object(server, "nvidia_query", return_value=None)
    patcher.start()
    unittest.addModuleCleanup(patcher.stop)
    # Ne' la sonda del contenitore: lanciati da un terminale dentro un'app che virtualizza AppData,
    # i test di avvio.pyw si rilanciavano davvero fuori con WMI. Chi la prova la finge.
    boxed = mock.patch.object(fuori, "redirected_to", return_value=None)
    boxed.start()
    unittest.addModuleCleanup(boxed.stop)

HERE = Path(__file__).resolve().parent


# --- il Worker finto ------------------------------------------------------------------------------


class FakeWorker:
    """
    Risponde come il Worker vero alle due verifiche che il companion fa.

    Biglietti: `pt_good` vale per l'owner `owner@example.com` e scade fra un'ora; `pt_far` scade fra
    trenta ore (per vedere il tetto delle dodici); `pt_old` e' gia' scaduto ma il Worker lo dice
    buono lo stesso (per vedere che il companion guarda la scadenza). Ospiti: `pg_friend` e' «Anna».
    """

    OWNER = "owner@example.com"

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []
        worker = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_: object) -> None:  # zitto
                pass

            def do_POST(self) -> None:  # noqa: N802
                body = json.loads(self.rfile.read(int(self.headers.get("content-length") or 0)) or b"{}")
                worker.calls.append((self.path, body))
                now_ms = time.time() * 1000
                if self.path == "/v1/computer/verify":
                    expiries = {"pt_good": now_ms + 3600_000, "pt_far": now_ms + 30 * 3600_000, "pt_old": now_ms - 1000}
                    if body.get("owner") == FakeWorker.OWNER and body.get("ticket") in expiries:
                        self.answer(200, {"ok": True, "ownerId": "u1", "expiresAt": expiries[body["ticket"]]})
                    else:
                        self.answer(401, {"error": "biglietto non valido"})
                elif self.path == "/v1/guests/verify":
                    if body.get("owner") == FakeWorker.OWNER and body.get("token") == "pg_friend":
                        self.answer(200, {"name": "Anna"})
                    else:
                        self.answer(401, {"error": "no"})
                else:
                    self.answer(200, {})

            def answer(self, status: int, payload: dict) -> None:
                data = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def count(self, path: str) -> int:
        return sum(1 for called, _ in self.calls if called == path)

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class StateMixin:
    """Ogni prova parte da uno STATE pulito e lo rimette com'era."""

    SAVED = (
        "token", "index_url", "owner", "accept_anonymous", "alignment", "device", "name", "compute_type",
        "batch_size", "idle_seconds", "tunables", "vram", "gpu", "config_path", "hf_token", "diarization",
        "diarization_denied",
    )

    def setUp(self) -> None:  # noqa: D401
        self._saved = {key: server.STATE[key] for key in self.SAVED}
        server.GUEST_CACHE.clear()
        server.TICKET_CACHE.clear()

    def tearDown(self) -> None:
        server.STATE.update(self._saved)
        server.GUEST_CACHE.clear()
        server.TICKET_CACHE.clear()


# --- la fila ---------------------------------------------------------------------------------------


class PriorityGateTest(unittest.TestCase):
    def test_cancelled_waiter_leaves_the_queue(self) -> None:
        async def scenario() -> list[str]:
            gate = server.PriorityGate()
            order: list[str] = []
            release = asyncio.Event()

            async def holder() -> None:
                async with gate.slot(1):
                    order.append("holder")
                    await release.wait()

            async def waiter(name: str, priority: int) -> None:
                async with gate.slot(priority):
                    order.append(name)

            first = asyncio.create_task(holder())
            await asyncio.sleep(0.01)
            # Il proprietario si mette in fila, poi la sua richiesta viene annullata.
            doomed = asyncio.create_task(waiter("annullato", 0))
            await asyncio.sleep(0.01)
            self.assertEqual(gate.waiting, 1)
            doomed.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await doomed
            self.assertEqual(gate.waiting, 0, "chi rinuncia deve uscire dalla fila")
            # Chi arriva dopo deve passare, non restare dietro a un fantasma.
            after = asyncio.create_task(waiter("ospite", 1))
            await asyncio.sleep(0.01)
            release.set()
            await asyncio.wait_for(asyncio.gather(first, after), timeout=2)
            return order

        self.assertEqual(asyncio.run(scenario()), ["holder", "ospite"])

    def test_cancelled_head_wakes_the_next(self) -> None:
        """Il primo della fila annullato proprio quando il posto si libera: il secondo non resta fermo."""

        async def scenario() -> list[str]:
            gate = server.PriorityGate()
            order: list[str] = []
            release = asyncio.Event()

            async def holder() -> None:
                async with gate.slot(0):
                    await release.wait()

            async def waiter(name: str, priority: int) -> None:
                async with gate.slot(priority):
                    order.append(name)

            first = asyncio.create_task(holder())
            await asyncio.sleep(0.01)
            head = asyncio.create_task(waiter("proprietario", 0))
            tail = asyncio.create_task(waiter("ospite", 1))
            await asyncio.sleep(0.01)
            release.set()
            head.cancel()
            await asyncio.gather(head, return_exceptions=True)
            await asyncio.wait_for(asyncio.gather(first, tail), timeout=2)
            return order

        self.assertEqual(asyncio.run(scenario()), ["ospite"])

    def test_owner_goes_before_guest(self) -> None:
        async def scenario() -> list[str]:
            gate = server.PriorityGate()
            order: list[str] = []
            release = asyncio.Event()

            async def holder() -> None:
                async with gate.slot(1):
                    await release.wait()

            async def waiter(name: str, priority: int) -> None:
                async with gate.slot(priority):
                    order.append(name)

            first = asyncio.create_task(holder())
            await asyncio.sleep(0.01)
            guest = asyncio.create_task(waiter("ospite", 1))
            await asyncio.sleep(0.01)
            owner = asyncio.create_task(waiter("proprietario", 0))
            await asyncio.sleep(0.01)
            release.set()
            await asyncio.wait_for(asyncio.gather(first, guest, owner), timeout=2)
            return order

        self.assertEqual(asyncio.run(scenario()), ["proprietario", "ospite"])

    def test_position_counts_the_one_running(self) -> None:
        """«Sei il 2°»: chi trascrive adesso conta, e il proprietario arrivato dopo passa davanti."""

        async def scenario() -> list:
            gate = server.PriorityGate()
            release = asyncio.Event()
            seen: list = []

            async def holder() -> None:
                async with gate.slot(0, key="adesso"):
                    await release.wait()

            async def waiter(key: str, priority: int) -> None:
                async with gate.slot(priority, key=key):
                    pass

            first = asyncio.create_task(holder())
            await asyncio.sleep(0.01)
            seen.append(gate.position("adesso"))  # sta lavorando, non aspetta
            guest = asyncio.create_task(waiter("ospite", 1))
            await asyncio.sleep(0.01)
            seen.append(gate.position("ospite"))
            owner = asyncio.create_task(waiter("mio", 0))
            await asyncio.sleep(0.01)
            seen.extend([gate.position("mio"), gate.position("ospite")])
            release.set()
            await asyncio.wait_for(asyncio.gather(first, guest, owner), timeout=2)
            seen.append(gate.position("ospite"))
            return seen

        self.assertEqual(asyncio.run(scenario()), [None, 2, 2, 3, None])


# --- a che punto e' una trascrizione -----------------------------------------------------------------


def as_caller(kind: str, bearer: str) -> server.Caller:
    return server.Caller(kind, "test", None, bearer)


class JobProgressTest(unittest.TestCase):
    def test_late_callback_of_an_abandoned_pass_is_ignored(self) -> None:
        progress = server.JobProgress("abcdefgh")
        progress.set("transcribing")
        old = progress.callback("transcribing")
        old(60.0)
        self.assertAlmostEqual(progress.fraction, 0.6)
        progress.set("aligning")
        old(90.0)  # lo scatto tardivo del passo prima non tocca l'allineamento
        self.assertEqual((progress.state, progress.fraction), ("aligning", 0.0))
        progress.callback("aligning")(40.0)
        progress.callback("aligning")(30.0)  # non torna indietro
        self.assertAlmostEqual(progress.fraction, 0.4)

    def test_snapshot_tells_elapsed_eta_and_processing(self) -> None:
        progress = server.JobProgress("abcdefgh", now=100.0)
        progress.set("queued", now=101.0)
        snap = progress.snapshot(position=2, now=105.0)
        self.assertEqual((snap["state"], snap["position"], snap["elapsed_s"], snap["eta_s"]), ("queued", 2, 5.0, None))
        progress.admitted(now=110.0)
        progress.set("transcribing", now=110.0)
        progress.advance(0.25, "transcribing")
        snap = progress.snapshot(position=7, now=120.0)
        self.assertIsNone(snap["position"], "il posto in fila vale solo in fila")
        self.assertEqual(snap["eta_s"], 30.0)  # 10 s per il 25%: ne mancano 30
        self.assertEqual(snap["processing_s"], 10.0)
        progress.set("done", 1.0, now=130.0)
        snap = progress.snapshot(now=500.0)
        self.assertEqual((snap["processing_s"], snap["elapsed_s"]), (20.0, 30.0))


class AutoPiecesTest(StateMixin, unittest.TestCase):
    """«Automatico»: pezzi da circa quattro minuti di lavoro, dalla velocita' misurata."""

    def test_fast_card_keeps_a_lesson_whole(self) -> None:
        server.STATE["speeds"] = {"cuda": [40.0, 43.0, 38.0]}
        # 40x per quattro minuti = 160 minuti di audio, oltre il massimo: 120, e un'ora e mezza ci sta.
        self.assertIsNone(server.auto_piece_minutes(90 * 60, "cuda"))
        self.assertEqual(server.auto_piece_minutes(200 * 60, "cuda"), 120)

    def test_processor_gets_short_pieces(self) -> None:
        server.STATE["speeds"] = {"cpu": [1.2, 1.4, 1.5]}
        # 1,4x per quattro minuti = 5,6 minuti: sotto il minimo, quindici.
        self.assertEqual(server.auto_piece_minutes(60 * 60, "cpu"), 15)

    def test_without_measurements_a_default_is_used(self) -> None:
        server.STATE["speeds"] = {}
        # 25x per quattro minuti = 100 minuti.
        self.assertEqual(server.auto_piece_minutes(4 * 3600, "cuda"), 100)

    def test_short_or_loading_heavy_runs_are_not_measured(self) -> None:
        server.STATE["speeds"] = {}
        server.record_speed("cuda", 60, 5)
        self.assertEqual(server.STATE["speeds"].get("cuda", []), [])
        for _ in range(15):
            server.record_speed("cuda", 3600, 90)
        self.assertEqual(len(server.STATE["speeds"]["cuda"]), server.SPEED_SAMPLES)

    def test_auto_reaches_the_job_and_comes_back(self) -> None:
        seen = {}

        def fake_transcribe_audio(audio, sample_rate, language, progress, engine, max_minutes=None, prompt=None, **frozen):
            seen["max_minutes"] = max_minutes
            seen.update(frozen)
            return {"segments": [], "language": "it", "device_used": "cuda", "batch_size": 8, "alignment": "ok", "chunks": 3}

        server.STATE["speeds"] = {"cuda": [8.0]}
        server.STATE["device"] = "cuda"
        server.STATE["batch_size"] = 8
        audio = [0.0] * 16000 * 60 * 50  # cinquanta minuti
        with mock.patch.object(server, "load_audio", return_value=audio), \
                mock.patch.object(server, "probe_duration", return_value=50 * 60.0), \
                mock.patch.object(server, "transcribe_audio", fake_transcribe_audio), \
                mock.patch.object(server, "replan_for_job"):
            result = server._transcribe("x.m4a", "it", server.JobProgress(), max_minutes="auto")
        # 8x per quattro minuti = 32, arrotondato a 30: cinquanta minuti vanno in pezzi.
        self.assertEqual((seen["max_minutes"], result["max_minutes_used"]), (30, 30))
        # Lotto e dispositivo fissati all'inizio della lezione, per tutti i pezzi.
        self.assertEqual((seen["batch_size"], seen["device"]), (8, "cuda"))


class RegistryKeepsLiveJobsTest(unittest.TestCase):
    def test_a_running_job_is_never_forgotten(self) -> None:
        registry = server.JobRegistry(limit=2)
        owner = server.Caller("owner", "token", "", "t")
        running = registry.open("aaaaaaaa", owner, now=0.0)
        running.set("transcribing")
        other = registry.open("bbbbbbbb", owner, now=1.0)
        other.set("transcribing")
        registry.open("cccccccc", owner, now=2.0)
        self.assertIsNotNone(registry.get("aaaaaaaa", now=3.0), "oltre il limite non se ne va chi lavora")
        # Una lezione di tre ore sul processore resta per tutte le tre ore (e oltre).
        self.assertIsNotNone(registry.get("aaaaaaaa", now=server.JOB_STALE_S * 3))

    def test_a_job_that_never_started_goes_after_hours(self) -> None:
        registry = server.JobRegistry()
        owner = server.Caller("owner", "token", "", "t")
        registry.open("eeeeeeee", owner, now=0.0)
        self.assertIsNone(registry.get("eeeeeeee", now=server.JOB_STALE_S + 1))


class JobCancelTest(unittest.TestCase):
    def test_cancel_stops_at_the_next_callback(self) -> None:
        progress = server.JobProgress("abcdefgh")
        progress.set("transcribing")
        step = progress.callback("transcribing")
        step(10.0)
        progress.cancel()
        with self.assertRaises(server.JobCancelled):
            step(20.0)
        with self.assertRaises(server.JobCancelled):
            progress.check_cancelled()

    def test_cancelled_is_not_an_ordinary_error(self) -> None:
        # I ripieghi prendono `Exception`: un annullamento non deve finire sul processore.
        self.assertFalse(issubclass(server.JobCancelled, Exception))


class JobRegistryTest(unittest.TestCase):
    def test_ids_are_checked(self) -> None:
        jobs = server.JobRegistry()
        owner = as_caller("owner", "pt_good")
        self.assertIsNone(jobs.open("", owner))
        self.assertIsNone(jobs.open("corto", owner))
        self.assertIsNone(jobs.open("../../etc/passwd", owner))
        self.assertIsNotNone(jobs.open("3f2b8c1e-5a6d-4e7f-8a9b-0c1d2e3f4a5b", owner))

    def test_guest_reads_only_its_own_and_cannot_take_an_id(self) -> None:
        jobs = server.JobRegistry()
        mine = jobs.open("job-del-proprietario", as_caller("owner", "pt_good"))
        theirs = jobs.open("job-dell-ospite-anna", as_caller("guest", "pg_friend"))
        self.assertFalse(jobs.visible_to(mine, as_caller("guest", "pg_friend")))
        self.assertTrue(jobs.visible_to(theirs, as_caller("guest", "pg_friend")))
        self.assertFalse(jobs.visible_to(theirs, as_caller("guest", "pg_other")))
        self.assertTrue(jobs.visible_to(theirs, as_caller("owner", "pt_good")), "il computer e' del proprietario")
        # Lo stesso id da un altro bearer non sostituisce quello che c'e'.
        self.assertIsNone(jobs.open("job-del-proprietario", as_caller("guest", "pg_friend")))
        self.assertIs(jobs.get("job-del-proprietario"), mine)
        # Dallo stesso bearer si' (l'app che riprova con lo stesso id).
        again = jobs.open("job-del-proprietario", as_caller("owner", "pt_good"))
        self.assertIsNot(again, mine)

    def test_finished_jobs_expire_and_the_registry_is_bounded(self) -> None:
        jobs = server.JobRegistry(limit=3, keep_s=600)
        owner = as_caller("owner", "")
        done = jobs.open("finito-da-poco", owner, now=1000.0)
        done.set("done", 1.0, now=1000.0)
        self.assertIsNotNone(jobs.get("finito-da-poco", now=1500.0))
        self.assertIsNone(jobs.get("finito-da-poco", now=1700.0))
        for index in range(3):
            jobs.open(f"in-corso-{index}", owner, now=2000.0)
        finished = jobs.open("finito-dopo", owner, now=2001.0)
        finished.set("done", 1.0, now=2001.0)
        jobs.open("l-ultimo-arrivato", owner, now=2002.0)
        self.assertEqual(len(jobs), 3)
        # Esce prima un lavoro finito, anche se piu' giovane, che uno in corso.
        self.assertIsNone(jobs.get("finito-dopo", now=2002.0))
        self.assertIsNone(jobs.get("in-corso-0", now=2002.0))
        self.assertIsNotNone(jobs.get("l-ultimo-arrivato", now=2002.0))


class BoundedCacheTest(unittest.TestCase):
    def test_evicts_oldest_and_expires(self) -> None:
        cache = server.BoundedCache(limit=3)
        for index in range(5):
            cache.put(f"k{index}", index, until=time.time() + 60)
        self.assertEqual(len(cache), 3)
        self.assertEqual(cache.get("k0"), (False, None))
        self.assertEqual(cache.get("k4"), (True, 4))
        cache.put("short", "x", until=time.time() - 1)
        self.assertEqual(cache.get("short"), (False, None))

    def test_negative_value_is_a_hit(self) -> None:
        cache = server.BoundedCache()
        cache.put("pt_x", False, until=time.time() + 60)
        self.assertEqual(cache.get("pt_x"), (True, False))


# --- la scheda che finisce la memoria ---------------------------------------------------------------


class FakeOOM(RuntimeError):
    def __init__(self) -> None:
        super().__init__("CUDA failed with error out of memory")


class FakeModel:
    """Finisce la memoria con un lotto piu' grande di `fits`; `fits=0` non entra mai."""

    def __init__(self, fits: int, error: BaseException | None = None) -> None:
        self.fits = fits
        self.error = error
        self.sizes: list[int] = []

    def transcribe(self, audio: object, batch_size: int, language: str | None, progress_callback=None) -> dict:
        self.sizes.append(batch_size)
        if progress_callback is not None:
            progress_callback(50.0)  # meta' dei segmenti, poi magari la memoria finisce
        if self.error is not None:
            raise self.error
        if batch_size > self.fits:
            raise FakeOOM()
        if progress_callback is not None:
            progress_callback(100.0)
        return {"language": "it", "segments": [{"start": 0.0, "end": 1.0, "text": "ciao a tutti"}]}


class FakeEngine(server.Engine):
    def __init__(self, main: FakeModel | None, load_error: BaseException | None = None, align_errors: dict | None = None) -> None:
        self.main = main
        self.load_error = load_error
        self.cpu = FakeModel(fits=99)
        self.cpu_loads = 0
        self.releases = 0
        self.align_devices: list[str] = []
        self.align_errors = align_errors or {}

    def main_model(self) -> FakeModel:
        if self.load_error is not None:
            raise self.load_error
        return self.main

    def cpu_model(self) -> FakeModel:
        self.cpu_loads += 1
        return self.cpu

    def align(self, segments: list[dict], language: str, audio: object, device: str, progress_callback=None) -> list[dict]:
        self.align_devices.append(device)
        error = self.align_errors.get(device)
        if error is not None:
            raise error
        if progress_callback is not None:
            progress_callback(100.0)
        return [dict(segment, words=[{"word": "ciao", "start": 0.0, "end": 0.4, "score": 0.9}]) for segment in segments]

    def needs_load(self) -> bool:
        return self.main is not None or self.load_error is not None

    def release(self) -> None:
        self.releases += 1


class RecordingProgress(server.JobProgress):
    """Un JobProgress che si ricorda ogni stato in cui e' passato, con la percentuale a cui l'ha lasciato."""

    def __init__(self) -> None:
        super().__init__("job-registrato", "", False)
        self.trail: list[tuple[str, str | None]] = []
        self.reached: dict[str, float] = {}

    def set(self, state: str, fraction: float = 0.0, detail: str | None = None, now: float | None = None) -> None:
        super().set(state, fraction, detail, now)
        self.trail.append((state, detail))

    def advance(self, fraction: float, state: str) -> None:
        super().advance(fraction, state)
        if self.state == state:
            self.reached[state] = self.fraction


class RunJobTest(unittest.TestCase):
    def test_halves_batch_until_it_fits(self) -> None:
        engine = FakeEngine(FakeModel(fits=4))
        job = server.run_job(None, "it", engine, 16, "cuda")
        self.assertEqual(engine.main.sizes, [16, 8, 4])
        self.assertEqual(job["device_used"], "cuda")
        self.assertEqual(job["batch_size"], 4)
        # Due dopo i lotti che non entravano, una dopo l'allineamento (la riserva di torch non resta sotto il pezzo dopo).
        self.assertEqual(engine.releases, 3)
        self.assertEqual(engine.cpu_loads, 0)
        self.assertEqual(job["alignment"], "ok")

    def test_falls_back_to_cpu_after_batch_one(self) -> None:
        engine = FakeEngine(FakeModel(fits=0))
        job = server.run_job(None, "it", engine, 16, "cuda")
        self.assertEqual(engine.main.sizes, [16, 8, 4, 2, 1])
        self.assertEqual(job["device_used"], "cpu")
        self.assertEqual(engine.cpu_loads, 1)
        self.assertEqual(engine.cpu.sizes, [1])
        self.assertEqual(engine.align_devices, ["cpu"])
        self.assertTrue(job["segments"][0]["words"])

    def test_model_that_does_not_load_goes_to_cpu(self) -> None:
        engine = FakeEngine(None, load_error=FakeOOM())
        job = server.run_job(None, "it", engine, 16, "cuda")
        self.assertEqual(job["device_used"], "cpu")
        self.assertEqual(engine.cpu.sizes, [server.CPU_BATCH_SIZE])

    def test_torch_oom_is_recognised(self) -> None:
        import torch

        engine = FakeEngine(FakeModel(fits=99, error=torch.cuda.OutOfMemoryError("CUDA out of memory. Tried to allocate 2 GiB")))
        job = server.run_job(None, "it", engine, 2, "cuda")
        self.assertEqual(job["device_used"], "cpu")
        self.assertEqual(engine.main.sizes, [2, 1])

    def test_other_errors_are_not_swallowed(self) -> None:
        engine = FakeEngine(FakeModel(fits=99, error=ValueError("file rotto")))
        with self.assertRaises(ValueError):
            server.run_job(None, "it", engine, 16, "cuda")
        self.assertEqual(engine.cpu_loads, 0)

    def test_oom_on_cpu_device_is_not_retried(self) -> None:
        engine = FakeEngine(FakeModel(fits=0))
        with self.assertRaises(FakeOOM):
            server.run_job(None, "it", engine, 16, "cpu")

    def test_align_oom_is_redone_on_cpu(self) -> None:
        engine = FakeEngine(FakeModel(fits=99), align_errors={"cuda": FakeOOM()})
        job = server.run_job(None, "it", engine, 16, "cuda")
        self.assertEqual(engine.align_devices, ["cuda", "cpu"])
        self.assertEqual(job["device_used"], "cuda")
        self.assertEqual(job["alignment"], "ok")
        self.assertTrue(job["segments"][0]["words"])

    def test_align_failure_keeps_segments_and_says_why(self) -> None:
        engine = FakeEngine(FakeModel(fits=99), align_errors={"cuda": PermissionError("Security Violation")})
        with self.assertLogs("pampa", level="WARNING") as logs:
            job = server.run_job(None, "it", engine, 16, "cuda")
        self.assertTrue(job["alignment"].startswith("errore: PermissionError"))
        self.assertNotIn("words", job["segments"][0])
        # Con la traccia, non solo la riga: e' quello che mancava.
        self.assertTrue(any("Traceback" in line for line in logs.output))

    def test_progress_walks_through_the_states(self) -> None:
        progress = RecordingProgress()
        server.run_job(None, "it", FakeEngine(FakeModel(fits=99)), 16, "cuda", progress)
        self.assertEqual([state for state, _ in progress.trail], ["loading_model", "transcribing", "aligning"])
        self.assertEqual(progress.reached, {"transcribing": 1.0, "aligning": 1.0})
        self.assertEqual(progress.device, "cuda")

    def test_progress_restarts_and_says_why_on_fallback(self) -> None:
        progress = RecordingProgress()
        server.run_job(None, "it", FakeEngine(FakeModel(fits=0)), 2, "cuda", progress)
        self.assertEqual(
            progress.trail,
            [
                ("loading_model", None),
                ("transcribing", None),
                ("transcribing", "batch 1"),
                ("loading_model", "cpu"),
                ("transcribing", "cpu"),
                ("aligning", None),
            ],
        )
        self.assertEqual(progress.device, "cpu")


@dataclasses.dataclass
class FakeOptions:
    """Come `TranscriptionOptions` di faster-whisper: un dataclass con `initial_prompt`."""

    initial_prompt: str | None = None
    beam_size: int = 5


class PromptedModel(FakeModel):
    """Un modello con le `options` della pipeline di WhisperX: si ricorda il prompt che ha visto."""

    def __init__(self, error: BaseException | None = None) -> None:
        super().__init__(fits=99, error=error)
        self.options = FakeOptions()
        self.seen: list[str | None] = []

    def transcribe(self, audio: object, batch_size: int, language: str | None, progress_callback=None) -> dict:
        self.seen.append(self.options.initial_prompt)
        return super().transcribe(audio, batch_size, language, progress_callback)


class InitialPromptTest(unittest.TestCase):
    def test_prompt_reaches_whisper_and_goes_away(self) -> None:
        model = PromptedModel()
        server.run_job(None, "it", FakeEngine(model), 16, "cuda", prompt="Fichte, Schelling, Io puro")
        self.assertEqual(model.seen, ["Fichte, Schelling, Io puro"])
        self.assertIsNone(model.options.initial_prompt, "il prossimo lavoro non deve trovarsi il vocabolario di questo")
        self.assertEqual(model.options.beam_size, 5)

    def test_restored_even_when_it_fails_and_absent_without_prompt(self) -> None:
        model = PromptedModel(error=ValueError("file rotto"))
        with self.assertRaises(ValueError):
            server.run_job(None, "it", FakeEngine(model), 16, "cuda", prompt="Kant")
        self.assertIsNone(model.options.initial_prompt)
        plain = PromptedModel()
        server.run_job(None, "it", FakeEngine(plain), 16, "cuda")
        self.assertEqual(plain.seen, [None])

    def test_model_without_options_is_left_alone(self) -> None:
        with server.initial_prompt(object(), "Kant"):
            pass


# --- i pezzi, sul computer ----------------------------------------------------------------------------


RATE = 100  # campioni al secondo: 45 minuti sono 270 mila numeri invece di 43 milioni


def lecture(minutes: float, quiet_at_s: float | None = None) -> "numpy.ndarray":
    """Un «parlato» rumoroso lungo [minutes], con due secondi di silenzio a [quiet_at_s]."""
    import numpy

    generator = numpy.random.default_rng(7)
    audio = (0.2 + 0.1 * generator.random(int(minutes * 60 * RATE))).astype(numpy.float32)
    if quiet_at_s is not None:
        audio[int((quiet_at_s - 1) * RATE) : int((quiet_at_s + 1) * RATE)] = 0.0
    return audio


class PiecesTest(StateMixin, unittest.TestCase):
    def test_piece_count_follows_chunk_policy(self) -> None:
        self.assertEqual(server.piece_count(40 * 60, 30), 1, "fino a dieci minuti oltre il tetto, intero")
        self.assertEqual(server.piece_count(41 * 60, 30), 2)
        self.assertEqual(server.piece_count(45 * 60, 30), 2)
        self.assertEqual(server.piece_count(61 * 60, 30), 3)
        self.assertEqual(server.piece_count(5 * 3600, None), 1)
        self.assertEqual(server.piece_count(5 * 3600, 0), 1)

    def test_cut_falls_in_the_nearest_silence(self) -> None:
        audio = lecture(45, quiet_at_s=22.5 * 60 + 17)
        energies = server.frame_energies(audio, RATE)
        self.assertEqual(len(energies), len(audio) // 2)
        bounds = server.plan_pieces(energies, server.FRAME_MS / 1000, len(audio) / RATE, 2)
        self.assertEqual(len(bounds), 2)
        # Dentro i due secondi di silenzio, non sul cronometro (22:30).
        self.assertLessEqual(abs(bounds[0][1] - (22.5 * 60 + 17)), 1.0)
        self.assertEqual((bounds[0][0], bounds[1][1]), (0.0, 45 * 60.0))
        self.assertEqual(bounds[0][1], bounds[1][0], "niente sovrapposizione: si mettono in fila e basta")

    def run_pieces(self, audio, max_minutes: int | None):
        calls: list[tuple[int, str | None, int, int, str | None]] = []

        def fake_run_job(piece, language, engine, batch_size, device, progress=None, prompt=None):
            calls.append((len(piece), language, progress.chunk, progress.chunks, prompt))
            progress.set("transcribing")
            return {
                "segments": [
                    {"start": 1.0, "end": 2.5, "text": f"pezzo {len(calls)}",
                     "words": [{"word": "pezzo", "start": 1.0, "end": 1.4, "score": 0.9}]},
                ],
                "language": language or "it",
                "device_used": "cpu" if len(calls) == 2 else "cuda",
                "batch_size": 16,
                "alignment": "ok",
            }

        server.STATE.update(device="cuda", batch_size=16)
        progress = RecordingProgress()
        with mock.patch.object(server, "run_job", fake_run_job):
            result = server.transcribe_audio(audio, RATE, None, progress, FakeEngine(None), max_minutes=max_minutes, prompt="Fichte")
        return result, calls, progress

    def test_forty_minutes_with_a_cap_of_thirty_stay_whole(self) -> None:
        audio = lecture(40)
        result, calls, progress = self.run_pieces(audio, 30)
        self.assertEqual(calls, [(len(audio), None, 1, 1, "Fichte")])
        self.assertEqual(result["chunks"], 1)
        self.assertEqual(result["segments"][0]["start"], 1.0)
        self.assertEqual(progress.snapshot()["chunks"], 1)

    def test_forty_five_minutes_become_two_pieces_with_shifted_times(self) -> None:
        cut = 22 * 60 + 40.0
        audio = lecture(45, quiet_at_s=cut)
        result, calls, progress = self.run_pieces(audio, 30)
        self.assertEqual(result["chunks"], 2)
        self.assertEqual([call[2:4] for call in calls], [(1, 2), (2, 2)], "«pezzo 1 di 2», poi «2 di 2»")
        # Il secondo pezzo usa la lingua che il primo ha riconosciuto; il prompt vale per tutti e due.
        self.assertEqual([call[1] for call in calls], [None, "it"])
        self.assertEqual([call[4] for call in calls], ["Fichte", "Fichte"])
        self.assertEqual(sum(call[0] for call in calls), len(audio))
        first, second = result["segments"]
        self.assertEqual((first["start"], first["end"]), (1.0, 2.5))
        offset = calls[0][0] / RATE
        self.assertLessEqual(abs(offset - cut), 1.0, "il taglio cade nel silenzio")
        self.assertAlmostEqual(second["start"], offset + 1.0, places=6)
        self.assertAlmostEqual(second["end"], offset + 2.5, places=6)
        self.assertAlmostEqual(second["words"][0]["start"], offset + 1.0, places=6)
        self.assertAlmostEqual(second["words"][0]["end"], offset + 1.4, places=6)
        self.assertEqual(result["device_used"], "cpu", "un pezzo sul processore vale per la lezione")
        snap = progress.snapshot()
        self.assertEqual((snap["chunk"], snap["chunks"]), (2, 2))

    def test_settings_changed_mid_lesson_do_not_reach_the_next_piece(self) -> None:
        audio = lecture(45, quiet_at_s=22 * 60 + 40.0)
        seen: list[tuple[int, str]] = []

        def fake_run_job(piece, language, engine, batch_size, device, progress=None, prompt=None):
            seen.append((batch_size, device))
            # L'app cambia le impostazioni mentre il primo pezzo si trascrive.
            server.STATE.update(batch_size=2, device="cpu", name="medium")
            return {"segments": [], "language": "it", "device_used": device, "batch_size": batch_size, "alignment": "ok"}

        server.STATE.update(device="cuda", batch_size=16)
        with mock.patch.object(server, "run_job", fake_run_job):
            result = server.transcribe_audio(audio, RATE, None, RecordingProgress(), FakeEngine(None), max_minutes=30)
        self.assertEqual(seen, [(16, "cuda"), (16, "cuda")])
        self.assertEqual((result["batch_size"], result["device_used"]), (16, "cuda"))


class LanguageTest(StateMixin, unittest.TestCase):
    """La lingua si riconosce dove si parla, non sui primi trenta secondi (che possono essere rumore)."""

    class Speaker(FakeEngine):
        """Riconosce «it» dove c'e' voce e «en» sul rumore, come un Whisper davanti a una stanza vuota."""

        def __init__(self) -> None:
            super().__init__(None)
            self.heard: list[float] = []

        def needs_load(self) -> bool:
            return False

        def detect_language(self, audio) -> str:
            level = float(abs(audio).mean())
            self.heard.append(level)
            return "it" if level > 0.1 else "en"

    def test_windows_go_where_it_is_loud_and_do_not_overlap(self) -> None:
        import numpy

        energies = numpy.full(int(600 / 0.02), 0.01, dtype=numpy.float32)
        energies[int(400 / 0.02) : int(460 / 0.02)] = 0.3  # un minuto di voce al minuto sei e quaranta
        starts = server.speech_windows(energies, 0.02)
        self.assertEqual(len(starts), 3)
        self.assertTrue(400 <= starts[0] <= 430, starts)
        self.assertTrue(all(abs(a - b) >= 29.99 for a in starts for b in starts if a is not b))

    def test_noise_first_then_speech_gives_the_language_of_the_speech(self) -> None:
        import numpy

        # Venti minuti di rumore, poi venticinque di lezione: WhisperX avrebbe ascoltato il rumore.
        audio = numpy.concatenate([
            numpy.full(20 * 60 * RATE, 0.02, dtype=numpy.float32),
            lecture(25),
        ])
        engine = self.Speaker()
        seen: list[str | None] = []

        def fake_run_job(piece, language, engine, batch_size, device, progress=None, prompt=None):
            seen.append(language)
            return {"segments": [], "language": language or "en", "device_used": device, "batch_size": batch_size, "alignment": "ok"}

        server.STATE.update(device="cuda", batch_size=16)
        with mock.patch.object(server, "run_job", fake_run_job), self.assertLogs("pampa", level="INFO"):
            result = server.transcribe_audio(audio, RATE, None, RecordingProgress(), engine, max_minutes=20)
        self.assertEqual(result["language"], "it")
        self.assertEqual(seen, ["it", "it", "it"], "la stessa lingua per tutti i pezzi, anche il primo di solo rumore")
        self.assertTrue(all(level > 0.1 for level in engine.heard[:1]))

    def test_a_language_given_by_the_app_is_not_detected_again(self) -> None:
        engine = self.Speaker()
        with mock.patch.object(server, "run_job", lambda piece, language, *a, **k: {"segments": [], "language": language}):
            server.transcribe_audio(lecture(5), RATE, "de", RecordingProgress(), engine)
        self.assertEqual(engine.heard, [])

    def test_a_model_that_cannot_detect_leaves_it_to_the_first_piece(self) -> None:
        class Broken(self.Speaker):
            def detect_language(self, audio) -> str:
                raise FakeOOM()

        with self.assertLogs("pampa", level="WARNING"):
            self.assertIsNone(server.spoken_language(server.LoadedAudio(lecture(2), RATE), 0.02, Broken(), RecordingProgress()))


def seg(start: float, end: float, text: str, words: list[dict] | None = None) -> dict:
    segment = {"start": start, "end": end, "text": text}
    if words is not None:
        segment["words"] = words
    return segment


class HallucinationTest(unittest.TestCase):
    """[drop_hallucinations]: quello che Whisper scrive sul rumore se ne va, la lezione resta com'e'."""

    FRAME = 0.02

    def room(self, seconds: float, voice: list[tuple[float, float]] = ()) -> "numpy.ndarray":
        """Le energie di una stanza (fondo 0,01) con della voce (0,2) nei tratti [voice]."""
        import numpy

        generator = numpy.random.default_rng(3)
        energies = (0.008 + 0.004 * generator.random(int(seconds / self.FRAME))).astype(numpy.float32)
        for start, end in voice:
            energies[int(start / self.FRAME) : int(end / self.FRAME)] = 0.2
        return energies

    def test_echoes_of_the_prompt_go(self) -> None:
        energies = self.room(120, voice=[(60, 64)])
        segments = [
            seg(10.0, 10.14, "18h"),
            seg(20.0, 20.3, "18h 18h 18h"),
            seg(30.0, 30.2, "18h30"),
            seg(40.0, 41.5, "Napoli, 18h. Napoli, 18h."),
            seg(60.0, 61.4, "Napoli."),  # detto davvero, chiaro, per un secondo e mezzo
            seg(62.0, 64.0, "Siamo arrivati a Napoli alle 18."),
        ]
        kept, dropped = server.drop_hallucinations(segments, energies, self.FRAME, prompt="Napoli 18h")
        self.assertEqual([s["text"] for s in kept], ["Napoli.", "Siamo arrivati a Napoli alle 18."])
        self.assertEqual(dropped, {"eco": 4})

    def test_silence_phrases_go_only_when_alone_or_in_the_noise(self) -> None:
        energies = self.room(200, voice=[(50, 58), (100, 104)])
        segments = [
            seg(20.0, 20.6, "Grazie."),  # sola, nel rumore
            seg(50.0, 54.0, "E con questo abbiamo finito la parte sulla peste."),
            seg(54.5, 55.3, "Grazie."),  # detta a lezione, attaccata al resto
            seg(55.8, 58.0, "Adesso passiamo al Seicento."),
            seg(100.0, 101.2, "Buonanotte a tutti."),  # sola ma piena di voce: sola basta
            seg(150.0, 150.8, "Grazie, grazie."),
            seg(170.0, 173.0, "Grazie a tutti per essere venuti fin qui oggi."),  # non e' una frase del silenzio
        ]
        kept, dropped = server.drop_hallucinations(segments, energies, self.FRAME)
        self.assertEqual(
            [s["text"] for s in kept],
            [
                "E con questo abbiamo finito la parte sulla peste.",
                "Grazie.",
                "Adesso passiamo al Seicento.",
                "Grazie a tutti per essere venuti fin qui oggi.",
            ],
        )
        self.assertEqual(dropped, {"frasi": 3})

    def test_short_or_fast_segments_go_only_over_the_noise_floor(self) -> None:
        energies = self.room(100, voice=[(40, 45)])
        segments = [
            seg(10.0, 10.14, "e quindi abbiamo detto che"),  # 190 caratteri al secondo, nel rumore
            seg(20.0, 20.2, "Sì."),  # corto, nel rumore
            seg(40.0, 40.2, "Sì."),  # corto ma pieno di voce: qualcuno ha risposto
            seg(41.0, 41.5, "e quindi abbiamo detto che"),  # veloce ma con la voce sotto
            seg(60.0, 64.0, "una frase detta piano, lontano dal microfono"),  # lenta: resta anche nel rumore
        ]
        kept, dropped = server.drop_hallucinations(segments, energies, self.FRAME)
        self.assertEqual([s["start"] for s in kept], [40.0, 41.0, 60.0])
        self.assertEqual(dropped, {"brevi": 2})

    def test_a_denoised_recording_is_judged_against_the_voice(self) -> None:
        # Come il telefono di «Napoli 18h»: silenzio digitale a −95 dB, voce a −25, e gli «a posto»
        # inventati a −51 — trenta volte sopra il fondo, ma lontanissimi dalla voce.
        import numpy

        db = lambda value: 10 ** (value / 20)  # noqa: E731
        energies = numpy.full(int(300 / self.FRAME), db(-95), dtype=numpy.float32)
        energies[int(100 / self.FRAME) : int(160 / self.FRAME)] = db(-25)
        energies[int(20 / self.FRAME) : int(21 / self.FRAME)] = db(-51)
        energies[int(150 / self.FRAME) : int(151 / self.FRAME)] = db(-40)  # uno che risponde piano
        energies[int(200 / self.FRAME) : int(202 / self.FRAME)] = db(-70)  # un fruscio lungo
        segments = [
            seg(20.0, 20.2, "a posto"),
            seg(100.0, 110.0, "Allora ragazzi, domani sveglia alle sette e colazione alle sette e mezza."),
            seg(150.0, 150.2, "sì"),
            seg(200.0, 202.0, "e allora ci vediamo domani"),  # lento, ma 45 dB sotto la voce
        ]
        floors, speech = server.sound_levels(energies, self.FRAME)
        self.assertAlmostEqual(20 * math.log10(speech), -25, places=0)
        kept, dropped = server.drop_hallucinations(segments, energies, self.FRAME)
        self.assertEqual([s["start"] for s in kept], [100.0, 150.0])
        self.assertEqual(dropped, {"brevi": 1, "muti": 1})

    def test_loops_are_shortened_not_dropped(self) -> None:
        unit = "ho dormito 3 ore e mezzo".split()
        tokens = unit * 8
        words = [{"word": w, "start": 5.0 + i * 0.3, "end": 5.0 + i * 0.3 + 0.25, "score": 0.5} for i, w in enumerate(tokens)]
        segments = [
            seg(5.0, words[-1]["end"], " ".join(tokens), words),
            seg(30.0, 34.0, "la la la la la la la la la la la la la la la la"),
            seg(40.0, 44.0, "Allora, ho dormito, ho dormito, ho dormito, ho dormito, ho dormito, ho dormito."),
        ]
        self.assertGreater(server.compression_ratio(segments[0]["text"]), server.LOOP_COMPRESSION)
        kept, dropped = server.drop_hallucinations(segments, None, self.FRAME)
        self.assertEqual([s["text"] for s in kept], ["ho dormito 3 ore e mezzo", "la", "Allora, ho dormito,"])
        first = kept[0]
        self.assertEqual([w["word"] for w in first["words"]], unit)
        self.assertEqual((first["start"], first["end"]), (5.0, words[len(unit) - 1]["end"]), "i tempi della prima volta")
        self.assertEqual(kept[1]["words"], [], "parole e testo non si corrispondevano: meglio nessuna")
        self.assertEqual(dropped, {"giri": 3})

    def test_a_shortened_loop_is_judged_by_what_it_was(self) -> None:
        # «via via via…» detto in mezzo al brusio: accorciato a «via» dura 0,2 s, ma era 2,6 s di voce.
        import numpy

        energies = numpy.full(int(60 / self.FRAME), 0.05, dtype=numpy.float32)
        words = [{"word": "via", "start": 10.0 + i * 0.24, "end": 10.2 + i * 0.24, "score": 0.8} for i in range(11)]
        segments = [seg(10.0, words[-1]["end"], " ".join(["via"] * 11), words)]
        kept, dropped = server.drop_hallucinations(segments, energies, self.FRAME)
        self.assertEqual([s["text"] for s in kept], ["via"])
        self.assertEqual(dropped, {"giri": 1})

    def test_real_lecture_is_left_alone(self) -> None:
        energies = self.room(60, voice=[(0, 60)])
        text = (
            "No, no, aspettate: la peste del 1656 a Napoli non arriva dal mare come quella del 1348, "
            "arriva con i soldati. E i soldati, i soldati spagnoli, erano ovunque."
        )
        segments = [seg(1.0, 12.0, text), seg(13.0, 15.0, "Grazie a voi per la domanda."), seg(16.0, 17.0, "Sì, sì, sì.")]
        kept, dropped = server.drop_hallucinations(segments, energies, self.FRAME, prompt="Napoli, peste, 1656")
        self.assertEqual(kept, segments)
        self.assertEqual(dropped, {})
        self.assertLess(server.compression_ratio(text), server.LOOP_COMPRESSION)

    def test_empty_and_credits_go_and_nothing_quiet_without_energies(self) -> None:
        segments = [
            seg(1.0, 2.0, "..."),
            seg(3.0, 6.0, "Sottotitoli creati dalla comunità Amara.org"),
            seg(6.5, 6.8, "Teksting av Nicolai Winther"),  # le ore mute prese per norvegese
            seg(7.0, 7.1, "breve ma senza energie non si sa"),
            seg(8.0, 11.0, "I sottotitoli di questo film erano sbagliati."),
        ]
        kept, dropped = server.drop_hallucinations(segments, None, self.FRAME)
        self.assertEqual([s["text"] for s in kept], ["breve ma senza energie non si sa", "I sottotitoli di questo film erano sbagliati."])
        self.assertEqual(dropped, {"vuoti": 1, "crediti": 2})
        self.assertEqual(server.describe_dropped(dropped), "1 senza parole, 2 titoli di coda")

    def test_collapse_keeps_the_first_unit(self) -> None:
        self.assertEqual(server.collapse_repeats(["a", "a", "a"]), [0])
        self.assertEqual(server.collapse_repeats(["no", "no"]), [0, 1], "una parola detta due volte e' vera")
        self.assertEqual(server.collapse_repeats(["a", "b", "a", "b", "c"]), [0, 1, 4])
        self.assertEqual(server.collapse_repeats(["la"] * 4), [0], "l'unita' piu' corta, non «la la»")

    def test_the_response_has_real_ratios_and_no_invented_silence(self) -> None:
        segments = [{"start": 0.5, "end": 2.0, "text": "una frase qualunque", "avg_logprob": -0.4}]

        def fake_transcribe_audio(audio, *args, **kwargs):
            return {"segments": segments, "language": "it", "device_used": "cuda", "batch_size": 8,
                    "alignment": "ok", "chunks": 1, "dropped": {"eco": 2}}

        with mock.patch.object(server, "load_audio", return_value=[0.0] * 16000), \
                mock.patch.object(server, "probe_duration", return_value=1.0), \
                mock.patch.object(server, "transcribe_audio", fake_transcribe_audio), \
                mock.patch.object(server, "replan_for_job"):
            result = server._transcribe("x.m4a", "it", server.JobProgress())
        out = result["segments"][0]
        self.assertIsNone(out["no_speech_prob"], "0,0 diceva «voce di sicuro» e spegneva il filtro dell'app")
        self.assertEqual(out["avg_logprob"], -0.4)
        self.assertAlmostEqual(out["compression_ratio"], server.compression_ratio("una frase qualunque"), places=3)
        self.assertEqual(result["dropped"], {"eco": 2})
        json.dumps(result, allow_nan=False)


class DecodeTest(unittest.TestCase):
    """L'audio da ffmpeg in float32, in un array solo; e le lezioni lunghe a pezzi."""

    RATE = 16000

    def setUp(self) -> None:
        import shutil

        if shutil.which("ffmpeg") is None:
            self.skipTest("ffmpeg non installato")
        import numpy
        import wave

        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.path = Path(folder.name) / "tono.wav"
        seconds = 3.0
        t = numpy.arange(int(seconds * self.RATE)) / self.RATE
        # Un tono che cresce: ogni tratto e' diverso dagli altri, e una fetta sbagliata si vede.
        signal = (0.5 * numpy.sin(2 * numpy.pi * 440 * t) * (t / seconds)).astype(numpy.float32)
        self.pcm = (signal * 32767).astype(numpy.int16)
        with wave.open(str(self.path), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(self.RATE)
            out.writeframes(self.pcm.tobytes())
        self.expected = self.pcm.astype(numpy.float32) / 32768.0

    def test_whole_file_matches_the_samples(self) -> None:
        import numpy

        for expected_s in (3.0, 0.5, None):  # giusto, bugiardo (l'array cresce), sconosciuto
            audio = server.load_audio(self.path, self.RATE, expected_s=expected_s)
            self.assertEqual(audio.dtype, numpy.float32)
            self.assertEqual(len(audio), len(self.expected), expected_s)
            self.assertLess(float(numpy.abs(audio - self.expected).max()), 1e-4)

    def test_a_stretch_is_the_same_as_a_slice(self) -> None:
        import numpy

        piece = server.load_audio(self.path, self.RATE, start_s=1.0, duration_s=1.0)
        self.assertEqual(len(piece), self.RATE)
        self.assertLess(float(numpy.abs(piece - self.expected[self.RATE : 2 * self.RATE]).max()), 1e-4)

    def test_streamed_energies_are_those_of_the_whole_array(self) -> None:
        import numpy

        with mock.patch.object(server, "DECODE_BLOCK_BYTES", 7000):  # blocchi che non cadono sulle finestre
            energies, samples = server.stream_energies(self.path, self.RATE)
        self.assertEqual(samples, len(self.expected))
        whole = server.frame_energies(self.expected, self.RATE)
        self.assertEqual(len(energies), len(whole))
        self.assertLess(float(numpy.abs(energies - whole).max()), 1e-4)
        streamed = server.StreamedAudio(self.path, self.RATE)
        self.assertAlmostEqual(streamed.duration_s, 3.0, places=3)
        self.assertEqual(len(streamed.piece(2.0, 3.0)), self.RATE)

    def test_duration_with_and_without_ffprobe(self) -> None:
        import shutil

        self.assertAlmostEqual(server.probe_duration(self.path), 3.0, places=1)
        which = {"ffprobe": None, "ffmpeg": shutil.which("ffmpeg")}
        with mock.patch.object(server.shutil, "which", side_effect=which.get):
            self.assertAlmostEqual(server.probe_duration(self.path), 3.0, places=1)
        self.assertIsNone(server.probe_duration(self.path.with_name("non-esiste.wav")))

    def test_broken_file_and_cancel(self) -> None:
        broken = self.path.with_name("rotto.m4a")
        broken.write_bytes(b"non e' audio")
        with self.assertRaises(RuntimeError):
            server.load_audio(broken, self.RATE)

        def cancelled() -> None:
            raise server.JobCancelled()

        with self.assertRaises(server.JobCancelled):
            server.load_audio(self.path, self.RATE, check=cancelled)


class WordsTest(unittest.TestCase):
    def test_words_without_times_or_nan_are_dropped(self) -> None:
        segment = {
            "words": [
                {"word": "uno", "start": 0.1, "end": 0.2, "score": 0.9},
                {"word": "2024"},
                {"word": "tre", "start": math.nan, "end": 0.5},
                {"word": "quattro", "start": 0.6, "end": 0.7, "score": math.nan},
            ]
        }
        words = server.words_of(segment)
        self.assertEqual([w["word"] for w in words], ["uno", "quattro"])
        self.assertEqual(words[1]["score"], 0.0)
        json.dumps(words, allow_nan=False)


# --- quanta VRAM ------------------------------------------------------------------------------------


GPU_12 = {"name": "NVIDIA GeForce RTX 4070 Ti", "total_gb": 12.0}


def tunables(**overrides: object) -> dict:
    base = {"model": "large-v3", "compute_type": "", "vram_mode": "auto", "vram_gb": None, "batch_size_max": 16, "idle_minutes": 10}
    base.update(overrides)
    return base


class EstimatorTest(unittest.TestCase):
    def test_large_v3_float16_batch_16(self) -> None:
        # 3,1 di pesi + 16 × 0,32 + 0,9 di allineamento + 0,9 di contesto (misurati il 23/09).
        self.assertAlmostEqual(server.estimate_vram_gb("large-v3", "float16", 16), 10.02, places=2)
        self.assertAlmostEqual(server.estimate_vram_gb("large-v3", "", 16), 10.02, places=2, msg="vuoto = float16 sulla scheda")
        self.assertAlmostEqual(server.estimate_vram_gb("large-v3", "float16", 16, align=False), 9.12, places=2)

    def test_int8_halves_the_weights_not_the_batch(self) -> None:
        full = server.vram_breakdown("large-v3", "float16", 8)
        light = server.vram_breakdown("large-v3", "int8_float16", 8)
        self.assertAlmostEqual(light["weights"], 1.7, places=1)
        self.assertEqual(light["batch"], full["batch"])
        self.assertLess(light["total"], full["total"])

    def test_batch_grows_linearly_and_smaller_models_cost_less(self) -> None:
        one, two = (server.estimate_vram_gb("large-v3", "float16", n) for n in (1, 2))
        self.assertAlmostEqual(two - one, server.BATCH_ITEM_GB, places=2)
        sizes = [server.estimate_vram_gb(model, "float16", 16) for model in ("large-v3", "medium", "small", "base", "tiny")]
        self.assertEqual(sizes, sorted(sizes, reverse=True))
        self.assertAlmostEqual(server.vram_breakdown("medium", "int8_float16", 0)["weights"], 0.8, places=1)

    def test_duration_is_not_a_parameter(self) -> None:
        """La domanda dell'utente: la durata non cambia la VRAM, e la funzione non la chiede nemmeno."""
        import inspect

        self.assertEqual(list(inspect.signature(server.estimate_vram_gb).parameters), ["model", "compute_type", "batch_size", "align"])

    def test_unknown_names_are_guessed_on_the_heavy_side(self) -> None:
        self.assertEqual(server.model_family("Systran/faster-whisper-large-v3"), "large-v3")
        self.assertEqual(server.model_family("faster-whisper-medium"), "medium")
        self.assertEqual(server.model_family("C:/modelli/mio"), "large-v3")


class VramPlanTest(unittest.TestCase):
    def plan(self, total: float | None = None, **overrides: object) -> dict:
        gpu = {"name": "scheda", "total_gb": total} if total else None
        return server.decide_vram(tunables(**overrides), "cuda", gpu)

    def test_12_gb_keeps_large_float16_at_the_max_batch(self) -> None:
        plan = self.plan(12.0)
        self.assertEqual((plan["model"], plan["compute_type"], plan["batch_size"]), ("large-v3", "float16", 16))
        self.assertEqual((plan["mode"], plan["budget_gb"], plan["usable_gb"]), ("auto", 12.0, 10.2))
        self.assertAlmostEqual(plan["estimate_gb"], 10.02, places=2)
        self.assertFalse(plan["downgraded"])
        self.assertTrue(plan["fits"])

    def test_8_gb_lowers_the_batch(self) -> None:
        plan = self.plan(8.0)
        self.assertEqual((plan["model"], plan["compute_type"], plan["batch_size"]), ("large-v3", "float16", 5))
        self.assertLessEqual(plan["estimate_gb"], 8.0 * server.HEADROOM)

    def test_4_gb_goes_down_to_medium(self) -> None:
        plan = self.plan(4.0)
        self.assertEqual((plan["model"], plan["compute_type"], plan["batch_size"]), ("medium", "int8_float16", 4))
        self.assertTrue(plan["downgraded"])
        self.assertEqual(plan["requested"], {"model": "large-v3", "compute_type": "float16", "batch_size_max": 16})
        self.assertLessEqual(plan["estimate_gb"], 4.0 * server.HEADROOM)

    def test_manual_6_gb_uses_the_number_not_the_card(self) -> None:
        plan = self.plan(12.0, vram_mode="manual", vram_gb=6)
        self.assertEqual(plan["mode"], "manual")
        self.assertEqual(plan["budget_gb"], 6.0)
        # large float16 ci starebbe solo con lotto 3: large in int8 con lotto 9 e' meglio.
        self.assertEqual((plan["model"], plan["compute_type"], plan["batch_size"]), ("large-v3", "int8_float16", 4))
        self.assertLessEqual(plan["estimate_gb"], 6.0 * server.HEADROOM)

    def test_never_above_the_configured_max(self) -> None:
        self.assertEqual(self.plan(24.0, batch_size_max=8)["batch_size"], 8)

    def test_nothing_fits(self) -> None:
        plan = self.plan(1.0)
        self.assertFalse(plan["fits"])
        self.assertEqual((plan["model"], plan["batch_size"]), ("tiny", 1))

    def test_small_model_is_never_upgraded(self) -> None:
        plan = self.plan(4.0, model="small")
        self.assertEqual((plan["model"], plan["compute_type"]), ("small", "float16"))
        self.assertFalse(plan["downgraded"])

    def test_cpu_and_unknown_card(self) -> None:
        cpu = server.decide_vram(tunables(), "cpu", None)
        self.assertEqual((cpu["device"], cpu["compute_type"], cpu["estimate_gb"], cpu["budget_gb"]), ("cpu", "int8", None, None))
        unmeasured = self.plan(None)
        self.assertEqual((unmeasured["model"], unmeasured["batch_size"], unmeasured["budget_gb"]), ("large-v3", 16, None))
        self.assertAlmostEqual(unmeasured["estimate_gb"], 10.02, places=2)

    def test_describe_says_what_was_asked(self) -> None:
        line = server.describe_plan(self.plan(4.0))
        self.assertIn("medium int8_float16", line)
        self.assertIn("chiesto large-v3 float16", line)


class DriverBudgetTest(StateMixin, unittest.TestCase):
    """In automatico il budget e' quello che resta dopo le altre app, letto dal driver."""

    DRIVER = {"name": GPU_12["name"], "total_gb": 12.0, "used_gb": 3.0, "free_gb": 9.0}

    def test_other_apps_shrink_the_batch(self) -> None:
        with mock.patch.object(server, "nvidia_query", return_value=self.DRIVER):
            plan = server.decide_vram(tunables(), "cuda", GPU_12)
        # 12 - 3 degli altri = 9; all'85% sono 7,65: 3,1 + 0,9 + 0,9 fissi, poi 0,32 a elemento.
        self.assertEqual((plan["budget_gb"], plan["others_gb"], plan["batch_size"]), (9.0, 3.0, 8))

    def test_our_own_model_is_not_counted_as_others(self) -> None:
        server.STATE.update(model=object(), own_gb=4.0)
        with mock.patch.object(server, "nvidia_query", return_value={**self.DRIVER, "used_gb": 6.0}):
            self.assertAlmostEqual(server.others_gb(), 2.0)
        server.STATE.update(model=None)
        with mock.patch.object(server, "nvidia_query", return_value={**self.DRIVER, "used_gb": 6.0}):
            self.assertAlmostEqual(server.others_gb(), 6.0)

    def test_resident_aligner_is_ours_not_the_others(self) -> None:
        # Dalla seconda lezione l'allineatore resta in memoria: contarlo fra gli altri, e poi ancora
        # nel piano (ALIGN_GB), toglieva un gigabyte di lotto a ogni lezione.
        server.STATE.update(model=object(), own_gb=4.0, align_gb=0.9)
        server.STATE["align"]["it"] = ("allineatore", {})
        self.addCleanup(server.STATE.update, model=None, own_gb=0.0, align_gb=0.0)
        self.addCleanup(server.STATE["align"].clear)
        with mock.patch.object(server, "nvidia_query", return_value={**self.DRIVER, "used_gb": 7.9}):
            self.assertAlmostEqual(server.others_gb(), 3.0)
        server.STATE["align"].clear()
        with mock.patch.object(server, "nvidia_query", return_value={**self.DRIVER, "used_gb": 7.0}):
            self.assertAlmostEqual(server.others_gb(), 3.0, msg="senza allineatore non si toglie niente per lui")

    def test_manual_ignores_the_driver(self) -> None:
        with mock.patch.object(server, "nvidia_query", return_value=self.DRIVER):
            plan = server.decide_vram(tunables(vram_mode="manual", vram_gb=12.0), "cuda", GPU_12)
        self.assertEqual(plan["budget_gb"], 12.0)
        self.assertNotIn("others_gb", plan)

    def test_detect_gpu_asks_the_driver_not_torch(self) -> None:
        with mock.patch.object(server, "nvidia_query", return_value=self.DRIVER):
            self.assertEqual(server.detect_gpu(), GPU_12)


class ConfigureVramTest(StateMixin, unittest.TestCase):
    def test_manual_config_drives_the_state(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            path.write_text(json.dumps({"vram_mode": "manual", "vram_gb": 6, "accept_anonymous": False}), encoding="utf-8")
            with mock.patch.object(config, "resolve_device", return_value="cuda"), \
                    mock.patch.object(server, "detect_gpu", return_value=GPU_12), \
                    mock.patch.object(archive, "open_archive"), self.assertLogs("pampa", level="INFO") as logs:
                resolved = server.configure(config.load(path), path)
        self.assertEqual((resolved["model"], resolved["compute_type"], resolved["batch_size"]), ("large-v3", "int8_float16", 4))
        self.assertEqual((server.STATE["name"], server.STATE["compute_type"], server.STATE["batch_size"]), ("large-v3", "int8_float16", 4))
        self.assertEqual(server.STATE["tunables"]["batch_size_max"], 16)
        self.assertEqual(server.STATE["config_path"], path)
        self.assertTrue(any("non ci stava" in line for line in logs.output), logs.output)

    def test_manual_without_a_number_falls_back_to_auto(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            path.write_text(json.dumps({"vram_mode": "manual", "accept_anonymous": False}), encoding="utf-8")
            with mock.patch.object(config, "resolve_device", return_value="cuda"), \
                    mock.patch.object(server, "detect_gpu", return_value=GPU_12), \
                    mock.patch.object(archive, "open_archive"), self.assertLogs("pampa", level="WARNING"):
                server.configure(config.load(path), path)
        self.assertEqual(server.STATE["vram"]["mode"], "auto")
        self.assertEqual(server.STATE["batch_size"], 16)


class EnsureModelTest(StateMixin, unittest.TestCase):
    def test_reloads_when_the_settings_changed_under_it(self) -> None:
        """Il modello cambiato dall'app mentre si trascriveva: il lavoro dopo carica quello nuovo."""
        import sys
        import types

        loads: list[tuple] = []
        options: list[dict] = []

        def load_model(name, device, compute_type, **more):
            loads.append((name, device, compute_type))
            options.append(more)
            return f"modello {name}"

        fake = types.SimpleNamespace(load_model=load_model)
        old = object()
        server.STATE.update(model=old, loaded_as=("large-v3", "cpu", "int8"), name="large-v3", device="cpu", compute_type="int8")
        self.addCleanup(server.STATE.update, model=None, loaded_as=None)
        with mock.patch.dict(sys.modules, {"whisperx": fake}), self.assertLogs("pampa", level="INFO"):
            server.ensure_model()
            self.assertIs(server.STATE["model"], old, "stesse impostazioni: resta quello che c'e'")
            server.STATE["name"] = "medium"
            server.ensure_model()
        self.assertEqual(loads, [("medium", "cpu", "int8")])
        # Il VAD piu' severo arriva alla pipeline: e' la prima difesa contro il rumore.
        self.assertEqual(options[0]["vad_options"], server.VAD_OPTIONS)
        self.assertEqual(server.STATE["model"], "modello medium")
        self.assertEqual(server.STATE["loaded_as"], ("medium", "cpu", "int8"))

    def test_engine_keeps_the_model_it_started_with(self) -> None:
        """Le impostazioni cambiate a meta' lezione non fanno caricare un altro modello al pezzo dopo."""
        import sys
        import types

        loads: list[tuple] = []
        fake = types.SimpleNamespace(load_model=lambda name, device, compute_type: loads.append((name, device, compute_type)) or f"modello {name}")
        old = object()
        server.STATE.update(model=old, loaded_as=("large-v3", "cpu", "int8"), name="large-v3", device="cpu", compute_type="int8")
        self.addCleanup(server.STATE.update, model=None, loaded_as=None)
        engine = server.Engine()
        server.STATE.update(name="medium", compute_type="int8_float16")  # arriva da /v1/admin/settings
        with mock.patch.dict(sys.modules, {"whisperx": fake}):
            self.assertFalse(engine.needs_load())
            self.assertIs(engine.main_model(), old)
            self.assertIs(engine.main_model(), old, "anche al secondo pezzo")
        self.assertEqual(loads, [])
        self.assertTrue(server.Engine().needs_load(), "la lezione dopo carica quello nuovo")


class AlignModelTest(StateMixin, unittest.TestCase):
    """Un allineatore alla volta, e quello che occupa si sa: e' nostro, non degli altri programmi."""

    def test_only_the_current_language_stays(self) -> None:
        import sys
        import types

        loads: list[str] = []
        fake = types.SimpleNamespace(load_align_model=lambda language_code, device: loads.append(language_code) or (f"model-{language_code}", {}))
        server.STATE.update(device="cuda", align_gb=0.0)
        server.STATE["align"].clear()
        self.addCleanup(server.STATE.update, align_gb=0.0)
        self.addCleanup(server.STATE["align"].clear)
        with mock.patch.dict(sys.modules, {"whisperx": fake}), \
                mock.patch.object(server, "trust_sentence_splitter"), \
                mock.patch.object(server, "vram_gb", side_effect=[2.0, 3.2, 2.0, 2.5]), \
                mock.patch.object(server, "empty_cuda_cache") as emptied, self.assertLogs("pampa", level="INFO"):
            self.assertEqual(server.align_model_for("it")[0], "model-it")
            self.assertAlmostEqual(server.STATE["align_gb"], 1.2)
            server.align_model_for("it")
            self.assertEqual(loads, ["it"], "la stessa lingua non si ricarica")
            emptied.assert_not_called()
            self.assertEqual(server.align_model_for("en")[0], "model-en")
        self.assertEqual(loads, ["it", "en"])
        self.assertEqual(list(server.STATE["align"]), ["en"], "l'allineatore italiano se ne va")
        emptied.assert_called_once()
        self.assertAlmostEqual(server.STATE["align_gb"], 0.5)


class HealthDriverTest(StateMixin, unittest.TestCase):
    def test_health_uses_a_cached_driver_reading_and_never_torch(self) -> None:
        import sys
        import types

        touched: list[str] = []
        fake_torch = types.SimpleNamespace(cuda=types.SimpleNamespace(mem_get_info=lambda: touched.append("torch") or (0, 1)))
        server.STATE.update(device="cuda", gpu=GPU_12)
        with mock.patch.dict(sys.modules, {"torch": fake_torch}), \
                mock.patch.object(server, "nvidia_query", return_value=None) as query:
            data = server.health()
        self.assertEqual(data["vram_gb"], 0.0)
        self.assertEqual(touched, [], "/health e' aperto a tutti: niente contesto CUDA a comando")
        self.assertTrue(query.call_args_list)
        for call in query.call_args_list:
            self.assertGreaterEqual(call.kwargs.get("max_age_s", 2.0), server.HEALTH_DRIVER_AGE_S)
        self.assertIn("inflight", data)


class PreloadTest(StateMixin, unittest.TestCase):
    def test_preload_waits_its_turn(self) -> None:
        """Il caricamento anticipato dell'icona passa dalla fila: non carica un secondo modello sotto una lezione."""
        loaded: list[str] = []
        self.addCleanup(server.STATE.update, model=None)
        server.STATE["model"] = None

        async def scenario() -> list[list[str]]:
            gate = server.PriorityGate()
            seen: list[list[str]] = []
            release = asyncio.Event()

            async def lesson() -> None:
                async with gate.slot(0, key="lezione"):
                    await release.wait()

            with mock.patch.object(server, "GATE", gate), \
                    mock.patch.object(server, "ensure_model", side_effect=lambda: loaded.append("carico") or server.STATE.update(model=object())):
                holder = asyncio.create_task(lesson())
                await asyncio.sleep(0.01)
                preload = asyncio.create_task(server.preload_now())
                await asyncio.sleep(0.05)
                seen.append(list(loaded))
                release.set()
                await asyncio.wait_for(asyncio.gather(holder, preload), timeout=5)
                seen.append(list(loaded))
                # Gia' in memoria: una seconda volta non carica niente.
                await server.preload_now()
                seen.append(list(loaded))
            return seen

        self.assertEqual(asyncio.run(scenario()), [[], ["carico"], ["carico"]])


class RestartTest(StateMixin, unittest.TestCase):
    """Il riavvio da se' aspetta l'ultima richiesta a meta', poi chiude uvicorn con garbo."""

    class FakeServer:
        def __init__(self, stopped: threading.Event) -> None:
            self._stopped = stopped
            self._exit = False

        @property
        def should_exit(self) -> bool:
            return self._exit

        @should_exit.setter
        def should_exit(self, value: bool) -> None:
            self._exit = value
            if value:
                self._stopped.set()

    def test_waits_for_requests_in_flight(self) -> None:
        counter = server.RequestCounter()
        stopped = threading.Event()
        fake = self.FakeServer(stopped)
        order: list[str] = []
        exited = threading.Event()
        self.addCleanup(server.STATE.update, restarting=False, restart_wanted=False, busy=False)
        server.STATE.update(restart_wanted=True, restarting=False, busy=False)
        counter.enter()  # la risposta della lezione che ha chiesto il riavvio, ancora in viaggio
        with mock.patch.object(server, "REQUESTS", counter), \
                mock.patch.object(server, "GATE", server.PriorityGate()), \
                mock.patch.object(server, "SERVER", fake), \
                mock.patch.object(server, "STOPPED", stopped), \
                mock.patch.object(server, "ON_EXIT", lambda: order.append("icona")), \
                mock.patch.object(server, "RESTART_POLL_S", 0.02), \
                mock.patch.object(server, "_spawn_launcher", lambda launcher: order.append("lanciatore")), \
                mock.patch.object(server, "_exit_process", lambda: order.append("esci") or exited.set()), \
                self.assertLogs("pampa", level="WARNING"):
            server.restart_when_idle()
            self.assertTrue(server.STATE["restarting"], "da subito le richieste nuove hanno un 503")
            time.sleep(0.3)
            self.assertEqual(order, [], "una richiesta a meta': non si esce")
            self.assertFalse(fake.should_exit)
            counter.leave()
            self.assertTrue(exited.wait(5))
        self.assertTrue(fake.should_exit, "prima la chiusura di uvicorn, non os._exit a freddo")
        self.assertEqual(order, ["lanciatore", "icona", "esci"])

    def test_not_while_busy(self) -> None:
        self.addCleanup(server.STATE.update, restarting=False, restart_wanted=False, busy=False)
        server.STATE.update(restart_wanted=True, restarting=False, busy=True)
        with mock.patch.object(server, "_drain_and_restart", side_effect=AssertionError("non ora")):
            server.restart_when_idle()
        self.assertFalse(server.STATE.get("restarting"))
        self.assertTrue(server.STATE["restart_wanted"])


class ValidateTunablesTest(unittest.TestCase):
    def test_good_and_bad_values(self) -> None:
        current = tunables()
        merged = server.validate_tunables({"vram_mode": "manual", "vram_gb": 6}, current)
        self.assertEqual((merged["vram_mode"], merged["vram_gb"]), ("manual", 6.0))
        self.assertEqual(current["vram_mode"], "auto", "le impostazioni di adesso non si toccano")
        for bad in (
            {"model": "enorme"},
            {"compute_type": "int4"},
            {"vram_mode": "boh"},
            {"vram_mode": "manual"},
            {"vram_gb": 0.5},
            {"vram_gb": True},
            {"batch_size_max": 0},
            {"batch_size_max": 8.5},
            {"idle_minutes": -1},
            {"token": "x"},
            ["model"],
        ):
            with self.assertRaises(ValueError, msg=bad):
                server.validate_tunables(bad, current)


# --- chi entra ------------------------------------------------------------------------------------


class IdentifyTest(StateMixin, unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.worker = FakeWorker()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.worker.close()

    def setUp(self) -> None:
        super().setUp()
        self.worker.calls.clear()
        server.STATE.update(token="", index_url=self.worker.url, owner=FakeWorker.OWNER, accept_anonymous=False)

    def test_ticket_is_owner_and_cached(self) -> None:
        caller = server.identify("Bearer pt_good")
        self.assertEqual((caller.kind, caller.via), ("owner", "ticket"))
        server.identify("Bearer pt_good")
        self.assertEqual(self.worker.count("/v1/computer/verify"), 1, "il secondo passa dalla cache")
        _, until = server.TICKET_CACHE._items["pt_good"]
        self.assertLessEqual(until, time.time() + 3600 + 1)

    def test_positive_cache_is_capped_at_twelve_hours(self) -> None:
        self.assertTrue(server.verify_ticket("pt_far"))
        _, until = server.TICKET_CACHE._items["pt_far"]
        self.assertLessEqual(until, time.time() + server.TICKET_MAX_S + 1)
        self.assertGreater(until, time.time() + server.TICKET_MAX_S - 60)

    def test_expired_ticket_is_refused_even_if_worker_says_ok(self) -> None:
        self.assertFalse(server.verify_ticket("pt_old"))

    def test_bad_ticket_is_refused_and_negatively_cached(self) -> None:
        with self.assertRaises(server.AuthError) as raised:
            server.identify("Bearer pt_forged")
        self.assertIn("owner", str(raised.exception))
        with self.assertRaises(server.AuthError):
            server.identify("Bearer pt_forged")
        self.assertEqual(self.worker.count("/v1/computer/verify"), 1)
        _, until = server.TICKET_CACHE._items["pt_forged"]
        self.assertLessEqual(until, time.time() + server.NEGATIVE_S + 1)

    def test_ticket_of_another_owner_is_refused(self) -> None:
        server.STATE["owner"] = "altro@example.com"
        with self.assertRaises(server.AuthError):
            server.identify("Bearer pt_good")

    def test_worker_unreachable_refuses_without_crashing(self) -> None:
        server.STATE["index_url"] = f"http://127.0.0.1:{free_port()}"
        with self.assertLogs("pampa", level="WARNING"):
            self.assertFalse(server.verify_ticket("pt_good"))

    def test_no_credentials(self) -> None:
        with self.assertRaises(server.AuthError):
            server.identify("")
        server.STATE["accept_anonymous"] = True
        self.assertEqual(server.identify("").via, "anonymous")

    def test_bad_credentials_count_as_none_when_anonymous_is_on(self) -> None:
        server.STATE["accept_anonymous"] = True
        self.assertEqual(server.identify("Bearer pt_forged").via, "anonymous")
        self.assertEqual(server.identify("Bearer sbagliato").via, "anonymous")

    def test_config_token_still_works(self) -> None:
        server.STATE["token"] = "segreto"
        self.assertEqual(server.identify("Bearer segreto").via, "token")
        with self.assertRaises(server.AuthError):
            server.identify("Bearer segretO")
        with self.assertRaises(server.AuthError):
            server.identify("Bearer èèè")  # non ASCII: un no, non un'eccezione di compare_digest

    def test_guest(self) -> None:
        caller = server.identify("Bearer pg_friend")
        self.assertEqual((caller.kind, caller.name), ("guest", "Anna"))
        server.STATE["accept_anonymous"] = True
        with self.assertRaises(server.AuthError):
            server.identify("Bearer pg_revoked")

    def test_no_account_configured(self) -> None:
        server.STATE.update(index_url="", owner="")
        self.assertFalse(server.verify_ticket("pt_good"))
        self.assertEqual(self.worker.calls, [])


# --- il server vero, su una porta a caso -------------------------------------------------------------


class ServerTest(StateMixin, unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        import uvicorn

        cls.worker = FakeWorker()
        cls.archive_dir = tempfile.TemporaryDirectory()
        archive.open_archive(cls.archive_dir.name)
        server.STATE["port"] = 0
        cls.port = free_port()
        cls.uv = uvicorn.Server(uvicorn.Config(server.app, host="127.0.0.1", port=cls.port, log_level="warning"))
        cls.thread = threading.Thread(target=cls.uv.run, daemon=True)
        cls.thread.start()
        deadline = time.time() + 10
        while not cls.uv.started and time.time() < deadline:
            time.sleep(0.05)
        cls.base = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.uv.should_exit = True
        cls.thread.join(timeout=10)
        cls.worker.close()
        archive.ARCHIVE.db.close()
        cls.archive_dir.cleanup()

    def setUp(self) -> None:
        super().setUp()
        server.STATE.update(token="", index_url=self.worker.url, owner=FakeWorker.OWNER, accept_anonymous=False)

    def call(self, method: str, path: str, bearer: str = "", body: bytes | None = None, headers: dict | None = None):
        request = urllib.request.Request(self.base + path, data=body, method=method, headers=dict(headers or {}))
        if bearer:
            request.add_header("Authorization", f"Bearer {bearer}")
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read()

    def test_health_is_open_and_tells_auth(self) -> None:
        server.STATE["alignment"] = {"it": "ok"}
        status, _, body = self.call("GET", "/health")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["auth"], {"account": True, "anonymous": False})
        self.assertEqual(data["alignment"], {"it": "ok"})
        self.assertTrue(data["word_timestamps"])
        server.STATE["alignment"] = {"it": "errore: PermissionError: x"}
        self.assertFalse(json.loads(self.call("GET", "/health")[2])["word_timestamps"])

    def test_everything_else_needs_credentials(self) -> None:
        self.assertEqual(self.call("GET", "/v1/models")[0], 401)
        self.assertEqual(self.call("GET", "/v1/models", bearer="pt_good")[0], 200)
        self.assertEqual(self.call("GET", "/v1/files")[0], 401)
        server.STATE["accept_anonymous"] = True
        self.assertEqual(self.call("GET", "/v1/models")[0], 200)

    def test_guest_cannot_touch_the_archive_or_unload(self) -> None:
        status, _, body = self.call("GET", "/v1/files", bearer="pg_friend")
        self.assertEqual(status, 401)
        self.assertIn("proprietario", body.decode())
        self.assertEqual(self.call("POST", "/v1/admin/unload", bearer="pg_friend", body=b"")[0], 401)
        self.assertEqual(self.call("GET", "/v1/files", bearer="pt_good")[0], 200)

    def refused_before_body(self, request_line: str) -> str:
        """
        Manda solo gli header di un corpo da 50 MB e aspetta la risposta. Se il server volesse
        leggere il corpo prima di rispondere, aspetterebbe per sempre: il timeout fa fallire la prova.
        """
        with socket.create_connection(("127.0.0.1", self.port), timeout=5) as sock:
            sock.sendall(
                (
                    f"{request_line} HTTP/1.1\r\nHost: x\r\n"
                    "Content-Type: multipart/form-data; boundary=zzz\r\n"
                    "Content-Length: 50000000\r\n\r\n"
                ).encode()
            )
            sock.sendall(b"--zzz\r\n")
            return sock.recv(4096).decode(errors="replace")

    @staticmethod
    def multipart(audio: bytes = b"un audio finto") -> tuple[bytes, dict]:
        boundary = "pampatest"
        head = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="model"\r\n\r\nm\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="voce.m4a"\r\n'
            "Content-Type: audio/mp4\r\n\r\n"
        )
        body = head.encode() + audio + f"\r\n--{boundary}--\r\n".encode()
        return body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}

    def test_job_progress_is_readable_while_it_runs(self) -> None:
        server.JOBS.clear()
        release = threading.Event()
        inside = threading.Event()

        def fake(path: str, language: str | None, progress: server.JobProgress, **_: object) -> dict:
            progress.audio_s = 60.0
            progress.set("transcribing")
            progress.callback("transcribing")(70.0)
            inside.set()
            release.wait(10)
            return {
                "task": "transcribe", "language": "it", "duration": 1.0, "text": "ciao",
                "segments": [{"start": 0.0, "end": 1.0, "text": "ciao"}], "device_used": "cpu", "audio_s": 60.0,
            }

        job_id = "3f2b8c1e-5a6d-4e7f-8a9b-0c1d2e3f4a5b"
        body, headers = self.multipart()
        headers["X-Pampa-Job"] = job_id
        answer: dict = {}
        with mock.patch.object(server, "_transcribe", fake):
            post = threading.Thread(
                target=lambda: answer.update(r=self.call("POST", "/v1/audio/transcriptions", bearer="pg_friend", body=body, headers=headers))
            )
            post.start()
            try:
                self.assertTrue(inside.wait(10))
                status, _, got = self.call("GET", f"/v1/jobs/{job_id}", bearer="pg_friend")
                self.assertEqual(status, 200, got)
                data = json.loads(got)
                self.assertEqual((data["state"], data["fraction"], data["audio_s"]), ("transcribing", 0.7, 60.0))
                self.assertIsNone(data["position"])
                # Senza credenziali no, il proprietario si', un id che non c'e' e' un 404.
                self.assertEqual(self.call("GET", f"/v1/jobs/{job_id}")[0], 401)
                self.assertEqual(self.call("GET", f"/v1/jobs/{job_id}", bearer="pt_good")[0], 200)
                self.assertEqual(self.call("GET", "/v1/jobs/nessuno-lo-conosce", bearer="pg_friend")[0], 404)
                # Il lavoro del proprietario, all'ospite, non esiste.
                server.JOBS.open("job-del-proprietario", as_caller("owner", "pt_good"))
                self.assertEqual(self.call("GET", "/v1/jobs/job-del-proprietario", bearer="pg_friend")[0], 404)
                self.assertEqual(self.call("GET", "/v1/jobs/job-del-proprietario", bearer="pt_good")[0], 200)
            finally:
                release.set()
                post.join(10)

        status, _, got = answer["r"]
        self.assertEqual(status, 200, got)
        result = json.loads(got)
        self.assertEqual(result["audio_s"], 60.0)
        self.assertGreaterEqual(result["processing_s"], 0.0)
        data = json.loads(self.call("GET", f"/v1/jobs/{job_id}", bearer="pg_friend")[2])
        self.assertEqual((data["state"], data["fraction"]), ("done", 1.0))

    def test_delete_cancels_a_running_job(self) -> None:
        # «Annulla» dal telefono ferma il computer al lotto dopo, non solo la connessione del telefono.
        server.JOBS.clear()
        inside = threading.Event()

        def endless(path: str, language: str | None, progress: server.JobProgress, **_: object) -> dict:
            progress.set("transcribing")
            inside.set()
            for step in range(1000):
                progress.callback("transcribing")(step / 10)
                time.sleep(0.01)
            raise AssertionError("l'annullamento non e' arrivato")

        job_id = "7a1c2e3f-4b5d-4c6e-8f9a-0b1c2d3e4f5a"
        body, headers = self.multipart()
        headers["X-Pampa-Job"] = job_id
        answer: dict = {}
        with mock.patch.object(server, "_transcribe", endless):
            post = threading.Thread(
                target=lambda: answer.update(r=self.call("POST", "/v1/audio/transcriptions", bearer="pg_friend", body=body, headers=headers))
            )
            post.start()
            try:
                self.assertTrue(inside.wait(10))
                self.assertEqual(self.call("DELETE", "/v1/jobs/nessuno-lo-conosce", bearer="pg_friend")[0], 404)
                status, _, got = self.call("DELETE", f"/v1/jobs/{job_id}", bearer="pg_friend")
                self.assertEqual(status, 200, got)
            finally:
                post.join(20)
        self.assertEqual(answer["r"][0], 499, answer["r"][2])
        data = json.loads(self.call("GET", f"/v1/jobs/{job_id}", bearer="pg_friend")[2])
        self.assertEqual((data["state"], data["detail"]), ("failed", "annullata"))
        # La risposta parte appena la richiesta si stacca; il computer si ferma al lotto dopo.
        deadline = time.time() + 5
        while server.STATE["busy"] and time.time() < deadline:
            time.sleep(0.05)
        self.assertFalse(server.STATE["busy"])

    def test_same_recording_twice_is_transcribed_once(self) -> None:
        # Il tablet e il telefono mandano la stessa registrazione: una trascrizione, due risposte.
        server.JOBS.clear()
        data = b"una lezione" * 40
        blob = hashlib.sha256(data).hexdigest()
        archive.ARCHIVE.store(blob, "Voce 001.m4a", "audio/mp4", [data])
        calls = []
        release = threading.Event()
        inside = threading.Event()

        def slow(path: str, language: str | None, progress: server.JobProgress, **_: object) -> dict:
            calls.append(path)
            progress.set("transcribing")
            inside.set()
            for _ in range(500):
                if release.is_set():
                    break
                progress.callback("transcribing")(10.0)
                time.sleep(0.01)
            return {"task": "transcribe", "language": "it", "duration": 1.0, "text": "ciao",
                    "segments": [{"start": 0.0, "end": 1.0, "text": "ciao"}], "device_used": "cuda", "audio_s": 1.0}

        answers: dict = {}

        def send(name: str, job: str) -> None:
            body, headers = self.form({"source_sha256": blob, "language": "it"}, None)
            headers["X-Pampa-Job"] = job
            answers[name] = self.call("POST", "/v1/audio/transcriptions", bearer="pt_good", body=body, headers=headers)

        with mock.patch.object(server, "_transcribe", slow):
            first = threading.Thread(target=send, args=("a", "aaaaaaaa-1111-4111-8111-111111111111"))
            first.start()
            self.assertTrue(inside.wait(10))
            second = threading.Thread(target=send, args=("b", "bbbbbbbb-2222-4222-8222-222222222222"))
            second.start()
            time.sleep(1.5)
            # Il secondo segue il primo: stesso stato, e annullarlo non ferma il lavoro.
            data = json.loads(self.call("GET", "/v1/jobs/bbbbbbbb-2222-4222-8222-222222222222", bearer="pt_good")[2])
            self.assertEqual(data["state"], "transcribing")
            release.set()
            first.join(20)
            second.join(20)
        self.assertEqual(len(calls), 1)
        self.assertEqual((answers["a"][0], answers["b"][0]), (200, 200))
        self.assertEqual(json.loads(answers["a"][2])["text"], json.loads(answers["b"][2])["text"])

    def test_one_of_two_cancelled_keeps_the_other_going(self) -> None:
        server.JOBS.clear()
        data = b"un'altra lezione" * 40
        blob = hashlib.sha256(data).hexdigest()
        archive.ARCHIVE.store(blob, "Voce 002.m4a", "audio/mp4", [data])
        release = threading.Event()
        inside = threading.Event()
        stopped = []

        def slow(path: str, language: str | None, progress: server.JobProgress, **_: object) -> dict:
            progress.set("transcribing")
            inside.set()
            try:
                for _ in range(1000):
                    if release.is_set():
                        break
                    progress.callback("transcribing")(10.0)
                    time.sleep(0.01)
            except server.JobCancelled:
                stopped.append(True)
                raise
            return {"task": "transcribe", "language": "it", "duration": 1.0, "text": "ciao",
                    "segments": [{"start": 0.0, "end": 1.0, "text": "ciao"}], "device_used": "cuda", "audio_s": 1.0}

        answers: dict = {}

        def send(name: str, job: str) -> None:
            body, headers = self.form({"source_sha256": blob, "language": "it"}, None)
            headers["X-Pampa-Job"] = job
            answers[name] = self.call("POST", "/v1/audio/transcriptions", bearer="pt_good", body=body, headers=headers)

        with mock.patch.object(server, "_transcribe", slow):
            first = threading.Thread(target=send, args=("a", "cccccccc-1111-4111-8111-111111111111"))
            first.start()
            self.assertTrue(inside.wait(10))
            second = threading.Thread(target=send, args=("b", "dddddddd-2222-4222-8222-222222222222"))
            second.start()
            time.sleep(1.5)
            self.assertEqual(self.call("DELETE", "/v1/jobs/cccccccc-1111-4111-8111-111111111111", bearer="pt_good")[0], 200)
            first.join(20)
            self.assertEqual(answers["a"][0], 499)
            self.assertEqual(stopped, [], "il secondo aspetta ancora: il lavoro non si ferma")
            release.set()
            second.join(20)
        self.assertEqual(answers["b"][0], 200)

    def test_restarting_answers_503_before_reading_the_body(self) -> None:
        # Un proprietario riconosciuto: senza credenziali il 401 viene prima, com'e' giusto.
        server.STATE["restarting"] = True
        try:
            with mock.patch.object(server, "identify", return_value=as_caller("owner", "pt_good")):
                answer = self.refused_before_body("POST /v1/audio/transcriptions")
            self.assertIn("503", answer.splitlines()[0])
            self.assertIn("retry-after: 20", answer.lower())
        finally:
            server.STATE["restarting"] = False

    def test_restarting_refuses_every_new_request_but_not_health(self) -> None:
        # Non solo le lezioni: un PUT nell'archivio o uno scaricamento tagliati a meta' dal riavvio
        # sono lo stesso guasto. /health resta aperto: e' da li' che avvio.pyw vede la porta liberarsi.
        server.STATE["restarting"] = True
        try:
            status, headers, body = self.call("GET", "/v1/models", bearer="pt_good")
            self.assertEqual((status, json.loads(body)), (503, {"detail": "restarting"}))
            self.assertEqual({k.lower(): v for k, v in headers.items()}.get("retry-after"), "20")
            status, _, body = self.call("GET", "/health")
            self.assertEqual(status, 200)
            self.assertTrue(json.loads(body)["restarting"])
        finally:
            server.STATE["restarting"] = False

    def wait_inflight(self, value: int) -> int:
        deadline = time.time() + 5
        seen = server.REQUESTS.count
        while seen != value and time.time() < deadline:
            time.sleep(0.02)
            seen = server.REQUESTS.count
        return seen

    def test_health_counts_the_requests_in_flight(self) -> None:
        server.JOBS.clear()
        release = threading.Event()
        inside = threading.Event()

        def slow(path: str, language: str | None, progress: server.JobProgress, **_: object) -> dict:
            inside.set()
            release.wait(10)
            return {"task": "transcribe", "language": "it", "duration": 1.0, "text": "ciao",
                    "segments": [{"start": 0.0, "end": 1.0, "text": "ciao"}], "device_used": "cpu", "audio_s": 1.0}

        self.assertEqual(self.wait_inflight(0), 0)
        self.assertEqual(json.loads(self.call("GET", "/health")[2])["inflight"], 0, "/health non conta se stesso")
        body, headers = self.multipart()
        answer: dict = {}
        with mock.patch.object(server, "_transcribe", slow):
            post = threading.Thread(target=lambda: answer.update(r=self.call("POST", "/v1/audio/transcriptions", bearer="pt_good", body=body, headers=headers)))
            post.start()
            try:
                self.assertTrue(inside.wait(10))
                self.assertEqual(json.loads(self.call("GET", "/health")[2])["inflight"], 1)
            finally:
                release.set()
                post.join(10)
        self.assertEqual(answer["r"][0], 200)
        self.assertEqual(self.wait_inflight(0), 0, "finita la risposta, la richiesta non conta piu'")

    def test_failure_while_receiving_the_upload_removes_the_temporary(self) -> None:
        # Un errore dopo aver scritto il temporaneo ma prima del lavoro: il `finally` guardava `work`,
        # che non c'era ancora, e il file restava in %TEMP%.
        made: list[Path] = []
        real = tempfile.NamedTemporaryFile

        def recording(*args: object, **kwargs: object):
            handle = real(*args, **kwargs)
            made.append(Path(handle.name))
            return handle

        body, headers = self.form({"max_minutes": "30"}, b"audio che non arrivera' mai a WhisperX")
        with mock.patch.object(server.tempfile, "NamedTemporaryFile", recording), \
                mock.patch.object(server, "_positive_int", side_effect=server.HTTPException(status_code=400, detail="rotto")), \
                mock.patch.object(server, "_transcribe", side_effect=AssertionError("non deve partire")):
            status, _, raw = self.call("POST", "/v1/audio/transcriptions", bearer="pt_good", body=body, headers=headers)
        self.assertEqual((status, json.loads(raw)), (400, {"detail": "rotto"}))
        self.assertEqual(len(made), 1)
        self.assertFalse(made[0].exists(), "il temporaneo se ne va anche se il lavoro non e' mai nato")

    def test_delete_during_the_upload_does_not_start_the_work(self) -> None:
        server.JOBS.clear()
        job_id = "eeeeeeee-5555-4555-8555-555555555555"
        calls: list[str] = []
        body, headers = self.form({"language": "it"}, b"una lezione lunga" * 200)
        head = (
            f"POST /v1/audio/transcriptions HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer pt_good\r\n"
            f"X-Pampa-Job: {job_id}\r\nContent-Type: {headers['Content-Type']}\r\nContent-Length: {len(body)}\r\n\r\n"
        ).encode()
        with mock.patch.object(server, "_transcribe", side_effect=lambda path, *a, **k: calls.append(path)), \
                socket.create_connection(("127.0.0.1", self.port), timeout=10) as sock:
            sock.sendall(head + body[:200])
            # Il lavoro esiste da quando sono arrivati gli header: lo si annulla a caricamento a meta'.
            deadline = time.time() + 5
            while self.call("GET", f"/v1/jobs/{job_id}", bearer="pt_good")[0] != 200 and time.time() < deadline:
                time.sleep(0.02)
            self.assertEqual(self.call("DELETE", f"/v1/jobs/{job_id}", bearer="pt_good")[0], 200)
            sock.sendall(body[200:])
            answer = sock.recv(4096).decode(errors="replace")
        self.assertIn("499", answer.splitlines()[0])
        self.assertEqual(calls, [], "il computer non deve cominciare una lezione gia' annullata")
        data = json.loads(self.call("GET", f"/v1/jobs/{job_id}", bearer="pt_good")[2])
        self.assertEqual((data["state"], data["detail"]), ("failed", "annullata"))

    def test_cancel_after_admission_keeps_the_gate_until_the_thread_stops(self) -> None:
        # Il turno e' arrivato ma il thread sta ancora pianificando o decodificando (lo stato e' ancora
        # «queued»): annullare il task liberava la fila subito, e la lezione dopo partiva sopra.
        server.JOBS.clear()
        inside = threading.Event()
        ended = threading.Event()

        def planning(path: str, language: str | None, progress: server.JobProgress, **_: object) -> dict:
            inside.set()
            time.sleep(3.0)  # il piano della VRAM, la decodifica: niente controlli nel frattempo
            ended.set()
            progress.check_cancelled()
            raise AssertionError("l'annullamento non e' arrivato")

        job_id = "ffffffff-6666-4666-8666-666666666666"
        body, headers = self.multipart()
        headers["X-Pampa-Job"] = job_id
        answer: dict = {}
        with mock.patch.object(server, "_transcribe", planning):
            post = threading.Thread(target=lambda: answer.update(r=self.call("POST", "/v1/audio/transcriptions", bearer="pt_good", body=body, headers=headers)))
            post.start()
            try:
                self.assertTrue(inside.wait(10))
                self.assertEqual(self.call("DELETE", f"/v1/jobs/{job_id}", bearer="pt_good")[0], 200)
                post.join(10)
                self.assertEqual(answer["r"][0], 499)
                self.assertFalse(ended.is_set())
                self.assertTrue(server.STATE["busy"], "il thread lavora ancora: la fila resta sua")
            finally:
                deadline = time.time() + 10
                while server.STATE["busy"] and time.time() < deadline:
                    time.sleep(0.05)
        self.assertFalse(server.STATE["busy"])
        self.assertTrue(ended.is_set(), "la fila si libera solo quando il thread ha finito davvero")

    def test_delete_of_a_file_in_use_is_retryable(self) -> None:
        data = b"un file che qualcuno sta leggendo"
        sha = hashlib.sha256(data).hexdigest()
        archive.ARCHIVE.store(sha, "x.m4a", "audio/mp4", [data])
        with mock.patch.object(archive.ARCHIVE, "remove", side_effect=archive.BlobInUse("in uso")):
            status, headers, body = self.call("DELETE", f"/v1/files/{sha}", bearer="pt_good")
        self.assertEqual((status, json.loads(body)), (503, {"detail": "file_in_use"}))
        self.assertEqual({k.lower(): v for k, v in headers.items()}.get("retry-after"), str(archive.BLOB_IN_USE_RETRY_S))
        self.assertEqual(self.call("DELETE", f"/v1/files/{sha}", bearer="pt_good")[0], 200)

    def test_instance_is_in_health_and_jobs(self) -> None:
        self.use_config({"accept_anonymous": False})
        data = json.loads(self.call("GET", "/health")[2])
        self.assertEqual(data["instance"], server.INSTANCE)

    def test_failed_job_says_so(self) -> None:
        server.JOBS.clear()

        def broken(path: str, language: str | None, progress: server.JobProgress, **_: object) -> dict:
            progress.set("transcribing")
            raise ValueError("file rotto")

        body, headers = self.multipart()
        headers["X-Pampa-Job"] = "lavoro-che-fallisce"
        with mock.patch.object(server, "_transcribe", broken), self.assertLogs("pampa", level="ERROR"):
            status = self.call("POST", "/v1/audio/transcriptions", bearer="pt_good", body=body, headers=headers)[0]
        self.assertEqual(status, 500)
        data = json.loads(self.call("GET", "/v1/jobs/lavoro-che-fallisce", bearer="pt_good")[2])
        self.assertEqual((data["state"], data["detail"]), ("failed", "file rotto"))

    def test_upload_without_credentials_is_refused_before_the_body(self) -> None:
        answer = self.refused_before_body("POST /v1/audio/transcriptions")
        self.assertTrue(answer.startswith("HTTP/1.1 401"), answer[:80])
        answer = self.refused_before_body("PUT /v1/files/" + "a" * 64)
        self.assertTrue(answer.startswith("HTTP/1.1 401"), answer[:80])

    def test_archive_put_with_ticket(self) -> None:
        data = b"una registrazione finta" * 100
        sha = hashlib.sha256(data).hexdigest()
        status, _, body = self.call("PUT", f"/v1/files/{sha}", bearer="pt_good", body=data, headers={"Content-Type": "audio/mp4", "X-Pampa-Name": "voce.m4a"})
        self.assertEqual(status, 200, body)
        self.assertTrue(json.loads(body)["stored"])
        status, _, got = self.call("GET", f"/v1/files/{sha}", bearer="pt_good")
        self.assertEqual((status, got), (200, data))

    # --- il computer trascrive da se' -------------------------------------------------------------

    @staticmethod
    def form(fields: dict[str, str], audio: bytes | None = None, filename: str = "voce.m4a") -> tuple[bytes, dict]:
        """Un multipart coi campi dati e, se c'e', il file."""
        boundary = "pampaform"
        body = b""
        for key, value in fields.items():
            body += f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
        if audio is not None:
            body += (
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                "Content-Type: audio/mp4\r\n\r\n"
            ).encode() + audio + b"\r\n"
        body += f"--{boundary}--\r\n".encode()
        return body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}

    def transcribe_with(self, fields: dict[str, str], audio: bytes | None = None, bearer: str = "pt_good"):
        """
        POST con un `_transcribe` finto che si ricorda da quale file ha letto, se in quel momento il
        file c'era, e con quali argomenti.
        """
        seen: dict = {}

        def fake(path: str, language: str | None, progress: server.JobProgress, **kwargs: object) -> dict:
            seen.update(path=Path(path), existed=Path(path).exists(), content=Path(path).read_bytes(), **kwargs)
            return {
                "task": "transcribe", "language": "it", "duration": 1.0, "text": "ciao",
                "segments": [{"start": 0.0, "end": 1.0, "text": "ciao"}], "device_used": "cuda", "audio_s": 1.0,
                "chunks": 1,
            }

        body, headers = self.form(fields, audio)
        with mock.patch.object(server, "_transcribe", fake):
            status, _, raw = self.call("POST", "/v1/audio/transcriptions", bearer=bearer, body=body, headers=headers)
        return status, json.loads(raw), seen

    def test_health_lists_the_features(self) -> None:
        data = json.loads(self.call("GET", "/health")[2])
        self.assertEqual(set(data["features"]), {"by_ref", "archive_upload", "server_chunks", "file_meta", "prompt", "auto_chunks"})

    def test_diarize_is_a_feature_only_with_a_token_and_the_token_never_shows(self) -> None:
        server.STATE["hf_token"] = None
        data = json.loads(self.call("GET", "/health")[2])
        self.assertNotIn("diarize", data["features"])
        self.assertEqual((data["diarization"]["available"], data["diarization"]["configured"]), (False, False))
        server.STATE["hf_token"] = "hf_segretissimo"
        with mock.patch.object(server, "_pyannote_installed", return_value=True):
            raw = self.call("GET", "/health")[2]
        data = json.loads(raw)
        self.assertIn("diarize", data["features"])
        self.assertTrue(data["diarization"]["available"])
        self.assertEqual(data["diarization"]["model"], server.DIARIZE_MODEL)
        self.assertNotIn(b"hf_segretissimo", raw)

    def test_diarize_reaches_the_work_only_when_the_computer_can(self) -> None:
        server.STATE["hf_token"] = None
        _, _, seen = self.transcribe_with({"diarize": "1"}, audio=b"voce")
        self.assertNotIn("diarize", seen, "senza token la richiesta e' quella di sempre")
        server.STATE["hf_token"] = "hf_x"
        with mock.patch.object(server, "_pyannote_installed", return_value=True):
            _, _, seen = self.transcribe_with({"diarize": "1", "min_speakers": "3", "max_speakers": "2"}, audio=b"voce")
            self.assertEqual(seen["diarize"], server.DiarizeRequest(2, 3), "limiti girati se al contrario")
            _, _, plain = self.transcribe_with({}, audio=b"voce")
        self.assertNotIn("diarize", plain)

    def test_by_ref_reads_the_blob_and_keeps_it(self) -> None:
        data = b"una lezione gia' nell'archivio" * 50
        sha = hashlib.sha256(data).hexdigest()
        blob = archive.ARCHIVE.store(sha, "Voce 001.m4a", "audio/mp4", [data])["path"]
        status, answer, seen = self.transcribe_with({"source_sha256": sha, "language": "it"})
        self.assertEqual(status, 200, answer)
        self.assertEqual(seen["path"], blob)
        self.assertTrue(seen["existed"])
        self.assertTrue(blob.exists(), "un blob dell'archivio non si cancella mai")
        self.assertEqual((answer["source"], answer["archived"], answer["chunks"]), ("archive", True, 1))
        # Mandato anche il file, vince il blob.
        status, answer, seen = self.transcribe_with({"source_sha256": sha}, audio=b"altro")
        self.assertEqual((status, seen["path"], answer["source"]), (200, blob, "archive"))
        self.assertTrue(blob.exists())

    def test_missing_blob_is_a_404_the_app_understands(self) -> None:
        sha = hashlib.sha256(b"mai arrivato qui").hexdigest()
        status, answer, seen = self.transcribe_with({"source_sha256": sha})
        self.assertEqual((status, answer), (404, {"detail": "blob_missing"}))
        self.assertEqual(seen, {})
        self.assertEqual(self.transcribe_with({"source_sha256": "non-uno-sha"})[0], 400)
        self.assertEqual(self.transcribe_with({})[1], {"detail": "file_missing"})

    def test_guest_cannot_use_sha_or_archive(self) -> None:
        data = b"del proprietario"
        sha = hashlib.sha256(data).hexdigest()
        archive.ARCHIVE.store(sha, "x.m4a", "audio/mp4", [data])
        for fields, audio in (({"source_sha256": sha}, None), ({"source_sha256": sha}, data), ({"archive": "1"}, data)):
            status, answer, seen = self.transcribe_with(fields, audio, bearer="pg_friend")
            self.assertEqual((status, answer), (403, {"detail": "owner_only"}), fields)
            self.assertEqual(seen, {})
        # Il caricamento normale resta.
        status, answer, _ = self.transcribe_with({}, b"dell'ospite", bearer="pg_friend")
        self.assertEqual((status, answer["source"], answer["archived"]), (200, "upload", False))

    def test_archive_upload_stores_then_transcribes_from_the_blob(self) -> None:
        data = b"una registrazione nuova" * 80
        sha = hashlib.sha256(data).hexdigest()
        status, answer, seen = self.transcribe_with({"source_sha256": sha, "archive": "1", "name": "Voce 002.m4a"}, data)
        self.assertEqual(status, 200, answer)
        self.assertEqual((answer["archived"], answer["source"]), (True, "upload"))
        record = archive.ARCHIVE.get(sha)
        self.assertIsNotNone(record)
        self.assertEqual((record["name"], record["mime"], record["size"]), ("Voce 002.m4a", "audio/mp4", len(data)))
        self.assertEqual((seen["path"], seen["content"]), (record["path"], data))
        self.assertTrue(record["path"].exists())
        # La seconda volta c'e' gia': non si riscrive, si legge.
        status, answer, seen = self.transcribe_with({"source_sha256": sha, "archive": "true"}, data)
        self.assertEqual((status, answer["source"], answer["archived"]), (200, "archive", True))

    def test_archive_upload_with_the_wrong_sha_is_refused(self) -> None:
        wrong = hashlib.sha256(b"un altro file").hexdigest()
        status, answer, seen = self.transcribe_with({"source_sha256": wrong, "archive": "1"}, b"questo file")
        self.assertEqual((status, answer), (400, {"detail": "sha_mismatch"}))
        self.assertEqual(seen, {})
        self.assertIsNone(archive.ARCHIVE.get(wrong))
        self.assertEqual(list((Path(archive.ARCHIVE.root) / "tmp").glob("*.part")), [])

    def test_without_archive_the_upload_is_temporary(self) -> None:
        data = b"da non tenere" * 40
        sha = hashlib.sha256(data).hexdigest()
        status, answer, seen = self.transcribe_with({"source_sha256": sha, "name": "lezione.ogg"}, data)
        self.assertEqual(status, 200, answer)
        self.assertEqual((answer["archived"], answer["source"]), (False, "upload"))
        self.assertTrue(seen["existed"])
        self.assertEqual(seen["path"].suffix, ".ogg", "l'estensione viene da `name`")
        self.assertFalse(seen["path"].exists(), "il temporaneo se ne va a fine lavoro")
        self.assertIsNone(archive.ARCHIVE.get(sha), "niente nell'archivio senza archive=1")

    def test_prompt_and_max_minutes_reach_the_job(self) -> None:
        status, _, seen = self.transcribe_with({"prompt": "  Fichte, Io puro ", "max_minutes": "30"}, b"audio")
        self.assertEqual(status, 200)
        self.assertEqual((seen["prompt"], seen["max_minutes"]), ("Fichte, Io puro", 30))
        _, _, seen = self.transcribe_with({"max_minutes": "0"}, b"audio")
        self.assertEqual((seen["prompt"], seen["max_minutes"]), (None, None))

    def test_meta_of_a_real_sdocx(self) -> None:
        data = (HERE.parent / "core/src/test/resources/sdocx/fichte.sdocx").read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        archive.ARCHIVE.store(sha, "Fichte.sdocx", "application/sdoc", [data])
        status, _, raw = self.call("GET", f"/v1/files/{sha}/meta", bearer="pt_good")
        self.assertEqual(status, 200, raw)
        self.assertEqual(
            json.loads(raw),
            # 17/09 09:39:43 UTC la creazione, 18/09 09:13:23 l'ultima modifica.
            {"sha256": sha, "kind": "sdocx", "created_us": 1789637983096228, "modified_us": 1789722803594379, "recorded_us": None},
        )
        self.assertEqual(self.call("GET", f"/v1/files/{sha}/meta", bearer="pg_friend")[0], 401)
        self.assertEqual(self.call("GET", f"/v1/files/{'0' * 64}/meta", bearer="pt_good")[0], 404)

    def use_config(self, stored: dict) -> Path:
        """Un config.json temporaneo, letto e messo in uso come farebbe `main`, su una scheda da 12 GB finta."""
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "config.json"
        path.write_text(json.dumps(stored), encoding="utf-8")
        with mock.patch.object(config, "resolve_device", return_value="cuda"), \
                mock.patch.object(server, "detect_gpu", return_value=GPU_12), \
                mock.patch.object(archive, "open_archive"), self.assertLogs("pampa", level="INFO"):
            server.configure(config.load(path), path)
        server.STATE.update(token="", index_url=self.worker.url, owner=FakeWorker.OWNER, accept_anonymous=False)
        return path

    def post_json(self, path: str, payload: object, bearer: str = "pt_good"):
        return self.call("POST", path, bearer=bearer, body=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})

    def test_health_tells_gpu_and_vram(self) -> None:
        self.use_config({"accept_anonymous": False})
        data = json.loads(self.call("GET", "/health")[2])
        self.assertEqual(data["gpu"]["name"], GPU_12["name"])
        self.assertEqual(data["gpu"]["total_gb"], 12.0)
        self.assertIn("free_gb", data["gpu"])
        vram = data["vram"]
        self.assertEqual({key: vram[key] for key in ("mode", "budget_gb", "batch_size", "model", "compute_type")},
                         {"mode": "auto", "budget_gb": 12.0, "batch_size": 16, "model": "large-v3", "compute_type": "float16"})
        self.assertAlmostEqual(vram["estimate_gb"], 10.02, places=2)
        self.assertNotIn("breakdown", vram)
        server.STATE.update(device="cpu", vram=server.decide_vram(tunables(), "cpu", None))
        data = json.loads(self.call("GET", "/health")[2])
        self.assertIsNone(data["gpu"])
        self.assertEqual(data["vram"]["device"], "cpu")

    def test_admin_settings_are_for_the_owner_only(self) -> None:
        self.use_config({"accept_anonymous": False})
        self.assertEqual(self.call("GET", "/v1/admin/settings", bearer="pg_friend")[0], 401)
        self.assertEqual(self.post_json("/v1/admin/settings", {"batch_size_max": 4}, bearer="pg_friend")[0], 401)
        self.assertEqual(self.post_json("/v1/admin/estimate", {"batch_size_max": 4}, bearer="pg_friend")[0], 401)
        self.assertEqual(self.call("GET", "/v1/admin/settings")[0], 401)
        # Un ospite resta fuori anche con l'accesso libero acceso; chi non manda niente, li', e' il proprietario.
        server.STATE["accept_anonymous"] = True
        self.assertEqual(self.call("GET", "/v1/admin/settings", bearer="pg_friend")[0], 401)
        self.assertEqual(self.call("GET", "/v1/admin/settings")[0], 200)
        server.STATE.update(accept_anonymous=False, token="segreto")
        self.assertEqual(self.call("GET", "/v1/admin/settings", bearer="segreto")[0], 200)
        status, _, body = self.call("GET", "/v1/admin/settings", bearer="pt_good")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["settings"], tunables())
        self.assertEqual(data["vram"]["batch_size"], 16)
        self.assertIn("breakdown", data["vram"])
        self.assertIn("large-v3", data["models"])

    def test_admin_settings_are_saved_and_applied(self) -> None:
        path = self.use_config({"accept_anonymous": False, "port": 9000, "chiave_mia": 1})
        status, _, body = self.post_json("/v1/admin/settings", {"vram_mode": "manual", "vram_gb": 6, "idle_minutes": 5})
        self.assertEqual(status, 200, body)
        data = json.loads(body)
        self.assertEqual((data["vram"]["model"], data["vram"]["compute_type"], data["vram"]["batch_size"]), ("large-v3", "int8_float16", 4))
        self.assertFalse(data["unloaded"], "non c'era niente in memoria")
        self.assertEqual((server.STATE["compute_type"], server.STATE["batch_size"], server.STATE["idle_seconds"]), ("int8_float16", 4, 300))
        stored = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(stored, {"accept_anonymous": False, "port": 9000, "chiave_mia": 1, "vram_mode": "manual", "vram_gb": 6.0, "idle_minutes": 5})
        # Il tetto del lotto si scrive nella chiave di sempre, e al riavvio vale lo stesso piano.
        self.assertEqual(self.post_json("/v1/admin/settings", {"batch_size_max": 8})[0], 200)
        self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["batch_size"], 8)
        # 6 GB a mano: il massimo e' 8, ma dopo le misure del 23/09 ce ne stanno 4.
        self.assertEqual(server.STATE["batch_size"], 4)
        self.assertEqual(config.load(path)["vram_mode"], "manual")

    def test_admin_settings_refuse_bad_values_without_writing(self) -> None:
        path = self.use_config({"accept_anonymous": False})
        before = path.read_text(encoding="utf-8")
        for payload in ({"model": "enorme"}, {"vram_mode": "manual"}, {"batch_size_max": 0}, {"token": "x"}):
            status, _, body = self.post_json("/v1/admin/settings", payload)
            self.assertEqual(status, 400, payload)
            self.assertTrue(json.loads(body)["detail"])
        self.assertEqual(self.call("POST", "/v1/admin/settings", bearer="pt_good", body=b"non json", headers={"Content-Type": "application/json"})[0], 400)
        self.assertEqual(path.read_text(encoding="utf-8"), before)
        self.assertEqual(server.STATE["batch_size"], 16)

    def test_changing_the_model_unloads_the_old_one(self) -> None:
        self.use_config({"accept_anonymous": False})
        server.STATE.update(model=object(), loaded_as=("large-v3", "cuda", "float16"))
        self.addCleanup(server.STATE.update, model=None, loaded_as=None)
        with mock.patch.object(server, "unload_model", side_effect=lambda reason: server.STATE.update(model=None, loaded_as=None)) as unload:
            status, _, body = self.post_json("/v1/admin/settings", {"model": "medium"})
        self.assertEqual(status, 200, body)
        self.assertTrue(json.loads(body)["unloaded"])
        unload.assert_called_once()
        self.assertEqual(server.STATE["name"], "medium")

    def test_estimate_previews_without_saving(self) -> None:
        path = self.use_config({"accept_anonymous": False})
        before = path.read_text(encoding="utf-8")
        status, _, body = self.post_json("/v1/admin/estimate", {"vram_mode": "manual", "vram_gb": 4})
        self.assertEqual(status, 200, body)
        data = json.loads(body)
        self.assertEqual((data["vram"]["model"], data["vram"]["compute_type"], data["vram"]["batch_size"]), ("medium", "int8_float16", 4))
        self.assertEqual(data["settings"]["vram_gb"], 4.0)
        self.assertEqual(path.read_text(encoding="utf-8"), before)
        self.assertEqual((server.STATE["name"], server.STATE["batch_size"], server.STATE["tunables"]["vram_mode"]), ("large-v3", 16, "auto"))
        self.assertEqual(self.post_json("/v1/admin/estimate", {"vram_gb": 0})[0], 400)

    def test_pair_page_has_no_token_and_is_not_cached(self) -> None:
        server.STATE["token"] = "segretissimo"
        key = server.new_pairing_key()
        status, headers, body = self.call("GET", f"/pair?k={key}")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("cache-control") or headers.get("Cache-Control"), "no-store")
        self.assertNotIn("segretissimo", body.decode())
        self.assertNotIn("token=", server.pairing_link(8765))


# --- config, archivio, lanciatore, icona -----------------------------------------------------------


class ConfigTest(unittest.TestCase):
    def test_existing_config_gets_anonymous_on_and_keeps_its_keys(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            path.write_text(json.dumps({"owner": "a@b.c", "chiave_futura": 1}), encoding="utf-8")
            settings = config.load(path)
            self.assertTrue(settings["accept_anonymous"])
            stored = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(stored, {"owner": "a@b.c", "chiave_futura": 1, "accept_anonymous": True})

    def test_existing_config_with_token_stays_closed(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            path.write_text(json.dumps({"token": "x"}), encoding="utf-8")
            self.assertFalse(config.load(path)["accept_anonymous"])

    def test_new_config_is_closed(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            self.assertFalse(config.load(path)["accept_anonymous"])
            self.assertFalse(path.exists())

    def test_explicit_value_is_respected_and_set_value_keeps_the_rest(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            path.write_text(json.dumps({"accept_anonymous": False, "port": 9000}), encoding="utf-8")
            self.assertFalse(config.load(path)["accept_anonymous"])
            config.set_value("accept_anonymous", True, path)
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"accept_anonymous": True, "port": 9000})

    def test_concurrent_writers_do_not_lose_keys(self) -> None:
        # L'icona, /v1/admin/settings e /v1/pair/bind scrivono da thread diversi: nessuno perde la sua chiave.
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            path.write_text(json.dumps({"port": 9000}), encoding="utf-8")
            start = threading.Barrier(16)

            def write(index: int) -> None:
                start.wait()
                for round_ in range(5):
                    config.set_value(f"chiave_{index}", round_, path)

            threads = [threading.Thread(target=write, args=(index,)) for index in range(16)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(20)
            stored = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(stored, {"port": 9000, **{f"chiave_{index}": 4 for index in range(16)}})
            self.assertEqual([item.name for item in Path(folder).iterdir()], ["config.json"], "nessun temporaneo resta")

    def test_failed_write_leaves_the_old_file_whole(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            before = json.dumps({"owner": "a@b.c", "accept_anonymous": False})
            path.write_text(before, encoding="utf-8")
            with mock.patch.object(config.os, "replace", side_effect=OSError("disco pieno")), self.assertRaises(OSError):
                config.set_values({"owner": "altro@b.c", "index_url": "https://x"}, path)
            self.assertEqual(path.read_text(encoding="utf-8"), before)
            self.assertEqual([item.name for item in Path(folder).iterdir()], ["config.json"])


class ArchiveTest(unittest.TestCase):
    def test_old_parts_are_swept_new_ones_kept(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            tmp = Path(folder) / "tmp"
            tmp.mkdir()
            old = tmp / "vecchio.part"
            young = tmp / "giovane.part"
            old.write_bytes(b"x")
            young.write_bytes(b"y")
            past = time.time() - archive.STALE_PART_S - 10
            os.utime(old, (past, past))
            store = archive.Archive(Path(folder))
            try:
                self.assertFalse(old.exists())
                self.assertTrue(young.exists())
            finally:
                store.db.close()

    def test_stalled_stream_times_out(self) -> None:
        async def never() -> object:
            yield b"primo"
            await asyncio.sleep(3600)
            yield b"mai"

        async def scenario() -> list[bytes]:
            chunks = archive._sync_chunks(never(), timeout_s=0.3)
            got: list[bytes] = []

            def consume() -> None:
                for chunk in chunks:
                    got.append(chunk)

            with self.assertRaises(archive.StalledUpload):
                await asyncio.get_running_loop().run_in_executor(archive.EXECUTOR, consume)
            return got

        self.assertEqual(asyncio.run(scenario()), [b"primo"])

    def test_store_cleans_its_part_when_the_stream_stalls(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            store = archive.Archive(Path(folder))
            try:
                def chunks():
                    yield b"abc"
                    raise archive.StalledUpload("ferma")

                with self.assertRaises(archive.StalledUpload):
                    store.store("0" * 64, "x.m4a", "audio/mp4", chunks())
                self.assertEqual(list((Path(folder) / "tmp").glob("*.part")), [])
            finally:
                store.db.close()

    def test_same_blob_stored_while_the_first_is_open(self) -> None:
        # Il `PUT` dell'archivio e il caricamento con `archive=1` della stessa registrazione: il secondo
        # trovava il blob gia' scritto (e aperto da ffmpeg) e Windows rifiutava il rename, WinError 5.
        data = b"la stessa registrazione, due volte"
        sha = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as folder:
            store = archive.Archive(Path(folder))
            try:
                path = store.store(sha, "x.opus", "audio/opus", [data])["path"]
                with path.open("rb"):
                    again = store.store(sha, "x.opus", "audio/opus", [data])
                self.assertEqual(again["path"], path)
                self.assertEqual(path.read_bytes(), data)
                self.assertEqual(list((Path(folder) / "tmp").glob("*.part")), [])
                self.assertEqual(store.stats(), (1, len(data)))
            finally:
                store.db.close()

    def test_rename_refused_for_a_moment_is_retried(self) -> None:
        data = b"un file che l'antivirus guarda un istante"
        sha = hashlib.sha256(data).hexdigest()
        real_replace = os.replace
        calls: list[int] = []

        def flaky(source, destination):
            calls.append(1)
            if len(calls) < 3:
                raise PermissionError(5, "Accesso negato")
            real_replace(source, destination)

        with tempfile.TemporaryDirectory() as folder:
            store = archive.Archive(Path(folder))
            try:
                with mock.patch.object(archive.os, "replace", flaky), mock.patch.object(archive, "PLACE_PAUSE_S", 0.0):
                    path = store.store(sha, "x.m4a", "audio/mp4", [data])["path"]
                self.assertEqual(len(calls), 3)
                self.assertEqual(path.read_bytes(), data)
                with mock.patch.object(archive.os, "replace", side_effect=PermissionError(5, "Accesso negato")), \
                        mock.patch.object(archive, "PLACE_PAUSE_S", 0.0):
                    other = b"un altro file, che non entra mai"
                    with self.assertRaises(PermissionError):
                        store.store(hashlib.sha256(other).hexdigest(), "y.m4a", "audio/mp4", [other])
                self.assertEqual(list((Path(folder) / "tmp").glob("*.part")), [], "il .part rifiutato non resta")
            finally:
                store.db.close()

    def test_remove_keeps_the_row_when_the_file_is_in_use(self) -> None:
        # Su Windows un file aperto non si cancella: la riga deve restare, o il file resta orfano per sempre.
        data = b"una registrazione che qualcuno sta scaricando"
        sha = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as folder:
            store = archive.Archive(Path(folder))
            try:
                path = store.store(sha, "x.m4a", "audio/mp4", [data])["path"]
                with mock.patch.object(Path, "unlink", side_effect=PermissionError("in uso da un altro processo")):
                    with self.assertRaises(archive.BlobInUse):
                        store.remove(sha)
                self.assertTrue(path.exists())
                self.assertIsNotNone(store.get(sha), "la riga resta: la prossima DELETE lo ritrova")
                self.assertTrue(store.remove(sha))
                self.assertFalse(path.exists())
                self.assertIsNone(store.get(sha))
                self.assertEqual(store.stats(), (0, 0))
            finally:
                store.db.close()


class FileMetaTest(unittest.TestCase):
    def sdocx(self, folder: str, end_tag: bytes | None, note: bytes | None) -> Path:
        import zipfile

        path = Path(folder) / "nota.sdocx"
        with zipfile.ZipFile(path, "w") as bundle:
            if end_tag is not None:
                bundle.writestr("end_tag.bin", end_tag)
            if note is not None:
                bundle.writestr("note.note", note)
        return path

    @staticmethod
    def stamps(size: int, at: dict[int, int]) -> bytes:
        import struct

        data = bytearray(size)
        for offset, value in at.items():
            struct.pack_into("<q", data, offset, value)
        return bytes(data)

    def test_falls_back_to_note_and_refuses_implausible_dates(self) -> None:
        created, modified = 1_700_000_000_000_000, 1_700_000_500_000_000
        now = 1_800_000_000_000_000
        with tempfile.TemporaryDirectory() as folder:
            # end_tag con la creazione dopo la modifica: si passa a note.note.
            path = self.sdocx(folder, self.stamps(148, {8: created, 46: modified}), self.stamps(64, {24: created, 32: modified}))
            self.assertEqual(archive.sdocx_dates(path, now), (created, modified))
            # Tutte e due nel futuro, o prima del 2010: niente.
            future = now + 10 * 86_400_000_000
            path = self.sdocx(folder, self.stamps(148, {8: future, 46: future}), None)
            self.assertEqual(archive.sdocx_dates(path, now), (None, None))
            path = self.sdocx(folder, self.stamps(148, {8: 1_000, 46: 10}), None)
            self.assertEqual(archive.sdocx_dates(path, now), (None, None))
            # Non uno zip, o uno zip senza i due file.
            broken = Path(folder) / "rotto.sdocx"
            broken.write_bytes(b"non e' uno zip")
            self.assertEqual(archive.sdocx_dates(broken, now), (None, None))
            self.assertEqual(archive.sdocx_dates(self.sdocx(folder, None, None), now), (None, None))

    def test_audio_creation_time_via_ffprobe(self) -> None:
        import shutil
        import subprocess

        ffmpeg = shutil.which("ffmpeg")
        if ffmpeg is None or shutil.which("ffprobe") is None:
            self.skipTest("ffmpeg non installato")
        with tempfile.TemporaryDirectory() as folder:
            dated = Path(folder) / "dated.m4a"
            plain = Path(folder) / "plain.m4a"
            base = [ffmpeg, "-v", "error", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", "-c:a", "aac"]
            subprocess.run([*base, "-metadata", "creation_time=2025-10-09T08:15:30Z", str(dated)], check=True, timeout=60)
            subprocess.run([*base, str(plain)], check=True, timeout=60)
            self.assertEqual(archive.audio_recorded_us(dated), 1_759_997_730_000_000)
            self.assertIsNone(archive.audio_recorded_us(plain))
            record = {"sha256": "a" * 64, "name": "x.m4a", "mime": "audio/mp4", "ext": "m4a", "path": dated}
            self.assertEqual(archive.file_meta(record)["kind"], "audio")
            self.assertEqual(archive.file_meta(dict(record, path=plain, mime="application/pdf", ext="pdf"))["kind"], "other")

    FFMPEG_STDERR = (
        b"Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'voce.m4a':\n"
        b"  Metadata:\n"
        b"    major_brand     : M4A \n"
        b"    creation_time   : 2025-10-09T08:15:30.000000Z\n"
        b"  Duration: 00:00:01.02, start: 0.000000, bitrate: 3 kb/s\n"
        b"  Stream #0:0[0x1](und): Audio: aac (LC) (mp4a / 0x6134706D), 16000 Hz, mono, fltp, 1 kb/s (default)\n"
        b"      Metadata:\n"
        b"        creation_time   : 2025-10-09T08:15:30.000000Z\n"
        b"At least one output file must be specified\n"
    )

    def test_without_ffprobe_ffmpeg_tells_the_date(self) -> None:
        # Il computer installato col setup ha solo il ffmpeg di imageio-ffmpeg in bin/, senza ffprobe.
        import types

        commands: list[list[str]] = []

        def run(command, **_: object):
            commands.append(command)
            return types.SimpleNamespace(returncode=1, stdout=b"", stderr=self.FFMPEG_STDERR)

        which = {"ffprobe": None, "ffmpeg": r"C:\companion\bin\ffmpeg.exe"}
        with mock.patch.object(archive.shutil, "which", side_effect=which.get), mock.patch.object(archive.subprocess, "run", run):
            self.assertEqual(archive.audio_recorded_us(Path("voce.m4a")), 1_759_997_730_000_000)
        self.assertEqual(commands[0][0], which["ffmpeg"])
        self.assertIn("-i", commands[0])

    def test_without_ffprobe_or_ffmpeg_warns_once(self) -> None:
        archive._WARNED.clear()
        self.addCleanup(archive._WARNED.clear)
        with mock.patch.object(archive.shutil, "which", return_value=None), self.assertLogs("pampa", level="WARNING") as logs:
            for _ in range(5):
                self.assertIsNone(archive.audio_recorded_us(Path("voce.m4a")))
        self.assertEqual(len(logs.output), 1, "una riga, non una per nota")

    def test_real_ffmpeg_without_ffprobe(self) -> None:
        import shutil
        import subprocess

        ffmpeg = shutil.which("ffmpeg")
        if ffmpeg is None:
            self.skipTest("ffmpeg non installato")
        with tempfile.TemporaryDirectory() as folder:
            dated = Path(folder) / "dated.m4a"
            plain = Path(folder) / "plain.m4a"
            base = [ffmpeg, "-v", "error", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", "-c:a", "aac"]
            subprocess.run([*base, "-metadata", "creation_time=2025-10-09T08:15:30Z", str(dated)], check=True, timeout=60)
            subprocess.run([*base, str(plain)], check=True, timeout=60)
            which = {"ffprobe": None, "ffmpeg": ffmpeg}
            with mock.patch.object(archive.shutil, "which", side_effect=which.get):
                self.assertEqual(archive.audio_recorded_us(dated), 1_759_997_730_000_000)
                self.assertIsNone(archive.audio_recorded_us(plain))


def load_avvio():
    loader = importlib.machinery.SourceFileLoader("avvio", str(HERE / "avvio.pyw"))
    spec = importlib.util.spec_from_loader("avvio", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class AvvioTest(unittest.TestCase):
    class Process:
        def __init__(self, code: int | None) -> None:
            self.code = code
            self.killed = False

        def poll(self) -> int | None:
            return self.code

        def kill(self) -> None:
            self.killed = True

    def test_live_but_slow_is_left_alone(self) -> None:
        avvio = load_avvio()
        process = self.Process(None)
        with mock.patch.object(avvio, "SLOW_START_S", 0.2), mock.patch.object(avvio, "healthy", return_value=False), mock.patch.object(avvio.time, "sleep", lambda _: None):
            self.assertEqual(avvio.wait_for(process, 1), "slow")
        self.assertFalse(process.killed)

    def test_exit_code_is_reported(self) -> None:
        avvio = load_avvio()
        with mock.patch.object(avvio, "healthy", return_value=False):
            self.assertEqual(avvio.wait_for(self.Process(3), 1), 3)

    def test_slow_start_does_not_relaunch(self) -> None:
        avvio = load_avvio()
        launches: list[int] = []

        def launch() -> AvvioTest.Process:
            launches.append(1)
            return self.Process(None)

        with mock.patch.object(avvio, "healthy", return_value=False), mock.patch.object(avvio, "launch", launch), \
                mock.patch.object(avvio, "wait_for", return_value="slow"), mock.patch.object(avvio.time, "sleep", lambda _: None), \
                mock.patch.object(avvio, "log", lambda _: None):
            avvio.main()
        self.assertEqual(len(launches), 1)


class TrayTest(unittest.TestCase):
    def test_apostrophe_in_paths_is_escaped(self) -> None:
        import tray

        command = tray.shortcut_command(Path(r"C:\Users\D'Amico\a.lnk"), Path(r"C:\py\pythonw.exe"), Path(r"C:\Pampa's\avvio.pyw"))
        self.assertIn(r"CreateShortcut('C:\Users\D''Amico\a.lnk')", command)
        self.assertIn(r"""$s.Arguments = '"C:\Pampa''s\avvio.pyw"'""", command)
        self.assertIn(r"$s.WorkingDirectory = 'C:\Pampa''s'", command)
        # Ogni apice aperto si chiude: il numero di apici singoli e' pari.
        self.assertEqual(command.count("'") % 2, 0)

    def test_update_waits_for_requests_in_flight(self) -> None:
        # Un telefono che sta caricando una lezione non e' ne' «busy» ne' in fila, ma il setup lo taglierebbe.
        import tray

        counter = server.RequestCounter()
        with mock.patch.object(server, "REQUESTS", counter), mock.patch.object(server, "GATE", server.PriorityGate()), \
                mock.patch.dict(server.STATE, {"busy": False}):
            self.assertTrue(tray.idle_for_update())
            counter.enter()
            self.assertFalse(tray.idle_for_update())
            counter.leave()
            self.assertTrue(tray.idle_for_update())

    def test_the_voices_token_goes_to_config_and_to_the_server(self) -> None:
        import tray

        with mock.patch.object(tray.config, "set_values") as written, \
                mock.patch.dict(server.STATE, {"hf_token": None, "diarization_denied": True}):
            tray.save_hf_token("hf_nuovo")
            written.assert_called_once_with({"hf_token": "hf_nuovo", "hf_token_disabled": False})
            self.assertEqual(server.STATE["hf_token"], "hf_nuovo")
            self.assertFalse(server.STATE["diarization_denied"], "il rifiuto era del token di prima")
            self.assertIn("accesa", tray.voices_label())
            tray.save_hf_token("")
            self.assertIsNone(server.STATE["hf_token"], "un token vuoto spegne la separazione")
            # «Togli il token» resta tolto anche dopo il riavvio, con HF_TOKEN nell'ambiente.
            self.assertEqual(written.call_args.args[0], {"hf_token": "", "hf_token_disabled": True})
            self.assertNotIn("accesa", tray.voices_label())

    @unittest.skipUnless(os.name == "nt", "i collegamenti .lnk sono di Windows")
    def test_the_startup_link_of_another_copy_is_not_taken(self) -> None:
        import subprocess

        import tray

        with tempfile.TemporaryDirectory() as root:
            mine = Path(root) / "Pampa notes" / "companion"
            other = Path(root) / "Pampa notes" / "companion-prova"
            link = Path(root) / "Pampa Notes companion.lnk"
            command = tray.shortcut_command(link, mine / ".venv" / "Scripts" / "pythonw.exe", mine / "avvio.pyw")
            done = subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True, timeout=60)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertTrue(tray.shortcut_mentions(link, mine))
            self.assertFalse(tray.shortcut_mentions(link, other), "una copia accanto non se lo prende")
            self.assertFalse(tray.shortcut_mentions(Path(root) / "non-ce.lnk", mine))

    def test_pythonw_without_a_console_gets_a_log_instead_of_nothing(self) -> None:
        import sys as _sys

        import tray

        with tempfile.TemporaryDirectory() as root, mock.patch.object(_sys, "stdout", None), \
                mock.patch.object(_sys, "stderr", None):
            tray.ensure_std_streams(Path(root) / "logs")
            # Quello che uvicorn chiede configurando il suo registro: prima era None.isatty().
            self.assertFalse(_sys.stdout.isatty())
            print("una riga", file=_sys.stderr)
            _sys.stdout.close()
            self.assertIn("una riga", (Path(root) / "logs" / "tray-stderr.log").read_text(encoding="utf-8"))

    def test_the_token_check_runs_off_the_window_thread(self) -> None:
        import tray

        release = threading.Event()
        seen: list[str] = []

        def slow(token: str) -> tuple[bool, str]:
            seen.append(threading.current_thread().name)
            release.wait(5)
            return True, "Tutto pronto"

        box = tray.check_in_background("hf_x", check=slow)
        # Torna subito, con la risposta ancora da arrivare: la finestra intanto disegna.
        self.assertFalse(box["done"])
        release.set()
        for _ in range(100):
            if box["done"]:
                break
            time.sleep(0.02)
        self.assertEqual((box["done"], box["message"]), (True, "Tutto pronto"))
        self.assertEqual(seen, ["controllo-hf"])
        broken = tray.check_in_background("hf_x", check=lambda token: 1 / 0)
        for _ in range(100):
            if broken["done"]:
                break
            time.sleep(0.02)
        self.assertIn("Non riesco a controllare", broken["message"])


class AddressTest(unittest.TestCase):
    def test_real_lan_goes_before_virtual_adapters(self) -> None:
        with mock.patch.object(server, "_primary_ipv4", return_value="192.168.1.20"), \
                mock.patch.object(server.socket, "getaddrinfo", return_value=[(2, 0, 0, "", ("172.24.160.1", 0)), (2, 0, 0, "", ("100.101.1.2", 0)), (2, 0, 0, "", ("192.168.1.20", 0))]):
            self.assertEqual(server.local_addresses(8765)[0], "http://192.168.1.20:8765")
        with mock.patch.object(server, "_primary_ipv4", return_value=None), \
                mock.patch.object(server.socket, "getaddrinfo", return_value=[(2, 0, 0, "", ("172.24.160.1", 0)), (2, 0, 0, "", ("10.0.0.5", 0))]):
            self.assertEqual(server.local_addresses(8765)[0], "http://10.0.0.5:8765")


class SentenceSplitterTest(unittest.TestCase):
    def test_italian_punkt_opens_after_trust(self) -> None:
        """Il guasto vero: senza [trust_sentence_splitter], dentro l'app di Claude, NLTK rifiutava il file."""
        import nltk

        try:
            server.trust_sentence_splitter("it")
        except Exception as error:  # noqa: BLE001 — senza rete e senza dati non c'e' niente da provare
            self.skipTest(f"punkt_tab non disponibile: {error}")
        splitter = nltk.data.load("tokenizers/punkt_tab/italian.pickle")
        spans = list(splitter.span_tokenize("Buongiorno a tutti. Oggi parliamo di Fichte."))
        self.assertEqual(len(spans), 2)


# --- la lingua sul rumore, l'allineatore, i titoli di coda, l'eco (24/09) ---------------------------


class Voter(FakeEngine):
    """Risponde a ogni finestra con la lingua e la probabilita' della lista, nell'ordine."""

    def __init__(self, answers: list[tuple[str, float | None]]) -> None:
        super().__init__(None)
        self.answers = list(answers)

    def needs_load(self) -> bool:
        return False

    def language_vote(self, audio) -> tuple[str | None, float | None]:
        return self.answers.pop(0)


class NoisyLanguageTest(StateMixin, unittest.TestCase):
    """Sul rumore la lingua e' un tiro a caso: vota solo chi e' sicuro."""

    def vote(self, answers: list[tuple[str, float | None]]) -> str | None:
        import numpy

        energies = numpy.full(int(300 / 0.02), 0.01, dtype=numpy.float32)
        source = server.LoadedAudio(numpy.zeros(300 * RATE, dtype=numpy.float32), RATE)
        source._energies = energies
        with self.assertLogs("pampa", level="INFO"):
            return server.spoken_language(source, 0.02, Voter(answers), RecordingProgress())

    def test_no_confident_vote_means_no_language(self) -> None:
        # Il registro vero delle ore mute di «Napoli 18h»: `lingua: nn (nn, nn, haw)`.
        self.assertIsNone(self.vote([("nn", 0.47), ("nn", 0.52), ("haw", 0.21)]))

    def test_only_confident_votes_count(self) -> None:
        self.assertEqual(self.vote([("nn", 0.4), ("it", 0.95), ("nn", 0.45)]), "it")
        self.assertEqual(self.vote([("nn", 0.3), ("it", 0.9), ("en", 0.8)]), "it", "a pari merito, la finestra piu' parlata")

    def test_a_model_without_probabilities_votes_as_before(self) -> None:
        self.assertEqual(self.vote([("de", None), ("it", None), ("it", None)]), "it")

    def test_the_probability_comes_from_the_encoder(self) -> None:
        import sys
        import types

        import numpy

        seen: dict[str, object] = {}

        def log_mel_spectrogram(audio, n_mels, padding):
            seen.update(samples=len(audio), n_mels=n_mels, padding=padding)
            return "mel"

        native = types.SimpleNamespace(detect_language=lambda encoded: [[("<|it|>", 0.93), ("<|en|>", 0.02)]])
        whisper = types.SimpleNamespace(feat_kwargs={"feature_size": 128}, encode=lambda mel: f"enc({mel})", model=native)
        engine = server.Engine()
        engine.main_model = lambda: types.SimpleNamespace(model=whisper)
        audio_module = types.SimpleNamespace(N_SAMPLES=480000, log_mel_spectrogram=log_mel_spectrogram)
        with mock.patch.dict(sys.modules, {"whisperx": types.SimpleNamespace(audio=audio_module), "whisperx.audio": audio_module}):
            language, probability = engine.language_vote(numpy.zeros(16000 * 10, dtype=numpy.float32))
        self.assertEqual((language, probability), ("it", 0.93))
        self.assertEqual(seen, {"samples": 160000, "n_mels": 128, "padding": 320000})


class EmptyModel(FakeModel):
    """Whisper che sul rumore non trova niente, e tira a indovinare la lingua."""

    def transcribe(self, audio: object, batch_size: int, language: str | None, progress_callback=None) -> dict:
        return {"language": language or "nn", "segments": []}


class RealAligner(FakeEngine):
    """Allinea passando da [server.align_model_for] vero, con WhisperX finto."""

    def align(self, segments, language, audio, device, progress_callback=None):
        self.align_devices.append(device)
        server.align_model_for(language, device)
        return segments


class AlignmentLanguagesTest(StateMixin, unittest.TestCase):
    """Nessun allineatore per niente, e mai un allineatore da un miliardo di parametri per il rumore."""

    def setUp(self) -> None:
        # numpy si importa prima di fingere WhisperX, e torch non si importa affatto ([is_oom] lo
        # farebbe): `patch.dict(sys.modules)` all'uscita toglie quello che si e' importato dentro, e
        # numpy e torch non si lasciano caricare due volte nello stesso processo.
        import numpy  # noqa: F401

        oom = mock.patch.object(server, "is_oom", lambda error: "out of memory" in str(error).lower())
        oom.start()
        self.addCleanup(oom.stop)
        super().setUp()
        self.addCleanup(server.STATE.update, align_gb=0.0)
        self.addCleanup(server.STATE["align"].clear)
        server.STATE["align"].clear()
        server.STATE.update(device="cuda", align_gb=0.0)

    def whisperx(self, loads: list[str], broken: tuple[str, ...] = ()):
        import types

        def load_align_model(language_code, device):
            loads.append(language_code)
            if language_code in broken:
                raise OSError(f"impossibile scaricare il modello per {language_code}")
            return f"model-{language_code}", {}

        return types.SimpleNamespace(load_align_model=load_align_model)

    def test_no_segments_no_alignment(self) -> None:
        engine = FakeEngine(EmptyModel(fits=99))
        job = server.run_job(None, None, engine, 8, "cuda")
        self.assertEqual(job["segments"], [])
        self.assertEqual(engine.align_devices, [], "niente da allineare: nessun modello da caricare")
        self.assertIsNone(job["alignment"], "e niente da dire su come va l'allineamento in nynorsk")

    def test_a_language_off_the_list_keeps_whisper_times(self) -> None:
        import sys

        class Nynorsk(FakeModel):
            def transcribe(self, audio, batch_size, language, progress_callback=None):
                return {"language": "nn", "segments": [{"start": 0.0, "end": 1.0, "text": "Takk for oss."}]}

        loads: list[str] = []
        with mock.patch.dict(sys.modules, {"whisperx": self.whisperx(loads)}), mock.patch.object(server, "trust_sentence_splitter"):
            job = server.run_job(None, None, RealAligner(Nynorsk(fits=99)), 8, "cuda")
        self.assertEqual(loads, [], "nessun download da 3,6 GB")
        self.assertEqual(job["alignment"], server.ALIGN_UNAVAILABLE)
        self.assertEqual(job["segments"], [{"start": 0.0, "end": 1.0, "text": "Takk for oss."}])

    def test_the_old_aligner_stays_until_the_new_one_loads(self) -> None:
        import sys

        loads: list[str] = []
        with mock.patch.dict(sys.modules, {"whisperx": self.whisperx(loads, broken=("pt",))}), \
                mock.patch.object(server, "trust_sentence_splitter"), \
                mock.patch.object(server, "vram_gb", return_value=2.0), \
                mock.patch.object(server, "empty_cuda_cache") as emptied, self.assertLogs("pampa", level="INFO"):
            self.assertEqual(server.align_model_for("it")[0], "model-it")
            with self.assertRaises(server.AlignmentUnavailable):
                server.align_model_for("pt")
            with self.assertRaises(server.AlignmentUnavailable):
                server.align_model_for("nn")
            self.assertEqual(server.align_model_for("it")[0], "model-it")
        self.assertEqual(loads, ["it", "pt"], "nn non si prova neanche; l'italiano non si ricarica")
        self.assertEqual(list(server.STATE["align"]), ["it"])
        emptied.assert_not_called()

    def test_health_keeps_word_timestamps_for_the_languages_that_work(self) -> None:
        server.STATE.update(gpu=None, alignment={"it": "ok", "nn": server.ALIGN_UNAVAILABLE})
        self.assertTrue(server.health()["word_timestamps"])
        server.STATE.update(alignment={"it": "errore: PermissionError: Security Violation"})
        self.assertFalse(server.health()["word_timestamps"], "un errore vero si vede ancora")

    def test_a_silent_piece_says_nothing_about_alignment(self) -> None:
        answers = iter([
            {"segments": [], "language": "it", "alignment": None},
            {"segments": [{"start": 0.0, "end": 1.0, "text": "ciao"}], "language": "it", "alignment": "ok"},
        ])
        with mock.patch.object(server, "run_job", lambda *a, **k: next(answers)):
            # 45 minuti con un tetto di 30: due pezzi, il primo muto.
            result = server.transcribe_audio(lecture(45), RATE, "it", RecordingProgress(), FakeEngine(None), max_minutes=30)
        self.assertEqual(result["alignment"], "ok")
        with mock.patch.object(server, "run_job", lambda *a, **k: {"segments": [], "language": "it", "alignment": None}):
            result = server.transcribe_audio(lecture(5), RATE, "it", RecordingProgress(), FakeEngine(None))
        self.assertIsNone(result["alignment"])

        def fake_transcribe_audio(audio, *args, **kwargs):
            return {"segments": [], "language": "nn", "device_used": "cuda", "batch_size": 8,
                    "alignment": None, "chunks": 1, "dropped": {}}

        server.STATE["alignment"] = {"it": "ok"}
        with mock.patch.object(server, "load_audio", return_value=[0.0] * 16000), \
                mock.patch.object(server, "probe_duration", return_value=1.0), \
                mock.patch.object(server, "transcribe_audio", fake_transcribe_audio), \
                mock.patch.object(server, "replan_for_job"):
            server._transcribe("x.m4a", None, server.JobProgress())
        self.assertEqual(server.STATE["alignment"], {"it": "ok"}, "una lingua tirata a indovinare sul silenzio non entra")


class CreditsAndEchoTest(unittest.TestCase):
    FRAME = 0.02

    def test_real_sentences_about_media_and_subtitles_stay(self) -> None:
        segments = [
            seg(10.0, 14.0, " Il ministro ha poi parlato ai media della crisi economica del paese."),
            seg(15.0, 16.0, " Ha parlato ai media."),
            seg(17.0, 21.0, " Sottotitoli di un film in lingua originale aiutano molto a imparare."),
            seg(22.0, 23.0, " Sottotitoli di un film."),
            seg(24.0, 25.0, " Subtítulos de la película."),
            seg(26.0, 32.0, " Sottotitoli creati dal professore, con la comunità della classe, per tutte le lezioni dell'anno."),
        ]
        kept, dropped = server.drop_hallucinations(segments, None, self.FRAME)
        self.assertEqual(kept, [dict(s) for s in segments])
        self.assertEqual(dropped, {})

    def test_whole_credit_lines_go(self) -> None:
        lines = [
            "Sottotitoli creati dalla comunità Amara.org",
            "Sottotitoli e revisione a cura di QTSS",
            "Sottotitoli a cura di Marco Rossi",
            "Teksting av Nicolai Winther",
            "Captions by AI-Media",
            "Subtitles by the Amara.org community",
            "Sous-titres réalisés par la communauté d'Amara.org",
            "Subtítulos realizados por la comunidad de Amara.org",
            "Untertitel im Auftrag des ZDF, 2017",
        ]
        segments = [seg(10.0 * i, 10.0 * i + 2.0, line) for i, line in enumerate(lines)]
        kept, dropped = server.drop_hallucinations(segments, None, self.FRAME)
        self.assertEqual(kept, [])
        self.assertEqual(dropped, {"crediti": len(lines)})

    def test_a_one_word_answer_from_the_vocabulary_stays(self) -> None:
        import numpy

        energies = numpy.full(int(60 / self.FRAME), 0.01, dtype=numpy.float32)
        energies[int(10 / self.FRAME) : int(12 / self.FRAME)] = 0.2  # qualcuno risponde, chiaro
        prompt = "Fichte, Schelling, Hegel"
        answer = [seg(10.0, 10.5, " Fichte.")]
        self.assertEqual(server.drop_hallucinations(answer, energies, self.FRAME, prompt=prompt), (answer, {}))
        self.assertEqual(server.drop_hallucinations([seg(10.0, 10.8, " Fichte.")], None, self.FRAME, prompt=prompt)[1], {})
        # Un soffio (meno di [ECHO_MIN_S]), o sopra la stanza vuota: eco.
        self.assertEqual(server.drop_hallucinations([seg(10.0, 10.2, " Fichte.")], energies, self.FRAME, prompt=prompt)[1], {"eco": 1})
        self.assertEqual(server.drop_hallucinations([seg(30.0, 30.5, " Fichte.")], energies, self.FRAME, prompt=prompt)[1], {"eco": 1})


class ArchiveMissingFileTest(unittest.TestCase):
    """Una riga il cui file non c'e': [get] dice «non c'e'», ma una DELETE la toglie e le statistiche non la contano."""

    def test_remove_and_stats_of_a_row_without_its_file(self) -> None:
        present = b"una registrazione che c'e'"
        gone = b"una registrazione sparita"
        with tempfile.TemporaryDirectory() as folder:
            store = archive.Archive(Path(folder))
            try:
                store.store(hashlib.sha256(present).hexdigest(), "a.m4a", "audio/mp4", [present])
                sha = hashlib.sha256(gone).hexdigest()
                store.store(sha, "b.m4a", "audio/mp4", [gone])["path"].unlink()
                with self.assertLogs("pampa", level="WARNING"):
                    self.assertIsNone(store.get(sha))
                self.assertEqual(store.stats(), (1, len(present)))
                self.assertEqual(store.inventory(), (1, len(present), 1))
                self.assertTrue(store.remove(sha), "prima rispondeva 404 e la riga restava per sempre")
                self.assertEqual(store.inventory(), (1, len(present), 0))
                self.assertFalse(store.remove(sha))
            finally:
                store.db.close()


class ExactPiecesTest(unittest.TestCase):
    """Un pezzo decodificato da solo comincia al campione giusto, anche su un formato compresso."""

    RATE = 16000

    def setUp(self) -> None:
        import shutil
        import subprocess
        import wave

        import numpy

        if shutil.which("ffmpeg") is None:
            self.skipTest("ffmpeg non installato")
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        wav = Path(folder.name) / "rumore.wav"
        # Rumore: ogni tratto e' diverso da tutti gli altri, e uno spostamento anche di un campione si vede.
        signal = (0.3 * numpy.random.default_rng(7).standard_normal(8 * self.RATE)).clip(-0.99, 0.99)
        with wave.open(str(wav), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(self.RATE)
            out.writeframes((signal * 32767).astype(numpy.int16).tobytes())
        self.path = Path(folder.name) / "rumore.m4a"
        subprocess.run(
            ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", str(wav), "-c:a", "aac", "-b:a", "128k", str(self.path)],
            check=True, capture_output=True,
        )

    def offset(self, whole, piece, start: int) -> int:
        import numpy

        window = piece[4000:12000]
        best = min(
            range(-800, 801),
            key=lambda shift: float(numpy.mean(numpy.abs(whole[start + 4000 + shift : start + 12000 + shift] - window))),
        )
        return best

    def test_streamed_pieces_start_on_the_sample(self) -> None:
        whole = server.load_audio(self.path, self.RATE)
        streamed = server.StreamedAudio(self.path, self.RATE)
        for start_s, end_s in ((0.0, 2.5), (2.5, 5.13), (5.13, 8.0)):
            piece = streamed.piece(start_s, end_s)
            start = int(round(start_s * self.RATE))
            self.assertEqual(self.offset(whole, piece, start), 0, (start_s, end_s))
            self.assertLessEqual(abs(len(piece) - (int(round(end_s * self.RATE)) - start)), 1)

    def test_the_command_seeks_coarse_then_fine(self) -> None:
        command = server._decode_command("x.m4a", 16000, start_s=100.0, duration_s=30.0)
        at = command.index("-i")
        self.assertEqual(command[at - 2 : at], ["-ss", "98.000"])
        self.assertEqual(command[at + 2 : at + 6], ["-ss", "2.000000", "-t", "30.000000"])
        near = server._decode_command("x.m4a", 16000, start_s=1.5, duration_s=1.0)
        self.assertLess(near.index("-i"), near.index("-ss"), "vicino all'inizio niente salto nel contenitore")
        self.assertNotIn("-ss", server._decode_command("x.m4a", 16000))


class PreallocationTest(unittest.TestCase):
    """La durata dichiarata non si prende in parola oltre [PREALLOCATE_MAX_S]."""

    RATE = DecodeTest.RATE
    setUp = DecodeTest.setUp  # lo stesso tono da tre secondi

    def sizes(self, expected_s: float) -> tuple[list[int], object]:
        import numpy

        real_empty = numpy.empty
        sizes: list[int] = []

        def recording(shape, dtype=float):
            sizes.append(int(shape))
            return real_empty(shape, dtype=dtype)

        with mock.patch.object(server, "PREALLOCATE_MAX_S", 1.0), mock.patch.object(numpy, "empty", recording):
            audio = server.load_audio(self.path, self.RATE, expected_s=expected_s)
        return sizes, audio

    def test_an_unfinished_wav_does_not_ask_for_hours(self) -> None:
        sizes, audio = self.sizes(37 * 3600.0)  # un WAV mai chiuso: 37 ore dichiarate
        self.assertEqual(sizes[0], self.RATE, "al massimo il tetto, non 8,5 GB")
        self.assertLess(max(sizes), 11 * 60 * self.RATE, "e poi cresce a passi, non fino alle 37 ore")
        self.assertEqual(len(audio), len(self.expected))

    def test_growth_never_passes_the_declared_duration(self) -> None:
        sizes, audio = self.sizes(3.0)
        self.assertEqual(sizes, [self.RATE, 5 * self.RATE], "il tetto, poi fino alla durata dichiarata e non oltre")
        self.assertEqual(len(audio), len(self.expected))


if __name__ == "__main__":
    unittest.main()


# La sonda vera, presa prima che setUpModule la spenga per tutti gli altri test.
REAL_REDIRECTED = fuori.redirected_to


class FuoriTest(unittest.TestCase):
    """Il companion che si accorge di essere dentro il contenitore di un'altra app."""

    def test_a_probe_that_lands_in_a_package_is_a_container(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            box = Path(root) / "Packages" / "Claude_prova" / "LocalCache" / "Local" / "PampaNotes"
            box.mkdir(parents=True)
            real_write = Path.write_text

            # La scrittura finisce anche nel contenitore, come fa Windows con un processo virtualizzato.
            def redirect(self: Path, data: str, encoding: str | None = None) -> int:
                if self.name.startswith(".sonda-"):
                    real_write(box / self.name, data, encoding=encoding)
                return real_write(self, data, encoding=encoding)

            with mock.patch.dict(os.environ, {"LOCALAPPDATA": root}), mock.patch.object(Path, "write_text", redirect),                     mock.patch.object(fuori.sys, "platform", "win32"):
                self.assertEqual(REAL_REDIRECTED(), "Claude_prova")

    def test_a_probe_that_stays_home_is_no_container(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            (Path(root) / "Packages" / "Altra").mkdir(parents=True)
            with mock.patch.dict(os.environ, {"LOCALAPPDATA": root}), mock.patch.object(fuori.sys, "platform", "win32"):
                self.assertIsNone(REAL_REDIRECTED())
            self.assertEqual(list((Path(root) / "PampaNotes").iterdir()), [], "la sonda resta in giro")


class FuoriLoopTest(unittest.TestCase):
    """Rilanciarsi fuori una volta sola, e mai da un contenitore da cui non si esce."""

    STORE = "PythonSoftwareFoundation.Python.3.11_qbz5n2kfra8p0"

    def test_empty_localappdata_is_not_the_current_folder(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            here = os.getcwd()
            os.chdir(root)
            try:
                with mock.patch.dict(os.environ, {"LOCALAPPDATA": ""}), mock.patch.object(fuori.sys, "platform", "win32"):
                    self.assertIsNone(REAL_REDIRECTED())
            finally:
                os.chdir(here)
            self.assertEqual(list(Path(root).iterdir()), [], "Path('') e' '.': la sonda finiva nella cartella corrente")

    def test_the_relaunched_process_does_not_look_again(self) -> None:
        with mock.patch.object(fuori, "redirected_to", side_effect=AssertionError("non si guarda")):
            self.assertEqual(fuori.container_to_leave(["tray.py", fuori.RELAUNCHED]), (None, False))
        with mock.patch.object(fuori, "redirected_to", return_value="Claude_pzs8sxrjxfjjc"):
            self.assertEqual(fuori.container_to_leave(["tray.py"]), ("Claude_pzs8sxrjxfjjc", True))
        with mock.patch.object(fuori, "redirected_to", return_value=self.STORE):
            self.assertEqual(fuori.container_to_leave(["tray.py"]), (self.STORE, False))

    def run_avvio(self, argv: list[str], boxed: str | None) -> tuple[list[list[str]], list[str]]:
        avvio = load_avvio()
        relaunches: list[list[str]] = []
        lines: list[str] = []
        with mock.patch.object(avvio.sys, "argv", argv), \
                mock.patch.object(fuori, "redirected_to", return_value=boxed), \
                mock.patch.object(fuori, "relaunch_outside", side_effect=lambda args, cwd: relaunches.append(args) or True), \
                mock.patch.object(avvio, "healthy", return_value=True), mock.patch.object(avvio, "log", lines.append):
            avvio.main()
        return relaunches, lines

    def test_avvio_relaunches_once_with_the_flag(self) -> None:
        relaunches, _ = self.run_avvio(["avvio.pyw", "--dopo"], "Claude_pzs8sxrjxfjjc")
        self.assertEqual(len(relaunches), 1)
        self.assertEqual(relaunches[0][-2:], ["--dopo", fuori.RELAUNCHED])
        # Il rilancio arriva con `--fuori` e non guarda piu', anche se fosse ancora dentro.
        relaunches, _ = self.run_avvio(["avvio.pyw", fuori.RELAUNCHED], "Claude_pzs8sxrjxfjjc")
        self.assertEqual(relaunches, [])

    def test_avvio_inside_store_python_goes_on(self) -> None:
        relaunches, lines = self.run_avvio(["avvio.pyw"], self.STORE)
        self.assertEqual(relaunches, [], "dallo Store non si esce: rilanciarsi sarebbe un giro senza fine")
        self.assertTrue(any(self.STORE in line for line in lines))

    def test_avvio_passes_the_flag_on_to_tray(self) -> None:
        avvio = load_avvio()
        with tempfile.TemporaryDirectory() as logs:
            for argv, expected in ((["avvio.pyw", fuori.RELAUNCHED], [fuori.RELAUNCHED]), (["avvio.pyw"], [])):
                with mock.patch.object(avvio.sys, "argv", argv), mock.patch.object(avvio, "LOGS", Path(logs)), \
                        mock.patch.object(avvio.subprocess, "Popen") as popen:
                    avvio.launch()
                command = popen.call_args.args[0]
                self.assertTrue(command[1].endswith("tray.py"))
                self.assertEqual(command[2:], expected)
                for handle in {call.kwargs["stdout"] for call in popen.call_args_list}:
                    handle.close()


# --- chi parla -------------------------------------------------------------------------------------


class FakeDiarizer:
    """Il modello di pyannote finto: torna i turni che gli si danno, o finisce la memoria a comando."""

    def __init__(self, turns: list[tuple[float, float, str]], error: BaseException | None = None) -> None:
        self.turns = turns
        self.error = error
        self.calls: list[tuple[int, int | None, int | None]] = []

    def __call__(self, audio, min_speakers=None, max_speakers=None, progress_callback=None):  # noqa: ANN001
        self.calls.append((len(audio), min_speakers, max_speakers))
        if progress_callback is not None:
            progress_callback(50.0)
        if self.error is not None:
            raise self.error
        if progress_callback is not None:
            progress_callback(100.0)
        return list(self.turns)


class DiarizationTest(StateMixin, unittest.TestCase):
    """«Chi parla»: le voci sui segmenti, senza modelli veri e senza mai far fallire la lezione."""

    def setUp(self) -> None:
        super().setUp()
        server.STATE.update(device="cuda", batch_size=16, hf_token="hf_segreto", diarization=None)

    @staticmethod
    def conversation(language) -> dict:  # noqa: ANN001
        return {
            "segments": [
                {"start": 1.0, "end": 4.0, "text": "buongiorno a tutti",
                 "words": [{"word": "buongiorno", "start": 1.0, "end": 1.8, "score": 0.9},
                           {"word": "a", "start": 1.9, "end": 2.0, "score": 0.9},
                           {"word": "tutti", "start": 2.1, "end": 2.6, "score": 0.9}]},
                {"start": 5.0, "end": 8.0, "text": "grazie, anche a te"},
                # Whisper ha scritto qualcosa dove pyannote non ha sentito nessuno.
                {"start": 20.0, "end": 21.0, "text": "eh no"},
            ],
            "language": language or "it", "device_used": "cuda", "batch_size": 16, "alignment": "ok",
        }

    def run_with(self, diarizer, minutes: float = 1, request=None, loads: list | None = None):  # noqa: ANN001
        def fake_run_job(piece, language, engine, batch_size, device, progress=None, prompt=None):  # noqa: ANN001
            return self.conversation(language)

        def load(device: str):
            if loads is not None:
                loads.append(device)
            return diarizer if isinstance(diarizer, FakeDiarizer) else diarizer(device)

        progress = RecordingProgress()
        with mock.patch.object(server, "run_job", fake_run_job), mock.patch.object(server, "load_diarizer", load), \
                mock.patch.object(server, "drop_hallucinations", lambda segments, *a, **k: (segments, {})):
            result = server.transcribe_audio(
                lecture(minutes), RATE, "it", progress, FakeEngine(None),
                diarize=request or server.DiarizeRequest(), batch_size=16, device="cuda",
            )
        return result, progress

    def test_each_segment_gets_the_voice_that_speaks_longest(self) -> None:
        diarizer = FakeDiarizer([(0.5, 4.2, "SPEAKER_01"), (4.8, 6.0, "SPEAKER_00"), (6.0, 8.5, "SPEAKER_01")])
        result, progress = self.run_with(diarizer, request=server.DiarizeRequest(2, 4))
        first, second, stray = result["segments"]
        self.assertEqual(first["speaker"], "SPEAKER_01")
        self.assertEqual(second["speaker"], "SPEAKER_01", "2 secondi contro 1,2: vince chi parla di piu'")
        self.assertEqual(stray["speaker"], "SPEAKER_01", "senza turni sopra, la voce del turno piu' vicino")
        self.assertEqual([w["speaker"] for w in first["words"]], ["SPEAKER_01"] * 3)
        self.assertEqual(result["diarization"], "ok")
        self.assertEqual(diarizer.calls, [(60 * RATE, 2, 4)], "l'audio intero, una volta, coi limiti chiesti")
        self.assertIn(("diarizing", None), progress.trail)
        self.assertEqual(progress.reached["diarizing"], 1.0)

    def test_a_failure_leaves_the_lesson_without_voices_and_without_the_token(self) -> None:
        def broken(_device: str):
            raise RuntimeError("401 Client Error: token hf_segreto non valido")

        with self.assertLogs("pampa", level="WARNING") as logs:
            result, _ = self.run_with(broken)
        self.assertEqual(len(result["segments"]), 3)
        self.assertTrue(all("speaker" not in segment for segment in result["segments"]))
        self.assertTrue(result["diarization"].startswith("errore: RuntimeError"))
        self.assertNotIn("hf_segreto", result["diarization"])
        self.assertNotIn("hf_segreto", "\n".join(logs.output))

    def test_a_full_card_moves_the_voices_to_the_processor(self) -> None:
        full = FakeDiarizer([], error=FakeOOM())
        fine = FakeDiarizer([(0.0, 30.0, "SPEAKER_00")])
        loads: list[str] = []
        result, progress = self.run_with(lambda device: full if device == "cuda" else fine, loads=loads)
        self.assertEqual(loads, ["cuda", "cpu"])
        self.assertEqual(result["segments"][0]["speaker"], "SPEAKER_00")
        self.assertIn(("diarizing", "cpu"), progress.trail)

    def test_no_room_on_the_card_goes_straight_to_the_processor(self) -> None:
        loads: list[str] = []
        driver = {"free_gb": 0.4, "total_gb": 12.0, "used_gb": 11.6}
        with mock.patch.object(server, "nvidia_query", return_value=driver):
            self.run_with(FakeDiarizer([(0.0, 30.0, "SPEAKER_00")]), loads=loads)
        self.assertEqual(loads, ["cpu"])

    def test_a_cancel_during_the_voices_cancels_the_lesson(self) -> None:
        class Cancelling(FakeDiarizer):
            def __call__(self, audio, min_speakers=None, max_speakers=None, progress_callback=None):  # noqa: ANN001
                raise server.JobCancelled()

        with self.assertRaises(server.JobCancelled):
            self.run_with(Cancelling([]))

    def test_very_long_audio_is_separated_in_windows_with_their_own_voices(self) -> None:
        diarizer = FakeDiarizer([(0.0, 30.0, "SPEAKER_00")])
        with mock.patch.object(server, "DIARIZE_WINDOW_S", 25 * 60):
            result, _ = self.run_with(diarizer, minutes=45)
        self.assertEqual(len(diarizer.calls), 2)
        self.assertEqual(result["segments"][0]["speaker"], "1:SPEAKER_00", "le voci di una finestra sono sue")

    def test_request_needs_a_token_and_pyannote(self) -> None:
        with mock.patch.object(server, "_pyannote_installed", return_value=True):
            self.assertEqual(server.diarize_request("1"), server.DiarizeRequest())
            self.assertEqual(server.diarize_request("1", "0", "99"), server.DiarizeRequest(None, server.MAX_SPEAKERS_LIMIT))
            self.assertIsNone(server.diarize_request(""))
            server.STATE["hf_token"] = None
            self.assertIsNone(server.diarize_request("1"))
        server.STATE["hf_token"] = "hf_x"
        with mock.patch.object(server, "_pyannote_installed", return_value=False):
            self.assertIsNone(server.diarize_request("1"))

    def test_assign_speakers_does_not_touch_its_input(self) -> None:
        segments = [{"start": 0.0, "end": 1.0, "text": "a", "words": [{"word": "a", "start": 0.0, "end": 0.0}]}]
        out = server.assign_speakers(segments, [(0.0, 2.0, "SPEAKER_00")])
        self.assertNotIn("speaker", segments[0])
        self.assertEqual(out[0]["speaker"], "SPEAKER_00")
        self.assertEqual(out[0]["words"][0]["speaker"], "SPEAKER_00", "una parola lunga zero sta dentro il turno")
        self.assertEqual(server.assign_speakers(segments, []), segments)

    def test_the_answer_carries_speakers_only_when_there_are(self) -> None:
        segment = {"start": 0.0, "end": 1.0, "text": "ciao", "speaker": "SPEAKER_00",
                   "words": [{"word": "ciao", "start": 0.0, "end": 0.5, "score": 0.9, "speaker": "SPEAKER_00"}]}
        self.assertEqual(server.words_of(segment)[0]["speaker"], "SPEAKER_00")
        self.assertNotIn("speaker", server.words_of({"words": [{"word": "x", "start": 0.0, "end": 0.1}]})[0])

    def test_the_token_check_tells_what_is_missing(self) -> None:
        import huggingface_hub
        from huggingface_hub.errors import GatedRepoError, RepositoryNotFoundError

        cases = [(None, True, "Tutto pronto"), (GatedRepoError("gated"), False, "condizioni"),
                 (RepositoryNotFoundError("401"), False, "non riconosce"), (OSError("rete"), False, "Non riesco")]
        for error, ok, words in cases:
            with mock.patch.object(huggingface_hub, "auth_check", side_effect=error):
                self.assertEqual(server.check_diarization_access("hf_x")[0], ok)
                self.assertIn(words, server.check_diarization_access("hf_x")[1])

    @staticmethod
    def http_error(status: int) -> BaseException:
        import requests
        from huggingface_hub.utils import HfHubHTTPError

        response = requests.models.Response()
        response.status_code = status
        return HfHubHTTPError(f"{status} Client Error", response=response)

    def test_a_refused_token_is_said_and_takes_the_voices_off_health(self) -> None:
        import huggingface_hub

        cases = [(401, "Token non valido"), (403, "condizioni del modello")]
        with mock.patch.object(server, "_pyannote_installed", return_value=True):
            for status, words in cases:
                server.STATE["diarization_denied"] = False
                with mock.patch.object(huggingface_hub, "auth_check", side_effect=self.http_error(status)):
                    ok, message = server.check_diarization_access("hf_x")
                self.assertFalse(ok)
                self.assertIn(words, message)
                self.assertNotIn("Non riesco", message, "un rifiuto non e' «riprova piu' tardi»")
                self.assertTrue(server.STATE["diarization_denied"])
                self.assertFalse(server.diarization_available(), "un token rifiutato non si offre")
                self.assertTrue(server.diarization_status()["denied"])
                self.assertIsNone(server.diarize_request("1"))
            # Un errore di rete non dice niente del token: il rifiuto resta com'era.
            with mock.patch.object(huggingface_hub, "auth_check", side_effect=self.http_error(503)):
                self.assertIn("Non riesco", server.check_diarization_access("hf_x")[1])
            self.assertTrue(server.STATE["diarization_denied"])
            # Un controllo riuscito le riaccende.
            with mock.patch.object(huggingface_hub, "auth_check", return_value=None):
                self.assertTrue(server.check_diarization_access("hf_x")[0])
            self.assertTrue(server.diarization_available())

    def test_a_lesson_refused_by_hugging_face_takes_the_voices_off(self) -> None:
        def refused(_device: str):
            raise server.DiarizerAccessDenied("il modello non si scarica con questo token")

        with self.assertLogs("pampa", level="WARNING"):
            result, _ = self.run_with(refused)
        self.assertTrue(result["diarization"].startswith("errore: DiarizerAccessDenied"))
        self.assertTrue(server.STATE["diarization_denied"])
        # Un'altra lezione, dopo che le condizioni sono state accettate e il controllo e' andato: torna.
        server.STATE["diarization_denied"] = False
        result, _ = self.run_with(FakeDiarizer([(0.0, 30.0, "SPEAKER_00")]))
        self.assertEqual(result["diarization"], "ok")
        self.assertFalse(server.STATE["diarization_denied"])

    def test_any_other_failure_keeps_the_voices_offered(self) -> None:
        def broken(_device: str):
            raise RuntimeError("pyannote si e' rotto")

        with self.assertLogs("pampa", level="WARNING"):
            self.run_with(broken)
        self.assertFalse(server.STATE["diarization_denied"])

    def test_voices_time_is_not_transcription_time(self) -> None:
        class Slow(FakeDiarizer):
            def __call__(self, *args, **kwargs):  # noqa: ANN002, ANN003
                # L'orologio di Windows va a scatti di 15 ms: si dorme il doppio di quello che si chiede.
                time.sleep(0.1)
                return super().__call__(*args, **kwargs)

        result, _ = self.run_with(Slow([(0.0, 30.0, "SPEAKER_00")]))
        self.assertGreaterEqual(result["diarize_s"], 0.05)
        # Il caricamento del modello e la separazione escono dal tempo di trascrizione, mai sotto zero.
        self.assertEqual(server.transcription_work_s(100.0, 10.0, 30.0), 60.0)
        self.assertEqual(server.transcription_work_s(5.0, 0.0, 9.0), 0.0)
        # Una lezione di un'ora trascritta in 100 s con 900 s di voci sul processore: 36 volte, non 3,6.
        with mock.patch.dict(server.STATE, {"speeds": {}}):
            server.record_speed("cuda", 3600.0, server.transcription_work_s(1000.0, 0.0, 900.0))
            self.assertEqual(server.recent_speed("cuda"), 36.0)

    def test_the_processor_retry_starts_after_the_card_is_given_back(self) -> None:
        import sys as _sys

        full = FakeDiarizer([], error=FakeOOM())
        fine = FakeDiarizer([(0.0, 30.0, "SPEAKER_00")])
        order: list[str] = []

        def load(device: str):
            # Fuori dall'`except`: l'errore della scheda (e i suoi frame coi tensori) non c'e' piu'.
            order.append(f"load {device} dentro un except={_sys.exc_info()[0] is not None}")
            return full if device == "cuda" else fine

        with mock.patch.object(server, "empty_cuda_cache", side_effect=lambda: order.append("svuota")):
            result, _ = self.run_with(load)
        on_card = order.index("load cuda dentro un except=False")
        on_cpu = order.index("load cpu dentro un except=False")
        self.assertIn("svuota", order[on_card + 1:on_cpu], "la scheda si svuota prima di caricare sul processore")
        self.assertEqual(result["segments"][0]["speaker"], "SPEAKER_00")

    def test_old_pyannote_is_not_offered(self) -> None:
        self.assertTrue(server.pyannote_version_ok("4.0.7"))
        self.assertTrue(server.pyannote_version_ok("5.1"))
        self.assertFalse(server.pyannote_version_ok("3.3.2"))
        self.assertFalse(server.pyannote_version_ok("sviluppo"))

    def test_pyannote_does_not_phone_home(self) -> None:
        # Scritta all'import del server, prima di qualunque pyannote; chi l'ha accesa la trova accesa.
        self.assertIn("PYANNOTE_METRICS_ENABLED", os.environ)
        if os.environ["PYANNOTE_METRICS_ENABLED"].lower() not in ("true", "1"):
            self.assertEqual(os.environ["PYANNOTE_METRICS_ENABLED"], "false")

    def test_removing_the_token_holds_across_restarts(self) -> None:
        env = {"HF_TOKEN": " hf_ambiente "}
        self.assertEqual(server.resolve_hf_token({"hf_token": ""}, env), "hf_ambiente")
        self.assertIsNone(server.resolve_hf_token({"hf_token": "", "hf_token_disabled": True}, env))
        self.assertEqual(server.resolve_hf_token({"hf_token": "hf_file", "hf_token_disabled": True}, env), "hf_file")
        self.assertIsNone(server.resolve_hf_token({}, {}))
        self.assertIn("hf_token_disabled", config.DEFAULTS)

    def test_configure_reads_the_token_from_the_environment_too(self) -> None:
        settings = dict(config.DEFAULTS, device="cpu")
        with tempfile.TemporaryDirectory() as root, mock.patch.dict(os.environ, {"HF_TOKEN": "hf_ambiente"}), \
                mock.patch.object(server.archive, "open_archive"):
            server.configure(dict(settings, archive_root=root))
            self.assertEqual(server.STATE["hf_token"], "hf_ambiente")
            server.configure(dict(settings, archive_root=root, hf_token=" hf_file "))
            self.assertEqual(server.STATE["hf_token"], "hf_file")
