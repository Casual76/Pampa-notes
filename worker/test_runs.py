"""
Le statistiche delle trascrizioni (`transcription_runs`) viaggiano come le altre righe, senza padre.

Una corsa ha un `sessionId`, ma non e' una chiave esterna: la sessione puo' essere stata cancellata,
e la velocita' del computer di casa resta vera lo stesso. Quindi il Worker non deve ne' rifiutarla
ne' tirarsi dietro la sessione quando la pagina si ferma in mezzo. Contro un'istanza qualunque (gli
id sono nuovi a ogni giro):

    python test_runs.py http://127.0.0.1:8788
"""

from __future__ import annotations

import sys
import uuid

import test_protocol as p

RUN = uuid.uuid4().hex[:6]
F, N, S, R1, R2 = (f"{x}-{RUN}" for x in ("rf", "rn", "rs", "r1", "r2"))

if len(sys.argv) > 1:
    p.BASE = sys.argv[1]


def run_payload(rid: str, session: str, device_name: str, audio_ms: int = 2_400_000, wall_ms: int = 48_000) -> dict:
    return {
        "id": rid, "jobId": f"job-{rid}", "sessionId": session, "noteId": None, "provider": "custom",
        "model": "large-v3", "device": "cuda", "audioMs": audio_ms, "wallMs": wall_ms, "processingMs": 40_000,
        "words": 5214, "segments": 300, "resumed": False, "finishedAt": p.NOW, "deviceName": device_name,
    }


def head(device: str) -> int:
    seq = 0
    while True:
        page = p.pull(device, seq)
        seq = page["seq"]
        if not page["more"]:
            return seq


def main() -> None:
    ok = 0
    owner = head("osservatore")

    # 1. una corsa di una sessione che l'indice non ha: entra, e un altro dispositivo la riceve intera
    r = p.push("telefono", [p.change("transcription_runs", R1, 1, payload=run_payload(R1, f"sparita-{RUN}", "Pixel"))])
    assert r["applied"] == 1 and r["rejected"] == [], r
    page = p.pull(f"tablet-{RUN}", owner)
    got = [c for c in page["changes"] if c["tbl"] == "transcription_runs" and c["id"] == R1]
    assert got and got[0]["payload"]["deviceName"] == "Pixel" and got[0]["payload"]["audioMs"] == 2_400_000, page
    assert got[0].get("segments") is None, got[0]
    print("corsa senza sessione: accettata, arriva agli altri col nome di chi l'ha fatta"); ok += 1

    # 2. nessun padre tirato dentro: con pagine da una riga la corsa arriva da sola, anche se la sua
    #    sessione e' stata scritta dopo (seq piu' alto)
    since = head("osservatore")
    p.push("telefono", [p.change("transcription_runs", R2, 2, payload=run_payload(R2, S, "Pixel"))])
    p.push("telefono", [
        p.change("folders", F, 3),
        p.change("notes", N, 4, payload={"note": {"id": N, "folderId": F, "title": "Fichte"}, "tags": []}),
        p.change("sessions", S, 5, payload={"id": S, "noteId": N}),
    ])
    page = p.pull(f"tablet-{RUN}", since, limit=1)
    assert page["more"] and [(c["tbl"], c["id"]) for c in page["changes"]] == [("transcription_runs", R2)], page
    print("pagina da una riga: la corsa arriva da sola, la sua sessione aspetta la sua pagina"); ok += 1

    # 3. la base vale anche qui: chi riscrive senza aver visto l'ultima versione e' rifiutato
    r = p.push("tablet", [p.change("transcription_runs", R1, 6, payload=run_payload(R1, f"sparita-{RUN}", "Tab S9"))])
    assert r["rejected"] == [{"tbl": "transcription_runs", "id": R1, "reason": "stale"}], r
    r = p.push("tablet", [p.change("transcription_runs", R1, 7, payload=run_payload(R1, f"sparita-{RUN}", "Tab S9"), baseHash=f"h{p.NOW + 1}")])
    assert r["applied"] == 1, r
    print("corsa riscritta: stale senza base, accettata con la base giusta"); ok += 1

    # 4. un tombstone viaggia
    since = head("osservatore")
    r = p.push("telefono", [p.change("transcription_runs", R2, 8, op="D", baseHash=f"h{p.NOW + 2}")])
    assert r["applied"] == 1, r
    page = p.pull(f"tablet-{RUN}", since)
    gone = [c for c in page["changes"] if c["tbl"] == "transcription_runs" and c["id"] == R2]
    assert gone and gone[0]["op"] == "D" and gone[0].get("payload") is None, page
    print("tombstone di una corsa: arriva come D, senza payload"); ok += 1

    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
