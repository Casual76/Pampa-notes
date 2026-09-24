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
import dataclasses
import gc
import hashlib
import json
import logging
import math
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
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
import binding
import config

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("pampa")

# Chi e' questo processo: cambia a ogni avvio. L'app lo legge in /health e nei lavori, e da li' sa
# distinguere «il computer si e' riavviato e ha perso la lezione» da «non ha risposto per un po'»:
# senza, due 404 di fila bastavano per rimandare da capo una lezione che stava andando benissimo.
INSTANCE = secrets.token_hex(8)

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
    # Quello che l'utente ha scelto (modello, lotto massimo, modo della VRAM...), com'e' scritto in
    # config.json: `name`, `compute_type` e `batch_size` qui sopra sono invece quello che si usa,
    # dopo che [decide_vram] ha fatto i conti con la scheda. Vedi [configure].
    "tunables": {},
    # La decisione di [decide_vram] (il `vram` di /health) e la scheda vista all'avvio.
    "vram": None,
    "gpu": None,
    # Il file da cui vengono le impostazioni: /v1/admin/settings ci riscrive.
    "config_path": config.CONFIG_PATH,
    # Con quali impostazioni e' stato caricato il modello in memoria: se cambiano, [ensure_model]
    # lo ricarica invece di usare quello vecchio.
    "loaded_as": None,
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
        # Chi aspetta, per nome: e' quello che fa dire all'app «sei il 2°» invece di «in coda».
        self._keys: dict[tuple[int, int], str] = {}

    @contextlib.asynccontextmanager
    async def slot(self, priority: int, key: str | None = None):
        async with self._cond:
            self._seq += 1
            me = (priority, self._seq)
            self._waiting.append(me)
            self._waiting.sort()
            if key:
                self._keys[me] = key
            # Chi smette di aspettare (la richiesta annullata, il server che si chiude) deve uscire
            # dalla fila. Senza, restava primo per sempre: nessun altro vedeva `_waiting[0] == me`,
            # e il computer smetteva di trascrivere per chiunque finche' non lo si riavviava.
            admitted = False
            try:
                await self._cond.wait_for(lambda: not self._busy and self._waiting[0] == me)
                admitted = True
            finally:
                self._waiting.remove(me)
                self._keys.pop(me, None)
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

    def position(self, key: str) -> int | None:
        """
        Il posto in fila di [key], contando anche chi sta trascrivendo adesso: 2 vuol dire «ce n'e'
        uno davanti», che e' quello che chi aspetta vuole sapere. None se non sta aspettando.

        Si legge dal ciclo di eventi, lo stesso che tocca la fila: niente lucchetto.
        """
        for index, me in enumerate(self._waiting):
            if self._keys.get(me) == key:
                return index + 1 + (1 if self._busy else 0)
        return None


GATE = PriorityGate()


class RequestCounter:
    """
    Quante richieste sono a meta' adesso: entrate da [AuthGate] e non ancora finite di rispondere.

    «Sta trascrivendo» ([STATE] `busy`) e «qualcuno in fila» ([PriorityGate]) non bastano per dire
    che il computer e' libero: un telefono che sta ancora caricando un'ora di audio non e' ne' l'uno
    ne' l'altro, e neanche un `PUT` nell'archivio, o la risposta di una lezione appena finita che sta
    ancora viaggiando verso un telefono con due tacche. Fermare il server in quel momento buttava via
    tutto, e lasciava un `.part` a meta' nell'archivio. Lo guardano il riavvio da se'
    ([restart_when_idle]), l'aggiornamento dall'icona e l'installer (`inflight` in /health).

    Il ciclo di eventi scrive, gli altri thread leggono: un lucchetto perche' `+=` non e' atomico.
    """

    def __init__(self) -> None:
        self._count = 0
        self._lock = threading.Lock()

    def enter(self) -> None:
        with self._lock:
            self._count += 1

    def leave(self) -> None:
        with self._lock:
            self._count = max(0, self._count - 1)

    @property
    def count(self) -> int:
        return self._count


REQUESTS = RequestCounter()

# Il server uvicorn in cui gira l'app, quando chi l'ha lanciato lo registra (`main`, l'icona): il
# riavvio da se' lo chiude con garbo invece di staccare la spina al processo. E quello che chi lo ha
# lanciato vuole fare prima di uscire (l'icona: togliersi dall'area di notifica).
SERVER: Any = None
ON_EXIT: Callable[[], None] | None = None
# Il server ha finito di chiudersi: [lifespan] l'ha visto uscire.
STOPPED = threading.Event()


# --- a che punto e' una trascrizione -------------------------------------------------------------
#
# Dal telefono una lezione mandata al computer era un'attesa muta: la barra arrivava al 100% del
# caricamento e poi niente, per dieci minuti o per un'ora, senza sapere se il PC stava caricando il
# modello, era in fila dietro un ospite o era a meta'. L'app ora manda un suo identificativo
# (`X-Pampa-Job`) insieme all'audio e, mentre aspetta la risposta, chiede `GET /v1/jobs/<id>`.
# Le percentuali sono vere: vengono dai callback di WhisperX (un passo per segmento della VAD in
# trascrizione, uno per segmento in allineamento), non da una stima sul tempo.

JOB_STATES = ("received", "queued", "decoding", "loading_model", "transcribing", "aligning", "done", "failed")
JOB_ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{8,64}")
# Dopo la fine un lavoro resta leggibile dieci minuti: l'app lo chiede fino all'ultima risposta, e
# una risposta persa per strada non deve trasformarsi in un 404 che sembra un companion vecchio.
JOB_KEEP_S = 10 * 60
# Uno che non finisce mai (il telefono sparito a meta' caricamento) se ne va comunque.
JOB_STALE_S = 6 * 3600
JOB_LIMIT = 256
ACTIVE_JOB_STATES = ("decoding", "loading_model", "transcribing", "aligning")


def bearer_hash(bearer: str) -> str:
    """Il bearer non si tiene in chiaro nemmeno in memoria: basta poterlo riconoscere."""
    return hashlib.sha256(bearer.encode("utf-8")).hexdigest()


class JobCancelled(BaseException):
    """
    La lezione e' stata annullata dal telefono. `BaseException` e non `Exception` apposta: i
    ripieghi (memoria finita, allineamento non riuscito) prendono `Exception`, e un annullamento
    non deve finire trascritto sul processore o con i tempi di Whisper.
    """


class JobProgress:
    """
    Lo stato di una trascrizione, scritto dal thread che lavora e letto dal ciclo di eventi.

    `fraction` vale dentro lo stato corrente (0..1 della trascrizione, poi di nuovo 0..1
    dell'allineamento): e' l'app che sa quante registrazioni ci sono e quanto pesa ognuna, e fa
    lei il conto complessivo. Un lucchetto perche' uno scatto a meta' di [set] direbbe lo stato
    nuovo con la percentuale del vecchio.
    """

    def __init__(self, job_id: str = "", owner: str = "", guest: bool = False, now: float | None = None) -> None:
        now = time.time() if now is None else now
        self.id = job_id
        self.bearer_hash = owner
        self.guest = guest
        self.state = "received"
        self.fraction = 0.0
        self.detail: str | None = None
        self.device: str | None = None
        self.audio_s: float | None = None
        self.created = now
        self.state_since = now
        self.started: float | None = None
        self.finished: float | None = None
        # Il pezzo che si sta facendo, da 1, e quanti sono: vedi [transcribe_audio]. Un file intero
        # e' «1 di 1», e `fraction` vale dentro lo stato del pezzo di adesso.
        self.chunk = 1
        self.chunks = 1
        # Chiesto dal telefono (`DELETE /v1/jobs/{id}`) o dalla connessione chiusa: il lavoro si
        # ferma al prossimo scatto di WhisperX ([check_cancelled]).
        self.cancelled = False
        # Il lavoro condiviso che questa richiesta aspetta (vedi [SharedWork]): finche' la richiesta
        # non ha un esito suo, a che punto e' lo dice lui.
        self._leader: JobProgress | None = None
        self._lock = threading.Lock()

    def follow(self, leader: "JobProgress") -> None:
        self._leader = leader

    @property
    def gate_key(self) -> str:
        """Il nome con cui il lavoro sta in fila in [PriorityGate]: quello del lavoro condiviso."""
        return self._leader.id if self._leader is not None else self.id

    def _source(self) -> "JobProgress":
        leader = self._leader
        if leader is not None and self.state not in ("done", "failed"):
            return leader
        return self

    def current_state(self) -> str:
        return self._source().state

    def cancel(self) -> None:
        self.cancelled = True

    def check_cancelled(self) -> None:
        if self.cancelled:
            raise JobCancelled()

    def piece(self, chunk: int, chunks: int) -> None:
        """Comincia il pezzo [chunk] di [chunks]: gli stati che seguono sono i suoi."""
        with self._lock:
            self.chunk = max(1, int(chunk))
            self.chunks = max(self.chunk, int(chunks))

    def set(self, state: str, fraction: float = 0.0, detail: str | None = None, now: float | None = None) -> None:
        now = time.time() if now is None else now
        with self._lock:
            self.state = state
            self.fraction = min(1.0, max(0.0, float(fraction)))
            self.detail = detail
            self.state_since = now
            if state in ("done", "failed"):
                self.finished = now

    def admitted(self, now: float | None = None) -> None:
        """Il turno e' arrivato: da qui si conta `processing_s`, la coda non e' lavoro."""
        self.started = time.time() if now is None else now

    def advance(self, fraction: float, state: str) -> None:
        """
        Dal callback di WhisperX. Solo se lo stato e' ancora quello per cui il callback e' nato:
        dopo un ripiego (memoria finita, lotto dimezzato) l'ultimo scatto del giro abbandonato non
        deve riportare su la barra del giro nuovo.
        """
        with self._lock:
            if self.state != state:
                return
            self.fraction = max(self.fraction, min(1.0, max(0.0, float(fraction))))

    def callback(self, state: str) -> Callable[[float], None]:
        """
        Il `progress_callback` di WhisperX parla in percento. E' anche il punto in cui un annullamento
        ferma il lavoro: WhisperX lo chiama a ogni lotto, e un'eccezione qui esce da `transcribe`.
        """

        def step(percent: float) -> None:
            self.check_cancelled()
            self.advance(float(percent) / 100.0, state)

        return step

    def processing_s(self, now: float | None = None) -> float:
        if self.started is None:
            return 0.0
        end = self.finished if self.finished is not None else (time.time() if now is None else now)
        return max(0.0, end - self.started)

    def snapshot(self, position: int | None = None, now: float | None = None) -> dict[str, Any]:
        now = time.time() if now is None else now
        src = self._source()
        with src._lock:
            state, fraction, detail = src.state, src.fraction, src.detail
            since = src.state_since
            chunk, chunks = src.chunk, src.chunks
            audio_s, device = src.audio_s, src.device
        in_state = max(0.0, now - since)
        # La stima c'e' solo quando dice qualcosa: con il 2% fatto in un secondo verrebbe fuori un
        # numero che cambia di minuti a ogni domanda.
        eta = None
        if state in ("transcribing", "aligning") and 0.03 <= fraction < 1.0 and in_state >= 2.0:
            eta = round(in_state * (1.0 - fraction) / fraction, 1)
        return {
            "id": self.id,
            "state": state,
            "fraction": round(fraction, 4),
            "position": position if state == "queued" else None,
            "audio_s": round(audio_s, 2) if audio_s is not None else None,
            "elapsed_s": round(max(0.0, (self.finished or now) - self.created), 1),
            "state_elapsed_s": round(in_state, 1),
            "eta_s": eta,
            "processing_s": round(src.processing_s(now), 1),
            "device": device,
            "detail": detail,
            "chunk": chunk,
            "chunks": chunks,
            "instance": INSTANCE,
        }


class JobRegistry:
    """
    I lavori di cui si puo' chiedere, al massimo `limit`, ognuno per dieci minuti dopo la fine.

    L'id lo sceglie l'app, quindi chiunque abbia accesso potrebbe provare a indovinarne uno: il
    bearer con cui e' arrivato l'audio resta con il lavoro (come impronta), e un ospite legge solo i
    suoi. Il proprietario li legge tutti: il computer e' suo.
    """

    def __init__(self, limit: int = JOB_LIMIT, keep_s: float = JOB_KEEP_S) -> None:
        self.limit = limit
        self.keep_s = keep_s
        self._items: OrderedDict[str, JobProgress] = OrderedDict()
        self._lock = threading.Lock()

    def open(self, job_id: str, caller: "Caller", now: float | None = None) -> JobProgress | None:
        if not job_id or not JOB_ID_PATTERN.fullmatch(job_id):
            return None
        now = time.time() if now is None else now
        owner = bearer_hash(caller.bearer)
        with self._lock:
            self._prune(now)
            existing = self._items.get(job_id)
            # Lo stesso id da un altro bearer non si prende il posto di quello che c'e': sarebbe il
            # modo di leggere, o cancellare, il lavoro di un altro.
            if existing is not None and not secrets.compare_digest(existing.bearer_hash, owner):
                return None
            job = JobProgress(job_id, owner, caller.kind == "guest", now)
            self._items[job_id] = job
            self._items.move_to_end(job_id)
            # Oltre il limite se ne va prima un lavoro finito, poi uno mai partito (il file non e'
            # mai arrivato); uno che sta lavorando mai: dimenticarlo farebbe credere all'app che il
            # computer l'abbia perso, e la lezione ripartirebbe da capo.
            while len(self._items) > self.limit:
                gone = next((key for key, item in self._items.items() if item.finished is not None), None)
                if gone is None:
                    gone = next((key for key, item in self._items.items() if item.current_state() == "received" and key != job_id), None)
                if gone is None:
                    break
                self._items.pop(gone)
            return job

    def get(self, job_id: str, now: float | None = None) -> JobProgress | None:
        now = time.time() if now is None else now
        with self._lock:
            self._prune(now)
            return self._items.get(job_id)

    @staticmethod
    def visible_to(job: JobProgress, caller: "Caller") -> bool:
        if caller.kind == "owner":
            return True
        return secrets.compare_digest(job.bearer_hash, bearer_hash(caller.bearer))

    def _prune(self, now: float) -> None:
        # Scaduto: finito da piu' di [keep_s], o mai partito da ore (il caricamento si e' perso). Un
        # lavoro in corso resta quanto dura — una lezione di tre ore sul processore dura tre ore.
        gone = [
            key
            for key, item in self._items.items()
            if (item.finished is not None and now - item.finished > self.keep_s)
            or (item.finished is None and item.current_state() == "received" and now - item.created > JOB_STALE_S)
        ]
        for key in gone:
            del self._items[key]

    def clear(self) -> None:
        with self._lock:
            self._items.clear()

    def __len__(self) -> int:
        return len(self._items)


JOBS = JobRegistry()


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


def vram_gb(max_age_s: float = 0.0, allow_torch: bool = True) -> float:
    """
    Quanta memoria della scheda risulta occupata, in tutto.

    Si chiede al driver (`mem_get_info`) e non a torch (`memory_reserved`), perche' il modello
    grande non passa da torch: lo alloca ctranslate2 per conto suo, e torch non lo vede. Misurando
    dalla parte di torch, scaricare quattro gigabyte e mezzo si leggeva come "0.0 GB -> 0.0 GB".

    E' il totale della scheda, non solo di questo processo: comprende il desktop e tutto il resto.
    Per la domanda a cui serve rispondere — "la memoria e' tornata libera?" — e' il numero giusto.

    Di serie una misura fresca, perche' chi la chiede di solito confronta un prima e un dopo.
    `/health` invece si accontenta di una di qualche secondo fa e non passa mai da torch: vedi
    [HEALTH_DRIVER_AGE_S].
    """
    if STATE["device"] != "cuda":
        return 0.0
    driver = nvidia_query(max_age_s=max_age_s)
    if driver is not None:
        return driver["used_gb"]
    if not allow_torch:
        return 0.0
    try:
        import torch

        free, total = torch.cuda.mem_get_info()
        return (total - free) / (1024**3)
    except Exception:  # noqa: BLE001 — una misura che non si puo' prendere non e' un guasto
        return 0.0


