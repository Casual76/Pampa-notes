"""
Le prove dell'installer, dell'aggiornamento e del collegamento all'account.

    cd companion
    .venv\\Scripts\\python.exe -m unittest discover -s installer -p "test_*.py" -v

Le prove della logica pura girano con qualunque Python; quelle che parlano col server vero
(`BindServerTest`) vogliono i pacchetti del companion, e senza si saltano. Niente rete, niente GPU,
niente installazioni: nessuna prova tocca il `config.json` vero o la porta 8765.
"""

from __future__ import annotations

import json
import re
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
COMPANION = HERE.parent
for folder in (str(HERE), str(COMPANION)):
    if folder not in sys.path:
        sys.path.insert(0, folder)

import install  # noqa: E402
import updater  # noqa: E402

try:
    import binding  # noqa: E402
except ImportError:  # fastapi assente: le regole si provano con il server, e si saltano insieme
    binding = None  # type: ignore[assignment]

try:
    import whisperx_server as server  # noqa: E402
except Exception:  # noqa: BLE001
    server = None  # type: ignore[assignment]


class NvidiaSmiTest(unittest.TestCase):
    def test_one_card(self) -> None:
        gpus = install.parse_nvidia_smi("NVIDIA GeForce RTX 4070 Ti, 12282, 576.88\n")
        self.assertEqual(gpus, [install.Gpu("NVIDIA GeForce RTX 4070 Ti", 12.0, "576.88")])
        self.assertEqual(gpus[0].driver_major, 576)

    def test_two_cards_and_noise(self) -> None:
        output = "NVIDIA GeForce GTX 1650, 4096, 546.33\n\nNVIDIA RTX A4000, 16376 MiB, 546.33\nNo devices were found\n"
        gpus = install.parse_nvidia_smi(output)
        self.assertEqual([gpu.name for gpu in gpus], ["NVIDIA GeForce GTX 1650", "NVIDIA RTX A4000"])
        self.assertEqual(install.best_gpu(gpus).name, "NVIDIA RTX A4000")
        self.assertIsNone(install.best_gpu([]))
        self.assertEqual(install.parse_nvidia_smi(""), [])

    def test_driver_chooses_torch(self) -> None:
        self.assertEqual(install.cuda_flavor(None), "cpu")
        self.assertEqual(install.cuda_flavor(install.Gpu("x", 12.0, "576.88")), "cu128")
        self.assertEqual(install.cuda_flavor(install.Gpu("x", 12.0, "560.94")), "cu126")
        self.assertEqual(install.cuda_flavor(install.Gpu("x", 8.0, "472.12")), "cpu")
        # Un driver che non si legge: il piu' recente, che e' anche quello delle schede nuove.
        self.assertEqual(install.cuda_flavor(install.Gpu("x", 8.0, "")), "cu128")

    def test_torch_already_right(self) -> None:
        self.assertTrue(install.torch_ok(["2.8.0+cu128", "True"], "cu128"))
        self.assertFalse(install.torch_ok(["2.8.0+cpu", "False"], "cu128"))
        self.assertFalse(install.torch_ok(["2.8.0+cu128", "False"], "cu128"))
        self.assertFalse(install.torch_ok([], "cu126"))
        self.assertTrue(install.torch_ok(["2.8.0+cpu"], "cpu"))


class ModelChoiceTest(unittest.TestCase):
    def test_without_card_small_on_cpu(self) -> None:
        choice = install.choose_model(None, cuda_works=False)
        self.assertEqual((choice.model, choice.compute_type, choice.device, choice.download), ("small", "int8", "cpu", "small"))
        self.assertTrue(choice.on_cpu)

    def test_card_that_torch_does_not_see_is_cpu(self) -> None:
        self.assertTrue(install.choose_model(install.Gpu("x", 12.0, "576"), cuda_works=False).on_cpu)

    def test_card_keeps_large_in_auto_and_downloads_what_fits(self) -> None:
        big = install.choose_model(install.Gpu("x", 12.0, "576"), cuda_works=True)
        self.assertEqual((big.model, big.compute_type, big.device, big.download), ("large-v3", "", "auto", "large-v3"))
        small = install.choose_model(install.Gpu("x", 4.0, "576"), cuda_works=True)
        self.assertEqual(small.model, "large-v3")
        self.assertEqual(small.download, "medium")

    def test_planner_is_used(self) -> None:
        choice = install.choose_model(install.Gpu("x", 6.0, "576"), True, planner=lambda gb: {"model": "turbo", "compute_type": "float16", "batch_size": 3})
        self.assertEqual(choice.download, "turbo")

    @unittest.skipIf(server is None, "i pacchetti del companion non ci sono")
    def test_copy_agrees_with_the_server(self) -> None:
        for gb in (1.5, 2, 2.5, 3, 4, 4.5, 5, 6, 6.5, 8, 10, 12, 16, 24, 48):
            real = server.plan_vram("large-v3", "float16", 16, gb)
            copy = install.fallback_plan(gb)
            self.assertEqual(
                (copy["model"], copy["compute_type"], copy["batch_size"], copy["fits"]),
                (real["model"], real["compute_type"], real["batch_size"], real["fits"]),
                f"{gb} GB",
            )


