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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelOpenIntentInstrumentedTest {
    @get:Rule
    val blockingProviderProcess = BlockingProviderProcessRule()

    @Test
    fun externalModelRequestBindsOneOperationAndRestoresAsRetryableAfterProcessLoss() {
        val sharedUris = arrayListOf(
            Uri.parse("content://example/first.stl"),
            Uri.parse("content://example/second.stl"),
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "model/stl"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, sharedUris)
            clipData = ClipData.newRawUri("first", sharedUris.first()).apply {
                addItem(ClipData.Item(sharedUris.last()))
            }
        }
        val savedState = SavedStateHandle()
        val retained = ExternalModelRequestViewModel(savedState)

        assertTrue(retained.enqueue(intent))
        val first = requireNotNull(retained.request.value)
        assertTrue(retained.markStarted(first.id, 31L))
        assertEquals(31L, retained.request.value?.startedOperationId)

        assertTrue(retained.enqueue(intent))
        val second = requireNotNull(retained.request.value)
        assertTrue(second.id > first.id)
        assertNull(second.startedOperationId)
        assertFalse(retained.consume(first.id, 31L))
        assertTrue(retained.markStarted(second.id, 32L))
        assertFalse(retained.discardUnstarted(second.id))

        val restoredAfterProcessLoss = ExternalModelRequestViewModel(savedState)
        val restored = requireNotNull(restoredAfterProcessLoss.request.value)
        assertEquals(second.id, restored.id)
        assertEquals(sharedUris, restored.uris)
        assertNull(restored.startedOperationId)
        assertTrue(restoredAfterProcessLoss.discardUnstarted(restored.id))
        assertNull(restoredAfterProcessLoss.request.value)
    }

    @Test
    fun modelIntentsAcceptSupportedDocumentsAndRejectUnsafeOrUnrelatedUris() {
        val explicit = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/document/no-extension"),
            "model/3mf",
        )
        val explicitWithParameter = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/document/no-extension"),
            "model/3mf; version=1",
        )
        val compatible = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/duck.STL"),
            "application/octet-stream",
        )
        val compatibleThreeMfZip = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/duck.3mf"),
            "application/zip",
        )
        val sharedUri = Uri.parse("content://example/duck.obj")
        val shared = Intent(Intent.ACTION_SEND).apply {
            type = "model/obj"
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }
        val sharedByClip = Intent(Intent.ACTION_SEND).apply {
            type = "model/stl"
            clipData = ClipData.newRawUri("duck", Uri.parse("content://example/duck.stl"))
        }
        val sharedMultipleUris = arrayListOf(
            Uri.parse("content://example/one.stl"),
            Uri.parse("content://example/two.stl"),
        )
        val sharedMultiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "model/stl"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, sharedMultipleUris)
            clipData = ClipData.newRawUri("one", sharedMultipleUris.first()).apply {
                addItem(ClipData.Item(sharedMultipleUris.last()))
            }
        }
        val network = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("https://example.invalid/private.stl"),
            "model/stl",
        )
        val file = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("file:///sdcard/Download/private.stl"),
            "model/stl",
        )
        val unrelated = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/manual.pdf"),
            "application/octet-stream",
        )
        val misleadingZip = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/not-a-zip.stl"),
            "application/zip",
        )
        val multiple = Intent(Intent.ACTION_SEND).apply {
            type = "model/stl"
            clipData = ClipData.newRawUri("first", Uri.parse("content://example/one.stl")).apply {
                addItem(ClipData.Item(Uri.parse("content://example/two.stl")))
            }
        }
        val conflictingMultiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "model/stl"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, sharedMultipleUris)
            clipData = ClipData.newRawUri("different", Uri.parse("content://example/other.stl"))
        }
        val duplicateMultiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "model/stl"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(sharedMultipleUris.first(), sharedMultipleUris.first()),
            )
        }
        val networkMultiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "model/stl"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(sharedMultipleUris.first(), Uri.parse("https://example.invalid/two.stl")),
            )
        }
        val malformedMultiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "model/stl"
            putStringArrayListExtra(Intent.EXTRA_STREAM, arrayListOf("not-a-content-uri"))
        }
        val tooManyMultiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "model/stl"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList<Uri>(ProjectStore.MAX_PROJECT_OBJECTS + 1).apply {
                    repeat(ProjectStore.MAX_PROJECT_OBJECTS + 1) { index ->
                        add(Uri.parse("content://example/model-$index.stl"))
                    }
                },
            )
        }

        assertEquals(explicit.data, modelDocumentUriOrNull(explicit))
        assertEquals(
            explicitWithParameter.data,
            modelDocumentUriOrNull(explicitWithParameter),
        )
        assertEquals(compatible.data, modelDocumentUriOrNull(compatible))
        assertEquals(compatibleThreeMfZip.data, modelDocumentUriOrNull(compatibleThreeMfZip))
        assertEquals(sharedUri, modelDocumentUriOrNull(shared))
        assertEquals(sharedByClip.clipData?.getItemAt(0)?.uri, modelDocumentUriOrNull(sharedByClip))
        assertEquals(sharedMultipleUris, modelDocumentUrisOrNull(sharedMultiple))
        assertNull(modelDocumentUriOrNull(sharedMultiple))
        assertNull(modelDocumentUriOrNull(network))
        assertNull(modelDocumentUriOrNull(file))
        assertNull(modelDocumentUriOrNull(unrelated))
        assertNull(modelDocumentUriOrNull(misleadingZip))
        assertNull(modelDocumentUriOrNull(multiple))
        assertNull(modelDocumentUrisOrNull(conflictingMultiple))
        assertNull(modelDocumentUrisOrNull(duplicateMultiple))
        assertNull(modelDocumentUrisOrNull(networkMultiple))
        assertNull(modelDocumentUrisOrNull(malformedMultiple))
        assertNull(modelDocumentUrisOrNull(tooManyMultiple))

        val packageManager = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        assertTrue(packageManager.resolvesMainActivity(explicit))
        assertTrue(packageManager.resolvesMainActivity(compatible))
        assertTrue(packageManager.resolvesMainActivity(compatibleThreeMfZip))
        assertTrue(packageManager.resolvesMainActivity(shared))
        assertTrue(packageManager.resolvesMainActivity(sharedMultiple))
        assertFalse(packageManager.resolvesMainActivity(network))
        assertFalse(packageManager.resolvesMainActivity(file))
        assertFalse(packageManager.resolvesMainActivity(unrelated))
        assertFalse(packageManager.resolvesMainActivity(misleadingZip))
    }

    @Test
    fun modelViewIntentSurvivesRecreationAndImportsExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val fixture = File(context.cacheDir, "external-model-intent.stl")
        projectRoot.deleteRecursively()
        instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
            fixture.outputStream().use(input::copyTo)
        }
        prepareImport(fixture)
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(context.packageName)
                .setDataAndType(BlockingImportProvider.MODEL_URI, "model/stl")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var retainedProject: ProjectTransferViewModel
                lateinit var retainedRequest: ExternalModelRequestViewModel
                scenario.onActivity { activity ->
                    retainedProject = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                    retainedRequest =
                        ViewModelProvider(activity)[ExternalModelRequestViewModel::class.java]
                }
                waitForImportProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }
                val operation = requireNotNull(retainedProject.state.value.activeEdit)
                assertEquals(ProjectEditKind.MODEL_IMPORT, operation.kind)
                assertEquals(operation.id, retainedRequest.request.value?.startedOperationId)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedProject,
                        ViewModelProvider(recreated)[ProjectTransferViewModel::class.java],
                    )
                    assertSame(
                        retainedRequest,
                        ViewModelProvider(recreated)[ExternalModelRequestViewModel::class.java],
                    )
                    assertEquals(operation.id, retainedRequest.request.value?.startedOperationId)
                }

                releaseImportProvider()
                waitUntil("model opened from Files was not committed") {
                    val state = retainedProject.state.value
                    !state.busy && state.history.current.allObjects.size == 1
                }
                waitUntil("completed external model request was not consumed") {
                    retainedRequest.request.value == null
                }
                assertEquals(
                    1,
                    retainedProject.state.value.history.current.allObjects.size,
                )
                scenario.recreate()
                assertEquals(
                    1,
                    retainedProject.state.value.history.current.allObjects.size,
                )
            }
        } finally {
            releaseImportProvider()
            fixture.delete()
            projectRoot.deleteRecursively()
        }
    }

    private fun android.content.pm.PackageManager.resolvesMainActivity(intent: Intent): Boolean =
        queryIntentActivities(intent, 0).any { result ->
            result.activityInfo.name == MainActivity::class.java.name
        }

    private fun prepareImport(fixture: File) {
        ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val extras = Bundle().apply {
                putParcelable(BlockingImportProvider.KEY_SOURCE_DESCRIPTOR, descriptor)
            }
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.MODEL_URI,
                BlockingImportProvider.METHOD_PREPARE,
                null,
                extras,
            )
        }
    }

    private fun releaseImportProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingImportProvider.MODEL_URI,
            BlockingImportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun waitForImportProvider(predicate: (Bundle) -> Boolean): Bundle {
        var status = Bundle.EMPTY
        waitUntil("blocking model provider did not reach the expected state") {
            status = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.MODEL_URI,
                BlockingImportProvider.METHOD_STATUS,
                null,
                null,
            ) ?: Bundle.EMPTY
            predicate(status)
        }
        return status
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
        const val WAIT_TIMEOUT_MILLIS = 60_000L
        const val WAIT_POLL_MILLIS = 50L
    }
}
