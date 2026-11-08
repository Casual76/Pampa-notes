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
