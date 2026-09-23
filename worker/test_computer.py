"""
Il computer di casa che segue l'account, provato da fuori contro un `wrangler dev` con stato vuoto.

    python test_computer.py http://127.0.0.1:8788 [--persist-to .wrangler/test-state] [--key <COMPUTER_KEY>]
    python test_computer.py http://127.0.0.1:8789 --no-key

Serve `COMPUTER_KEY` in `.dev.vars` (32 byte in base64). Un dispositivo scrive indirizzi e token,
un altro li legge; un PUT piu' vecchio non tocca niente e riceve la versione corrente; un token
assente resta quello di prima, uno vuoto si cancella; un altro proprietario non vede niente. Con
`--persist-to` guarda anche dentro D1 che il token non ci sia in chiaro.

Poi i biglietti per il PC (`contratto-biglietti-pc.md`): uno buono si verifica col suo proprietario
e non con un altro, una firma o un payload toccati non passano. Con `--key` (la stessa COMPUTER_KEY
del server) il test i biglietti se li fabbrica anche da solo, seguendo il contratto alla lettera:
uno scaduto non passa, uno fatto bene si'. Con `--persist-to` prova anche l'email al posto
dell'ownerId, con una sessione scritta in D1. `--no-key`, contro un server **senza** COMPUTER_KEY,
controlla solo che biglietto e verifica rispondano 503.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

ARGS = [a for a in sys.argv[1:] if not a.startswith("--") and (sys.argv.index(a) == 0 or sys.argv[sys.argv.index(a) - 1] not in ("--persist-to", "--key"))]
BASE = ARGS[0] if ARGS else "http://127.0.0.1:8788"
PERSIST = sys.argv[sys.argv.index("--persist-to") + 1] if "--persist-to" in sys.argv else None
KEY = sys.argv[sys.argv.index("--key") + 1] if "--key" in sys.argv else None
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


def d1(sql: str) -> None:
    out = subprocess.run(
        ["npx", "wrangler", "d1", "execute", "pampa-notes", "--local", "--persist-to", PERSIST, "--command", sql],
        shell=sys.platform == "win32", capture_output=True, text=True, timeout=180,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr or out.stdout)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def craft(owner_id: str, expires_at: int) -> str:
    """Un biglietto fatto qui, con la ricetta del contratto: se il Worker lo accetta, il formato e' quello."""
    derived = hmac.new(base64.b64decode(KEY), b"pampa-computer-ticket-v1", hashlib.sha256).digest()
    payload = b64url(json.dumps({"o": owner_id, "e": expires_at, "v": 1}, separators=(",", ":")).encode())
    return f"pt_{payload}.{b64url(hmac.new(derived, payload.encode(), hashlib.sha256).digest())}"


def verify(ticket: str, owner: str) -> tuple[int, dict]:
    return call("POST", "/v1/computer/verify", {"ticket": ticket, "owner": owner}, token=None)


