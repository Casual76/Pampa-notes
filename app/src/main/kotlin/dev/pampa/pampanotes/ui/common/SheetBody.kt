package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Il corpo di un pannello di vetro.
 *
 * L'engine consegna il pannello e basta: i margini li mette chi ci scrive dentro, e senza, il
 * contenuto arriva a filo del bordo arrotondato e si legge come rotto. Sta qui perche' i pannelli
 * a pagina intera dell'app sono sette e devono essere identici.
 *
 * Due dettagli che non si vedono finche' non mancano: il contenuto scorre, perche' un pannello con
 * dentro dieci righe su un telefono piccolo altrimenti taglia i tasti; e su uno schermo largo non si
 * stira oltre una certa misura, perche' una riga con l'interruttore a settanta centimetri dalla sua
 * etichetta non si legge come una riga.
 */
@Composable
fun SheetBody(
  modifier: Modifier = Modifier,
  scrollable: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) {
  val scroll = rememberScrollState()
  Column(
    modifier = modifier
      .fillMaxWidth()
      .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 560.dp)
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(top = 12.dp, bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = content,
    )
  }
}

/**
 * I tasti in fondo a una pagina intera.
 *
 * Per `FluidGlassModalPresentation.FullScreen` — i fogli e i pop-up hanno `FluidSheetScaffold`
 * dell'engine, che e' fatto per quelli: li' e' il portale a tenere i tasti fermi sotto il contenuto
 * che scorre e a scavalcare la barra di sistema, quindi qui restano solo la misura e l'aria intorno.
 */
@Composable
fun ColumnScope.PageActions(content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 560.dp)
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(top = 8.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
    )
  }
}
