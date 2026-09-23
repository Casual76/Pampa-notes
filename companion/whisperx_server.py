"""
Il tuo computer come servizio di trascrizione per Pampa Notes.

WhisperX dietro le tre chiamate dell'API di OpenAI che l'app conosce. Non e' un progetto a se':
sono duecento righe che espongono qualcosa che gia' funziona, perche' WhisperX e' una libreria e
una riga di comando, non un server.

Perche' vale la pena, rispetto a Groq:

  * **Nessun limite di dimensione.** Groq si ferma a venticinque megabyte per richiesta, quindi
    un'ora di lezione va tagliata in pezzi e ricucita. Qui il file va intero, e una cucitura che
    non si fa e' una cucitura che non puo' sbagliare.
  * **I file non escono di casa.** Una lezione registrata contiene le voci di persone che non
    hanno acconsentito a niente.
  * **I tempi sono migliori.** WhisperX allinea le parole con un modello fonetico invece di
    fidarsi dei tempi che Whisper inventa, e si sente quando il lettore segue il testo.

Il modello **non** si carica all'avvio: si carica alla prima richiesta e si scarica da solo dopo
dieci minuti che non arriva niente. Vedi [unload_model].

Avvio:
    installa.cmd      (una volta sola)
    avvia.cmd         (tutti i giorni, anche con un doppio clic)
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import gc
import json
import logging
import math
import os
import secrets
import socket
import tempfile
import threading
import time
import urllib.error
import urllib.request
import warnings
from collections import OrderedDict
from dataclasses import dataclass
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Callable

# pyannote e torchcodec stampano in avvio un muro di avvisi su ffmpeg che non riguardano niente di
# quello che facciamo qui (la diarizzazione non si usa). Sembravano errori, e una console che
# sembra piena di errori e' una console che fa chiudere la finestra.
warnings.filterwarnings("ignore", message=".*torchcodec.*")
warnings.filterwarnings("ignore", message=".*TensorFloat-32.*")
warnings.filterwarnings("ignore", category=UserWarning, module="pyannote.*")
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse
from starlette.datastructures import Headers
import uvicorn

import archive
import config

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("pampa")

# Lo stato del processo. Il modello e' `None` finche' non serve davvero: vedi [ensure_model].
# I valori qui sono quelli di partenza e non li legge nessuno: [configure] li sostituisce con
# quelli veri prima che il server si metta in ascolto.
STATE: dict[str, Any] = {
    "model": None,
    "align": {},
    "name": config.DEFAULTS["model"],
    "device": config.DEFAULTS["device"],
    "compute_type": config.DEFAULTS["compute_type"],
    "batch_size": config.DEFAULTS["batch_size"],
    "token": None,
    # Vedi config.DEFAULTS: senza questi due, gli ospiti non esistono.
    "index_url": "",
    "owner": "",
    # Vedi config.DEFAULTS: una richiesta senza credenziali passa come proprietario?
    "accept_anonymous": False,
    # L'esito dell'ultimo allineamento per lingua: "ok" o l'errore. Lo mostra /health, perche' un
    # allineamento che fallisce non ferma niente — la trascrizione esce lo stesso, coi tempi per
    # frase — e cosi' e' rimasto rotto per giorni senza che nessuno lo vedesse.
    "alignment": {},
    "busy": False,
    # Quando e' finita l'ultima trascrizione. Da qui parte il conto per lo sfratto.
    "last_used": 0.0,
    # Dopo quanti secondi di silenzio si libera la VRAM. 0 = mai.
    "idle_seconds": config.DEFAULTS["idle_minutes"] * 60,
}

# Il ciclo di eventi del server, messo da parte appena parte. Serve a [request_unload]: l'icona
# nell'area di notifica vive su un altro thread, e per toccare il modello deve passare da qui —
# lo sfratto prende lo stesso lucchetto delle trascrizioni, e un lucchetto di asyncio si prende
# solo da dentro il suo ciclo.
LOOP: asyncio.AbstractEventLoop | None = None

# Il file di registro, quando c'e': [attach_access_log] lo aggancia anche alle richieste.
FILE_HANDLER: logging.Handler | None = None

class PriorityGate:
    """
    Una trascrizione alla volta, ma non in ordine di arrivo: prima il proprietario, poi gli ospiti.

    Una alla volta non e' pigrizia: due richieste insieme raddoppiano la memoria della GPU e,
    superato il limite, falliscono entrambe invece di una. Un lucchetto normale pero' e' cieco:
    chi arriva secondo aspetta, chiunque sia. Qui chi aspetta si mette in fila con una priorita',
    e quando il posto si libera passa il primo della fila piu' importante. Nessuno viene
    interrotto a meta' — la lezione dell'ospite gia' partita finisce — ma la prossima e' la tua,
    non quella dell'ospite arrivato un secondo prima di te. Lo sfratto del modello prende il posto
    con la priorita' piu' alta: e' veloce, e cosi' non capita mai sotto una trascrizione in corso.
    """

    def __init__(self) -> None:
        self._cond = asyncio.Condition()
        self._busy = False
        self._waiting: list[tuple[int, int]] = []
        self._seq = 0

    @contextlib.asynccontextmanager
    async def slot(self, priority: int):
        async with self._cond:
            self._seq += 1
            me = (priority, self._seq)
            self._waiting.append(me)
            self._waiting.sort()
            # Chi smette di aspettare (la richiesta annullata, il server che si chiude) deve uscire
            # dalla fila. Senza, restava primo per sempre: nessun altro vedeva `_waiting[0] == me`,
            # e il computer smetteva di trascrivere per chiunque finche' non lo si riavviava.
            admitted = False
            try:
                await self._cond.wait_for(lambda: not self._busy and self._waiting[0] == me)
                admitted = True
            finally:
                self._waiting.remove(me)
                if admitted:
                    self._busy = True
                else:
                    # Il posto potrebbe toccare a chi era dietro: lo si sveglia a guardare.
                    self._cond.notify_all()
        try:
            yield
        finally:
            async with self._cond:
                self._busy = False
                self._cond.notify_all()

    @property
    def waiting(self) -> int:
        return len(self._waiting)


GATE = PriorityGate()


class BoundedCache:
    """
    Le risposte del Worker tenute da parte, ognuna con la sua scadenza, e al massimo `limit`.

    Un dict semplice cresceva per sempre: ogni token inventato che qualcuno prova sulla porta e'
    una voce in piu', e chi ne prova un milione si prende la memoria del server. Quando e' pieno
    se ne vanno le voci piu' vecchie. Un lucchetto perche' le verifiche girano sui thread.
    """

    def __init__(self, limit: int = 256) -> None:
        self.limit = limit
        self._items: OrderedDict[str, tuple[Any, float]] = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key: str, now: float | None = None) -> tuple[bool, Any]:
        """(True, valore) se c'e' e non e' scaduto; (False, None) altrimenti."""
        now = time.time() if now is None else now
        with self._lock:
            item = self._items.get(key)
            if item is None:
                return False, None
            if item[1] <= now:
                del self._items[key]
                return False, None
            return True, item[0]

    def put(self, key: str, value: Any, until: float) -> None:
        with self._lock:
            self._items[key] = (value, until)
            self._items.move_to_end(key)
            while len(self._items) > self.limit:
                self._items.popitem(last=False)

    def clear(self) -> None:
        with self._lock:
            self._items.clear()

    def __len__(self) -> int:
        return len(self._items)


