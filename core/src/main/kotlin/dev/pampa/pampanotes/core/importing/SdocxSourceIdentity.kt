package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.archive.FileMetaApi
import dev.pampa.pampanotes.core.archive.FileMetaResult
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.files.AppFiles
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Backfills intrinsic identity without downloading an original or changing note dates/titles. */
@Singleton
class SdocxSourceIdentity @Inject constructor(
  private val sources: SourceDao,
  private val files: AppFiles,
  private val meta: FileMetaApi,
) {
  suspend fun refresh(list: List<SourceEntity>): List<SourceEntity> {
    var opened = false
    var remote: FileMetaApi.Session? = null
    return list.map { source ->
      if (source.kind != SourceKind.SDOCX || source.sdocxCreatedAtMillis != null) return@map source
      try {
        val local = source.storedFileName?.let(files::sourceFile)
        var created = local?.let { rememberLocal(source, it, sources) }
        if (created == null && source.archivedAt > 0) {
          if (!opened) { remote = meta.open(); opened = true }
          val answer = remote?.meta(source.sha256)
          if (answer is FileMetaResult.Found && answer.meta.kind == "sdocx" && answer.meta.sha256 == source.sha256) {
            created = SdocxParser.plausibleDates(answer.meta.createdUs ?: -1, answer.meta.modifiedUs ?: -1,
              System.currentTimeMillis())?.createdAtMillis
            created?.let { sources.rememberSdocxCreation(source.id, it) }
          }
        }
        if (created != null) sources.get(source.id) ?: source else source
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        android.util.Log.w("PampaNotes", "Samsung source identity: ${source.id}", error)
        source
      }
    }
  }

  /** Run at every startup: earlier date repair may already be complete, or the PC was offline. */
  suspend fun backfill(): Int {
    val list = sources.byKind(SourceKind.SDOCX).filter { it.sdocxCreatedAtMillis == null }
    return refresh(list).count { it.sdocxCreatedAtMillis != null }
  }

  companion object {
    /** Called before eviction too, so a legacy local original never loses readable identity. */
    suspend fun rememberLocal(source: SourceEntity, file: File, sources: SourceDao): Long? {
      if (source.kind != SourceKind.SDOCX) return null
      source.sdocxCreatedAtMillis?.let { return it }
      val created = file.takeIf { it.exists() }?.let { SdocxParser.readDates(it)?.createdAtMillis } ?: return null
      sources.rememberSdocxCreation(source.id, created)
      return sources.get(source.id)?.sdocxCreatedAtMillis
    }
  }
}
