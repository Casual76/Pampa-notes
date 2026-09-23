"""
Il computer diventa di un account senza scrivere niente a mano.

Il companion riconosce il proprietario da due righe di `config.json`, `owner` (l'account Google con
cui si sincronizza) e `index_url` (il Worker dell'indice). Scriverle a mano chiedeva di sapere cos'e'
un Worker e dove si trova il suo indirizzo: per chi installa col setup e' una domanda senza risposta.
Qui le porta l'app, che le sa gia'.

Il giro:

1. sul PC, `GET /pair/start` (solo da questo computer) mostra il QR; il setup la apre alla fine;
2. il telefono lo inquadra e arriva a `/pair?k=…`: finche' il computer non e' di nessuno, il link
   `pampanotes://endpoint` di quella pagina porta anche un **codice di collegamento** (`bind=`), che
   vale dieci minuti e una volta sola;
3. l'app, dopo «Collega», se e' entrata con Google manda `POST /v1/pair/bind` con il codice,
   l'account e l'indice, e il suo biglietto per il PC (`pt_…`) come bearer;
4. il companion chiede al Worker *indicato* se il biglietto e' davvero di quell'account
   (`/v1/computer/verify`), e solo allora scrive le due righe.

Perche' tre controlli e non uno:

* **il codice** dice «ha visto lo schermo di questo PC»: senza, chiunque sulla rete di casa potrebbe
  far diventare suo un companion appena installato;
* **il biglietto verificato** dice «l'account e' davvero quello»: senza, chi ha il codice potrebbe
  scriverci un'email qualunque, o un indice suo. E un indice inventato non passa, perche' non sa
  firmare un biglietto che un Worker vero riconosca — ne' questo glielo chiede: chiede all'indice
  scritto nella richiesta, e un indice inventato che risponde «si'» ha comunque dovuto avere il
  codice;
* **la rete di casa** (o Tailscale, o questo computer): il codice non deve poter arrivare da fuori.

Un computer gia' di un account non cambia padrone da qui: un secondo account riceve 409, e per
cambiarlo si scrive `config.json` a mano. Lo stesso account puo' ripassare (un indice nuovo, un QR
rifatto): e' idempotente.
"""

from __future__ import annotations

import asyncio
import base64
import io
import ipaddress
import json
import logging
import re
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from html import escape
from typing import Any, Callable

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse

log = logging.getLogger("pampa")

BIND_PATH = "/v1/pair/bind"
START_PATH = "/pair/start"
# Le strade che il server lascia passare senza credenziali: si difendono da sole, qui sotto.
OPEN_PATHS = frozenset({BIND_PATH, START_PATH})

CODE_TTL_S = 600
MAX_OWNER = 320
MAX_INDEX_URL = 512
OWNER_PATTERN = re.compile(r"^[^\s<>\"'`]{3,320}$")

# Casa, Tailscale (100.64.0.0/10) e questo computer. Il resto del mondo non ha niente da collegare.
LOCAL_NETWORKS = tuple(
    ipaddress.ip_network(net)
    for net in (
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "100.64.0.0/10",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )
)
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})


class BindCodes:
    """
    Il codice di collegamento: uno alla volta, dieci minuti, una volta sola.

    Lo stesso finche' vale, e non uno nuovo a ogni apertura della pagina: il telefono che ricarica
    `/pair` non deve invalidare il link che ha appena toccato.
    """

    def __init__(self, ttl_s: float = CODE_TTL_S, clock: Callable[[], float] = time.time) -> None:
        self._ttl = ttl_s
        self._clock = clock
        self._code: str | None = None
        self._until = 0.0
        self._lock = threading.Lock()

    def current(self) -> str:
        with self._lock:
            now = self._clock()
            if self._code is None or now > self._until:
                self._code = secrets.token_urlsafe(18)
                self._until = now + self._ttl
            return self._code

    def check(self, code: Any) -> bool:
        if not isinstance(code, str) or not code:
            return False
        with self._lock:
            if self._code is None or self._clock() > self._until:
                return False
            return secrets.compare_digest(code.encode("utf-8"), self._code.encode("utf-8"))

    def consume(self) -> None:
        with self._lock:
            self._code = None
            self._until = 0.0


CODES = BindCodes()


def is_local_client(host: str | None) -> bool:
    """La richiesta viene da casa, da Tailscale o da questo computer?"""
    if not host:
        return False
    try:
        address = ipaddress.ip_address(host.split("%", 1)[0])
    except ValueError:
        return False
    mapped = getattr(address, "ipv4_mapped", None)
    if mapped is not None:
        address = mapped
    return any(address in network for network in LOCAL_NETWORKS if address.version == network.version)


