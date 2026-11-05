# Importazione ZIP e aggiornamenti note

Configurazione autorizzata: GPT-6.1 Sol, reasoning high, massimo due implementatori contemporanei e due reviewer indipendenti per giro. Concorrenza massima quattro agenti incluso l'orchestratore. Dopo i giri iniziali l'utente aveva autorizzato prosecuzione illimitata, poi ha imposto tre giri aggiuntivi (6–8) e pubblicazione stable. Lavoro chiuso al giro 7 con due review senza rilievi.

## Stato corrente: completato e pubblicato

Pampa Notes **1.2.0 / versionCode 18**, canale **stable**, pubblicata e verificata il 2026-11-05. Importazione ZIP nativa e aggiornamenti/Samsung integrati in main. Nessun difetto concreto aperto nei perimetri delle due review finali. I limiti di esecuzione dei reviewer sono dichiarati nelle rispettive tabelle.

- Release: https://github.com/Casual76/Pampa-notes/releases/tag/stable-pampa-notes-v1.2.0
- APK: https://github.com/Casual76/Pampa-notes/releases/download/stable-pampa-notes-v1.2.0/pampa-notes-1.2.0.apk
- Sorgenti della release: e8c9ba4f2d0b7cc4a5f138e622be62da61979845. Engine 3.0.5, database 14.
- Artefatto remoto riscaricato e verificato: 13.018.187 byte; SHA256 355953a81bb72af63e1024db71865930aa6e265b2b22bf59efd58006f19ae939. Firma identica alla 1.1.2, certificato SHA256 825738500ce9605429ffba3c4019228f992f2c37d1b70a2230a7ce2c25637e43.
- Debug: 846 test JVM e 127 Android passati, build/lint/QA passati. Release: altri 846 test JVM della variante release passati, assembleRelease e lintRelease passati (log %TEMP%/pampa-zip-release-gradle.log, 6m37s). Aggiornamento reale 1.1.2→1.2.0 e avvio su emulator-5580 passati, crash buffer vuoto.
- Publisher: probe, dry-run e pubblicazione riusciti; tag sul commit sorgente esatto, release non prerelease, manifest app/indice Pampa Store verificati, configurazione engine remota conservata. Evidenze %TEMP%/pampa-zip-release/publish.json e remote-verification.json.
- La cancellazione locale preesistente di companion/tools/__pycache__/holes.cpython-311.pyc è stata ripristinata. Rimosso soltanto lo stash temporaneo di questa release, identificato per hash.

## Criteri

- ZIP Pampa importati come note native con struttura, testo, immagini, audio e trascrizioni effettivamente presenti.
- Nuovi export con dati strutturati e tempi di segmenti/parole per il roundtrip audio; vecchi pacchetti importati con limiti espliciti.
- Identità stabili per proporre aggiornamenti dopo rinomina, aggiornamenti espliciti senza duplicare figli o perdere contenuti locali assenti dal pacchetto.
- Titoli Samsung puliti da suffissi data/ora riconosciuti; numeri significativi conservati, fonti originali considerate, ambiguità gestite senza sostituzioni arbitrarie.
- Validazione degli archivi prima di modifiche durevoli e gestione coerente di errori/cancellazione.
- Test mirati significativi, build Android integrata e due review indipendenti sulla stessa versione. Le prove su dispositivo saranno riportate separatamente: nessun dispositivo era collegato all'avvio.

## Stato iniziale (storico)

- Implementatore wizard/Samsung completato: d748249e20e72a2c3a9ecfb8b1e74417f5bc396f, worktree corrente. Riportati 42 test JVM passati; due regressioni Room aggiunte, ancora da eseguire.
- Implementatore bundle core/export/persistenza in corso; API concordata PampaBundleArchive, PampaBundleImporter, PampaBundlePreview, PampaBundleImportResult.
- Integrazione, build completa e due review ancora da svolgere. Nessuna approvazione della fase attribuita.
- Checkout principale riservato all'altro thread fino alla conferma della release 1.1.2 / 17 con engine 3.0.5 e pulizia lint MainApp/local.properties. Non modificare versioni, engine, manifest o stash nel checkout principale.

## Verifica integrata e review iniziale

- Release di manutenzione conclusa: main 7e66664, engine 3.0.5. Entrambe le implementazioni integrate nel worktree; snapshot di review a42dc50.
- Engine doctor passato; 741 test JVM core passati, zero errori/fallimenti.
- Build UI inizialmente fallita per tre subtitle obbligatori: corretti con 194b9fa, nuova build da eseguire.
- Emulatore Pampa_Tablet API 35 avviato su emulator-5580. Nove test Room eseguiti: otto passati, uno Samsung con Unicode decomposto fallito. Log %TEMP%/pampa-zip-room.log; build %TEMP%/pampa-zip-gradle.log.
- Due review indipendenti completate. Rilievi confermati da correggere: identità multi-hop; conflitti di ordinamento parti/sessioni e offset; spostamenti legittimi di audio; testo locale preservato su aggiornamento bundle e Samsung; pagine a mano legacy; scelta trascrizioni rispettata nel payload; warning/progresso localizzati. Nessun gate di approvazione passato.

