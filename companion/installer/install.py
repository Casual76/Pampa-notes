"""
L'installazione del companion, per chi non ha mai aperto un terminale.

Era `setup.ps1` piu' mezza pagina di README: un Python della versione giusta, un ambiente, WhisperX,
torch nella versione per la scheda, il firewall, l'avvio automatico, e le due righe dell'account in
`config.json`. Ognuno di questi passi era un posto dove fermarsi. Qui li fa tutti una finestra con
una barra, nell'ordine giusto, e dove la rete cade c'e' «Riprova» invece di un errore rosso.

Come si arriva qui:

* dal setup (`PampaCompanion.iss`), che copia il codice, prepara con `uv` un Python 3.11 tutto suo
  e l'ambiente `.venv`, e poi lancia questo file con quel Python;
* a mano, per provarlo: `python install.py --app C:\\cartella\\di\\prova --port 8799`. Senza `uv`
  crea l'ambiente con il Python che lo sta eseguendo, se e' un 3.9-3.12.

I passi, e perche' in quest'ordine:

 1. **controlli**: Windows 10 o 11, almeno 10 GB liberi (torch per CUDA sono due gigabyte e mezzo, il
    modello tre);
 2. **scheda video**: `nvidia-smi` dice nome, memoria e driver. Il driver sceglie torch (cu128 dal
    570, cu126 prima); senza NVIDIA si va sul processore, e lo si dice;
 3. **ambiente** e **pacchetti**: prima WhisperX, poi torch per la scheda sopra, come `setup.ps1` —
    WhisperX si porta dietro un torch senza CUDA che scavalca quello giusto;
 4. **ffmpeg**, che WhisperX chiama per nome: se nel PATH non c'e', se ne mette uno in `bin/`
    (`config.add_local_bin` lo aggiunge al PATH del companion);
 5. **il modello**, scelto con la stima della VRAM del companion stesso (`plan_vram`) e scaricato
    subito, con la barra, invece che alla prima lezione;
 6. **config.json**: su un'installazione nuova il modello, la porta e un codice per i dispositivi
    senza account; su una che c'era gia' non si tocca niente di scritto;
 7. **firewall** (con UAC, solo per questo passo), **avvio automatico** (lo stesso collegamento del
    menu dell'icona), **Tailscale** (c'e'? quale indirizzo?);
 8. **avvio** dell'icona e la **pagina del QR**, da inquadrare col telefono: e' li' che il computer
    diventa del tuo account (vedi `binding.py`).

`--upgrade` (il setup lo passa quando l'ambiente c'era gia'): ambiente e modello restano, il codice
e' gia' stato sostituito, e l'icona si riavvia **solo** quando `/health` dice che non sta
trascrivendo e non c'e' nessuno in fila.

La logica pura (la lettura di `nvidia-smi`, la scelta del modello, l'unione del config, le regole del
riavvio) sta in funzioni senza effetti, provate da `test_install.py`.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
import platform
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
import webbrowser
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

HERE = Path(__file__).resolve().parent
DEFAULT_APP = HERE.parent

# La coppia provata, come in setup.ps1: whisperx 3.x con torch 2.8.
TORCH_VERSION = "2.8.0"
TORCH_INDEX = "https://download.pytorch.org/whl/"
PYTHON_VERSION = "3.11"
DEFAULT_PORT = 8765
MIN_FREE_GB = 10.0
MIN_FREE_GB_UPGRADE = 2.0
# Il driver minimo per le ruote di torch: CUDA 12.8 vuole il ramo 570, 12.6 il 560; sotto, le
# ruote 12.x girano ancora dal 528 (compatibilita' fra versioni minori), ma e' il momento di dirlo.
DRIVER_CU128 = 570
DRIVER_CU126 = 528
HEALTH_WAIT_S = 300
# Quanto si aspetta la fine di una trascrizione prima di riavviare l'icona in un aggiornamento.
BUSY_WAIT_S = 3 * 3600

TAILSCALE_URL = "https://tailscale.com/download/windows"
NVIDIA_DRIVERS_URL = "https://www.nvidia.com/Download/index.aspx"
STARTUP_LINK = "Pampa Notes companion.lnk"

NO_WINDOW = 0x08000000 if os.name == "nt" else 0
DETACHED = 0x00000008 | 0x00000200 if os.name == "nt" else 0

# Le parole che nell'output di pip o di uv dicono «e' la rete», per scriverlo nel messaggio.
NETWORK_HINTS = (
    "connection", "timed out", "timeout", "temporary failure", "getaddrinfo", "name resolution",
    "network", "ssl", "failed to fetch", "failed to download", "remote end closed", "reset by peer",
    "unreachable", "proxy", "http error", "status code 5",
)


# --- la logica pura ------------------------------------------------------------------------------


@dataclass(frozen=True)
class Gpu:
    name: str
    total_gb: float
    driver: str = ""

    @property
    def driver_major(self) -> int:
        match = re.match(r"^\s*(\d+)", self.driver or "")
        return int(match.group(1)) if match else 0


def parse_nvidia_smi(output: str) -> list[Gpu]:
    """
    Le righe di `nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader,nounits`.

    La memoria e' in MiB. Una riga che non si legge si salta: meglio una scheda in meno che un
    installer che si ferma su un formato che non conosce.
    """
    gpus: list[Gpu] = []
    for line in (output or "").splitlines():
        parts = [part.strip() for part in line.split(",")]
        if len(parts) < 2 or not parts[0]:
            continue
        memory = re.sub(r"[^0-9.]", "", parts[1])
        try:
            mib = float(memory)
        except ValueError:
            continue
        gpus.append(Gpu(parts[0], round(mib / 1024, 1), parts[2] if len(parts) > 2 else ""))
    return gpus


def best_gpu(gpus: list[Gpu]) -> Gpu | None:
    """Con piu' schede, quella con piu' memoria: e' quella su cui ha senso il modello grande."""
    return max(gpus, key=lambda gpu: gpu.total_gb) if gpus else None


def cuda_flavor(gpu: Gpu | None) -> str:
    """Quale torch: `cu128`, `cu126` o `cpu`. Senza driver leggibile, il piu' recente."""
    if gpu is None:
        return "cpu"
    major = gpu.driver_major
    if major == 0 or major >= DRIVER_CU128:
        return "cu128"
    if major >= DRIVER_CU126:
        return "cu126"
    return "cpu"