class ConfigMergeTest(unittest.TestCase):
    CPU = install.ModelChoice("small", "int8", "cpu", "small", True)
    GPU = install.ModelChoice("large-v3", "", "auto", "large-v3", False)

    def test_fresh_config(self) -> None:
        merged, added = install.merge_config(None, self.GPU, 8799, lambda: "codice")
        self.assertEqual(merged, {"model": "large-v3", "compute_type": "", "device": "auto", "port": 8799,
                                  "token": "codice", "accept_anonymous": False})
        self.assertEqual(set(added), set(merged))

    def test_existing_values_win_and_unknown_keys_stay(self) -> None:
        existing = {"model": "medium", "owner": "tu@gmail.com", "index_url": "https://x", "chiave_futura": 1}
        merged, added = install.merge_config(existing, self.CPU, 8765, lambda: "mai")
        self.assertEqual(merged["model"], "medium")
        self.assertEqual(merged["owner"], "tu@gmail.com")
        self.assertEqual(merged["chiave_futura"], 1)
        self.assertEqual(set(added), {"compute_type", "device", "port"})
        # Ne' un token nuovo ne' l'accesso libero su un config che c'era: lo decide config.load.
        self.assertNotIn("token", merged)
        self.assertNotIn("accept_anonymous", merged)

    def test_complete_config_is_untouched(self) -> None:
        existing = {"model": "large-v3", "compute_type": "", "device": "auto", "port": 8765, "token": ""}
        merged, added = install.merge_config(existing, self.GPU, 9999, lambda: "mai")
        self.assertEqual(merged, existing)
        self.assertEqual(added, [])


class SmallRulesTest(unittest.TestCase):
    def test_windows(self) -> None:
        self.assertEqual(install.windows_status(10, 19045), (True, "Windows 10"))
        self.assertEqual(install.windows_status(10, 26100), (True, "Windows 11"))
        self.assertFalse(install.windows_status(6, 9600)[0])

    def test_space(self) -> None:
        gb = 1024**3
        self.assertTrue(install.enough_space(12 * gb, upgrade=False))
        self.assertFalse(install.enough_space(9 * gb, upgrade=False))
        self.assertTrue(install.enough_space(3 * gb, upgrade=True))

    def test_network_hints(self) -> None:
        self.assertTrue(install.looks_like_network("error: Failed to fetch: `https://pypi.org/simple/torch/`"))
        self.assertTrue(install.looks_like_network("ReadTimeoutError: HTTPSConnectionPool: Read timed out."))
        self.assertFalse(install.looks_like_network("No matching distribution found for whisperx==99"))

    def test_restart_only_when_idle(self) -> None:
        self.assertTrue(install.restart_allowed(None))
        self.assertTrue(install.restart_allowed({"busy": False, "queue": 0}))
        self.assertFalse(install.restart_allowed({"busy": True, "queue": 0}))
        self.assertFalse(install.restart_allowed({"busy": False, "queue": 2}))
        # Un caricamento a meta' non e' ne' «busy» ne' in fila; un companion vecchio non lo dice.
        self.assertFalse(install.restart_allowed({"busy": False, "queue": 0, "inflight": 1}))
        self.assertTrue(install.restart_allowed({"busy": False, "queue": 0, "inflight": 0}))

    def test_companion_stops_before_the_venv_changes(self) -> None:
        # WhisperX, torch e ffmpeg non si reinstallano sotto un companion che gira.
        order = [step.run for step in install.STEPS]
        stop = order.index(install.step_stop)
        for touches_venv in (install.step_venv, install.step_packages, install.step_torch, install.step_ffmpeg):
            self.assertLess(stop, order.index(touches_venv), touches_venv.__name__)

    def test_tailscale_and_ownership(self) -> None:
        self.assertEqual(install.pick_tailscale(["192.168.1.5", "100.101.3.4"]), "100.101.3.4")
        self.assertIsNone(install.pick_tailscale(["100.200.1.1", "10.0.0.2"]))
        app = Path(r"C:\Users\x\AppData\Local\Programs\PampaCompanion")
        self.assertTrue(install.ours(r'"c:\users\x\appdata\local\programs\pampacompanion\.venv\scripts\pythonw.exe" tray.py', app))
        self.assertFalse(install.ours(r'"C:\VibeCoded Projects\Pampa notes\companion\.venv\Scripts\pythonw.exe" tray.py', app))

    def test_powershell_quoting(self) -> None:
        self.assertEqual(install.ps_quote("C:\\Users\\D'Amico"), "'C:\\Users\\D''Amico'")
        import base64

        self.assertEqual(base64.b64decode(install.encoded("exit 0")).decode("utf-16-le"), "exit 0")

    def test_port_comes_from_the_config(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder)
            self.assertEqual(install.default_port(app, None), 8765)
            (app / "config.json").write_text(json.dumps({"port": 9123}), encoding="utf-8")
            self.assertEqual(install.default_port(app, None), 9123)
            self.assertEqual(install.default_port(app, 8799), 8799)


