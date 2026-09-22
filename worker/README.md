# L'indice in cloud

Un Worker Cloudflare con un database D1. È quello che tiene le note allineate fra telefono, tablet
e computer: **solo testo** — note, cartelle, sessioni, trascrizioni con i segmenti, sorgenti,
preset — e mai un file. Registrazioni e originali vanno da dispositivo a computer di casa e basta
(vedi `companion/`).

Non è un registro di eventi: una riga per (proprietario, tabella, id), sovrascritta a ogni
modifica, con un numero di sequenza che sale. Il database è grande quanto i dati, non quanto la
loro storia. Il piano per esteso e le ragioni delle scelte stanno nel piano di progetto.

## In locale, senza account

```
cd worker
npm install
npm run schema:local
npm run dev            # http://localhost:8787, e su tutte le interfacce
python test_protocol.py
```

`wrangler dev --local` gira su Miniflare con un D1 su disco in `.wrangler/`: niente rete, niente
login. I token di sviluppo stanno in `.dev.vars` (git-ignorato, letto solo da `wrangler dev`):
`wrangler.toml` in produzione li ha vuoti. `test_protocol.py` vuole un proprietario **vuoto** (conta i `seq` dall'uno): se sull'istanza
di sviluppo ci sono già i dati di un dispositivo vero, lo si prova contro una seconda istanza con
uno stato suo, che si butta e si rifà ogni volta:

```
rm -rf .wrangler/test-state
npx wrangler d1 execute pampa-notes --local --persist-to .wrangler/test-state --file=./schema.sql
npx wrangler dev --local --port 8788 --persist-to .wrangler/test-state
python test_protocol.py http://127.0.0.1:8788
python test_share.py http://127.0.0.1:8788
python test_guests.py http://127.0.0.1:8788
```
 I token di sviluppo stanno in `wrangler.toml` (`AUTH_DEV_TOKENS`, forma `token:ownerId`):
nell'app, in *Impostazioni → Sincronizzazione*, l'indirizzo è quello del computer (LAN o
Tailscale, porta 8787) e il codice è uno di quei token. `test_protocol.py` fa il giro che fa un
dispositivo vero, con due dispositivi dello stesso proprietario e uno di un altro.

## Il deploy vero

```
npx wrangler login                 # apre il browser: e' il tuo account Cloudflare
npx wrangler d1 create pampa-notes # stampa un database_id: va in wrangler.toml
npx wrangler r2 bucket create pampa-notes-audio   # l'audio delle note condivise
npm run schema:remote
npm run deploy                     # https://pampa-notes-sync.<tuo-sottodominio>.workers.dev
```

Prima del deploy, in `wrangler.toml`: **svuota `AUTH_DEV_TOKENS`** e metti in `GOOGLE_CLIENT_ID`
il client ID OAuth (tipo *Web*) del tuo progetto Google — lo stesso valore va in
`local.properties` dell'app come `pampa.googleClientId`, o la pagina Sincronizzazione continua a
chiedere un codice. Da quel momento l'accesso è l'ID token di Google che l'app ottiene dal
Credential Manager, verificato qui contro le chiavi pubbliche di Google; il `sub` dell'account è il
proprietario dei dati, per sempre, anche se cambia l'email. L'ID token dura un'ora, quindi l'app non
lo tiene: lo presenta una volta a `POST /v1/auth/google` e riceve un **token di sessione**
(tabella `sessions`, uno per dispositivo, revocabile con `POST /v1/auth/logout` o a mano), che è
quello che manda da lì in poi.

Il piano gratuito di Cloudflare basta: Workers 100.000 richieste al giorno, D1 5 GB. Una
sincronizzazione ogni sei ore per tre dispositivi sono poche decine di richieste al giorno; il
testo di anni di lezioni sono decine di megabyte.

## Il protocollo, in breve

| | |
|---|---|
| `POST /v1/sync/push` | Un lotto di righe cambiate (`op` U o D, `updatedAt`, `hash`, `baseHash`, JSON). `baseHash` è l'impronta dell'ultima versione che il dispositivo ha visto: se non è quella che il server ha adesso la riga viene **rifiutata** (`stale`) e il dispositivo se la riprende col pull, dove il merge decide. Non si guarda l'orologio. Il lotto ha un id: ripeterlo non fa danni. |
| `GET /v1/sync/pull?since=N&deviceId=X` | Le righe con `seq > N`, a pagine da 200, escluse quelle scritte da `X`. Una trascrizione arriva con tutti i suoi segmenti, mai spezzata fra due pagine. |
| `GET /v1/sync/status` | Sequenza, conteggi, dispositivi visti. |
| `POST /v1/auth/google`, `POST /v1/auth/logout` | Un ID token di Google diventa una sessione (token lungo, per dispositivo); la chiusura la revoca. |
| `POST /v1/guests`, `GET /v1/guests`, `DELETE /v1/guests/{id}` | Gli ospiti del computer: un token `pg_…` per persona (visibile solo alla creazione), l'elenco con l'uso, la revoca. |
| `POST /v1/guests/verify`, `POST /v1/guests/usage` | Quello che chiede il companion: «è un ospite di `owner`?» e «ha trascritto N secondi». Senza token del proprietario: il token dell'ospite è la prova. |
| `GET /health` | Vivo, e con quale versione del protocollo. |
| `POST /v1/shares`, `GET /v1/shares`, `DELETE /v1/shares/{id}` | Condividere una nota: un link con un token casuale, l'elenco, la revoca (che cancella anche l'audio da R2). |
| `PUT /v1/shares/{id}/audio/{partId}` e `…/multipart/…` | L'audio della nota condivisa, intero o a blocchi (R2 multipart). `HEAD` dice se c'è già. |
| `GET /s/{token}`, `…/data`, `…/audio/{partId}` | La pagina che un compagno apre, i suoi dati, l'audio con `Range`. Senza token: il link è la chiave. |

I tombstone restano novanta giorni, contati sull'orologio del **server**: un dispositivo con la
data sbagliata non può far sparire una cancellazione. Chi torna dopo più di novanta giorni riceve
`rebaseline: true` e rifà tutto da zero, cancellando localmente solo ciò che non ha toccato lui.

I limiti di D1 (due megabyte per riga, cento parametri per query, poche decine di query per
invocazione) disegnano le forme: i segmenti stanno in una tabella loro, a blocchi da quattrocento,
e ogni lotto si scrive con pochi `batch`.
