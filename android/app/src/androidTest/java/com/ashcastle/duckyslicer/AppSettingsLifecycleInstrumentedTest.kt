package com.ashcastle.duckyslicer

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsLifecycleInstrumentedTest {
    @Test
    fun backgroundingFlushesLatestSettingsBeforeDebounce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retained: AppSettingsViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[AppSettingsViewModel::class.java]
                }
                assertTrue(
                    retained.updateSettings(
                        retained.state.value.settings.copy(
                            previewDetail = PreviewDetail.DETAIL,
                            toolpathDepthContrast = 0.41f,
                            confirmBeforeRemotePrint = false,
                        ),
                    ),
                )
                val expectedRevision = retained.state.value.revision

                // onStop must replace the pending 350 ms write with an immediate one.
                scenario.onActivity { activity ->
                    assertTrue(activity.moveTaskToBack(true))
                }

                waitUntil("background settings flush did not become durable") {
                    retained.state.value.persistedRevision == expectedRevision &&
                        AppSettingsStore(context).load().let { stored ->
                            stored.previewDetail == PreviewDetail.DETAIL &&
                                stored.toolpathDepthContrast == 0.41f &&
                                !stored.confirmBeforeRemotePrint
                        }
                }
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun latestUnsavedSettingsSurviveImmediateActivityRecreationAndPersist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retained: AppSettingsViewModel
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[AppSettingsViewModel::class.java]
                }
                assertTrue(
                    retained.updateSettings(
                        retained.state.value.settings.copy(toolpathOpacity = 0.51f),
                    ),
                )
                assertTrue(
                    retained.updateSettings(
                        retained.state.value.settings.copy(
                            toolpathOpacity = 0.63f,
                            connectionTimeoutSeconds = 27,
                        ),
                    ),
                )

                // Recreate before the 350 ms persistence debounce can run.
                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retained,
                        ViewModelProvider(recreated)[AppSettingsViewModel::class.java],
                    )
                }
                assertEquals(0.63f, retained.state.value.settings.toolpathOpacity)
                assertEquals(27, retained.state.value.settings.connectionTimeoutSeconds)
                assertEquals(2L, retained.state.value.revision)

                waitUntil("latest retained settings did not become durable") {
                    AppSettingsStore(context).load().let { stored ->
                        stored.toolpathOpacity == 0.63f && stored.connectionTimeoutSeconds == 27
                    }
                }
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25L)
        }
        throw AssertionError(message)
    }
}