# --- quanta VRAM -------------------------------------------------------------------------------
#
# La memoria che WhisperX chiede alla scheda dipende da tre cose: il modello, il `compute_type` e il
# `batch_size`. **Non dalla durata della lezione**: l'audio si lavora a finestre di trenta secondi, e
# un'ora sono solo piu' finestre in fila — costano tempo e RAM, non VRAM. Il lotto e' quante finestre
# passano insieme, ed e' l'unica manopola che la VRAM sente davvero dopo il modello.
#
# Perche' serve saperlo prima. Su Windows la scheda che si riempie non da' quasi mai «out of memory»:
# il driver sposta quello che non ci sta nella RAM condivisa, e la lezione esce lo stesso, sei volte
# piu' lenta. E' successo il 23/09 alle 13:56: un altro programma teneva quasi quattro gigabyte, e
# lezioni che di solito vanno a 50–100 volte il tempo reale sono andate a 12–18. Il ripiego di
# [run_job] non scatta, perche' non c'e' nessun errore da prendere: l'unica difesa e' non chiedere
# piu' di quello che c'e'.
#
# I numeri sono **stime**, tarate su questo computer (RTX 4070 Ti, 12 GB, large-v3 float16):
#   * pesi: quelli del modello in float16; in int8 poco piu' della meta';
#   * per elemento del lotto: la ricerca a fasci tiene per ogni finestra, per ogni fascio e per ogni
#     strato del decoder le chiavi dell'attenzione sull'audio. Misurato con `small` float16: 0,08 GB
#     in piu' per ogni elemento (da lotto 1 a 16, lineare). Per large si scala con strati e
#     larghezza del decoder, e il registro dice dove cade: con lotto 16 la lezione va veloce quando
#     la scheda e' libera (picco sotto i ~10,5 GB che restano oltre il desktop) e rallenta quando un
#     altro programma tiene 3,7 GB (picco sopra i ~7). 0,25 GB a elemento sta in mezzo;
#   * l'allineamento (wav2vec2 base, uno per lingua) circa 0,4 GB;
#   * il contesto CUDA, il VAD e gli spazi di lavoro di cuBLAS circa 0,7 GB.
# Il totale e' la memoria di *questo* processo: il desktop e gli altri programmi stanno fuori, ed e'
# per loro che la scelta automatica si ferma all'85% della scheda.

# I pesi in float16, in GB.
MODEL_WEIGHTS_GB: dict[str, float] = {
    "tiny": 0.08,
    "base": 0.15,
    "small": 0.5,
    "medium": 1.5,
    "large-v1": 3.1,
    "large-v2": 3.1,
    "large-v3": 3.1,
    "large": 3.1,
    "large-v3-turbo": 1.6,
    "turbo": 1.6,
    "distil-large-v3": 1.5,
}

# Quanto pesa un elemento del lotto rispetto a large. Il costo sta nel decoder (strati per
# larghezza): turbo e distil hanno l'encoder di large ma quattro e due strati di decoder.
MODEL_BATCH_SCALE: dict[str, float] = {
    "tiny": 0.06,
    "base": 0.12,
    "small": 0.32,
    "medium": 0.6,
    "large-v1": 1.0,
    "large-v2": 1.0,
    "large-v3": 1.0,
    "large": 1.0,
    "large-v3-turbo": 0.3,
    "turbo": 0.3,
    "distil-large-v3": 0.25,
}

# I pesi per tipo di calcolo, rispetto a float16. Le attivazioni restano in float16 con
# `int8_float16`; con `int8` e `float32` sulla scheda il resto del calcolo e' in float32.
COMPUTE_WEIGHT_FACTOR: dict[str, float] = {
    "float16": 1.0,
    "bfloat16": 1.0,
    "float32": 2.0,
    "int8_float16": 0.55,
    "int8_bfloat16": 0.55,
    "int8_float32": 0.55,
    "int8": 0.55,
}
COMPUTE_BATCH_FACTOR: dict[str, float] = {"float32": 2.0, "int8_float32": 2.0, "int8": 2.0}

# Misurati il 23/09 su una RTX 4070 Ti con nvidia-smi, dieci minuti di una lezione vera: il modello
# caricato (pesi, VAD, contesto CUDA) +4,0 GB; lotto 4 / 8 / 16 +1,3 / +2,4 / +5,0 GB sopra il modello,
# cioe' circa 0,3 GB a elemento; l'allineamento italiano fino a +1,7 GB sopra il modello, che stanno
# dentro il lotto quando il lotto e' grande. Le stime di prima (0,25 a elemento, 0,7 di contesto)
# stavano sotto di quasi un gigabyte con lotto 16.
BATCH_ITEM_GB = 0.32  # large-v3 float16, per elemento del lotto
# L'allineatore resta caricato fra un pezzo e l'altro: in una lezione vera da 41 minuti in tre pezzi,
# con la riserva di torch restituita dopo ogni allineamento, il companion ha preso 7,1 GB con il lotto
# da 7 contro 6,6 stimati con 0,4 qui (23/09). Da li' 0,9.
ALIGN_GB = 0.9
CONTEXT_GB = 0.9
# Quanto della VRAM che c'e' si da' al companion. Il resto e' per il desktop e per chi altro c'e'.
HEADROOM = 0.85
# Sotto questo lotto conviene un modello piu' leggero: large in int8 con lotto 9 fa lo stesso testo
# di large in float16 con lotto 3 (la differenza fra i due non si sente), e lo fa prima.
MIN_USEFUL_BATCH = 4
# I modelli verso cui si scende quando quello scelto non ci sta, dal piu' grande.
SMALLER_MODELS = ("medium", "small", "base", "tiny")
VRAM_MODES = ("auto", "manual")


def model_family(model: str) -> str:
    """
    La voce della tabella per un nome di modello.

    Un nome che non si conosce (un percorso, `Systran/faster-whisper-large-v3`) si riconosce da
    quello che contiene; se non contiene niente di noto vale come large, perche' una stima che
    sbaglia per eccesso toglie un po' di velocita', una che sbaglia per difetto la toglie tutta.
    """
    name = (model or "").strip().lower()
    if name in MODEL_WEIGHTS_GB:
        return name
    base = name.rsplit("/", 1)[-1].removeprefix("faster-whisper-").removeprefix("faster-")
    if base in MODEL_WEIGHTS_GB:
        return base
    for key in ("turbo", "distil", "large", "medium", "small", "base", "tiny"):
        if key in base:
            return {"turbo": "large-v3-turbo", "distil": "distil-large-v3", "large": "large-v3"}.get(key, key)
    return "large-v3"


def vram_breakdown(model: str, compute_type: str, batch_size: int, align: bool = True) -> dict[str, float]:
    """La stima divisa nelle sue voci, in GB. Per l'app, che puo' dire dove va la memoria."""
    return {key: round(value, 2) for key, value in _vram_parts(model, compute_type, batch_size, align).items()}


def _vram_parts(model: str, compute_type: str, batch_size: int, align: bool = True) -> dict[str, float]:
    """[vram_breakdown] senza arrotondare: i conti del piano si fanno su questi."""
    family = model_family(model)
    compute = compute_type or "float16"
    weights = MODEL_WEIGHTS_GB[family] * COMPUTE_WEIGHT_FACTOR.get(compute, 1.0)
    per_item = BATCH_ITEM_GB * MODEL_BATCH_SCALE[family] * COMPUTE_BATCH_FACTOR.get(compute, 1.0)
    batch = per_item * max(0, int(batch_size))
    parts = {
        "weights": weights,
        "batch": batch,
        "align": ALIGN_GB if align else 0.0,
        "context": CONTEXT_GB,
    }
    parts["total"] = sum(parts.values())
    parts["per_batch_item"] = per_item
    return parts


def estimate_vram_gb(model: str, compute_type: str, batch_size: int, align: bool = True) -> float:
    """Quanta VRAM chiede il companion con queste impostazioni, in GB. Una stima: vedi sopra."""
    return vram_breakdown(model, compute_type, batch_size, align)["total"]


def downgrade_chain(model: str, compute_type: str) -> list[tuple[str, str]]:
    """
    Le combinazioni da provare, dalla scelta dell'utente in giu'.

    Prima si toglie precisione ai pesi (float16 → int8_float16: meta' memoria, lo stesso testo),
    poi si scende di modello. Mai in su: chi ha scelto medium l'ha scelto.
    """
    compute = compute_type or "float16"
    chain = [(model, compute)]
    if not compute.startswith("int8"):
        chain.append((model, "int8_float16"))
    weight = MODEL_WEIGHTS_GB[model_family(model)]
    for smaller in SMALLER_MODELS:
        if MODEL_WEIGHTS_GB[smaller] < weight:
            chain.append((smaller, "int8_float16"))
    return chain


def plan_vram(model: str, compute_type: str, batch_max: int, budget_gb: float) -> dict[str, Any]:
    """
    Modello, calcolo e lotto che stanno nell'85% di `budget_gb`, senza superare `batch_max`.

    Si tiene la prima combinazione della catena ([downgrade_chain]) che ci sta con un lotto di
    almeno [MIN_USEFUL_BATCH]; se nessuna arriva a tanto, la prima che ci sta con qualunque lotto;
    se non ci sta niente, l'ultima con lotto 1 e `fits` falso — a quel punto decide il ripiego sul
    processore di [run_job]. Pura: la chiamano [configure], l'anteprima dell'app e le prove.
    """
    compute = compute_type or "float16"
    batch_max = max(1, int(batch_max))
    usable = float(budget_gb) * HEADROOM
    chain = downgrade_chain(model, compute)
    want = min(MIN_USEFUL_BATCH, batch_max)
    chosen: tuple[str, str, int] | None = None
    fallback: tuple[str, str, int] | None = None
    for candidate, kind in chain:
        parts = _vram_parts(candidate, kind, 0)
        room = (usable - parts["total"]) / parts["per_batch_item"]
        # Il piccolo epsilon: 10.2 - 4.2 diviso 0.25 non deve fare 23.999999.
        batch = min(batch_max, math.floor(room + 1e-9))
        if batch >= want:
            chosen = (candidate, kind, batch)
            break
        if batch >= 1 and fallback is None:
            fallback = (candidate, kind, batch)
    fits = True
    if chosen is None:
        chosen = fallback
    if chosen is None:
        fits = False
        chosen = (chain[-1][0], chain[-1][1], 1)
    name, kind, batch = chosen
    breakdown = vram_breakdown(name, kind, batch)
    return {
        "budget_gb": round(float(budget_gb), 1),
        "usable_gb": round(usable, 1),
        "estimate_gb": breakdown["total"],
        "batch_size": batch,
        "model": name,
        "compute_type": kind,
        "fits": fits,
        "downgraded": (name, kind) != (model, compute),
        "requested": {"model": model, "compute_type": compute, "batch_size_max": batch_max},
        "breakdown": breakdown,
    }


_NVSMI: dict[str, Any] = {"at": -1e9, "value": None}
# Una domanda al driver alla volta: dieci `/health` arrivati insieme a cache scaduta lanciavano dieci
# `nvidia-smi`. Cosi' il primo chiede e gli altri leggono la sua risposta.
_NVSMI_LOCK = threading.Lock()

# Quanto puo' essere vecchia la misura della scheda che `/health` riporta. `/health` e' aperto a
# tutti e l'app lo chiede spesso (la sonda, il «Prova», ogni lavoro): con una misura fresca a ogni
# chiamata chiunque sulla rete faceva girare `nvidia-smi` a comando, e senza il driver il ripiego su
# torch apriva un contesto CUDA — proprio quello che all'accensione del PC resta convinto che la
# scheda sia piena (vedi [nvidia_query]). Qualche secondo di ritardo nel numero non lo vede nessuno.
HEALTH_DRIVER_AGE_S = 5.0


def nvidia_query(max_age_s: float = 2.0) -> dict[str, Any] | None:
    """
    Nome, memoria totale, occupata e libera della scheda, chiesti al driver con `nvidia-smi`.

    Non a torch, per due motivi. Torch per rispondere apre un contesto CUDA, e un contesto aperto
    all'accensione del PC — il companion parte appena si entra in Windows, col driver della scheda
    forse non ancora pronto — restava convinto che la scheda fosse piena: `mem_get_info` diceva 0 GB
    liberi con 10,8 liberi davvero, il modello «non entrava» e ogni lezione finiva sul processore
    (23/09, dopo un riavvio). E il driver vede tutti i processi, che e' quello che serve per sapere
    quanto lasciano libero gli altri. Tenuto per [max_age_s]: `/health` lo chiede spesso.
    """
    with _NVSMI_LOCK:
        return _nvidia_query_locked(max_age_s)


def _nvidia_query_locked(max_age_s: float) -> dict[str, Any] | None:
    now = time.monotonic()
    if now - _NVSMI["at"] < max_age_s:
        return _NVSMI["value"]
    value: dict[str, Any] | None = None
    exe = shutil.which("nvidia-smi")
    if exe:
        try:
            out = subprocess.run(
                [exe, "--query-gpu=name,memory.total,memory.used,memory.free", "--format=csv,noheader,nounits"],
                capture_output=True, text=True, timeout=5,
                creationflags=0x08000000 if sys.platform == "win32" else 0,  # CREATE_NO_WINDOW
            )
            name, total, used, free = (part.strip() for part in out.stdout.strip().splitlines()[0].split(","))
            value = {
                "name": name,
                "total_gb": round(float(total) / 1024, 1),
                "used_gb": float(used) / 1024,
                "free_gb": float(free) / 1024,
            }
        except Exception:  # noqa: BLE001 — senza driver che risponde si torna a torch
            value = None
    _NVSMI["at"], _NVSMI["value"] = now, value
    return value


def detect_gpu() -> dict[str, Any] | None:
    """Nome e memoria totale della scheda, o None se non ce n'e' una. Dal driver, se c'e'."""
    driver = nvidia_query(max_age_s=0)
    if driver is not None:
        return {"name": driver["name"], "total_gb": driver["total_gb"]}
    try:
        import torch

        if not torch.cuda.is_available():
            return None
        props = torch.cuda.get_device_properties(0)
        return {"name": props.name, "total_gb": round(props.total_memory / 1024**3, 1)}
    except Exception:  # noqa: BLE001 — una scheda che non si lascia leggere e' una scheda che non c'e'
        return None


def gpu_status() -> dict[str, Any] | None:
    """La scheda vista da /health: nome, totale e quanta ne resta libera adesso (tutti i processi)."""
    gpu = STATE.get("gpu")
    if STATE["device"] != "cuda" or not gpu:
        return None
    driver = nvidia_query(max_age_s=HEALTH_DRIVER_AGE_S)
    free = round(driver["free_gb"], 1) if driver is not None else None
    return {"name": gpu["name"], "total_gb": gpu["total_gb"], "free_gb": free}


def decide_vram(tunables: dict[str, Any], device: str, gpu: dict[str, Any] | None) -> dict[str, Any]:
    """
    Dalle impostazioni a quello che si usa davvero: il `vram` di /health.

    `auto`: il budget e' la memoria totale della scheda. `manual`: e' `vram_gb`, «la VRAM che ho» —
    per chi divide la scheda con altro e vuole lasciarne una parte, o per chi vuole provare il conto
    senza fidarsi di torch. Sul processore non c'e' niente da decidere: il modello e' quello scelto,
    e il lotto pure. Una scheda che torch non sa misurare in `auto` tiene le impostazioni com'erano.
    """
    mode = tunables["vram_mode"] if tunables.get("vram_mode") in VRAM_MODES else "auto"
    model = tunables["model"]
    compute = tunables.get("compute_type") or ""
    batch_max = max(1, int(tunables["batch_size_max"]))
    if device != "cuda":
        return {
            "mode": mode,
            "device": device,
            "budget_gb": None,
            "usable_gb": None,
            "estimate_gb": None,
            "batch_size": batch_max,
            "model": model,
            "compute_type": compute or "int8",
            "fits": True,
            "downgraded": False,
            "requested": {"model": model, "compute_type": compute or "int8", "batch_size_max": batch_max},
            "breakdown": None,
        }
    budget: float | None = None
    others: float | None = None
    if mode == "manual" and tunables.get("vram_gb"):
        budget = float(tunables["vram_gb"])
    elif gpu:
        # In automatico il budget e' quello che resta dopo gli altri — il desktop, il browser, un
        # gioco — non il totale della scheda. Contare 12 GB quando Windows ne tiene gia' due o tre
        # faceva scegliere il lotto 16 e mandava quattro gigabyte nella memoria condivisa, dove
        # tutto va sei volte piu' lento (23/09). Si rimisura a ogni lezione ([replan_for_job]).
        others = others_gb()
        budget = max(0.0, float(gpu["total_gb"]) - (others or 0.0))
    if budget is None:
        breakdown = vram_breakdown(model, compute, batch_max)
        plan = {
            "budget_gb": None,
            "usable_gb": None,
            "estimate_gb": breakdown["total"],
            "batch_size": batch_max,
            "model": model,
            "compute_type": compute or "float16",
            "fits": True,
            "downgraded": False,
            "requested": {"model": model, "compute_type": compute or "float16", "batch_size_max": batch_max},
            "breakdown": breakdown,
        }
    else:
        plan = plan_vram(model, compute, batch_max, budget)
    if others is not None:
        plan["others_gb"] = round(others, 1)
    return {"mode": mode, "device": device, **plan}


