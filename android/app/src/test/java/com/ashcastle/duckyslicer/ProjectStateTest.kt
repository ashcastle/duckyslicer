package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectStateTest {
    private fun projectObject(id: String) = ProjectObject(
        id = id,
        model = ModelInfo(
            fileName = "$id.stl",
            triangles = 1,
            dimensions = listOf(1.0, 1.0, 1.0),
            localPath = "/tmp/$id.stl",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(1.0, 1.0, 1.0),
            previewTriangles = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
        ),
    )

    @Test
    fun addRemoveUndoAndRedoKeepObjectSelectionDeterministic() {
        val first = projectObject("first")
        val second = projectObject("second")
        var state = ProjectHistoryState().add(first).add(second)

        assertEquals(listOf(first, second), state.current.objects)
        assertEquals("second", state.current.selectedObjectId)

        state = state.removeSelected()
        assertEquals(listOf(first), state.current.objects)
        assertEquals("first", state.current.selectedObjectId)
        assertTrue(state.canUndo)

        state = state.undo()
        assertEquals(listOf(first, second), state.current.objects)
        assertEquals("second", state.current.selectedObjectId)
        assertTrue(state.canRedo)

        state = state.redo()
        assertEquals(listOf(first), state.current.objects)
        assertFalse(state.canRedo)
    }

    @Test
    fun dragUpdatesCoalesceIntoOneUndoEntry() {
        var state = ProjectHistoryState().add(projectObject("part"))
        val before = state.current.selectedObject!!.transform

        state = state.updateSelectedTransform(before.copy(offsetXmm = 5f), recordHistory = false)
        state = state.updateSelectedTransform(before.copy(offsetXmm = 12f), recordHistory = false)
        state = state.commitSelectedTransform(before)

        assertEquals(12f, state.current.selectedObject!!.transform.offsetXmm)
        state = state.undo()
        assertEquals(0f, state.current.selectedObject!!.transform.offsetXmm)
        state = state.undo()
        assertTrue(state.current.objects.isEmpty())
    }

    @Test
    fun aNewEditClearsRedoHistory() {
        var state = ProjectHistoryState().add(projectObject("part"))
        state = state.updateSelectedTransform(ModelTransform(offsetXmm = 10f))
        state = state.undo()
        assertTrue(state.canRedo)

        state = state.updateSelectedTransform(ModelTransform(offsetYmm = 8f))
        assertFalse(state.canRedo)
        assertEquals(8f, state.current.selectedObject!!.transform.offsetYmm)
    }

    @Test
    fun duplicateAndOrcaArrangementAreSingleUndoableProjectEdits() {
        var state = ProjectHistoryState().add(projectObject("part"))
        state = state.duplicateSelected("copy")
        assertEquals(2, state.current.objects.size)
        assertEquals("copy", state.current.selectedObjectId)
        assertEquals(legacyProjectVolumeId("part"), state.current.objects.first().singleVolume.id)
        assertEquals(legacyProjectVolumeId("copy"), state.current.selectedObject!!.singleVolume.id)
        assertTrue(
            state.current.objects.first().singleVolume.id !=
                state.current.selectedObject!!.singleVolume.id,
        )

        state = state.applyOrcaArrangement(
            OrcaArrangement(
                lowerLeftMm = floatArrayOf(10f, 10f, 20f, 10f),
                sizesMm = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f),
                centersMm = floatArrayOf(55f, 56f, 43f, 42f),
            ),
            bedSizeX = 100f,
            bedSizeY = 100f,
        )
        assertEquals(5f, state.current.objects.first().transform.offsetXmm)
        assertEquals(-7f, state.current.selectedObject!!.transform.offsetXmm)
        assertEquals(-8f, state.current.selectedObject!!.transform.offsetYmm)
        state = state.undo()
        assertEquals(12f, state.current.selectedObject!!.transform.offsetXmm)
    }

    @Test
    fun splitReplacementIsOneUndoableEditAtTheOriginalListPosition() {
        val before = projectObject("compound")
        val trailing = projectObject("trailing")
        var state = ProjectHistoryState()
            .add(before)
            .add(trailing)
            .select(before.id)

        state = state.replaceSelected(listOf(projectObject("left"), projectObject("right")))

        assertEquals(listOf("left", "right", "trailing"), state.current.objects.map { it.id })
        assertEquals("left", state.current.selectedObjectId)
        state = state.undo()
        assertEquals(listOf("compound", "trailing"), state.current.objects.map { it.id })
        assertEquals("compound", state.current.selectedObjectId)
        state = state.redo()
        assertEquals(listOf("left", "right", "trailing"), state.current.objects.map { it.id })
    }

    @Test
    fun splitPartsKeepObjectIdentityAndUseDeterministicVolumeIdsAcrossUndoRedo() {
        val before = projectObject("compound")
        val source = before.singleVolume
        val secondPart = source.copy(
            id = splitProjectVolumeId(before.id, source.id, 1),
            model = source.model.copy(fileName = "compound part 2.stl"),
        )
        val replacement = before.copy(volumes = listOf(source, secondPart))
        var state = ProjectHistoryState().add(before)

        state = state.replaceSelected(listOf(replacement))

        assertEquals("compound", state.current.selectedObjectId)
        assertEquals(
            splitProjectVolumeId(before.id, source.id, 1),
            state.current.selectedObject!!.volumes[1].id,
        )
        state = state.undo()
        assertEquals(1, state.current.selectedObject!!.volumes.size)
        state = state.redo()
        assertEquals(replacement, state.current.selectedObject)
    }

    @Test
    fun asynchronousPlacementUpdatesTheRequestedObjectEvenIfSelectionChanges() {
        var state = ProjectHistoryState()
            .add(projectObject("first"))
            .add(projectObject("second"))
            .select("second")

        state = state.updateTransform("first", ModelTransform(rotationXdeg = 90f))

        assertEquals("second", state.current.selectedObjectId)
        assertEquals(90f, state.current.objects.first { it.id == "first" }.transform.rotationXdeg)
        assertEquals(0f, state.current.objects.first { it.id == "second" }.transform.rotationXdeg)
        state = state.undo()
        assertEquals(0f, state.current.objects.first { it.id == "first" }.transform.rotationXdeg)
    }

    @Test
    fun supportPaintingIsObjectScopedAndUndoable() {
        var state = ProjectHistoryState().add(projectObject("part"))
        state = state.updateSupportPaint(
            "part",
            SupportPaint().paint(0, SupportPaintState.ENFORCE),
            recordHistory = false,
        )
        state = state.updateSupportPaint(
            "part",
            state.current.selectedObject!!.supportPaint.paint(0, SupportPaintState.BLOCK),
            recordHistory = false,
        )
        state = state.commitSupportPaint("part", SupportPaint())

        assertEquals(SupportPaintState.BLOCK, state.current.selectedObject!!.supportPaint.facets[0])
        state = state.undo()
        assertTrue(state.current.selectedObject!!.supportPaint.facets.isEmpty())
    }

    @Test
    fun seamPaintingIsObjectScopedAndUndoable() {
        var state = ProjectHistoryState().add(projectObject("part"))
        state = state.updateSeamPaint(
            "part",
            SeamPaint().paint(0, SeamPaintState.ENFORCE),
            recordHistory = false,
        )
        state = state.updateSeamPaint(
            "part",
            state.current.selectedObject!!.seamPaint.paint(0, SeamPaintState.BLOCK),
            recordHistory = false,
        )
        state = state.commitSeamPaint("part", SeamPaint())

        assertEquals(SeamPaintState.BLOCK, state.current.selectedObject!!.seamPaint.facets[0])
        state = state.undo()
        assertTrue(state.current.selectedObject!!.seamPaint.facets.isEmpty())
    }

    @Test
    fun multiColorPaintingIsObjectScopedUndoableAndConstrainedWithFilaments() {
        var state = ProjectHistoryState().add(projectObject("part"))
        state = state.updateMultiColorPaint(
            "part",
            MultiColorPaint().paint(0, 1),
            recordHistory = false,
        )
        state = state.commitMultiColorPaint("part", MultiColorPaint())

        assertEquals(1, state.current.selectedObject!!.multiColorPaint.facets[0])
        state = state.undo()
        assertTrue(state.current.selectedObject!!.multiColorPaint.facets.isEmpty())
        state = state.redo().constrainFilamentSlots(1)
        assertTrue(state.current.selectedObject!!.multiColorPaint.facets.isEmpty())
        state = state.undo()
        assertEquals(1, state.current.selectedObject!!.multiColorPaint.facets[0])
    }

    @Test
    fun surfacePaintingTargetsOneVolumeWithoutMutatingItsSiblings() {
        val base = projectObject("assembly")
        val left = base.singleVolume.copy(id = "left")
        val right = base.singleVolume.copy(id = "right")
        var state = ProjectHistoryState().add(
            base.copy(volumes = listOf(left, right)),
        )

        state = state.updateSupportPaint(
            "assembly",
            "right",
            SupportPaint().paint(0, SupportPaintState.ENFORCE),
            recordHistory = false,
        )
        state = state.commitSupportPaint("assembly", "right", SupportPaint())

        assertTrue(state.current.selectedObject!!.volumes[0].supportPaint.facets.isEmpty())
        assertEquals(
            SupportPaintState.ENFORCE,
            state.current.selectedObject!!.volumes[1].supportPaint.facets[0],
        )
        state = state.undo()
        assertTrue(state.current.selectedObject!!.volumes.all { it.supportPaint.facets.isEmpty() })
    }

    @Test
    fun surfacePaintingRejectsAuxiliaryVolumes() {
        val base = projectObject("assembly")
        val modelPart = base.singleVolume.copy(id = "model")
        val negative = base.singleVolume.copy(
            id = "negative",
            role = ProjectVolumeRole.NEGATIVE_VOLUME,
        )
        val state = ProjectHistoryState().add(
            base.copy(volumes = listOf(modelPart, negative)),
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            state.updateSupportPaint(
                "assembly",
                "negative",
                SupportPaint().paint(0, SupportPaintState.BLOCK),
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            state.updateSeamPaint(
                "assembly",
                "negative",
                SeamPaint().paint(0, SeamPaintState.BLOCK),
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            state.updateMultiColorPaint(
                "assembly",
                "negative",
                MultiColorPaint().paint(0, 0),
            )
        }
    }

    @Test
    fun variableLayerHeightsAreObjectScopedAndUndoable() {
        var state = ProjectHistoryState().add(projectObject("part"))
        val variableLayers = VariableLayerHeights(
            listOf(VariableLayerRange(0.25f, 0.75f, 0.08f)),
        )

        state = state.updateSelectedVariableLayerHeights(variableLayers)
        assertEquals(variableLayers, state.current.selectedObject!!.variableLayerHeights)

        state = state.undo()
        assertTrue(state.current.selectedObject!!.variableLayerHeights.ranges.isEmpty())
        state = state.redo()
        assertEquals(variableLayers, state.current.selectedObject!!.variableLayerHeights)
    }

    @Test
    fun brimPointsAreObjectScopedUndoableAndRetainedByDuplication() {
        val points = BrimPoints(
            listOf(
                BrimPoint(0f, 0f, 0f, 4f),
                BrimPoint(1f, 1f, 0f, 5f),
            ),
        )
        var state = ProjectHistoryState()
            .add(projectObject("first"))
            .add(projectObject("second"))

        state = state.updateSelectedBrimPoints(points)
        assertTrue(state.current.objects.first().brimPoints.points.isEmpty())
        assertEquals(points, state.current.selectedObject!!.brimPoints)

        state = state.undo()
        assertTrue(state.current.selectedObject!!.brimPoints.points.isEmpty())
        state = state.redo()
        assertEquals(points, state.current.selectedObject!!.brimPoints)

        state = state.duplicateSelected("second-copy")
        assertEquals(points, state.current.selectedObject!!.brimPoints)
        state = state.undo()
        assertEquals("second", state.current.selectedObjectId)
        assertEquals(points, state.current.selectedObject!!.brimPoints)
    }

    @Test
    fun processOverridesAreObjectScopedAndUndoable() {
        var state = ProjectHistoryState()
            .add(projectObject("first"))
            .add(projectObject("second"))
        val overrides = ObjectProcessOverrides(
            layerHeightMm = 0.12f,
            wallLoops = 5,
            sparseInfillDensityPercent = 30f,
            supportEnabled = true,
        )

        state = state.updateSelectedProcessOverrides(overrides)
        assertTrue(state.current.objects.first().processOverrides.isEmpty)
        assertEquals(overrides, state.current.selectedObject!!.processOverrides)

        state = state.undo()
        assertTrue(state.current.selectedObject!!.processOverrides.isEmpty)
        state = state.redo()
        assertEquals(overrides, state.current.selectedObject!!.processOverrides)
    }

    @Test
    fun filamentAssignmentUpdatesEverySelectedObjectVolumeAndRemainsUndoable() {
        val second = projectObject("second")
        var state = ProjectHistoryState()
            .add(projectObject("first"))
            .add(
                second.copy(
                    volumes = listOf(
                        second.singleVolume.copy(id = "second-left"),
                        second.singleVolume.copy(id = "second-right"),
                    ),
                ),
            )

        state = state.updateSelectedFilamentSlot(1)
        assertEquals(0, state.current.objects.first().filamentSlot)
        assertTrue(state.current.selectedObject!!.volumes.all { it.filamentSlot == 1 })

        state = state.undo()
        assertTrue(state.current.selectedObject!!.volumes.all { it.filamentSlot == 0 })
        state = state.redo().constrainFilamentSlots(1)
        assertTrue(state.current.selectedObject!!.volumes.all { it.filamentSlot == 0 })
        state = state.undo()
        assertTrue(state.current.selectedObject!!.volumes.all { it.filamentSlot == 1 })
    }

    @Test
    fun filamentAssignmentPreservesNonFilamentAuxiliaryVolumes() {
        val base = projectObject("assembly")
        val modelPart = base.singleVolume.copy(id = "model")
        val modifier = base.singleVolume.copy(
            id = "modifier",
            role = ProjectVolumeRole.PARAMETER_MODIFIER,
        )
        val negative = base.singleVolume.copy(
            id = "negative",
            role = ProjectVolumeRole.NEGATIVE_VOLUME,
        )
        val blocker = base.singleVolume.copy(
            id = "blocker",
            role = ProjectVolumeRole.SUPPORT_BLOCKER,
        )
        var state = ProjectHistoryState().add(
            base.copy(volumes = listOf(negative, modelPart, blocker, modifier)),
        )

        state = state.updateSelectedFilamentSlot(2)

        val assigned = state.current.selectedObject!!.volumes.associateBy(ProjectVolume::id)
        assertEquals(2, assigned.getValue("model").filamentSlot)
        assertEquals(2, assigned.getValue("modifier").filamentSlot)
        assertEquals(0, assigned.getValue("negative").filamentSlot)
        assertEquals(0, assigned.getValue("blocker").filamentSlot)
        assertEquals("model", state.current.selectedObject!!.primaryModelPart.id)

        val restored = state.undo().current.selectedObject!!.volumes
        assertTrue(restored.all { it.filamentSlot == 0 })
    }

    @Test
    fun platesKeepObjectsAndSelectionIsolatedAcrossRemovalUndoAndRedo() {
        var state = ProjectHistoryState()
            .add(projectObject("first-plate-object"))
        val firstPlateId = state.current.selectedPlateId

        state = state.addPlate("second-plate").add(projectObject("second-plate-object"))
        assertEquals("second-plate", state.current.selectedPlateId)
        assertEquals(listOf("second-plate-object"), state.current.objects.map(ProjectObject::id))
        assertEquals(
            listOf("first-plate-object", "second-plate-object"),
            state.current.allObjects.map(ProjectObject::id),
        )

        state = state.selectPlate(firstPlateId)
        assertEquals(listOf("first-plate-object"), state.current.objects.map(ProjectObject::id))
        assertEquals("first-plate-object", state.current.selectedObjectId)

        state = state.selectPlate("second-plate").removeSelectedPlate()
        assertEquals(listOf(firstPlateId), state.current.plates.map(ProjectPlate::id))
        assertEquals("first-plate-object", state.current.selectedObjectId)

        state = state.undo()
        assertEquals(listOf(firstPlateId, "second-plate"), state.current.plates.map(ProjectPlate::id))
        assertEquals("second-plate", state.current.selectedPlateId)
        assertEquals("second-plate-object", state.current.selectedObjectId)

        state = state.redo()
        assertEquals(listOf(firstPlateId), state.current.plates.map(ProjectPlate::id))
        assertEquals(state, state.removeSelectedPlate())
    }
}
