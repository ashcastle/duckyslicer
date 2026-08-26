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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GcodeOpenIntentInstrumentedTest {
    @Test
    fun acceptsExplicitOrNamedContentDocumentsAndRejectsBroaderUris() {
        val explicit = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/document/no-extension"),
            "text/x.gcode",
        )
        val named = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/duck.GCODE"),
            "application/octet-stream",
        )
        val network = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("https://example.invalid/duck.gcode"),
            "text/x.gcode",
        )
        val unrelated = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://example/Download/notes.txt"),
            "text/plain",
        )
        val sharedUri = Uri.parse("content://example/duck.gcode")
        val shared = Intent(Intent.ACTION_SEND).apply {
            type = "text/x.gcode"
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }
        val sharedByClip = Intent(Intent.ACTION_SEND).apply {
            type = "application/gcode"
            clipData = ClipData.newRawUri("duck", sharedUri)
        }
        val conflictingShare = Intent(Intent.ACTION_SEND).apply {
            type = "text/x.gcode"
            clipData = ClipData.newRawUri("duck", sharedUri)
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example/other.gcode"))
        }
        val multipleShare = Intent(Intent.ACTION_SEND).apply {
            type = "text/x.gcode"
            clipData = ClipData.newRawUri("first", sharedUri).apply {
                addItem(ClipData.Item(Uri.parse("content://example/second.gcode")))
            }
        }

        assertEquals(explicit.data, gcodeDocumentUriOrNull(explicit))
        assertEquals(named.data, gcodeDocumentUriOrNull(named))
        assertEquals(sharedUri, gcodeDocumentUriOrNull(shared))
        assertEquals(sharedUri, gcodeDocumentUriOrNull(sharedByClip))
        assertNull(gcodeDocumentUriOrNull(network))
        assertNull(gcodeDocumentUriOrNull(unrelated))
        assertNull(gcodeDocumentUriOrNull(conflictingShare))
        assertNull(gcodeDocumentUriOrNull(multipleShare))

        val packageManager = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        assertTrue(packageManager.resolvesMainActivity(explicit))
        assertTrue(packageManager.resolvesMainActivity(named))
        assertTrue(packageManager.resolvesMainActivity(shared))
        assertFalse(packageManager.resolvesMainActivity(network))
        assertFalse(packageManager.resolvesMainActivity(unrelated))
    }

    @Test
    fun retainedRequestIsClaimedAndConsumedExactlyOnce() {
        val savedState = SavedStateHandle()
        val model = ExternalGcodeRequestViewModel(savedState)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/x.gcode"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example/duck.gcode"))
        }

        assertTrue(model.enqueue(intent))
        val request = requireNotNull(model.request.value)
        assertTrue(model.markStarted(request.id, 41L))
        assertFalse(model.markStarted(request.id, 42L))
        assertFalse(model.consume(request.id, 42L))
        assertTrue(model.consume(request.id, 41L))
        assertNull(model.request.value)
    }

    @Test
    fun sharedGcodeSurvivesActivityRecreationAndImportsExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "shared-gcode-preview.gcode")
        fixture.writeText(
            "; filament_colour = #123456\n;LAYER_CHANGE\n;Z:0.2\n" +
                "G1 X0 Y0 Z0.2\nG1 X5 Y0 E1\n",
        )
        prepareDirectImport(fixture)
        val outputDirectory = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY)
        outputDirectory.deleteRecursively()
        val intent = Intent(Intent.ACTION_SEND).apply {
            setPackage(context.packageName)
            type = "text/x.gcode"
            putExtra(Intent.EXTRA_STREAM, BlockingImportProvider.GCODE_URI)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var firstModel: GcodePreviewImportViewModel
                scenario.onActivity { activity ->
                    firstModel = ViewModelProvider(activity)[GcodePreviewImportViewModel::class.java]
                }
                waitUntil("shared G-code was not previewed") {
                    val state = firstModel.state.value
                    !state.busy && state.document?.preview != null
                }
                val firstOutput = requireNotNull(firstModel.state.value.document).output.canonicalFile
                assertEquals(listOf(0x123456), firstModel.state.value.document?.filamentColors)
                assertEquals(1, outputDirectory.listFiles()?.count(File::isFile))

                scenario.recreate()
                lateinit var restoredModel: GcodePreviewImportViewModel
                scenario.onActivity { activity ->
                    restoredModel = ViewModelProvider(activity)[GcodePreviewImportViewModel::class.java]
                }
                waitUntil("shared G-code Preview did not survive recreation") {
                    val state = restoredModel.state.value
                    !state.busy && state.document?.preview != null
                }
                assertEquals(
                    firstOutput,
                    requireNotNull(restoredModel.state.value.document).output.canonicalFile,
                )
                assertEquals(1, outputDirectory.listFiles()?.count(File::isFile))
                restoredModel.clearDocument()
            }
        } finally {
            fixture.delete()
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun importedDocumentIsCopiedParsedAndPreviewedOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "gcode-preview-import-fixture.gcode")
        fixture.writeText(
            """
            ;TIME:42
            ;Filament used: 1.25m
            ; filament_colour = #123456;#ABCDEF
            ;LAYER_CHANGE
            ;Z:0.2
            ;HEIGHT:0.2
            ;TYPE:Outer wall
            G1 X0 Y0 Z0.2 F1200
            G1 X10 Y0 E1
            G1 X10 Y10 E2
            ;LAYER_CHANGE
            ;Z:0.4
            ;HEIGHT:0.2
            G1 X0 Y0 Z0.4
            G1 X10 Y0 E3
            """.trimIndent(),
        )
        prepareDirectImport(fixture)
        val model = GcodePreviewImportViewModel(
            ApplicationProvider.getApplicationContext(),
            SavedStateHandle(),
        )
        try {
            val operationId = requireNotNull(model.open(BlockingImportProvider.GCODE_URI))
            waitUntil("G-code document was not previewed") {
                val state = model.state.value
                !state.busy && state.completionOperationId == operationId
            }

            val state = model.state.value
            assertEquals(GcodePreviewImportStatus.SUCCEEDED, state.status)
            val document = requireNotNull(state.document)
            assertEquals("preview.gcode", document.displayName)
            assertTrue(document.output.isFile)
            assertEquals(
                File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY).canonicalFile,
                document.output.canonicalFile.parentFile,
            )
            val preview = requireNotNull(document.preview)
            assertTrue(preview.segments.isNotEmpty())
            assertEquals(2, preview.layerCount)
            assertEquals(1.25f, document.summary.filamentMeters ?: Float.NaN, 0.001f)
            assertTrue(requireNotNull(document.summary.duration).underOneMinute)
            assertEquals(listOf(0x123456, 0xABCDEF), document.filamentColors)

            val importedOutput = document.output
            model.clearDocument()
            assertNull(model.state.value.document)
            assertFalse(importedOutput.exists())
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun interruptedReplacementRetriesWithoutDiscardingThePreviousPreviewFirst() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val outputDirectory = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY).apply {
            mkdirs()
        }
        val previous = File(outputDirectory, "previous-preview.gcode").apply {
            writeText(";LAYER_CHANGE\n;Z:0.2\nG1 X0 Y0 Z0.2\nG1 X2 Y0 E1\n")
        }
        val replacement = File(context.cacheDir, "replacement-preview.gcode").apply {
            writeText(
                ";TIME:120\n;LAYER_CHANGE\n;Z:0.2\n" +
                    "G1 X0 Y0 Z0.2\nG1 X5 Y0 E1\n",
            )
        }
        prepareDirectImport(replacement)
        val savedState = SavedStateHandle(
            mapOf(
                "gcode_preview_document_path" to previous.absolutePath,
                "gcode_preview_display_name" to "previous.gcode",
                "gcode_preview_active_uri" to BlockingImportProvider.GCODE_URI.toString(),
            ),
        )
        val model = GcodePreviewImportViewModel(
            ApplicationProvider.getApplicationContext(),
            savedState,
        )
        try {
            assertTrue(model.state.value.importing)
            assertEquals("previous.gcode", model.state.value.document?.displayName)
            waitUntil("interrupted G-code replacement was not retried") {
                val state = model.state.value
                !state.busy && state.status == GcodePreviewImportStatus.SUCCEEDED
            }
            assertEquals("preview.gcode", model.state.value.document?.displayName)
            assertFalse(previous.exists())
            model.clearDocument()
        } finally {
            replacement.delete()
            previous.delete()
        }
    }

    @Test
    fun providerOpenCanBeCanceledWithoutLeavingAPartialPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "cancel-preview.gcode").apply {
            writeText(";LAYER_CHANGE\n;Z:0.2\nG1 X0 Y0 Z0.2\nG1 X5 Y0 E1\n")
        }
        prepareImport(BlockingImportProvider.METHOD_PREPARE_OPEN_BLOCK, fixture)
        val model = GcodePreviewImportViewModel(
            ApplicationProvider.getApplicationContext(),
            SavedStateHandle(),
        )
        try {
            val operationId = requireNotNull(model.open(BlockingImportProvider.GCODE_URI))
            waitUntil("G-code provider open did not start") {
                importProviderStatus().getBoolean(BlockingImportProvider.KEY_STARTED)
            }
            model.cancel()
            assertTrue(model.state.value.cancellationRequested)
            waitUntil("canceled G-code import did not finish") {
                val state = model.state.value
                !state.busy && state.completionOperationId == operationId
            }
            assertEquals(GcodePreviewImportStatus.CANCELED, model.state.value.status)
            assertNull(model.state.value.document)
        } finally {
            releaseImportProvider()
            fixture.delete()
        }
    }

    private fun android.content.pm.PackageManager.resolvesMainActivity(intent: Intent): Boolean =
        queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).any {
            it.activityInfo.name == MainActivity::class.java.name
        }

    private fun prepareDirectImport(fixture: File) {
        prepareImport(BlockingImportProvider.METHOD_PREPARE_DIRECT, fixture)
    }

    private fun prepareImport(method: String, fixture: File) {
        ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val extras = Bundle().apply {
                putParcelable(BlockingImportProvider.KEY_SOURCE_DESCRIPTOR, descriptor)
            }
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.GCODE_URI,
                method,
                null,
                extras,
            )
        }
    }

    private fun releaseImportProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingImportProvider.GCODE_URI,
            BlockingImportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun importProviderStatus(): Bundle =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingImportProvider.GCODE_URI,
            BlockingImportProvider.METHOD_STATUS,
            null,
            null,
        ) ?: Bundle.EMPTY

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