# Gli ospiti verificati di recente: token -> nome. Dieci minuti se e' buono, uno se no: un token
# revocato smette di valere entro dieci minuti, e uno inventato non fa una richiesta al Worker a
# ogni tentativo.
GUEST_CACHE = BoundedCache(256)

# I biglietti dell'account (`pt_…`, vedi [verify_ticket]): biglietto -> valido. Uno buono vale
# fino alla sua scadenza, che il Worker dice, e al massimo dodici ore; uno cattivo un minuto.
TICKET_CACHE = BoundedCache(256)
TICKET_MAX_S = 12 * 3600
NEGATIVE_S = 60

# Cloudflare rifiuta (403, «error code: 1010») le richieste con lo User-Agent di serie di urllib:
# ci si presenta con un nome, come fa l'app.
WORKER_USER_AGENT = "PampaNotes-companion/1.0"


def vram_gb() -> float:
    """
    Quanta memoria della scheda risulta occupata, in tutto.

    Si chiede al driver (`mem_get_info`) e non a torch (`memory_reserved`), perche' il modello
    grande non passa da torch: lo alloca ctranslate2 per conto suo, e torch non lo vede. Misurando
    dalla parte di torch, scaricare quattro gigabyte e mezzo si leggeva come "0.0 GB -> 0.0 GB".

    E' il totale della scheda, non solo di questo processo: comprende il desktop e tutto il resto.
    Per la domanda a cui serve rispondere — "la memoria e' tornata libera?" — e' il numero giusto.
    """
    if STATE["device"] != "cuda":
        return 0.0
    try:
        import torch

        free, total = torch.cuda.mem_get_info()
        return (total - free) / (1024**3)
    except Exception:  # noqa: BLE001 — una misura che non si puo' prendere non e' un guasto
        return 0.0


def ensure_model() -> None:
    """
    Carica il modello se non c'e'. Chiamato dalla richiesta, non dall'avvio.

    Caricarlo all'avvio sembrava giusto — la prima lezione non paga l'attesa — ma costava due cose
    che si pagano ogni giorno: quattro minuti in cui il server non risponde nemmeno a `/health`
    (e dall'app si legge come «server non raggiungibile», che manda a cercare il problema dalla
    parte sbagliata), e qualche gigabyte di VRAM tenuti occupati anche quando il computer sta
    facendo altro. Ora l'attesa la paga la prima trascrizione, che tanto e' gia' un'attesa.
    """
    if STATE["model"] is not None:
        return
    import whisperx

    started = time.time()
    log.info("carico %s su %s (%s)...", STATE["name"], STATE["device"], STATE["compute_type"])
    STATE["model"] = whisperx.load_model(
        STATE["name"],
        device=STATE["device"],
        compute_type=STATE["compute_type"],
    )
    log.info("pronto in %.0f s (%.1f GB di VRAM)", time.time() - started, vram_gb())


def unload_model(reason: str) -> None:
    """
    Molla il modello e restituisce la VRAM.

    Non basta cancellare il riferimento: ctranslate2 e torch tengono una riserva loro, e finche'
    non gli si dice di svuotarla la scheda risulta occupata anche a modello sparito. Da qui si
    vede nel Task Manager, ed e' il motivo per cui si fa: tenere quattro gigabyte impegnati tutto
    il giorno per una lezione al pomeriggio e' spazio tolto a tutto il resto.
    """
    if STATE["model"] is None and not STATE["align"]:
        return
    before = vram_gb()
    STATE["model"] = None
    # Anche gli allineatori stanno sulla scheda, uno per lingua.
    STATE["align"].clear()
    gc.collect()
    if STATE["device"] == "cuda":
        with contextlib.suppress(Exception):
            import torch

            torch.cuda.empty_cache()
            torch.cuda.ipc_collect()
    log.info("modello scaricato (%s): da %.1f a %.1f GB occupati sulla scheda", reason, before, vram_gb())


async def idle_watch() -> None:
    """
    Guarda l'orologio e sfratta il modello quando non serve piu' a nessuno.

    Gira dentro il server, ogni mezzo minuto. Prende lo stesso lucchetto delle trascrizioni, cosi'
    non puo' capitare che il modello sparisca a meta' di una lezione.
    """
    while True:
        await asyncio.sleep(30)
        idle = STATE["idle_seconds"]
        if not idle or STATE["model"] is None or STATE["busy"]:
            continue
        quiet = time.time() - STATE["last_used"]
        if quiet < idle:
            continue
        async with GATE.slot(0):
            if STATE["model"] is not None and not STATE["busy"]:
                unload_model(f"{quiet / 60:.0f} minuti senza richieste")


async def unload_now(reason: str) -> bool:
    """
    Lo sfratto su richiesta, con lo stesso lucchetto dello sfratto a tempo.

    Torna falso se in quel momento c'era una trascrizione in corso: il modello non sparisce sotto
    una lezione a meta'. Chi chiede lo scarico se lo sente dire, invece di vederlo non succedere.
    """
    if STATE["busy"]:
        return False
    async with GATE.slot(0):
        if STATE["busy"] or STATE["model"] is None:
            return False
        unload_model(reason)
        return True


def request_unload(reason: str = "richiesta") -> bool:
    """
    [unload_now] chiamata da un altro thread, per l'icona nell'area di notifica.

    Il menu del tray gira sul thread principale mentre il server sta nel suo; toccare il modello
    da li' significherebbe strapparlo mentre una richiesta lo usa. `run_coroutine_threadsafe`
    consegna il lavoro al ciclo giusto e ne aspetta l'esito.
    """
    loop = LOOP
    if loop is None or loop.is_closed():
        return False
    try:
        return asyncio.run_coroutine_threadsafe(unload_now(reason), loop).result(timeout=30)
    except Exception:  # noqa: BLE001 — un menu che non riesce a scaricare non deve chiudere il server
        return False


