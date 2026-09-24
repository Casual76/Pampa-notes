"""
L'archivio dei file originali sul computer: registrazioni e documenti, indirizzati per contenuto.

Perche' esiste. Le registrazioni pesano sessanta megabyte l'ora e un `.sdocx` di Samsung Notes
arriva a mezzo giga: dopo un semestre stanno solo sul dispositivo che li ha fatti, e un telefono
perso e' un semestre perso. Il computer ha i dischi; il cloud costerebbe e non serve — vedi il
piano. Quindi i file salgono qui, da casa o da Tailscale, e qui restano.

**Per contenuto, non per nome.** Il nome del file e' `<sha256>.<ext>`: lo stesso file caricato due
volte — dal tablet e poi dal telefono — occupa una volta sola, e un caricamento interrotto a meta'
non lascia un file mezzo scritto con il nome di quello buono, perche' l'hash lo si verifica
*mentre* si scrive e il file prende il suo nome solo alla fine, se torna. E' anche il motivo per
cui `PUT` e' idempotente: ripetere un caricamento non puo' fare danni.

L'indice e' un SQLite accanto ai file, con il nome originale e il MIME: serve a ridare il file con
il nome giusto e a far dire al menu «12.480 file, 214 GB» senza camminare le cartelle.

La cartella di default sta fuori dal progetto e fuori da Documenti: `C:\\VibeCoded Projects` e'
sincronizzata da Google Drive, e gigabyte di audio dentro Drive sono esattamente quello che
questo archivio esiste per evitare.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import contextlib
import datetime
import hashlib
import json
import logging
import os
import re
import shutil
import sqlite3
import struct
import subprocess
import threading
import time
import urllib.parse
import zipfile
from pathlib import Path
from typing import Any, AsyncIterator, Callable

from fastapi import APIRouter, HTTPException, Request, Response
from fastapi.responses import FileResponse

log = logging.getLogger("pampa")

SHA256 = re.compile(r"^[0-9a-f]{64}$")

# I caricamenti scrivono su thread loro, non su quelli di serie del ciclo di eventi: quelli li usa
# anche la trascrizione (`asyncio.to_thread`), e un tablet che perde la rete a meta' di un file
# teneva un thread fermo ad aspettare il blocco dopo. Con abbastanza caricamenti appesi i thread
# finivano, e la lezione successiva non partiva piu'. Qui ne restano quattro, e sono solo dell'archivio.
EXECUTOR = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="archivio")

# Quanto si aspetta un blocco del corpo prima di dichiarare il caricamento morto. Il telefono manda
# decine di kilobyte alla volta: due minuti senza niente non sono una rete lenta, sono una rete che
# non c'e' piu'.
CHUNK_TIMEOUT_S = 120

# Un `.part` piu' vecchio di cosi' e' di un caricamento che non finira' mai (il processo e' morto a
# meta'). Piu' giovane, potrebbe essere di un caricamento vivo di un'altra istanza appena partita.
STALE_PART_S = 3600


class StalledUpload(Exception):
    """Il corpo della richiesta ha smesso di arrivare."""


class BlobInUse(OSError):
    """Il file non si cancella adesso (su Windows: qualcuno lo tiene aperto). Si riprova dopo."""


# Quanto dire di aspettare a chi voleva cancellare un file in uso: il tempo di finire uno scaricamento.
BLOB_IN_USE_RETRY_S = 60

# L'estensione dal MIME, quando il nome non ne ha una buona. Rispecchia `AppFiles.extensionFor`.
EXT_BY_MIME = {
    "audio/mp4": "m4a", "audio/m4a": "m4a", "audio/x-m4a": "m4a", "audio/aac": "m4a",
    "audio/mpeg": "mp3", "audio/mp3": "mp3",
    "audio/wav": "wav", "audio/x-wav": "wav", "audio/wave": "wav",
    "audio/ogg": "ogg", "application/ogg": "ogg", "audio/opus": "opus",
    "audio/flac": "flac", "audio/x-flac": "flac", "audio/webm": "webm", "video/webm": "webm",
    "video/mp4": "mp4", "application/pdf": "pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
    "application/sdoc": "sdocx", "application/zip": "zip",
    "text/markdown": "md", "text/x-markdown": "md", "text/plain": "txt",
    "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp",
}


def extension_for(name: str, mime: str) -> str:
    """La coda dopo l'ultimo punto vale come estensione solo se comincia per lettera ed e' corta."""
    tail = name.rsplit(".", 1)[1].lower() if "." in name else ""
    if 1 <= len(tail) <= 5 and tail[0].isalpha() and tail.isalnum():
        return tail
    return EXT_BY_MIME.get(mime.lower(), "bin")


class Archive:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.blobs = root / "blobs"
        self.blobs.mkdir(parents=True, exist_ok=True)
        (root / "tmp").mkdir(exist_ok=True)
        self.sweep_parts()
        # Un lucchetto perche' gli endpoint sincroni girano in un pool di thread, e sqlite vuole
        # una connessione per thread o un lucchetto: il lucchetto e' meno codice.
        self.lock = threading.Lock()
        self.db = sqlite3.connect(root / "archive.db", check_same_thread=False)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS files (sha256 TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL, "
            "ext TEXT NOT NULL, size INTEGER NOT NULL, added_at REAL NOT NULL)"
        )
        self.db.commit()

    def sweep_parts(self, older_than_s: float = STALE_PART_S) -> int:
        """
        Via i `.part` rimasti da caricamenti che non sono finiti.

        Di solito li toglie il `finally` di [store]; restano quando il processo muore a meta' —
        il computer spento, l'icona chiusa — e senza questo giro restavano per sempre, a volte
        mezzo giga l'uno. Si tolgono solo quelli vecchi: `tray.py` apre l'archivio prima di
        guardare se la porta e' occupata, e un file giovane potrebbe essere di un caricamento vivo.
        """
        removed = 0
        now = time.time()
        for part in (self.root / "tmp").glob("*.part"):
            try:
                if now - part.stat().st_mtime > older_than_s:
                    part.unlink()
                    removed += 1
            except OSError:
                continue
        if removed:
            log.info("archivio: tolti %d caricamenti rimasti a meta'", removed)
        return removed

    def path_for(self, sha256: str, ext: str) -> Path:
        return self.blobs / sha256[:2] / f"{sha256}.{ext}"

    def _row(self, sha256: str) -> dict[str, Any] | None:
        """La riga cosi' com'e', col percorso dove il file dovrebbe stare, che ci sia o no."""
        with self.lock:
            row = self.db.execute("SELECT sha256, name, mime, ext, size, added_at FROM files WHERE sha256 = ?", (sha256,)).fetchone()
        if row is None:
            return None
        record = dict(zip(("sha256", "name", "mime", "ext", "size", "added_at"), row))
        record["path"] = self.path_for(record["sha256"], record["ext"])
        return record

    def get(self, sha256: str) -> dict[str, Any] | None:
        record = self._row(sha256)
        if record is None:
            return None
        path = record["path"]
        # Una riga senza il suo file non si cancella: si risponde «non c'e'» e basta. Il file puo'
        # essere solo invisibile a questo processo — il 24/09 un companion avviato dall'app di Claude
        # aveva scritto 80 registrazioni nella copia di AppData che Windows tiene per quell'app, e
        # quello avviato da Windows, non vedendole, cancellava le righe una per una. Se torna
        # visibile (lo si rimette al suo posto, o riparte il processo giusto) la riga e' ancora li'.
        if not path.exists():
            log.warning("archivio: %s ha la riga ma non il file (%s)", sha256[:12], path)
            return None
        return record

    def remove(self, sha256: str) -> bool:
        """
        Via il file e poi la riga. Chi lo chiede sa che nessuna nota lo cita piu'.

        In quest'ordine perche' su Windows un file aperto non si cancella: un telefono che lo sta
        scaricando, una trascrizione che lo legge. Con la riga tolta per prima, il file restava sul
        disco senza nessuno che lo sapesse — ne' [get], ne' le statistiche, ne' una `DELETE` ripetuta
        l'avrebbero piu' trovato. Adesso la riga resta, e chi ha chiesto si sente dire [BlobInUse]:
        riprova fra poco e trova tutto com'era.

        La riga si legge da se', non con [get]: [get] dice «non c'e'» per una riga senza file, e una
        `DELETE` su quella rispondeva 404 lasciandola li' per sempre, contata nelle statistiche.
        Qui chi chiede vuole che il file sparisca: se non c'e' gia', resta da togliere la riga.
        """
        record = self._row(sha256)
        if record is None:
            return False
        try:
            record["path"].unlink(missing_ok=True)
        except OSError as error:
            raise BlobInUse(f"il file e' in uso, riprova fra poco: {error}") from error
        with self.lock:
            self.db.execute("DELETE FROM files WHERE sha256 = ?", (sha256,))
            self.db.commit()
        return True

    def stats(self) -> tuple[int, int]:
        """Quanti file e quanti byte, contando solo le righe il cui file c'e' davvero: vedi [inventory]."""
        count, size, _ = self.inventory()
        return count, size

    def inventory(self) -> tuple[int, int, int]:
        """
        File presenti, i loro byte, e le righe il cui file non si vede ([get] le tratta come assenti).

        Prima si contavano le righe: una riga senza file (vedi [get]) diceva al tray e al telefono
        che l'archivio aveva una registrazione che nessuno poteva scaricare. Le mancanti si dicono a
        parte, perche' spesso non sono perse ma invisibili a questo processo.
        """
        with self.lock:
            rows = self.db.execute("SELECT sha256, ext, size FROM files").fetchall()
        count = size = missing = 0
        for sha256, ext, length in rows:
            if self.path_for(sha256, ext).exists():
                count += 1
                size += int(length)
            else:
                missing += 1
        return count, size, missing

    def store(self, sha256: str, name: str, mime: str, chunks: Any, expected_size: int | None = None) -> dict[str, Any]:
        """
        Scrive i blocchi verificando l'hash strada facendo. Il file entra nell'archivio solo se
        l'hash torna: fino a quel momento e' un temporaneo con un nome suo.
        """
        ext = extension_for(name, mime)
        target = self.path_for(sha256, ext)
        temp = self.root / "tmp" / f"{sha256}.{os.getpid()}.{threading.get_ident()}.part"
        digest = hashlib.sha256()
        size = 0
        try:
            with temp.open("wb") as out:
                for chunk in chunks:
                    digest.update(chunk)
                    out.write(chunk)
                    size += len(chunk)
            if digest.hexdigest() != sha256:
                raise ValueError("il contenuto non corrisponde all'hash dichiarato")
            if expected_size is not None and size != expected_size:
                raise ValueError(f"attesi {expected_size} byte, arrivati {size}")
            target.parent.mkdir(parents=True, exist_ok=True)
            _place(temp, target, size)
        finally:
            if temp.exists():
                temp.unlink()
        with self.lock:
            self.db.execute(
                "INSERT OR REPLACE INTO files (sha256, name, mime, ext, size, added_at) VALUES (?, ?, ?, ?, ?, ?)",
                (sha256, name, mime, ext, size, time.time()),
            )
            self.db.commit()
        log.info("archiviato %s (%.1f MB) come %s", name, size / 1_000_000, target.name)
        return {"sha256": sha256, "name": name, "mime": mime, "ext": ext, "size": size, "path": target}


