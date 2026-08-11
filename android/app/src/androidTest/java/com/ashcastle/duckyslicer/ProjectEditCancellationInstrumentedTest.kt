package com.ashcastle.duckyslicer

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectEditCancellationInstrumentedTest {
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
}
