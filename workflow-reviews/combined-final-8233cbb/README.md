# Review finale integrata PN e CLOUD/RECORDINGS

Snapshot: `8233cbbd71be08ecc8659fb2a1372ed203a73197`, branch `main`. Nove critici distinti, tutti GPT-6.1 Sol/xhigh, al massimo tre figli attivi. PN ha già consumato quattro giri standalone e CLOUD tre; questa è la review finale combinata autorizzata dall’utente, senza azzerare quei contatori.

| Criterio | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Media (n/attesi) | Minimo | Esito e problemi aperti |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| PN1 | 9 | NA | NA | NA | NA | NA | NA | NA | NA | 9 (1/1) | 9 | Nel perimetro R1; R6-01 aperto sull’export ristretto |
| PN2 | NA | 9 | NA | NA | NA | NA | NA | NA | NA | 9 (1/1) | 9 | Nessun rilievo nel perimetro |
| PN3 | NA | NA | 9 | NA | NA | NA | NA | NA | NA | 9 (1/1) | 9 | Nessun rilievo nel perimetro; runtime dispensato |
| PN4 | NA | NA | 9 | NA | NA | NA | NA | NA | NA | 9 (1/1) | 9 | Nessun rilievo nel perimetro; runtime dispensato |
| PN5 | NA | NA | NA | NA | NA | NA | NA | 6 | NA | 6 (1/1) | 6 | PN5-F1 P2: scoperta originali SHARE/CLIPBOARD |
| PN6 | NA | NA | NA | NA | NA | 6 | NA | NA | NA | 6 (1/1) | 6 | R6-01 P2: cronologie/selezione eliminate senza avviso |
| PN7 | NA | 6 | NA | NA | NA | NA | NA | NA | NA | 6 (1/1) | 6 | R2-F1 P2 condizionale: rollback e owner sync |
| PN8 | NA | NA | NA | NA | NA | NA | NA | NA | 9 | 9 (1/1) | 9 | Evidenze verificate; non approvazione funzionale |
| C1 | NA | NA | NA | 6 | NA | NA | NA | NA | NA | 6 (1/1) | 6 | R4-F1 P2: risposta Mistral malformata certificata vuota |
| C2 | NA | NA | NA | 9 | NA | NA | NA | NA | NA | 9 (1/1) | 9 | Pinning/no fallback verificati nel perimetro |
| C3 | NA | NA | NA | 8 | NA | NA | NA | NA | NA | 8 (1/1) | 8 | R4-F2 P3: fallback429 trattiene attesa eccessiva |
| C4 | NA | NA | NA | NA | 6 | NA | NA | NA | NA | 6 (1/1) | 6 | R5-F1 P2: annullamento sovrascritto; duplicato Mistral |
| C5 | NA | NA | NA | NA | 8 | NA | NA | NA | NA | 8 (1/1) | 8 | R5-F3 P3 condizionale: lingua modello fra finestre |
| C6 | NA | NA | NA | NA | NA | 6 | NA | NA | NA | 6 (1/1) | 6 | R6-01 P2 e R6-02 P3 sul consenso cloud |
| C7 | NA | NA | NA | NA | NA | NA | 6 | NA | NA | 6 (1/1) | 6 | R7-01 P2: tuple clock composte; R7-02 P3 avviso tempi |
| C8 | 6 | NA | NA | NA | NA | NA | NA | NA | NA | 6 (1/1) | 6 | R1-C8-01 P2: dati operativi privati nel backup |
| C9 | NA | NA | NA | NA | NA | NA | NA | NA | 9 | 9 (1/1) | 9 | Evidenze verificate; non approvazione funzionale |

Ogni criterio ha un reviewer assegnato: media e minimo includono il solo voto verificato comparabile (1/1), gli altri sono NA. Nessuna media fra criteri, snapshot o giri. Nessuna nuova soglia numerica. I voti non compensano requisiti obbligatori falliti. R1 PN1 resta trascritto fedelmente, mentre il rilievo trasversale R6-01 impedisce una conclusione globale di fedeltà dell’export ristretto.

## Decisione del coordinatore

Avanzare con correzioni consolidate dei rilievi confermati, poi verifiche integrate e controlli indipendenti mirati prima della pubblicazione stable già autorizzata. Nessuna release/deploy ancora eseguita. Sono distinti sette P2 (uno condizionale) e quattro P3 (uno condizionale); R5-F2 duplica R4-F1 e non è contato una seconda volta.