class ConfigStepTest(unittest.TestCase):
    """Il passo vero su una cartella di prova: scrive, e alla seconda volta non riscrive."""

    def context(self, app: Path) -> install.Context:
        options = install.parse_args(["--app", str(app), "--console", "--port", "8799"])
        return install.Context(app=app, port=8799, upgrade=False, uv=None, options=options,
                               reporter=install.Reporter(), log_path=app / "logs" / "install.log")

    def test_fresh_then_upgrade(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder)
            ctx = self.context(app)
            install.step_config(ctx)
            stored = json.loads((app / "config.json").read_text(encoding="utf-8"))
            self.assertEqual(stored["port"], 8799)
            self.assertFalse(stored["accept_anonymous"])
            self.assertTrue(stored["token"])
            before = (app / "config.json").read_text(encoding="utf-8")
            again = self.context(app)
            again.upgrade = True
            install.step_config(again)
            self.assertEqual((app / "config.json").read_text(encoding="utf-8"), before)

    def test_broken_config_stops_instead_of_overwriting(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder)
            (app / "config.json").write_text("{ rotto", encoding="utf-8")
            with self.assertRaises(install.StepFailed):
                install.step_config(self.context(app))
            self.assertEqual((app / "config.json").read_text(encoding="utf-8"), "{ rotto")


# --- aggiornamenti ---------------------------------------------------------------------------------


def release(tag: str, *, draft: bool = False, prerelease: bool = False, asset: str | None = None, digest: str = "") -> dict:
    name = asset if asset is not None else f"PampaCompanionSetup-{tag.removeprefix('companion-v')}.exe"
    return {
        "tag_name": tag,
        "draft": draft,
        "prerelease": prerelease,
        "assets": [{"name": name, "browser_download_url": f"https://github.com/x/{name}", "size": 10, "digest": digest}],
    }


