# Pacchetto Pampa Notes .pn e affidabilità importazione

## Configurazione e contesto

- Richiesta 2026-11-08: formato dedicato .pn, compressione senza perdita, tutti i dati disponibili e tempi audio, importazione semplice/affidabile; valutare il riuso della sync manuale cloud senza PC.
- Caso segnalato: mittente e destinatario probabilmente su 1.2.0; nessun file, messaggio d'errore o punto di blocco più preciso disponibile. Non attribuire una causa al caso reale senza evidenza.
- Confermato: GPT-6.1 Sol/xhigh per tutti, massimo 3 implementatori contemporanei, 3 reviewer indipendenti per giro; massimo ambiente 4 agenti incluso coordinatore. Criteri delegati riusati/adattati all'incarico. Nuova fase: massimo 3 giri di correzione dopo review iniziale, poi decisione utente secondo skill.
- Base verificata: main ea4e0a2, app 1.3.1/code20, engine3.0.5; include OpenRouter e relay cloud aggiunti dopo 1.2.0. Preservare la cancellazione locale preesistente companion/tools/__pycache__/holes.cpython-311.pyc.
- Skill: agentic-workflow aggiornata (tabelle individuali e aggregate obbligatorie), fluid-engine, material3-expressive; QA dispositivi tramite device_list/device_open/agent-device secondo regole runtime, non vecchi script adb di input.

## Criteri osservabili e rubrica

| ID | Criterio |
|---|---|
| PN1 | Roundtrip fedele di testo, cartelle, tag, fonti/immagini, audio, tutte le versioni RAW/raffinate, selezione, tempi segmento/parola, speaker e date presenti. Metadati privati/account/credenziali esclusi. Nessun tempo inventato per trascrizioni non temporizzate. |
| PN2 | Compressione senza perdita e deduplicazione dei byte senza fondere identità; misure su fixture testo/audio/originali, niente audio intero in memoria; limiti e progressi coerenti. |
| PN3 | Import .pn e vecchi ZIP Pampa, incluse fixture 1.2.0, da picker/condivisione/apri-con; cancellazione/errori file/MIME/versioni/corruzione comprensibili, nessuna attesa pesante nascosta o perdita di dati. |
| PN4 | Aggiornamenti/cop ie/multi-hop e modifiche locali conservati, identità/cronologie/timing/gerarchie corretti; niente regressione del sync dei file dopo alias. |
| PN5 | Export .pn recupera gli allegati disponibili dal relay cloud senza PC; contenuti importati interoperano con sync manuale esistente. Conservare trasferimenti incrementali per singolo allegato, consenso, account, scadenza e integrità. |
| PN6 | UX IT/EN semplice: destinazione esplicita Pampa Notes, contenuto incluso spiegato, avvisi reali per allegati irrecuperabili, aggiornamento/copia e destinazione validi. |
| PN7 | Validazione integrità/percorsi/riferimenti/limiti, staging e transazione con rollback e gestione spazio/cancellazione; archivi estranei o corrotti non modificano note esistenti. |
| PN8 | Build/lint/test mirati + Room/migrazione se necessaria + flusso reale tra export/import e cloud; 3 review indipendenti sulla stessa versione, con evidenze e limiti espliciti. |

Rubrica condivisa 0–10: 0–2 non soddisfatto; 3–4 parziale con difetti gravi; 5–6 implementato ma criterio ancora violato; 7–8 soddisfatto nei controlli con limiti espliciti; 9–10 soddisfatto con copertura solida dei casi pertinenti. NV non verificato, NA fuori ambito/non applicabile, voti provvisori esclusi dagli aggregati. Nessuna soglia numerica nuova: un requisito obbligatorio fallito resta aperto indipendentemente dalla media.

## Contratto di integrazione iniziale

