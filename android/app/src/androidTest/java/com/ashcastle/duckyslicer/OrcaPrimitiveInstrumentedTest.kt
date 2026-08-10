package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaPrimitiveInstrumentedTest {
    @Test
    fun inheritedOrcaGeneratorsCreateEveryMobileShapeAndRealGcode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        projectRoot.deleteRecursively()
        try {
            OrcaPrimitive.entries.forEach { primitive ->
                val staging = store.createModelImportStaging()
                try {
                    val generated = SlicerProcessClient.createPrimitive(primitive, 20f, staging)
                    val info = ModelInfo.fromJson(
                        NativeEngine.inspectStl(generated.file.absolutePath),
                        generated.file.absolutePath,
                    )
                    assertTrue("$primitive must contain triangles", info.triangles >= 12)
                    assertTrue("$primitive must be finite", info.dimensions.all(Double::isFinite))
                    assertEquals(20.0, info.dimensions[0], 0.25)
                    assertEquals(20.0, info.dimensions[1], 0.25)
                    val expectedHeight = when (primitive) {
                        OrcaPrimitive.DISC -> 0.2
                        OrcaPrimitive.TORUS -> 5.0
                        else -> 20.0
                    }
                    assertEquals(expectedHeight, info.dimensions[2], 0.25)

                    if (primitive == OrcaPrimitive.CUBE) {
                        val installed = store.installImportedModel(
                            generated.file,
                            "cube.stl",
                        )
                        val sliced = OnDeviceSlicer.slice(
                            File(installed.localPath),
                            SliceOptions().copy(
                                layerHeight = 0.2f,
                                firstLayerHeight = 0.2f,
                                bedSizeX = 100f,
                                bedSizeY = 100f,
                                bedPolygon = rectangularBedPolygon(100f, 100f),
                            ),
                        )
                        assertTrue("layers=${sliced.layers}", sliced.layers in 90..110)
                        assertTrue(sliced.output.length() > 10_000L)
                    }
                } finally {
                    staging.deleteRecursively()
                }
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