class UpdaterTest(unittest.TestCase):
    def test_versions(self) -> None:
        self.assertEqual(updater.parse_version("companion-v1.2.3"), (1, 2, 3))
        self.assertEqual(updater.parse_version("v1.10"), (1, 10))
        self.assertEqual(updater.parse_version("1.2.3-beta"), (1, 2, 3))
        self.assertIsNone(updater.parse_version("dev"))
        self.assertTrue(updater.is_newer((1, 10), (1, 9, 9)))
        self.assertFalse(updater.is_newer((1, 2), (1, 2, 0)))
        self.assertFalse(updater.is_newer((0, 9), (1, 0)))
        self.assertFalse(updater.is_newer((9, 9), None))

    def test_picks_the_highest_published_companion_release(self) -> None:
        releases = [
            release("v0.3.0"),  # l'app, non il companion
            release("companion-v1.0.0"),
            release("companion-v1.3.0", draft=True),
            release("companion-v1.2.0", prerelease=True),
            release("companion-v1.1.0", digest="sha256:ABCDEF"),
            release("companion-v1.4.0", asset="altro.zip"),
        ]
        latest = updater.pick_latest(releases)
        self.assertEqual(latest.tag, "companion-v1.1.0")
        self.assertEqual(latest.label, "1.1.0")
        self.assertEqual(latest.sha256, "abcdef")
        self.assertIsNone(updater.pick_latest([]))
        self.assertIsNone(updater.pick_latest({"message": "Not Found"}))

    def test_available_only_when_newer(self) -> None:
        newer = updater.pick_latest([release("companion-v1.1.0")])
        self.assertEqual(updater.available("1.0.0", lambda _: newer), newer)
        self.assertIsNone(updater.available("1.1.0", lambda _: newer))
        self.assertIsNone(updater.available("dev", lambda _: newer))
        self.assertIsNone(updater.available("1.0.0", lambda _: None))

    def test_only_installed_copies_update(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            self.assertFalse(updater.installed(Path(folder)))
            (Path(folder) / "unins000.exe").write_bytes(b"")
            self.assertTrue(updater.installed(Path(folder)))

    def test_apply_waits_for_idle(self) -> None:
        watcher = updater.UpdateWatcher("1.0.0", on_change=lambda: None, fetch=lambda _: None)
        self.assertEqual(watcher.apply(lambda: True), "none")
        watcher.release = updater.pick_latest([release("companion-v2.0.0")])
        self.assertEqual(watcher.apply(lambda: False), "busy")

    def test_network_error_keeps_what_was_known(self) -> None:
        changes: list[int] = []

        def broken(_: str):
            raise OSError("rete")

        watcher = updater.UpdateWatcher("1.0.0", on_change=lambda: changes.append(1), fetch=broken)
        self.assertIsNone(watcher.check_now())
        self.assertEqual(changes, [])
        watcher._fetch = lambda _: updater.pick_latest([release("companion-v1.2.0")])
        self.assertEqual(watcher.check_now().label, "1.2.0")
        self.assertEqual(changes, [1])


# --- il collegamento all'account ------------------------------------------------------------------


@unittest.skipIf(binding is None, "fastapi non c'e'")
class BindRulesTest(unittest.TestCase):
    def test_code_is_stable_expires_and_is_single_use(self) -> None:
        now = [1000.0]
        codes = binding.BindCodes(ttl_s=600, clock=lambda: now[0])
        code = codes.current()
        self.assertEqual(codes.current(), code)
        self.assertTrue(codes.check(code))
        self.assertFalse(codes.check(code + "x"))
        self.assertFalse(codes.check(None))
        now[0] += 601
        self.assertFalse(codes.check(code))
        fresh = codes.current()
        self.assertNotEqual(fresh, code)
        codes.consume()
        self.assertFalse(codes.check(fresh))

    def test_local_clients(self) -> None:
        for host in ("127.0.0.1", "192.168.1.20", "10.1.2.3", "172.20.0.5", "100.101.1.2", "::1", "::ffff:192.168.1.4", "fe80::1%12"):
            self.assertTrue(binding.is_local_client(host), host)
        for host in ("8.8.8.8", "172.32.0.1", "100.200.0.1", "2001:db8::1", "", None, "nonsense"):
            self.assertFalse(binding.is_local_client(host), host)

    def test_start_page_only_from_this_computer_by_its_name(self) -> None:
        self.assertTrue(binding.is_loopback_request("127.0.0.1", "127.0.0.1:8765"))
        self.assertTrue(binding.is_loopback_request("127.0.0.1", "localhost:8765"))
        self.assertTrue(binding.is_loopback_request("::1", "[::1]:8765"))
        self.assertFalse(binding.is_loopback_request("127.0.0.1", "evil.example:8765"))
        self.assertFalse(binding.is_loopback_request("192.168.1.20", "127.0.0.1:8765"))
        self.assertFalse(binding.is_loopback_request(None, "localhost"))

    def test_owner_and_index(self) -> None:
        self.assertEqual(binding.normalize_owner("  tu@gmail.com "), "tu@gmail.com")
        self.assertIsNone(binding.normalize_owner("due parole@x.it"))
        self.assertIsNone(binding.normalize_owner("<script>@x"))
        self.assertIsNone(binding.normalize_owner(42))
        self.assertEqual(binding.normalize_index_url("https://pampa.workers.dev/"), "https://pampa.workers.dev")
        self.assertEqual(binding.normalize_index_url("http://192.168.1.2:8787"), "http://192.168.1.2:8787")
        for bad in ("ftp://x", "https://", "https://u:p@x.dev", "https://x.dev/?a=1", "javascript:alert(1)", "x" * 600, None):
            self.assertIsNone(binding.normalize_index_url(bad), bad)

    def test_who_can_bind(self) -> None:
        self.assertEqual(binding.decide("", "tu@gmail.com"), "bind")
        self.assertEqual(binding.decide("Tu@Gmail.com", "tu@gmail.com"), "same")
        self.assertEqual(binding.decide("altro@gmail.com", "tu@gmail.com"), "taken")


class FakeIndex:
    """Un Worker finto che conosce un biglietto solo: `pt_good`, di `tu@gmail.com`."""

    def __init__(self) -> None:
        calls = self.calls = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_: object) -> None:
                pass

            def do_POST(self) -> None:  # noqa: N802
                body = json.loads(self.rfile.read(int(self.headers.get("content-length") or 0)) or b"{}")
                calls.append((self.path, body))
                good = body.get("ticket") == "pt_good" and str(body.get("owner", "")).lower() == "tu@gmail.com"
                payload = {"ok": True, "expiresAt": time.time() * 1000 + 3600_000} if good else {"error": "no"}
                data = json.dumps(payload).encode()
                self.send_response(200 if good else 401)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