def torch_ok(lines: list[str], flavor: str) -> bool:
    """`import torch; print(version); print(cuda)` dice gia' quello giusto? Allora non si riscarica."""
    if flavor == "cpu":
        return bool(lines) and lines[0].strip().startswith(TORCH_VERSION.rsplit(".", 1)[0])
    if len(lines) < 2:
        return False
    return lines[0].strip() == f"{TORCH_VERSION}+{flavor}" and lines[1].strip() == "True"


# Una copia delle tabelle di `whisperx_server.plan_vram`, solo per il caso in cui il server non si
# lascia importare (un pacchetto che manca a meta' installazione). `test_install` controlla che dica
# le stesse cose dell'originale.
_WEIGHTS = {"large-v3": 3.1, "medium": 1.5, "small": 0.5, "base": 0.15, "tiny": 0.08}
_BATCH_SCALE = {"large-v3": 1.0, "medium": 0.6, "small": 0.32, "base": 0.12, "tiny": 0.06}
_CHAIN = (("large-v3", "float16"), ("large-v3", "int8_float16"), ("medium", "int8_float16"),
          ("small", "int8_float16"), ("base", "int8_float16"), ("tiny", "int8_float16"))


def fallback_plan(total_gb: float, batch_max: int = 16) -> dict[str, Any]:
    """Modello, calcolo e lotto per `large-v3` float16 su `total_gb`, come li sceglierebbe il server."""
    usable = float(total_gb) * 0.85
    want = min(4, batch_max)
    chosen = None
    fallback = None
    for model, compute in _CHAIN:
        factor = 1.0 if compute == "float16" else 0.55
        fixed = _WEIGHTS[model] * factor + 0.4 + 0.7
        per_item = 0.25 * _BATCH_SCALE[model]
        batch = min(batch_max, math.floor((usable - fixed) / per_item + 1e-9))
        if batch >= want:
            chosen = (model, compute, batch)
            break
        if batch >= 1 and fallback is None:
            fallback = (model, compute, batch)
    fits = True
    if chosen is None:
        chosen = fallback
    if chosen is None:
        fits = False
        chosen = (_CHAIN[-1][0], _CHAIN[-1][1], 1)
    return {"model": chosen[0], "compute_type": chosen[1], "batch_size": chosen[2], "fits": fits}


@dataclass(frozen=True)
class ModelChoice:
    """Cosa scrivere in config.json, e quale modello scaricare subito."""

    model: str
    compute_type: str
    device: str
    download: str
    on_cpu: bool
    note: str = ""


def choose_model(gpu: Gpu | None, cuda_works: bool, planner: Callable[[float], dict[str, Any]] | None = None) -> ModelChoice:
    """
    Con una scheda che torch vede, il config resta `large-v3` in automatico: la scelta vera la fa il
    server a ogni avvio sulla memoria che c'e' (e scende da solo, se serve). Si scarica pero' il
    modello che quella scelta userebbe oggi. Senza scheda, `small` in int8 sul processore: `large`
    sul processore e' un'ora per un'ora di lezione.
    """
    if gpu is None or not cuda_works:
        return ModelChoice("small", "int8", "cpu", "small", True, "sul processore: piu' lento, ma funziona")
    plan = (planner or fallback_plan)(gpu.total_gb)
    note = f"{plan.get('model')} {plan.get('compute_type')}, lotto {plan.get('batch_size')} su {gpu.total_gb:.0f} GB"
    return ModelChoice("large-v3", "", "auto", str(plan.get("model") or "large-v3"), False, note)


def merge_config(existing: dict[str, Any] | None, choice: ModelChoice, port: int, token_factory: Callable[[], str]) -> tuple[dict[str, Any], list[str]]:
    """
    Il config.json da scrivere, e le chiavi aggiunte.

    Nuovo: modello, calcolo, dispositivo, porta, un codice per i dispositivi senza account e
    l'accesso libero spento — chi arriva col setup ha l'app nuova, che manda il biglietto.

    Esistente (un aggiornamento, o chi reinstalla sopra una cartella fatta a mano): quello che c'e'
    vince sempre, e si aggiunge solo cio' che manca fra modello, calcolo, dispositivo e porta. Non
    `accept_anonymous`: su un file che non l'ha la decide `config.load` alla prima lettura, ed e' la
    decisione giusta per un companion che aveva gia' dei dispositivi collegati.
    """
    ours: dict[str, Any] = {
        "model": choice.model,
        "compute_type": choice.compute_type,
        "device": choice.device,
        "port": int(port),
    }
    if existing is None:
        fresh = dict(ours)
        fresh["token"] = token_factory()
        fresh["accept_anonymous"] = False
        return fresh, list(fresh)
    merged = dict(existing)
    added = [key for key in ours if key not in merged]
    for key in added:
        merged[key] = ours[key]
    return merged, added


def windows_status(major: int, build: int) -> tuple[bool, str]:
    if major < 10:
        return False, "Windows piu' vecchio del 10"
    return True, "Windows 11" if build >= 22000 else "Windows 10"


def enough_space(free_bytes: int, upgrade: bool) -> bool:
    return free_bytes / 1024**3 >= (MIN_FREE_GB_UPGRADE if upgrade else MIN_FREE_GB)


def looks_like_network(text: str) -> bool:
    lowered = (text or "").lower()
    return any(hint in lowered for hint in NETWORK_HINTS)


def restart_allowed(health: dict[str, Any] | None) -> bool:
    """
    Si puo' fermare l'icona adesso? Si', se non risponde (non c'e' niente da interrompere) o se dice
    che non sta trascrivendo, che non c'e' nessuno in fila e nessuna richiesta a meta' (`inflight`:
    un telefono che sta ancora caricando una lezione, un file che sale nell'archivio). Un companion
    vecchio `inflight` non lo dice, e vale come zero: e' quello che si faceva prima.
    """
    if health is None:
        return True
    return (
        not health.get("busy")
        and int(health.get("queue") or 0) == 0
        and int(health.get("inflight") or 0) == 0
    )


def is_tailscale(address: str) -> bool:
    parts = address.split(".")
    try:
        return len(parts) == 4 and parts[0] == "100" and 64 <= int(parts[1]) <= 127
    except ValueError:
        return False


def pick_tailscale(addresses: list[str]) -> str | None:
    return next((address for address in addresses if is_tailscale(address)), None)


def ps_quote(value: Any) -> str:
    """Una stringa PowerShell fra apici singoli: dentro, l'apice si scrive due volte."""
    return "'" + str(value).replace("'", "''") + "'"


