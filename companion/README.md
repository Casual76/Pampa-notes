# Il tuo computer come servizio di trascrizione

Pampa Notes può mandare le registrazioni a Groq, oppure al tuo computer. Questa cartella contiene
il secondo: WhisperX dietro le tre chiamate dell'API di OpenAI che l'app conosce.

## Perché, se Groq funziona già

| | Groq | Questo |
|---|---|---|
| Limite per richiesta | 25 MB, quindi un'ora va tagliata e ricucita | nessuno, il file va intero |
| Dove finiscono i file | su un server americano | in casa |
| Tempi delle parole | quelli che Whisper stima | allineati con un modello fonetico |
| Velocità | un'ora in pochi minuti | 42 volte il tempo reale su una 4070 Ti |
| Serve | una chiave gratuita | una GPU, o molta pazienza |

Una cucitura che non si fa è una cucitura che non può sbagliare: sul computer il file non viene mai
diviso, quindi non c'è un confine in cui una frase possa perdersi.

## Due doppi clic

Nella cartella ci sono due file da aprire com'è, senza terminale:

| | |
|---|---|
| `installa.cmd` | Una volta sola. Crea un ambiente Python a sé in `.venv`, ci installa WhisperX, e poi rimette torch nella versione per la tua scheda. |
| `avvia.cmd` | Tutti i giorni. Parte il server, e la finestra resta aperta: se qualcosa va storto, l'errore si legge. |

L'ordine dentro `installa.cmd` non è casuale, ed è l'inciampo che ha tenuto fermo questo server per
un giorno: WhisperX si porta dietro un torch senza CUDA che scavalca quello che c'era, quindi la
versione per la scheda va messa **dopo**.

L'altro inciampo è la versione di Python. WhisperX va su **3.9–3.12**: con un Python più nuovo
(3.13, 3.14) l'installazione muore a metà con un errore che parla di compilatori C. Lo script cerca
da solo un 3.11 o un 3.12; se non lo trova:

```
installa.cmd -InstallPython
```

Con un driver NVIDIA vecchio, `installa.cmd -Cuda cu126`. Senza scheda, `installa.cmd -Cuda cpu`.

Chi preferisce il terminale ha ancora `setup.ps1` e `run.ps1`, che i due `.cmd` si limitano a
chiamare.

## Il modello arriva quando serve, e poi se ne va

`avvia.cmd` **non** carica il modello: si mette in ascolto in due secondi e aspetta. Il modello si
carica alla prima registrazione che arriva, e se ne va da solo dopo **dieci minuti** che non arriva
più niente, restituendo la memoria della scheda — su una 4070 Ti sono quattro gigabyte e mezzo che
tornano liberi per tutto il resto.

Prima non era così, e le due cose che costava si pagavano ogni volta:

- per i minuti del caricamento il server non rispondeva nemmeno a `/health`, e dall'app si leggeva
  come «server non raggiungibile» — che manda a cercare il problema dalla parte sbagliata;
- la VRAM restava occupata tutto il giorno per una lezione al pomeriggio.

Adesso l'attesa la paga la prima trascrizione, che tanto è già un'attesa. «Prova la connessione»
risponde subito, a modello scarico.

```
avvia.cmd --idle-minutes 30     # tienilo più a lungo
avvia.cmd --idle-minutes 0      # non liberare mai la VRAM
avvia.cmd --preload             # caricalo subito, come prima
```

`/health` dice a che punto è: `loaded`, quanta memoria risulta occupata sulla scheda, e fra quanti
secondi scade.

Per dare un'idea: su una RTX 4070 Ti, `large-v3` fa una lezione di **31 minuti in 45 secondi**.

Poi nell'app: **Altro → Impostazioni → Server personale**, incolli l'indirizzo, tocchi «Prova la
connessione». Se risponde, in Trascrizione scegli «Server personale» e da lì in poi le lezioni
passano di qui.

## Opzioni

Valgono per tutti e due, con il trattino singolo per `run.ps1` e doppio per `avvia.cmd`:

```powershell
.\run.ps1 -Model medium        # più veloce, un po' meno preciso
.\run.ps1 -Device cpu          # senza GPU: lento, ma funziona
.\run.ps1 -Port 9000
.\run.ps1 -BatchSize 8         # se la GPU va in esaurimento di memoria
.\run.ps1 -IdleMinutes 30      # quanto tenere il modello dopo l'ultima lezione
.\run.ps1 -Token unaparola     # se il computer è raggiungibile da fuori casa
```

## Se il tablet non lo trova

Quasi sempre è il firewall di Windows, e c'è un file apposta:

```
apri-firewall.cmd
```

Chiede lui i permessi di amministratore e apre la porta 8765 **solo verso i dispositivi della tua
rete** (`-RemoteAddress LocalSubnet`).

Vale la pena sapere perché il consiglio di prima non bastava. La regola che questo README suggeriva
veniva creata per il profilo **Private**, ma Windows classifica spesso la rete di casa come
**Public** senza dirlo a nessuno, e in quel caso la regola non si applica: il comando sembrava
eseguito bene e il tablet continuava a non vedere niente. Adesso la regola vale per tutti i profili,
e `run.ps1` controlla la porta invece del nome della regola, così una regola sbagliata non si
scambia per una buona.

Il resto: che tablet e computer siano sulla stessa rete. Se vuoi usarlo anche da fuori casa,
Tailscale è la strada più semplice — il computer prende un indirizzo che funziona ovunque — e in
quel caso metti anche un token.

## Non è per forza questo

L'app parla con qualsiasi endpoint compatibile con l'API di OpenAI. Vanno bene anche
[Speaches](https://github.com/speaches-ai/speaches) (Docker, un comando), LocalAI, o il server di
`whisper.cpp`. Basta che risponda a `POST /v1/audio/transcriptions` con `verbose_json`. Questo
script esiste perché WhisperX da solo non è un server, e perché i suoi tempi sono i migliori
in circolazione.
