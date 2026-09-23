# Il tuo computer come servizio di trascrizione

Pampa Notes può mandare le registrazioni a Groq, oppure al tuo computer. Questa cartella contiene
il secondo: WhisperX dietro le tre chiamate dell'API di OpenAI che l'app conosce.

## Gli ospiti

Un amico può trascrivere con questo computer senza avere il tuo token. Nell'app, *Impostazioni →
Ospiti del computer* crea un invito con un codice `pg_…`; il companion lo verifica chiedendo al
Worker dell'indice, e per questo in `config.json` servono due righe:

```json
{ "index_url": "https://pampa-notes-sync.<tuo>.workers.dev", "owner": "tu@gmail.com" }
```

`owner` è l'account Google con cui fai la sincronizzazione (lo stesso che vedi in *Sincronizzazione*).
Senza queste due righe gli ospiti non esistono e vale solo il token. Tu passi sempre davanti agli
ospiti nella fila; l'archivio dei file e lo scarico del modello restano solo tuoi. L'ospite deve
entrare nella tua rete Tailscale (pannello di Tailscale → *Users → Invite*, o condividi il nodo).

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

## Tre doppi clic

Nella cartella ci sono tre file da aprire com'è, senza terminale:

| | |
|---|---|
| `installa.cmd` | Una volta sola. Crea un ambiente Python a sé in `.venv`, ci installa WhisperX, e poi rimette torch nella versione per la tua scheda. |
| `avvia-in-background.cmd` | Una volta sola, poi dal menu accendi l'avvio automatico. Il server va accanto all'orologio, senza finestra. Vedi [L'icona accanto all'orologio](#licona-accanto-allorologio). |
| `avvia.cmd` | Quando qualcosa non va. Stesso server, ma con la finestra aperta: l'errore si legge lì. |

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

## L'icona accanto all'orologio

`avvia-in-background.cmd` lancia `tray.py`: lo stesso server, ma senza una finestra da tenere aperta
e da ricordarsi di aprire. Col tasto destro sull'icona:

- **lo stato** — se il modello è in memoria, quanta scheda video sta occupando, fra quanto se ne va;
- **«Scarica il modello dalla scheda video»** — per quando stai per aprire un gioco e la rivuoi
  adesso, senza aspettare i dieci minuti. Se c'è una trascrizione in corso te lo dice e non lo fa;
- **«Mostra il QR per i dispositivi»** (anche con un clic sull'icona) — si inquadra con la
  fotocamera del telefono: apre una pagina del server con il bottone «Apri Pampa Notes», e l'app si
  configura da sola con indirizzi e token. Il QR vale dieci minuti; il link è anche nel registro,
  per chi preferisce copiarlo. Non contiene direttamente il link `pampanotes://` perché la
  fotocamera riconosce come link solo `http` — il resto lo mostra come testo;
- **«Avvio automatico»** — un collegamento nella cartella Esecuzione automatica dell'utente, che si
  vede e si spegne anche da Impostazioni → App → Avvio. Non un'attività pianificata, che vorrebbe
  i privilegi di amministratore; non un servizio di Windows, che non può disegnare un'icona. Il
  collegamento lancia `avvio.pyw`, non `tray.py`: aspetta venti secondi dopo l'accesso, avvia
  l'icona, controlla che `/health` risponda e se no riprova, scrivendo ogni tentativo in
  `logs/avvio.log` e gli errori in `logs/tray-stderr.log`. Senza, un errore nei primi secondi dopo
  un riavvio moriva senza traccia, e il tablet a scuola non trovava più il computer;
- **«Apri le impostazioni»** e **«Apri i log»**.

Il colore dell'icona dice la stessa cosa a colpo d'occhio: grigia in ascolto a scheda libera, verde
con il modello in memoria, arancione mentre trascrive.

Senza una console, gli errori finiscono in `logs\companion.log`. Un secondo doppio clic non apre un
secondo server: se la porta è già occupata, se ne accorge e si chiude.

Lo scarico manuale esiste anche come chiamata, per l'app o per chi automatizza:

```
POST /v1/admin/unload        (con il token, se c'è)
```

## L'archivio dei file

Le registrazioni pesano sessanta megabyte l'ora e una nota di Samsung Notes arriva a mezzo giga:
dopo un semestre stanno solo sul dispositivo che le ha fatte. Da qui in poi il server le **tiene
anche lui**: l'app, dopo ogni import e ogni sei ore, manda al computer quello che ancora non ha —
da casa o da Tailscale, e di default solo su Wi-Fi. Si accende in *Impostazioni → Archiviazione*.

Sul computer i file stanno in `%LOCALAPPDATA%\PampaNotes\archivio` (dal menu dell'icona: «Apri la
cartella dell'archivio»; la riga sopra dice quanti sono e quanto pesano). Il percorso si cambia in
`config.json`, chiave `archive_root` — per esempio su un disco esterno o sul NAS montato come
unità. Di proposito **non** sta dentro la cartella del progetto, che Google Drive sincronizza:
gigabyte di audio dentro Drive sono esattamente quello che l'archivio esiste per evitare.

I file si chiamano con il loro hash (`blobs/39/39d2a3….m4a`): lo stesso file mandato dal tablet e
poi dal telefono occupa una volta sola, e un caricamento interrotto non lascia un file a metà con
il nome di quello buono. L'indice `archive.db` accanto ricorda il nome originale e il tipo.

Sull'app niente cambia: i file **restano anche sul dispositivo**. Questo è un archivio, non uno
sfratto — si è deciso così, e se un giorno il telefono sarà pieno il pezzo da aggiungere sarà lo
sfratto dei file già archiviati, che a quel punto è sicuro perché una copia qui c'è.

## Opzioni

Stanno in **`config.json`**, accanto agli script — il menu dell'icona lo apre, e se non c'è lo crea
dai valori di partenza. È l'unico posto che vale quando il server parte da solo all'accesso, perché
lì non c'è nessuna riga di comando.

```json
{
  "model": "large-v3",
  "device": "auto",
  "compute_type": "",
  "batch_size": 16,
  "port": 8765,
  "token": "",
  "idle_minutes": 10,
  "preload": false,
  "archive_root": "C:\\Users\\<tu>\\AppData\\Local\\PampaNotes\\archivio"
}
```

Quello che si passa a `run.ps1` o ad `avvia.cmd` vale sopra al file, per quella volta sola. Il
trattino è singolo per `run.ps1` e doppio per `avvia.cmd`:

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

Il resto: che tablet e computer siano sulla stessa rete. Da fuori casa, vedi sotto.

## Da fuori casa, con Tailscale

Il computer non si espone a internet: né porte aperte, né dominio, né indirizzo pubblico. Si mette
lui, il telefono e il tablet dentro una rete privata — [Tailscale](https://tailscale.com) — e da
lì si vedono ovunque, come se fossero in casa. È gratis per uso personale e non devia il resto del
traffico: instrada solo gli indirizzi della tua rete privata.

1. Installa Tailscale sul computer, sul telefono e sul tablet, ed entra con lo stesso account su
   tutti e tre.
2. Il computer prende un indirizzo che comincia per `100.` — il server lo trova da solo e lo mette
   nel QR e nella console (*«E da fuori casa, con Tailscale»*). Nel menu dell'icona è la riga
   *«Da fuori»*.
3. Nell'app, il campo **Indirizzo fuori casa** (o il QR, che riempie tutti e due i campi).

Da quel momento l'app **prova prima l'indirizzo di casa, per due secondi**: se il computer risponde
sulla rete locale si va diretti, altrimenti si passa da Tailscale. Non c'è niente da cambiare
uscendo o rientrando. «Prova la connessione» dice quale dei due ha risposto, così l'indirizzo di
fuori si può verificare stando a casa.

Con Tailscale la porta resta dentro una rete privata, ma un token è comunque una buona idea: costa
una riga in `config.json` e si porta dietro col QR.

## Non è per forza questo

L'app parla con qualsiasi endpoint compatibile con l'API di OpenAI. Vanno bene anche
[Speaches](https://github.com/speaches-ai/speaches) (Docker, un comando), LocalAI, o il server di
`whisper.cpp`. Basta che risponda a `POST /v1/audio/transcriptions` con `verbose_json`. Questo
script esiste perché WhisperX da solo non è un server, e perché i suoi tempi sono i migliori
in circolazione.
