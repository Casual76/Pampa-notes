package dev.pampa.pampanotes.core.transcription

/**
 * La fase di un lavoro come si salva in `jobs.phase`: un codice compatto, non una frase.
 *
 * Una frase salvata nel database resterebbe nella lingua in cui l'app girava quando il lavoro e'
 * partito; il codice lo traducono la notifica (`JobPhaseText`) e le schermate (`jobPhaseText`), che
 * leggono entrambe da qui. Scrittura e lettura stanno nello stesso posto perche' un campo aggiunto
 * da una parte e dimenticato dall'altra e' un «Pezzo 0 di 1» sullo schermo.
 *
 * I formati, campi separati da `:` (i campi in coda sono facoltativi, e un lettore vecchio li ignora):
 *
 * | fase | formato |
 * |---|---|
 * | [Preparing] | `preparing:<parte>/<parti>:<percento>` |
 * | [Uploading] | `uploading:<pezzo>/<pezzi>:<percento>[:<parte>/<parti>]` |
 * | [Transcribing] | `transcribing:<pezzo>/<pezzi>[:<parte>/<parti>]` |
 * | [Remote] | `remote:<stadio>:<parte>/<parti>:<percento>[:<posizione>[:<pezzo>/<pezzi>[:<eta s>[:<device>]]]]` |
 * | [Refining] | `refining:<pezzo>/<pezzi>` |
 * | [Waiting] | `waiting:<secondi>` |
 * | [Endpoint] | `endpoint` |
 * | [Until] | `until:<epoch ms>` |
 * | [Stitching] | `stitching` |
 * | [NeedsApp] | `app` |
 * | [Elsewhere] | `elsewhere:<dispositivo>` (il nome e' tutto quello che segue, `:` compresi) |
 *
 * Parti e pezzi contano da uno. Un campo facoltativo vuoto (`remote:queued:2/6:0::1/1`) vale assente.
 */
sealed interface JobPhase {

  fun encode(): String

  /** Il percento del passo in corso, per la seconda barra; null quando non c'e' un passo misurabile. */
  val stepPercent: Int? get() = null

  data class Preparing(val part: Int, val parts: Int, val percent: Int) : JobPhase {
    override fun encode() = "preparing:$part/$parts:$percent"
    override val stepPercent: Int get() = percent
  }

  data class Uploading(
    val chunk: Int,
    val chunks: Int,
    val percent: Int,
    val part: Int = 1,
    val parts: Int = 1,
  ) : JobPhase {
    override fun encode() = "uploading:$chunk/$chunks:$percent:$part/$parts"

    /** Caricato tutto, e il servizio non dice niente: si aspetta la risposta. */
    val awaiting: Boolean get() = percent >= 100
    override val stepPercent: Int? get() = percent.takeUnless { awaiting }
  }

  data class Transcribing(val chunk: Int, val chunks: Int, val part: Int = 1, val parts: Int = 1) : JobPhase {
    override fun encode() = "transcribing:$chunk/$chunks:$part/$parts"
  }

  data class Remote(
    val stage: RemoteStage,
    val part: Int,
    val parts: Int,
    val percent: Int,
    val position: Int? = null,
    val chunk: Int = 1,
    val chunks: Int = 1,
    val etaSeconds: Int? = null,
    val device: String? = null,
  ) : JobPhase {
    override fun encode(): String = buildString {
      append("remote:").append(stage.code).append(':').append(part).append('/').append(parts).append(':').append(percent)
      append(':').append(position?.toString().orEmpty())
      append(':').append(chunk).append('/').append(chunks)
      append(':').append(etaSeconds?.toString().orEmpty())
      append(':').append(device?.filter { it.isLetterOrDigit() }.orEmpty())
    }

    override val stepPercent: Int?
      get() = percent.takeIf { stage == RemoteStage.TRANSCRIBING || stage == RemoteStage.ALIGNING }
  }

  data class Refining(val chunk: Int, val chunks: Int) : JobPhase {
    override fun encode() = "refining:$chunk/$chunks"
  }

  data class Waiting(val seconds: Int) : JobPhase {
    override fun encode() = "waiting:$seconds"
  }

  data object Endpoint : JobPhase {
    override fun encode() = "endpoint"
  }

  data class Until(val atMillis: Long) : JobPhase {
    override fun encode() = "until:$atMillis"
  }

  data object Stitching : JobPhase {
    override fun encode() = "stitching"
  }

  /**
   * Pronto a partire, ma Android non lascia partire il servizio in primo piano finche' l'app non si
   * apre (da Android 12, un worker svegliato in background). Non e' un'attesa di qualcun altro: e'
   * un tocco di chi legge.
   */
  data object NeedsApp : JobPhase {
    override fun encode() = "app"
  }

  /**
   * Un altro dispositivo sta trascrivendo la stessa sessione ([device], il suo nome nel sync): il
   * lavoro resta in fila invece di partire, e la trascrizione arriva col sync.
   */
  data class Elsewhere(val device: String) : JobPhase {
    override fun encode() = "elsewhere:$device"
  }

  companion object {
    /** Null per una fase assente o che non si capisce: chi legge ripiega sullo stato del lavoro. */
    fun parse(raw: String?): JobPhase? {
      if (raw.isNullOrBlank()) return null
      val fields = raw.split(':')
      fun field(index: Int): String? = fields.getOrNull(index)?.takeIf { it.isNotEmpty() }
      fun int(index: Int): Int? = field(index)?.toIntOrNull()
      fun pair(index: Int): Pair<Int, Int>? {
        val parts = field(index)?.split('/') ?: return null
        val first = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val second = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return first.coerceAtLeast(1) to second.coerceAtLeast(1)
      }
      return when (fields.first()) {
        "preparing" -> {
          val (part, parts) = pair(1) ?: (1 to 1)
          Preparing(part, parts, int(2) ?: 0)
        }
        "uploading" -> {
          val (chunk, chunks) = pair(1) ?: (1 to 1)
          val (part, parts) = pair(3) ?: (1 to 1)
          Uploading(chunk, chunks, int(2) ?: 0, part, parts)
        }
        "transcribing" -> {
          val (chunk, chunks) = pair(1) ?: (1 to 1)
          val (part, parts) = pair(2) ?: (1 to 1)
          Transcribing(chunk, chunks, part, parts)
        }
        "remote" -> {
          val stage = RemoteStage.fromCode(field(1)) ?: return null
          val (part, parts) = pair(2) ?: (1 to 1)
          val (chunk, chunks) = pair(5) ?: (1 to 1)
          Remote(stage, part, parts, int(3) ?: 0, int(4), chunk, chunks, int(6), field(7))
        }
        "refining" -> {
          val (chunk, chunks) = pair(1) ?: (1 to 1)
          Refining(chunk, chunks)
        }
        "waiting" -> Waiting(int(1) ?: 0)
        "endpoint" -> Endpoint
        "until" -> field(1)?.toLongOrNull()?.let(::Until)
        "stitching" -> Stitching
        "app" -> NeedsApp
        // Il nome di un dispositivo puo' contenere di tutto, anche i due punti: e' il resto della riga.
        "elsewhere" -> raw.substringAfter(':', "").takeIf { it.isNotBlank() }?.let(::Elsewhere)
        else -> null
      }
    }

    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()
  }
}
