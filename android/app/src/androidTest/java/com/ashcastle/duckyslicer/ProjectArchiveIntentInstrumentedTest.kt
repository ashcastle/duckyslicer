package com.ashcastle.duckyslicer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
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
class ProjectArchiveIntentInstrumentedTest {
    @get:Rule
    val blockingProviderProcess = BlockingProviderProcessRule()

    @Test
    fun externalProjectRequestBindsOneOperationAndRestoresAsRetryableAfterProcessLoss() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(BlockingImportProvider.URI, PROJECT_ARCHIVE_MIME_TYPE)
        val savedState = SavedStateHandle()
        val retained = ExternalProjectRequestViewModel(savedState)

        assertTrue(retained.enqueue(intent))
        val first = requireNotNull(retained.request.value)
        assertTrue(retained.markStarted(first.id, 51L))
        assertEquals(51L, retained.request.value?.startedOperationId)

        assertTrue(retained.enqueue(intent))
        val second = requireNotNull(retained.request.value)
        assertTrue(second.id > first.id)
        assertNull(second.startedOperationId)
        assertFalse(retained.consume(first.id, 51L))
        assertTrue(retained.markStarted(second.id, 52L))
        assertFalse(retained.consume(second.id, 51L))
        assertFalse(retained.discardUnstarted(second.id))