- Nuovo formato dedicato .pn, MIME application/vnd.pampa.notes, contenitore compresso/versionato con contenuti nativi e blob. Lo ZIP per assistenti resta compatibile. Estensione indipendente dal riconoscimento del contenuto. Nessuna ricodifica audio con perdita.
- API nuova: core.export.PampaNoteFormat (MIME_TYPE, EXTENSION) e PampaNoteWriter(audioDir: File, sourcesDir: File, checkpoint: () -> Unit = {}).write(set: ExportSet, out: OutputStream, onProgress: (Float) -> Unit = {}). Include l'intero grafo nativo raccolto, non soltanto la versione visibile.
- ExportFormat.PAMPA e ExportTarget.PAMPA gestiti dal servizio/export UI. Allegati completi di default, mancanze esplicite e recupero via fetch/relay; controlli transcript/AI non pertinenti non devono suggerire un'operazione diversa.
- PampaBundleArchive.isBundle/read riconoscono PN e ZIP precedenti, pubbliche API importer/preview/result conservate. Aggiungere read(file, verifyAttachments: Boolean, checkpoint: () -> Unit = {}) lasciando compatibile la firma precedente; metadata/preview non devono decomprimere e verificare ripetutamente tutti gli audio. Staging verifica ogni blob prima del commit.
- Contenuti duplicati possono condividere un blob, mai un ID entità. Nessun account, endpoint, chiave o stato di lavoro nell'export. Compatibilità con RAW non temporizzate OpenRouter.

## Unità di lavoro

1. Formato/codec/lettore e test di roundtrip/compressione (nuovo implementatore).
2. Export servizio e UI dedicata, recupero allegati e test (nuovo implementatore).
3. Diagnosi import ZIP, pipeline robusta e UI/intents .pn/ZIP e test (nuovo implementatore).
4. Coordinatore integra/compila/verifica; verifica cloud trasversale come unità dipendente se necessario, con nuovo contesto e senza superare tre implementatori simultanei.

Nessuna build Gradle o operazione su dispositivi in parallelo agli agenti: risorse assegnate al coordinatore. Tre review nuove sul risultato stabilizzato con voto per ciascun criterio assegnato e riepilogo generale. Stato iniziale, nessuna approvazione attribuita.

## Coordinamento dei due flussi richiesti dall'utente

- L'utente ha esplicitamente richiesto due lavori contemporanei e separati: PN/ZIP conserva il proprio pool massimo3 implementatori e3 reviewer Sol/xhigh; Mistral/allineamento/orari/Registrazioni ha un secondo pool massimo3 implementatori e3 reviewer Sol/xhigh. Non applicare la proposta intermedia di quattro reviewer complessivi, superata da questa istruzione.
- Aperto un secondo thread di primo livello con worktree proprio da origin/main e modello/effort effettivi confermati Sol/xhigh: thread mcp:launch:1da3c0e4a84975480edac1d82c9425a7555cb4b73530905dedd0474543710be8, titolo Pampa Notes: Mistral, allineamento e Registrazioni. I limiti effettivi del runtime restano vincolanti; nessuna eccezione tecnica creata nei prompt.
- Il secondo coordinatore gestisce provider Mistral con429/attesa/pinning senza fallback, scelta automatica cloud, allineamento del testo cloud tramite PC se fattibile, data/ora reali per parte quando note e UX Registrazioni senza passaggio da note scolastiche. Mantiene il proprio CLOUD_RECORDINGS_WORKFLOW_STATUS.md e tre review specializzate.
- La pipeline PN non deve essere riscritta dal secondo flusso. API PN concordate nel contratto sopra. Metadati temporali/provenienza eventualmente nuovi devono sopravvivere al roundtrip PN; conflitti sui file export/import si risolvono in integrazione, senza sovrascrivere il lavoro altrui.
- Il secondo coordinatore comunica milestone/API/migrazioni/resource lease al thread corrente. Gradle, dispositivi e runtime WhisperX/GPU pesanti devono avere un solo utilizzatore concordato per volta. L'integrazione finale dei due flussi e i controlli trasversali rimangono coordinati qui; nessuna pubblicazione automatica richiesta dal nuovo incarico.
- Riferimenti tecnici verificati: Mistral API audio direct /v1/audio/transcriptions e timestamp segment (documentazione attuale); WhisperX espone forced alignment di testo esistente. Fattibilità end-to-end e accuratezza non ancora dimostrate sul companion dell'utente.
- Secondo flusso inizializzato su ea4e0a2, worktree C:\Users\casua\.t3\worktrees\Pampa notes\t3-cloud-recordings-parallel. Confermati coordinatore e target Sol/xhigh, pool indipendente3/3. Provider/retry, UX Registrazioni e API Python allineamento sono unità iniziali; bridge Android e orari/export seguiranno con agenti freschi. Entities/DB/metadati clock/allineamento gestiti dal secondo coordinatore, che invierà contratti prima delle modifiche.
- Risorse pesanti attualmente libere; nessun lease assegnato implicitamente. Richiesta esplicita prima di Gradle/dispositivo/WhisperX reale/GPU; i tre implementatori PN non hanno autorizzazione a usare tali risorse. Comunicato al secondo flusso.

