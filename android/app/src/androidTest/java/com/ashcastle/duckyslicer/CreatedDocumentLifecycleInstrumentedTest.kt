package com.ashcastle.duckyslicer

import android.app.Application
import android.content.Intent
import android.os.Bundle
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreatedDocumentLifecycleInstrumentedTest {
    @get:Rule
    val blockingProviderProcess = BlockingProviderProcessRule()

    @Test
    fun supportReportExportSurvivesActivityRecreationAndRejectsDuplicateWork() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK,
            null,
            null,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retained: SupportReportExportViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[SupportReportExportViewModel::class.java]
                    assertTrue(retained.export(BlockingExportProvider.URI, AppSettings()))
                }
                waitForProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }
                assertTrue(retained.state.value.busy)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[SupportReportExportViewModel::class.java],
                    )
                    assertFalse(retained.export(BlockingExportProvider.URI, AppSettings()))
                }
                releaseProvider()
                waitUntil("support report export did not finish") {
                    !retained.state.value.busy
                }
                assertTrue(requireNotNull(retained.state.value.completion).succeeded)
                val status = waitForProvider {
                    it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertFalse(status.getBoolean(BlockingExportProvider.KEY_DELETED))
                assertEquals("", status.getString(BlockingExportProvider.KEY_ERROR))
                assertTrue(status.getInt(BlockingExportProvider.KEY_BYTES) in 1..MAX_SUPPORT_REPORT_BYTES)
                assertTrue(status.getString(BlockingExportProvider.KEY_SHA256).orEmpty().isNotBlank())
            }
        } finally {
            releaseProvider()
        }
    }

    @Test
    fun supportReportCancellationSurvivesRecreationAndDeletesThePartialDocument() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK,
            null,
            null,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retained: SupportReportExportViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[SupportReportExportViewModel::class.java]
                    assertTrue(retained.export(BlockingExportProvider.URI, AppSettings()))
                }
                waitForProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[SupportReportExportViewModel::class.java],
                    )
                    assertTrue(retained.cancel())
                    assertFalse(retained.cancel())
                }

                waitUntil("support report cancellation did not settle") {
                    retained.state.value.completion?.outcome ==
                        SupportReportExportOutcome.CANCELED
                }
                val status = waitForProvider {
                    it.getBoolean(BlockingExportProvider.KEY_DELETED) &&
                        it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertEquals(0, status.getInt(BlockingExportProvider.KEY_BYTES))
                assertEquals(
                    "OperationCanceledException",
                    status.getString(BlockingExportProvider.KEY_ERROR),
                )
            }
        } finally {
            releaseProvider()
        }
    }

    @Test
    fun finalSupportReportOwnerStopsProviderOpenAndDeletesThePartialDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK,
            null,
            null,
        )
        val store = ViewModelStore()
        var storeCleared = false
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[SupportReportExportViewModel::class.java]
            assertTrue(model.export(BlockingExportProvider.URI, AppSettings()))
            waitForProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }

            store.clear()
            storeCleared = true

            val status = waitForProvider {
                it.getBoolean(BlockingExportProvider.KEY_DELETED) &&
                    it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
            }
            assertEquals(0, status.getInt(BlockingExportProvider.KEY_BYTES))
            assertEquals(
                "OperationCanceledException",
                status.getString(BlockingExportProvider.KEY_ERROR),
            )
        } finally {
            if (!storeCleared) store.clear()
            releaseProvider()
        }
    }

    @Test
    fun failedProjectArchiveExportDeletesTheNewDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_FAILURE,
            null,
            null,
        )
        val store = ViewModelStore()
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }

            assertTrue(
                model.exportProject(
                    BlockingExportProvider.URI,
                    ProjectSnapshot(),
                    model.state.value.plateOptions,
                ),
            )
            waitUntil("failed project export did not complete") {
                model.state.value.completion != null
            }
            val completion = model.state.value.completion
            assertTrue(completion is ProjectTransferCompletion.Failed)
            assertEquals(
                ProjectTransferDirection.EXPORT,
                (completion as ProjectTransferCompletion.Failed).direction,
            )
            val status = waitForProvider {
                it.getBoolean(BlockingExportProvider.KEY_DELETED)
            }
            assertTrue(status.getBoolean(BlockingExportProvider.KEY_COMPLETED))
        } finally {
            store.clear()
            releaseProvider()
        }
    }

    @Test
    fun failedLinkedProjectExportPreservesTheExistingDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_FAILURE,
            null,
            null,
        )
        val store = ViewModelStore()
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }

            assertTrue(
                model.exportProject(
                    BlockingExportProvider.URI,
                    ProjectSnapshot(),
                    model.state.value.plateOptions,
                    deleteFailedDocument = false,
                ),
            )
            waitUntil("failed linked project export did not complete") {
                model.state.value.completion != null
            }
            assertTrue(model.state.value.completion is ProjectTransferCompletion.Failed)
            val status = waitForProvider {
                it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
            }
            assertFalse(status.getBoolean(BlockingExportProvider.KEY_DELETED))
        } finally {
            store.clear()
            releaseProvider()
        }
    }

    @Test
    fun persistedProjectDocumentLinkSurvivesOwnerRecreationAndSavesDirectly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        val resolver = context.contentResolver
        val uri = BlockingExportProvider.URI
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        testContext.grantUriPermission(context.packageName, uri, grantFlags)
        assertTrue(resolver.retainProjectDocumentWritePermission(uri))

        var firstStore: ViewModelStore? = ViewModelStore()
        var secondStore: ViewModelStore? = null
        try {
            resolver.call(uri, BlockingExportProvider.METHOD_PREPARE, null, null)
            releaseProvider()
            val application = context.applicationContext as Application
            val first = ViewModelProvider(
                checkNotNull(firstStore),
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("project session did not restore") {
                first.state.value.restored && !first.state.value.busy
            }
            assertTrue(
                first.exportProject(
                    uri,
                    first.state.value.history.current,
                    first.state.value.plateOptions,
                ),
            )
            waitUntil("linked project export did not complete") {
                first.state.value.completion is ProjectTransferCompletion.Exported
            }
            assertEquals(uri, first.state.value.linkedDocument?.contentUri)
            assertEquals(
                "Linked-project.duckyproject",
                first.state.value.linkedDocument?.displayName,
            )
            assertFalse(first.state.value.linkedDocumentDirty)
            first.consumeCompletion(checkNotNull(first.state.value.completion).id)
            first.flushPersistence()
            waitUntil("project document link was not persisted") {
                first.state.value.sessionRevision == first.state.value.persistedRevision
            }
            checkNotNull(firstStore).clear()
            firstStore = null

            resolver.call(uri, BlockingExportProvider.METHOD_PREPARE, null, null)
            releaseProvider()
            secondStore = ViewModelStore()
            val restored = ViewModelProvider(
                secondStore,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("linked project session did not restore") {
                restored.state.value.restored && !restored.state.value.busy
            }
            assertEquals(uri, restored.state.value.linkedDocument?.contentUri)
            assertFalse(restored.state.value.linkedDocumentDirty)
            val current = restored.state.value
            assertTrue(
                restored.updateSession(
                    expectedHistory = current.history,
                    nextHistory = current.history,
                    expectedOptions = current.sliceOptions,
                    nextOptions = current.sliceOptions.copy(fillDensity = 0.37f),
                ),
            )
            assertTrue(restored.state.value.linkedDocumentDirty)
            assertTrue(
                restored.saveLinkedProject(
                    restored.state.value.history.current,
                    restored.state.value.plateOptions,
                ),
            )
            waitUntil("direct linked project save did not complete") {
                restored.state.value.completion is ProjectTransferCompletion.Exported
            }
            assertFalse(restored.state.value.linkedDocumentDirty)
            val status = waitForProvider {
                it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
            }
            assertTrue(status.getInt(BlockingExportProvider.KEY_BYTES) > 0)
            assertFalse(status.getBoolean(BlockingExportProvider.KEY_DELETED))
        } finally {
            firstStore?.clear()
            secondStore?.clear()
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            testContext.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            releaseProvider()
        }
    }

    @Test
    fun projectExportCancellationSurvivesRecreationAndDeletesThePartialDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK,
            null,
            null,
        )
        var modelFile: File? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retained: ProjectTransferViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                }
                waitUntil("project session did not restore") {
                    retained.state.value.restored && !retained.state.value.busy
                }
                val fixture = largeProjectSnapshot()
                val snapshot = fixture.first
                modelFile = fixture.second
                scenario.onActivity {
                    assertTrue(
                        retained.exportProject(
                            BlockingExportProvider.URI,
                            snapshot,
                            retained.state.value.plateOptions,
                        ),
                    )
                }
                waitForProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[ProjectTransferViewModel::class.java],
                    )
                    assertTrue(retained.cancelProjectExport())
                    assertFalse(retained.cancelProjectExport())
                }
                waitUntil("project export cancellation did not settle") {
                    !retained.state.value.busy
                }
                val status = waitForProvider {
                    it.getBoolean(BlockingExportProvider.KEY_DELETED) &&
                        it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertTrue(status.getInt(BlockingExportProvider.KEY_BYTES) < fixture.second.length())
                assertTrue(fixture.second.isFile)
            }
        } finally {
            modelFile?.delete()
            releaseProvider()
        }
    }

    @Test
    fun projectExportCancellationInterruptsProviderOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK,
            null,
            null,
        )
        var modelFile: File? = null
        val store = ViewModelStore()
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }
            val fixture = largeProjectSnapshot()
            modelFile = fixture.second
            assertTrue(
                model.exportProject(
                    BlockingExportProvider.URI,
                    fixture.first,
                    model.state.value.plateOptions,
                ),
            )
            waitForProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }

            assertTrue(model.cancelProjectExport())
            assertFalse(model.cancelProjectExport())
            waitUntil("provider-open cancellation did not settle") {
                !model.state.value.busy
            }
            val status = waitForProvider {
                it.getBoolean(BlockingExportProvider.KEY_DELETED) &&
                    it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
            }
            assertEquals(0, status.getInt(BlockingExportProvider.KEY_BYTES))
            assertEquals(
                "OperationCanceledException",
                status.getString(BlockingExportProvider.KEY_ERROR),
            )
            assertTrue(fixture.second.isFile)
        } finally {
            store.clear()
            modelFile?.delete()
            releaseProvider()
        }
    }

    @Test
    fun finalProjectOwnerClearStopsItsExportAndDeletesThePartialDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE,
            null,
            null,
        )
        var modelFile: File? = null
        val store = ViewModelStore()
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }
            val fixture = largeProjectSnapshot()
            val snapshot = fixture.first
            modelFile = fixture.second
            assertTrue(
                model.exportProject(
                    BlockingExportProvider.URI,
                    snapshot,
                    model.state.value.plateOptions,
                ),
            )
            waitForProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }

            store.clear()

            val status = waitForProvider {
                it.getBoolean(BlockingExportProvider.KEY_DELETED) &&
                    it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
            }
            assertTrue(status.getInt(BlockingExportProvider.KEY_BYTES) < fixture.second.length())
            assertTrue(fixture.second.isFile)
        } finally {
            store.clear()
            modelFile?.delete()
            releaseProvider()
        }
    }

    private fun largeProjectSnapshot(): Pair<ProjectSnapshot, File> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelRoot = ProjectStore.modelStorageRoot(context.filesDir).apply { mkdirs() }
        val modelFile = modelRoot.resolve("archive-export-${UUID.randomUUID()}.stl")
        val payload = ByteArray(LARGE_MODEL_BYTES)
        Random(73).nextBytes(payload)
        modelFile.writeBytes(payload)
        val model = ModelInfo(
            fileName = "large-export.stl",
            triangles = 1,
            dimensions = listOf(1.0, 1.0, 1.0),
            localPath = modelFile.canonicalPath,
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(1.0, 1.0, 1.0),
            previewTriangles = FloatArray(9),
        )
        return ProjectSnapshot(
            objects = listOf(ProjectObject("archive-export", model)),
            selectedObjectId = "archive-export",
        ) to modelFile
    }

    private fun releaseProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun waitForProvider(condition: (Bundle) -> Boolean): Bundle {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        var latest = Bundle.EMPTY
        waitUntil("blocking export provider did not reach the expected state") {
            latest = requireNotNull(
                resolver.call(
                    BlockingExportProvider.URI,
                    BlockingExportProvider.METHOD_STATUS,
                    null,
                    null,
                ),
            )
            condition(latest)
        }
        return latest
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
        const val WAIT_TIMEOUT_MILLIS = 15_000L
        const val WAIT_POLL_MILLIS = 25L
        const val LARGE_MODEL_BYTES = 2 * 1_024 * 1_024
    }
}
