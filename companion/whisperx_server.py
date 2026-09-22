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
import os
import secrets
import socket
import tempfile
import time
import urllib.error
import urllib.request
import warnings
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any

# pyannote e torchcodec stampano in avvio un muro di avvisi su ffmpeg che non riguardano niente di
# quello che facciamo qui (la diarizzazione non si usa). Sembravano errori, e una console che
# sembra piena di errori e' una console che fa chiudere la finestra.
warnings.filterwarnings("ignore", message=".*torchcodec.*")
warnings.filterwarnings("ignore", message=".*TensorFloat-32.*")
warnings.filterwarnings("ignore", category=UserWarning, module="pyannote.*")
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse
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
            await self._cond.wait_for(lambda: not self._busy and self._waiting[0] == me)
            self._waiting.remove(me)
            self._busy = True
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

# Gli ospiti verificati di recente: token -> (nome, scadenza). Dieci minuti se e' buono, uno se
# no: un token revocato smette di valere entro dieci minuti, e uno inventato non fa una richiesta
# al Worker a ogni tentativo.
GUEST_CACHE: dict[str, tuple[str | None, float]] = {}

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


def align_model_for(language: str):
    """
    Il modello di allineamento della lingua, tenuto da parte dopo il primo uso.

    Ce n'e' uno per lingua e pesa poco, ma scaricarlo la prima volta richiede rete: tenerlo in
    memoria evita di rifarlo a ogni lezione. Se ne va insieme al modello grande.
    """
    import whisperx

    if language not in STATE["align"]:
        log.info("carico l'allineamento per '%s'...", language)
        model, metadata = whisperx.load_align_model(language_code=language, device=STATE["device"])
        STATE["align"][language] = (model, metadata)
    return STATE["align"][language]


@app.get("/health")
def health() -> dict[str, Any]:
    """Quello che l'app chiama per dire «raggiunto» invece di «non risponde». Risponde subito
    anche a modello scarico: e' il senso di non caricarlo all'avvio."""
    loaded = STATE["model"] is not None
    quiet = time.time() - STATE["last_used"] if STATE["last_used"] else 0.0
    return {
        "status": "ok",
        "model": STATE["name"],
        "device": STATE["device"],
        "compute_type": STATE["compute_type"],
        "busy": STATE["busy"],
        # L'app lo guarda per sapere se il testo si accendera' parola per parola davvero o per stima.
        "word_timestamps": True,
        # Le tre righe qui sotto non le legge l'app: le legge chi sta guardando la VRAM.
        "loaded": loaded,
        "vram_gb": round(vram_gb(), 1),
        "unload_in_s": max(0, int(STATE["idle_seconds"] - quiet)) if loaded and STATE["idle_seconds"] else None,
        # Quanti aspettano il loro turno, e se questo computer accetta ospiti.
        "queue": GATE.waiting,
        "guests": bool(STATE["index_url"] and STATE["owner"]),
    }


def pairing_link(port: int) -> str:
    """Il link che l'app sa aprire: `pampanotes://endpoint?url=...&remote=...&token=...`."""
    lan = local_addresses(port)
    link = "pampanotes://endpoint?url=" + (lan[0] if lan else f"http://localhost:{port}")
    remote = tailscale_address(port)
    if remote:
        link += "&remote=" + remote
    if STATE["token"]:
        link += "&token=" + STATE["token"]
    return link


def new_pairing_key() -> str:
    """
    Una chiave usa-e-getta per la pagina di accoppiamento, buona per dieci minuti.

    La pagina porta il token del server: senza una chiave, chiunque sulla rete di casa potrebbe
    chiederla e leggerlo. Con la chiave, il segreto e' il QR sullo schermo, come prima.
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
    if STATE["token"]:
        rows += f"<p><b>Token:</b> <code>{escape(STATE['token'])}</code></p>"
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
    return HTMLResponse(body)


@app.get("/v1/models")
def models() -> dict[str, Any]:
    """Nel formato di OpenAI, perche' e' quello che l'app sa leggere."""
    return {"object": "list", "data": [{"id": STATE["name"], "object": "model", "owned_by": "whisperx"}]}


