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
import fuori
import updater
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


def estimate_line(_: Any = None) -> str:
    """
    Quanta VRAM chiedono le impostazioni di adesso, sulla VRAM che c'e'. Una stima, ed e' scritto.

    La riga sopra dice quanta ne e' occupata adesso, da tutti; questa quanta ne vorra' il companion
    nel momento peggiore di una lezione. Sono due domande diverse: la prima e' «la scheda e' libera?»,
    la seconda «ci sta?».
    """
    plan = server.STATE.get("vram")
    if not plan or plan.get("device") != "cuda" or plan.get("estimate_gb") is None:
        return "VRAM: non serve (sul processore)"
    budget = f" / {plan['budget_gb']:.1f}" if plan.get("budget_gb") is not None else ""
    line = f"VRAM: stima {plan['estimate_gb']:.1f}{budget} GB (batch {plan['batch_size']})"
    if plan.get("downgraded"):
        line += f" - {plan['model']} {plan['compute_type']}"
    return line


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

    L'indirizzo del server scritto a mano su un telefono e' un'occasione di sbagliare un carattere
    e poi cercare il guasto dalla parte del firewall. Lo schema `pampanotes://endpoint` lo dichiara
    gia' il manifest dell'app. Il token non c'e' piu': vedi `pairing_link` nel server.

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


# --- aggiornamenti ------------------------------------------------------------------------------
#
# Solo per chi ha installato col setup (vedi updater.py): il controllo e' una volta al giorno, e la
# voce del menu compare quando c'e' qualcosa di nuovo. Mai durante una trascrizione, ne' con qualcuno
# in fila: il setup ferma l'icona per rimetterla in piedi.

UPDATES: updater.UpdateWatcher | None = None


def update_available(_: Any = None) -> bool:
    return UPDATES is not None and UPDATES.release is not None


def update_label(_: Any = None) -> str:
    release = UPDATES.release if UPDATES is not None else None
    return f"Aggiorna a v{release.label}" if release else "Aggiorna"


def idle_for_update() -> bool:
    """
    Si puo' lanciare il setup, che fermera' l'icona? Non se trascrive, non se qualcuno e' in fila, e
    non se c'e' una richiesta a meta': un telefono che sta ancora caricando una lezione non e' ne'
    «busy» ne' in fila, ma fermare il server gli butta via il caricamento (vedi server.REQUESTS).
    """
    return not server.STATE["busy"] and server.GATE.waiting == 0 and server.REQUESTS.count == 0


def on_update(icon: pystray.Icon, _: Any) -> None:
    if UPDATES is None:
        return
    icon.notify("Scarico l'aggiornamento...", "Pampa Notes")

    def run() -> None:
        outcome = UPDATES.apply(idle_for_update)
        messages = {
            "started": "Aggiornamento avviato: l'icona torna fra poco.",
            "busy": "Non ora: c'e' una trascrizione in corso. Riprova quando ha finito.",
            "running": "L'aggiornamento e' gia' in corso.",
            "none": "Niente da aggiornare.",
        }
        server.log.info("aggiornamento dal menu: %s", outcome)
        icon.notify(messages.get(outcome, f"Aggiornamento non riuscito: {outcome}"), "Pampa Notes")

    threading.Thread(target=run, daemon=True, name="aggiorna").start()


def start_update_watch(icon: pystray.Icon) -> None:
    global UPDATES
    if not updater.installed(config.HERE):
        return
    UPDATES = updater.UpdateWatcher(config.version(), on_change=icon.update_menu)
    UPDATES.start()


def on_logs(_: pystray.Icon = None, __: Any = None) -> None:
    config.LOG_DIR.mkdir(exist_ok=True)
    open_path(config.LOG_DIR)


def on_config(_: pystray.Icon = None, __: Any = None) -> None:
    """Apre `config.json`, creandolo dai valori attuali se non c'era: modificarlo a mano e'
    l'unico modo di cambiare modello o token adesso che non c'e' piu' una riga di comando."""
    if not config.CONFIG_PATH.exists():
        # I valori di partenza, non `SETTINGS`: li' ci sono modello e lotto *dopo* i conti della
        # VRAM, e scriverli come scelta dell'utente farebbe del lotto di oggi il tetto di domani.
        config.save(config.load())
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


def anonymous_enabled(_: Any = None) -> bool:
    return bool(server.STATE["accept_anonymous"])


