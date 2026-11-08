package dev.pampa.pampanotes.ui.session

import androidx.lifecycle.SavedStateHandle
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

/** Executes the actual callback with its SavedState dependency. No Android/player constructor. */
class SessionRecoveryDismissTest {
  @Test fun `hide retains recovery ownership and remembers only this session card`() {
    val handle = SavedStateHandle()
    val hidden = handle.getStateFlow<List<String>>("dismissedJobs", emptyList())
    val repository = mockk<TranscriptionRepository>()
    val unsafe = Class.forName("sun.misc.Unsafe")
    val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val model = unsafe.getMethod("allocateInstance", Class::class.java).invoke(instance, SessionViewModel::class.java) as SessionViewModel
    for ((name, value) in mapOf("savedState" to handle, "dismissedJobs" to hidden, "transcription" to repository))
      model.javaClass.getDeclaredField(name).apply { isAccessible = true; set(model, value) }

    model.dismissJob("paid-job")
    assertEquals(listOf("paid-job"), hidden.value)
    model.dismissJob("paid-job")
    assertEquals(listOf("paid-job"), handle.get<List<String>>("dismissedJobs"))
    assertFalse("new-job" in hidden.value)
    assertEquals(emptyList<String>(), SavedStateHandle().getStateFlow<List<String>>("dismissedJobs", emptyList()).value)
    coVerify(exactly = 0) { repository.delete(any()) }
    coVerify(exactly = 0) { repository.clearFinished() }
    coVerify(exactly = 0) { repository.retry(any()) }
  }
}
