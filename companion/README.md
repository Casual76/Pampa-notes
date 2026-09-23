# Il tuo computer come servizio di trascrizione

Pampa Notes può mandare le registrazioni a Groq, oppure al tuo computer. Questa cartella contiene
il secondo: WhisperX dietro le tre chiamate dell'API di OpenAI che l'app conosce.

## Chi può usarlo

Il computer riconosce **il tuo account Google**. In `config.json` servono due righe:

```json
{ "index_url": "https://pampa-notes-sync.<tuo>.workers.dev", "owner": "tu@gmail.com" }
```

`owner` è l'account con cui fai la sincronizzazione (lo stesso che vedi in *Sincronizzazione*). Un
dispositivo in cui sei entrato con Google chiede al Worker un **biglietto per il PC** (`pt_…`, dura
dodici ore) e lo manda al companion, che chiede al Worker se è davvero tuo e tiene la risposta fino
alla scadenza — così un'interruzione di internet non ferma il computer. Verso il PC non viaggia mai
il token della sincronizzazione, che in casa passerebbe in chiaro e aprirebbe tutte le note.

Valgono anche:

- il **codice** (`token` in `config.json`), per i dispositivi senza account e come riserva senza
  internet. Si scrive a mano nell'app: il QR non lo porta più;
- gli **ospiti** (`pg_…`), vedi sotto.

Senza credenziali il server risponde 401, tranne `/health` e la pagina del QR. Il controllo avviene
**prima** di leggere il corpo: un caricamento senza permesso viene rifiutato prima dei suoi
gigabyte, non dopo.

**L'accesso libero** (`accept_anonymous`) è il ponte per il passaggio: acceso, chi non manda niente
passa come proprietario, com'era prima. Un `config.json` che c'era già lo trova acceso alla prima
lettura (spento se aveva un `token`: lì chi non lo mandava era già fuori), così l'app vecchia sul
tablet continua a funzionare; un config nuovo parte spento. Quando tutti i dispositivi sono
aggiornati, dal menu dell'icona: **«Accesso libero (spegni quando i dispositivi sono aggiornati)»**.
`/health` dice come si entra: `"auth": {"account": true, "anonymous": false}`.

## Gli ospiti

Un amico può trascrivere con questo computer senza avere il tuo account né il tuo codice.
Nell'app, *Impostazioni → Ospiti del computer* crea un invito con un codice `pg_…`; il companion lo
verifica chiedendo al Worker dell'indice, con le stesse due righe di sopra. Tu passi sempre davanti
agli ospiti nella fila; l'archivio dei file e lo scarico del modello restano solo tuoi. L'ospite
deve entrare nella tua rete Tailscale (pannello di Tailscale → *Users → Invite*, o condividi il nodo).

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

## Installazione per tutti

Per chi non vuole sapere cos'è un ambiente Python: un setup, `PampaCompanionSetup-<versione>.exe`,
dalle [release del repository](https://github.com/Casual76/Pampa-notes/releases) (quelle che
cominciano per `companion-v`). Nell'app, *Impostazioni → Servizi → Installa sul tuo computer* porta lì,
e «Condividi il link» lo manda al PC per mail o chat.

Serve Windows 10 o 11, una decina di GB liberi e internet per la prima installazione. Una scheda
NVIDIA è consigliata: senza, il companion trascrive sul processore con un modello più piccolo, e
un'ora di lezione chiede mezz'ora.

Cosa succede, in una finestra con la barra e con **«Riprova»** se la rete cade:

1. il setup copia il companion in `%LOCALAPPDATA%\Programs\PampaCompanion`, senza chiedere
   l'amministratore, e con [uv](https://github.com/astral-sh/uv) prepara un Python 3.11 tutto suo
   dentro quella cartella (nessun altro Python del computer viene toccato);
