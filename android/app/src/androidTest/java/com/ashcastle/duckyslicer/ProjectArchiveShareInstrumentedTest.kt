package com.ashcastle.duckyslicer

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectArchiveShareInstrumentedTest {
    @Test
    fun preparedProjectArchiveSharesOneExactReadOnlyDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shareRoot = File(context.filesDir, "project-shares")
        shareRoot.deleteRecursively()
        val outside = File(context.filesDir, "not-a-project-share.duckyproject")
        outside.writeText("private")
        try {
            val first = requireNotNull(prepareProjectArchiveShare(context))
            first.file.writeText("stale-project")
            val target = requireNotNull(prepareProjectArchiveShare(context))
            assertFalse(first.file.exists())
            val expected = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x44, 0x53)
            context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                requireNotNull(output).write(expected)
            }

            val share = requireNotNull(projectArchiveShareIntentOrNull(context, target.uri))
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals(PROJECT_ARCHIVE_MIME_TYPE, share.type)
            assertEquals(
                target.uri,
                IntentCompat.getParcelableExtra(share, Intent.EXTRA_STREAM, Uri::class.java),
            )
            assertEquals(PROJECT_ARCHIVE_SHARE_DISPLAY_NAME, share.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(target.uri, share.clipData?.getItemAt(0)?.uri)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertArrayEquals(
                expected,
                context.contentResolver.openInputStream(target.uri).use { input ->
                    requireNotNull(input).readBytes()
                },
            )

            context.contentResolver.query(
                target.uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            ).use { cursor ->
                requireNotNull(cursor)
                assertTrue(cursor.moveToFirst())
                assertEquals(PROJECT_ARCHIVE_SHARE_DISPLAY_NAME, cursor.getString(0))
                assertEquals(expected.size.toLong(), cursor.getLong(1))
            }

            assertEquals(1, requireNotNull(shareRoot.listFiles()).count(File::isFile))
            assertNull(
                projectArchiveShareIntentOrNull(
                    context,
                    Uri.parse("content://example.invalid/project.duckyproject"),
                ),
            )
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.project-share",
                    outside,
                )
            }
            assertFalse(discardProjectArchiveShare(context, outside.absolutePath))
            assertTrue(outside.isFile)
        } finally {
            shareRoot.deleteRecursively()
            outside.delete()
        }
    }
}
