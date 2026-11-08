# Pampa Notes

Appunti e registrazioni, organizzati e pronti da condividere.

Pampa Notes non è un'app per prendere appunti: è quella che li **raccoglie**. Importi il testo e le
registrazioni che hai fatto altrove — Samsung Notes, un registratore, un PDF, un documento Word — li
organizzi in cartelle per materia, e ne ricavi un pacchetto Markdown ordinato da dare a Claude,
ChatGPT o Gemini come fonte per riassunti, domande e ripasso.

## Cosa fa

- **Importa** i file `.pn` e i precedenti pacchetti ZIP di Pampa Notes, testo, Markdown, PDF, Word, gli archivi `.sdocx` di Samsung Notes e audio in tutti i
  formati comuni (m4a, mp3, wav, ogg, flac, opus). Dal selettore file, dalla condivisione di
  un'altra app, o incollando.
- **Trasferisce i dati con un file `.pn`**: conserva cartelle, appunti, pagine a mano, registrazioni,
  tutte le versioni delle trascrizioni, la versione selezionata e i tempi disponibili. Comprime
  senza ricodificare l'audio e salva una volta i file con gli stessi byte, mantenendo distinte le
  registrazioni e le fonti. Segnala gli allegati mancanti prima dell'esportazione. I precedenti ZIP
  restano importabili entro i limiti indicati nell'app. Una nuova versione della
  stessa nota propone l'aggiornamento, conservando le aggiunte locali; si possono anche creare copie.
- **Trascrive** le registrazioni con Groq, Mistral o OpenRouter nel cloud, usando la tua chiave API, oppure
  con WhisperX sul tuo computer (il server opzionale sta in `companion/`). I tempi audio dipendono
  dal modello: una risposta solo testuale resta una trascrizione leggibile, senza tempi inventati.
  Quando il servizio restituisce i tempi, il lettore segue la trascrizione mentre scorre.
- **Separa le registrazioni dagli appunti**: nella raccolta Registrazioni apri direttamente una
  sessione, il suo audio e la trascrizione. Puoi organizzarle in cartelle ed esportare soltanto
  quelle selezionate, con istruzioni adatte a registrazioni personali.
- **Raggruppa** le registrazioni come sono andate davvero: se la registrazione si è interrotta e l'hai
  ripresa, le due parti diventano una trascrizione sola; un altro giorno è un'altra sessione.
- **Esporta** un bundle con le note, le trascrizioni, un indice e un file di istruzioni che spiega
  all'assistente come leggere il materiale e come citarlo.
- **Aggiunge data e ora del parlato**, se richiesto nell'export e se l'origine della registrazione
  le rende conoscibili. Una data senza ora o un fuso sconosciuto non diventano un orario inventato.

Puoi importare, organizzare ed esportare dal telefono senza account, chiavi API o computer.
Il backup locale si scrive nella cartella che scegli tu. La trascrizione cloud e la
sincronizzazione sono funzioni opzionali che inviano dati ai rispettivi servizi.

## Cloud e computer opzionale

In **Impostazioni → Servizi** scegli il modulo Groq, Mistral, OpenRouter o computer per configurarlo;
in **Trascrizione** scegli chi trascrive per impostazione predefinita. Configurare una chiave
non avvia una trascrizione. L'audio viene inviato quando chiedi di trascriverlo oppure dopo
un import se **Trascrivi dopo import** è attivo: nei nuovi avvii è spento e una scelta già salvata viene conservata. Costi, credito disponibile,
modelli e limiti dipendono dal provider e dal tuo piano: l'app non promette API gratuite o illimitate.
Ogni trascrizione mantiene il provider e il modello scelti: un limite di quota non la sposta
automaticamente su un altro servizio. Quando il provider richiede un'attesa, il lavoro resta in
coda e riprova rispettando l'attesa disponibile. La modalità **Solo il computer di casa** esclude
tutti i provider cloud, anche per le scelte automatiche.

Se una trascrizione cloud non contiene tempi delle parole, puoi allineare il testo con il
computer di casa, usando un companion aggiornato con il supporto all'allineamento. Il testo
originale del servizio resta conservato; l'allineamento aggiunge i tempi misurati senza chiedere
un'altra trascrizione cloud. Se il computer non è disponibile il lavoro può aspettare. Le parole
senza un tempo misurato restano indicate come tali.

In **Trascrizione → Costi e autorizzazioni cloud** trovi budget giornaliero o mensile, limite per
trascrizione, previsioni e registro degli importi confermati o ancora incerti. Le opzioni avanzate
gestiscono prezzi sconosciuti e riconciliazione; lo stato della chiave mostra anche i limiti impostati
su OpenRouter. Dal menu di una cartella puoi consentire, bloccare o ereditare l'uso del cloud e
scegliere un modello OpenRouter per quella cartella. Le regole vengono ricontrollate prima di ogni
invio, anche per importazioni automatiche e nuovi tentativi. Budget e regole sono locali al dispositivo;
il budget controlla la spesa prevista, mentre i limiti della chiave si configurano su OpenRouter.