2. `installer\install.py` controlla Windows e spazio, legge la scheda con `nvidia-smi`, installa
   WhisperX e poi torch per la scheda (cu128, o cu126 con un driver prima del 570, o per il
   processore), mette ffmpeg in `bin\` se il computer non ce l'ha;
3. sceglie il modello con la stessa stima della VRAM del server ([Quanta VRAM](#quanta-vram)) e lo
   **scarica subito**, con la barra, insieme all'allineamento per l'italiano;
4. scrive `config.json` (modello, porta, e un **codice** per i dispositivi senza account); apre la porta
   nel firewall — qui Windows chiede il permesso, una volta; accende l'avvio automatico; dice se c'è
   Tailscale e con quale indirizzo;
5. avvia l'icona accanto all'orologio e apre nel browser **la pagina del QR** (`/pair/start`, che
   risponde solo a questo computer).

**L'account senza scrivere niente.** Il QR porta alla pagina del server, e finché il computer non è
di nessuno il bottone «Apri Pampa Notes» porta anche un **codice di collegamento**, che vale dieci
minuti e una volta sola. Se nell'app sei entrato con Google, dopo «Collega» l'app manda codice,
account e indirizzo dell'indice a `POST /v1/pair/bind`, col suo biglietto per il PC; il companion
chiede all'indice se il biglietto è davvero di quell'account e solo allora scrive `owner` e
`index_url` in `config.json`. Da lì entra chi ha il tuo account. Un computer già di un account non
cambia padrone dall'app (risponde 409): si cambia a mano in `config.json`. Il collegamento si fa solo
dalla rete di casa o da Tailscale.

**Gli aggiornamenti.** Una volta al giorno l'icona guarda l'ultima release `companion-v*`; se è più
nuova di `VERSION`, il menu dice **«Aggiorna a vX»**: scarica il setup e lo lancia in modalità
aggiornamento (`/SILENT /UPGRADE`). L'ambiente e il modello restano, cambia il codice, e l'icona si
riavvia solo quando `/health` dice che non sta trascrivendo e non c'è nessuno in fila. Solo per chi
ha installato col setup: una cartella preparata a mano con `installa.cmd` non riceve un setup che
installerebbe altrove.

Dal menu Start: **«Collega il telefono (QR)»**, **«ripara»** (rifà i passi, tenendo quello che c'è) e
la disinstallazione, che toglie ambiente, collegamento di avvio e regola del firewall, e chiede se
tenere l'archivio dei file e `config.json`. Il modello resta nella cache di Hugging Face
(`%USERPROFILE%\.cache\huggingface`), condivisa con altri programmi: si cancella a mano.

Per costruire il setup (Inno Setup 6 e uv sul computer di chi lo costruisce; lo script dice come
averli se mancano):

```powershell
powershell -ExecutionPolicy Bypass -File installer\build-installer.ps1
```

La versione sta solo in `VERSION`. Le prove: `.venv\Scripts\python.exe -m unittest discover -s installer -p "test_*.py"`.
Per provare `install.py` senza toccare il companion vero, su una copia della cartella (il codice
deve già stare in `--app`, come dopo il setup):
`python C:\prova\installer\install.py --app C:\prova --port 8799 --no-autostart --no-firewall`.

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

## Le parole allineate

WhisperX dà i tempi di ogni parola allineandola con un modello fonetico (per l'italiano
`VOXPOPULI_ASR_BASE_10K_IT` di torchaudio). Prima di allineare divide il testo in frasi con NLTK, e
NLTK 3.10 rifiuta di aprire un file il cui percorso *risolto* non sta sotto le sue cartelle dati.
Quando il companion parte da dentro l'app di Claude, Windows sposta le scritture in `%APPDATA%` in
una copia virtuale (`...\Packages\Claude_…\LocalCache\Roaming`), la cartella e i file dentro si
risolvono in due posti diversi, e l'allineamento falliva con `Security Violation [pathsec.open]`
**a ogni lezione**, in silenzio: le parole arrivavano all'app senza tempi, e l'app li stimava.
`trust_sentence_splitter` aggiunge a NLTK la cartella dove il file sta davvero.

Un allineamento che fallisce non ferma la trascrizione, ma non si nasconde più: il registro ha la
traccia intera, e `/health` dice per lingua come è andato l'ultimo (`"alignment": {"it": "ok"}`, o
l'errore). `word_timestamps` è vero solo se sono tutti `ok`.

## Quando la scheda è piena

Se un gioco o un altro programma si è preso la scheda, WhisperX finisce la memoria a metà lezione.
Invece di un errore:

1. si libera la riserva di torch e si riprova con un lotto grande la metà (`batch_size` 16, 8, 4, 2, 1);
2. se neanche con 1 entra, quella lezione si fa **sul processore**, con un modello `int8` caricato
   apposta e poi buttato: più lenta, ma trascritta. La lezione dopo riparte dalla scheda.

L'allineamento, se finisce la memoria, si rifà sul processore. La risposta dice dove si è trascritto
(`"device_used": "cuda"` o `"cpu"`), e il registro lo scrive.

Per dare un'idea: su una RTX 4070 Ti, `large-v3` fa una lezione di **31 minuti in 45 secondi**.

## A che punto è

Finito il caricamento, dal telefono una trascrizione era un'attesa muta: la barra ferma al 100% per
dieci minuti o per un'ora, senza sapere se il computer stava caricando il modello, era in fila dietro
un ospite o era a metà. Ora l'app manda con l'audio un suo identificativo (`X-Pampa-Job: <uuid>`) e,
mentre aspetta la risposta, chiede ogni secondo `GET /v1/jobs/<id>`:

```json
{"id": "…", "state": "transcribing", "fraction": 0.7, "position": null, "audio_s": 3600.0,
 "elapsed_s": 95.2, "state_elapsed_s": 60.1, "eta_s": 25.8, "processing_s": 80.3,
 "device": "cuda", "detail": null}
