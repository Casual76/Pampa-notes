"""
Il protocollo, provato da fuori contro `wrangler dev` (http://127.0.0.1:8787).

Non e' una suite: e' il giro che un dispositivo fa davvero, con due dispositivi finti dello stesso
proprietario e uno di un altro, e le cose che devono restare vere. Si lancia a mano:

    python test_protocol.py
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request
import time
import uuid

NOW = int(time.time() * 1000)

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8787"
TOKEN = "dev-alessio"
OTHER = "dev-amico"


def call(method: str, path: str, body: dict | None = None, token: str = TOKEN) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "pampa-test/1"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def raw(method: str, path: str, data: bytes | None = None, token: str = TOKEN) -> tuple[int, bytes]:
    """Una richiesta con un corpo qualunque, anche rotto: per i 400."""
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "pampa-test/1"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def push(device: str, changes: list, token: str = TOKEN, batch: str | None = None) -> dict:
    code, body = call("POST", "/v1/sync/push", {"protocolVersion": 1, "deviceId": device, "deviceName": device, "batchId": batch or str(uuid.uuid4()), "changes": changes}, token)
    assert code == 200, (code, body)
    return body


def pull(device: str, since: int, token: str = TOKEN, limit: int = 200, own: bool = False) -> dict:
    code, body = call("GET", f"/v1/sync/pull?since={since}&limit={limit}&deviceId={device}" + ("&includeOwn=1" if own else ""), token=token)
    assert code == 200, (code, body)
    return body


def change(tbl: str, rid: str, at: int, op: str = "U", payload: dict | None = None, **extra) -> dict:
    at = NOW + at   # gli offset del test sono piccoli: li si appoggia sull'ora vera
    c = {"tbl": tbl, "id": rid, "op": op, "updatedAt": at, "hash": f"h{at}"}
    if op == "U":
        c["payload"] = payload if payload is not None else {"id": rid, "title": f"riga {rid}", "updatedAt": at}
    c.update(extra)
    return c


def main() -> None:
    ok = 0

    # 0. senza token e con token sconosciuto
    assert call("GET", "/v1/sync/status", token="")[0] == 401
    assert call("GET", "/v1/sync/status", token="chi-sei")[0] == 401
    # un token lungo e casuale non e' una sessione; un ID token inventato non apre niente; senza sessione niente da chiudere
    assert call("GET", "/v1/sync/status", token="x" * 43)[0] == 401
    assert call("POST", "/v1/auth/google", {"idToken": "aaa.bbb.ccc"})[0] == 401
    assert call("POST", "/v1/auth/google", {})[0] == 400
    assert call("POST", "/v1/auth/logout", {}, token="x" * 43)[0] == 401
    print("auth: 401 senza token, con token sconosciuto o di sessione inventato, con un ID token finto"); ok += 1

    # 1. il tablet pubblica una cartella, una nota e una trascrizione con 1000 segmenti (piu' blocchi)
    segs = [{"idx": i, "text": f"segmento {i}", "wordsJson": "0,500,ciao\n500,900,mondo"} for i in range(1000)]
    r = push("tablet", [
        change("folders", "f1", 1000),
        change("notes", "n1", 1001, payload={"id": "n1", "folderId": "f1", "title": "Kant", "body": "ciao", "tags": ["filosofia"]}),
        change("sessions", "s1", 1002),
        change("transcripts", "t1", 1003, payload={"id": "t1", "sessionId": "s1", "text": "..."}, segments=segs),
    ])
    assert r["applied"] == 4 and r["rejected"] == [] and r["seq"] == 4, r
    print("push: 4 righe, seq 4, 1000 segmenti a blocchi"); ok += 1

    # 2. il telefono tira: vede le 4 righe (non sue), la trascrizione con tutti i segmenti
    p = pull("telefono", 0)
    assert [c["tbl"] for c in p["changes"]] == ["folders", "notes", "sessions", "transcripts"], p["changes"]
    t = p["changes"][3]
    assert len(t["segments"]) == 1000 and t["segments"][999]["idx"] == 999
    assert p["seq"] == 4 and p["more"] is False
    print("pull: 4 righe in ordine, segmenti interi (1000), seq 4"); ok += 1

    # 3. il tablet non rivede le sue righe
    assert pull("tablet", 0)["changes"] == []
    print("pull: chi ha scritto non rivede le sue righe"); ok += 1

    # 4. stesso lotto due volte: stessa risposta, seq fermo
    batch = str(uuid.uuid4())
    a = push("telefono", [change("notes", "n2", 2000)], batch=batch)
    b = push("telefono", [change("notes", "n2", 2000)], batch=batch)
    assert a == b and a["seq"] == 5, (a, b)
    print("push: lotto ripetuto = stessa risposta, niente seq sprecato"); ok += 1

    # 5. chi scrive senza aver visto l'ultima versione viene rifiutato; con la base giusta passa,
    #    anche se il suo orologio e' indietro; l'identica si ignora
    r = push("tablet", [change("notes", "n2", 1500)])
    assert r["rejected"] == [{"tbl": "notes", "id": "n2", "reason": "stale"}] and r["applied"] == 0, r
    r = push("telefono", [change("notes", "n2", 1500, baseHash=f"h{NOW + 2000}")])
    assert r["applied"] == 1 and r["rejected"] == [] and r["seq"] == 6, r
    r = push("tablet", [change("notes", "n2", 1500)])   # identica a quella che c'e' adesso
    assert r["applied"] == 0 and r["rejected"] == [], r
    print("push: senza base rifiutata (stale), con la base passa anche se piu' vecchia, l'identica ignorata"); ok += 1

    # 6. un tombstone viaggia; la trascrizione cancellata perde i blocchi
    r = push("telefono", [change("transcripts", "t1", 3000, op="D", baseHash=f"h{NOW + 1003}")])
    assert r["applied"] == 1
    p = pull("tablet", 4)
    kinds = [(c["tbl"], c["id"], c["op"]) for c in p["changes"]]
    assert ("transcripts", "t1", "D") in kinds and ("notes", "n2", "U") in kinds, kinds
    assert all("segments" not in c or c["segments"] is None for c in p["changes"] if c["op"] == "D")
    print("pull: tombstone consegnato, senza segmenti"); ok += 1

    # 7. paginazione: 250 righe in pagine da 100, con `more` e ripartenza dal seq
    r = push("tablet", [change("sources", f"src{i}", 4000 + i) for i in range(250)])
    got, pages = [], 0
    since = 7   # dopo n2 (seq 6) e il tombstone (seq 7)
    while True:
        p = pull("telefono", since, limit=100); pages += 1
        got += p["changes"]; since = p["seq"]
        if not p["more"]: break
    assert len(got) == 250 and pages == 3 and len({c["id"] for c in got}) == 250, (len(got), pages)
    assert [c["seq"] for c in got] == sorted(c["seq"] for c in got)
    print(f"pull: 250 righe in {pages} pagine, seq crescenti, nessun doppione"); ok += 1

    # 8. un altro proprietario non vede niente
    assert pull("altro", 0, token=OTHER)["changes"] == []
    r = push("altro", [change("notes", "n1", 9999, payload={"id": "n1", "title": "non e' la tua"})], token=OTHER)
    assert r["applied"] == 1
    p = pull("telefono", 0)
    mine = [c for c in p["changes"] if c["tbl"] == "notes" and c["id"] == "n1"]
    assert mine and mine[0]["payload"]["title"] == "Kant", mine
    print("proprietari separati: stesso id, dati diversi, nessuna fuga"); ok += 1

    # 9. richieste malformate
    assert call("POST", "/v1/sync/push", {"deviceId": "x", "batchId": "b", "changes": [{"tbl": "jobs", "id": "j", "op": "U", "updatedAt": 1, "payload": {}}]})[0] == 400
    assert call("GET", "/v1/sync/pull?since=0")[0] == 400
    assert call("POST", "/v1/sync/push", {"protocolVersion": 99, "deviceId": "x", "batchId": "b2", "changes": []})[0] == 409
    # un corpo che non e' JSON, o JSON che non e' un lotto, e un percorso con un `%` rotto: 400, mai 500
    assert raw("POST", "/v1/sync/push", b"{non e' json")[0] == 400
    assert raw("POST", "/v1/sync/push", b"null")[0] == 400
    assert raw("POST", "/v1/sync/push", b"[1,2]")[0] == 400
    assert call("POST", "/v1/sync/push", {"deviceId": "x", "batchId": "b3", "changes": [None]})[0] == 400
    assert raw("POST", "/v1/shares", b"{")[0] == 400
    assert raw("GET", "/s/abc%zz")[0] == 400
    assert raw("POST", "/v1/auth/google", b"null")[0] == 400
    print("400 su tabella sconosciuta, deviceId mancante, JSON rotto o non oggetto, % rotto; 409 su protocollo diverso"); ok += 1

    # 9b. un token di sviluppo il cui proprietario ha un `:` dentro (`google:123`) si divide al primo
    code, s = call("GET", "/v1/sync/status", token="dev-google")
    if code == 200:
        assert s["ownerId"] == "google:12345", s
        print("token di sviluppo: il proprietario 'google:12345' resta intero"); ok += 1
    else:
        print("token di sviluppo con ':' saltato (manca dev-google:google:12345 in AUTH_DEV_TOKENS)")

    # 10. lo stato
    code, s = call("GET", "/v1/sync/status")
    assert code == 200 and s["rows"] >= 253 and s["tombstones"] == 1 and {d["deviceId"] for d in s["devices"]} >= {"tablet", "telefono"}, s
    print(f"status: {s['rows']} righe, {s['tombstones']} tombstone, dispositivi {[d['deviceId'] for d in s['devices']]}"); ok += 1

    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
