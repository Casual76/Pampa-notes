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
- controlla che `/health` risponda; se il processo muore riprova, qualche volta, sempre piu'
  distanziato, e scrive ogni tentativo in `logs/avvio.log`. Un processo vivo ma lento non si uccide:
  lo si aspetta fino a cinque minuti, e poi lo si lascia al suo lavoro.

Se il server risponde, questo processo esce e lascia l'icona al suo lavoro. Con `--dopo` e' il
server stesso che si riavvia: si aspetta che il vecchio lasci la porta e si rilancia subito.
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
# Quanto si aspetta un processo vivo prima di lasciarlo stare. Vedi [wait_for].
SLOW_START_S = 300


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
    if "--dopo" in sys.argv:
        # Chiamato dal server che si riavvia da se' (vedi `restart_when_idle`): quello vecchio sta
        # uscendo, e finche' risponde non si lancia niente. Poi niente attesa iniziale: il sistema
        # e' gia' acceso da un pezzo.
        log("riavvio chiesto dal server: aspetto che lasci la porta")
        deadline = time.monotonic() + 60
        while healthy(at) and time.monotonic() < deadline:
            time.sleep(1)
    elif healthy(at):
        log("il server risponde gia': niente da fare")
        return
    else:
        time.sleep(FIRST_WAIT_S)
    for attempt in range(1, ATTEMPTS + 1):
        if healthy(at):
            log("il server risponde")
            return
        log(f"tentativo {attempt} di {ATTEMPTS}")
        process = launch()
        outcome = wait_for(process, at)
        if outcome == "ok":
            log(f"in ascolto sulla porta {at}")
            return
        if outcome == "slow":
            # Vivo ma muto dopo cinque minuti: non lo si uccide e non se ne lancia un altro. Un
            # secondo tray.py troverebbe la porta presa appena il primo si sveglia, e ucciderlo
            # voleva dire buttare via proprio il caricamento lento che stava finendo.
            log(f"tray.py e' vivo ma non risponde dopo {SLOW_START_S // 60} minuti: lo lascio lavorare")
            return
        log(f"tray.py e' uscito con codice {outcome}: l'errore e' in logs/tray-stderr.log")
        time.sleep(30 * attempt)
    log("rinuncio: avvia il companion a mano con avvia-in-background.cmd")


def wait_for(process: subprocess.Popen[bytes], at: int) -> str | int:
    """
    Aspetta che il server risponda: "ok", "slow" se e' ancora vivo dopo [SLOW_START_S], o il codice
    d'uscita se e' morto.

    Prima si aspettava un minuto e poi si uccideva il processo. Ma un minuto non basta sempre: subito
    dopo l'accesso, con Google Drive che rilegge la cartella del progetto, importare torch e
    WhisperX puo' metterci di piu' — e il processo ucciso era uno che stava partendo bene. Quello
    che dice «non partira'» e' il processo che esce, non l'orologio.
    """
    deadline = time.monotonic() + SLOW_START_S
    while time.monotonic() < deadline:
        if healthy(at):
            return "ok"
        code = process.poll()
        if code is not None:
            return code
        time.sleep(2)
    return "slow"


if __name__ == "__main__":
    main()
