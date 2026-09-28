"""
I buchi di una lezione, a confronto: chi perde il parlato, il VAD, le finestre o il vocabolario?

Nelle lezioni tornate dal computer mancavano tratti lunghi di parlato normale. Il file era quello
giusto e il filtro delle allucinazioni ne toglieva due o quattro segmenti: il testo non l'aveva
scritto WhisperX. Questo programma trascrive la stessa registrazione in piu' modi e per ognuno conta
i buchi (`speech_holes` del server: voce a volume di voce, e nessun testo sopra), cosi' si vede quale
manopola li fa sparire invece di indovinarlo:

  * `current`     — come il server oggi: `VAD_OPTIONS`, finestre da 30 s, col vocabolario;
  * `vad-default` — il VAD di serie di WhisperX (0,5/0,363), quello di prima del 24/09;
  * `chunk20`     — finestre fuse dal VAD al massimo da 20 s invece di 30;
  * `no-prompt`   — senza vocabolario (`initial_prompt`), che WhisperX mette davanti a ogni finestra;
  * `fill`        — `current` piu' `fill_holes`, quello che il server fa dalla 1.0.4: e per ogni buco
                    ritrascritto il testo ritrovato, da leggere a orecchio contro l'audio.

Uso, dalla cartella `companion` e **col companion fermo** (dal menu dell'icona, «Esci»): carica il
modello sulla scheda come una lezione vera, e due modelli insieme non ci stanno. Se `/health` sulla
8765 risponde, il programma si rifiuta.

    .venv\\Scripts\\python.exe tools\\holes.py 3fa2c1d0 --language it --prompt-file vocabolario.txt ^
        --configs current,vad-default,chunk20,no-prompt,fill --out buchi.json

Il primo argomento e' l'inizio dello sha256 di un file dell'archivio (quello del computer:
`archive_root` di `config.json`, o `%LOCALAPPDATA%\\PampaNotes\\archivio`) o il percorso di un file.
Con `--config` si legge il `config.json` di un'altra copia (quella installata dal setup sta in
`%LOCALAPPDATA%\\Programs\\PampaCompanion`). L'archivio si apre in sola lettura: il programma non
scrive niente li' dentro, e puo' girare anche da un terminale dentro un'altra app (le letture di
`%LOCALAPPDATA%` vedono la cartella vera; vedi `fuori.py`).

La lezione si trascrive intera, come fa il server senza `max_minutes`: per una registrazione di
molte ore servono molti gigabyte di RAM (float32 a 16 kHz, 230 MB l'ora).
"""

from __future__ import annotations

import argparse
import contextlib
import json
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
COMPANION = HERE.parent
if str(COMPANION) not in sys.path:
    sys.path.insert(0, str(COMPANION))

import config  # noqa: E402
import whisperx_server as server  # noqa: E402

# Le prove, per nome. `prompt: False` vuol dire senza vocabolario; `fill` aggiunge `fill_holes`.
CONFIGS: dict[str, dict[str, Any]] = {
    "current": {},
    "vad-default": {"vad": {"vad_onset": 0.5, "vad_offset": 0.363}},
    "chunk20": {"chunk_size": 20},
    "no-prompt": {"prompt": False},
    "fill": {"fill": True},
}
HEALTH_URL = "http://127.0.0.1:8765/health"
# I buchi piu' lunghi che il rapporto elenca, per prova.
LONGEST = 10


def companion_running(url: str = HEALTH_URL, timeout_s: float = 2.0) -> bool:
    """Risponde qualcuno su /health? Anche un errore HTTP e' una risposta: c'e' un server."""
    try:
        with urllib.request.urlopen(url, timeout=timeout_s):
            return True
    except urllib.error.HTTPError:
        return True
    except (OSError, ValueError):
        return False


