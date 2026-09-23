"""
Il companion si aggiorna da solo, quando e' stato installato col setup.

Una volta al giorno l'icona chiede a GitHub l'ultima release `companion-v*` del repository, e se e'
piu' nuova di `VERSION` il menu dice «Aggiorna a vX». Il tocco scarica il setup nella cartella
temporanea e lo lancia in modalita' aggiornamento (`/SILENT /UPGRADE`): il setup sostituisce il
codice, tiene l'ambiente Python e il modello, e riavvia l'icona quando non sta trascrivendo.

Due scelte:

* **Solo per chi ha installato col setup** ([installed]): una copia di sviluppo, o una cartella
  preparata a mano con `installa.cmd`, non deve ricevere un setup che installa in un altro posto e
  lascia due companion a litigarsi la porta.
* **Mai durante una trascrizione**: il setup ferma l'icona per rimetterla in piedi, e fermarla a meta'
  lezione butterebbe via il lavoro. L'icona non lo lancia se c'e' qualcosa in corso o in fila.

Solo la libreria standard: le prove girano senza rete e senza pacchetti.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

REPOSITORY = "Casual76/Pampa-notes"
RELEASES_API = f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=30"
TAG_PREFIX = "companion-v"
ASSET_PATTERN = re.compile(r"^PampaCompanionSetup-[0-9][0-9A-Za-z.\-]*\.exe$")
CHECK_EVERY_S = 24 * 3600
FIRST_CHECK_AFTER_S = 120
# Il setup si lancia senza wizard e senza domande; `/UPGRADE` lo legge il codice di PampaCompanion.iss.
SETUP_ARGS = ("/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/UPGRADE")


@dataclass(frozen=True)
class Release:
    version: tuple[int, ...]
    tag: str
    asset_url: str
    asset_name: str
    size: int
    sha256: str | None = None

    @property
    def label(self) -> str:
        return ".".join(str(part) for part in self.version)


def parse_version(raw: str) -> tuple[int, ...] | None:
    """
    `companion-v1.2.3`, `v1.2.3` o `1.2.3` → (1, 2, 3). None se non e' una versione.

    Un suffisso (`1.2.3-beta`) si ferma ai numeri: le prerelease le scarta GitHub stesso, col suo
    flag, e qui basta non confondersi.
    """
    text = (raw or "").strip()
    if text.startswith(TAG_PREFIX):
        text = text[len(TAG_PREFIX):]
    text = text.lstrip("vV")
    match = re.match(r"^(\d+(?:\.\d+)*)", text)
    if not match:
        return None
    return tuple(int(part) for part in match.group(1).split("."))


def is_newer(candidate: tuple[int, ...], current: tuple[int, ...] | None) -> bool:
    """1.10 viene dopo 1.9, e 1.2 e 1.2.0 sono la stessa. Una copia senza versione non si aggiorna."""
    if current is None:
        return False
    width = max(len(candidate), len(current))
    return candidate + (0,) * (width - len(candidate)) > current + (0,) * (width - len(current))


def pick_latest(releases: Any) -> Release | None:
    """La release `companion-v*` piu' alta, pubblicata, non prerelease, con il setup fra i file."""
    best: Release | None = None
    if not isinstance(releases, list):
        return None
    for entry in releases:
        if not isinstance(entry, dict) or entry.get("draft") or entry.get("prerelease"):
            continue
        tag = str(entry.get("tag_name") or "")
        if not tag.startswith(TAG_PREFIX):
            continue
        version = parse_version(tag)
        if version is None:
            continue
        for asset in entry.get("assets") or []:
            name = str((asset or {}).get("name") or "")
            url = str((asset or {}).get("browser_download_url") or "")
            if not ASSET_PATTERN.match(name) or not url.startswith("https://"):
                continue
            digest = str(asset.get("digest") or "")
            candidate = Release(
                version=version,
                tag=tag,
                asset_url=url,
                asset_name=name,
                size=int(asset.get("size") or 0),
                sha256=digest[7:].lower() if digest.startswith("sha256:") else None,
            )
            if best is None or is_newer(candidate.version, best.version):
                best = candidate
            break
    return best


