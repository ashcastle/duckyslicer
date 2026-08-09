package com.ashcastle.duckyslicer

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.u1.slicer.NativeLibrary
import java.io.File
import java.util.UUID
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

    private val replyThread by lazy {
        HandlerThread("DuckySlicer replies").apply { start() }
    }

    fun slice(
        transformedModels: List<File>,
        options: SliceOptions,
        onProgress: (Int) -> Unit,
    ): SliceOutcome = sliceInternal(transformedModels, options, null, onProgress)

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
        return sliceInternal(transformedModels, options, maximumGcodeBytes, onProgress)
    }

    private fun sliceInternal(
        transformedModels: List<File>,
        options: SliceOptions,
        maximumGcodeBytesForTest: Int?,
        onProgress: (Int) -> Unit,
    ): SliceOutcome {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Slicing must run outside the application main thread"
        }
        val context = DuckySlicerApplication.context()
        val requestId = UUID.randomUUID().toString()
        val modelPaths = transformedModels.map(File::getAbsolutePath)
        val optionsText = options.toProjectJson().toString()
        require(encodedRequestBytes(modelPaths, optionsText) <= SlicerProcessContract.MAX_REQUEST_BYTES) {
            "Slice request is too large"
        }
        val request = Bundle().apply {
            putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
            putStringArrayList(
                SlicerProcessContract.KEY_MODEL_PATHS,
                ArrayList(modelPaths),
            )
            putString(SlicerProcessContract.KEY_OPTIONS, optionsText)
            maximumGcodeBytesForTest?.let {
                putInt(SlicerProcessContract.KEY_MAXIMUM_GCODE_BYTES_FOR_TEST, it)
            }
        }
        check(activeRequestId.compareAndSet(null, requestId)) {
            "Another slice is already running"
        }
        try {
            val response = withWorker(context) { worker ->
                worker.request(
                    what = SlicerProcessContract.MESSAGE_SLICE,
                    data = request,
                    timeoutSeconds = SLICE_TIMEOUT_SECONDS,
                    onProgress = onProgress,
                )
            }
            check(response.getBoolean(SlicerProcessContract.KEY_OK)) {
                response.getString(SlicerProcessContract.KEY_ERROR)
                    ?: "Slicer process returned no result"
            }
            latestWorkerPid = response.getInt(SlicerProcessContract.KEY_PID)
            val output = validateOutput(context, response.getString(SlicerProcessContract.KEY_OUTPUT_PATH))
            return SliceOutcome(
                output = output,
                layers = response.getInt(SlicerProcessContract.KEY_LAYERS),
                estimatedSeconds = response.getFloat(SlicerProcessContract.KEY_ESTIMATED_SECONDS),
                filamentGrams = response.getFloat(SlicerProcessContract.KEY_FILAMENT_GRAMS),
            )
        } catch (failure: Exception) {
            if (cancelledRequestId.get() == requestId) throw SlicingCancelledException()
            throw failure
        } finally {
            activeRequestId.compareAndSet(requestId, null)
            cancelledRequestId.compareAndSet(requestId, null)
        }
    }

    /** Cancels only the currently active request by terminating its isolated worker. */
    fun cancelActiveSlice(): Boolean {
        val requestId = activeRequestId.get() ?: return false
        cancelledRequestId.set(requestId)
        return runCatching {
            val response = withWorker(DuckySlicerApplication.context()) { worker ->
                worker.request(
                    what = SlicerProcessContract.MESSAGE_CANCEL,
                    data = Bundle().apply {
                        putString(SlicerProcessContract.KEY_REQUEST_ID, requestId)
                    },
                    timeoutSeconds = CONNECTION_TIMEOUT_SECONDS,
                )
            }
            response.getBoolean(SlicerProcessContract.KEY_OK)
        }.getOrDefault(activeRequestId.get() != requestId)
    }

    fun cancelActiveSliceAsync() {
        if (activeRequestId.get() == null) return
        Thread({ cancelActiveSlice() }, "DuckySlicer cancellation").apply {
            isDaemon = true
            start()
        }
    }

    internal fun lastWorkerPid(): Int = latestWorkerPid

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

    internal fun cancellationProbeForTest(onStarted: () -> Unit) {
        check(BuildConfig.DEBUG) { "Cancellation probe is available only in debug builds" }
        val requestId = UUID.randomUUID().toString()
        check(activeRequestId.compareAndSet(null, requestId)) {
            "Another slice is already running"
        }
        try {
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
    private const val SLICE_TIMEOUT_SECONDS = 30L * 60L
    private const val TEST_PROBE_TIMEOUT_SECONDS = 60L
    private const val TEST_MINIMUM_GCODE_BYTES = 16 * 1_024
    private const val PRODUCTION_MAXIMUM_GCODE_BYTES = 1_073_741_824
}

internal class SlicingCancelledException : Exception("Slicing was cancelled")

class SlicerProcessService : Service() {
    private val activeRequestId = AtomicReference<String?>(null)
    private val cancelledRequestId = AtomicReference<String?>(null)
    private val sliceThreadDelegate = lazy {
        HandlerThread("DuckySlicer Orca work").apply { start() }
    }
    private val sliceThread by sliceThreadDelegate
    private val sliceHandler by lazy { Handler(sliceThread.looper) }
    private val mainHandler = Handler(Looper.getMainLooper()) { message ->
        handleMessage(message)
        true
    }
    private val messenger = Messenger(mainHandler)
    private val artifactStore by lazy {
        SliceArtifactStore(filesDir, transientRoots = listOf(filesDir, cacheDir))
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { artifactStore.recover() }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        val abandonedRequestId = activeRequestId.get()
        if (abandonedRequestId != null) {
            mainHandler.post {
                if (activeRequestId.get() == abandonedRequestId) {
                    Process.killProcess(Process.myPid())
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeRequestId.get() != null) {
            Process.killProcess(Process.myPid())
        } else if (sliceThreadDelegate.isInitialized()) {
            sliceThread.quitSafely()
        }
    }

    private fun handleMessage(message: Message) {
        when (message.what) {
            SlicerProcessContract.MESSAGE_SLICE -> startWork(message, testProbe = false)
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
                    startWork(message, testProbe = true)
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

    private fun startWork(message: Message, testProbe: Boolean) {
        val requestId = message.data.getString(SlicerProcessContract.KEY_REQUEST_ID)
        if (requestId == null || requestId.length !in 1..MAX_REQUEST_ID_LENGTH) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Slice request id is invalid"),
            )
            return
        }
        if (!activeRequestId.compareAndSet(null, requestId)) {
            send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Another slice is already running"),
            )
            return
        }
        cancelledRequestId.set(null)
        val requestData = Bundle(message.data)
        val reply = message.replyTo
        val accepted = sliceHandler.post {
            val result = if (testProbe) {
                runCancellationProbe(reply)
            } else {
                runSlice(requestData) { percent ->
                    send(reply, SlicerProcessContract.MESSAGE_PROGRESS, percent)
                }
            }
            if (cancelledRequestId.get() == requestId) return@post
            if (activeRequestId.compareAndSet(requestId, null)) {
                send(reply, SlicerProcessContract.MESSAGE_RESULT, data = result)
            }
        }
        if (accepted && !testProbe) scheduleStorageGuard(requestId)
        if (!accepted) {
            activeRequestId.compareAndSet(requestId, null)
            send(
                reply,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Slicer worker thread is unavailable"),
            )
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
        val optionsText = requireNotNull(extras.getString(SlicerProcessContract.KEY_OPTIONS)) {
            "Slice settings are unavailable"
        }
        require(optionsText.toByteArray(Charsets.UTF_8).size <= SlicerProcessContract.MAX_OPTIONS_BYTES) {
            "Slice settings are too large"
        }
        require(encodedRequestBytes(paths, optionsText) <= SlicerProcessContract.MAX_REQUEST_BYTES) {
            "Slice request is too large"
        }
        val options = requireNotNull(JSONObject(optionsText).toProjectSliceOptionsOrNull()) {
            "Slice settings are invalid"
        }
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
        success(runNativeSlice(models, options, maximumGcodeBytes, onProgress))
    } catch (error: Exception) {
        failure(error.message ?: "Slicer operation failed")
    }

    private fun runNativeSlice(
        models: List<File>,
        options: SliceOptions,
        maximumGcodeBytes: Int,
        onProgress: (Int) -> Unit,
    ): SliceOutcome {
        artifactStore.prepareForSlice()
        val runtime = NativeLibrary(onProgress)
        return try {
            check(runtime.loadModel(models.first().absolutePath)) { "Model could not be prepared" }
            models.drop(1).forEach { model ->
                check(runtime.addModel(model.absolutePath)) { "Additional model could not be prepared" }
            }
            check(runtime.getObjectBoundingBoxes().size == models.size * 3) {
                "Native model count does not match the request"
            }
            val nativeConfig = options.toNativeConfig().apply {
                this.maximumGcodeBytes = maximumGcodeBytes
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
            require(result.estimatedFilamentGrams.isFinite() && result.estimatedFilamentGrams >= 0f) {
                "Slicer returned an invalid filament estimate"
            }
            SliceOutcome(
                output = artifactStore.persist(File(result.gcodePath)),
                layers = result.totalLayers,
                estimatedSeconds = result.estimatedTimeSeconds,
                filamentGrams = result.estimatedFilamentGrams,
            )
        } finally {
            runtime.clearModel()
        }
    }

    private fun validateModel(path: String): File {
        require(path.length in 1..MAX_PATH_LENGTH) { "Invalid model path" }
        val model = File(path).canonicalFile
        val allowedRoots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
        require(allowedRoots.any(model::isInside)) { "Model is outside private storage" }
        require(model.isFile && model.length() in 1..MAX_MODEL_BYTES) { "Model is unavailable" }
        return model
    }

    private fun success(outcome: SliceOutcome) = Bundle().apply {
        putBoolean(SlicerProcessContract.KEY_OK, true)
        putInt(SlicerProcessContract.KEY_PID, Process.myPid())
        putString(SlicerProcessContract.KEY_OUTPUT_PATH, outcome.output.absolutePath)
        putInt(SlicerProcessContract.KEY_LAYERS, outcome.layers)
        putFloat(SlicerProcessContract.KEY_ESTIMATED_SECONDS, outcome.estimatedSeconds)
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

    private companion object {
        const val MAX_OBJECTS = 256
        const val MAX_PATH_LENGTH = 1_024
        const val MAX_MODEL_BYTES = 512L * 1_024 * 1_024
        const val MAX_ERROR_LENGTH = 500
        const val MAX_REQUEST_ID_LENGTH = 128
        const val CANCEL_PROCESS_DELAY_MILLIS = 50L
        const val TEST_PROBE_DURATION_MILLIS = 30_000L
        const val STORAGE_GUARD_INTERVAL_MILLIS = 500L
        const val TEST_MINIMUM_GCODE_BYTES = 16 * 1_024
        const val PRODUCTION_MAXIMUM_GCODE_BYTES = 1_073_741_824
    }
}

private object SlicerProcessContract {
    const val MESSAGE_SLICE = 1
    const val MESSAGE_HEALTH = 2
    const val MESSAGE_TERMINATE_FOR_TEST = 3
    const val MESSAGE_PROGRESS = 4
    const val MESSAGE_RESULT = 5
    const val MESSAGE_CANCEL = 6
    const val MESSAGE_BLOCK_FOR_TEST = 7
    const val KEY_REQUEST_ID = "requestId"
    const val KEY_MODEL_PATHS = "modelPaths"
    const val KEY_OPTIONS = "options"
    const val KEY_MAXIMUM_GCODE_BYTES_FOR_TEST = "maximumGcodeBytesForTest"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_PID = "pid"
    const val KEY_OUTPUT_PATH = "outputPath"
    const val KEY_LAYERS = "layers"
    const val KEY_ESTIMATED_SECONDS = "estimatedSeconds"
    const val KEY_FILAMENT_GRAMS = "filamentGrams"
    const val OUTPUT_DIRECTORY = SliceArtifactStore.OUTPUT_DIRECTORY
    const val MAX_OPTIONS_BYTES = 384 * 1_024
    const val MAX_REQUEST_BYTES = 640 * 1_024
}

private fun File.isInside(root: File): Boolean = this == root || path.startsWith(root.path + File.separator)

private fun encodedRequestBytes(paths: List<String>, options: String): Long =
    paths.fold(options.toByteArray(Charsets.UTF_8).size.toLong()) { total, path ->
        total + path.toByteArray(Charsets.UTF_8).size
    }
