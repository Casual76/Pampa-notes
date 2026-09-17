# Pampa Notes

Un'app Android che fa da **ponte** fra gli appunti che prendi altrove e gli assistenti IA. Importa
note (testo, PDF, DOCX, `.sdocx` di Samsung Notes) e registrazioni, le tiene in cartelle, trascrive
l'audio con Whisper, ed esporta bundle Markdown pensati per essere dati in pasto a Claude, ChatGPT o
Gemini come fonti.

**Quello che l'app non fa**: non riassume, non risponde a domande, non ha un assistente. L'unico uso
di un LLM è il raffinamento opzionale di una trascrizione grezza, e la grezza resta sempre.

## Comandi

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug        # build
.\gradlew.bat --no-daemon :core:testDebugUnitTest   # test JVM (la logica pura sta tutta qui)
.\gradlew.bat --no-daemon :app:installDebug         # sul telefono
.\gradlew.bat --no-daemon :core:connectedDebugAndroidTest   # Room, migrazioni, FTS (serve un dispositivo)
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
| `engine/` | [Fluid Engine](https://github.com/Casual76/fluid-engine) 1.31.0, submodule. **Non si modifica da qui**: una modifica non committata a monte sparisce al primo aggiornamento. |

Il design system è quello dell'engine: `FluidScreen`, `FluidListGroup`/`FluidListRow`,
`ContinuousCornerShape` (mai `RoundedCornerShape`), nessun colore o dimensione scritti a mano,
transizioni di rotta laterali e opache. Le regole per esteso stanno nella skill `fluid-engine`.

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
| M0 scheletro, engine, tema, Room, Home, cartelle | fatto |
| M1 note, editor, import testo/PDF, ricerca | da fare |
| M2 audio, Groq Whisper, chunking, coda | da fare |
| M3 endpoint personale + server companion WhisperX | da fare |
| M4 sessioni, parti, lettore con segmenti | da fare |
| M5 export bundle e skill | da fare |
| M6 raffinamento | da fare |
| M7 DOCX, sdocx, share target | da fare |
| M8 backup, onboarding, pubblicazione | da fare |

Il piano per esteso: `C:\Users\casua\.claude\plans\praticamente-vorrei-un-applicazione-che-crispy-falcon.md`

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