@unittest.skipIf(server is None, "i pacchetti del companion non ci sono")
class BindServerTest(unittest.TestCase):
    """Il server vero su una porta a caso, con un config.json di prova e un indice finto."""

    SAVED = ("token", "index_url", "owner", "accept_anonymous", "config_path")

    @classmethod
    def setUpClass(cls) -> None:
        import socket

        import uvicorn

        cls.index = FakeIndex()
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            cls.port = probe.getsockname()[1]
        cls.saved_port = server.STATE.get("port")
        server.STATE["port"] = cls.port
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
        cls.index.close()
        server.STATE["port"] = cls.saved_port

    def setUp(self) -> None:
        self.saved = {key: server.STATE.get(key) for key in self.SAVED}
        self.folder = tempfile.TemporaryDirectory()
        self.config = Path(self.folder.name) / "config.json"
        self.config.write_text(json.dumps({"port": self.port, "token": "codice"}), encoding="utf-8")
        server.STATE.update(token="codice", index_url="", owner="", accept_anonymous=False, config_path=self.config)
        binding.CODES.consume()

    def tearDown(self) -> None:
        server.STATE.update(self.saved)
        binding.CODES.consume()
        self.folder.cleanup()

    def call(self, method: str, path: str, body: dict | None = None, bearer: str = "", host: str | None = None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if bearer:
            request.add_header("Authorization", f"Bearer {bearer}")
        if host:
            request.add_header("Host", host)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    def pair_page(self) -> str:
        status, body = self.call("GET", f"/pair?k={server.new_pairing_key()}")
        self.assertEqual(status, 200)
        return body

    def test_unbound_computer_is_bound_once(self) -> None:
        page = self.pair_page()
        self.assertIn("bind=", page)
        code = binding.CODES.current()
        request = {"code": code, "owner": "tu@gmail.com", "index_url": self.index.url + "/"}

        self.assertEqual(self.call("POST", "/v1/pair/bind", request)[0], 401)  # senza biglietto
        self.assertEqual(self.call("POST", "/v1/pair/bind", {**request, "code": "sbagliato"}, bearer="pt_good")[0], 403)
        self.assertEqual(self.call("POST", "/v1/pair/bind", request, bearer="pt_altro")[0], 401)  # l'indice dice no

        status, body = self.call("POST", "/v1/pair/bind", request, bearer="pt_good")
        self.assertEqual(status, 200, body)
        self.assertEqual(json.loads(body)["owner"], "tu@gmail.com")
        stored = json.loads(self.config.read_text(encoding="utf-8"))
        self.assertEqual(stored, {"port": self.port, "token": "codice", "owner": "tu@gmail.com", "index_url": self.index.url})
        self.assertEqual(server.STATE["owner"], "tu@gmail.com")

        # Una volta sola, e il QR dopo non porta piu' il codice.
        self.assertEqual(self.call("POST", "/v1/pair/bind", request, bearer="pt_good")[0], 403)
        self.assertNotIn("bind=", self.pair_page())
        # Da adesso il biglietto dell'account apre il computer.
        self.assertEqual(self.call("GET", "/v1/models", bearer="pt_good")[0], 200)

    def test_computer_of_another_account_says_409(self) -> None:
        server.STATE["owner"] = "altro@gmail.com"
        code = binding.CODES.current()
        status, _ = self.call("POST", "/v1/pair/bind", {"code": code, "owner": "tu@gmail.com", "index_url": self.index.url}, bearer="pt_good")
        self.assertEqual(status, 409)
        self.assertNotIn("owner", json.loads(self.config.read_text(encoding="utf-8")))

    def test_start_page_is_for_this_computer_only(self) -> None:
        status, body = self.call("GET", "/pair/start")
        self.assertEqual(status, 200)
        self.assertIn("Collega il telefono", body)
        self.assertIn("/pair?k=", body)
        self.assertEqual(self.call("GET", "/pair/start", host="evil.example")[0], 404)

    def test_health_says_the_version(self) -> None:
        status, body = self.call("GET", "/health")
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["version"])


# --- la release: quello che il setup si porta dietro -------------------------------------------------


