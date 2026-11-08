package dev.pampa.pampanotes.ui.importing

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.export.PampaNoteFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportRequestTest {
  private val uri = Uri.parse("content://documents/document/opaque-provider-id")

  @Test fun clip_only_share_with_caption_is_one_portable_file_and_is_consumed_once() {
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "application/octet-stream"
      clipData = ClipData.newRawUri("lesson.PN", uri)
      putExtra(Intent.EXTRA_TEXT, "A message caption, not a second note")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val request = ImportRequest.fromIntent(intent)!!
    assertEquals(listOf(uri), request.uris)
    assertNull(request.text)
    assertNull(ImportRequest.fromIntent(intent))
  }

  @Test fun stream_and_clip_uris_are_deduplicated_without_dropping_other_shared_files() {
    val other = Uri.parse("content://documents/document/another")
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
      type = PampaNoteFormat.MIME_TYPE
      putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
      clipData = ClipData.newRawUri("lesson.pn", uri).apply { addItem(ClipData.Item(other)) }
    }
    assertEquals(listOf(uri, other), ImportRequest.fromIntent(intent)!!.uris)
    assertNull(ImportRequest.fromIntent(intent))
  }

  @Test fun view_and_data_only_shares_work_for_portable_and_legacy_zip_files() {
    for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) {
      for (mime in listOf(PampaNoteFormat.MIME_TYPE, "application/octet-stream", "application/zip")) {
        val intent = Intent(action).setDataAndType(uri, mime)
        assertEquals(listOf(uri), ImportRequest.fromIntent(intent)!!.uris)
        assertNull(ImportRequest.fromIntent(intent))
      }
    }
    assertNull(ImportRequest.fromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("pampanotes://endpoint?url=private"))))
  }

  @Test fun text_only_shares_remain_supported_and_picker_contains_portable_MIME() {
    assertEquals("Text without a file", ImportRequest.fromIntent(Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Text without a file")
    })!!.text)
    assertTrue(PampaNoteFormat.MIME_TYPE in ImportRequest.PICKER_MIME_TYPES)
    assertTrue("application/sdoc" in ImportRequest.PICKER_MIME_TYPES)
    assertTrue("application/vnd.openxmlformats-officedocument.wordprocessingml.document" in ImportRequest.PICKER_MIME_TYPES)
  }

  @Test fun installed_manifest_accepts_custom_and_generic_portable_open_and_share_intents() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
      for (mime in listOf(PampaNoteFormat.MIME_TYPE, "application/octet-stream", "application/zip")) {
        val intent = Intent(action).setPackage(context.packageName).addCategory(Intent.CATEGORY_DEFAULT).apply {
          if (action == Intent.ACTION_VIEW) setDataAndType(uri, mime) else type = mime
        }
        assertNotNull("Missing intent route: $action $mime", context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY))
      }
    }
  }
}
