package com.ashcastle.duckyslicer

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shell-restricted Debug activity used only by the local UI-process death gate. */
class ProcessRecoveryHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG)
        setContentView(TextView(this).apply { text = "DuckySlicer process recovery test" })
        val operation = ViewModelProvider(this)[SliceOperationViewModel::class.java]
        val readyFile = File(filesDir, READY_FILE_NAME)
        readyFile.delete()
        lifecycleScope.launch {
            runCatching {
                val projectObject = withContext(Dispatchers.IO) {
                    val modelFile = createCubeStl(File(cacheDir, "process-recovery-cube.stl"))
                    ProjectObject(
                        id = "process-recovery",
                        model = ModelInfo.fromJson(
                            NativeEngine.inspectStl(modelFile.absolutePath),
                            modelFile.absolutePath,
                        ),
                        transform = ModelTransform(scale = 1.5f),
                    )
                }
                check(
                    operation.start(
                        legacyProjectPlateId(),
                        listOf(projectObject),
                        SliceOptions().copy(
                            layerHeight = 0.02f,
                            firstLayerHeight = 0.04f,
                            perimeters = 6,
                            fillDensity = 0.50f,
                        ),
                    ),
                ) { "Process recovery slice could not start" }
                val deadline = SystemClock.elapsedRealtime() + ACTIVE_TIMEOUT_MILLIS
                while (
                    operation.state.value.slicing &&
                    (
                        operation.state.value.progress <= 0 ||
                            !SlicerProcessClient.workerIsForegroundForTest(this@ProcessRecoveryHarnessActivity)
                    ) &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    delay(20)
                }
                check(operation.state.value.slicing) { "Process recovery slice finished too early" }
                check(operation.state.value.progress > 0) { "Process recovery slice made no progress" }
                check(SlicerProcessClient.workerIsForegroundForTest(this@ProcessRecoveryHarnessActivity)) {
                    "Process recovery slice is not foreground"
                }
                val record = requireNotNull(
                    withContext(Dispatchers.IO) {
                        ForegroundSliceStore.load(this@ProcessRecoveryHarnessActivity)
                    },
                ) { "Process recovery checkpoint is unavailable" }
                check(record.phase == ForegroundSlicePhase.ACTIVE) {
                    "Process recovery checkpoint is no longer active"
                }
                writeReady(readyFile, "${record.requestId}\n${Process.myPid()}\n")
            }.onFailure { failure ->
                writeReady(
                    readyFile,
                    "ERROR\n${failure.message.orEmpty().replace('\n', ' ').take(300)}\n",
                )
            }
        }
    }

    private fun writeReady(destination: File, value: String) {
        FileOutputStream(destination).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun createCubeStl(destination: File): File {
        data class Vertex(val x: Float, val y: Float, val z: Float)
        val low = 0f
        val high = 20f
        fun vertex(x: Float, y: Float, z: Float) = Vertex(x, y, z)
        val facets = mutableListOf<List<Vertex>>()
        fun quad(a: Vertex, b: Vertex, c: Vertex, d: Vertex) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }
        quad(vertex(low, low, low), vertex(high, low, low), vertex(high, low, high), vertex(low, low, high))
        quad(vertex(high, low, low), vertex(high, high, low), vertex(high, high, high), vertex(high, low, high))
        quad(vertex(high, high, low), vertex(low, high, low), vertex(low, high, high), vertex(high, high, high))
        quad(vertex(low, high, low), vertex(low, low, low), vertex(low, low, high), vertex(low, high, high))
        quad(vertex(low, low, high), vertex(high, low, high), vertex(high, high, high), vertex(low, high, high))
        quad(vertex(low, high, low), vertex(high, high, low), vertex(high, low, low), vertex(low, low, low))
        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid process_recovery_cube")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid process_recovery_cube")
        }
        return destination
    }

    private companion object {
        const val READY_FILE_NAME = "process-recovery.ready"
        const val ACTIVE_TIMEOUT_MILLIS = 20_000L
    }
}
