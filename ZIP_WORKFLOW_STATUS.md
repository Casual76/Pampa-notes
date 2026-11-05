# Importazione ZIP e aggiornamenti note

Configurazione autorizzata: GPT-6.1 Sol, reasoning high, due implementatori e due reviewer indipendenti per fase. Concorrenza massima quattro agenti incluso l'orchestratore. Massimo tre giri di correzione dopo la review iniziale; giri consumati: zero.

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

## Prossime azioni

1. Integrare nel presente worktree il commit bundle quando disponibile.
2. Compilare e verificare i flussi integrati con buildRoot separato dalle build della release.
3. Congelare la versione da sottoporre ai due reviewer e risolvere rilievi concreti.
4. Integrare nel checkout principale soltanto dopo conferma di fine release, conservando ogni modifica preesistente.