@contextlib.asynccontextmanager
async def lifespan(_: FastAPI):
    global LOOP
    LOOP = asyncio.get_running_loop()
    watcher = asyncio.create_task(idle_watch())
    try:
        yield
    finally:
        watcher.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await watcher
        unload_model("chiusura")
        LOOP = None


app = FastAPI(title="Pampa Notes companion", docs_url=None, redoc_url=None, lifespan=lifespan)


def trust_sentence_splitter(language: str) -> None:
    """
    Fa trovare a NLTK le regole per dividere le frasi della lingua, e gliele fa aprire.

    **E' il motivo per cui l'allineamento non ha mai funzionato.** `whisperx.align` divide ogni
    segmento in frasi con il Punkt di NLTK prima di allineare le parole, e NLTK 3.10 controlla ogni
    file che apre: il percorso *risolto* deve stare sotto una delle cartelle dati, *risolte* anche
    quelle. Sul computer di casa le due risoluzioni non tornano: quando il companion parte da dentro
    l'app di Claude, Windows (la virtualizzazione dei pacchetti MSIX) manda le scritture in
    `%APPDATA%` in `...\\Packages\\Claude_…\\LocalCache\\Roaming`, e cosi' la cartella `nltk_data`
    si risolve in `AppData\\Roaming\\nltk_data` mentre i file dentro — scaricati da li' — si
    risolvono nella copia virtuale. NLTK vede un file «fuori» dalle sue cartelle e rifiuta:
    `PermissionError: Security Violation [pathsec.open]: Unauthorized path ...`. L'`except` intorno
    all'allineamento lo inghiottiva, e ogni lezione tornava con i tempi per frase.

    Qui si cerca la cartella della lingua, si risolve un file vero che ci sta dentro (la cartella da
    sola si risolve nell'altro posto), e la cartella dove quel file sta davvero si aggiunge a quelle
    di cui NLTK si fida. Vale in tutti e due i casi: fuori dall'app le due strade coincidono e
    l'aggiunta non cambia niente. Se le regole non ci sono si scaricano, come farebbe WhisperX.
    """
    import nltk

    try:
        from whisperx.utils import PUNKT_LANGUAGES
    except ImportError:  # una versione di WhisperX che non le ha: si prova col nome inglese
        PUNKT_LANGUAGES = {}
    name = PUNKT_LANGUAGES.get(language, "english")
    resource = f"tokenizers/punkt_tab/{name}/"
    try:
        found = nltk.data.find(resource)
    except LookupError:
        log.info("scarico le regole delle frasi di NLTK (punkt_tab)...")
        nltk.download("punkt_tab", quiet=True, raise_on_error=True)
        found = nltk.data.find(resource)
    folder = Path(str(getattr(found, "path", found)))
    if folder.is_file():
        # Dentro uno zip: il file da aprire e' lo zip stesso.
        real = folder.resolve().parent
    else:
        probe = next((child for child in folder.iterdir() if child.is_file()), None)
        real = probe.resolve().parent if probe is not None else folder.resolve()
    if str(real) not in [str(entry) for entry in nltk.data.path]:
        nltk.data.path.append(str(real))


def align_model_for(language: str, device: str | None = None):
    """
    Il modello di allineamento della lingua, tenuto da parte dopo il primo uso.

    Ce n'e' uno per lingua e pesa poco, ma scaricarlo la prima volta richiede rete: tenerlo in
    memoria evita di rifarlo a ogni lezione. Se ne va insieme al modello grande. Quello sul
    processore del ripiego (vedi [run_job]) non si tiene: serve a una lezione sola, e la prossima
    torna sulla scheda.
    """
    import whisperx

    device = device or STATE["device"]
    if device != STATE["device"]:
        trust_sentence_splitter(language)
        return whisperx.load_align_model(language_code=language, device=device)
    if language not in STATE["align"]:
        log.info("carico l'allineamento per '%s'...", language)
        trust_sentence_splitter(language)
        model, metadata = whisperx.load_align_model(language_code=language, device=device)
        STATE["align"][language] = (model, metadata)
    return STATE["align"][language]


@app.get("/health")
def health() -> dict[str, Any]:
    """Quello che l'app chiama per dire «raggiunto» invece di «non risponde». Risponde subito
    anche a modello scarico: e' il senso di non caricarlo all'avvio. E senza credenziali: e' la
    domanda «ci sei?», non «chi sei?»."""
    loaded = STATE["model"] is not None
    quiet = time.time() - STATE["last_used"] if STATE["last_used"] else 0.0
    alignment = dict(STATE["alignment"])
    return {
        "status": "ok",
        "model": STATE["name"],
        "device": STATE["device"],
        "compute_type": STATE["compute_type"],
        "busy": STATE["busy"],
        # L'app lo guarda per sapere se il testo si accendera' parola per parola davvero o per stima.
        # Vero finche' non si sa il contrario: prima della prima lezione non c'e' niente da dire.
        "word_timestamps": all(status == "ok" for status in alignment.values()),
        # Per lingua, "ok" o l'errore: un allineamento rotto si vede qui, senza leggere il registro.
        "alignment": alignment,
        # Le tre righe qui sotto non le legge l'app: le legge chi sta guardando la VRAM.
        "loaded": loaded,
        "vram_gb": round(vram_gb(), 1),
        "unload_in_s": max(0, int(STATE["idle_seconds"] - quiet)) if loaded and STATE["idle_seconds"] else None,
        # Quanti aspettano il loro turno, e se questo computer accetta ospiti.
        "queue": GATE.waiting,
        "guests": bool(STATE["index_url"] and STATE["owner"]),
        # Come si entra: con l'account (biglietti verificati dal Worker) e/o senza credenziali.
        "auth": {
            "account": bool(STATE["index_url"] and STATE["owner"]),
            "anonymous": bool(STATE["accept_anonymous"]),
        },
    }


def pairing_link(port: int) -> str:
    """
    Il link che l'app sa aprire: `pampanotes://endpoint?url=...&remote=...`.

    Senza token, di proposito: il QR si fotografa, si inoltra, resta nella galleria, e il token apre
    il computer a chi lo legge. Un dispositivo con l'account entra col biglietto (vedi
    [verify_ticket]); uno senza il codice lo scrive a mano, dal config.json.
    """
    lan = local_addresses(port)
    link = "pampanotes://endpoint?url=" + (lan[0] if lan else f"http://localhost:{port}")
    remote = tailscale_address(port)
    if remote:
        link += "&remote=" + remote
    return link


