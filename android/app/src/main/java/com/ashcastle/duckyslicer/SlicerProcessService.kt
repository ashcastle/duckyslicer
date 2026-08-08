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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object SlicerProcessClient {
    @Volatile
    private var latestWorkerPid = 0

    private val replyThread by lazy {
        HandlerThread("DuckySlicer replies").apply { start() }
    }

    fun slice(
        transformedModels: List<File>,
        options: SliceOptions,
        onProgress: (Int) -> Unit,
    ): SliceOutcome {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Slicing must run outside the application main thread"
        }
        val context = DuckySlicerApplication.context()
        val modelPaths = transformedModels.map(File::getAbsolutePath)
        val optionsText = options.toProjectJson().toString()
        require(encodedRequestBytes(modelPaths, optionsText) <= SlicerProcessContract.MAX_REQUEST_BYTES) {
            "Slice request is too large"
        }
        val request = Bundle().apply {
            putStringArrayList(
                SlicerProcessContract.KEY_MODEL_PATHS,
                ArrayList(modelPaths),
            )
            putString(SlicerProcessContract.KEY_OPTIONS, optionsText)
        }
        val response = withWorker(context) { worker ->
            worker.request(
                what = SlicerProcessContract.MESSAGE_SLICE,
                data = request,
                timeoutSeconds = SLICE_TIMEOUT_SECONDS,
                onProgress = onProgress,
            )
        }
        check(response.getBoolean(SlicerProcessContract.KEY_OK)) {
            response.getString(SlicerProcessContract.KEY_ERROR) ?: "Slicer process returned no result"
        }
        latestWorkerPid = response.getInt(SlicerProcessContract.KEY_PID)
        val output = validateOutput(context, response.getString(SlicerProcessContract.KEY_OUTPUT_PATH))
        return SliceOutcome(
            output = output,
            layers = response.getInt(SlicerProcessContract.KEY_LAYERS),
            estimatedSeconds = response.getFloat(SlicerProcessContract.KEY_ESTIMATED_SECONDS),
            filamentGrams = response.getFloat(SlicerProcessContract.KEY_FILAMENT_GRAMS),
        )
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
                check(completed.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    "Slicer service request timed out"
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
}

class SlicerProcessService : Service() {
    private val messenger by lazy {
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                handleMessage(message)
                true
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handleMessage(message: Message) {
        when (message.what) {
            SlicerProcessContract.MESSAGE_SLICE -> {
                val result = runSlice(message.data) { percent ->
                    send(message.replyTo, SlicerProcessContract.MESSAGE_PROGRESS, percent)
                }
                send(message.replyTo, SlicerProcessContract.MESSAGE_RESULT, data = result)
            }
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
            else -> send(
                message.replyTo,
                SlicerProcessContract.MESSAGE_RESULT,
                data = failure("Unsupported slicer operation"),
            )
        }
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
        success(runNativeSlice(models, options, onProgress))
    } catch (error: Exception) {
        failure(error.message ?: "Slicer operation failed")
    }

    private fun runNativeSlice(
        models: List<File>,
        options: SliceOptions,
        onProgress: (Int) -> Unit,
    ): SliceOutcome {
        val runtime = NativeLibrary(onProgress)
        return try {
            check(runtime.loadModel(models.first().absolutePath)) { "Model could not be prepared" }
            models.drop(1).forEach { model ->
                check(runtime.addModel(model.absolutePath)) { "Additional model could not be prepared" }
            }
            check(runtime.getObjectBoundingBoxes().size == models.size * 3) {
                "Native model count does not match the request"
            }
            val result = requireNotNull(runtime.slice(options.toNativeConfig())) {
                "Slicer returned no output"
            }
            check(result.success) {
                if (result.cancelled) "Slicing was cancelled" else "Slicer could not produce output"
            }
            SliceOutcome(
                output = persistOutput(File(result.gcodePath)),
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

    private fun persistOutput(nativeOutput: File): File {
        require(nativeOutput.isFile && nativeOutput.length() > 0L) { "G-code output is unavailable" }
        FileOutputStream(nativeOutput, true).use { output -> output.fd.sync() }
        val outputRoot = File(filesDir, SlicerProcessContract.OUTPUT_DIRECTORY)
        check(outputRoot.isDirectory || outputRoot.mkdirs()) { "G-code storage is unavailable" }
        outputRoot.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty().forEach(File::delete)
        val output = File(outputRoot, "${System.currentTimeMillis()}-${UUID.randomUUID()}.gcode")
        if (!nativeOutput.renameTo(output)) {
            val temporary = File(outputRoot, ".${output.name}.tmp")
            try {
                FileInputStream(nativeOutput).use { input ->
                    FileOutputStream(temporary).use { sink ->
                        input.copyTo(sink)
                        sink.flush()
                        sink.fd.sync()
                    }
                }
                check(temporary.renameTo(output)) { "G-code could not be finalized" }
                nativeOutput.delete()
            } finally {
                temporary.delete()
            }
        }
        check(output.isFile && output.length() > 0L) { "G-code output is unavailable" }
        outputRoot.listFiles { file -> file.isFile && file.extension == "gcode" }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_OUTPUTS)
            .forEach(File::delete)
        return output
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
        const val MAX_RETAINED_OUTPUTS = 8
        const val MAX_ERROR_LENGTH = 500
    }
}

private object SlicerProcessContract {
    const val MESSAGE_SLICE = 1
    const val MESSAGE_HEALTH = 2
    const val MESSAGE_TERMINATE_FOR_TEST = 3
    const val MESSAGE_PROGRESS = 4
    const val MESSAGE_RESULT = 5
    const val KEY_MODEL_PATHS = "modelPaths"
    const val KEY_OPTIONS = "options"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_PID = "pid"
    const val KEY_OUTPUT_PATH = "outputPath"
    const val KEY_LAYERS = "layers"
    const val KEY_ESTIMATED_SECONDS = "estimatedSeconds"
    const val KEY_FILAMENT_GRAMS = "filamentGrams"
    const val OUTPUT_DIRECTORY = "slices"
    const val MAX_OPTIONS_BYTES = 384 * 1_024
    const val MAX_REQUEST_BYTES = 640 * 1_024
}

private fun File.isInside(root: File): Boolean = this == root || path.startsWith(root.path + File.separator)

private fun encodedRequestBytes(paths: List<String>, options: String): Long =
    paths.fold(options.toByteArray(Charsets.UTF_8).size.toLong()) { total, path ->
        total + path.toByteArray(Charsets.UTF_8).size
    }