def on_anonymous(icon: pystray.Icon, _: Any) -> None:
    """
    Accende o spegne l'accesso senza credenziali, e lo scrive in `config.json`.

    Serve per il passaggio: l'app vecchia non manda niente, quella nuova manda il biglietto
    dell'account. Finche' c'e' un dispositivo con quella vecchia l'accesso libero resta acceso;
    quando sono aggiornati tutti si spegne da qui, senza aprire il file a mano.
    """
    enabled = not anonymous_enabled()
    server.STATE["accept_anonymous"] = enabled
    SETTINGS["accept_anonymous"] = enabled
    try:
        config.set_value("accept_anonymous", enabled)
    except OSError as error:
        server.log.warning("config.json non si scrive: %s", error)
        icon.notify("Cambiato solo fino al riavvio: config.json non si scrive.", "Pampa Notes")
    else:
        if enabled:
            icon.notify("Accesso libero: chi raggiunge il computer trascrive senza credenziali.", "Pampa Notes")
        else:
            icon.notify("Solo con l'account o con il codice, d'ora in poi.", "Pampa Notes")
    server.log.info("accesso libero %s dal menu", "acceso" if enabled else "spento")
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
    console vuota che non si puo' chiudere. E il collegamento punta ad `avvio.pyw`, non a questo
    file: all'accesso un errore nei primi secondi non lascerebbe traccia, e il lanciatore lo scrive
    e riprova.
    """
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    runner = pythonw if pythonw.exists() else Path(sys.executable)
    script = Path(__file__).resolve().with_name("avvio.pyw")
    STARTUP_DIR.mkdir(parents=True, exist_ok=True)
    # Il collegamento lo scrive PowerShell: un .lnk e' un formato binario, e l'unico modo comodo
    # di produrlo senza dipendenze e' l'oggetto COM di Windows.
    command = shortcut_command(SHORTCUT, runner, script)
    startup = None
    if os.name == "nt":
        startup = subprocess.STARTUPINFO()
        startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    result = subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True, text=True, startupinfo=startup, check=False)
    return result.returncode == 0 and SHORTCUT.exists()


def ps_quote(value: Any) -> str:
    """
    Una stringa PowerShell fra apici singoli: dentro, l'apice si scrive due volte.

    Senza, una cartella come `C:\\Users\\D'Amico` chiudeva la stringa a meta' e il comando falliva
    (nel caso buono) o eseguiva il resto del percorso come codice (nel caso cattivo).
    """
    return "'" + str(value).replace("'", "''") + "'"


def shortcut_command(shortcut: Path, runner: Path, script: Path) -> str:
    """Il comando PowerShell che scrive il collegamento, con ogni percorso fra apici come si deve."""
    # Fra virgolette doppie dentro l'argomento: il percorso dello script ha degli spazi.
    arguments = '"' + str(script) + '"'
    return (
        f"$s = (New-Object -ComObject WScript.Shell).CreateShortcut({ps_quote(shortcut)}); "
        f"$s.TargetPath = {ps_quote(runner)}; $s.Arguments = {ps_quote(arguments)}; "
        f"$s.WorkingDirectory = {ps_quote(script.parent)}; $s.Description = 'Pampa Notes: il server di trascrizione'; "
        "$s.Save()"
    )


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
        pystray.MenuItem(estimate_line, nothing, enabled=False),
        pystray.MenuItem(address_line, nothing, enabled=False),
        pystray.MenuItem(remote_line, nothing, enabled=False),
        pystray.MenuItem(archive_line, nothing, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Scarica il modello dalla scheda video", on_unload, enabled=can_unload),
        # Predefinita: un clic sinistro sull'icona apre il QR, che dopo la prima volta e' l'unica
        # cosa che si viene a cercare qui.
        pystray.MenuItem("Mostra il QR per i dispositivi", on_qr, default=True),
        pystray.MenuItem(update_label, on_update, visible=update_available),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Apri la cartella dell'archivio", on_archive),
        pystray.MenuItem("Apri le impostazioni (config.json)", on_config),
        pystray.MenuItem("Apri i log", on_logs),
        pystray.MenuItem("Avvio automatico", on_autostart, checked=autostart_enabled),
        pystray.MenuItem("Accesso libero (spegni quando i dispositivi sono aggiornati)", on_anonymous, checked=anonymous_enabled),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Esci", on_quit),
    )


def main() -> None:
    global SETTINGS, SERVER

    # Prima il registro: la decisione sulla VRAM che [server.configure] prende deve finirci dentro.
    log_path = server.setup_file_logging()
    # Mai dentro il contenitore di un'altra app (vedi fuori.py): l'archivio finirebbe in una
    # cartella che il companion avviato da Windows non vede. Si riparte da avvio.pyw, fuori.
    boxed = fuori.redirected_to()
    if boxed:
        here = Path(__file__).resolve().parent
        pythonw = Path(sys.prefix) / "Scripts" / "pythonw.exe"
        runner = pythonw if pythonw.exists() else Path(sys.executable)
        again = fuori.relaunch_outside([str(runner), str(here / "avvio.pyw"), "--dopo"], here)
        server.log.warning(
            "avviato dentro il contenitore di %s: %s", boxed,
            "mi rilancio fuori con WMI" if again else "WMI ha rifiutato, parto lo stesso (l'archivio andra' nel contenitore)",
        )
        if again:
            return
    SETTINGS = server.configure(config.load())
    # WhisperX si importa adesso, fuori da ogni lezione: vedi server.warm_imports.
    server.warm_imports()

    if server.already_running(SETTINGS["port"]):
        # Un secondo doppio clic non deve dare un errore di porta occupata trenta secondi dopo.
        print(f"C'e' gia' un server sulla porta {SETTINGS['port']}. Guarda accanto all'orologio.")
        return

    if SETTINGS["preload"]:
        # Dalla fila del server, non per conto suo: vedi server.preload_now.
        threading.Thread(target=server.preload_when_ready, daemon=True, name="preload").start()

    SERVER = uvicorn.Server(
        uvicorn.Config(server.app, host="0.0.0.0", port=SETTINGS["port"], log_level="warning")
    )
    # Il server che si riavvia da se' lo chiude con garbo (vedi server.restart_when_idle), e poi
    # toglie l'icona invece di lasciarla orfana accanto all'orologio.
    server.SERVER = SERVER
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
    server.ON_EXIT = icon.stop
    threading.Thread(target=watch, args=(icon,), daemon=True).start()
    start_update_watch(icon)
    # Un collegamento scritto da una versione precedente puntava a questo file, senza lanciatore:
    # si riscrive, cosi' chi aveva gia' acceso l'avvio automatico non deve spegnerlo e riaccenderlo.
    if autostart_enabled():
        threading.Thread(target=autostart_enable, daemon=True).start()
    server.log.info("icona avviata, registro in %s", log_path)
    icon.run()


SETTINGS: dict[str, Any] = dict(config.DEFAULTS)
SERVER: uvicorn.Server

if __name__ == "__main__":
    main()