## Primo giro di correzioni

- fd0d7f2: correzioni Samsung/Unicode e conservazione testo personale; avvisi/progresso IT/EN tipizzati. Test Gradle core e otto test Room del coordinatore passati dopo la correzione.
- cf80a72: identità portabili stabili, copie rimappate, ordine e offset riconciliati, spostamenti audio della stessa nota; merge del testo con baseline persistente; pagine a mano legacy; trascrizioni limitate alla scelta e alla grezza necessaria.
- Database 12: colonna nullable bundleBodyBase, automigrazione 11→12; segue sync/backup ed è esclusa dagli export. Schema 12 generato da KSP nell'integrazione.
- Verifica integrata in corso. Primo tentativo interrotto da crash nativo JVM C2 in Kotlin FIR (hs_err_pid38952.log, conservato in %TEMP%/pampa-zip-qa), senza errore sorgente; ripetizione con due worker, senza parallelismo Gradle e compilatore in-process. Log %TEMP%/pampa-zip-round1-build.log.
- Due nuove review ancora da eseguire sulla versione integrata di questo giro. Non attribuita approvazione.

## Verifica del primo giro e secondo giro

- Snapshot 82ba47a: assembleDebug, 756 test JVM core, 21 test JVM app, 23 test Android importazione/migrazioni passati. Lint passato dopo correzione locale del percorso SDK e rigenerazione del report cache.
- Prova reale con picker: due note e gerarchia importate; testo/immagini visibili; audio WAV in sequenza con tempi 00:00 e 00:02, riproduzione fino a 00:04; reimportazione 0 nuove/2 aggiornate; copie senza audio con avviso e riproduzione disabilitata. Screenshot/XML/log in %TEMP%/pampa-zip-qa.
- Review del primo giro completata: selezione RAW e segmenti non allineati con storie locali più recenti; parole fuori dal segmento ammesse; audio legacy con durata zero; identità Samsung rinominata non riconosciuta dopo archiviazione del file originale.
- Rilievo UI del parent: audio omesso erroneamente descritto come in attesa del proprio computer; etichetta immagini singolare da correggere.
- 92a5b8a del parent: helper RawTranscripts.forSelection e limiti stretti dei tempi delle parole con regressioni.
- Implementatori al lavoro sul secondo giro: core per probe audio legacy/export selezionato, UI/Samsung per identità persistita (prevista migrazione 12→13), lettore e messaggi sugli audio omessi. Nessun gate di approvazione passato.

## Secondo giro integrato

- 9f8ce30: identità Source sdocxCreatedAtMillis persistente, migrazione 12→13, backfill locale/remoto leggero e preservazione via sync; selezione coerente del lettore e messaggi distinti per audio omesso.
- 1926bd8: probe reale degli audio inclusi con durata sconosciuta prima della scrittura; export aderente a RAW/raffinata selezionati e relativo genitore.
- Schema 13 generato da KSP; build, test JVM app/core, suite Android core completa e lint in corso con due worker e compilatore in-process. Log %TEMP%/pampa-zip-round2-gradle.log.
- Da ripetere prova legacy/ricerca audio, omissioni e rifiuto di tempi incompatibili sulla build finale. Due nuove review ancora da avviare dopo congelamento dello schema e della versione.

## Esito secondo giro e terzo giro

- Snapshot 699bc6f: build app/test JVM app passati; tutti i 94 test Android core passati su Pampa_Tablet API35 e sull'altro emulatore attivo. Le prossime esecuzioni saranno limitate a emulator-5580.
- Compilazione JVM core bloccata dal test che usava javax.sound.sampled, escluso dalla compilazione Android: 81a6408 sostituisce solo l'adapter JVM con lettura del formato PCM della fixture; il probe Android reale è già provato nei test Room.
- Due review disponibili: P1 SessionRepository mescola/cancella segmenti di storie RAW diverse su riordino/spostamento; P1 SyncApplier elimina versioni selezionate/raffinate usando solo copertura parti; P2 metadati Samsung nuovi persi quando l'allegato non viene sostituito; P3 anteprima confonde segmenti e tempi parola esatti.
- Terzo giro assegnato ai due implementatori: repository delle sessioni e relativi test; sync/identità fonte/copertura tempi e relativi test. Nessun gate di approvazione passato.
- Prove manuali su schema13: tempi parola invalidi rifiutati prima dell'import; vecchio ZIP con audio misurato a 2s+2s, cursore e ricerca disponibili. Nuovo difetto riprodotto nel passaggio schema2→3 della stessa nota: sessioni/audio duplicati per ID sintetici diversi. Aggiunto al terzo giro dell'implementatore importer, con prova richiesta vecchio→nuovo→reimportazione e conservazione del contenuto locale. Evidenze audio-upgrade.xml/png in %TEMP%/pampa-zip-qa.