def others_gb() -> float | None:
    """
    Quanta VRAM occupano gli altri programmi: tutta quella occupata meno la nostra.

    La nostra si misura quando i modelli si caricano: quello grande in [ensure_model], l'allineatore
    in [align_model_for]. A modello scaricato e' zero. None se il driver non risponde: allora si
    conta sul totale, come prima.

    L'allineatore conta anche lui. Prima si misurava solo il modello grande, e dalla seconda lezione
    l'allineatore rimasto in memoria finiva fra «gli altri» — e il piano, che l'allineamento lo
    aggiunge gia' da se' ([ALIGN_GB]), lo pagava due volte: un gigabyte di lotto in meno a ogni
    lezione, per niente.
    """
    driver = nvidia_query()
    if driver is None:
        return None
    mine = STATE.get("own_gb", 0.0) if STATE.get("model") is not None else 0.0
    if STATE.get("align"):
        mine += STATE.get("align_gb", 0.0)
    return max(0.0, driver["used_gb"] - mine)


def replan_for_job() -> None:
    """
    Prima di ogni lezione, in automatico: il lotto giusto per la VRAM libera *adesso*.

    Il piano fatto all'avvio valeva per la scheda di allora; se nel frattempo si e' aperto un gioco,
    il lotto di prima non ci sta piu'. Cambia solo se deve: un modello diverso lo ricarica
    [ensure_model], un lotto diverso vale dalla prossima finestra.
    """
    if STATE["device"] != "cuda" or STATE["tunables"].get("vram_mode") == "manual" or not STATE.get("gpu"):
        return
    plan = decide_vram(STATE["tunables"], STATE["device"], STATE["gpu"])
    before = (STATE["name"], STATE["compute_type"], STATE["batch_size"])
    apply_plan(plan)
    if (plan["model"], plan["compute_type"], plan["batch_size"]) != before:
        log.info("per questa lezione: %s (altri programmi: %.1f GB)", describe_plan(plan), plan.get("others_gb") or 0.0)


def describe_plan(plan: dict[str, Any]) -> str:
    """Una riga per il registro: cosa si e' deciso e perche'."""
    if plan["device"] != "cuda":
        return f"sul processore: {plan['model']} {plan['compute_type']}, lotto {plan['batch_size']}"
    budget = f"{plan['budget_gb']:.1f} GB ({plan['mode']})" if plan["budget_gb"] is not None else "scheda non misurata"
    line = f"VRAM {budget}: stima {plan['estimate_gb']:.1f} GB con {plan['model']} {plan['compute_type']}, lotto {plan['batch_size']}"
    asked = plan["requested"]
    if plan["downgraded"]:
        line += f" (chiesto {asked['model']} {asked['compute_type']}: non ci stava)"
    elif plan["batch_size"] < asked["batch_size_max"]:
        line += f" (massimo {asked['batch_size_max']})"
    if not plan["fits"]:
        line += " — non ci sta niente: decidera' il ripiego sul processore"
    return line


def apply_plan(plan: dict[str, Any]) -> None:
    """Il piano diventa quello che il prossimo lavoro usa. Il modello caricato lo guarda [ensure_model]."""
    STATE["vram"] = plan
    STATE["name"] = plan["model"]
    STATE["compute_type"] = plan["compute_type"]
    STATE["batch_size"] = plan["batch_size"]


# Il VAD di WhisperX (pyannote) decide quali tratti vanno a Whisper. Coi valori di serie (0,5 per
# aprire, 0,363 per chiudere) passava anche il rumore di fondo di una registrazione lasciata accesa:
# «Napoli 18h», venti ore di cui dieci di stanza vuota, tornava con le ore 0–3 e 13–18 quasi tutte
# inventate. Un po' piu' severo per aprire e per restare aperto: un tratto di voce vera supera 0,6
# senza fatica, un fruscio no. Misurato su tre ore di quel file: nelle due di stanza vuota i tratti
# mandati a Whisper scendono da 41 a 19 e da 23 a 8; in quella in cui si parla le parole restano le
# stesse (1231 contro 1233). Le allucinazioni che passano lo stesso le toglie [drop_hallucinations].
VAD_OPTIONS = {"vad_onset": 0.6, "vad_offset": 0.45}
# Le opzioni di decodifica della pipeline a lotti (`generate_segment_batched` le passa a ctranslate2).
# Vuote di proposito. `repetition_penalty` 1,1 con `no_repeat_ngram_size` 3 toglie i giri a vuoto, ma
# vieta al modello di ripetere tre parole in trenta secondi anche quando le ripete chi parla: sull'ora
# vera di «Napoli 18h» «vorrei fare festa… ha detto vorrei fare festa» diventava «vuoi rifare festa…
# vuol fa festa», «la mia gatta» «la mia gatto»; la sola `repetition_penalty` perdeva il 6% delle
# parole e un «nemmeno» che rovesciava la frase. I giri li accorcia [drop_hallucinations], dopo.
ASR_OPTIONS: dict[str, Any] = {}


def ensure_model(name: str | None = None, compute_type: str | None = None) -> None:
    """
    Carica il modello se non c'e'. Chiamato dalla richiesta, non dall'avvio.

    Caricarlo all'avvio sembrava giusto — la prima lezione non paga l'attesa — ma costava due cose
    che si pagano ogni giorno: quattro minuti in cui il server non risponde nemmeno a `/health`
    (e dall'app si legge come «server non raggiungibile», che manda a cercare il problema dalla
    parte sbagliata), e qualche gigabyte di VRAM tenuti occupati anche quando il computer sta
    facendo altro. Ora l'attesa la paga la prima trascrizione, che tanto e' gia' un'attesa.

    Se nel frattempo le impostazioni sono cambiate (un altro modello da /v1/admin/settings, arrivato
    mentre si trascriveva), quello in memoria se ne va e si carica quello giusto. Qui e non nella
    richiesta che cambia le impostazioni perche' questa gira dentro il turno della fila: il modello
    non sparisce mai sotto una lezione a meta'.

    `name` e `compute_type`, se ci sono, sono quelli fissati all'inizio del lavoro ([Engine]): una
    lezione a pezzi chiama questa funzione una volta per pezzo, e le impostazioni cambiate dall'app
    fra un pezzo e l'altro non devono far ricaricare un altro modello a meta' lezione.
    """
    name = name or STATE["name"]
    compute_type = compute_type or STATE["compute_type"]
    wanted = (name, STATE["device"], compute_type)
    if STATE["model"] is not None:
        if STATE["loaded_as"] in (None, wanted):
            return
        unload_model("impostazioni cambiate")
    import whisperx

    started = time.time()
    before_gb = vram_gb()
    log.info("carico %s su %s (%s)...", name, STATE["device"], compute_type)
    STATE["model"] = whisperx.load_model(
        name,
        device=STATE["device"],
        compute_type=compute_type,
        vad_options=dict(VAD_OPTIONS),
        asr_options=dict(ASR_OPTIONS),
    )
    STATE["loaded_as"] = wanted
    # Quanto e' nostro, per sapere poi quanto e' degli altri ([others_gb]).
    STATE["own_gb"] = max(0.0, vram_gb() - before_gb)
    STATE["last_load_s"] = time.time() - started
    STATE["loads"] = STATE.get("loads", 0) + 1
    log.info("pronto in %.0f s (%.1f GB di VRAM)", time.time() - started, vram_gb())


def warm_imports() -> None:
    """
    Importa WhisperX e i suoi pezzi all'avvio, su un thread suo, invece che dentro la prima lezione.

    All'import pyannote prova torchcodec, fallisce, e tiene da parte l'errore col suo traceback
    («fix torchcodec installation»). Se l'import avveniva dentro [run_job] — la prima lezione dopo
    l'avvio — quel traceback teneva in vita i frame fino a `run_job`, e con loro il modello: scaricarlo
    lasciava 2–4 GB sulla scheda (23/09), e il budget automatico li contava come «altri programmi».
    Importati qui, il traceback porta solo a questo thread, che non tiene niente.
    """

    def run() -> None:
        with contextlib.suppress(Exception):
            import whisperx  # noqa: F401
            import whisperx.alignment  # noqa: F401
            import whisperx.asr  # noqa: F401
        with contextlib.suppress(Exception):
            import whisperx.vads.pyannote  # noqa: F401

    threading.Thread(target=run, name="warm-imports", daemon=True).start()


# Il riavvio da se' ([restart_when_idle]). Ogni quanto si guarda se le richieste a meta' sono finite;
# quanto al massimo si aspetta che finiscano — una lezione gia' in caricamento quando si e' deciso
# puo' durare ore, ma una connessione rimasta appesa non deve tenere il computer in «restarting» per
# sempre, rifiutando tutto; e quanto si lascia a uvicorn per chiudersi prima di staccare la spina.
RESTART_POLL_S = 0.5
RESTART_DRAIN_MAX_S = 3 * 3600
RESTART_SHUTDOWN_S = 30.0


def restart_when_idle() -> None:
    """
    Riparte con un processo nuovo, se lo si e' chiesto ([run_job]) e nessuno aspetta.

    Da qui in poi le richieste nuove si sentono dire `503 restarting` ([AuthGate]), e il riavvio
    vero lo fa [_drain_and_restart] su un thread suo, quando anche l'ultima richiesta a meta' ha
    finito ([REQUESTS]). Prima si usciva tre secondi dopo la lezione che l'aveva chiesto, a orologio:
    la sua risposta — un JSON grosso verso un telefono sotto rete mobile — poteva non essere ancora
    arrivata, e un altro telefono che stava caricando una lezione, o un file nell'archivio, veniva
    tagliato a meta'.
    """
    if not STATE.get("restart_wanted") or STATE.get("restarting") or STATE.get("busy") or GATE.waiting:
        return
    launcher = Path(__file__).with_name("avvio.pyw")
    if not launcher.exists():
        STATE["restart_wanted"] = False
        log.warning("vorrei ripartire ma avvio.pyw non c'e': riavvia il companion a mano")
        return
    STATE["restart_wanted"] = False
    STATE["restarting"] = True
    log.warning("riparto con un processo nuovo, per ritrovare la scheda: aspetto le richieste a meta'")
    threading.Thread(target=_drain_and_restart, args=(launcher,), daemon=True, name="riavvio").start()


def idle_now() -> bool:
    """Niente in corso: ne' una trascrizione, ne' qualcuno in fila, ne' una richiesta a meta'."""
    return not STATE.get("busy") and GATE.waiting == 0 and REQUESTS.count == 0


