package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaVolumeSemanticsInstrumentedTest {
    @Test
    fun negativeVolumeRemovesMaterialInRealOrcaSlice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outerFile = File(context.cacheDir, "semantic-outer.stl")
        val cutoutFile = File(context.cacheDir, "semantic-cutout.stl")
        val outputs = ArrayList<File>()
        outerFile.writeText(boxStl("outer", 0f, 0f, 0f, 24f, 24f, 24f))
        cutoutFile.writeText(boxStl("cutout", 4f, 4f, 0f, 20f, 20f, 24f))
        try {
            val outer = projectVolume(outerFile)
            val baseline = ProjectObject(UUID.randomUUID().toString(), volumes = listOf(outer))
            val withCutout = ProjectObject(
                UUID.randomUUID().toString(),
                volumes = listOf(
                    outer.copy(id = UUID.randomUUID().toString()),
                    projectVolume(cutoutFile).copy(
                        role = ProjectVolumeRole.NEGATIVE_VOLUME,
                    ),
                ),
            )
            val options = denseSliceOptions()

            val solid = OnDeviceSlicer.slice(listOf(baseline), options).also { outputs += it.output }
            val hollow = OnDeviceSlicer.slice(listOf(withCutout), options).also { outputs += it.output }

            assertTrue("Baseline filament estimate must be meaningful", solid.filamentMm > 100f)
            assertTrue(
                "A negative volume must remove a substantial part of the solid, " +
                    "solid=${solid.filamentMm}, cut=${hollow.filamentMm}",
                hollow.filamentMm < solid.filamentMm * 0.82f,
            )
        } finally {
            outputs.forEach(File::delete)
            outerFile.delete()
            cutoutFile.delete()
        }
    }

    @Test
    fun parameterModifierChangesOnlyItsRegionInRealOrcaSlice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outerFile = File(context.cacheDir, "modifier-outer.stl")
        val modifierFile = File(context.cacheDir, "modifier-region.stl")
        val outputs = ArrayList<File>()
        outerFile.writeText(boxStl("outer", 0f, 0f, 0f, 30f, 30f, 20f))
        modifierFile.writeText(boxStl("modifier", 0f, 0f, 0f, 15f, 30f, 20f))
        try {
            val outer = projectVolume(outerFile)
            val baseline = ProjectObject(UUID.randomUUID().toString(), volumes = listOf(outer))
            val modified = ProjectObject(
                UUID.randomUUID().toString(),
                volumes = listOf(
                    outer.copy(id = UUID.randomUUID().toString()),
                    projectVolume(modifierFile).copy(
                        role = ProjectVolumeRole.PARAMETER_MODIFIER,
                        config = ProjectVolumeConfig(
                            mapOf("sparse_infill_density" to "100%"),
                        ),
                    ),
                ),
            )
            val options = denseSliceOptions().copy(fillDensity = 0.05f)

            val sparse = OnDeviceSlicer.slice(listOf(baseline), options).also { outputs += it.output }
            val regional = OnDeviceSlicer.slice(listOf(modified), options).also { outputs += it.output }

            assertTrue("Sparse baseline filament estimate must be meaningful", sparse.filamentMm > 100f)
            assertTrue(
                "A dense parameter-modifier region must materially increase extrusion, " +
                    "sparse=${sparse.filamentMm}, modified=${regional.filamentMm}",
                regional.filamentMm > sparse.filamentMm * 1.35f,
            )
        } finally {
            outputs.forEach(File::delete)
            outerFile.delete()
            modifierFile.delete()
        }
    }

    private fun projectVolume(file: File): ProjectVolume = ProjectVolume(
        id = UUID.randomUUID().toString(),
        model = inspectModel(file.absolutePath),
    )

    private fun denseSliceOptions(): SliceOptions = SliceOptions().copy(
        fillDensity = 1f,
        layerHeight = 0.24f,
        bedSizeX = 100f,
        bedSizeY = 100f,
        bedPolygon = rectangularBedPolygon(100f, 100f),
    )

    private fun boxStl(
        name: String,
        minX: Float,
        minY: Float,
        minZ: Float,
        maxX: Float,
        maxY: Float,
        maxZ: Float,
    ): String {
        val vertices = arrayOf(
            floatArrayOf(minX, minY, minZ), floatArrayOf(maxX, minY, minZ),
            floatArrayOf(maxX, maxY, minZ), floatArrayOf(minX, maxY, minZ),
            floatArrayOf(minX, minY, maxZ), floatArrayOf(maxX, minY, maxZ),
            floatArrayOf(maxX, maxY, maxZ), floatArrayOf(minX, maxY, maxZ),
        )
        val faces = arrayOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6),
            intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7),
            intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        return buildString {
            appendLine("solid $name")
            faces.forEach { face ->
                appendLine("facet normal 0 0 0")
                appendLine("outer loop")
                face.forEach { vertexIndex ->
                    val vertex = vertices[vertexIndex]
                    appendLine("vertex ${vertex[0]} ${vertex[1]} ${vertex[2]}")
                }
                appendLine("endloop")
                appendLine("endfacet")
            }
            appendLine("endsolid $name")
        }
    }
}
