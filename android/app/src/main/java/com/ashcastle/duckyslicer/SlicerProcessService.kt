package com.ashcastle.duckyslicer

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.u1.slicer.NativeLibrary
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal object SlicerProcessClient {
    @Volatile
    private var latestWorkerPid = 0

    private val activeRequestId = AtomicReference<String?>(null)
    private val cancelledRequestId = AtomicReference<String?>(null)
    private val cancelledProjectRequestIds = ConcurrentHashMap.newKeySet<String>()

    private val replyThread by lazy {
        HandlerThread("DuckySlicer replies").apply { start() }
    }

    fun slice(
        transformedModels: List<File>,
        options: SliceOptions,
        objectVolumeCounts: IntArray = IntArray(transformedModels.size) { 1 },
        filamentSlots: IntArray = IntArray(transformedModels.size),
        foregroundSession: ForegroundSliceSession? = null,
        cancellationRequested: () -> Boolean = { false },
        onProgress: (Int) -> Unit,
    ): SliceOutcome = sliceInternal(
        transformedModels,
        List(transformedModels.size) { null },
        List(transformedModels.size) { null },
        List(transformedModels.size) { null },
        List(objectVolumeCounts.size) { null },
        List(objectVolumeCounts.size) { null },
        List(objectVolumeCounts.size) { null },
        options,
        objectVolumeCounts,
        filamentSlots,
        foregroundSession,
        cancellationRequested,
        null,
        onProgress,
    )

    fun slice(
        transformedModels: List<File>,
        supportPaintFiles: List<File?>,
        seamPaintFiles: List<File?>,
        multiColorPaintFiles: List<File?>,
        variableLayerHeightFiles: List<File?>,
        processOverrideFiles: List<File?>,
        brimPointFiles: List<File?>,
        options: SliceOptions,
        objectVolumeCounts: IntArray = IntArray(transformedModels.size) { 1 },
        filamentSlots: IntArray = IntArray(transformedModels.size),
        foregroundSession: ForegroundSliceSession? = null,
        cancellationRequested: () -> Boolean = { false },
        onProgress: (Int) -> Unit,
    ): SliceOutcome = sliceInternal(
        transformedModels,
        supportPaintFiles,
        seamPaintFiles,
        multiColorPaintFiles,
        variableLayerHeightFiles,
        processOverrideFiles,
        brimPointFiles,
        options,
        objectVolumeCounts,
        filamentSlots,
        foregroundSession,
        cancellationRequested,
        null,
        onProgress,
    )

    internal fun sliceWithOutputLimitForTest(
        transformedModels: List<File>,
        options: SliceOptions,
        maximumGcodeBytes: Int,
        onProgress: (Int) -> Unit = {},
    ): SliceOutcome {
        check(BuildConfig.DEBUG) { "G-code output overrides are available only in debug builds" }
        require(maximumGcodeBytes in TEST_MINIMUM_GCODE_BYTES..PRODUCTION_MAXIMUM_GCODE_BYTES) {
            "Invalid test G-code output limit"
        }
        return sliceInternal(
            transformedModels,
            List(transformedModels.size) { null },
            List(transformedModels.size) { null },
            List(transformedModels.size) { null },
            List(transformedModels.size) { null },
            List(transformedModels.size) { null },
            List(transformedModels.size) { null },
            options,
            IntArray(transformedModels.size) { 1 },
            IntArray(transformedModels.size),
            null,
            { false },
            maximumGcodeBytes,
            onProgress,
        )
    }

    /** Runs automatic orientation in the isolated native worker. */
    fun autoOrient(
        models: List<File>,
        requestId: String = UUID.randomUUID().toString(),
    ): OrcaOrientation {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Automatic orientation must run outside the application main thread"
        }
        require(models.size in 1..MAX_PROJECT_VOLUMES_PER_OBJECT) {
            "Automatic orientation requires at least one model volume"
        }
        requireValidRequestId(requestId)
        throwIfProjectRequestCanceled(requestId)
        check(activeRequestId.compareAndSet(null, requestId)) {
            "Another slicer operation is already running"
        }
        return try {
            throwIfProjectRequestCanceled(requestId)
            val response = withWorker(DuckySlicerApplication.context()) { worker ->
                worker.request(
                    what = SlicerProcessContract.MESSAGE_AUTO_ORIENT,
                    data = Bundle().apply {
                        putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
                        putStringArrayList(
                            SlicerProcessContract.KEY_MODEL_PATHS,
                            ArrayList(models.map(File::getAbsolutePath)),
                        )
                    },
                    timeoutSeconds = ORIENTATION_TIMEOUT_SECONDS,
                )
            }
            throwIfProjectRequestCanceled(requestId)
            check(response.getBoolean(SlicerProcessContract.KEY_OK)) {
                response.getString(SlicerProcessContract.KEY_ERROR)
                    ?: "Slicer could not orient the model"
            }
            latestWorkerPid = response.getInt(SlicerProcessContract.KEY_PID)
            OrcaOrientation(
                requireNotNull(response.getDoubleArray(SlicerProcessContract.KEY_ROTATION_RADIANS)) {
                    "Slicer returned no orientation"
                },
            )
        } catch (failure: Exception) {
            if (projectRequestCancellationRequested(requestId)) {
                throw ProjectEditCancelledException()
            }
            throw failure
        } finally {
            activeRequestId.compareAndSet(requestId, null)
            cancelledRequestId.compareAndSet(requestId, null)
        }
    }

    fun autoOrient(
        model: File,
        requestId: String = UUID.randomUUID().toString(),
    ): OrcaOrientation = autoOrient(listOf(model), requestId)

    /** Loads STL, 3MF, or OBJ through the native slicer and exports bounded project-owned STL objects. */
    fun normalizeModel(
        model: File,
        stagingDirectory: File,
        requestId: String = UUID.randomUUID().toString(),
    ): List<OrcaImportedProjectObject> =
        parseProjectModelRecords(
            response = requestModelOperation(
                message = SlicerProcessContract.MESSAGE_NORMALIZE_MODEL,
                model = model,
                stagingDirectory = stagingDirectory,
                fallbackError = "Slicer could not import the model",
                requestId = requestId,
            ),
            stagingDirectory = stagingDirectory,
        )

    /** Splits a model object in the native worker and exports each result. */
    fun splitModel(
        model: File,
        stagingDirectory: File,
        requestId: String = UUID.randomUUID().toString(),
    ): List<OrcaImportedObject> =
        runModelOperation(
            message = SlicerProcessContract.MESSAGE_SPLIT_MODEL,
            model = model,
            stagingDirectory = stagingDirectory,
            fallbackError = "Slicer could not split the model",
            requestId = requestId,
        )

    /** Splits one selected model-part volume inside a reconstructed native model object. */
    fun splitModelVolume(
        models: List<File>,
        sourceVolumeIndex: Int,
        stagingDirectory: File,
        requestId: String = UUID.randomUUID().toString(),
    ): List<OrcaImportedObject> {
        require(models.size in 1..MAX_PROJECT_VOLUMES_PER_OBJECT) {
            "Project object volume count is invalid"
        }
        require(sourceVolumeIndex in models.indices) { "Source volume is unavailable" }
        require(encodedRequestBytes(models.map(File::getAbsolutePath), "") <=
            SlicerProcessContract.MAX_REQUEST_BYTES) {
            "Split request is too large"
        }
        return runModelOperation(
            message = SlicerProcessContract.MESSAGE_SPLIT_MODEL_VOLUME,
            model = null,
            stagingDirectory = stagingDirectory,
            fallbackError = "Slicer could not split the part",
            requestId = requestId,
        ) {
            putStringArrayList(
                SlicerProcessContract.KEY_MODEL_PATHS,
                ArrayList(models.map(File::getAbsolutePath)),
            )
            putInt(SlicerProcessContract.KEY_VOLUME_INDEX, sourceVolumeIndex)
        }
    }

    /** Runs a planar cut in the native worker and exports the two resulting solids. */
    fun cutModel(
        model: File,
        stagingDirectory: File,
        heightRatio: Float,
        placeOnCut: Boolean,
        requestId: String = UUID.randomUUID().toString(),
    ): List<OrcaImportedObject> = runModelOperation(
        message = SlicerProcessContract.MESSAGE_CUT_MODEL,
        model = model,
        stagingDirectory = stagingDirectory,
        fallbackError = "Slicer could not cut the model",
        requestId = requestId,
    ) {
        putFloat(SlicerProcessContract.KEY_CUT_HEIGHT_RATIO, heightRatio)
        putBoolean(SlicerProcessContract.KEY_PLACE_ON_CUT, placeOnCut)
    }

    /** Runs the native mesh simplifier in the isolated worker. */
    fun simplifyModel(
        model: File,
        stagingDirectory: File,
        targetTriangles: Int,
        requestId: String = UUID.randomUUID().toString(),
    ): OrcaImportedObject {
        require(targetTriangles in MINIMUM_SIMPLIFIED_TRIANGLES..MAXIMUM_SIMPLIFIED_TRIANGLES) {
            "Simplified model detail is invalid"
        }
        return runModelOperation(
            message = SlicerProcessContract.MESSAGE_SIMPLIFY_MODEL,
            model = model,
            stagingDirectory = stagingDirectory,
            fallbackError = "Slicer could not simplify the model",
            requestId = requestId,
        ) {
            putInt(SlicerProcessContract.KEY_TARGET_TRIANGLES, targetTriangles)
        }.single()
    }

    /** Creates a bounded STL with the native primitive mesh generators. */
    fun createPrimitive(
        primitive: OrcaPrimitive,
        sizeMm: Float,
        stagingDirectory: File,
        requestId: String = UUID.randomUUID().toString(),
    ): OrcaImportedObject {
        require(sizeMm.isFinite() && sizeMm in MIN_PRIMITIVE_SIZE_MM..MAX_PRIMITIVE_SIZE_MM) {
            "Shape size is invalid"
        }
        return runModelOperation(
            message = SlicerProcessContract.MESSAGE_CREATE_PRIMITIVE,
            model = null,
            stagingDirectory = stagingDirectory,
            fallbackError = "Slicer could not create the shape",
            requestId = requestId,
        ) {
            putInt(SlicerProcessContract.KEY_PRIMITIVE_TYPE, primitive.nativeId)
            putFloat(SlicerProcessContract.KEY_PRIMITIVE_SIZE_MM, sizeMm)
        }.single()
    }

    private fun runModelOperation(
        message: Int,
        model: File?,
        stagingDirectory: File,
        fallbackError: String,
        requestId: String,
        configureRequest: Bundle.() -> Unit = {},
    ): List<OrcaImportedObject> = parseFlatModelRecords(
        response = requestModelOperation(
            message = message,
            model = model,
            stagingDirectory = stagingDirectory,
            fallbackError = fallbackError,
            requestId = requestId,
            configureRequest = configureRequest,
        ),
        stagingDirectory = stagingDirectory,
    )

    private fun requestModelOperation(
        message: Int,
        model: File?,
        stagingDirectory: File,
        fallbackError: String,
        requestId: String,
        configureRequest: Bundle.() -> Unit = {},
    ): Bundle {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Model operations must run outside the application main thread"
        }
        requireValidRequestId(requestId)
        throwIfProjectRequestCanceled(requestId)
        check(activeRequestId.compareAndSet(null, requestId)) {
            "Another slicer operation is already running"
        }
        return try {
            throwIfProjectRequestCanceled(requestId)
            val response = withWorker(DuckySlicerApplication.context()) { worker ->
                worker.request(
                    what = message,
                    data = Bundle().apply {
                        putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
                        model?.let {
                            putString(SlicerProcessContract.KEY_MODEL_PATH, it.absolutePath)
                        }
                        putString(
                            SlicerProcessContract.KEY_MODEL_OUTPUT_DIRECTORY,
                            stagingDirectory.absolutePath,
                        )
                        configureRequest()
                    },
                    timeoutSeconds = MODEL_NORMALIZATION_TIMEOUT_SECONDS,
                )
            }
            throwIfProjectRequestCanceled(requestId)
            if (response.getBoolean(SlicerProcessContract.KEY_MODEL_NOT_SPLITTABLE)) {
                throw ModelNotSplittableException()
            }
            if (response.getBoolean(SlicerProcessContract.KEY_MODEL_NOT_CUTTABLE)) {
                throw ModelNotCuttableException()
            }
            check(response.getBoolean(SlicerProcessContract.KEY_OK)) {
                response.getString(SlicerProcessContract.KEY_ERROR) ?: fallbackError
            }
            latestWorkerPid = response.getInt(SlicerProcessContract.KEY_PID)
            response
        } catch (failure: Exception) {
            if (projectRequestCancellationRequested(requestId)) {
                throw ProjectEditCancelledException()
            }
            throw failure
        } finally {
            activeRequestId.compareAndSet(requestId, null)
            cancelledRequestId.compareAndSet(requestId, null)
        }
    }

    private fun parseFlatModelRecords(
        response: Bundle,
        stagingDirectory: File,
    ): List<OrcaImportedObject> {
        val records = requireNotNull(
            response.getStringArrayList(SlicerProcessContract.KEY_NORMALIZED_MODELS),
        ) { "Slicer returned no model objects" }
        val canonicalStaging = stagingDirectory.canonicalFile
        val seen = HashSet<File>()
        return records.map { record ->
            val values = record.split('\t', limit = 4)
            require(values.size == 4) { "Slicer returned invalid model metadata" }
            val output = checkedImportedModelFile(values[0], canonicalStaging, seen)
            val name = values[1].trim().takeIf { it.length in 1..200 } ?: "model.stl"
            val centerX = checkedImportedCoordinate(values[2])
            val centerY = checkedImportedCoordinate(values[3])
            OrcaImportedObject(output, name, centerX, centerY)
        }.also { imported ->
            require(imported.size in 1..SlicerProcessService.MAX_OBJECTS) {
                "Slicer returned an invalid model count"
            }
        }
    }

    private fun parseProjectModelRecords(
        response: Bundle,
        stagingDirectory: File,
    ): List<OrcaImportedProjectObject> {
        val records = requireNotNull(
            response.getStringArrayList(SlicerProcessContract.KEY_NORMALIZED_MODELS),
        ) { "Slicer returned no model volumes" }
        require(records.size in 1..ProjectStore.MAX_PROJECT_VOLUMES) {
            "Slicer returned an invalid model volume count"
        }
        val canonicalStaging = stagingDirectory.canonicalFile
        val seen = HashSet<File>()
        val grouped = ArrayList<MutableList<OrcaImportedProjectVolumeRecord>>()
        records.forEach { record ->
            val values = record.split('\t', limit = 7)
            require(values.size == 7) { "Slicer returned invalid project model metadata" }
            val output = checkedImportedModelFile(values[0], canonicalStaging, seen)
            val objectName = values[1].trim().takeIf { it.length in 1..200 } ?: "model"
            val volumeName = values[2].trim().takeIf { it.length in 1..200 } ?: "part.stl"
            val centerX = checkedImportedCoordinate(values[3])
            val centerY = checkedImportedCoordinate(values[4])
            val filamentSlot = requireNotNull(values[5].toIntOrNull()) {
                "Slicer returned invalid volume filament metadata"
            }
            val objectOrdinal = requireNotNull(values[6].toIntOrNull()) {
                "Slicer returned invalid object grouping metadata"
            }
            require(filamentSlot in 0 until MAX_FILAMENT_SLOTS) {
                "Slicer returned an invalid volume filament"
            }
            require(objectOrdinal in 0 until SlicerProcessService.MAX_OBJECTS) {
                "Slicer returned an invalid object group"
            }
            if (objectOrdinal == grouped.size) grouped.add(ArrayList())
            require(objectOrdinal == grouped.lastIndex) {
                "Slicer returned non-contiguous object groups"
            }
            val group = grouped.last()
            require(group.size < MAX_PROJECT_VOLUMES_PER_OBJECT) {
                "Slicer returned too many volumes for one object"
            }
            group += OrcaImportedProjectVolumeRecord(
                volume = OrcaImportedProjectVolume(output, volumeName, filamentSlot),
                objectName = objectName,
                centerXmm = centerX,
                centerYmm = centerY,
            )
        }
        require(grouped.size in 1..SlicerProcessService.MAX_OBJECTS) {
            "Slicer returned an invalid object count"
        }
        return grouped.map { group ->
            val first = group.first()
            require(group.all { record ->
                record.objectName == first.objectName &&
                    kotlin.math.abs(record.centerXmm - first.centerXmm) <= 0.001f &&
                    kotlin.math.abs(record.centerYmm - first.centerYmm) <= 0.001f
            }) { "Slicer returned inconsistent object metadata" }
            OrcaImportedProjectObject(
                volumes = group.map(OrcaImportedProjectVolumeRecord::volume),
                displayName = first.objectName,
                centerXmm = first.centerXmm,
                centerYmm = first.centerYmm,
            )
        }
    }

    private fun checkedImportedModelFile(
        path: String,
        canonicalStaging: File,
        seen: MutableSet<File>,
    ): File = File(path).canonicalFile.also { output ->
        require(
            output.parentFile == canonicalStaging && seen.add(output) &&
                output.isFile && output.length() in 1..MAX_MODEL_IMPORT_BYTES
        ) { "Slicer returned an unsafe model object" }
    }

    private fun checkedImportedCoordinate(value: String): Float =
        requireNotNull(value.toFloatOrNull()) {
            "Slicer returned invalid model placement"
        }.also { coordinate ->
            require(
                coordinate.isFinite() && kotlin.math.abs(coordinate) <= MAX_MODEL_COORDINATE_MM
            ) { "Slicer returned an unsafe model placement" }
        }

    /** Uses the silhouette-aware arrangement engine in the isolated worker. */
    fun autoArrange(
        transformedModels: List<File>,
        bedSizeX: Float,
        bedSizeY: Float,
        bedOriginX: Float,
        bedOriginY: Float,
        bedPolygon: List<Float>,
        objectVolumeCounts: IntArray = IntArray(transformedModels.size) { 1 },
        minimumGap: Float = 6f,
        requestId: String = UUID.randomUUID().toString(),
    ): OrcaArrangement {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Automatic arrangement must run outside the application main thread"
        }
        require(transformedModels.size >= 2) { "At least two models are required" }
        require(
            objectVolumeCounts.size >= 2 &&
                objectVolumeCounts.all { it in 1..MAX_PROJECT_VOLUMES_PER_OBJECT } &&
                objectVolumeCounts.sum() == transformedModels.size,
        ) { "Object volume counts do not match models" }
        requireValidRequestId(requestId)
        throwIfProjectRequestCanceled(requestId)
        val modelPaths = transformedModels.map(File::getAbsolutePath)
        require(encodedRequestBytes(modelPaths, "") <= SlicerProcessContract.MAX_REQUEST_BYTES) {
            "Arrange request is too large"
        }
        check(activeRequestId.compareAndSet(null, requestId)) {
            "Another slicer operation is already running"
        }
        return try {
            throwIfProjectRequestCanceled(requestId)
            val response = withWorker(DuckySlicerApplication.context()) { worker ->
                worker.request(
                    what = SlicerProcessContract.MESSAGE_AUTO_ARRANGE,
                    data = Bundle().apply {
                        putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
                        putStringArrayList(SlicerProcessContract.KEY_MODEL_PATHS, ArrayList(modelPaths))
                        putIntArray(
                            SlicerProcessContract.KEY_OBJECT_VOLUME_COUNTS,
                            objectVolumeCounts,
                        )
                        putFloat(SlicerProcessContract.KEY_BED_SIZE_X, bedSizeX)
                        putFloat(SlicerProcessContract.KEY_BED_SIZE_Y, bedSizeY)
                        putFloat(SlicerProcessContract.KEY_BED_ORIGIN_X, bedOriginX)
                        putFloat(SlicerProcessContract.KEY_BED_ORIGIN_Y, bedOriginY)
                        putFloatArray(SlicerProcessContract.KEY_BED_POLYGON, bedPolygon.toFloatArray())
                        putFloat(SlicerProcessContract.KEY_MINIMUM_GAP, minimumGap)
                    },
                    timeoutSeconds = ARRANGEMENT_TIMEOUT_SECONDS,
                )
            }
            throwIfProjectRequestCanceled(requestId)
            check(response.getBoolean(SlicerProcessContract.KEY_OK)) {
                response.getString(SlicerProcessContract.KEY_ERROR)
                    ?: "The objects could not be arranged"
            }
            latestWorkerPid = response.getInt(SlicerProcessContract.KEY_PID)
            OrcaArrangement(
                lowerLeftMm = requireNotNull(
                    response.getFloatArray(SlicerProcessContract.KEY_ARRANGED_LOWER_LEFT),
                ) { "Slicer returned no arrangement" },
                sizesMm = requireNotNull(
                    response.getFloatArray(SlicerProcessContract.KEY_OBJECT_SIZES),
                ) { "Slicer returned no object sizes" },
                centersMm = requireNotNull(
                    response.getFloatArray(SlicerProcessContract.KEY_OBJECT_CENTERS),
                ) { "Slicer returned no object centers" },
            )
        } catch (failure: Exception) {
            if (projectRequestCancellationRequested(requestId)) {
                throw ProjectEditCancelledException()
            }
            throw failure
        } finally {
            activeRequestId.compareAndSet(requestId, null)
            cancelledRequestId.compareAndSet(requestId, null)
        }
    }

    private fun sliceInternal(
        transformedModels: List<File>,
        supportPaintFiles: List<File?>,
        seamPaintFiles: List<File?>,
        multiColorPaintFiles: List<File?>,
        variableLayerHeightFiles: List<File?>,
        processOverrideFiles: List<File?>,
        brimPointFiles: List<File?>,
        options: SliceOptions,
        objectVolumeCounts: IntArray,
        filamentSlots: IntArray,
        foregroundSession: ForegroundSliceSession?,
        cancellationRequested: () -> Boolean,
        maximumGcodeBytesForTest: Int?,
        onProgress: (Int) -> Unit,
    ): SliceOutcome {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Slicing must run outside the application main thread"
        }
        val context = DuckySlicerApplication.context()
        val requestId = foregroundSession?.requestId ?: UUID.randomUUID().toString()
        val modelPaths = transformedModels.map(File::getAbsolutePath)
        require(supportPaintFiles.size == transformedModels.size) { "Support paint count does not match models" }
        require(seamPaintFiles.size == transformedModels.size) { "Seam paint count does not match models" }
        require(multiColorPaintFiles.size == transformedModels.size) {
            "Multi-color paint count does not match models"
        }
        require(
            objectVolumeCounts.isNotEmpty() &&
                objectVolumeCounts.all { it in 1..MAX_PROJECT_VOLUMES_PER_OBJECT } &&
                objectVolumeCounts.sum() == transformedModels.size,
        ) { "Object volume counts do not match models" }
        require(variableLayerHeightFiles.size == objectVolumeCounts.size) {
            "Variable layer height count does not match objects"
        }
        require(processOverrideFiles.size == objectVolumeCounts.size) {
            "Object settings count does not match objects"
        }
        require(brimPointFiles.size == objectVolumeCounts.size) {
            "Brim point count does not match objects"
        }
        require(filamentSlots.size == transformedModels.size) { "Filament slot count does not match models" }
        val supportPaintPaths = supportPaintFiles.map { it?.absolutePath.orEmpty() }
        val seamPaintPaths = seamPaintFiles.map { it?.absolutePath.orEmpty() }
        val multiColorPaintPaths = multiColorPaintFiles.map { it?.absolutePath.orEmpty() }
        val variableLayerHeightPaths = variableLayerHeightFiles.map { it?.absolutePath.orEmpty() }
        val processOverridePaths = processOverrideFiles.map { it?.absolutePath.orEmpty() }
        val brimPointPaths = brimPointFiles.map { it?.absolutePath.orEmpty() }
        val optionsText = options.toProjectJson().toString()
        require(
            encodedRequestBytes(
                modelPaths + supportPaintPaths + seamPaintPaths + multiColorPaintPaths +
                    variableLayerHeightPaths + processOverridePaths + brimPointPaths,
                optionsText,
            ) <=
                SlicerProcessContract.MAX_REQUEST_BYTES,
        ) {
            "Slice request is too large"
        }
        val request = Bundle().apply {
            putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
            putStringArrayList(
                SlicerProcessContract.KEY_MODEL_PATHS,
                ArrayList(modelPaths),
            )
            putStringArrayList(
                SlicerProcessContract.KEY_SUPPORT_PAINT_PATHS,
                ArrayList(supportPaintPaths),
            )
            putStringArrayList(
                SlicerProcessContract.KEY_SEAM_PAINT_PATHS,
                ArrayList(seamPaintPaths),
            )
            putStringArrayList(
                SlicerProcessContract.KEY_MULTI_COLOR_PAINT_PATHS,
                ArrayList(multiColorPaintPaths),
            )
            putStringArrayList(
                SlicerProcessContract.KEY_VARIABLE_LAYER_HEIGHT_PATHS,
                ArrayList(variableLayerHeightPaths),
            )
            putStringArrayList(
                SlicerProcessContract.KEY_PROCESS_OVERRIDE_PATHS,
                ArrayList(processOverridePaths),
            )
            putStringArrayList(
                SlicerProcessContract.KEY_BRIM_POINT_PATHS,
                ArrayList(brimPointPaths),
            )
            putString(SlicerProcessContract.KEY_OPTIONS, optionsText)
            putIntArray(SlicerProcessContract.KEY_OBJECT_VOLUME_COUNTS, objectVolumeCounts)
            putIntArray(SlicerProcessContract.KEY_FILAMENT_SLOTS, filamentSlots)
            maximumGcodeBytesForTest?.let {
                putInt(SlicerProcessContract.KEY_MAXIMUM_GCODE_BYTES_FOR_TEST, it)
            }
        }
        if (foregroundSession == null) {
            check(activeRequestId.compareAndSet(null, requestId)) {
                "Another slice is already running"
            }
        } else {
            check(activeRequestId.get() == requestId) {
                "Foreground slice session is no longer active"
            }
        }
        try {
            if (cancellationRequested()) {
                cancelledRequestId.set(requestId)
                throw SlicingCancelledException()
            }
            val response = withWorker(context) { worker ->
                worker.request(
                    what = SlicerProcessContract.MESSAGE_SLICE,
                    data = request,
                    timeoutSeconds = SLICE_TIMEOUT_SECONDS,
                    onProgress = onProgress,
                )
            }
            if (response.getBoolean(SlicerProcessContract.KEY_CANCELED)) {
                throw SlicingCancelledException()
            }
            check(response.getBoolean(SlicerProcessContract.KEY_OK)) {
                response.getString(SlicerProcessContract.KEY_ERROR)
                    ?: "Slicer process returned no result"
            }
            latestWorkerPid = response.getInt(SlicerProcessContract.KEY_PID)
            return outcomeFromResponse(context, response)
        } catch (failure: Exception) {
            if (cancelledRequestId.get() == requestId) throw SlicingCancelledException()
            throw failure
        } finally {
            activeRequestId.compareAndSet(requestId, null)
            cancelledRequestId.compareAndSet(requestId, null)
        }
    }

    fun beginUserSlice(plateId: String): ForegroundSliceSession {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "A foreground slice must begin from visible UI"
        }
        require(plateId.length in 1..ProjectStore.MAX_ID_LENGTH) { "Invalid slice plate id" }
        val context = DuckySlicerApplication.context()
        val requestId = UUID.randomUUID().toString()
        check(activeRequestId.compareAndSet(null, requestId)) {
            "Another slicer operation is already running"
        }
        val session = try {
            ForegroundSliceSession.prepare(context, requestId, plateId)
        } catch (failure: Exception) {
            activeRequestId.compareAndSet(requestId, null)
            throw failure
        }
        return try {
            context.startForegroundService(SlicerProcessService.startSliceIntent(context, requestId))
            session
        } catch (failure: Exception) {
            activeRequestId.compareAndSet(requestId, null)
            session.abandon()
            throw failure
        }
    }

    fun recoverUserSlice(): ForegroundSliceSession? {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "A foreground slice must be recovered by the UI"
        }
        val session = ForegroundSliceSession.recover(DuckySlicerApplication.context()) ?: return null
        check(activeRequestId.compareAndSet(null, session.requestId)) {
            "Another slicer operation is already running"
        }
        return session
    }

    fun awaitRecoveredSlice(
        session: ForegroundSliceSession,
        onProgress: (Int) -> Unit,
    ): SliceOutcome {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Slice recovery must run outside the application main thread"
        }
        check(activeRequestId.get() == session.requestId) {
            "Foreground slice session is no longer active"
        }
        val context = session.context
        try {
            when (val record = ForegroundSliceStore.load(context)) {
                null -> error("Foreground slice checkpoint is unavailable")
                else -> when (record.phase) {
                    ForegroundSlicePhase.CANCELED -> throw SlicingCancelledException()
                    ForegroundSlicePhase.FAILED -> error("Foreground slice failed while the app was closed")
                    ForegroundSlicePhase.COMPLETED -> return requireNotNull(record.outcome)
                    ForegroundSlicePhase.ACTIVE -> Unit
                }
            }
            val response = withWorker(context) { worker ->
                worker.request(
                    what = SlicerProcessContract.MESSAGE_ATTACH,
                    data = Bundle().apply {
                        putString(SlicerProcessContract.KEY_REQUEST_ID, session.requestId)
                    },
                    timeoutSeconds = SLICE_TIMEOUT_SECONDS,
                    onProgress = onProgress,
                )
            }
            if (response.getBoolean(SlicerProcessContract.KEY_CANCELED)) {
                throw SlicingCancelledException()
            }
            check(response.getBoolean(SlicerProcessContract.KEY_OK)) {
                response.getString(SlicerProcessContract.KEY_ERROR)
                    ?: "Foreground slice could not be recovered"
            }
            latestWorkerPid = response.getInt(SlicerProcessContract.KEY_PID)
            return outcomeFromResponse(context, response)
        } catch (failure: Exception) {
            if (cancelledRequestId.get() == session.requestId) throw SlicingCancelledException()
            throw failure
        } finally {
            activeRequestId.compareAndSet(session.requestId, null)
            cancelledRequestId.compareAndSet(session.requestId, null)
        }
    }

    internal fun finishUserSlice(session: ForegroundSliceSession) {
        activeRequestId.compareAndSet(session.requestId, null)
        cancelledRequestId.compareAndSet(session.requestId, null)
        runCatching {
            session.context.startService(
                SlicerProcessService.finishSliceIntent(session.context, session.requestId),
            )
        }.onFailure {
            session.context.stopService(Intent(session.context, SlicerProcessService::class.java))
        }
    }

    /** Marks and cancels only the project-edit request with this exact identifier. */
    fun cancelProjectRequestAsync(requestId: String): Boolean {
        if (!isValidRequestId(requestId)) return false
        cancelledProjectRequestIds.add(requestId)
        if (activeRequestId.get() != requestId) return true
        Thread(
            { cancelProjectRequest(requestId) },
            "DuckySlicer project cancellation",
        ).apply {
            isDaemon = true
            start()
        }
        return true
    }

    internal fun projectRequestCancellationRequested(requestId: String): Boolean =
        requestId in cancelledProjectRequestIds

    internal fun releaseProjectRequest(requestId: String) {
        cancelledProjectRequestIds.remove(requestId)
        if (activeRequestId.get() != requestId) {
            cancelledRequestId.compareAndSet(requestId, null)
        }
    }

    private fun cancelProjectRequest(requestId: String): Boolean {
        if (activeRequestId.get() != requestId) return false
        cancelledRequestId.set(requestId)
        return cancelMatchingRequest(requestId)
    }

    private fun cancelMatchingRequest(requestId: String): Boolean {
        return runCatching {
            withWorker(DuckySlicerApplication.context()) { worker ->
                repeat(REQUEST_CANCEL_BIND_RETRIES) {
                    if (activeRequestId.get() != requestId) return@withWorker true
                    val response = worker.request(
                        what = SlicerProcessContract.MESSAGE_CANCEL,
                        data = Bundle().apply {
                            putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
                        },
                        timeoutSeconds = CONNECTION_TIMEOUT_SECONDS,
                    )
                    if (response.getBoolean(SlicerProcessContract.KEY_OK)) {
                        return@withWorker true
                    }
                    Thread.sleep(REQUEST_CANCEL_RETRY_MILLIS)
                }
                activeRequestId.get() != requestId
            }
        }.getOrDefault(activeRequestId.get() != requestId)
    }

    private fun throwIfProjectRequestCanceled(requestId: String) {
        if (projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
    }

    private fun requireValidRequestId(requestId: String) {
        require(isValidRequestId(requestId)) { "Slicer request id is invalid" }
    }

    private fun isValidRequestId(requestId: String): Boolean =
        requestId.length in 1..SlicerProcessService.MAX_REQUEST_ID_LENGTH &&
            requestId.none(Char::isISOControl)

    /** Cancels only the retained foreground slice represented by this exact session. */
    fun cancelUserSliceAsync(session: ForegroundSliceSession): Boolean {
        val requestId = session.requestId
        val active = activeRequestId.get() == requestId
        val checkpointed = runCatching(session::requestCancellation).getOrDefault(false)
        if (!active && !checkpointed) return false
        runCatching {
            session.context.startService(
                SlicerProcessService.cancelSliceIntent(session.context, requestId),
            )
        }
        if (activeRequestId.get() != requestId) return true
        cancelledRequestId.set(requestId)
        Thread({ cancelMatchingRequest(requestId) }, "DuckySlicer cancellation").apply {
            isDaemon = true
            start()
        }
        return true
    }

    internal fun lastWorkerPid(): Int = latestWorkerPid

    @Suppress("DEPRECATION")
    internal fun workerIsForegroundForTest(context: Context): Boolean {
        check(BuildConfig.DEBUG) { "Foreground service inspection is available only in debug builds" }
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getRunningServices(32).any { running ->
            running.service.className == SlicerProcessService::class.java.name && running.foreground
        }
    }

    internal fun cancelFromNotificationForTest(session: ForegroundSliceSession): Boolean {
        check(BuildConfig.DEBUG) { "Notification cancellation is available only in debug builds" }
        if (!session.isCurrent()) return false
        return session.context.startService(
            SlicerProcessService.cancelSliceIntent(session.context, session.requestId),
        ) != null
    }

    internal fun cancelRequestForTest(requestId: String): Boolean {
        check(BuildConfig.DEBUG) { "Exact request cancellation is available only in debug builds" }
        if (!isValidRequestId(requestId) || activeRequestId.get() != requestId) return false
        cancelledRequestId.set(requestId)
        return cancelMatchingRequest(requestId)
    }

    internal fun terminateWorkerForTest(context: Context): Int {
        check(BuildConfig.DEBUG) { "Worker termination is available only in debug builds" }
        val worker = BoundWorker(context.applicationContext)
        try {
            worker.connect()
            val response = worker.request(
                what = SlicerProcessContract.MESSAGE_TERMINATE_FOR_TEST,
                timeoutSeconds = CONNECTION_TIMEOUT_SECONDS,
            )
            val pid = response.getInt(SlicerProcessContract.KEY_PID)
            check(pid > 0 && pid != Process.myPid()) { "Invalid worker process" }
            check(worker.awaitDeath(CONNECTION_TIMEOUT_SECONDS)) { "Slicer worker did not stop" }
            return pid
        } finally {
            worker.close()
        }
    }

    internal fun cancellationProbeForTest(
        onStarted: () -> Unit,
        requestId: String = UUID.randomUUID().toString(),
    ) {
        check(BuildConfig.DEBUG) { "Cancellation probe is available only in debug builds" }
        requireValidRequestId(requestId)
        throwIfProjectRequestCanceled(requestId)
        check(activeRequestId.compareAndSet(null, requestId)) {
            "Another slice is already running"
        }
        try {
            throwIfProjectRequestCanceled(requestId)
            withWorker(DuckySlicerApplication.context()) { worker ->
                worker.request(
                    what = SlicerProcessContract.MESSAGE_BLOCK_FOR_TEST,
                    data = Bundle().apply {
                        putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
                    },
                    timeoutSeconds = TEST_PROBE_TIMEOUT_SECONDS,
                    onProgress = { progress -> if (progress > 0) onStarted() },
                )
            }
            error("Cancellation probe completed unexpectedly")
        } catch (failure: Exception) {
            if (projectRequestCancellationRequested(requestId)) {
                throw ProjectEditCancelledException()
            }
            if (cancelledRequestId.get() == requestId) throw SlicingCancelledException()
            throw failure
        } finally {
            activeRequestId.compareAndSet(requestId, null)
            cancelledRequestId.compareAndSet(requestId, null)
        }
    }

    internal fun workerHealthForTest(context: Context): Int {
        check(BuildConfig.DEBUG) { "Worker health is available only in debug builds" }
        val response = withWorker(context.applicationContext) { worker ->
            worker.request(
                what = SlicerProcessContract.MESSAGE_HEALTH,
                timeoutSeconds = CONNECTION_TIMEOUT_SECONDS,
            )
        }
        check(response.getBoolean(SlicerProcessContract.KEY_OK)) { "Slicer worker is unhealthy" }
        return response.getInt(SlicerProcessContract.KEY_PID)
    }

    private inline fun <T> withWorker(context: Context, block: (BoundWorker) -> T): T {
        val worker = BoundWorker(context.applicationContext)
        try {
            worker.connect()
            return block(worker)
        } finally {
            worker.close()
        }
    }

    private fun validateOutput(context: Context, path: String?): File {
        val output = File(requireNotNull(path) { "Slicer output path is unavailable" }).canonicalFile
        val outputRoot = File(context.filesDir, SlicerProcessContract.OUTPUT_DIRECTORY).canonicalFile
        check(output.isFile && output.length() > 0L && output.isInside(outputRoot)) {
            "Slicer output is unavailable"
        }
        return output
    }

    private fun outcomeFromResponse(context: Context, response: Bundle): SliceOutcome =
        SliceOutcome(
            output = validateOutput(
                context,
                response.getString(SlicerProcessContract.KEY_OUTPUT_PATH),
            ),
            layers = response.getInt(SlicerProcessContract.KEY_LAYERS),
            estimatedSeconds = response.getFloat(SlicerProcessContract.KEY_ESTIMATED_SECONDS),
            filamentMm = response.getFloat(SlicerProcessContract.KEY_FILAMENT_MM),
            filamentGrams = response.getFloat(SlicerProcessContract.KEY_FILAMENT_GRAMS),
        ).also {
            check(it.isRestorableFrom(context.filesDir)) { "Slicer result is invalid" }
        }

    private class BoundWorker(
        private val context: Context,
    ) : ServiceConnection {
        private val connected = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private var bound = false

        @Volatile
        private var binder: IBinder? = null

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            died.countDown()
        }

        override fun onBindingDied(name: ComponentName?) {
            binder = null
            died.countDown()
            connected.countDown()
        }

        override fun onNullBinding(name: ComponentName?) {
            binder = null
            connected.countDown()
        }

        fun connect() {
            bound = context.bindService(
                Intent(context, SlicerProcessService::class.java),
                this,
                Context.BIND_AUTO_CREATE,
            )
            check(bound) { "Slicer service could not be started" }
            check(connected.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Slicer service connection timed out"
            }
            checkNotNull(binder) { "Slicer service connection failed" }
        }

        fun request(
            what: Int,
            data: Bundle = Bundle.EMPTY,
            timeoutSeconds: Long,
            onProgress: (Int) -> Unit = {},
        ): Bundle {
            val activeBinder = checkNotNull(binder) { "Slicer service is disconnected" }
            val completed = CountDownLatch(1)
            val binderDied = AtomicBoolean(false)
            var response: Bundle? = null
            val deathRecipient = IBinder.DeathRecipient {
                binderDied.set(true)
                died.countDown()
                completed.countDown()
            }
            activeBinder.linkToDeath(deathRecipient, 0)
            val reply = Messenger(
                Handler(replyThread.looper) { message ->
                    when (message.what) {
                        SlicerProcessContract.MESSAGE_PROGRESS -> {
                            onProgress(message.arg1.coerceIn(0, 100))
                            true
                        }
                        SlicerProcessContract.MESSAGE_RESULT -> {
                            response = message.data
                            completed.countDown()
                            true
                        }
                        else -> false
                    }
                },
            )
            try {
                Messenger(activeBinder).send(
                    Message.obtain(null, what).apply {
                        this.data = data
                        replyTo = reply
                    },
                )
                val finished = try {
                    completed.await(timeoutSeconds, TimeUnit.SECONDS)
                } catch (interrupted: InterruptedException) {
                    cancelAbandonedWork(activeBinder, what, data)
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Slicer service request was interrupted", interrupted)
                }
                if (!finished) {
                    cancelAbandonedWork(activeBinder, what, data)
                    error("Slicer service request timed out")
                }
                check(!binderDied.get()) { "Slicer process stopped unexpectedly" }
                return requireNotNull(response) { "Slicer service returned no result" }
            } catch (error: RemoteException) {
                throw IllegalStateException("Slicer process stopped unexpectedly", error)
            } finally {
                if (activeBinder.isBinderAlive) {
                    activeBinder.unlinkToDeath(deathRecipient, 0)
                }
            }
        }

        private fun cancelAbandonedWork(activeBinder: IBinder, what: Int, data: Bundle) {
            val cancellable = what == SlicerProcessContract.MESSAGE_SLICE ||
                what == SlicerProcessContract.MESSAGE_AUTO_ORIENT ||
                what == SlicerProcessContract.MESSAGE_AUTO_ARRANGE ||
                what == SlicerProcessContract.MESSAGE_NORMALIZE_MODEL ||
                what == SlicerProcessContract.MESSAGE_SPLIT_MODEL ||
                what == SlicerProcessContract.MESSAGE_SPLIT_MODEL_VOLUME ||
                what == SlicerProcessContract.MESSAGE_CUT_MODEL ||
                what == SlicerProcessContract.MESSAGE_SIMPLIFY_MODEL ||
                what == SlicerProcessContract.MESSAGE_CREATE_PRIMITIVE ||
                what == SlicerProcessContract.MESSAGE_BLOCK_FOR_TEST
            if (!cancellable) return
            val requestId = data.getString(SlicerProcessContract.KEY_REQUEST_ID) ?: return
            runCatching {
                Messenger(activeBinder).send(
                    Message.obtain(null, SlicerProcessContract.MESSAGE_CANCEL).apply {
                        this.data = Bundle().apply {
                            putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
                        }
                    },
                )
            }
        }

        fun awaitDeath(timeoutSeconds: Long): Boolean =
            died.await(timeoutSeconds, TimeUnit.SECONDS)

        fun close() {
            if (!bound) return
            bound = false
            try {
                context.unbindService(this)
            } catch (_: IllegalArgumentException) {
                // A crashed remote process may already have removed the binding.
            }
        }
    }

    private const val CONNECTION_TIMEOUT_SECONDS = 10L
    private const val REQUEST_CANCEL_BIND_RETRIES = 100
    private const val REQUEST_CANCEL_RETRY_MILLIS = 10L
    private const val ARRANGEMENT_TIMEOUT_SECONDS = 5L * 60L
    private const val MODEL_NORMALIZATION_TIMEOUT_SECONDS = 5L * 60L
    private const val ORIENTATION_TIMEOUT_SECONDS = 5L * 60L
    private const val SLICE_TIMEOUT_SECONDS = 30L * 60L
    private const val TEST_PROBE_TIMEOUT_SECONDS = 60L
    private const val TEST_MINIMUM_GCODE_BYTES = 16 * 1_024
    private const val PRODUCTION_MAXIMUM_GCODE_BYTES = 1_073_741_824
    private const val MINIMUM_SIMPLIFIED_TRIANGLES = 4
    private const val MAXIMUM_SIMPLIFIED_TRIANGLES = 10_000_000
}

