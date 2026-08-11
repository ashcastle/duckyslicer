package com.ashcastle.duckyslicer

import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectEditCancellationInstrumentedTest {
    @Test
    fun retainedModelImportCancellationInterruptsProviderOpenAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "blocked-model-open.stl")
        source.writeBytes(ByteArray(1_024))
        try {
            prepareProvider(BlockingImportProvider.METHOD_PREPARE_OPEN_BLOCK, source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retained: ProjectTransferViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                }
                waitUntil("Project session did not restore") {
                    retained.state.value.restored && !retained.state.value.busy
                }
                val baseline = retained.state.value
                assertTrue(retained.importModels(BlockingImportProvider.MODEL_URI))
                waitForProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[ProjectTransferViewModel::class.java],
                    )
                    assertTrue(retained.cancelActiveEdit())
                    assertFalse(retained.cancelActiveEdit())
                }

                waitUntil("Model provider-open cancellation did not settle") {
                    !retained.state.value.busy && retained.state.value.editCompletion != null
                }
                val completed = retained.state.value
                assertEquals(ProjectEditFailure.CANCELED, completed.editCompletion?.failure)
                assertFalse(requireNotNull(completed.editCompletion).sessionChanged)
                assertEquals(baseline.history, completed.history)
                assertEquals(baseline.sliceOptions, completed.sliceOptions)
                val status = waitForProvider {
                    it.getBoolean(BlockingImportProvider.KEY_COMPLETED)
                }
                assertEquals(0, status.getInt(BlockingImportProvider.KEY_BYTES))
                assertEquals(
                    "OperationCanceledException",
                    status.getString(BlockingImportProvider.KEY_ERROR),
                )
                waitForModelStagingCleanup()
                assertTrue(source.isFile)
            }
        } finally {
            releaseProvider()
            source.delete()
        }
    }

    @Test
    fun finalProjectOwnerStopsBlockedModelReadAndRemovesItsStaging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val store = ViewModelStore()
        val source = File(context.cacheDir, "blocked-model-read.stl")
        var storeCleared = false
        source.writeBytes(ByteArray(4 * 1_024 * 1_024) { 19 })
        try {
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("Project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }
            val baseline = model.state.value
            val modelRoot = context.filesDir.resolve(ProjectStore.PROJECT_DIRECTORY)
                .resolve("models")
            val baselineFiles = modelRoot.listFiles().orEmpty().map(File::getCanonicalPath).toSet()
            prepareProvider(BlockingImportProvider.METHOD_PREPARE, source)
            assertTrue(model.importModels(BlockingImportProvider.MODEL_URI))
            waitForProvider {
                it.getBoolean(BlockingImportProvider.KEY_STARTED) &&
                    it.getInt(BlockingImportProvider.KEY_BYTES) > 0
            }

            store.clear()
            storeCleared = true

            val status = waitForProvider {
                it.getBoolean(BlockingImportProvider.KEY_COMPLETED)
            }
            assertTrue(status.getInt(BlockingImportProvider.KEY_BYTES) < source.length())
            waitForModelStagingCleanup()
            assertEquals(
                baselineFiles,
                modelRoot.listFiles().orEmpty().map(File::getCanonicalPath).toSet(),
            )
            assertEquals(baseline.history.current, ProjectStore(context).loadProject().snapshot)
            assertTrue(source.isFile)
        } finally {
            if (!storeCleared) store.clear()
            releaseProvider()
            source.delete()
        }
    }

    @Test
    fun retainedOwnerCancelsOnlyItsNativeEditAndKeepsTheProjectUnchanged() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        val model = ViewModelProvider(
            store,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[ProjectTransferViewModel::class.java]
        var storeCleared = false
        try {
            waitUntil("Project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }
            val baseline = model.state.value
            val workerStarted = CountDownLatch(1)

            assertTrue(model.startCancellationProbeForTest(workerStarted::countDown))
            assertTrue(
                "The isolated project edit did not start",
                workerStarted.await(10, TimeUnit.SECONDS),
            )
            val busyWorkerPid = SlicerProcessClient.workerHealthForTest(application)
            val operation = requireNotNull(model.state.value.activeEdit)
            assertEquals(ProjectEditKind.AUTO_LAY, operation.kind)

            assertTrue(model.cancelActiveEdit())
            assertTrue(requireNotNull(model.state.value.activeEdit).cancellationRequested)
            assertFalse("Duplicate cancellation must be rejected", model.cancelActiveEdit())

            waitUntil("Project edit cancellation did not settle") {
                !model.state.value.busy && model.state.value.editCompletion != null
            }
            val completed = model.state.value
            assertEquals(ProjectEditFailure.CANCELED, completed.editCompletion?.failure)
            assertFalse(requireNotNull(completed.editCompletion).sessionChanged)
            assertEquals(baseline.history, completed.history)
            assertEquals(baseline.sliceOptions, completed.sliceOptions)
            assertEquals(baseline.sessionRevision, completed.sessionRevision)
            assertNull(completed.activeEdit)

            val restartedWorkerPid = SlicerProcessClient.workerHealthForTest(application)
            assertNotEquals(
                "Canceling the exact edit request must restart the isolated worker",
                busyWorkerPid,
                restartedWorkerPid,
            )

            model.consumeEditCompletion(requireNotNull(completed.editCompletion).id)
            assertTrue(model.startCancellationProbeForTest {})
            assertTrue("Pre-bind cancellation must be accepted", model.cancelActiveEdit())
            waitUntil("Pre-bind project cancellation did not settle") {
                !model.state.value.busy &&
                    model.state.value.editCompletion?.failure == ProjectEditFailure.CANCELED
            }
            assertEquals(baseline.history, model.state.value.history)
            assertEquals(baseline.sessionRevision, model.state.value.sessionRevision)

            model.consumeEditCompletion(requireNotNull(model.state.value.editCompletion).id)
            val finalOwnerWorkerStarted = CountDownLatch(1)
            assertTrue(model.startCancellationProbeForTest(finalOwnerWorkerStarted::countDown))
            assertTrue(
                "Final-owner project edit did not start",
                finalOwnerWorkerStarted.await(10, TimeUnit.SECONDS),
            )
            val finalOwnerWorkerPid = SlicerProcessClient.workerHealthForTest(application)
            store.clear()
            storeCleared = true
            waitUntil("Clearing the final owner did not stop its exact native edit") {
                runCatching {
                    SlicerProcessClient.workerHealthForTest(application) != finalOwnerWorkerPid
                }.getOrDefault(false)
            }
        } finally {
            if (!storeCleared) store.clear()
        }
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20)
        }
        assertTrue(message, predicate())
    }

    private fun prepareProvider(method: String, source: File) {
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val extras = Bundle().apply {
                putParcelable(BlockingImportProvider.KEY_SOURCE_DESCRIPTOR, descriptor)
            }
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.MODEL_URI,
                method,
                null,
                extras,
            )
        }
    }

    private fun releaseProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingImportProvider.MODEL_URI,
            BlockingImportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun waitForProvider(predicate: (Bundle) -> Boolean): Bundle {
        var status = Bundle.EMPTY
        waitUntil("Blocking model provider did not reach the expected state") {
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

    private fun waitForModelStagingCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        waitUntil("Model import staging was not removed") {
            val root = context.filesDir.resolve(ProjectStore.PROJECT_DIRECTORY)
            root.listFiles().orEmpty().none {
                it.name.startsWith(ProjectStore.MODEL_IMPORT_DIRECTORY_PREFIX)
            }
        }
    }
}