# Quante volte, e ogni quanto, si riprova a dare al `.part` il nome del blob quando Windows dice di no.
PLACE_ATTEMPTS = 5
PLACE_PAUSE_S = 0.2


def _place(temp: Path, target: Path, size: int) -> None:
    """
    Il `.part` verificato prende il nome del blob — a meno che il blob ci sia gia'.

    Il nome e' l'impronta, quindi un blob con quel nome e quella misura *e'* questo file: il `.part`
    si butta. Succedeva davvero: il telefono archiviava una registrazione con un `PUT` mentre la
    stessa partiva da trascrivere con `archive=1`, e il secondo dei due `os.replace` trovava il
    blob appena scritto — o aperto da ffmpeg — e Windows rispondeva `WinError 5`: un 500 per un
    file che nell'archivio c'era gia'. Se il blob non c'e', un `PermissionError` e' quasi sempre
    un altro processo che tiene il file un istante (l'antivirus, l'indicizzatore, l'altro
    caricamento a meta' rinomina): si riprova qualche volta, riguardando ogni volta se nel
    frattempo il blob e' arrivato.
    """
    refused: PermissionError | None = None
    for attempt in range(PLACE_ATTEMPTS + 1):
        with contextlib.suppress(OSError):
            if target.stat().st_size == size:
                log.info("archivio: %s c'era gia', tengo quello", target.name)
                return
        if attempt == PLACE_ATTEMPTS:
            break
        if attempt:
            time.sleep(PLACE_PAUSE_S * attempt)
        try:
            os.replace(temp, target)
            return
        except PermissionError as error:
            refused = error
    assert refused is not None
    raise refused