class ReleaseContentsTest(unittest.TestCase):
    """Quello che un'installazione nuova riceve deve essere quello provato."""

    def requirements(self) -> dict[str, str]:
        lines = (COMPANION / "requirements.txt").read_text(encoding="utf-8").splitlines()
        found: dict[str, str] = {}
        for line in lines:
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            name = re.split(r"[<>=!~ ]", line, maxsplit=1)[0]
            found[name.lower().replace("_", "-")] = line[len(name):].strip()
        return found

    def test_requirements_are_pinned_where_the_code_depends_on_them(self) -> None:
        wanted = self.requirements()
        # WhisperX esatto: il companion chiama pezzi interni che cambiano fra le versioni.
        self.assertEqual(wanted["whisperx"], "==3.8.6")
        # «Chi parla» vuole pyannote 4 (`token=`, community-1) e gli errori di huggingface_hub 0.x.
        self.assertEqual(wanted["pyannote.audio"], ">=4.0,<5")
        self.assertEqual(wanted["huggingface-hub"], ">=0.24,<1.0")

    @unittest.skipIf(server is None, "i pacchetti del companion non ci sono")
    def test_server_wants_the_pyannote_the_requirements_install(self) -> None:
        self.assertTrue(server.pyannote_version_ok("4.0.7"))
        self.assertFalse(server.pyannote_version_ok("3.3.2"))
        self.assertFalse(server.pyannote_version_ok(""))

    def test_version_is_the_release(self) -> None:
        self.assertEqual((COMPANION / "VERSION").read_text(encoding="utf-8").strip(), "1.0.3")

    @staticmethod
    def local_imports(path: Path) -> set[str]:
        """I moduli del companion che un file importa, direttamente."""
        import ast

        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        names: set[str] = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module and not node.level:
                names.add(node.module.split(".")[0])
        return {name for name in names if (COMPANION / f"{name}.py").is_file()}

    @staticmethod
    def iss_sources() -> list[tuple[str, list[str]]]:
        """I `Source: "..\\x"` del setup che finiscono in {app}, coi loro Excludes."""
        text = (HERE / "PampaCompanion.iss").read_text(encoding="utf-8")
        section = text.split("[Files]", 1)[1].split("\n[", 1)[0]
        sources: list[tuple[str, list[str]]] = []
        for line in section.splitlines():
            if line.lstrip().startswith(";"):
                continue
            match = re.search(r'Source:\s*"\.\.\\([^"]+)"', line)
            if not match or 'DestDir: "{app}"' not in line:
                continue
            excludes = re.search(r'Excludes:\s*"([^"]+)"', line)
            sources.append((match.group(1), excludes.group(1).split(",") if excludes else []))
        return sources

    def test_every_module_the_companion_imports_goes_into_the_setup(self) -> None:
        import fnmatch

        # Da quello che parte (avvio.pyw, tray.py, il server) a tutto quello che si tira dietro.
        todo = [COMPANION / "avvio.pyw", COMPANION / "tray.py", COMPANION / "whisperx_server.py"]
        needed: set[str] = {path.name for path in todo}
        while todo:
            for name in self.local_imports(todo.pop()):
                file = f"{name}.py"
                if file not in needed:
                    needed.add(file)
                    todo.append(COMPANION / file)
        self.assertTrue({"fuori.py", "config.py", "archive.py", "updater.py", "binding.py"} <= needed, needed)
        sources = self.iss_sources()
        for file in sorted(needed):
            covered = any(
                fnmatch.fnmatch(file, pattern) and not any(fnmatch.fnmatch(file, skip.strip()) for skip in excludes)
                for pattern, excludes in sources
            )
            self.assertTrue(covered, f"{file} non entra nel setup: il companion installato non partirebbe")


class MergeConfigTokenTest(unittest.TestCase):
    def test_upgrade_keeps_the_voices_token_and_its_switch(self) -> None:
        existing = {"model": "large-v3", "compute_type": "", "device": "auto", "port": 8765,
                    "hf_token": "hf_segreto", "hf_token_disabled": True}
        merged, added = install.merge_config(existing, ConfigMergeTest.GPU, 8765, lambda: "mai")
        self.assertEqual(merged["hf_token"], "hf_segreto")
        self.assertTrue(merged["hf_token_disabled"])
        self.assertEqual(added, [])


# --- l'aggiornamento non si uccide da solo ---------------------------------------------------------


class AncestorsTest(unittest.TestCase):
    def test_chain_goes_up_to_the_first_unknown_parent(self) -> None:
        # install.py (50) <- lanciatore della venv (40) <- setup.tmp (30) <- setup.exe (20) <- icona (10)
        parents = {50: 40, 40: 30, 30: 20, 20: 10, 10: 4, 99: 10}
        self.assertEqual(install.ancestor_chain(50, parents, lambda pid: pid), [40, 30, 20, 10])

    def test_a_recycled_number_is_not_a_parent(self) -> None:
        # Il padre vero di 30 e' morto, e il suo numero (20) ora e' di un processo nato dopo.
        parents = {50: 40, 40: 30, 30: 20, 20: 10, 10: 4}
        born = {50: 500, 40: 400, 30: 300, 20: 900, 10: 100}
        self.assertEqual(install.ancestor_chain(50, parents, born.get), [40, 30])

    def test_unknown_birth_keeps_the_link_and_loops_stop(self) -> None:
        self.assertEqual(install.ancestor_chain(3, {3: 2, 2: 3}, lambda pid: None), [2])
        self.assertEqual(install.ancestor_chain(3, {3: 2, 2: 1}, lambda pid: None), [2], "1 non c'e' nella fotografia")

    @unittest.skipUnless(sys.platform == "win32", "la fotografia dei processi e' di Windows")
    def test_real_snapshot_sees_our_parent(self) -> None:
        import os

        self.assertIn(os.getppid(), install.process_ancestors())
        self.assertNotIn(os.getpid(), install.process_ancestors())


