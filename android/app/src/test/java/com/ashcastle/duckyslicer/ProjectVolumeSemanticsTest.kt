package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectVolumeSemanticsTest {
    @Test
    fun nativeRoleValuesAreStableAndComplete() {
        assertEquals(listOf(0, 1, 2, 3, 4), ProjectVolumeRole.entries.map { it.nativeValue })
        ProjectVolumeRole.entries.forEach { role ->
            assertEquals(role, ProjectVolumeRole.fromNative(role.nativeValue))
        }
        assertThrows(IllegalArgumentException::class.java) { ProjectVolumeRole.fromNative(5) }
    }

    @Test
    fun volumeConfigSidecarAndJsonRoundTripExactly() {
        val root = Files.createTempDirectory("ducky-volume-config-").toFile()
        try {
            val config = ProjectVolumeConfig(
                linkedMapOf(
                    "wall_loops" to "5",
                    "sparse_infill_density" to "31%",
                    "top_surface_pattern" to "monotonicline",
                ),
            )
            val sidecar = root.resolve("modifier.bin")

            config.writeSidecar(sidecar)

            assertEquals(config.encodedBytes.toLong(), sidecar.length())
            assertEquals(config, ProjectVolumeConfig.readSidecar(sidecar))
            assertEquals(config, ProjectVolumeConfig.fromJson(config.toJson()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun auxiliaryVolumesRejectPrintableOnlyState() {
        val model = ModelInfo(
            fileName = "part.stl",
            triangles = 1,
            dimensions = listOf(1.0, 1.0, 1.0),
            localPath = "/tmp/part.stl",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(1.0, 1.0, 1.0),
            previewTriangles = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectVolume(
                id = "negative",
                model = model,
                supportPaint = SupportPaint().paint(0, SupportPaintState.BLOCK),
                role = ProjectVolumeRole.NEGATIVE_VOLUME,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectVolume(
                id = "blocker",
                model = model,
                filamentSlot = 1,
                role = ProjectVolumeRole.SUPPORT_BLOCKER,
            )
        }
        assertTrue(ProjectVolumeRole.PARAMETER_MODIFIER.acceptsFilament)
        assertTrue(!ProjectVolumeRole.PARAMETER_MODIFIER.acceptsFacetPaint)
    }

    @Test
    fun mobileAuxiliaryShapeDraftsCoverEveryCreatableRoleAndBoundTheirInputs() {
        assertEquals(
            listOf(
                ProjectVolumeRole.NEGATIVE_VOLUME,
                ProjectVolumeRole.PARAMETER_MODIFIER,
                ProjectVolumeRole.SUPPORT_BLOCKER,
                ProjectVolumeRole.SUPPORT_ENFORCER,
            ),
            CREATABLE_AUXILIARY_VOLUME_ROLES,
        )
        CREATABLE_AUXILIARY_VOLUME_ROLES.forEach { role ->
            val draft = OrcaAuxiliaryPrimitiveDraft(
                primitive = OrcaPrimitive.CYLINDER,
                role = role,
                sizeMm = 25f,
                centerOffsetXmm = 2f,
                centerOffsetYmm = -3f,
                centerOffsetZmm = 4f,
                modifierInfillPercent = 73,
            )
            assertEquals(
                if (role == ProjectVolumeRole.PARAMETER_MODIFIER) {
                    mapOf("sparse_infill_density" to "73%")
                } else {
                    emptyMap()
                },
                draft.config.values,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrcaAuxiliaryPrimitiveDraft(
                primitive = OrcaPrimitive.CUBE,
                role = ProjectVolumeRole.MODEL_PART,
                sizeMm = 20f,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrcaAuxiliaryPrimitiveDraft(
                primitive = OrcaPrimitive.CUBE,
                role = ProjectVolumeRole.NEGATIVE_VOLUME,
                sizeMm = Float.NaN,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrcaAuxiliaryPrimitiveDraft(
                primitive = OrcaPrimitive.CUBE,
                role = ProjectVolumeRole.PARAMETER_MODIFIER,
                sizeMm = 20f,
                modifierInfillPercent = 101,
            )
        }
    }

    @Test
    fun auxiliaryVolumeEditDraftBoundsScalePlacementAndPreservesModifierSettings() {
        val volume = ProjectVolume(
            id = "settings-region",
            model = model("settings-region"),
            role = ProjectVolumeRole.PARAMETER_MODIFIER,
            config = ProjectVolumeConfig(
                mapOf(
                    "sparse_infill_density" to "20%",
                    "wall_loops" to "4",
                ),
            ),
        )
        val draft = OrcaAuxiliaryVolumeEditDraft(
            volumeId = volume.id,
            scalePercent = 175,
            centerOffsetXmm = 4f,
            centerOffsetYmm = -5f,
            centerOffsetZmm = 6f,
            modifierInfillPercent = 73,
        )

        assertEquals(
            mapOf(
                "sparse_infill_density" to "73%",
                "wall_loops" to "4",
            ),
            draft.updatedConfig(volume).values,
        )
        assertThrows(IllegalArgumentException::class.java) {
            draft.copy(scalePercent = MIN_AUXILIARY_EDIT_SCALE_PERCENT - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft.copy(centerOffsetZmm = Float.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft.copy(modifierInfillPercent = -1)
        }
    }

    @Test
    fun projectAndArchiveObjectsRequirePrintableModelParts() {
        val model = ModelInfo(
            fileName = "cutout.stl",
            triangles = 1,
            dimensions = listOf(1.0, 1.0, 1.0),
            localPath = "/tmp/cutout.stl",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(1.0, 1.0, 1.0),
            previewTriangles = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
        )
        val negative = ProjectVolume(
            id = "negative",
            model = model,
            role = ProjectVolumeRole.NEGATIVE_VOLUME,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProjectObject(id = "invalid", volumes = listOf(negative))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchivedProjectObject(
                id = "invalid",
                volumes = listOf(
                    ArchivedProjectVolume(
                        id = "negative",
                        displayName = "cutout.stl",
                        modelEntry = "models/000.stl",
                        supportPaint = SupportPaint(),
                        seamPaint = SeamPaint(),
                        multiColorPaint = MultiColorPaint(),
                        filamentSlot = 0,
                        role = ProjectVolumeRole.NEGATIVE_VOLUME,
                        config = ProjectVolumeConfig(),
                    ),
                ),
                transform = ModelTransform(),
                variableLayerHeights = VariableLayerHeights(),
                processOverrides = ObjectProcessOverrides(),
                brimPoints = BrimPoints(),
            )
        }
    }

    private fun model(name: String) = ModelInfo(
        fileName = "$name.stl",
        triangles = 1,
        dimensions = listOf(1.0, 1.0, 1.0),
        localPath = "/tmp/$name.stl",
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(1.0, 1.0, 1.0),
        previewTriangles = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
    )
}
