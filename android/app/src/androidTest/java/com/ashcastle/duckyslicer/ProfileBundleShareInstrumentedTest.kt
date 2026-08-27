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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileBundleShareInstrumentedTest {
    @Test
    fun preparedProfileBundleSharesExactReadOnlyDocumentAndRejectsOtherUris() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shareRoot = File(context.filesDir, "profile-shares")
        shareRoot.deleteRecursively()
        val outside = File(context.filesDir, "not-a-profile-share.duckyprofiles")
        outside.writeText("private")
        try {
            val target = requireNotNull(prepareProfileBundleShare(context))
            val expected = "{\"type\":\"portable-profile-test\"}".toByteArray()
            context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                requireNotNull(output).write(expected)
            }

            val share = requireNotNull(profileBundleShareIntentOrNull(context, target.uri))
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals(PROFILE_BUNDLE_MIME_TYPE, share.type)
            assertEquals(
                target.uri,
                IntentCompat.getParcelableExtra(share, Intent.EXTRA_STREAM, Uri::class.java),
            )
            assertEquals(PROFILE_BUNDLE_SHARE_DISPLAY_NAME, share.getStringExtra(Intent.EXTRA_TITLE))
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
                assertEquals(PROFILE_BUNDLE_SHARE_DISPLAY_NAME, cursor.getString(0))
                assertEquals(expected.size.toLong(), cursor.getLong(1))
            }

            assertNull(
                profileBundleShareIntentOrNull(
                    context,
                    Uri.parse("content://example.invalid/profiles.duckyprofiles"),
                ),
            )
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.profile-share",
                    outside,
                )
            }
            assertFalse(discardProfileBundleShare(context, outside.absolutePath))
            assertTrue(outside.isFile)
        } finally {
            shareRoot.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun preparedProfileBundleStorageRetainsOnlyThreePrivateOutputs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shareRoot = File(context.filesDir, "profile-shares")
        shareRoot.deleteRecursively()
        try {
            repeat(5) { index ->
                val target = requireNotNull(prepareProfileBundleShare(context))
                target.file.writeText("bundle-$index")
                assertNotNull(profileBundleShareIntentOrNull(context, target.uri))
            }
            val retained = requireNotNull(shareRoot.listFiles()).filter(File::isFile)
            assertEquals(3, retained.size)
            assertTrue(retained.all { it.length() > 0L })
        } finally {
            shareRoot.deleteRecursively()
        }
    }
}