def _drain_and_restart(launcher: Path) -> None:
    """
    Aspetta che il server sia libero ([idle_now]), poi lancia `avvio.pyw --dopo` e chiude.

    Il lanciatore parte solo adesso, non all'inizio dell'attesa: aspetta un minuto che questo processo
    lasci la porta, e un'attesa di mezz'ora dietro a un caricamento lo farebbe arrivare quando la
    porta e' ancora presa — l'icona nuova troverebbe il server vecchio, e se ne andrebbe.

    La chiusura e' quella di uvicorn ([SERVER] `should_exit`): smette di ascoltare, lascia finire le
    connessioni e fa girare [lifespan], che restituisce la scheda. `os._exit` resta come ultima
    strada: se il server non e' registrato, o non si chiude in [RESTART_SHUTDOWN_S], o se l'icona
    tiene in piedi il processo dopo che il server e' uscito.
    """
    deadline = time.monotonic() + RESTART_DRAIN_MAX_S
    while not idle_now():
        if time.monotonic() > deadline:
            log.warning("dopo %d minuti c'e' ancora qualcosa a meta': riparto lo stesso", RESTART_DRAIN_MAX_S // 60)
            break
        time.sleep(RESTART_POLL_S)
    log.warning("nessuna richiesta a meta': riparto")
    _spawn_launcher(launcher)
    server = SERVER
    if server is not None:
        server.should_exit = True
        if not STOPPED.wait(RESTART_SHUTDOWN_S):
            log.warning("il server non si e' chiuso in %.0f s: chiudo il processo", RESTART_SHUTDOWN_S)
    hook = ON_EXIT
    if hook is not None:
        with contextlib.suppress(Exception):
            hook()
        # All'icona un attimo per togliersi dall'area di notifica.
        time.sleep(1.0)
    _exit_process()


def _spawn_launcher(launcher: Path) -> None:
    runner = Path(sys.executable).with_name("pythonw.exe")
    subprocess.Popen(
        [str(runner if runner.exists() else sys.executable), str(launcher), "--dopo"],
        cwd=str(launcher.parent),
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        close_fds=True,
        creationflags=0x208 if sys.platform == "win32" else 0,
    )


def _exit_process() -> None:
    logging.shutdown()
    os._exit(0)


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
    STATE["loaded_as"] = None
    STATE["own_gb"] = 0.0
    # Anche l'allineatore sta sulla scheda.
    STATE["align"].clear()
    STATE["align_gb"] = 0.0
    gc.collect()
    empty_cuda_cache(ipc=True)
    log.info("modello scaricato (%s): da %.1f a %.1f GB occupati sulla scheda", reason, before, vram_gb())


def empty_cuda_cache(ipc: bool = False) -> None:
    """Restituisce alla scheda la riserva di torch: senza, quello che si e' mollato resta occupato."""
    if STATE["device"] != "cuda":
        return
    with contextlib.suppress(Exception):
        import torch

        torch.cuda.empty_cache()
        if ipc:
            torch.cuda.ipc_collect()


async def idle_watch() -> None:
    """
    Guarda l'orologio e sfratta il modello quando non serve piu' a nessuno.

    Gira dentro il server, ogni mezzo minuto. Prende lo stesso lucchetto delle trascrizioni, cosi'
    non puo' capitare che il modello sparisca a meta' di una lezione.
    """
    while True:
        await asyncio.sleep(30)
        # Un riavvio chiesto mentre qualcuno era in fila, e quel qualcuno poi ha rinunciato: nessuna
        # lezione finira' per ricordarsene, e allora se ne ricorda l'orologio.
        restart_when_idle()
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


async def preload_now() -> None:
    """
    Carica il modello prima che serva (`preload` in config.json), prendendo il turno nella fila.

    L'icona lo caricava su un thread suo, fuori dalla fila: una lezione arrivata durante quei minuti
    trovava `model` ancora vuoto e ne caricava un secondo, e la scheda si prendeva il doppio della
    memoria — oltre il limite, nella RAM condivisa o in un errore. Col turno la lezione aspetta, e
    trova il modello pronto.
    """
    async with GATE.slot(0, key="preload"):
        if STATE["model"] is None:
            await asyncio.to_thread(ensure_model)
            STATE["last_used"] = time.time()


def preload_when_ready(timeout_s: float = 120.0) -> bool:
    """
    [preload_now] da un altro thread, per l'icona: aspetta che il server sia partito ([LOOP]), poi
    consegna il caricamento al suo ciclo di eventi e ne aspetta la fine. Falso se non e' andata.
    """
    deadline = time.monotonic() + timeout_s
    while LOOP is None or LOOP.is_closed():
        if time.monotonic() > deadline:
            log.warning("il server non e' partito in tempo: il modello si carichera' alla prima lezione")
            return False
        time.sleep(0.2)
    try:
        asyncio.run_coroutine_threadsafe(preload_now(), LOOP).result()
        return True
    except Exception as error:  # noqa: BLE001 — un caricamento anticipato che fallisce non ferma niente
        log.warning("caricamento anticipato del modello non riuscito: %s", error)
        return False


@contextlib.asynccontextmanager
async def lifespan(_: FastAPI):
    global LOOP
    LOOP = asyncio.get_running_loop()
    STOPPED.clear()
    watcher = asyncio.create_task(idle_watch())
    try:
        yield
    finally:
        watcher.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await watcher
        unload_model("chiusura")
        LOOP = None
        STOPPED.set()


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

    Ce n'e' uno per lingua, e leggerlo dal disco ogni lezione costerebbe secondi: si tiene quello
    dell'ultima lingua, che e' quasi sempre anche quella della prossima. **Uno solo**: prima ne
    restava uno per ogni lingua incontrata, fino allo sfratto del modello grande, e una lezione in
    inglese in mezzo a quelle in italiano lasciava sulla scheda un gigabyte che il piano della VRAM
    non vedeva. Cambiando lingua il vecchio se ne va, e la sua riserva torna alla scheda, prima di
    caricare il nuovo. Quello sul processore del ripiego (vedi [run_job]) non si tiene: serve a una
    lezione sola, e la prossima torna sulla scheda.

    Quanto occupa sulla scheda si misura al caricamento (`align_gb`): e' nostro, non degli altri
    programmi ([others_gb]).
    """
    import whisperx

    device = device or STATE["device"]
    if device != STATE["device"]:
        trust_sentence_splitter(language)
        return whisperx.load_align_model(language_code=language, device=device)
    if language not in STATE["align"]:
        if STATE["align"]:
            log.info("lascio l'allineamento per '%s'", "', '".join(STATE["align"]))
            STATE["align"].clear()
            STATE["align_gb"] = 0.0
            gc.collect()
            empty_cuda_cache()
        log.info("carico l'allineamento per '%s'...", language)
        trust_sentence_splitter(language)
        before = vram_gb()
        model, metadata = whisperx.load_align_model(language_code=language, device=device)
        STATE["align"][language] = (model, metadata)
        STATE["align_gb"] = max(0.0, vram_gb() - before)
    return STATE["align"][language]


# * `by_ref`: `source_sha256` trascrive un file gia' nell'archivio, senza che il telefono lo mandi;
# * `archive_upload`: `archive=1` tiene nell'archivio il file mandato, invece di buttarlo dopo;
# * `server_chunks`: `max_minutes` divide qui una lezione lunga, invece che sul telefono;
# * `file_meta`: `GET /v1/files/<sha>/meta`, le date vere di un `.sdocx` o di una registrazione;
# * `prompt`: il campo `prompt` arriva davvero a Whisper (prima si accettava e si ignorava).
FEATURES = ("by_ref", "archive_upload", "server_chunks", "file_meta", "prompt", "auto_chunks")


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
        "version": config.version(),
        "instance": INSTANCE,
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
        # Dal driver, di qualche secondo fa, e mai da torch: vedi [HEALTH_DRIVER_AGE_S].
        "vram_gb": round(vram_gb(max_age_s=HEALTH_DRIVER_AGE_S, allow_torch=False), 1),
        "unload_in_s": max(0, int(STATE["idle_seconds"] - quiet)) if loaded and STATE["idle_seconds"] else None,
        # Quanti aspettano il loro turno, e se questo computer accetta ospiti.
        "queue": GATE.waiting,
        # Le richieste a meta' ([REQUESTS]): l'installer non ferma il server finche' non e' zero.
        "inflight": REQUESTS.count,
        # Sta per ripartire da se' ([restart_when_idle]): le richieste nuove hanno un 503.
        "restarting": bool(STATE.get("restarting")),
        "guests": bool(STATE["index_url"] and STATE["owner"]),
        # Come si entra: con l'account (biglietti verificati dal Worker) e/o senza credenziali.
        "auth": {
            "account": bool(STATE["index_url"] and STATE["owner"]),
            "anonymous": bool(STATE["accept_anonymous"]),
        },
        # La scheda (None sul processore) e quanta ne chiedono le impostazioni di adesso: vedi
        # [decide_vram]. `vram_gb` qui sopra e' quanta ne risulta occupata, da tutti; questa e' una
        # stima di quanta ne vuole il companion nel momento peggiore di una lezione.
        "gpu": gpu_status(),
        "vram": public_plan(STATE["vram"]),
        # Quello che questo companion sa fare in piu' della chiamata di OpenAI. L'app lo legge prima
        # di ogni lavoro: senza la lista (un companion vecchio) fa tutto da se', come prima.
        "features": list(FEATURES),
    }


def public_plan(plan: dict[str, Any] | None) -> dict[str, Any] | None:
    """Il piano senza la scomposizione, che serve alla pagina delle impostazioni e non a /health."""
    if plan is None:
        return None
    return {key: value for key, value in plan.items() if key != "breakdown"}


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
    # Finche' il computer non e' di nessuno, il link porta anche il codice per diventarlo: l'app che
    # e' entrata con Google lo usa dopo «Collega» (vedi binding.py). Non e' il token: vale dieci
    # minuti, una volta sola, e senza un biglietto dell'account non apre niente.
    unbound = not STATE["owner"]
    if unbound:
        link += "&bind=" + binding.CODES.current()
    intent = "intent://endpoint?" + link.split("?", 1)[1] + "#Intent;scheme=pampanotes;package=dev.pampa.pampanotes;end"
    lan = local_addresses(port)
    remote = tailscale_address(port)
    rows = f"<p><b>In casa:</b> {escape(lan[0] if lan else '?')}</p>"
    rows += f"<p><b>Fuori casa:</b> {escape(remote)}</p>" if remote else "<p><b>Fuori casa:</b> installa Tailscale sul computer</p>"
    if STATE["index_url"] and STATE["owner"]:
        rows += "<p><b>Accesso:</b> entra nell'app con l'account Google di questo computer.</p>"
    elif unbound:
        rows += "<p><b>Accesso:</b> se nell'app sei entrato con Google, toccando il bottone il computer diventa del tuo account.</p>"
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
OPEN_PATHS = frozenset({"/health", "/pair", *binding.OPEN_PATHS})
TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions"


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
        headers = Headers(scope=scope)
        authorization = headers.get("authorization", "")
        try:
            caller = await asyncio.to_thread(identify, authorization)
        except AuthError as error:
            response = JSONResponse({"detail": str(error)}, status_code=401, headers={"WWW-Authenticate": "Bearer"})
            await response(scope, receive, send)
            return
        # Da qui la richiesta conta fra quelle a meta' ([REQUESTS]) finche' la risposta non e' partita
        # tutta: `self.app` torna solo dopo l'ultimo pezzo del corpo. Il conto sale *prima* di
        # guardare `restarting`: cosi' il riavvio, che mette `restarting` e poi aspetta il conto a
        # zero, o vede questa richiesta, o questa vede lui — mai nessuno dei due.
        REQUESTS.enter()
        try:
            # Sta per ripartire ([restart_when_idle]): una lezione o un file accettati adesso
            # morirebbero a meta' caricamento. Meglio dirlo subito, prima del corpo, e far riprovare
            # fra poco, quando risponde il processo nuovo.
            if STATE.get("restarting"):
                response = JSONResponse({"detail": "restarting"}, status_code=503, headers={"Retry-After": "20"})
                await response(scope, receive, send)
                return
            state = scope.setdefault("state", {})
            state["caller"] = caller
            # Il lavoro si registra qui, con i soli header, e non nell'endpoint: FastAPI chiama
            # l'endpoint solo dopo aver letto tutto il multipart, e fino ad allora chi chiedeva a che
            # punto fosse si sarebbe sentito dire 404, cioe' «companion vecchio, smetti di chiedere».
            if scope.get("method") == "POST" and scope.get("path") == TRANSCRIPTIONS_PATH:
                job = JOBS.open(headers.get("x-pampa-job", ""), caller)
                if job is not None:
                    state["job"] = job
            await self.app(scope, receive, send)
        finally:
            REQUESTS.leave()


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


def _bound(owner: str, index_url: str) -> None:
    """Il computer e' appena diventato di un account (binding.py): da adesso i biglietti valgono."""
    STATE["owner"] = owner
    STATE["index_url"] = index_url
    GUEST_CACHE.clear()
    TICKET_CACHE.clear()


def _home_addresses() -> tuple[str | None, str | None]:
    lan = local_addresses(STATE["port"])
    return (lan[0] if lan else None), tailscale_address(STATE["port"])


def _pair_url() -> str:
    """L'indirizzo del QR, con una chiave nuova: quello che `/pair/start` mostra sullo schermo."""
    lan, _ = _home_addresses()
    base = lan or "http://localhost:%d" % STATE["port"]
    return f"{base}/pair?k={new_pairing_key()}"


app.include_router(
    binding.build_router(
        STATE,
        pair_url=_pair_url,
        addresses=_home_addresses,
        persist=lambda values: config.set_values(values, STATE["config_path"]),
        on_bound=_bound,
        user_agent=WORKER_USER_AGENT,
    )
)


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


# --- le impostazioni dall'app ------------------------------------------------------------------
#
# Il config.json si apre dal menu dell'icona, ma l'icona sta sul computer e le lezioni si guardano
# dal telefono: la pagina del computer nell'app legge e cambia da qui le sei impostazioni che
# contano per la VRAM e per il tempo in cui il modello resta in memoria. Solo il proprietario: un
# ospite che cambia il modello cambia il computer di un altro.

# Le chiavi che l'app puo' cambiare, e dove stanno in config.json. `batch_size_max` e' la vecchia
# `batch_size`: con la VRAM decisa dal companion e' diventata il tetto, non il valore.
TUNABLE_KEYS: dict[str, str] = {
    "model": "model",
    "compute_type": "compute_type",
    "vram_mode": "vram_mode",
    "vram_gb": "vram_gb",
    "batch_size_max": "batch_size",
    "idle_minutes": "idle_minutes",
}
BATCH_MAX_LIMIT = 64
IDLE_MAX_MINUTES = 24 * 60


def tunables_of(settings: dict[str, Any]) -> dict[str, Any]:
    """Dalle impostazioni di config.py alle sei chiavi che l'app vede."""
    return {public: settings.get(stored, config.DEFAULTS.get(stored)) for public, stored in TUNABLE_KEYS.items()}


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def validate_tunables(changes: Any, current: dict[str, Any]) -> dict[str, Any]:
    """
    Le modifiche chieste, controllate, sopra a quelle di adesso. [ValueError] col motivo se no.

    Un valore sbagliato non deve arrivare a config.json: il server lo rileggerebbe al prossimo avvio,
    e un modello che non esiste e' un computer che non trascrive piu' finche' qualcuno non apre il file.
    """
    if not isinstance(changes, dict):
        raise ValueError("serve un oggetto JSON")
    unknown = sorted(set(changes) - set(TUNABLE_KEYS))
    if unknown:
        raise ValueError(f"chiavi sconosciute: {', '.join(unknown)}")
    merged = dict(current)
    for key, value in changes.items():
        if key == "model":
            if not isinstance(value, str) or value not in MODEL_WEIGHTS_GB:
                raise ValueError(f"modello sconosciuto: {value!r} (vanno bene {', '.join(MODEL_WEIGHTS_GB)})")
        elif key == "compute_type":
            if not isinstance(value, str) or (value and value not in COMPUTE_WEIGHT_FACTOR):
                raise ValueError(f"compute_type sconosciuto: {value!r}")
        elif key == "vram_mode":
            if value not in VRAM_MODES:
                raise ValueError("vram_mode e' «auto» o «manual»")
        elif key == "vram_gb":
            if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or not 1 <= value <= 256):
                raise ValueError("vram_gb e' un numero fra 1 e 256, o null")
            value = None if value is None else round(float(value), 1)
        elif key == "batch_size_max":
            if not _is_int(value) or not 1 <= value <= BATCH_MAX_LIMIT:
                raise ValueError(f"batch_size_max e' un intero fra 1 e {BATCH_MAX_LIMIT}")
        elif key == "idle_minutes":
            if not _is_int(value) or not 0 <= value <= IDLE_MAX_MINUTES:
                raise ValueError(f"idle_minutes e' un intero fra 0 e {IDLE_MAX_MINUTES}")
        merged[key] = value
    if merged.get("vram_mode") == "manual" and not merged.get("vram_gb"):
        raise ValueError("con vram_mode «manual» serve vram_gb: quanta VRAM ha la scheda")
    return merged


def settings_answer(tunables: dict[str, Any], plan: dict[str, Any]) -> dict[str, Any]:
    """La stessa forma per le tre chiamate: cosa e' scelto, cosa si usa, la scheda, le scelte possibili."""
    return {
        "settings": tunables,
        "vram": plan,
        "gpu": gpu_status(),
        "device": STATE["device"],
        "models": list(MODEL_WEIGHTS_GB),
        "compute_types": ["", *COMPUTE_WEIGHT_FACTOR],
    }


async def _json_body(request: Request) -> Any:
    try:
        return await request.json()
    except ValueError as error:
        raise HTTPException(status_code=400, detail="il corpo non e' JSON") from error


@app.get("/v1/admin/settings")
def admin_settings(request: Request) -> dict[str, Any]:
    """Le impostazioni di adesso e la stima della VRAM che ne viene."""
    require_owner(request)
    return settings_answer(dict(STATE["tunables"]), STATE["vram"])


@app.post("/v1/admin/estimate")
async def admin_estimate(request: Request) -> dict[str, Any]:
    """
    La stima per impostazioni che non ci sono ancora, senza salvare niente.

    Serve all'anteprima: l'app sposta il cursore del lotto o sceglie un altro modello, e la pagina
    dice subito «stima 6,7 GB» — o «in auto diventa large-v3 int8, lotto 9» — prima che si tocchi
    «Salva».
    """
    require_owner(request)
    try:
        merged = validate_tunables(await _json_body(request), STATE["tunables"])
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    return settings_answer(merged, decide_vram(merged, STATE["device"], STATE["gpu"]))


@app.post("/v1/admin/settings")
async def admin_settings_update(request: Request) -> dict[str, Any]:
    """
    Cambia le impostazioni, le scrive in config.json e le mette in uso.

    Si scrivono solo le chiavi cambiate, con [config.set_value]: il resto del file resta com'e',
    comprese le righe scritte a mano. Se cambiano modello o calcolo il modello in memoria se ne va
    subito, se non sta trascrivendo; se sta trascrivendo finisce la lezione, e il prossimo lavoro
    carica quello nuovo ([ensure_model]).
    """
    require_owner(request)
    changes = await _json_body(request)
    try:
        merged = validate_tunables(changes, STATE["tunables"])
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    try:
        config.set_values({TUNABLE_KEYS[key]: merged[key] for key in changes}, STATE["config_path"])
    except OSError as error:
        raise HTTPException(status_code=500, detail=f"config.json non si scrive: {error}") from error
    STATE["tunables"] = merged
    STATE["idle_seconds"] = max(0, int(merged["idle_minutes"])) * 60
    before = (STATE["name"], STATE["compute_type"])
    plan = decide_vram(merged, STATE["device"], STATE["gpu"])
    apply_plan(plan)
    log.info("impostazioni cambiate dall'app: %s", describe_plan(plan))
    unloaded = False
    if (plan["model"], plan["compute_type"]) != before and STATE["model"] is not None:
        unloaded = await unload_now("impostazioni cambiate")
    answer = settings_answer(dict(merged), plan)
    answer["unloaded"] = unloaded
    return answer


def _truthy(value: str) -> bool:
    return value.strip().lower() in ("1", "true", "yes", "on")


def _positive_int(value: str) -> int | None:
    """`max_minutes` com'e' arrivato nel form: un intero positivo, o niente (vuoto, zero, sbagliato)."""
    try:
        number = int(value.strip())
    except (AttributeError, ValueError):
        return None
    return number if number > 0 else None


