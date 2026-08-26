package com.ashcastle.duckyslicer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileBundleIntentInstrumentedTest {
    @get:Rule
    val blockingProviderProcess = BlockingProviderProcessRule()

    @Test
    fun conflictingImportNoticeReportsAdjustedNames() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val completion = ProfileTransferCompletion(
            id = 1L,
            direction = ProfileTransferDirection.IMPORT,
            outcome = ProfileTransferOutcome.SUCCEEDED,
            importResult = ProfileBundleImportResult(
                importedPrinters = 1,
                importedFilaments = 1,
                importedSlicing = 1,
                skippedDuplicates = 0,
                renamedConflicts = 2,
            ),
        )

        assertEquals(
            resources.getString(R.string.profiles_imported_with_renamed_conflicts, 3, 2),
            profileTransferSuccessNotice(resources, completion, "unchanged", "exported"),
        )
    }

    @Test
    fun externalProfileRequestBindsOneOperationAndRestoresAsRetryableAfterProcessLoss() {
        val sharedUri = Uri.parse("content://example/Download/profiles.duckyprofiles")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = PROFILE_BUNDLE_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }
        val savedState = SavedStateHandle()
        val retained = ExternalProfileRequestViewModel(savedState)

        assertTrue(retained.enqueue(intent))
        val first = requireNotNull(retained.request.value)
        assertTrue(retained.markStarted(first.id, 41L))
        assertEquals(41L, retained.request.value?.startedOperationId)

        assertTrue(retained.enqueue(intent))
        val second = requireNotNull(retained.request.value)
        assertTrue(second.id > first.id)
        assertNull(second.startedOperationId)
        assertFalse(retained.consume(first.id, 41L))
        assertTrue(retained.markStarted(second.id, 42L))
        assertFalse(retained.consume(second.id, 41L))

        val restoredAfterProcessLoss = ExternalProfileRequestViewModel(savedState)
        val restored = requireNotNull(restoredAfterProcessLoss.request.value)
        assertEquals(second.id, restored.id)
        assertEquals(second.uri, restored.uri)
        assertNull(restored.startedOperationId)
        assertTrue(restoredAfterProcessLoss.markStarted(restored.id, 1L))
        assertTrue(restoredAfterProcessLoss.consume(restored.id, 1L))
        assertNull(restoredAfterProcessLoss.request.value)
    }

    @Test
    fun profileDocumentIntentsAcceptOneContentStreamAndRejectUnsafeDocuments() {
        val custom = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/document/no-extension"),
            PROFILE_BUNDLE_MIME_TYPE,
        )
        val compatible = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/profiles.duckyprofiles"),
            "application/json",
        )
        val compatibleWithParameter = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/profiles.duckyprofiles"),
            "application/json; charset=utf-8",
        )
        val network = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("https://example.invalid/profiles.duckyprofiles"),
            PROFILE_BUNDLE_MIME_TYPE,
        )
        val file = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("file:///sdcard/Download/profiles.duckyprofiles"),
            PROFILE_BUNDLE_MIME_TYPE,
        )
        val unrelatedName = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/model.stl"),
            "application/json",
        )
        val unrelatedType = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/profiles.duckyprofiles"),
            "text/plain",
        )
        val sharedUri = Uri.parse("content://example/Share/profiles.duckyprofiles")
        val shared = Intent(Intent.ACTION_SEND).apply {
            type = PROFILE_BUNDLE_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }
        val sharedByClip = Intent(Intent.ACTION_SEND).apply {
            type = PROFILE_BUNDLE_MIME_TYPE
            clipData = ClipData.newRawUri("profiles.duckyprofiles", sharedUri)
        }
        val conflictingShare = Intent(shared).apply {
            clipData = ClipData.newRawUri(
                "other.duckyprofiles",
                Uri.parse("content://example/Share/other.duckyprofiles"),
            )
        }
        val multipleShare = Intent(Intent.ACTION_SEND).apply {
            type = PROFILE_BUNDLE_MIME_TYPE
            clipData = ClipData.newRawUri("profiles.duckyprofiles", sharedUri).apply {
                addItem(ClipData.Item(Uri.parse("content://example/Share/other.duckyprofiles")))
            }
        }
        val networkShare = Intent(Intent.ACTION_SEND).apply {
            type = PROFILE_BUNDLE_MIME_TYPE
            putExtra(
                Intent.EXTRA_STREAM,
                Uri.parse("https://example.invalid/profiles.duckyprofiles"),
            )
        }

        assertEquals(custom.data, profileBundleDocumentUriOrNull(custom))
        assertEquals(compatible.data, profileBundleDocumentUriOrNull(compatible))
        assertEquals(
            compatibleWithParameter.data,
            profileBundleDocumentUriOrNull(compatibleWithParameter),
        )
        assertEquals(sharedUri, profileBundleDocumentUriOrNull(shared))
        assertEquals(sharedUri, profileBundleDocumentUriOrNull(sharedByClip))
        assertNull(profileBundleDocumentUriOrNull(conflictingShare))
        assertNull(profileBundleDocumentUriOrNull(multipleShare))
        assertNull(profileBundleDocumentUriOrNull(networkShare))
        assertNull(profileBundleDocumentUriOrNull(network))
        assertNull(profileBundleDocumentUriOrNull(file))
        assertNull(profileBundleDocumentUriOrNull(unrelatedName))
        assertNull(profileBundleDocumentUriOrNull(unrelatedType))

        val packageManager = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        assertTrue(packageManager.resolvesMainActivity(custom))
        assertTrue(packageManager.resolvesMainActivity(compatible))
        assertTrue(packageManager.resolvesMainActivity(shared))
        assertFalse(packageManager.resolvesMainActivity(network))
        assertFalse(packageManager.resolvesMainActivity(file))
        assertFalse(packageManager.resolvesMainActivity(unrelatedName))
        assertFalse(packageManager.resolvesMainActivity(unrelatedType))
    }

    @Test
    fun sharedProfileIntentSurvivesRecreationAndImportsExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val importedName = "Share intent filament ${UUID.randomUUID()}"
        val fixture = profileBundleFixture(importedName)
        resetTargetProfiles()
        prepareImport(fixture)
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                setPackage(context.packageName)
                type = PROFILE_BUNDLE_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, BlockingImportProvider.PROFILE_URI)
                clipData = ClipData.newRawUri(
                    "profiles.duckyprofiles",
                    BlockingImportProvider.PROFILE_URI,
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var retainedProfiles: ProfileLibraryViewModel
                lateinit var retainedRequest: ExternalProfileRequestViewModel
                scenario.onActivity { activity ->
                    retainedProfiles = ViewModelProvider(activity)[ProfileLibraryViewModel::class.java]
                    retainedRequest =
                        ViewModelProvider(activity)[ExternalProfileRequestViewModel::class.java]
                }
                waitForImportProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }
                val operationId = retainedProfiles.state.value.activeOperationId
                assertTrue(operationId > 0L)
                assertEquals(operationId, retainedRequest.request.value?.startedOperationId)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedProfiles,
                        ViewModelProvider(recreated)[ProfileLibraryViewModel::class.java],
                    )
                    assertSame(
                        retainedRequest,
                        ViewModelProvider(recreated)[ExternalProfileRequestViewModel::class.java],
                    )
                    assertEquals(operationId, retainedRequest.request.value?.startedOperationId)
                }

                releaseImportProvider()
                waitUntil("profile shared from another app was not committed") {
                    ProfileStore(context).load().filaments.count { it.name == importedName } == 1
                }
                waitUntil("completed external profile request was not consumed") {
                    retainedRequest.request.value == null
                }
                val status = waitForImportProvider {
                    it.getBoolean(BlockingImportProvider.KEY_COMPLETED)
                }
                assertEquals(fixture.length().toInt(), status.getInt(BlockingImportProvider.KEY_BYTES))
                scenario.recreate()
                assertEquals(
                    1,
                    ProfileStore(context).load().filaments.count { it.name == importedName },
                )
            }
        } finally {
            releaseImportProvider()
            fixture.parentFile?.deleteRecursively()
            resetTargetProfiles()
        }
    }

    private fun android.content.pm.PackageManager.resolvesMainActivity(intent: Intent): Boolean =
        queryIntentActivities(intent, 0).any { result ->
            result.activityInfo.name == MainActivity::class.java.name
        }

    private fun profileBundleFixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.cacheDir.resolve("profile-intent-${UUID.randomUUID()}")
        val source = ProfileStore(directory.resolve("user_profiles.json"))
        source.saveFilament(name, SliceOptions())
        return directory.resolve("fixture$PROFILE_BUNDLE_FILE_EXTENSION").apply {
            writeBytes(source.exportBundle())
        }
    }

    private fun prepareImport(fixture: File) {
        ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val extras = Bundle().apply {
                putParcelable(BlockingImportProvider.KEY_SOURCE_DESCRIPTOR, descriptor)
            }
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.PROFILE_URI,
                BlockingImportProvider.METHOD_PREPARE,
                null,
                extras,
            )
        }
    }

    private fun releaseImportProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingImportProvider.PROFILE_URI,
            BlockingImportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun waitForImportProvider(predicate: (Bundle) -> Boolean): Bundle {
        var status = Bundle.EMPTY
        waitUntil("blocking profile provider did not reach the expected state") {
            status = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.PROFILE_URI,
                BlockingImportProvider.METHOD_STATUS,
                null,
                null,
            ) ?: Bundle.EMPTY
            predicate(status)
        }
        return status
    }

    private fun resetTargetProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.filesDir.resolve("profiles").deleteRecursively()
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError(message)
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS = 30_000L
        const val WAIT_POLL_MILLIS = 50L
    }
}
