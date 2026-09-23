"""
Il computer di casa che segue l'account, provato da fuori contro un `wrangler dev` con stato vuoto.

    python test_computer.py http://127.0.0.1:8788 [--persist-to .wrangler/test-state]

Serve `COMPUTER_KEY` in `.dev.vars` (32 byte in base64). Un dispositivo scrive indirizzi e token,
un altro li legge; un PUT piu' vecchio non tocca niente e riceve la versione corrente; un token
assente resta quello di prima, uno vuoto si cancella; un altro proprietario non vede niente. Con
`--persist-to` guarda anche dentro D1 che il token non ci sia in chiaro.
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
BASE = ARGS[0] if ARGS else "http://127.0.0.1:8788"
PERSIST = sys.argv[sys.argv.index("--persist-to") + 1] if "--persist-to" in sys.argv else None
TOKEN = "dev-alessio"
OTHER = "dev-amico"
SECRET = "companion-token-tanto-segreto-1234"
NOW = int(time.time() * 1000)


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


def stored_cipher() -> str | None:
    """La colonna com'e' davvero in D1, letta da wrangler sullo stesso stato del server di prova."""
    out = subprocess.run(
        "npx wrangler d1 execute pampa-notes --local --persist-to " + PERSIST
        + " --json --command \"SELECT tokenCipher FROM computers WHERE ownerId = 'dev-owner-1'\"",
        shell=True, capture_output=True, text=True, timeout=120,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    rows = json.loads(out.stdout)[0]["results"]
    return rows[0]["tokenCipher"] if rows else None


def main() -> None:
    ok = 0
    path = "/v1/account/computer"

    # 0. senza token non si entra, e un account nuovo non ha un computer
    assert call("GET", path, token=None)[0] == 401
    assert call("GET", path)[0] == 404
    assert call("PUT", path, {"url": "192.168.1.10:8765"})[0] == 400, "updatedAt e' obbligatorio"
    print("vuoto: 401 senza token, 404 senza computer, 400 senza updatedAt"); ok += 1

    # 1. il telefono scrive, il tablet legge: indirizzi e token in chiaro al proprietario
    code, put = call("PUT", path, {
        "url": "http://192.168.1.10:8765", "remoteUrl": "http://100.64.0.2:8765", "name": "Casa",
        "model": "large-v3", "token": SECRET, "updatedAt": NOW, "deviceId": "telefono",
    })
    assert code == 200 and put["accepted"] is True and put["computer"]["tokenStored"] is True, (code, put)
    code, got = call("GET", path)
    assert code == 200, (code, got)
    assert got["url"] == "http://192.168.1.10:8765" and got["remoteUrl"] == "http://100.64.0.2:8765", got
    assert got["name"] == "Casa" and got["model"] == "large-v3" and got["token"] == SECRET, got
    assert got["updatedAt"] == NOW and got["deviceId"] == "telefono" and got["tokenStored"] is True, got
    print("andata e ritorno: indirizzi, nome, modello, token"); ok += 1

    # 2. in D1 il token non c'e' in chiaro
    if PERSIST:
        cipher = stored_cipher()
        assert cipher and cipher.startswith("v1.") and SECRET not in cipher, cipher
        print("D1: il token e' un blob v1.<iv>.<cifrato>, non il testo"); ok += 1
    else:
        print("D1: controllo del testo in chiaro saltato (manca --persist-to)")

    # 3. un PUT piu' vecchio non tocca niente, e riceve la versione corrente
    code, stale = call("PUT", path, {"url": "http://10.0.0.9:8765", "updatedAt": NOW - 60_000, "deviceId": "tablet"})
    assert code == 200 and stale["accepted"] is False and stale["stale"] is True, (code, stale)
    assert stale["computer"]["url"] == "http://192.168.1.10:8765" and stale["computer"]["token"] == SECRET, stale
    # anche uguale: e' lo stesso PUT rimandato, non una scrittura nuova
    code, same = call("PUT", path, {"url": "http://10.0.0.9:8765", "updatedAt": NOW, "deviceId": "tablet"})
    assert code == 200 and same["accepted"] is False, same
    assert call("GET", path)[1]["url"] == "http://192.168.1.10:8765"
    print("ultimo che scrive: piu' vecchio e uguale rifiutati, con la versione corrente"); ok += 1

    # 4. token assente: resta; token vuoto: si cancella
    code, kept = call("PUT", path, {"url": "http://192.168.1.20:8765", "remoteUrl": "", "updatedAt": NOW + 1000, "deviceId": "tablet"})
    assert code == 200 and kept["accepted"] is True and kept["computer"]["token"] == SECRET, kept
    assert kept["computer"]["url"] == "http://192.168.1.20:8765" and kept["computer"]["remoteUrl"] == "", kept
    code, cleared = call("PUT", path, {"url": "http://192.168.1.20:8765", "token": "", "updatedAt": NOW + 2000, "deviceId": "tablet"})
    assert code == 200 and cleared["accepted"] is True, cleared
    assert cleared["computer"]["token"] is None and cleared["computer"]["tokenStored"] is False, cleared
    if PERSIST:
        assert stored_cipher() is None
    print("token: assente resta, vuoto si cancella"); ok += 1

    # 5. un altro proprietario non vede e non cancella
    assert call("GET", path, token=OTHER)[0] == 404
    assert call("DELETE", path, token=OTHER)[0] == 404
    code, theirs = call("PUT", path, {"url": "http://172.16.0.5:8765", "token": "altro", "updatedAt": NOW, "deviceId": "x"}, token=OTHER)
    assert code == 200 and theirs["accepted"] is True, theirs
    assert call("GET", path)[1]["url"] == "http://192.168.1.20:8765", "il PUT dell'altro non tocca il mio"
    print("proprietari separati"); ok += 1

    # 6. la cancellazione
    code, removed = call("DELETE", path)
    assert code == 200 and removed["removed"] is True, removed
    assert call("GET", path)[0] == 404
    assert call("DELETE", path)[0] == 404
    assert call("GET", path, token=OTHER)[1]["token"] == "altro"
    call("DELETE", path, token=OTHER)
    print("cancellazione: 404 dopo, quello dell'altro resta"); ok += 1

    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
