# Pampa Notes

Un'app Android che fa da **ponte** fra gli appunti che prendi altrove e gli assistenti IA. Importa
note (testo, PDF, DOCX, `.sdocx` di Samsung Notes) e registrazioni, le tiene in cartelle, trascrive
l'audio con Whisper, ed esporta bundle Markdown pensati per essere dati in pasto a Claude, ChatGPT o
Gemini come fonti.

**Quello che l'app non fa**: non riassume, non risponde a domande, non ha un assistente. L'unico uso
di un LLM è il raffinamento opzionale di una trascrizione grezza, e la grezza resta sempre.

## Comandi

`C:\VibeCoded Projects` e' sincronizzata da Google Drive, e Drive prende in carico ogni file che
Gradle scrive: per questo le cartelle di build stanno **fuori dal progetto**, in
`%LOCALAPPDATA%\PampaNotesuild\<modulo>` (vedi `build.gradle` di radice; `-Ppampa.buildRoot=`
per spostarle). L'APK di debug e' quindi in `%LOCALAPPDATA%\PampaNotesuildpp\outputspk\debug\`.
Se una build muore lo stesso con `Unable to delete directory`, e' Drive che sta ancora smaltendo
un arretrato: si rilancia.

```powershell
.\gradlew.bat :app:assembleDebug                    # build
.\gradlew.bat :core:testDebugUnitTest   # test JVM (la logica pura sta tutta qui)
.\gradlew.bat :app:installDebug         # sul telefono
.\gradlew.bat :core:connectedDebugAndroidTest   # Room, migrazioni, FTS (serve un dispositivo)
powershell -ExecutionPolicy Bypass -File engine\tools\engine-doctor.ps1 -AppRoot .
```

`local.properties` deve avere `sdk.dir=C:/Android/Sdk` — con le barre in avanti: un `\` in un file
`.properties` è un escape, e `C:\Android\Sdk` viene letto come `C:AndroidSdk`.

## Architettura

Due moduli più l'engine come submodule.

| | |
|---|---|
| `:core` | dominio, Room, DataStore, file, import, trascrizione, raffinamento, export. I package puri (pianificatore dei chunk, cucitura, writer Markdown, parser DOCX) non importano niente di Android e si provano in JVM. |
| `:app` | UI Compose, navigazione, DI, worker, share target, lettore audio. |
| `engine/` | [Fluid Engine](https://github.com/Casual76/fluid-engine) 1.36.0, submodule. **Non si modifica da qui**: una modifica non committata a monte sparisce al primo aggiornamento. |

Il design system è quello dell'engine: `FluidScreen`, `FluidListGroup`/`FluidListRow`,
`ContinuousCornerShape` (mai `RoundedCornerShape`), nessun colore o dimensione scritti a mano,
transizioni di rotta laterali e opache. Le regole per esteso stanno nella skill `fluid-engine`.

### I pannelli

Tre regimi, decisi da `fluidPaneLayout` dell'engine sulla larghezza della finestra: sotto i 600 dp
un pannello e la pillola in basso (il telefono di sempre); fino a 1000 dp il `FluidTabRail` di
fianco a un pannello (tablet in ritratto); oltre, **la barra laterale con le materie piu' uno** — o
l'elenco, o quello che si e' aperto. Non si usa `material3-adaptive`: consegna lambda di contenuto,
non `NavBackStackEntry`, e i ViewModel leggono l'id dalla rotta.

**Due pannelli al massimo, e la barra laterale e' uno dei due.** Tre si leggono come tre pagine
appiccicate, e sulle impostazioni diventano tre livelli della stessa gerarchia in una volta:
l'indice, la sezione, e un menu che con quella sezione non c'entra. Con due c'e' anche un solo tasto
indietro. Scegliere qualcosa dalla barra laterale **chiude** quello che era aperto
(`PampaNavActions.closeDetail`): senza, il tocco cambierebbe un elenco che in quel momento nessuno
vede.

Due `NavHost`, issati nella shell (`MainApp.kt`) e descritti in `ui/nav/PampaGraph.kt`: `listNav`
ha **tutte** le destinazioni, `detailNav` solo quelle di dettaglio piu' `DETAIL_EMPTY`. Dove va una
rotta lo decide `PampaNavActions` al momento del tocco, in base al regime: su una pagina larga la
nota va nel dettaglio, su una stretta in cima alla lista. `syncPanes` sposta la nota aperta da un
padrone di casa all'altro quando la finestra cambia regime, ricostruendo la rotta dagli argomenti:
la rotazione non perde il posto. Il back non ha handler scritti a mano: con `DETAIL_EMPTY` sotto,
lo stack del dettaglio ha due voci quando una nota e' aperta e il suo `NavHost` vince.

### Il colore della materia

Entrando in Storia l'accento dell'app diventa quello di Storia: tasti, pillola, selezioni, tinte
del vetro, fondali. E' la cosa che rende Pampa Notes riconoscibile a colpo d'occhio, e nessun'altra
app della famiglia ce l'ha, perche' nessun'altra ha un concetto forte come «la materia» da cui
prendere il colore.

Le schermate si iscrivono con `ReportSubject(folder?.asSubject())` — cartella, nota, sessione — e
`SubjectRegistry` decide chi vince: con due pannelli sulla stessa scena vince il ruolo `Detail`,
cioe' quello che si sta leggendo. Le schermate senza materia non si iscrivono, e si *sente* di
essere usciti da Storia. La materia vince **solo** con `AccentMode.BRAND`: chi ha scelto Material
You o una tinta dal selettore ha gia' detto di che colore vuole l'app.

I sei accenti di `subjectAccent` sono le stesse sei tinte delle tessere (`folderVividColors`): la
tessera di Storia e l'app dentro Storia devono essere dello stesso colore, o la materia non si
riconosce. L'accento si anima, perche' sul tablet passare da una materia all'altra non ha una
transizione di rotta da cui nascondersi.

### Il modello dei dati

```
Cartella (annidabile)
└── Nota — testo Markdown dell'utente + tag
    ├── Fonte — il file da cui il testo è arrivato (l'originale resta in filesDir/sources)
    └── Sessione — una lezione, un giorno
        ├── Parte audio — un file, in ordine (registrazione interrotta e ripresa = due parti, una sessione)
        └── Trascrizione — RAW da Whisper, oppure REFINED da un LLM con la RAW come genitore
            └── Segmento — testo con i tempi, relativi alla parte e assoluti nella sessione
```

Il **primo avvio** (`ui/onboarding/`) sono quattro passi e nessuno e' obbligatorio: cosa fa l'app,
da dove arrivano gli appunti, chi trascrive, dove va il backup. Con l'accesso Google compilato ce
n'e' un quinto, subito dopo il benvenuto — «Hai gia' usato Pampa Notes?» — perche' quello che
l'account porta cambia i passi dopo: chi entra (`AccountSignIn`, la stessa strada della pagina
Sincronizzazione: indice compilato se non se n'e' scritto un altro, sessione, sincronizzazione
accesa, un primo giro aspettato) ritrova le note e arriva a «Chi trascrive» col computer di casa
gia' collegato, detto in una riga con «Cambia». I campi del computer si salvano anche con
«Avanti», non solo con «Prova»; un link `pampanotes://endpoint` aperto durante il primo avvio si
puo' collegare subito (`OnboardingLinks` in `MainApp.kt`), con la stessa conferma della shell. Si puo' arrivare in fondo senza
configurare niente, perche' un avvio che non lascia entrare finche' non gli si da' una chiave API e'
un avvio che si chiude; quello che chiede lo chiede pero' adesso, che e' l'unico momento in cui
qualcuno scrive l'indirizzo di un server. Finche' `onboardingDone` non si sa — e' `null`, non
`false` — la shell non disegna niente: il default compilato direbbe «non fatto», e sarebbe un lampo
di benvenuto a ogni apertura.

`JobEntity` è la coda: una riga per lavoro, il worker la porta avanti, la UI la guarda. Una coda per
provider (`groq`, `custom`), concorrenza 1 dentro ciascuna. Un lavoro che all'avvio del processo e'
ancora «in corso» e' un lavoro il cui processo e' morto (Android l'ha ucciso, un aggiornamento l'ha
sostituito): `PampaNotesApp.onCreate` lo rimette in coda (`requeueInterrupted`) prima che WorkManager
possa far partire un worker. Esisteva la query ma non la chiamava nessuno, e la fila restava ferma
dietro un «caricamento 98%» per sempre.

La ricerca è FTS4 su note e trascrizioni, tenuta in passo da **trigger SQL** (in
`PampaDatabase.SEARCH_TRIGGERS`), non da Room: un contenuto esterno si aggancia al rowid, e il rowid
di una tabella con chiave testuale non promette di restare lo stesso.

### I file

Gli URI che arrivano da una condivisione valgono una volta sola, quindi **si copia sempre**:
`filesDir/audio/<partId>.<ext>`, `filesDir/sources/<sourceId>.<ext>`, `filesDir/jobs/<jobId>/` per i
pezzi intermedi di una trascrizione, `cacheDir/exports/` per i bundle. `StorageRepository.sweepOrphans`
toglie quelli che nessuna riga cita più, e la pagina Archiviazione e' il posto da cui si chiede.

## Backup e ripristino

Un file solo, uno zip nella cartella che l'utente sceglie col SAF: `manifest.json` per primo, poi
`database/pampa_notes.db`, poi `files/audio/` e `files/sources/`. Il manifesto sta davanti perche'
leggerlo non costa aprire i gigabyte che seguono: e' cosi' che la schermata puo' dire «3 note, 1
registrazione, di oggi» **prima** di chiedere conferma, che e' l'unico momento in cui accorgersi di
aver scelto il backup di marzo costa un tocco invece di un mese di appunti.

Quattro cose non ovvie, tutte in `core/backup/`:

- **Il database si copia, non si zippa vivo.** Room scrive in WAL: il file principale da solo puo'
  essere indietro di minuti. `snapshotDatabase` fa `PRAGMA wal_checkpoint(TRUNCATE)` e poi copia.
- **Niente segreti dentro.** La chiave di Groq e il token del server sono cifrati col Keystore del
  telefono, che non esce dal telefono: in un file su Drive sarebbero in chiaro e su un altro
  dispositivo non si aprirebbero comunque. `BackupSettings` porta solo le preferenze.
- **Si estrae in `filesDir/restore`, e solo alla fine si sposta.** Fino al penultimo passo un errore
  lascia l'archivio dell'utente esattamente com'era. `BackupArchive.accepts` scarta ogni nome fuori
  dai tre posti previsti: `../../databases/altro.db` dentro uno zip e' il modo classico di far
  scrivere a un'app un file che non e' suo.
- **Dopo, l'app si riavvia.** Il database e' un altro file e ogni ViewModel vivo tiene in mano le
  righe di prima; ripartire da zero e' l'unico stato di cui fidarsi, e farlo subito evita che la
  coda di trascrizione riscriva sopra quello appena ripristinato (per questo `WorkScheduler.stopAll`
  viene prima). Un backup piu' vecchio dello schema corrente va bene — Room migra all'apertura —
  uno piu' nuovo si rifiuta e lo dice.

## Stato

| Milestone | |
|---|---|
| M0 scheletro, engine, tema, Room | fatto |
| M1 note, editor, import testo/PDF, ricerca, griglia di cartelle | fatto |
| M2 audio, Groq Whisper, taglio nei silenzi, coda in primo piano | fatto |
| M3 endpoint personale + server companion WhisperX | fatto |
| M4 lettore audio con i segmenti, riordino delle parti | fatto |
| M5 export bundle e skill | fatto |
| M6 raffinamento della trascrizione | fatto |
| M7 DOCX, sdocx, share target completo | fatto |
| M8 backup, primo avvio, archiviazione | fatto |
| M9 companion in background (tray, QR, avvio automatico), Tailscale, archivio dei file sul PC | fatto |
| M10 l'indice in cloud (Worker + D1, merge a tre vie, note di conflitto) | fatto, accesso Google da configurare |
| M11 i file di un altro dispositivo (scaricati dal PC quando servono) | fatto |
| M12 condividere una nota: pagina `/s/<token>` con le parole che si accendono, audio su R2, pannello Condivisioni | fatto |
| M13 accesso Google (sessioni per dispositivo), Worker pubblicato, ospiti del computer | fatto |
| M14 aggiornare una nota da un `.sdocx` piu' nuovo, libera spazio, «solo il computer di casa», «tieni tutto anche qui», ritrascrivi, selezione multipla | fatto |
| M15 export per agenti (una cartella, un file per lezione, file sciolti), pagine scritte a mano come immagini, il computer di casa segue l'account, accesso Google nel primo avvio | fatto |

Dopo M7, il rifacimento dell'interfaccia (engine 1.32–1.35): misura di lettura e pagine intere,
vetro solo sugli elementi piccoli, tre pannelli sul tablet, la materia che colora l'app, il testo
che si accende.

Il piano per esteso: `C:\Users\casua\.claude\plans\praticamente-vorrei-un-applicazione-che-crispy-falcon.md`

## Sincronizzazione

L'indice in cloud (`worker/`, Cloudflare Worker + D1) tiene allineate note, cartelle, sessioni,
trascrizioni coi segmenti, fonti e preset fra i dispositivi. **Solo testo**: registrazioni e
originali vanno da dispositivo a computer (`companion/archive.py`) e basta, e `jobs` resta la coda
di quel dispositivo. In locale: `cd worker && npm run dev` (D1 su disco in `.wrangler/`, token in
`wrangler.toml`), e `pampanotes://sync?url=...&token=...&name=...` configura l'app senza scrivere
niente — sul tablet la dettatura di KeyVoice si infila in qualunque campo a fuoco. **Un link di
configurazione non si applica da solo**: `sync` ed `endpoint` si fermano in
`MainViewModel.pendingLink` e un `FluidAlert` mostra l'host a cui andranno note o registrazioni;
solo «Collega» scrive le impostazioni. Qualunque pagina o messaggio puo' aprire un link, e un tocco
non deve bastare a mandare gli appunti al server di un altro. Per il QR del proprio PC e' un tocco
in piu'.

Il client sta in `core/sync/`. Cinque cose che reggono tutto:

- **L'outbox la scrivono i trigger SQL** (`PampaDatabase.SYNC_TRIGGERS`), non i repository: e'
  l'unico modo di registrare una cancellazione in cascata. Una guardia (`sync_guard.applying`) li
  ferma mentre si applica un pull, o ogni riga ricevuta tornerebbe sporca e rimbalzerebbe fra i
  dispositivi. Per lo stesso motivo `INSERT OR REPLACE` e' vietato sulle tabelle sincronizzate: un
  REPLACE su un padre cancella i figli in cascata.
- **Il confronto e' a tre vie** (`SyncMerge`): locale, remoto, e l'impronta dell'ultima versione
  concordata (`sync_meta`). L'impronta salta `updatedAt`, perche' `notes.touch()` lo alza senza
  cambiare niente, e una riga «toccata» non deve vincere su una modifica vera.
- **Prima si tira, poi si manda**, e il push dichiara per ogni riga la base su cui ha scritto
  (`baseHash`): il server rifiuta (`stale`) quello che non parte dalla versione corrente, **senza
  guardare l'orologio**. Le righe rifiutate restano nell'outbox: e' cosi' che il pull le trova
  sporche e le biforca invece di sovrascriverle.
- **Un figlio puo' arrivare prima del padre.** Lo stato del Worker tiene una riga per elemento col
  `seq` della sua *ultima* modifica: una nota ritoccata dopo che le si e' aggiunta una sessione ha il
  `seq` piu' alto, e con le pagine da 200 la sessione arriva in una pagina e la nota in una dopo. La
  chiave esterna la rifiutava, la pagina tornava indietro, e il pull falliva sempre nello stesso
  punto — col push fermo dietro («FOREIGN KEY constraint failed» sul tablet, 23/09). Due difese: il
  Worker, quando una pagina si ferma a meta', ci mette dentro anche i padri che arriverebbero dopo
  (`parentsAfter`, provato su una copia del D1 vero: da 24 punti di partenza che fallivano a zero);
  il client, se un padre manca lo stesso, riporta la riga indietro nel suo savepoint e la ripresenta
  con la pagina dopo, senza che `lastPullSeq` la scavalchi (`SyncApplier.applyOrPark`). Il push manda
  prima i padri.
- **Il testo scritto a mano non si perde mai.** Una nota cambiata da tutte e due le parti: vince la
  piu' recente e l'altra diventa una nota «(conflitto — dispositivo, data)» nella stessa cartella,
  in tutti e due i versi. Per tutto il resto (sessioni, parti, cartelle) vale l'ultimo che ha scritto.
- **Un dispositivo nuovo, o un backup ripristinato**, riparte da zero: `deviceId` diverso da quello
  in `sync_state`, impronte azzerate, e l'outbox seminata con tutto quello che c'e'
  (`SyncRepository.ensureIdentity`), perche' i trigger registrano solo il futuro.
- **L'accesso e' un token, sempre.** Con `pampa.googleClientId` in `local.properties` (e lo stesso
  valore in `GOOGLE_CLIENT_ID` del Worker) la pagina mostra «Accedi con Google»: il Credential
  Manager da' un ID token, `POST /v1/auth/google` lo verifica e apre una **sessione** per
  dispositivo, e il token di sessione va nel Keystore al posto del codice. L'ID token dura un'ora e
  non si tiene. Senza client ID (sviluppo) la pagina chiede un codice, e il server accetta quelli di
  `AUTH_DEV_TOKENS`. Il client non sa quale dei due sta mandando, e non deve.

**Il computer di casa segue l'account.** Chi entra con Google su un dispositivo nuovo non deve
ricollegare il PC: indirizzo di casa, indirizzo Tailscale, nome, modello e token del companion
stanno nel Worker (tabella `computers`, `GET/PUT/DELETE /v1/account/computer`, `worker/src/computer.ts`)
e li porta `ComputerSync`, subito dopo lo stato di ogni giro — prima delle righe, cosi' un primo
giro lungo e interrotto il computer l'ha portato lo stesso; un suo errore non ferma il giro. Il
token e' l'unico segreto che l'indice tiene, e sta cifrato (AES-GCM) con `COMPUTER_KEY`, un segreto
del Worker e non una colonna: chi legge D1 vede un blob. Senza la chiave il Worker tiene gli
indirizzi e non il token, e lo dice (`tokenStored: false`). Qui non c'e' merge a tre vie: il
computer e' uno, e vince l'ultimo che ha scritto (`endpoint_updated_at`), con `endpoint_dirty` a
dire che la modifica e' nata qui (`ComputerMerge`, puro). I setter scritti a mano — Impostazioni,
primo avvio, il QR di `pampanotes://endpoint` — sporcano solo se qualcosa cambia davvero, e
spingono un giro; `applyRemoteEndpoint` scrive senza sporcare, e se il servizio non e' mai stato
scelto sceglie il computer. Il ripristino di un backup non sporca (`touch = false`): un indirizzo
di marzo non deve vincere su quello che l'account ha di oggi. Un computer configurato prima che
esistesse tutto questo sale da solo al primo giro, se l'account non ne ha uno. La chiave di Groq
invece non sale: e' dell'utente e del suo account Groq, non del computer.

I file di una parte o di una fonte cancellate altrove non si buttano: vanno in
`filesDir/trash/<giorno>/`, perche' questo dispositivo potrebbe averne l'unica copia. Il worker
(`SyncWorker`) non e' in primo piano: dura secondi. Gira all'apertura, dopo ogni import e ogni sei
ore; «Sincronizza adesso» sta in Impostazioni → Sincronizzazione.

### I file di un altro dispositivo

Con l'indice in cloud **«il file non c'e'» e' uno stato normale**: la riga di una parte audio o di
una fonte arriva dal sync, il file sta sul dispositivo che l'ha registrata e — se quello l'ha
archiviata — sul computer di casa (`archivedAt > 0`). `ArchiveFetcher` lo prende da li' quando
serve: `GET /v1/files/<sha256>` sull'endpoint risolto (LAN o Tailscale), scaricato in `cacheDir/tmp`
con l'impronta calcolata in scrittura, e spostato in `audio/` o `sources/` solo se torna. Un file a
meta' non prende mai il nome di quello buono, e `sweepOrphans` non guarda `tmp`.

**Libera spazio** (Archiviazione → «Qui e anche sul computer»): quello che ha `archivedAt > 0` e sta
ancora qui si puo' togliere dal dispositivo — originali e registrazioni con due tasti separati,
perche' un PDF si riapre in un secondo e una lezione da un'ora senza il PC non si ascolta
(`StorageRepository.evictArchived`; non tocca una registrazione con un lavoro in corso). Le righe
restano: e' lo stesso stato «il file non c'e'» di una riga arrivata dal sync, e tutto quello che
segue vale anche qui. L'export dichiara anche gli originali saltati (`skippedSources`).

**Tieni tutto anche qui** (Archiviazione, `mirrorEnabled`) e' il verso opposto per chi vuole
consultare offline: `FetchWorker` — in primo piano, come l'archivio — scarica tutto quello che il
computer ha e il dispositivo no (`ArchiveFetcher.fetchAll`, registrazioni prima degli originali,
dal piu' recente), dopo ogni giro di sync riuscito e dal tasto «Scarica adesso», che vale una volta
anche con l'interruttore spento. Segue «solo su Wi-Fi» dell'archivio. Senza, l'indice in cloud
porta solo le righe: e' per questo che un tablet appena sincronizzato con 42 note occupa 14 kB.

Chi lo chiede: il **lettore** (`SessionViewModel` guarda su disco a ogni cambio di parti e non
carica ExoPlayer finche' non ha guardato; se manca qualcosa la pagina mostra peso e tasto «Scarica»,
o «registrate su un altro dispositivo» se il PC non le ha ancora); la **coda di trascrizione**
(`TranscriptionRunner` scarica da solo prima di decodificare, cosi' una lezione registrata sul
tablet si trascrive dal telefono); le **fonti** della nota (tocco → scarica → apre). L'**export**
non scarica: dice quante registrazioni non sono entrate (`ExportResult.skippedAudio`), perche' un
semestre sono gigabyte e non si tirano giu' per sbaglio. Archiviazione conta i file che ci sono
davvero, piu' una riga «Sul computer, non qui».

## Condividere una nota

Il bundle ZIP serve a un assistente; a un compagno serve **un link che si apre e si ascolta**.
`POST /v1/shares` crea una riga in `shares` con un token da 24 byte casuali; la pagina
(`worker/src/page.ts`, un file solo, nessuna risorsa esterna) legge testo, sessioni, trascrizione
e segmenti **dall'indice al momento dell'apertura**, quindi una condivisione non copia niente ed e'
sempre aggiornata. Solo l'audio sale, in R2 sotto `<ownerId>/<shareId>/<partId>`, e solo quello
della nota condivisa: revocare cancella per prefisso e il link muore (404 su pagina, dati, audio).

Tre cose non ovvie:

- **L'audio sale a blocchi da 20 MB** (`ShareApi.uploadAudio`, multipart di R2): una richiesta a
  un Worker porta al massimo 100 MB e una lezione da due ore e' di piu'. Un `HEAD` prima di ogni
  parte rende il caricamento ripetibile, e un blocco a meta' si butta (`abort`). Prima di caricare
  si fa un giro di sync (`ShareRepository.share`), perche' la pagina mostra quello che l'indice ha
  *adesso*; una parte che sta solo sul PC passa da `ArchiveFetcher`.
- **La pagina accende le parole** con un port in JavaScript di `SessionAssembler.locate` e del
  formato di `WordTimings`: tempi relativi all'inizio del segmento, sommati a `sessionStartMs`. Se
  la sessione ha una raffinata attiva, la pagina la mostra in una scheda e la grezza in un'altra,
  perche' la raffinata non ha tempi. L'audio si serve con `Range` (206), o il salto al minuto
  quaranta scaricherebbe i primi trentanove.
- **Il controllo e' sul server, per proprietario**: elenco, revoca e caricamento passano dal token
  di chi ha creato la condivisione; un altro proprietario vede 404. Il link invece e' pubblico per
  costruzione — e' la chiave — e la schermata lo dice.

In locale R2 e' una cartella in `.wrangler/`; per il deploy vero `npx wrangler r2 bucket create
pampa-notes-audio`. `worker/test_share.py` fa il giro intero contro un'istanza vuota.

## Gli ospiti del computer

Un amico che trascrive col tuo PC. Il companion ha un token solo, quello del proprietario, che
apre anche l'archivio e lo sfratto del modello: un amico non deve averlo. Gli ospiti hanno un
token loro (`pg_…`), emesso dal Worker (`POST /v1/guests`, pannello Impostazioni → Ospiti del
computer) e revocabile da li'. Il companion, ricevuto un `pg_…`, chiede al Worker
(`POST /v1/guests/verify` con `owner` = l'account Google scritto nel suo `config.json`,
`index_url` = il Worker) e tiene la risposta dieci minuti (`GUEST_CACHE`): un token revocato
smette di valere entro dieci minuti, uno inventato non fa una richiesta a ogni tentativo. A fine
trascrizione riporta i secondi (`/v1/guests/usage`), e il pannello dice chi ha trascritto quanto.

Due scelte che non si vedono:

- **Il proprietario passa davanti.** `PriorityGate` sostituisce il lucchetto: una trascrizione
  alla volta, ma la prossima e' quella con la priorita' piu' alta (proprietario 0, ospiti 1,
  sfratto del modello 0), non la prima arrivata. Nessuno viene interrotto a meta'.
- **`owner` e' un'email, non un segreto.** Un token di ospite vale solo per il PC del proprietario
  che l'ha creato: il Worker confronta l'`ownerId` dell'ospite con le sessioni aperte da quell'email.
  Un altro utente dello stesso Worker non puo' fabbricare un ospite per il PC di qualcun altro.

L'ospite deve entrare nella rete Tailscale del proprietario (invito dal pannello di Tailscale, o
nodo condiviso): l'invito che l'app compone dice indirizzo e codice, e il link
`pampanotes://endpoint?url=…&token=…` incollato nella barra del browser configura tutto.

## Sessioni, parti, segmenti

Una regola sola, e il resto ne discende: **i segmenti appartengono alla parte, non alla
trascrizione**. La trascrizione grezza di una sessione è quello che si ottiene mettendo in fila i
segmenti delle parti che ha in quel momento, e `SessionRepository.rebuildRaw` la rifà dopo ogni
cambiamento. Per questo riordinare, separare o unire non costa una richiesta di rete: i tempi dentro
il file (`partStartMs`) non cambiano mai, cambia solo chi viene prima, e da lì `SessionAssembler`
ricalcola i tempi di sessione (`sessionStartMs`) e ricompone il testo.

Attenzione all'ordine quando una parte cambia sessione: **prima si ricompone chi riceve, poi chi
perde**. Cancellare la trascrizione di una sessione rimasta vuota si porta dietro i suoi segmenti
via cascata, compresi quelli appena spostati altrove. `SessionRepositoryTest` copre tutti e tre i
casi (sposta, separa, unisci).

Il lettore (`SessionPlayer`) parla solo in tempo di sessione: dentro ci sono N file e un indice di
playlist, ma chi tocca la frase del minuto quaranta sente il minuto quaranta della lezione, non
della terza registrazione. La traduzione la fa `SessionAssembler.locate`.
## Raffinamento

L'unico posto in cui l'app manda del testo a un modello di chat, e fa una cosa sola: riscrivere
quello che gli si da'. Non riassume, non risponde, non commenta.

La guardia (`RefinementPrompts.GUARD`) e' la prima e l'ultima cosa che il modello legge, sempre, e
non e' teorica: ognuno dei sei divieti e' una cosa che un modello fa da solo la prima volta che gli
si passa una trascrizione senza dirgli niente. Il preset dice *quanto* ripulire; la guardia dice che
non si sta rispondendo a niente.

**La grezza resta.** Una raffinata nasce figlia della grezza (`parentId`) e non la sostituisce mai:
un tocco sulla scheda «Grezza» la riporta a schermo, con i suoi tempi e il suo lettore. E' l'unica
cosa che rende accettabile far riscrivere una fonte a una macchina. Una raffinata non ha segmenti,
quindi non ha tempi: l'export lo dice invece di stampare un testo senza tempi come se fosse quello
che era stato chiesto.

Due difetti reali che il codice gestisce perche' sono capitati:

- **Il limite di Groq.** Il piano gratuito da' 8000 token al minuto, e una lezione da tremila parole
  sono due richieste che sulla seconda lo superano. Il servizio dice quanti secondi mancano:
  `sendWithRetry` li aspetta, e la riga del lavoro lo scrive. Senza, il raffinamento di qualunque
  lezione lunga falliva sempre, sul secondo pezzo.
- **Quello che il modello aggiunge lo stesso.** Il blocco di codice intorno al testo e la frase di
  servizio in apertura se ne vanno in `cleanUp`. Il preambolo si riconosce da due cose insieme —
  parole di servizio *e* due punti finali — perche' le parole da sole tagliavano «Ecco, allora,
  ricominciamo da dove eravamo», che era la prima frase della lezione.

Il rapporto fra le parole ripulite e quelle grezze fuori da 0,6–1,3 marca la versione `SUSPICIOUS`:
sotto ha riassunto, sopra ha aggiunto. Non si rifiuta il risultato, si segnala.

## Export

Il motivo per cui l'app esiste. Tre formati: il **pacchetto** (ZIP), gli stessi **file sciolti** per
chi non apre gli ZIP (un Progetto di Claude, ChatGPT), e un **file singolo** da incollare.

Lo ZIP ha dentro **una cartella sola**, `pampa-notes-<ambito>/`, che e' anche il nome della skill:
Claude carica una skill cosi' — una cartella con `SKILL.md` — ed estratto non sparge file nella
cartella di chi lo apre. Dentro, in ordine d'importanza:

- `INDEX.md` — per ogni nota le date, le prime parole degli appunti (il «di cosa parla» che un titolo
  come «Lezione 7» non dice) e **una riga per file** con parole, minuti e, per i pezzi, il tratto di
  lezione (`41:10–1:22:05`). Un assistente non apre venti file: ne apre uno e decide.
- `notes/<cartelle>--<nota>.md` — gli **appunti**: front-matter, corpo, le pagine scritte a mano come
  immagini, e l'elenco delle sessioni coi collegamenti.
- `notes/<cartelle>--<nota>--AAAA-MM-GG.md` — la **trascrizione** di una lezione, un file per
  sessione. Sopra le 6000 parole (`TranscriptPieces.MAX_WORDS_PER_FILE`) si divide in pezzi uguali ai
  confini di paragrafo, `--1di3`, `--2di3`: in un file solo una lezione di due ore un agente la legge
  troncata. Ogni pezzo si legge da solo: front-matter con nota, giorno, pezzo e tratto, una riga che
  ricorda che e' testo di una macchina e dove stanno gli appunti, i collegamenti al pezzo prima e dopo.
  Appunti e trascrizione in **file diversi** e' la distinzione da cui dipende tutto: un modello che
  non sa quale dei due sta leggendo tratta un errore di Whisper come una cosa che l'autore ha scritto.
- `images/<nota>/pagina-N.png` — le pagine scritte a mano. Entrano sempre, anche con gli originali
  spenti: sono appunti, non provenienza.
- `SKILL.md` + `instructions.md` — le regole. La `description` della skill si costruisce dai titoli
  veri delle note (una frase generica non viene scelta mai), sotto i 1024 caratteri e senza `<>`, o
  Claude rifiuta la skill.
- `README-FOR-AI.md`, bilingue, e `manifest.json` (schema 2, con `files[]` per nota).
- `audio/`, `sources/` se chiesti, coi nomi resi unici (`2025-10-09-01-Voce 001.m4a`): Samsung Notes
  chiama «Voce 001» la prima registrazione di ogni nota, e due voci uguali facevano fallire lo ZIP.

`notes/` e' piatta apposta: i nomi sono unici per costruzione (`BundleLayout`, senza distinguere
maiuscole, coi nomi riservati di Windows evitati), i file di una nota stanno vicini in un elenco
ordinato, e nel formato sciolto la cartella sparisce senza rompere un collegamento — fra le note i
collegamenti sono nomi nudi. Indice, manifest e note leggono la stessa mappa dei percorsi.

Provato alla cieca: un agente con in mano solo lo ZIP di «Romanticismo» ha aperto README, INDEX,
istruzioni e appunti, ha cercato con `grep` nella trascrizione e ha guardato la pagina a mano; ha
risposto «social catena» — Whisper aveva capito «social casino» — dicendo da dove veniva ciascuna.

I writer (`BundleLayout`, `MarkdownWriter`, `IndexWriter`, `SkillWriter`, `BundleWriter`) sono puri e
si provano in JVM; `ExportService` e' l'unico pezzo che tocca il database e il SAF. Lo ZIP si scrive
in streaming, con le voci di cartella esplicite, e audio e immagini `STORED`. Una scrittura fallita
cancella il file a meta'. I file sciolti si scrivono sempre nella cache e da li' si condividono
(`ACTION_SEND_MULTIPLE`) o si copiano in una sottocartella di quella scelta. Cambiare «Quale
trascrizione» nel pannello rifa' la raccolta: si decide leggendo il database, non scrivendo.

Le parole che finiscono dentro il pacchetto passano da `ExportLabels`, riempito dall'app con le
stringhe della sua lingua: i writer stanno in `:core` e non possono leggere `res/values`.

## Import

Gli URI di una condivisione si copiano subito (vedi sopra) e poi si legge la copia. Un tipo per
lettore: `TextExtractor` per testo, PDF (PdfBox) e DOCX (`DocxParser`, SAX su `word/document.xml`,
puro e provato in JVM). `MimeSniffer` non si fida del MIME dichiarato: guarda l'estensione, poi i
primi byte, e uno zip lo apre per distinguere `.docx` da `.sdocx`. "Apri con" da un gestore file e'
un `ACTION_VIEW` con il file in `data`: `ImportRequest.fromIntent` lo tratta come una condivisione.

### Samsung Notes (`.sdocx`), la strada principale

E' quello che l'utente usa ogni giorno, quindi ha un percorso corto: una schermata sola con titolo,
paragrafi, registrazioni e la cartella indovinata dal titolo, un tasto, e la trascrizione parte da
sola se `autoTranscribeOnImport` e' acceso. L'archivio originale resta come fonte `SDOCX`.

Il formato, decodificato da un file vero (`core/src/test/resources/sdocx/fichte.sdocx`):

- e' uno zip; il tablet lo condivide con MIME **`application/sdoc`**, che va nel manifest, nel
  selettore e in `MimeSniffer`, altrimenti l'app non compare fra quelle proposte;
- `note.note`: stringhe UTF-16LE con prefisso int32 di lunghezza in caratteri; la prima lunga e'
  il titolo, la seconda il corpo. Il filtro "quasi solo lettere latine" serve: i tratti della S-Pen
  sono coppie di byte che `isLetter` accetta;
- i nomi delle registrazioni ("Voce 001", "HH:MM:SS") hanno il prefisso **int16**;
- `media/mediaInfo.dat`: un record per file, int32 tag `0x79`, int32 indice, **int16** lunghezza
  del nome, nome UTF-16LE, sha256 in esadecimale, 2 byte, int64 timestamp in microsecondi. L'ordine
  dei record e' l'ordine cronologico delle parti.

`SdocxParser` e' tarato su questo file: se non riconosce niente, l'archivio resta come fonte e lo
dice, invece di importare una nota vuota.

**L'inchiostro diventa immagini.** Una pagina scritta con la S-Pen non ha testo da estrarre, ma
sono appunti anche quelli, e un assistente li legge con la vista. `SdocxInk` legge i tratti dei
`<uuid>.page` (formato documentato in [twangodev/sdocx](https://github.com/twangodev/sdocx), che e'
GPL: riscritto da capo, non copiato): ai livelli `u32` quanti, poi per ognuno intestazione, `u32`
quanti oggetti, gli oggetti (`u8` tipo, `u16` figli, `u32` lunghezza), 32 byte d'impronta; un
tratto ha punti compressi (primo in `f64`, poi scarti `u16` col segno nel bit 15 in trentaduesimi),
pressione, e colore e dimensione nei campi flessibili. `InkLayout` ritaglia sull'inchiostro e taglia
le pagine lunghe fra le righe (al massimo 1,4 volte la larghezza); sotto i 15 tratti non conta — una
freccia sopra una foto non e' una pagina di appunti. `InkRenderer` disegna: spessore dalla
pressione, evidenziatore largo e trasparente **sotto** la penna (disegnato come una penna sembrava
una riga che barrava la parola), colori veri. Le immagini sono sorgenti `IMAGE` con `derivedFromId`
verso il `.sdocx` (database 5): seguono sync, archivio, «Libera spazio» ed export, e se ne vanno col
`.sdocx` quando la nota si aggiorna. Gli id sono deterministici (`.sdocx` + numero di pagina), cosi'
due dispositivi che le ricavano tutti e due producono le stesse righe. Un giro unico all'avvio le
ricava dalle note importate prima; «Ricava le pagine a mano» nel menu della nota le rifa'. Nella nota
stanno nella scheda Testo, sotto gli appunti. I `media/*.spi` sono miniature in un codec proprietario
e non si usano. Un identificatore interno («0com.samsung») passava il filtro della prosa e diventava
il testo di una nota scritta tutta a mano: ora si scarta.

**La stessa nota, una versione dopo.** Gli appunti si prendono in Samsung Notes e si ricondividono
quando crescono: se il titolo e' quello di una nota gia' importata da un `.sdocx`
(`ImportCandidate.updateOfNoteId`, cercato all'ispezione) il wizard propone «Aggiorna» per primo.
`ImportTarget.UpdateNote`: il testo si **sostituisce**, le registrazioni con la stessa impronta
restano con le loro trascrizioni, le nuove entrano una sessione per giorno di registrazione, e il
`.sdocx` vecchio se ne va — riga, file, e blob sul PC (`DELETE /v1/files/<sha>` del companion,
solo se nessun'altra fonte lo cita). Stesso contenuto (stessa impronta) e' invece un doppione, e
resta l'avviso di prima.

## Il testo che si accende

Premuto play, le parole passano da velate a piene mentre vengono dette. Regge su tre cose:

- **le parole stanno nel database**, sempre. WhisperX le allinea con un modello fonetico; Groq da'
  solo i tempi di ogni frase, e allora `WordTimings.interpolate` le distribuisce nell'intervallo in
  proporzione ai caratteri. L'interpolazione si fa **in scrittura** (`SessionAssembler.assemble`),
  cosi' la UI ha una strada sola. `wordsEstimated` dice quale delle due, e la schermata lo scrive:
  una parola che si accende e' una promessa di precisione.
- **i tempi salvati sono relativi al segmento dentro la parte** (`WordTimings.encode`), che e'
  l'unico riferimento che non cambia mai. Riordinare le parti resta una ricomposizione: se fossero
  assoluti, ogni riordino vorrebbe dire riscrivere ogni parola di ogni segmento.
- **il disegno non rimisura**. `FluidSpokenText` dell'engine prende la posizione come lambda e la
  legge dentro il disegno: a cinque battiti al secondo un parametro rimisurerebbe il paragrafo
  cinque volte al secondo.

## Trascrizione

Due strade, stessa interfaccia (`TranscriptionProvider`):

- **Groq** con la chiave dell'utente (`AiKeyStore` dell'engine la cifra col Keystore). Limite 25 MB
  per richiesta sul piano gratuito, quindi un'ora di audio va decodificata a PCM 16 kHz mono,
  tagliata nei silenzi e ricucita.
- **Endpoint compatibile OpenAI** (`{base}/v1/audio/transcriptions`): il PC di casa con WhisperX
  dietro il server in `companion/`. Accetta file interi, niente chunking, e restituisce i segmenti.

Il raffinamento passa da `ChatProvider.complete` di `engine-ai` su Groq. Non è un assistente: è un
passaggio che toglie intercalari e rimette la punteggiatura senza cambiare il contenuto.

### Solo il computer di casa

`customOnly`: mai con Groq, nemmeno in automatico. `PampaSettings.transcriptionProvider` e' quello
che ogni `enqueue` usa (import, nota, sessione, selezione), e con l'interruttore acceso e' sempre
`CUSTOM`; il selettore del servizio sparisce dalle impostazioni. E' la garanzia che serve per tenere
accesa «trascrivi appena importi» senza che una lezione finisca nel cloud per sbaglio.

Quello che la rende utilizzabile e' che **la coda del computer di casa aspetta invece di fallire**.
`TranscriptionQueueWorker`, prima di ogni lavoro di quella coda, chiede
`TranscriptionRepository.endpointState()`: `/health` sull'indirizzo scelto, due secondi. Se il
computer e' configurato ma non risponde, il lavoro resta `QUEUED` con la fase `endpoint` («In
attesa del computer di casa»), il worker si chiude con `retry` (trenta secondi, poi il doppio) e
accende la sonda `EndpointWatchWorker`, ogni quarto d'ora finche' la fila non e' vuota. Un errore di
rete a meta' lavoro col computer muto rimette in fila invece di fallire (`requeueForEndpoint`). Chi
vede il computer rispondere lo sveglia prima: l'archivio dopo un giro andato bene, l'apertura
dell'app (`WorkScheduler.wake`, che sostituisce un tentativo in attesa ma mai un worker che lavora).
Attenzione al resolver: con due indirizzi `EndpointResolver.resolve` **restituisce sempre una
strada**, anche se nessuna delle due risponde — decide *quale*, non *se*. Per sapere se il computer
c'e' bisogna battere `/health`, ed e' il bug che il primo giro di prova ha trovato («Il servizio non
ha risposto in tempo» invece dell'attesa).

**Ritrascrivi** (menu della sessione, con conferma) e' un `enqueue` come gli altri: `saveTranscript`
sostituisce la grezza e porta via le raffinate. **Selezione multipla** («Seleziona» nel menu della
barra): nella nota, le sessioni (ritrascrivi, elimina); nella cartella, le note (trascrivi quelle
da fare, sposta con `FolderPickerSheet`, esporta con `ExportScope.Notes`, elimina). La barra in alto
diventa quella della selezione — titolo «N selezionate», indietro la chiude — invece di una barra
in basso che non esiste nell'engine. In Lavori, «Riprova tutti i falliti».

## Firma e pubblicazione

`local.properties` (git-ignorato) con `pampa.storeFile`, `pampa.storePassword`, `pampa.keyAlias`,
`pampa.keyPassword`, oppure le variabili `PAMPA_RELEASE_*`. Senza chiave la release si firma con
quella di debug e lo dice: quella build non è pubblicabile. Lo store legge `manifest.json` alla
radice; la pubblicazione passa dalla skill `pampa-store-publish-direct`.
