package dev.pampa.pampanotes.core.repo

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.PampaDatabase
import javax.inject.Inject

/** Keeps the Room transaction implementation inside core, without exposing Room to app callers.
 * Uses the injected singleton database, so ordinary DAO writers and explicit transactions share
 * Room's writer exclusion, including when block suspends. Never hold a file monitor while entering
 * this boundary; network HEAD/upload belongs before it. There is no unlocked fallback. */
class DatabaseTransactions @Inject constructor(private val db: PampaDatabase) {
  suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}
