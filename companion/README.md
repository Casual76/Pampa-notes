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

## Due comandi

```powershell
powershell -ExecutionPolicy Bypass -File setup.ps1
```

Crea un ambiente Python a sé in `.venv`, ci installa WhisperX, e poi rimette torch nella versione
per la tua scheda. Lo fa lui, e nell'ordine giusto: WhisperX si porta dietro un torch senza CUDA
che scavalca quello che c'era, quindi la versione CUDA va messa **dopo**. È l'inciampo che ha
tenuto fermo questo server per un giorno, e adesso sta dentro lo script.

L'altro inciampo è la versione di Python. WhisperX va su **3.9–3.12**: con un Python più nuovo
(3.13, 3.14) l'installazione muore a metà con un errore che parla di compilatori C. Lo script cerca
da solo un 3.11 o un 3.12; se non lo trova, lo installa lui, accanto a quello che c'è:

```powershell
powershell -ExecutionPolicy Bypass -File setup.ps1 -InstallPython
```

Con un driver NVIDIA vecchio, `-Cuda cu126`. Senza scheda, `-Cuda cpu`.

```powershell
powershell -ExecutionPolicy Bypass -File run.ps1
```

Carica il modello e resta in ascolto. La prima volta scarica qualche gigabyte: il modello, e un
allineatore per ogni lingua la prima volta che la incontra. Dopo, parte in mezzo minuto. Stampa gli
indirizzi su cui il telefono lo trova.

Per dare un'idea: su una RTX 4070 Ti, `large-v3` fa una lezione di **31 minuti in 45 secondi**.

Poi nell'app: **Altro → Impostazioni → Server personale**, incolli l'indirizzo, tocchi «Prova la
connessione». Se risponde, in Trascrizione scegli «Server personale» e da lì in poi le lezioni
passano di qui.

## Opzioni

```powershell
.\run.ps1 -Model medium        # più veloce, un po' meno preciso
.\run.ps1 -Device cpu          # senza GPU: lento, ma funziona
.\run.ps1 -Port 9000
.\run.ps1 -BatchSize 8         # se la GPU va in esaurimento di memoria
.\run.ps1 -Token unaparola     # se il computer è raggiungibile da fuori casa
```

## Se il telefono non lo trova

Quasi sempre è il firewall di Windows. Da un PowerShell come amministratore:

```powershell
New-NetFirewallRule -DisplayName "Pampa Notes 8765" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow -Profile Private
```

Poi che telefono e computer siano sulla stessa rete. Se vuoi usarlo anche da fuori casa, Tailscale
è la strada più semplice: il computer prende un indirizzo che funziona ovunque, e in quel caso metti
anche un token.

## Non è per forza questo

L'app parla con qualsiasi endpoint compatibile con l'API di OpenAI. Vanno bene anche
[Speaches](https://github.com/speaches-ai/speaches) (Docker, un comando), LocalAI, o il server di
`whisper.cpp`. Basta che risponda a `POST /v1/audio/transcriptions` con `verbose_json`. Questo
script esiste perché WhisperX da solo non è un server, e perché i suoi tempi sono i migliori
in circolazione.
