package dev.pampa.pampanotes.core.transcription

import java.io.File

/** Cosa un servizio di trascrizione sa fare, e dove si ferma. */
data class TranscriptionCapabilities(
  /** Il tetto per una singola richiesta. Null quando non c'e': il server di casa accetta tutto. */
  val maxUploadBytes: Long?,
  /** Restituisce i tempi dei segmenti, non solo il testo. Senza, il lettore non puo' seguire. */
  val supportsSegments: Boolean,
  /** Serve tagliare un audio lungo prima di mandarlo. */
  val needsChunking: Boolean,
  val supportsAutoLanguage: Boolean = true,
  /**
   * Le estensioni che il servizio prende cosi' come sono. Null: tutto quello che arriva. Un file
   * fuori elenco non passa per la via breve anche se ci starebbe: si decodifica e si ricodifica.
   */
  val acceptedExtensions: Set<String>? = null,
  /**
   * Il pezzo piu' lungo che il servizio vuole, se ne vuole uno. Groq lo lascia alle impostazioni
   * (`chunkMinutes`); il computer di casa lo dichiara qui quando l'utente ne ha scelto uno.
   */
  val maxChunkMinutes: Int? = null,
) {
  /** Il file si puo' mandare com'e'? Decide l'estensione del nome, che e' quello che il servizio guarda. */
  fun acceptsAsIs(fileName: String): Boolean {
    val accepted = acceptedExtensions ?: return true
    return fileName.substringAfterLast('.', "").lowercase() in accepted
  }
}

/** Cosa si chiede a una trascrizione. */
data class TranscribeRequest(
  val model: String,
  /** ISO 639-1, oppure null per lasciare che il modello indovini. */
  val language: String? = null,
  /**
   * Il vocabolario: nomi propri e termini che il modello non conoscerebbe.
   *
   * Whisper lo usa come se fosse il testo appena precedente, quindi funziona meglio come elenco di
   * parole che come istruzione. Vale al massimo 224 token, che sono circa 800 caratteri.
   */
  val prompt: String? = null,
  val temperature: Double = 0.0,
  /**
   * «Chi parla»: chiedere al computer di casa di separare le voci (`diarize=1`). Lo decide
   * [dev.pampa.pampanotes.core.repo.TranscriptionRepository.requestFor] dalla preferenza e dalla
   * sezione della nota; [TranscriptionRunner] lo manda solo a un companion che dichiara
   * [CompanionFeatures.DIARIZE], e mai a Groq, che non saprebbe cosa farne.
   */
  val diarize: Boolean = false,
)

/** Un pezzo di testo con i suoi tempi, come lo restituisce il servizio. */
data class RawSegment(
  val startMs: Long,
  val endMs: Long,
  val text: String,
  /** Quanto il modello crede che qui non ci fosse voce: sopra 0.9 di solito e' un'allucinazione. */
  val noSpeechProb: Float? = null,
  val avgLogProb: Float? = null,
  /**
   * Le parole con i loro tempi, quando il servizio le da'.
   *
   * Vuota di default perche' Groq non le da' mai: e' l'allineamento fonetico di WhisperX che le
   * produce. Chi non le ha se le fa stimare da [WordTimings], cosi' la UI ha una strada sola.
   */
  val words: List<RawWord> = emptyList(),
  /**
   * La voce che lo dice («SPEAKER_00»), quando il computer di casa ha separato le voci. L'etichetta
   * vale solo dentro la stessa registrazione: la schermata la traduce in «Voce 1» parte per parte
   * (vedi [TranscriptParagraphs]).
   */
  val speaker: String? = null,
)

/** Una parola con i suoi tempi. I millisecondi sono nello stesso riferimento del segmento. */
data class RawWord(
  val startMs: Long,
  val endMs: Long,
  val text: String,
)

data class TranscriptResult(
  val text: String,
  val segments: List<RawSegment>,
  val language: String?,
  val durationMs: Long?,
  /** Il computer di casa ha tenuto il file nel suo archivio (`archive=1`): la parte si segna archiviata. */
  val archived: Boolean = false,
  /** In quanti pezzi il computer ha diviso la registrazione da se' (`max_minutes`); null se non l'ha detto. */
  val serverChunks: Int? = null,
  /** Il tetto dei pezzi usato davvero, in minuti (0 = intera): con `max_minutes=auto` e' la scelta del computer. */
  val maxMinutesUsed: Int? = null,
)

