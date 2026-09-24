"""
Scarica il modello di faster-whisper nella cache di Hugging Face, dicendo a che punto e'.

Lo lancia `install.py` con il Python dell'ambiente del companion, perche' e' li' che ci sono
faster-whisper e huggingface_hub. Scarica esattamente quello che WhisperX cerchera' alla prima
lezione (`faster_whisper.utils.download_model`, la stessa cache), cosi' la prima trascrizione non
comincia con tre gigabyte da scaricare davanti a un telefono che aspetta.

Scrive una riga per secondo sullo standard output, che l'installer legge:

    PROGRESS <byte scaricati> <byte totali, 0 se non si sa>
    DONE <cartella>
    ERROR <perche'>

Il totale lo chiede alle API di Hugging Face; i byte fatti li conta guardando la cartella dei blob
della cache, compresi i `.incomplete` che crescono. Non e' elegante, ma non dipende dalle barre di
tqdm, che cambiano da una versione all'altra.

    python fetch_model.py large-v3 [--align it]
"""

from __future__ import annotations

import fnmatch
import os
import sys
import threading
from pathlib import Path

# Come in whisperx_server.py: niente statistiche d'uso ai server di pyannote, neanche dall'installer
# (qui pyannote non si importa, ma l'allineamento passa da WhisperX, che se lo porta dietro).
os.environ.setdefault("PYANNOTE_METRICS_ENABLED", "false")

# Quello che faster-whisper scarica di un modello: il resto del repository non serve.
PATTERNS = ["config.json", "preprocessor_config.json", "model.bin", "tokenizer.json", "vocabulary.*"]


def folder_size(folder: Path) -> int:
    total = 0
    if not folder.is_dir():
        return 0
    for entry in folder.iterdir():
        try:
            total += entry.stat().st_size
        except OSError:
            pass
    return total


def expected_size(repo: str) -> int:
    try:
        from huggingface_hub import HfApi

        info = HfApi().model_info(repo, files_metadata=True)
        return sum(
            (sibling.size or 0)
            for sibling in info.siblings or []
            if any(fnmatch.fnmatch(sibling.rfilename, pattern) for pattern in PATTERNS)
        )
    except Exception:  # noqa: BLE001 — senza totale la barra gira e basta
        return 0


def fetch(name: str) -> int:
    from faster_whisper import utils

    try:
        path = utils.download_model(name, local_files_only=True)
        print(f"DONE {path}", flush=True)
        return 0
    except Exception:  # noqa: BLE001 — non c'e' ancora: si scarica
        pass

    repo = name if "/" in name else getattr(utils, "_MODELS", {}).get(name, "")
    total = expected_size(repo) if repo else 0
    blobs = None
    if repo:
        from huggingface_hub import constants

        blobs = Path(constants.HF_HUB_CACHE) / ("models--" + repo.replace("/", "--")) / "blobs"

    result: dict[str, str] = {}

    def work() -> None:
        try:
            result["path"] = str(utils.download_model(name))
        except Exception as error:  # noqa: BLE001
            result["error"] = f"{type(error).__name__}: {error}"

    thread = threading.Thread(target=work, daemon=True)
    thread.start()
    while thread.is_alive():
        done = folder_size(blobs) if blobs else 0
        print(f"PROGRESS {done} {total}", flush=True)
        thread.join(1.0)
    if "error" in result:
        print(f"ERROR {result['error']}", flush=True)
        return 1
    print(f"PROGRESS {total} {total}", flush=True)
    print(f"DONE {result['path']}", flush=True)
    return 0


def fetch_align(language: str) -> int:
    """Il modello di allineamento della lingua (wav2vec2, qualche centinaio di MB), senza barra."""
    try:
        import whisperx

        whisperx.load_align_model(language_code=language, device="cpu")
    except Exception as error:  # noqa: BLE001
        print(f"ERROR {type(error).__name__}: {error}", flush=True)
        return 1
    print(f"DONE align-{language}", flush=True)
    return 0


def main(argv: list[str]) -> int:
    os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
    if len(argv) >= 3 and argv[1] == "--align":
        return fetch_align(argv[2])
    if len(argv) < 2:
        print("ERROR quale modello?", flush=True)
        return 2
    return fetch(argv[1])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
