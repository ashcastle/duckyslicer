package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RemoteDeviceInstrumentedTest {
    @Test
    fun octoPrintStatusUsesApiKeyAndParsesProgress() {
        withServer(
            """{"state":"Printing","job":{"file":{"name":"duck.gcode"}},"progress":{"completion":42.8}}""",
        ) { baseUrl, request ->
            val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "octo-secret")

            assertEquals("Printing", status.state)
            assertEquals("duck.gcode", status.fileName)
            assertEquals(42, status.progressPercent)
            val rawRequest = request.get()
            assertTrue(rawRequest.startsWith("GET /api/job HTTP/1.1"))
            assertTrue(rawRequest.contains("X-Api-Key: octo-secret", ignoreCase = true))
        }
    }

    @Test
    fun moonrakerStatusUsesApiKeyAndParsesProgress() {
        withServer(
            """{"result":{"status":{"print_stats":{"state":"printing","filename":"duck.gcode"},"virtual_sdcard":{"progress":0.735}}}}""",
        ) { baseUrl, request ->
            val profile = RemoteDeviceProfile("klipper", "Workshop", RemoteDeviceKind.KLIPPER, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "moonraker-secret")

            assertEquals("printing", status.state)
            assertEquals("duck.gcode", status.fileName)
            assertEquals(73, status.progressPercent)
            val rawRequest = request.get()
            assertTrue(rawRequest.startsWith("GET /printer/objects/query?print_stats&virtual_sdcard HTTP/1.1"))
            assertTrue(rawRequest.contains("X-Api-Key: moonraker-secret", ignoreCase = true))
        }
    }

    @Test
    fun octoPrintUploadUsesLocalFilesEndpointWithoutStartingPrint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gcode = context.cacheDir.resolve("octo-upload.gcode").apply { writeText("G28\nG1 X10 Y20\n") }
        try {
            withServer(
                """{"files":{"local":{"path":"octo-upload.gcode"}}}""",
            ) { baseUrl, request ->
                val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
                val upload = RemoteDeviceClient(2_000).upload(profile, "octo-secret", gcode)

                assertEquals("octo-upload.gcode", upload.remotePath)
                val rawRequest = request.get()
                assertTrue(rawRequest.startsWith("POST /api/files/local HTTP/1.1"))
                assertTrue(rawRequest.contains("name=\"select\"\r\n\r\ntrue"))
                assertTrue(rawRequest.contains("name=\"print\"\r\n\r\nfalse"))
                assertTrue(rawRequest.contains("filename=\"octo-upload.gcode\""))
                assertTrue(rawRequest.contains("G28\nG1 X10 Y20"))
            }
        } finally {
            gcode.delete()
        }
    }

    @Test
    fun moonrakerUploadTargetsGcodesRootWithoutStartingPrint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gcode = context.cacheDir.resolve("moonraker-upload.gcode").apply { writeText("G28\n") }
        try {
            withServer(
                """{"result":{"item":{"path":"moonraker-upload.gcode"}}}""",
            ) { baseUrl, request ->
                val profile = RemoteDeviceProfile("klipper", "Workshop", RemoteDeviceKind.KLIPPER, baseUrl)
                val upload = RemoteDeviceClient(2_000).upload(profile, "moonraker-secret", gcode)

                assertEquals("moonraker-upload.gcode", upload.remotePath)
                val rawRequest = request.get()
                assertTrue(rawRequest.startsWith("POST /server/files/upload HTTP/1.1"))
                assertTrue(rawRequest.contains("name=\"root\"\r\n\r\ngcodes"))
                assertTrue(rawRequest.contains("name=\"path\"\r\n\r\n\r\n"))
                assertTrue(rawRequest.contains("filename=\"moonraker-upload.gcode\""))
            }
        } finally {
            gcode.delete()
        }
    }

    @Test
    fun remotePrintStartsOnlyThroughExplicitStartEndpoints() {
        withServer("{}") { baseUrl, request ->
            val octo = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
            RemoteDeviceClient(2_000).start(
                octo,
                "octo-secret",
                RemoteUpload(octo.id, "folder/duck one.gcode", "duck one.gcode"),
            )

            val rawRequest = request.get()
            assertTrue(rawRequest.startsWith("POST /api/files/local/folder/duck%20one.gcode HTTP/1.1"))
            assertTrue(rawRequest.contains("{\"command\":\"select\",\"print\":true}"))
        }
        withServer("{}") { baseUrl, request ->
            val klipper = RemoteDeviceProfile("klipper", "Workshop", RemoteDeviceKind.KLIPPER, baseUrl)
            RemoteDeviceClient(2_000).start(
                klipper,
                "moonraker-secret",
                RemoteUpload(klipper.id, "folder/duck one.gcode", "duck one.gcode"),
            )

            assertTrue(
                request.get().startsWith(
                    "POST /printer/print/start?filename=folder%2Fduck%20one.gcode HTTP/1.1",
                ),
            )
        }
    }

    @Test
    fun accessKeyIsEncryptedOutsideTheDeviceProfileFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RemoteDeviceStore(context)
        val id = "test-${UUID.randomUUID()}"
        try {
            val saved = store.save(
                RemoteDeviceDraft(
                    id = id,
                    name = "Test printer",
                    kind = RemoteDeviceKind.OCTOPRINT,
                    baseUrl = "http://127.0.0.1:5000",
                    credential = "not-plain-text",
                ),
            )

            assertTrue(saved.hasCredential)
            assertEquals("not-plain-text", store.credential(id))
            val profileFile = context.filesDir.resolve("remote_devices.json")
            assertFalse(profileFile.readText().contains("not-plain-text"))
            assertTrue(store.load().any { it.id == id && it.hasCredential })
        } finally {
            store.delete(id)
        }
    }

    @Test
    fun cleartextConnectionIsLimitedToLocalAddresses() {
        assertEquals(
            "cleartext_not_local",
            RemoteDeviceProfile(
                "remote",
                "Remote",
                RemoteDeviceKind.OCTOPRINT,
                "http://203.0.113.10",
            ).validate(),
        )
        assertEquals(
            null,
            RemoteDeviceProfile(
                "local",
                "Local",
                RemoteDeviceKind.KLIPPER,
                "http://192.168.1.20",
            ).validate(),
        )
    }

    private fun withServer(
        responseBody: String,
        block: (String, AtomicReference<String>) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val request = AtomicReference("")
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val received = StringBuilder()
                    var current: Int
                    while (input.read().also { current = it } >= 0) {
                        received.append(current.toChar())
                        if (received.endsWith("\r\n\r\n")) break
                    }
                    val contentLength = Regex("(?i)Content-Length: (\\d+)")
                        .find(received)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(contentLength) {
                        val value = input.read()
                        if (value >= 0) received.append(value.toChar())
                    }
                    request.set(received.toString())
                    val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Type: application/json\r\n".toByteArray())
                        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                        output.write("Connection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                    }
                }
            }.onFailure { failure.set(it) }
        }.apply { start() }

        try {
            block("http://127.0.0.1:${server.localPort}", request)
        } finally {
            worker.join(3_000)
            server.close()
        }
        failure.get()?.let { throw AssertionError("Local printer server failed", it) }
        assertFalse("The client did not reach the local printer server", worker.isAlive)
    }
}