def new_pairing_key() -> str:
    """
    Una chiave usa-e-getta per la pagina di accoppiamento, buona per dieci minuti.

    La pagina non porta piu' il token, ma dice dove sta il computer, in casa e fuori: senza una
    chiave, chiunque sulla rete potrebbe chiederla. Con la chiave, il segreto e' il QR sullo schermo.
    """
    key = secrets.token_urlsafe(16)
    STATE["pairing"] = (key, time.time() + 600)
    return key


@app.get("/pair")
def pair(request: Request, k: str = "") -> Any:
    """
    La pagina che apre il QR.

    Il QR non puo' contenere `pampanotes://...` direttamente: la fotocamera del telefono riconosce
    come link solo `http` e `https`, e tutto il resto lo mostra come testo da copiare. Quindi il QR
    porta a questa pagina, che ha un bottone con il link vero: un browser, su un tocco, un link con
    schema proprio lo apre. Per chi non ha ancora l'app, gli indirizzi sono scritti sotto.
    """
    from fastapi.responses import HTMLResponse
    from html import escape

    pairing = STATE.get("pairing")
    if not pairing or not secrets.compare_digest(k, pairing[0]) or time.time() > pairing[1]:
        raise HTTPException(status_code=404, detail="QR scaduto: rifallo dal menu dell'icona")

    port = STATE["port"]
    link = pairing_link(port)
    intent = "intent://endpoint?" + link.split("?", 1)[1] + "#Intent;scheme=pampanotes;package=dev.pampa.pampanotes;end"
    lan = local_addresses(port)
    remote = tailscale_address(port)
    rows = f"<p><b>In casa:</b> {escape(lan[0] if lan else '?')}</p>"
    rows += f"<p><b>Fuori casa:</b> {escape(remote)}</p>" if remote else "<p><b>Fuori casa:</b> installa Tailscale sul computer</p>"
    if STATE["index_url"] and STATE["owner"]:
        rows += "<p><b>Accesso:</b> entra nell'app con l'account Google di questo computer.</p>"
    if STATE["token"]:
        rows += "<p><b>Codice:</b> quello scritto in <code>config.json</code>, per i dispositivi senza account.</p>"
    body = f"""<!doctype html><html lang="it"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Pampa Notes</title>
<style>body{{font-family:system-ui,sans-serif;margin:0;padding:24px;background:#f4f0fb;color:#1c1b1f}}
h1{{font-size:22px;margin:0 0 6px}}p{{margin:6px 0;font-size:16px;line-height:1.4}}code{{word-break:break-all}}
a.b{{display:block;margin:20px 0;padding:16px;border-radius:16px;background:#7c3aed;color:#fff;text-align:center;
font-size:18px;font-weight:600;text-decoration:none}}a.s{{color:#7c3aed}}</style></head><body>
<h1>Il tuo computer, per Pampa Notes</h1><p>Tocca il bottone: l'app si configura da sola.</p>
<a class="b" href="{escape(link)}">Apri Pampa Notes</a>
<p><a class="s" href="{escape(intent)}">Se non si apre, prova questo</a></p>
<hr><p>Oppure a mano, in <i>Impostazioni → Servizi → Server personale</i>:</p>{rows}</body></html>"""
    # Una pagina con gli indirizzi del computer non deve restare nella cache del browser, ne' di
    # qualunque cosa stia in mezzo: vale dieci minuti, e dopo non deve piu' esistere da nessuna parte.
    return HTMLResponse(body, headers={"Cache-Control": "no-store"})


@app.get("/v1/models")
def models() -> dict[str, Any]:
    """Nel formato di OpenAI, perche' e' quello che l'app sa leggere."""
    return {"object": "list", "data": [{"id": STATE["name"], "object": "model", "owned_by": "whisperx"}]}


# --- chi sta chiedendo -------------------------------------------------------------------------


class AuthError(Exception):
    """Credenziali che non passano. Il messaggio arriva all'app nel 401."""


@dataclass(frozen=True)
class Caller:
    """
    Chi ha fatto la richiesta, deciso una volta sola in [AuthGate] prima di leggere il corpo.

    `kind` e' "owner" o "guest"; `via` dice come ci si e' arrivati ("token", "ticket", "guest",
    "anonymous"), e serve al registro e a nient'altro.
    """

    kind: str
    via: str
    name: str | None = None
    bearer: str = ""


def _same_secret(given: str, expected: str) -> bool:
    """
    Il confronto e' `compare_digest` e non `==` perche' un `==` su stringhe esce al primo carattere
    diverso, e quanto ci mette a uscire dice quanti caratteri erano giusti. In casa non cambia
    niente; il giorno in cui questa porta si affaccia altrove, cambia. In byte, perche' con una
    stringa non ASCII `compare_digest` solleva invece di rispondere di no.
    """
    return secrets.compare_digest(given.encode("utf-8"), expected.encode("utf-8"))


def identify(authorization: str) -> Caller:
    """
    Da un header `Authorization` a chi e', o [AuthError].

    Nell'ordine:
      * `pg_…`: un ospite, se il Worker lo riconosce ([verify_guest]). Un ospite rifiutato resta
        fuori anche con l'accesso libero acceso: l'invito revocato deve dirlo, non fingere di valere;
      * il token di `config.json`, se c'e': la riserva che funziona anche senza internet;
      * `pt_…`: il biglietto dell'account, se il Worker lo riconosce ([verify_ticket]);
      * nient'altro: passa solo con `accept_anonymous`. Una credenziale che non torna conta come
        nessuna credenziale — con l'accesso libero non apre niente di piu' di quanto non apra non
        mandarla — ed e' quello che tiene in piedi un'app nuova su un computer a cui manca ancora
        `owner` nel config.json.
    """
    bearer = authorization[7:].strip() if authorization[:7].lower() == "bearer " else ""
    if bearer.startswith("pg_"):
        name = verify_guest(bearer)
        if not name:
            raise AuthError("ospite non riconosciuto")
        return Caller("guest", "guest", name, bearer)
    expected = STATE["token"]
    if bearer and expected and _same_secret(bearer, expected):
        return Caller("owner", "token", None, bearer)
    if bearer.startswith("pt_") and verify_ticket(bearer):
        return Caller("owner", "ticket", None, bearer)
    if STATE["accept_anonymous"]:
        return Caller("owner", "anonymous")
    if bearer.startswith("pt_"):
        raise AuthError("il computer non riconosce l'account: controlla owner e index_url nel config.json")
    if bearer:
        raise AuthError("token non valido")
    raise AuthError("servono le credenziali: entra con l'account nell'app, o scrivi il codice del computer")


