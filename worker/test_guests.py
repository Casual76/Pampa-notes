"""
Gli ospiti del computer, provati da fuori contro un `wrangler dev` con stato vuoto.

    python test_guests.py http://127.0.0.1:8788

Il proprietario crea un ospite e riceve il token una volta sola; il companion lo verifica dicendo
di chi e' il PC; l'uso si conta; un altro proprietario non vede e non revoca; la revoca spegne il
token subito.
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8788"
TOKEN = "dev-alessio"
OTHER = "dev-amico"


def call(method: str, path: str, body: dict | None = None, token: str | None = TOKEN) -> tuple[int, dict]:
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json", "User-Agent": "pampa-test/1"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main() -> None:
    ok = 0

    # 1. si crea, e il token si vede una volta sola
    code, g = call("POST", "/v1/guests", {"name": "Marco"})
    assert code == 200 and g["name"] == "Marco" and g["token"].startswith("pg_") and len(g["token"]) > 30, (code, g)
    code, lst = call("GET", "/v1/guests")
    assert [x["guestId"] for x in lst["guests"]] == [g["guestId"]] and "token" not in lst["guests"][0], lst
    assert call("POST", "/v1/guests", {"name": "   "})[0] == 400
    print("ospite: creato, token solo alla creazione, 400 senza nome"); ok += 1

    # 2. il companion verifica: con l'ownerId del proprietario si', con un altro no, con un token inventato no
    code, v = call("POST", "/v1/guests/verify", {"token": g["token"], "owner": "dev-owner-1"}, token=None)
    assert code == 200 and v["ok"] is True and v["name"] == "Marco", (code, v)
    assert call("POST", "/v1/guests/verify", {"token": g["token"], "owner": "dev-owner-2"}, token=None)[0] == 401
    assert call("POST", "/v1/guests/verify", {"token": "pg_inventato_inventato_inventato", "owner": "dev-owner-1"}, token=None)[0] == 401
    assert call("POST", "/v1/guests/verify", {"token": "dev-alessio", "owner": "dev-owner-1"}, token=None)[0] == 401
    print("verifica: vale solo per il proprietario giusto; token inventati e non-ospiti fuori"); ok += 1

    # 3. l'uso si conta
    assert call("POST", "/v1/guests/usage", {"token": g["token"], "seconds": 3600}, token=None)[0] == 200
    assert call("POST", "/v1/guests/usage", {"token": g["token"], "seconds": 1800.4}, token=None)[0] == 200
    code, lst = call("GET", "/v1/guests")
    row = lst["guests"][0]
    assert row["jobs"] == 2 and row["seconds"] == 5400 and row["lastUsedAt"], row
    print("registro: 2 trascrizioni, 5400 secondi, ultima volta segnata"); ok += 1

    # 4. un altro proprietario non vede e non revoca
    assert call("GET", "/v1/guests", token=OTHER)[1]["guests"] == []
    assert call("DELETE", f"/v1/guests/{g['guestId']}", token=OTHER)[0] == 404
    print("proprietari separati"); ok += 1

    # 5. la revoca spegne il token subito
    code, r = call("DELETE", f"/v1/guests/{g['guestId']}")
    assert code == 200 and r["revoked"] is True
    assert call("POST", "/v1/guests/verify", {"token": g["token"], "owner": "dev-owner-1"}, token=None)[0] == 401
    assert call("POST", "/v1/guests/usage", {"token": g["token"], "seconds": 1}, token=None)[0] == 401
    assert call("GET", "/v1/guests")[1]["guests"] == []
    assert call("DELETE", f"/v1/guests/{g['guestId']}")[0] == 404
    print("revoca: verifica e uso rifiutati, elenco vuoto"); ok += 1

    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
