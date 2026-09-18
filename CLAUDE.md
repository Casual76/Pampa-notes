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
| `engine/` | [Fluid Engine](https://github.com/Casual76/fluid-engine) 1.33.0, submodule. **Non si modifica da qui**: una modifica non committata a monte sparisce al primo aggiornamento. |

Il design system è quello dell'engine: `FluidScreen`, `FluidListGroup`/`FluidListRow`,
`ContinuousCornerShape` (mai `RoundedCornerShape`), nessun colore o dimensione scritti a mano,
transizioni di rotta laterali e opache. Le regole per esteso stanno nella skill `fluid-engine`.

### I pannelli

Tre regimi, decisi da `fluidPaneLayout` dell'engine sulla larghezza della finestra: sotto i 600 dp
un pannello e la pillola in basso (il telefono di sempre); fino a 1000 dp il `FluidTabRail` di
fianco a un pannello (tablet in ritratto); oltre, barra laterale con le materie + lista + dettaglio
(tablet in orizzontale). Non si usa `material3-adaptive`: consegna lambda di contenuto, non
`NavBackStackEntry`, e i ViewModel leggono l'id dalla rotta.

Due `NavHost`, issati nella shell (`MainApp.kt`) e descritti in `ui/nav/PampaGraph.kt`: `listNav`
ha **tutte** le destinazioni, `detailNav` solo quelle di dettaglio piu' `DETAIL_EMPTY`. Dove va una
rotta lo decide `PampaNavActions` al momento del tocco, in base al regime: su una pagina larga la
nota va nel dettaglio, su una stretta in cima alla lista. `syncPanes` sposta la nota aperta da un
padrone di casa all'altro quando la finestra cambia regime, ricostruendo la rotta dagli argomenti:
la rotazione non perde il posto. Il back non ha handler scritti a mano: con `DETAIL_EMPTY` sotto,
lo stack del dettaglio ha due voci quando una nota e' aperta e il suo `NavHost` vince.

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

`JobEntity` è la coda: una riga per lavoro, il worker la porta avanti, la UI la guarda. Una coda per
provider (`groq`, `custom`), concorrenza 1 dentro ciascuna.

La ricerca è FTS4 su note e trascrizioni, tenuta in passo da **trigger SQL** (in
`PampaDatabase.SEARCH_TRIGGERS`), non da Room: un contenuto esterno si aggancia al rowid, e il rowid
di una tabella con chiave testuale non promette di restare lo stesso.

### I file

Gli URI che arrivano da una condivisione valgono una volta sola, quindi **si copia sempre**:
`filesDir/audio/<partId>.<ext>`, `filesDir/sources/<sourceId>.<ext>`, `filesDir/jobs/<jobId>/` per i
pezzi intermedi di una trascrizione, `cacheDir/exports/` per i bundle. `StorageRepository.sweepOrphans`
toglie quelli che nessuna riga cita più.

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
| M8 backup, onboarding, pubblicazione | da fare |

Il piano per esteso: `C:\Users\casua\.claude\plans\praticamente-vorrei-un-applicazione-che-crispy-falcon.md`

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

Il motivo per cui l'app esiste. Un pacchetto ZIP con dentro, in ordine di importanza:

- `INDEX.md` — l'elenco delle note. Un assistente non apre venti file per rispondere a una domanda:
  ne apre uno e decide. Senza indice o li apre tutti e finisce il contesto, o ne apre uno a caso.
- `notes/<cartella>/<nota>.md` — una nota per file, front-matter YAML piu' corpo. **Appunti** e
  **Trascrizione** stanno sotto due titoli diversi, ed e' la distinzione da cui dipende tutto: un
  modello che non sa quale delle due sta leggendo tratta un errore di Whisper come una cosa che
  l'autore ha scritto.
- `SKILL.md` + `instructions.md` — le regole, nel formato di Claude e in quello di ChatGPT o Gemini.
  La `description` della skill si costruisce dai titoli veri delle note: e' quello che Claude legge
  per decidere se aprirla, e una frase generica non viene scelta mai.
- `README-FOR-AI.md` — bilingue, nella radice, per chi apre lo ZIP senza aver configurato niente.
- `manifest.json` — gli stessi dati per un programma, con un numero di schema.

I writer (`MarkdownWriter`, `IndexWriter`, `SkillWriter`, `BundleWriter`) sono puri e si provano in
JVM; `ExportService` e' l'unico pezzo che tocca il database e il SAF. Lo ZIP si scrive in streaming:
un bundle con le registrazioni di un semestre sono gigabyte, e un telefono che prova a costruirlo in
memoria viene ucciso dal sistema a meta'. Gli audio entrano `STORED` perche' un m4a e' gia'
compresso. Una scrittura fallita cancella il file a meta': un archivio rotto e' peggio di nessun
archivio.

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

## Trascrizione

Due strade, stessa interfaccia (`TranscriptionProvider`):

- **Groq** con la chiave dell'utente (`AiKeyStore` dell'engine la cifra col Keystore). Limite 25 MB
  per richiesta sul piano gratuito, quindi un'ora di audio va decodificata a PCM 16 kHz mono,
  tagliata nei silenzi e ricucita.
- **Endpoint compatibile OpenAI** (`{base}/v1/audio/transcriptions`): il PC di casa con WhisperX
  dietro il server in `companion/`. Accetta file interi, niente chunking, e restituisce i segmenti.

Il raffinamento passa da `ChatProvider.complete` di `engine-ai` su Groq. Non è un assistente: è un
passaggio che toglie intercalari e rimette la punteggiatura senza cambiare il contenuto.

## Firma e pubblicazione

`local.properties` (git-ignorato) con `pampa.storeFile`, `pampa.storePassword`, `pampa.keyAlias`,
`pampa.keyPassword`, oppure le variabili `PAMPA_RELEASE_*`. Senza chiave la release si firma con
quella di debug e lo dice: quella build non è pubblicabile. Lo store legge `manifest.json` alla
radice; la pubblicazione passa dalla skill `pampa-store-publish-direct`.