ARCHIVE: Archive | None = None


def open_archive(root: str | Path) -> Archive:
    global ARCHIVE
    ARCHIVE = Archive(Path(root))
    return ARCHIVE


def current() -> Archive:
    if ARCHIVE is None:
        raise HTTPException(status_code=503, detail="archivio non configurato")
    return ARCHIVE


# --- le date vere di un file -------------------------------------------------------------------------
#
# La home dell'app mostrava il giorno dell'import invece di quello in cui la nota era stata scritta.
# Il telefono le date le legge da se' quando il file ce l'ha; per quelli che stanno solo qui chiede
# `GET /v1/files/<sha>/meta`, e il computer apre il file al posto suo invece di mandarlo.

# Prima del 2010 Samsung Notes non esisteva: un valore piu' vecchio e' un campo letto male.
SDOCX_EPOCH_US = 1_262_304_000 * 1_000_000  # 2010-01-01
# Una registrazione di prima del 2000 e' un contenitore senza data (1904 o 1970): come `RecordingDate`.
AUDIO_EPOCH_US = 946_684_800 * 1_000_000  # 2000-01-01
# Un'ora oltre l'orologio di qui, per un tablet un po' avanti; oltre, e' nel futuro.
FUTURE_SLACK_US = 3600 * 1_000_000
FFPROBE_TIMEOUT_S = 10
AUDIO_EXTENSIONS = frozenset({"m4a", "mp3", "wav", "ogg", "opus", "flac", "webm", "aac", "amr", "3gp", "mp4"})


