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
import logging
import os
import socket
import tempfile
import time
import warnings
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

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("pampa")

# Lo stato del processo. Il modello e' `None` finche' non serve davvero: vedi [ensure_model].
STATE: dict[str, Any] = {
    "model": None,
    "align": {},
    "name": "large-v3",
    "device": "cuda",
    "compute_type": "float16",
    "batch_size": 16,
    "token": None,
    "busy": False,
    # Quando e' finita l'ultima trascrizione. Da qui parte il conto per lo sfratto.
    "last_used": 0.0,
    # Dopo quanti secondi di silenzio si libera la VRAM. 0 = mai.
    "idle_seconds": 600,
}

# Una trascrizione alla volta. Non e' pigrizia: due richieste insieme raddoppiano la memoria della
# GPU e, superato il limite, falliscono entrambe invece di una. Chi arriva secondo aspetta.
# Lo stesso lucchetto protegge lo sfratto: il modello non sparisce sotto una trascrizione in corso.
LOCK = asyncio.Lock()


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
        async with LOCK:
            if STATE["model"] is not None and not STATE["busy"]:
                unload_model(f"{quiet / 60:.0f} minuti senza richieste")


@contextlib.asynccontextmanager
async def lifespan(_: FastAPI):
    watcher = asyncio.create_task(idle_watch())
    try:
        yield
    finally:
        watcher.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await watcher
        unload_model("chiusura")


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
    }


@app.get("/v1/models")
def models() -> dict[str, Any]:
    """Nel formato di OpenAI, perche' e' quello che l'app sa leggere."""
    return {"object": "list", "data": [{"id": STATE["name"], "object": "model", "owned_by": "whisperx"}]}


def check_token(request: Request) -> None:
    expected = STATE["token"]
    if not expected:
        return
    header = request.headers.get("authorization", "")
    if header != f"Bearer {expected}":
        raise HTTPException(status_code=401, detail="token non valido")


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
    check_token(request)

    suffix = Path(file.filename or "audio").suffix or ".m4a"
    # Su disco e non in memoria: qui arrivano file da un'ora.
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        target = Path(tmp.name)
        while chunk := await file.read(1024 * 1024):
            tmp.write(chunk)

    size_mb = target.stat().st_size / (1024 * 1024)
    log.info("ricevuto %s (%.1f MB)", file.filename, size_mb)

    async with LOCK:
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
    log.info("fatto: %.1f min in %.0f s (%.0f volte il tempo reale)", duration / 60, elapsed, speed)
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
    """Gli indirizzi su cui il telefono puo' trovare questo computer."""
    found = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if not address.startswith("127.") and address not in found:
                found.append(address)
    except OSError:
        pass
    return [f"http://{a}:{port}" for a in found]


def main() -> None:
    parser = argparse.ArgumentParser(description="WhisperX per Pampa Notes")
    parser.add_argument("--model", default="large-v3", help="large-v3, medium, small...")
    parser.add_argument("--device", default="auto", help="cuda, cpu, auto")
    parser.add_argument("--compute-type", default="", help="float16 su GPU, int8 su CPU")
    parser.add_argument("--batch-size", type=int, default=16, help="abbassalo se la GPU va in esaurimento")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", default="", help="se c'e', l'app deve mandarlo")
    parser.add_argument(
        "--idle-minutes",
        type=int,
        default=10,
        help="dopo quanti minuti di silenzio liberare la VRAM. 0 per non liberarla mai",
    )
    parser.add_argument(
        "--preload",
        action="store_true",
        help="carica il modello subito invece che alla prima richiesta",
    )
    args = parser.parse_args()

    device = args.device
    if device == "auto":
        try:
            import torch

            device = "cuda" if torch.cuda.is_available() else "cpu"
        except ImportError:
            device = "cpu"

    STATE["name"] = args.model
    STATE["device"] = device
    STATE["compute_type"] = args.compute_type or ("float16" if device == "cuda" else "int8")
    STATE["batch_size"] = args.batch_size
    STATE["token"] = args.token or os.environ.get("PAMPA_TOKEN") or None
    STATE["idle_seconds"] = max(0, args.idle_minutes) * 60

    print()
    print("  Pampa Notes trova questo computer a uno di questi indirizzi:")
    for address in local_addresses(args.port) or [f"http://localhost:{args.port}"]:
        print(f"    {address}")
    print()
    print("  Mettilo in Altro -> Impostazioni -> Server personale, e tocca 'Prova la connessione'.")
    if STATE["token"]:
        print("  Il token va nel campo sotto l'indirizzo.")
    if STATE["idle_seconds"]:
        print(f"  Il modello si carica alla prima registrazione e se ne va dopo {args.idle_minutes} minuti di silenzio.")
    else:
        print("  Il modello resta in memoria finche' il server e' acceso (--idle-minutes 0).")
    print("  Se il tablet non lo trova, lancia apri-firewall.cmd come amministratore.")
    print(flush=True)

    if args.preload:
        ensure_model()
        STATE["last_used"] = time.time()

    uvicorn.run(app, host="0.0.0.0", port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
