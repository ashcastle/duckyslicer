package com.ashcastle.duckyslicer

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    fun notificationSettingsIntentTargetsThisApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = sliceNotificationSettingsIntent(context)

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun notificationCancelIntentsAreExplicitAndRequestScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = SlicerProcessService.cancelSliceIntent(context, "request-one")
        val second = SlicerProcessService.cancelSliceIntent(context, "request-two")

        assertEquals(context.packageName, first.component?.packageName)
        assertEquals(SlicerProcessService::class.java.name, first.component?.className)
        assertEquals("duckyslicer", first.data?.scheme)
        assertEquals("slice-cancel", first.data?.authority)
        assertEquals("request-one", first.data?.lastPathSegment)
        assertEquals("request-two", second.data?.lastPathSegment)
        assertFalse("Each slice must receive a distinct cancel token", first.filterEquals(second))
    }

    @Test
    fun completedSliceCheckpointPreservesSafetyWarnings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ForegroundSliceStore.load(context)?.let { record ->
            ForegroundSliceStore.remove(context, record.requestId)
        }
        val requestId = UUID.randomUUID().toString()
        val output = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY)
            .apply(File::mkdirs)
            .resolve("checkpoint-warning.gcode")
            .apply { writeText("G28\n") }
        try {
            ForegroundSliceStore.begin(context, requestId, legacyProjectPlateId())
            ForegroundSliceStore.complete(
                context,
                requestId,
                SliceOutcome(
                    output = output,
                    layers = 1,
                    estimatedSeconds = 1f,
                    filamentMm = 1f,
                    filamentGrams = 1f,
                    warnings = setOf(SliceWarningCode.NOZZLE_HARDNESS),
                ),
            )

            val restored = requireNotNull(ForegroundSliceStore.load(context))
            assertEquals(ForegroundSlicePhase.COMPLETED, restored.phase)
            assertEquals(
                setOf(SliceWarningCode.NOZZLE_HARDNESS),
                requireNotNull(restored.outcome).warnings,
            )
        } finally {
            ForegroundSliceStore.remove(context, requestId)
            output.delete()
        }
    }

    @Test
    fun clearingFinalActiveSliceOwnerCancelsItsExactSessionAndRecovers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storedModel = File(context.cacheDir, "final-owner-slice.stl")
        instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
            storedModel.outputStream().use(input::copyTo)
        }
        val model = inspectModel(storedModel.absolutePath)
        val projectObject = ProjectObject(
            id = "final-owner-slice",
            model = model,
        )
        try {
            val launchIntent = Intent(Intent.ACTION_MAIN)
                .setClass(context, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                scenario.onActivity { activity ->
                    val owner = ViewModelProvider(activity)[SliceOperationViewModel::class.java]
                    assertTrue(
                        owner.start(legacyProjectPlateId(), listOf(projectObject), SliceOptions()),
                    )
                    assertTrue(owner.state.value.slicing)
                }
            }

            val deadline = SystemClock.elapsedRealtime() + FINAL_OWNER_TIMEOUT_MILLIS
            while (
                (
                    ForegroundSliceStore.load(context) != null ||
                        SlicerProcessClient.workerIsForegroundForTest(context)
                    ) && SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(20)
            }
            assertNull(
                "Final owner cancellation left a recoverable foreground session",
                ForegroundSliceStore.load(context),
            )
            assertFalse(
                "Final owner cancellation left the slicer service in foreground",
                SlicerProcessClient.workerIsForegroundForTest(context),
            )
            val recovery = OnDeviceSlicer.slice(
                storedModel,
                SliceOptions().selectQuality(QualityProfile.DRAFT),
            )
            assertTrue("A clean slice must succeed after final-owner cancellation", recovery.output.isFile)
        } finally {
            storedModel.delete()
        }
    }

    @Test
    fun activeSliceSurvivesActivityRecreationAndCompletes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storedModel = File(context.cacheDir, "lifecycle-rotation.stl")
        instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
            storedModel.outputStream().use(input::copyTo)
        }
        val model = inspectModel(storedModel.absolutePath)
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
            var ownerPlateId: String? = null
            val launchIntent = Intent(Intent.ACTION_MAIN)
                .setClass(context, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                scenario.onActivity { activity ->
                    val initial = ViewModelProvider(activity)[SliceOperationViewModel::class.java]
                    val projectTransfer =
                        ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                    val selectedPlateId =
                        projectTransfer.state.value.history.current.selectedPlateId
                    retainedModel = initial
                    ownerPlateId = selectedPlateId
                    assertTrue(initial.start(selectedPlateId, listOf(projectObject), options))
                    assertTrue("The slice must be active before recreation", initial.state.value.slicing)
                    assertEquals(selectedPlateId, initial.state.value.plateId)
                    assertEquals(selectedPlateId, ForegroundSliceStore.load(context)?.plateId)
                }

                val retained = requireNotNull(retainedModel)
                val livePlateId = requireNotNull(ownerPlateId)
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
                assertEquals(livePlateId, completed.plateId)
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
                    assertTrue(
                        retained.loadPreview(
                            livePlateId,
                            outcome,
                            0,
                            outcome.layers / 2,
                        ),
                    )
                    assertEquals(livePlateId, retained.state.value.plateId)
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
                    assertTrue(retained.start(livePlateId, listOf(projectObject), options))
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
                    assertTrue(
                        "An idle canceled slice must release ownership before allowing restart",
                        retained.start(livePlateId, listOf(projectObject), options),
                    )
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
                assertTrue(retained.cancelFromNotificationForTest())
                val notificationCancelDeadline =
                    SystemClock.elapsedRealtime() + COMPLETION_TIMEOUT_MILLIS
                while (
                    retained.state.value.terminalStatus != SliceTerminalStatus.CANCELED &&
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

    @Test
    fun clearingIdleSliceOwnerAndStaleSessionCannotCancelLaterNativeRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ForegroundSliceStore.load(context)?.let { record ->
            ForegroundSliceStore.remove(context, record.requestId)
        }
        val ownerStore = ViewModelStore()
        ViewModelProvider(
            ownerStore,
            ViewModelProvider.NewInstanceFactory(),
        )[SliceOperationViewModel::class.java]
        var ownerCleared = false
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val requestId = UUID.randomUUID().toString()
        val probe = Thread {
            runCatching {
                SlicerProcessClient.cancellationProbeForTest(started::countDown, requestId)
            }.onFailure(failure::set)
        }.apply { start() }
        try {
            assertTrue("The later native request did not start", started.await(10, TimeUnit.SECONDS))
            val workerPid = SlicerProcessClient.workerHealthForTest(context)
            val staleSession = ForegroundSliceSession(context, UUID.randomUUID().toString())

            assertFalse(
                "A stale foreground session must not cancel another request",
                SlicerProcessClient.cancelUserSliceAsync(staleSession),
            )
            ownerStore.clear()
            ownerCleared = true
            SystemClock.sleep(300)

            assertTrue("Clearing an idle slice owner canceled later native work", probe.isAlive)
            assertEquals(
                "An idle slice owner must leave the exact worker untouched",
                workerPid,
                SlicerProcessClient.workerHealthForTest(context),
            )
            assertTrue(
                "The exact test request must remain cancelable",
                SlicerProcessClient.cancelRequestForTest(requestId),
            )
            probe.join(10_000)
            assertFalse("Exact cancellation did not release the request", probe.isAlive)
            assertTrue(failure.get() is SlicingCancelledException)
        } finally {
            if (!ownerCleared) ownerStore.clear()
            if (probe.isAlive) {
                SlicerProcessClient.cancelRequestForTest(requestId)
                probe.join(10_000)
            }
        }
    }

    private companion object {
        const val COMPLETION_TIMEOUT_MILLIS = 90_000L
        const val FINAL_OWNER_TIMEOUT_MILLIS = 15_000L
        const val SERVICE_STATE_TIMEOUT_MILLIS = 10_000L
    }
}