def encoded(script: str) -> str:
    """Per `powershell -EncodedCommand`: niente virgolette da far sopravvivere a due interpreti."""
    return base64.b64encode(script.encode("utf-16-le")).decode("ascii")


# --- chi mostra quello che succede ---------------------------------------------------------------


class Cancelled(Exception):
    pass


class StepFailed(Exception):
    def __init__(self, message: str, detail: str = "", network: bool | None = None) -> None:
        super().__init__(message)
        self.detail = detail
        self.network = looks_like_network(detail or message) if network is None else network


class Reporter:
    """Quello che i passi dicono. La finestra e la console lo mostrano in modi diversi."""

    def step(self, index: int, total: int, title: str) -> None: ...
    def status(self, text: str) -> None: ...
    def progress(self, fraction: float | None) -> None: ...
    def log(self, line: str) -> None: ...
    def note(self, text: str, warn: bool = False) -> None: ...
    def ask_retry(self, error: StepFailed) -> bool: return False
    def finish(self, ok: bool, summary: list[str], pair_url: str | None) -> None: ...


class ConsoleReporter(Reporter):
    def __init__(self, interactive: bool) -> None:
        self.interactive = interactive

    def step(self, index: int, total: int, title: str) -> None:
        print(f"\n[{index}/{total}] {title}", flush=True)

    def status(self, text: str) -> None:
        print(f"    {text}", flush=True)

    def progress(self, fraction: float | None) -> None:
        pass

    def log(self, line: str) -> None:
        pass

    def note(self, text: str, warn: bool = False) -> None:
        print(("  ! " if warn else "  - ") + text, flush=True)

    def ask_retry(self, error: StepFailed) -> bool:
        print(f"\n  Non e' andata: {error}", flush=True)
        if error.detail:
            print("  " + error.detail[-800:].replace("\n", "\n  "), flush=True)
        if not self.interactive:
            return False
        try:
            answer = input("  Riprovo? [S/n] ").strip().lower()
        except EOFError:
            return False
        return answer in ("", "s", "si", "y", "yes")

    def finish(self, ok: bool, summary: list[str], pair_url: str | None) -> None:
        print("\n" + ("Fatto." if ok else "L'installazione non e' finita."), flush=True)
        for line in summary:
            print("  " + line, flush=True)


class WindowReporter(Reporter):
    """
    Una finestra Tk con una barra, i passi e il registro.

    I passi girano su un altro thread; qui si disegna e basta. Ogni chiamata mette una funzione in
    una coda che la finestra svuota dieci volte al secondo: Tk vuole essere toccato solo dal suo.
    """

    def __init__(self, title: str, on_cancel: Callable[[], None], auto_close: bool) -> None:
        import tkinter as tk
        from tkinter import scrolledtext, ttk

        self.tk = tk
        self.auto_close = auto_close
        self.on_cancel = on_cancel
        self.queue: queue.Queue[Callable[[], None]] = queue.Queue()
        self.answer = threading.Event()
        self.retry_choice = False
        self.done = False

        root = tk.Tk()
        self.root = root
        root.title(title)
        root.geometry("760x560")
        root.minsize(600, 460)
        try:
            ttk.Style().theme_use("vista")
        except tk.TclError:
            pass
        frame = ttk.Frame(root, padding=18)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="Pampa Notes - il computer di casa", font=("Segoe UI", 15, "bold")).pack(anchor="w")
        self.step_label = ttk.Label(frame, text="Preparo...", font=("Segoe UI", 11))
        self.step_label.pack(anchor="w", pady=(10, 2))
        self.status_label = ttk.Label(frame, text="", foreground="#555555", wraplength=700, justify="left")
        self.status_label.pack(anchor="w", pady=(0, 8))
        self.bar = ttk.Progressbar(frame, mode="indeterminate", length=700)
        self.bar.pack(fill="x")
        self.bar.start(12)
        self.notes = ttk.Label(frame, text="", wraplength=700, justify="left")
        self.notes.pack(anchor="w", pady=(10, 4), fill="x")
        self.text = scrolledtext.ScrolledText(frame, height=12, font=("Consolas", 9), state="disabled")
        self.text.pack(fill="both", expand=True, pady=(4, 10))
        buttons = ttk.Frame(frame)
        buttons.pack(fill="x")
        self.primary = ttk.Button(buttons, text="Annulla", command=self._primary)
        self.primary.pack(side="right")
        self.retry = ttk.Button(buttons, text="Riprova", command=lambda: self._choose(True))
        self.qr = ttk.Button(buttons, text="Mostra il QR", command=self._open_qr)
        self.pair_url: str | None = None
        self._notes: list[str] = []
        root.protocol("WM_DELETE_WINDOW", self._primary)
        root.after(100, self._pump)

    # chiamate dal thread dei passi
    def _later(self, action: Callable[[], None]) -> None:
        self.queue.put(action)

    def step(self, index: int, total: int, title: str) -> None:
        self._later(lambda: self.step_label.configure(text=f"{index} di {total} - {title}"))

    def status(self, text: str) -> None:
        self._later(lambda: self.status_label.configure(text=text))

    def progress(self, fraction: float | None) -> None:
        def apply() -> None:
            if fraction is None:
                if str(self.bar["mode"]) != "indeterminate":
                    self.bar.configure(mode="indeterminate")
                    self.bar.start(12)
            else:
                if str(self.bar["mode"]) != "determinate":
                    self.bar.stop()
                    self.bar.configure(mode="determinate", maximum=1000)
                self.bar["value"] = max(0, min(1000, int(fraction * 1000)))

        self._later(apply)

    def log(self, line: str) -> None:
        def apply() -> None:
            self.text.configure(state="normal")
            self.text.insert("end", line.rstrip() + "\n")
            # Il registro a schermo tiene le ultime duemila righe; quello su file tutto.
            if int(self.text.index("end-1c").split(".")[0]) > 2000:
                self.text.delete("1.0", "200.0")
            self.text.see("end")
            self.text.configure(state="disabled")

        self._later(apply)

    def note(self, text: str, warn: bool = False) -> None:
        def apply() -> None:
            self._notes.append(("! " if warn else "") + text)
            self.notes.configure(text="\n".join(self._notes[-6:]))

        self._later(apply)

    def ask_retry(self, error: StepFailed) -> bool:
        self.answer.clear()

        def apply() -> None:
            hint = " Controlla la connessione, poi riprova." if error.network else ""
            self.status_label.configure(text=f"Non e' andata: {error}.{hint}", foreground="#b3261e")
            self.bar.stop()
            self.retry.pack(side="right", padx=(0, 8))
            self.primary.configure(text="Esci")

        self._later(apply)
        self.answer.wait()
        self._later(lambda: (self.retry.pack_forget(), self.primary.configure(text="Annulla"),
                             self.status_label.configure(foreground="#555555")))
        return self.retry_choice

    def finish(self, ok: bool, summary: list[str], pair_url: str | None) -> None:
        def apply() -> None:
            self.done = True
            self.bar.stop()
            self.bar.configure(mode="determinate", maximum=1000)
            self.bar["value"] = 1000 if ok else 0
            self.step_label.configure(text="Fatto: il computer e' pronto." if ok else "L'installazione non e' finita.")
            self.status_label.configure(text="\n".join(summary), foreground="#1c1b1f")
            self.primary.configure(text="Fine")
            self.pair_url = pair_url
            if pair_url:
                self.qr.pack(side="right", padx=(0, 8))
            if self.auto_close:
                self.root.after(4000, self.root.destroy)

        self._later(apply)

    # dalla finestra
    def _choose(self, retry: bool) -> None:
        self.retry_choice = retry
        self.answer.set()

    def _primary(self) -> None:
        if self.done:
            self.root.destroy()
            return
        if not self.answer.is_set() and self.retry.winfo_ismapped():
            self._choose(False)
            return
        from tkinter import messagebox

        if messagebox.askyesno("Pampa Notes", "Interrompere l'installazione?\n\nSi riprende dal menu Start: «Pampa Notes companion - ripara»."):
            self.on_cancel()
            self.root.destroy()

    def _open_qr(self) -> None:
        if self.pair_url:
            webbrowser.open(self.pair_url)

    def _pump(self) -> None:
        try:
            while True:
                self.queue.get_nowait()()
        except queue.Empty:
            pass
        try:
            self.root.after(100, self._pump)
        except self.tk.TclError:
            pass

    def run(self) -> None:
        self.root.mainloop()


