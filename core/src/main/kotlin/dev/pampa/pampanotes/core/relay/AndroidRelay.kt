package dev.pampa.pampanotes.core.relay

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Owner validation uses the same authenticated status route as text sync. No credential goes to disk. */
internal class AndroidRelayAccounts(
  private val settings: PampaSettingsStore,
  private val http: RelayHttp,
) : RelayAccounts {
  override suspend fun snapshot(): RelaySession {
    val first = settings.current()
    val owner = settings.syncOwnerId()
      ?: throw RelayFailure(RelayState.AUTH_REQUIRED, "text_sync_required")
    val token = settings.syncToken()?.takeIf { it.isNotBlank() }
      ?: throw RelayFailure(RelayState.AUTH_REQUIRED, "session_required")
    if (!first.syncEnabled || first.syncDeviceId.isBlank()) throw RelayFailure(RelayState.AUTH_REQUIRED, "text_sync_required")
    val session = RelaySession(RelayHttp.normalizedBase(first.syncServerUrl), owner, token,
      first.syncDeviceId, first.syncDeviceName)
    if (!isCurrent(session)) throw RelayFailure(RelayState.ACCOUNT_CHANGED, "account_changed")
    if (http.owner(session) != owner) throw RelayFailure(RelayState.ACCOUNT_CHANGED, "owner_mismatch")
    if (!isCurrent(session)) throw RelayFailure(RelayState.ACCOUNT_CHANGED, "account_changed")
    return session
  }

  override suspend fun isCurrent(session: RelaySession): Boolean {
    // Double read around secret/owner access avoids mixing two DataStore snapshots during login/logout.
    val before = settings.current()
    val token = settings.syncToken()
    val owner = settings.syncOwnerId()
    val foreign = settings.syncForeignOwner.first()
    val after = settings.current()
    return before.syncEnabled && after.syncEnabled && before.syncServerUrl == after.syncServerUrl &&
      before.syncDeviceId == after.syncDeviceId && before.syncAccount == after.syncAccount &&
      after.syncServerUrl.trimEnd('/') == session.baseUrl && after.syncDeviceId == session.deviceId &&
      token == session.token && owner == session.ownerId && foreign.isEmpty()
  }
}

internal class AndroidRelayLocalFiles(
  private val db: PampaDatabase,
  private val files: AppFiles,
  private val inUse: FilesInUse,
) : RelayLocalFiles {
  override suspend fun list(noteId: String?): List<RelayLocalFile> {
    val sessions = db.sessions().all().associate { it.id to it.noteId }
    val audio = db.audioParts().all().mapNotNull { part ->
      sessions[part.sessionId]?.let { part.relay(it) }
    }
    val sources = db.sources().all().mapNotNull { it.relay() }
    return (audio + sources).filter { noteId == null || it.noteId == noteId }
  }

  override suspend fun get(kind: RelayKind, entityId: String): RelayLocalFile? = when (kind) {
    RelayKind.AUDIO -> db.audioParts().get(entityId)?.let { part ->
      db.sessions().get(part.sessionId)?.let { part.relay(it.noteId) }
    }
    RelayKind.SOURCE -> db.sources().get(entityId)?.relay()
  }

  override fun path(file: RelayLocalFile): File {
    val name = file.fileName
    if (name.isBlank() || name == "." || name == ".." || name.any { it in "/\\:" || it.isISOControl() }) {
      throw RelayFailure(RelayState.UNSUPPORTED, "invalid_local_path")
    }
    val root = files.root.canonicalFile
    val expectedFolder = File(root, if (file.kind == RelayKind.AUDIO) "audio" else "sources")
    val folder = expectedFolder.canonicalFile
    // Revalidate every time, including immediately before the atomic move; reject symlink escapes.
    val target = File(folder, name).canonicalFile
    if (folder != expectedFolder || target.parentFile != folder || target != File(folder, name).absoluteFile) {
      throw RelayFailure(RelayState.UNSUPPORTED, "invalid_local_path")
    }
    return target
  }

  override suspend fun placeIfCurrent(expected: RelayLocalFile, place: suspend () -> Unit): Boolean =
    db.withTransaction {
      if (get(expected.kind, expected.entityId) != expected) false
      else { place(); true }
    }

  override fun hold(file: RelayLocalFile) {
    inUse.hold(listOf(key(file)), 15 * 60_000L, owner = this)
  }

  override fun release(file: RelayLocalFile) { inUse.release(listOf(key(file)), owner = this) }

  private fun key(file: RelayLocalFile): String = if (file.kind == RelayKind.AUDIO) {
    FilesInUse.audio(file.fileName)
  } else FilesInUse.source(file.fileName)

  private fun AudioPartEntity.relay(noteId: String) = RelayLocalFile(
    RelayKind.AUDIO, id, noteId, fileName, originalName, mime, sha256, sizeBytes,
    relayHash(toString().toByteArray()),
  )

  private fun SourceEntity.relay(): RelayLocalFile? = storedFileName?.let {
    RelayLocalFile(RelayKind.SOURCE, id, noteId, it, originalName, mime, sha256, sizeBytes,
      relayHash(toString().toByteArray()))
  }
}

@Module
@InstallIn(SingletonComponent::class)
object RelayModule {
  @Provides @Singleton
  fun http(): RelayHttp = RelayHttp()

  @Provides @Singleton
  fun repository(
    @ApplicationContext context: Context, db: PampaDatabase, files: AppFiles,
    inUse: FilesInUse, settings: PampaSettingsStore, http: RelayHttp,
  ): RelayRepository = RelayRepository(
    AndroidRelayAccounts(settings, http), AndroidRelayLocalFiles(db, files, inUse), http,
    RelayJournal(File(context.noBackupFilesDir, "cloud-relay-v1")),
    object : RelayClock {
      override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
      override fun wallMillis(): Long = System.currentTimeMillis()
    },
  )
}