## Terzo giro integrato

- a3bfafd: niente potatura automatica RAW nel sync; metadati identità Samsung conservati negli aggiornamenti senza originale; copertura dei tempi parola effettivi; identificatori legacy riconoscibili e riconciliazione con i pacchetti nativi senza duplicare registrazioni.
- 2005e8b: RawTimelinePlan e operazioni SessionRepository transazionali, con proprietà separata dei segmenti per ogni versione RAW e conservazione delle raffinate.
- 93e82ad: ricerca, scelta del genitore per i lavori e riconoscimento del risultato remoto usano la grezza selezionata, senza unire coperture di storie alternative.
- Verifica integrata completa in corso sul solo emulator-5580, buildRoot %LOCALAPPDATA%/PampaNotes/build-zip-final, log %TEMP%/pampa-zip-round3-gradle.log. Nuove review finali ancora da avviare; non attribuita approvazione.

## Verifica integrata finale prima delle review

- Build assembleDebug, lintDebug, 791 test JVM core, 25 test JVM app e 109 test Android core passati. Suite Android limitata a emulator-5580. Engine doctor passato, engine 3.0.5 pulito.
- Prova manuale fresca dopo il terzo giro: vecchio ZIP importato, successivo ZIP nativo della stessa nota aggiornato (0 nuove/2 aggiornate), una sola sessione con due WAV da 2s. La nota mantiene il titolo aggiornato e la versione raffinata selezionata. Reimportazione del vecchio pacchetto in verifica.
- Codice stabilizzato al commit 2005e8b; aggiornamenti successivi in questo passaggio sono soltanto documentazione. Due review finali richieste sul medesimo risultato integrato; tre giri di correzione consumati, nessuna approvazione ancora attribuita.

## Review del terzo giro e prosecuzione autorizzata

- Primo reviewer finale: P1 RawTimelinePlan compone storie RAW non selezionate quando coprono parti disgiunte; P1 legacy→legacy usa identità posizionali e può sostituire l'audio conservando parole di un'altra registrazione dopo riordino/rimozione. Entrambi confermati dai riferimenti del codice; nessun fix ancora applicato alla versione in review f2a4c5c.
- Prova manuale di reimportazione del vecchio ZIP dopo upgrade nativo passata: titolo aggiornato conservato, una sessione/due parti, nessun downgrade della selezione. Non copre il riordino legacy segnalato dal reviewer.
- L'utente ha risposto: «Continua fino alla chiusura dei difetti, senza limite di giri». Approvazione aggiuntiva acquisita; attendere il secondo report concordato, poi correggere e ripetere due review sul risultato stabilizzato. Checkout principale non ancora modificato dalle nuove funzioni.

## Quarto giro autorizzato

- Entrambe le review sul terzo giro concordano sui due casi: composizione di RAW alternativi non selezionati e collisioni degli ID legacy dopo riordini/rimozioni.
- a9b468e del parent: fallback alle storie alternative soltanto in assenza di rami selezionati; regressione per selezione RAW/raffinata parziale, ricostruzione invariata e riordino. Tutti i 12 test RawTimelinePlanTest passati tramite Gradle, comprese le composizioni legittime tra sessioni.
- 8a3a66f: identità legacy riconciliate per contenuto verificato, chiavi di staging rimappate, audio/fonti locali e versioni conservati, prove ambigue rifiutate. L'implementatore riporta 33 test JVM passati e test Room compilati standalone.
- Verifica integrata Android/JVM/build/lint in corso sul solo emulator-5580, log %TEMP%/pampa-zip-round4-gradle.log. Nessuna approvazione ancora attribuita.

## Verifica del quarto giro

- Snapshot funzionale 8a3a66f: assembleDebug, lintDebug, 803 test JVM core, 25 test JVM app e 113 test Android core passati, zero fallimenti/errori. I quattro nuovi test Room verificano riordino/rimozione, nuove chiavi di staging, riordino sessioni e rollback dell'identità legacy ambigua o assente. Log %TEMP%/pampa-zip-round4-gradle.log, BUILD SUCCESSFUL in 3m21s.
- Engine doctor passato (3.0.5 pulito). APK aggiornato installato sul solo emulator-5580 senza cancellare dati. Versione app invariata 1.1.2/17.
- Nuove review indipendenti di chiusura da avviare sulla versione congelata. Main ancora a 7e66664; l'unica modifica locale preesistente resta la cancellazione della cache Python.

## Quinto giro

