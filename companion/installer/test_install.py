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
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
