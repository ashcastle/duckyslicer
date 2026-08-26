package com.ashcastle.duckyslicer

import org.json.JSONObject
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
            listOf(
                """{"state":"Printing","job":{"file":{"name":"duck.gcode"}},"progress":{"completion":42.8,"printTime":125,"printTimeLeft":245}}""",
                """{"temperature":{"tool0":{"actual":205.4,"target":210.0},"bed":{"actual":59.8,"target":60.0}}}""",
            ),
        ) { baseUrl, requests ->
            val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "octo-secret")
            assertEquals("Printing", status.state)
            assertEquals(42, status.progressPercent)
            assertEquals(205.4, status.nozzleTemperatureC)
            assertEquals(210.0, status.nozzleTargetC)
            assertEquals(59.8, status.bedTemperatureC)
            assertEquals(60.0, status.bedTargetC)
            assertEquals(125L, status.elapsedSeconds)
            assertEquals(245L, status.remainingSeconds)
            assertTrue(requests[0].get().startsWith("GET /api/job HTTP/1.1"))
            assertTrue(requests[1].get().startsWith("GET /api/printer?exclude=sd,state HTTP/1.1"))
            assertTrue(requests.all { it.get().contains("X-Api-Key: octo-secret", ignoreCase = true) })
        }

        val gcode = Files.createTempFile("ducky-octo-", ".gcode").toFile().apply {
            writeText("G28\nG1 X10\n")
        }
        try {
            withServer("""{"files":{"local":{"path":"duck.gcode"}}}""") { baseUrl, request ->
                val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
                val upload = RemoteDeviceClient(2_000).upload(
                    profile,
                    "octo-secret",
                    gcode,
                    "bench_PLA_22m.gcode",
                    {},
                    RemoteRequestCancellation(),
                )
                assertEquals("duck.gcode", upload.remotePath)
                assertEquals("bench_PLA_22m.gcode", upload.displayName)
                assertTrue(request.get().startsWith("POST /api/files/local HTTP/1.1"))
                assertTrue(request.get().contains("name=\"print\"\r\n\r\nfalse"))
                assertTrue(request.get().contains("filename=\"bench_PLA_22m.gcode\""))
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
            """{"result":{"status":{"print_stats":{"state":"printing","filename":"duck.gcode","print_duration":3600},"virtual_sdcard":{"progress":0.75},"extruder":{"temperature":214.5,"target":215.0},"heater_bed":{"temperature":64.8,"target":65.0}}}}""",
        ) { baseUrl, request ->
            val profile = RemoteDeviceProfile("klipper", "Workshop", RemoteDeviceKind.KLIPPER, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "moonraker-secret")
            assertEquals("printing", status.state)
            assertEquals(75, status.progressPercent)
            assertEquals(214.5, status.nozzleTemperatureC)
            assertEquals(215.0, status.nozzleTargetC)
            assertEquals(64.8, status.bedTemperatureC)
            assertEquals(65.0, status.bedTargetC)
            assertEquals(3_600L, status.elapsedSeconds)
            assertEquals(1_200L, status.remainingSeconds)
            assertTrue(
                request.get().startsWith(
                    "GET /printer/objects/query?print_stats&virtual_sdcard&extruder&heater_bed HTTP/1.1",
                ),
            )
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
    fun remoteTelemetryIsBoundedAndOptionalOctoPrintTelemetryCannotHideJobStatus() {
        val invalidOcto = parseOctoPrintStatus(
            JSONObject(
                """{"state":"Printing","progress":{"completion":142,"printTime":-1,"printTimeLeft":999999999}}""",
            ),
            JSONObject(
                """{"temperature":{"tool0":{"actual":1200,"target":"bad"},"bed":{"actual":-101,"target":60}}}""",
            ),
        )
        assertEquals(100, invalidOcto.progressPercent)
        assertEquals(null, invalidOcto.nozzleTemperatureC)
        assertEquals(null, invalidOcto.nozzleTargetC)
        assertEquals(null, invalidOcto.bedTemperatureC)
        assertEquals(60.0, invalidOcto.bedTargetC)
        assertEquals(null, invalidOcto.elapsedSeconds)
        assertEquals(null, invalidOcto.remainingSeconds)

        withServer(
            listOf(
                """{"state":"Operational","progress":{"printTime":12}}""",
                "not-json",
            ),
        ) { baseUrl, _ ->
            val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "")
            assertEquals("Operational", status.state)
            assertEquals(12L, status.elapsedSeconds)
            assertEquals(null, status.nozzleTemperatureC)
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
        withServer(
            listOf(
                """{"state":"Operational"}""",
                """{"temperature":{"tool0":{"actual":20,"target":0}}}""",
            ),
        ) { baseUrl, requests ->
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
            assertTrue(requests.all { it.get().contains("X-Api-Key: pinned-secret", ignoreCase = true) })
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
    ) = withServer(listOf(responseBody)) { baseUrl, requests ->
        block(baseUrl, requests.single())
    }

    private fun withServer(
        responseBodies: List<String>,
        block: (String, List<AtomicReference<String>>) -> Unit,
    ) {
        require(responseBodies.isNotEmpty())
        val server = ServerSocket(0, responseBodies.size, InetAddress.getByName("127.0.0.1"))
        val requests = responseBodies.map { AtomicReference("") }
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            runCatching {
                responseBodies.forEachIndexed { index, responseBody ->
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
                        requests[index].set(received.toString())
                        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                        socket.getOutputStream().use { output ->
                            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                            output.write("Content-Type: application/json\r\n".toByteArray())
                            output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                            output.write("Connection: close\r\n\r\n".toByteArray())
                            output.write(bytes)
                        }
                    }
                }
            }.onFailure { failure.set(it) }
        }.apply { start() }

        try {
            block("http://127.0.0.1:${server.localPort}", requests)
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
