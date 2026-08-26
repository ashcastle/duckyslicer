package com.ashcastle.duckyslicer

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileBundleLifecycleInstrumentedTest {
    @get:Rule
    val blockingProviderProcess = BlockingProviderProcessRule()

    @Test
    fun profileExportSurvivesRecreationAndWritesTheExactBoundedBundle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetTargetProfiles()
        val targetStore = ProfileStore(context)
        targetStore.savePrinter("Device portable printer", SliceOptions())
        val expected = targetStore.exportBundle()
        prepareExport(BlockingExportProvider.METHOD_PREPARE)
        try {
            launchHarness().use { scenario ->
                lateinit var retained: ProfileLibraryViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[ProfileLibraryViewModel::class.java]
                }
                waitUntil("profile library did not load") { retained.state.value.catalogLoaded && !retained.state.value.busy }
                scenario.onActivity {
                    assertTrue(retained.exportBundle(BlockingExportProvider.URI))
                }
                waitForExportProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[ProfileLibraryViewModel::class.java],
                    )
                    assertFalse(retained.exportBundle(BlockingExportProvider.URI))
                }
                releaseExportProvider()
                waitUntil("profile export did not finish") {
                    retained.state.value.transferCompletion?.outcome ==
                        ProfileTransferOutcome.SUCCEEDED
                }
                val status = waitForExportProvider {
                    it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertFalse(status.getBoolean(BlockingExportProvider.KEY_DELETED))
                assertEquals(expected.size, status.getInt(BlockingExportProvider.KEY_BYTES))
                assertEquals(sha256(expected), status.getString(BlockingExportProvider.KEY_SHA256))
            }
        } finally {
            releaseExportProvider()
            resetTargetProfiles()
        }
    }

    @Test
    fun profileExportCancellationSurvivesRecreationAndDeletesThePartialDocument() {
        resetTargetProfiles()
        prepareExport(BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK)
        try {
            launchHarness().use { scenario ->
                lateinit var retained: ProfileLibraryViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[ProfileLibraryViewModel::class.java]
                }
                waitUntil("profile library did not load") { retained.state.value.catalogLoaded && !retained.state.value.busy }
                scenario.onActivity {
                    assertTrue(retained.exportBundle(BlockingExportProvider.URI))
                }
                waitForExportProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[ProfileLibraryViewModel::class.java],
                    )
                    assertTrue(retained.cancelTransfer())
                    assertFalse(retained.cancelTransfer())
                }
                waitUntil("profile export cancellation did not settle") {
                    retained.state.value.transferCompletion?.outcome ==
                        ProfileTransferOutcome.CANCELED
                }
                val status = waitForExportProvider {
                    it.getBoolean(BlockingExportProvider.KEY_DELETED) &&
                        it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertEquals(0, status.getInt(BlockingExportProvider.KEY_BYTES))
                assertEquals(
                    "OperationCanceledException",
                    status.getString(BlockingExportProvider.KEY_ERROR),
                )
            }
        } finally {
            releaseExportProvider()
            resetTargetProfiles()
        }
    }

    @Test
    fun profileImportCancellationSurvivesRecreationAndPreservesSavedProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetTargetProfiles()
        val targetStore = ProfileStore(context)
        targetStore.savePrinter("Keep device printer", SliceOptions())
        val targetFile = context.filesDir.resolve("profiles/user_profiles.json")
        val original = targetFile.readBytes()
        val fixture = profileBundleFixture("Incoming device filament")
        prepareImport(BlockingImportProvider.METHOD_PREPARE, fixture)
        try {
            launchHarness().use { scenario ->
                lateinit var retained: ProfileLibraryViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[ProfileLibraryViewModel::class.java]
                }
                waitUntil("profile library did not load") { retained.state.value.catalogLoaded && !retained.state.value.busy }
                scenario.onActivity {
                    assertTrue(retained.importBundle(BlockingImportProvider.URI))
                }
                waitForImportProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[ProfileLibraryViewModel::class.java],
                    )
                    assertTrue(retained.cancelTransfer())
                    assertFalse(retained.cancelTransfer())
                }
                waitUntil("profile import cancellation did not settle") {
                    retained.state.value.transferCompletion?.outcome ==
                        ProfileTransferOutcome.CANCELED
                }
                waitForImportProvider { it.getBoolean(BlockingImportProvider.KEY_COMPLETED) }
                assertTrue(original.contentEquals(targetFile.readBytes()))
                val catalog = ProfileStore(context).load()
                assertTrue(catalog.printers.any { it.name == "Keep device printer" })
                assertFalse(catalog.filaments.any { it.name == "Incoming device filament" })
            }
        } finally {
            releaseImportProvider()
            fixture.parentFile?.deleteRecursively()
            resetTargetProfiles()
        }
    }

    @Test
    fun providerBackedProfileImportPublishesTheMergedCatalogOnlyAfterCommit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetTargetProfiles()
        val fixture = profileBundleFixture("Imported device filament")
        prepareImport(BlockingImportProvider.METHOD_PREPARE, fixture)
        try {
            launchHarness().use { scenario ->
                lateinit var retained: ProfileLibraryViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[ProfileLibraryViewModel::class.java]
                }
                waitUntil("profile library did not load") { retained.state.value.catalogLoaded && !retained.state.value.busy }
                scenario.onActivity {
                    assertTrue(retained.importBundle(BlockingImportProvider.URI))
                }
                waitForImportProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }
                assertFalse(retained.state.value.catalog.filaments.any {
                    it.name == "Imported device filament"
                })

                releaseImportProvider()
                waitUntil("profile import did not finish") {
                    retained.state.value.transferCompletion?.outcome ==
                        ProfileTransferOutcome.SUCCEEDED
                }
                assertEquals(1, retained.state.value.transferCompletion?.importResult?.importedTotal)
                assertTrue(retained.state.value.catalog.filaments.any {
                    it.name == "Imported device filament"
                })
                assertTrue(ProfileStore(context).load().filaments.any {
                    it.name == "Imported device filament"
                })
            }
        } finally {
            releaseImportProvider()
            fixture.parentFile?.deleteRecursively()
            resetTargetProfiles()
        }
    }

    @Test
    fun providerBackedImportRenamesAConflictingProfileWithoutReplacingEitherVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetTargetProfiles()
        val targetStore = ProfileStore(context)
        targetStore.saveFilament("Conflict material", SliceOptions())
        val fixture = profileBundleFixture(
            "conflict material",
            SliceOptions().copy(nozzleTemp = 237, flowRatio = 0.96f),
        )
        prepareImport(BlockingImportProvider.METHOD_PREPARE, fixture)
        try {
            launchHarness().use { scenario ->
                lateinit var retained: ProfileLibraryViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[ProfileLibraryViewModel::class.java]
                }
                waitUntil("profile library did not load") {
                    retained.state.value.catalogLoaded && !retained.state.value.busy
                }
                scenario.onActivity {
                    assertTrue(retained.importBundle(BlockingImportProvider.URI))
                }
                waitForImportProvider { it.getBoolean(BlockingImportProvider.KEY_STARTED) }
                releaseImportProvider()
                waitUntil("conflicting profile import did not finish") {
                    retained.state.value.transferCompletion?.outcome ==
                        ProfileTransferOutcome.SUCCEEDED
                }

                val result = requireNotNull(
                    retained.state.value.transferCompletion?.importResult,
                )
                assertEquals(1, result.importedTotal)
                assertEquals(1, result.renamedConflicts)
                val stored = ProfileStore(context).load().filaments.filterNot { it.builtIn }
                assertEquals(2, stored.size)
                assertEquals(220, stored.single { it.name == "Conflict material" }.nozzleTemp)
                val imported = stored.single { it.name == "conflict material (2)" }
                assertEquals(237, imported.nozzleTemp)
                assertEquals(0.96f, imported.flowRatio)
            }
        } finally {
            releaseImportProvider()
            fixture.parentFile?.deleteRecursively()
            resetTargetProfiles()
        }
    }

    private fun profileBundleFixture(name: String, options: SliceOptions = SliceOptions()): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.cacheDir.resolve("profile-bundle-${UUID.randomUUID()}")
        val source = ProfileStore(directory.resolve("user_profiles.json"))
        source.saveFilament(name, options)
        return directory.resolve("fixture$PROFILE_BUNDLE_FILE_EXTENSION").apply {
            writeBytes(source.exportBundle())
        }
    }

    private fun prepareImport(method: String, fixture: File) {
        ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val extras = Bundle().apply {
                putParcelable(BlockingImportProvider.KEY_SOURCE_DESCRIPTOR, descriptor)
            }
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                BlockingImportProvider.URI,
                method,
                null,
                extras,
            )
        }
    }

    private fun prepareExport(method: String) {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingExportProvider.URI,
            method,
            null,
            null,
        )
    }

    private fun launchHarness(): ActivityScenario<AccessibilityHarnessActivity> =
        ActivityScenario.launch(AccessibilityHarnessActivity::class.java)

    private fun releaseImportProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingImportProvider.URI,
            BlockingImportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun releaseExportProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun waitForImportProvider(predicate: (Bundle) -> Boolean): Bundle =
        waitForProvider(BlockingImportProvider.URI, BlockingImportProvider.METHOD_STATUS, predicate)

    private fun waitForExportProvider(predicate: (Bundle) -> Boolean): Bundle =
        waitForProvider(BlockingExportProvider.URI, BlockingExportProvider.METHOD_STATUS, predicate)

    private fun waitForProvider(
        uri: android.net.Uri,
        method: String,
        predicate: (Bundle) -> Boolean,
    ): Bundle {
        var status = Bundle.EMPTY
        waitUntil("blocking profile provider did not reach the expected state") {
            status = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
                uri,
                method,
                null,
                null,
            ) ?: Bundle.EMPTY
            predicate(status)
        }
        return status
    }

    private fun resetTargetProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.filesDir.resolve("profiles").deleteRecursively()
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError(message)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS = 30_000L
        const val WAIT_POLL_MILLIS = 50L
    }
}