# --- il lavoro --------------------------------------------------------------------------------


@dataclass
class Context:
    app: Path
    port: int
    upgrade: bool
    uv: Path | None
    options: argparse.Namespace
    reporter: Reporter
    log_path: Path
    gpu: Gpu | None = None
    flavor: str = "cpu"
    cuda_works: bool = False
    choice: ModelChoice | None = None
    summary: list[str] = field(default_factory=list)
    process: subprocess.Popen[str] | None = None
    cancelled: bool = False

    @property
    def venv(self) -> Path:
        return self.app / ".venv"

    @property
    def python(self) -> Path:
        return self.venv / ("Scripts/python.exe" if os.name == "nt" else "bin/python")

    @property
    def pythonw(self) -> Path:
        windowed = self.venv / "Scripts/pythonw.exe"
        return windowed if windowed.exists() else self.python

    @property
    def config_path(self) -> Path:
        return self.app / "config.json"

    def write_log(self, line: str) -> None:
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        with self.log_path.open("a", encoding="utf-8") as out:
            out.write(f"{datetime.now():%Y-%m-%d %H:%M:%S}  {line.rstrip()}\n")

    def say(self, text: str) -> None:
        self.write_log(text)
        self.reporter.status(text)

    def note(self, text: str, warn: bool = False) -> None:
        self.write_log(("ATTENZIONE: " if warn else "") + text)
        self.reporter.note(text, warn)

    def uv_env(self) -> dict[str, str]:
        env = dict(os.environ)
        # Il Python di uv sta dentro la cartella del companion: la disinstallazione lo porta via, e
        # non tocca nessun altro Python del computer.
        env["UV_PYTHON_INSTALL_DIR"] = str(self.app / "python")
        env["UV_PYTHON_PREFERENCE"] = "only-managed"
        env["UV_LINK_MODE"] = "copy"
        env["UV_HTTP_TIMEOUT"] = "120"
        env["PYTHONUTF8"] = "1"
        env["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
        return env

    def run(self, command: list[str], cwd: Path | None = None, on_line: Callable[[str], None] | None = None,
            env: dict[str, str] | None = None) -> tuple[int, list[str]]:
        """Un comando, con ogni riga nel registro. Torna il codice e le ultime righe."""
        if self.cancelled:
            raise Cancelled()
        self.write_log("$ " + " ".join(command)[:300])
        tail: list[str] = []
        try:
            process = subprocess.Popen(
                command, cwd=str(cwd or self.app), env=env or self.uv_env(), stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                creationflags=NO_WINDOW,
            )
        except OSError as error:
            return 127, [str(error)]
        self.process = process
        assert process.stdout is not None
        for line in process.stdout:
            line = line.rstrip()
            if not line:
                continue
            self.write_log("  " + line)
            self.reporter.log(line)
            tail = (tail + [line])[-40:]
            if on_line:
                on_line(line)
        code = process.wait()
        self.process = None
        if self.cancelled:
            raise Cancelled()
        return code, tail

    def must(self, command: list[str], message: str, **kwargs: Any) -> list[str]:
        code, tail = self.run(command, **kwargs)
        if code != 0:
            raise StepFailed(message, "\n".join(tail))
        return tail


def installer_uv(options: argparse.Namespace, app: Path) -> Path | None:
    candidates = [Path(options.uv)] if options.uv else []
    candidates += [app / "installer" / "uv.exe", HERE / "uv.exe"]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    found = shutil.which("uv")
    return Path(found) if found else None


def health(port: int, timeout: float = 2.0) -> dict[str, Any] | None:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=timeout) as response:
            answer = json.loads(response.read() or b"{}")
            return answer if isinstance(answer, dict) else {}
    except (OSError, ValueError):
        return None


def primary_address() -> str | None:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            return probe.getsockname()[0]
    except OSError:
        return None


def all_addresses() -> list[str]:
    found: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if info[4][0] not in found:
                found.append(info[4][0])
    except OSError:
        pass
    return found


# --- i passi -------------------------------------------------------------------------------------


def step_checks(ctx: Context) -> None:
    if os.name == "nt":
        version = sys.getwindowsversion()  # type: ignore[attr-defined]
        ok, label = windows_status(version.major, version.build)
        if not ok:
            raise StepFailed("serve Windows 10 o 11", network=False)
        ctx.say(f"{label} (build {version.build})")
    else:
        ctx.note(f"non e' Windows ({platform.system()}): vado avanti, ma il setup e' pensato per Windows", warn=True)
    ctx.app.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(ctx.app).free
    needed = MIN_FREE_GB_UPGRADE if ctx.upgrade else MIN_FREE_GB
    if not enough_space(free, ctx.upgrade):
        raise StepFailed(f"servono almeno {needed:.0f} GB liberi, ce ne sono {free / 1024**3:.1f}", network=False)
    ctx.say(f"spazio libero: {free / 1024**3:.0f} GB")