def resolve(target: str, archive_root: Path) -> tuple[Path, str]:
    """
    Il file da trascrivere e il suo nome: un percorso che esiste, o l'inizio di uno sha256
    dell'archivio. Un inizio che vale per piu' file li elenca e si ferma: indovinare quale dei due
    vorrebbe dire confrontare la lezione sbagliata.
    """
    path = Path(target)
    if path.is_file():
        return path, path.name
    prefix = target.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{4,64}", prefix):
        raise SystemExit(f"«{target}» non e' ne' un file ne' l'inizio di uno sha256")
    database = archive_root / "archive.db"
    if not database.is_file():
        raise SystemExit(f"nessun archivio in {archive_root} (manca archive.db)")
    # Sola lettura: niente `CREATE TABLE`, niente giornale, niente da spostare in un contenitore.
    with contextlib.closing(sqlite3.connect(f"{database.as_uri()}?mode=ro", uri=True)) as db:
        rows = db.execute(
            "SELECT sha256, name, ext FROM files WHERE sha256 LIKE ? ORDER BY sha256", (prefix + "%",)
        ).fetchall()
    if not rows:
        raise SystemExit(f"nessun file dell'archivio comincia con {prefix}")
    if len(rows) > 1:
        listed = "\n".join(f"  {sha}  {name}" for sha, name, _ in rows[:20])
        raise SystemExit(f"{prefix} vale per {len(rows)} file, scrivine di piu':\n{listed}")
    sha, name, ext = rows[0]
    blob = archive_root / "blobs" / sha[:2] / f"{sha}.{ext}"
    if not blob.is_file():
        raise SystemExit(f"{sha[:12]} ha la riga ma non il file ({blob})")
    return blob, name


def prepare(settings: dict[str, Any]) -> None:
    """
    Modello, calcolo e lotto come li sceglierebbe il server su questa scheda (`configure`, senza
    aprire l'archivio e senza toccare il resto dello stato): la prova deve somigliare a una lezione vera.
    """
    device = config.resolve_device(settings["device"])
    tunables = server.tunables_of(settings)
    if tunables["vram_mode"] not in server.VRAM_MODES or (tunables["vram_mode"] == "manual" and not tunables["vram_gb"]):
        tunables["vram_mode"] = "auto"
    gpu = server.detect_gpu() if device == "cuda" else None
    server.STATE.update(device=device, tunables=tunables, gpu=gpu)
    server.apply_plan(server.decide_vram(tunables, device, gpu))
    print(f"  {server.describe_plan(server.STATE['vram'])}", flush=True)


def summary(
    segments: list[dict], energies: Any, frame_s: float, total_s: float, prompt: str | None,
) -> dict[str, Any]:
    """Quello che il rapporto dice di una prova: dopo il filtro delle allucinazioni, come nella risposta."""
    kept, dropped = server.drop_hallucinations(segments, energies, frame_s, prompt=prompt)
    holes = server.speech_holes(kept, energies, frame_s, total_s)
    longest = sorted(holes, key=lambda hole: hole[0] - hole[1])[:LONGEST]
    return {
        "segments": len(kept),
        "words": sum(len(server.normalized_words(segment.get("text") or "")) for segment in kept),
        "holes": len(holes),
        "hole_minutes": round(sum(end - start for start, end, _ in holes) / 60, 2),
        "longest_holes": [
            f"[{server.clock(start)}–{server.clock(end)}] voce {round(share * 100)}%" for start, end, share in longest
        ],
        "dropped": dropped,
    }


def run_config(
    name: str,
    audio: Any,
    energies: Any,
    total_s: float,
    language: str | None,
    prompt: str | None,
    engine: server.Engine,
    base: tuple[list[dict], float] | None = None,
) -> tuple[dict[str, Any], list[dict], float]:
    """
    Una prova: [run_job] con le sue manopole, poi (per `fill`) [fill_holes]. [base] sono i segmenti
    e i secondi di `current`, se gia' fatta: `fill` riparte da li' invece di ritrascrivere tutto.
    Torna il rapporto, i segmenti grezzi e i secondi della sola trascrizione.
    """
    spec = CONFIGS[name]
    frame_s = server.FRAME_MS / 1000
    use_prompt = None if spec.get("prompt") is False else prompt
    batch_size, device = server.STATE["batch_size"], server.STATE["device"]
    progress = server.JobProgress()
    started = time.time()
    if spec.get("fill") and base is not None:
        segments, first_s = list(base[0]), base[1]
    else:
        job = server.run_job(
            audio, language, engine, batch_size, device, progress,
            prompt=use_prompt, vad=spec.get("vad"), chunk_size=spec.get("chunk_size"),
        )
        segments = job["segments"]
        first_s = time.time() - started
    raw = list(segments)
    report: dict[str, Any] = {}
    if spec.get("fill"):
        filled: list[dict] = []
        fill_started = time.time()
        segments, stats = server.fill_holes(
            server.LoadedAudio(audio, server_sample_rate()), segments, language, engine, batch_size, device, progress,
            prompt=use_prompt, energies=energies, report=filled,
        )
        report["fill"] = dict(stats, seconds_taken=round(time.time() - fill_started, 1))
        report["filled"] = [
            {
                "at": f"{server.clock(hole['start'])}–{server.clock(hole['end'])}",
                "speech": hole["speech"],
                "words": hole["words"],
                "text": hole["text"],
            }
            for hole in filled
        ]
        first_s += report["fill"]["seconds_taken"]
    engine.release()
    report = {**summary(segments, energies, frame_s, total_s, use_prompt), "seconds": round(first_s, 1), **report}
    return report, raw, first_s


