package dev.pampa.pampanotes.core.relay

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RelayReceivedAvailabilitySignalTest {
  @Test fun openConsumersSeeSuccessiveDiskPlacementsWithoutRoomRowChanges() = runBlocking {
    val availability = RelayFileAvailability()
    val seen = mutableListOf<Long>()
    val observer = launch(start = CoroutineStart.UNDISPATCHED) { availability.changes.take(3).toList(seen) }
    availability.placed()
    yield()
    availability.placed()
    observer.join()
    assertEquals(listOf(0L, 1L, 2L), seen)
  }

  @Test fun latestPlacementSurvivesAnInactiveConsumer() {
    val availability = RelayFileAvailability()
    availability.placed()
    availability.placed()
    assertEquals(2L, availability.changes.value)
  }
}