def installed(folder: Path) -> bool:
    """Installato col setup: accanto al codice c'e' il disinstallatore di Inno Setup."""
    return any(folder.glob("unins*.exe"))


def user_agent(version: str) -> str:
    # GitHub rifiuta le richieste senza User-Agent; con uno nostro si riconosce chi chiede.
    return f"PampaNotes-companion/{version}"


def fetch_latest(version: str, timeout: float = 15.0) -> Release | None:
    request = urllib.request.Request(
        RELEASES_API,
        headers={"User-Agent": user_agent(version), "Accept": "application/vnd.github+json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return pick_latest(json.loads(response.read() or b"[]"))


def available(version: str, fetch: Callable[[str], Release | None] = fetch_latest) -> Release | None:
    """La release da proporre, o None: nessuna, gia' questa, o una copia senza versione."""
    latest = fetch(version)
    if latest is None or not is_newer(latest.version, parse_version(version)):
        return None
    return latest


def download(release: Release, version: str, target_dir: Path | None = None, chunk: int = 1 << 20) -> Path:
    """
    Scarica il setup in una cartella temporanea e lo controlla: dimensione e, se GitHub la da',
    l'impronta. Un file a meta' non prende mai il nome di quello buono.
    """
    folder = Path(target_dir or tempfile.mkdtemp(prefix="pampa-companion-"))
    folder.mkdir(parents=True, exist_ok=True)
    final = folder / release.asset_name
    partial = final.with_suffix(".part")
    request = urllib.request.Request(release.asset_url, headers={"User-Agent": user_agent(version)})
    digest = hashlib.sha256()
    written = 0
    with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as out:
        while True:
            block = response.read(chunk)
            if not block:
                break
            out.write(block)
            digest.update(block)
            written += len(block)
    if release.size and written != release.size:
        partial.unlink(missing_ok=True)
        raise OSError(f"setup scaricato a meta' ({written} di {release.size} byte)")
    if release.sha256 and digest.hexdigest() != release.sha256:
        partial.unlink(missing_ok=True)
        raise OSError("l'impronta del setup non torna")
    os.replace(partial, final)
    return final


def launch_setup(setup: Path) -> None:
    """Il setup parte per conto suo: l'icona verra' fermata da lui, quando e' il momento."""
    flags = 0
    if os.name == "nt":
        # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP, come avvio.pyw con tray.py.
        flags = 0x00000008 | 0x00000200
    subprocess.Popen([str(setup), *SETUP_ARGS], close_fds=True, creationflags=flags)  # noqa: S603


class UpdateWatcher:
    """
    Il controllo di ogni giorno, su un thread suo. `release` e' quella da proporre, o None.

    Un errore di rete non si mostra: e' un controllo in piu', non una funzione. Si riprova domani.
    """

    def __init__(self, version: str, on_change: Callable[[], None], fetch: Callable[[str], Release | None] = fetch_latest) -> None:
        self.version = version
        self.release: Release | None = None
        self._on_change = on_change
        self._fetch = fetch
        self._busy = threading.Lock()

    def check_now(self) -> Release | None:
        try:
            found = available(self.version, self._fetch)
        except (OSError, ValueError):
            return self.release
        if found != self.release:
            self.release = found
            self._on_change()
        return found

    def run_forever(self) -> None:
        time.sleep(FIRST_CHECK_AFTER_S)
        while True:
            self.check_now()
            time.sleep(CHECK_EVERY_S)

    def start(self) -> None:
        threading.Thread(target=self.run_forever, daemon=True, name="aggiornamenti").start()

    def apply(self, idle: Callable[[], bool]) -> str:
        """
        Scarica e lancia. Torna `started`, `busy` (sta trascrivendo: dopo), `running` (gia' in corso),
        `none` (niente da aggiornare) o il messaggio d'errore.
        """
        release = self.release
        if release is None:
            return "none"
        if not idle():
            return "busy"
        if not self._busy.acquire(blocking=False):
            return "running"
        try:
            setup = download(release, self.version)
            # Ricontrollato dopo lo scaricamento, che puo' aver preso minuti.
            if not idle():
                return "busy"
            launch_setup(setup)
            return "started"
        except OSError as error:
            return str(error)
        finally:
            self._busy.release()
