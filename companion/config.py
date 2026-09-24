"""
Le impostazioni del server, in un posto solo.

Nasce con l'icona nell'area di notifica. Finche' il server si lanciava da `avvia.cmd`, la riga di
comando bastava: chi voleva un modello diverso scriveva `avvia.cmd --model medium` e finiva li'.
Un'applicazione che parte da sola all'accesso non ha una riga di comando dove scriverlo, e
cambiare quello che fa richiederebbe di modificare l'attivita' pianificata — cioe' un posto che
nessuno apre.

I valori vengono, in ordine, da tre fonti: i default qui sotto, `config.json` accanto a questo
file, e gli argomenti della riga di comando. L'ultimo che parla vince, cosi' `avvia.cmd --model
medium` continua a funzionare come prima anche se `config.json` dice un'altra cosa.

I default stavano in tre posti da tenere allineati a mano — il dict `STATE` del server, gli
`argparse` di `main`, e i `param()` di `run.ps1` — e tenerli allineati a mano vuol dire scoprire
che non lo sono quando qualcosa si comporta in un modo che nessuno ha chiesto. Adesso stanno qui.
"""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent

# Chi scrive config.json lo fa da tre posti che non si parlano: il menu dell'icona (il suo thread),
# /v1/admin/settings e /v1/pair/bind (i thread del server). Ognuno legge il file, cambia una chiave e
# lo riscrive: due insieme, e la chiave del primo spariva sotto la riscrittura del secondo. Leggere,
# cambiare e scrivere stanno quindi sotto lo stesso lucchetto. Rientrante perche' [load] puo'
# riscrivere il file da dentro una lettura.
_LOCK = threading.RLock()
CONFIG_PATH = HERE / "config.json"
LOG_DIR = HERE / "logs"
# La versione del companion, una sola: la leggono /health, l'icona (per gli aggiornamenti) e
# `installer/build-installer.ps1` (per il nome del setup). Senza il file e' una copia di sviluppo.
VERSION_PATH = HERE / "VERSION"
# Programmi che l'installer mette accanto al codice quando sul computer non ci sono: ffmpeg, per
# ora. WhisperX lo chiama per nome, quindi deve stare nel PATH di questo processo.
LOCAL_BIN = HERE / "bin"


def version() -> str:
    try:
        return VERSION_PATH.read_text(encoding="utf-8").strip() or "dev"
    except OSError:
        return "dev"


def add_local_bin() -> None:
    """
    `bin/` accanto al codice davanti al PATH, se c'e'.

    Chi ha installato col setup non ha ffmpeg nel PATH di sistema, e non glielo si mette: toccare il
    PATH di tutto il computer per un programma che lo usa da solo e' un posto in piu' da ripulire
    alla disinstallazione, e un ffmpeg diverso per gli altri programmi. Qui vale per questo
    processo e per quelli che lancia, e basta.
    """
    if not LOCAL_BIN.is_dir():
        return
    current = os.environ.get("PATH", "")
    entries = [entry for entry in current.split(os.pathsep) if entry]
    if str(LOCAL_BIN) not in entries:
        os.environ["PATH"] = os.pathsep.join([str(LOCAL_BIN), *entries])


add_local_bin()