def server_sample_rate() -> int:
    from whisperx.audio import SAMPLE_RATE

    return SAMPLE_RATE


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="I buchi di una lezione con VAD, finestre e vocabolario diversi.")
    parser.add_argument("target", help="l'inizio dello sha256 di un file dell'archivio, o il percorso di un file")
    parser.add_argument("--prompt-file", dest="prompt_file", help="il «Vocabolario» dell'app, in un file di testo")
    parser.add_argument("--language", help="it, en...: senza, la si riconosce una volta per tutte le prove")
    parser.add_argument("--configs", default=",".join(CONFIGS), help=f"quali prove, separate da virgole: {', '.join(CONFIGS)}")
    parser.add_argument("--out", default="holes-report.json", help="dove scrivere il rapporto (JSON)")
    parser.add_argument("--config", help="il config.json da cui leggere modello e archivio (di serie quello accanto al server)")
    parser.add_argument("--archive", help="la cartella dell'archivio, se non e' quella di config.json")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    names = [name.strip() for name in args.configs.split(",") if name.strip()]
    unknown = [name for name in names if name not in CONFIGS]
    if unknown or not names:
        print(f"prove sconosciute: {', '.join(unknown) or '(nessuna)'}; ci sono {', '.join(CONFIGS)}", file=sys.stderr)
        return 2
    if companion_running():
        print(
            "Il companion e' acceso (risponde su 127.0.0.1:8765). Fermalo dal menu dell'icona accanto "
            "all'orologio («Esci») e riprova: questa prova carica il modello sulla scheda come una lezione vera.",
            file=sys.stderr,
        )
        return 3

    settings = config.load(Path(args.config)) if args.config else config.load()
    archive_root = Path(args.archive or settings["archive_root"])
    path, label = resolve(args.target, archive_root)
    prompt = None
    if args.prompt_file:
        prompt = Path(args.prompt_file).read_text(encoding="utf-8").strip() or None
    print(f"  {label} ({path})", flush=True)

    prepare(settings)
    rate = server_sample_rate()
    started = time.time()
    audio = server.load_audio(path, rate, expected_s=server.probe_duration(path))
    total_s = len(audio) / rate
    frame_s = server.FRAME_MS / 1000
    energies = server.frame_energies(audio, rate)
    print(f"  {server.clock(total_s)} di audio, decodificato in {time.time() - started:.0f} s", flush=True)

    engine = server.Engine()
    language = args.language or server.spoken_language(server.LoadedAudio(audio, rate), frame_s, engine, server.JobProgress())
    print(f"  lingua: {language or 'la riconosce ogni prova da se'}", flush=True)

    results: dict[str, Any] = {}
    current: tuple[list[dict], float] | None = None
    for name in names:
        print(f"  {name}...", flush=True)
        report, raw, seconds = run_config(name, audio, energies, total_s, language, prompt, engine, base=current)
        if name == "current":
            current = (raw, seconds)
        results[name] = report
        print(
            f"    {report['segments']} segmenti, {report['words']} parole, {report['holes']} buchi "
            f"({report['hole_minutes']} min), {report['seconds']} s",
            flush=True,
        )
        for line in report["longest_holes"][:3]:
            print(f"      {line}", flush=True)

    answer = {
        "file": label,
        "path": str(path),
        "audio": server.clock(total_s),
        "audio_s": round(total_s, 1),
        "language": language,
        "prompt": prompt,
        "model": server.STATE["name"],
        "compute_type": server.STATE["compute_type"],
        "batch_size": server.STATE["batch_size"],
        "vad_options": server.VAD_OPTIONS,
        "configs": results,
    }
    out = Path(args.out)
    out.write_text(json.dumps(answer, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"  rapporto in {out.resolve()}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
