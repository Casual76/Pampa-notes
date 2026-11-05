# Importazione ZIP e aggiornamenti note

Configurazione autorizzata: GPT-6.1 Sol, reasoning high, due implementatori e due reviewer indipendenti per fase. Concorrenza massima quattro agenti incluso l'orchestratore. Massimo tre giri di correzione dopo la review iniziale; secondo giro in corso (uno restante dopo questo).

## Criteri

- ZIP Pampa importati come note native con struttura, testo, immagini, audio e trascrizioni effettivamente presenti.
- Nuovi export con dati strutturati e tempi di segmenti/parole per il roundtrip audio; vecchi pacchetti importati con limiti espliciti.
- Identità stabili per proporre aggiornamenti dopo rinomina, aggiornamenti espliciti senza duplicare figli o perdere contenuti locali assenti dal pacchetto.
- Titoli Samsung puliti da suffissi data/ora riconosciuti; numeri significativi conservati, fonti originali considerate, ambiguità gestite senza sostituzioni arbitrarie.
- Validazione degli archivi prima di modifiche durevoli e gestione coerente di errori/cancellazione.
- Test mirati significativi, build Android integrata e due review indipendenti sulla stessa versione. Le prove su dispositivo saranno riportate separatamente: nessun dispositivo era collegato all'avvio.

## Stato

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

## Prossime azioni

1. Integrare nel presente worktree il commit bundle quando disponibile.
2. Compilare e verificare i flussi integrati con buildRoot separato dalle build della release.
3. Congelare la versione da sottoporre ai due reviewer e risolvere rilievi concreti.
4. Integrare nel checkout principale soltanto dopo conferma di fine release, conservando ogni modifica preesistente.