## Export dedicato consegnato

- Implementatore export terminato, commit locale 11a7cbc1750eb04e5e23d94950be0d34f453c2f5, branch t3/add-pampa-notes-export, worktree C:\Users\casua\.t3\worktrees\Pampa notes\t3-533d8448 pulito. Target/formato PAMPA, UI IT/EN dedicata, raccolta di tutti gli allegati nativi, gestione relay pronto e trasferimenti indipendenti dopo errore PC, progressi/cancellazione e mancanze esplicite.
- Riportati 66 test Kotlin/JVM standalone passati, controlli XML/resource parity/placeholders/diff passati. PampaNoteWriter/PampaNoteFormat non ancora presenti nel worktree: nessuna build Gradle o prova servizio integrata eseguita, nessuno stub aggiunto. Test PampaExportServiceTest ancora da eseguire.
- Il coordinatore userà questo worktree, ora privo di writer attivi, per l'integrazione dei tre contributi quando codec/import saranno consegnati. Nessuna modifica applicativa parziale nel checkout principale. Nessuna approvazione della fase attribuita; nessun lease Gradle/dispositivo acquisito.

## Prima integrazione PN

- Tutti e tre gli implementatori terminati: export 11a7cbc, codec 1eaeec9, import 4c30b0c. Integrati nel worktree t3-533d8448 senza conflitti, HEAD 8ebfda8 (codec cherry-pick 65c80dc). Codice main ancora invariato; nessuna review della nuova fase ancora eseguita.
- Codec: riportati 91 JVM standalone passati, misure e limiti in PN_FORMAT.md. Import: 9 JVM standalone passati, 15 ulteriori test Android/MIME/Room/provider/Compose aggiunti ma non eseguiti dal delegato. I conteggi non sono un totale integrato deduplicato.
- Riprodotto un limite del vecchio formato schema3: writer native.json senza limite contro lettore limitato a64MiB. Il PN equivalente distribuisce i metadati e condivide i blob. Nessuna evidenza attribuisce questo limite al caso dell'amico. I temporanei del harness codec restano nel suo worktree dopo cleanup bloccato dalla policy; nessun nuovo tentativo di cancellazione necessario.
- Prima build integrata in corso, log %TEMP%\pampa-pn-integration-gradle.log, buildRoot %LOCALAPPDATA%\PampaNotes\build-pn-workflow. Lease Gradle acquisito esplicitamente e comunicato al secondo coordinatore. Richiesto anche dispositivo Pampa_Tablet tramite device_open per QA PN; nessun dispositivo fisico o GPU coinvolto.
- Contratto clock del secondo flusso ricevuto: AudioPartEntity avrà recordedAtEpochMs:Long?, recordedAtLocal:String?, recordedAtZoneId:String?, recordedAtSource:String?, recordedAtPrecision:String?, tutti default=null/SQL NULL; nessun backfill inventato, createdAt resta indipendente. Schema14→15 e coda allineamento di sua competenza. Aggiungere roundtrip PN completo, day-only e unknown quando integrati. ExportScope.Sessions sarà adattamento additivo limitato alle sessioni selezionate.

