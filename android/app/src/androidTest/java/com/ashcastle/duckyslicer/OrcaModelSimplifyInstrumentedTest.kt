package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaModelSimplifyInstrumentedTest {
    @Test
    fun inheritedOrcaSimplifierExportsARealReducedMesh() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        projectRoot.deleteRecursively()
        try {
            val staging = store.createModelImportStaging()
            try {
                val source = SlicerProcessClient.createPrimitive(OrcaPrimitive.SPHERE, 30f, staging)
                val sourceInfo = ModelInfo.fromJson(
                    NativeEngine.inspectStl(source.file.absolutePath),
                    source.file.absolutePath,
                )
                assertTrue(sourceInfo.triangles >= MINIMUM_SIMPLIFIABLE_TRIANGLES)
                val target = simplificationTargetTriangleCount(
                    sourceInfo.triangles,
                    DEFAULT_SIMPLIFY_KEEP_PERCENT,
                )

                val simplified = SlicerProcessClient.simplifyModel(
                    source.file,
                    staging,
                    target,
                )
                val simplifiedInfo = ModelInfo.fromJson(
                    NativeEngine.inspectStl(simplified.file.absolutePath),
                    simplified.file.absolutePath,
                )

                assertTrue(simplifiedInfo.triangles < sourceInfo.triangles)
                assertTrue(simplifiedInfo.triangles <= target)
                sourceInfo.dimensions.zip(simplifiedInfo.dimensions).forEach { (before, after) ->
                    assertEquals(before, after, 0.5)
                }
                assertTrue(simplified.file.length() < source.file.length())
            } finally {
                staging.deleteRecursively()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun clientRejectsAnUnsafeTargetBeforeBindingTheWorker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProjectStore(context)
        val staging = store.createModelImportStaging()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SlicerProcessClient.simplifyModel(File(staging, "missing.stl"), staging, 3)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun canceledRequestNeverReachesTheNativeSimplifier() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProjectStore(context)
        val staging = store.createModelImportStaging()
        val requestId = "simplify-canceled-before-bind"
        try {
            assertTrue(SlicerProcessClient.cancelProjectRequestAsync(requestId))
            assertThrows(ProjectEditCancelledException::class.java) {
                SlicerProcessClient.simplifyModel(
                    File(staging, "missing.stl"),
                    staging,
                    MINIMUM_SIMPLIFIED_TRIANGLES,
                    requestId,
                )
            }
        } finally {
            SlicerProcessClient.releaseProjectRequest(requestId)
            staging.deleteRecursively()
        }
    }
}