def _int64_at(data: bytes, offset: int) -> int | None:
    if len(data) < offset + 8:
        return None
    return struct.unpack_from("<q", data, offset)[0]


def _plausible_pair(created: int | None, modified: int | None, now_us: int) -> bool:
    if created is None or modified is None:
        return False
    limit = now_us + FUTURE_SLACK_US
    return SDOCX_EPOCH_US <= created <= modified <= limit


def sdocx_dates(path: Path, now_us: int | None = None) -> tuple[int | None, int | None]:
    """
    Creazione e ultima modifica di una nota di Samsung Notes, in microsecondi, o `(None, None)`.

    Decodificato da `fichte.sdocx`: in `end_tag.bin` (148 byte) l'ultima modifica sta a +8 e la
    creazione a +46, int64 little-endian in microsecondi; gli stessi valori stanno in `note.note` a
    +24 (creazione) e +32 (modifica), che vale da riserva. Le date dello zip no: quelle dicono quando
    la nota e' stata condivisa. Una coppia che non torna — prima del 2010, nel futuro, creata dopo
    l'ultima modifica — e' un formato diverso da quello che si conosce, e non si indovina.
    """
    now_us = int(time.time() * 1_000_000) if now_us is None else now_us
    try:
        with zipfile.ZipFile(path) as bundle:
            names = set(bundle.namelist())
            candidates = []
            if "end_tag.bin" in names:
                data = bundle.read("end_tag.bin")
                candidates.append((_int64_at(data, 46), _int64_at(data, 8)))
            if "note.note" in names:
                with bundle.open("note.note") as note:
                    data = note.read(40)
                candidates.append((_int64_at(data, 24), _int64_at(data, 32)))
    except (OSError, zipfile.BadZipFile, KeyError):
        return None, None
    for created, modified in candidates:
        if _plausible_pair(created, modified, now_us):
            return created, modified
    return None, None


