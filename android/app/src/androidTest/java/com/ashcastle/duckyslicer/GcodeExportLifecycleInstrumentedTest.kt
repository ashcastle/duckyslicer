package com.ashcastle.duckyslicer

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GcodeExportLifecycleInstrumentedTest {
    @get:Rule
    val blockingProviderProcess = BlockingProviderProcessRule()

    @Test
    fun batchProviderRejectsTraversalDocumentNamesOutsideItsRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val escaped = File(context.cacheDir, "batch-export-escape.gcode")
        escaped.delete()
        prepareBatchProvider(BatchExportDocumentsProvider.METHOD_PREPARE_SUCCESS)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            BatchExportDocumentsProvider.TREE_URI,
            BatchExportDocumentsProvider.ROOT_ID,
        )

        val result = runCatching {
            DocumentsContract.createDocument(
                resolver,
                parent,
                "application/octet-stream",
                "../batch-export-escape.gcode",
            )
        }

        assertTrue("Traversal document names must be rejected", result.isFailure)
        assertFalse("The provider must not write outside its root", escaped.exists())
        assertTrue(
            "Rejected names must not leave a provider document",
            batchProviderFiles().isEmpty(),
        )
    }

    @Test
    fun gcodeExportSurvivesActivityRecreationAndCopiesTheExactArtifactOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(BlockingExportProvider.URI, BlockingExportProvider.METHOD_PREPARE, null, null)
        val output = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY)
            .apply(File::mkdirs)
            .resolve("retained-export.gcode")
        val payload = buildPayload()
        output.writeBytes(payload)
        val outcome = SliceOutcome(output, 10, 12f, 34f, 0.1f)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retainedModel: GcodeExportViewModel
                scenario.onActivity { activity ->
                    retainedModel = ViewModelProvider(activity)[GcodeExportViewModel::class.java]
                    assertTrue(retainedModel.export(BlockingExportProvider.URI, outcome))
                }
                waitForProvider { status -> status.getBoolean(BlockingExportProvider.KEY_STARTED) }
                assertTrue(retainedModel.state.value.busy)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedModel,
                        ViewModelProvider(recreated)[GcodeExportViewModel::class.java],
                    )
                    assertFalse(retainedModel.export(BlockingExportProvider.URI, outcome))
                }
                resolver.call(
                    BlockingExportProvider.URI,
                    BlockingExportProvider.METHOD_RELEASE,
                    null,
                    null,
                )
                waitForExport(retainedModel)
                val status = waitForProvider { value ->
                    value.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertFalse(status.getBoolean(BlockingExportProvider.KEY_DELETED))
                assertEquals("", status.getString(BlockingExportProvider.KEY_ERROR))
                assertEquals(payload.size, status.getInt(BlockingExportProvider.KEY_BYTES))
                assertEquals(sha256(payload), status.getString(BlockingExportProvider.KEY_SHA256))
            }
        } finally {
            resolver.call(
                BlockingExportProvider.URI,
                BlockingExportProvider.METHOD_RELEASE,
                null,
                null,
            )
            output.delete()
        }
    }

    @Test
    fun retainedCancellationStopsTheExactCopyAndDeletesThePartialDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(BlockingExportProvider.URI, BlockingExportProvider.METHOD_PREPARE, null, null)
        val output = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY)
            .apply(File::mkdirs)
            .resolve("cancel-retained-export.gcode")
        val payload = buildPayload()
        output.writeBytes(payload)
        val outcome = SliceOutcome(output, 10, 12f, 34f, 0.1f)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retainedModel: GcodeExportViewModel
                scenario.onActivity { activity ->
                    retainedModel = ViewModelProvider(activity)[GcodeExportViewModel::class.java]
                    assertTrue(retainedModel.export(BlockingExportProvider.URI, outcome))
                }
                waitForProvider { status -> status.getBoolean(BlockingExportProvider.KEY_STARTED) }

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedModel,
                        ViewModelProvider(recreated)[GcodeExportViewModel::class.java],
                    )
                    assertTrue(retainedModel.cancelActiveExport())
                    assertFalse(retainedModel.cancelActiveExport())
                }
                waitForExport(retainedModel)
                val status = waitForProvider { value ->
                    value.getBoolean(BlockingExportProvider.KEY_DELETED) &&
                        value.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertTrue(status.getInt(BlockingExportProvider.KEY_BYTES) < payload.size)
                assertTrue(output.isFile)
            }
        } finally {
            resolver.call(
                BlockingExportProvider.URI,
                BlockingExportProvider.METHOD_RELEASE,
                null,
                null,
            )
            output.delete()
        }
    }

    @Test
    fun finalOwnerClearStopsItsCopyAndDeletesThePartialDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(BlockingExportProvider.URI, BlockingExportProvider.METHOD_PREPARE, null, null)
        val output = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY)
            .apply(File::mkdirs)
            .resolve("clear-retained-export.gcode")
        output.writeBytes(buildPayload())
        val outcome = SliceOutcome(output, 10, 12f, 34f, 0.1f)
        val store = ViewModelStore()
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[GcodeExportViewModel::class.java]
            assertTrue(model.export(BlockingExportProvider.URI, outcome))
            waitForProvider { status -> status.getBoolean(BlockingExportProvider.KEY_STARTED) }

            store.clear()

            val status = waitForProvider { value ->
                value.getBoolean(BlockingExportProvider.KEY_DELETED) &&
                    value.getBoolean(BlockingExportProvider.KEY_COMPLETED)
            }
            assertTrue(status.getInt(BlockingExportProvider.KEY_BYTES) < PAYLOAD_BYTES)
            assertTrue(output.isFile)
        } finally {
            store.clear()
            resolver.call(
                BlockingExportProvider.URI,
                BlockingExportProvider.METHOD_RELEASE,
                null,
                null,
            )
            output.delete()
        }
    }

    @Test
    fun allPlateExportSurvivesRecreationAndCreatesEveryNamedDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        prepareBatchProvider(BatchExportDocumentsProvider.METHOD_PREPARE_BLOCK_SECOND)
        val fixture = batchFixture()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retainedModel: GcodeExportViewModel
                scenario.onActivity { activity ->
                    retainedModel = ViewModelProvider(activity)[GcodeExportViewModel::class.java]
                    assertTrue(retainedModel.exportAll(BatchExportDocumentsProvider.TREE_URI, fixture))
                }
                waitForBatchProvider { it.getBoolean(BatchExportDocumentsProvider.KEY_SECOND_OPEN_STARTED) }
                assertEquals(2, retainedModel.state.value.currentFile)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedModel,
                        ViewModelProvider(recreated)[GcodeExportViewModel::class.java],
                    )
                    assertFalse(
                        retainedModel.exportAll(BatchExportDocumentsProvider.TREE_URI, fixture),
                    )
                }
                resolver.call(
                    BatchExportDocumentsProvider.TREE_URI,
                    BatchExportDocumentsProvider.METHOD_RELEASE,
                    null,
                    null,
                )
                waitForExport(retainedModel)

                val status = waitForBatchProvider {
                    it.getStringArrayList(BatchExportDocumentsProvider.KEY_FILES)?.size == 2
                }
                assertEquals(
                    fixture.entries.map(GcodeExportEntry::displayName),
                    status.getStringArrayList(BatchExportDocumentsProvider.KEY_FILES),
                )
                assertEquals(
                    fixture.entries.map { it.outcome.output.readText() },
                    status.getStringArrayList(BatchExportDocumentsProvider.KEY_CONTENTS),
                )
                assertFalse(retainedModel.state.value.busy)
            }
        } finally {
            resolver.call(
                BatchExportDocumentsProvider.TREE_URI,
                BatchExportDocumentsProvider.METHOD_RELEASE,
                null,
                null,
            )
            fixture.entries.forEach { it.outcome.output.delete() }
            prepareBatchProvider(BatchExportDocumentsProvider.METHOD_PREPARE_SUCCESS)
        }
    }

    @Test
    fun laterBatchFailureDeletesEarlierDocumentsAndKeepsPrivateArtifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        prepareBatchProvider(BatchExportDocumentsProvider.METHOD_PREPARE_FAIL_SECOND)
        val fixture = batchFixture()
        val store = ViewModelStore()
        try {
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[GcodeExportViewModel::class.java]
            assertTrue(model.exportAll(BatchExportDocumentsProvider.TREE_URI, fixture))
            waitForExport(model)

            assertEquals(GcodeExportResult.FAILED, requireNotNull(model.state.value.completion).result)
            assertTrue(batchProviderFiles().isEmpty())
            assertTrue(fixture.entries.all { it.outcome.output.isFile })
        } finally {
            store.clear()
            fixture.entries.forEach { it.outcome.output.delete() }
            prepareBatchProvider(BatchExportDocumentsProvider.METHOD_PREPARE_SUCCESS)
        }
    }

    @Test
    fun batchCancellationDeletesEveryDocumentCreatedByThatOperation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        prepareBatchProvider(BatchExportDocumentsProvider.METHOD_PREPARE_BLOCK_SECOND)
        val fixture = batchFixture()
        val store = ViewModelStore()
        try {
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[GcodeExportViewModel::class.java]
            assertTrue(model.exportAll(BatchExportDocumentsProvider.TREE_URI, fixture))
            waitForBatchProvider { it.getBoolean(BatchExportDocumentsProvider.KEY_SECOND_OPEN_STARTED) }
            assertTrue(model.cancelActiveExport())
            waitForExport(model)

            assertEquals(GcodeExportResult.CANCELED, requireNotNull(model.state.value.completion).result)
            assertTrue(batchProviderFiles().isEmpty())
            assertTrue(fixture.entries.all { it.outcome.output.isFile })
        } finally {
            store.clear()
            context.contentResolver.call(
                BatchExportDocumentsProvider.TREE_URI,
                BatchExportDocumentsProvider.METHOD_RELEASE,
                null,
                null,
            )
            fixture.entries.forEach { it.outcome.output.delete() }
            prepareBatchProvider(BatchExportDocumentsProvider.METHOD_PREPARE_SUCCESS)
        }
    }

    private fun waitForExport(model: GcodeExportViewModel) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!model.state.value.busy) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for retained G-code export")
    }

    private fun waitForProvider(condition: (Bundle) -> Boolean): Bundle {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        var latest = Bundle.EMPTY
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = requireNotNull(
                resolver.call(
                    BlockingExportProvider.URI,
                    BlockingExportProvider.METHOD_STATUS,
                    null,
                    null,
                ),
            )
            if (condition(latest)) return latest
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for blocking export provider: $latest")
    }

    private fun prepareBatchProvider(method: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.targetContext.contentResolver.call(
            BatchExportDocumentsProvider.TREE_URI,
            method,
            null,
            null,
        )
    }

    private fun waitForBatchProvider(condition: (Bundle) -> Boolean): Bundle {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        var latest = Bundle.EMPTY
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = requireNotNull(
                resolver.call(
                    BatchExportDocumentsProvider.TREE_URI,
                    BatchExportDocumentsProvider.METHOD_STATUS,
                    null,
                    null,
                ),
            )
            if (condition(latest)) return latest
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for batch export provider: $latest")
    }

    private fun batchFixture(): GcodeExportBatch {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputRoot = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY)
            .apply(File::mkdirs)
        return GcodeExportBatch(
            listOf(
                GcodeExportEntry(
                    "plate-01-first.gcode",
                    SliceOutcome(
                        outputRoot.resolve("batch-first.gcode").apply { writeText("G1 X1 E1\n") },
                        1,
                        1f,
                        1f,
                        1f,
                    ),
                ),
                GcodeExportEntry(
                    "plate-02-second.gcode",
                    SliceOutcome(
                        outputRoot.resolve("batch-second.gcode").apply { writeText("G1 X2 E2\n") },
                        1,
                        1f,
                        1f,
                        1f,
                    ),
                ),
            ),
        )
    }

    private fun batchProviderFiles(): List<String> = requireNotNull(
        waitForBatchProvider { true }
            .getStringArrayList(BatchExportDocumentsProvider.KEY_FILES),
    )

    private fun buildPayload(): ByteArray {
        val line = "G1 X10 Y10 E1.25\n".toByteArray()
        return ByteArray(PAYLOAD_BYTES) { index -> line[index % line.size] }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PAYLOAD_BYTES = 2 * 1_024 * 1_024
        const val WAIT_TIMEOUT_MILLIS = 15_000L
        const val WAIT_POLL_MILLIS = 25L
    }
}
