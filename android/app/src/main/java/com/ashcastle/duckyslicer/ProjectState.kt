package com.ashcastle.duckyslicer

import java.util.UUID

internal const val MAX_PROJECT_VOLUMES_PER_OBJECT = 64

data class ProjectVolume(
    val id: String,
    val model: ModelInfo,
    val supportPaint: SupportPaint = SupportPaint(),
    val seamPaint: SeamPaint = SeamPaint(),
    val multiColorPaint: MultiColorPaint = MultiColorPaint(),
    val filamentSlot: Int = 0,
) {
    init {
        require(id.length in 1..ProjectStore.MAX_ID_LENGTH) { "Invalid project volume id" }
    }
}

/**
 * Stable identity for a legacy object's only volume. Once schema 9 is written, the value
 * is persisted instead of regenerated. Volume ids are scoped to their owning object.
 */
internal fun legacyProjectVolumeId(objectId: String, index: Int = 0): String =
    UUID.nameUUIDFromBytes(
        "com.ashcastle.duckyslicer:$objectId:volume:$index".toByteArray(Charsets.UTF_8),
    ).toString()

internal fun splitProjectVolumeId(
    objectId: String,
    sourceVolumeId: String,
    partIndex: Int,
): String {
    require(partIndex > 0) { "Split part index is invalid" }
    return UUID.nameUUIDFromBytes(
        "com.ashcastle.duckyslicer:$objectId:split:$sourceVolumeId:$partIndex"
            .toByteArray(Charsets.UTF_8),
    ).toString()
}

data class ProjectObject(
    val id: String,
    val volumes: List<ProjectVolume>,
    val transform: ModelTransform = ModelTransform(),
    val variableLayerHeights: VariableLayerHeights = VariableLayerHeights(),
    val processOverrides: ObjectProcessOverrides = ObjectProcessOverrides(),
    val brimPoints: BrimPoints = BrimPoints(),
) {
    init {
        require(volumes.size in 1..MAX_PROJECT_VOLUMES_PER_OBJECT) {
            "Project object volume count is invalid"
        }
        require(volumes.map(ProjectVolume::id).toSet().size == volumes.size) {
            "Project object contains duplicate volume ids"
        }
    }

    constructor(
        id: String,
        model: ModelInfo,
        transform: ModelTransform = ModelTransform(),
        supportPaint: SupportPaint = SupportPaint(),
        seamPaint: SeamPaint = SeamPaint(),
        multiColorPaint: MultiColorPaint = MultiColorPaint(),
        variableLayerHeights: VariableLayerHeights = VariableLayerHeights(),
        processOverrides: ObjectProcessOverrides = ObjectProcessOverrides(),
        brimPoints: BrimPoints = BrimPoints(),
        filamentSlot: Int = 0,
    ) : this(
        id = id,
        volumes = listOf(
            ProjectVolume(
                id = legacyProjectVolumeId(id),
                model = model,
                supportPaint = supportPaint,
                seamPaint = seamPaint,
                multiColorPaint = multiColorPaint,
                filamentSlot = filamentSlot,
            ),
        ),
        transform = transform,
        variableLayerHeights = variableLayerHeights,
        processOverrides = processOverrides,
        brimPoints = brimPoints,
    )

    val singleVolumeOrNull: ProjectVolume?
        get() = volumes.singleOrNull()

    val singleVolume: ProjectVolume
        get() = requireNotNull(singleVolumeOrNull) {
            "This operation requires a single-volume object"
        }

    // Compatibility accessors keep existing one-volume behavior explicit while the next
    // milestone teaches renderer and slicer boundaries to address a volume by id/index.
    val model: ModelInfo get() = singleVolume.model
    val supportPaint: SupportPaint get() = singleVolume.supportPaint
    val seamPaint: SeamPaint get() = singleVolume.seamPaint
    val multiColorPaint: MultiColorPaint get() = singleVolume.multiColorPaint
    val filamentSlot: Int get() = singleVolume.filamentSlot

    fun updateSingleVolume(update: (ProjectVolume) -> ProjectVolume): ProjectObject =
        copy(volumes = listOf(update(singleVolume)))

    fun rebaseVolumeIds(newObjectId: String): List<ProjectVolume> = volumes.mapIndexed { index, volume ->
        volume.copy(id = legacyProjectVolumeId(newObjectId, index))
    }
}

