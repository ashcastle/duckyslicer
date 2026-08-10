package com.ashcastle.duckyslicer

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaModelImportInstrumentedTest {
    @Test
    fun objImportsAsAProjectObjectAndSlicesThroughOrca() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val source = File(context.cacheDir, "cube.obj")
        var gcode: File? = null
        projectRoot.deleteRecursively()
        source.writeText(CUBE_OBJ)
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                source,
            )
            val objects = importOrcaModels(
                context,
                uri,
                ProjectStore(context),
                SliceOptions(),
            )
            assertEquals(1, objects.size)
            assertEquals("cube.stl", objects.single().model.fileName)
            val outcome = OnDeviceSlicer.slice(objects, SliceOptions())
            gcode = outcome.output
            assertTrue("Imported OBJ must produce real G-code", outcome.output.length() > 1_000L)
        } finally {
            gcode?.delete()
            source.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun standard3mfImportsTwoPlacedObjectsAndSlicesThroughOrca() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val archive = File(context.cacheDir, "two-objects.3mf")
        var gcode: File? = null
        projectRoot.deleteRecursively()
        archive.delete()
        try {
            writeStandard3mf(archive)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                archive,
            )
            val options = SliceOptions().copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val objects = importOrcaModels(context, uri, ProjectStore(context), options)

            assertEquals(2, objects.size)
            assertTrue(objects.all { it.model.localPath.endsWith(".stl") })
            assertEquals(-15f, objects[0].transform.offsetXmm, 0.01f)
            assertEquals(-5f, objects[0].transform.offsetYmm, 0.01f)
            assertEquals(15f, objects[1].transform.offsetXmm, 0.01f)
            assertEquals(-5f, objects[1].transform.offsetYmm, 0.01f)

            val outcome = OnDeviceSlicer.slice(objects, options)
            gcode = outcome.output
            assertTrue("Imported 3MF objects must produce real G-code", outcome.output.length() > 1_000L)
        } finally {
            gcode?.delete()
            archive.delete()
            projectRoot.deleteRecursively()
        }
    }

    private fun writeStandard3mf(destination: File) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.writeEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
                    </Types>
                """.trimIndent(),
            )
            zip.writeEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
                    </Relationships>
                """.trimIndent(),
            )
            zip.writeEntry(
                "3D/3dmodel.model",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
                      <resources>
                        ${tetrahedronObject(1, "Left")}
                        ${tetrahedronObject(2, "Right")}
                      </resources>
                      <build>
                        <item objectid="1" transform="1 0 0 0 1 0 0 0 1 30 40 0"/>
                        <item objectid="2" transform="1 0 0 0 1 0 0 0 1 60 40 0"/>
                      </build>
                    </model>
                """.trimIndent(),
            )
        }
    }

    private fun tetrahedronObject(id: Int, name: String): String =
        """<object id="$id" type="model" name="$name">
             <mesh>
               <vertices>
                 <vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/>
                 <vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/>
               </vertices>
               <triangles>
                 <triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/>
                 <triangle v1="0" v2="3" v3="2"/><triangle v1="1" v2="2" v3="3"/>
               </triangles>
             </mesh>
           </object>"""

    private fun ZipOutputStream.writeEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private companion object {
        val CUBE_OBJ = """
            o Cube
            v 0 0 0
            v 10 0 0
            v 10 10 0
            v 0 10 0
            v 0 0 10
            v 10 0 10
            v 10 10 10
            v 0 10 10
            f 1 3 2
            f 1 4 3
            f 5 6 7
            f 5 7 8
            f 1 2 6
            f 1 6 5
            f 2 3 7
            f 2 7 6
            f 3 4 8
            f 3 8 7
            f 4 1 5
            f 4 5 8
        """.trimIndent()
    }
}