internal class ForegroundSliceSession internal constructor(
    internal val context: Context,
    internal val requestId: String,
    internal val plateId: String? = null,
) : AutoCloseable {
    private val cancellationFile = File(context.filesDir, CANCELLATION_FILE)

    internal fun cancellationRequested(): Boolean = wasCanceled(context, requestId)

    internal fun isCurrent(): Boolean =
        ForegroundSliceStore.load(context)?.requestId == requestId

    internal fun requestCancellation(): Boolean = markCanceled(context, requestId)

    override fun close() {
        SlicerProcessClient.finishUserSlice(this)
        if (cancellationRequested()) cancellationFile.delete()
        ForegroundSliceStore.remove(context, requestId)
    }

    internal fun abandon() {
        if (cancellationRequested()) cancellationFile.delete()
        ForegroundSliceStore.remove(context, requestId)
    }

    internal companion object {
        private const val CANCELLATION_FILE = "foreground-slice.cancel"
        private const val MAX_CANCELLATION_BYTES = 128L

        fun prepare(
            context: Context,
            requestId: String,
            plateId: String,
        ): ForegroundSliceSession =
            ForegroundSliceSession(context, requestId, plateId).also { session ->
                check(!session.cancellationFile.exists() || session.cancellationFile.delete()) {
                    "Slice cancellation state is unavailable"
                }
                ForegroundSliceStore.begin(context, requestId, plateId)
            }

        fun recover(context: Context): ForegroundSliceSession? =
            ForegroundSliceStore.load(context)?.let { record ->
                ForegroundSliceSession(context, record.requestId, record.plateId)
            }

        fun markCanceled(context: Context, requestId: String): Boolean {
            if (ForegroundSliceStore.load(context)?.requestId != requestId) return false
            FileOutputStream(File(context.filesDir, CANCELLATION_FILE)).use { output ->
                output.write(requestId.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            ForegroundSliceStore.mark(context, requestId, ForegroundSlicePhase.CANCELED)
            return true
        }

        fun wasCanceled(context: Context, requestId: String): Boolean = runCatching {
            val cancellationFile = File(context.filesDir, CANCELLATION_FILE)
            val fileCanceled = cancellationFile.isFile &&
                cancellationFile.length() in 1..MAX_CANCELLATION_BYTES &&
                cancellationFile.readText(Charsets.UTF_8).trim() == requestId
            val checkpointCanceled = ForegroundSliceStore.load(context)?.let { record ->
                record.requestId == requestId && record.phase == ForegroundSlicePhase.CANCELED
            } == true
            fileCanceled || checkpointCanceled
        }.getOrDefault(false)
    }
}

internal class SlicingCancelledException : Exception("Slicing was cancelled")
internal class ProjectEditCancelledException : Exception("Project edit was cancelled")

internal class ModelNotSplittableException : Exception("model_not_splittable")
internal class ModelNotCuttableException : Exception("model_not_cuttable")

internal data class OrcaImportedObject(
    val file: File,
    val displayName: String,
    val centerXmm: Float,
    val centerYmm: Float,
)

internal data class OrcaImportedProjectVolume(
    val file: File,
    val displayName: String,
    val filamentSlot: Int,
)

internal data class OrcaImportedProjectObject(
    val volumes: List<OrcaImportedProjectVolume>,
    val displayName: String,
    val centerXmm: Float,
    val centerYmm: Float,
)

private data class OrcaImportedProjectVolumeRecord(
    val volume: OrcaImportedProjectVolume,
    val objectName: String,
    val centerXmm: Float,
    val centerYmm: Float,
)

private const val MAX_MODEL_COORDINATE_MM = 1_000_000f

class SlicerProcessService : Service() {
    private val activeRequestId = AtomicReference<String?>(null)
    private val cancelledRequestId = AtomicReference<String?>(null)
    private val activeReply = AtomicReference<Messenger?>(null)
    private val foregroundRequestId = AtomicReference<String?>(null)
    private val completedForegroundResult = AtomicReference<Bundle?>(null)
    @Volatile
    private var foregroundProgress = 0
    private val sliceThreadDelegate = lazy {
        HandlerThread("DuckySlicer native work").apply { start() }
    }
    private val sliceThread by sliceThreadDelegate
    private val sliceHandler by lazy { Handler(sliceThread.looper) }
    private val mainHandler = Handler(Looper.getMainLooper()) { message ->
        handleMessage(message)
        true
    }
    private val messenger = Messenger(mainHandler)
    private val artifactStore by lazy {
        SliceArtifactStore(
            filesDir,
            transientRoots = listOf(
                filesDir,
                cacheDir,
                ProjectStore.modelStorageRoot(filesDir),
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { artifactStore.recover() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        when (intent?.action) {
            ACTION_START_SLICE -> {
                if (
                    requestId == null || requestId.length !in 1..MAX_REQUEST_ID_LENGTH ||
                    !beginForegroundSlice(requestId)
                ) {
                    stopSelf(startId)
                }
            }
            ACTION_CANCEL_SLICE -> if (requestId != null) cancelFromNotification(requestId)
            ACTION_FINISH_SLICE -> {
                if (
                    requestId == null || requestId.length !in 1..MAX_REQUEST_ID_LENGTH ||
                    !finishForegroundSlice(requestId)
                ) {
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        val abandonedRequestId = activeRequestId.get()
        if (
            abandonedRequestId != null &&
            foregroundRequestId.get() != abandonedRequestId
        ) {
            mainHandler.post {
                if (
                    activeRequestId.get() == abandonedRequestId &&
                    foregroundRequestId.get() != abandonedRequestId
                ) {
                    Process.killProcess(Process.myPid())
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        if (activeRequestId.get() != null) {
            Process.killProcess(Process.myPid())
        } else if (sliceThreadDelegate.isInitialized()) {
            sliceThread.quitSafely()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val requestId = foregroundRequestId.get()
        if (requestId != null) {
            activeReply.getAndSet(null)?.let { reply ->
                send(
                    reply,
                    SlicerProcessContract.MESSAGE_RESULT,
                    data = failure("Slicing timed out"),
                )
            }
            cancelledRequestId.set(requestId)
            runCatching {
                ForegroundSliceStore.mark(this, requestId, ForegroundSlicePhase.FAILED)
            }
            finishForegroundSlice(requestId)
        }
        stopSelf(startId)
        if (activeRequestId.get() != null) Process.killProcess(Process.myPid())
    }

    private fun beginForegroundSlice(requestId: String): Boolean {
        val current = foregroundRequestId.get()
        if (current != null && current != requestId) return false
        if (current == null && !foregroundRequestId.compareAndSet(null, requestId)) return false
        foregroundProgress = 0
        completedForegroundResult.set(null)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.slice_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.slice_notification_channel_summary)
                setShowBadge(false)
            },
        )
        val notification = sliceNotification(requestId, progress = 0, canceling = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return true
    }

    private fun sliceNotification(
        requestId: String,
        progress: Int,
        canceling: Boolean,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            cancelSliceIntent(this, requestId),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            canceling -> getString(R.string.slice_notification_canceling)
            progress <= 0 -> getString(R.string.slice_notification_preparing)
            else -> getString(R.string.slice_notification_progress, progress.coerceIn(0, 100))
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_slice_notification)
            .setContentTitle(getString(R.string.slice_notification_title))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(!canceling)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0 && !canceling)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_slice_notification),
                    getString(R.string.cancel),
                    cancelIntent,
                ).build(),
            )
            .build()
    }

    private fun updateForegroundSlice(requestId: String, progress: Int, canceling: Boolean = false) {
        if (foregroundRequestId.get() != requestId) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            sliceNotification(requestId, progress, canceling),
        )
    }

    private fun finishForegroundSlice(requestId: String): Boolean {
        if (!foregroundRequestId.compareAndSet(requestId, null)) return false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return true
    }

    private fun cancelFromNotification(requestId: String) {
        if (foregroundRequestId.get() != requestId) return
        runCatching { ForegroundSliceSession.markCanceled(this, requestId) }
        if (activeRequestId.get() != requestId) {
            finishForegroundSlice(requestId)
            return
        }
        cancelledRequestId.set(requestId)
        updateForegroundSlice(requestId, progress = 0, canceling = true)
        activeReply.getAndSet(null)?.let { reply ->
            send(
                reply,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Slicing was canceled").apply {
                    putBoolean(SlicerProcessContract.KEY_CANCELED, true)
                },
            )
        }
        mainHandler.postDelayed(
            {
                if (cancelledRequestId.get() == requestId) {
                    Process.killProcess(Process.myPid())
                }
            },
            CANCEL_PROCESS_DELAY_MILLIS,
        )
    }

    private fun handleMessage(message: Message) {
        when (message.what) {
            SlicerProcessContract.MESSAGE_SLICE -> startWork(message, WorkOperation.SLICE)
            SlicerProcessContract.MESSAGE_AUTO_ORIENT -> startWork(message, WorkOperation.AUTO_ORIENT)
            SlicerProcessContract.MESSAGE_AUTO_ARRANGE -> startWork(message, WorkOperation.AUTO_ARRANGE)
            SlicerProcessContract.MESSAGE_NORMALIZE_MODEL ->
                startWork(message, WorkOperation.NORMALIZE_MODEL)
            SlicerProcessContract.MESSAGE_SPLIT_MODEL ->
                startWork(message, WorkOperation.SPLIT_MODEL)
            SlicerProcessContract.MESSAGE_SPLIT_MODEL_VOLUME ->
                startWork(message, WorkOperation.SPLIT_MODEL_VOLUME)
            SlicerProcessContract.MESSAGE_CUT_MODEL ->
                startWork(message, WorkOperation.CUT_MODEL)
            SlicerProcessContract.MESSAGE_SIMPLIFY_MODEL ->
                startWork(message, WorkOperation.SIMPLIFY_MODEL)
            SlicerProcessContract.MESSAGE_CREATE_PRIMITIVE ->
                startWork(message, WorkOperation.CREATE_PRIMITIVE)
            SlicerProcessContract.MESSAGE_ATTACH -> attachToForegroundSlice(message)
            SlicerProcessContract.MESSAGE_CANCEL -> cancelWork(message)
            SlicerProcessContract.MESSAGE_HEALTH -> send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = Bundle().apply {
                    putBoolean(SlicerProcessContract.KEY_OK, true)
                    putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                },
            )
            SlicerProcessContract.MESSAGE_TERMINATE_FOR_TEST -> {
                val result = if (BuildConfig.DEBUG) {
                    Bundle().apply {
                        putBoolean(SlicerProcessContract.KEY_OK, true)
                        putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                    }
                } else {
                    failure("Worker termination is unavailable")
                }
                send(message.replyTo, SlicerProcessContract.MESSAGE_RESULT, data = result)
                if (BuildConfig.DEBUG) {
                    Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
                }
            }
            SlicerProcessContract.MESSAGE_BLOCK_FOR_TEST -> {
                if (BuildConfig.DEBUG) {
                    startWork(message, WorkOperation.TEST_PROBE)
                } else {
                    send(
                        message.replyTo,
                        SlicerProcessContract.MESSAGE_RESULT,
                        data = failure("Cancellation probe is unavailable"),
                    )
                }
            }
            else -> send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Unsupported slicer operation"),
            )
        }
    }

    private fun attachToForegroundSlice(message: Message) {
        val requestId = message.data.getString(SlicerProcessContract.KEY_REQUEST_ID)
        if (requestId == null || requestId.length !in 1..MAX_REQUEST_ID_LENGTH) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Foreground slice request id is invalid"),
            )
            return
        }
        val record = ForegroundSliceStore.load(this)
        if (record == null || record.requestId != requestId) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Foreground slice checkpoint is unavailable"),
            )
            return
        }
        when (record.phase) {
            ForegroundSlicePhase.COMPLETED -> {
                send(
                    message.replyTo,
                    SlicerProcessContract.MESSAGE_RESULT,
                    data = success(requireNotNull(record.outcome)),
                )
                return
            }
            ForegroundSlicePhase.CANCELED -> {
                send(
                    message.replyTo,
                    SlicerProcessContract.MESSAGE_RESULT,
                    data = failure("Slicing was canceled").apply {
                        putBoolean(SlicerProcessContract.KEY_CANCELED, true)
                    },
                )
                return
            }
            ForegroundSlicePhase.FAILED -> {
                send(
                    message.replyTo,
                    SlicerProcessContract.MESSAGE_RESULT,
                    data = failure("Foreground slice failed while the app was closed"),
                )
                return
            }
            ForegroundSlicePhase.ACTIVE -> Unit
        }
        completedForegroundResult.get()?.let { result ->
            send(message.replyTo, SlicerProcessContract.MESSAGE_RESULT, data = Bundle(result))
            return
        }
        if (
            foregroundRequestId.get() != requestId ||
            activeRequestId.get() != requestId
        ) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Foreground slice worker is unavailable"),
            )
            return
        }
        activeReply.set(message.replyTo)
        send(
            message.replyTo,
            SlicerProcessContract.MESSAGE_PROGRESS,
            foregroundProgress.coerceIn(0, 100),
        )
        completedForegroundResult.get()?.let { result ->
            if (activeReply.compareAndSet(message.replyTo, null)) {
                send(message.replyTo, SlicerProcessContract.MESSAGE_RESULT, data = Bundle(result))
            }
        }
    }

    private fun startWork(message: Message, operation: WorkOperation) {
        val requestId = message.data.getString(SlicerProcessContract.KEY_REQUEST_ID)
        if (requestId == null || requestId.length !in 1..MAX_REQUEST_ID_LENGTH) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Slicer request id is invalid"),
            )
            return
        }
        if (
            operation == WorkOperation.SLICE &&
            ForegroundSliceSession.wasCanceled(this, requestId)
        ) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Slicing was canceled").apply {
                    putBoolean(SlicerProcessContract.KEY_CANCELED, true)
                },
            )
            return
        }
        if (!activeRequestId.compareAndSet(null, requestId)) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Another slicer operation is already running"),
            )
            return
        }
        cancelledRequestId.set(null)
        val requestData = Bundle(message.data)
        val reply = message.replyTo
        if (operation == WorkOperation.SLICE) activeReply.set(reply)
        val accepted = sliceHandler.post {
            val result = when (operation) {
                WorkOperation.TEST_PROBE -> runCancellationProbe(reply)
                WorkOperation.AUTO_ORIENT -> runAutoOrient(requestData)
                WorkOperation.AUTO_ARRANGE -> runAutoArrange(requestData)
                WorkOperation.NORMALIZE_MODEL -> runNormalizeModel(requestData)
                WorkOperation.SPLIT_MODEL -> runSplitModel(requestData)
                WorkOperation.SPLIT_MODEL_VOLUME -> runSplitModelVolume(requestData)
                WorkOperation.CUT_MODEL -> runCutModel(requestData)
                WorkOperation.SIMPLIFY_MODEL -> runSimplifyModel(requestData)
                WorkOperation.CREATE_PRIMITIVE -> runCreatePrimitive(requestData)
                WorkOperation.SLICE -> runSlice(requestData) { percent ->
                    foregroundProgress = maxOf(foregroundProgress, percent.coerceIn(0, 100))
                    mainHandler.post { updateForegroundSlice(requestId, percent) }
                    send(
                        activeReply.get() ?: reply,
                        SlicerProcessContract.MESSAGE_PROGRESS,
                        percent,
                    )
                }
            }
            if (cancelledRequestId.get() == requestId) return@post
            if (
                operation == WorkOperation.SLICE &&
                foregroundRequestId.get() == requestId
            ) {
                checkpointForegroundResult(requestId, result)
                completedForegroundResult.set(Bundle(result))
            }
            if (activeRequestId.compareAndSet(requestId, null)) {
                val resultReply = if (operation == WorkOperation.SLICE) {
                    activeReply.getAndSet(null) ?: reply
                } else {
                    reply
                }
                send(resultReply, SlicerProcessContract.MESSAGE_RESULT, data = result)
                if (operation == WorkOperation.SLICE) {
                    mainHandler.post {
                        updateForegroundSlice(requestId, progress = 100)
                        scheduleForegroundCompletionGuard(requestId)
                    }
                }
            }
        }
        if (accepted && operation == WorkOperation.SLICE) scheduleStorageGuard(requestId)
        if (!accepted) {
            activeRequestId.compareAndSet(requestId, null)
            activeReply.compareAndSet(reply, null)
            val rejected = failure("Slicer worker thread is unavailable")
            if (
                operation == WorkOperation.SLICE &&
                foregroundRequestId.get() == requestId
            ) {
                runCatching {
                    ForegroundSliceStore.mark(this, requestId, ForegroundSlicePhase.FAILED)
                }
                completedForegroundResult.set(Bundle(rejected))
            }
            send(
                reply,
                SlicerProcessContract.MESSAGE_RESULT,
                data = rejected,
            )
            if (operation == WorkOperation.SLICE) finishForegroundSlice(requestId)
        }
    }

    private fun scheduleStorageGuard(requestId: String) {
        mainHandler.postDelayed(
            object : Runnable {
                override fun run() {
                    if (activeRequestId.get() != requestId || cancelledRequestId.get() == requestId) {
                        return
                    }
                    if (artifactStore.activeOutputIsUnsafe()) {
                        Process.killProcess(Process.myPid())
                        return
                    }
                    mainHandler.postDelayed(this, STORAGE_GUARD_INTERVAL_MILLIS)
                }
            },
            STORAGE_GUARD_INTERVAL_MILLIS,
        )
    }

    private fun checkpointForegroundResult(requestId: String, result: Bundle) {
        runCatching {
            if (result.getBoolean(SlicerProcessContract.KEY_OK)) {
                ForegroundSliceStore.complete(
                    this,
                    requestId,
                    SliceOutcome(
                        output = File(
                            requireNotNull(
                                result.getString(SlicerProcessContract.KEY_OUTPUT_PATH),
                            ) { "Foreground slice output is unavailable" },
                        ),
                        layers = result.getInt(SlicerProcessContract.KEY_LAYERS),
                        estimatedSeconds = result.getFloat(
                            SlicerProcessContract.KEY_ESTIMATED_SECONDS,
                        ),
                        filamentMm = result.getFloat(SlicerProcessContract.KEY_FILAMENT_MM),
                        filamentGrams = result.getFloat(
                            SlicerProcessContract.KEY_FILAMENT_GRAMS,
                        ),
                    ),
                )
            } else {
                ForegroundSliceStore.mark(this, requestId, ForegroundSlicePhase.FAILED)
            }
        }.onFailure { failure ->
            if (BuildConfig.DEBUG) {
                Log.e(LOG_TAG, "Foreground slice checkpoint failed", failure)
            }
        }
    }

    private fun scheduleForegroundCompletionGuard(requestId: String) {
        mainHandler.postDelayed(
            {
                if (
                    foregroundRequestId.get() == requestId &&
                    activeRequestId.get() == null
                ) {
                    finishForegroundSlice(requestId)
                }
            },
            FOREGROUND_COMPLETION_GRACE_MILLIS,
        )
    }

    private fun cancelWork(message: Message) {
        val requestId = message.data.getString(SlicerProcessContract.KEY_REQUEST_ID)
        if (requestId == null || activeRequestId.get() != requestId) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Slice request is no longer active"),
            )
            return
        }
        cancelledRequestId.set(requestId)
        if (foregroundRequestId.get() == requestId) {
            runCatching { ForegroundSliceSession.markCanceled(this, requestId) }
        }
        updateForegroundSlice(requestId, progress = 0, canceling = true)
        activeReply.getAndSet(null)?.let { active ->
            send(
                active,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Slicing was canceled").apply {
                    putBoolean(SlicerProcessContract.KEY_CANCELED, true)
                },
            )
        }
        send(
            message.replyTo,
            SlicerProcessContract.MESSAGE_RESULT,
            data = Bundle().apply {
                putBoolean(SlicerProcessContract.KEY_OK, true)
                putInt(SlicerProcessContract.KEY_PID, Process.myPid())
            },
        )
        mainHandler.postDelayed(
            {
                if (cancelledRequestId.get() == requestId) {
                    Process.killProcess(Process.myPid())
                }
            },
            CANCEL_PROCESS_DELAY_MILLIS,
        )
    }

    private fun runCancellationProbe(reply: Messenger?): Bundle {
        send(reply, SlicerProcessContract.MESSAGE_PROGRESS, 1)
        Thread.sleep(TEST_PROBE_DURATION_MILLIS)
        return failure("Cancellation probe was not cancelled")
    }

    private fun runSlice(extras: Bundle, onProgress: (Int) -> Unit): Bundle = try {
        val paths = requireNotNull(extras.getStringArrayList(SlicerProcessContract.KEY_MODEL_PATHS)) {
            "Model paths are unavailable"
        }
        require(paths.size in 1..MAX_OBJECTS) { "Invalid model count" }
        val models = paths.map(::validateModel)
        val objectVolumeCounts = requireNotNull(
            extras.getIntArray(SlicerProcessContract.KEY_OBJECT_VOLUME_COUNTS),
        ) { "Object volume counts are unavailable" }
        require(
            objectVolumeCounts.size in 1..MAX_OBJECTS &&
                objectVolumeCounts.all { it in 1..MAX_PROJECT_VOLUMES_PER_OBJECT } &&
                objectVolumeCounts.sum() == models.size,
        ) { "Object volume counts do not match models" }
        val supportPaintPaths = requireNotNull(
            extras.getStringArrayList(SlicerProcessContract.KEY_SUPPORT_PAINT_PATHS),
        ) { "Support paint paths are unavailable" }
        require(supportPaintPaths.size == models.size) { "Support paint count does not match models" }
        val supportPaintFiles = supportPaintPaths.map { path ->
            path.takeIf(String::isNotEmpty)?.let(::validateSupportPaint)
        }
        val seamPaintPaths = requireNotNull(
            extras.getStringArrayList(SlicerProcessContract.KEY_SEAM_PAINT_PATHS),
        ) { "Seam paint paths are unavailable" }
        require(seamPaintPaths.size == models.size) { "Seam paint count does not match models" }
        val seamPaintFiles = seamPaintPaths.map { path ->
            path.takeIf(String::isNotEmpty)?.let(::validateSeamPaint)
        }
        val multiColorPaintPaths = requireNotNull(
            extras.getStringArrayList(SlicerProcessContract.KEY_MULTI_COLOR_PAINT_PATHS),
        ) { "Multi-color paint paths are unavailable" }
        require(multiColorPaintPaths.size == models.size) {
            "Multi-color paint count does not match models"
        }
        val multiColorPaintFiles = multiColorPaintPaths.map { path ->
            path.takeIf(String::isNotEmpty)?.let(::validateMultiColorPaint)
        }
        val variableLayerHeightPaths = requireNotNull(
            extras.getStringArrayList(SlicerProcessContract.KEY_VARIABLE_LAYER_HEIGHT_PATHS),
        ) { "Variable layer height paths are unavailable" }
        require(variableLayerHeightPaths.size == objectVolumeCounts.size) {
            "Variable layer height count does not match objects"
        }
        val variableLayerHeightFiles = variableLayerHeightPaths.map { path ->
            path.takeIf(String::isNotEmpty)?.let(::validateVariableLayerHeights)
        }
        val processOverridePaths = requireNotNull(
            extras.getStringArrayList(SlicerProcessContract.KEY_PROCESS_OVERRIDE_PATHS),
        ) { "Object setting paths are unavailable" }
        require(processOverridePaths.size == objectVolumeCounts.size) {
            "Object settings count does not match objects"
        }
        val processOverrideFiles = processOverridePaths.map { path ->
            path.takeIf(String::isNotEmpty)?.let(::validateObjectProcessOverrides)
        }
        val brimPointPaths = requireNotNull(
            extras.getStringArrayList(SlicerProcessContract.KEY_BRIM_POINT_PATHS),
        ) { "Brim point paths are unavailable" }
        require(brimPointPaths.size == objectVolumeCounts.size) {
            "Brim point count does not match objects"
        }
        val brimPointFiles = brimPointPaths.map { path ->
            path.takeIf(String::isNotEmpty)?.let(::validateBrimPoints)
        }
        val optionsText = requireNotNull(extras.getString(SlicerProcessContract.KEY_OPTIONS)) {
            "Slice settings are unavailable"
        }
        require(optionsText.toByteArray(Charsets.UTF_8).size <= SlicerProcessContract.MAX_OPTIONS_BYTES) {
            "Slice settings are too large"
        }
        require(
            encodedRequestBytes(
                paths + supportPaintPaths + seamPaintPaths + multiColorPaintPaths +
                    variableLayerHeightPaths + processOverridePaths + brimPointPaths,
                optionsText,
            ) <=
                SlicerProcessContract.MAX_REQUEST_BYTES,
        ) {
            "Slice request is too large"
        }
        val options = requireNotNull(JSONObject(optionsText).toProjectSliceOptionsOrNull()) {
            "Slice settings are invalid"
        }
        val filamentSlots = requireNotNull(
            extras.getIntArray(SlicerProcessContract.KEY_FILAMENT_SLOTS),
        ) { "Filament assignments are unavailable" }
        val availableFilaments = options.resolvedFilamentSlots()
        require(
            filamentSlots.size == models.size &&
                filamentSlots.all { it in availableFilaments.indices } &&
                multiColorPaintFiles.filterNotNull().all { paint ->
                    paint.filamentSlots.all { it in availableFilaments.indices }
                },
        ) { "Filament assignments are invalid" }
        val maximumGcodeBytes = if (
            BuildConfig.DEBUG &&
            extras.containsKey(SlicerProcessContract.KEY_MAXIMUM_GCODE_BYTES_FOR_TEST)
        ) {
            extras.getInt(SlicerProcessContract.KEY_MAXIMUM_GCODE_BYTES_FOR_TEST).also {
                require(it in TEST_MINIMUM_GCODE_BYTES..PRODUCTION_MAXIMUM_GCODE_BYTES) {
                    "Invalid test G-code output limit"
                }
            }
        } else {
            PRODUCTION_MAXIMUM_GCODE_BYTES
        }
        success(
            runNativeSlice(
                models,
                supportPaintFiles,
                seamPaintFiles,
                multiColorPaintFiles,
                variableLayerHeightFiles,
                processOverrideFiles,
                brimPointFiles,
                objectVolumeCounts,
                filamentSlots,
                options,
                maximumGcodeBytes,
                onProgress,
            ),
        )
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "On-device slicing failed", error)
        failure(error.message ?: "Slicer operation failed")
    }

    private fun runAutoOrient(extras: Bundle): Bundle = try {
        val paths = requireNotNull(
            extras.getStringArrayList(SlicerProcessContract.KEY_MODEL_PATHS),
        ) {
            "Model paths are unavailable"
        }
        require(paths.size in 1..MAX_PROJECT_VOLUMES_PER_OBJECT) {
            "Automatic orientation requires at least one model volume"
        }
        val models = paths.map(::validateModel)
        val runtime = createNativeRuntime()
        try {
            loadNativeObjects(runtime, models, intArrayOf(models.size))
            val rotation = requireNotNull(runtime.nativeAutoOrientObject(0)) {
                "Slicer could not orient the model"
            }
            require(rotation.size == 3 && rotation.all { it.isFinite() }) {
                "Slicer returned an invalid orientation"
            }
            Bundle().apply {
                putBoolean(SlicerProcessContract.KEY_OK, true)
                putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                putDoubleArray(SlicerProcessContract.KEY_ROTATION_RADIANS, rotation)
            }
        } finally {
            runtime.clearModel()
        }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Automatic orientation failed", error)
        failure(error.message ?: "Slicer could not orient the model")
    }

    private fun runNormalizeModel(extras: Bundle): Bundle = try {
        val sourcePath = requireNotNull(extras.getString(SlicerProcessContract.KEY_MODEL_PATH)) {
            "Model path is unavailable"
        }
        val outputPath = requireNotNull(
            extras.getString(SlicerProcessContract.KEY_MODEL_OUTPUT_DIRECTORY),
        ) { "Model output is unavailable" }
        val source = validateModel(sourcePath)
        val outputDirectory = validateModelImportDirectory(outputPath)
        val runtime = createNativeRuntime()
        try {
            check(runtime.loadModel(source.absolutePath)) { "Model could not be prepared" }
            check(runtime.nativeGetUnsupportedProjectSemanticCount() == 0) {
                "Model contains paint, modifier, negative, or support data that is not importable yet"
            }
            val records = requireNotNull(
                runtime.nativeExportLoadedProjectVolumes(outputDirectory.absolutePath),
            ) { "Model objects and parts could not be exported" }
            require(records.size in 1..ProjectStore.MAX_PROJECT_VOLUMES) {
                "Invalid imported model volume count"
            }
            Bundle().apply {
                putBoolean(SlicerProcessContract.KEY_OK, true)
                putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                putStringArrayList(
                    SlicerProcessContract.KEY_NORMALIZED_MODELS,
                    ArrayList(records.toList()),
                )
            }
        } finally {
            runtime.clearModel()
        }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Model normalization failed", error)
        failure(error.message ?: "Slicer could not import the model")
    }

    private fun runSplitModel(extras: Bundle): Bundle = try {
        val sourcePath = requireNotNull(extras.getString(SlicerProcessContract.KEY_MODEL_PATH)) {
            "Model path is unavailable"
        }
        val outputPath = requireNotNull(
            extras.getString(SlicerProcessContract.KEY_MODEL_OUTPUT_DIRECTORY),
        ) { "Model output is unavailable" }
        val source = validateModel(sourcePath)
        val outputDirectory = validateModelImportDirectory(outputPath)
        val runtime = createNativeRuntime()
        try {
            check(runtime.loadModel(source.absolutePath)) { "Model could not be prepared" }
            if (!runtime.nativeIsObjectSplittable(0)) {
                return failure("Model has no separate objects").apply {
                    putBoolean(SlicerProcessContract.KEY_MODEL_NOT_SPLITTABLE, true)
                }
            }
            val split = requireNotNull(runtime.nativeSplitObject(0)) {
                "Model has no separate objects"
            }
            require(split.size == 2 && split[0] == 0 && split[1] in 2..MAX_OBJECTS) {
                "Invalid split object count"
            }
            val records = requireNotNull(
                runtime.nativeExportLoadedObjects(outputDirectory.absolutePath),
            ) { "Split objects could not be exported" }
            require(records.size == split[1]) { "Split object export count changed" }
            Bundle().apply {
                putBoolean(SlicerProcessContract.KEY_OK, true)
                putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                putStringArrayList(
                    SlicerProcessContract.KEY_NORMALIZED_MODELS,
                    ArrayList(records.toList()),
                )
            }
        } finally {
            runtime.clearModel()
        }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Model split failed", error)
        failure(error.message ?: "Slicer could not split the model")
    }

    private fun runSplitModelVolume(extras: Bundle): Bundle = try {
        val paths = requireNotNull(
            extras.getStringArrayList(SlicerProcessContract.KEY_MODEL_PATHS),
        ) { "Model paths are unavailable" }
        require(paths.size in 1..MAX_PROJECT_VOLUMES_PER_OBJECT) {
            "Invalid model volume count"
        }
        require(encodedRequestBytes(paths, "") <= SlicerProcessContract.MAX_REQUEST_BYTES) {
            "Split request is too large"
        }
        val models = paths.map(::validateModel)
        val sourceVolumeIndex = extras.getInt(SlicerProcessContract.KEY_VOLUME_INDEX, -1)
        require(sourceVolumeIndex in models.indices) { "Source volume is unavailable" }
        val outputPath = requireNotNull(
            extras.getString(SlicerProcessContract.KEY_MODEL_OUTPUT_DIRECTORY),
        ) { "Model output is unavailable" }
        val outputDirectory = validateModelImportDirectory(outputPath)
        val runtime = createNativeRuntime()
        try {
            loadNativeObjects(runtime, models, intArrayOf(models.size))
            if (!runtime.nativeIsVolumeSplittable(0, sourceVolumeIndex)) {
                return failure("Part has no separate pieces").apply {
                    putBoolean(SlicerProcessContract.KEY_MODEL_NOT_SPLITTABLE, true)
                }
            }
            val resultingVolumeCount = runtime.nativeSplitVolume(0, sourceVolumeIndex)
            require(
                resultingVolumeCount in (models.size + 1)..MAX_PROJECT_VOLUMES_PER_OBJECT,
            ) { "Invalid split part count" }
            val createdPartCount = resultingVolumeCount - models.size + 1
            val records = requireNotNull(
                runtime.nativeExportObjectVolumeRange(
                    outputDirectory.absolutePath,
                    objectIndex = 0,
                    startVolumeIndex = sourceVolumeIndex,
                    volumeCount = createdPartCount,
                ),
            ) { "Split parts could not be exported" }
            require(records.size == createdPartCount) { "Split part export count changed" }
            Bundle().apply {
                putBoolean(SlicerProcessContract.KEY_OK, true)
                putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                putStringArrayList(
                    SlicerProcessContract.KEY_NORMALIZED_MODELS,
                    ArrayList(records.toList()),
                )
            }
        } finally {
            runtime.clearModel()
        }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Model part split failed", error)
        failure(error.message ?: "Slicer could not split the part")
    }

    private fun runCutModel(extras: Bundle): Bundle = try {
        val sourcePath = requireNotNull(extras.getString(SlicerProcessContract.KEY_MODEL_PATH)) {
            "Model path is unavailable"
        }
        val outputPath = requireNotNull(
            extras.getString(SlicerProcessContract.KEY_MODEL_OUTPUT_DIRECTORY),
        ) { "Model output is unavailable" }
        val heightRatio = extras.getFloat(SlicerProcessContract.KEY_CUT_HEIGHT_RATIO, Float.NaN)
        require(heightRatio.isFinite() && heightRatio in 0.02f..0.98f) {
            "Cut height is invalid"
        }
        val placeOnCut = extras.getBoolean(SlicerProcessContract.KEY_PLACE_ON_CUT, true)
        val source = validateModel(sourcePath)
        val outputDirectory = validateModelImportDirectory(outputPath)
        val runtime = createNativeRuntime()
        try {
            check(runtime.loadModel(source.absolutePath)) { "Model could not be prepared" }
            val cut = runtime.nativeCutObject(0, heightRatio, placeOnCut)
            if (cut == null) {
                return failure("The cut plane does not divide this model").apply {
                    putBoolean(SlicerProcessContract.KEY_MODEL_NOT_CUTTABLE, true)
                }
            }
            require(cut.size == 2 && cut[0] == 0 && cut[1] == 2) {
                "Invalid cut object count"
            }
            val records = requireNotNull(
                runtime.nativeExportLoadedObjects(outputDirectory.absolutePath),
            ) { "Cut objects could not be exported" }
            require(records.size == 2) { "Cut object export count changed" }
            Bundle().apply {
                putBoolean(SlicerProcessContract.KEY_OK, true)
                putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                putStringArrayList(
                    SlicerProcessContract.KEY_NORMALIZED_MODELS,
                    ArrayList(records.toList()),
                )
            }
        } finally {
            runtime.clearModel()
        }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Model cut failed", error)
        failure(error.message ?: "Slicer could not cut the model")
    }

    private fun runSimplifyModel(extras: Bundle): Bundle = try {
        val sourcePath = requireNotNull(extras.getString(SlicerProcessContract.KEY_MODEL_PATH)) {
            "Model path is unavailable"
        }
        val outputPath = requireNotNull(
            extras.getString(SlicerProcessContract.KEY_MODEL_OUTPUT_DIRECTORY),
        ) { "Model output is unavailable" }
        val targetTriangles = extras.getInt(SlicerProcessContract.KEY_TARGET_TRIANGLES, -1)
        require(targetTriangles in MINIMUM_SIMPLIFIED_TRIANGLES..MAXIMUM_SIMPLIFIED_TRIANGLES) {
            "Simplified model detail is invalid"
        }
        val source = validateModel(sourcePath)
        val outputDirectory = validateModelImportDirectory(outputPath)
        val runtime = createNativeRuntime()
        try {
            check(runtime.loadModel(source.absolutePath)) { "Model could not be prepared" }
            val actualTriangles = runtime.nativeSimplifyObject(0, targetTriangles)
            check(actualTriangles in MINIMUM_SIMPLIFIED_TRIANGLES until Int.MAX_VALUE) {
                "Model could not be simplified"
            }
            val records = requireNotNull(
                runtime.nativeExportLoadedObjects(outputDirectory.absolutePath),
            ) { "Simplified model could not be exported" }
            require(records.size == 1) { "Simplified model export count changed" }
            Bundle().apply {
                putBoolean(SlicerProcessContract.KEY_OK, true)
                putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                putStringArrayList(
                    SlicerProcessContract.KEY_NORMALIZED_MODELS,
                    ArrayList(records.toList()),
                )
            }
        } finally {
            runtime.clearModel()
        }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Model simplification failed", error)
        failure(error.message ?: "Slicer could not simplify the model")
    }

    private fun runCreatePrimitive(extras: Bundle): Bundle = try {
        val outputPath = requireNotNull(
            extras.getString(SlicerProcessContract.KEY_MODEL_OUTPUT_DIRECTORY),
        ) { "Shape output is unavailable" }
        val primitiveType = extras.getInt(SlicerProcessContract.KEY_PRIMITIVE_TYPE, -1)
        val primitive = OrcaPrimitive.entries.firstOrNull { it.nativeId == primitiveType }
        requireNotNull(primitive) { "Shape type is invalid" }
        val sizeMm = extras.getFloat(SlicerProcessContract.KEY_PRIMITIVE_SIZE_MM, Float.NaN)
        require(sizeMm.isFinite() && sizeMm in MIN_PRIMITIVE_SIZE_MM..MAX_PRIMITIVE_SIZE_MM) {
            "Shape size is invalid"
        }
        val outputDirectory = validateModelImportDirectory(outputPath)
        val output = File(outputDirectory, "primitive.stl")
        require(!output.exists()) { "Shape output already exists" }
        val runtime = createNativeRuntime()
        check(runtime.nativeCreatePrimitive(primitiveType, sizeMm, output.absolutePath)) {
            "Shape could not be generated"
        }
        require(output.isFile && output.length() in 1..MAX_MODEL_BYTES) {
            "Generated shape is invalid"
        }
        Bundle().apply {
            putBoolean(SlicerProcessContract.KEY_OK, true)
            putInt(SlicerProcessContract.KEY_PID, Process.myPid())
            putStringArrayList(
                SlicerProcessContract.KEY_NORMALIZED_MODELS,
                arrayListOf(
                    "${output.absolutePath}\t${primitive.wireName}.stl\t0\t0",
                ),
            )
        }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Shape creation failed", error)
        failure(error.message ?: "Slicer could not create the shape")
    }

    private fun runAutoArrange(extras: Bundle): Bundle = try {
        val paths = requireNotNull(extras.getStringArrayList(SlicerProcessContract.KEY_MODEL_PATHS)) {
            "Model paths are unavailable"
        }
        require(paths.size in 2..MAX_OBJECTS) { "Invalid model count" }
        require(encodedRequestBytes(paths, "") <= SlicerProcessContract.MAX_REQUEST_BYTES) {
            "Arrange request is too large"
        }
        val models = paths.map(::validateModel)
        val objectVolumeCounts = requireNotNull(
            extras.getIntArray(SlicerProcessContract.KEY_OBJECT_VOLUME_COUNTS),
        ) { "Object volume counts are unavailable" }
        require(
            objectVolumeCounts.size in 2..MAX_OBJECTS &&
                objectVolumeCounts.all { it in 1..MAX_PROJECT_VOLUMES_PER_OBJECT } &&
                objectVolumeCounts.sum() == models.size,
        ) { "Object volume counts do not match models" }
        val bedSizeX = extras.getFloat(SlicerProcessContract.KEY_BED_SIZE_X)
        val bedSizeY = extras.getFloat(SlicerProcessContract.KEY_BED_SIZE_Y)
        val bedOriginX = extras.getFloat(SlicerProcessContract.KEY_BED_ORIGIN_X)
        val bedOriginY = extras.getFloat(SlicerProcessContract.KEY_BED_ORIGIN_Y)
        val bedPolygon = requireNotNull(extras.getFloatArray(SlicerProcessContract.KEY_BED_POLYGON)) {
            "Bed geometry is unavailable"
        }.toList()
        val minimumGap = extras.getFloat(SlicerProcessContract.KEY_MINIMUM_GAP)
        require(
            bedSizeX.isFinite() && bedSizeX in MINIMUM_BED_SIZE_MM..MAXIMUM_BED_SIZE_MM &&
                bedSizeY.isFinite() && bedSizeY in MINIMUM_BED_SIZE_MM..MAXIMUM_BED_SIZE_MM &&
                bedOriginX.isFinite() && bedOriginX in -MAXIMUM_BED_SIZE_MM..MAXIMUM_BED_SIZE_MM &&
                bedOriginY.isFinite() && bedOriginY in -MAXIMUM_BED_SIZE_MM..MAXIMUM_BED_SIZE_MM &&
                bedPolygonIsValid(bedPolygon, bedSizeX, bedSizeY) &&
                minimumGap.isFinite() && minimumGap in 0f..MAXIMUM_ARRANGE_GAP_MM,
        ) { "Arrange settings are invalid" }

        val runtime = createNativeRuntime()
        try {
            loadNativeObjects(runtime, models, objectVolumeCounts)
            val sizes = runtime.getObjectBoundingBoxes()
            require(
                sizes.size == objectVolumeCounts.size * 3 &&
                    sizes.all { it.isFinite() && it > 0f },
            ) {
                "Slicer returned invalid object sizes"
            }
            val originalLowerLeft = runtime.nativeGetObjectWorldAABBMins()
            require(
                originalLowerLeft.size == objectVolumeCounts.size * 2 &&
                    originalLowerLeft.all(Float::isFinite),
            ) {
                "Slicer returned invalid source positions"
            }
            val machinePolygon = machineBedPolygon(bedPolygon, bedOriginX, bedOriginY)
            val machineLowerLeft = requireNotNull(
                runtime.nativeAutoArrangeObjects(machinePolygon.toFloatArray(), minimumGap),
            ) { "The objects do not fit on this bed" }
            require(
                machineLowerLeft.size == objectVolumeCounts.size * 2 &&
                    machineLowerLeft.all { it.isFinite() },
            ) {
                "Slicer returned an invalid arrangement"
            }
            repeat(objectVolumeCounts.size) { index ->
                val x = machineLowerLeft[index * 2]
                val y = machineLowerLeft[index * 2 + 1]
                val width = sizes[index * 3]
                val depth = sizes[index * 3 + 1]
                require(
                    x >= bedOriginX - ARRANGE_TOLERANCE_MM &&
                        y >= bedOriginY - ARRANGE_TOLERANCE_MM &&
                        x + width <= bedOriginX + bedSizeX + ARRANGE_TOLERANCE_MM &&
                        y + depth <= bedOriginY + bedSizeY + ARRANGE_TOLERANCE_MM,
                ) { "Slicer placed an object outside the bed" }
            }
            val lowerLeft = FloatArray(machineLowerLeft.size) { index ->
                machineLowerLeft[index] - if (index % 2 == 0) bedOriginX else bedOriginY
            }
            val centers = FloatArray(machineLowerLeft.size) { index ->
                machineLowerLeft[index] - originalLowerLeft[index] -
                    (if (index % 2 == 0) bedOriginX else bedOriginY)
            }
            Bundle().apply {
                putBoolean(SlicerProcessContract.KEY_OK, true)
                putInt(SlicerProcessContract.KEY_PID, Process.myPid())
                putFloatArray(SlicerProcessContract.KEY_ARRANGED_LOWER_LEFT, lowerLeft)
                putFloatArray(SlicerProcessContract.KEY_OBJECT_SIZES, sizes)
                putFloatArray(SlicerProcessContract.KEY_OBJECT_CENTERS, centers)
            }
        } finally {
            runtime.clearModel()
        }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Automatic arrangement failed", error)
        failure(error.message ?: "The objects could not be arranged")
    }

    private fun runNativeSlice(
        models: List<File>,
        supportPaintFiles: List<ValidatedSupportPaint?>,
        seamPaintFiles: List<ValidatedSeamPaint?>,
        multiColorPaintFiles: List<ValidatedMultiColorPaint?>,
        variableLayerHeightFiles: List<ValidatedVariableLayerHeights?>,
        processOverrideFiles: List<ValidatedObjectProcessOverrides?>,
        brimPointFiles: List<ValidatedBrimPoints?>,
        objectVolumeCounts: IntArray,
        filamentSlots: IntArray,
        options: SliceOptions,
        maximumGcodeBytes: Int,
        onProgress: (Int) -> Unit,
    ): SliceOutcome {
        artifactStore.prepareForSlice()
        val runtime = createNativeRuntime(onProgress)
        return try {
            val mapping = loadNativeObjects(runtime, models, objectVolumeCounts)
            val volumeObjectIndices = mapping.objectIndices
            val nativeVolumeIndices = mapping.volumeIndices
            filamentSlots.forEachIndexed { volumeIndex, slot ->
                check(
                    runtime.nativeSetVolumeExtruder(
                        volumeObjectIndices[volumeIndex],
                        nativeVolumeIndices[volumeIndex],
                        options.featureFilaments.nativeVolumeSlot(slot),
                    ),
                ) {
                    "Volume filament could not be applied"
                }
            }
            supportPaintFiles.forEachIndexed { volumeIndex, supportPaint ->
                if (supportPaint != null) {
                    check(
                        runtime.applySupportPaint(
                            volumeObjectIndices[volumeIndex],
                            nativeVolumeIndices[volumeIndex],
                            supportPaint.file.absolutePath,
                        ),
                    ) {
                        "Support paint could not be applied"
                    }
                }
            }
            seamPaintFiles.forEachIndexed { volumeIndex, seamPaint ->
                if (seamPaint != null) {
                    check(
                        runtime.applySeamPaint(
                            volumeObjectIndices[volumeIndex],
                            nativeVolumeIndices[volumeIndex],
                            seamPaint.file.absolutePath,
                        ),
                    ) {
                        "Seam paint could not be applied"
                    }
                }
            }
            multiColorPaintFiles.forEachIndexed { volumeIndex, multiColorPaint ->
                if (multiColorPaint != null) {
                    check(
                        runtime.applyMultiColorPaint(
                            volumeObjectIndices[volumeIndex],
                            nativeVolumeIndices[volumeIndex],
                            multiColorPaint.file.absolutePath,
                        ),
                    ) { "Multi-color paint could not be applied" }
                }
            }
            variableLayerHeightFiles.forEachIndexed { objectIndex, variableLayers ->
                if (variableLayers != null) {
                    check(
                        runtime.applyVariableLayerHeights(
                            objectIndex,
                            variableLayers.file.absolutePath,
                        ),
                    ) { "Variable layer heights could not be applied" }
                }
            }
            processOverrideFiles.forEachIndexed { objectIndex, overrides ->
                if (overrides != null) {
                    check(
                        runtime.applyObjectProcessOverrides(
                            objectIndex,
                            overrides.file.absolutePath,
                        ),
                    ) { "Object settings could not be applied" }
                }
            }
            brimPointFiles.forEachIndexed { objectIndex, brimPoints ->
                if (brimPoints != null) {
                    check(
                        runtime.applyBrimPoints(objectIndex, brimPoints.file.absolutePath),
                    ) { "Brim points could not be applied" }
                }
            }
            val nativeConfig = options.toNativeConfig().apply {
                this.maximumGcodeBytes = maximumGcodeBytes
                if (supportPaintFiles.any { it?.hasEnforcer == true }) {
                    this.supportEnabled = true
                    this.supportType = if (options.supportType.isTreeSupportType()) {
                        "tree(manual)"
                    } else {
                        "normal(manual)"
                    }
                }
            }
            val result = requireNotNull(runtime.slice(nativeConfig)) {
                "Slicer returned no output"
            }
            check(result.success) {
                if (result.cancelled) "Slicing was cancelled" else "Slicer could not produce output"
            }
            require(result.totalLayers > 0) { "Slicer returned an invalid layer count" }
            require(result.estimatedTimeSeconds.isFinite() && result.estimatedTimeSeconds >= 0f) {
                "Slicer returned an invalid time estimate"
            }
            require(result.estimatedFilamentMm.isFinite() && result.estimatedFilamentMm >= 0f) {
                "Slicer returned an invalid filament length estimate"
            }
            require(result.estimatedFilamentGrams.isFinite() && result.estimatedFilamentGrams >= 0f) {
                "Slicer returned an invalid filament estimate"
            }
            SliceOutcome(
                output = artifactStore.persist(File(result.gcodePath)),
                layers = result.totalLayers,
                estimatedSeconds = result.estimatedTimeSeconds,
                filamentMm = result.estimatedFilamentMm,
                filamentGrams = result.estimatedFilamentGrams,
            )
        } finally {
            runtime.clearModel()
        }
    }

    private fun loadNativeObjects(
        runtime: NativeLibrary,
        models: List<File>,
        objectVolumeCounts: IntArray,
    ): NativeVolumeMapping {
        require(
            objectVolumeCounts.isNotEmpty() &&
                objectVolumeCounts.all { it in 1..MAX_PROJECT_VOLUMES_PER_OBJECT } &&
                objectVolumeCounts.sum() == models.size,
        ) { "Object volume counts do not match models" }
        val objectIndices = IntArray(models.size)
        val volumeIndices = IntArray(models.size)
        var flatVolumeIndex = 0
        objectVolumeCounts.forEachIndexed { objectIndex, volumeCount ->
            val firstVolume = models[flatVolumeIndex]
            val loaded = if (objectIndex == 0) {
                runtime.loadModel(firstVolume.absolutePath)
            } else {
                runtime.addModel(firstVolume.absolutePath)
            }
            check(loaded) { "Model object could not be prepared" }
            objectIndices[flatVolumeIndex] = objectIndex
            volumeIndices[flatVolumeIndex] = 0
            flatVolumeIndex += 1
            repeat(volumeCount - 1) { volumeOffset ->
                objectIndices[flatVolumeIndex] = objectIndex
                val nativeVolumeIndex = runtime.nativeAddModelPartVolume(
                    objectIndex,
                    models[flatVolumeIndex].absolutePath,
                    "Part ${volumeOffset + 2}",
                )
                check(nativeVolumeIndex >= 0) { "Model volume could not be prepared" }
                volumeIndices[flatVolumeIndex] = nativeVolumeIndex
                flatVolumeIndex += 1
            }
        }
        check(flatVolumeIndex == models.size) { "Native volume count does not match the request" }
        check(runtime.getObjectBoundingBoxes().size == objectVolumeCounts.size * 3) {
            "Native model count does not match the request"
        }
        return NativeVolumeMapping(objectIndices, volumeIndices)
    }

    private fun validateModel(path: String): File {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid model path" }
        val model = File(path).canonicalFile
        val allowedRoots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
        require(allowedRoots.any(model::isInside)) { "Model is outside private storage" }
        require(model.isFile && model.length() in 1..MAX_MODEL_BYTES) { "Model is unavailable" }
        return model
    }

    private fun validateModelImportDirectory(path: String): File {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid model output path" }
        val directory = File(path).canonicalFile
        val projectRoot = File(filesDir, ProjectStore.PROJECT_DIRECTORY).canonicalFile
        val identifier = directory.name.removePrefix(ProjectStore.MODEL_IMPORT_DIRECTORY_PREFIX)
        val expectedName = runCatching { UUID.fromString(identifier).toString() }
            .getOrNull()
            ?.let { "${ProjectStore.MODEL_IMPORT_DIRECTORY_PREFIX}$it" }
        require(
            expectedName == directory.name && directory.parentFile == projectRoot &&
                directory.isDirectory && !Files.isSymbolicLink(directory.toPath())
        ) { "Model output directory is unavailable" }
        return directory
    }

    private fun createNativeRuntime(onProgress: (Int) -> Unit = {}): NativeLibrary =
        NativeLibrary(onProgress)

    private fun validateSupportPaint(path: String): ValidatedSupportPaint {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid support paint path" }
        val sidecar = File(path).canonicalFile
        val allowedRoots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
        require(allowedRoots.any(sidecar::isInside)) { "Support paint is outside private storage" }
        require(sidecar.isFile && sidecar.length() in SupportPaint.HEADER_BYTES..SupportPaint.MAX_SIDECAR_BYTES) {
            "Support paint is unavailable"
        }
        var hasEnforcer = false
        DataInputStream(sidecar.inputStream().buffered()).use { reader ->
            val magic = ByteArray(SupportPaint.MAGIC.size)
            reader.readFully(magic)
            require(magic.contentEquals(SupportPaint.MAGIC)) { "Support paint format is invalid" }
            val count = reader.readInt()
            require(count in 0..SupportPaint.MAX_PAINTED_FACETS) { "Support paint count is invalid" }
            require(sidecar.length() == SupportPaint.HEADER_BYTES + count.toLong() * SupportPaint.ENTRY_BYTES) {
                "Support paint size is invalid"
            }
            var previousIndex = -1
            repeat(count) {
                val facetIndex = reader.readInt()
                val state = reader.readUnsignedByte()
                require(facetIndex > previousIndex && SupportPaintState.fromCode(state) != null) {
                    "Support paint entry is invalid"
                }
                if (state == SupportPaintState.ENFORCE.code) hasEnforcer = true
                previousIndex = facetIndex
            }
        }
        return ValidatedSupportPaint(sidecar, hasEnforcer)
    }

    private fun validateSeamPaint(path: String): ValidatedSeamPaint {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid seam paint path" }
        val sidecar = File(path).canonicalFile
        val allowedRoots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
        require(allowedRoots.any(sidecar::isInside)) { "Seam paint is outside private storage" }
        require(sidecar.isFile && sidecar.length() in SeamPaint.HEADER_BYTES..SeamPaint.MAX_SIDECAR_BYTES) {
            "Seam paint is unavailable"
        }
        DataInputStream(sidecar.inputStream().buffered()).use { reader ->
            val magic = ByteArray(SeamPaint.MAGIC.size)
            reader.readFully(magic)
            require(magic.contentEquals(SeamPaint.MAGIC)) { "Seam paint format is invalid" }
            val count = reader.readInt()
            require(count in 0..SeamPaint.MAX_PAINTED_FACETS) { "Seam paint count is invalid" }
            require(sidecar.length() == SeamPaint.HEADER_BYTES + count.toLong() * SeamPaint.ENTRY_BYTES) {
                "Seam paint size is invalid"
            }
            var previousIndex = -1
            repeat(count) {
                val facetIndex = reader.readInt()
                val state = reader.readUnsignedByte()
                require(facetIndex > previousIndex && SeamPaintState.fromCode(state) != null) {
                    "Seam paint entry is invalid"
                }
                previousIndex = facetIndex
            }
        }
        return ValidatedSeamPaint(sidecar)
    }

    private fun validateVariableLayerHeights(path: String): ValidatedVariableLayerHeights {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid variable layer height path" }
        val sidecar = File(path).canonicalFile
        val allowedRoots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
        require(allowedRoots.any(sidecar::isInside)) {
            "Variable layer heights are outside private storage"
        }
        require(
            sidecar.isFile &&
                sidecar.length() in VariableLayerHeights.HEADER_BYTES..
                    VariableLayerHeights.MAX_SIDECAR_BYTES,
        ) { "Variable layer heights are unavailable" }
        DataInputStream(sidecar.inputStream().buffered()).use { reader ->
            val magic = ByteArray(VariableLayerHeights.MAGIC.size)
            reader.readFully(magic)
            require(magic.contentEquals(VariableLayerHeights.MAGIC)) {
                "Variable layer height format is invalid"
            }
            val count = reader.readInt()
            require(count in 0..VariableLayerHeights.MAX_RANGES) {
                "Variable layer height count is invalid"
            }
            require(
                sidecar.length() ==
                    VariableLayerHeights.HEADER_BYTES + count.toLong() * VariableLayerHeights.ENTRY_BYTES,
            ) { "Variable layer height size is invalid" }
            VariableLayerHeights(
                List(count) {
                    VariableLayerRange(reader.readFloat(), reader.readFloat(), reader.readFloat())
                },
            )
        }
        return ValidatedVariableLayerHeights(sidecar)
    }

    private fun validateBrimPoints(path: String): ValidatedBrimPoints {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid Brim point path" }
        val sidecar = File(path).canonicalFile
        val allowedRoots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
        require(allowedRoots.any(sidecar::isInside)) {
            "Brim points are outside private storage"
        }
        require(
            sidecar.isFile && sidecar.length() in BrimPoints.HEADER_BYTES..BrimPoints.MAX_SIDECAR_BYTES,
        ) { "Brim points are unavailable" }
        DataInputStream(sidecar.inputStream().buffered()).use { reader ->
            val magic = ByteArray(BrimPoints.MAGIC.size)
            reader.readFully(magic)
            require(magic.contentEquals(BrimPoints.MAGIC)) { "Brim point format is invalid" }
            val count = reader.readInt()
            require(count in 1..BrimPoints.MAX_POINTS) { "Brim point count is invalid" }
            require(sidecar.length() == BrimPoints.HEADER_BYTES + count.toLong() * BrimPoints.ENTRY_BYTES) {
                "Brim point size is invalid"
            }
            BrimPoints(
                List(count) {
                    BrimPoint(
                        xMm = reader.readFloat(),
                        yMm = reader.readFloat(),
                        zMm = reader.readFloat(),
                        radiusMm = reader.readFloat(),
                    )
                },
            )
        }
        return ValidatedBrimPoints(sidecar)
    }

    private fun validateMultiColorPaint(path: String): ValidatedMultiColorPaint {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid multi-color paint path" }
        val sidecar = File(path).canonicalFile
        val allowedRoots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
        require(allowedRoots.any(sidecar::isInside)) {
            "Multi-color paint is outside private storage"
        }
        require(
            sidecar.isFile &&
                sidecar.length() in MultiColorPaint.HEADER_BYTES..MultiColorPaint.MAX_SIDECAR_BYTES,
        ) { "Multi-color paint is unavailable" }
        val filamentSlots = HashSet<Int>()
        DataInputStream(sidecar.inputStream().buffered()).use { reader ->
            val magic = ByteArray(MultiColorPaint.MAGIC.size)
            reader.readFully(magic)
            require(magic.contentEquals(MultiColorPaint.MAGIC)) {
                "Multi-color paint format is invalid"
            }
            val count = reader.readInt()
            require(count in 0..MultiColorPaint.MAX_PAINTED_FACETS) {
                "Multi-color paint count is invalid"
            }
            require(
                sidecar.length() ==
                    MultiColorPaint.HEADER_BYTES + count.toLong() * MultiColorPaint.ENTRY_BYTES,
            ) { "Multi-color paint size is invalid" }
            var previousIndex = -1
            repeat(count) {
                val facetIndex = reader.readInt()
                val state = reader.readUnsignedByte()
                require(
                    facetIndex > previousIndex && state in 1..MAX_FILAMENT_SLOTS,
                ) { "Multi-color paint entry is invalid" }
                filamentSlots += state - 1
                previousIndex = facetIndex
            }
        }
        return ValidatedMultiColorPaint(sidecar, filamentSlots)
    }

    private fun validateObjectProcessOverrides(path: String): ValidatedObjectProcessOverrides {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid object settings path" }
        val sidecar = File(path).canonicalFile
        val allowedRoots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
        require(allowedRoots.any(sidecar::isInside)) { "Object settings are outside private storage" }
        require(sidecar.isFile && sidecar.length() == ObjectProcessOverrides.SIDECAR_BYTES) {
            "Object settings are unavailable"
        }
        DataInputStream(sidecar.inputStream().buffered()).use { reader ->
            val magic = ByteArray(ObjectProcessOverrides.MAGIC.size)
            reader.readFully(magic)
            require(magic.contentEquals(ObjectProcessOverrides.MAGIC)) {
                "Object settings format is invalid"
            }
            val mask = reader.readInt()
            require(mask != 0 && mask and ObjectProcessOverrides.ALL_BITS == mask) {
                "Object setting mask is invalid"
            }
            val values = ObjectProcessOverrides(
                layerHeightMm = reader.readFloat().takeIf {
                    mask and ObjectProcessOverrides.LAYER_HEIGHT_BIT != 0
                },
                wallLoops = reader.readInt().takeIf {
                    mask and ObjectProcessOverrides.WALL_LOOPS_BIT != 0
                },
                topShellLayers = reader.readInt().takeIf {
                    mask and ObjectProcessOverrides.TOP_SHELL_LAYERS_BIT != 0
                },
                bottomShellLayers = reader.readInt().takeIf {
                    mask and ObjectProcessOverrides.BOTTOM_SHELL_LAYERS_BIT != 0
                },
                sparseInfillDensityPercent = reader.readFloat().takeIf {
                    mask and ObjectProcessOverrides.INFILL_DENSITY_BIT != 0
                },
                outerWallSpeedMmS = reader.readFloat().takeIf {
                    mask and ObjectProcessOverrides.OUTER_WALL_SPEED_BIT != 0
                },
                innerWallSpeedMmS = reader.readFloat().takeIf {
                    mask and ObjectProcessOverrides.INNER_WALL_SPEED_BIT != 0
                },
                sparseInfillSpeedMmS = reader.readFloat().takeIf {
                    mask and ObjectProcessOverrides.INFILL_SPEED_BIT != 0
                },
                supportEnabled = reader.readUnsignedByte().let { enabled ->
                    require(enabled in 0..1) { "Object support setting is invalid" }
                    (enabled == 1).takeIf {
                        mask and ObjectProcessOverrides.SUPPORT_ENABLED_BIT != 0
                    }
                },
            )
            require(!values.isEmpty) { "Object settings are empty" }
        }
        return ValidatedObjectProcessOverrides(sidecar)
    }

    private fun success(outcome: SliceOutcome) = Bundle().apply {
        putBoolean(SlicerProcessContract.KEY_OK, true)
        putInt(SlicerProcessContract.KEY_PID, Process.myPid())
        putString(SlicerProcessContract.KEY_OUTPUT_PATH, outcome.output.absolutePath)
        putInt(SlicerProcessContract.KEY_LAYERS, outcome.layers)
        putFloat(SlicerProcessContract.KEY_ESTIMATED_SECONDS, outcome.estimatedSeconds)
        putFloat(SlicerProcessContract.KEY_FILAMENT_MM, outcome.filamentMm)
        putFloat(SlicerProcessContract.KEY_FILAMENT_GRAMS, outcome.filamentGrams)
    }

    private fun failure(message: String) = Bundle().apply {
        putBoolean(SlicerProcessContract.KEY_OK, false)
        putInt(SlicerProcessContract.KEY_PID, Process.myPid())
        putString(SlicerProcessContract.KEY_ERROR, message.take(MAX_ERROR_LENGTH))
    }

    private fun send(reply: Messenger?, what: Int, argument: Int = 0, data: Bundle = Bundle.EMPTY) {
        if (reply == null) return
        try {
            reply.send(Message.obtain(null, what, argument, 0).apply { this.data = data })
        } catch (_: RemoteException) {
            // The UI can leave while the isolated worker is completing.
        }
    }

    internal companion object {
        private const val ACTION_START_SLICE =
            "com.ashcastle.duckyslicer.action.START_FOREGROUND_SLICE"
        private const val ACTION_CANCEL_SLICE =
            "com.ashcastle.duckyslicer.action.CANCEL_FOREGROUND_SLICE"
        private const val ACTION_FINISH_SLICE =
            "com.ashcastle.duckyslicer.action.FINISH_FOREGROUND_SLICE"
        private const val EXTRA_REQUEST_ID = "foregroundRequestId"
        internal const val NOTIFICATION_CHANNEL_ID = "active_slicing"
        private const val NOTIFICATION_ID = 2_041
        private const val OPEN_APP_REQUEST_CODE = 2_042
        private const val CANCEL_REQUEST_CODE = 2_043

        fun startSliceIntent(context: Context, requestId: String): Intent =
            Intent(context, SlicerProcessService::class.java).apply {
                action = ACTION_START_SLICE
                putExtra(EXTRA_REQUEST_ID, requestId)
            }

        fun cancelSliceIntent(context: Context, requestId: String): Intent =
            Intent(context, SlicerProcessService::class.java).apply {
                action = ACTION_CANCEL_SLICE
                data = Uri.Builder()
                    .scheme("duckyslicer")
                    .authority("slice-cancel")
                    .appendPath(requestId)
                    .build()
                putExtra(EXTRA_REQUEST_ID, requestId)
            }

        fun finishSliceIntent(context: Context, requestId: String): Intent =
            Intent(context, SlicerProcessService::class.java).apply {
                action = ACTION_FINISH_SLICE
                putExtra(EXTRA_REQUEST_ID, requestId)
            }

        const val MAX_OBJECTS = 256
        const val MINIMUM_BED_SIZE_MM = 1f
        const val MAXIMUM_BED_SIZE_MM = 10_000f
        const val MAXIMUM_ARRANGE_GAP_MM = 100f
        const val ARRANGE_TOLERANCE_MM = 0.05f
        const val MAX_PATH_LENGTH = 1_024
        const val MAX_MODEL_BYTES = 512L * 1_024 * 1_024
        const val MINIMUM_SIMPLIFIED_TRIANGLES = 4
        const val MAXIMUM_SIMPLIFIED_TRIANGLES = 10_000_000
        const val MAX_ERROR_LENGTH = 500
        const val MAX_REQUEST_ID_LENGTH = 128
        // Give both the active request and cancellation bindings time to consume their
        // replies and unbind before terminating native work. Killing while a binding is
        // still registered makes Android classify the intentional stop as a crash and
        // defer the next worker connection behind its 30-second restart backoff.
        const val CANCEL_PROCESS_DELAY_MILLIS = 1_000L
        const val TEST_PROBE_DURATION_MILLIS = 30_000L
        const val STORAGE_GUARD_INTERVAL_MILLIS = 500L
        const val FOREGROUND_COMPLETION_GRACE_MILLIS = 10L * 60L * 1_000L
        const val TEST_MINIMUM_GCODE_BYTES = 16 * 1_024
        const val PRODUCTION_MAXIMUM_GCODE_BYTES = 1_073_741_824
        const val LOG_TAG = "DuckySlicer"
    }

    private enum class WorkOperation {
        SLICE,
        AUTO_ORIENT,
        AUTO_ARRANGE,
        NORMALIZE_MODEL,
        SPLIT_MODEL,
        SPLIT_MODEL_VOLUME,
        CUT_MODEL,
        SIMPLIFY_MODEL,
        CREATE_PRIMITIVE,
        TEST_PROBE,
    }

    private data class ValidatedSupportPaint(
        val file: File,
        val hasEnforcer: Boolean,
    )

    private data class ValidatedSeamPaint(val file: File)

    private data class ValidatedMultiColorPaint(
        val file: File,
        val filamentSlots: Set<Int>,
    )

    private data class ValidatedVariableLayerHeights(val file: File)

    private data class ValidatedBrimPoints(val file: File)

    private data class ValidatedObjectProcessOverrides(val file: File)

    private data class NativeVolumeMapping(
        val objectIndices: IntArray,
        val volumeIndices: IntArray,
    )
}