class StopRunningTest(unittest.TestCase):
    IDLE = {"busy": False, "queue": 0, "inflight": 0}

    def run_stop(self, ancestors: set[int], health: list) -> tuple[str, list[list[str]]]:
        with tempfile.TemporaryDirectory() as folder:
            app = Path(folder)
            options = install.parse_args(["--app", str(app), "--console", "--port", "8799"])
            ctx = install.Context(app=app, port=8799, upgrade=True, uv=None, options=options,
                                  reporter=install.Reporter(), log_path=app / "logs" / "install.log")
            commands: list[list[str]] = []
            ctx.run = lambda command, **kwargs: commands.append(command) or (0, [])  # type: ignore[method-assign]
            listener = (1234, f'"{app}\\.venv\\Scripts\\pythonw.exe" "{app}\\tray.py"')
            with mock.patch.object(install, "health", side_effect=health), \
                    mock.patch.object(install, "listener_command_line", return_value=listener), \
                    mock.patch.object(install.os, "name", "nt"), mock.patch.object(install.time, "sleep"):
                outcome = install.stop_running(ctx, ancestors=lambda: ancestors)
        return outcome, commands

    def test_the_tray_that_launched_us_is_never_killed(self) -> None:
        outcome, commands = self.run_stop({1234, 777}, [self.IDLE, self.IDLE])
        self.assertEqual(outcome, install.ANCESTOR)
        self.assertEqual(commands, [], "niente taskkill su un antenato")

    def test_a_stranger_tray_is_killed_alone_without_its_tree(self) -> None:
        outcome, commands = self.run_stop({777}, [self.IDLE, self.IDLE, None])
        self.assertEqual(outcome, install.STOPPED)
        self.assertEqual(commands, [["taskkill", "/PID", "1234", "/F"]])
        self.assertNotIn("/T", commands[0])


class LaunchSetupTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "win32", "WMI e' di Windows")
    def test_the_setup_starts_outside_the_tray_through_wmi(self) -> None:
        asked: list[list[str]] = []
        setup = Path(r"C:\Temp\pampa-companion-x\PampaCompanionSetup-1.0.2.exe")
        with mock.patch.object(updater.subprocess, "Popen") as popen:
            how = updater.launch_setup(setup, outside=lambda args, cwd: asked.append(args) or True)
        self.assertEqual(how, "wmi")
        self.assertEqual(asked, [[str(setup), *updater.SETUP_ARGS]])
        popen.assert_not_called()

    @unittest.skipUnless(sys.platform == "win32", "WMI e' di Windows")
    def test_without_wmi_it_still_starts_detached(self) -> None:
        with mock.patch.object(updater.subprocess, "Popen") as popen:
            how = updater.launch_setup(Path(r"C:\Temp\s.exe"), outside=lambda args, cwd: False)
        self.assertEqual(how, "figlio")
        popen.assert_called_once()


# --- dentro il contenitore di un'altra app -------------------------------------------------------------


class ContainerTest(unittest.TestCase):
    def test_an_install_under_localappdata_in_a_container_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as local:
            app = Path(local) / "Programs" / "PampaCompanion"
            with mock.patch.dict(install.os.environ, {"LOCALAPPDATA": local}):
                self.assertEqual(install.installer_container(app, lambda: "Claude_pzs8sxrjxfjjc"), "Claude_pzs8sxrjxfjjc")
                self.assertIsNone(install.installer_container(app, lambda: None))
                # Una prova a mano fuori da %LOCALAPPDATA%: il contenitore non la sposta.
                with tempfile.TemporaryDirectory() as elsewhere:
                    self.assertIsNone(install.installer_container(Path(elsewhere), lambda: "Claude_x"))
            with mock.patch.dict(install.os.environ, {"LOCALAPPDATA": ""}):
                self.assertIsNone(install.installer_container(app, lambda: "Claude_x"))

    def test_main_stops_before_doing_anything(self) -> None:
        with tempfile.TemporaryDirectory() as folder, \
                mock.patch.object(install, "installer_container", return_value="Claude_x"), \
                mock.patch.object(install, "run_steps", side_effect=AssertionError("nessun passo")), \
                mock.patch("builtins.print"):
            self.assertEqual(install.main(["--app", folder, "--console"]), install.EXIT_CONTAINER)
        self.assertIn("Claude_x", install.container_message("Claude_x"))

    @staticmethod
    def iss_relaunch_script(command: str, folder: str) -> str:
        """Lo script di `RelaunchOutside` com'e' scritto nel setup, con `Command` e `Folder` al loro posto."""
        text = (HERE / "PampaCompanion.iss").read_text(encoding="utf-8")
        body = text.split("function RelaunchOutside", 1)[1].split("\nend;", 1)[0]
        expression = re.search(r"Script := (.*?);\n", body, re.S).group(1)
        values = {"Command": command, "Folder": folder}
        parts: list[str] = []
        # Solo stringhe Pascal ('' e' un apice) e i due nomi, uniti da +.
        for literal, name in re.findall(r"'((?:[^']|'')*)'|([A-Za-z]+)", expression):
            parts.append(literal.replace("''", "'") if not name else values[name])
        return "".join(parts)

    @unittest.skipUnless(sys.platform == "win32", "lo script e' PowerShell")
    def test_the_setup_relaunch_fails_when_wmi_fails(self) -> None:
        import subprocess

        script = self.iss_relaunch_script('"C:\\Setup.exe" /FUORI', "C:\\Temp")
        self.assertIn("CommandLine = '\"C:\\Setup.exe\" /FUORI'", script)

        # Una funzione con lo stesso nome vince sul cmdlet: WMI finto, lo script del setup vero.
        def exit_code(fake: str) -> int:
            with tempfile.TemporaryDirectory() as folder:
                path = Path(folder) / "fuori.ps1"
                path.write_text(f"function Invoke-CimMethod {{ {fake} }}\n{script}", encoding="utf-8")
                done = subprocess.run(
                    ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(path)],
                    capture_output=True, timeout=60,
                )
            return done.returncode

        # Prima `exit $r.ReturnValue` con $r vuoto usciva 0, e il setup si chiudeva senza ripartire.
        self.assertEqual(exit_code("Write-Error 'accesso negato'"), 1)
        self.assertEqual(exit_code("throw 'WMI fermo'"), 1)
        self.assertEqual(exit_code("$null"), 1)
        self.assertEqual(exit_code("[pscustomobject]@{ ReturnValue = 8 }"), 8)
        self.assertEqual(exit_code("[pscustomobject]@{ ReturnValue = 0 }"), 0)

    def test_the_setup_stops_when_the_relaunch_fails(self) -> None:
        text = (HERE / "PampaCompanion.iss").read_text(encoding="utf-8")
        body = text.split("function InitializeSetup", 1)[1].split("\nend;", 1)[0]
        # Rilanciato: esce senza dire niente. Non rilanciato: si ferma lo stesso (Result resta False) e lo dice.
        self.assertIn("Result := False;\n  if RelaunchOutside then", body)
        self.assertIn("MsgBox(", body)
        self.assertNotIn("Result := True;\n  end", body)


