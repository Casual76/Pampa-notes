package dev.pampa.pampanotes.core.importing

/** Conservative three-way body merge. A repeated incoming baseline is always a no-op.
 * Independent edits merge exactly; overlapping edits remain visible as explicit diff3 markers.
 * Only the last incoming baseline is persisted, never an accumulating version log. */
internal object PampaBundleBodyMerge {
  fun merge(base: String?, local: String, incoming: String): String {
    if (local == incoming || base == incoming) return local
    if (base == local || base == null && local.isEmpty()) return incoming
    if (base == null) return conflict(local, incoming)
    val a = edit(base, local)
    val b = edit(base, incoming)
    // Two different insertions at the same boundary are a conflict, not an arbitrary ordering.
    if (a.end <= b.start && a.start != b.start) return apply(base, a, b)
    if (b.end <= a.start && a.start != b.start) return apply(base, b, a)
    return conflict(local, incoming)
  }

  private data class Edit(val start: Int, val end: Int, val text: String)
  private fun edit(base: String, changed: String): Edit {
    var start = 0
    while (start < minOf(base.length, changed.length) && base[start] == changed[start]) start++
    var suffix = 0
    while (suffix < base.length - start && suffix < changed.length - start && base[base.lastIndex - suffix] == changed[changed.lastIndex - suffix]) suffix++
    return Edit(start, base.length - suffix, changed.substring(start, changed.length - suffix))
  }

  private fun apply(base: String, first: Edit, second: Edit): String =
    base.substring(0, first.start) + first.text + base.substring(first.end, second.start) + second.text + base.substring(second.end)

  private fun conflict(local: String, incoming: String): String =
    "<<<<<<<\n$local\n=======\n$incoming\n>>>>>>>"
}