data class ProjectSnapshot(
    val objects: List<ProjectObject> = emptyList(),
    val selectedObjectId: String? = null,
) {
    val selectedObject: ProjectObject?
        get() = objects.firstOrNull { it.id == selectedObjectId }
}

data class ProjectHistoryState(
    val current: ProjectSnapshot = ProjectSnapshot(),
    private val undoStates: List<ProjectSnapshot> = emptyList(),
    private val redoStates: List<ProjectSnapshot> = emptyList(),
) {
    val canUndo: Boolean get() = undoStates.isNotEmpty()
    val canRedo: Boolean get() = redoStates.isNotEmpty()

    fun add(projectObject: ProjectObject): ProjectHistoryState {
        require(current.objects.none { it.id == projectObject.id }) { "Duplicate project object id" }
        return record(
            current.copy(
                objects = current.objects + projectObject,
                selectedObjectId = projectObject.id,
            ),
        )
    }

    fun addAll(projectObjects: List<ProjectObject>): ProjectHistoryState {
        if (projectObjects.isEmpty()) return this
        val existingIds = current.objects.mapTo(HashSet(), ProjectObject::id)
        require(projectObjects.all { existingIds.add(it.id) }) { "Duplicate project object id" }
        return record(
            current.copy(
                objects = current.objects + projectObjects,
                selectedObjectId = projectObjects.last().id,
            ),
        )
    }

    fun replaceSelected(replacements: List<ProjectObject>): ProjectHistoryState {
        val selectedId = current.selectedObjectId ?: return this
        require(replacements.isNotEmpty()) { "Replacement objects are empty" }
        val selectedIndex = current.objects.indexOfFirst { it.id == selectedId }
        if (selectedIndex < 0) return this
        require(current.objects.size - 1 + replacements.size <= ProjectStore.MAX_PROJECT_OBJECTS) {
            "Project has too many objects"
        }
        val ids = current.objects
            .asSequence()
            .filterNot { it.id == selectedId }
            .mapTo(HashSet(), ProjectObject::id)
        require(replacements.all { ids.add(it.id) }) { "Duplicate project object id" }
        val nextObjects = current.objects.toMutableList().apply {
            removeAt(selectedIndex)
            addAll(selectedIndex, replacements)
        }
        return record(
            current.copy(
                objects = nextObjects,
                selectedObjectId = replacements.first().id,
            ),
        )
    }

    fun removeSelected(): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        val remaining = current.objects.filterNot { it.id == selected.id }
        return record(
            current.copy(
                objects = remaining,
                selectedObjectId = remaining.lastOrNull()?.id,
            ),
        )
    }

    fun clear(): ProjectHistoryState = if (current.objects.isEmpty()) this else record(ProjectSnapshot())

    fun duplicateSelected(newId: String): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        require(current.objects.none { it.id == newId }) { "Duplicate project object id" }
        val duplicate = selected.copy(
            id = newId,
            volumes = selected.rebaseVolumeIds(newId),
            transform = selected.transform.copy(
                offsetXmm = selected.transform.offsetXmm + 12f,
                offsetYmm = selected.transform.offsetYmm + 12f,
            ),
        )
        return record(
            current.copy(
                objects = current.objects + duplicate,
                selectedObjectId = duplicate.id,
            ),
        )
    }

    fun applyOrcaArrangement(
        arrangement: OrcaArrangement,
        bedSizeX: Float,
        bedSizeY: Float,
    ): ProjectHistoryState {
        require(arrangement.objectCount == current.objects.size) {
            "Arrangement count does not match the project"
        }
        val arranged = current.objects.mapIndexed { index, projectObject ->
            projectObject.copy(
                transform = projectObject.transform.copy(
                    offsetXmm = arrangement.centersMm[index * 2] - bedSizeX / 2f,
                    offsetYmm = arrangement.centersMm[index * 2 + 1] - bedSizeY / 2f,
                ),
            )
        }
        return if (arranged == current.objects) this else record(current.copy(objects = arranged))
    }

    fun select(objectId: String?): ProjectHistoryState {
        require(objectId == null || current.objects.any { it.id == objectId }) {
            "Selected object is not part of the project"
        }
        return copy(current = current.copy(selectedObjectId = objectId))
    }

    fun updateSelectedTransform(
        transform: ModelTransform,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        return updateTransform(selected.id, transform, recordHistory)
    }

    fun updateTransform(
        objectId: String,
        transform: ModelTransform,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        if (target.transform == transform) return this
        val next = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) projectObject.copy(transform = transform) else projectObject
            },
        )
        return if (recordHistory) record(next) else copy(current = next)
    }

    fun updateSelectedFilamentSlot(slot: Int): ProjectHistoryState {
        require(slot in 0 until MAX_FILAMENT_SLOTS) { "Filament slot is invalid" }
        val selected = current.selectedObject ?: return this
        if (selected.volumes.all { it.filamentSlot == slot }) return this
        return record(
            current.copy(
                objects = current.objects.map { projectObject ->
                    if (projectObject.id == selected.id) {
                        projectObject.copy(
                            volumes = projectObject.volumes.map { it.copy(filamentSlot = slot) },
                        )
                    } else {
                        projectObject
                    }
                },
            ),
        )
    }

    fun updateSelectedBrimPoints(brimPoints: BrimPoints): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        return updateBrimPoints(selected.id, brimPoints)
    }

    fun updateBrimPoints(objectId: String, brimPoints: BrimPoints): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        if (target.brimPoints == brimPoints) return this
        return record(
            current.copy(
                objects = current.objects.map { projectObject ->
                    if (projectObject.id == objectId) {
                        projectObject.copy(brimPoints = brimPoints)
                    } else {
                        projectObject
                    }
                },
            ),
        )
    }

    fun constrainFilamentSlots(slotCount: Int): ProjectHistoryState {
        require(slotCount in 1..MAX_FILAMENT_SLOTS) { "Filament slot count is invalid" }
        val updated = current.objects.map { projectObject ->
            projectObject.copy(
                volumes = projectObject.volumes.map { volume ->
                    volume.copy(
                        filamentSlot = volume.filamentSlot.takeIf { it < slotCount } ?: 0,
                        multiColorPaint = volume.multiColorPaint.constrainedToSlotCount(slotCount),
                    )
                },
            )
        }
        return if (updated == current.objects) this else record(current.copy(objects = updated))
    }

    fun updateSupportPaint(
        objectId: String,
        supportPaint: SupportPaint,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        return updateSupportPaint(objectId, target.singleVolume.id, supportPaint, recordHistory)
    }

    fun updateSupportPaint(
        objectId: String,
        volumeId: String,
        supportPaint: SupportPaint,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        val targetVolume = target.volumes.firstOrNull { it.id == volumeId } ?: return this
        if (targetVolume.supportPaint == supportPaint) return this
        require(supportPaint.facets.keys.all { it in 0 until targetVolume.model.triangles }) {
            "Support paint references an unavailable facet"
        }
        val next = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) volume.copy(supportPaint = supportPaint) else volume
                        },
                    )
                } else {
                    projectObject
                }
            },
        )
        return if (recordHistory) record(next) else copy(current = next)
    }

    fun commitSupportPaint(objectId: String, previous: SupportPaint): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        return commitSupportPaint(objectId, target.singleVolume.id, previous)
    }

    fun commitSupportPaint(
        objectId: String,
        volumeId: String,
        previous: SupportPaint,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        val targetVolume = target.volumes.firstOrNull { it.id == volumeId } ?: return this
        if (targetVolume.supportPaint == previous) return this
        val previousSnapshot = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) volume.copy(supportPaint = previous) else volume
                        },
                    )
                } else {
                    projectObject
                }
            },
        )
        return copy(
            undoStates = (undoStates + previousSnapshot).takeLast(HISTORY_LIMIT),
            redoStates = emptyList(),
        )
    }

    fun updateSeamPaint(
        objectId: String,
        seamPaint: SeamPaint,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        return updateSeamPaint(objectId, target.singleVolume.id, seamPaint, recordHistory)
    }

    fun updateSeamPaint(
        objectId: String,
        volumeId: String,
        seamPaint: SeamPaint,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        val targetVolume = target.volumes.firstOrNull { it.id == volumeId } ?: return this
        if (targetVolume.seamPaint == seamPaint) return this
        require(seamPaint.facets.keys.all { it in 0 until targetVolume.model.triangles }) {
            "Seam paint references an unavailable facet"
        }
        val next = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) volume.copy(seamPaint = seamPaint) else volume
                        },
                    )
                } else {
                    projectObject
                }
            },
        )
        return if (recordHistory) record(next) else copy(current = next)
    }

    fun commitSeamPaint(objectId: String, previous: SeamPaint): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        return commitSeamPaint(objectId, target.singleVolume.id, previous)
    }

    fun commitSeamPaint(
        objectId: String,
        volumeId: String,
        previous: SeamPaint,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        val targetVolume = target.volumes.firstOrNull { it.id == volumeId } ?: return this
        if (targetVolume.seamPaint == previous) return this
        val previousSnapshot = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) volume.copy(seamPaint = previous) else volume
                        },
                    )
                } else {
                    projectObject
                }
            },
        )
        return copy(
            undoStates = (undoStates + previousSnapshot).takeLast(HISTORY_LIMIT),
            redoStates = emptyList(),
        )
    }

    fun updateMultiColorPaint(
        objectId: String,
        multiColorPaint: MultiColorPaint,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        return updateMultiColorPaint(objectId, target.singleVolume.id, multiColorPaint, recordHistory)
    }

    fun updateMultiColorPaint(
        objectId: String,
        volumeId: String,
        multiColorPaint: MultiColorPaint,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        val targetVolume = target.volumes.firstOrNull { it.id == volumeId } ?: return this
        if (targetVolume.multiColorPaint == multiColorPaint) return this
        require(multiColorPaint.facets.keys.all { it in 0 until targetVolume.model.triangles }) {
            "Multi-color paint references an unavailable facet"
        }
        val next = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) volume.copy(multiColorPaint = multiColorPaint) else volume
                        },
                    )
                } else {
                    projectObject
                }
            },
        )
        return if (recordHistory) record(next) else copy(current = next)
    }

    fun commitMultiColorPaint(
        objectId: String,
        previous: MultiColorPaint,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        return commitMultiColorPaint(objectId, target.singleVolume.id, previous)
    }

    fun commitMultiColorPaint(
        objectId: String,
        volumeId: String,
        previous: MultiColorPaint,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        val targetVolume = target.volumes.firstOrNull { it.id == volumeId } ?: return this
        if (targetVolume.multiColorPaint == previous) return this
        val previousSnapshot = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) volume.copy(multiColorPaint = previous) else volume
                        },
                    )
                } else {
                    projectObject
                }
            },
        )
        return copy(
            undoStates = (undoStates + previousSnapshot).takeLast(HISTORY_LIMIT),
            redoStates = emptyList(),
        )
    }

    fun updateSelectedVariableLayerHeights(
        variableLayerHeights: VariableLayerHeights,
    ): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        if (selected.variableLayerHeights == variableLayerHeights) return this
        return record(
            current.copy(
                objects = current.objects.map { projectObject ->
                    if (projectObject.id == selected.id) {
                        projectObject.copy(variableLayerHeights = variableLayerHeights)
                    } else {
                        projectObject
                    }
                },
            ),
        )
    }

    fun updateSelectedProcessOverrides(
        processOverrides: ObjectProcessOverrides,
    ): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        if (selected.processOverrides == processOverrides) return this
        return record(
            current.copy(
                objects = current.objects.map { projectObject ->
                    if (projectObject.id == selected.id) {
                        projectObject.copy(processOverrides = processOverrides)
                    } else {
                        projectObject
                    }
                },
            ),
        )
    }

    fun commitSelectedTransform(previous: ModelTransform): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        if (selected.transform == previous) return this
        val previousSnapshot = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == selected.id) projectObject.copy(transform = previous) else projectObject
            },
        )
        return copy(
            undoStates = (undoStates + previousSnapshot).takeLast(HISTORY_LIMIT),
            redoStates = emptyList(),
        )
    }

    fun undo(): ProjectHistoryState {
        val previous = undoStates.lastOrNull() ?: return this
        return copy(
            current = previous,
            undoStates = undoStates.dropLast(1),
            redoStates = (redoStates + current).takeLast(HISTORY_LIMIT),
        )
    }

    fun redo(): ProjectHistoryState {
        val next = redoStates.lastOrNull() ?: return this
        return copy(
            current = next,
            undoStates = (undoStates + current).takeLast(HISTORY_LIMIT),
            redoStates = redoStates.dropLast(1),
        )
    }

    private fun record(next: ProjectSnapshot): ProjectHistoryState = if (next == current) {
        this
    } else {
        copy(
            current = next,
            undoStates = (undoStates + current).takeLast(HISTORY_LIMIT),
            redoStates = emptyList(),
        )
    }

    private companion object {
        const val HISTORY_LIMIT = 60
    }
}
