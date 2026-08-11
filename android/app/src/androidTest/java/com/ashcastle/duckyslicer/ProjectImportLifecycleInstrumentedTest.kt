package com.ashcastle.duckyslicer

import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectImportLifecycleInstrumentedTest {
    @Test
    fun projectImportCancellationSurvivesRecreationAndPreservesTheCurrentProject() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var fixture: ImportFixture? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retained: ProjectTransferViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                }
                waitUntil("project session did not restore") {
                    retained.state.value.restored && !retained.state.value.busy
                }
                val baseline = retained.state.value
                fixture = importFixture(baseline.sliceOptions)
                prepareProvider(BlockingImportProvider.METHOD_PREPARE, requireNotNull(fixture))
                scenario.onActivity {
                    assertTrue(retained.importProject(BlockingImportProvider.URI))
                }
                waitForProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[ProjectTransferViewModel::class.java],
                    )
                    assertTrue(retained.cancelProjectImport())
                    assertFalse(retained.cancelProjectImport())
                }
                waitUntil("project import cancellation did not settle") {
                    !retained.state.value.busy
                }
                val status = waitForProvider {
                    it.getBoolean(BlockingImportProvider.KEY_COMPLETED)
                }
                assertTrue(status.getInt(BlockingImportProvider.KEY_BYTES) < requireNotNull(fixture).archive.length())
                assertEquals(baseline.history, retained.state.value.history)
                assertEquals(baseline.sliceOptions, retained.state.value.sliceOptions)
                assertTrue(requireNotNull(fixture).archive.isFile)
                waitForStagingCleanup()
            }
        } finally {
            releaseProvider()
            fixture?.delete()
        }
    }

    @Test
    fun projectImportCancellationInterruptsProviderOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ViewModelStore()
        var fixture: ImportFixture? = null
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }
            val baseline = model.state.value
            fixture = importFixture(baseline.sliceOptions)
            prepareProvider(
                BlockingImportProvider.METHOD_PREPARE_OPEN_BLOCK,
                requireNotNull(fixture),
            )
            assertTrue(model.importProject(BlockingImportProvider.URI))
            waitForProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }

            assertTrue(model.cancelProjectImport())
            assertFalse(model.cancelProjectImport())
            waitUntil("provider-open cancellation did not settle") {
                !model.state.value.busy
            }
            val status = waitForProvider {
                it.getBoolean(BlockingImportProvider.KEY_COMPLETED)
            }
            assertEquals(0, status.getInt(BlockingImportProvider.KEY_BYTES))
            assertEquals(
                "OperationCanceledException",
                status.getString(BlockingImportProvider.KEY_ERROR),
            )
            assertEquals(baseline.history, model.state.value.history)
            assertEquals(baseline.sliceOptions, model.state.value.sliceOptions)
            assertTrue(requireNotNull(fixture).archive.isFile)
            waitForStagingCleanup()
        } finally {
            store.clear()
            releaseProvider()
            fixture?.delete()
        }
    }

    @Test
    fun finalProjectOwnerClearStopsItsImportAndPreservesTheCurrentProject() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ViewModelStore()
        var fixture: ImportFixture? = null
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }
            val original = model.state.value
            fixture = importFixture(original.sliceOptions)
            prepareProvider(BlockingImportProvider.METHOD_PREPARE, requireNotNull(fixture))
            val unsavedOptions = original.sliceOptions.copy(
                fillDensity = if (original.sliceOptions.fillDensity == 0.17f) 0.18f else 0.17f,
            )
            assertTrue(
                model.updateSession(
                    original.history,
                    original.history,
                    original.sliceOptions,
                    unsavedOptions,
                ),
            )
            val baseline = model.state.value
            assertTrue(model.importProject(BlockingImportProvider.URI))
            waitForProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }

            store.clear()

            val status = waitForProvider {
                it.getBoolean(BlockingImportProvider.KEY_COMPLETED)
            }
            assertTrue(status.getInt(BlockingImportProvider.KEY_BYTES) < requireNotNull(fixture).archive.length())
            assertTrue(requireNotNull(fixture).archive.isFile)
            waitForStagingCleanup()
            val restored = ProjectStore(context).loadProject()
            assertEquals(baseline.history.current, restored.snapshot)
            val restoredOptions = restored.sliceOptions ?: SliceOptions()
            assertEquals(baseline.sliceOptions.fillDensity, restoredOptions.fillDensity)
            assertFalse(original.sliceOptions.fillDensity == restoredOptions.fillDensity)
        } finally {
            store.clear()
            releaseProvider()
            fixture?.delete()
        }
    }

    private fun importFixture(options: SliceOptions): ImportFixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceRoot = context.cacheDir.resolve("project-import-${UUID.randomUUID()}")
        val source = ProjectStore(sourceRoot, ::inspectedModel)
        val modelFile = source.createModelDestination("incoming.stl")
        val payload = ByteArray(LARGE_MODEL_BYTES)
        Random(83).nextBytes(payload)
        modelFile.writeBytes(payload)
        val archive = context.filesDir.resolve("project-import-${UUID.randomUUID()}.duckyproject")
        archive.outputStream().use { output ->
            source.exportArchive(
                ProjectSnapshot(
                    objects = listOf(ProjectObject("incoming", inspectedModel(modelFile))),
                    selectedObjectId = "incoming",
                ),
                options,
                output,
            )
        }
        return ImportFixture(archive, sourceRoot)
    }

    private fun inspectedModel(file: File) = ModelInfo(
        fileName = file.name,
        triangles = 1,
        dimensions = listOf(1.0, 1.0, 1.0),
        localPath = file.canonicalPath,
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(1.0, 1.0, 1.0),
        previewTriangles = FloatArray(9),
    )

    private fun prepareProvider(method: String, fixture: ImportFixture) {
        ParcelFileDescriptor.open(
            fixture.archive,
            ParcelFileDescriptor.MODE_READ_ONLY,
        ).use { descriptor ->
            val extras = Bundle().apply {
                putParcelable(BlockingImportProvider.KEY_SOURCE_DESCRIPTOR, descriptor)
            }
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.URI,
                method,
                null,
                extras,
            )
        }
    }

    private fun releaseProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingImportProvider.URI,
            BlockingImportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun waitForProvider(predicate: (Bundle) -> Boolean): Bundle {
        var status = Bundle.EMPTY
        waitUntil("blocking import provider did not reach the expected state") {
            status = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.URI,
                BlockingImportProvider.METHOD_STATUS,
                null,
                null,
            ) ?: Bundle.EMPTY
            predicate(status)
        }
        return status
    }

    private fun waitForStagingCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        waitUntil("project import staging was not removed") {
            val root = context.filesDir.resolve(ProjectStore.PROJECT_DIRECTORY)
            root.listFiles().orEmpty().none { it.name.startsWith(".archive-") }
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        assertTrue(message, condition())
    }

    private data class ImportFixture(
        val archive: File,
        val sourceRoot: File,
    ) {
        fun delete() {
            archive.delete()
            sourceRoot.deleteRecursively()
        }
    }

    private companion object {
        const val LARGE_MODEL_BYTES = 4 * 1_024 * 1_024
        const val WAIT_TIMEOUT_MILLIS = 15_000L
        const val WAIT_POLL_MILLIS = 25L
    }
}