        val restoredAfterProcessLoss = ExternalProjectRequestViewModel(savedState)
        val restored = requireNotNull(restoredAfterProcessLoss.request.value)
        assertEquals(second.id, restored.id)
        assertEquals(second.uri, restored.uri)
        assertNull(restored.startedOperationId)
        assertTrue(restoredAfterProcessLoss.discardUnstarted(restored.id))
        assertNull(restoredAfterProcessLoss.request.value)
    }

    @Test
    fun multipleSelectedModelDocumentsCommitAsOneProjectEdit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val owner = ViewModelStore()
        val first = File(context.cacheDir, "batch-first.stl")
        val second = File(context.cacheDir, "batch-second.stl")
        projectRoot.deleteRecursively()
        try {
            listOf(first, second).forEach { destination ->
                instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                    destination.outputStream().use(input::copyTo)
                }
            }
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                owner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitForReadySession(model)
            val uris = listOf(first, second).map { source ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.debug-files",
                    source,
                )
            }

            assertTrue(model.importModels(uris))
            val completed = waitForEditCompletion(model, expectedRevision = 1)

            assertEquals(ProjectEditKind.MODEL_IMPORT, completed.editCompletion?.kind)
            assertEquals(2, completed.editCompletion?.objectCount)
            assertEquals(
                listOf("batch-first.stl", "batch-second.stl"),
                completed.history.current.objects.map { it.model.fileName },
            )
            assertEquals(0f, completed.history.current.objects[0].transform.offsetXmm)
            assertEquals(24f, completed.history.current.objects[1].transform.offsetXmm)
            assertTrue(completed.history.canUndo)
        } finally {
            owner.clear()
            first.delete()
            second.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun failedModelInMultipleSelectionRollsBackEveryEarlierDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val owner = ViewModelStore()
        val valid = File(context.cacheDir, "batch-valid.stl")
        val invalid = File(context.cacheDir, "batch-invalid.txt")
        projectRoot.deleteRecursively()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                valid.outputStream().use(input::copyTo)
            }
            invalid.writeText("not a model")
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                owner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitForReadySession(model)
            val uris = listOf(valid, invalid).map { source ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.debug-files",
                    source,
                )
            }

            assertTrue(model.importModels(uris))
            val completed = waitForEditCompletion(model, expectedRevision = 0)

            assertEquals(ProjectEditFailure.GENERIC, completed.editCompletion?.failure)
            assertTrue(completed.history.current.allObjects.isEmpty())
            assertFalse(
                File(projectRoot, "models").listFiles().orEmpty().any { it.isFile },
            )
        } finally {
            owner.clear()
            valid.delete()
            invalid.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun newProjectPersistsAnEmptyWorkspaceAndKeepsTheActiveProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val owner = ViewModelStore()
        projectRoot.deleteRecursively()
        try {
            seedCurrentProject("reset-object", "reset-object.stl")
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                owner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitForSession(model, "reset-object")
            val initial = model.state.value
            val installedModel = File(
                requireNotNull(initial.history.current.selectedObject).model.localPath,
            )
            val retainedOptions = initial.sliceOptions.copy(fillDensity = 0.39f)
            assertTrue(
                model.updateSession(
                    initial.history,
                    initial.history,
                    initial.sliceOptions,
                    retainedOptions,
                ),
            )

            assertTrue(model.newProject())
            val cleared = model.state.value
            assertEquals(1, cleared.history.current.plates.size)
            assertTrue(cleared.history.current.allObjects.isEmpty())
            assertFalse(cleared.history.canUndo)
            assertEquals(retainedOptions, cleared.sliceOptions)
            waitForPersistence(model, cleared.sessionRevision)

            assertFalse(installedModel.exists())
            val restored = ProjectStore(context).loadProject()
            assertTrue(restored.snapshot.allObjects.isEmpty())
            assertEquals(1, restored.snapshot.plates.size)
            assertEquals(retainedOptions.fillDensity, restored.activeSliceOptions.fillDensity)
        } finally {
            owner.clear()
            projectRoot.deleteRecursively()
        }
    }

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
    fun automaticLayReportsAnAlreadyStableModelWithoutCreatingFakeHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        projectRoot.deleteRecursively()
        try {
            seedCurrentProject("stable-auto-lay", "stable-auto-lay.stl")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retainedModel: ProjectTransferViewModel
                scenario.onActivity { activity ->
                    retainedModel = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                }
                waitForSession(retainedModel, "stable-auto-lay")
                val starting = retainedModel.state.value
                val layFlat = waitForNode(context.getString(R.string.auto_lay)) { node ->
                    node.isClickable && node.isEnabled
                }
                assertTrue(layFlat.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitForActiveEdit(retainedModel, ProjectEditKind.AUTO_LAY)

                val completed = waitForEditCompletion(retainedModel, starting.sessionRevision)
                assertEquals(starting.history, completed.history)
                assertEquals(starting.sessionRevision, completed.sessionRevision)
                waitForNode(context.getString(R.string.auto_lay_unchanged))
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
    fun projectViewIntentSurvivesRecreationAndImportsExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val archive = createArchive(
            objectId = "external-once",
            displayName = "external-once.stl",
            fillDensity = 0.33f,
        )
        projectRoot.deleteRecursively()
        prepareBlockingProjectImport(archive)
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(context.packageName)
                .setDataAndType(BlockingImportProvider.URI, PROJECT_ARCHIVE_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var retainedProject: ProjectTransferViewModel
                lateinit var retainedRequest: ExternalProjectRequestViewModel
                scenario.onActivity { activity ->
                    retainedProject = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                    retainedRequest =
                        ViewModelProvider(activity)[ExternalProjectRequestViewModel::class.java]
                }
                waitForBlockingProjectImport { it.getBoolean(BlockingImportProvider.KEY_STARTED) }
                val operationId = requireNotNull(retainedProject.state.value.activeTransferId)
                assertEquals(
                    ProjectTransferDirection.IMPORT,
                    retainedProject.state.value.activeTransferDirection,
                )
                assertEquals(operationId, retainedRequest.request.value?.startedOperationId)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedProject,
                        ViewModelProvider(recreated)[ProjectTransferViewModel::class.java],
                    )
                    assertSame(
                        retainedRequest,
                        ViewModelProvider(recreated)[ExternalProjectRequestViewModel::class.java],
                    )
                    assertEquals(operationId, retainedRequest.request.value?.startedOperationId)
                }

                releaseBlockingProjectImport()
                val restored = waitForProject("external-once", "external-once.stl")
                assertEquals(0.33f, restored.sliceOptions?.fillDensity)
                waitUntil("completed external project request was not consumed") {
                    retainedRequest.request.value == null
                }
                val status = waitForBlockingProjectImport {
                    it.getBoolean(BlockingImportProvider.KEY_COMPLETED)
                }
                assertEquals(archive.length().toInt(), status.getInt(BlockingImportProvider.KEY_BYTES))

                scenario.recreate()
                assertNull(retainedRequest.request.value)
                assertEquals(
                    1,
                    retainedProject.state.value.history.current.allObjects.size,
                )
            }
        } finally {
            releaseBlockingProjectImport()
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
        // It is unrelated to the project-archive parser, but it is now a supported model intent.
        assertTrue(
            packageManager.queryIntentActivities(unrelated, 0).any { result ->
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
            inspectModel(model.absolutePath)
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
        val model = inspectModel(modelFile.absolutePath).copy(fileName = displayName)
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

    private fun prepareBlockingProjectImport(archive: File) {
        ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val extras = Bundle().apply {
                putParcelable(BlockingImportProvider.KEY_SOURCE_DESCRIPTOR, descriptor)
            }
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.URI,
                BlockingImportProvider.METHOD_PREPARE_OPEN_BLOCK,
                null,
                extras,
            )
        }
    }

    private fun releaseBlockingProjectImport() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingImportProvider.URI,
            BlockingImportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun waitForBlockingProjectImport(predicate: (Bundle) -> Boolean): Bundle {
        var status = Bundle.EMPTY
        waitUntil("blocking project provider did not reach the expected state") {
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

    private fun waitForReadySession(model: ProjectTransferViewModel) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.state.value
            if (state.restored && !state.busy && state.editCompletion == null) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for an empty retained project session")
    }

    private fun waitForPersistence(model: ProjectTransferViewModel, revision: Long) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = model.state.value
            if (state.persistedRevision >= revision) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for project persistence revision $revision")
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

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError(message)
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