- Due review sullo snapshot 51c7926 completate: i precedenti difetti risultano chiusi; restano P1 alias audio saltato quando la sessione marcata è già condivisa e P2 ricerca globale che può mostrare una storia diversa dalla trascrizione aperta.
- Correzioni del parent: figli marcati riconciliati anche nelle sessioni già riconosciute, nessun alias della sessione verso se stessa, guardia per lavori attivi quando cambiano solo gli ID audio. Audio locale assente dall'aggiornamento della sessione condivisa conservato.
- Ricerca FTS limitata alla trascrizione effettivamente visibile aprendo la sessione, con selezione esplicita o fallback RAW più recente; il limite dei risultati si applica dopo il filtro. Restano memorizzate le storie alternative.
- Nuove regressioni JVM e Room per vecchi ID ordinali → nuovo re-export nativo, reimportazioni ripetute, conservazione dei file/tempi e blocco dei lavori attivi. Regressioni Room per ricerca dopo aggiornamento bundle, RAW/raffinata selezionati e fallback.
- Verifica integrata in corso su emulator-5580, log %TEMP%/pampa-zip-round5-gradle.log. Nessun gate di approvazione ancora passato.

## Verifica finale del quinto giro

- Snapshot funzionale 18afd60: riconciliazione dei figli indipendente dall'alias della sessione, aggiornamenti delle sessioni condivise solo testuali, guardia dei lavori attivi e ricerca FTS del testo visibile.
- Build assembleDebug, lintDebug, 806 test JVM core, 25 test JVM app e 116 test Android core passati. Log %TEMP%/pampa-zip-round5-final-gradle.log, BUILD SUCCESSFUL in 2m53s. Le nuove query FTS sono state eseguite nel database Android reale, anche dopo importazione e aggiornamento di uno ZIP con storie conservate.
- Da sottoporre a due nuove review indipendenti prima dell'integrazione in main. App/engine/schema restano 1.1.2-17 / 3.0.5 / 13.

## Sesto giro

- Due review sul quinto giro completate. Ricerca e duplicazione nel caso di sessione condivisa semplice risultano corrette; emersi P1 downgrade di corpo/titolo/cartella dopo legacy → native con ID marcati ereditati → vecchio legacy, P1 sessioni ordinali scambiate prima del nuovo re-export nativo, P2 rifiuto di una sessione interamente omessa e P2 audio nativo identico con ID esplicito già riconosciuto.
- Il parent riserva gli ID audio esatti compatibili prima degli alias per contenuto, mantenendo distinta la registrazione nativa aggiuntiva identica; regressione con reimportazione ripetuta passata tramite Gradle.
- Da correggere insieme provenienza nativa persistente indipendente dagli ID ereditati e riconciliazione globale dei grafi/sessioni, con conservazione delle sessioni locali omesse e gestione sicura di alias incrociati. Nessun gate di approvazione passato.

## Aggiornamento della skill e quadro corrente

- Letta la skill agentic-workflow aggiornata e references/runtime.md. Configurazione confermata: GPT-6.1 Sol/high per implementazione e review; massimo 2 implementatori contemporanei, 2 reviewer distinti per giro; massimo complessivo 4 agenti incluso orchestratore. Correzioni senza limite di giri per autorizzazione dell'utente. Il sesto giro è in implementazione; una sola unità sostanziale attiva perché le modifiche condividono lo stesso riconciliatore.
- Dal prossimo giro di review: tabella individuale obbligatoria 0–10 per reviewer e riepilogo generale con media/minimo soltanto di voti verificati comparabili. Nessuna nuova soglia numerica: passaggio soltanto a criteri obbligatori soddisfatti, con entrambi i report e nessun difetto concreto aperto.
- Identificatori condivisi, corrispondenti ai criteri già concordati: C1 importazione nativa completa e flusso utente; C2 roundtrip audio/trascrizioni/tempi e selezione; C3 identità/aggiornamenti/conservazione locale; C4 riconoscimento e titoli Samsung; C5 validazione, errori, cancellazione e rollback; C6 verifiche integrate e prove d'uso.
- Rubrica comune: 0–2 criterio sostanzialmente non soddisfatto; 3–4 funzionalità parziale con difetti gravi; 5–6 implementazione presente ma requisito ancora violato; 7–8 criterio soddisfatto nelle verifiche dichiarate con limiti espliciti; 9–10 criterio soddisfatto con copertura solida dei casi pertinenti. NV per non verificato, NA per non applicabile; provvisorio escluso dagli aggregati. Ogni voto deve avere evidenze e limiti. I voti non compensano requisiti falliti.

### Riepilogo dell'ultima review completa, precedente all'aggiornamento

Fase: integrazione ZIP e aggiornamenti. Giro: 5. Versione: d6cce25 (codice 18afd60). R1 identità/persistenza e R2 ricerca/flussi: entrambi GPT-6.1 Sol, reasoning high. I report storici sono qualitativi e non contengono voti; NF significa voto non fornito, non criterio non verificato. Nessun voto ricostruito dall'orchestratore; aggregati su 0/2 voti disponibili. Le altre review storiche restano documentate nelle sezioni precedenti senza attribuzione retroattiva di voti.

