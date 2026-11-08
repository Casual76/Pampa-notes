package dev.pampa.pampanotes.core.alignment

import java.io.File

/** Held by FilesInUse at the Android boundary; every present original is rehashed, never cached. */
object AlignmentAudio {
  fun file(root: File, name: String): File {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it in "/\\:" || it.isISOControl() })
    val absolute = root.absoluteFile
    val path = File(absolute, name)
    require(absolute.canonicalFile == absolute && path.canonicalFile.parentFile == absolute && path.canonicalFile == path)
    return path
  }
  fun verify(root: File, parts: List<AlignmentPlacement>): Boolean = parts.all { p ->
    val path = file(root, p.fileName)
    !path.isFile || AlignmentProtocol.sha256(path) == p.sha256
  }
}