DEFAULTS: dict[str, Any] = {
    # large-v3, medium, small... Il modello e' quello scelto qui e non cambia per richiesta:
    # cambiarlo a meta' di una lezione vorrebbe dire rileggere qualche gigabyte di pesi.
    "model": "large-v3",
    # cuda, cpu, oppure auto: decide [resolve_device] guardando se torch vede la scheda.
    "device": "auto",
    # Vuoto = float16 sulla scheda, int8 sul processore.
    "compute_type": "",
    # Il lotto piu' grande che si usa: quante finestre di trenta secondi passano insieme. Il lotto
    # vero lo sceglie il server sotto questo tetto, in base alla VRAM (vedi `vram_mode`).
    "batch_size": 16,
    # Come si decide quanta VRAM usare. «auto»: si legge la scheda e si sceglie il lotto (e, se non
    # basta, un calcolo o un modello piu' leggero) perche' la stima stia nell'85%. «manual»: lo
    # stesso conto, ma sulla VRAM scritta in `vram_gb` — «la VRAM che ho», o quella che si vuole
    # lasciare al companion mentre il resto della scheda serve ad altro.
    "vram_mode": "auto",
    "vram_gb": None,
    "port": 8765,
    # Se c'e', l'app deve mandarlo. Vuoto = chiunque raggiunga la porta puo' trascrivere.
    "token": "",
    # Dopo quanti minuti di silenzio liberare la VRAM. 0 = non liberarla mai.
    "idle_minutes": 10,
    # Carica il modello all'avvio invece che alla prima richiesta. Vedi [ensure_model]: da spento
    # il server risponde subito e paga l'attesa la prima lezione, che tanto e' gia' un'attesa.
    "preload": False,
    # Dove stanno i file originali che i dispositivi caricano (vedi archive.py). Fuori dal progetto
    # e fuori da Documenti: la cartella del progetto e' sincronizzata da Google Drive, e gigabyte
    # di registrazioni dentro Drive sono esattamente quello che l'archivio esiste per evitare.
    "archive_root": str(Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "PampaNotes" / "archivio"),
    # Gli ospiti: un amico che trascrive con questo computer. Un token `pg_…` che arriva qui si
    # chiede al Worker dell'indice (`index_url`) se e' un ospite di questo proprietario (`owner`:
    # l'account Google della sincronizzazione, com'e' scritto nell'app). Vuoti = niente ospiti,
    # e vale solo il token qui sopra.
    "index_url": "",
    "owner": "",
    # Una richiesta senza credenziali passa lo stesso, come proprietario? Per un config nuovo no:
    # con `index_url` e `owner` l'app entra con il biglietto dell'account (`pt_…`), e senza con il
    # token qui sopra. Un config.json che c'era gia' prima di questa chiave la riceve accesa alla
    # prima lettura (vedi [load]): i dispositivi con l'app vecchia non mandano niente, e spegnerla
    # d'ufficio li lascerebbe fuori senza dire perche'. Si spegne dal menu dell'icona, quando tutti
    # i dispositivi sono aggiornati.
    "accept_anonymous": False,
    # «Chi parla»: il token di lettura di Hugging Face con cui si scarica il modello che separa le
    # voci (pyannote, vedi `DIARIZE_MODEL` nel server). Vuoto = la separazione non c'e', e /health
    # non la offre. Si scrive dal menu dell'icona («Separazione delle voci...») e non si stampa mai:
    # ne' nel registro, ne' in /health, ne' in /v1/admin/settings. Vale anche la variabile HF_TOKEN.
    "hf_token": "",
    # «Togli il token» dal menu: vero, e la variabile HF_TOKEN dell'ambiente non vale piu' (vedi
    # `resolve_hf_token` nel server). Senza, toglierlo durava fino al riavvio. Salvare un token lo
    # rimette falso.
    "hf_token_disabled": False,
}


def load(path: Path = CONFIG_PATH) -> dict[str, Any]:
    """
    I default piu' quello che c'e' in `config.json`.

    Un file rotto non ferma il server: si torna ai default e lo si scrive nel registro. Il momento
    in cui si scopre che il JSON ha una virgola di troppo non deve essere il momento in cui il
    tablet non trova piu' il computer.
    """
    settings = dict(DEFAULTS)
    with _LOCK:
        if not path.exists():
            return settings
        try:
            stored = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"  config.json non si legge ({error}): uso i valori di partenza.")
            return settings
        if not isinstance(stored, dict):
            return settings
        if "accept_anonymous" not in stored:
            stored["accept_anonymous"] = _migrated_anonymous(stored)
            _write_back(stored, path)
    # Solo le chiavi che conosciamo: una chiave scritta male resta nel file senza fare danni,
    # e chi lo rilegge la trova ancora li' invece di vedersela sparire.
    for key in DEFAULTS:
        if key in stored:
            settings[key] = stored[key]
    return settings


def _migrated_anonymous(stored: dict[str, Any]) -> bool:
    """
    Il valore di `accept_anonymous` per un config.json scritto prima che la chiave esistesse.

    Acceso, perche' oggi chi non manda niente passa, e la versione dell'app sul tablet non manda
    niente. Tranne quando c'e' un token: li' chi non lo mandava era gia' fuori, e accenderla
    aprirebbe una porta che l'utente aveva chiuso apposta.
    """
    return not str(stored.get("token") or "").strip()


def _write_back(stored: dict[str, Any], path: Path) -> None:
    """
    Riscrive il file com'era, con in piu' la chiave nuova.

    Non passa da [save]: quello tiene solo le chiavi conosciute, e una migrazione che fa sparire una
    riga scritta a mano (magari da una versione piu' nuova) e' peggio di nessuna migrazione. Se il
    file non si puo' scrivere si va avanti lo stesso: il valore vale per questa volta, e si riprova
    alla prossima.
    """
    try:
        _dump(stored, path)
    except OSError as error:
        print(f"  config.json non si scrive ({error}): accept_anonymous vale solo per questa volta.")


