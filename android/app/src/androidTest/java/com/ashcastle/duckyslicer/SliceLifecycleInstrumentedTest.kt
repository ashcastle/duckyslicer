package com.ashcastle.duckyslicer

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SliceLifecycleInstrumentedTest {
    @Test
    fun activeSliceSurvivesActivityRecreationAndCompletes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storedModel = File(context.cacheDir, "lifecycle-rotation.stl")
        instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
            storedModel.outputStream().use(input::copyTo)
        }
        val model = ModelInfo.fromJson(
            NativeEngine.inspectStl(storedModel.absolutePath),
            storedModel.absolutePath,
        )
        val projectObject = ProjectObject(
            id = "lifecycle-rotation",
            model = model,
            transform = ModelTransform(scale = 1.5f),
        )
        val options = SliceOptions().copy(
            layerHeight = 0.02f,
            firstLayerHeight = 0.04f,
            perimeters = 6,
            fillDensity = 0.50f,
        )

        try {
            var retainedModel: SliceOperationViewModel? = null
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val initial = ViewModelProvider(activity)[SliceOperationViewModel::class.java]
                    retainedModel = initial
                    assertTrue(initial.start(listOf(projectObject), options))
                    assertTrue("The slice must be active before recreation", initial.state.value.slicing)
                }

                val retained = requireNotNull(retainedModel)
                val foregroundDeadline = SystemClock.elapsedRealtime() + SERVICE_STATE_TIMEOUT_MILLIS
                while (
                    !SlicerProcessClient.workerIsForegroundForTest(context) &&
                    retained.state.value.busy &&
                    SystemClock.elapsedRealtime() < foregroundDeadline
                ) {
                    SystemClock.sleep(20)
                }
                assertTrue(
                    "The user-started slice must promote the isolated worker",
                    SlicerProcessClient.workerIsForegroundForTest(context),
                )

                scenario.moveToState(Lifecycle.State.CREATED)
                val backgroundState = retained.state.value
                assertFalse(
                    "Stopping the Activity must not cancel the slice",
                    backgroundState.terminalStatus == SliceTerminalStatus.CANCELED,
                )
                if (backgroundState.slicing) {
                    assertTrue(
                        "The slicer service must be foreground while a stopped Activity is slicing",
                        SlicerProcessClient.workerIsForegroundForTest(context),
                    )
                } else {
                    assertTrue(
                        "A slice that finishes during the background transition must retain its result",
                        backgroundState.previewLoading || backgroundState.outcome != null,
                    )
                }

                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                scenario.onActivity { activity ->
                    val recreated = ViewModelProvider(activity)[SliceOperationViewModel::class.java]
                    assertSame("Configuration recreation must retain the operation", retainedModel, recreated)
                    assertFalse(
                        "Recreation must not report disposal cancellation",
                        recreated.state.value.terminalStatus == SliceTerminalStatus.CANCELED,
                    )
                }
                scenario.moveToState(Lifecycle.State.CREATED)

                val deadline = SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MILLIS
                while (retained.state.value.busy && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(50)
                }
                val completed = retained.state.value
                assertFalse("The retained slice must finish before timeout", completed.busy)
                assertEquals(SliceTerminalStatus.NONE, completed.terminalStatus)
                val outcome = requireNotNull(completed.outcome) {
                    "The retained slice must produce G-code"
                }
                assertNotNull("The retained slice must produce a Preview", completed.preview)
                assertTrue(outcome.isRestorableFrom(context.filesDir))
                val stoppedDeadline = SystemClock.elapsedRealtime() + SERVICE_STATE_TIMEOUT_MILLIS
                while (
                    SlicerProcessClient.workerIsForegroundForTest(context) &&
                    SystemClock.elapsedRealtime() < stoppedDeadline
                ) {
                    SystemClock.sleep(20)
                }
                assertFalse(
                    "The foreground service must stop after Preview generation",
                    SlicerProcessClient.workerIsForegroundForTest(context),
                )
                scenario.moveToState(Lifecycle.State.RESUMED)

                scenario.onActivity {
                    assertTrue(retained.loadPreview(outcome, 0, outcome.layers / 2))
                    assertTrue("Preview range loading must be active before recreation", retained.state.value.previewLoading)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertSame(
                        "Configuration recreation must retain Preview range loading",
                        retained,
                        ViewModelProvider(activity)[SliceOperationViewModel::class.java],
                    )
                }
                val previewDeadline = SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MILLIS
                while (retained.state.value.previewLoading && SystemClock.elapsedRealtime() < previewDeadline) {
                    SystemClock.sleep(50)
                }
                assertFalse("Retained Preview range loading must finish", retained.state.value.previewLoading)
                assertEquals(SliceTerminalStatus.NONE, retained.state.value.terminalStatus)
                assertNotNull("Retained range loading must replace the Preview", retained.state.value.preview)

                scenario.onActivity {
                    retained.clearCompleted()
                    assertTrue(retained.start(listOf(projectObject), options))
                    retained.cancel()
                    assertTrue("Cancellation must remain visible while preprocessing", retained.state.value.cancellationRequested)
                }
                val cancellationDeadline = SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MILLIS
                while (retained.state.value.busy && SystemClock.elapsedRealtime() < cancellationDeadline) {
                    SystemClock.sleep(50)
                }
                assertFalse("Pre-service cancellation must finish", retained.state.value.busy)
                assertEquals(SliceTerminalStatus.CANCELED, retained.state.value.terminalStatus)
                assertNull("A canceled operation must not publish G-code", retained.state.value.outcome)

                val cancelCleanupDeadline =
                    SystemClock.elapsedRealtime() + SERVICE_STATE_TIMEOUT_MILLIS
                while (
                    SlicerProcessClient.workerIsForegroundForTest(context) &&
                    SystemClock.elapsedRealtime() < cancelCleanupDeadline
                ) {
                    SystemClock.sleep(20)
                }
                scenario.onActivity {
                    retained.clearCompleted()
                    assertTrue(retained.start(listOf(projectObject), options))
                }
                val notificationDeadline =
                    SystemClock.elapsedRealtime() + SERVICE_STATE_TIMEOUT_MILLIS
                while (
                    !SlicerProcessClient.workerIsForegroundForTest(context) &&
                    retained.state.value.busy &&
                    SystemClock.elapsedRealtime() < notificationDeadline
                ) {
                    SystemClock.sleep(20)
                }
                assertTrue(
                    "Notification cancellation requires an active foreground slice",
                    SlicerProcessClient.workerIsForegroundForTest(context),
                )
                assertTrue(SlicerProcessClient.cancelFromNotificationForTest())
                val notificationCancelDeadline =
                    SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MILLIS
                while (
                    retained.state.value.busy &&
                    SystemClock.elapsedRealtime() < notificationCancelDeadline
                ) {
                    SystemClock.sleep(20)
                }
                assertFalse("Notification cancellation must finish", retained.state.value.busy)
                assertEquals(SliceTerminalStatus.CANCELED, retained.state.value.terminalStatus)
                assertNull("Notification cancellation must publish no G-code", retained.state.value.outcome)
            }
        } finally {
            storedModel.delete()
            File(storedModel.parentFile, SliceArtifactStore.NATIVE_OUTPUT_NAME).delete()
        }
    }

    private companion object {
        const val COMPLETION_TIMEOUT_MILLIS = 90_000L
        const val SERVICE_STATE_TIMEOUT_MILLIS = 10_000L
    }
}
