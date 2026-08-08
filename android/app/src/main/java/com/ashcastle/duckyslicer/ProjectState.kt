package com.ashcastle.duckyslicer

data class ProjectObject(
    val id: String,
    val model: ModelInfo,
    val transform: ModelTransform = ModelTransform(),
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

    fun arrange(bedSizeX: Float, bedSizeY: Float, gap: Float = 6f): ProjectHistoryState {
        if (current.objects.isEmpty()) return this
        var cursorX = gap
        var cursorY = gap
        var rowDepth = 0f
        val arranged = ArrayList<ProjectObject>(current.objects.size)
        current.objects.forEach { projectObject ->
            val (width, depth) = projectObject.footprintMm()
            if (cursorX + width + gap > bedSizeX) {
                cursorX = gap
                cursorY += rowDepth + gap
                rowDepth = 0f
            }
            if (cursorY + depth + gap > bedSizeY) return this
            arranged += projectObject.copy(
                transform = projectObject.transform.copy(
                    offsetXmm = cursorX + width / 2f - bedSizeX / 2f,
                    offsetYmm = cursorY + depth / 2f - bedSizeY / 2f,
                ),
            )
            cursorX += width + gap
            rowDepth = maxOf(rowDepth, depth)
        }
        return record(current.copy(objects = arranged))
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
        if (selected.transform == transform) return this
        val next = current.copy(
            objects = current.objects.map { projectObject ->
                if (projectObject.id == selected.id) projectObject.copy(transform = transform) else projectObject
            },
        )
        return if (recordHistory) record(next) else copy(current = next)
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

private fun ProjectObject.footprintMm(): Pair<Float, Float> {
    val halfX = model.dimensions[0].toFloat() * transform.scale / 2f
    val halfY = model.dimensions[1].toFloat() * transform.scale / 2f
    val halfZ = model.dimensions[2].toFloat() * transform.scale / 2f
    var minimumX = Float.POSITIVE_INFINITY
    var maximumX = Float.NEGATIVE_INFINITY
    var minimumY = Float.POSITIVE_INFINITY
    var maximumY = Float.NEGATIVE_INFINITY
    listOf(-halfX, halfX).forEach { x ->
        listOf(-halfY, halfY).forEach { y ->
            listOf(-halfZ, halfZ).forEach { z ->
                val rotated = transform.rotate(floatArrayOf(x, y, z))
                minimumX = minOf(minimumX, rotated[0])
                maximumX = maxOf(maximumX, rotated[0])
                minimumY = minOf(minimumY, rotated[1])
                maximumY = maxOf(maximumY, rotated[1])
            }
        }
    }
    return (maximumX - minimumX).coerceAtLeast(0.1f) to
        (maximumY - minimumY).coerceAtLeast(0.1f)
}