private object SlicerProcessContract {
    const val MESSAGE_SLICE = 1
    const val MESSAGE_HEALTH = 2
    const val MESSAGE_TERMINATE_FOR_TEST = 3
    const val MESSAGE_PROGRESS = 4
    const val MESSAGE_RESULT = 5
    const val MESSAGE_CANCEL = 6
    const val MESSAGE_BLOCK_FOR_TEST = 7
    const val MESSAGE_AUTO_ORIENT = 8
    const val MESSAGE_AUTO_ARRANGE = 9
    const val MESSAGE_ATTACH = 10
    const val MESSAGE_NORMALIZE_MODEL = 11
    const val MESSAGE_SPLIT_MODEL = 12
    const val MESSAGE_CUT_MODEL = 13
    const val MESSAGE_CREATE_PRIMITIVE = 14
    const val MESSAGE_SIMPLIFY_MODEL = 15
    const val MESSAGE_SPLIT_MODEL_VOLUME = 16
    const val KEY_REQUEST_ID = "requestId"
    const val KEY_MODEL_PATH = "modelPath"
    const val KEY_MODEL_PATHS = "modelPaths"
    const val KEY_MODEL_OUTPUT_DIRECTORY = "modelOutputDirectory"
    const val KEY_NORMALIZED_MODELS = "normalizedModels"
    const val KEY_OBJECT_VOLUME_COUNTS = "objectVolumeCounts"
    const val KEY_FILAMENT_SLOTS = "filamentSlots"
    const val KEY_MODEL_NOT_SPLITTABLE = "modelNotSplittable"
    const val KEY_MODEL_NOT_CUTTABLE = "modelNotCuttable"
    const val KEY_CUT_HEIGHT_RATIO = "cutHeightRatio"
    const val KEY_PLACE_ON_CUT = "placeOnCut"
    const val KEY_TARGET_TRIANGLES = "targetTriangles"
    const val KEY_VOLUME_INDEX = "volumeIndex"
    const val KEY_PRIMITIVE_TYPE = "primitiveType"
    const val KEY_PRIMITIVE_SIZE_MM = "primitiveSizeMm"
    const val KEY_SUPPORT_PAINT_PATHS = "supportPaintPaths"
    const val KEY_SEAM_PAINT_PATHS = "seamPaintPaths"
    const val KEY_MULTI_COLOR_PAINT_PATHS = "multiColorPaintPaths"
    const val KEY_VARIABLE_LAYER_HEIGHT_PATHS = "variableLayerHeightPaths"
    const val KEY_PROCESS_OVERRIDE_PATHS = "processOverridePaths"
    const val KEY_BRIM_POINT_PATHS = "brimPointPaths"
    const val KEY_OPTIONS = "options"
    const val KEY_MAXIMUM_GCODE_BYTES_FOR_TEST = "maximumGcodeBytesForTest"
    const val KEY_OK = "ok"
    const val KEY_CANCELED = "canceled"
    const val KEY_ERROR = "error"
    const val KEY_PID = "pid"
    const val KEY_OUTPUT_PATH = "outputPath"
    const val KEY_LAYERS = "layers"
    const val KEY_ESTIMATED_SECONDS = "estimatedSeconds"
    const val KEY_FILAMENT_MM = "filamentMm"
    const val KEY_FILAMENT_GRAMS = "filamentGrams"
    const val KEY_ROTATION_RADIANS = "rotationRadians"
    const val KEY_BED_SIZE_X = "bedSizeX"
    const val KEY_BED_SIZE_Y = "bedSizeY"
    const val KEY_BED_ORIGIN_X = "bedOriginX"
    const val KEY_BED_ORIGIN_Y = "bedOriginY"
    const val KEY_BED_POLYGON = "bedPolygon"
    const val KEY_MINIMUM_GAP = "minimumGap"
    const val KEY_ARRANGED_LOWER_LEFT = "arrangedLowerLeft"
    const val KEY_OBJECT_SIZES = "objectSizes"
    const val KEY_OBJECT_CENTERS = "objectCenters"
    const val OUTPUT_DIRECTORY = SliceArtifactStore.OUTPUT_DIRECTORY
    const val MAX_OPTIONS_BYTES = 384 * 1_024
    const val MAX_REQUEST_BYTES = 640 * 1_024
}

private fun File.isInside(root: File): Boolean = this == root || path.startsWith(root.path + File.separator)

private fun encodedRequestBytes(paths: List<String>, options: String): Long =
    paths.fold(options.toByteArray(Charsets.UTF_8).size.toLong()) { total, path ->
        total + path.toByteArray(Charsets.UTF_8).size
    }
