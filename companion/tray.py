"""
Il server con un'icona accanto all'orologio, invece che con una finestra nera aperta.

Perche' esiste. `avvia.cmd` funziona, ma chiede di ricordarsene: chi si dimentica di lanciarlo
scopre che il tablet «non trova il computer» mezz'ora dopo, a lezione finita. E la finestra di
console resta li' tutto il giorno a occupare la barra delle applicazioni per un processo che per
il 99% del tempo non sta facendo niente. Da qui il server parte all'accesso, sta zitto, e si fa
guardare col tasto destro.

Il menu risponde alle tre domande che ci si fa davvero: **sta ascoltando?** (l'icona c'e'),
**quanta scheda video mi sta tenendo?** (la riga dello stato), e **me la ridai subito?** (lo
scarico manuale, per quando ci si vuole mettere a giocare senza aspettare i dieci minuti).

Un servizio di Windows non andava bene: un servizio gira fuori dalla sessione dell'utente e non
puo' disegnare niente accanto all'orologio, che e' esattamente la cosa che serviva.

Avvio:
    avvia-in-background.cmd     (oppure, una volta sola, «Avvio automatico» dal menu)
"""

from __future__ import annotations

import os
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw
import pystray
import qrcode
import uvicorn

import archive
import config
import whisperx_server as server

# I tre colori dell'icona. Non sono decorativi: dicono a colpo d'occhio se la scheda video e'
# impegnata, che e' l'unica cosa che si vuole sapere guardando li' di sfuggita.
GREY = (142, 142, 147, 255)   # in ascolto, modello scarico, scheda libera
GREEN = (48, 176, 108, 255)   # modello in memoria
AMBER = (232, 152, 40, 255)   # sta trascrivendo


def make_icon(color: tuple[int, int, int, int], ring: bool) -> Image.Image:
    """
    Un cerchio pieno, con un anello intorno quando sta lavorando.

    Disegnato invece che caricato da un file: a sedici pixel ogni immagine importata diventa una
    macchia, e comunque serviva cambiare colore a seconda dello stato.
    """
    size = 64
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    if ring:
        draw.ellipse((2, 2, size - 3, size - 3), outline=color, width=6)
        draw.ellipse((20, 20, size - 21, size - 21), fill=color)
    else:
        draw.ellipse((10, 10, size - 11, size - 11), fill=color)
    return image


def state_of() -> tuple[tuple[int, int, int, int], bool]:
    if server.STATE["busy"]:
        return AMBER, True
    if server.STATE["model"] is not None:
        return GREEN, False
    return GREY, False


# --- le righe del menu -------------------------------------------------------------------------


def status_line(_: Any = None) -> str:
    if server.STATE["busy"]:
        return "Sta trascrivendo..."
    if server.STATE["model"] is not None:
        quiet = time.time() - server.STATE["last_used"] if server.STATE["last_used"] else 0
        idle = server.STATE["idle_seconds"]
        if idle:
            return f"Modello in memoria - si scarica fra {max(0, int((idle - quiet) / 60))} min"
        return "Modello in memoria"
    return "In ascolto - modello scarico"


def vram_line(_: Any = None) -> str:
    if server.STATE["device"] != "cuda":
        return f"Sul processore ({server.STATE['name']})"
    return f"Scheda video: {server.vram_gb():.1f} GB occupati"


def address_line(_: Any = None) -> str:
    addresses = server.local_addresses(SETTINGS["port"])
    return addresses[0] if addresses else f"http://localhost:{SETTINGS['port']}"


def remote_line(_: Any = None) -> str:
    remote = server.tailscale_address(SETTINGS["port"])
    return f"Da fuori: {remote}" if remote else "Da fuori: installa Tailscale"


def has_remote(_: Any = None) -> bool:
    return server.tailscale_address(SETTINGS["port"]) is not None


def archive_line(_: Any = None) -> str:
    try:
        count, size = archive.current().stats()
    except Exception:  # noqa: BLE001 — un archivio che non risponde non deve rompere il menu
        return "Archivio: non disponibile"
    return f"Archivio: {count} file, {size / 1e9:.1f} GB"


def on_archive(_: pystray.Icon = None, __: Any = None) -> None:
    open_path(Path(SETTINGS["archive_root"]))


def can_unload(_: Any = None) -> bool:
    return server.STATE["model"] is not None and not server.STATE["busy"]


# --- le azioni ---------------------------------------------------------------------------------