def _upload_chunks(handle: Any) -> Any:
    """Il file del multipart, gia' arrivato tutto (starlette lo tiene in un temporaneo), a blocchi."""
    handle.seek(0)
    while chunk := handle.read(1024 * 1024):
        yield chunk


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    request: Request,
    file: UploadFile | None = File(default=None),
    model: str = Form(default=""),
    language: str = Form(default=""),
    prompt: str = Form(default=""),
    temperature: float = Form(default=0.0),
    response_format: str = Form(default="json"),
    source_sha256: str = Form(default=""),
    # `archive` e' anche il nome del modulo: qui dentro si chiama in un altro modo.
    archive_flag: str = Form(default="", alias="archive"),
    name: str = Form(default=""),
    max_minutes: str = Form(default=""),
):
    """
    La chiamata vera. Multipart come OpenAI, risposta `verbose_json` con i segmenti.

    Il campo `model` si ignora di proposito: il modello e' quello scelto all'avvio, e cambiarlo per
    richiesta significherebbe rileggere qualche gigabyte di pesi nel mezzo di una lezione.

    **Il computer lavora, il telefono chiede.** Il telefono preparava l'audio da se': se la
    registrazione stava solo qui la scaricava per rimandarla indietro, e con un tetto ai pezzi
    decodificava tutta la lezione, la tagliava e ricodificava ogni pezzo — piu' tempo della
    trascrizione. Qui c'e' gia' tutto, l'archivio per impronta e ffmpeg, quindi:

      * `source_sha256`: se il file e' nell'archivio si trascrive da li', e `file` non serve. Senza
        il file e senza il blob: 404 `blob_missing`, e l'app lo manda;
      * `archive=1` con `file` e `source_sha256`: il file mandato entra nell'archivio (impronta
        verificata, come un `PUT`) e si trascrive da li'. Senza, resta un temporaneo che se ne va a
        fine lavoro — e' la strada di chi i file sul computer non li vuole;
      * `max_minutes`: la divisione in pezzi la fa il computer ([transcribe_audio]).

    Impronta e archivio sono **solo del proprietario**: a un ospite uno `sha256` direbbe se un file
    e' nell'archivio di un altro. Gli ospiti mandano il file, come sempre.
    """
    caller = caller_of(request)
    who = f"ospite {caller.name}: " if caller.kind == "guest" else ""
    # Registrato da [AuthGate] se l'app ha mandato `X-Pampa-Job`; altrimenti uno che non legge
    # nessuno, cosi' il resto del codice non ha un «se» a ogni passo.
    progress: JobProgress = getattr(request.state, "job", None) or JobProgress()
    # Il file da cui si trascrive, e — solo se e' un temporaneo — quello da cancellare dopo. Un blob
    # dell'archivio non si cancella mai: e' l'unica copia che il computer ha.
    source: Path | None = None
    target: Path | None = None
    # Prima del `try`: il `finally` la guarda per sapere se il temporaneo lo sta ancora leggendo un
    # lavoro. Assegnata solo piu' giu', un errore nel frattempo (il telefono che cade mentre si copia
    # l'audio) diventava un `UnboundLocalError` nel `finally`, e il temporaneo restava in %TEMP%.
    work: SharedWork | None = None
    origin = "upload"
    archived = False
    sha = source_sha256.strip().lower()
    keep = _truthy(archive_flag)
    # Il `try` comincia prima del file temporaneo, non dopo: una richiesta annullata mentre si
    # copiava l'audio, o mentre aspettava il suo turno, lasciava il file in %TEMP% per sempre.
    try:
        if (sha or keep) and caller.kind != "owner":
            raise HTTPException(status_code=403, detail="owner_only")
        if sha and not archive.SHA256.match(sha):
            raise HTTPException(status_code=400, detail="non e' uno sha256")
        store = archive.ARCHIVE
        record = store.get(sha) if sha and store is not None else None
        label = name.strip() or (file.filename if file is not None else "") or "audio"

        if record is not None:
            source, origin, archived = record["path"], "archive", True
            label = name.strip() or record["name"]
        elif file is None:
            raise HTTPException(status_code=404 if sha else 400, detail="blob_missing" if sha else "file_missing")
        elif keep and sha and store is not None:
            mime = (file.content_type or "application/octet-stream").split(";")[0].strip()
            try:
                stored = await archive._run_blocking(store.store, sha, label, mime, _upload_chunks(file.file), file.size)
            except ValueError as error:
                raise HTTPException(status_code=400, detail="sha_mismatch") from error
            source, archived = stored["path"], True
        else:
            suffix = Path(label).suffix or Path(file.filename or "").suffix or ".m4a"
            # Su disco e non in memoria: qui arrivano file da un'ora.
            with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
                target = Path(tmp.name)
                while chunk := await file.read(1024 * 1024):
                    tmp.write(chunk)
            source = target

        size_mb = source.stat().st_size / (1024 * 1024)
        how = "dall'archivio" if origin == "archive" else ("ricevuto e archiviato" if archived else "ricevuto")
        log.info("%s%s %s (%.1f MB)%s", who, how, label, size_mb, f", {GATE.waiting} in fila" if GATE.waiting else "")
        cap: int | str | None = "auto" if max_minutes.strip().lower() == "auto" else _positive_int(max_minutes)
        vocabulary = prompt.strip() or None
        lang = language.strip() or None

        # La stessa registrazione, con le stesse richieste, gia' in corso per qualcun altro — il tablet
        # che non sapeva che il telefono l'aveva mandata, o il telefono che la rimanda dopo aver perso
        # la risposta: ci si aggancia a quella. Il computer la fa una volta, e la danno a tutti e due.
        key = (sha, lang or "", vocabulary or "", cap or 0) if sha and archived else None
        # Annullata mentre il file arrivava (`DELETE /v1/jobs/{id}` o il telefono che se ne va): il
        # lavoro non si comincia, e non ci si aggancia a quello di un altro. Prima lo si scopriva solo
        # un secondo dopo, in [_await_work], con il lavoro gia' creato e magari gia' in decodifica.
        if not progress.cancelled:
            with contextlib.suppress(Exception):
                if await request.is_disconnected():
                    progress.cancel()
        if progress.cancelled:
            log.info("%sannullata durante il caricamento: %s", who, label)
            progress.set("failed", detail="annullata")
            raise HTTPException(status_code=499, detail="annullata")
        work = INFLIGHT.get(key) if key is not None else None
        if work is not None and not work.task.done() and not work.progress.cancelled:
            log.info("%sla stessa registrazione e' gia' in corso: aspetto quella (%s)", who, label)
        else:
            work = SharedWork(key=key, progress=JobProgress("w" + secrets.token_hex(8)))
            work.task = asyncio.create_task(
                _run_work(work, source, lang, vocabulary, cap, 0 if caller.kind == "owner" else 1, who, label)
            )
            if key is not None:
                INFLIGHT[key] = work
                work.task.add_done_callback(lambda _task, k=key, w=work: INFLIGHT.pop(k, None) if INFLIGHT.get(k) is w else None)
        progress.follow(work.progress)
        try:
            shared = await _await_work(work, request, progress)
        except JobCancelled:
            log.info("%sannullata dal telefono: %s", who, label)
            progress.set("failed", detail="annullata")
            raise HTTPException(status_code=499, detail="annullata") from None
        except Exception as error:  # noqa: BLE001 — qualunque guasto deve tornare come 500 leggibile
            progress.set("failed", detail=str(error)[:300])
            raise HTTPException(status_code=500, detail=str(error)) from error
    except BaseException:
        # Finita prima di un esito — il caricamento interrotto, il server che si chiude: chi chiede
        # deve leggere che e' finita, non vederla ferma in fila per sempre.
        if progress.state not in ("done", "failed"):
            progress.set("failed", detail="interrotta")
        raise
    finally:
        if target is not None:
            # Il temporaneo si cancella quando il lavoro ha finito di leggerlo, non prima: una
            # richiesta annullata esce subito, ma ffmpeg potrebbe averlo ancora aperto.
            def drop(_task: object = None, path: Path = target) -> None:
                with contextlib.suppress(OSError):
                    path.unlink(missing_ok=True)

            if work is not None and work.task is not None and not work.task.done():
                work.task.add_done_callback(drop)
            else:
                drop()

    result = dict(shared)
    progress.set("done", 1.0)
    duration = result["segments"][-1]["end"] if result["segments"] else 0.0
    if caller.kind == "guest":
        asyncio.get_running_loop().run_in_executor(None, report_usage, caller.bearer, duration)

    # Quanto ha lavorato il computer (senza la fila) e quanto era lungo l'audio: l'app ne fa le
    # statistiche («un'ora di lezione in quattro minuti»). I nomi restano questi.
    result["processing_s"] = round(work.progress.processing_s(), 2)
    result["audio_s"] = round(float(result.get("audio_s") or duration), 2)
    # Se il file ora sta nell'archivio (c'era gia', o e' entrato adesso: l'app marca la parte come
    # archiviata), da dove si e' trascritto, e in quanti pezzi.
    result["archived"] = archived
    result["source"] = origin
    result["chunks"] = int(result.get("chunks") or 1)
    if response_format == "text":
        return PlainTextResponse(result["text"])
    return JSONResponse(result)


@dataclass
class SharedWork:
    """
    Una trascrizione che il computer sta facendo, e le richieste che la aspettano.

    Il lavoro vive per conto suo, non dentro la richiesta: una richiesta che si stacca (annullata,
    connessione caduta) non ferma chi aspetta la stessa registrazione. Si ferma quando non la aspetta
    piu' nessuno ([_await_work]).
    """

    key: tuple | None
    progress: JobProgress
    waiters: set = dataclasses.field(default_factory=set)
    task: Any = None
    # Ha avuto il suo turno nella fila, e da quel momento c'e' un thread che lavora. Un flag suo e non
    # lo stato «queued»: dopo il turno lo stato resta «queued» ancora per un po' — finche' il thread
    # non comincia a decodificare — e annullare il task in quella finestra liberava la fila mentre il
    # thread andava avanti, con la lezione dopo sopra (vedi [_await_work]).
    admitted: bool = False


# Le trascrizioni in corso per impronta, per agganciare le richieste uguali. Solo sul ciclo di eventi.
INFLIGHT: dict[tuple, SharedWork] = {}


