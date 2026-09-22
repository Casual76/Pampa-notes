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

import hashlib
import logging
import os
import re
import sqlite3
import threading
import time
import urllib.parse
from pathlib import Path
from typing import Any, AsyncIterator, Callable

from fastapi import APIRouter, HTTPException, Request, Response
from fastapi.responses import FileResponse

log = logging.getLogger("pampa")

SHA256 = re.compile(r"^[0-9a-f]{64}$")

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
        # Un lucchetto perche' gli endpoint sincroni girano in un pool di thread, e sqlite vuole
        # una connessione per thread o un lucchetto: il lucchetto e' meno codice.
        self.lock = threading.Lock()
        self.db = sqlite3.connect(root / "archive.db", check_same_thread=False)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS files (sha256 TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL, "
            "ext TEXT NOT NULL, size INTEGER NOT NULL, added_at REAL NOT NULL)"
        )
        self.db.commit()

    def path_for(self, sha256: str, ext: str) -> Path:
        return self.blobs / sha256[:2] / f"{sha256}.{ext}"

    def get(self, sha256: str) -> dict[str, Any] | None:
        with self.lock:
            row = self.db.execute("SELECT sha256, name, mime, ext, size, added_at FROM files WHERE sha256 = ?", (sha256,)).fetchone()
        if row is None:
            return None
        record = dict(zip(("sha256", "name", "mime", "ext", "size", "added_at"), row))
        path = self.path_for(record["sha256"], record["ext"])
        # Una riga senza il suo file e' una riga bugiarda: chi l'ha cancellato a mano ha vinto.
        if not path.exists():
            with self.lock:
                self.db.execute("DELETE FROM files WHERE sha256 = ?", (sha256,))
                self.db.commit()
            return None
        record["path"] = path
        return record

    def remove(self, sha256: str) -> bool:
        """Via la riga e il file. Chi lo chiede sa che nessuna nota lo cita piu'."""
        record = self.get(sha256)
        if record is None:
            return False
        with self.lock:
            self.db.execute("DELETE FROM files WHERE sha256 = ?", (sha256,))
            self.db.commit()
        record["path"].unlink(missing_ok=True)
        return True

    def stats(self) -> tuple[int, int]:
        with self.lock:
            count, size = self.db.execute("SELECT COUNT(*), COALESCE(SUM(size), 0) FROM files").fetchone()
        return int(count), int(size)

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
            os.replace(temp, target)
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


ARCHIVE: Archive | None = None


def open_archive(root: str | Path) -> Archive:
    global ARCHIVE
    ARCHIVE = Archive(Path(root))
    return ARCHIVE


def current() -> Archive:
    if ARCHIVE is None:
        raise HTTPException(status_code=503, detail="archivio non configurato")
    return ARCHIVE


def original_name(request: Request) -> str:
    """Il nome originale viaggia in un header, percent-encoded: gli header sono ASCII e i nomi no."""
    raw = request.headers.get("x-pampa-name", "")
    # `unquote_plus`: la prima versione dell'app codificava lo spazio come `+` (e il `+` vero come
    # `%2B`), quindi leggere il `+` come spazio e' giusto per lei e innocuo per quelle dopo.
    name = urllib.parse.unquote_plus(raw).strip() if raw else ""
    return name or "file"


def build_router(check_token: Callable[[Request], None]) -> APIRouter:
    router = APIRouter(prefix="/v1/files")

    def valid(sha256: str) -> str:
        if not SHA256.match(sha256):
            raise HTTPException(status_code=400, detail="non e' uno sha256")
        return sha256

    @router.get("")
    def stats(request: Request) -> dict[str, Any]:
        check_token(request)
        count, size = current().stats()
        return {"count": count, "bytes": size, "root": str(current().root)}

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
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return {"stored": True, "size": record["size"]}

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
        removed = current().remove(valid(sha256))
        if not removed:
            raise HTTPException(status_code=404, detail="non in archivio")
        log.info("tolto dall'archivio %s", sha256[:12])
        return {"removed": True}

    return router


def _sync_chunks(stream: AsyncIterator[bytes]) -> Any:
    """
    Un iteratore sincrono sopra lo stream asincrono della richiesta.

    Il lavoro di scrittura gira in un thread (vedi [_run_blocking]) e da un thread non si puo'
    fare `await`: ogni blocco lo si chiede al ciclo di eventi e lo si aspetta. E' un rimbalzo per
    blocco, ma i blocchi sono da decine di kilobyte e il disco e' comunque piu' lento.
    """
    import asyncio

    loop = asyncio.get_event_loop()
    iterator = stream.__aiter__()

    def generator() -> Any:
        while True:
            future = asyncio.run_coroutine_threadsafe(iterator.__anext__(), loop)
            try:
                chunk = future.result()
            except StopAsyncIteration:
                return
            if chunk:
                yield chunk

    return generator()


async def _run_blocking(function: Callable[..., Any], *args: Any) -> Any:
    import asyncio

    return await asyncio.get_event_loop().run_in_executor(None, function, *args)
