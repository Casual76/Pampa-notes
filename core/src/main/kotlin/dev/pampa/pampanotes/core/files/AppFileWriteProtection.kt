package dev.pampa.pampanotes.core.files

import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A sweep's reference-read generation. Acquire BEFORE reading any DB references and close in
 * finally, even when a query fails. An import released after this generation remains protected
 * from this snapshot, whose DB rows may predate the import's commit.
 */
class OrphanSweepSnapshot internal constructor(internal val generation: Long) : AutoCloseable {
  internal var closed = false

  override fun close() = AppFileWriteProtection.close(this)
}

/**
 * One process-wide registry, keyed by canonical paths, used by BOTH staging and AppFiles sweep.
 * No expiry: a slow live writer stays protected. Only overlapping sweeps retain release records;
 * closing the last such snapshot forgets them, so abandoned files are ordinary orphans again.
 * The monitor covers registration and the sweep's check AND delete, never a DB call or suspend.
 */
internal object AppFileWriteProtection {
  private var generation = 0L
  private val owners = mutableMapOf<File, MutableSet<Any>>()
  private val releasedAt = mutableMapOf<File, Long>()
  private val snapshots = mutableSetOf<OrphanSweepSnapshot>()

  @Synchronized
  fun hold(file: File, owner: Any) {
    owners.getOrPut(file.canonicalFile) {
      Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    }.add(owner)
  }

  @Synchronized
  fun heldBy(file: File, owner: Any): Boolean = owners[file.canonicalFile]?.contains(owner) == true

  /** An unrelated owner cannot release somebody else's write, even for the same filename. */
  @Synchronized
  fun release(owner: Any) {
    val iterator = owners.iterator()
    while (iterator.hasNext()) {
      val (file, holders) = iterator.next()
      if (!holders.remove(owner) || holders.isNotEmpty()) continue
      iterator.remove()
      generation++
      if (snapshots.isNotEmpty()) releasedAt[file] = generation
    }
    pruneReleases()
  }

  @Synchronized
  fun beginSweep(): OrphanSweepSnapshot = OrphanSweepSnapshot(generation).also { snapshots.add(it) }

  @Synchronized
  fun close(snapshot: OrphanSweepSnapshot) {
    if (snapshot.closed) return
    snapshot.closed = true
    snapshots.remove(snapshot)
    pruneReleases()
  }

  @Synchronized
  fun deleteIfUnprotected(file: File, snapshot: OrphanSweepSnapshot, eligible: (File) -> Boolean): Boolean {
    check(!snapshot.closed && snapshot in snapshots) { "Orphan sweep snapshot is closed" }
    val key = file.canonicalFile
    if (key in owners || (releasedAt[key]?.let { it > snapshot.generation } == true)) return false
    return eligible(file) && file.delete()
  }

  private fun pruneReleases() {
    val oldest = snapshots.minOfOrNull { it.generation }
    if (oldest == null) releasedAt.clear()
    else releasedAt.entries.removeAll { it.value <= oldest }
  }
}