- **R2-F1 — P2 conditional**, PN7: PN staging rollback deletes file after sync adopts it under a foreign active owner. Evidenza: file-layer compiled production probe. Condizione: Sync payload must name fresh PN staging basename; ordinary-client exposure not demonstrated. Stato: queued for source adjudication/fix after all frozen reviews.
- **R1-C8-01 — P2**, C8: Whole database backup includes queued jobs and sync_state/sync_origin private identity rows. Evidenza: production BackupArchive.write against synthetic production-schema DB; private rows survive. Stato: queued for backup contract adjudication/content-only snapshot after frozen reviews.
- **PN5-F1 — P2**, PN5: Expected-source reservation excludes all SHARE/CLIPBOARD kinds, so genuine null-reference binary/archived originals are not discovered on ready relay. Evidenza: production export planner, valid SHA/8-byte original/no donor/relay-ready; required-recovery assertion fails. Stato: confirmed source predicate; queued correction after frozen reviews.
- **R4-F1 — P2**, C1: Mistral generic parsing can throw Empty before validating typed text; runner treats it as exact silence and clears sending barrier. Evidenza: A valid then B null/segments-empty through production parser/runner/cache/save; whitespace-only control lost. Stato: confirmed parsing order; queued correction after frozen reviews.
- **R4-F2 — P3**, C3: Provisional10min429 fallback cannot shrink for this response readable2s/60s Retry-After because durable extend only takes max. Evidenza: production HTTP/cooldown with fresh mocked429 stores600004ms retryAfterKnown=false; long control passes. Stato: queued per-response provisional replacement preserving independent earlier cooldowns.
- **R5-F1 — P2**, C4: Alignment completion and defer check/update pairs can overwrite cancelled durable state and requeue after restart. Evidenza: production persistent queue probes cancelled->deferred->queued, reopening confirms due. Stato: confirmed atomic terminal-state mutation needed after frozen reviews.
- **R5-F3 — P3 conditional**, C5: Mismatched aligner metadata is assigned before language validation and then reused on next window without validation. Evidenza: guarded 2-window en request with it metadata mock measures second token using rejected pair. Condizione: Real pinned loader returning inconsistent metadata not demonstrated. Stato: queued bounded guard correction after frozen reviews.
- **R6-01 — P2**, PN6/C6; related PN1: Restricted recording PN export silently removes unprovable legacy refinement history and changes selection with no warning. Evidenza: actual gather with DAO proxies -> PN writer/read, one session RAW+selected refined/no sourceJSON: only RAW returned; whole note preserves both. Stato: confirmed; preserve privacy fence and reject/disclose unprovable reduction, explicit full-note alternative; never silently broaden.
- **R6-02 — P3**, C6: Recording cloud consent adds Groq-specific wording for Mistral/OpenRouter too. Evidenza: source/resource message composition, no rendered UI. Stato: queued provider-neutral/resolved-name text fix.
- **R7-01 — P2**, C7: Android/Worker audio metadata merge splices minute filename origin with source-only media_metadata/second tuple into invented precise provenance. Evidenza: production Android helper + Worker function. Stato: confirmed; select coherent observation with native quality policy, matching Android/Worker regression.
- **R7-02 — P3**, C7: Markdown claims no associated timings despite valid measured cloud overlay with exported clocks and no coarse segments. Evidenza: actual Markdown writer. Stato: queued effective timing-evidence notice correction.

R2-F1 richiede che il payload sync conosca il basename di staging appena generato: esposizione ordinaria non dimostrata; il difetto di composizione delle protezioni è comunque riprodotto al livello file. R1-C8-01 non dimostra esposizione di credenziali reali. R5-F3 non dimostra che il loader reale restituisca metadata incompatibili. La causa dell’import fallito dell’amico resta sconosciuta.

## Evidenze e limiti

Manifest di verifica esterno: `%LOCALAPPDATA%/PampaNotes/combined-verification/8233cbb/verification-manifest.json`, SHA256 `d79655f19823b76f451950732e7dfada1ce0390b087704fe1552437276a32a48`: 701 input e 228 artefatti. Core1497 pass+1skip opzionale, app73pass; lint0errori/136warning. Gradle SUCCESSFUL2m14s, cinque task eseguiti e460UP-TO-DATE. Gli AndroidTest sono compilati/package, non eseguiti. Worker44, SQL17, TypeScript exit0 e companion88guardati sono prove eseguite su input di perimetro verificati byte-identici; i log copiati non rappresentano riesecuzioni aggiuntive. I tentativi falliti e le correzioni delle fixture restano archiviati separatamente, senza rimozione di asserzioni.

Runtime Android/Room/WorkManager/UI, dispositivi/emulatori, provider/cloud/D1/R2 live e audio/modelli/GPU/acustica sono USER-WAIVED, NOT EXECUTED. Non diventano nuovi gate. Le prove dei reviewer sono distinte dalle evidenze parent nei report verbatim.

## Report indipendenti verbatim

- [R1](./R1-review.md) — PN1, C8.
- [R2](./R2-review.md) — PN2, PN7.
- [R3](./R3-review.md) — PN3, PN4.
- [R4](./R4-review.md) — C1, C2, C3.
- [R5](./R5-review.md) — C4, C5.
- [R6](./R6-review.md) — PN6, C6.
- [R7](./R7-review.md) — C7.
- [R8](./R8-review.md) — PN5.
- [R9](./R9-review.md) — PN8, C9.

