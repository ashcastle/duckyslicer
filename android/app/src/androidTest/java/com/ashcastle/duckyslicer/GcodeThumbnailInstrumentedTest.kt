package com.ashcastle.duckyslicer

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class GcodeThumbnailInstrumentedTest {
    @Test
    fun configuredPngThumbnailIsEmbeddedAndEmptyConfigurationEmitsNoBlock() {
        val model = fixtureModel()
        val configured = PrinterProfile.CUSTOM_CARTESIAN.copy(
            gcodeThumbnails = "64x48/PNG",
        )
        val withThumbnail = OnDeviceSlicer.slice(
            model,
            SliceOptions().selectPrinter(configured),
        )
        val withoutThumbnail = OnDeviceSlicer.slice(
            model,
            SliceOptions().selectPrinter(configured.copy(gcodeThumbnails = "")),
        )

        try {
            val gcode = withThumbnail.output.readText()
            val begin = requireNotNull(
                Regex("(?m)^; thumbnail begin 64x48 ([0-9]+)$").find(gcode),
            ) { "PNG thumbnail header is missing" }
            val end = gcode.indexOf("\n; thumbnail end", begin.range.last + 1)
            assertTrue("PNG thumbnail footer is missing", end > begin.range.last)
            val encoded = gcode
                .substring(begin.range.last + 1, end)
                .lineSequence()
                .filter { it.startsWith("; ") }
                .joinToString(separator = "") { it.removePrefix("; ") }
            assertEquals(begin.groupValues[1].toInt(), encoded.length)

            val png = Base64.decode(encoded, Base64.DEFAULT)
            assertTrue("Embedded PNG must contain an IHDR chunk", png.size > 32)
            assertTrue(
                "Embedded thumbnail must use the PNG signature",
                png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE),
            )
            val dimensions = ByteBuffer.wrap(png, 16, 8).order(ByteOrder.BIG_ENDIAN)
            assertEquals(64, dimensions.int)
            assertEquals(48, dimensions.int)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) {
                "Embedded PNG must be decodable"
            }
            try {
                assertEquals(64, bitmap.width)
                assertEquals(48, bitmap.height)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue(
                    "Embedded thumbnail must contain visible model pixels",
                    pixels.any { Color.alpha(it) > 0 },
                )
            } finally {
                bitmap.recycle()
            }
            assertTrue(gcode.contains("; THUMBNAIL_BLOCK_START"))
            assertTrue(gcode.contains("; THUMBNAIL_BLOCK_END"))

            val emptyGcode = withoutThumbnail.output.readText()
            assertFalse(emptyGcode.contains("; THUMBNAIL_BLOCK_START"))
            assertFalse(emptyGcode.contains("; thumbnail begin"))
        } finally {
            withThumbnail.output.delete()
            withoutThumbnail.output.delete()
        }
    }

    @Test
    fun bundledPrinterThumbnailDefinitionsAreCanonical() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val definitions = OrcaProfileCatalog(context).load().printers
            .map { it.gcodeThumbnails }
            .filter { it.isNotBlank() }

        assertTrue("Bundled printer profiles must retain thumbnail definitions", definitions.isNotEmpty())
        definitions.forEach { value ->
            assertTrue("Invalid bundled thumbnail definition: $value", gcodeThumbnailDefinitionsAreValid(value))
            value.split(',').forEach { definition ->
                assertTrue(
                    "Thumbnail definitions must include an explicit format: $definition",
                    CANONICAL_THUMBNAIL.matches(definition),
                )
            }
        }
    }

    private fun fixtureModel(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destination = File(instrumentation.targetContext.cacheDir, "gcode-thumbnail-fixture.stl")
        instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val CANONICAL_THUMBNAIL = Regex(
            "[1-9][0-9]{0,2}x[1-9][0-9]{0,2}/(PNG|JPG|QOI|BTT_TFT|COLPIC)",
        )
    }
}