/** A che punto e' l'invio di un file: la barra di avanzamento ha bisogno di questo, non di uno spinner. */
data class UploadProgress(val sentBytes: Long, val totalBytes: Long) {
  val fraction: Float get() = if (totalBytes <= 0) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * Cosa sta facendo il computer di casa con l'audio che ha ricevuto, come lo dice `GET /v1/jobs/<id>`.
 *
 * I codici sono quelli del companion; uno che l'app non conosce (un companion piu' nuovo) si
 * ignora invece di rompere qualcosa.
 */
enum class RemoteStage(val code: String) {
  RECEIVED("received"),
  QUEUED("queued"),
  DECODING("decoding"),
  LOADING_MODEL("loading_model"),
  TRANSCRIBING("transcribing"),
  ALIGNING("aligning"),
  /** «Chi parla»: separa le voci, dopo l'allineamento e solo se glielo si e' chiesto. */
  DIARIZING("diarizing"),
  DONE("done"),
  FAILED("failed"),
  ;

  val finished: Boolean get() = this == DONE || this == FAILED

  companion object {
    fun fromCode(code: String?): RemoteStage? = entries.firstOrNull { it.code == code }
  }
}

/**
 * A che punto e' il lavoro dall'altra parte.
 *
 * [fraction] vale dentro lo stadio (la trascrizione da 0 a 1, poi l'allineamento da 0 a 1): il
 * conto sull'intera sessione lo fa [TranscriptionRunner], che sa quante registrazioni ci sono.
 */
data class RemoteProgress(
  val stage: RemoteStage,
  val fraction: Float = 0f,
  /** In fila: 2 vuol dire «c'e' una lezione davanti», quella che il computer sta facendo adesso. */
  val position: Int? = null,
  val audioSeconds: Double? = null,
  /** Quanto manca allo stadio corrente, quando il computer ha abbastanza dati per dirlo. */
  val etaSeconds: Double? = null,
  /** "cuda" o "cpu": la stessa lezione sul processore dura dieci volte tanto, e va detto. */
  val device: String? = null,
  val detail: String? = null,
  /**
   * Il pezzo su cui sta lavorando, da uno, quando la registrazione la divide lui (`max_minutes`):
   * il telefono non ha tagliato niente e non saprebbe dirlo da se'.
   */
  val chunk: Int? = null,
  val chunks: Int? = null,
)

/**
 * «Il testo che arriva a pezzi»: i segmenti dei pezzi che il computer di casa ha gia' finito, mentre
 * fa il resto (`GET /v1/jobs/<id>/partial`, solo con [CompanionFeatures.PARTIAL]).
 *
 * **Provvisori**: la risposta finale passa ancora dal filtro sulla lezione intera e dalla separazione
 * delle voci, e vince lei. Non si salvano e non si sincronizzano: servono solo a leggere prima.
 *
 * @property from il primo pezzo che questi segmenti coprono, da zero: 0 vuol dire «da capo», e chi li
 *   raccoglie butta quello che aveva (un secondo tentativo, un lavoro agganciato a quello di un altro).
 * @property piecesDone quanti pezzi sono finiti; la domanda dopo parte da qui.
 * @property segments i segmenti di quei pezzi, nel tempo della **registrazione** (della parte).
 */
data class RemotePartial(
  val from: Int,
  val piecesDone: Int,
  val piecesTotal: Int,
  val segments: List<RawSegment>,
)

/** Se il server risponde, e cosa dice di se'. */
data class EndpointHealth(
  val reachable: Boolean,
  val latencyMs: Long,
  val models: List<String> = emptyList(),
  val serverName: String? = null,
  val detail: String? = null,
  /** Cosa il companion sa fare oltre all'API di OpenAI (vedi [CompanionFeatures]). Vuoto: un server qualsiasi, o un companion vecchio. */
  val features: Set<String> = emptySet(),
)

/**
 * Chi trasforma un audio in testo.
 *
 * Due implementazioni con lo stesso contratto: Groq nel cloud con la chiave dell'utente, e un
 * endpoint compatibile OpenAI — di regola il computer di casa con WhisperX. La differenza che conta
 * sta in [capabilities]: il primo ha un tetto per richiesta e va servito a pezzi, il secondo si
 * prende l'ora intera.
 */