| Criterio | R1 | R2 | Media | Minimo | Esito e problemi aperti alla versione esaminata |
|---|---|---|---|---|---|
| C1 Importazione nativa e flussi | NF | NF | — | — | Implementata e provata dal parent; approvazione finale pendente. |
| C2 Audio, versioni e tempi | NF | NF | — | — | Prove passate; riordino tra sessioni legacy può duplicare audio e spostare tempi (P1, C3). |
| C3 Identità, aggiornamenti e conservazione | NF | NF | — | — | Due P1: downgrade del contenuto nativo e sessioni ordinali scambiate. Due P2: sessione omessa e audio identico con ID esatto. |
| C4 Samsung e titoli | NF | NF | — | — | Implementato con regressioni; nessun nuovo rilievo nei due report. |
| C5 Validazione e rollback | NF | NF | — | — | Prove parent passate; nessun nuovo rilievo specifico nei due report. |
| C6 Verifiche integrate | NF | NF | — | — | Parent: 831 test JVM, 116 Android, assembleDebug e lintDebug passati. I reviewer non hanno eseguito Gradle/Android. |

Decisione: correggere, non integrare ancora in main. Stato successivo alla review: P2 ID audio esatto corretto con f369b81 e 19 test mirati passati; altri tre rilievi in lavorazione nel sesto giro. La suite completa del quinto giro non certifica le modifiche ancora in corso.

## Nuovo limite e autorizzazione alla release stable

- Ultima istruzione utente: «Imposto un limite di altri 3 giri di sistemamente difetti, poi prosegui con la publish sul pampa store canale stable».
- Il nuovo limite sostituisce quello illimitato: massimo tre giri da questo punto, contando il sesto attualmente in corso come primo; quindi giri 6, 7 e 8. Dopo il giro 6 restano al massimo due ulteriori giri, senza azzerare il contatore. Se i criteri si chiudono prima, non inventare altri giri.
- Pubblicazione Pampa Store canale stable esplicitamente autorizzata: predisporre integrazione main, versione superiore a 1.1.2/code17, build release, firma coerente, verifiche e pubblicazione. Il precedente vincolo «nessun bump/push/rilascio richiesto» è superato da questa istruzione; mantenere intatte le modifiche preesistenti.
- Letta la skill pampa-store-publish-direct. Probe del checkout principale in preparazione; la release si esegue dopo le correzioni/verifiche, senza prolungare i giri oltre il nuovo limite. I report di review successivi devono includere le tabelle dei voti aggiornate.

## Sesto giro: implementazione consegnata

- 4afe8e0: riconciliazione globale degli audio, sessioni incrociate/cicliche e sessioni omesse con conservazione di file, storie, voci, job e statistiche. Provenienza privata nullable bundleNativeAdopted, automigrazione 13→14, sync monotono e rimozione dagli export portabili. Le note migrate senza provenienza conservano corpo/baseline negli import legacy fino a prova nativa.
- Implementatore: 78 test JVM standalone passati, test Room/sync/migrazione compilati ma non eseguiti. Parent: generazione schema 14 e verifica integrata in corso, log %TEMP%/pampa-zip-round6-gradle.log. Le modifiche non sono ancora approvate.
- Il giro 6 è il primo dei tre consentiti (6–8). Dopo la verifica integrata seguiranno due nuove review indipendenti con tabella individuale dei voti e riepilogo generale. Pubblicazione stable autorizzata dopo la fase di correzione/verifica.

## Verifica integrata del sesto giro

- Codice 4afe8e0: 818 test JVM core + 25 app (843 totali), 126 test Android core, assembleDebug e lintDebug passati. Log %TEMP%/pampa-zip-round6-final-gradle.log, BUILD SUCCESSFUL in 1m30s. Il primo tentativo aveva un solo errore di asset del nuovo schema 14 generato durante la build; ripetizione con schema presente passata senza modifiche applicative aggiuntive.
- Schema 14 generato: unica modifica di tabella rispetto al 13 è notes.bundleNativeAdopted, INTEGER nullable con default NULL. Engine doctor passato, versione 3.0.5 pulita.
- APK installato con aggiornamento senza cancellazione dati sul solo emulator-5580. Avvio e migrazione reale dell'archivio QA passati: schema14, quattro note QA riconosciute, sessioni/parti/baseline conservate, durate 2s e audio omessi distinti da quelli presenti. Buffer crash vuoto. Evidenze %TEMP%/pampa-zip-qa/round6-avvio.xml e state-verification.json.
- Due nuove review del giro 6 da avviare sullo snapshot congelato con rubrica e tabelle aggiornate. È il primo dei tre giri consentiti: eventuali correzioni successive solo nei giri 7 e 8, poi release stable autorizzata.

## Review giro 6: R1 ricevuto, riepilogo parziale

Fase integrazione, giro 6, versione f667cb1ef93e9d0b8d824fdfcf8e53075215c15f. R1: GPT-6.1 Sol/high, dati/provenienza/audio. R2: GPT-6.1 Sol/high, import/Samsung/uso, ancora in attesa. Nessuna approvazione: confermato dal parent il percorso che mette in quarantena file ancora referenziati dopo retirement degli alias. Nessuna modifica applicativa durante la review ancora aperta.

