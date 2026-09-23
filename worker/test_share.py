"""
La condivisione, provata da fuori contro un `wrangler dev` con stato vuoto.

    python test_share.py http://127.0.0.1:8788

Fa quello che fa l'app: pubblica una nota con una lezione (due parti, una trascrizione con le
parole), la condivide, carica l'audio (una parte intera, l'altra a blocchi), apre la pagina come
farebbe un compagno, salta nell'audio con `Range`, e revoca. Un altro proprietario non vede niente.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8788"
TOKEN = "dev-alessio"
OTHER = "dev-amico"
NOW = int(time.time() * 1000)


def call(method: str, path: str, body: bytes | dict | None = None, token: str | None = TOKEN, headers: dict | None = None) -> tuple[int, dict, bytes]:
    data = json.dumps(body).encode() if isinstance(body, dict) else body
    h = {"Content-Type": "application/json"} if isinstance(body, dict) else {}
    h["User-Agent"] = "pampa-test/1"
    if token:
        h["Authorization"] = f"Bearer {token}"
    h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, dict(r.headers), raw
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def js(raw: bytes) -> dict:
    return json.loads(raw or b"{}")


def push(device: str, changes: list) -> dict:
    code, _, raw = call("POST", "/v1/sync/push", {"protocolVersion": 1, "deviceId": device, "deviceName": device, "batchId": str(uuid.uuid4()), "changes": changes})
    assert code == 200, (code, raw)
    return js(raw)


def change(tbl: str, rid: str, payload: dict, **extra) -> dict:
    c = {"tbl": tbl, "id": rid, "op": "U", "updatedAt": NOW, "hash": f"h-{tbl}-{rid}", "payload": payload}
    c.update(extra)
    return c


def main() -> None:
    ok = 0

    # 1. la nota, com'e' nell'indice
    segments = [
        {"partId": "p1", "indexInPart": 0, "partStartMs": 0, "partEndMs": 2500, "sessionStartMs": 0, "sessionEndMs": 2500, "text": "ciao mondo", "wordsJson": "0,1000,ciao\n1000,2500,mondo", "wordsEstimated": False},
        {"partId": "p1", "indexInPart": 1, "partStartMs": 2500, "partEndMs": 5000, "sessionStartMs": 2500, "sessionEndMs": 5000, "text": "bella lezione", "wordsJson": "0,1200,bella\n1200,2500,lezione", "wordsEstimated": False},
        {"partId": "p2", "indexInPart": 0, "partStartMs": 0, "partEndMs": 3000, "sessionStartMs": 5000, "sessionEndMs": 8000, "text": "seconda parte", "wordsJson": None, "wordsEstimated": True},
    ]
    r = push("tablet", [
        change("folders", "f1", {"id": "f1", "name": "Filosofia", "parentId": None, "position": 0, "createdAt": NOW, "updatedAt": NOW}),
        change("notes", "n1", {"note": {"id": "n1", "folderId": "f1", "title": "Kant, la critica", "body": "# Appunti\n\nLa **cosa in sé** non si conosce.\n\n- fenomeno\n- noumeno", "pinned": False, "createdAt": NOW, "updatedAt": NOW}, "tags": ["filosofia"]}),
        change("sessions", "s1", {"id": "s1", "noteId": "n1", "position": 0, "title": "Lezione 1", "date": "2026-09-22", "activeTranscriptId": "t1", "createdAt": NOW, "updatedAt": NOW}),
        change("audio_parts", "p1", {"id": "p1", "sessionId": "s1", "position": 0, "fileName": "p1.m4a", "originalName": "Voce 001.m4a", "mime": "audio/mp4", "sizeBytes": 300000, "durationMs": 5000, "sha256": "a" * 64, "createdAt": NOW, "archivedAt": NOW}),
        change("audio_parts", "p2", {"id": "p2", "sessionId": "s1", "position": 1, "fileName": "p2.m4a", "originalName": "Voce 002.m4a", "mime": "audio/mp4", "sizeBytes": 6291456, "durationMs": 3000, "sha256": "b" * 64, "createdAt": NOW, "archivedAt": NOW}),
        change("transcripts", "t1", {"id": "t1", "sessionId": "s1", "kind": "RAW", "provider": "custom", "model": "large-v3", "language": "it", "text": "ciao mondo bella lezione seconda parte", "wordCount": 6, "status": "OK", "createdAt": NOW}, segments=segments),
        change("sources", "src1", {"id": "src1", "noteId": "n1", "kind": "PDF", "originalName": "kant.pdf", "mime": "application/pdf", "sizeBytes": 10, "sha256": "c" * 64, "importedAt": NOW}),
    ])
    assert r["applied"] == 7, r
    print("indice: nota, lezione con due parti, trascrizione con le parole"); ok += 1

    # 2. la condivisione nasce una volta sola
    code, _, raw = call("POST", "/v1/shares", {"noteId": "n1"})
    assert code == 200, (code, raw)
    share = js(raw)
    assert share["title"] == "Kant, la critica" and share["url"].startswith(BASE + "/s/") and len(share["url"].rsplit("/", 1)[1]) >= 30, share
    code, _, raw = call("POST", "/v1/shares", {"noteId": "n1"})
    assert js(raw)["shareId"] == share["shareId"], raw
    code, _, raw = call("GET", "/v1/shares")
    assert [s["shareId"] for s in js(raw)["shares"]] == [share["shareId"]], raw
    code, _, raw = call("POST", "/v1/shares", {"noteId": "n-che-non-esiste"})
    assert code == 400, (code, raw)
    sid = share["shareId"]
    token = share["url"].rsplit("/", 1)[1]
    print(f"share: {share['url']} (una per nota, 400 su una nota che non c'e')"); ok += 1

    # 3. l'audio: una parte intera, l'altra a blocchi
    audio1 = os.urandom(300_000)
    code, _, raw = call("PUT", f"/v1/shares/{sid}/audio/p1", audio1, headers={"Content-Type": "audio/mp4"})
    assert code == 200 and js(raw)["bytes"] == 300_000, (code, raw)
    code, h, _ = call("HEAD", f"/v1/shares/{sid}/audio/p1")
    assert code == 200 and int(h.get("Content-Length", "0")) == 300_000, (code, h)
    code, _, _ = call("HEAD", f"/v1/shares/{sid}/audio/p2")
    assert code == 404

    audio2 = os.urandom(5 * 1024 * 1024 + 1_048_576)
    code, _, raw = call("POST", f"/v1/shares/{sid}/audio/p2/multipart", {"mime": "audio/mp4"})
    assert code == 200, (code, raw)
    upload = js(raw)["uploadId"]
    etags = []
    for n, chunk in enumerate([audio2[: 5 * 1024 * 1024], audio2[5 * 1024 * 1024 :]], start=1):
        code, _, raw = call("PUT", f"/v1/shares/{sid}/audio/p2/multipart/{upload}/{n}", chunk, headers={"Content-Type": "application/octet-stream"})
        assert code == 200, (code, raw)
        etags.append(js(raw))
    code, _, raw = call("POST", f"/v1/shares/{sid}/audio/p2/multipart/{upload}/complete", {"parts": etags})
    assert code == 200 and js(raw)["bytes"] == len(audio2), (code, raw)
    code, _, raw = call("GET", "/v1/shares")
    assert js(raw)["shares"][0]["audioBytes"] == 300_000 + len(audio2), raw
    print(f"audio: p1 intero (300 kB), p2 a blocchi ({len(audio2) // 1024} kB in 2 parti), conteggio giusto"); ok += 1

    # 4. la pagina e i suoi dati, senza token
    code, h, raw = call("GET", f"/s/{token}", token=None)
    assert code == 200 and "text/html" in h.get("Content-Type", "") and b"Pampa Notes" in raw and b"/data" in raw, (code, h)
    code, _, raw = call("GET", f"/s/{token}/data", token=None)
    assert code == 200, (code, raw)
    data = js(raw)
    assert data["title"] == "Kant, la critica" and data["folder"] == "Filosofia" and data["tags"] == ["filosofia"], data
    assert data["body"].startswith("# Appunti") and data["sources"] == ["kant.pdf"]
    s1 = data["sessions"][0]
    assert [p["id"] for p in s1["parts"]] == ["p1", "p2"] and all(p["available"] for p in s1["parts"]), s1["parts"]
    assert s1["raw"]["model"] == "large-v3" and len(s1["raw"]["segments"]) == 3 and s1["raw"]["segments"][0]["words"] == "0,1000,ciao\n1000,2500,mondo"
    assert s1["raw"]["segments"][2]["sessionStartMs"] == 5000 and s1["raw"]["wordsEstimated"] is True
    assert s1["refined"] is None
    code, _, raw = call("GET", "/v1/shares")
    assert js(raw)["shares"][0]["opens"] == 1 and js(raw)["shares"][0]["openedAt"], raw
    print("pagina: HTML senza token, dati con parti, segmenti e parole; apertura contata"); ok += 1

    # 5. l'audio, intero e a salti
    code, h, raw = call("GET", f"/s/{token}/audio/p1", token=None)
    assert code == 200 and raw == audio1 and h.get("Accept-Ranges") == "bytes", (code, len(raw))
    code, h, raw = call("GET", f"/s/{token}/audio/p1", token=None, headers={"Range": "bytes=100-199"})
    assert code == 206 and raw == audio1[100:200] and h.get("Content-Range") == "bytes 100-199/300000", (code, h)
    code, h, raw = call("GET", f"/s/{token}/audio/p2", token=None, headers={"Range": f"bytes={len(audio2) - 10}-"})
    assert code == 206 and raw == audio2[-10:], (code, len(raw))
    code, h, _ = call("HEAD", f"/s/{token}/audio/p2", token=None)
    assert code == 200 and int(h.get("Content-Length", "0")) == len(audio2)
    code, _, _ = call("GET", f"/s/{token}/audio/p1", token=None, headers={"Range": "bytes=999999-"})
    assert code == 416
    for bad in ("bytes=5-3", "bytes=300000-300010", "bytes=-0"):
        code, h, _ = call("GET", f"/s/{token}/audio/p1", token=None, headers={"Range": bad})
        assert code == 416 and h.get("Content-Range") == "bytes */300000", (bad, code, h)
    # un Range che non si capisce si ignora: il file intero
    code, _, raw = call("GET", f"/s/{token}/audio/p1", token=None, headers={"Range": "items=0-5"})
    assert code == 200 and raw == audio1
    code, _, raw = call("GET", f"/s/{token}/audio/p1", token=None, headers={"Range": "bytes=299990-400000"})
    assert code == 206 and raw == audio1[299990:], (code, len(raw))
    print("audio: intero uguale, Range a 206 con Content-Range, oltre la fine / rovesciato / vuoto 416, sconosciuto ignorato"); ok += 1

    # 5b. le intestazioni: niente Referer, niente indice, niente sniffing; la pagina con la sua CSP
    for sub in ("", "/data", "/audio/p1", "/audio/nessuna"):
        code, h, _ = call("GET", f"/s/{token}{sub}", token=None)
        low = {k.lower(): v for k, v in h.items()}
        assert low.get("x-content-type-options") == "nosniff" and low.get("referrer-policy") == "no-referrer", (sub, h)
        assert "noindex" in low.get("x-robots-tag", ""), (sub, h)
    code, h, html = call("GET", f"/s/{token}", token=None)
    csp = {k.lower(): v for k, v in h.items()}.get("content-security-policy", "")
    assert "default-src 'self'" in csp and "script-src 'unsafe-inline'" in csp and "frame-ancestors 'none'" in csp, csp
    # dentro il template ogni `\` e' doppio: al browser deve arrivare `\d`, non `d`
    assert rb"/^\d{4}-\d{2}-\d{2}$/" in html, "la regex della data arriva senza le barre"
    code, h, _ = call("GET", "/s/token-che-non-esiste", token=None)
    assert code == 404 and "content-security-policy" in {k.lower() for k in h}, h
    print("intestazioni: nosniff, no-referrer, noindex su pagina, dati e audio; CSP sulla pagina; \\d nella regex"); ok += 1

    # 5c. un «audio» caricato come pagina si serve come audio
    code, _, raw = call("PUT", f"/v1/shares/{sid}/audio/p3", b"<script>alert(1)</script>", headers={"Content-Type": "text/html"})
    assert code == 200, (code, raw)
    code, h, _ = call("GET", f"/s/{token}/audio/p3", token=None)
    assert code == 200 and h.get("Content-Type") == "audio/mp4", h
    code, _, raw = call("PUT", f"/v1/shares/{sid}/audio/p3", b"ogg", headers={"Content-Type": "audio/ogg; codecs=opus"})
    code, h, _ = call("GET", f"/s/{token}/audio/p3", token=None)
    assert h.get("Content-Type") == "audio/ogg", h
    code, _, raw = call("POST", f"/v1/shares/{sid}/audio/p4/multipart", {"mime": "image/svg+xml"})
    upload = js(raw)["uploadId"]
    code, _, raw = call("PUT", f"/v1/shares/{sid}/audio/p4/multipart/{upload}/1", b"<svg/>", headers={"Content-Type": "application/octet-stream"})
    code, _, raw = call("POST", f"/v1/shares/{sid}/audio/p4/multipart/{upload}/complete", {"parts": [js(raw)]})
    assert code == 200, (code, raw)
    code, h, _ = call("GET", f"/s/{token}/audio/p4", token=None)
    assert h.get("Content-Type") == "audio/mp4", h
    print("audio: text/html e image/svg+xml diventano audio/mp4, audio/ogg resta"); ok += 1

    # 6. un altro proprietario: non vede, non revoca; il link pero' e' pubblico
    code, _, raw = call("GET", "/v1/shares", token=OTHER)
    assert js(raw)["shares"] == []
    code, _, _ = call("DELETE", f"/v1/shares/{sid}", token=OTHER)
    assert code == 404
    code, _, _ = call("HEAD", f"/v1/shares/{sid}/audio/p1", token=OTHER)
    assert code == 404
    code, _, _ = call("GET", f"/s/{token}/data", token=OTHER)
    assert code == 200
    print("proprietari separati: l'altro non vede e non revoca; il link vale per chiunque ce l'abbia"); ok += 1

    # 7. la revoca: link morto, audio sparito, e la nota si puo' ricondividere con un link nuovo
    code, _, raw = call("DELETE", f"/v1/shares/{sid}")
    assert code == 200 and js(raw)["revoked"] is True
    assert call("GET", f"/s/{token}/data", token=None)[0] == 404
    assert call("GET", f"/s/{token}", token=None)[0] == 404
    assert call("GET", f"/s/{token}/audio/p1", token=None)[0] == 404
    assert call("HEAD", f"/v1/shares/{sid}/audio/p1")[0] == 404
    assert js(call("GET", "/v1/shares")[2])["shares"] == []
    code, _, raw = call("POST", "/v1/shares", {"noteId": "n1"})
    again = js(raw)
    assert again["shareId"] != sid and again["url"] != share["url"] and again["audioBytes"] == 0, again
    code, _, raw = call("GET", f"/s/{again['url'].rsplit('/', 1)[1]}/data", token=None)
    assert code == 200 and not js(raw)["sessions"][0]["parts"][0]["available"]
    print("revoca: 404 su pagina, dati e audio; ricondivisa con un link nuovo e senza audio"); ok += 1

    print(f"\n{ok} verifiche passate")


if __name__ == "__main__":
    main()