```

- `state`: `received` (l'audio sta arrivando), `queued` (in fila: `position` 2 vuol dire «ce n'è una
  davanti», quella che il computer sta facendo), `decoding` (ffmpeg apre il file), `loading_model`,
  `transcribing`, `aligning`, `done`, `failed` (con il motivo in `detail`);
- `fraction` vale **dentro lo stato**: la trascrizione da 0 a 1, poi l'allineamento da 0 a 1. Sono i
  callback di WhisperX (`progress_callback`), uno per segmento della VAD in trascrizione e uno per
  segmento in allineamento: niente stime sul tempo. Con un lotto grande i segmenti escono a gruppi,
  quindi la trascrizione avanza a scatti di un lotto;
- `eta_s` c'è quando c'è abbastanza da dire (almeno il 3% e due secondi nello stato);
- `detail` dice i ripieghi: `batch 4` (memoria finita, lotto dimezzato), `cpu` (si continua sul
  processore). Un ripiego ricomincia la sua barra da zero.

Le credenziali sono quelle della trascrizione. Il proprietario vede tutti i lavori; un ospite solo i
suoi (il bearer con cui è arrivato l'audio resta col lavoro, come impronta), e per quelli degli altri
riceve lo stesso 404 di un id che non esiste. Un lavoro resta leggibile **dieci minuti** dopo la fine;
se ne tengono al massimo 256. Il lavoro si registra appena arrivano gli header, prima di leggere
l'audio: chi chiede durante il caricamento legge `received`, non un 404 che l'app scambierebbe per
un companion vecchio — e con un companion vecchio l'app smette di chiedere dopo due 404.

La risposta della trascrizione porta anche `processing_s` (quanto ha lavorato il computer, senza la
fila) e `audio_s` (la durata vera del file).

## Quanta VRAM

La memoria che WhisperX chiede alla scheda dipende da tre cose: **il modello, il `compute_type` e il
lotto** (`batch_size`). Non dalla durata: l'audio si lavora a finestre di trenta secondi, e un'ora
sono solo più finestre in fila — costa tempo e RAM, non VRAM. Il lotto è quante finestre passano
insieme, ed è l'unica manopola che conta dopo il modello.

Saperlo prima serve perché su Windows la scheda piena di solito **non dà errore**: il driver sposta
quello che non ci sta nella RAM condivisa e la lezione esce lo stesso, sei volte più lenta (è
successo: con un altro programma che teneva 3,7 GB, lezioni che vanno a 50–100 volte il tempo reale
sono andate a 12–18). Il ripiego di [Quando la scheda è piena](#quando-la-scheda-è-piena) non
scatta, perché non c'è un errore da prendere.

La stima, per *questo* processo (il desktop e gli altri programmi stanno fuori):

| voce | GB |
|---|---|
| pesi, float16 | large-v3 3,1 · medium 1,5 · small 0,5 · turbo 1,6 · base/tiny meno di 0,2 |
| pesi, `int8_float16` | poco più della metà: large-v3 1,7 · medium 0,8 |
| ogni elemento del lotto | 0,25 con large; medium 0,15, small 0,08 (misurato), in proporzione al decoder |
| allineamento (wav2vec2, uno per lingua) | 0,4 |
| contesto CUDA, VAD, spazi di lavoro | 0,7 |

`large-v3` float16 con lotto 16 fa **8,2 GB**. Sono stime: il costo per elemento è misurato con
`small` e scalato, e per large sta in mezzo a quello che il registro di questo computer dice (lotto
16 veloce a scheda libera, lento con 3,7 GB presi da altro).

**`vram_mode`** in `config.json`:

- **`"auto"`** (di serie): legge la scheda con torch e sceglie il lotto più grande — fino a
  `batch_size`, che ora è il **tetto** — perché la stima stia nell'**85%** della memoria. Se con
  il modello scelto il lotto scenderebbe sotto 4, passa a `int8_float16` (metà dei pesi, lo stesso
  testo) e poi a un modello più piccolo: su 12 GB `large-v3` float16 lotto 16; su 8 GB lotto 10; su
  4 GB `medium` int8 lotto 9. Mai in su;
- **`"manual"`**, con **`"vram_gb": 6`**: lo stesso conto, ma sulla VRAM scritta lì — «la VRAM che
  ho», o quella che vuoi lasciare al companion mentre il resto della scheda serve ad altro. Con 6 GB:
  `large-v3` int8 lotto 9.

La decisione si legge nel registro all'avvio, nel menu dell'icona (**«VRAM: stima 8.2 / 12.0 GB
(batch 16)»**) e in `/health`:

```json
"gpu":  { "name": "NVIDIA GeForce RTX 4070 Ti", "total_gb": 12.0, "free_gb": 6.8 },
"vram": { "mode": "auto", "device": "cuda", "budget_gb": 12.0, "usable_gb": 10.2, "estimate_gb": 8.2,
          "batch_size": 16, "model": "large-v3", "compute_type": "float16", "fits": true, "downgraded": false,
          "requested": { "model": "large-v3", "compute_type": "float16", "batch_size_max": 16 } }