def find_nvidia_smi() -> str | None:
    found = shutil.which("nvidia-smi")
    if found:
        return found
    for candidate in (r"C:\Windows\System32\nvidia-smi.exe", r"C:\Program Files\NVIDIA Corporation\NVSMI\nvidia-smi.exe"):
        if Path(candidate).is_file():
            return candidate
    return None


def step_gpu(ctx: Context) -> None:
    smi = find_nvidia_smi()
    gpus: list[Gpu] = []
    if smi:
        code, lines = ctx.run([smi, "--query-gpu=name,memory.total,driver_version", "--format=csv,noheader,nounits"])
        if code == 0:
            gpus = parse_nvidia_smi("\n".join(lines))
    ctx.gpu = best_gpu(gpus)
    ctx.flavor = cuda_flavor(ctx.gpu) if not ctx.options.cpu else "cpu"
    if ctx.gpu is None:
        ctx.note("Nessuna scheda NVIDIA: il companion trascrivera' sul processore, con un modello piu' piccolo. "
                 "Funziona, ma un'ora di lezione puo' chiedere mezz'ora.", warn=True)
        return
    ctx.say(f"{ctx.gpu.name}, {ctx.gpu.total_gb:.1f} GB, driver {ctx.gpu.driver or '?'}")
    if ctx.flavor == "cpu" and not ctx.options.cpu:
        ctx.note(f"Il driver NVIDIA ({ctx.gpu.driver}) e' troppo vecchio: per ora si va sul processore. "
                 f"Aggiornalo da {NVIDIA_DRIVERS_URL} e rilancia «ripara».", warn=True)
    elif ctx.flavor == "cu126":
        ctx.note(f"Driver {ctx.gpu.driver}: uso torch per CUDA 12.6. Con un driver dal 570 in su va la versione piu' nuova.")


def step_venv(ctx: Context) -> None:
    if ctx.python.exists():
        ctx.say("l'ambiente Python c'e' gia'")
        return
    if ctx.uv:
        ctx.say(f"preparo Python {PYTHON_VERSION} e l'ambiente (una trentina di MB)...")
        ctx.must([str(ctx.uv), "venv", "--python", PYTHON_VERSION, "--seed", str(ctx.venv)], "l'ambiente Python non si crea")
        return
    if not ((3, 9) <= sys.version_info[:2] <= (3, 12)):
        raise StepFailed(f"serve uv, o un Python 3.9-3.12 (questo e' {platform.python_version()})", network=False)
    ctx.say("creo l'ambiente con questo Python...")
    ctx.must([sys.executable, "-m", "venv", str(ctx.venv)], "l'ambiente Python non si crea")


def pip_install(ctx: Context, arguments: list[str], message: str) -> None:
    def show(line: str) -> None:
        if any(word in line for word in ("Downloading", "Downloaded", "Collecting", "Installing", "Prepared", "Resolved", "Installed")):
            ctx.reporter.status(line.strip()[:140])

    if ctx.uv:
        command = [str(ctx.uv), "pip", "install", "--python", str(ctx.python), *arguments]
    else:
        # I due nomi che cambiano fra uv e pip.
        translated = ["--force-reinstall" if argument == "--reinstall" else argument for argument in arguments]
        command = [str(ctx.python), "-m", "pip", "install", *translated]
    ctx.must(command, message, on_line=show)


def step_packages(ctx: Context) -> None:
    ctx.say("installo WhisperX e il server (qualche minuto, un paio di GB)...")
    pip_install(ctx, ["-r", str(ctx.app / "requirements.txt")], "i pacchetti non si installano")


def torch_lines(ctx: Context) -> list[str]:
    code, lines = ctx.run([str(ctx.python), "-c", "import torch; print(torch.__version__); print(torch.cuda.is_available())"])
    return [line for line in lines if line.strip()][-2:] if code == 0 else []


def step_torch(ctx: Context) -> None:
    if ctx.flavor == "cpu":
        ctx.say("torch per il processore: e' quello che WhisperX ha gia' portato")
        return
    if torch_ok(torch_lines(ctx), ctx.flavor) and not ctx.options.force_torch:
        ctx.say(f"torch {TORCH_VERSION}+{ctx.flavor} c'e' gia' e vede la scheda")
        return
    ctx.say(f"metto torch {TORCH_VERSION} per la scheda ({ctx.flavor}, due gigabyte e mezzo)...")
    pip_install(
        ctx,
        ["--reinstall", "--no-deps", "--index-url", TORCH_INDEX + ctx.flavor, f"torch=={TORCH_VERSION}", f"torchaudio=={TORCH_VERSION}"],
        "torch per la scheda non si installa",
    )


def step_ffmpeg(ctx: Context) -> None:
    local = ctx.app / "bin" / "ffmpeg.exe"
    if local.exists() or shutil.which("ffmpeg"):
        ctx.say("ffmpeg c'e'")
        return
    ctx.say("ffmpeg non c'e': ne metto uno accanto al companion...")
    pip_install(ctx, ["imageio-ffmpeg"], "ffmpeg non si installa")
    code, lines = ctx.run([str(ctx.python), "-c", "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"])
    source = Path(lines[-1].strip()) if code == 0 and lines else None
    if source is None or not source.is_file():
        raise StepFailed("ffmpeg non si trova dopo l'installazione", "\n".join(lines), network=False)
    local.parent.mkdir(exist_ok=True)
    shutil.copy2(source, local)


def step_verify(ctx: Context) -> None:
    ctx.say("controllo che torch e WhisperX si carichino...")
    code, lines = ctx.run([str(ctx.python), "-c", "import torch, whisperx; print(torch.__version__); print(torch.cuda.is_available())"])
    if code != 0:
        raise StepFailed("torch o WhisperX non si caricano", "\n".join(lines), network=False)
    values = [line.strip() for line in lines if line.strip()][-2:]
    ctx.cuda_works = len(values) == 2 and values[1] == "True"
    if ctx.flavor != "cpu" and not ctx.cuda_works:
        ctx.note("torch non vede la scheda: quasi sempre e' il driver. Per ora il companion va sul processore.", warn=True)
    ctx.say(f"torch {values[0] if values else '?'}" + (" con la scheda" if ctx.cuda_works else ", sul processore"))


