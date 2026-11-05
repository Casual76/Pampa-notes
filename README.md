# Pampa Notes

Gli appunti che hai già preso, pronti da dare a un'IA.

Pampa Notes non è un'app per prendere appunti: è quella che li **raccoglie**. Importi il testo e le
registrazioni che hai fatto altrove — Samsung Notes, un registratore, un PDF, un documento Word — li
organizzi in cartelle per materia, e ne ricavi un pacchetto Markdown ordinato da dare a Claude,
ChatGPT o Gemini come fonte per riassunti, domande e ripasso.

## Cosa fa

- **Importa** i pacchetti ZIP condivisi da Pampa Notes, testo, Markdown, PDF, Word, gli archivi `.sdocx` di Samsung Notes e audio in tutti i
  formati comuni (m4a, mp3, wav, ogg, flac, opus). Dal selettore file, dalla condivisione di
  un'altra app, o incollando.
- **Riceve e aggiorna note condivise**: i nuovi ZIP conservano cartelle, appunti, pagine a mano,
  trascrizioni e tempi audio. Le registrazioni sono riproducibili quando incluse nel pacchetto;
  i vecchi ZIP si possono importare con i limiti indicati nell'app. Una nuova versione della
  stessa nota propone l'aggiornamento, conservando le aggiunte locali; si possono anche creare copie.
- **Trascrive** le registrazioni con Whisper, e bene: Groq nel cloud con la tua chiave, oppure il tuo
  computer in rete locale con WhisperX (il server sta in `companion/`, sono due comandi). Un'ora di
  lezione diventa un testo con i tempi, e il lettore segue la trascrizione mentre scorre.
- **Raggruppa** le registrazioni come sono andate davvero: se la registrazione si è interrotta e l'hai
  ripresa, le due parti diventano una trascrizione sola; un altro giorno è un'altra sessione.
- **Esporta** un bundle con le note, le trascrizioni, un indice e un file di istruzioni che spiega
  all'assistente come leggere il materiale e come citarlo.

Tutto resta sul telefono. Nessun account, nessun server, e il backup lo scrivi nella cartella che
scegli tu.

## Requisiti

Android 8.0 o più recente. Per la trascrizione serve una chiave [Groq](https://console.groq.com)
(gratuita) oppure un computer in rete con il server di `companion/`.

## Per chi sviluppa

Vedi [CLAUDE.md](CLAUDE.md): comandi, architettura, modello dei dati.

L'app è costruita sul [Fluid Engine](https://github.com/Casual76/fluid-engine) (3.0.0), agganciato come
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
