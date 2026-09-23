"""
Pampa Notes companion: l'avvio all'accesso.

E' quello che parte dalla cartella Esecuzione automatica al posto di `tray.py`, per una ragione
sola: `pythonw.exe` non ha una console, e un errore nei primi secondi — un import che fallisce
perche' Google Drive sta ancora rileggendo la cartella, una scheda video non ancora pronta — muore
senza lasciare traccia. E' successo: il PC si riavvia di notte, il companion non riparte, e a scuola
il tablet non trova piu' il computer di casa senza che niente dica perche'.

Qui si usa solo la libreria standard, cosi' questo file parte anche quando il resto non puo':
- aspetta un po' dopo l'accesso, che e' il momento in cui il sistema e' piu' occupato;
- lancia `tray.py` con gli errori scritti in `logs/tray-stderr.log`;
- controlla che `/health` risponda; se il processo muore o non risponde, riprova, qualche volta,
  sempre piu' distanziato, e scrive ogni tentativo in `logs/avvio.log`.

Se il server risponde, questo processo esce e lascia l'icona al suo lavoro.
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOGS = HERE / "logs"
DEFAULT_PORT = 8765
FIRST_WAIT_S = 20
ATTEMPTS = 5
HEALTH_TIMEOUT_S = 60


def log(message: str) -> None:
    LOGS.mkdir(exist_ok=True)
    with (LOGS / "avvio.log").open("a", encoding="utf-8") as out:
        out.write(f"{datetime.now():%Y-%m-%d %H:%M:%S}  {message}\n")


def port() -> int:
    try:
        return int(json.loads((HERE / "config.json").read_text(encoding="utf-8")).get("port", DEFAULT_PORT))
    except (OSError, ValueError):
        return DEFAULT_PORT


def healthy(at: int) -> bool:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{at}/health", timeout=2) as response:
            return response.status == 200
    except OSError:
        return False


def launch() -> subprocess.Popen[bytes]:
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    runner = pythonw if pythonw.exists() else Path(sys.executable)
    LOGS.mkdir(exist_ok=True)
    stderr = (LOGS / "tray-stderr.log").open("ab")
    return subprocess.Popen(
        [str(runner), str(HERE / "tray.py")],
        cwd=HERE,
        stdin=subprocess.DEVNULL,
        stdout=stderr,
        stderr=stderr,
        close_fds=True,
        # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP: l'icona sopravvive a questo processo.
        creationflags=0x208 if sys.platform == "win32" else 0,
    )


def main() -> None:
    at = port()
    if healthy(at):
        log("il server risponde gia': niente da fare")
        return

    time.sleep(FIRST_WAIT_S)
    for attempt in range(1, ATTEMPTS + 1):
        if healthy(at):
            log("il server risponde")
            return
        log(f"tentativo {attempt} di {ATTEMPTS}")
        process = launch()
        deadline = time.monotonic() + HEALTH_TIMEOUT_S
        while time.monotonic() < deadline:
            if healthy(at):
                log(f"in ascolto sulla porta {at}")
                return
            code = process.poll()
            if code is not None:
                log(f"tray.py e' uscito con codice {code}: l'errore e' in logs/tray-stderr.log")
                break
            time.sleep(2)
        else:
            log(f"nessuna risposta in {HEALTH_TIMEOUT_S} s")
            process.kill()
        time.sleep(30 * attempt)
    log("rinuncio: avvia il companion a mano con avvia-in-background.cmd")


if __name__ == "__main__":
    main()
