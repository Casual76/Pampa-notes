"""
Le misure del pull e del push, contro un `wrangler dev` con stato vuoto:

    python test_pull_limits.py http://127.0.0.1:8788

- `includeOwn=1`: il riallineamento completo vede anche le righe scritte dal dispositivo stesso
  (senza, le avrebbe cancellate come «sparite dall'indice»); senza il parametro non cambia niente.
- Pagine a byte: sei lezioni da 1,2 MB di segmenti non arrivano in una pagina da 7 MB ma in due, e
  nessuna trascrizione si spezza; una da sola piu' grande del budget arriva lo stesso, da sola.
- `too_large`: una riga che non sta in D1 si rifiuta da sola, e il resto del lotto entra.
"""

from __future__ import annotations

import sys
import uuid

import test_protocol as p

if len(sys.argv) > 1:
    p.BASE = sys.argv[1]

RUN = uuid.uuid4().hex[:6]


def big_segments(n: int, words_bytes: int) -> list:
    """`n` segmenti con un campo parole da circa `words_bytes`: una lezione allineata da WhisperX."""
    words = ("0,250,parola\n" * (words_bytes // 13 + 1))[:words_bytes]
    return [{"idx": i, "text": f"frase {i}", "wordsJson": words} for i in range(n)]


def drain(device: str, since: int, **kw) -> list:
    pages = []
    while True:
        page = p.pull(device, since, **kw)
        pages.append(page)
        since = page["seq"]
        if not page["more"]:
            return pages


def main() -> None:
    ok = 0

    # 1. includeOwn
    mine = [p.change("notes", f"own-{RUN}-{i}", i) for i in range(3)]
    r = p.push("tablet", mine)
    assert r["applied"] == 3, r
    assert p.pull("tablet", 0)["changes"] == []
    own = p.pull("tablet", 0, own=True)
    assert {c["id"] for c in own["changes"]} == {c["id"] for c in mine} and all(c["deviceId"] == "tablet" for c in own["changes"]), own
    assert own["seq"] == r["seq"] and own["more"] is False
    print("includeOwn=1: il dispositivo vede le sue 3 righe; senza, nessuna"); ok += 1
    start = r["seq"]

    # 2. sei lezioni da ~1,2 MB di segmenti (400 segmenti da 3 kB: un blocco solo ciascuna)
    lessons = [f"lez-{RUN}-{k}" for k in range(6)]
    r = p.push("tablet", [
        p.change("transcripts", t, 100 + k, payload={"id": t, "sessionId": "s", "text": "..."}, segments=big_segments(400, 3000))
        for k, t in enumerate(lessons)
    ])
    assert r["applied"] == 6, r
    pages = drain("telefono", start)
    sizes = [len(pg["changes"]) for pg in pages]
    assert len(pages) >= 2 and sum(sizes) == 6, sizes
    assert all(pg["more"] for pg in pages[:-1]) and not pages[-1]["more"], [pg["more"] for pg in pages]
    for pg in pages:
        for c in pg["changes"]:
            assert len(c["segments"]) == 400 and c["segments"][399]["idx"] == 399, (c["id"], len(c["segments"]))
        # la pagina riparte dall'ultimo consegnato
        if pg["more"]:
            assert pg["seq"] == max(c["seq"] for c in pg["changes"]), pg["seq"]
    assert [c["id"] for pg in pages for c in pg["changes"]] == lessons
    print(f"6 lezioni da ~1,2 MB: {len(pages)} pagine da {sizes}, ogni trascrizione intera, in ordine"); ok += 1

    # 3. una lezione da sola oltre il budget (~4,8 MB, tre blocchi): arriva, da sola, e poi il resto
    huge = f"enorme-{RUN}"
    after = r["seq"]
    r = p.push("tablet", [
        p.change("transcripts", huge, 200, payload={"id": huge, "sessionId": "s", "text": "..."}, segments=big_segments(1200, 4000)),
        p.change("notes", f"dopo-{RUN}", 201),
    ])
    assert r["applied"] == 2, r
    first = p.pull("telefono", after)
    assert [c["id"] for c in first["changes"]] == [huge] and first["more"] is True, [c["id"] for c in first["changes"]]
    assert len(first["changes"][0]["segments"]) == 1200
    rest = p.pull("telefono", first["seq"])
    assert [c["id"] for c in rest["changes"]] == [f"dopo-{RUN}"] and rest["more"] is False
    print("una lezione da ~4,8 MB: da sola nella sua pagina, intera; la riga dopo nella pagina dopo"); ok += 1

    # 4. too_large: una nota da 2 MB e una trascrizione con un segmento da 2 MB si rifiutano da sole
    fat = "x" * 2_000_000
    r = p.push("tablet", [
        p.change("notes", f"grassa-{RUN}", 300, payload={"id": f"grassa-{RUN}", "body": fat}),
        p.change("transcripts", f"seg-{RUN}", 301, payload={"id": f"seg-{RUN}", "sessionId": "s"}, segments=[{"idx": 0, "text": fat}]),
        p.change("notes", f"magra-{RUN}", 302),
    ])
    assert r["applied"] == 1, r
    assert sorted(r["rejected"], key=lambda x: x["id"]) == [
        {"tbl": "notes", "id": f"grassa-{RUN}", "reason": "too_large"},
        {"tbl": "transcripts", "id": f"seg-{RUN}", "reason": "too_large"},
    ], r["rejected"]
    ids = {c["id"] for pg in drain("telefono", start) for c in pg["changes"]}
    assert f"magra-{RUN}" in ids and f"grassa-{RUN}" not in ids and f"seg-{RUN}" not in ids
    print("too_large: la nota da 2 MB e il segmento da 2 MB rifiutati, il resto del lotto entrato"); ok += 1

    # 5. l'ultima pagina con includeOwn e senza dice lo stesso seq finale
    assert drain("tablet", 0, own=True)[-1]["seq"] == drain("telefono", 0)[-1]["seq"]
    print("seq finale uguale con e senza includeOwn"); ok += 1

    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