```

Il ripiego resta dov'era: se la stima sbaglia per difetto e arriva un «out of memory», il lotto si
dimezza e alla fine si va sul processore.

Dall'app, solo per il proprietario (biglietto dell'account, codice, o accesso libero; mai gli ospiti):

```
GET  /v1/admin/settings     le impostazioni, la stima, la scheda
POST /v1/admin/settings     {"vram_mode": "manual", "vram_gb": 6} — si scrive in config.json e vale subito
POST /v1/admin/estimate     la stessa cosa senza salvare, per l'anteprima
```

Le chiavi sono `model`, `compute_type`, `vram_mode`, `vram_gb`, `batch_size_max` (in `config.json` è
`batch_size`) e `idle_minutes`. Se cambiano modello o calcolo, il modello in memoria se ne va
subito — o, se sta trascrivendo, alla fine della lezione.

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
  configura da sola con gli indirizzi. Il **codice non c'è**: una foto del QR si inoltra e resta
  nella galleria. Chi ha l'account entra col biglietto; chi no scrive il codice a mano. Il QR vale
  dieci minuti e la pagina non resta nella cache del browser; il link è anche nel registro,
  per chi preferisce copiarlo. Non contiene direttamente il link `pampanotes://` perché la
  fotocamera riconosce come link solo `http` — il resto lo mostra come testo;