def check_token(request: Request) -> None:
    """
    Il confronto e' `compare_digest` e non `==` perche' un `==` su stringhe esce al primo carattere
    diverso, e quanto ci mette a uscire dice quanti caratteri erano giusti. In casa non cambia
    niente; il giorno in cui questa porta si affaccia altrove, cambia.
    """
    header = request.headers.get("authorization", "")
    # Un ospite trascrive e basta: l'archivio dei file e lo sfratto del modello sono del proprietario.
    if header.startswith("Bearer pg_"):
        raise HTTPException(status_code=401, detail="riservato al proprietario")
    expected = STATE["token"]
    if not expected:
        return
    if not secrets.compare_digest(header, f"Bearer {expected}"):
        raise HTTPException(status_code=401, detail="token non valido")


def verify_guest(token: str) -> str | None:
    """Chiede al Worker se il token e' un ospite di questo proprietario. Il nome, o None."""
    now = time.time()
    cached = GUEST_CACHE.get(token)
    if cached and cached[1] > now:
        return cached[0]
    name: str | None = None
    index, owner = STATE["index_url"], STATE["owner"]
    if index and owner:
        try:
            body = json.dumps({"token": token, "owner": owner}).encode("utf-8")
            req = urllib.request.Request(index.rstrip("/") + "/v1/guests/verify", data=body, method="POST", headers={"Content-Type": "application/json", "User-Agent": WORKER_USER_AGENT})
            with urllib.request.urlopen(req, timeout=10) as response:
                name = json.loads(response.read() or b"{}").get("name") or None
        except urllib.error.HTTPError:
            name = None
        except (OSError, ValueError) as error:
            log.warning("verifica dell'ospite non riuscita: %s", error)
            name = None
    GUEST_CACHE[token] = (name, now + (600 if name else 60))
    return name


def report_usage(token: str, seconds: float) -> None:
    """Una trascrizione fatta da un ospite: si dice al Worker quanti secondi, per il registro."""
    index = STATE["index_url"]
    if not index:
        return
    try:
        body = json.dumps({"token": token, "seconds": round(seconds)}).encode("utf-8")
        req = urllib.request.Request(index.rstrip("/") + "/v1/guests/usage", data=body, method="POST", headers={"Content-Type": "application/json", "User-Agent": WORKER_USER_AGENT})
        with urllib.request.urlopen(req, timeout=10):
            pass
    except (OSError, ValueError) as error:
        log.warning("registro degli ospiti non aggiornato: %s", error)


async def caller_of(request: Request) -> tuple[str, str | None, str]:
    """
    Chi chiede di trascrivere: ("owner", None, token) oppure ("guest", nome, token). 401 altrimenti.

    Un ospite ha un token `pg_…` e passa dal Worker; il proprietario ha il token di config.json, o
    niente se non ne ha messo uno. La verifica dell'ospite fa una richiesta di rete: va su un
    thread, o bloccherebbe il server per tutti.
    """
    header = request.headers.get("authorization", "")
    bearer = header[7:] if header.startswith("Bearer ") else ""
    if bearer.startswith("pg_"):
        name = await asyncio.to_thread(verify_guest, bearer)
        if not name:
            raise HTTPException(status_code=401, detail="ospite non riconosciuto")
        return "guest", name, bearer
    check_token(request)
    return "owner", None, bearer


app.include_router(archive.build_router(check_token))


