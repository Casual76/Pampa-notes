"""
I padri arrivano con i figli anche quando la pagina si ferma in mezzo.

Lo stato tiene una riga per elemento col `seq` dell'ultima modifica: una nota ritoccata dopo che le
si e' aggiunta una sessione ha il `seq` piu' alto, e con pagine piccole la sessione arriverebbe senza
la nota. Si prova con pagine da una riga, contro un'istanza vuota:

    python test_pull_parents.py http://127.0.0.1:8788
"""

from __future__ import annotations

import sys
import uuid

import test_protocol as p

RUN = uuid.uuid4().hex[:6]
F, N, S, A = (f"{x}-{RUN}" for x in ("pf", "pn", "ps", "pa"))

if len(sys.argv) > 1:
    p.BASE = sys.argv[1]


def main() -> None:
    # Il punto di partenza: dove e' arrivato il proprietario prima di questa prova.
    owner = 0
    while True:
        page = p.pull("osservatore", owner)
        owner = page["seq"]
        if not page["more"]:
            break
    # Il telefono crea cartella, nota e sessione con una parte, poi ritocca la nota e la cartella.
    p.push("telefono", [
        p.change("folders", F, 1),
        p.change("notes", N, 2, payload={"note": {"id": N, "folderId": F, "title": "Romanticismo"}, "tags": []}),
        p.change("sessions", S, 3, payload={"id": S, "noteId": N}),
        p.change("audio_parts", A, 4, payload={"id": A, "sessionId": S}),
    ])
    p.push("telefono", [
        p.change("sessions", S, 10, payload={"id": S, "noteId": N, "title": "ritoccata"}, baseHash=f"h{p.NOW + 3}"),
        p.change("notes", N, 11, payload={"note": {"id": N, "folderId": F, "title": "Romanticismo 2"}, "tags": []}, baseHash=f"h{p.NOW + 2}"),
        p.change("folders", F, 12, baseHash=f"h{p.NOW + 1}"),
    ])

    # Un tablet che parte da prima: con pagine da una riga la prima e' la parte audio, e i suoi
    # padri — sessione, nota, cartella — stanno tutti dopo.
    page = p.pull(f"tablet-{RUN}", owner, limit=1)
    got = [(c["tbl"], c["id"]) for c in page["changes"]]
    assert page["more"], page
    assert got[0] == ("audio_parts", A), got
    assert {("sessions", S), ("notes", N), ("folders", F)} <= set(got), got
    # Il punto da cui ripartire resta quello della parte: i padri torneranno nella loro pagina.
    assert page["seq"] == next(c["seq"] for c in page["changes"] if c["id"] == A), page
    print("pagina da una riga: la parte arriva con sessione, nota e cartella")

    # Chi ha scritto i padri non se li vede ridare: li ha gia'.
    mine = p.pull("telefono", owner, limit=1)
    assert all(c["deviceId"] != "telefono" for c in mine["changes"]), mine
    print("i padri scritti dal dispositivo stesso non tornano")

    # L'ultima pagina non aggiunge niente.
    last = p.pull(f"tablet-{RUN}", owner)
    assert not last["more"] and len(last["changes"]) == 4, last
    print("ultima pagina: niente di aggiunto")


if __name__ == "__main__":
    main()
