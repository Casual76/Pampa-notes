package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Una cartella sul filo, col tipo (materia o Registrazioni) dalla versione 9 del database. */
class FolderPayloadTest {

  private val folder = FolderEntity(id = "f", name = "Storia", tone = "Blue", icon = "history", createdAt = 1, updatedAt = 2)

  private fun encode(value: FolderEntity) = SyncPayloads.encode(FolderEntity.serializer(), value, value.updatedAt)

  /** La cartella come la scriveva un'app di prima: senza `kind`. */
  private fun before(value: FolderEntity) = JsonObject(encode(value).payload.jsonObject.filterKeys { it != "kind" })

  @Test
  fun `una materia ha l'impronta di prima dell'aggiornamento`() {
    // Se il default contasse, l'aggiornamento farebbe sembrare cambiate tutte le cartelle, su ogni
    // dispositivo: tutte nell'outbox, e tutte a vincere o perdere contro l'indice.
    assertEquals(SyncCodec.hash(before(folder)), encode(folder).hash)
  }

  @Test
  fun `Registrazioni cambia l'impronta, cosi' sale, e tornare materia la riporta com'era`() {
    val personal = encode(folder.copy(kind = FolderEntity.KIND_PERSONAL))
    assertNotEquals(encode(folder).hash, personal.hash)
    assertEquals("personal", personal.payload.jsonObject["kind"]?.jsonPrimitive?.content)
    assertEquals(encode(folder).hash, encode(folder.copy(kind = FolderEntity.KIND_SCHOOL)).hash)
  }

  @Test
  fun `una cartella arrivata da un'app di prima si legge come materia`() {
    assertEquals(folder, SyncCodec.json.decodeFromJsonElement(FolderEntity.serializer(), before(folder)))
  }

  @Test
  fun `il tipo va e torna col resto della cartella`() {
    val personal = folder.copy(kind = FolderEntity.KIND_PERSONAL)
    assertEquals(personal, SyncCodec.json.decodeFromJsonElement(FolderEntity.serializer(), encode(personal).payload))
  }

  @Test
  fun `il kind delle fonti conta sempre`() {
    // La regola salta solo il `kind` di serie di una cartella: quello di una fonte e' un enum, e
    // cambiarlo e' una modifica vera.
    val source = SourceEntity(
      id = "s", noteId = "n", kind = SourceKind.PDF, originalName = "a.pdf", mime = "application/pdf",
      sizeBytes = 1, sha256 = "x", storedFileName = "s.pdf", importedAt = 0,
    )
    val pdf = SyncPayloads.encode(SourceEntity.serializer(), source, 0)
    val docx = SyncPayloads.encode(SourceEntity.serializer(), source.copy(kind = SourceKind.DOCX), 0)
    assertNotEquals(pdf.hash, docx.hash)
  }
}