def on_unload(icon: pystray.Icon, _: Any) -> None:
    if server.request_unload("richiesta dal menu"):
        icon.notify("Scheda video liberata.", "Pampa Notes")
    else:
        icon.notify("Non ora: c'e' una trascrizione in corso.", "Pampa Notes")
    refresh(icon)


def on_qr(icon: pystray.Icon, _: Any) -> None:
    """
    Il QR che l'app legge per configurarsi da sola.

    L'indirizzo del server e il token scritti a mano su un telefono sono due occasioni di
    sbagliare un carattere e poi cercare il guasto dalla parte del firewall. Lo schema
    `pampanotes://endpoint` lo dichiara gia' il manifest dell'app.

    Si salva come immagine e si apre col visualizzatore di sistema: aprire una finestra vera da
    qui vorrebbe dire un secondo giro di eventi grafico accanto a quello dell'icona, che e' un
    bel modo di far sparire il menu senza motivo.
    """
    # Non il link `pampanotes://` direttamente: la fotocamera lo mostrerebbe come testo. Un
    # indirizzo `http` del server, con una chiave che vale dieci minuti; la pagina ha il bottone.
    page = f"{address_line()}/pair?k={server.new_pairing_key()}"
    # Nel registro, per chi non riesce a inquadrarlo: «Apri i log» e lo copia a mano.
    server.log.info("pagina di accoppiamento (vale dieci minuti): %s", page)

    image = qrcode.make(page)
    target = config.LOG_DIR / "accoppiamento.png"
    config.LOG_DIR.mkdir(exist_ok=True)
    image.save(target)
    open_path(target)


def on_logs(_: pystray.Icon = None, __: Any = None) -> None:
    config.LOG_DIR.mkdir(exist_ok=True)
    open_path(config.LOG_DIR)


def on_config(_: pystray.Icon = None, __: Any = None) -> None:
    """Apre `config.json`, creandolo dai valori attuali se non c'era: modificarlo a mano e'
    l'unico modo di cambiare modello o token adesso che non c'e' piu' una riga di comando."""
    if not config.CONFIG_PATH.exists():
        config.save(SETTINGS)
    open_path(config.CONFIG_PATH)


def on_autostart(icon: pystray.Icon, item: Any) -> None:
    if autostart_enabled():
        autostart_disable()
        icon.notify("Il server non partira' piu' da solo.", "Pampa Notes")
    else:
        if autostart_enable():
            icon.notify("Partira' da solo al prossimo accesso.", "Pampa Notes")
        else:
            icon.notify("Non sono riuscito a creare l'attivita' pianificata.", "Pampa Notes")
    refresh(icon)


def on_quit(icon: pystray.Icon, _: Any) -> None:
    SERVER.should_exit = True
    icon.stop()


def open_path(path: Path) -> None:
    """`os.startfile` c'e' solo su Windows; altrove il browser apre cartelle e immagini uguale."""
    try:
        os.startfile(str(path))  # type: ignore[attr-defined]  # noqa: S606
    except AttributeError:
        webbrowser.open(path.as_uri())


# --- avvio automatico --------------------------------------------------------------------------

STARTUP_DIR = Path(os.environ.get("APPDATA", "")) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup"
SHORTCUT = STARTUP_DIR / "Pampa Notes companion.lnk"


def autostart_enabled(_: Any = None) -> bool:
    return SHORTCUT.exists()


def autostart_enable() -> bool:
    """
    Un collegamento nella cartella Esecuzione automatica dell'utente.

    Un'attivita' pianificata «all'accesso» sarebbe stata piu' elegante, ma `schtasks /SC ONLOGON`
    vuole i privilegi di amministratore, e questo programma non li ha ne' li deve chiedere per una
    spunta nel menu. Il collegamento parte all'accesso di questo utente, si vede e si spegne anche
    da Impostazioni → App → Avvio, e non chiede niente a nessuno.

    `pythonw.exe` e non `python.exe`: e' la differenza fra un'icona e un'icona con appiccicata una
    console vuota che non si puo' chiudere.
    """
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    runner = pythonw if pythonw.exists() else Path(sys.executable)
    script = Path(__file__).resolve()
    STARTUP_DIR.mkdir(parents=True, exist_ok=True)
    # Il collegamento lo scrive PowerShell: un .lnk e' un formato binario, e l'unico modo comodo
    # di produrlo senza dipendenze e' l'oggetto COM di Windows.
    command = (
        f"$s = (New-Object -ComObject WScript.Shell).CreateShortcut('{SHORTCUT}'); "
        f"$s.TargetPath = '{runner}'; $s.Arguments = '\"{script}\"'; "
        f"$s.WorkingDirectory = '{script.parent}'; $s.Description = 'Pampa Notes: il server di trascrizione'; "
        "$s.Save()"
    )
    startup = None
    if os.name == "nt":
        startup = subprocess.STARTUPINFO()
        startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    result = subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True, text=True, startupinfo=startup, check=False)
    return result.returncode == 0 and SHORTCUT.exists()


