package dev.pampa.pampanotes.ui.importing

import android.content.Intent
import android.net.Uri
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quello che c'e' da importare, in attesa che il wizard lo raccolga.
 *
 * Perche' non passa dalla rotta: un URI di condivisione e' lungo, va codificato due volte per
 * starci dentro, e soprattutto **scade**. Una rotta la si ritrova identica dopo la morte del
 * processo, l'URI no: il wizard riaperto troverebbe permessi revocati e file che non si aprono.
 * Qui vive in memoria, e se il processo muore la richiesta se ne va con lui, che e' il
 * comportamento giusto.
 */
@Singleton
class ImportRequestHolder @Inject constructor() {
  private val _pending = MutableStateFlow<ImportRequest?>(null)
  val pending: StateFlow<ImportRequest?> = _pending.asStateFlow()

  fun offer(request: ImportRequest) {
    _pending.value = request
  }

  /** Legge e svuota: la stessa richiesta non deve importarsi due volte dopo una rotazione. */
  fun take(): ImportRequest? = _pending.value.also { _pending.value = null }

  fun clear() {
    _pending.value = null
  }
}

data class ImportRequest(
  val uris: List<Uri> = emptyList(),
  val text: String? = null,
  /** La nota in cui importare, quando si e' partiti da dentro una nota. */
  val intoNoteId: String? = null,
) {
  val isEmpty: Boolean get() = uris.isEmpty() && text.isNullOrBlank()

  companion object {
    /** I MIME che il selettore file propone: gli stessi dichiarati nel manifest per la condivisione. */
    val PICKER_MIME_TYPES = arrayOf(
      "text/*",
      "application/pdf",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "audio/*",
      "image/*",
      "application/zip",
      "application/octet-stream",
    )

    /** La richiesta dentro un intent di condivisione, o null se l'intent non ne porta una. */
    fun fromIntent(intent: Intent?): ImportRequest? {
      intent ?: return null
      val single = intent.action == Intent.ACTION_SEND
      val multiple = intent.action == Intent.ACTION_SEND_MULTIPLE
      if (!single && !multiple) return null

      val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
      val uris = if (single) listOfNotNull(streamOf(intent)) else streamsOf(intent)
      if (text == null && uris.isEmpty()) return null

      // Gli extra si tolgono: una rotazione ricrea l'Activity con lo stesso intent, e senza questo
      // ogni giro riproporrebbe lo stesso import.
      intent.removeExtra(Intent.EXTRA_TEXT)
      intent.removeExtra(Intent.EXTRA_STREAM)

      return ImportRequest(uris = uris.take(MAX_ITEMS), text = text)
    }

    /** Oltre questo il wizard diventa un elenco da scorrere, e l'import un'attesa senza fine. */
    const val MAX_ITEMS = 40

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
      if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamsOf(intent: Intent): List<Uri> =
      (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
      else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
  }
}
