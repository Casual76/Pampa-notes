package dev.pampa.pampanotes.ui.common

import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateStage
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.pampa.pampanotes.update.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStateTest {

  private val release = AvailableAppUpdate(
    version = "1.1.0",
    changelog = "**Novita'** e correzioni.",
    releaseTag = "stable-pampa-notes-v1.1.0",
    apkAsset = "pampa-notes-1.1.0.apk",
    downloadUrl = "https://example.invalid/pampa-notes-1.1.0.apk",
    sizeBytes = 41_000_000L,
  )

  @Test
  fun `senza nessun controllo non si sa niente`() {
    assertEquals(FluidUpdateUiState.Idle, UpdateUiState().toFluidUpdateState())
  }

  @Test
  fun `un controllo in corso copre quello che si sapeva`() {
    assertEquals(FluidUpdateUiState.Checking, UpdateUiState(checking = true, lastCheckedAt = 5L).toFluidUpdateState())
  }

  @Test
  fun `controllato e niente di nuovo non e' un errore`() {
    assertEquals(FluidUpdateUiState.UpToDate, UpdateUiState(lastCheckedAt = 5L).toFluidUpdateState())
  }

  @Test
  fun `un controllo fallito si dice, ma non e' il fallimento di un'installazione`() {
    val state = UpdateUiState(checkFailed = true).toFluidUpdateState()
    assertTrue(state is FluidUpdateUiState.CheckFailed)
  }

  @Test
  fun `una versione trovata resta offerta anche se l'ultimo controllo e' fallito`() {
    // UpdateController non cancella `available` quando il controllo fallisce: la vista non deve perderla.
    val state = UpdateUiState(available = release, checkFailed = true).toFluidUpdateState()
    assertEquals("1.1.0", (state as FluidUpdateUiState.Available).release.version)
    assertEquals(41_000_000L, state.release.sizeBytes)
  }

  @Test
  fun `il download senza peso non ha una percentuale`() {
    val unsized = UpdateUiState(
      available = release,
      install = AppUpdateInstallState.Downloading(progress = 0.4f, downloadedBytes = 10L, totalBytes = 0L),
    ).toFluidUpdateState()
    assertEquals(null, (unsized as FluidUpdateUiState.Downloading).progress)

    val sized = UpdateUiState(
      available = release,
      install = AppUpdateInstallState.Downloading(progress = 0.4f, downloadedBytes = 10L, totalBytes = 25L),
    ).toFluidUpdateState()
    assertEquals(0.4f, (sized as FluidUpdateUiState.Downloading).progress!!, 1e-6f)
  }

  @Test
  fun `verifica e attesa della conferma sono due momenti diversi`() {
    val verifying = UpdateUiState(available = release, install = AppUpdateInstallState.Verifying("")).toFluidUpdateState()
    assertEquals(FluidUpdateStage.Verifying, (verifying as FluidUpdateUiState.Working).stage)
    val waiting = UpdateUiState(available = release, install = AppUpdateInstallState.AwaitingUserAction("")).toFluidUpdateState()
    assertEquals(FluidUpdateStage.AwaitingUser, (waiting as FluidUpdateUiState.Working).stage)
  }

  @Test
  fun `il permesso mancante vince sull'installazione`() {
    val state = UpdateUiState(
      available = release,
      needsPermission = true,
      install = AppUpdateInstallState.Verifying(""),
    ).toFluidUpdateState()
    assertTrue(state is FluidUpdateUiState.NeedsPermission)
  }

  @Test
  fun `un errore dell'installazione si puo' riprovare`() {
    val state = UpdateUiState(available = release, install = AppUpdateInstallState.Error("rete")).toFluidUpdateState()
    assertEquals("rete", (state as FluidUpdateUiState.Failed).message)
  }
}