- **«Avvio automatico»** — un collegamento nella cartella Esecuzione automatica dell'utente, che si
  vede e si spegne anche da Impostazioni → App → Avvio. Non un'attività pianificata, che vorrebbe
  i privilegi di amministratore; non un servizio di Windows, che non può disegnare un'icona. Il
  collegamento lancia `avvio.pyw`, non `tray.py`: aspetta venti secondi dopo l'accesso, avvia
  l'icona, controlla che `/health` risponda e, se l'icona si chiude, riprova, scrivendo ogni
  tentativo in `logs/avvio.log` e gli errori in `logs/tray-stderr.log`. Un'icona viva ma lenta a
  partire non si uccide: la si aspetta fino a cinque minuti, e poi la si lascia al suo lavoro.
  Senza, un errore nei primi secondi dopo un riavvio moriva senza traccia, e il tablet a scuola non
  trovava più il computer;
- **«Accesso libero»** — vedi [Chi può usarlo](#chi-può-usarlo). La spunta dice se è acceso, e
  cambiarla la scrive in `config.json`;
- **«Apri le impostazioni»** e **«Apri i log»**.

Il colore dell'icona dice la stessa cosa a colpo d'occhio: grigia in ascolto a scheda libera, verde
con il modello in memoria, arancione mentre trascrive.

Senza una console, gli errori finiscono in `logs\companion.log`. Un secondo doppio clic non apre un
secondo server: se la porta è già occupata, se ne accorge e si chiude.

Lo scarico manuale esiste anche come chiamata, per l'app o per chi automatizza:

```
POST /v1/admin/unload        (col biglietto dell'account o col codice; non per gli ospiti)
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

I caricamenti scrivono su quattro thread loro, separati da quelli della trascrizione, e un blocco
che non arriva per due minuti chiude il caricamento (408): un tablet che perde la rete a metà file
non può più tenere fermo il server. I `.part` rimasti da un processo morto a metà si tolgono
all'avvio.

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
  "vram_mode": "auto",
  "vram_gb": null,
  "port": 8765,
  "token": "",
  "idle_minutes": 10,
  "preload": false,
  "archive_root": "C:\\Users\\<tu>\\AppData\\Local\\PampaNotes\\archivio",
  "index_url": "https://pampa-notes-sync.<tuo>.workers.dev",
  "owner": "tu@gmail.com",
  "accept_anonymous": false
}
```

Quello che si passa a `run.ps1` o ad `avvia.cmd` vale sopra al file, per quella volta sola. Il
trattino è singolo per `run.ps1` e doppio per `avvia.cmd`:

```powershell
.\run.ps1 -Model medium        # più veloce, un po' meno preciso
.\run.ps1 -Device cpu          # senza GPU: lento, ma funziona
.\run.ps1 -Port 9000
.\run.ps1 -BatchSize 8         # il tetto del lotto (quello vero lo sceglie la VRAM)
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

Con Tailscale la porta resta dentro una rete privata, ma l'accesso libero va spento appena i
dispositivi sono aggiornati: da lì in poi entra solo chi ha il tuo account, o il codice.

## Le prove

```
.venv\Scripts\python.exe -m unittest test_companion -v
```

Senza GPU e senza modelli: un Worker finto su una porta a caso per biglietti e ospiti, il server
vero con uvicorn su un'altra, e modelli finti che finiscono la memoria a comando per il ripiego sul
processore.

## Non è per forza questo

L'app parla con qualsiasi endpoint compatibile con l'API di OpenAI. Vanno bene anche
[Speaches](https://github.com/speaches-ai/speaches) (Docker, un comando), LocalAI, o il server di
`whisper.cpp`. Basta che risponda a `POST /v1/audio/transcriptions` con `verbose_json`. Questo
script esiste perché WhisperX da solo non è un server, e perché i suoi tempi sono i migliori
in circolazione.
