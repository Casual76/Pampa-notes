package dev.pampa.pampanotes.core.testing

import dev.pampa.pampanotes.core.repo.DatabaseTransactions
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** JVM writer exclusion only, not Room proof. Every concurrent fixture mutation must use write.
 * The same mutex spans the repository transaction's suspensions AND physical deletion. */
class StorageWriterTransactionsB06 {
  private val writers = Mutex()
  @Volatile var inside = false
    private set
  val transactions = mockk<DatabaseTransactions>()

  init {
    coEvery { transactions.run<Any?>(any()) } coAnswers {
      write { firstArg<suspend () -> Any?>().invoke() }
    }
  }

  suspend fun <T> write(block: suspend () -> T): T = writers.withLock {
    check(!inside)
    inside = true
    try { block() } finally { inside = false }
  }
}