## Steering utente: risorse e coordinamento

- L'utente ha richiesto di procedere senza emulatore/dispositivi perché il computer è già occupato, basandosi sulle review indipendenti del codice. Le prove runtime Android non sono quindi richieste per questo incarico e devono restare dichiarate NON ESEGUITE. Mantengono valore compilazione/test sostenibili e le tre review per ciascun flusso.
- Nessun tentativo ulteriore di avvio, operazione sui lock/sessioni o sostituzione con dispositivi fisici. Il tentativo diagnostico precedente è fallito con duplicate-AVD; non attribuire prove runtime.
- Ridotti gli scambi fra coordinatori: contratto pubblico fissato, consegna consolidata a milestone stabile e integrazione finale unica, salvo blocchi o lease effettivi. Build successive con max-workers2 e parallel=false; nessun nuovo runtime pesante o GPU.

## Snapshot per review iniziale PN

- Integrato contratto pubblico5c91d57 (cherry-pick acd67d2); generato schema15 in220854ef6cd996f244f2f18c74ae2bb6c246c72b. Confronto14→15: solo5 colonne nullable audio_parts e1 nullable transcripts; nessuna coda privata nel DB pubblico. Generazione e compilazione non equivalgono a migrazione Android eseguita.
- Adattamenti PN in b52e45c6c8814b1c5c699a0116d0bccc1595074b: cloudSourceJson esterno in blob UTF-8/hash per non superare1MiB/record, null/empty/string distinti, stima dimensioni aggiornata, test completo clock/day/unknown e sorgente lunga/corrotta. Correzione MockK del test servizio; fixture legacy oltre64MiB costruita in streaming per evitare OOM del vecchio writer nel heap dei test.
- Build integrata PASSATA: assembleDebug, testDebugUnitTest core/app, assembleDebugAndroidTest core/app e lintDebug app. Log %TEMP%\pampa-pn-integration-gradle-4.log, BUILD SUCCESSFUL in4m5s; max-workers2/no-parallel. Test package Android compilati, NON eseguiti su dispositivi su richiesta utente.
- XML JVM ricontati: core991 casi (990 passati,1 skipped), app41 passati; totale1031 passati,1 skipped,0 failure/error. L'unico skipped è RelayWorkerInteropTest opt-in con workerd locale non avviato. Lint0 errori e123 warning; nessuna affermazione che tutti i warning siano preesistenti.
- PampaNoteRelayTest PASSATO: ArchiveFetcher, RelayRepository/RelayHttp, orchestration export, PN writer/reader e staging reali contro due fixture HTTP loopback; recuperati audio e fonte senza configurare il companion, confrontati byte estratti. Non è una chiamata al cloud distribuito né una prova account reale/UI/Room.
- Artefatti: %LOCALAPPDATA%\PampaNotes\build-pn-workflow\{core,app}\test-results\testDebugUnitTest; app\reports\lint-results-debug.{txt,xml,html}. Schema e test già nel worktree d'integrazione. Versione visibile ancora1.3.1/code20, nessuna release nuova.
- Avvio review iniziale su snapshot congelato successivo a questo aggiornamento, tre reviewer nuovi e indipendenti Sol/xhigh, criteri PN1–PN8/rubrica sopra con gate Android escluso esplicitamente dall'utente. Giri di correzione consumati0/3. Nessuna approvazione globale attribuita; integrazione finale cloud/Registrazioni resta successiva alla sua consegna consolidata.