async def _run_work(
    work: SharedWork, source: Path, language: str | None, prompt: str | None, cap: int | str | None,
    priority: int, who: str, label: str,
) -> dict[str, Any]:
    """La trascrizione vera: il turno nella fila, poi WhisperX su un thread."""
    progress = work.progress
    progress.set("queued")
    try:
        # Il proprietario passa davanti agli ospiti in attesa; nessuno interrompe chi sta gia' trascrivendo.
        async with GATE.slot(priority, key=progress.id):
            # Nessuna attesa fra il turno e queste righe: chi guarda `admitted` da [_await_work] non
            # puo' trovarlo falso con il thread gia' partito.
            work.admitted = True
            STATE["busy"] = True
            progress.admitted()
            started = time.time()
            loads = STATE.get("loads", 0)
            try:
                # Su un thread anche il caricamento del modello: cosi' `/health` continua a
                # rispondere durante i minuti del primo avvio, invece di far credere all'app che il
                # server sia morto.
                result = await asyncio.to_thread(_transcribe, str(source), language, progress, prompt=prompt, max_minutes=cap)
            except JobCancelled:
                log.info("%snessuno aspetta piu' %s: il computer si ferma", who, label)
                progress.set("failed", detail="annullata")
                raise
            except Exception as error:
                log.exception("trascrizione fallita")
                progress.set("failed", detail=str(error)[:300])
                raise
            finally:
                STATE["busy"] = False
                STATE["last_used"] = time.time()
                restart_when_idle()
    except asyncio.CancelledError:
        # Tolta dalla fila prima del suo turno: nessuno la aspettava piu'.
        if progress.state not in ("done", "failed"):
            progress.set("failed", detail="annullata")
        raise JobCancelled() from None
    progress.set("done", 1.0)
    elapsed = time.time() - started
    duration = result["segments"][-1]["end"] if result["segments"] else 0.0
    speed = duration / elapsed if elapsed > 0 else 0
    # La velocita' per la scelta automatica dei pezzi: senza il caricamento del modello, se c'e'
    # stato, che si paga una volta e non dice niente di quanto va veloce la trascrizione.
    work_s = elapsed - (STATE.get("last_load_s", 0.0) if STATE.get("loads", 0) != loads else 0.0)
    record_speed(result.get("device_used") or STATE["device"], float(result.get("audio_s") or duration), work_s)
    on_cpu = " sul processore" if result.get("device_used") == "cpu" and STATE["device"] != "cpu" else ""
    log.info("%sfatto%s: %.1f min in %.0f s (%.0f volte il tempo reale)", who, on_cpu, duration / 60, elapsed, speed)
    if STATE["idle_seconds"]:
        log.info("tengo il modello in memoria per %d minuti", STATE["idle_seconds"] // 60)
    return result


async def _await_work(work: SharedWork, request: Request, progress: JobProgress) -> dict[str, Any]:
    """
    Aspetta il lavoro condiviso, tenendo d'occhio chi lo ha chiesto: se il telefono chiude la
    connessione, o la annulla con `DELETE /v1/jobs/{id}`, questa richiesta si stacca. Prima «Annulla»
    chiudeva solo la connessione del telefono, e il computer andava avanti a trascrivere per nessuno
    (23/09). Se era l'ultima ad aspettarlo, il lavoro si ferma: in fila esce dalla fila, mentre
    trascrive si ferma al lotto dopo.

    Il task si annulla solo se il lavoro non ha ancora avuto il turno ([SharedWork] `admitted`).
    Dopo, c'e' un thread che lavora, e annullare il task avrebbe liberato la fila subito, con il
    thread ancora dentro la decodifica o il piano della VRAM: la lezione dopo partiva sopra, e la
    scheda se le prendeva tutte e due. Da li' in poi si passa dal flag ([JobProgress.cancel]), che il
    thread guarda a ogni passo, e la fila si libera quando il thread ha davvero finito.
    """
    token = object()
    work.waiters.add(token)
    try:
        while True:
            done, _ = await asyncio.wait({work.task}, timeout=1.0)
            if done:
                return work.task.result()
            if not progress.cancelled:
                with contextlib.suppress(Exception):
                    if await request.is_disconnected():
                        progress.cancel()
            if progress.cancelled:
                raise JobCancelled()
    finally:
        work.waiters.discard(token)
        if not work.task.done() and not work.waiters:
            work.progress.cancel()
            if not work.admitted:
                work.task.cancel()


@app.delete("/v1/jobs/{job_id}")
async def job_cancel(job_id: str, request: Request) -> dict[str, Any]:
    """
    «Annulla» dal telefono: la lezione si ferma qui, non solo sul telefono. Chi puo' vederla puo'
    annullarla — il proprietario tutte, un ospite le sue ([JobRegistry.visible_to]); per gli altri
    404, come per un id che non esiste.
    """
    caller = caller_of(request)
    job = JOBS.get(job_id)
    if job is None or not JOBS.visible_to(job, caller):
        raise HTTPException(status_code=404, detail="lavoro sconosciuto")
    if job.state not in ("done", "failed"):
        job.cancel()
    return {"cancelled": True, "state": job.current_state()}


@app.get("/v1/jobs/{job_id}")
async def job_status(job_id: str, request: Request) -> dict[str, Any]:
    """
    A che punto e' una trascrizione, per chi l'ha mandata (o per il proprietario del computer).

    Un ospite che chiede l'id di un altro riceve lo stesso 404 di un id che non esiste: dire
    «esiste ma non e' tuo» direbbe gia' troppo. Gira sul ciclo di eventi, lo stesso della fila,
    cosi' il posto in coda si legge senza lucchetti.
    """
    caller = caller_of(request)
    job = JOBS.get(job_id)
    if job is None or not JOBS.visible_to(job, caller):
        raise HTTPException(status_code=404, detail="lavoro sconosciuto")
    position = GATE.position(job.gate_key) if job.current_state() == "queued" else None
    return job.snapshot(position=position)


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

    Modello e calcolo si fissano quando l'Engine nasce, cioe' all'inizio del lavoro: se dall'app
    arrivano impostazioni nuove a meta' di una lezione a pezzi, il pezzo dopo non carica un altro
    modello. Valgono dalla lezione successiva, come quando la lezione e' intera.
    """

    def __init__(self, name: str | None = None, compute_type: str | None = None) -> None:
        self.name = name or STATE["name"]
        self.compute_type = compute_type or STATE["compute_type"]

    def main_model(self) -> Any:
        ensure_model(self.name, self.compute_type)
        return STATE["model"]

    def needs_load(self) -> bool:
        """[main_model] dovra' leggere i pesi dal disco? E' il «carico il modello» che l'app mostra."""
        wanted = (self.name, STATE["device"], self.compute_type)
        return STATE["model"] is None or STATE["loaded_as"] not in (None, wanted)

    def detect_language(self, audio: Any) -> str | None:
        """La lingua dei primi trenta secondi di [audio], o None se il modello non lo sa fare."""
        detect = getattr(self.main_model(), "detect_language", None)
        return detect(audio) if callable(detect) else None

    def cpu_model(self) -> Any:
        import whisperx

        log.warning("carico %s sul processore (int8) per questa lezione: sara' piu' lenta", self.name)
        return whisperx.load_model(
            self.name, device="cpu", compute_type="int8", vad_options=dict(VAD_OPTIONS), asr_options=dict(ASR_OPTIONS),
        )

    def align(
        self,
        segments: list[dict],
        language: str,
        audio: Any,
        device: str,
        progress_callback: Callable[[float], None] | None = None,
    ) -> list[dict]:
        import whisperx

        model, metadata = align_model_for(language, device)
        aligned = whisperx.align(
            segments, model, metadata, audio, device,
            return_char_alignments=False, progress_callback=progress_callback,
        )
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


@contextlib.contextmanager
def initial_prompt(model: Any, prompt: str | None):
    """
    Il «Vocabolario» dell'app come `initial_prompt` di Whisper, per una trascrizione sola.

    Il campo `prompt` arrivava e non lo leggeva nessuno. In WhisperX 3.8 il prompt non e' un
    argomento di `transcribe`: sta nelle `options` della pipeline (un dataclass di faster-whisper,
    `TranscriptionOptions`), fissate quando il modello si carica, e `generate_segment_batched` lo
    rilegge a ogni lotto. Quindi si sostituiscono le opzioni per la durata della chiamata e si
    rimettono com'erano dopo, anche se la chiamata fallisce: il modello resta in memoria per il
    prossimo, che non deve trovarsi il vocabolario di un altro. Senza lucchetti perche' la fila
    ([PriorityGate]) fa passare una trascrizione alla volta. Un modello senza `options` (quelli
    finti delle prove, o un WhisperX che le ha spostate) trascrive senza prompt, come prima.
    """
    options = getattr(model, "options", None)
    if not prompt or options is None or not dataclasses.is_dataclass(options) or not hasattr(options, "initial_prompt"):
        yield
        return
    model.options = dataclasses.replace(options, initial_prompt=prompt)
    try:
        yield
    finally:
        model.options = options


def run_job(
    audio: Any,
    language: str | None,
    engine: Engine,
    batch_size: int,
    device: str,
    progress: JobProgress | None = None,
    prompt: str | None = None,
) -> dict[str, Any]:
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

    Ogni passo lo dice a [progress] (vedi [JobProgress]): il caricamento solo come stato, la
    trascrizione e l'allineamento con i callback di WhisperX, che scattano una volta per segmento.
    Un ripiego ricomincia la sua barra da zero e lo dice in `detail`: tornare indietro e' meglio
    che restare fermi al 60% mentre la lezione riparte da capo sul processore.
    """
    progress = progress or JobProgress()
    device_used = device
    progress.device = device
    size = max(1, int(batch_size))
    transcription: dict[str, Any] | None = None

    try:
        if engine.needs_load():
            progress.set("loading_model")
        model = engine.main_model()
    except Exception as error:
        if device != "cuda" or not is_oom(error):
            raise
        engine.release()
        log.warning("il modello non entra nella scheda: questa lezione va sul processore (%s: %s)", type(error).__name__, error)
        # La scheda puo' essere piena davvero (un gioco) o sembrarlo solo a questo processo: il
        # driver dice quanta ce n'e' per tutti. Se basta, e' il contesto CUDA di questo processo che
        # non la vede — succede quando si apre all'accensione del PC — e l'unico rimedio e' un
        # processo nuovo: si riparte appena il computer e' libero ([restart_when_idle]).
        driver = nvidia_query(max_age_s=0)
        needed = estimate_vram_gb(getattr(engine, "name", STATE["name"]), getattr(engine, "compute_type", STATE["compute_type"]), 1)
        if driver is not None and driver["free_gb"] >= needed:
            STATE["restart_wanted"] = True
            log.warning(
                "la scheda ha %.1f GB liberi ma questo processo non li vede: mi riavvio appena ho finito",
                driver["free_gb"],
            )
        model = None

    while model is not None:
        try:
            progress.set("transcribing", detail=None if size == batch_size else f"batch {size}")
            with initial_prompt(model, prompt):
                transcription = model.transcribe(
                    audio, batch_size=size, language=language, progress_callback=progress.callback("transcribing"),
                )
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
        progress.device = "cpu"
        progress.set("loading_model", detail="cpu")
        cpu = engine.cpu_model()
        try:
            progress.set("transcribing", detail="cpu")
            with initial_prompt(cpu, prompt):
                transcription = cpu.transcribe(
                    audio, batch_size=min(size, CPU_BATCH_SIZE), language=language,
                    progress_callback=progress.callback("transcribing"),
                )
        finally:
            del cpu
            engine.release()

    detected = transcription.get("language") or language or "en"
    segments = transcription.get("segments", [])

    alignment = "ok"
    progress.check_cancelled()
    try:
        align_device = device_used
        try:
            progress.set("aligning")
            segments = engine.align(segments, detected, audio, align_device, progress_callback=progress.callback("aligning"))
            # Quello che l'allineamento ha usato resta nella riserva di torch, e il pezzo dopo lo
            # trascrive ctranslate2, che quella riserva non la vede: due gigabyte tenuti per niente
            # sotto la trascrizione (23/09, 10,6 GB di picco con il lotto da 7). Si restituiscono.
            engine.release()
        except Exception as error:
            if align_device != "cuda" or not is_oom(error):
                raise
            engine.release()
            log.warning("allineamento: memoria della scheda finita, lo rifaccio sul processore")
            progress.set("aligning", detail="cpu")
            segments = engine.align(segments, detected, audio, "cpu", progress_callback=progress.callback("aligning"))
    except Exception as error:  # noqa: BLE001
        # Senza allineamento i tempi restano quelli di Whisper: meno precisi, ma una trascrizione
        # con tempi approssimativi vale piu' di un errore. Con la traccia, pero': senza, l'errore di
        # NLTK (vedi [trust_sentence_splitter]) e' rimasto nascosto dietro questa riga per giorni.
        alignment = f"errore: {type(error).__name__}: {error}"
        log.warning("allineamento non riuscito per '%s': tengo i tempi originali", detected, exc_info=True)

    # Niente di grande resta nel frame: se qualcuno lo conserva (una libreria che tiene da parte un
    # errore d'import col suo traceback, vedi [warm_imports]) si porterebbe dietro il modello e
    # l'audio, e scaricare il modello non restituirebbe piu' la scheda.
    model = audio = None
    return {
        "segments": segments,
        "language": detected,
        "device_used": device_used,
        "batch_size": size,
        "alignment": alignment,
    }


# --- i pezzi, sul computer ------------------------------------------------------------------------
#
# Il tetto ai pezzi (`customMaxMinutes` dell'app) e' una scelta di chi preferisce richieste corte.
# Il telefono la rispettava decodificando tutta la lezione in PCM, tagliandola e ricodificando ogni
# pezzo in AAC: minuti di lavoro su un telefono per preparare quello che WhisperX rifa' comunque.
# Qui l'audio e' gia' in memoria come array (`whisperx.load_audio`), e tagliarlo costa una fetta.
# La regola e' quella di `ChunkPolicy.decide` con la tolleranza del computer, e i tagli quelli di
# `ChunkPlanner.planEqual`: un port piccolo, perche' la stessa lezione deve venire divisa allo stesso
# modo da chiunque la divida.

# Fino a dieci minuti oltre il tetto il file va intero: costano solo attesa, un taglio costa contesto.
COMPUTER_TOLERANCE_S = 10 * 60
FRAME_MS = 20
# Quanto il taglio puo' spostarsi dal confine ideale per cadere in un silenzio.
SEARCH_WINDOW_S = 30.0
# Il silenzio si cerca a finestre di mezzo secondo: un frame muto capita anche in mezzo a una parola.
QUIET_WINDOW_MS = 500
# Sotto il minuto un pezzo non si fa: meglio meno pezzi, un po' piu' lunghi.
MIN_PIECE_S = 60.0


# «Automatico»: pezzi da circa quattro minuti di lavoro ciascuno. Abbastanza lunghi da dare a Whisper
# il contesto che gli serve e da non moltiplicare le cuciture; abbastanza corti che una lezione
# annullata, un computer che si spegne o un riavvio perdano poco, e che la barra si muova. Sulla
# scheda, veloce, questo vuol dire lezioni intere; sul processore, pezzi da un quarto d'ora.
AUTO_PIECE_WORK_S = 4 * 60
AUTO_MIN_MINUTES = 15
AUTO_MAX_MINUTES = 120
# Quanto va veloce, prima di averlo misurato: large-v3 su una scheda di fascia media, e sul processore.
DEFAULT_SPEED = {"cuda": 25.0, "cpu": 1.5}
SPEED_SAMPLES = 10


def recent_speed(device: str) -> float:
    """Quante volte il tempo reale va questo computer, dalle ultime lezioni (la mediana) o una stima."""
    samples = sorted(STATE.get("speeds", {}).get(device, []))
    if not samples:
        return DEFAULT_SPEED.get(device, 1.5)
    return samples[len(samples) // 2]


def record_speed(device: str, audio_s: float, work_s: float) -> None:
    """Una lezione finita: quanto audio in quanto lavoro. Le brevi non contano: pesa il caricamento."""
    if audio_s < 120 or work_s <= 0:
        return
    speeds = STATE.setdefault("speeds", {}).setdefault(device, [])
    speeds.append(audio_s / work_s)
    del speeds[:-SPEED_SAMPLES]


def auto_piece_minutes(duration_s: float, device: str) -> int | None:
    """
    Il tetto dei pezzi scelto dal computer, in minuti, o None per la lezione intera.

    Dalla velocita' misurata: quanti minuti di audio fa in [AUTO_PIECE_WORK_S], arrotondati a cinque e
    tenuti fra [AUTO_MIN_MINUTES] e [AUTO_MAX_MINUTES]. Se la lezione ci sta in un pezzo (con la
    stessa tolleranza di [piece_count]) va intera.
    """
    minutes = recent_speed(device) * AUTO_PIECE_WORK_S / 60
    minutes = int(max(AUTO_MIN_MINUTES, min(AUTO_MAX_MINUTES, round(minutes / 5) * 5)))
    if duration_s <= minutes * 60 + COMPUTER_TOLERANCE_S:
        return None
    return minutes


def piece_count(duration_s: float, max_minutes: int | None) -> int:
    """
    In quanti pezzi va una registrazione: 1 fino al tetto piu' dieci minuti, poi `ceil(durata/tetto)`.

    Quaranta minuti con un tetto di trenta vanno interi; quarantuno fanno due pezzi da venti e mezzo,
    mai trenta piu' undici.
    """
    if not max_minutes or max_minutes <= 0 or duration_s <= 0:
        return 1
    cap = max_minutes * 60.0
    if duration_s <= cap + COMPUTER_TOLERANCE_S:
        return 1
    return max(1, math.ceil(duration_s / cap))


def frame_energies(audio: Any, sample_rate: int, frame_ms: int = FRAME_MS) -> Any:
    """
    L'energia (RMS) di ogni finestra di [frame_ms], con numpy sull'array che c'e' gia'.

    Il prodotto scalare riga per riga (`einsum`) e non `square` + `mean`: un'ora a 16 kHz sono 230 MB
    in float32, e `square` ne farebbe una copia intera solo per sommarla.
    """
    import numpy as np

    frame = max(1, int(round(sample_rate * frame_ms / 1000)))
    count = len(audio) // frame
    if count == 0:
        return np.zeros(0, dtype=np.float32)
    frames = np.asarray(audio[: count * frame], dtype=np.float32).reshape(count, frame)
    return np.sqrt(np.einsum("ij,ij->i", frames, frames) / frame)


def quietest_point(energies: Any, frame_s: float, from_s: float, to_s: float, window_ms: int = QUIET_WINDOW_MS) -> float:
    """Il centro della finestra piu' silenziosa fra due istanti, in secondi. `ChunkPlanner.quietestPoint`."""
    import numpy as np

    window = max(1, int(round(window_ms / 1000 / frame_s)))
    last_index = len(energies) - 1
    first = min(max(int(from_s / frame_s), 0), last_index)
    last = min(max(int(to_s / frame_s), 0), last_index)
    if last - first < window:
        return (from_s + to_s) / 2
    sums = np.concatenate(([0.0], np.cumsum(energies[first:last], dtype=np.float64)))
    windows = sums[window:] - sums[:-window]
    best = first + int(np.argmin(windows))
    return (best + window // 2) * frame_s


def plan_pieces(
    energies: Any,
    frame_s: float,
    total_s: float,
    pieces: int,
    search_s: float = SEARCH_WINDOW_S,
    min_piece_s: float = MIN_PIECE_S,
) -> list[tuple[float, float]]:
    """
    [pieces] pezzi uguali, ognuno tagliato nel silenzio piu' vicino al suo confine: `ChunkPlanner.planEqual`.

    Il confine k-esimo si cerca attorno a `k * totale / pieces`, non alla fine del pezzo prima: gli
    scarti della ricerca non si sommano, e l'ultimo pezzo non diventa un moncone. Niente
    sovrapposizione, a differenza del telefono: il taglio cade in un silenzio, e i pezzi si mettono
    in fila spostando i tempi, senza cucitura da fare.
    """
    count = min(pieces, max(1, int(total_s // min_piece_s))) if min_piece_s > 0 else pieces
    if count <= 1 or len(energies) == 0:
        return [(0.0, total_s)]
    cuts: list[float] = []
    for k in range(1, count):
        ideal = total_s * k / count
        previous = cuts[-1] if cuts else 0.0
        low = max(previous + min_piece_s, ideal - search_s)
        high = min(total_s - min_piece_s, ideal + search_s)
        cut = ideal if low >= high else quietest_point(energies, frame_s, low, high)
        if cut <= previous or cut >= total_s:
            continue
        cuts.append(cut)
    bounds = [0.0, *cuts, total_s]
    return list(zip(bounds[:-1], bounds[1:]))


def _shifted(segment: dict, offset: float) -> dict:
    """Un segmento di un pezzo, con i tempi (suoi e delle parole) spostati all'inizio del pezzo."""
    if not offset:
        return segment
    moved = dict(segment)
    for key in ("start", "end"):
        if isinstance(moved.get(key), (int, float)):
            moved[key] = moved[key] + offset
    if moved.get("words"):
        words = []
        for word in moved["words"]:
            word = dict(word)
            for key in ("start", "end"):
                if isinstance(word.get(key), (int, float)):
                    word[key] = word[key] + offset
            words.append(word)
        moved["words"] = words
    return moved


# --- l'audio, con una copia sola --------------------------------------------------------------------
#
# `whisperx.load_audio` chiede a ffmpeg interi a 16 bit, li raccoglie tutti in un `bytes`, li converte
# in float32 e poi li divide per 32768: tre copie della lezione vive insieme. Per «Napoli 18h» (19,8
# ore, 4,3 GB in float32) il picco di memoria impegnata era 11,75 GB, e 103 secondi. Qui ffmpeg scrive
# gia' float32 e i byte cadono in un array preparato della misura che il contenitore dichiara: una
# copia, e basta — 4,65 GB, 41 secondi.

# Oltre questa durata, se la lezione va comunque in pezzi, l'audio intero non si tiene in memoria: un
# primo giro calcola le energie (tagli, lingua, silenzi) e ogni pezzo si decodifica quando tocca a lui
# (lo stesso file: 0,5 GB). Il prezzo: `-ss` su un opus cade al pacchetto, e un pezzo puo' cominciare
# fino a 30 ms dopo il punto chiesto (misurato). Il taglio sta in un silenzio, e l'allineamento lavora
# a finestre di 20 ms: sotto le quattro ore l'array intero resta la strada esatta, e la piu' comune.
STREAM_ABOVE_S = 4 * 3600
DECODE_BLOCK_BYTES = 4 * 1024 * 1024
PROBE_TIMEOUT_S = 30
_DURATION_LINE = re.compile(rb"Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)")
_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0  # CREATE_NO_WINDOW


def probe_duration(path: str | Path) -> float | None:
    """
    Quanto dura il file secondo il contenitore, senza decodificarlo: ffprobe, o se manca (il setup
    porta solo ffmpeg, vedi `archive.audio_recorded_us`) la riga «Duration:» di `ffmpeg -i`.
    """
    commands = []
    if probe := shutil.which("ffprobe"):
        commands.append((probe, [probe, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(path)]))
    if ffmpeg := shutil.which("ffmpeg"):
        commands.append((ffmpeg, [ffmpeg, "-hide_banner", "-nostdin", "-i", str(path)]))
    for program, command in commands:
        try:
            answer = subprocess.run(
                command, capture_output=True, timeout=PROBE_TIMEOUT_S, stdin=subprocess.DEVNULL, creationflags=_NO_WINDOW,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if program == probe:
            seconds = _finite(answer.stdout.strip() or None, math.nan)
        else:
            match = _DURATION_LINE.search(answer.stderr or b"")
            seconds = int(match[1]) * 3600 + int(match[2]) * 60 + float(match[3]) if match else math.nan
        if seconds > 0:
            return seconds
    return None


def _decode_command(path: str | Path, sample_rate: int, start_s: float | None = None, duration_s: float | None = None) -> list[str]:
    """Il comando di `whisperx.load_audio` (mono, [sample_rate]), ma in float32, e volendo un tratto solo."""
    command = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "0"]
    if start_s:
        command += ["-ss", f"{start_s:.3f}"]
    if duration_s is not None:
        command += ["-t", f"{duration_s:.3f}"]
    return command + ["-i", str(path), "-f", "f32le", "-ac", "1", "-acodec", "pcm_f32le", "-ar", str(sample_rate), "-"]


@contextlib.contextmanager
def _ffmpeg_output(command: list[str]):
    """
    ffmpeg che scrive su una pipe, letta da chi usa il blocco. Gli errori vanno in un temporaneo e
    non in un'altra pipe: una pipe di errori che nessuno svuota, piena, fermerebbe ffmpeg e noi con lui.
    """
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(
            command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=errors, bufsize=0, creationflags=_NO_WINDOW,
        )
        try:
            yield process.stdout
        except BaseException:
            process.kill()
            process.wait()
            raise
        finally:
            process.stdout.close()
        if process.wait() != 0:
            errors.seek(0)
            raise RuntimeError(f"Failed to load audio: {errors.read()[-2000:].decode('utf-8', errors='replace').strip()}")


def load_audio(
    path: str | Path,
    sample_rate: int,
    start_s: float | None = None,
    duration_s: float | None = None,
    expected_s: float | None = None,
    check: Callable[[], None] | None = None,
) -> Any:
    """
    L'audio (o il tratto da [start_s] lungo [duration_s]) come float32 mono, in un array solo.

    L'array si prepara lungo quanto il contenitore dice ([expected_s], da [probe_duration]) piu' due
    secondi, e ffmpeg ci scrive dentro a blocchi: niente `bytes` intermedio, niente conversione. Se il
    contenitore mente, l'array cresce (una copia, solo in quel caso). [check] si chiama a ogni
    blocco: una lezione annullata smette di decodificare subito, non dopo venti ore di audio.
    """
    import numpy as np

    span = duration_s if duration_s is not None else expected_s
    buffer = np.empty(int(math.ceil(((span or 600.0) + 2.0) * sample_rate)), dtype=np.float32)
    filled = 0
    with _ffmpeg_output(_decode_command(path, sample_rate, start_s, duration_s)) as stream:
        while True:
            if check is not None:
                check()
            if filled == buffer.nbytes:
                bigger = np.empty(len(buffer) + max(len(buffer) // 2, sample_rate * 600), dtype=np.float32)
                bigger[: len(buffer)] = buffer
                buffer = bigger
            with memoryview(buffer).cast("B") as raw:
                read = stream.readinto(raw[filled : filled + DECODE_BLOCK_BYTES])
            if not read:
                break
            filled += read
    count = filled // 4
    try:
        # Restituisce i due secondi di scorta senza copiare: nessun altro vede ancora l'array.
        buffer.resize(count, refcheck=False)
    except ValueError:
        buffer = buffer[:count]
    return buffer


def stream_energies(path: str | Path, sample_rate: int, check: Callable[[], None] | None = None) -> tuple[Any, int]:
    """
    [frame_energies] di tutto il file e quanti campioni ha, decodificando a blocchi senza tenerlo.

    I blocchi sono multipli esatti della finestra di [FRAME_MS], cosi' le energie sono le stesse che
    darebbe l'array intero: stessi tagli, qualunque strada abbia preso la lezione.
    """
    import numpy as np

    frame_bytes = max(1, int(round(sample_rate * FRAME_MS / 1000))) * 4
    block = max(frame_bytes, DECODE_BLOCK_BYTES // frame_bytes * frame_bytes)
    buffer = bytearray(block)
    parts: list[Any] = []
    filled = total = 0
    with _ffmpeg_output(_decode_command(path, sample_rate)) as stream, memoryview(buffer) as view:
        while True:
            if check is not None:
                check()
            read = stream.readinto(view[filled:])
            filled += read or 0
            total += read or 0
            if filled == block or (not read and filled):
                usable = filled - filled % frame_bytes
                if usable:
                    parts.append(frame_energies(np.frombuffer(buffer, dtype=np.float32, count=usable // 4), sample_rate))
                    view[: filled - usable] = view[usable:filled]
                    filled -= usable
            if not read:
                break
    energies = np.concatenate(parts) if parts else np.zeros(0, dtype=np.float32)
    return energies, total // 4


class LoadedAudio:
    """La lezione tutta in memoria: i pezzi sono fette dello stesso array, niente copie."""

    def __init__(self, audio: Any, sample_rate: int) -> None:
        self.audio = audio
        self.sample_rate = sample_rate
        self.duration_s = len(audio) / sample_rate
        self._energies: Any = None

    def energies(self) -> Any:
        if self._energies is None:
            self._energies = frame_energies(self.audio, self.sample_rate)
        return self._energies

    def piece(self, start_s: float, end_s: float) -> Any:
        if start_s <= 0 and end_s >= self.duration_s:
            return self.audio
        return self.audio[int(round(start_s * self.sample_rate)) : int(round(end_s * self.sample_rate))]

    def release(self) -> None:
        self.audio = None


class StreamedAudio:
    """
    Una lezione troppo lunga per tenerla in memoria ([STREAM_ABOVE_S]): le energie da un primo giro
    ([stream_energies]), e ogni pezzo decodificato da ffmpeg quando serve (`-ss`/`-t`). Venti ore
    costano due decodifiche invece di 4,6 GB fermi per tutta la trascrizione.
    """

    def __init__(self, path: str | Path, sample_rate: int, check: Callable[[], None] | None = None) -> None:
        self.path = path
        self.sample_rate = sample_rate
        self.check = check
        self._energies, samples = stream_energies(path, sample_rate, check)
        self.duration_s = samples / sample_rate

    def energies(self) -> Any:
        return self._energies

    def piece(self, start_s: float, end_s: float) -> Any:
        return load_audio(self.path, self.sample_rate, start_s=start_s, duration_s=end_s - start_s, check=self.check)

    def release(self) -> None:
        pass


# --- la lingua --------------------------------------------------------------------------------------
#
# Senza una lingua dall'app, WhisperX la riconosce sui primi trenta secondi del file. «Napoli 18h»
# cominciava con tre ore di stanza vuota: la lingua si decideva sul rumore, e il rumore si trascriveva
# in quella lingua. Qui si ascoltano i trenta secondi in cui si parla di piu' — la finestra con
# l'energia mediana piu' alta, che un colpo isolato non alza — e la lingua vale per tutti i pezzi.

LANGUAGE_WINDOW_S = 30.0
LANGUAGE_STEP_S = 5.0
# Tre finestre diverse, a maggioranza: una sola puo' capitare su una canzone o una frase straniera.
LANGUAGE_VOTES = 3


def speech_windows(energies: Any, frame_s: float, count: int = LANGUAGE_VOTES, window_s: float = LANGUAGE_WINDOW_S) -> list[float]:
    """Dove cominciano le [count] finestre da [window_s] con l'energia mediana piu' alta, senza sovrapporsi."""
    import numpy as np

    frames = max(1, int(round(window_s / frame_s)))
    if len(energies) <= frames:
        return [0.0]
    step = max(1, int(round(LANGUAGE_STEP_S / frame_s)))
    windows = np.lib.stride_tricks.sliding_window_view(energies, frames)[::step]
    scores = np.median(windows, axis=1)
    chosen: list[int] = []
    for index in np.argsort(scores)[::-1]:
        start = int(index) * step
        if all(abs(start - other) >= frames for other in chosen):
            chosen.append(start)
            if len(chosen) == count:
                break
    return [start * frame_s for start in chosen]


def spoken_language(source: Any, frame_s: float, engine: Engine, progress: JobProgress) -> str | None:
    """
    La lingua delle finestre di [speech_windows], a maggioranza (a pari merito, la piu' parlata).

    None se il modello non la sa dire o non si carica: allora la riconosce [run_job] come prima, e
    il ripiego sul processore resta tutto suo.
    """
    try:
        if engine.needs_load():
            progress.set("loading_model")
        votes: list[str] = []
        for start_s in speech_windows(source.energies(), frame_s):
            progress.check_cancelled()
            window = source.piece(start_s, min(source.duration_s, start_s + LANGUAGE_WINDOW_S))
            language = engine.detect_language(window)
            if not language:
                return None
            votes.append(language)
    except JobCancelled:
        raise
    except Exception as error:  # noqa: BLE001
        engine.release()
        log.warning("lingua non riconosciuta in anticipo (%s: %s): la riconosce il primo pezzo", type(error).__name__, error)
        return None
    if not votes:
        return None
    best = max(votes, key=lambda language: (votes.count(language), -votes.index(language)))
    log.info("lingua: %s (%s)", best, ", ".join(votes))
    return best


# --- le allucinazioni -------------------------------------------------------------------------------
#
# Whisper davanti al rumore non tace: scrive. Nel file vero da cui nasce tutto questo («Napoli 18h»,
# 2354 segmenti) c'erano 407 eco del titolo passato come vocabolario («18h 18h 18h»), un centinaio di
# «Grazie.» e «Buonanotte» nei tratti muti, 78 giri a vuoto («la la la…»). Si riconoscevano da tre
# cose che una frase vera non ha insieme: allineati durano un soffio (mediana 140 ms contro 1,8 s),
# vanno a undici parole al secondo (il parlato a 3,5), e l'audio sotto e' quieto. La
# confidenza del modello da sola non basta (−0,71 contro −0,47: si sovrappongono).
#
# Le regole sono strette apposta: una frase lunga, piena di voce e di parole diverse non si tocca mai.

# Oltre questo rapporto di compressione (quello di Whisper: byte / byte con zlib) il testo gira in tondo.
LOOP_COMPRESSION = 2.4
# Un'unita' ripetuta piu' lunga di cosi' (in parole) non si cerca: non e' piu' un giro, e' un discorso.
LOOP_MAX_UNIT = 12
# Un segmento allineato piu' corto di cosi', o piu' veloce di cosi', non e' qualcuno che parla...
SHORT_SPAN_S = 0.25
FAST_CHARS_PER_S = 25.0
# ...se l'audio sotto e' «quieto»: meno di [QUIET_RATIO] volte il fondo del suo tratto, o piu' di
# [QUIET_BELOW_SPEECH_DB] sotto il livello della voce del file. Servono tutti e due. Con un fruscio
# costante il fondo e' il fruscio, e un'allucinazione ci sta appena sopra. Ma il telefono di «Napoli
# 18h» toglieva il rumore da se': il fondo era a −95 dB, ogni colpo di tosse stava 40 dB sopra, e la
# misura contro il fondo non diceva niente. Li' contava la distanza dalla voce: le frasi vere fra −17
# e −45 dB, gli «18h» e i «Grazie.» inventati fra −48 e −95, con la voce (vedi [sound_levels]) a −26.
# Il margine sul fondo e' di soli 2 dB: nelle ore di chiacchiere di quel file il fondo era il brusio
# stesso (−36/−40 dB), e con 6 dB se ne andavano «Ma che c'e'?», «Non lo so, mi dispiace».
QUIET_RATIO = 1.25
QUIET_BELOW_SPEECH_DB = 24.0
# Ancora piu' giu', nessuna durata la salva: quaranta decibel sotto la voce del file non si capisce
# una parola, e quello che Whisper ci scrive («Ja.», «Takk for oss.» sulle ore mute) e' inventato.
MUTE_BELOW_SPEECH_DB = 40.0
# Il fondo e' il decimo percentile delle energie di ogni dieci minuti: il rumore di una stanza cambia
# in venti ore, e un fondo unico per tutto il file confonderebbe il condizionatore con il silenzio.
FLOOR_PERCENTILE = 10
FLOOR_BLOCK_S = 600.0
# La voce invece si misura su tutto il file, perche' in un blocco di sola stanza vuota non c'e': il
# 95° percentile delle finestre che stanno almeno 20 dB sopra il fondo del loro blocco.
SPEECH_PERCENTILE = 95
ACTIVE_ABOVE_FLOOR = 10.0
# Le frasi del silenzio: corte, e sole (tre secondi di niente prima e dopo) o sopra un audio quieto.
PHRASE_MAX_S = 2.0
ISOLATION_S = 3.0
# Quello che Whisper scrive nel silenzio perche' l'ha visto in coda a mille video. Solo frasi intere:
# «grazie» dentro una frase vera non c'entra. Niente «ciao a tutti», che una lezione la apre davvero.
SILENCE_PHRASES = frozenset(
    {
        "grazie", "grazie a tutti", "grazie mille", "grazie a voi", "grazie per la visione",
        "grazie per l ascolto", "grazie per aver guardato", "buonanotte", "buonanotte a tutti",
        "thank you", "thanks for watching", "thank you for watching",
    }
)
# I titoli di coda dei sottotitoli amatoriali: in una lezione non si dicono mai. In piu' lingue perche'
# sul rumore anche la lingua e' a caso: le ore mute di «Napoli 18h», prese da sole, venivano
# riconosciute come norvegese, e ogni colpo di tosse diventava «Teksting av Nicolai Winther».
CREDITS = re.compile(
    r"\bamara org\b|\bai media\b|\bnicolai winther\b|\bqtss\b"
    r"|^(sottotitoli|subtitles|subtitulos|subtítulos|sous titres|untertitel\w*|teksting|undertekst\w*|undertext\w*)"
    r" (creati|a cura|e revisione|di|by|av|por|de|der|réalisés)\b"
)
HALLUCINATION_REASONS = {
    "vuoti": "senza parole",
    "giri": "giri a vuoto accorciati",
    "crediti": "titoli di coda",
    "eco": "eco del vocabolario",
    "frasi": "frasi del silenzio",
    "brevi": "brevi nel rumore",
    "muti": "sotto la voce",
}


def normalized_words(text: str) -> list[str]:
    """Le parole, minuscole e senza punteggiatura: «L'ha detto, 18h30!» -> ["l", "ha", "detto", "18h30"]."""
    return re.findall(r"[^\W_]+", (text or "").lower())


def _pieces_of_words(text: str) -> list[str]:
    """Come [normalized_words], ma separando cifre e lettere: «18h30» -> ["18", "h", "30"]."""
    return re.findall(r"\d+|[^\W\d_]+", (text or "").lower())


def compression_ratio(text: str) -> float:
    """Il rapporto di compressione di Whisper: quanto zlib accorcia il testo. Un parlato vero sta sotto 2."""
    import zlib

    data = (text or "").encode("utf-8")
    return len(data) / len(zlib.compress(data)) if data else 0.0


def collapse_repeats(keys: list[str], max_unit: int = LOOP_MAX_UNIT) -> list[int]:
    """
    Gli indici delle parole da tenere quando un'unita' si ripete di fila: ne resta la prima.

    L'unita' puo' essere di una parola (almeno tre volte: «no, no» si dice davvero) o di piu' (almeno
    due volte). A ogni posizione vince la ripetizione che copre piu' parole, e a pari copertura
    l'unita' piu' corta: «la la la la» diventa «la», non «la la».
    """
    kept: list[int] = []
    index, count = 0, len(keys)
    while index < count:
        best: tuple[int, int] | None = None
        for unit in range(1, min(max_unit, (count - index) // 2) + 1):
            pattern = keys[index : index + unit]
            repeats = 1
            while keys[index + repeats * unit : index + (repeats + 1) * unit] == pattern:
                repeats += 1
            if repeats >= (3 if unit == 1 else 2) and (best is None or repeats * unit > best[0]):
                best = (repeats * unit, unit)
        if best is None:
            kept.append(index)
            index += 1
        else:
            kept.extend(range(index, index + best[1]))
            index += best[0]
    return kept


def _collapsed(segment: dict) -> dict | None:
    """Il segmento con i giri tolti, o None se non c'era niente da togliere. I tempi sono quelli delle parole tenute."""
    tokens = (segment.get("text") or "").split()
    keys = [" ".join(normalized_words(token)) for token in tokens]
    kept = list(range(len(tokens)))
    for _ in range(3):  # un giro dentro un giro: «a b a b c a b a b c» -> «a b c»
        inner = collapse_repeats([keys[i] for i in kept])
        if len(inner) == len(kept):
            break
        kept = [kept[i] for i in inner]
    if len(kept) == len(tokens):
        return None
    shorter = dict(segment)
    shorter["text"] = " ".join(tokens[i] for i in kept)
    words = segment.get("words") or []
    if len(words) == len(tokens):
        shorter["words"] = [words[i] for i in kept]
        timed = [
            w for w in shorter["words"]
            if not math.isnan(_finite(w.get("start"), math.nan)) and not math.isnan(_finite(w.get("end"), math.nan))
        ]
        if timed:
            shorter["start"], shorter["end"] = float(timed[0]["start"]), float(timed[-1]["end"])
    else:
        # Parole e testo non si corrispondono una a una: meglio nessuna parola accesa che quelle sbagliate.
        shorter["words"] = []
    return shorter


def noise_floors(energies: Any, frame_s: float, block_s: float = FLOOR_BLOCK_S) -> list[float]:
    """Il fondo di ogni blocco da [block_s]: il [FLOOR_PERCENTILE]-esimo percentile, senza lo zero digitale."""
    import numpy as np

    block = max(1, int(round(block_s / frame_s)))
    floors: list[float] = []
    for first in range(0, len(energies), block):
        window = energies[first : first + block]
        if len(window) < block // 2 and len(energies) >= block:
            window = energies[-block:]  # un moncone finale si misura con i dieci minuti prima
        audible = window[window > 1e-6]
        floors.append(float(np.percentile(audible, FLOOR_PERCENTILE)) if len(audible) else 1e-6)
    return floors


def sound_levels(energies: Any, frame_s: float) -> tuple[list[float], float | None]:
    """
    I fondi di ogni blocco ([noise_floors]) e il livello della voce di tutto il file: il
    [SPEECH_PERCENTILE]-esimo percentile delle finestre almeno [ACTIVE_ABOVE_FLOOR] volte sopra il
    fondo del loro blocco. None se nel file non c'e' niente sopra il fondo.
    """
    import numpy as np

    floors = noise_floors(energies, frame_s)
    if not floors:
        return floors, None
    block = max(1, int(round(FLOOR_BLOCK_S / frame_s)))
    per_frame = np.repeat(np.asarray(floors, dtype=np.float32), block)[: len(energies)]
    active = energies[energies >= per_frame * ACTIVE_ABOVE_FLOOR]
    return floors, (float(np.percentile(active, SPEECH_PERCENTILE)) if len(active) else None)


def _rms(energies: Any, frame_s: float, start: float, end: float) -> float | None:
    """L'energia (RMS) sotto [start, end], dalle finestre di [frame_energies]."""
    import numpy as np

    first = max(0, int(start / frame_s))
    last = max(first + 1, int(math.ceil(end / frame_s)))
    span = np.asarray(energies[first:last], dtype=np.float64)
    return float(np.sqrt(np.mean(span * span))) if len(span) else None


def drop_hallucinations(
    segments: list[dict],
    energies: Any = None,
    frame_s: float = FRAME_MS / 1000,
    prompt: str | None = None,
) -> tuple[list[dict], dict[str, int]]:
    """
    Toglie dai segmenti (gia' allineati e messi in fila) quello che Whisper ha scritto sul rumore.

    Nell'ordine:
      * **vuoti** — niente lettere ne' cifre («...», «- -»);
      * **giri** — rapporto di compressione oltre [LOOP_COMPRESSION]: la ripetizione si accorcia a
        una volta sola (coi tempi delle parole tenute), il segmento resta. Non si butta testo vero;
      * **crediti** — i titoli di coda dei sottotitoli ([CREDITS]);
      * **eco** — tutte le parole stanno nel vocabolario mandato come prompt (e le cifre attaccate,
        «18h30»): Whisper lo rilegge a ogni finestra da trenta secondi, e sul rumore lo ripete. Si
        toglie se ripete le parole, o se non sembra detto da qualcuno: corto, veloce o quieto.
        «Napoli» detto chiaro per un secondo, dal vivo, resta;
      * **brevi** — allineato in meno di [SHORT_SPAN_S] o a piu' di [FAST_CHARS_PER_S] caratteri al
        secondo, sopra un audio quieto (vicino al fondo, o molto sotto la voce: [QUIET_RATIO]);
      * **muti** — lungo quanto si vuole, ma [MUTE_BELOW_SPEECH_DB] sotto la voce del file;
      * **frasi** — una frase del silenzio ([SILENCE_PHRASES]), corta, e sola fra i segmenti rimasti
        o sopra un audio quieto.

    Senza energie ([energies] None) le regole che guardano il fondo non scattano. Torna i segmenti
    tenuti e quanti ne ha tolti (o accorciati) per ragione.
    """
    floors, speech = sound_levels(energies, frame_s) if energies is not None and len(energies) else ([], None)
    below_speech = speech * 10 ** (-QUIET_BELOW_SPEECH_DB / 20) if speech else 0.0
    mute = speech * 10 ** (-MUTE_BELOW_SPEECH_DB / 20) if speech else 0.0
    counts = {reason: 0 for reason in HALLUCINATION_REASONS}
    prompt_keys = set(_pieces_of_words(prompt or ""))

    def rms_of(segment: dict) -> float | None:
        return _rms(energies, frame_s, segment["start"], segment["end"]) if floors else None

    def quiet(segment: dict) -> bool:
        rms = rms_of(segment)
        if rms is None:
            return False
        floor = floors[min(len(floors) - 1, int(segment["start"] / FLOOR_BLOCK_S))]
        return rms < max(QUIET_RATIO * floor, below_speech)

    first_pass: list[dict] = []
    for original in segments:
        segment = dict(original)
        segment["start"] = _finite(segment.get("start"), 0.0)
        segment["end"] = max(segment["start"], _finite(segment.get("end"), segment["start"]))
        text = (segment.get("text") or "").strip()
        words = normalized_words(text)
        if not words:
            counts["vuoti"] += 1
            continue
        # Durata, velocita' ed energia si misurano sul segmento com'e' arrivato: un giro accorciato
        # dura quanto la sua prima volta, e sembrerebbe «corto» anche quando era voce vera.
        measured = segment
        span = segment["end"] - segment["start"]
        pace = len(text) / span if span > 0 else math.inf
        repeated = len(words) > len(set(words))
        if compression_ratio(text) > LOOP_COMPRESSION and (shorter := _collapsed(segment)) is not None:
            counts["giri"] += 1
            segment = shorter
            text = segment["text"].strip()
            words = normalized_words(text)
        if CREDITS.search(" ".join(words)):
            counts["crediti"] += 1
            continue
        pieces = _pieces_of_words(text)
        if prompt_keys and any(p in prompt_keys and not p.isdigit() for p in pieces) and all(
            p in prompt_keys or p.isdigit() for p in pieces
        ):
            if repeated or span < 1.0 or pace > FAST_CHARS_PER_S or quiet(measured):
                counts["eco"] += 1
                continue
        if (span < SHORT_SPAN_S or pace > FAST_CHARS_PER_S) and quiet(measured):
            counts["brevi"] += 1
            continue
        if (rms := rms_of(measured)) is not None and rms < mute:
            counts["muti"] += 1
            continue
        first_pass.append(segment)

    kept: list[dict] = []
    for index, segment in enumerate(first_pass):
        words = normalized_words(segment["text"])
        # «Grazie, grazie.» e' la stessa frase: le ripetizioni di fila non contano.
        phrase = " ".join(w for i, w in enumerate(words) if i == 0 or w != words[i - 1])
        span = segment["end"] - segment["start"]
        if phrase in SILENCE_PHRASES and span < PHRASE_MAX_S:
            before = segment["start"] - first_pass[index - 1]["end"] if index > 0 else math.inf
            after = first_pass[index + 1]["start"] - segment["end"] if index + 1 < len(first_pass) else math.inf
            if (before >= ISOLATION_S and after >= ISOLATION_S) or quiet(segment):
                counts["frasi"] += 1
                continue
        kept.append(segment)
    return kept, {reason: count for reason, count in counts.items() if count}


def describe_dropped(counts: dict[str, int]) -> str:
    """«407 eco del vocabolario, 98 frasi del silenzio» per il log."""
    return ", ".join(f"{count} {HALLUCINATION_REASONS[reason]}" for reason, count in counts.items() if count) or "niente"


def transcribe_audio(
    audio: Any,
    sample_rate: int,
    language: str | None,
    progress: JobProgress,
    engine: Engine,
    max_minutes: int | None = None,
    prompt: str | None = None,
    batch_size: int | None = None,
    device: str | None = None,
) -> dict[str, Any]:
    """
    [run_job] sull'audio intero, o su ogni pezzo se [max_minutes] lo chiede ([piece_count]).

    I pezzi sono fette dello stesso array, niente copie ne' file. Ogni pezzo passa da [run_job] per
    conto suo — il ripiego sul processore vale pezzo per pezzo — con la lingua che il primo ha
    riconosciuto, cosi' non la si riconosce da capo a ogni pezzo. I tempi di segmenti e parole si
    spostano dell'inizio del pezzo, e i pezzi si mettono in fila. [progress] dice «pezzo 2 di 3» e,
    dentro, lo stato di quel pezzo.

    Lotto e dispositivo si leggono una volta, qui, e valgono per tutti i pezzi: /v1/admin/settings
    puo' cambiare [STATE] mentre si trascrive, e il pezzo due non deve partire con un altro lotto.

    [audio] e' l'array della lezione, o una sorgente ([LoadedAudio], [StreamedAudio]) che i pezzi li
    decodifica quando servono. Senza una lingua, la si riconosce prima dei pezzi dove si parla di
    piu' ([spoken_language]); alla fine si tolgono le allucinazioni ([drop_hallucinations]).
    """
    batch_size = int(batch_size or STATE["batch_size"])
    device = device or STATE["device"]
    source = audio if isinstance(audio, (LoadedAudio, StreamedAudio)) else LoadedAudio(audio, sample_rate)
    audio = None
    total_s = source.duration_s
    frame_s = FRAME_MS / 1000
    energies = source.energies()
    count = piece_count(total_s, max_minutes)
    bounds = plan_pieces(energies, frame_s, total_s, count) if count > 1 else [(0.0, total_s)]
    if len(bounds) > 1:
        log.info("divido %.1f min in %d pezzi (tetto %d min)", total_s / 60, len(bounds), max_minutes)

    segments: list[dict] = []
    detected = language or spoken_language(source, frame_s, engine, progress)
    device_used = device
    alignment = "ok"
    used_batch = batch_size
    for index, (start_s, end_s) in enumerate(bounds):
        progress.check_cancelled()
        progress.piece(index + 1, len(bounds))
        piece = source.piece(start_s, end_s)
        job = run_job(piece, detected, engine, batch_size, device, progress, prompt=prompt)
        piece = None
        detected = detected or job.get("language")
        segments.extend(_shifted(segment, start_s) for segment in job["segments"])
        if job.get("device_used") == "cpu":
            device_used = "cpu"
        if job.get("alignment", "ok") != "ok" and alignment == "ok":
            alignment = job["alignment"]
        used_batch = job.get("batch_size", used_batch)
    # Niente di grande resta nel frame: se qualcuno lo conserva (una libreria che tiene da parte un
    # errore d'import col suo traceback, vedi [warm_imports]) si porterebbe dietro il modello e
    # l'audio, e scaricare il modello non restituirebbe piu' la scheda.
    source.release()
    source = piece = None
    kept, dropped = drop_hallucinations(segments, energies, frame_s, prompt=prompt)
    if dropped:
        log.info("allucinazioni: %s (restano %d segmenti su %d)", describe_dropped(dropped), len(kept), len(segments))
    return {
        "segments": kept,
        "language": detected or "en",
        "device_used": device_used,
        "batch_size": used_batch,
        "alignment": alignment,
        "chunks": len(bounds),
        "dropped": dropped,
    }


def _transcribe(
    path: str,
    language: str | None,
    progress: JobProgress | None = None,
    prompt: str | None = None,
    max_minutes: int | str | None = None,
) -> dict[str, Any]:
    """Il lavoro vero, su un thread suo: WhisperX blocca, e bloccare il loop ferma anche /health."""
    from whisperx.audio import SAMPLE_RATE

    progress = progress or JobProgress()
    replan_for_job()
    # Da qui in poi la lezione usa queste impostazioni fino alla fine, pezzo dopo pezzo: vedi [Engine].
    engine = Engine()
    batch_size, device = STATE["batch_size"], STATE["device"]
    # Annullata mentre si pianificava: non si decodifica un'ora di audio per nessuno.
    progress.check_cancelled()
    # ffmpeg che decodifica un'ora di m4a sono secondi veri: meglio dirlo che restare «in coda».
    progress.set("decoding")
    check = progress.check_cancelled
    # «auto»: la lunghezza dei pezzi la sceglie il computer da quanto e' lunga la lezione e quanto va
    # veloce ([auto_piece_minutes]). Prima di decodificare con la durata del contenitore, per sapere se
    # la lezione sta in memoria; dopo con quella vera.
    expected_s = probe_duration(path)
    if expected_s and expected_s > STREAM_ABOVE_S:
        planned = auto_piece_minutes(expected_s, device) if max_minutes == "auto" else max_minutes
        streamed = piece_count(expected_s, planned) > 1
    else:
        streamed = False
    if streamed:
        log.info("%.1f ore: la decodifico a pezzi invece di tenerla tutta in memoria", expected_s / 3600)
        source: LoadedAudio | StreamedAudio = StreamedAudio(path, SAMPLE_RATE, check)
    else:
        source = LoadedAudio(load_audio(path, SAMPLE_RATE, expected_s=expected_s, check=check), SAMPLE_RATE)
    progress.check_cancelled()
    audio_s = source.duration_s
    progress.audio_s = audio_s
    cap = auto_piece_minutes(audio_s, device) if max_minutes == "auto" else max_minutes
    job = transcribe_audio(
        source, SAMPLE_RATE, language, progress, engine, max_minutes=cap, prompt=prompt, batch_size=batch_size, device=device,
    )
    STATE["alignment"][job["language"]] = job["alignment"]

    out = []
    for segment in job["segments"]:
        text = (segment.get("text") or "").strip()
        if not text:
            continue
        out.append(
            {
                # Numerati dopo aver messo in fila i pezzi: ogni pezzo ripartirebbe da zero.
                "id": len(out),
                "seek": 0,
                "start": _finite(segment.get("start"), 0.0),
                "end": _finite(segment.get("end"), 0.0),
                "text": text,
                # La confidenza la da' WhisperX; la probabilita' di silenzio no, e resta null: prima
                # era 0,0 — «voce di sicuro» — e il filtro dell'app non poteva scattare mai. Il
                # rapporto di compressione e' quello vero del testo ([compression_ratio]).
                "avg_logprob": _finite_or_none(segment.get("avg_logprob")),
                "no_speech_prob": None,
                "compression_ratio": round(compression_ratio(text), 3),
                "temperature": 0.0,
                "tokens": [],
                "words": words_of(segment),
            }
        )

    # Niente di grande resta nel frame: se qualcuno lo conserva (una libreria che tiene da parte un
    # errore d'import col suo traceback, vedi [warm_imports]) si porterebbe dietro il modello e
    # l'audio, e scaricare il modello non restituirebbe piu' la scheda.
    source = None
    gc.collect()
    return {
        "task": "transcribe",
        "language": job["language"],
        "duration": out[-1]["end"] if out else 0.0,
        "text": " ".join(s["text"] for s in out),
        "segments": out,
        # «cuda» o «cpu»: l'app lo usa per dire «trascritta sulla RAM, piu' lenta».
        "device_used": job["device_used"],
        # La durata vera del file, non la fine dell'ultimo segmento: un finale muto conta lo stesso.
        "audio_s": audio_s,
        "chunks": job["chunks"],
        # Il tetto usato davvero, in minuti; 0 = intera. Con «auto» e' quello che l'app mostra.
        "max_minutes_used": int(cap or 0),
        # Quanti segmenti inventati sono stati tolti, per ragione ([drop_hallucinations]).
        "dropped": job.get("dropped") or {},
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


def _finite_or_none(value: Any) -> float | None:
    """Come [_finite], ma un valore che non c'e' resta null invece di diventare un numero inventato."""
    number = _finite(value, math.nan)
    return None if math.isnan(number) else number


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


def configure(settings: dict[str, Any], path: Path | None = None) -> dict[str, Any]:
    """
    Da impostazioni a [STATE]. La chiamano sia `main` sia l'icona nell'area di notifica.

    Torna le impostazioni con `device`, `model`, `compute_type` e `batch_size` risolti davvero —
    cioe' dopo i conti della VRAM ([decide_vram]) — perche' chi le ha passate puo' aver scritto
    `auto` e vuole sapere com'e' finita. `path` e' il config.json da cui vengono, dove
    /v1/admin/settings riscrive: quello accanto al server se non si dice altro.
    """
    resolved = dict(settings)
    resolved["device"] = config.resolve_device(settings["device"])
    tunables = tunables_of(settings)
    if tunables["vram_mode"] not in VRAM_MODES or (tunables["vram_mode"] == "manual" and not tunables["vram_gb"]):
        log.warning("vram_mode %r senza una vram_gb valida: faccio i conti in automatico", tunables["vram_mode"])
        tunables["vram_mode"] = "auto"
    gpu = detect_gpu() if resolved["device"] == "cuda" else None
    plan = decide_vram(tunables, resolved["device"], gpu)
    resolved["model"] = plan["model"]
    resolved["compute_type"] = plan["compute_type"]
    resolved["batch_size"] = plan["batch_size"]

    STATE["config_path"] = path or config.CONFIG_PATH
    STATE["tunables"] = tunables
    STATE["gpu"] = gpu
    STATE["device"] = resolved["device"]
    apply_plan(plan)
    if gpu:
        log.info("scheda: %s, %.1f GB", gpu["name"], gpu["total_gb"])
    elif resolved["device"] == "cuda":
        log.warning("torch non sa dire quanta memoria ha la scheda: uso le impostazioni come sono")
    log.info("%s", describe_plan(plan))
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
    print(f"  {describe_plan(STATE['vram'])}.")
    if STATE["idle_seconds"]:
        print(f"  Il modello si carica alla prima registrazione e se ne va dopo {settings['idle_minutes']} minuti di silenzio.")
    else:
        print("  Il modello resta in memoria finche' il server e' acceso (--idle-minutes 0).")
    print("  Se il tablet non lo trova, lancia apri-firewall.cmd come amministratore.")
    count, size = archive.current().stats()
    print(f"  Archivio dei file: {settings['archive_root']}  ({count} file, {size / 1e9:.1f} GB)")
    print(flush=True)


def main() -> None:
    global SERVER
    parser = argparse.ArgumentParser(description="WhisperX per Pampa Notes")
    config.add_arguments(parser)
    args = parser.parse_args()

    settings = config.apply_cli(config.load(), args)
    # Prima il registro, poi [configure]: la decisione sulla VRAM deve finire anche nel file.
    setup_file_logging()
    settings = configure(settings)
    warm_imports()

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
    SERVER = uvicorn.Server(config_)
    SERVER.run()


if __name__ == "__main__":
    main()