Tabella individuale R1 (voti trascritti dal report; motivazioni sintetizzate):

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---|---|---|---|
| C1 | NA | Fuori ambito | Nessuna valutazione completa del flusso UI e della gerarchia. | Non valutati. |
| C2 | 8/10 | Sorgenti e artefatti parent verificati | Export selezionato/RAW, offset del merge, limiti dei tempi parola; XML parent senza fallimenti. | Nessun nuovo difetto locale; P1 sync compromette disponibilità audio successiva. |
| C3 | 6/10 | Verificato, requisito ancora violato | Provenienza, baseline, riconciliazione globale/omissioni/scambi, sync monotono ed esclusione metadati privati esaminati. | P1: sync rimuove dalla posizione attiva un file conservato sotto il nuovo ID. |
| C4 | NA | Fuori ambito | Nessuna review completa Samsung/normalizzazione. | Non valutati. |
| C5 | 6/10 | Verificato, requisito ancora violato | Validazione/staging/transazione/cleanup/checkpoint e confine NonCancellable esaminati. | P1: quarantena post-transazione senza controllo dei riferimenti vivi. |
| C6 | 8/10 | Artefatti verificati; esecuzioni del parent | Ricontati 818 core JVM, 25 app JVM e 126 Android, zero fallimenti/errori/skipped; log/lint/schema14/QA verificati. | Manca regressione di sync dopo retirement alias; nessuna esecuzione Gradle/Android del reviewer. |

| Criterio | R1 | R2 | Media | Minimo | Esito e problemi aperti |
|---|---|---|---|---|---|
| C1 | NA | in attesa | — | — | 0/2 voti validi, criterio ancora in attesa di R2. |
| C2 | 8 | in attesa | 8 (1/2) | 8 | Locale verificato; disponibilità audio successiva compromessa dal P1 sync. |
| C3 | 6 | in attesa | 6 (1/2) | 6 | P1 file ancora referenziato dal nuovo ID spostato nel cestino. |
| C4 | NA | in attesa | — | — | 0/2 voti validi, criterio ancora in attesa di R2. |
| C5 | 6 | in attesa | 6 (1/2) | 6 | Stesso P1: controllo dei riferimenti assente prima della quarantena. |
| C6 | 8 | in attesa | 8 (1/2) | 8 | Artefatti passati, necessaria nuova regressione sync. |

Rilievo R1: SyncApplier.apply usa trash.forEach(::quarantine) dopo il commit; delete audio/source raccoglie il vecchio file senza verificare se il nuovo record lo usa. Esempio B riceve upsert N e tombstone L, entrambi con shared.wav: resta N valido, ma il file viene spostato. Rilievo source-derived e modello SQL, non esecuzione Kotlin. Da coprire sia audio sia fonti con regressione Room e verifica byte. Decisione: attendere R2, poi correggere nel giro 7 (secondo dei tre consentiti); al massimo successivo giro 8 prima della release stable.

## Review completa del giro 6

R2: GPT-6.1 Sol/high, integrazione, giro 6, f667cb1ef93e9d0b8d824fdfcf8e53075215c15f. Secondo report ricevuto. Voti individuali R2 trascritti; motivazioni sintetizzate dal report.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---|---|---|---|
| C1 | 6/10 | Sorgenti e artefatti UI parent ispezionati | Payload, gerarchia, allegati, preview, update/copy e IT/EN implementati. | P2: import dalla cartella inclusa nel bundle propone una destinazione che persistence rifiuta; errore generico fuorviante. |
| C2 | NA | Fuori ambito completo assegnato | Consumer selezionati esaminati incidentalmente, nessuna certificazione completa del roundtrip. | Nessun verdetto separato. |
| C3 | 8/10 | Sorgenti/test e XML parent verificati | Provenienza e riconciliazione globale/cicli/omissioni; regressioni Room con parole, file, job, statistiche e FK. | Nessun nuovo difetto nelle sequenze esaminate; nessuna prova runtime propria. |
| C4 | 8/10 | Sorgenti/test e risultati Android parent verificati | Identità Samsung originale/intrinseca, ambiguità, eviction e normalizzazione conservativa dei suffissi. | Nessun rilievo; nessuna prova indipendente su dispositivo Samsung. |
| C5 | NA | Fuori ambito completo assegnato | Validazione destinazione esaminata incidentalmente. | Nessun verdetto separato. |
| C6 | 8/10 | Verifica indipendente degli artefatti parent | 818+25 JVM e 126 Android senza errori/fallimenti/skips; log/schema14 e DB QA schema14/4 note/FK verificati. | UI precedente non copre il nuovo flusso difettoso; engine doctor/install/crash restano dichiarazioni parent. |

Riepilogo generale completo: stessa fase/giro/versione; R1 e R2 entrambi GPT-6.1 Sol/high. NA esclusi dagli aggregati. Tra parentesi i voti comparabili su due reviewer.

