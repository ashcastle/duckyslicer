package com.ashcastle.duckyslicer

data class ProjectObject(
    val id: String,
    val model: ModelInfo,
    val transform: ModelTransform = ModelTransform(),
    val supportPaint: SupportPaint = SupportPaint(),
)

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

    fun updateSupportPaint(
        objectId: String,
        supportPaint: SupportPaint,
        recordHistory: Boolean = true,
    ): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        if (target.supportPaint == supportPaint) return this
        require(supportPaint.facets.keys.all { it in 0 until target.model.triangles }) {
            "Support paint references an unavailable facet"
        }
        val next = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(supportPaint = supportPaint)
                } else {
                    projectObject
                }
            },
        )
        return if (recordHistory) record(next) else copy(current = next)
    }

    fun commitSupportPaint(objectId: String, previous: SupportPaint): ProjectHistoryState {
        val target = current.objects.firstOrNull { it.id == objectId } ?: return this
        if (target.supportPaint == previous) return this
        val previousSnapshot = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == objectId) {
                    projectObject.copy(supportPaint = previous)
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
