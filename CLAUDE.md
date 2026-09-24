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
sostituito): torna in coda (`TranscriptionRepository.requeueInterruptedOnce`), una volta per processo,
chiamata sia dall'avvio dell'app sia dal worker prima di prendere un lavoro — chi arriva secondo
aspetta. Un «annullamento chiesto» rimasto a meta' diventa annullato. Esisteva la query ma non la
chiamava nessuno, e la fila restava ferma dietro un «caricamento 98%» per sempre.

Tre cose che la coda faceva male e non fa piu':
- **«Annulla» annulla davvero.** Il progresso si scrive con un `UPDATE … WHERE state !=
  'CANCEL_REQUESTED'` (prima la riga intera, ogni mezzo secondo, sovrascriveva la richiesta) e il
  worker guarda la riga: se diventa annullata, smette. Anche sul PC: una richiesta al companion
  annullata manda `DELETE /v1/jobs/<id>` con le credenziali della POST (`cancelRemote`, al meglio,
  quattro secondi al massimo); chiudere il socket non bastava, il companion trascriveva per nessuno.
- **Una richiesta al PC non resta appesa.** `CancellableConnection` chiude il socket appena la
  coroutine viene annullata, anche mentre aspetta la risposta; i timeout di lettura sono lunghi ma
  finiti (90 minuti per il companion). Una `CancellationException` non diventa mai un errore del
  lavoro: se il sistema ferma il worker, il lavoro torna in coda.
- **Un worker partito in background** a cui Android rifiuta il servizio in primo piano non fallisce
  e non fa `retry` (l'attesa cresceva, e restavano scritte fasi non piu' vere): i lavori in fila
  prendono la fase `app` («Pronta: apri l'app»), una notifica apre l'app, e `MainActivity.onStart`
  sveglia tutte e due le code (`readyToWake`: non chi aspetta un `until:` nel futuro). Un pezzo di
  silenzio vale come pezzo vuoto; fallisce solo una sessione tutta muta. Un retry-after di Groq piu'
  lungo di un minuto e mezzo rimette il lavoro in coda con la fase `until:<ms>` («riparte alle 14:32»).
- **Le scritture della riga sono condizionate**: partire (`start`, solo da `QUEUED`, e il numero di
  righe dice al worker se il lavoro e' ancora suo), finire, fallire (mai sopra un `CANCEL_REQUESTED`,
  che vince) e annullare sono `UPDATE` delle sole colonne che contano, non la copia della riga che il
  worker aveva letto alla partenza. Il tasto «Annulla» della notifica passa da `JobCancelReceiver`
  (`requestCancel`), non dal `PendingIntent` di WorkManager, che fermava il worker e non il lavoro.
- **Il tetto di tempo e' di silenzio** (`withIdleTimeout`): `endpointTimeoutMinutes` senza un evento
  di progresso, con un tetto complessivo largo (lo stesso per parte, piu' uno). Prima era un
  `withTimeout` sull'intera sessione, che uccideva lezioni lunghe che andavano benissimo.

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

Cinque cose non ovvie, tutte in `core/backup/`:

- **Il database si copia, non si zippa vivo.** Room scrive in WAL: il file principale da solo puo'
  essere indietro di minuti. `snapshotDatabase` fa `PRAGMA wal_checkpoint(TRUNCATE)` e poi copia.
- **Niente segreti dentro.** La chiave di Groq e il token del server sono cifrati col Keystore del
  telefono, che non esce dal telefono: in un file su Drive sarebbero in chiaro e su un altro
  dispositivo non si aprirebbero comunque. `BackupSettings` porta solo le preferenze.
- **L'archivio ha una chiusura.** L'ultima voce (`end.json`, `BackupTrailer`) dice quanti file e
  byte sono entrati: un file sparito a meta' backup si salta, e uno zip troncato fra due voci — che
  `ZipInputStream` legge senza errori — si rifiuta (`INCOMPLETE`) prima di toccare niente.
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
| M16 caccia ai problemi: sync senza corse ne' orfani, coda che si annulla e non si appende, il PC riconosce l'account, parole allineate in italiano, link che chiedono conferma | fatto |
| M17 export per destinazione, tre pallini al tocco, data vera delle registrazioni, pezzi uguali, VRAM stimata, avanzamento in tempo reale dal computer, statistiche | fatto |
| M18 il PC trascrive per impronta e taglia da se', «solo sul computer», date vere delle note, home con «Da fare» e «Riprendi ad ascoltare», statistiche sincronizzate | fatto |
| M19 Registrazioni: una sezione per l'audio che non e' scuola, coi suoi numeri e di serie solo sul computer | fatto |

Dopo M7, il rifacimento dell'interfaccia (engine 1.32–1.35): misura di lettura e pagine intere,
vetro solo sugli elementi piccoli, tre pannelli sul tablet, la materia che colora l'app, il testo
che si accende.

Il piano per esteso: `C:\Users\casua\.claude\plans\praticamente-vorrei-un-applicazione-che-crispy-falcon.md`

## Registrazioni

L'audio che non e' una lezione — una registrazione di diciannove ore, un'intervista, un viaggio —
ha una **quarta scheda**, fra Materie e Altro (`Routes.RECORDINGS`, `ui/recordings/`; sul tablet
una riga sua sotto le materie nella barra laterale, staccata da un filo e senza un titolo che
ripeta «Registrazioni», accesa anche dentro una sua cartella). La scheda delle tessere si chiama
«Materie» (era «Cartelle»: accanto a «Registrazioni», che sono cartelle anche loro, non diceva la
differenza), e cosi' «Tutte le materie» nella barra laterale. Il primo avvio lo dice in una riga, e
«Dal tasto importa» nomina anche Registrazioni. Prima finiva in una materia, e da li' in testa a
«Da fare» per settimane, nelle ore «di lezione» della home, nel pacchetto «Esporta tutto» e sul
telefono, dove diciannove ore sono gigabyte.

**La regola sta sulla radice.** `FolderEntity.kind` (database 9, `"school"` di serie, `"personal"`)
conta solo su una cartella di primo livello: una sottocartella sta dove sta la sua radice, qualunque
cosa dica la sua colonna, cosi' spostarla non chiede di ricordarsi di cambiarle il tipo. La colonna
si tiene lo stesso allineata, perche' chi guarda la riga da sola (il sync, un'altra versione) non si
confonda: `FolderRepository.create` scrive il tipo della radice, `setKind` — solo su una cartella di
primo livello («Sposta in Registrazioni» dalle tessere e dal menu della cartella, «Sposta fra le
materie» dalla scheda e dal menu) — riscrive anche le sottocartelle che dicevano altro, e `move`
da' alla cartella spostata e a tutto quello che ha dentro la sezione in cui finisce
(`kindAfterMove`: quella della nuova radice, o, portata al primo livello, quella da cui viene;
`FolderKindTest`). La stessa
regola sta in due posti che devono dire la stessa cosa: in SQL `PersonalSql.FOLDER_IDS`, una
sottoselect ricorsiva da mettere dopo `IN`/`NOT IN` (Room la accetta dentro una sottoselect e tiene
d'occhio `folders` per i `Flow`), e in Kotlin l'oggetto `PersonalScope` (`rootIds`, `folderIds`,
`isPersonal`: solo funzioni pure sulle cartelle). `PersonalSqlTest` le confronta.

**Cosa ne resta fuori**, e resta della scuola: «Da fare» e le ultime note della home
(`NoteDao.observeTodo`/`observeRecent`), «Trascrivi tutte», i numeri della home (ore, parole,
giorni di lezione, materia piu' ascoltata, note e cartelle, e le statistiche delle trascrizioni:
`StatsRepository.observe(personal)`, con le corse lette dalla sessione che hanno adesso — quella di
una sessione cancellata resta fra le materie), le tessere di Materie e la barra laterale
(`FoldersViewModel`), `ExportScope.Everything`. «Riprendi ad ascoltare» e la ricerca le mostrano:
sono di chi ascolta, non della scuola. Una cartella, una nota o una sessione di Registrazioni **non
e' una materia**: niente `ReportSubject` (la cartella lo salta, `NoteViewModel` e
`SessionViewModel` le danno `folder = null`), e l'app resta del suo accento.

**Di serie solo sul computer.** `ComputerOnlyScope.current()` tratta le cartelle di Registrazioni
come se avessero la regola quando vale `personalByDefault`: non se questo dispositivo chiede di
tenerle (`keepPersonalHere` in DataStore, per dispositivo, dal menu della scheda), non senza un
computer collegato (non avrebbe dove tenerle, e «· sul computer» su una nota che sta qui era una
bugia), e **non con «tieni tutto anche qui» acceso**: una cartella spostata in Registrazioni sul
telefono arriva col sync al tablet che scarica tutto, e la regola di serie la toglieva da li' senza
nessuna conferma. `ComputerOnlyViewModel` (menu e segni «sul computer») usa la stessa funzione. La
conferma di una regola nuova (`resolve` senza `includePersonal`) le lascia fuori, perche' conta solo
quello che quella regola toglierebbe. Mirror, «Libera spazio» e Archiviazione seguono da li'; nei
menu la voce «Solo sul computer» di una cartella di Registrazioni dice che la regola e' della
sezione. Dalla scheda: «Registrazioni solo sul computer» chiede conferma col peso di quello che se ne
andrebbe (`ComputerOnlyConfirmAlert`, la stessa di una cartella); «Tieni qui le Registrazioni»
fa partire subito un giro di scarico della sola sezione (`WorkScheduler.fetchPersonal`,
`ArchiveFetcher.fetchAll(onlyPersonal)`), anche con il mirror spento; col mirror acceso le due voci
non ci sono e la nota in fondo dice perche'. E non si aspetta
il giro periodico: quando una trascrizione finisce su una sessione coperta da una regola,
`TranscriptionQueueWorker` chiede un giro dell'archivio (`archiveIfComputerOnly`, solo con
l'archivio acceso, con `WorkScheduler.archiveSoon`: se un giro sta gia' girando ne accoda uno dopo,
perche' quello ha letto l'elenco prima che la lezione finisse), che porta il file sul PC se non c'e'
e alla fine chiama `evictComputerOnly` — con le sue guardie: il `HEAD` per file, la lezione
ascoltata nelle ultime 24 ore, i lavori in corso, la sessione aperta a schermo. Le guardie si
riguardano subito prima di ogni cancellazione, non solo all'inizio: cento `HEAD` possono durare
minuti. Un file mai archiviato non se ne va.

