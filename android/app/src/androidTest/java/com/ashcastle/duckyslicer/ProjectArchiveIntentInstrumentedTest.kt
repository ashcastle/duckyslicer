package com.ashcastle.duckyslicer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectArchiveIntentInstrumentedTest {
    @Test
    fun clearingRetainedOwnerFlushesProjectBeforeDebounce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val owner = ViewModelStore()
        projectRoot.deleteRecursively()
        try {
            seedCurrentProject("owner-clear-object", "owner-clear.stl")
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                owner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitForSession(model, "owner-clear-object")
            val initial = model.state.value
            assertTrue(
                model.updateSession(
                    initial.history,
                    initial.history.updateSelectedTransform(
                        ModelTransform(offsetXmm = 29f, rotationYdeg = 11f),
                    ),
                    initial.sliceOptions,
                    initial.sliceOptions.copy(fillDensity = 0.43f),
                ),
            )
            val dirty = model.state.value
            assertTrue(dirty.sessionRevision > dirty.persistedRevision)

            // Clear immediately, before the 400 ms background save can run.
            owner.clear()

            val restored = ProjectStore(context).loadProject()
            assertEquals("owner-clear-object", restored.snapshot.selectedObjectId)
            assertEquals(29f, restored.snapshot.selectedObject?.transform?.offsetXmm)
            assertEquals(11f, restored.snapshot.selectedObject?.transform?.rotationYdeg)
            assertEquals(0.43f, restored.sliceOptions?.fillDensity)
        } finally {
            owner.clear()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun automaticLayButtonKeepsOneRetainedOperationAcrossActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        projectRoot.deleteRecursively()
        try {
            seedCurrentProject("retained-auto-lay", "retained-auto-lay.stl")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retainedModel: ProjectTransferViewModel
                scenario.onActivity { activity ->
                    retainedModel = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                }
                waitForSession(retainedModel, "retained-auto-lay")
                val ready = retainedModel.state.value
                val tilted = ModelTransform(
                    rotationXdeg = 31f,
                    rotationYdeg = 17f,
                    rotationZdeg = 9f,
                )
                assertTrue(
                    retainedModel.updateHistory(
                        ready.history,
                        ready.history.updateSelectedTransform(tilted),
                    ),
                )
                val startingRevision = retainedModel.state.value.sessionRevision
                val layFlatLabel = context.getString(R.string.auto_lay)
                val layFlat = waitForNode(layFlatLabel) { node ->
                    node.isClickable && node.isEnabled
                }
                assertTrue(
                    "The visible Lay flat action must accept the user's click",
                    layFlat.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                )
                waitForActiveEdit(retainedModel, ProjectEditKind.AUTO_LAY)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedModel,
                        ViewModelProvider(recreated)[ProjectTransferViewModel::class.java],
                    )
                }
                val completed = waitForEditCompletion(retainedModel, startingRevision + 1)
                val applied = requireNotNull(completed.history.current.selectedObject).transform
                assertTrue("Automatic lay must replace the staged tilt", applied != tilted)
                assertEquals(startingRevision + 1, completed.sessionRevision)
                waitForPersistedTransform("retained-auto-lay", applied)
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun unsavedProjectEditAndUndoSurviveImmediateActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        projectRoot.deleteRecursively()
        try {
            seedCurrentProject("retained-object", "retained.stl")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retainedModel: ProjectTransferViewModel
                scenario.onActivity { activity ->
                    retainedModel = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                }
                waitForSession(retainedModel, "retained-object")
                val initial = retainedModel.state.value
                val nextHistory = initial.history.updateSelectedTransform(
                    ModelTransform(offsetXmm = 23f, rotationZdeg = 17f),
                )
                val nextOptions = initial.sliceOptions.copy(fillDensity = 0.37f)
                assertTrue(
                    retainedModel.updateSession(
                        initial.history,
                        nextHistory,
                        initial.sliceOptions,
                        nextOptions,
                    ),
                )

                // Recreate before the 400 ms durable-save debounce can finish.
                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedModel,
                        ViewModelProvider(recreated)[ProjectTransferViewModel::class.java],
                    )
                }
                val retained = retainedModel.state.value
                assertEquals(23f, retained.history.current.selectedObject?.transform?.offsetXmm)
                assertEquals(17f, retained.history.current.selectedObject?.transform?.rotationZdeg)
                assertEquals(0.37f, retained.sliceOptions.fillDensity)
                assertTrue("Undo history must survive recreation", retained.history.canUndo)

                waitForPersistedSession("retained-object", 23f, 0.37f)
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun customProjectIntentSurvivesRecreationRestoresAndSlices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val archive = createArchive(
            objectId = "external-empty",
            displayName = "external-empty.stl",
            fillDensity = 0.31f,
        )
        var gcode: File? = null
        projectRoot.deleteRecursively()
        try {
            val uri = archiveUri(archive)
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(context.packageName)
                .setDataAndType(uri, PROJECT_ARCHIVE_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.recreate()
                val restored = waitForProject("external-empty", "external-empty.stl")
                assertEquals("external-empty.stl", restored.snapshot.selectedObject?.model?.fileName)
                assertEquals(0.31f, restored.sliceOptions?.fillDensity)

                val outcome = OnDeviceSlicer.slice(
                    restored.snapshot.objects,
                    requireNotNull(restored.sliceOptions),
                )
                gcode = outcome.output
                assertTrue("A project opened from Files must slice normally", outcome.output.length() > 1_000L)
            }
        } finally {
            gcode?.delete()
            archive.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun compatibleZipIntentConfirmsBeforeReplacingTheCurrentProject() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val replacementArchive = createArchive(
            objectId = "replacement-object",
            displayName = "replacement.stl",
            fillDensity = 0.27f,
        )
        projectRoot.deleteRecursively()
        try {
            seedCurrentProject("current-object", "current.stl")
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(context.packageName)
                .setDataAndType(archiveUri(replacementArchive), "application/zip")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<MainActivity>(intent).use {
                val title = context.getString(R.string.replace_project_title)
                waitForNode(title)
                assertEquals(
                    "A VIEW intent must not replace a non-empty project before confirmation",
                    "current-object",
                    ProjectStore(context).load().selectedObjectId,
                )

                val openLabel = context.getString(R.string.open_project)
                val openAction = waitForNode(openLabel) { node -> node.isClickable }
                assertTrue(openAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val restored = waitForProject("replacement-object", "replacement.stl")
                assertEquals("replacement.stl", restored.snapshot.selectedObject?.model?.fileName)
                assertEquals(0.27f, restored.sliceOptions?.fillDensity)
            }
        } finally {
            replacementArchive.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun projectViewIntentRejectsNetworkAndUnrelatedBinaryUris() {
        val network = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                Uri.parse("https://example.invalid/private.duckyproject"),
                PROJECT_ARCHIVE_MIME_TYPE,
            )
        val unrelated = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://example/model.stl"), "application/octet-stream")
        val compatible = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                Uri.parse("content://example/Download/project.duckyproject"),
                "application/octet-stream",
            )

        assertNull(projectArchiveViewUriOrNull(network))
        assertNull(projectArchiveViewUriOrNull(unrelated))
        assertEquals(compatible.data, projectArchiveViewUriOrNull(compatible))
        val packageManager = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        assertTrue(
            packageManager.queryIntentActivities(compatible, 0).any { result ->
                result.activityInfo.name == MainActivity::class.java.name
            },
        )
        assertTrue(
            packageManager.queryIntentActivities(unrelated, 0).none { result ->
                result.activityInfo.name == MainActivity::class.java.name
            },
        )
        assertTrue(
            packageManager.queryIntentActivities(network, 0).none { result ->
                result.activityInfo.name == MainActivity::class.java.name
            },
        )
    }

    private fun createArchive(
        objectId: String,
        displayName: String,
        fillDensity: Float,
    ): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sourceRoot = File(context.cacheDir, "intent-source-$objectId").apply {
            deleteRecursively()
        }
        val archive = File(context.cacheDir, "$objectId.duckyproject").apply { delete() }
        val inspector: (File) -> ModelInfo = { model ->
            ModelInfo.fromJson(NativeEngine.inspectStl(model.absolutePath), model.absolutePath)
        }
        try {
            val store = ProjectStore(sourceRoot, inspector)
            val modelFile = store.createModelDestination(displayName)
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                modelFile.outputStream().use(input::copyTo)
            }
            val model = inspector(modelFile).copy(fileName = displayName)
            archive.outputStream().use { output ->
                store.exportArchive(
                    ProjectSnapshot(
                        objects = listOf(
                            ProjectObject(
                                id = objectId,
                                model = model,
                                transform = ModelTransform(offsetXmm = 7f, rotationZdeg = 15f),
                                supportPaint = SupportPaint().paint(0, SupportPaintState.BLOCK),
                            ),
                        ),
                        selectedObjectId = objectId,
                    ),
                    SliceOptions().copy(fillDensity = fillDensity),
                    output,
                )
            }
            return archive
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    private fun seedCurrentProject(objectId: String, displayName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = ProjectStore(context)
        val modelFile = store.createModelDestination(displayName)
        instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
            modelFile.outputStream().use(input::copyTo)
        }
        val model = ModelInfo.fromJson(
            NativeEngine.inspectStl(modelFile.absolutePath),
            modelFile.absolutePath,
        ).copy(fileName = displayName)
        store.save(
            ProjectSnapshot(
                objects = listOf(ProjectObject(objectId, model)),
                selectedObjectId = objectId,
            ),
            SliceOptions(),
        )
    }

    private fun archiveUri(archive: File): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.debug-files",
            archive,
        )
    }

    private fun waitForProject(objectId: String, displayName: String): StoredProjectDocument {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        waitForNode(displayName)
        return ProjectStore(context).loadProject().also { document ->
            assertEquals(objectId, document.snapshot.selectedObjectId)
        }
    }

    private fun waitForSession(model: ProjectTransferViewModel, objectId: String) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.state.value
            if (state.restored && !state.busy && state.history.current.selectedObjectId == objectId) {
                return
            }
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for retained project session: $objectId")
    }

    private fun waitForPersistedSession(
        objectId: String,
        offsetXmm: Float,
        fillDensity: Float,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val stored = ProjectStore(context).loadProject()
            if (
                stored.snapshot.selectedObjectId == objectId &&
                stored.snapshot.selectedObject?.transform?.offsetXmm == offsetXmm &&
                stored.sliceOptions?.fillDensity == fillDensity
            ) {
                return
            }
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for retained project persistence")
    }

    private fun waitForEditCompletion(
        model: ProjectTransferViewModel,
        expectedRevision: Long,
    ): ProjectTransferState {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        var latest = model.state.value
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.state.value
            latest = state
            if (!state.busy && state.activeEdit == null && state.sessionRevision == expectedRevision) {
                return state
            }
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError(
            "Timed out waiting for retained project edit completion: " +
                "expectedRevision=$expectedRevision actualRevision=${latest.sessionRevision} " +
                "active=${latest.activeEdit} completion=${latest.editCompletion}",
        )
    }

    private fun waitForActiveEdit(
        model: ProjectTransferViewModel,
        expectedKind: ProjectEditKind,
    ) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        var latest = model.state.value
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = model.state.value
            if (latest.busy && latest.activeEdit?.kind == expectedKind) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError(
            "Timed out waiting for project edit to start: " +
                "expected=$expectedKind active=${latest.activeEdit} " +
                "completion=${latest.editCompletion}",
        )
    }

    private fun waitForPersistedTransform(objectId: String, transform: ModelTransform) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        var latest = StoredProjectDocument()
        while (SystemClock.elapsedRealtime() < deadline) {
            val stored = ProjectStore(context).loadProject()
            latest = stored
            if (
                stored.snapshot.selectedObjectId == objectId &&
                stored.snapshot.selectedObject?.transform == transform
            ) {
                return
            }
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError(
            "Timed out waiting for retained project edit persistence: " +
                "expected=$transform actual=${latest.snapshot.selectedObject?.transform} " +
                "storageUnavailable=${latest.storageUnavailable}",
        )
    }

    private fun waitForNode(
        label: String,
        predicate: (AccessibilityNodeInfo) -> Boolean = { true },
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            currentNodes().firstOrNull { node ->
                predicate(node) && node.effectiveLabel().contains(label)
            }?.let { return it }
            SystemClock.sleep(WAIT_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for action: $label")
    }

    private fun currentNodes(): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return emptyList()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            nodes += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(::collect) }
        }
        collect(root)
        return nodes
    }

    private fun AccessibilityNodeInfo.effectiveLabel(depth: Int = 0): String {
        if (depth > MAX_LABEL_DEPTH) return ""
        val labels = ArrayList<String>()
        contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
        text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
        repeat(childCount) { index ->
            getChild(index)?.effectiveLabel(depth + 1)?.takeIf(String::isNotBlank)?.let(labels::add)
        }
        return labels.distinct().joinToString(" ")
    }

    private companion object {
        const val MAX_LABEL_DEPTH = 12
        const val WAIT_TIMEOUT_MILLIS = 15_000L
        const val WAIT_POLL_MILLIS = 50L
    }
}
