package dev.pampa.pampanotes.core.repo

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.PampaDatabase
import javax.inject.Inject

/** Keeps the Room transaction implementation inside core, without exposing Room to app callers. */
class DatabaseTransactions @Inject constructor(private val db: PampaDatabase) {
  suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}
