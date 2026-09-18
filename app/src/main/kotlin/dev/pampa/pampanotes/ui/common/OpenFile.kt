package dev.pampa.pampanotes.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Riapre un file con l'app che lo sa leggere: un PDF nel lettore, un `.sdocx` in Samsung Notes.
 *
 * Passa dal FileProvider dell'app, perche' un percorso dentro `filesDir` non e' leggibile da
 * nessun altro. Il chooser e' per il caso in cui piu' app lo aprano; se non ne esiste nessuna il
 * sistema lo dice da solo, e qui non c'e' niente da fare a parte non cadere.
 */
fun openWithSystem(context: Context, file: File, mime: String): Boolean {
  if (!file.exists()) return false
  val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }
    .getOrNull() ?: return false
  val intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uri, mime.ifBlank { "*/*" })
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
  return runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