La sincronizzazione con account conserva testi, cartelle e trascrizioni: non è una copia completa
dei file audio e degli originali. Il companion può conservarli sul computer e renderli scaricabili
quando è raggiungibile.

In **Impostazioni → Sincronizzazione → Trasferisci file** puoi inviare tutti gli audio e gli originali
presenti sul dispositivo, ricevere adesso, riprendere o interrompere il lavoro e controllare gli esiti
dei singoli file. Dal menu di una nota, **Invia file agli altri dispositivi** limita l’invio a quella
nota. La ricezione dopo il sync è opzionale e inizialmente spenta; nessun sync invia nuovi file.
Il **trasferimento temporaneo per dieci minuti** passa dal backend in `worker/`, tra dispositivi
dello stesso account (contratto in [worker/RELAY_API.md](worker/RELAY_API.md)). Sincronizza prima il
testo su entrambi, tienili online e ricevi entro la scadenza fissa, che comprende il caricamento.
Android può ritardare il lavoro: se la finestra scade, scegli esplicitamente di inviare di nuovo
dal dispositivo che conserva l’originale. Ogni file può pesare al massimo 256 MiB; per account il
limite temporaneo è 512 MiB e otto trasferimenti, inclusi quelli in attesa di pulizia.

Aprendo un originale o scaricando una registrazione, l’app cerca prima il trasferimento temporaneo
e poi, se disponibile, l’archivio del computer. La finestra riguarda audio e file originali, non i testi
sincronizzati: dopo la scadenza il download è negato; la cancellazione fisica passa dalla pulizia
programmata, con possibili ritardi e nuovi tentativi. Non è un archivio o un backup permanente;
va conservata una copia originale e un trasferimento scaduto richiede un nuovo invio dal dispositivo
che possiede il file. L'accesso di produzione e l'esecuzione della pulizia richiedono il deployment
e la configurazione del Worker.
Build integrata e interoperabilità del client Android con il Worker locale sono state eseguite;
evidenze e limiti delle prove sono in [CLOUD_VERIFICATION.md](CLOUD_VERIFICATION.md). Non è dichiarato
un deployment o un test su API a pagamento. La fixture locale opt-in è `worker/tests/android-relay-server.mjs`:
avviala con Node, imposta `PAMPA_RELAY_FIXTURE_URL` all’indirizzo stampato e, con Gradle disponibile,
esegui `:core:testDebugUnitTest --tests "*RelayWorkerInteropTest"`.

## Requisiti

Android 8.0 o più recente. Per la trascrizione cloud servono internet e una chiave
[Groq](https://console.groq.com), [Mistral](https://console.mistral.ai) o
[OpenRouter](https://openrouter.ai), con accesso al modello scelto.
In alternativa puoi usare un computer raggiungibile con il server di `companion/`.

## Per chi sviluppa

Vedi [CLAUDE.md](CLAUDE.md): comandi, architettura, modello dei dati.

L'app è costruita sul [Fluid Engine](https://github.com/Casual76/fluid-engine) (3.0.5), agganciato come
submodule. Dopo il clone:

```bash
git submodule update --init --recursive
```

## Licenza

Pampa Notes è software libero: si distribuisce sotto la **GNU General Public License, versione 3**
(testo completo in [`LICENSE`](LICENSE)). Chi riceve l'APK ha diritto ai sorgenti, che sono questo
repository, pubblico: <https://github.com/Casual76/Pampa-notes>. Si possono leggere, modificare e
ridistribuire alle condizioni della GPL-3.0, con la stessa licenza. La licenza è quella del
repository intero, quindi anche del server `companion/` e del Worker in `worker/`.

**Perché GPL-3.0.** Dalla 3.0 il Fluid Engine contiene codice di
[LibrePods](https://github.com/librepods-org/librepods) (GPL-3.0): il comportamento dei pulsanti e delle
pagine non è più riscritto, è portato. Un'app che distribuisce l'engine è un'opera derivata, e va
distribuita con la stessa licenza. È la regola di tutte le app Pampa che usano l'engine.

Le opere di altri che l'APK porta con sé (il vetro di Kyant, Apache-2.0; i caratteri Inter, SIL OFL 1.1;
LibrePods) sono elencate nella pagina **Impostazioni › Informazioni** dell'app, con gli avvisi che le
loro licenze chiedono; i testi sono in [`engine/LICENSES/`](engine/LICENSES).
