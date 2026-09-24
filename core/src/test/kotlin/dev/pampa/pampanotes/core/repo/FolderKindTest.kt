package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Il tipo di una cartella spostata: segue la sezione in cui finisce, sottocartelle comprese. */
class FolderKindTest {

  // Storia (s) > Novecento (s2); Viaggio (v, personale) > Giappone (v2, che si dice ancora «scuola»).
  private val all = listOf(
    folder("s", null),
    folder("s2", "s"),
    folder("v", null, kind = FolderEntity.KIND_PERSONAL),
    folder("v2", "v"),
  )

  @Test
  fun `dentro Registrazioni diventa personale`() {
    assertEquals(FolderEntity.KIND_PERSONAL, FolderRepository.kindAfterMove("s2", "v", all))
    assertEquals(FolderEntity.KIND_PERSONAL, FolderRepository.kindAfterMove("s", "v2", all))
  }

  @Test
  fun `fra le materie diventa scuola`() {
    assertEquals(FolderEntity.KIND_SCHOOL, FolderRepository.kindAfterMove("v2", "s", all))
  }

  @Test
  fun `portata al primo livello resta nella sezione da cui viene`() {
    // La colonna di Giappone dice «scuola», ma stava in Registrazioni: ci resta.
    assertEquals(FolderEntity.KIND_PERSONAL, FolderRepository.kindAfterMove("v2", null, all))
    assertEquals(FolderEntity.KIND_SCHOOL, FolderRepository.kindAfterMove("s2", null, all))
  }

  @Test
  fun `si riscrivono solo le righe che dicono altro`() {
    val moved = listOf(folder("s", null), folder("s2", "s"), folder("s3", "s2", kind = FolderEntity.KIND_PERSONAL))
    assertEquals(listOf("s", "s2"), FolderRepository.kindRewrites("s", FolderEntity.KIND_PERSONAL, moved).map { it.id })
    assertEquals(listOf("s3"), FolderRepository.kindRewrites("s", FolderEntity.KIND_SCHOOL, moved).map { it.id })
  }

  @Test
  fun `un ciclo o una cartella sparita non hanno sezione`() {
    val loop = listOf(folder("a", "b", kind = FolderEntity.KIND_PERSONAL), folder("b", "a", kind = FolderEntity.KIND_PERSONAL))
    assertNull(FolderRepository.rootKind("a", loop))
    assertNull(FolderRepository.rootKind("sparita", all))
    assertEquals(FolderEntity.KIND_SCHOOL, FolderRepository.kindAfterMove("x", "a", loop))
  }

  private fun folder(id: String, parent: String?, kind: String = FolderEntity.KIND_SCHOOL) =
    FolderEntity(id = id, name = id, parentId = parent, createdAt = 0, updatedAt = 0, kind = kind)
}
