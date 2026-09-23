"""
Un push morto a meta' e ripetuto con lo stesso `batchId`, contro un `wrangler dev` con stato vuoto:

    python test_push_retry.py http://127.0.0.1:8788 --persist-to .wrangler/test-state

Un lotto grande va in piu' `batch` di D1, e ognuno si conferma per conto suo: se il Worker muore
fra il secondo e il terzo, le righe dei primi due restano. Il client rimanda lo stesso lotto con lo
stesso id (un'impronta del contenuto). Deve succedere questo:

- l'id del lotto non c'e' ancora (si scrive per ultimo), quindi il lotto si rifa';
- le righe gia' entrate, identiche, si saltano;
- una trascrizione entrata senza i suoi blocchi di segmenti (lo stato che il codice di prima
  lasciava) si riscrive, e chi la tira la riceve intera;
- una volta finito, lo stesso id risponde come l'ultima volta senza toccare niente.

Lo stato «morto a meta'» si fabbrica direttamente in D1 con `wrangler d1 execute`, sullo stesso
`--persist-to` del server: e' l'unico modo di fermare un Worker fra due `batch`.
"""

from __future__ import annotations

import json
import subprocess
import sys
import uuid

import test_protocol as p

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
if ARGS:
    p.BASE = ARGS[0]
PERSIST = sys.argv[sys.argv.index("--persist-to") + 1] if "--persist-to" in sys.argv else None
OWNER = "dev-owner-1"
RUN = uuid.uuid4().hex[:6]


def d1(sql: str) -> list:
    out = subprocess.run(
        ["npx", "wrangler", "d1", "execute", "pampa-notes", "--local", "--persist-to", PERSIST, "--json", "--command", sql],
        shell=sys.platform == "win32", capture_output=True, text=True, timeout=180,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr or out.stdout)
    return json.loads(out.stdout)[0]["results"]


def everything(device: str) -> dict:
    since, rows = 0, {}
    while True:
        page = p.pull(device, since)
        for c in page["changes"]:
            rows[(c["tbl"], c["id"])] = c
        since = page["seq"]
        if not page["more"]:
            return rows


def main() -> None:
    if not PERSIST:
        sys.exit("serve --persist-to, lo stesso del server")
    ok = 0
    T, N = f"t-{RUN}", f"n-{RUN}"
    segs = [{"idx": i, "text": f"frase {i}", "wordsJson": "0,300,una\n300,700,frase"} for i in range(1000)]
    changes = [
        p.change("notes", N, 1, payload={"id": N, "title": "Fichte"}),
        p.change("transcripts", T, 2, payload={"id": T, "sessionId": "s", "text": "..."}, segments=segs),
    ]
    batch = f"lotto-{RUN}"

    # 1. il lotto entra, e ripeterlo da' la stessa risposta
    first = p.push("tablet", changes, batch=batch)
    assert first["applied"] == 2 and first["rejected"] == [], first
    assert p.push("tablet", changes, batch=batch) == first
    assert d1(f"SELECT COUNT(*) AS n FROM batches WHERE ownerId = '{OWNER}' AND batchId = '{batch}'")[0]["n"] == 1
    print("lotto intero: entra, e ripeterlo con lo stesso id risponde uguale"); ok += 1

    # 2. il Worker e' morto dopo la riga della trascrizione e prima dei blocchi e dell'id del lotto
    d1(f"DELETE FROM segment_chunks WHERE ownerId = '{OWNER}' AND transcriptId = '{T}'")
    d1(f"DELETE FROM batches WHERE ownerId = '{OWNER}' AND batchId = '{batch}'")
    before = d1(f"SELECT seq FROM state WHERE ownerId = '{OWNER}' AND tbl = 'transcripts' AND rowId = '{T}'")[0]["seq"]
    broken = everything("telefono")[("transcripts", T)]
    assert broken["segments"] == [], "la trascrizione rotta arriva senza segmenti"
    again = p.push("tablet", changes, batch=batch)
    assert again["applied"] == 1 and again["rejected"] == [], again   # la nota si salta, la trascrizione si riscrive
    after = d1(f"SELECT seq FROM state WHERE ownerId = '{OWNER}' AND tbl = 'transcripts' AND rowId = '{T}'")[0]["seq"]
    assert after > before, (before, after)
    fixed = p.pull("telefono", before)["changes"]
    got = next(c for c in fixed if c["id"] == T)
    assert len(got["segments"]) == 1000 and got["segments"][999]["idx"] == 999, len(got["segments"])
    assert d1(f"SELECT COUNT(*) AS n FROM segment_chunks WHERE ownerId = '{OWNER}' AND transcriptId = '{T}'")[0]["n"] == 3
    print(f"trascrizione senza blocchi: riscritta al secondo tentativo (seq {before} -> {after}), 1000 segmenti in 3 blocchi"); ok += 1

    # 3. e adesso l'id del lotto c'e': un terzo tentativo risponde come il secondo, senza toccare niente
    assert p.push("tablet", changes, batch=batch) == again
    assert d1(f"SELECT seq FROM state WHERE ownerId = '{OWNER}' AND tbl = 'transcripts' AND rowId = '{T}'")[0]["seq"] == after
    print("terzo tentativo: la risposta del secondo, niente riscritto"); ok += 1

    # 4. morto prima di una riga: quella che manca entra, quella che c'e' si salta
    N2, T2, batch2 = f"n2-{RUN}", f"t2-{RUN}", f"lotto2-{RUN}"
    changes2 = [
        p.change("notes", N2, 10, payload={"id": N2, "title": "Schelling"}),
        p.change("transcripts", T2, 11, payload={"id": T2, "sessionId": "s", "text": "..."}, segments=segs[:10]),
    ]
    p.push("tablet", changes2, batch=batch2)
    d1(f"DELETE FROM state WHERE ownerId = '{OWNER}' AND tbl = 'notes' AND rowId = '{N2}'")
    d1(f"DELETE FROM batches WHERE ownerId = '{OWNER}' AND batchId = '{batch2}'")
    r = p.push("tablet", changes2, batch=batch2)
    assert r["applied"] == 1 and r["rejected"] == [], r
    rows = everything("telefono")
    assert rows[("notes", N2)]["payload"]["title"] == "Schelling" and len(rows[("transcripts", T2)]["segments"]) == 10
    print("riga mancante: rientra, la trascrizione intera non si tocca"); ok += 1

    # 5. una trascrizione senza segmenti (una raffinata) non e' «rotta»: uguale, si salta
    R, batch3 = f"r-{RUN}", f"lotto3-{RUN}"
    refined = [p.change("transcripts", R, 20, payload={"id": R, "sessionId": "s", "kind": "REFINED", "text": "pulita"}, segments=[])]
    p.push("tablet", refined, batch=batch3)
    d1(f"DELETE FROM batches WHERE ownerId = '{OWNER}' AND batchId = '{batch3}'")
    r = p.push("tablet", refined, batch=batch3)
    assert r["applied"] == 0 and r["rejected"] == [], r
    print("raffinata senza segmenti: uguale, saltata"); ok += 1

    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
