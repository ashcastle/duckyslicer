package com.ashcastle.duckyslicer

import android.content.ContentProviderClient
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class OrcaModelFormat(val extension: String) {
    STL("stl"),
    THREE_MF("3mf"),
    OBJ("obj"),
}

internal class UnsupportedModelFormatException : IllegalArgumentException("unsupported_model_format")

internal suspend fun importOrcaModels(
    context: Context,
    uri: Uri,
    projectStore: ProjectStore,
    options: SliceOptions,
    requestId: String = UUID.randomUUID().toString(),
    transferCancellation: DocumentTransferCancellation? = null,
): List<ProjectObject> = withContext(Dispatchers.IO) {
    val cancellation = transferCancellation ?: DocumentTransferCancellation()
    fun cancellationRequested(): Boolean = cancellation.wasRequested() ||
        SlicerProcessClient.projectRequestCancellationRequested(requestId)
    fun throwIfCancellationRequested() {
        if (cancellationRequested()) throw ProjectEditCancelledException()
    }
    try {
        throwIfCancellationRequested()
        val provider = requireNotNull(
            context.contentResolver.acquireContentProviderClient(uri),
        ) { "model_provider_unavailable" }
        provider.use {
            val metadata = queryModelMetadata(provider, uri, cancellation)
            val format = modelFormat(metadata.displayName, metadata.mimeType)
                ?: throw UnsupportedModelFormatException()
            metadata.size?.let { size ->
                if (size > MAX_MODEL_IMPORT_BYTES) throw ModelTooLargeException()
            }
            val staging = projectStore.createModelImportStaging()
            val installed = ArrayList<File>()
            try {
                val source = File(staging, "source.${format.extension}")
                throwIfCancellationRequested()
                val descriptor = requireNotNull(
                    provider.openAssetFile(uri, "r", cancellation.providerSignal),
                ) { "model_unreadable" }
                descriptor.use {
                    descriptor.createInputStream().use { input ->
                        cancellation.attachInput(input)
                        try {
                            source.outputStream().use { output ->
                                copyModelWithLimit(
                                    input,
                                    output,
                                    cancellationRequested = ::cancellationRequested,
                                )
                            }
                        } finally {
                            cancellation.detachInput(input)
                        }
                    }
                }
                throwIfCancellationRequested()
                val exported = if (format == OrcaModelFormat.STL) {
                    listOf(OrcaImportedObject(source, metadata.displayName, 0f, 0f))
                } else {
                    SlicerProcessClient.normalizeModel(source, staging, requestId)
                }
                val imported = exported.mapIndexed { index, normalized ->
                    throwIfCancellationRequested()
                    val displayName = importedObjectName(
                        sourceName = metadata.displayName,
                        objectName = normalized.displayName,
                        index = index,
                        objectCount = exported.size,
                    )
                    val model = projectStore.installImportedModel(normalized.file, displayName)
                    installed += File(model.localPath)
                    ImportedGeometry(model, normalized.centerXmm, normalized.centerYmm)
                }
                throwIfCancellationRequested()
                val transforms = importedTransforms(imported, format, options)
                imported.mapIndexed { index, geometry ->
                    ProjectObject(
                        id = UUID.randomUUID().toString(),
                        model = geometry.model,
                        transform = transforms[index],
                    )
                }
            } catch (failure: Throwable) {
                installed.forEach(File::delete)
                throw failure
            } finally {
                staging.deleteRecursively()
            }
        }
    } finally {
        if (transferCancellation == null) cancellation.close()
    }
}

private data class ModelDocumentMetadata(
    val displayName: String,
    val size: Long?,
    val mimeType: String?,
)

private data class ImportedGeometry(
    val model: ModelInfo,
    val originalCenterX: Float,
    val originalCenterY: Float,
)

private fun queryModelMetadata(
    provider: ContentProviderClient,
    uri: Uri,
    cancellation: DocumentTransferCancellation,
): ModelDocumentMetadata {
    val row = try {
        provider.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
            cancellation.providerSignal,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
            val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            name to size
        }
    } catch (failure: Exception) {
        if (cancellation.wasRequested()) throw ProjectEditCancelledException()
        null
    }
    cancellation.throwIfRequested()
    val inferredFromName = modelFormat(row?.first, null)
    val mimeType = if (inferredFromName != null) null else runCatching { provider.getType(uri) }
        .getOrNull()
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
    val inferred = modelFormat(row?.first, mimeType)
    val fallbackName = inferred?.let { "model.${it.extension}" } ?: "model"
    return ModelDocumentMetadata(
        displayName = row?.first
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.take(200)
            ?.takeIf(String::isNotBlank)
            ?: fallbackName,
        size = row?.second?.takeIf { it >= 0 },
        mimeType = mimeType,
    )
}