def tickets() -> int:
    ok = 0
    # 7. senza sessione niente biglietto; con la sessione un biglietto da dodici ore
    assert call("POST", "/v1/computer/ticket", token=None)[0] == 401
    before = int(time.time() * 1000)
    code, t = call("POST", "/v1/computer/ticket")
    assert code == 200 and t["ticket"].startswith("pt_") and t["ticket"].count(".") == 1, (code, t)
    assert abs(t["expiresAt"] - (before + 12 * 3_600_000)) < 60_000, t
    payload = json.loads(base64.urlsafe_b64decode(t["ticket"][3:].split(".")[0] + "=="))
    assert payload == {"o": "dev-owner-1", "e": t["expiresAt"], "v": 1}, payload
    print("biglietto: 401 senza sessione, pt_<payload>.<firma> da 12 ore con {o, e, v}"); ok += 1

    # 8. la verifica: col suo proprietario si', con un altro no
    code, v = verify(t["ticket"], "dev-owner-1")
    assert code == 200 and v == {"ok": True, "ownerId": "dev-owner-1", "expiresAt": t["expiresAt"]}, (code, v)
    assert verify(t["ticket"], "dev-owner-2")[0] == 401
    assert verify(t["ticket"], "")[0] == 401
    code, other = call("POST", "/v1/computer/ticket", token=OTHER)
    assert code == 200 and verify(other["ticket"], "dev-owner-1")[0] == 401 and verify(other["ticket"], "dev-owner-2")[0] == 200
    print("verifica: il proprietario si', un altro proprietario no, il biglietto di un altro no"); ok += 1

    # 9. toccati: firma, payload, formato
    body, sig = t["ticket"][3:].split(".")
    flipped = sig[:-2] + ("A" if sig[-2] != "A" else "B") + sig[-1]
    forged = b64url(json.dumps({"o": "dev-owner-2", "e": t["expiresAt"], "v": 1}, separators=(",", ":")).encode())
    for bad in (f"pt_{body}.{flipped}", f"pt_{forged}.{sig}", f"pt_{body}", f"pt_{body}.{sig}.x", "pt_", "dev-alessio", f"xx_{body}.{sig}", f"pt_{body}.{sig}==="):
        code, _ = verify(bad, "dev-owner-1")
        assert code == 401, (bad, code)
    assert call("POST", "/v1/computer/verify", {"owner": "dev-owner-1"}, token=None)[0] == 401
    print("toccati: firma cambiata, payload di un altro, formato sbagliato -> 401"); ok += 1

    # 10. fabbricati con la chiave: scaduto no, fatto bene si', versione diversa no
    if KEY:
        now = int(time.time() * 1000)
        assert verify(craft("dev-owner-1", now - 1000), "dev-owner-1")[0] == 401
        code, v = verify(craft("dev-owner-1", now + 60_000), "dev-owner-1")
        assert code == 200 and v["expiresAt"] == now + 60_000, (code, v)
        wrong_v = b64url(json.dumps({"o": "dev-owner-1", "e": now + 60_000, "v": 2}, separators=(",", ":")).encode())
        derived = hmac.new(base64.b64decode(KEY), b"pampa-computer-ticket-v1", hashlib.sha256).digest()
        assert verify(f"pt_{wrong_v}.{b64url(hmac.new(derived, wrong_v.encode(), hashlib.sha256).digest())}", "dev-owner-1")[0] == 401
        print("fabbricati col contratto: scaduto 401, valido 200, v=2 401"); ok += 1
    else:
        print("biglietti fabbricati saltati (manca --key)")

    # 11. l'email al posto dell'ownerId, con una sessione viva di quel proprietario
    if PERSIST:
        now = int(time.time() * 1000)
        d1("INSERT INTO sessions (token, ownerId, deviceId, deviceName, email, createdAt, lastSeenAt, revokedAt) "
           f"VALUES ('sessione-di-prova-{now}', 'dev-owner-1', 'tel', 'Telefono', 'Alessio@Example.com', {now}, {now}, NULL)")
        code, v = verify(t["ticket"], "alessio@example.com")
        assert code == 200 and v["ownerId"] == "dev-owner-1", (code, v)
        assert verify(t["ticket"], "qualcuno@example.com")[0] == 401
        assert verify(other["ticket"], "alessio@example.com")[0] == 401
        d1(f"UPDATE sessions SET revokedAt = {now} WHERE token = 'sessione-di-prova-{now}'")
        assert verify(t["ticket"], "alessio@example.com")[0] == 401
        print("owner come email: con una sessione viva si', di un altro account no, revocata no"); ok += 1
    else:
        print("owner come email saltato (manca --persist-to)")
    return ok


def no_key() -> None:
    code, body = call("POST", "/v1/computer/ticket")
    assert code == 503 and body == {"error": "COMPUTER_KEY mancante"}, (code, body)
    code, body = call("POST", "/v1/computer/verify", {"ticket": "pt_abc.def", "owner": "dev-owner-1"}, token=None)
    assert code == 503, (code, body)
    print("senza COMPUTER_KEY: biglietto 503, verifica 503\n\n1 verifiche passate")


def main() -> None:
    if "--no-key" in sys.argv:
        no_key()
        return
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

    # 6b. un orologio avanti di un anno non inchioda il computer: l'updatedAt si riporta ad adesso
    year = 365 * 86_400_000
    code, ahead = call("PUT", path, {"url": "http://192.168.1.30:8765", "updatedAt": NOW + year, "deviceId": "futuro"})
    assert code == 200 and ahead["accepted"] is True and ahead["computer"]["updatedAt"] < NOW + year - 86_400_000, ahead
    code, later = call("PUT", path, {"url": "http://192.168.1.31:8765", "updatedAt": int(time.time() * 1000) + 1000, "deviceId": "giusto"})
    assert code == 200 and later["accepted"] is True and later["computer"]["url"] == "http://192.168.1.31:8765", later
    call("DELETE", path)
    print("orologio avanti di un anno: updatedAt riportato ad adesso, la scrittura dopo vince"); ok += 1

    ok += tickets()
    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
