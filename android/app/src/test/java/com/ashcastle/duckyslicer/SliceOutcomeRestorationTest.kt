package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceOutcomeRestorationTest {
    @Test
    fun inputFilenameBaseDropsTheSourceExtensionWithoutKeepingItsPath() {
        assertEquals("bench model.v2", safeGcodeInputFilenameBase("../imports/bench model.v2.stl"))
        assertEquals("hidden", safeGcodeInputFilenameBase(".hidden"))
        assertEquals("model", safeGcodeInputFilenameBase(".."))
    }

    @Test
    fun retainedPrivateOutputCanBeRestoredAfterConfigurationChange() = withRoot { root ->
        val output = root.resolve(SliceArtifactStore.OUTPUT_DIRECTORY)
            .apply(File::mkdirs)
            .resolve("restored.gcode")
            .apply { writeText("G1 X1 Y1\n") }

        assertTrue(outcome(output).isRestorableFrom(root))
    }

    @Test
    fun missingOrOutsideOutputCannotBeRestored() = withRoot { root ->
        val missing = root.resolve(SliceArtifactStore.OUTPUT_DIRECTORY).resolve("missing.gcode")
        val outside = root.resolve("outside.gcode").apply { writeText("G1 X1\n") }

        assertFalse(outcome(missing).isRestorableFrom(root))
        assertFalse(outcome(outside).isRestorableFrom(root))
    }

    @Test
    fun invalidStatisticsCannotReenterPreviewState() = withRoot { root ->
        val output = root.resolve(SliceArtifactStore.OUTPUT_DIRECTORY)
            .apply(File::mkdirs)
            .resolve("invalid.gcode")
            .apply { writeText("G1 X1 Y1\n") }

        assertFalse(outcome(output).copy(estimatedSeconds = Float.NaN).isRestorableFrom(root))
        assertFalse(outcome(output).copy(filamentMm = -1f).isRestorableFrom(root))
        assertFalse(outcome(output).copy(layers = 0).isRestorableFrom(root))
        assertFalse(
            outcome(output).copy(suggestedName = "../outside.gcode").isRestorableFrom(root),
        )
    }

    @Test
    fun gcodeDocumentNamesArePathFreeBoundedAndHaveOneSuffix() {
        assertEquals("duck.gcode", safeGcodeFileName("../../duck.gcode.gcode"))
        assertEquals("duck_bad_name.gcode", safeGcodeFileName("duck:bad?name.GCODE"))
        assertEquals("safe_name.gcode", safeGcodeFileName("safe\u202Ename.gcode"))
        assertEquals("safe_name.gcode", safeGcodeFileName("safe\u0085name.gcode"))
        assertEquals("model.gcode", safeGcodeFileName("..."))
        val unicode = safeGcodeFileName("오리🦆".repeat(100))
        assertTrue(unicode.toByteArray(Charsets.UTF_8).size <= 186)
        assertTrue(unicode.endsWith(".gcode"))
    }

    private fun outcome(output: File) = SliceOutcome(
        output = output,
        layers = 100,
        estimatedSeconds = 840f,
        filamentMm = 1_190f,
        filamentGrams = 3.6f,
    )

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("duckyslicer-outcome-restore").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
