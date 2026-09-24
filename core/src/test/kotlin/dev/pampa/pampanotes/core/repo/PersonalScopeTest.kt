package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quali cartelle stanno in Registrazioni: la regola della radice, in Kotlin (in SQL e' `PersonalSql`). */
class PersonalScopeTest {

  // Storia (s) > Novecento (s2); Viaggio (v, personale) > Giappone (v2, che si dice «scuola») > Kyoto (v3).
  private val all = listOf(
    folder("s", null),
    folder("s2", "s", kind = FolderEntity.KIND_PERSONAL),
    folder("v", null, kind = FolderEntity.KIND_PERSONAL),
    folder("v2", "v"),
    folder("v3", "v2"),
  )

  @Test
  fun `la radice decide per tutto quello che ha dentro`() {
    assertEquals(setOf("v"), PersonalScope.rootIds(all))
    assertEquals(setOf("v", "v2", "v3"), PersonalScope.folderIds(all))
  }

  @Test
  fun `una sottocartella segue la sua radice, qualunque cosa dica la sua colonna`() {
    assertTrue(PersonalScope.isPersonal("v3", all))
    assertTrue(PersonalScope.isPersonal("v2", all))
    assertFalse(PersonalScope.isPersonal("s2", all))
    assertFalse(PersonalScope.isPersonal("s", all))
    assertFalse(PersonalScope.isPersonal(null, all))
    assertFalse(PersonalScope.isPersonal("sparita", all))
  }

  @Test
  fun `un ciclo nei genitori non gira per sempre`() {
    val loop = listOf(folder("a", "b", kind = FolderEntity.KIND_PERSONAL), folder("b", "a", kind = FolderEntity.KIND_PERSONAL))
    assertFalse(PersonalScope.isPersonal("a", loop))
    assertTrue(PersonalScope.folderIds(loop).isEmpty())
  }

  private fun folder(id: String, parent: String?, kind: String = FolderEntity.KIND_SCHOOL) =
    FolderEntity(id = id, name = id, parentId = parent, createdAt = 0, updatedAt = 0, kind = kind)
}
