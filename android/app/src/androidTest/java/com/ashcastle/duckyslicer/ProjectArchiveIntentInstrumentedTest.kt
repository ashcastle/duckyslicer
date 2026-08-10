package com.ashcastle.duckyslicer

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectArchiveIntentInstrumentedTest {
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