@app.post("/v1/admin/unload")
async def admin_unload(request: Request) -> dict[str, Any]:
    """
    Molla il modello adesso, senza aspettare i dieci minuti.

    Serve a chi sta per aprire un gioco e rivuole la scheda: l'icona nell'area di notifica lo fa
    senza passare di qui ([request_unload]), questo e' per l'app e per chi automatizza.
    """
    check_token(request)
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
    kind, guest, bearer = await caller_of(request)

    suffix = Path(file.filename or "audio").suffix or ".m4a"
    # Su disco e non in memoria: qui arrivano file da un'ora.
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        target = Path(tmp.name)
        while chunk := await file.read(1024 * 1024):
            tmp.write(chunk)

    size_mb = target.stat().st_size / (1024 * 1024)
    who = f"ospite {guest}: " if guest else ""
    log.info("%sricevuto %s (%.1f MB)%s", who, file.filename, size_mb, f", {GATE.waiting} in fila" if GATE.waiting else "")

    # Il proprietario passa davanti agli ospiti in attesa; nessuno interrompe chi sta gia' trascrivendo.
    async with GATE.slot(0 if kind == "owner" else 1):
        STATE["busy"] = True
        started = time.time()
        try:
            # Su un thread anche il caricamento: cosi' `/health` continua a rispondere durante i
            # minuti del primo avvio, invece di far credere all'app che il server sia morto.
            await asyncio.to_thread(ensure_model)
            result = await asyncio.to_thread(_transcribe, str(target), language.strip() or None)
        except Exception as error:  # noqa: BLE001 — qualunque guasto deve tornare come 500 leggibile
            log.exception("trascrizione fallita")
            raise HTTPException(status_code=500, detail=str(error)) from error
        finally:
            STATE["busy"] = False
            STATE["last_used"] = time.time()
            target.unlink(missing_ok=True)

    elapsed = time.time() - started
    duration = result["segments"][-1]["end"] if result["segments"] else 0.0
    speed = duration / elapsed if elapsed > 0 else 0
    log.info("%sfatto: %.1f min in %.0f s (%.0f volte il tempo reale)", who, duration / 60, elapsed, speed)
    if guest:
        asyncio.get_running_loop().run_in_executor(None, report_usage, bearer, duration)
    if STATE["idle_seconds"]:
        log.info("tengo il modello in memoria per %d minuti", STATE["idle_seconds"] // 60)

    if response_format == "text":
        return PlainTextResponse(result["text"])
    return JSONResponse(result)


def _transcribe(path: str, language: str | None) -> dict[str, Any]:
    """Il lavoro vero, su un thread suo: WhisperX blocca, e bloccare il loop ferma anche /health."""
    import whisperx

    audio = whisperx.load_audio(path)
    transcription = STATE["model"].transcribe(
        audio,
        batch_size=STATE["batch_size"],
        language=language,
    )
    detected = transcription.get("language", language or "en")

    segments = transcription.get("segments", [])
    try:
        align_model, metadata = align_model_for(detected)
        aligned = whisperx.align(
            segments,
            align_model,
            metadata,
            audio,
            STATE["device"],
            return_char_alignments=False,
        )
        segments = aligned.get("segments", segments)
    except Exception:  # noqa: BLE001
        # Senza allineamento i tempi restano quelli di Whisper: meno precisi, ma una trascrizione
        # con tempi approssimativi vale piu' di un errore.
        log.warning("allineamento non riuscito per '%s': tengo i tempi originali", detected)

    out = []
    for index, segment in enumerate(segments):
        text = (segment.get("text") or "").strip()
        if not text:
            continue
        out.append(
            {
                "id": index,
                "seek": 0,
                "start": float(segment.get("start", 0.0)),
                "end": float(segment.get("end", 0.0)),
                "text": text,
                # WhisperX non li restituisce: l'app li usa per scartare le allucinazioni, e valori
                # che dicono "voce presente, confidenza buona" lasciano passare tutto — che e' il
                # comportamento giusto quando l'informazione non c'e'.
                "avg_logprob": float(segment.get("avg_logprob", -0.2)),
                "no_speech_prob": float(segment.get("no_speech_prob", 0.0)),
                "compression_ratio": 1.0,
                "temperature": 0.0,
                "tokens": [],
                "words": words_of(segment),
            }
        )

    gc.collect()
    return {
        "task": "transcribe",
        "language": detected,
        "duration": out[-1]["end"] if out else 0.0,
        "text": " ".join(s["text"] for s in out),
        "segments": out,
    }


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
        start = word.get("start")
        end = word.get("end")
        if start is None or end is None:
            continue
        out.append(
            {
                "word": text,
                "start": float(start),
                "end": float(end),
                "score": float(word.get("score", 0.0)),
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
    found: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if not address.startswith("127.") and address not in found:
                found.append(address)
    except OSError:
        pass
    return found


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
        print("  Il token va nel campo sotto l'indirizzo.")
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