def _parse_creation_time(value: str) -> int | None:
    try:
        moment = datetime.datetime.fromisoformat(value.strip())
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=datetime.timezone.utc)
    return int(moment.timestamp() * 1_000_000)


def audio_recorded_us(path: Path, now_us: int | None = None) -> int | None:
    """
    Quando e' stata fatta una registrazione, dal `creation_time` del contenitore, o None.

    Lo legge ffprobe, se c'e': legge solo l'intestazione, e dieci secondi bastano anche a un file da
    un'ora. Sul computer installato col setup pero' ffprobe non c'e' — `bin/` ha solo il ffmpeg di
    imageio-ffmpeg — e allora lo si chiede a ffmpeg stesso ([_ffmpeg_creation_times]). Senza
    nessuno dei due, o senza la data, None: il telefono prova il nome del file, che non chiede di
    aprire niente.
    """
    now_us = int(time.time() * 1_000_000) if now_us is None else now_us
    probe = shutil.which("ffprobe")
    if probe is not None:
        values = _ffprobe_creation_times(probe, path)
    else:
        ffmpeg = shutil.which("ffmpeg")
        if ffmpeg is None:
            _warn_once("senza_ffmpeg", "ne' ffprobe ne' ffmpeg trovati: la data delle registrazioni resta sconosciuta")
            return None
        values = _ffmpeg_creation_times(ffmpeg, path)
    for value in values:
        recorded = _parse_creation_time(value)
        if recorded is not None and AUDIO_EPOCH_US <= recorded <= now_us + FUTURE_SLACK_US:
            return recorded
    return None


# Gli avvisi gia' scritti: la stessa riga a ogni nota del backfill riempiva il registro e non diceva
# niente di piu' della prima volta.
_WARNED: set[str] = set()


def _warn_once(key: str, message: str, *args: Any) -> None:
    if key in _WARNED:
        return
    _WARNED.add(key)
    log.warning(message, *args)


def _run_quiet(command: list[str]) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        command,
        capture_output=True,
        timeout=FFPROBE_TIMEOUT_S,
        # Il server gira spesso senza console (pythonw): senza, ogni domanda aprirebbe una finestra nera.
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


def _ffprobe_creation_times(probe: str, path: Path) -> list[str]:
    """I `creation_time` del contenitore e delle tracce, nell'ordine in cui ffprobe li dice."""
    try:
        answer = _run_quiet(
            [probe, "-v", "error", "-print_format", "json", "-show_entries", "format_tags=creation_time:stream_tags=creation_time", str(path)]
        )
        data = json.loads(answer.stdout or b"{}")
    except (OSError, subprocess.SubprocessError, ValueError) as error:
        log.warning("ffprobe non ha risposto su %s: %s", path.name, error)
        return []
    tags = [(data.get("format") or {}).get("tags") or {}]
    tags += [stream.get("tags") or {} for stream in data.get("streams") or []]
    return [entry["creation_time"] for entry in tags if isinstance(entry.get("creation_time"), str)]


# Come ffmpeg scrive i metadati quando gli si da' solo un ingresso: «    creation_time   : 2025-…Z».
_CREATION_LINE = re.compile(r"^\s*creation_time\s*:\s*(\S+)\s*$", re.MULTILINE)