private fun modelFormat(displayName: String?, mimeType: String?): OrcaModelFormat? {
    val extension = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
    return when {
        extension == "stl" -> OrcaModelFormat.STL
        extension == "3mf" -> OrcaModelFormat.THREE_MF
        extension == "obj" -> OrcaModelFormat.OBJ
        mimeType in STL_MIME_TYPES -> OrcaModelFormat.STL
        mimeType in THREE_MF_MIME_TYPES -> OrcaModelFormat.THREE_MF
        mimeType in OBJ_MIME_TYPES -> OrcaModelFormat.OBJ
        else -> null
    }
}

private fun importedObjectName(
    sourceName: String,
    objectName: String,
    index: Int,
    objectCount: Int,
): String {
    val sourceBase = sourceName.substringBeforeLast('.').ifBlank { "model" }
    val nativeBase = objectName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.')
        .replace(Regex("[\\p{Cc}/\\\\]"), " ")
        .trim()
        .take(180)
    val base = when {
        objectCount == 1 -> sourceBase
        nativeBase.isNotBlank() -> nativeBase
        else -> "$sourceBase ${index + 1}"
    }
    return "${base.take(190).ifBlank { "model ${index + 1}" }}.stl"
}

private fun importedTransforms(
    imported: List<ImportedGeometry>,
    format: OrcaModelFormat,
    options: SliceOptions,
): List<ModelTransform> {
    if (format == OrcaModelFormat.STL) return List(imported.size) { ModelTransform() }
    val machinePolygon = machineBedPolygon(
        options.bedPolygon,
        options.bedOriginX,
        options.bedOriginY,
    )
    val preserveProjectPlacement = format == OrcaModelFormat.THREE_MF && imported.all { geometry ->
        val minX = geometry.model.minMm[0].toFloat()
        val minY = geometry.model.minMm[1].toFloat()
        val maxX = geometry.model.maxMm[0].toFloat()
        val maxY = geometry.model.maxMm[1].toFloat()
        pointInsideBedPolygon(minX, minY, machinePolygon) &&
            pointInsideBedPolygon(minX, maxY, machinePolygon) &&
            pointInsideBedPolygon(maxX, minY, machinePolygon) &&
            pointInsideBedPolygon(maxX, maxY, machinePolygon) &&
            geometry.model.minMm[2] >= 0.0 &&
            geometry.model.maxMm[2] <= options.maxPrintHeight.toDouble()
    }
    val groupCenterX = (imported.minOf { it.model.minMm[0] } +
        imported.maxOf { it.model.maxMm[0] }).toFloat() / 2f
    val groupCenterY = (imported.minOf { it.model.minMm[1] } +
        imported.maxOf { it.model.maxMm[1] }).toFloat() / 2f
    val targetCenterX = options.bedOriginX + options.bedSizeX / 2f
    val targetCenterY = options.bedOriginY + options.bedSizeY / 2f
    val groupMinimumZ = imported.minOf { it.model.minMm[2] }.toFloat()
    return imported.map { geometry ->
        val centerX = if (preserveProjectPlacement) geometry.originalCenterX else {
            targetCenterX + geometry.originalCenterX - groupCenterX
        }
        val centerY = if (preserveProjectPlacement) geometry.originalCenterY else {
            targetCenterY + geometry.originalCenterY - groupCenterY
        }
        ModelTransform(
            offsetXmm = centerX - targetCenterX,
            offsetYmm = centerY - targetCenterY,
            offsetZmm = geometry.model.minMm[2].toFloat() -
                if (preserveProjectPlacement) 0f else groupMinimumZ,
        )
    }
}

private val STL_MIME_TYPES = setOf(
    "model/stl",
    "application/sla",
    "application/vnd.ms-pki.stl",
)
private val THREE_MF_MIME_TYPES = setOf(
    "model/3mf",
    "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
    "application/vnd.ms-3mfdocument",
)
private val OBJ_MIME_TYPES = setOf("model/obj", "application/x-tgif")
