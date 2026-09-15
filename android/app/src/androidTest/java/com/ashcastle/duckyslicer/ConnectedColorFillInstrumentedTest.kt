package com.ashcastle.duckyslicer

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectedColorFillInstrumentedTest {
    private fun source(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "connected-fill-test.stl").apply {
            writeText("""
                solid test
                facet normal 0 0 1
                outer loop
                vertex 0 0 0
                vertex 1 0 0
                vertex 0 1 0
                endloop
                endfacet
                facet normal 0 0 1
                outer loop
                vertex 1 0 0
                vertex 1 1 0
                vertex 0 1 0
                endloop
                endfacet
                facet normal -1 0 0
                outer loop
                vertex 1 0 0
                vertex 1 0 1
                vertex 1 1 0
                endloop
                endfacet
                endsolid test
            """.trimIndent())
        }
    }

    @Test fun nativeSelectionUsesSourceEdgesAndRejectsInvalidSeed() {
        val file = source()
        try {
            fun select(seed: Int) = JSONObject(NativeEngine.selectConnectedFacets(JSONObject()
                .put("path", file.absolutePath).put("seed", seed).put("angleDegrees", 30).toString()))
            assertEquals("[0,1]", select(0).getJSONArray("facets").toString())
            assertTrue(select(100).has("error"))
        } finally { file.delete() }
    }

    @Test fun connectedFillCommitsOneUndoableProjectEdit() {
        val file = source()
        try {
            ActivityScenario.launch(AccessibilityHarnessActivity::class.java).use { scenario ->
                lateinit var model: ProjectTransferViewModel
                scenario.onActivity { model = ViewModelProvider(it)[ProjectTransferViewModel::class.java] }
                await { model.state.value.restored && !model.state.value.busy }
                val initial = model.state.value.history
                try {
                    scenario.onActivity {
                        model.state.value.editCompletion?.let { model.consumeEditCompletion(it.id) }
                        val part = ProjectObject("connected-fill-test", ModelInfo.fromNative(
                            NativeEngine.inspectStlPayload(file.absolutePath), file.absolutePath))
                        assertTrue(model.updateHistory(initial, initial.add(part)))
                    }
                    val before = model.state.value.history
                    val part = requireNotNull(before.current.selectedObject)
                    scenario.onActivity {
                        assertTrue(model.fillConnectedColor(part.id, part.singleVolume.id, 0, 0, 30f))
                    }
                    await { model.state.value.editCompletion != null }
                    assertNull(model.state.value.editCompletion?.failure)
                    val after = model.state.value.history
                    assertEquals(mapOf(0 to "4", 1 to "4"),
                        after.current.selectedObject!!.singleVolume.orcaFacetAnnotations.multiColor.triangles)
                    assertEquals(before.current, after.undo().current)
                    assertEquals(after.current, after.undo().redo().current)
                } finally {
                    scenario.onActivity {
                        model.state.value.editCompletion?.let { model.consumeEditCompletion(it.id) }
                        assertTrue(model.updateHistory(model.state.value.history, initial))
                    }
                }
            }
        } finally { file.delete() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Fill did not settle" }
            android.os.SystemClock.sleep(20)
        }
    }
}