def server_planner(ctx: Context) -> Callable[[float], dict[str, Any]]:
    """`plan_vram` del companion vero, dentro l'ambiente; se non si lascia importare, la copia."""

    def plan(total_gb: float) -> dict[str, Any]:
        script = (
            "import json, sys; sys.path.insert(0, sys.argv[1]); import whisperx_server as s; "
            "print('PLAN ' + json.dumps(s.plan_vram('large-v3', 'float16', 16, float(sys.argv[2]))))"
        )
        code, lines = ctx.run([str(ctx.python), "-c", script, str(ctx.app), str(total_gb)])
        for line in reversed(lines):
            if code == 0 and line.startswith("PLAN "):
                try:
                    return json.loads(line[5:])
                except ValueError:
                    break
        ctx.write_log("stima del server non disponibile: uso la copia dell'installer")
        return fallback_plan(total_gb)

    return plan


def step_model(ctx: Context) -> None:
    ctx.choice = choose_model(ctx.gpu, ctx.cuda_works, server_planner(ctx))
    if ctx.choice.on_cpu:
        ctx.note(f"Modello: {ctx.choice.download} ({ctx.choice.note}).")
    else:
        ctx.note(f"Modello: {ctx.choice.note}.")
    if ctx.options.skip_model:
        ctx.say("scaricamento del modello saltato (--skip-model)")
        return
    ctx.say(f"scarico il modello {ctx.choice.download}...")
    fetch = str(HERE / "fetch_model.py")

    def show(line: str) -> None:
        if line.startswith("PROGRESS "):
            try:
                done, total = (int(value) for value in line.split()[1:3])
            except ValueError:
                return
            if total > 0:
                ctx.reporter.progress(min(1.0, done / total))
                ctx.reporter.status(f"modello {ctx.choice.download}: {done / 1024**3:.2f} di {total / 1024**3:.2f} GB")
            else:
                ctx.reporter.progress(None)
                ctx.reporter.status(f"modello {ctx.choice.download}: {done / 1024**3:.2f} GB")

    code, lines = ctx.run([str(ctx.python), fetch, ctx.choice.download], on_line=show)
    ctx.reporter.progress(None)
    if code != 0:
        raise StepFailed(f"il modello {ctx.choice.download} non si scarica", "\n".join(lines))
    ctx.say("scarico l'allineamento delle parole per l'italiano...")
    code, lines = ctx.run([str(ctx.python), fetch, "--align", "it"])
    if code != 0:
        ctx.note("L'allineamento delle parole arrivera' con la prima lezione: adesso non si e' scaricato.", warn=True)