# --- la disinstallazione ---------------------------------------------------------------------------------


@unittest.skipUnless(sys.platform == "win32", "l'aiutante della disinstallazione e' PowerShell")
class UninstallModelsTest(unittest.TestCase):
    def test_only_the_companion_models_leave_the_cache(self) -> None:
        import os
        import subprocess

        with tempfile.TemporaryDirectory() as hub, tempfile.TemporaryDirectory() as app, \
                tempfile.TemporaryDirectory() as torch:
            ours = ["models--Systran--faster-whisper-large-v3", "models--pyannote--speaker-diarization-community-1",
                    "models--jonatasgrosman--wav2vec2-large-xlsr-53-italian",
                    # distil-large-v3 e large-v3-turbo: stessi pesi di large, altri autori e altri nomi.
                    "models--Systran--faster-distil-whisper-large-v3",
                    "models--mobiuslabsgmbh--faster-whisper-large-v3-turbo"]
            theirs = ["models--openai--whisper-large-v3", "models--meta-llama--Llama-3.1-8B", "datasets--x--y",
                      "models--mobiuslabsgmbh--altro-modello"]
            for name in ours + theirs:
                (Path(hub) / name / "blobs").mkdir(parents=True)
                (Path(hub) / name / "blobs" / "a").write_text("x", encoding="utf-8")
            (Path(hub) / ".locks" / ours[0]).mkdir(parents=True)
            # Gli allineatori di torchaudio: file sciolti nella cache di torch, accanto a quelli di altri.
            checkpoints = Path(torch) / "hub" / "checkpoints"
            checkpoints.mkdir(parents=True)
            our_files = ["wav2vec2_fairseq_base_ls960_asr_ls960.pth", "wav2vec2_voxpopuli_base_10k_asr_it.pt",
                         "voxpopuli_prova.pt"]
            their_files = ["resnet50-0676ba61.pth", "hubert_fairseq_base_ls960.pth"]
            for name in our_files + their_files:
                (checkpoints / name).write_text("x", encoding="utf-8")
            # TORCH_HOME sempre: senza, la prova toglierebbe gli allineatori veri di questo PC.
            env = dict(os.environ, HF_HUB_CACHE=hub, TORCH_HOME=torch)
            done = subprocess.run(
                ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / "uninstall-helper.ps1"),
                 "-App", app, "-Action", "purge-models"],
                env=env, capture_output=True, timeout=120,
            )
            self.assertEqual(done.returncode, 0, done.stderr)
            left = sorted(entry.name for entry in Path(hub).iterdir() if entry.name != ".locks")
            self.assertEqual(left, sorted(theirs))
            self.assertEqual(list((Path(hub) / ".locks").iterdir()), [])
            self.assertEqual(sorted(entry.name for entry in checkpoints.iterdir()), sorted(their_files))

    def test_the_uninstaller_asks_and_removes_an_empty_data_folder(self) -> None:
        text = (HERE / "PampaCompanion.iss").read_text(encoding="utf-8")
        self.assertIn("RunHelper('purge-models')", text)
        # Di serie No: i modelli li usa anche un'altra copia del companion.
        self.assertIn("MB_DEFBUTTON2) = IDYES then\n        RunHelper('purge-models')", text)
        self.assertIn("RemoveDir(ExpandConstant('{localappdata}\\PampaNotes'))", text)
        self.assertNotIn("DelTree(ExpandConstant('{localappdata}\\PampaNotes'", text)
        # Il setup resta ASCII (vedi la sua intestazione).
        self.assertTrue(text.isascii())


if __name__ == "__main__":
    unittest.main()