def autostart_disable() -> bool:
    try:
        SHORTCUT.unlink()
    except FileNotFoundError:
        pass
    except OSError:
        return False
    return True


# --- il ciclo ------------------------------------------------------------------------------------


def refresh(icon: pystray.Icon) -> None:
    color, ring = state_of()
    icon.icon = make_icon(color, ring)
    icon.title = f"Pampa Notes - {status_line()}"
    icon.update_menu()


def watch(icon: pystray.Icon) -> None:
    """
    Tiene l'icona in passo con quello che fa il server.

    Serve perche' lo stato cambia da solo: il modello si carica alla prima richiesta e se ne va
    dopo dieci minuti di silenzio, e un'icona che dice «scarico» mentre la scheda e' piena e'
    peggio di nessuna icona. Due secondi bastano, e costa quanto leggere due variabili.
    """
    last: tuple[Any, ...] = ()
    # Demone: muore col processo, non serve una condizione d'uscita.
    while True:
        current = state_of()
        if current != last:
            last = current
            with_ring = current[1]
            icon.icon = make_icon(current[0], with_ring)
            icon.title = f"Pampa Notes - {status_line()}"
        time.sleep(2)


def nothing(*_: Any) -> None:
    """Le prime tre righe del menu si leggono e basta. pystray vuole comunque un'azione."""


def build_menu() -> pystray.Menu:
    return pystray.Menu(
        pystray.MenuItem(status_line, nothing, enabled=False),
        pystray.MenuItem(vram_line, nothing, enabled=False),
        pystray.MenuItem(address_line, nothing, enabled=False),
        pystray.MenuItem(remote_line, nothing, enabled=False),
        pystray.MenuItem(archive_line, nothing, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Scarica il modello dalla scheda video", on_unload, enabled=can_unload),
        # Predefinita: un clic sinistro sull'icona apre il QR, che dopo la prima volta e' l'unica
        # cosa che si viene a cercare qui.
        pystray.MenuItem("Mostra il QR per i dispositivi", on_qr, default=True),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Apri la cartella dell'archivio", on_archive),
        pystray.MenuItem("Apri le impostazioni (config.json)", on_config),
        pystray.MenuItem("Apri i log", on_logs),
        pystray.MenuItem("Avvio automatico", on_autostart, checked=autostart_enabled),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Esci", on_quit),
    )


def main() -> None:
    global SETTINGS, SERVER

    SETTINGS = server.configure(config.load())
    log_path = server.setup_file_logging()

    if server.already_running(SETTINGS["port"]):
        # Un secondo doppio clic non deve dare un errore di porta occupata trenta secondi dopo.
        print(f"C'e' gia' un server sulla porta {SETTINGS['port']}. Guarda accanto all'orologio.")
        return

    if SETTINGS["preload"]:
        threading.Thread(target=server.ensure_model, daemon=True).start()

    SERVER = uvicorn.Server(
        uvicorn.Config(server.app, host="0.0.0.0", port=SETTINGS["port"], log_level="warning")
    )
    server.attach_access_log()
    # Demone: se il programma esce per una strada imprevista, il server non resta appeso a tenere
    # la porta occupata di un processo che non ha piu' nessuno che lo guarda.
    threading.Thread(target=SERVER.run, daemon=True).start()

    color, ring = state_of()
    icon = pystray.Icon(
        "pampa-notes",
        icon=make_icon(color, ring),
        title="Pampa Notes - in ascolto",
        menu=build_menu(),
    )
    threading.Thread(target=watch, args=(icon,), daemon=True).start()
    server.log.info("icona avviata, registro in %s", log_path)
    icon.run()


SETTINGS: dict[str, Any] = dict(config.DEFAULTS)
SERVER: uvicorn.Server

if __name__ == "__main__":
    main()
