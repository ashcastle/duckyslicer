package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceOutcomeRestorationTest {
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