interface TranscriptionProvider {
  /** "groq" oppure "custom": lo stesso identificatore con cui i lavori si mettono in coda. */
  val id: String

  val capabilities: TranscriptionCapabilities

  suspend fun listModels(): List<String>

  suspend fun health(): EndpointHealth

  /**
   * @param onProgress i byte che partono.
   * @param onRemote quello che il servizio sta facendo, finito il caricamento. Solo il computer di
   *   casa lo racconta: Groq risponde in secondi e non ha niente da dire nel frattempo.
   */
  suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit = {},
    onRemote: (RemoteProgress) -> Unit = {},
  ): TranscriptResult
}

/**
 * Le voci di `features` in `GET /health` del companion: cose che un server compatibile OpenAI
 * qualsiasi non fa, e che quindi si chiedono solo a chi le dichiara.
 */
object CompanionFeatures {
  /** Trascrive un file del suo archivio, indicato con lo sha256: niente da caricare. */
  const val BY_REF = "by_ref"

  /** Tiene nell'archivio il file appena caricato (`archive=1`), invece di buttarlo a fine lavoro. */
  const val ARCHIVE_UPLOAD = "archive_upload"

  /** Divide da se' una registrazione lunga (`max_minutes`), con la stessa regola di `ChunkPolicy`. */
  const val SERVER_CHUNKS = "server_chunks"

  /**
   * Separa le voci (`diarize=1`): ogni segmento torna con `speaker`. C'e' solo quando sul computer
   * c'e' il token di Hugging Face che il modello chiede.
   */
  const val DIARIZE = "diarize"

  /**
   * Da' il testo dei pezzi gia' finiti mentre fa il resto (`GET /v1/jobs/<id>/partial`): la sessione
   * lo mostra «in arrivo» invece di aspettare la fine di una lezione lunga. Vedi [RemotePartial].
   */
  const val PARTIAL = "partial"
}

/** Come caricare un file al companion quando sa fare di piu' di un server qualsiasi. */
data class CompanionUpload(
  /** L'impronta del file, minuscola: e' il nome con cui l'archivio lo terra'. */
  val sha256: String?,
  /** Il nome che l'archivio mostrera' (quello originale della registrazione). */
  val name: String?,
  /** Tenerlo nell'archivio dopo averlo trascritto. */
  val archive: Boolean,
  /** Il tetto dei pezzi, che il computer applica da se'; null: intero. */
  val maxMinutes: Int?,
)

/**
 * Il computer di casa che lavora da se': la registrazione la prende dal suo archivio, e se va divisa
 * la divide lui. Lo implementa [OpenAiCompatProvider]; [TranscriptionRunner] lo usa solo se il
 * companion dichiara [CompanionFeatures.BY_REF].
 */
interface CompanionTranscription {
  /** Le [CompanionFeatures] del companion, lette da `/health` una volta e poi ricordate. Vuoto se non risponde. */
  suspend fun features(): Set<String>

  /**
   * Trascrive il file che l'archivio del computer tiene sotto [sha256], senza caricare niente.
   *
   * @throws TranscriptionError.BlobMissing se l'archivio non ce l'ha.
   * @throws TranscriptionError.OwnerOnly se chi chiede e' un ospite.
   */
  suspend fun transcribeByRef(
    sha256: String,
    request: TranscribeRequest,
    maxMinutes: Int?,
    // Prima di [onRemote], che resta l'ultimo: chi lo passa come lambda in coda continua a passare lui.
    onPartial: (RemotePartial) -> Unit = {},
    onRemote: (RemoteProgress) -> Unit = {},
  ): TranscriptResult

  /**
   * Carica e trascrive, chiedendo al computer di tenerlo e di dividerlo da se' ([upload]).
   *
   * @param onPartial il testo dei pezzi gia' finiti, se il computer lo sa dare ([CompanionFeatures.PARTIAL]).
   */
  suspend fun transcribeUpload(
    file: File,
    mime: String,
    request: TranscribeRequest,
    upload: CompanionUpload,
    onProgress: (UploadProgress) -> Unit = {},
    onPartial: (RemotePartial) -> Unit = {},
    onRemote: (RemoteProgress) -> Unit = {},
  ): TranscriptResult
}
