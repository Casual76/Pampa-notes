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
import whisperx_server as server

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

    def setUp(self) -> None:  # noqa: D401
        self._saved = {key: server.STATE[key] for key in ("token", "index_url", "owner", "accept_anonymous", "alignment", "device")}
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

    def transcribe(self, audio: object, batch_size: int, language: str | None) -> dict:
        self.sizes.append(batch_size)
        if self.error is not None:
            raise self.error
        if batch_size > self.fits:
            raise FakeOOM()
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

    def align(self, segments: list[dict], language: str, audio: object, device: str) -> list[dict]:
        self.align_devices.append(device)
        error = self.align_errors.get(device)
        if error is not None:
            raise error
        return [dict(segment, words=[{"word": "ciao", "start": 0.0, "end": 0.4, "score": 0.9}]) for segment in segments]

    def release(self) -> None:
        self.releases += 1


class RunJobTest(unittest.TestCase):
    def test_halves_batch_until_it_fits(self) -> None:
        engine = FakeEngine(FakeModel(fits=4))
        job = server.run_job(None, "it", engine, 16, "cuda")
        self.assertEqual(engine.main.sizes, [16, 8, 4])
        self.assertEqual(job["device_used"], "cuda")
        self.assertEqual(job["batch_size"], 4)
        self.assertEqual(engine.releases, 2)
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


if __name__ == "__main__":
    unittest.main()
