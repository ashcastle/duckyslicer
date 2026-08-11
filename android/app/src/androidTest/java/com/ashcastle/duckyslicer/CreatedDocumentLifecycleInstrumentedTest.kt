package com.ashcastle.duckyslicer

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreatedDocumentLifecycleInstrumentedTest {
    @Test
    fun supportReportExportSurvivesActivityRecreationAndRejectsDuplicateWork() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK,
            null,
            null,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retained: SupportReportExportViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[SupportReportExportViewModel::class.java]
                    assertTrue(retained.export(BlockingExportProvider.URI, AppSettings()))
                }
                waitForProvider { it.getBoolean(BlockingExportProvider.KEY_STARTED) }
                assertTrue(retained.state.value.busy)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[SupportReportExportViewModel::class.java],
                    )
                    assertFalse(retained.export(BlockingExportProvider.URI, AppSettings()))
                }
                releaseProvider()
                waitUntil("support report export did not finish") {
                    !retained.state.value.busy
                }
                assertTrue(requireNotNull(retained.state.value.completion).succeeded)
                val status = waitForProvider {
                    it.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertFalse(status.getBoolean(BlockingExportProvider.KEY_DELETED))
                assertEquals("", status.getString(BlockingExportProvider.KEY_ERROR))
                assertTrue(status.getInt(BlockingExportProvider.KEY_BYTES) in 1..MAX_SUPPORT_REPORT_BYTES)
                assertTrue(status.getString(BlockingExportProvider.KEY_SHA256).orEmpty().isNotBlank())
            }
        } finally {
            releaseProvider()
        }
    }

    @Test
    fun failedProjectArchiveExportDeletesTheNewDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_PREPARE_FAILURE,
            null,
            null,
        )
        val store = ViewModelStore()
        try {
            val application = context.applicationContext as Application
            val model = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProjectTransferViewModel::class.java]
            waitUntil("project session did not restore") {
                model.state.value.restored && !model.state.value.busy
            }

            assertTrue(
                model.exportProject(
                    BlockingExportProvider.URI,
                    ProjectSnapshot(),
                    model.state.value.sliceOptions,
                ),
            )
            waitUntil("failed project export did not complete") {
                model.state.value.completion != null
            }
            val completion = model.state.value.completion
            assertTrue(completion is ProjectTransferCompletion.Failed)
            assertEquals(
                ProjectTransferDirection.EXPORT,
                (completion as ProjectTransferCompletion.Failed).direction,
            )
            val status = waitForProvider {
                it.getBoolean(BlockingExportProvider.KEY_DELETED)
            }
            assertTrue(status.getBoolean(BlockingExportProvider.KEY_COMPLETED))
        } finally {
            store.clear()
            releaseProvider()
        }
    }

    private fun releaseProvider() {
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.call(
            BlockingExportProvider.URI,
            BlockingExportProvider.METHOD_RELEASE,
            null,
            null,
        )
    }

    private fun waitForProvider(condition: (Bundle) -> Boolean): Bundle {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        var latest = Bundle.EMPTY
        waitUntil("blocking export provider did not reach the expected state") {
            latest = requireNotNull(
                resolver.call(
                    BlockingExportProvider.URI,
                    BlockingExportProvider.METHOD_STATUS,
                    null,
                    null,
                ),
            )
            condition(latest)
        }
        return latest
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError(message)
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS = 15_000L
        const val WAIT_POLL_MILLIS = 25L
    }
}