def is_loopback_request(client_host: str | None, host_header: str | None) -> bool:
    """
    Da questo computer, e chiamando questo computer per nome.

    Il secondo controllo e' per il DNS rebinding: una pagina web aperta sul PC potrebbe far risolvere
    un suo dominio a 127.0.0.1 e leggere la pagina del QR. Il browser in quel caso manda il *suo*
    dominio nell'header Host, e qui non passa.
    """
    if not client_host or not is_local_client(client_host):
        return False
    try:
        if not ipaddress.ip_address(client_host.split("%", 1)[0]).is_loopback:
            return False
    except ValueError:
        return False
    host = (host_header or "").strip().lower()
    if host.startswith("["):
        name = host[1:].split("]", 1)[0]
    else:
        name = host.rsplit(":", 1)[0] if host.count(":") == 1 else host
    return name in LOOPBACK_HOSTS


def normalize_owner(raw: Any) -> str | None:
    """L'account com'e' scritto nell'app: un'email (o l'id del proprietario). Niente spazi."""
    if not isinstance(raw, str):
        return None
    owner = raw.strip()
    if len(owner) > MAX_OWNER or not OWNER_PATTERN.match(owner):
        return None
    return owner


def normalize_index_url(raw: Any) -> str | None:
    """L'indirizzo del Worker: http o https, un host, niente credenziali, query o frammenti."""
    if not isinstance(raw, str):
        return None
    url = raw.strip().rstrip("/")
    if not url or len(url) > MAX_INDEX_URL:
        return None
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return None
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return None
    if parts.username or parts.password or parts.query or parts.fragment:
        return None
    return url


def decide(current_owner: str, requested_owner: str) -> str:
    """
    `bind` se il computer non e' di nessuno, `same` se e' gia' di quell'account, `taken` se e' di
    un altro. Le email non distinguono maiuscole: `Tu@Gmail.com` e' la stessa persona.
    """
    current = (current_owner or "").strip()
    if not current:
        return "bind"
    return "same" if current.casefold() == requested_owner.strip().casefold() else "taken"


def verify_at(index_url: str, owner: str, ticket: str, user_agent: str, timeout: float = 10.0) -> bool:
    """
    Chiede al Worker indicato se il biglietto e' di `owner`. Vero solo con un «ok» che non e' scaduto.

    Solleva `OSError` se il Worker non risponde: e' un «riprova», non un «no».
    """
    body = json.dumps({"ticket": ticket, "owner": owner}).encode("utf-8")
    request = urllib.request.Request(
        index_url.rstrip("/") + "/v1/computer/verify",
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "User-Agent": user_agent},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            answer = json.loads(response.read() or b"{}")
    except urllib.error.HTTPError:
        return False
    except ValueError:
        return False
    if not isinstance(answer, dict) or answer.get("ok") is not True:
        return False
    try:
        return float(answer.get("expiresAt") or 0) / 1000 > time.time()
    except (TypeError, ValueError):
        return False


def qr_data_uri(text: str) -> str | None:
    """Il QR come immagine dentro la pagina. None se qrcode non c'e': la pagina mostra il link."""
    try:
        import qrcode
    except ImportError:
        return None
    image = qrcode.make(text, box_size=8, border=2)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")


def start_page(pair_url: str, state: dict[str, Any], lan: str | None, remote: str | None) -> str:
    """La pagina che si guarda sul PC: il QR grande, e cosa succede quando lo si inquadra."""
    image = qr_data_uri(pair_url)
    picture = f'<img src="{image}" alt="QR" width="320" height="320">' if image else ""
    owner = str(state.get("owner") or "")
    if owner:
        who = f"<p>Questo computer e' collegato all'account <b>{escape(owner)}</b>: entra nell'app con lo stesso account.</p>"
    else:
        who = (
            "<p><b>Non e' ancora di nessuno.</b> Prima entra nell'app con Google (Impostazioni → "
            "Sincronizzazione), poi inquadra il QR: toccando «Collega» il computer diventa del tuo account.</p>"
        )
    rows = f"<p>In casa: <code>{escape(lan or '?')}</code></p>"
    rows += f"<p>Fuori casa (Tailscale): <code>{escape(remote)}</code></p>" if remote else (
        "<p>Fuori casa: installa <a href=\"https://tailscale.com/download/windows\">Tailscale</a> "
        "sul computer e sul telefono, con lo stesso account.</p>"
    )
    if state.get("token"):
        rows += "<p>Codice per i dispositivi senza account: quello in <code>config.json</code> (chiave <code>token</code>).</p>"
    return f"""<!doctype html><html lang="it"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Pampa Notes - collega il telefono</title>
<style>body{{font-family:system-ui,sans-serif;margin:0;padding:32px;background:#f4f0fb;color:#1c1b1f}}
main{{max-width:560px;margin:0 auto;background:#fff;border-radius:24px;padding:28px}}
h1{{font-size:24px;margin:0 0 8px}}p{{font-size:16px;line-height:1.45;margin:8px 0}}
.qr{{text-align:center;margin:16px 0}}code{{word-break:break-all}}small{{color:#5f5b66}}</style></head><body><main>
<h1>Collega il telefono</h1>
<p>Nell'app Pampa Notes, o con la fotocamera del telefono, inquadra questo QR.</p>
<div class="qr">{picture}</div>
{who}
<hr>{rows}
<p><small>Il QR vale dieci minuti. Se scade, ricarica questa pagina, o dal menu dell'icona accanto
all'orologio: «Mostra il QR per i dispositivi».</small></p>
<p><small>{escape(pair_url)}</small></p>
</main></body></html>"""