def set_value(key: str, value: Any, path: Path = CONFIG_PATH) -> None:
    """
    Cambia una chiave sola nel file, lasciando le altre come stanno.

    Per il menu dell'icona: riscrivere tutto con [save] metterebbe nel file i valori di partenza di
    ogni chiave che l'utente non aveva mai scritto, e il file che ha aperto a mano non sarebbe piu'
    quello che ricorda.
    """
    set_values({key: value}, path)


def set_values(values: dict[str, Any], path: Path = CONFIG_PATH) -> None:
    """[set_value] per piu' chiavi insieme, con una scrittura sola: dall'app ne arrivano diverse."""
    with _LOCK:
        stored: dict[str, Any] = {}
        if path.exists():
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    stored = loaded
            except (OSError, ValueError):
                stored = {}
        stored.update(values)
        _dump(stored, path)


# Windows rifiuta `os.replace` finche' un altro processo tiene il file aperto (un antivirus, Google
# Drive che lo legge): e' questione di un attimo, e un attimo dopo si riprova.
REPLACE_ATTEMPTS = 5
REPLACE_PAUSE_S = 0.1


def _dump(stored: dict[str, Any], path: Path) -> None:
    """
    Scrive tutto il file o niente: prima un temporaneo accanto, poi `os.replace` al posto suo.

    `write_text` direttamente sul file lo tronca e poi lo riempie: un processo che muore a meta' (il
    computer spento, l'icona chiusa) lasciava un config.json vuoto o a mezzo, che al prossimo avvio
    [load] non legge — e il computer ripartiva coi valori di partenza, senza account e senza token.
    Il temporaneo sta nella stessa cartella perche' `os.replace` e' atomico solo sullo stesso disco.
    """
    text = json.dumps(stored, indent=2, ensure_ascii=False) + "\n"
    with _LOCK:
        handle, name = tempfile.mkstemp(prefix=".config-", suffix=".tmp", dir=str(path.parent))
        temp = Path(name)
        try:
            with os.fdopen(handle, "w", encoding="utf-8") as out:
                out.write(text)
                out.flush()
                os.fsync(out.fileno())
            for attempt in range(REPLACE_ATTEMPTS):
                try:
                    os.replace(temp, path)
                    break
                except PermissionError:
                    if attempt == REPLACE_ATTEMPTS - 1:
                        raise
                    time.sleep(REPLACE_PAUSE_S)
        finally:
            temp.unlink(missing_ok=True)


def save(settings: dict[str, Any], path: Path = CONFIG_PATH) -> None:
    """Riscrive il file tenendo solo le chiavi conosciute, cosi' resta leggibile a mano."""
    body = {key: settings.get(key, DEFAULTS[key]) for key in DEFAULTS}
    _dump(body, path)


def resolve_device(device: str) -> str:
    """`auto` diventa `cuda` se la scheda c'e' davvero, altrimenti `cpu`."""
    if device != "auto":
        return device
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except ImportError:
        return "cpu"


def apply_cli(settings: dict[str, Any], args: Any) -> dict[str, Any]:
    """
    Gli argomenti della riga di comando sopra a quello che c'e' gia'.

    Ogni opzione ha `default=None` in `argparse`: e' l'unico modo di distinguere «non l'ha scritto»
    da «l'ha scritto uguale al default». Senza, `avvia.cmd` senza argomenti riscriverebbe sopra
    `config.json` con i valori di partenza, che e' il contrario di quello che serve.
    """
    merged = dict(settings)
    for key in DEFAULTS:
        value = getattr(args, key, None)
        if value is not None and value is not False:
            merged[key] = value
    return merged


def add_arguments(parser: Any) -> None:
    """Le stesse opzioni di sempre, ma senza default: li mette [apply_cli]."""
    parser.add_argument("--model", default=None, help="large-v3, medium, small...")
    parser.add_argument("--device", default=None, help="cuda, cpu, auto")
    parser.add_argument("--compute-type", dest="compute_type", default=None, help="float16 su GPU, int8 su CPU")
    parser.add_argument("--batch-size", dest="batch_size", type=int, default=None, help="il lotto massimo: quello vero lo sceglie la VRAM")
    parser.add_argument("--vram-mode", dest="vram_mode", choices=["auto", "manual"], default=None, help="auto legge la scheda, manual usa --vram-gb")
    parser.add_argument("--vram-gb", dest="vram_gb", type=float, default=None, help="la VRAM da usare per i conti, in GB")
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--token", default=None, help="se c'e', l'app deve mandarlo")
    parser.add_argument(
        "--idle-minutes",
        dest="idle_minutes",
        type=int,
        default=None,
        help="dopo quanti minuti di silenzio liberare la VRAM. 0 per non liberarla mai",
    )
    parser.add_argument(
        "--preload",
        action="store_true",
        default=None,
        help="carica il modello subito invece che alla prima richiesta",
    )