def _ffmpeg_creation_times(ffmpeg: str, path: Path) -> list[str]:
    """
    Gli stessi `creation_time`, da `ffmpeg -i`: senza un'uscita ffmpeg descrive l'ingresso su stderr
    e si ferma con un errore («At least one output file must be specified»), che qui e' la normalita'.
    Il formato del testo non e' un contratto come il JSON di ffprobe, ma la riga dei metadati e' la
    stessa da quindici anni; se un giorno cambiasse, si torna a None, come senza ffprobe.
    """
    try:
        answer = _run_quiet([ffmpeg, "-hide_banner", "-nostdin", "-i", str(path)])
    except (OSError, subprocess.SubprocessError) as error:
        log.warning("ffmpeg non ha risposto su %s: %s", path.name, error)
        return []
    return _CREATION_LINE.findall((answer.stderr or b"").decode("utf-8", errors="replace"))


def file_kind(record: dict[str, Any]) -> str:
    """"sdocx", "audio" o "other": dal contenuto per lo zip, dal tipo per il resto."""
    path = record["path"]
    if record["ext"] == "sdocx" or record["mime"] == "application/sdoc" or zipfile.is_zipfile(path):
        with contextlib.suppress(OSError, zipfile.BadZipFile):
            with zipfile.ZipFile(path) as bundle:
                names = set(bundle.namelist())
            if "note.note" in names or "end_tag.bin" in names:
                return "sdocx"
    mime = record["mime"].lower()
    if mime.startswith("audio/") or record["ext"] in AUDIO_EXTENSIONS:
        return "audio"
    return "other"


def file_meta(record: dict[str, Any]) -> dict[str, Any]:
    """La risposta di `GET /v1/files/<sha>/meta`."""
    kind = file_kind(record)
    created = modified = recorded = None
    if kind == "sdocx":
        created, modified = sdocx_dates(record["path"])
    elif kind == "audio":
        recorded = audio_recorded_us(record["path"])
    return {"sha256": record["sha256"], "kind": kind, "created_us": created, "modified_us": modified, "recorded_us": recorded}


def original_name(request: Request) -> str:
    """Il nome originale viaggia in un header, percent-encoded: gli header sono ASCII e i nomi no."""
    raw = request.headers.get("x-pampa-name", "")
    # `unquote_plus`: la prima versione dell'app codificava lo spazio come `+` (e il `+` vero come
    # `%2B`), quindi leggere il `+` come spazio e' giusto per lei e innocuo per quelle dopo.
    name = urllib.parse.unquote_plus(raw).strip() if raw else ""
    return name or "file"


