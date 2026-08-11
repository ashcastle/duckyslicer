package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RemoteDeviceClientTest {
    @Test
    fun remoteResultsOnlyBelongToTheirOriginatingSelection() {
        assertTrue(remoteResultBelongsToSelection("printer-a", "printer-a"))
        assertFalse(remoteResultBelongsToSelection("printer-a", "printer-b"))
        assertFalse(remoteResultBelongsToSelection("printer-a", null))
    }

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

        val gcode = Files.createTempFile("ducky-octo-", ".gcode").toFile().apply {
            writeText("G28\nG1 X10\n")
        }
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

        val gcode = Files.createTempFile("ducky-moonraker-", ".gcode").toFile().apply {
            writeText("G28\n")
        }
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
        assertEquals(
            null,
            RemoteDeviceProfile(
                "local-v6",
                "Local IPv6",
                RemoteDeviceKind.KLIPPER,
                "http://[fd00::20]",
            ).validate(),
        )
    }

    @Test
    fun cleartextDnsResultsAreValidatedAndPinnedBeforeCredentialsAreAttached() {
        val local = InetAddress.getByAddress(
            "printer.local",
            byteArrayOf(192.toByte(), 168.toByte(), 1, 55),
        )
        val endpoint = resolveRemoteEndpoint(
            URI("http://printer.local:5000/api/job?history=false"),
        ) { listOf(local) }

        assertEquals("http://192.168.1.55:5000/api/job?history=false", endpoint.uri.toString())
        assertEquals("printer.local:5000", endpoint.hostHeader)

        val public = InetAddress.getByAddress(
            "printer.local",
            byteArrayOf(203.toByte(), 0, 113, 9),
        )
        assertThrows(IllegalArgumentException::class.java) {
            resolveRemoteEndpoint(URI("http://printer.local/api/job")) {
                listOf(local, public)
            }
        }

        var resolvedHttps = false
        val https = resolveRemoteEndpoint(URI("https://printer.example/api/job")) {
            resolvedHttps = true
            listOf(public)
        }
        assertEquals("https://printer.example/api/job", https.uri.toString())
        assertEquals(null, https.hostHeader)
        assertFalse("HTTPS must retain certificate hostname resolution", resolvedHttps)
    }

    @Test
    fun cleartextHostnameRequestUsesThePinnedResolverAddress() {
        withServer("""{"state":"Operational"}""") { baseUrl, request ->
            val port = URI(baseUrl).port
            val profile = RemoteDeviceProfile(
                "dns-pinned",
                "Pinned printer",
                RemoteDeviceKind.OCTOPRINT,
                "http://printer.local:$port",
            )
            val client = RemoteDeviceClient(2_000) { host ->
                assertEquals("printer.local", host)
                listOf(InetAddress.getByName("127.0.0.1"))
            }

            assertEquals("Operational", client.status(profile, "pinned-secret").state)
            assertTrue(request.get().contains("X-Api-Key: pinned-secret", ignoreCase = true))
        }
    }

    @Test
    fun redirectsOversizedResponsesAndDeepJsonFailClosed() {
        withRawServer(
            "HTTP/1.1 302 Found\r\nLocation: http://203.0.113.10/steal\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n",
        ) { baseUrl ->
            val failure = assertThrows(RemoteDeviceException::class.java) {
                RemoteDeviceClient(2_000).status(
                    RemoteDeviceProfile("redirect", "Redirect", RemoteDeviceKind.OCTOPRINT, baseUrl),
                    "must-not-follow",
                )
            }
            assertEquals(302, failure.statusCode)
        }

        withRawServer(
            "HTTP/1.1 200 OK\r\nContent-Length: 1048577\r\nConnection: close\r\n\r\n",
        ) { baseUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteDeviceClient(2_000).status(
                    RemoteDeviceProfile("large", "Large", RemoteDeviceKind.OCTOPRINT, baseUrl),
                    "",
                )
            }
        }

        val deep = "{\"state\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        withServer(deep) { baseUrl, _ ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteDeviceClient(2_000).status(
                    RemoteDeviceProfile("deep", "Deep", RemoteDeviceKind.OCTOPRINT, baseUrl),
                    "",
                )
            }
        }
    }

    @Test
    fun unsafeServerUploadPathIsRejected() {
        val gcode = Files.createTempFile("ducky-path-", ".gcode").toFile().apply {
            writeText("G28\n")
        }
        try {
            withServer("""{"files":{"local":{"path":"../other/duck.gcode"}}}""") { baseUrl, _ ->
                assertThrows(IllegalArgumentException::class.java) {
                    RemoteDeviceClient(2_000).upload(
                        RemoteDeviceProfile("path", "Path", RemoteDeviceKind.OCTOPRINT, baseUrl),
                        "",
                        gcode,
                    )
                }
            }
        } finally {
            gcode.delete()
        }
    }

    @Test
    fun cancelingUploadDisconnectsItsSocketAndDoesNotPoisonTheNextUpload() {
        val gcode = Files.createTempFile("ducky-cancel-upload-", ".gcode").toFile()
        RandomAccessFile(gcode, "rw").use { it.setLength(32L * 1_024 * 1_024) }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverWorker = Thread {
            runCatching {
                server.accept().use {
                    accepted.countDown()
                    check(releaseServer.await(10, TimeUnit.SECONDS))
                }
            }.onFailure(serverFailure::set)
        }.apply { start() }
        val cancellation = RemoteRequestCancellation()
        val uploadFailure = AtomicReference<Throwable?>(null)
        val profile = RemoteDeviceProfile(
            "cancel-upload",
            "Cancel upload",
            RemoteDeviceKind.OCTOPRINT,
            "http://127.0.0.1:${server.localPort}",
        )
        val uploadWorker = Thread {
            uploadFailure.set(
                runCatching {
                    RemoteDeviceClient(30_000).upload(
                        profile,
                        "",
                        gcode,
                        {},
                        cancellation,
                    )
                }.exceptionOrNull(),
            )
        }.apply { start() }

        var stoppedPromptly = false
        try {
            assertTrue("Upload never opened its printer socket", accepted.await(3, TimeUnit.SECONDS))
            assertTrue("The active upload must accept one cancellation", cancellation.cancel())
            uploadWorker.join(3_000)
            stoppedPromptly = !uploadWorker.isAlive
            assertFalse("A completed cancellation must not be reusable", cancellation.cancel())
        } finally {
            releaseServer.countDown()
            server.close()
            uploadWorker.join(5_000)
            serverWorker.join(5_000)
            gcode.delete()
        }

        assertTrue("Disconnecting the exact upload socket must stop it promptly", stoppedPromptly)
        assertTrue(uploadFailure.get() is RemoteRequestCancelledException)
        serverFailure.get()?.let { throw AssertionError("Blocked printer server failed", it) }
        assertFalse("Blocked printer server did not stop", serverWorker.isAlive)

        val followUp = Files.createTempFile("ducky-follow-up-upload-", ".gcode").toFile().apply {
            writeText("G28\n")
        }
        try {
            withServer("""{"files":{"local":{"path":"follow-up.gcode"}}}""") { baseUrl, _ ->
                val nextProfile = profile.copy(baseUrl = baseUrl)
                val uploaded = RemoteDeviceClient(2_000).upload(
                    nextProfile,
                    "",
                    followUp,
                    {},
                    RemoteRequestCancellation(),
                )
                assertEquals("follow-up.gcode", uploaded.remotePath)
            }
        } finally {
            followUp.delete()
        }
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

    private fun withRawServer(response: String, block: (String) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
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
                    socket.getOutputStream().use { output ->
                        output.write(response.toByteArray(StandardCharsets.UTF_8))
                    }
                }
            }.onFailure { failure.set(it) }
        }.apply { start() }
        try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            worker.join(3_000)
            server.close()
        }
        failure.get()?.let { throw AssertionError("Local printer server failed", it) }
        assertFalse("The client did not reach the local printer server", worker.isAlive)
    }
}
