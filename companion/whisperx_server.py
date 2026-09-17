"""
Il tuo computer come servizio di trascrizione per Pampa Notes.

WhisperX dietro le tre chiamate dell'API di OpenAI che l'app conosce. Non e' un progetto a se':
sono centocinquanta righe che espongono qualcosa che gia' funziona, perche' WhisperX e' una
libreria e una riga di comando, non un server.

Perche' vale la pena, rispetto a Groq:

  * **Nessun limite di dimensione.** Groq si ferma a venticinque megabyte per richiesta, quindi
    un'ora di lezione va tagliata in pezzi e ricucita. Qui il file va intero, e una cucitura che
    non si fa e' una cucitura che non puo' sbagliare.
  * **I file non escono di casa.** Una lezione registrata contiene le voci di persone che non
    hanno acconsentito a niente.
  * **I tempi sono migliori.** WhisperX allinea le parole con un modello fonetico invece di
    fidarsi dei tempi che Whisper inventa, e si sente quando il lettore segue il testo.

Avvio:
    powershell -ExecutionPolicy Bypass -File setup.ps1     (una volta)
    powershell -ExecutionPolicy Bypass -File run.ps1

Poi nell'app: Altro -> Impostazioni -> Server personale, e l'indirizzo che run.ps1 stampa.
"""

from __future__ import annotations

import argparse
import asyncio
import gc
import logging
import os
import socket
import tempfile
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse
import uvicorn

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("pampa")

app = FastAPI(title="Pampa Notes companion", docs_url=None, redoc_url=None)

# Lo stato del processo: il modello si carica una volta e resta in memoria, perche' caricarlo
# costa piu' di quanto costi trascrivere dieci minuti di audio.
STATE: dict[str, Any] = {
    "model": None,
    "align": {},
    "name": "large-v3",
    "device": "cuda",
    "compute_type": "float16",
    "batch_size": 16,
    "token": None,
    "busy": False,
}

# Una trascrizione alla volta. Non e' pigrizia: due richieste insieme raddoppiano la memoria della
# GPU e, superato il limite, falliscono entrambe invece di una. Chi arriva secondo aspetta.
LOCK = asyncio.Lock()


def load_model() -> None:
    """Carica il modello. Chiamato all'avvio, cosi' la prima richiesta non paga l'attesa."""
    import whisperx

    started = time.time()
    log.info("carico %s su %s (%s)…", STATE["name"], STATE["device"], STATE["compute_type"])
    STATE["model"] = whisperx.load_model(
        STATE["name"],
        device=STATE["device"],
        compute_type=STATE["compute_type"],
    )
    log.info("pronto in %.1f s", time.time() - started)


def align_model_for(language: str):
    """
    Il modello di allineamento della lingua, tenuto da parte dopo il primo uso.

    Ce n'e' uno per lingua e pesa poco, ma scaricarlo la prima volta richiede rete: tenerlo in
    memoria evita di rifarlo a ogni lezione.
    """
    import whisperx

    if language not in STATE["align"]:
        log.info("carico l'allineamento per '%s'…", language)
        model, metadata = whisperx.load_align_model(language_code=language, device=STATE["device"])
        STATE["align"][language] = (model, metadata)
    return STATE["align"][language]


@app.get("/health")
def health() -> dict[str, Any]:
    """Quello che l'app chiama per dire «raggiunto» invece di «non risponde»."""
    return {
        "status": "ok",
        "model": STATE["name"],
        "device": STATE["device"],
        "compute_type": STATE["compute_type"],
        "busy": STATE["busy"],
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

    Il campo `model` si ignora di proposito: il modello e' quello caricato all'avvio, e cambiarlo
    per richiesta significherebbe rileggere qualche gigabyte di pesi nel mezzo di una lezione.
    """
    check_token(request)
    import whisperx

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
            result = await asyncio.to_thread(_transcribe, str(target), language.strip() or None)
        except Exception as error:  # noqa: BLE001 — qualunque guasto deve tornare come 500 leggibile
            log.exception("trascrizione fallita")
            raise HTTPException(status_code=500, detail=str(error)) from error
        finally:
            STATE["busy"] = False
            target.unlink(missing_ok=True)

    elapsed = time.time() - started
    duration = result["segments"][-1]["end"] if result["segments"] else 0.0
    speed = duration / elapsed if elapsed > 0 else 0
    log.info("fatto: %.1f min in %.0f s (%.0f volte il tempo reale)", duration / 60, elapsed, speed)

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
    parser.add_argument("--model", default="large-v3", help="large-v3, medium, small…")
    parser.add_argument("--device", default="auto", help="cuda, cpu, auto")
    parser.add_argument("--compute-type", default="", help="float16 su GPU, int8 su CPU")
    parser.add_argument("--batch-size", type=int, default=16, help="abbassalo se la GPU va in esaurimento")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", default="", help="se c'e', l'app deve mandarlo")
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

    load_model()

    print()
    print("  Pampa Notes trova questo computer a uno di questi indirizzi:")
    for address in local_addresses(args.port) or [f"http://localhost:{args.port}"]:
        print(f"    {address}")
    print()
    print("  Mettilo in Altro -> Impostazioni -> Server personale, e tocca «Prova la connessione».")
    if STATE["token"]:
        print("  Il token va nel campo sotto l'indirizzo.")
    print()

    uvicorn.run(app, host="0.0.0.0", port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