**Spostare fra le sezioni si conferma**, e la conferma dice il vero su questo dispositivo
(`SectionMoveAlert`): dove vanno i file dipende da computer, mirror e «tieni anche qui», e la frase
ricorda che sugli altri dispositivi la cartella arriva col sync. Vale per una cartella intera e per
le note spostate con la selezione (`FolderPickerSheet` le raggruppa come il wizard, materie sopra e
Registrazioni sotto, e chiede quando la cartella di arrivo e' dell'altra sezione).

**La scheda** e' fatta per ascoltare: in cima i numeri della sola sezione (ore registrate, ore e
parole trascritte, velocita', dagli stessi `TranscriptionStats.aggregate` della home), poi le
cartelle, poi tutte le registrazioni di tutte le cartelle dalla piu' recente. Un tocco **ascolta**
e il lettore parte da solo: riprende la sessione dell'ultimo ascolto se e' di quella nota e non e'
finita (`LastListened`), altrimenti la sessione piu' recente dall'inizio; si apre con la strada di
«Riprendi» della home (`resumeSession`, `play=1`), che sul tablet prende il posto di quello che era
aperto invece di impilarsi. Una nota senza audio ha un'icona sua e si apre come nota. Tenendo
premuto c'e' «Apri la nota», «Trascrivi», esporta, elimina; il badge e' quello della home (lavoro,
«Da trascrivere», «In trascrizione su …», «Senza parole»). «Trascrivi tutte» chiede prima,
con le ore e chi trascrive (con Groq, che l'audio va nel cloud), lascia fuori le sessioni il cui
ultimo tentativo ha risposto `no_speech`, e sparisce se restano solo quelle. Vuota, spiega a cosa
serve. Le parole sono le sue: «3 sessioni», non «3 lezioni»; la nota vuota, il wizard («In quale
cartella», «Sessione 2») e il pacchetto esportato (`ExportSet.personal`: regole, README e indice
parlano di registrazioni, `LessonWords` e `indexHowToPersonal`) non parlano di scuola.

**L'import**: «Importa» dentro una cartella di Registrazioni (o dalla scheda, con una cartella sola)
apre il wizard con la cartella gia' scelta (`ImportRequest.intoFolderId`,
`MainViewModel.onPickIntoFolder`); dalla scheda con piu' cartelle il wizard sceglie la prima di
Registrazioni (`ImportRequest.preferPersonal`, `onPickPersonal`), e «Nuova cartella» nel wizard ne
crea una di Registrazioni quando si e' partiti da li' o la cartella scelta lo e'. Da qualunque altra
parte il wizard mostra le cartelle di Registrazioni in un gruppo loro sotto le materie, e di ripiego
sceglie la prima materia, mai una cartella di Registrazioni. Il `.sdocx` indovina la materia solo fra
le materie. **«Trascrivi appena importi»** su una nota di Registrazioni parte da solo solo verso il
computer di casa: con Groq la registrazione resta «Da trascrivere» (diciannove ore di audio privato
non vanno nel cloud senza che nessuno l'abbia chiesto).

Nel sync `kind` viaggia dentro il payload della cartella, e il Worker non lo guarda. L'impronta salta
`kind = "school"` come salta un `derivedFromId` vuoto: le cartelle di prima hanno l'impronta di
prima, e l'aggiornamento non le sporca tutte (`FolderPayloadTest`). Un dispositivo con l'app di
prima che riscrive una cartella di Registrazioni (un nome cambiato) la rimanda senza `kind`, e la
cartella torna una materia: si aggiornano tutti e due.

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
  il client, se un padre manca lo stesso, **controlla prima di scrivere** (`SyncPlan`): la riga
  resta da parte senza toccare il database, si riprova nella stessa pagina finche' un giro non ne
  applica nessuna, e `lastPullSeq` non la scavalca; dopo tre giri senza padre si salta
  (`OrphanLedger`). Una prima versione catturava l'errore della chiave esterna dentro un savepoint:
  ma ogni scrittura di Room e' una transazione annidata, e una annidata fallita fa tornare indietro
  **in silenzio** l'intera pagina. Non si fa.
- **Prima gli upsert, poi le cancellazioni.** Gli upsert da padre a figlio, le cancellazioni da
  figlio a padre: una cartella cancellata e una nota spostata fuori nella stessa pagina lasciavano
  la nota senza sessioni. Una cancellazione remota di una nota, una sessione o una cartella con
  sotto qualcosa cambiato qui si salta, e il push la fa rinascere; i file di quello che cade in
  cascata vanno in `trash/`.
- **Il push e' ripetibile.** Il Worker riserva i numeri di sequenza dentro lo stesso lotto che
  scrive le righe, e ogni riga si scrive solo se e' ancora la versione letta: due dispositivi che
  mandano insieme non si danno lo stesso numero e non si sovrascrivono. Il `batchId` e' l'impronta
  del contenuto del lotto, cosi' un push interrotto a meta' si rimanda identico; il server registra
  il lotto per ultimo e riscrive una trascrizione arrivata senza i suoi segmenti. Pagine e lotti sono
  limitati anche in byte (~4 MB); una riga oltre 1,9 MB viene rifiutata da sola (`too_large`) ed esce
  dall'outbox. Il push manda prima i padri.
- **Un altro account non si mescola.** Il dispositivo ricorda l'`ownerId` (`sync_owner_id`): se
  qualcuno entra con un altro account su un telefono che ha note sincronizzate col primo, il giro si
  ferma e lo dice; «Porta queste note in questo account» (`adoptAccount`) e' una scelta esplicita.
  Il riallineamento completo chiede anche le righe del dispositivo stesso (`includeOwn=1`), o le
  cancellava come sparite. L'impronta di una trascrizione comprende i suoi segmenti, e due trigger
  su `segments` la sporcano: prima un riordino delle parti non arrivava agli altri.
- **Il testo scritto a mano non si perde mai.** Una nota cambiata da tutte e due le parti: vince la
  piu' recente e l'altra diventa una nota «(conflitto — dispositivo, data)» nella stessa cartella,
  in tutti e due i versi. Per tutto il resto (sessioni, parti, cartelle) vale l'ultimo che ha scritto.
- **Un dispositivo nuovo** riparte da zero: `deviceId` diverso da quello in `sync_state`, impronte
  azzerate, e l'outbox seminata con tutto quello che c'e' (`SyncRepository.ensureIdentity`), perche'
  i trigger registrano solo il futuro. **Lo stesso account con un id nuovo** (uscito e rientrato, un
  backup ripristinato) invece tiene impronte e `lastPullSeq`, e semina solo le righe senza impronta:
  azzerare faceva rinascere le note cancellate qui e biforcava ogni nota cambiata altrove. Il
  ripristino di un backup deve chiamare `SyncRepository.afterRestore()` (dimentica l'id): con l'id
  vecchio il pull non riporterebbe mai le righe scritte da questo telefono dopo il backup, e il
  server lascerebbe al database vecchio riscriverle.
- **Una riga cancellata qui e cambiata altrove** resta cancellata se il remoto e' uguale alla base
  (o diverso solo per il segno «in trascrizione su»), e rinasce se e' cambiato davvero. Se rinasce
  una cartella, una nota o una sessione, i figli portati via dalla cascata di qui tornano prima del
  push (`reviveChildren`: un pull intero con `includeOwn`, le radici in DataStore finche' non e'
  andata), e i loro tombstone non salgono. Un remoto con la stessa impronta della base non e' mai un
  conflitto: e' una riga riscaricata. Il server accetta una riga scritta sopra la *propria* ultima
  versione anche con la base vecchia (la risposta di un push persa), mai sopra quella di un altro.
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
ore; «Sincronizza adesso» sta in Impostazioni → Sincronizzazione, e lo stesso giro parte **tirando
giu'** la home, le materie, una cartella o una nota (`PullToSync`: il gesto e la rotellina sono
quelli di `FluidScreen`, la rotellina resta finche' il `SyncWorker` non ha finito o per venti
secondi; senza sync il gesto non c'e').

**In trascrizione su un altro dispositivo.** `jobs` non viaggia, e mentre il telefono trascriveva
una lezione il tablet la offriva «Da trascrivere»: un tocco, e la stessa ora passava due volte dal
computer. Le sessioni hanno quindi un segno sincronizzato, `transcribingOn` (il nome del
dispositivo nel sync) e `transcribingSince` (database 8), con tre regole (`TranscribingMarker`,
puro): **lo scrive solo chi trascrive** — `TranscribingMarkers` guarda la coda e lo tiene uguale ai
lavori al lavoro qui, cosi' nessuna delle sei uscite di un lavoro puo' dimenticarsi di toglierlo, e
nel merge il segno che parla di questo dispositivo resta quello di qui; **non e' una modifica** —
non alza `updatedAt`, quindi non vince su un titolo cambiato altrove, entra nell'impronta solo
quando c'e' (vuoto, la sessione ha l'impronta di prima) e da solo non fa rinascere una nota
cancellata altrove; **scade** — dopo tre ore non vale (rinnovato ogni ora mentre lavora), e
all'avvio il dispositivo toglie i suoi. Il segno sale con `WorkScheduler.syncSoon` (un giro dopo
quello in corso, non il `KEEP` di `syncNow`). Gli altri mostrano «In trascrizione su …» al posto di
«Trascrivi» e dei badge «Da trascrivere», e `enqueue` salta la sessione. Il worker riguarda **prima
di partire**, perche' un lavoro accodato qui prima che l'altro cominciasse non l'aveva visto: se il
segno c'e' il lavoro resta in fila con la fase `elsewhere:<dispositivo>` e si riguarda fra dieci
minuti; se la grezza della sessione e' nata dopo il lavoro e copre tutte le parti
(`arrivedFromElsewhere`), il lavoro si chiude fatto senza chiamare nessuno — rifarlo cancellerebbe il
risultato dell'altro e le sue raffinate.

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
(`StorageRepository.evictArchived`; non tocca una registrazione con un lavoro in corso, ne' quello
che un export o una sessione aperta a schermo tengono in `FilesInUse` — una presa per padrone, cosi'
chi rilascia la sua non libera quella dell'altro). `archivedAt > 0` non basta: prima di togliere un file si chiede
al computer di adesso (`ArchiveRepository.presence`, un `HEAD`), e un 404 rimette la riga «da
archiviare» invece di togliere l'unica copia; lo stesso fa `ArchiveFetcher` quando scarica. Le righe
restano: e' lo stesso stato «il file non c'e'» di una riga arrivata dal sync, e tutto quello che
segue vale anche qui. L'export dichiara anche gli originali saltati (`skippedSources`).

**Solo sul computer** e' il contrario per una cartella o una nota sola, **per dispositivo**
(`computerOnlyFolders`/`computerOnlyNotes` in DataStore, non si sincronizza: il tablet puo' tenere
tutto e il telefono solo le materie di quest'anno). Una cartella vale con le sottocartelle
(`FolderRepository.descendants`, che ha sostituito due copie private); `ComputerOnlyScope` ne ricava
parti e fonti. Il mirror le salta, e `StorageRepository.evictComputerOnly` le toglie dal dispositivo
quando il computer le ha: dopo ogni archiviazione riuscita e alla conferma della regola. Non tocca
una sessione con un lavoro in corso, le pagine a mano, ne' la lezione ascoltata nelle ultime 24 ore
(`protectedSessionIds`, collegato a «Riprendi ad ascoltare» in `PampaNotesApp`). Aprirle le scarica
come sempre. In Archiviazione l'elenco comincia con «Registrazioni (di serie)» quando la regola di
serie vale qui (`ComputerOnlySummary.personalByDefault`): non e' una regola scritta, ma i file se ne
vanno lo stesso, e prima la pagina diceva «Nessuna». Toccarla offre «Tieni qui le Registrazioni»,
la stessa scelta del menu della pagina Registrazioni.

**Tieni tutto anche qui** (Archiviazione, `mirrorEnabled`) e' il verso opposto per chi vuole
consultare offline: `FetchWorker` — in primo piano, come l'archivio — scarica tutto quello che il
computer ha e il dispositivo no (`ArchiveFetcher.fetchAll`, registrazioni prima degli originali,
dal piu' recente), dopo ogni giro di sync riuscito e dal tasto «Scarica adesso», che vale una volta
anche con l'interruttore spento. Segue «solo su Wi-Fi» dell'archivio. Senza, l'indice in cloud
porta solo le righe: e' per questo che un tablet appena sincronizzato con 42 note occupa 14 kB.

Chi lo chiede: il **lettore** (`SessionViewModel` guarda su disco a ogni cambio di parti e non
carica ExoPlayer finche' non ha guardato; se manca qualcosa la pagina mostra peso e tasto «Scarica»,
o «registrate su un altro dispositivo» se il PC non le ha ancora; finche' la pagina e' aperta tiene i
suoi file in `FilesInUse`, e se il lettore da' errore riguarda su disco — e dopo un errore
`SessionPlayer.load` ricarica anche la stessa playlist); la **coda di trascrizione**
(`TranscriptionRunner` scarica da solo prima di decodificare, cosi' una lezione registrata sul
tablet si trascrive dal telefono); l'**export**, per quello che si e' chiesto di metterci dentro
(vedi Export); le **fonti** della nota (tocco → scarica → apre). Archiviazione conta i file che ci sono
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

Vale anche per il risultato di una trascrizione, che arriva minuti o ore dopo la fotografia delle
parti fatta alla partenza: `SessionRepository.saveTranscription` scrive i segmenti **per parte**,
nella sessione in cui ogni parte sta adesso (`TranscriptPlacement`, puro: quella del lavoro per
ultima), riadotta sotto la grezza nuova le parole delle altre parti della sessione, salta le parti
cancellate, e ricompone ogni sessione toccata. Una sessione unita a un'altra mentre si trascriveva
si porta via la riga del lavoro (cascata), ma il worker continua (`partsOutliveSession`) e il
risultato va in quella che resta; una sessione cancellata con le sue parti non ha niente da
salvare, e si chiude in silenzio.

Il lettore (`SessionPlayer`) parla solo in tempo di sessione: dentro ci sono N file e un indice di
playlist, ma chi tocca la frase del minuto quaranta sente il minuto quaranta della lezione, non
della terza registrazione. La traduzione la fa `SessionAssembler.locate`. La barra (`PlayerBar`) e'
pensata anche per le registrazioni di ore: in fondo allo scrubber quanto manca (un tocco: quanto
dura), i tasti dei secondi tenuti premuti saltano cinque minuti (anche come azioni di TalkBack), e
lo scrubber prende il dito su 48 dp ma nella colonna ne occupa 24, cosi' la capsula non si sposta.
Le pause di un minuto o piu' («— 16 min di silenzio —») sono righe alte almeno 48 dp.

**I paragrafi e i silenzi hanno una regola sola**, `TranscriptParagraphs.split` in `:core`: a capo
dopo due secondi di pausa, al confine fra registrazioni e oltre un tetto di frasi (10 a schermo, 8
nell'export). La schermata della sessione ne aveva una copia sua e adesso usa quella. Da un minuto
in su (`SILENCE_MS`) la pausa non e' piu' solo un a capo: il paragrafo dopo porta `silenceBeforeMs`,
misurato nel tempo della sessione (anche a cavallo fra due parti, che si mettono in fila senza
buchi). A schermo e' una riga quieta sopra la card, «— 16 min di silenzio —», nello stesso elemento
della lista del paragrafo (`ScrollFollower` conta un elemento per paragrafo), e un tocco salta dove
si riprende; nell'export coi tempi e' `*[— 16 min di silenzio —]*` prima del `[mm:ss]`. Il testo
salvato della grezza **non** ha il segno, solo l'a capo: va nella ricerca, nel conto delle parole,
nel sync, nel raffinamento e nell'export senza tempi, dove una frase scritta dall'app sembrerebbe
detta dal professore, nella lingua del telefono che ha trascritto. Durate con
`TranscriptParagraphs.silenceDuration` («16 min», «1 h 20 min»).

**Salta i silenzi** (in cima al menu della sessione, con l'icona e lo stato nel nome — «Salta i
silenzi oltre 12 s: acceso» — perche' il menu dell'engine non ha la spunta, e la soglia perche' le
righe della trascrizione dicono solo i silenzi di un minuto e piu'; per dispositivo:
`PampaSettingsStore.skipSilence`, spento di serie; c'e' solo con la grezza coi segmenti e l'audio
qui). Acceso, la capsula del lettore ha un segno piccolo accanto al tempo. Il resto del menu va per
famiglie: ripulisci e ritrascrivi, poi titolo e unione, in fondo elimina. I silenzi vengono dalla trascrizione,
non dall'audio (`SilenceSkipper.gapsOf`, puro, in `core/playback/`): dentro le parti che hanno
segmenti, tutto quello che nessun segmento copre e dura almeno 12 s (`MIN_GAP_MS`), compreso
l'inizio muto e la fine muta, anche a cavallo fra due parti; una parte senza segmenti e' sconosciuta,
non muta, e non si salta mai. A ogni battito del lettore `SessionViewModel` chiede `onTick`: dopo il
primo secondo di silenzio si salta a un secondo prima della voce, in tempo di sessione (quindi anche
fra un file e l'altro), e il cronometro della capsula dice per due secondi e mezzo «Saltati 16 min».
**Il dito vince**: un salto scelto dall'utente (scrubber, frecce, tocchi) che finisce dentro un
silenzio lo fa ascoltare (`onUserSeek`), finche' la posizione non ne esce; la ripresa dal punto
salvato e l'arrivarci suonando no. In pausa non si salta. La capsula del lettore non aveva posto per
un tasto in piu' su un telefono, per questo sta nel menu.

**Cerca dentro una registrazione** (la lente nella barra della sessione): una barra di vetro sopra
il lettore — sopra la tastiera quando c'e' — con il campo, «3 di 12», precedente, successiva e
chiudi; indietro la chiude, e chiuderla la cancella. Il confronto e' `TranscriptSearch` (puro):
maiuscole, accenti (`Normalizer` NFD), apostrofi tipografici e spazi in fila non contano, e le
posizioni tornano nel testo originale grazie a una mappa carattere per carattere; l'indice si
prepara una volta per trascrizione fuori dal thread della UI, e sotto i due caratteri non si cerca.
Sulla grezza si cerca nei paragrafi di `TranscriptParagraphs` e le occorrenze si dipingono **dietro**
il testo (`searchHighlights`, dal `TextLayoutResult` di `FluidSpokenText`, che resta quello che
accende le parole); scrivere porta la lista alla prima senza toccare il lettore, precedente e
successiva portano anche il lettore alla parola (`TranscriptSearch.timeOf`: il tempo della parola, o
l'inizio del segmento). Sulla raffinata, che e' Markdown reso da una libreria, mentre la ricerca e'
aperta il testo si mostra a blocchi di testo semplice (`plainBlocks`, preparati anche loro fuori dal
thread della UI e solo a ricerca aperta) e non si salta da nessuna parte. Quando i blocchi cambiano
(un'altra scheda, un testo arrivato) le occorrenze di prima si buttano subito (`clearMatches`): prima
restavano evidenziate sulla scheda nuova finche' la ricerca non ripartiva.

I tasti piccoli del lettore e della ricerca (44 e 40 dp) prendono il dito su 48 dp senza cambiare la
capsula (`touchTarget`, in `PlayerBar.kt`: i modificatori dopo vedono 48, il layout intorno la misura
disegnata), e cosi' il tempo rimanente/totale in fondo allo scrubber (`touchHeight`).
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
  Un silenzio di un minuto o piu' e' una riga sua, `*[— 16 min di silenzio —]*`, prima del paragrafo
  in cui si riprende (vedi «Sessioni, parti, segmenti»): senza, la frase dopo sembrava la risposta a
  quella prima. Non conta nelle parole.
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

**Il pannello chiede una cosa sola: «Dove lo usi?»** (`ExportTarget`): chat (ZIP leggero, testo e
pagine a mano), Progetto (file sciolti, e «Salva in una cartella» come tasto principale: venti file
condivisi insieme a un'app di chat arrivano spesso a meta'), agente (ZIP con registrazioni e
originali), incolla (file singolo). La risposta sceglie formato e allegati (`ExportTarget.defaults`);
«Personalizza», chiuso, mostra solo gli interruttori che contano per quel formato, e un cambiamento
tiene la destinazione e la marca «personalizzato». Le opzioni salvate prima della domanda si leggono
lo stesso: `ExportOptionsCodec` deduce la destinazione da formato e allegati. La pagina Impostazioni →
Esportazione usa lo stesso componente (`ExportChoices`). Prima di esportare il pannello dice peso e
token (`ExportEstimator`, puro: parole × 1,4, 1500 token per pagina a mano) e avvisa se una chat non
ce la fara' (oltre 25 MB o 180k token).

**Quello che si chiede entra.** Registrazioni, originali e pagine a mano che qui non ci sono ma il
computer di casa ha (`archivedAt > 0`) si scaricano **prima** di scrivere (`ExportService.plan` →
`fetchMissing`, «Scarico dal computer di casa: 3 di 12»); al primo «non risponde» ci si ferma. Se
qualcosa non si puo' avere — mai archiviato, o PC muto — il pannello lo dice prima di scrivere, per
tipo e motivo, con «Esporta senza» / «Annulla»; il risultato dice cosa c'e' dentro e cosa manca, e il
manifest elenca solo i file che ci sono davvero. Era il difetto da cui e' nato tutto: «esportato»,
con le registrazioni chieste rimaste sul PC.

Le parole che finiscono dentro il pacchetto passano da `ExportLabels`, riempito dall'app con le
stringhe della sua lingua: i writer stanno in `:core` e non possono leggere `res/values`.

## Import

**Le date vere della nota.** `updatedAt` e' l'ultima modifica *vera*, non l'import: un `.sdocx` la
porta in `end_tag.bin` (int64 in microsecondi: modifica a +8, creazione a +46; le stesse in
`note.note` a +32 e +24 — le date dello zip sono quelle della condivisione). Una nota importata
prende creazione e modifica dal `.sdocx` e dall'ultima registrazione (`NoteDates`), una modifica
fatta nell'app e' «adesso», e una trascrizione non tocca piu' la nota (prima la riportava a oggi).
Le note gia' importate le corregge `RealDatesBackfill` all'avvio, solo se non toccate dopo l'import:
il `.sdocx` qui si legge con un accesso diretto allo zip, quello che sta solo sul PC lo legge il PC
(`GET /v1/files/{sha}/meta`, `FileMetaApi`); col PC spento resta in attesa. Anche le sessioni datate
col giorno dell'import prendono il giorno della registrazione.

Gli URI di una condivisione si copiano subito (vedi sopra) e poi si legge la copia. **La data di una
registrazione** e' quella in cui e' stata fatta, non quella dell'import (`RecordingDate`): prima la
data dei metadati del contenitore, poi una data nel nome del file (Registratore Samsung, WhatsApp,
`20250922_101500`…), poi la data del file dal provider, e solo alla fine oggi; date nel futuro o
prima del 2000 si scartano. Registrazioni di giorni diversi fanno una sessione per giorno, e il
wizard dice da dove ha preso la data e la lascia cambiare. Un tipo per
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
`ImportTarget.UpdateNote`: il testo si **sostituisce**, le registrazioni gia' presenti — stessa impronta, o stesso nome e stessa durata
(`SdocxUpdate.sameRecording`: Samsung Notes riscrive l'intestazione dei file a ogni condivisione, e
il 24/09 «Voce 002» e' rientrata come nuova, ritrascritta) — le nuove entrano una sessione per giorno di registrazione (in coda
a quella della nota che ha gia' quel giorno), e il `.sdocx` vecchio se ne va — riga, file, pagine a
mano e blob sul PC (`DELETE /v1/files/<sha>` del companion, solo se nessun'altra fonte lo cita).
**Uno solo**: quello col titolo uguale (`ImportCandidate.updateOfSourceId`, `SdocxUpdate.pick`); un
`.sdocx` arrivato con «Importa qui» resta, e del corpo si sostituisce solo il suo pezzo
(`SdocxUpdate.mergeBody`: il testo vecchio dov'e', o il paragrafo `## <titolo>`, o in fondo). Stesso contenuto (stessa impronta) e' invece un doppione, e
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
  dietro il server in `companion/`. Di serie il file va **intero** — piu' contesto per Whisper — e
  in Impostazioni si puo' mettere un tetto (30, 60, 120 minuti: `customMaxMinutes`).

**Ogni registrazione va da se'.** Le parti di una sessione si trascrivono una per una, anche col
computer di casa: si concatenano solo nell'ascolto. Il tetto vale per parte, con una tolleranza
(`ChunkPolicy.decide`): fino a dieci minuti oltre il tetto del computer, due oltre quello di Groq, la
parte va intera; oltre, si divide in `ceil(durata / tetto)` pezzi **uguali**, tagliati nel silenzio
piu' vicino (`ChunkPlanner.planEqual`) — quaranta minuti con un tetto di trenta vanno interi,
quarantuno diventano due da venti e mezzo, mai trenta piu' undici. Per Groq i pezzi aumentano anche
se, ricodificati, supererebbero il limite in byte.

**La VRAM non la decide la durata.** WhisperX lavora a finestre di trenta secondi: la memoria della
scheda dipende dal modello, dal tipo di calcolo e dal lotto; una lezione piu' lunga costa tempo e un
po' di RAM. Il companion misura la scheda (o usa la VRAM indicata a mano, `vram_mode`/`vram_gb`),
stima quanto userebbe (`estimate_vram_gb`: pesi + lotto + allineamento + contesto CUDA) e sceglie il
lotto — e se non basta, `int8` o un modello piu' piccolo — per restare sotto l'85%. Su Windows una
scheda piena non da' «out of memory»: il driver travasa nella RAM condivisa e tutto va sei volte piu'
lento, quindi la stima e' la protezione vera; il ripiego sul processore resta per gli errori veri.
Impostazioni → Trascrizione mostra scheda e stima (`/health`) e le cambia sul computer
(`GET/POST /v1/admin/settings`, `POST /v1/admin/estimate`, solo il proprietario).

Tre cose imparate il 23/09, dopo un riavvio del PC con ogni lezione finita sul processore e, prima,
quattro gigabyte nella memoria condivisa:
- **La scheda si legge dal driver** (`nvidia_query`, cioe' `nvidia-smi`), mai con torch all'avvio:
  un contesto CUDA aperto appena entrati in Windows restava convinto che la scheda fosse piena
  (0 GB liberi con 10,8 liberi davvero). Se succede lo stesso — il driver dice che la memoria c'e',
  il processo no — il companion si riavvia da se' a lavoro finito (`restart_when_idle`,
  `avvio.pyw --dopo`), e nel frattempo risponde `503 restarting` prima di leggere il corpo.
- **In automatico il budget e' la VRAM libera**, non il totale: totale meno quello che occupano gli
  altri (`others_gb`: occupata meno la nostra, misurata al caricamento), rimisurato prima di ogni
  lezione (`replan_for_job`). Le costanti vengono da misure vere su una lezione a pezzi (0,32 GB a
  elemento del lotto, 0,9 di contesto, 0,9 di allineamento), e dopo ogni allineamento la riserva di
  torch si restituisce, o resta sotto il pezzo dopo (da 10,6 a 9,0 GB di picco col lotto da 7).
- **Scaricare il modello restituisce la scheda.** Un errore d'import che pyannote tiene da parte
  col suo traceback teneva in vita i frame di `run_job`, e con loro modello e audio: restavano 2–4
  GB, che il budget contava come «altri». Le variabili grandi si azzerano prima di uscire, e
  WhisperX si importa all'avvio su un thread suo (`warm_imports`). Resta il contesto CUDA, 250 MB.

**Il PC riconosce l'account.** Verso il companion non viaggia mai il token del sync (in casa e' http
in chiaro, e apre tutte le note): l'app chiede al Worker un **biglietto per il PC**
(`POST /v1/computer/ticket`, `pt_…`, firmato con una chiave derivata da `COMPUTER_KEY`, dura dodici
ore) e lo manda come bearer (`ComputerAuth`); il companion lo fa verificare
(`POST /v1/computer/verify` con l'`owner` del suo `config.json`) e tiene la risposta fino alla
scadenza, cosi' un'interruzione di internet non ferma il PC. Il codice scritto a mano resta per chi
non ha l'account. Il companion controlla le credenziali **prima** di leggere il corpo. Senza
credenziali risponde 401, a meno di `accept_anonymous`: acceso da solo sui `config.json` che
esistevano gia' (e non avevano un token), perche' l'app vecchia non manda niente; si spegne dal menu
dell'icona quando tutti i dispositivi sono aggiornati. Il QR non porta piu' il token.

**Le parole allineate, per davvero.** Fino al 23/09 ogni lezione tornava coi tempi per frase: prima
di allineare WhisperX divide il testo con NLTK, e NLTK 3.10 rifiuta un file il cui percorso risolto
non sta sotto le sue cartelle — che e' quello che succede quando il companion parte da dentro l'app
di Claude, che sposta `%APPDATA%` in una copia virtuale. L'eccezione veniva inghiottita.
`trust_sentence_splitter` aggiunge a NLTK la cartella vera; `/health` dice per lingua come e' andato
l'ultimo allineamento (`"alignment": {"it": "ok"}`).

**Il silenzio non si trascrive.** «Napoli 18h» (19,8 ore, una gita registrata di notte) tornava con
le ore 0–3 e 13–18 tutte inventate: 407 eco del titolo mandato come vocabolario («18h 18h 18h»:
WhisperX rilegge il prompt a ogni finestra da trenta secondi), un centinaio di «Grazie.», 78 giri a
vuoto. Il companion ora si difende da se', su quattro fronti. Il **VAD** e' piu' severo
(`VAD_OPTIONS`, 0,6/0,45: nelle ore mute i tratti mandati a Whisper si dimezzano, le parole dell'ora
parlata restano le stesse); `repetition_penalty` e `no_repeat_ngram_size` sono stati provati e
**lasciati spenti** (`ASR_OPTIONS`): vietano al modello le ripetizioni vere — «vorrei fare festa»,
ripetuto da chi parlava, diventava «vuoi rifare festa… vuol fa festa». La **lingua**, se l'app non la
dice, si riconosce a maggioranza sulle tre finestre da trenta secondi piu' parlate del file
(`spoken_language`), non sui primi trenta secondi: su quel file erano rumore, e WhisperX sceglieva
inglese e *traduceva* la gita. Votano solo le finestre di cui il modello e' sicuro almeno 0,6
(`Engine.language_vote` rifa' spettrogramma, encoder e `detect_language` di ctranslate2 per avere la
probabilita', che WhisperX butta): su un file tutto rumore anche le finestre «piu' parlate» sono
rumore, e `nn, nn, haw` faceva trascrivere in norvegese e scaricare un allineatore da 3,6 GB
(rumore bianco: «nn» a 0,47–0,52). Nessun voto sicuro: la lingua la dice il primo pezzo, come prima.
**L'allineatore** si carica solo per le lingue di `ALIGN_LANGUAGES` (le cinque di torchaudio piu'
ja, zh, nl, uk, pt, ru, pl, ca: niente modelli da un miliardo di parametri), mai per un pezzo senza
segmenti, e quello vecchio se ne va solo quando il nuovo si e' caricato — prima usciva per primo, e
un caricamento fallito lasciava senza parole allineate anche l'italiano fino al riavvio. Una lingua
senza allineatore (o col suo che non si carica: `AlignmentUnavailable`) tiene i tempi di Whisper e in
`/health` e' `"non disponibile"`, che non spegne `word_timestamps` per le altre. Dopo l'allineamento **`drop_hallucinations`** toglie quello che non e'
stato detto: segmenti senza parole, titoli di coda dei sottotitoli (in piu' lingue: sul rumore anche la
lingua e' a caso; solo le formule intere su un segmento di dieci parole al massimo — «ha parlato ai
media», «Sottotitoli di un film…» restano), eco del vocabolario (tutte le parole nel prompt, e
ripetute, sotto 0,3 s, veloci o quiete: «Fichte.» detto in mezzo secondo resta), «Grazie»/«Buonanotte» corti e soli o quieti, segmenti corti o veloci sopra un audio quieto, e
tutto quello che sta 40 dB sotto la voce; i giri (rapporto di compressione vero oltre 2,4) si
accorciano a una volta sola, coi tempi della prima. «Quieto» vuol dire 2 dB sul fondo del blocco da
dieci minuti **o** 24 dB sotto la voce del file (`sound_levels`): quel telefono toglieva il rumore da
se', il fondo era a −95 dB e solo la distanza dalla voce separava le frasi vere (−17/−45) da quelle
inventate (−48/−95). Sul file intero: 2213 segmenti → 1827, zero nelle ore mute, 251 eco, 996 parole
di giri, 316 parole d'altro, tutte controllate a mano. I conteggi vanno nel log («allucinazioni: …»)
e nella risposta (`dropped`); `compression_ratio` e' quello vero e `no_speech_prob` e' `null` (prima
0,0, che spegneva il filtro dell'app). La **memoria**: l'audio si decodifica in float32 direttamente
in un array della misura giusta (`load_audio`: 11,75 → 4,65 GB per quel file), e oltre le quattro ore,
se va comunque in pezzi, non si tiene affatto (`StreamedAudio`: un giro per le energie, poi ogni pezzo
con `-ss`/`-t`, 0,5 GB). Il pezzo comincia al campione giusto: un salto nel contenitore fino a due
secondi prima e il resto decodificato e buttato (`_decode_command`; col solo salto un m4a cominciava
11–18 ms dopo). La durata dichiarata vale al massimo quattro ore di array preparato
(`PREALLOCATE_MAX_S`): un WAV mai chiuso dichiara 37 ore, 8,5 GB chiesti in un colpo; oltre, l'array
cresce di meta' alla volta fino alla durata dichiarata.

**A che punto e', mentre trascrive.** Una richiesta al computer e' una sola `POST` che torna quando
ha finito, e per un'ora il telefono non sapeva niente. Ora l'app manda `X-Pampa-Job: <uuid>` e, finito
l'invio, chiede `GET /v1/jobs/<uuid>` ogni secondo (`RemoteJobPoller`): `state` fra `received`,
`queued` (con `position`), `decoding`, `loading_model`, `transcribing`, `aligning`, `diarizing` (solo
con «chi parla»), `done`, `failed`,
e `fraction` dentro la fase, che viene dal `progress_callback` di WhisperX — vero, non stimato dal
tempo, ma a scatti di un lotto. Il companion registra il lavoro appena le intestazioni passano
l'autenticazione, **prima** di leggere il corpo, o una domanda durante l'invio avrebbe un 404; il
proprietario vede tutti i lavori, un ospite solo i suoi, e gli altri hanno lo stesso 404 di un id
che non esiste. Un companion vecchio risponde 404 due volte e l'app smette di chiedere senza far
fallire niente — a meno che `/health` dica un `instance`: allora e' un companion nuovo che il lavoro
non l'ha ancora registrato, e si pazienta un minuto. `RemoteJobLost` annulla la POST — che altrimenti
restava appesa fino al timeout di novanta minuti, con la coda ferma dietro — e diventa un errore di
rete, che si riprova; ma **solo se `/health` lo conferma** (quindici secondi di pazienza), perche'
abbandonare un lavoro buono costa una lezione intera: un lavoro gia' visto che torna 404 due volte
di fila e' perso se l'`instance` di `/health` (l'id del processo del companion, nuovo a ogni avvio)
non e' piu' quello del lavoro — con lo stesso `instance` si aspetta la POST, e un companion 1.0.0
senza `instance` si da' per perso come prima; novanta secondi senza nessuna risposta (misurati con
l'orologio monotono, non con l'ora del telefono) sono un PC spento solo se tace anche `/health`.
Quando chiedere del lavoro non serve piu' (finito, dimenticato dallo stesso processo) si passa alla
**sola sonda**: `/health` ogni dieci secondi finche' la POST non torna. Un `503 restarting` del
companion che si riavvia si aspetta per il suo `Retry-After` (5–120 s, dieci volte al massimo) senza
consumare i tentativi del runner. **Il PC non lavora due volte**: una POST abbandonata finisce in
`AbandonedCompanionJobs` (in memoria, per processo: un processo che muore chiude le connessioni, e il
companion le ferma da se'), e la POST successiva le manda la `DELETE` **dopo** essersi agganciata —
il companion unisce le richieste identiche per impronta, e una `DELETE` stacca solo la sua. La fase finisce nella riga del lavoro in un formato solo, `JobPhase`
(`remote:<stato>:<parte>/<parti>:<percento>…`), e da li' la stessa frase va nella notifica, in Lavori,
nella nota e nella sessione, con due barre: tutta la lezione (le parti pesate per durata) e il
passo di adesso, che si anima quando non c'e' niente da misurare.

**Le statistiche.** `transcription_runs` (schema 6) tiene una riga per trascrizione finita: durata
dell'audio, tempo sul telefono dal primo passo alla fine (la coda esclusa), parole, dispositivo
(`cuda`, `cpu`, `groq`) e i `processing_s`/`audio_s` che il companion rimanda. Dallo schema 7 **si
sincronizza** come le altre tabelle, senza padre, e ogni riga dice chi l'ha misurata (`deviceName`,
il nome del dispositivo nel sync): la home mostra la velocita' di tutto l'account, e il record fatto
altrove dice dove («record 72× (Tab S9)»). Le corse di prima restano senza nome finche' il primo giro
non le rivendica (`StatsDao.claimUnnamed`, solo quelle mai concordate con l'indice): e' quell'`UPDATE`
a metterle nell'outbox. Non ha chiavi esterne: una sessione cancellata non si porta via la storia. Un lavoro ripreso (`resumed`) conta le parole ma non
la velocita', perche' ha saltato i pezzi gia' fatti. Tutto il resto della home (ore, parole, ritmo,
lezione piu' lunga) si conta dalle trascrizioni grezze, quindi vale anche per quelle arrivate dal
sync. I conti stanno in `TranscriptionStats.aggregate`, puro.

**Il computer lavora, il telefono chiede.** La preparazione — scaricare dal PC una registrazione
per rimandargliela, decodificarla in PCM, tagliarla e ricodificare i pezzi — costava piu' della
trascrizione. Con un companion che in `/health` dichiara `features` (`by_ref`, `archive_upload`,
`server_chunks`, `file_meta`, `prompt`) il telefono non decodifica niente (`CompanionTranscription`):
una parte che il PC ha gia' (`archivedAt > 0`) si manda **per impronta** (`source_sha256`, nessun
file: il companion la prende da `Archive.get`); una parte che sta solo qui si carica intera, con
`archive=1` se l'archivio e' acceso — il PC la conserva, verifica l'impronta e risponde `archived`,
e la parte si marca archiviata — altrimenti resta temporanea e si cancella a fine lavoro. Il tetto
dei pezzi (`max_minutes`) lo applica il PC, con la stessa regola di `ChunkPolicy` e i tagli nel
silenzio calcolati sull'array gia' in memoria; `GET /v1/jobs` dice `chunk`/`chunks`. Un blob sparito
(`blob_missing`) ricade sul caricamento, un ospite (`owner_only`: un'impronta non deve dire cosa c'e'
nell'archivio di un altro) e un companion vecchio sulla strada di prima. Il «Vocabolario» (`prompt`)
arriva a WhisperX come `initial_prompt` per quella sola richiesta: prima il companion lo ignorava.

**La pagina Trascrizione** va in cinque gruppi, nell'ordine in cui ci si pensa: «Chi trascrive»
(servizio, solo il computer, trascrivi appena importi), «Cosa si sente» (lingua, vocabolario), «Chi
parla», poi «Computer di casa, avanzate» — solo con un computer, chiusa dietro una riga che si apre:
pezzi, memoria video, modello — e «Groq, avanzate» solo se Groq si puo' usare (una chiave, e non
«solo il computer»), con ogni selettore subito sotto la sua riga.

**I pezzi, in automatico.** Impostazioni → Trascrizione → «Computer di casa, avanzate» ha «Automatico» (di serie, tranne per chi
aveva gia' scelto un tetto) e uno slider da 10 a 120 minuti e «intera» (`LiquidSlider`, il vetro di
Kyant portato nell'app: tocco e trascinamento su tutta la riga da 48 dp, la maniglia che si fa lente
da qualunque punto la si prenda, il titolo come `stateDescription` per TalkBack). Acceso, l'app manda
`max_minutes=auto`: il companion sceglie dopo aver decodificato, dalla velocita' misurata sulle
ultime lezioni senza il caricamento del modello (`auto_piece_minutes`: circa quattro minuti di lavoro
per pezzo, fra 15 e 120 minuti di audio — sulla scheda le lezioni vanno intere, sul processore a
pezzi), e risponde `max_minutes_used`, che lo slider fermo mostra come «Ultima lezione: …».

**Chi parla.** Il computer di casa sa separare le voci (pyannote, che arriva con WhisperX:
`pyannote/speaker-diarization-community-1`), e ogni segmento torna con `speaker` («SPEAKER_00»).
**Quello che l'utente deve fare, una volta**: un account gratuito su huggingface.co, accettare le
condizioni sulla pagina del modello, creare un token di tipo *Read* e incollarlo dal menu dell'icona,
«Separazione delle voci…» (`tray.voices_dialog`: salva `hf_token` in `config.json` col writer atomico
di `config.py`, e chiede subito a Hugging Face se il token apre il modello — `check_diarization_access`
dice se mancano le condizioni o il token e' sbagliato). Vale anche `HF_TOKEN`. Il token non si stampa
mai (registro, `/health`, `/v1/admin/settings`). **Senza token non cambia niente**: `/health` non
dichiara `diarize` fra le `features`, `diarize=1` si ignora, e la trascrizione e' quella di sempre.
Con il token, `diarize=1` (piu' `min_speakers`/`max_speakers`, facoltativi) entra nella chiave del
lavoro condiviso, e `diarize_segments` gira **dopo l'allineamento di tutti i pezzi e sull'audio
intero** — le etichette valgono solo dentro la stessa separazione, quindi separare pezzo per pezzo
darebbe SPEAKER_00 diversi con lo stesso nome — con la fase `diarizing` e la sua percentuale. Il
modello si carica per la lezione e se ne va alla fine con la sua riserva (`DIARIZE_GB`, 1,5: non
entra nel piano della VRAM perche' arriva quando il lotto di Whisper e' gia' restituito, ma si guarda
il driver prima di cominciare, e senza posto — o con la memoria finita a meta' — si fa sul
processore). Oltre `DIARIZE_WINDOW_S` (due ore: il raggruppamento di pyannote cresce col quadrato)
si separa a finestre tagliate nei silenzi, e le voci di ogni finestra sono sue (`2:SPEAKER_00`).
**Le voci non fanno mai fallire una lezione**: qualunque errore (token, condizioni, pyannote) lascia
i segmenti senza `speaker`, e l'esito sta in `/health` (`diarization.last`) come quello
dell'allineamento. `assign_speakers` e' la regola di `whisperx.assign_word_speakers` (vince chi parla
piu' a lungo nell'intervallo) senza pandas: un segmento che non tocca nessun turno prende il turno
piu' vicino, una parola resta senza.

Nell'app: **Impostazioni → Trascrizione → «Chi parla»** (`SpeakerSeparation`: «Solo in
Registrazioni», di serie — una lezione ha una voce sola —, Sempre, Mai). Senza un computer collegato
la scelta e' spenta e una riga porta a Servizi; con il computer la nota sotto dice lo stato vero, uno
per volta: sto chiedendo, non risponde, il programma sul computer e' vecchio (`/health` senza
`diarization`: `CompanionStatus.Ready.speakersKnown`), pronto, lo decide il proprietario (chi non
legge le impostazioni del computer, cioe' un ospite), manca il token.
`TranscriptionRepository.requestFor` lo mette nella richiesta, e `TranscriptionRunner` lo manda solo
sulla strada del computer che lavora da se' e solo se `/health` dichiara `diarize`: mai a Groq, mai
a un companion vecchio, mai a pezzi tagliati sul telefono. `SegmentEntity.speaker` (database 10,
nullable) viaggia coi segmenti dentro la trascrizione, e **vuoto non entra nell'impronta**
(`SyncCodec.canonicalSegments`, provato byte per byte contro la forma di prima in
`SegmentPayloadTest`): l'aggiornamento non sporca nessuna trascrizione. Un'app di prima che riceve
una trascrizione con le voci le perde e, se la riscrive, la rimanda senza: si aggiornano tutti.
L'etichetta e' una chiave, non un nome: `TranscriptParagraphs.voices` la traduce in «Voce 1», «Voce 2»
nell'ordine in cui compaiono, **parte per parte** (ogni registrazione si separa per conto suo: la
stessa persona in due parti e' due voci), e **solo se almeno una parte ha due voci**: un monologo in
due parti ha SPEAKER_00 e SPEAKER_00, due chiavi, e diventava «Voce 1» e «Voce 2» con l'export che
metteva una voce davanti a ogni paragrafo. La pagina condivisa (`worker/src/page.ts`) ha la stessa
regola. `split` va a capo
anche dove cambia la voce. A schermo la voce sta nella riga del tempo della card, solo dove cambia
(«0:42 · Voce 2»); nell'export coi tempi ogni paragrafo comincia con `**Voce 1:**` (non conta nelle
parole); la pagina di una condivisione la scrive piccola sotto il tempo. Rinominare le voci («Voce 1»
→ «Marco») non c'e': vorrebbe una mappa per sessione da sincronizzare.

**Annullare, perdersi, ripetersi.** Ogni trascrizione sul companion e' un lavoro condiviso
(`SharedWork`) con chi lo aspetta: due richieste uguali (stessa impronta, lingua, vocabolario,
tetto) — il tablet che non sapeva, il telefono che rimanda — ne fanno una sola, e ricevono lo
stesso risultato. «Annulla» manda `DELETE /v1/jobs/{id}` (anche la connessione chiusa vale):
quella richiesta si stacca, e il computer si ferma al lotto dopo solo quando non la aspetta piu'
nessuno. `/health` e i lavori portano `instance`, diverso a ogni avvio del companion: l'app rimanda
una lezione solo se il lavoro e' sparito **e** l'`instance` e' cambiato (riavvio), o se dopo 90
secondi di silenzio non risponde neanche `/health`; prima di rimandarla annulla quella vecchia. Il
registro dei lavori non dimentica mai uno in corso, neanche oltre il limite o dopo sei ore.

**Il companion non gira mai dentro un'altra app.** Avviato da un terminale dentro un'app che
virtualizza `%LOCALAPPDATA%` (l'app di Claude sul PC), le sue scritture finivano nella copia privata
di quell'app (`%LOCALAPPDATA%\Packages\<app>\LocalCache\Local\PampaNotes`): il 24/09 c'erano 80
registrazioni li' dentro, e il companion ripartito normale rispondeva `blob_missing` — il telefono
ricaricava da fuori casa file che il computer aveva. Non c'e' un'API che lo dica (il processo non ha
identita' di pacchetto): `fuori.redirected_to` scrive una sonda e guarda dove finisce, e `tray.py` e
`avvio.pyw` in quel caso si rilanciano con WMI (`Win32_Process.Create`, fuori da ogni contenitore).
Il rilancio porta `--fuori`, e chi lo riceve (e il `tray.py` che `avvio.pyw` lancia dopo) non guarda
piu': il Python dello Store (`PythonSoftwareFoundation.*`) e' un contenitore da cui WMI non fa
uscire, e senza il segno si rilanciava per sempre; li' si scrive nel registro e si va avanti. Un
`%LOCALAPPDATA%` vuoto non e' la cartella corrente. `Archive.get` non cancella piu' una riga il cui
file non si vede: risponde «non c'e'» e lo scrive. La `DELETE` invece legge la riga da se' e la
toglie anche senza file (prima rispondeva 404 e la riga restava per sempre), e le statistiche
contano solo i file che ci sono (`inventory`: `missing` a parte in `GET /v1/files`).

**Il companion non taglia chi e' a meta'.** `AuthGate` conta le richieste in volo dall'ingresso
all'ultimo byte della risposta (`inflight` in `/health`): il riavvio da se' aspetta che non ci sia
niente in coda, al lavoro o in volo — caricamenti e `PUT /v1/files` compresi — e chiude uvicorn con
garbo; il tray e l'installer usano lo stesso «fermo». L'installer ferma il companion prima di
toccare la venv. Modello, calcolo e lotto si fissano all'inizio del lavoro per tutti i pezzi, il
precaricamento passa dalla stessa fila, l'allineatore resta uno (quello della lingua di adesso) e
la sua memoria non finisce fra «gli altri». `config.json` si scrive tutto o niente, sotto un
lucchetto. Un file dell'archivio aperto (un download, ffmpeg) non si cancella: la riga resta e la
`DELETE` risponde 503 `file_in_use`. Lo stesso blob caricato due volte insieme (il `PUT` dell'archivio
e una trascrizione con `archive=1`) non da' piu' un 500 per `WinError 5`: chi arriva secondo trova il
blob con la stessa impronta e butta il suo `.part`, e un rename rifiutato un istante si riprova
(`archive._place`).

**Quello che Whisper inventa nei silenzi.** Una registrazione di venti ore («Napoli 18h») e' tornata
con 407 segmenti che dicevano solo «18h», un centinaio di «Grazie.» e «Buonanotte» sparsi nelle
pause e 78 segmenti in cui la stessa parola girava a vuoto. Il primo lo causava l'app: il prompt
portava il titolo della nota e della sessione, e Whisper legge il prompt come il testo appena
detto — davanti al silenzio lo ripete. Adesso il prompt e' **solo il «Vocabolario»**, e niente se e'
vuoto (`TranscriptionPrompt`). Il resto lo toglie `HallucinationFilter` dentro
`TranscriptStitcher.stitch`, sulle due strade (Groq a pezzi e il computer di casa, che risponde con
un pezzo solo), prima della cucitura dei confini: un giro a vuoto (la stessa unita' almeno tre volte
di fila e almeno sei parole in tutto, anche fatto di segmenti uguali) resta una volta; un segmento
fatto solo di parole del prompt e non piu' lungo del prompt e' un'eco, e si toglie se e' isolato (tre
secondi di niente prima e dopo, come i saluti) o se si ripete — due uguali di fila, o «18h 18h»:
«Fichte.» come risposta a una domanda e' fatto delle stesse parole ed e' vero (`dropEchoes`); «Grazie.», «Grazie mille»,
«Buonanotte», «Sottotitoli» si tolgono solo se il gruppo che formano ha almeno tre secondi di pausa
prima e dopo (i bordi della registrazione valgono come pausa), i titoli di coda («…Amara.org»)
sempre. Conservativo per costruzione: nessuna regola tocca un discorso lungo. Le parole allineate
seguono il testo (tagliate allo stesso punto se erano una per token, altrimenti stimate), anche al
confine fra pezzi, dove prima restavano tutte. Un `no_speech_prob` nullo e' «non lo so»: WhisperX
non lo calcola, e `isHallucination` decide solo coi due numeri veri. Se le difese tolgono tutti i
segmenti di una parte, il `text` del server non si rimette al loro posto: sarebbe l'allucinazione
stessa. Le trascrizioni gia' salvate restano come sono; «Ritrascrivi» le ripulisce.

Il raffinamento passa da `ChatProvider.complete` di `engine-ai` su Groq. Non è un assistente: è un
passaggio che toglie intercalari e rimette la punteggiatura senza cambiare il contenuto.

### Solo il computer di casa

`customOnly`: mai con Groq, nemmeno in automatico. `PampaSettings.transcriptionProvider` e' quello
che ogni `enqueue` usa (import, nota, sessione, selezione), e con l'interruttore acceso e' sempre
`CUSTOM`; il selettore del servizio sparisce dalle impostazioni. E' la garanzia che serve per tenere
accesa «trascrivi appena importi» senza che una lezione finisca nel cloud per sbaglio. Vale anche
per quello che c'era gia': accenderla sposta al computer le trascrizioni per Groq in fila o fallite
(`moveGroqTranscriptionsToComputer`), la coda di Groq fa lo stesso prima di prendere un lavoro, e
«Riprova» rimanda al computer (`effectiveProvider`).

Quello che la rende utilizzabile e' che **la coda del computer di casa aspetta invece di fallire**.
`TranscriptionQueueWorker`, prima di ogni lavoro di quella coda, chiede
`TranscriptionRepository.endpointState()`: `/health` sull'indirizzo scelto, due secondi. Se il
computer e' configurato ma non risponde, il lavoro resta `QUEUED` con la fase `endpoint` («In
attesa del computer di casa»), il worker mette un timer a passo fisso (`EndpointWait`: un minuto per
la prima mezz'ora, poi cinque) e si chiude con `success` — non `retry`, la cui attesa raddoppiava e
dopo un riavvio del PC teneva la fila ferma minuti con il companion che rispondeva gia'. Il timer e'
`EndpointRetryWorker` (`WorkScheduler.retryForEndpoint`, nome unico per provider e `REPLACE`: uno
solo, che non tocca mai la coda) e scadendo chiama `wake`, solo se la fila ha ancora lavori; da
quando si aspetta sta in DataStore (`endpointWaitingSince`), azzerato quando il PC risponde. Il
worker accende anche la sonda `EndpointWatchWorker`,
ogni quarto d'ora finche' la fila non e' vuota, come rete di sicurezza. Un errore di
rete a meta' lavoro col computer muto rimette in fila invece di fallire (`requeueForEndpoint`); col
computer che risponde da un altro indirizzo (casa → Tailscale uscendo) il lavoro riparte subito da
li' (`endpointMovedFrom`). Tutte e due al massimo due volte: alla terza (`MAX_ENDPOINT_LOSSES`,
contate in `optionsJson`, azzerate da «Riprova») il lavoro fallisce con `computer_lost`, perche'
una registrazione che fa cadere il companion tornava in testa alla fila per sempre. Chi
vede il computer rispondere lo sveglia prima: l'archivio dopo un giro andato bene, «Prova» in
Impostazioni e nel primo avvio, l'apertura dell'app (`WorkScheduler.wake`, che sostituisce un
tentativo in attesa ma mai un worker che lavora).
Attenzione al resolver: con due indirizzi `EndpointResolver.resolve` **restituisce sempre una
strada**, anche se nessuna delle due risponde — decide *quale*, non *se*. Per sapere se il computer
c'e' bisogna battere `/health`, ed e' il bug che il primo giro di prova ha trovato («Il servizio non
ha risposto in tempo» invece dell'attesa).

**Ritrascrivi** (menu della sessione, con conferma) e' un `enqueue` come gli altri: `saveTranscript`
sostituisce la grezza e porta via le raffinate. **Selezione multipla** («Seleziona» nel menu della
barra): nella nota, le sessioni (ritrascrivi, elimina); nella cartella, le note (trascrivi quelle
da fare, sposta con `FolderPickerSheet`, esporta con `ExportScope.Notes`, elimina). La barra in alto
diventa quella della selezione — titolo «N selezionate», indietro la chiude — invece di una barra
in basso che non esiste nell'engine. In Lavori, «Riprova tutti i falliti», che conta e rimanda solo
quelli che vale la pena (`FailedJobs.standing`, puro): non quelli superati da una trascrizione (o
una raffinata) piu' recente o da un lavoro dello stesso tipo partito dopo — la riga dice
«Superato», in grigio —, non le registrazioni mute (`no_speech`), non i lavori di una sessione che
non c'e' piu'. Una registrazione muta si presenta allo stesso modo dappertutto — sessione, nota,
Lavori, Registrazioni —: niente rosso, «Nessuna parola in questa registrazione», il segno «Senza
parole», «Trascrivi lo stesso»; in Lavori il «Riprova» della riga c'e' solo per i falliti
`RETRYABLE`, e la nota ha «Nascondi» come la sessione e salta i fallimenti superati. La stessa regola decide se la sessione mostra ancora il fallimento; la scheda del
fallimento ha «Nascondi» (cancella la riga del lavoro), e una ripulitura non riuscita con la grezza
li' sotto e' una scheda quieta, non l'allarme in cima alla pagina. Una trascrizione finita
`no_speech` lascia nella cartella del lavoro i suoi pezzi vuoti (`computer.json`), e «Riprova» usa lo
stesso id: li rileggeva e falliva all'istante senza chiamare nessuno. Adesso il worker butta la
cartella quando fallisce `no_speech`, e `retry` la butta per quelle di prima
(`FailedJobs.discardsWorkOnRetry`).

## Il companion per tutti

Fino alla 0.3.0 il companion si installava a mano (`setup.ps1`, una venv, torch rimesso dopo
WhisperX). Adesso c'e' un installer per Windows in `companion/installer/`:

- `PampaCompanion.iss` (Inno Setup 6) installa per utente in `%LOCALAPPDATA%\Programs\PampaCompanion`,
  senza amministratore; `uv` porta Python 3.11 **con tkinter** (il Python «embeddable» non ce l'ha,
  e la finestra di installazione e' Tk) e la venv dentro quella cartella, poi lancia `install.py`.
- `install.py` fa i passi con una barra e un log, e «Riprova» se la rete cade: controlli (Windows,
  10 GB), la scheda (`nvidia-smi`; il driver sceglie torch cu128, cu126 o il processore), WhisperX e
  poi torch nell'ordine di `setup.ps1`, **ffmpeg** in `bin/` (il companion non l'aveva mai
  installato: su un PC nuovo mancava), il modello scelto con lo stesso `plan_vram` del server e
  scaricato subito, `config.json` (mai sovrascritto), firewall (l'unico passo con UAC), avvio
  automatico, Tailscale, e alla fine la pagina col QR. `--upgrade` tiene venv e modello e riavvia
  il tray solo se `/health` dice fermo.
- `build-installer.ps1` fa l'`.exe` con la versione di `companion/VERSION`; senza Inno Setup o `uv`
  dice il comando winget e si ferma.
- **Il collegamento all'account senza scrivere niente**: finche' il PC non ha un `owner`, il link del
  QR porta `&bind=<codice>` (dieci minuti, una volta sola). L'app, a «Collega», manda
  `POST /v1/pair/bind` con il suo biglietto per il PC, il codice, l'account e l'indice
  (`CompanionBinder`); il companion accetta solo dalla rete di casa, da Tailscale o dal PC stesso,
  chiede al Worker se il biglietto e' di quell'account (`/v1/computer/verify`) e solo allora scrive
  `owner` e `index_url`. Un PC gia' di un altro account risponde 409.
- **Si aggiorna da se'**: il tray guarda una volta al giorno l'ultima release `companion-v*` di
  GitHub e offre «Aggiorna a vX», che scarica il setup e lo lancia in modalita' aggiornamento, solo a
  companion fermo e solo per le copie installate dal setup (quella di sviluppo mai).
- Nell'app: Impostazioni → Servizi → «Installa sul tuo computer» (e una riga nel primo avvio), con
  il link alle release, «Condividi il link» e «Cerca il computer».

## Firma e pubblicazione

`local.properties` (git-ignorato) con `pampa.storeFile`, `pampa.storePassword`, `pampa.keyAlias`,
`pampa.keyPassword`, oppure le variabili `PAMPA_RELEASE_*`. Senza chiave la release si firma con
quella di debug e lo dice: quella build non è pubblicabile. Lo store legge `manifest.json` alla
radice; la pubblicazione passa dalla skill `pampa-store-publish-direct`.
