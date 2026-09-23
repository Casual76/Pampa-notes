"""
Due push insieme, contro un `wrangler dev` con stato vuoto:

    python test_concurrent_push.py http://127.0.0.1:8788

Il Worker leggeva `owners.seq` all'inizio del push e lo riscriveva alla fine, e controllava la base
con una lettura fatta prima di scrivere: due push in volo insieme prendevano gli stessi `seq`, e
passavano tutti e due il controllo sulla stessa riga. Qui si mandano davvero in parallelo (thread,
con una barriera per farli partire insieme), piu' volte, e si guarda che:

- sulla stessa riga con la stessa base ne entra **uno solo**, gli altri tornano `stale`, e la riga
  nell'indice e' quella del vincitore;
- con righe diverse entrano tutte, ogni `seq` e' diverso, e `owners.seq` (lo stato) e' il piu' alto.
"""

from __future__ import annotations

import sys
import threading
import uuid

import test_protocol as p

if len(sys.argv) > 1:
    p.BASE = sys.argv[1]

RUN = uuid.uuid4().hex[:6]


def parallel(jobs: list) -> list:
    """Fa partire tutti i lavori nello stesso istante e aspetta le risposte, nell'ordine dei lavori."""
    barrier = threading.Barrier(len(jobs))
    out: list = [None] * len(jobs)

    def run(i: int, job) -> None:
        barrier.wait()
        try:
            out[i] = job()
        except BaseException as e:  # noqa: BLE001 - la si rilancia nel thread principale
            out[i] = e

    threads = [threading.Thread(target=run, args=(i, job)) for i, job in enumerate(jobs)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    for r in out:
        if isinstance(r, BaseException):
            raise r
    return out


def everything(device: str) -> list:
    """Tutto l'indice del proprietario, righe proprie comprese."""
    since, rows = 0, []
    while True:
        page = p.pull(device, since, own=True)
        rows += page["changes"]
        since = page["seq"]
        if not page["more"]:
            return rows


def main() -> None:
    ok = 0

    # 1. stessa riga, stessa base, otto dispositivi insieme: uno solo vince. Piu' giri, perche' la
    #    corsa non si presenta a comando.
    for round_ in range(6):
        rid = f"corsa-{RUN}-{round_}"
        p.push("origine", [p.change("notes", rid, 0, hash=f"base-{rid}")])
        jobs = [
            (lambda k=k: p.push(f"dev{k}", [p.change("notes", rid, 100 + k, hash=f"{rid}-v{k}", baseHash=f"base-{rid}")]))
            for k in range(8)
        ]
        results = parallel(jobs)
        winners = [k for k, r in enumerate(results) if r["applied"] == 1]
        losers = [k for k, r in enumerate(results) if r["rejected"] == [{"tbl": "notes", "id": rid, "reason": "stale"}] and r["applied"] == 0]
        assert len(winners) == 1 and len(losers) == 7, results
        row = next(c for c in everything("osservatore") if c["id"] == rid)
        assert row["hash"] == f"{rid}-v{winners[0]}" and row["deviceId"] == f"dev{winners[0]}", row
    print("stessa riga, stessa base, 8 push insieme (6 giri): ne entra uno, 7 stale, l'indice ha il vincitore"); ok += 1

    # 2. righe diverse, venti push insieme da cinque righe ciascuno: entrano tutte, seq tutti diversi
    jobs = [
        (lambda k=k: p.push(f"massa{k}", [p.change("sources", f"m-{RUN}-{k}-{i}", 1000 + i) for i in range(5)]))
        for k in range(20)
    ]
    results = parallel(jobs)
    assert all(r["applied"] == 5 and r["rejected"] == [] for r in results), results
    rows = everything("osservatore")
    mass = [c for c in rows if c["id"].startswith(f"m-{RUN}-")]
    assert len(mass) == 100, len(mass)
    seqs = [c["seq"] for c in rows]
    assert len(seqs) == len(set(seqs)), "due righe con lo stesso seq"
    # la risposta di ogni push dice il suo seq piu' alto: sono tutti diversi, e ognuno e' quello di una sua riga
    tops = [r["seq"] for r in results]
    assert len(set(tops)) == 20, tops
    code, st = p.call("GET", "/v1/sync/status")
    assert code == 200 and st["seq"] == max(seqs), (st["seq"], max(seqs))
    print(f"20 push insieme, 100 righe: seq tutti diversi, owners.seq = {st['seq']} = il piu' alto"); ok += 1

    # 3. un pull fatto a meta' non salta righe: chi riparte dal seq della pagina le trova tutte
    base = st["seq"]
    stop = threading.Event()
    seen: set = set()

    def puller() -> None:
        since = base
        while not stop.is_set():
            page = p.pull("lettore", since)
            for c in page["changes"]:
                seen.add(c["id"])
            since = page["seq"]
        # un ultimo giro dopo che tutti hanno finito
        while True:
            page = p.pull("lettore", since)
            for c in page["changes"]:
                seen.add(c["id"])
            since = page["seq"]
            if not page["more"]:
                break

    reader = threading.Thread(target=puller)
    reader.start()
    jobs = [
        (lambda k=k: p.push(f"scrittore{k}", [p.change("sources", f"w-{RUN}-{k}-{i}", 2000 + i) for i in range(3)]))
        for k in range(12)
    ]
    parallel(jobs)
    stop.set()
    reader.join()
    written = {f"w-{RUN}-{k}-{i}" for k in range(12) for i in range(3)}
    assert written <= seen, sorted(written - seen)
    print("pull in corsa con 12 push: nessuna riga scavalcata"); ok += 1

    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