def read_config(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise StepFailed(f"config.json non si legge ({error}): correggilo o spostalo, poi riprova", network=False) from error
    return loaded if isinstance(loaded, dict) else {}


def step_config(ctx: Context) -> None:
    choice = ctx.choice or choose_model(ctx.gpu, ctx.cuda_works)
    existing = read_config(ctx.config_path)
    merged, added = merge_config(existing, choice, ctx.port, lambda: secrets.token_urlsafe(9))
    if existing is not None and not added:
        ctx.say("config.json c'era gia': resta com'e'")
    else:
        ctx.config_path.write_text(json.dumps(merged, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        ctx.say("config.json scritto" if existing is None else "config.json: aggiunte " + ", ".join(added))
    ctx.port = int(merged.get("port") or ctx.port)
    if merged.get("token"):
        ctx.summary.append(f"Codice per i dispositivi senza account: {merged['token']} (in config.json).")


def powershell(ctx: Context, script: str) -> tuple[int, list[str]]:
    """
    Uno script PowerShell, senza le barre di avanzamento: con l'output rediretto, PowerShell 5.1 le
    manda come XML (`#< CLIXML`) e riempiono il registro di rumore.
    """
    code, lines = ctx.run(["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                           "-EncodedCommand", encoded("$ProgressPreference = 'SilentlyContinue'; " + script)])
    return code, [line for line in lines if not line.startswith(("#< CLIXML", "<Objs"))]


def firewall_open(ctx: Context) -> bool:
    script = (
        "$r = Get-NetFirewallPortFilter -ErrorAction SilentlyContinue | "
        f"Where-Object {{ $_.Protocol -eq 'TCP' -and $_.LocalPort -eq '{ctx.port}' }} | "
        "Get-NetFirewallRule -ErrorAction SilentlyContinue | "
        "Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' -and $_.Enabled -eq 'True' }; "
        "if ($r) { exit 0 } else { exit 1 }"
    )
    code, _ = powershell(ctx, script)
    return code == 0


def step_firewall(ctx: Context) -> None:
    if os.name != "nt" or ctx.options.no_firewall:
        ctx.say("firewall: saltato")
        return
    ctx.say("controllo il firewall...")
    if firewall_open(ctx):
        ctx.say(f"la porta {ctx.port} e' gia' aperta per la rete di casa")
        return
    ctx.say(f"apro la porta {ctx.port} per la rete di casa: Windows chiedera' il permesso...")
    helper = ctx.app / "apri-firewall.ps1"
    arguments = ", ".join(ps_quote(value) for value in
                          ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", f'"{helper}"', "-Port", str(ctx.port), "-NoPause"])
    script = (
        "try { $p = Start-Process powershell -Verb RunAs -Wait -PassThru -WindowStyle Hidden "
        f"-ArgumentList @({arguments}); exit $p.ExitCode }} catch {{ exit 1223 }}"
    )
    code, _ = powershell(ctx, script)
    if code == 0 and firewall_open(ctx):
        ctx.say(f"porta {ctx.port} aperta, solo per i dispositivi della tua rete")
        return
    ctx.note("La porta nel firewall non e' stata aperta (permesso negato?). Se il telefono non trova il computer, "
             "lancia «apri-firewall.cmd» dalla cartella del companion.", warn=True)


def step_autostart(ctx: Context) -> None:
    if os.name != "nt" or ctx.options.no_autostart:
        ctx.say("avvio automatico: saltato")
        return
    if ctx.upgrade:
        # L'icona riscrive da se' il collegamento, se era acceso; chi l'aveva spento l'ha voluto.
        ctx.say("avvio automatico: come prima")
        return
    ctx.say("accendo l'avvio automatico...")
    code, lines = ctx.run([str(ctx.python), "-c", "import sys, tray; sys.exit(0 if tray.autostart_enable() else 1)"], cwd=ctx.app)
    if code != 0:
        ctx.note("L'avvio automatico non si e' acceso: lo trovi nel menu dell'icona.", warn=True)


def tailscale_ip(ctx: Context) -> str | None:
    exe = shutil.which("tailscale") or next(
        (path for path in (r"C:\Program Files\Tailscale\tailscale.exe",) if Path(path).is_file()), None)
    if exe:
        code, lines = ctx.run([exe, "ip", "-4"])
        if code == 0:
            ip = pick_tailscale([line.strip() for line in lines])
            if ip:
                return ip
    return pick_tailscale(all_addresses())


def step_tailscale(ctx: Context) -> None:
    ip = tailscale_ip(ctx)
    if ip:
        ctx.summary.append(f"Da fuori casa (Tailscale): http://{ip}:{ctx.port}")
        ctx.say(f"Tailscale c'e': da fuori casa il computer e' http://{ip}:{ctx.port}")
    else:
        ctx.summary.append(f"Da fuori casa: installa Tailscale ({TAILSCALE_URL}) qui e sul telefono, con lo stesso account.")
        ctx.note("Tailscale non c'e': in casa funziona tutto; per trascrivere da fuori casa installalo qui e sul "
                 f"telefono, con lo stesso account ({TAILSCALE_URL}).")


def listener_command_line(ctx: Context) -> tuple[int | None, str]:
    """Chi tiene la porta: il numero del processo e la sua riga di comando."""
    script = (
        f"$c = Get-NetTCPConnection -LocalPort {ctx.port} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; "
        "if (-not $c) { exit 3 }; "
        "$p = Get-CimInstance Win32_Process -Filter \"ProcessId=$($c.OwningProcess)\"; "
        "Write-Output \"PID $($c.OwningProcess)\"; Write-Output \"CMD $($p.CommandLine)\""
    )
    code, lines = powershell(ctx, script)
    pid: int | None = None
    command = ""
    for line in lines:
        if line.startswith("PID "):
            try:
                pid = int(line[4:].strip())
            except ValueError:
                pid = None
        elif line.startswith("CMD "):
            command = line[4:].strip()
    return pid, command


def ours(command_line: str, app: Path) -> bool:
    """La riga di comando e' quella di un companion di questa cartella?"""
    return str(app).lower() in command_line.lower()


def stop_running(ctx: Context) -> bool:
    """Ferma l'icona di questa cartella, se c'e'. Falso se sulla porta c'e' qualcun altro."""
    if health(ctx.port) is None:
        return True
    started = time.monotonic()
    while True:
        state = health(ctx.port)
        if restart_allowed(state):
            break
        if time.monotonic() - started > BUSY_WAIT_S:
            raise StepFailed("il companion sta ancora trascrivendo: riprova quando ha finito", network=False)
        ctx.say("il companion sta trascrivendo: aspetto che finisca prima di riavviarlo...")
        time.sleep(10)
    if os.name != "nt":
        return False
    pid, command = listener_command_line(ctx)
    if pid is None:
        return True
    if not ours(command, ctx.app):
        return False
    ctx.say("fermo il companion di prima...")
    ctx.run(["taskkill", "/PID", str(pid), "/T", "/F"])
    for _ in range(20):
        if health(ctx.port) is None:
            return True
        time.sleep(0.5)
    return health(ctx.port) is None


def step_stop(ctx: Context) -> None:
    """
    Ferma il companion di questa cartella prima di toccare il suo ambiente Python.

    Prima si fermava solo all'ultimo passo, per riavviarlo: nel frattempo WhisperX, torch e ffmpeg
    si reinstallavano sotto un processo vivo, che li aveva gia' caricati e poteva caricarne altri pezzi
    a meta' sostituzione — una lezione arrivata in quei minuti, o l'aggiornamento dall'icona, che
    lancia questo setup proprio mentre l'icona gira. Su Windows, poi, un `.pyd` in uso non si
    sovrascrive, e il passo falliva. L'attesa e' la stessa del riavvio ([stop_running]): mai durante
    una trascrizione o un caricamento. Uno di un'altra cartella non si tocca: il suo ambiente non e'
    questo.
    """
    running = health(ctx.port)
    if running is None:
        ctx.say("nessun companion acceso")
        return
    if os.name == "nt":
        _, command = listener_command_line(ctx)
        if command and not ours(command, ctx.app):
            ctx.say(f"sulla porta {ctx.port} c'e' un companion di un'altra cartella: non lo tocco")
            return
    if stop_running(ctx):
        ctx.say("companion fermato: lo riavvio alla fine")
        if ctx.options.no_start:
            ctx.note("Il companion e' fermo e non verra' riavviato (--no-start).", warn=True)


def step_start(ctx: Context) -> None:
    if ctx.options.no_start:
        ctx.say("avvio: saltato")
        return
    running = health(ctx.port)
    if running is not None and not ctx.upgrade:
        pid, command = listener_command_line(ctx) if os.name == "nt" else (None, "")
        if command and not ours(command, ctx.app):
            ctx.note(f"Sulla porta {ctx.port} risponde gia' un altro companion: non avvio questo.", warn=True)
            return
    if running is not None:
        if not stop_running(ctx):
            ctx.note(f"Sulla porta {ctx.port} c'e' un companion di un'altra cartella: non lo tocco.", warn=True)
            return
    ctx.say("avvio il companion accanto all'orologio...")
    logs = ctx.app / "logs"
    logs.mkdir(exist_ok=True)
    stderr = (logs / "tray-stderr.log").open("ab")
    subprocess.Popen(  # noqa: S603
        [str(ctx.pythonw), str(ctx.app / "tray.py")], cwd=str(ctx.app), stdin=subprocess.DEVNULL,
        stdout=stderr, stderr=stderr, close_fds=True, creationflags=DETACHED,
    )
    started = time.monotonic()
    while time.monotonic() - started < HEALTH_WAIT_S:
        if health(ctx.port) is not None:
            ctx.say(f"il companion risponde sulla porta {ctx.port}")
            return
        time.sleep(1)
    raise StepFailed("il companion non risponde: l'errore e' in logs\\tray-stderr.log e logs\\companion.log", network=False)


def pair_url(port: int) -> str:
    return f"http://127.0.0.1:{port}/pair/start"


def step_pair(ctx: Context) -> None:
    lan = primary_address()
    if lan and not is_tailscale(lan):
        ctx.summary.insert(0, f"In casa: http://{lan}:{ctx.port}")
    ctx.summary.append("Adesso: nell'app entra con Google, poi inquadra il QR col telefono e tocca «Collega».")
    if ctx.options.no_start or ctx.options.no_browser or ctx.upgrade:
        return
    webbrowser.open(pair_url(ctx.port))


@dataclass(frozen=True)
class Step:
    title: str
    run: Callable[[Context], None]


STEPS: tuple[Step, ...] = (
    Step("Controlli", step_checks),
    Step("Scheda video", step_gpu),
    # Prima di ogni passo che scrive in .venv: vedi step_stop.
    Step("Ferma il companion", step_stop),
    Step("Ambiente Python", step_venv),
    Step("WhisperX", step_packages),
    Step("torch per la scheda", step_torch),
    Step("ffmpeg", step_ffmpeg),
    Step("Verifica", step_verify),
    Step("Il modello", step_model),
    Step("Impostazioni", step_config),
    Step("Firewall", step_firewall),
    Step("Avvio automatico", step_autostart),
    Step("Tailscale", step_tailscale),
    Step("Avvio", step_start),
    Step("Collega il telefono", step_pair),
)


def run_steps(ctx: Context, steps: tuple[Step, ...] = STEPS) -> bool:
    """I passi in fila. Un errore chiede «Riprova»; senza nessuno a cui chiedere, due tentativi per la rete."""
    for index, step in enumerate(steps, start=1):
        ctx.reporter.step(index, len(steps), step.title)
        ctx.reporter.progress(None)
        ctx.write_log(f"== {index}/{len(steps)} {step.title}")
        automatic = 0
        while True:
            try:
                step.run(ctx)
                break
            except Cancelled:
                return False
            except StepFailed as error:
                ctx.write_log(f"FALLITO: {error}\n{error.detail}")
                if ctx.options.silent:
                    if error.network and automatic < 2:
                        automatic += 1
                        time.sleep(15 * automatic)
                        continue
                    return False
                if not ctx.reporter.ask_retry(error):
                    return False
            except Exception as error:  # noqa: BLE001 — un errore imprevisto non deve chiudere la finestra in silenzio
                detail = traceback.format_exc()
                ctx.write_log(detail)
                if ctx.options.silent or not ctx.reporter.ask_retry(StepFailed(f"errore imprevisto: {error}", detail, network=False)):
                    return False
    return True


def default_port(app: Path, requested: int | None) -> int:
    if requested:
        return requested
    try:
        stored = json.loads((app / "config.json").read_text(encoding="utf-8"))
        return int(stored.get("port") or DEFAULT_PORT)
    except (OSError, ValueError, TypeError, AttributeError):
        return DEFAULT_PORT


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Installa il companion di Pampa Notes")
    parser.add_argument("--app", default=str(DEFAULT_APP), help="la cartella del companion (di serie, quella sopra installer/)")
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--upgrade", action="store_true", help="aggiornamento: ambiente e modello restano")
    parser.add_argument("--uv", default=None, help="il percorso di uv.exe")
    parser.add_argument("--cpu", action="store_true", help="senza scheda anche se c'e'")
    parser.add_argument("--force-torch", action="store_true", help="rimette torch anche se c'e' gia'")
    parser.add_argument("--silent", action="store_true", help="nessuna domanda: due tentativi per la rete, poi si esce")
    parser.add_argument("--auto-close", action="store_true", help="chiude la finestra alla fine")
    parser.add_argument("--console", action="store_true", help="senza finestra")
    parser.add_argument("--no-firewall", action="store_true")
    parser.add_argument("--no-autostart", action="store_true")
    parser.add_argument("--no-start", action="store_true")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--skip-model", action="store_true")
    parser.add_argument("--pair-only", action="store_true", help="solo: accendi il companion e mostra il QR")
    return parser.parse_args(argv)


def pair_only(options: argparse.Namespace, app: Path) -> int:
    port = default_port(app, options.port)
    if health(port) is None:
        pythonw = app / ".venv" / "Scripts" / "pythonw.exe"
        if pythonw.exists():
            subprocess.Popen([str(pythonw), str(app / "tray.py")], cwd=str(app), creationflags=DETACHED, close_fds=True)  # noqa: S603
        for _ in range(120):
            if health(port) is not None:
                break
            time.sleep(1)
    webbrowser.open(pair_url(port))
    return 0


def relaunch_in_console(argv: list[str]) -> int:
    """
    Senza tkinter e senza console (pythonw) non si vedrebbe niente: si riparte con python.exe in una
    finestra di console, che almeno mostra i passi.
    """
    console = Path(sys.executable).with_name("python.exe")
    flags = 0x00000010 if os.name == "nt" else 0  # CREATE_NEW_CONSOLE
    return subprocess.call([str(console), str(Path(__file__).resolve()), *argv, "--console"], creationflags=flags)  # noqa: S603


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    options = parse_args(argv)
    app = Path(options.app).resolve()
    if options.pair_only:
        return pair_only(options, app)
    upgrade = options.upgrade
    ctx_holder: dict[str, Context] = {}

    def cancel() -> None:
        ctx = ctx_holder.get("ctx")
        if ctx is None:
            return
        ctx.cancelled = True
        if ctx.process is not None:
            try:
                ctx.process.kill()
            except OSError:
                pass

    reporter: Reporter
    window: WindowReporter | None = None
    if options.console:
        reporter = ConsoleReporter(interactive=not options.silent)
    else:
        try:
            window = WindowReporter("Pampa Notes - installazione del companion", cancel, options.auto_close or options.silent)
            reporter = window
        except Exception:  # noqa: BLE001 — tkinter assente o senza display
            if sys.stdout is None:
                return relaunch_in_console(argv)
            reporter = ConsoleReporter(interactive=not options.silent)

    ctx = Context(
        app=app,
        port=default_port(app, options.port),
        upgrade=upgrade,
        uv=installer_uv(options, app),
        options=options,
        reporter=reporter,
        log_path=app / "logs" / "install.log",
    )
    ctx_holder["ctx"] = ctx
    ctx.write_log(f"installazione {'(aggiornamento) ' if upgrade else ''}in {app}, porta {ctx.port}, uv {ctx.uv or 'no'}")
    outcome: dict[str, bool] = {}

    def work() -> None:
        ok = run_steps(ctx)
        outcome["ok"] = ok
        if not ok:
            ctx.summary.append(f"Il registro completo e' in {ctx.log_path}.")
        reporter.finish(ok, ctx.summary, pair_url(ctx.port) if ok and not options.no_start else None)

    if window is not None:
        threading.Thread(target=work, daemon=True, name="installazione").start()
        window.run()
        if "ok" not in outcome:
            cancel()
            return 2
    else:
        work()
    return 0 if outcome.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