# Le uniche strade aperte a tutti: «ci sei?» e la pagina del QR, che ha la sua chiave.
OPEN_PATHS = frozenset({"/health", "/pair"})


class AuthGate:
    """
    Le credenziali si guardano **prima** che si legga il corpo della richiesta.

    Con il controllo dentro l'endpoint, FastAPI leggeva tutto il multipart — un'ora di audio, un
    `.sdocx` da mezzo giga — e solo dopo diceva 401: chiunque raggiungesse la porta poteva riempire
    il disco e tenere occupato il server senza nessuna credenziale. Un middleware ASGI vede la
    richiesta quando sono arrivati solo gli header, e un rifiuto qui non tocca `receive`.

    Chi passa lo trova in `request.state.caller` ([caller_of]). La verifica di un biglietto o di un
    ospite puo' fare una richiesta al Worker: va su un thread, o bloccherebbe il server per tutti.
    """

    def __init__(self, app: Any) -> None:
        self.app = app

    async def __call__(self, scope: dict, receive: Callable, send: Callable) -> None:
        if scope["type"] != "http" or scope.get("path") in OPEN_PATHS:
            await self.app(scope, receive, send)
            return
        authorization = Headers(scope=scope).get("authorization", "")
        try:
            caller = await asyncio.to_thread(identify, authorization)
        except AuthError as error:
            response = JSONResponse({"detail": str(error)}, status_code=401, headers={"WWW-Authenticate": "Bearer"})
            await response(scope, receive, send)
            return
        scope.setdefault("state", {})["caller"] = caller
        await self.app(scope, receive, send)


app.add_middleware(AuthGate)


def caller_of(request: Request) -> Caller:
    """Chi ha fatto la richiesta, come l'ha deciso [AuthGate]."""
    caller = getattr(request.state, "caller", None)
    if caller is None:  # una strada aperta che chiede chi e': non dovrebbe succedere
        raise HTTPException(status_code=401, detail="servono le credenziali")
    return caller


def require_owner(request: Request) -> None:
    """Un ospite trascrive e basta: l'archivio dei file e lo sfratto del modello sono del proprietario."""
    if caller_of(request).kind != "owner":
        raise HTTPException(status_code=401, detail="riservato al proprietario")


# Il nome di prima, per chi lo importa ancora.
check_token = require_owner


def _post_worker(path: str, payload: dict[str, Any]) -> dict[str, Any]:
    """Una POST al Worker dell'indice. Solleva `HTTPError` su un rifiuto, `OSError` sulla rete."""
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        STATE["index_url"].rstrip("/") + path,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "User-Agent": WORKER_USER_AGENT},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        answer = json.loads(response.read() or b"{}")
    return answer if isinstance(answer, dict) else {}


def verify_guest(token: str) -> str | None:
    """Chiede al Worker se il token e' un ospite di questo proprietario. Il nome, o None."""
    now = time.time()
    hit, cached = GUEST_CACHE.get(token, now)
    if hit:
        return cached
    name: str | None = None
    if STATE["index_url"] and STATE["owner"]:
        try:
            name = _post_worker("/v1/guests/verify", {"token": token, "owner": STATE["owner"]}).get("name") or None
        except urllib.error.HTTPError:
            name = None
        except (OSError, ValueError) as error:
            log.warning("verifica dell'ospite non riuscita: %s", error)
            name = None
    GUEST_CACHE.put(token, name, now + (600 if name else NEGATIVE_S))
    return name


def verify_ticket(ticket: str) -> bool:
    """
    Chiede al Worker se il biglietto `pt_…` e' dell'account scritto in `owner`.

    Il companion non lo decodifica da solo, di proposito: la chiave che lo firma sta nel Worker e
    non deve uscirne. La risposta buona si tiene fino alla scadenza del biglietto (al massimo dodici
    ore): cosi' un'interruzione di internet non ferma il computer per le ore in cui il biglietto
    vale. Quella cattiva un minuto, perche' un biglietto inventato non faccia una richiesta al
    Worker a ogni tentativo — e perche' chi corregge `owner` nel config.json non aspetti ore.
    """
    now = time.time()
    hit, cached = TICKET_CACHE.get(ticket, now)
    if hit:
        return bool(cached)
    until = now + NEGATIVE_S
    valid = False
    if STATE["index_url"] and STATE["owner"]:
        try:
            answer = _post_worker("/v1/computer/verify", {"ticket": ticket, "owner": STATE["owner"]})
            expires = float(answer.get("expiresAt") or 0) / 1000
            if answer.get("ok") is True and expires > now:
                valid = True
                until = min(expires, now + TICKET_MAX_S)
        except urllib.error.HTTPError as error:
            log.info("biglietto dell'account rifiutato dal Worker (%s): controlla owner nel config.json", error.code)
        except (OSError, ValueError, TypeError) as error:
            log.warning("verifica del biglietto non riuscita: %s", error)
    TICKET_CACHE.put(ticket, valid, until)
    return valid


def report_usage(token: str, seconds: float) -> None:
    """Una trascrizione fatta da un ospite: si dice al Worker quanti secondi, per il registro."""
    if not STATE["index_url"]:
        return
    try:
        _post_worker("/v1/guests/usage", {"token": token, "seconds": round(seconds)})
    except (OSError, ValueError) as error:
        log.warning("registro degli ospiti non aggiornato: %s", error)


app.include_router(archive.build_router(require_owner))


