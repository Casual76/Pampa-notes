package dev.pampa.pampanotes.ui.note

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampanotes.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Una pagina scritta a mano, com'e' stata disegnata all'import.
 *
 * Si mostra per intero, larga quanto la colonna: e' scrittura, e una miniatura non si legge. Si
 * decodifica a meta' risoluzione — 540 pixel bastano a una colonna di lettura e costano un quarto
 * della memoria — fuori dal thread principale. Un tocco la apre col visualizzatore di sistema, per
 * ingrandirla. Una pagina il cui file sta su un altro dispositivo e' una riga, come le fonti:
 * toccandola si scarica.
 */
@Composable
fun HandwritingPageCard(label: String, file: File, missing: Boolean, onOpen: () -> Unit) {
  if (missing) {
    FluidListRow(
      title = label,
      subtitle = stringResource(R.string.note_handwriting_missing),
      onClick = onOpen,
    )
    return
  }

  val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
    value = withContext(Dispatchers.IO) {
      runCatching {
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
      }.getOrNull()
    }
  }

  FluidCard(onClick = onOpen) {
    Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    bitmap?.let { image ->
      Image(
        bitmap = image,
        contentDescription = label,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().clip(ContinuousCornerShape(FluidRadius.Small)),
      )
    }
  }
}