def build_router(
    state: dict[str, Any],
    *,
    pair_url: Callable[[], str],
    addresses: Callable[[], tuple[str | None, str | None]],
    persist: Callable[[dict[str, str]], None],
    on_bound: Callable[[str, str], None],
    user_agent: str,
    codes: BindCodes = CODES,
    verify: Callable[[str, str, str, str], bool] = verify_at,
) -> APIRouter:
    """
    Le due strade: la pagina del QR per il PC e il collegamento per il telefono.

    `state` e' lo STATE del server (si leggono `owner` e `token`); `pair_url` fa una chiave nuova per
    `/pair` e torna l'indirizzo da mettere nel QR; `persist` scrive in `config.json`; `on_bound`
    aggiorna lo stato vivo (e butta le verifiche tenute da parte, che valevano per nessuno).
    """
    router = APIRouter()

    @router.get(START_PATH)
    def start(request: Request) -> HTMLResponse:
        client = request.client.host if request.client else None
        if not is_loopback_request(client, request.headers.get("host")):
            raise HTTPException(status_code=404, detail="Not Found")
        lan, remote = addresses()
        body = start_page(pair_url(), state, lan, remote)
        return HTMLResponse(body, headers={"Cache-Control": "no-store"})

    @router.post(BIND_PATH)
    async def bind(request: Request) -> dict[str, Any]:
        client = request.client.host if request.client else None
        if not is_local_client(client):
            raise HTTPException(status_code=403, detail="il collegamento si fa dalla rete di casa o da Tailscale")
        authorization = request.headers.get("authorization", "")
        ticket = authorization[7:].strip() if authorization[:7].lower() == "bearer " else ""
        if not ticket.startswith("pt_") or len(ticket) > 2048:
            raise HTTPException(status_code=401, detail="serve il biglietto dell'account: entra con Google nell'app")
        try:
            payload = json.loads(await request.body() or b"{}")
        except ValueError as error:
            raise HTTPException(status_code=400, detail="il corpo non e' JSON") from error
        if not isinstance(payload, dict):
            raise HTTPException(status_code=400, detail="il corpo non e' un oggetto")
        if not codes.check(payload.get("code")):
            raise HTTPException(status_code=403, detail="codice di collegamento scaduto: rifai il QR dal computer")
        owner = normalize_owner(payload.get("owner"))
        index_url = normalize_index_url(payload.get("index_url"))
        if owner is None or index_url is None:
            raise HTTPException(status_code=400, detail="servono owner e index_url validi")
        outcome = decide(str(state.get("owner") or ""), owner)
        if outcome == "taken":
            raise HTTPException(status_code=409, detail="questo computer e' gia' collegato a un altro account")
        try:
            confirmed = await asyncio.to_thread(verify, index_url, owner, ticket, user_agent)
        except OSError as error:
            log.warning("collegamento: il Worker %s non risponde (%s)", index_url, error)
            raise HTTPException(status_code=502, detail="l'indice non risponde: riprova fra poco") from error
        if not confirmed:
            raise HTTPException(status_code=401, detail="l'indice non conferma questo account")
        try:
            persist({"owner": owner, "index_url": index_url})
        except OSError as error:
            raise HTTPException(status_code=500, detail=f"config.json non si scrive: {error}") from error
        codes.consume()
        on_bound(owner, index_url)
        log.info("collegato all'account %s (indice %s) da %s", owner, index_url, client)
        return {"ok": True, "owner": owner, "index_url": index_url, "already": outcome == "same"}

    return router