| Criterio | R1 | R2 | Media | Minimo | Esito e problemi aperti |
|---|---|---|---|---|---|
| C1 | NA | 6 | 6 (1/2) | 6 | P2 destinazione del wizard incompatibile con aggiornamento nella propria gerarchia. |
| C2 | 8 | NA | 8 (1/2) | 8 | Roundtrip locale verificato; il P1 sync può compromettere disponibilità audio successiva. |
| C3 | 6 | 8 | 7 (2/2) | 6 | P1 file ancora referenziati spostati nel cestino: il voto R2 sulle identità locali non annulla il difetto. |
| C4 | NA | 8 | 8 (1/2) | 8 | Nessun rilievo aperto. |
| C5 | 6 | NA | 6 (1/2) | 6 | P1 cleanup del sync senza controllo dei riferimenti correnti. |
| C6 | 8 | 8 | 8 (2/2) | 8 | Artefatti verificati, necessarie regressioni per i due nuovi casi. |

Decisione: giro 6 non approvato; due difetti confermati, correzione nel giro 7. Non mescolare questi voti con la versione aggiornata del giro successivo.

## Settimo giro (secondo dei tre consentiti)

- Parent: quarantena del sync protetta dai riferimenti correnti ad audio/fonti, con lettura e spostamento serializzati rispetto agli altri writer Room. Regressione con upsert canonico e tombstone alias: byte conservati finché referenziati e quarantena soltanto dopo l'ultima cancellazione.
- Preview espone gli ID delle cartelle importate. Il wizard normalizza destinazione iniziale, selezione manuale e cambio copia→aggiornamento, esclude gerarchia importata e discendenti dalle destinazioni degli aggiornamenti; le copie conservano destinazioni valide grazie ai propri ID rimappati. Guard persistence anti-ciclo invariata.
- Build, test JVM/Android e lint in corso, log %TEMP%/pampa-zip-round7-gradle.log. Seguiranno due nuove review sul risultato stabilizzato. Rimane al massimo un ulteriore giro 8 prima della release stable autorizzata.

## Verifica integrata del settimo giro

- Passati 818 test JVM core e 28 app (846 totali), 127 test Android core senza fallimenti/errori/skips, assembleDebug e lintDebug. Log %TEMP%/pampa-zip-round7-gradle.log, BUILD SUCCESSFUL in 3m29s. Inclusa regressione reale SyncApplier con conservazione byte di audio/fonti e quarantena solo dopo rimozione dell'ultimo riferimento.
- APK aggiornato installato senza cancellare dati su emulator-5580. Prova UI del percorso segnalato: aperta la cartella originale Capitolo, menu Importa, picker del medesimo ZIP nativo; preview con 2 note aggiornabili e default «Mantieni le cartelle originali», import senza cambiare opzioni riuscito: 0 nuove / 2 aggiornate / 2 registrazioni. Evidenze %TEMP%/pampa-zip-qa/round7-anteprima.xml/png e round7-risultato.xml/png.
- Tre regressioni JVM app verificano cartella importata e discendenti esclusi dagli aggiornamenti, copie consentite e cambio copia→aggiornamento, conservazione di destinazione esterna e rimozione di cartella inesistente. Guard persistence invariata.
- Da congelare per due nuove review con tabelle dei voti: giro 7, secondo dei tre consentiti; al massimo un ulteriore giro 8 prima della pubblicazione stable autorizzata.

## Review giro 7: R1 ricevuto, riepilogo parziale

Versione 62cc0ddff6fc938c1608c3490a72b2e371dd29eb, integrazione, giro 7. R1 GPT-6.1 Sol/high, dati/file sync/integrità. Nessun rilievo azionabile nel perimetro. R2 GPT-6.1 Sol/high ancora in attesa; non attribuita approvazione finale.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---|---|---|---|
| C1 | NA | Verifica parziale, completo fuori assegnazione | Filtro/normalizzazione e screenshot/XML round7 confermano default e risultato 0 nuove/2 aggiornate/2 registrazioni. | Nessun difetto nel percorso controllato; non rivalutata tutta la completezza/localizzazione. |
| C2 | 8/10 | Sorgenti e report parent verificati | Export selezionato/RAW, relazione genitore, validazione tempi/offset e regressioni di roundtrip/consumer. | Nessun rilievo; nessuna riproduzione audio propria del reviewer. |
| C3 | 8/10 | Soddisfatto nei percorsi esaminati | ID esatti, provenienza monotona, copie, merge conservativo, retirement alias e regressioni Room su cicli/omissioni. | Nessun rilievo; quarantena risolta nei controlli, non esplorati grafi arbitrari esaustivamente. |
| C4 | NA | Fuori assegnazione | Nessuna rivalutazione completa Samsung/titoli. | NA escluso dagli aggregati. |
| C5 | 8/10 | Transazioni e cleanup verificati | Riferimenti audio/fonti correnti controllati nella seconda transazione Room, cancellazioni dirette/cascade filtrate, staging/rollback e regressione byte/quarantena. | Nessun rilievo; nessun test concorrente/crash proprio. |
| C6 | 8/10 | Artefatti parent verificati indipendentemente | 818 core JVM + 28 app + 127 Android, zero errori/fallimenti/skips; log3m29s, lint senza errori, schema14 e UI round7. | Nessun rilievo; esecuzioni parent. |