@app.post("/v1/admin/unload")
async def admin_unload(request: Request) -> dict[str, Any]:
    """
    Molla il modello adesso, senza aspettare i dieci minuti.

    Serve a chi sta per aprire un gioco e rivuole la scheda: l'icona nell'area di notifica lo fa
    senza passare di qui ([request_unload]), questo e' per l'app e per chi automatizza.
    """
    require_owner(request)
    freed = await unload_now("richiesta")
    return {"unloaded": freed, "busy": STATE["busy"], "vram_gb": round(vram_gb(), 1)}


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    request: Request,
    file: UploadFile = File(...),
    model: str = Form(default=""),
    language: str = Form(default=""),
    prompt: str = Form(default=""),
    temperature: float = Form(default=0.0),
    response_format: str = Form(default="json"),
):
    """
    La chiamata vera. Multipart come OpenAI, risposta `verbose_json` con i segmenti.

    Il campo `model` si ignora di proposito: il modello e' quello scelto all'avvio, e cambiarlo per
    richiesta significherebbe rileggere qualche gigabyte di pesi nel mezzo di una lezione.
    """
    caller = caller_of(request)
    who = f"ospite {caller.name}: " if caller.kind == "guest" else ""
    target: Path | None = None
    # Il `try` comincia prima del file temporaneo, non dopo: una richiesta annullata mentre si
    # copiava l'audio, o mentre aspettava il suo turno, lasciava il file in %TEMP% per sempre.
    try:
        suffix = Path(file.filename or "audio").suffix or ".m4a"
        # Su disco e non in memoria: qui arrivano file da un'ora.
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            target = Path(tmp.name)
            while chunk := await file.read(1024 * 1024):
                tmp.write(chunk)

        size_mb = target.stat().st_size / (1024 * 1024)
        log.info("%sricevuto %s (%.1f MB)%s", who, file.filename, size_mb, f", {GATE.waiting} in fila" if GATE.waiting else "")

        # Il proprietario passa davanti agli ospiti in attesa; nessuno interrompe chi sta gia' trascrivendo.
        async with GATE.slot(0 if caller.kind == "owner" else 1):
            STATE["busy"] = True
            started = time.time()
            try:
                # Su un thread anche il caricamento del modello: cosi' `/health` continua a
                # rispondere durante i minuti del primo avvio, invece di far credere all'app che il
                # server sia morto.
                result = await asyncio.to_thread(_transcribe, str(target), language.strip() or None)
            except Exception as error:  # noqa: BLE001 — qualunque guasto deve tornare come 500 leggibile
                log.exception("trascrizione fallita")
                raise HTTPException(status_code=500, detail=str(error)) from error
            finally:
                STATE["busy"] = False
                STATE["last_used"] = time.time()
    finally:
        if target is not None:
            with contextlib.suppress(OSError):
                target.unlink(missing_ok=True)

    elapsed = time.time() - started
    duration = result["segments"][-1]["end"] if result["segments"] else 0.0
    speed = duration / elapsed if elapsed > 0 else 0
    on_cpu = " sul processore" if result.get("device_used") == "cpu" and STATE["device"] != "cpu" else ""
    log.info("%sfatto%s: %.1f min in %.0f s (%.0f volte il tempo reale)", who, on_cpu, duration / 60, elapsed, speed)
    if caller.kind == "guest":
        asyncio.get_running_loop().run_in_executor(None, report_usage, caller.bearer, duration)
    if STATE["idle_seconds"]:
        log.info("tengo il modello in memoria per %d minuti", STATE["idle_seconds"] // 60)

    if response_format == "text":
        return PlainTextResponse(result["text"])
    return JSONResponse(result)


# --- il lavoro ---------------------------------------------------------------------------------


def is_oom(error: BaseException) -> bool:
    """
    La scheda ha finito la memoria?

    Da torch arriva `torch.cuda.OutOfMemoryError`; da ctranslate2, che fa girare Whisper, un
    `RuntimeError` qualunque con «out of memory» nel testo («CUDA failed with error out of
    memory»); da cuBLAS e cuDNN un «ALLOC_FAILED». Si riconoscono tutti e tre: sono la stessa cosa
    — qualcun altro, un gioco, un altro programma, si e' preso la scheda — e hanno lo stesso rimedio.
    """
    try:
        import torch

        if isinstance(error, torch.cuda.OutOfMemoryError):
            return True
    except Exception:  # noqa: BLE001 — senza torch si guarda solo il testo
        pass
    text = str(error).lower()
    return "out of memory" in text or "alloc_failed" in text


class Engine:
    """
    Il modello, l'allineamento e la memoria della scheda: le tre cose che il lavoro tocca.

    Una classe e non chiamate sparse perche' [run_job] la riceve come argomento, e i test gliene
    passano una finta che finisce la memoria a comando: il ripiego dalla scheda al processore e'
    codice che gira solo nei giorni storti, e un codice cosi' o si prova apposta o non si prova mai.
    """

    def main_model(self) -> Any:
        ensure_model()
        return STATE["model"]

    def cpu_model(self) -> Any:
        import whisperx

        log.warning("carico %s sul processore (int8) per questa lezione: sara' piu' lenta", STATE["name"])
        return whisperx.load_model(STATE["name"], device="cpu", compute_type="int8")

    def align(self, segments: list[dict], language: str, audio: Any, device: str) -> list[dict]:
        import whisperx

        model, metadata = align_model_for(language, device)
        aligned = whisperx.align(segments, model, metadata, audio, device, return_char_alignments=False)
        return aligned.get("segments", segments)

    def release(self) -> None:
        """Restituisce alla scheda quello che torch tiene in riserva, prima di riprovare."""
        gc.collect()
        if STATE["device"] == "cuda":
            with contextlib.suppress(Exception):
                import torch

                torch.cuda.empty_cache()


# Sul processore il lotto conta poco per la velocita' e molto per la RAM: non si esagera.
CPU_BATCH_SIZE = 4


def run_job(audio: Any, language: str | None, engine: Engine, batch_size: int, device: str) -> dict[str, Any]:
    """
    Trascrive e allinea, scendendo dalla scheda alla RAM se la scheda non basta.

    La scheda puo' essere piena per ragioni che col companion non c'entrano — un gioco aperto a
    meta' pomeriggio — e allora `transcribe` o `align` finiscono la memoria. Prima di arrendersi:
      1. si svuota la riserva di torch e si riprova con un lotto grande la meta', fino a 1: un
         lotto piu' piccolo occupa meno, e spesso basta;
      2. se neanche con 1 entra, la lezione si fa sul processore con un modello `int8` caricato
         apposta, e poi buttato: e' piu' lenta, ma e' una lezione trascritta invece di un errore.
         La prossima riparte dalla scheda, che nel frattempo potrebbe essersi liberata.
    L'allineamento, se finisce la memoria, si rifa' sul processore: il suo modello e' piccolo.

    Torna i segmenti, la lingua, dove si e' trascritto (`device_used`) e l'esito dell'allineamento.
    """
    device_used = device
    size = max(1, int(batch_size))
    transcription: dict[str, Any] | None = None

    try:
        model = engine.main_model()
    except Exception as error:
        if device != "cuda" or not is_oom(error):
            raise
        engine.release()
        log.warning("il modello non entra nella scheda: questa lezione va sul processore")
        model = None

    while model is not None:
        try:
            transcription = model.transcribe(audio, batch_size=size, language=language)
            break
        except Exception as error:
            if device != "cuda" or not is_oom(error):
                raise
            engine.release()
            if size == 1:
                log.warning("memoria della scheda finita anche con un lotto da 1: passo al processore")
                break
            size = max(1, size // 2)
            log.warning("memoria della scheda finita: riprovo con batch_size %d", size)

    if transcription is None:
        device_used = "cpu"
        cpu = engine.cpu_model()
        try:
            transcription = cpu.transcribe(audio, batch_size=min(size, CPU_BATCH_SIZE), language=language)
        finally:
            del cpu
            engine.release()

    detected = transcription.get("language") or language or "en"
    segments = transcription.get("segments", [])

    alignment = "ok"
    try:
        align_device = device_used
        try:
            segments = engine.align(segments, detected, audio, align_device)
        except Exception as error:
            if align_device != "cuda" or not is_oom(error):
                raise
            engine.release()
            log.warning("allineamento: memoria della scheda finita, lo rifaccio sul processore")
            segments = engine.align(segments, detected, audio, "cpu")
    except Exception as error:  # noqa: BLE001
        # Senza allineamento i tempi restano quelli di Whisper: meno precisi, ma una trascrizione
        # con tempi approssimativi vale piu' di un errore. Con la traccia, pero': senza, l'errore di
        # NLTK (vedi [trust_sentence_splitter]) e' rimasto nascosto dietro questa riga per giorni.
        alignment = f"errore: {type(error).__name__}: {error}"
        log.warning("allineamento non riuscito per '%s': tengo i tempi originali", detected, exc_info=True)

    return {
        "segments": segments,
        "language": detected,
        "device_used": device_used,
        "batch_size": size,
        "alignment": alignment,
    }


def _transcribe(path: str, language: str | None) -> dict[str, Any]:
    """Il lavoro vero, su un thread suo: WhisperX blocca, e bloccare il loop ferma anche /health."""
    import whisperx

    audio = whisperx.load_audio(path)
    job = run_job(audio, language, Engine(), STATE["batch_size"], STATE["device"])
    STATE["alignment"][job["language"]] = job["alignment"]

    out = []
    for index, segment in enumerate(job["segments"]):
        text = (segment.get("text") or "").strip()
        if not text:
            continue
        out.append(
            {
                "id": index,
                "seek": 0,
                "start": _finite(segment.get("start"), 0.0),
                "end": _finite(segment.get("end"), 0.0),
                "text": text,
                # WhisperX non li restituisce: l'app li usa per scartare le allucinazioni, e valori
                # che dicono "voce presente, confidenza buona" lasciano passare tutto — che e' il
                # comportamento giusto quando l'informazione non c'e'.
                "avg_logprob": _finite(segment.get("avg_logprob"), -0.2),
                "no_speech_prob": _finite(segment.get("no_speech_prob"), 0.0),
                "compression_ratio": 1.0,
                "temperature": 0.0,
                "tokens": [],
                "words": words_of(segment),
            }
        )

    gc.collect()
    return {
        "task": "transcribe",
        "language": job["language"],
        "duration": out[-1]["end"] if out else 0.0,
        "text": " ".join(s["text"] for s in out),
        "segments": out,
        # «cuda» o «cpu»: l'app lo usa per dire «trascritta sulla RAM, piu' lenta».
        "device_used": job["device_used"],
    }


def _finite(value: Any, fallback: float) -> float:
    """
    Un numero che il JSON sa scrivere.

    L'allineamento interpola i tempi mancanti con pandas, e un NaN puo' restare: `JSONResponse` lo
    rifiuta (`allow_nan=False`) e l'intera trascrizione tornerebbe come un 500 per una parola.
    """
    try:
        number = float(value)
    except (TypeError, ValueError):
        return fallback
    return number if math.isfinite(number) else fallback


def words_of(segment: dict) -> list[dict]:
    """Le parole allineate di un segmento, quelle con dei tempi.

    whisperx.align() restituisce senza `start`/`end` le parole su cui il modello fonetico non trova
    il token — numeri, sigle, parole in un'altra lingua. Una parola senza tempi in mezzo alla frase
    farebbe saltare il cursore dell'app, quindi non si manda: meglio una parola che non si accende
    di una che si accende a caso.
    """
    out = []
    for word in segment.get("words") or []:
        text = (word.get("word") or "").strip()
        if not text:
            continue
        start = _finite(word.get("start"), math.nan)
        end = _finite(word.get("end"), math.nan)
        if math.isnan(start) or math.isnan(end):
            continue
        out.append(
            {
                "word": text,
                "start": start,
                "end": end,
                "score": _finite(word.get("score"), 0.0),
            }
        )
    return out


def local_addresses(port: int) -> list[str]:
    """Gli indirizzi di casa su cui il telefono puo' trovare questo computer."""
    return [f"http://{a}:{port}" for a in _ipv4_addresses() if not _is_tailscale(a)]


def tailscale_address(port: int) -> str | None:
    """
    L'indirizzo che vale anche fuori casa, se Tailscale c'e'.

    Tailscale da' a ogni macchina un indirizzo nel blocco 100.64.0.0/10, che e' riservato ai
    provider e non compare mai su una rete di casa: trovarne uno fra le interfacce vuol dire che
    c'e' Tailscale, senza chiederlo al suo programma (che potrebbe non essere nel PATH).
    """
    for address in _ipv4_addresses():
        if _is_tailscale(address):
            return f"http://{address}:{port}"
    return None


def _ipv4_addresses() -> list[str]:
    """
    Gli indirizzi IPv4 del computer, il piu' probabile per primo.

    L'ordine conta: il menu dell'icona e il QR mostrano il primo, e su un PC con Hyper-V, WSL o
    Docker il primo che Windows elenca e' spesso quello di una scheda virtuale (`vEthernet`,
    172.x), che dal tablet non si raggiunge. Davanti va quello da cui il computer esce davvero verso
    la rete ([_primary_ipv4]); poi le reti di casa tipiche (192.168, 10), poi le 172.16/12 che le
    schede virtuali usano quasi sempre, e in fondo i 169.254 di una scheda senza rete.
    """
    found: list[str] = []
    primary = _primary_ipv4()
    if primary and not primary.startswith("127."):
        found.append(primary)
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if not address.startswith("127.") and address not in found:
                found.append(address)
    except OSError:
        pass
    return sorted(found, key=lambda address: _address_rank(address, primary))


def _primary_ipv4() -> str | None:
    """
    L'indirizzo della scheda che porta il traffico verso fuori.

    Un `connect` su un socket UDP non manda niente: chiede solo al sistema quale strada userebbe, e
    `getsockname` dice da quale indirizzo partirebbe. 192.0.2.1 e' un indirizzo di documentazione
    (TEST-NET-1) che non esiste da nessuna parte: nessun pacchetto, nessuna attesa.
    """
    with contextlib.suppress(OSError), socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect(("192.0.2.1", 9))
        return probe.getsockname()[0]
    return None


def _address_rank(address: str, primary: str | None) -> int:
    if address == primary and not _is_tailscale(address):
        return 0
    parts = [int(part) for part in address.split(".")] if address.count(".") == 3 else [0, 0, 0, 0]
    if parts[0] == 192 and parts[1] == 168:
        return 1
    if parts[0] == 10:
        return 2
    if parts[0] == 169 and parts[1] == 254:
        return 9
    if parts[0] == 172 and 16 <= parts[1] <= 31:
        return 5
    return 3


def _is_tailscale(address: str) -> bool:
    parts = address.split(".")
    return len(parts) == 4 and parts[0] == "100" and 64 <= int(parts[1]) <= 127


def configure(settings: dict[str, Any]) -> dict[str, Any]:
    """
    Da impostazioni a [STATE]. La chiamano sia `main` sia l'icona nell'area di notifica.

    Torna le impostazioni con `device` e `compute_type` risolti davvero, perche' chi le ha passate
    puo' aver scritto `auto` e vuole sapere com'e' finita.
    """
    resolved = dict(settings)
    resolved["device"] = config.resolve_device(settings["device"])
    resolved["compute_type"] = settings["compute_type"] or ("float16" if resolved["device"] == "cuda" else "int8")

    STATE["name"] = resolved["model"]
    STATE["device"] = resolved["device"]
    STATE["compute_type"] = resolved["compute_type"]
    STATE["batch_size"] = resolved["batch_size"]
    STATE["port"] = resolved["port"]
    # La variabile d'ambiente resta valida: era l'unico modo di dare un token senza scriverlo in
    # un file, e chi la usa non deve scoprire che ha smesso di funzionare.
    STATE["token"] = resolved["token"] or os.environ.get("PAMPA_TOKEN") or None
    STATE["idle_seconds"] = max(0, resolved["idle_minutes"]) * 60
    STATE["index_url"] = str(resolved.get("index_url") or "").strip()
    STATE["owner"] = str(resolved.get("owner") or "").strip()
    STATE["accept_anonymous"] = bool(resolved.get("accept_anonymous"))
    # Le verifiche tenute da parte valevano per l'account di prima.
    GUEST_CACHE.clear()
    TICKET_CACHE.clear()
    archive.open_archive(resolved["archive_root"])
    return resolved


def setup_file_logging() -> Path:
    """
    Il registro su file, che serve solo da quando il server puo' girare senza una finestra.

    Con `avvia.cmd` gli errori si leggevano nella console; partendo all'accesso non c'e' nessuna
    console, e un server che si ferma senza lasciare traccia e' un server che non si ripara.
    Cinque file da un megabyte: abbastanza per risalire a ieri, non abbastanza per accorgersene.
    """
    config.LOG_DIR.mkdir(exist_ok=True)
    path = config.LOG_DIR / "companion.log"
    handler = RotatingFileHandler(path, maxBytes=1_000_000, backupCount=5, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s  %(message)s", datefmt="%Y-%m-%d %H:%M:%S"))
    logging.getLogger().addHandler(handler)
    global FILE_HANDLER
    FILE_HANDLER = handler
    return path


def attach_access_log() -> None:
    """
    Una riga per richiesta, ma solo nel file: in console sarebbe rumore, nel registro e' l'unico
    modo di sapere *cosa* ha chiesto il telefono quando qualcosa non torna.

    Va chiamata **dopo** aver costruito la `uvicorn.Config`: e' li' che uvicorn riconfigura i suoi
    logger e butta via qualunque handler ci fosse prima.
    """
    if FILE_HANDLER is None:
        return
    access = logging.getLogger("uvicorn.access")
    access.addHandler(FILE_HANDLER)
    access.setLevel(logging.INFO)
    access.propagate = False


def already_running(port: int) -> bool:
    """
    C'e' gia' qualcosa in ascolto su quella porta?

    Un lock su file mente dopo un blocco del computer: il file resta, il processo no. La porta
    invece e' la cosa che conta davvero — se e' occupata, il secondo server non parte comunque.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.5)
        return probe.connect_ex(("127.0.0.1", port)) == 0


def banner(settings: dict[str, Any]) -> None:
    print()
    print("  Pampa Notes trova questo computer a uno di questi indirizzi:")
    for address in local_addresses(settings["port"]) or [f"http://localhost:{settings['port']}"]:
        print(f"    {address}")
    remote = tailscale_address(settings["port"])
    if remote:
        print(f"  E da fuori casa, con Tailscale:  {remote}")
    print()
    print("  Mettilo in Altro -> Impostazioni -> Server personale, e tocca 'Prova la connessione'.")
    if STATE["token"]:
        print("  Il codice (token di config.json) va nel campo sotto l'indirizzo, sui dispositivi senza account.")
    if STATE["index_url"] and STATE["owner"]:
        print(f"  Entra chi ha fatto l'accesso nell'app con {STATE['owner']}.")
    if STATE["accept_anonymous"]:
        print("  Accesso libero acceso: si trascrive anche senza credenziali (accept_anonymous in config.json).")
    if STATE["idle_seconds"]:
        print(f"  Il modello si carica alla prima registrazione e se ne va dopo {settings['idle_minutes']} minuti di silenzio.")
    else:
        print("  Il modello resta in memoria finche' il server e' acceso (--idle-minutes 0).")
    print("  Se il tablet non lo trova, lancia apri-firewall.cmd come amministratore.")
    count, size = archive.current().stats()
    print(f"  Archivio dei file: {settings['archive_root']}  ({count} file, {size / 1e9:.1f} GB)")
    print(flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="WhisperX per Pampa Notes")
    config.add_arguments(parser)
    args = parser.parse_args()

    settings = config.apply_cli(config.load(), args)
    settings = configure(settings)
    setup_file_logging()

    if already_running(settings["port"]):
        print()
        print(f"  C'e' gia' un server in ascolto sulla porta {settings['port']}.")
        print("  Guarda nell'area di notifica, accanto all'orologio: probabilmente e' quello.")
        print()
        return

    banner(settings)

    if settings["preload"]:
        ensure_model()
        STATE["last_used"] = time.time()

    config_ = uvicorn.Config(app, host="0.0.0.0", port=settings["port"], log_level="warning")
    attach_access_log()
    uvicorn.Server(config_).run()


if __name__ == "__main__":
    main()