def build_router(check_token: Callable[[Request], None]) -> APIRouter:
    """
    Le rotte dell'archivio. `check_token` qui dice solo «e' il proprietario?»: le credenziali le ha
    gia' guardate il server prima di leggere il corpo (`AuthGate` in whisperx_server.py), cosi' un
    `PUT` senza permesso viene rifiutato prima di mezzo giga di upload, non dopo.
    """
    router = APIRouter(prefix="/v1/files")

    def valid(sha256: str) -> str:
        if not SHA256.match(sha256):
            raise HTTPException(status_code=400, detail="non e' uno sha256")
        return sha256

    @router.get("")
    def stats(request: Request) -> dict[str, Any]:
        check_token(request)
        count, size, missing = current().inventory()
        return {"count": count, "bytes": size, "missing": missing, "root": str(current().root)}

    @router.head("/{sha256}")
    def head(request: Request, sha256: str) -> Response:
        check_token(request)
        record = current().get(valid(sha256))
        if record is None:
            raise HTTPException(status_code=404, detail="non in archivio")
        return Response(status_code=200, headers={"Content-Length": str(record["size"]), "Content-Type": record["mime"]})

    @router.put("/{sha256}")
    async def put(request: Request, sha256: str) -> dict[str, Any]:
        """
        Il corpo e' il file, cosi' com'e', niente multipart: sono gigabyte e un solo campo.

        Un file gia' presente non si riscrive, ma il corpo si legge lo stesso fino in fondo: e'
        l'unico modo di rispondere 200 su una connessione HTTP/1.1 senza troncarla al mittente.
        """
        check_token(request)
        archive = current()
        sha = valid(sha256)
        if archive.get(sha) is not None:
            async for _ in request.stream():
                pass
            return {"stored": False, "existed": True}

        name = original_name(request)
        mime = request.headers.get("content-type", "application/octet-stream").split(";")[0].strip()
        expected = request.headers.get("content-length")
        # I blocchi arrivano da un generatore asincrono e la scrittura e' sincrona: si raccolgono
        # in una lista di blocchi e si consegnano in blocco? No: un file da mezzo giga non deve
        # stare in memoria. Si scrive man mano, con lo stesso codice di [Archive.store].
        chunks = _sync_chunks(request.stream())
        try:
            record = await _run_blocking(archive.store, sha, name, mime, chunks, int(expected) if expected else None)
        except StalledUpload as error:
            raise HTTPException(status_code=408, detail=str(error)) from error
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return {"stored": True, "size": record["size"]}

    @router.get("/{sha256}/meta")
    def meta(request: Request, sha256: str) -> dict[str, Any]:
        """Le date vere del file (vedi [file_meta]), senza mandarlo. Solo il proprietario, come il resto."""
        check_token(request)
        record = current().get(valid(sha256))
        if record is None:
            raise HTTPException(status_code=404, detail="non in archivio")
        return file_meta(record)

    @router.get("/{sha256}")
    def get(request: Request, sha256: str) -> Response:
        """Con `Range`: il lettore salta al minuto quaranta senza scaricare i primi trentanove."""
        check_token(request)
        record = current().get(valid(sha256))
        if record is None:
            raise HTTPException(status_code=404, detail="non in archivio")
        return FileResponse(record["path"], media_type=record["mime"], filename=record["name"])

    @router.delete("/{sha256}")
    def delete(request: Request, sha256: str) -> dict[str, Any]:
        """Un originale sostituito da una versione piu' nuova: quello vecchio non serve piu' a nessuno."""
        check_token(request)
        try:
            removed = current().remove(valid(sha256))
        except BlobInUse as error:
            raise HTTPException(
                status_code=503, detail="file_in_use", headers={"Retry-After": str(BLOB_IN_USE_RETRY_S)}
            ) from error
        if not removed:
            raise HTTPException(status_code=404, detail="non in archivio")
        log.info("tolto dall'archivio %s", sha256[:12])
        return {"removed": True}

    return router


def _sync_chunks(stream: AsyncIterator[bytes], timeout_s: float | None = None) -> Any:
    """
    Un iteratore sincrono sopra lo stream asincrono della richiesta.

    Il lavoro di scrittura gira in un thread (vedi [_run_blocking]) e da un thread non si puo'
    fare `await`: ogni blocco lo si chiede al ciclo di eventi e lo si aspetta. E' un rimbalzo per
    blocco, ma i blocchi sono da decine di kilobyte e il disco e' comunque piu' lento.

    Ogni blocco si aspetta al massimo `timeout_s` (di serie [CHUNK_TIMEOUT_S]): senza, un
    caricamento rimasto appeso teneva il suo thread per sempre. Scaduto il tempo, la richiesta del
    blocco si annulla e il caricamento finisce con [StalledUpload]: il `.part` lo toglie [Archive.store].
    """
    loop = asyncio.get_running_loop()
    iterator = stream.__aiter__()
    limit = CHUNK_TIMEOUT_S if timeout_s is None else timeout_s

    def generator() -> Any:
        while True:
            future = asyncio.run_coroutine_threadsafe(iterator.__anext__(), loop)
            try:
                chunk = future.result(timeout=limit)
            except StopAsyncIteration:
                return
            except concurrent.futures.TimeoutError as error:
                future.cancel()
                raise StalledUpload(f"nessun dato da {limit:.0f} s: caricamento interrotto") from error
            if chunk:
                yield chunk

    return generator()


async def _run_blocking(function: Callable[..., Any], *args: Any) -> Any:
    return await asyncio.get_running_loop().run_in_executor(EXECUTOR, function, *args)
