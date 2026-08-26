package com.ashcastle.duckyslicer

import android.os.SystemClock
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
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
    fun retainedUploadCancellationStopsItsConnectionAcrossActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val gcode = File(
            instrumentation.targetContext.cacheDir,
            "cancel-upload-${UUID.randomUUID()}.gcode",
        )
        RandomAccessFile(gcode, "rw").use { it.setLength(32L * 1_024 * 1_024) }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestAccepted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            runCatching {
                server.accept().use {
                    requestAccepted.countDown()
                    check(releaseServer.await(10, TimeUnit.SECONDS))
                }
            }.onFailure(serverFailure::set)
        }.apply { start() }
        val profile = RemoteDeviceProfile(
            id = "retained-upload-cancel",
            name = "Retained upload",
            kind = RemoteDeviceKind.OCTOPRINT,
            baseUrl = "http://127.0.0.1:${server.localPort}",
        )
        val retainedModel = AtomicReference<RemoteOperationViewModel>()

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    retainedModel.set(
                        ViewModelProvider(activity)[RemoteOperationViewModel::class.java],
                    )
                }
                val model = retainedModel.get()
                waitForRemoteState(model, "Remote profiles did not finish loading") {
                    it.profilesLoaded && !it.busy
                }
                scenario.onActivity {
                    assertTrue(model.upload(profile, gcode, timeoutSeconds = 30))
                }
                assertTrue(
                    "Upload did not open its printer socket",
                    requestAccepted.await(3, TimeUnit.SECONDS),
                )

                scenario.recreate()
                scenario.onActivity { recreated ->
                    val recreatedModel =
                        ViewModelProvider(recreated)[RemoteOperationViewModel::class.java]
                    assertSame(model, recreatedModel)
                    assertTrue(recreatedModel.state.value.uploadActiveFor(profile.id))
                    assertTrue(recreatedModel.cancelActiveRequest())
                    assertFalse(
                        "Duplicate upload cancellation was accepted",
                        recreatedModel.cancelActiveRequest(),
                    )
                }

                waitForRemoteState(model, "Canceled upload did not release its connection") {
                    !it.busy
                }
                assertEquals(
                    RemoteOperationMessage.UPLOAD_CANCELED,
                    model.state.value.messageFor(profile.id),
                )
                assertNull(model.state.value.uploadFor(profile.id))

                val staleServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                val staleAccepted = CountDownLatch(1)
                val releaseStaleServer = CountDownLatch(1)
                val staleServerFailure = AtomicReference<Throwable?>(null)
                val staleWorker = Thread {
                    runCatching {
                        staleServer.accept().use {
                            staleAccepted.countDown()
                            check(releaseStaleServer.await(10, TimeUnit.SECONDS))
                        }
                    }.onFailure(staleServerFailure::set)
                }.apply { start() }
                val staleProfile = profile.copy(
                    id = "invalidated-upload",
                    baseUrl = "http://127.0.0.1:${staleServer.localPort}",
                )
                try {
                    scenario.onActivity {
                        assertTrue(model.upload(staleProfile, gcode, timeoutSeconds = 30))
                    }
                    assertTrue(
                        "Follow-up upload did not open its printer socket",
                        staleAccepted.await(3, TimeUnit.SECONDS),
                    )
                    scenario.onActivity { model.invalidateUpload() }
                    waitForRemoteState(
                        model,
                        "Invalidated upload did not release its connection",
                    ) { !it.busy }
                    assertNull(model.state.value.uploadFor(staleProfile.id))
                    assertNull(model.state.value.messageFor(staleProfile.id))
                } finally {
                    releaseStaleServer.countDown()
                    staleWorker.join(5_000)
                    staleServer.close()
                }
                staleServerFailure.get()?.let {
                    throw AssertionError("Invalidated printer server failed", it)
                }
                assertFalse("Invalidated printer server did not stop", staleWorker.isAlive)
            }
        } finally {
            retainedModel.get()?.cancelActiveRequest()
            releaseServer.countDown()
            worker.join(5_000)
            server.close()
            gcode.delete()
        }
        serverFailure.get()?.let { throw AssertionError("Blocked printer server failed", it) }
        assertFalse("Blocked printer server did not stop", worker.isAlive)
    }

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
                }
                waitForRemoteState(
                    retainedModel.get(),
                    "Remote profiles did not finish loading",
                ) { it.profilesLoaded && !it.busy }
                scenario.onActivity {
                    val model = retainedModel.get()
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
                val model = retainedModel.get()
                waitForRemoteState(model, "Retained remote request did not finish") { !it.busy }
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
    fun backgroundRefreshDoesNotBlockUiAndYieldsToManualRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileId = "background-refresh-${UUID.randomUUID()}"
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestAccepted = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val received = StringBuilder()
                    var current: Int
                    while (input.read().also { current = it } >= 0) {
                        received.append(current.toChar())
                        if (received.endsWith("\r\n\r\n")) break
                    }
                    requestAccepted.countDown()
                    try {
                        while (input.read() >= 0) {
                            // Wait for the exact background request to be disconnected.
                        }
                    } finally {
                        connectionClosed.countDown()
                    }
                }
            }.onFailure(serverFailure::set)
        }.apply { start() }
        val store = RemoteDeviceStore(context)
        val profile = store.save(
            RemoteDeviceDraft(
                id = profileId,
                name = "Background refresh",
                kind = RemoteDeviceKind.OCTOPRINT,
                baseUrl = "http://127.0.0.1:${server.localPort}",
            ),
        )
        val retainedModel = AtomicReference<RemoteOperationViewModel>()

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    retainedModel.set(
                        ViewModelProvider(activity)[RemoteOperationViewModel::class.java],
                    )
                }
                val model = retainedModel.get()
                waitForRemoteState(model, "Remote profiles did not finish loading") {
                    it.profilesLoaded && !it.busy
                }
                scenario.onActivity {
                    model.selectionChanged(profile.id)
                    assertTrue(model.refreshInBackground(profile.id, timeoutSeconds = 30))
                    assertFalse("Background monitoring must not enter foreground busy state", model.state.value.busy)
                    assertFalse("Duplicate background monitoring was accepted", model.refreshInBackground(profile.id, 30))
                }
                assertTrue(
                    "Background refresh did not reach its printer socket",
                    requestAccepted.await(3, TimeUnit.SECONDS),
                )

                withServer(
                    listOf(
                        """{"state":"Operational","progress":{"completion":0}}""",
                        """{"temperature":{"tool0":{"actual":21,"target":0}}}""",
                    ),
                ) { baseUrl, _ ->
                    scenario.onActivity {
                        assertTrue(
                            model.refresh(
                                profile.copy(baseUrl = baseUrl),
                                timeoutSeconds = 5,
                            ),
                        )
                    }
                    assertTrue(
                        "Manual refresh did not disconnect background monitoring",
                        connectionClosed.await(3, TimeUnit.SECONDS),
                    )
                    waitForRemoteState(model, "Manual refresh did not finish") { !it.busy }
                    assertEquals("Operational", model.state.value.statusFor(profile.id)?.state)
                }
            }
        } finally {
            retainedModel.get()?.stopBackgroundRefresh()
            runCatching { store.delete(profileId) }
            worker.join(5_000)
            server.close()
        }
        serverFailure.get()?.let { throw AssertionError("Background printer server failed", it) }
        assertFalse("Background printer server did not stop", worker.isAlive)
    }

    @Test
    fun retainedRefreshCancellationDisconnectsExactRequestAndAllowsFollowUp() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestAccepted = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val received = StringBuilder()
                    var current: Int
                    while (input.read().also { current = it } >= 0) {
                        received.append(current.toChar())
                        if (received.endsWith("\r\n\r\n")) break
                    }
                    requestAccepted.countDown()
                    try {
                        while (input.read() >= 0) {
                            // A GET request has no body; wait for the exact client connection to close.
                        }
                    } catch (_: SocketException) {
                        // HttpURLConnection.disconnect() may surface as EOF or a reset.
                    }
                    connectionClosed.countDown()
                }
            }.onFailure(serverFailure::set)
        }.apply { start() }
        val profile = RemoteDeviceProfile(
            id = "retained-refresh-cancel",
            name = "Cancelable refresh",
            kind = RemoteDeviceKind.OCTOPRINT,
            baseUrl = "http://127.0.0.1:${server.localPort}",
        )
        val retainedModel = AtomicReference<RemoteOperationViewModel>()

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    retainedModel.set(
                        ViewModelProvider(activity)[RemoteOperationViewModel::class.java],
                    )
                }
                val model = retainedModel.get()
                waitForRemoteState(model, "Remote profiles did not finish loading") {
                    it.profilesLoaded && !it.busy
                }
                scenario.onActivity {
                    assertTrue(model.refresh(profile, timeoutSeconds = 30))
                }
                assertTrue(
                    "Refresh did not reach its printer socket",
                    requestAccepted.await(3, TimeUnit.SECONDS),
                )

                scenario.recreate()
                scenario.onActivity { recreated ->
                    val recreatedModel =
                        ViewModelProvider(recreated)[RemoteOperationViewModel::class.java]
                    assertSame(model, recreatedModel)
                    assertTrue(recreatedModel.state.value.networkRequestActiveFor(profile.id))
                    assertTrue(recreatedModel.cancelActiveRequest())
                    assertFalse(
                        "Duplicate refresh cancellation was accepted",
                        recreatedModel.cancelActiveRequest(),
                    )
                }

                assertTrue(
                    "Canceled refresh did not close its exact socket",
                    connectionClosed.await(3, TimeUnit.SECONDS),
                )
                waitForRemoteState(model, "Canceled refresh did not settle") { !it.busy }
                assertNull(model.state.value.statusFor(profile.id))
                assertEquals(
                    RemoteOperationMessage.REQUEST_CANCELED,
                    model.state.value.messageFor(profile.id),
                )
                assertFalse("Settled cancellation was accepted again", model.cancelActiveRequest())

                withServer(
                    listOf(
                        """{"state":"Operational"}""",
                        """{"temperature":{"tool0":{"actual":20,"target":0}}}""",
                    ),
                ) { baseUrl, _ ->
                    val followUp = profile.copy(id = "refresh-follow-up", baseUrl = baseUrl)
                    assertTrue(model.refresh(followUp, timeoutSeconds = 5))
                    waitForRemoteState(model, "Follow-up refresh did not finish") { !it.busy }
                    assertEquals("Operational", model.state.value.statusFor(followUp.id)?.state)
                }
            }
        } finally {
            retainedModel.get()?.cancelActiveRequest()
            worker.join(5_000)
            server.close()
        }
        serverFailure.get()?.let { throw AssertionError("Cancelable printer server failed", it) }
        assertFalse("Cancelable printer server did not stop", worker.isAlive)
    }

    @Test
    fun finalRemoteOwnerDisconnectsBlockedCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestAccepted = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val received = StringBuilder()
                    var current: Int
                    while (input.read().also { current = it } >= 0) {
                        received.append(current.toChar())
                        if (received.endsWith("\r\n\r\n")) break
                    }
                    requestAccepted.countDown()
                    try {
                        while (input.read() >= 0) {
                            // Consume the short command body, then wait for owner-driven disconnect.
                        }
                    } catch (_: SocketException) {
                        // A reset is equivalent to EOF for this lifecycle contract.
                    }
                    connectionClosed.countDown()
                }
            }.onFailure(serverFailure::set)
        }.apply { start() }
        val profile = RemoteDeviceProfile(
            id = "final-owner-command",
            name = "Final owner command",
            kind = RemoteDeviceKind.OCTOPRINT,
            baseUrl = "http://127.0.0.1:${server.localPort}",
        )
        val store = ViewModelStore()
        var storeCleared = false
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[RemoteOperationViewModel::class.java]
            waitForRemoteState(model, "Remote profiles did not finish loading") {
                it.profilesLoaded && !it.busy
            }
            assertTrue(model.pause(profile, timeoutSeconds = 30))
            assertTrue(
                "Command did not reach its printer socket",
                requestAccepted.await(3, TimeUnit.SECONDS),
            )

            store.clear()
            storeCleared = true

            assertTrue(
                "Final remote owner did not close the blocked command socket",
                connectionClosed.await(3, TimeUnit.SECONDS),
            )
        } finally {
            if (!storeCleared) store.clear()
            worker.join(5_000)
            server.close()
        }
        serverFailure.get()?.let { throw AssertionError("Blocked command server failed", it) }
        assertFalse("Blocked command server did not stop", worker.isAlive)
    }

    private fun waitForRemoteState(
        model: RemoteOperationViewModel,
        failureMessage: String,
        condition: (RemoteOperationState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition(model.state.value) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue(failureMessage, condition(model.state.value))
    }

    @Test
    fun remoteProfileSaveAndSelectionSurviveActivityRecreation() {
        val profileId = "retained-profile-${UUID.randomUUID()}"
        val retainedModel = AtomicReference<RemoteOperationViewModel>()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                retainedModel.set(
                    ViewModelProvider(activity)[RemoteOperationViewModel::class.java],
                )
            }
            val model = retainedModel.get()
            waitForRemoteState(model, "Remote profiles did not finish loading") {
                it.profilesLoaded && !it.busy
            }
            scenario.onActivity {
                assertTrue(
                    model.saveProfile(
                        RemoteDeviceDraft(
                            id = profileId,
                            name = "Retained profile",
                            kind = RemoteDeviceKind.KLIPPER,
                            baseUrl = "http://127.0.0.1:7125",
                        ),
                    ),
                )
            }

            scenario.recreate()
            scenario.onActivity { recreated ->
                assertSame(
                    model,
                    ViewModelProvider(recreated)[RemoteOperationViewModel::class.java],
                )
            }
            waitForRemoteState(model, "Retained profile save did not finish") { state ->
                !state.busy && state.profiles.any { it.id == profileId }
            }
            assertEquals(profileId, model.state.value.selectedProfileId)

            assertTrue(model.deleteProfile(profileId))
            waitForRemoteState(model, "Retained profile cleanup did not finish") { state ->
                !state.busy && state.profiles.none { it.id == profileId }
            }
        }
    }

    @Test
    fun octoPrintStatusUsesApiKeyAndParsesProgress() {
        withServer(
            listOf(
                """{"state":"Printing","job":{"file":{"name":"duck.gcode"}},"progress":{"completion":42.8,"printTime":125,"printTimeLeft":245}}""",
                """{"temperature":{"tool0":{"actual":205.4,"target":210.0},"bed":{"actual":59.8,"target":60.0}}}""",
            ),
        ) { baseUrl, requests ->
            val profile = RemoteDeviceProfile("octo", "Workshop", RemoteDeviceKind.OCTOPRINT, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "octo-secret")

            assertEquals("Printing", status.state)
            assertEquals("duck.gcode", status.fileName)
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
    }

    @Test
    fun moonrakerStatusUsesApiKeyAndParsesProgress() {
        withServer(
            """{"result":{"status":{"print_stats":{"state":"printing","filename":"duck.gcode","print_duration":3600},"virtual_sdcard":{"progress":0.75},"extruder":{"temperature":214.5,"target":215.0},"heater_bed":{"temperature":64.8,"target":65.0}}}}""",
        ) { baseUrl, request ->
            val profile = RemoteDeviceProfile("klipper", "Workshop", RemoteDeviceKind.KLIPPER, baseUrl)
            val status = RemoteDeviceClient(2_000).status(profile, "moonraker-secret")

            assertEquals("printing", status.state)
            assertEquals("duck.gcode", status.fileName)
            assertEquals(75, status.progressPercent)
            assertEquals(214.5, status.nozzleTemperatureC)
            assertEquals(215.0, status.nozzleTargetC)
            assertEquals(64.8, status.bedTemperatureC)
            assertEquals(65.0, status.bedTargetC)
            assertEquals(3_600L, status.elapsedSeconds)
            assertEquals(1_200L, status.remainingSeconds)
            val rawRequest = request.get()
            assertTrue(
                rawRequest.startsWith(
                    "GET /printer/objects/query?print_stats&virtual_sdcard&extruder&heater_bed HTTP/1.1",
                ),
            )
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
            assertTrue(requests.all { it.get().contains("Host: printer.local:$port", ignoreCase = true) })
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
}
