package dev.pampa.pampanotes.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.EngineBuild
import dev.pampa.pampanotes.BuildConfig
import dev.pampa.pampanotes.core.db.JobDao
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MoreUiState(
  val activeJobs: Int = 0,
  val versionName: String = BuildConfig.VERSION_NAME,
  val engineVersion: String = EngineBuild.VERSION,
)

@HiltViewModel
class MoreViewModel @Inject constructor(
  jobs: JobDao,
) : ViewModel() {

  val uiState: StateFlow<MoreUiState> = jobs.observeActiveCount()
    .map { MoreUiState(activeJobs = it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoreUiState())
}