| Criterio | R1 | R2 | Media | Minimo | Esito e problemi aperti |
|---|---|---|---|---|---|
| C1 | NA | in attesa | — | — | 0/2 voti validi, attesa R2. |
| C2 | 8 | in attesa | 8 (1/2) | 8 | Nessun rilievo R1. |
| C3 | 8 | in attesa | 8 (1/2) | 8 | Nessun rilievo R1. |
| C4 | NA | in attesa | — | — | 0/2 voti validi, attesa R2. |
| C5 | 8 | in attesa | 8 (1/2) | 8 | Nessun rilievo R1. |
| C6 | 8 | in attesa | 8 (1/2) | 8 | Artefatti verificati. |

Preparazione indipendente della release: main ancora7e66664 con sola cancellazione cache Python preesistente; ultima stable1.1.2 confermata. APK precedente scaricato in %TEMP%/pampa-zip-release, SHA256 f981fd2a62f919a2b562031f4732f6eb686ab518cba087bd242e8c86eb440eb2 confermato. Changelog IT preparato nella stessa directory. Configurazione firma e OAuth disponibile nel local.properties ignorato del main; accesso GitHub verificato, credenziali mai riportate.

## Review completa del giro 7 e passaggio alla release

R2: GPT-6.1 Sol/high, integrazione, giro 7, versione62cc0ddff6fc938c1608c3490a72b2e371dd29eb. Nessun rilievo azionabile. Voti individuali trascritti, motivazioni sintetizzate.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---|---|---|---|
| C1 | 8/10 | Sorgenti, test e artefatti UI verificati | Preview IDs, filtro antenati/discendenti, default/manuale/copia→update e messaggi IT/EN esaminati. | Nessun difetto; nessuna interazione UI propria, screenshot con omonimi non identificano da soli gli ID esclusi. |
| C2 | NA | Fuori assegnazione completa | Consumer RAW/ricerca/player controllati incidentalmente. | Nessun voto aggregabile. |
| C3 | 8/10 | Conservazione verificata nei percorsi esaminati | Provenienza, ID esatti, riconciliazione globale/cicli/omissioni, baseline/copie/export e quarantena con regressioni Room. | Nessun difetto; runtime del parent, nessuna simulazione concorrente propria. |
| C4 | 8/10 | Controlli Samsung su sorgenti/test/report | Fonte/intrinsicità, ambiguità, eviction/omissioni, NFC/spazi/suffissi validi e numeri significativi. | Nessun rilievo; nessuna prova propria Samsung. |
| C5 | NA | Fuori assegnazione completa | Guard destinazione, riferimenti/quarantena controllati incidentalmente. | Nessun voto aggregabile. |
| C6 | 8/10 | Artefatti parent verificati indipendentemente | 818+28 JVM e 127 Android senza errori/failure/skips; lint/log3m29s, schema14, QA e APK debug17. | Nessuna discrepanza; nessuna esecuzione propria né verifica release firmata. |

Riepilogo generale completo: integrazione, giro 7, stessa versione62cc0dd; R1/R2 entrambi GPT-6.1 Sol/high. NA esclusi; conteggio fra parentesi su due reviewer.

| Criterio | R1 | R2 | Media | Minimo | Esito e problemi aperti |
|---|---|---|---|---|---|
| C1 | NA | 8 | 8 (1/2) | 8 | Soddisfatto nei controlli, nessun rilievo aperto. |
| C2 | 8 | NA | 8 (1/2) | 8 | Soddisfatto nei controlli, nessun rilievo aperto. |
| C3 | 8 | 8 | 8 (2/2) | 8 | Soddisfatto, nessun rilievo aperto. |
| C4 | NA | 8 | 8 (1/2) | 8 | Soddisfatto nei controlli, nessun rilievo aperto. |
| C5 | 8 | NA | 8 (1/2) | 8 | Soddisfatto nei controlli, nessun rilievo aperto. |
| C6 | 8 | 8 | 8 (2/2) | 8 | Build/test/QA verificati; verifica della release firmata ancora da eseguire. |

Decisione: tutti i criteri di implementazione soddisfatti entro i limiti dichiarati e due report disponibili, nessun difetto concreto aperto. Avanzare autonomamente alla release stable già autorizzata. Non consumare l'ulteriore giro 8 senza necessità. Preparare1.2.0/code18 con engine3.0.5 e DB14; preservare la cancellazione cache Python locale preesistente. Certificato APK1.1.2: SHA256 825738500ce9605429ffba3c4019228f992f2c37d1b70a2230a7ce2c25637e43.

## Consegna

Implementazione, review indipendenti, integrazione, firma, pubblicazione stable e verifica remota completate. Nessun ulteriore giro richiesto. Conservare gli artefatti di QA/release come evidenze; non attribuire esecuzioni runtime ai reviewer.
