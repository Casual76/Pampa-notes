"""
La finestra per il token di Hugging Face («Separazione delle voci...» nel menu dell'icona), in un
processo suo.

Il 24/09 era una finestra Tk dentro il processo del server, su un thread suo. Chiusa la finestra, il
garbage collector ha distrutto una `StringVar` da un altro thread — «main thread is not in main
loop» — e Tcl ha chiuso il processo intero (`Tcl_AsyncDelete: async handler deleted by the wrong
thread`), a meta' di una registrazione da diciannove ore: il telefono diceva «In attesa del computer
di casa» e il server non c'era piu'. Tk non si usa in sicurezza da un thread che non e' il principale,
e nel processo del server il thread principale e' dell'icona. Qui Tk ha un processo tutto per se', e
qualunque cosa succeda alla finestra il server non se ne accorge.

La finestra non scrive niente e non chiede niente a Hugging Face: parla con l'icona su stdin/stdout,
una riga per richiesta (`save <giro> <token>`, `remove <giro>`, `check <giro>`), e l'icona risponde
una riga per richiesta (`<giro> <messaggio>`, vedi `tray.serve_voices_dialog`). Cosi' `config.json`
ha un solo processo che lo scrive, e il rifiuto di Hugging Face finisce nello stato del server che lo
usa. Il giro e' un numero che cresce: la risposta a una richiesta vecchia (un token sostituito mentre
si aspettava) non scrive sopra quella di adesso.

Solo libreria standard.
"""

from __future__ import annotations

import argparse
import queue
import sys
import threading
import time
import webbrowser
from typing import IO, Any

HF_TOKENS_PAGE = "https://huggingface.co/settings/tokens"
# Quanto si aspetta la risposta prima di dire «non risponde». Il controllo gira comunque, e un esito
# che arriva dopo si mostra lo stesso.
WAIT_S = 20.0


def request_line(command: str, round_: int, token: str = "") -> str:
    """Una richiesta per l'icona. Il token non ha spazi ne' a capo: si tolgono, se ce li ha incollati."""
    token = "".join(token.split())
    return f"{command} {round_} {token}".rstrip() + "\n"


def parse_reply(line: str) -> tuple[int, str] | None:
    """`<giro> <messaggio>` in (giro, messaggio); None per una riga che non e' una risposta."""
    head, sep, message = line.rstrip("\r\n").partition(" ")
    if not sep or not head.isdigit():
        return None
    return int(head), message


