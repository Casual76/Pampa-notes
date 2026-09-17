# Pampa Notes

Gli appunti che hai già preso, pronti da dare a un'IA.

Pampa Notes non è un'app per prendere appunti: è quella che li **raccoglie**. Importi il testo e le
registrazioni che hai fatto altrove — Samsung Notes, un registratore, un PDF, un documento Word — li
organizzi in cartelle per materia, e ne ricavi un pacchetto Markdown ordinato da dare a Claude,
ChatGPT o Gemini come fonte per riassunti, domande e ripasso.

## Cosa fa

- **Importa** testo, Markdown, PDF, Word, gli archivi `.sdocx` di Samsung Notes e audio in tutti i
  formati comuni (m4a, mp3, wav, ogg, flac, opus). Dal selettore file, dalla condivisione di
  un'altra app, o incollando.
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

L'app è costruita sul [Fluid Engine](https://github.com/Casual76/fluid-engine), agganciato come
submodule. Dopo il clone:

```bash
git submodule update --init --recursive
```
