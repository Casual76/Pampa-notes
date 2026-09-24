"""
Il companion non deve mai girare dentro il contenitore di un'altra app.

Il 24/09 il companion era stato riavviato dall'app di Claude sul PC. Windows da' a quell'app (e a
tutto quello che lancia) una copia sua di `%LOCALAPPDATA%`: le letture vedono la cartella vera, le
scritture finiscono in `%LOCALAPPDATA%\\Packages\\<app>\\LocalCache\\Local`. Per ore l'archivio ha
scritto li' dentro — 80 registrazioni, 1,9 GB — e quando il companion e' ripartito normale non le ha
piu' viste: rispondeva `blob_missing`, e il telefono ricaricava da fuori casa file che il computer
aveva gia'.

Non c'e' una chiamata di sistema che lo dica: il processo non ha un'identita' di pacchetto
(`GetCurrentPackageFullName` risponde «nessuno»), eppure le sue scritture vengono spostate. Lo si
scopre come succede: si scrive un file e si guarda dove e' finito. Se e' finito in un contenitore, ci
si rilancia attraverso WMI — il processo lo crea il servizio di Windows, fuori da ogni contenitore —
e si esce.

Chi si rilancia lo dice con [RELAUNCHED] (`--fuori`), e chi lo riceve non guarda piu': se il
processo nuovo finisse di nuovo in un contenitore, rilanciarsi ancora sarebbe un giro senza fine. E
un contenitore da cui non si esce ([inescapable]) si dice nel registro e basta.

Solo libreria standard: lo usa anche `avvio.pyw`, che deve partire quando il resto non puo'.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

# L'argomento con cui ci si rilancia fuori: chi lo riceve e' gia' il processo rilanciato.
RELAUNCHED = "--fuori"
# Il Python del Microsoft Store e' lui stesso un'app a pacchetto: tutto quello che lancia, WMI o no,
# nasce nel suo contenitore. Rilanciarsi non serve, e senza [RELAUNCHED] non smetterebbe mai.
STORE_PYTHON = "PythonSoftwareFoundation."


def redirected_to() -> str | None:
    """Il nome del contenitore in cui finiscono le scritture in `%LOCALAPPDATA%`, o None."""
    if sys.platform != "win32":
        return None
    # Vuota o mancante non e' «la cartella corrente»: `Path("")` e' `.`, e la sonda finiva li'.
    raw = os.environ.get("LOCALAPPDATA") or ""
    if not raw.strip():
        return None
    base = Path(raw)
    if not base.is_dir():
        return None
    probe = base / "PampaNotes" / f".sonda-{os.getpid()}"
    try:
        probe.parent.mkdir(parents=True, exist_ok=True)
        probe.write_text("x", encoding="utf-8")
    except OSError:
        return None
    try:
        packages = base / "Packages"
        if not packages.is_dir():
            return None
        for package in packages.iterdir():
            if (package / "LocalCache" / "Local" / "PampaNotes" / probe.name).exists():
                return package.name
        return None
    finally:
        try:
            probe.unlink()
        except OSError:
            pass


def inescapable(package: str) -> bool:
    """Un contenitore da cui un rilancio non esce: quello del Python dello Store ([STORE_PYTHON])."""
    return package.startswith(STORE_PYTHON)


def container_to_leave(argv: list[str]) -> tuple[str | None, bool]:
    """
    Il contenitore in cui si e', e se vale la pena rilanciarsi per uscirne.

    (None, False) fuori da ogni contenitore, o se questo processo e' gia' il rilancio ([RELAUNCHED]
    fra gli argomenti: non si guarda neanche). (nome, False) dentro un contenitore da cui non si
    esce ([inescapable]): chi chiama lo scrive nel registro e va avanti. (nome, True): si rilanci.
    """
    if RELAUNCHED in argv:
        return None, False
    boxed = redirected_to()
    if not boxed:
        return None, False
    return boxed, not inescapable(boxed)


def relaunch_outside(args: list[str], cwd: Path) -> bool:
    """
    Rilancia `args` attraverso WMI (`Win32_Process.Create`): il processo nasce dal servizio di
    Windows e non eredita il contenitore di chi lo chiede. True se WMI ha accettato.
    """
    command = subprocess.list2cmdline(args).replace("'", "''")
    folder = str(cwd).replace("'", "''")
    script = (
        "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create "
        f"-Arguments @{{ CommandLine = '{command}'; CurrentDirectory = '{folder}' }}; "
        "exit $r.ReturnValue"
    )
    try:
        done = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            timeout=60,
            creationflags=0x08000000,  # CREATE_NO_WINDOW
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return done.returncode == 0