def listen(stream: IO[str], into: "queue.Queue[tuple[int, str] | None]") -> None:
    """Le risposte dell'icona nella coda, e None quando l'icona non c'e' piu' (stdin chiuso)."""
    try:
        for line in stream:
            parsed = parse_reply(line)
            if parsed is not None:
                into.put(parsed)
    except (OSError, ValueError):
        pass
    into.put(None)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--modello", default="pyannote/speaker-diarization-community-1")
    parser.add_argument("--acceso", action="store_true", help="un token e' gia' in uso")
    args = parser.parse_args(argv)
    for stream in (sys.stdin, sys.stdout):
        if stream is not None and hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    replies: "queue.Queue[tuple[int, str] | None]" = queue.Queue()
    if sys.stdin is not None:
        threading.Thread(target=listen, args=(sys.stdin, replies), daemon=True, name="risposte").start()

    import tkinter as tk
    from tkinter import ttk

    model_page = f"https://huggingface.co/{args.modello}"
    root = tk.Tk()
    root.title("Pampa Notes - Separazione delle voci")
    root.resizable(False, False)
    frame = ttk.Frame(root, padding=16)
    frame.grid()
    intro = (
        "Pampa Notes puo' dire chi parla in una registrazione: «Voce 1», «Voce 2»...\n"
        "Il modello che lo fa (pyannote) e' gratuito ma si scarica da Hugging Face,\n"
        "che chiede tre cose, una volta sola:\n\n"
        "  1. un account gratuito su huggingface.co;\n"
        f"  2. accettare le condizioni del modello {args.modello}\n"
        "      (il modulo in cima alla sua pagina);\n"
        "  3. un token di lettura: Settings -> Access Tokens -> Create new token, tipo «Read».\n\n"
        "Poi incolla qui il token. Resta su questo computer, in config.json."
    )
    ttk.Label(frame, text=intro, justify="left").grid(row=0, column=0, columnspan=3, sticky="w")
    ttk.Button(frame, text="Apri la pagina del modello", command=lambda: webbrowser.open(model_page)).grid(
        row=1, column=0, sticky="w", pady=(12, 0)
    )
    ttk.Button(frame, text="Crea il token", command=lambda: webbrowser.open(HF_TOKENS_PAGE)).grid(
        row=1, column=1, sticky="w", pady=(12, 0)
    )
    ttk.Label(frame, text="Token di Hugging Face:").grid(row=2, column=0, columnspan=3, sticky="w", pady=(16, 4))
    value = tk.StringVar()
    entry = ttk.Entry(frame, textvariable=value, show="•", width=56)
    entry.grid(row=3, column=0, columnspan=3, sticky="we")
    status = tk.StringVar(value="Un token e' gia' salvato: incollane un altro per sostituirlo." if args.acceso else "")
    ttk.Label(frame, textvariable=status, wraplength=440, justify="left").grid(row=4, column=0, columnspan=3, sticky="w", pady=(8, 0))

    state: dict[str, Any] = {"round": 0, "asked": 0.0, "waiting": False, "late": "", "gone": False}

    def send(command: str, saying: str, late: str, token: str = "") -> None:
        if state["gone"]:
            return
        state["round"] += 1
        status.set(saying)
        state.update(asked=time.monotonic(), waiting=True, late=late)
        try:
            sys.stdout.write(request_line(command, state["round"], token))
            sys.stdout.flush()
        except (OSError, ValueError, AttributeError):
            gone()

    def gone() -> None:
        state.update(gone=True, waiting=False)
        status.set("Il companion non c'e' piu': riaprilo, poi riapri questa finestra dal menu dell'icona.")

    def poll() -> None:
        """Le risposte arrivate, dal thread della finestra: Tk si tocca solo da qui."""
        while True:
            try:
                reply = replies.get_nowait()
            except queue.Empty:
                break
            if reply is None:
                gone()
                continue
            round_, message = reply
            if round_ == state["round"]:
                status.set(message)
                state["waiting"] = False
        if state["waiting"] and time.monotonic() - state["asked"] > WAIT_S:
            status.set(state["late"])
            state["waiting"] = False
        root.after(200, poll)

    def save() -> None:
        token = value.get().strip()
        if not token:
            status.set("Incolla prima il token.")
            return
        value.set("")
        send(
            "save",
            "Salvato. Controllo con Hugging Face...",
            "Hugging Face non risponde: il token e' salvato, si provera' alla prima registrazione.",
            token,
        )

    def remove() -> None:
        send("remove", "Tolgo il token...", "Il companion non risponde: riprova fra poco.")

    buttons = ttk.Frame(frame)
    buttons.grid(row=5, column=0, columnspan=3, sticky="e", pady=(16, 0))
    ttk.Button(buttons, text="Togli il token", command=remove).grid(row=0, column=0, padx=(0, 8))
    ttk.Button(buttons, text="Salva", command=save).grid(row=0, column=1, padx=(0, 8))
    ttk.Button(buttons, text="Chiudi", command=root.destroy).grid(row=0, column=2)
    entry.focus_set()
    if args.acceso:
        # Il token salvato si ricontrolla aprendo la finestra: chi ha appena accettato le condizioni
        # del modello riaccende le voci cosi', senza incollare niente (un rifiuto le aveva spente).
        send(
            "check",
            "Un token e' gia' salvato: controllo con Hugging Face...",
            "Hugging Face non risponde: si provera' alla prima registrazione.",
        )
    root.bind("<Return>", lambda _event: save())
    root.bind("<Escape>", lambda _event: root.destroy())
    root.attributes("-topmost", True)
    root.after(200, poll)
    root.mainloop()


if __name__ == "__main__":
    main()
