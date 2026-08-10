package com.ashcastle.duckyslicer

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RemoteDeviceInstrumentedTest {
    @Test
    fun remoteRefreshSurvivesActivityRecreationAndRejectsDuplicateWork() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestAccepted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>(null)
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
                    requestAccepted.countDown()
                    check(releaseResponse.await(5, TimeUnit.SECONDS))
                    val body = """{"state":"Operational"}""".toByteArray(StandardCharsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Type: application/json\r\n".toByteArray())
                        output.write("Content-Length: ${body.size}\r\n".toByteArray())
                        output.write("Connection: close\r\n\r\n".toByteArray())
                        output.write(body)
                    }
                }
            }.onFailure(serverFailure::set)
        }.apply { start() }
        val profile = RemoteDeviceProfile(
            id = "retained-refresh",
            name = "Retained printer",
            kind = RemoteDeviceKind.OCTOPRINT,
            baseUrl = "http://127.0.0.1:${server.localPort}",
        )
        val retainedModel = AtomicReference<RemoteOperationViewModel>()

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[RemoteOperationViewModel::class.java]
                    retainedModel.set(model)
                    assertTrue(model.refresh(profile, timeoutSeconds = 5))
                }
                assertTrue("Remote request did not start", requestAccepted.await(3, TimeUnit.SECONDS))

                scenario.recreate()
                scenario.onActivity { recreated ->
                    val model = ViewModelProvider(recreated)[RemoteOperationViewModel::class.java]
                    assertSame(retainedModel.get(), model)
                    assertTrue(model.state.value.busy)
                    assertFalse("Recreation allowed duplicate remote work", model.refresh(profile, 5))
                }

                releaseResponse.countDown()
                val deadline = SystemClock.elapsedRealtime() + 5_000
                val model = retainedModel.get()
                while (model.state.value.busy && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(25)
                }
                assertFalse("Retained remote request did not finish", model.state.value.busy)
                assertEquals("Operational", model.state.value.statusFor(profile.id)?.state)
            }
        } finally {
            releaseResponse.countDown()
            worker.join(5_000)
            server.close()
        }
        serverFailure.get()?.let { throw AssertionError("Local printer server failed", it) }
        assertFalse("Local printer server did not stop", worker.isAlive)
    }

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
            assertTrue(saved.credentialKey != null && saved.credentialKey != id)
            assertEquals("not-plain-text", store.credential(saved))
            val profileFile = context.filesDir.resolve("remote_devices.json")
            assertFalse(profileFile.readText().contains("not-plain-text"))
            val restored = store.load().single { it.id == id }
            assertTrue(restored.hasCredential)
            assertEquals("not-plain-text", store.credential(restored))
            assertEquals(2, JSONObject(profileFile.readText()).getInt("version"))
        } finally {
            store.delete(id)
        }
    }

    @Test
    fun changingPrinterAddressWithoutANewKeyCannotReuseTheOldCredential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RemoteDeviceStore(context)
        val id = "rebind-${UUID.randomUUID()}"
        try {
            val original = store.save(
                RemoteDeviceDraft(
                    id = id,
                    name = "Original printer",
                    kind = RemoteDeviceKind.OCTOPRINT,
                    baseUrl = "http://127.0.0.1:5000",
                    credential = "old-server-secret",
                ),
            )
            assertEquals("old-server-secret", store.credential(original))

            val rebound = store.save(
                RemoteDeviceDraft(
                    id = id,
                    name = "Replacement printer",
                    kind = RemoteDeviceKind.KLIPPER,
                    baseUrl = "http://127.0.0.1:7125",
                ),
            )

            assertFalse(rebound.hasCredential)
            assertEquals(null, rebound.credentialKey)
            assertEquals("", store.credential(rebound))
        } finally {
            store.delete(id)
        }
    }

    @Test
    fun remoteDeviceMetadataRecoversFromLastKnownGoodBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "recovery-${UUID.randomUUID()}"
        val profileFile = context.filesDir.resolve("remote_devices.json")
        try {
            val store = RemoteDeviceStore(context)
            store.save(
                RemoteDeviceDraft(
                    id = id,
                    name = "Recovery printer",
                    kind = RemoteDeviceKind.KLIPPER,
                    baseUrl = "http://127.0.0.1:7125",
                ),
            )
            assertTrue(store.load().any { it.id == id })
            assertTrue(context.filesDir.resolve("remote_devices.json.bak").isFile)
            profileFile.writeText("{broken")

            val recoveredStore = RemoteDeviceStore(context)
            val recovered = recoveredStore.load()

            assertTrue(recovered.any { it.id == id })
            assertFalse(recoveredStore.storageUnavailable)
            assertEquals(2, JSONObject(profileFile.readText()).getInt("version"))
        } finally {
            runCatching { RemoteDeviceStore(context).delete(id) }
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

    @Test
    fun cleartextHostnameRequestUsesOneValidatedPinnedAddress() {
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
            assertTrue(request.get().contains("Host: printer.local:$port", ignoreCase = true))
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
}
