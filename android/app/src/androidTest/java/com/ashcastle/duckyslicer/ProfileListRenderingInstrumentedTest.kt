package com.ashcastle.duckyslicer

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileListRenderingInstrumentedTest {
    @Test
    fun expandedLargeCatalogOnlyComposesVisibleProfiles() {
        val entries = (0 until 2_000).map { "Profile $it" }
        val composed = ConcurrentHashMap.newKeySet<String>()
        ActivityScenario.launch(AccessibilityHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize()) {
                            SearchableGroupedProfileChoices(
                                entries = entries,
                                selected = entries.first(),
                                recentIds = listOf(entries.first()),
                                id = { it },
                                name = { it },
                                label = { entry ->
                                    SideEffect { composed.add(entry) }
                                    entry
                                },
                                brand = { "Example" },
                                builtIn = { true },
                                searchTerms = { listOf(it) },
                                onSelected = {},
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (composed.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(50)
            }
            assertTrue("The catalog must render profiles", composed.isNotEmpty())
            assertTrue("Offscreen profiles must remain uncomposed: ${composed.size}", composed.size < 100)
        }
    }
}
