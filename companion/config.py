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
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
LOG_DIR = HERE / "logs"

DEFAULTS: dict[str, Any] = {
    # large-v3, medium, small... Il modello e' quello scelto qui e non cambia per richiesta:
    # cambiarlo a meta' di una lezione vorrebbe dire rileggere qualche gigabyte di pesi.
    "model": "large-v3",
    # cuda, cpu, oppure auto: decide [resolve_device] guardando se torch vede la scheda.
    "device": "auto",
    # Vuoto = float16 sulla scheda, int8 sul processore.
    "compute_type": "",
    # Abbassalo se la GPU va in esaurimento.
    "batch_size": 16,
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
}


def load(path: Path = CONFIG_PATH) -> dict[str, Any]:
    """
    I default piu' quello che c'e' in `config.json`.

    Un file rotto non ferma il server: si torna ai default e lo si scrive nel registro. Il momento
    in cui si scopre che il JSON ha una virgola di troppo non deve essere il momento in cui il
    tablet non trova piu' il computer.
    """
    settings = dict(DEFAULTS)
    if not path.exists():
        return settings
    try:
        stored = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"  config.json non si legge ({error}): uso i valori di partenza.")
        return settings
    if not isinstance(stored, dict):
        return settings
    # Solo le chiavi che conosciamo: una chiave scritta male resta nel file senza fare danni,
    # e chi lo rilegge la trova ancora li' invece di vedersela sparire.
    for key in DEFAULTS:
        if key in stored:
            settings[key] = stored[key]
    return settings


def save(settings: dict[str, Any], path: Path = CONFIG_PATH) -> None:
    """Riscrive il file tenendo solo le chiavi conosciute, cosi' resta leggibile a mano."""
    body = {key: settings.get(key, DEFAULTS[key]) for key in DEFAULTS}
    path.write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


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
    parser.add_argument("--batch-size", dest="batch_size", type=int, default=None, help="abbassalo se la GPU va in esaurimento")
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
