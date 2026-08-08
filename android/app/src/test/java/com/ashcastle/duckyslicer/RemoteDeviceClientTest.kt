package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class RemoteDeviceClientTest {
    @Test
    fun octoPrintStatusUploadAndStartFollowTheRemoteContract() {
        withServer(
            """{"state":"Printing","job":{"file":{"name":"duck.gcode"}},"progress":{"completion":42.8}}""",
        ) { baseUrl, request ->
            val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "octo-secret")
            assertEquals("Printing", status.state)
            assertEquals(42, status.progressPercent)
            assertTrue(request.get().startsWith("GET /api/job HTTP/1.1"))
            assertTrue(request.get().contains("X-Api-Key: octo-secret", ignoreCase = true))
        }

        val gcode = File.createTempFile("ducky-octo-", ".gcode").apply { writeText("G28\nG1 X10\n") }
        try {
            withServer("""{"files":{"local":{"path":"duck.gcode"}}}""") { baseUrl, request ->
                val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
                val upload = RemoteDeviceClient(2_000).upload(profile, "octo-secret", gcode)
                assertEquals("duck.gcode", upload.remotePath)
                assertTrue(request.get().startsWith("POST /api/files/local HTTP/1.1"))
                assertTrue(request.get().contains("name=\"print\"\r\n\r\nfalse"))
                assertTrue(request.get().contains("G28\nG1 X10"))
            }
        } finally {
            gcode.delete()
        }

        withServer("{}") { baseUrl, request ->
            val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
            RemoteDeviceClient(2_000).start(
                profile,
                "octo-secret",
                RemoteUpload(profile.id, "folder/duck one.gcode", "duck one.gcode"),
            )
            assertTrue(request.get().startsWith("POST /api/files/local/folder/duck%20one.gcode HTTP/1.1"))
            assertTrue(request.get().contains("{\"command\":\"select\",\"print\":true}"))
        }
    }

    @Test
    fun moonrakerStatusUploadAndStartFollowTheRemoteContract() {
        withServer(
            """{"result":{"status":{"print_stats":{"state":"printing","filename":"duck.gcode"},"virtual_sdcard":{"progress":0.735}}}}""",
        ) { baseUrl, request ->
            val profile = RemoteDeviceProfile("klipper", "Workshop", RemoteDeviceKind.KLIPPER, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "moonraker-secret")
            assertEquals("printing", status.state)
            assertEquals(73, status.progressPercent)
            assertTrue(request.get().startsWith("GET /printer/objects/query?print_stats&virtual_sdcard HTTP/1.1"))
            assertTrue(request.get().contains("X-Api-Key: moonraker-secret", ignoreCase = true))
        }

        val gcode = File.createTempFile("ducky-moonraker-", ".gcode").apply { writeText("G28\n") }
        try {
            withServer("""{"result":{"item":{"path":"duck.gcode"}}}""") { baseUrl, request ->
                val profile = RemoteDeviceProfile("klipper", "Workshop", RemoteDeviceKind.KLIPPER, baseUrl)
                val upload = RemoteDeviceClient(2_000).upload(profile, "moonraker-secret", gcode)
                assertEquals("duck.gcode", upload.remotePath)
                assertTrue(request.get().startsWith("POST /server/files/upload HTTP/1.1"))
                assertTrue(request.get().contains("name=\"root\"\r\n\r\ngcodes"))
            }
        } finally {
            gcode.delete()
        }

        withServer("{}") { baseUrl, request ->
            val profile = RemoteDeviceProfile("klipper", "Workshop", RemoteDeviceKind.KLIPPER, baseUrl)
            RemoteDeviceClient(2_000).start(
                profile,
                "moonraker-secret",
                RemoteUpload(profile.id, "folder/duck one.gcode", "duck one.gcode"),
            )
            assertTrue(
                request.get().startsWith(
                    "POST /printer/print/start?filename=folder%2Fduck%20one.gcode HTTP/1.1",
                ),
            )
        }
    }

    @Test
    fun unencryptedRemoteAddressesAreRejected() {
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
