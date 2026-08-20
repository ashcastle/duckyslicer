package com.ashcastle.duckyslicer

import java.util.UUID

internal const val MAX_PROJECT_VOLUMES_PER_OBJECT = 64
internal const val MAX_PROJECT_PLATES = 16

data class ProjectVolume(
    val id: String,
    val model: ModelInfo,
    val supportPaint: SupportPaint = SupportPaint(),
    val seamPaint: SeamPaint = SeamPaint(),
    val multiColorPaint: MultiColorPaint = MultiColorPaint(),
    val orcaFacetAnnotations: OrcaFacetAnnotations = OrcaFacetAnnotations(),
    val filamentSlot: Int = 0,
    val role: ProjectVolumeRole = ProjectVolumeRole.MODEL_PART,
    val config: ProjectVolumeConfig = ProjectVolumeConfig(),
) {
    init {
        require(id.length in 1..ProjectStore.MAX_ID_LENGTH) { "Invalid project volume id" }
        require(role.acceptsFacetPaint || (
            supportPaint.facets.isEmpty() && seamPaint.facets.isEmpty() &&
                multiColorPaint.facets.isEmpty() && orcaFacetAnnotations.isEmpty
        )) { "Auxiliary project volumes cannot carry facet paint" }
        orcaFacetAnnotations.support.constrainedToTriangleCount(model.triangles)
        orcaFacetAnnotations.seam.constrainedToTriangleCount(model.triangles)
        orcaFacetAnnotations.multiColor.constrainedToTriangleCount(model.triangles)
        require(orcaFacetAnnotations.support.maximumState <= 2) {
            "Support facet annotation state is invalid"
        }
        require(orcaFacetAnnotations.seam.maximumState <= 2) {
            "Seam facet annotation state is invalid"
        }
        require(orcaFacetAnnotations.multiColor.maximumState <= MAX_FILAMENT_SLOTS) {
            "Multi-color facet annotation state is invalid"
        }
        require(role.acceptsFilament || filamentSlot == 0) {
            "Auxiliary project volume filament assignment is invalid"
        }
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

internal fun legacyProjectPlateId(): String = UUID.nameUUIDFromBytes(
    "com.ashcastle.duckyslicer:legacy-plate".toByteArray(Charsets.UTF_8),
).toString()

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
        require(volumes.any { it.role == ProjectVolumeRole.MODEL_PART }) {
            "Project object has no printable model part"
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

    val modelPartVolumes: List<ProjectVolume>
        get() = volumes.filter { it.role == ProjectVolumeRole.MODEL_PART }

    val primaryModelPart: ProjectVolume
        get() = modelPartVolumes.first()

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

data class ProjectPlate(
    val id: String,
    val objects: List<ProjectObject> = emptyList(),
    val selectedObjectId: String? = null,
) {
    init {
        require(id.length in 1..ProjectStore.MAX_ID_LENGTH) { "Invalid project plate id" }
        require(objects.map(ProjectObject::id).toSet().size == objects.size) {
            "Project plate contains duplicate object ids"
        }
        require(selectedObjectId == null || objects.any { it.id == selectedObjectId }) {
            "Project plate selection is invalid"
        }
    }

    val selectedObject: ProjectObject?
        get() = objects.firstOrNull { it.id == selectedObjectId }
}

data class ProjectSnapshot(
    val selectedPlateId: String,
    val plates: List<ProjectPlate>,
) {
    constructor(
        objects: List<ProjectObject> = emptyList(),
        selectedObjectId: String? = null,
    ) : this(
        plates = listOf(
            ProjectPlate(
                id = legacyProjectPlateId(),
                objects = objects,
                selectedObjectId = selectedObjectId,
            ),
        ),
        selectedPlateId = legacyProjectPlateId(),
    )

    init {
        require(plates.size in 1..MAX_PROJECT_PLATES) { "Project plate count is invalid" }
        require(plates.map(ProjectPlate::id).toSet().size == plates.size) {
            "Project contains duplicate plate ids"
        }
        require(plates.any { it.id == selectedPlateId }) { "Selected plate is unavailable" }
        val objectIds = HashSet<String>()
        require(plates.flatMap(ProjectPlate::objects).all { objectIds.add(it.id) }) {
            "Project contains duplicate object ids"
        }
    }

    val activePlate: ProjectPlate
        get() = plates.first { it.id == selectedPlateId }

    val objects: List<ProjectObject>
        get() = activePlate.objects

    val selectedObjectId: String?
        get() = activePlate.selectedObjectId

    val selectedObject: ProjectObject?
        get() = activePlate.selectedObject

    val allObjects: List<ProjectObject>
        get() = plates.flatMap(ProjectPlate::objects)

    internal fun updateActivePlate(
        objects: List<ProjectObject> = this.objects,
        selectedObjectId: String? = this.selectedObjectId,
    ): ProjectSnapshot = copy(
        plates = plates.map { plate ->
            if (plate.id == selectedPlateId) {
                plate.copy(objects = objects, selectedObjectId = selectedObjectId)
            } else {
                plate
            }
        },
    )

    /** Compatibility copy for callers that still address the active plate as one project. */
    fun copy(
        objects: List<ProjectObject> = this.objects,
        selectedObjectId: String? = this.selectedObjectId,
    ): ProjectSnapshot = updateActivePlate(objects, selectedObjectId)

    internal fun selectPlate(plateId: String): ProjectSnapshot {
        require(plates.any { it.id == plateId }) { "Selected plate is unavailable" }
        return if (plateId == selectedPlateId) this else copy(selectedPlateId = plateId)
    }
}

data class ProjectHistoryState(
    val current: ProjectSnapshot = ProjectSnapshot(),
    private val undoStates: List<ProjectSnapshot> = emptyList(),
    private val redoStates: List<ProjectSnapshot> = emptyList(),
) {
    val canUndo: Boolean get() = undoStates.isNotEmpty()
    val canRedo: Boolean get() = redoStates.isNotEmpty()

    fun add(projectObject: ProjectObject): ProjectHistoryState {
        require(current.allObjects.none { it.id == projectObject.id }) { "Duplicate project object id" }
        require(current.allObjects.size < ProjectStore.MAX_PROJECT_OBJECTS) {
            "Project has too many objects"
        }
        return record(
            current.updateActivePlate(
                objects = current.objects + projectObject,
                selectedObjectId = projectObject.id,
            ),
        )
    }

    fun addAll(projectObjects: List<ProjectObject>): ProjectHistoryState {
        if (projectObjects.isEmpty()) return this
        require(current.allObjects.size + projectObjects.size <= ProjectStore.MAX_PROJECT_OBJECTS) {
            "Project has too many objects"
        }
        val existingIds = current.allObjects.mapTo(HashSet(), ProjectObject::id)
        require(projectObjects.all { existingIds.add(it.id) }) { "Duplicate project object id" }
        return record(
            current.updateActivePlate(
                objects = current.objects + projectObjects,
                selectedObjectId = projectObjects.last().id,
            ),
        )
    }

    fun addAuxiliaryVolumeToSelected(volume: ProjectVolume): ProjectHistoryState {
        require(volume.role in CREATABLE_AUXILIARY_VOLUME_ROLES) {
            "Only an auxiliary volume can be attached to an object"
        }
        val selected = current.selectedObject ?: return this
        require(selected.volumes.size < MAX_PROJECT_VOLUMES_PER_OBJECT) {
            "Project object has too many volumes"
        }
        require(current.allObjects.sumOf { it.volumes.size } < ProjectStore.MAX_PROJECT_VOLUMES) {
            "Project has too many volumes"
        }
        require(selected.volumes.none { it.id == volume.id }) { "Duplicate project volume id" }
        return record(
            current.updateActivePlate(
                objects = current.objects.map { projectObject ->
                    if (projectObject.id == selected.id) {
                        projectObject.copy(volumes = projectObject.volumes + volume)
                    } else {
                        projectObject
                    }
                },
            ),
        )
    }

    fun removeSelectedAuxiliaryVolume(volumeId: String): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        val target = selected.volumes.firstOrNull { it.id == volumeId } ?: return this
        require(target.role != ProjectVolumeRole.MODEL_PART) {
            "Printable model parts cannot be removed as auxiliary volumes"
        }
        return record(
            current.updateActivePlate(
                objects = current.objects.map { projectObject ->
                    if (projectObject.id == selected.id) {
                        projectObject.copy(
                            volumes = projectObject.volumes.filterNot { it.id == volumeId },
                        )
                    } else {
                        projectObject
                    }
                },
            ),
        )
    }

    fun replaceSelectedAuxiliaryVolume(
        volumeId: String,
        replacement: ProjectVolume,
    ): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        val target = selected.volumes.firstOrNull { it.id == volumeId } ?: return this
        require(target.role != ProjectVolumeRole.MODEL_PART) {
            "Printable model parts cannot be replaced as auxiliary volumes"
        }
        require(replacement.id == volumeId && replacement.role == target.role) {
            "Auxiliary volume identity or role changed"
        }
        return record(
            current.updateActivePlate(
                objects = current.objects.map { projectObject ->
                    if (projectObject.id == selected.id) {
                        projectObject.copy(
                            volumes = projectObject.volumes.map { volume ->
                                if (volume.id == volumeId) replacement else volume
                            },
                        )
                    } else {
                        projectObject
                    }
                },
            ),
        )
    }

    fun replaceSelected(replacements: List<ProjectObject>): ProjectHistoryState {
        val selectedId = current.selectedObjectId ?: return this
        require(replacements.isNotEmpty()) { "Replacement objects are empty" }
        val selectedIndex = current.objects.indexOfFirst { it.id == selectedId }
        if (selectedIndex < 0) return this
        require(current.allObjects.size - 1 + replacements.size <= ProjectStore.MAX_PROJECT_OBJECTS) {
            "Project has too many objects"
        }
        val ids = current.allObjects
            .asSequence()
            .filterNot { it.id == selectedId }
            .mapTo(HashSet(), ProjectObject::id)
        require(replacements.all { ids.add(it.id) }) { "Duplicate project object id" }
        val nextObjects = current.objects.toMutableList().apply {
            removeAt(selectedIndex)
            addAll(selectedIndex, replacements)
        }
        return record(
            current.updateActivePlate(
                objects = nextObjects,
                selectedObjectId = replacements.first().id,
            ),
        )
    }

    fun removeSelected(): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        val remaining = current.objects.filterNot { it.id == selected.id }
        return record(
            current.updateActivePlate(
                objects = remaining,
                selectedObjectId = remaining.lastOrNull()?.id,
            ),
        )
    }

    fun clear(): ProjectHistoryState = if (current.objects.isEmpty()) {
        this
    } else {
        record(current.updateActivePlate(objects = emptyList(), selectedObjectId = null))
    }

    fun addPlate(plateId: String): ProjectHistoryState {
        require(current.plates.size < MAX_PROJECT_PLATES) { "Project has too many plates" }
        require(current.plates.none { it.id == plateId }) { "Duplicate project plate id" }
        return record(
            current.copy(
                plates = current.plates + ProjectPlate(plateId),
                selectedPlateId = plateId,
            ),
        )
    }

    fun removeSelectedPlate(): ProjectHistoryState {
        if (current.plates.size <= 1) return this
        val index = current.plates.indexOfFirst { it.id == current.selectedPlateId }
        if (index < 0) return this
        val remaining = current.plates.toMutableList().apply { removeAt(index) }
        val nextSelection = remaining[minOf(index, remaining.lastIndex)].id
        return record(current.copy(plates = remaining, selectedPlateId = nextSelection))
    }

    fun selectPlate(plateId: String): ProjectHistoryState =
        copy(current = current.selectPlate(plateId))

    fun duplicateSelected(newId: String): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        require(current.allObjects.none { it.id == newId }) { "Duplicate project object id" }
        require(current.allObjects.size < ProjectStore.MAX_PROJECT_OBJECTS) {
            "Project has too many objects"
        }
        val duplicate = selected.copy(
            id = newId,
            volumes = selected.rebaseVolumeIds(newId),
            transform = selected.transform.copy(
                offsetXmm = selected.transform.offsetXmm + 12f,
                offsetYmm = selected.transform.offsetYmm + 12f,
            ),
        )
        return record(
            current.updateActivePlate(
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
        return if (arranged == current.objects) {
            this
        } else {
            record(current.updateActivePlate(objects = arranged))
        }
    }

    fun select(objectId: String?): ProjectHistoryState {
        require(objectId == null || current.objects.any { it.id == objectId }) {
            "Selected object is not part of the project"
        }
        return copy(current = current.updateActivePlate(selectedObjectId = objectId))
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
        val next = current.updateActivePlate(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) projectObject.copy(transform = transform) else projectObject
            },
        )
        return if (recordHistory) record(next) else copy(current = next)
    }

    fun updateSelectedFilamentSlot(slot: Int): ProjectHistoryState {
        require(slot in 0 until MAX_FILAMENT_SLOTS) { "Filament slot is invalid" }
        val selected = current.selectedObject ?: return this
        if (selected.volumes.filter { it.role.acceptsFilament }.all { it.filamentSlot == slot }) {
            return this
        }
        return record(
            current.updateActivePlate(
                objects = current.objects.map { projectObject ->
                    if (projectObject.id == selected.id) {
                        projectObject.copy(
                            volumes = projectObject.volumes.map { volume ->
                                if (volume.role.acceptsFilament) {
                                    volume.copy(filamentSlot = slot)
                                } else {
                                    volume
                                }
                            },
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
            current.updateActivePlate(
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
                        orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(
                            multiColor = volume.orcaFacetAnnotations.multiColor.takeIf {
                                it.maximumState <= slotCount
                            } ?: OrcaFacetAnnotation(),
                        ),
                    )
                },
            )
        }
        return if (updated == current.objects) {
            this
        } else {
            record(current.updateActivePlate(objects = updated))
        }
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
        require(targetVolume.role.acceptsFacetPaint) {
            "Support paint is unavailable for auxiliary project volumes"
        }
        if (targetVolume.supportPaint == supportPaint) return this
        require(supportPaint.facets.keys.all { it in 0 until targetVolume.model.triangles }) {
            "Support paint references an unavailable facet"
        }
        val next = current.updateActivePlate(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) {
                                volume.copy(
                                    supportPaint = supportPaint,
                                    orcaFacetAnnotations = if (recordHistory) {
                                        volume.orcaFacetAnnotations.copy(
                                            support = OrcaFacetAnnotation(),
                                        )
                                    } else {
                                        volume.orcaFacetAnnotations
                                    },
                                )
                            } else volume
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
        require(targetVolume.role.acceptsFacetPaint) {
            "Support paint is unavailable for auxiliary project volumes"
        }
        if (targetVolume.supportPaint == previous) return this
        val previousSnapshot = current.updateActivePlate(
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
        val finalized = current.updateActivePlate(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) {
                                volume.copy(
                                    orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(
                                        support = OrcaFacetAnnotation(),
                                    ),
                                )
                            } else volume
                        },
                    )
                } else projectObject
            },
        )
        return copy(
            current = finalized,
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
        require(targetVolume.role.acceptsFacetPaint) {
            "Seam paint is unavailable for auxiliary project volumes"
        }
        if (targetVolume.seamPaint == seamPaint) return this
        require(seamPaint.facets.keys.all { it in 0 until targetVolume.model.triangles }) {
            "Seam paint references an unavailable facet"
        }
        val next = current.updateActivePlate(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) {
                                volume.copy(
                                    seamPaint = seamPaint,
                                    orcaFacetAnnotations = if (recordHistory) {
                                        volume.orcaFacetAnnotations.copy(
                                            seam = OrcaFacetAnnotation(),
                                        )
                                    } else {
                                        volume.orcaFacetAnnotations
                                    },
                                )
                            } else volume
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
        require(targetVolume.role.acceptsFacetPaint) {
            "Seam paint is unavailable for auxiliary project volumes"
        }
        if (targetVolume.seamPaint == previous) return this
        val previousSnapshot = current.updateActivePlate(
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
        val finalized = current.updateActivePlate(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) {
                                volume.copy(
                                    orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(
                                        seam = OrcaFacetAnnotation(),
                                    ),
                                )
                            } else volume
                        },
                    )
                } else projectObject
            },
        )
        return copy(
            current = finalized,
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
        require(targetVolume.role.acceptsFacetPaint) {
            "Multi-color paint is unavailable for auxiliary project volumes"
        }
        if (targetVolume.multiColorPaint == multiColorPaint) return this
        require(multiColorPaint.facets.keys.all { it in 0 until targetVolume.model.triangles }) {
            "Multi-color paint references an unavailable facet"
        }
        val next = current.updateActivePlate(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) {
                                volume.copy(
                                    multiColorPaint = multiColorPaint,
                                    orcaFacetAnnotations = if (recordHistory) {
                                        volume.orcaFacetAnnotations.copy(
                                            multiColor = OrcaFacetAnnotation(),
                                        )
                                    } else {
                                        volume.orcaFacetAnnotations
                                    },
                                )
                            } else volume
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
        require(targetVolume.role.acceptsFacetPaint) {
            "Multi-color paint is unavailable for auxiliary project volumes"
        }
        if (targetVolume.multiColorPaint == previous) return this
        val previousSnapshot = current.updateActivePlate(
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
        val finalized = current.updateActivePlate(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(
                        volumes = projectObject.volumes.map { volume ->
                            if (volume.id == volumeId) {
                                volume.copy(
                                    orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(
                                        multiColor = OrcaFacetAnnotation(),
                                    ),
                                )
                            } else volume
                        },
                    )
                } else projectObject
            },
        )
        return copy(
            current = finalized,
            undoStates = (undoStates + previousSnapshot).takeLast(HISTORY_LIMIT),
            redoStates = emptyList(),
        )
    }

    fun updateExactSupportPaint(
        objectId: String,
        volumeId: String,
        supportPaint: SupportPaint,
        annotation: OrcaFacetAnnotation,
        recordHistory: Boolean = true,
    ): ProjectHistoryState = updateExactFacetPaint(
        objectId = objectId,
        volumeId = volumeId,
        validate = { volume ->
            require(annotation.maximumState <= 2) { "Support facet annotation state is invalid" }
            validateExactFacetPaint(volume, supportPaint.facets.keys, annotation)
        },
        update = { volume ->
            volume.copy(
                supportPaint = supportPaint,
                orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(support = annotation),
            )
        },
        recordHistory = recordHistory,
    )

    fun commitExactSupportPaint(
        objectId: String,
        volumeId: String,
        previousPaint: SupportPaint,
        previousAnnotation: OrcaFacetAnnotation,
    ): ProjectHistoryState = commitExactFacetPaint(
        objectId,
        volumeId,
    ) { volume ->
        volume.copy(
            supportPaint = previousPaint,
            orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(support = previousAnnotation),
        )
    }

    fun updateExactSeamPaint(
        objectId: String,
        volumeId: String,
        seamPaint: SeamPaint,
        annotation: OrcaFacetAnnotation,
        recordHistory: Boolean = true,
    ): ProjectHistoryState = updateExactFacetPaint(
        objectId = objectId,
        volumeId = volumeId,
        validate = { volume ->
            require(annotation.maximumState <= 2) { "Seam facet annotation state is invalid" }
            validateExactFacetPaint(volume, seamPaint.facets.keys, annotation)
        },
        update = { volume ->
            volume.copy(
                seamPaint = seamPaint,
                orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(seam = annotation),
            )
        },
        recordHistory = recordHistory,
    )

    fun commitExactSeamPaint(
        objectId: String,
        volumeId: String,
        previousPaint: SeamPaint,
        previousAnnotation: OrcaFacetAnnotation,
    ): ProjectHistoryState = commitExactFacetPaint(
        objectId,
        volumeId,
    ) { volume ->
        volume.copy(
            seamPaint = previousPaint,
            orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(seam = previousAnnotation),
        )
    }

    fun updateExactMultiColorPaint(
        objectId: String,
        volumeId: String,
        multiColorPaint: MultiColorPaint,
        annotation: OrcaFacetAnnotation,
        recordHistory: Boolean = true,
    ): ProjectHistoryState = updateExactFacetPaint(
        objectId = objectId,
        volumeId = volumeId,
        validate = { volume ->
            require(annotation.maximumState <= MAX_FILAMENT_SLOTS) {
                "Multi-color facet annotation state is invalid"
            }
            validateExactFacetPaint(volume, multiColorPaint.facets.keys, annotation)
        },
        update = { volume ->
            volume.copy(
                multiColorPaint = multiColorPaint,
                orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(multiColor = annotation),
            )
        },
        recordHistory = recordHistory,
    )

    fun commitExactMultiColorPaint(
        objectId: String,
        volumeId: String,
        previousPaint: MultiColorPaint,
        previousAnnotation: OrcaFacetAnnotation,
    ): ProjectHistoryState = commitExactFacetPaint(
        objectId,
        volumeId,
    ) { volume ->
        volume.copy(
            multiColorPaint = previousPaint,
            orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(multiColor = previousAnnotation),
        )
    }

    fun updateSelectedVariableLayerHeights(
        variableLayerHeights: VariableLayerHeights,
    ): ProjectHistoryState {
        val selected = current.selectedObject ?: return this
        if (selected.variableLayerHeights == variableLayerHeights) return this
        return record(
            current.updateActivePlate(
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
            current.updateActivePlate(
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
        val previousSnapshot = current.updateActivePlate(
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

    private fun updateExactFacetPaint(
        objectId: String,
        volumeId: String,
        validate: (ProjectVolume) -> Unit,
        update: (ProjectVolume) -> ProjectVolume,
        recordHistory: Boolean,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        val targetVolume = target.volumes.firstOrNull { it.id == volumeId } ?: return this
        require(targetVolume.role.acceptsFacetPaint) {
            "Facet paint is unavailable for auxiliary project volumes"
        }
        validate(targetVolume)
        val replacement = update(targetVolume)
        if (replacement == targetVolume) return this
        val next = replaceVolume(current, objectId, volumeId, replacement)
        return if (recordHistory) record(next) else copy(current = next)
    }

    private fun commitExactFacetPaint(
        objectId: String,
        volumeId: String,
        restore: (ProjectVolume) -> ProjectVolume,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        val targetVolume = target.volumes.firstOrNull { it.id == volumeId } ?: return this
        require(targetVolume.role.acceptsFacetPaint) {
            "Facet paint is unavailable for auxiliary project volumes"
        }
        val previousVolume = restore(targetVolume)
        if (previousVolume == targetVolume) return this
        val previousSnapshot = replaceVolume(current, objectId, volumeId, previousVolume)
        return copy(
            undoStates = (undoStates + previousSnapshot).takeLast(HISTORY_LIMIT),
            redoStates = emptyList(),
        )
    }

    private fun validateExactFacetPaint(
        volume: ProjectVolume,
        wholeFacetIndices: Set<Int>,
        annotation: OrcaFacetAnnotation,
    ) {
        require(wholeFacetIndices.all { it in 0 until volume.model.triangles }) {
            "Facet paint references an unavailable facet"
        }
        annotation.constrainedToTriangleCount(volume.model.triangles)
    }

    private fun replaceVolume(
        snapshot: ProjectSnapshot,
        objectId: String,
        volumeId: String,
        replacement: ProjectVolume,
    ): ProjectSnapshot = snapshot.updateActivePlate(
        objects = snapshot.objects.map { projectObject ->
            if (projectObject.id == objectId) {
                projectObject.copy(
                    volumes = projectObject.volumes.map { volume ->
                        if (volume.id == volumeId) replacement else volume
                    },
                )
            } else {
                projectObject
            }
        },
    )

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
