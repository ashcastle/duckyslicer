package com.ashcastle.duckyslicer

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityInstrumentedTest {
    @Test
    fun previewRangeAndDisplaySlidersExposeDistinctNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previewLabels = setOf(
            context.getString(R.string.first_visible_layer),
            context.getString(R.string.last_visible_layer),
            context.getString(R.string.toolpath_visibility_control),
            context.getString(R.string.toolpath_depth_contrast_control),
        )
        launchHarness(AccessibilityHarnessActivity.SCREEN_PREVIEW).use {
            val nodes = waitForNodes(previewLabels)
            previewLabels.forEach { label ->
                assertEquals(
                    "$label must identify exactly one adjustable preview control",
                    1,
                    nodes.count {
                        it.className?.toString() == SEEK_BAR_CLASS &&
                            it.effectiveLabel().contains(label)
                    },
                )
            }
            assertEquals(
                "The layer range must not retain Material's two generic duplicate controls",
                previewLabels.size,
                nodes.count { it.className?.toString() == SEEK_BAR_CLASS },
            )
        }
    }

    @Test
    fun profileSliderAndSwitchExposeTheirSettingNamesOnce() {
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROFILE).use {
            val nodes = waitForNodes(setOf(TEST_SETTING_LABEL, TEST_SWITCH_LABEL))
            assertEquals(
                1,
                nodes.count {
                    it.className?.toString() == SEEK_BAR_CLASS &&
                        it.effectiveLabel().contains(TEST_SETTING_LABEL)
                },
            )
            assertEquals(
                "The switch row and its visual switch must not create duplicate actions",
                1,
                nodes.count { it.isClickable && it.effectiveLabel().contains(TEST_SWITCH_LABEL) },
            )
            assertNotNull(nodes.firstOrNull { it.isCheckable && it.effectiveLabel().contains(TEST_SWITCH_LABEL) })
        }
    }

    @Test
    fun remoteDeviceProfileExposesOneNamedRadioAction() {
        launchHarness(AccessibilityHarnessActivity.SCREEN_DEVICE).use {
            val nodes = waitForNodes(setOf(TEST_DEVICE_LABEL))
            val labeledNodes = nodes.filter { it.effectiveLabel().contains(TEST_DEVICE_LABEL) }
            val selectionActions = labeledNodes.filter {
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            assertEquals(
                "A remote printer row must expose one selection action, not a nested duplicate: " +
                    labeledNodes.joinToString { node ->
                        "${node.className}/${node.isClickable}/${node.actionList.map { it.id }}/" +
                            node.effectiveLabel()
                    },
                1,
                selectionActions.size,
            )
        }
    }

    private fun launchHarness(screen: String): ActivityScenario<AccessibilityHarnessActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ActivityScenario.launch(
            Intent(context, AccessibilityHarnessActivity::class.java)
                .putExtra(AccessibilityHarnessActivity.EXTRA_SCREEN, screen),
        )
    }

    private fun waitForNodes(labels: Set<String>): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val nodes = currentNodes()
            if (labels.all { expected -> nodes.any { it.effectiveLabel().contains(expected) } }) {
                return nodes
            }
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for accessibility labels: $labels")
    }

    private fun currentNodes(): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(::collect) }
        }
        collect(root)
        return result
    }

    private fun AccessibilityNodeInfo.effectiveLabel(depth: Int = 0): String {
        if (depth > MAX_LABEL_DEPTH) return ""
        val parts = ArrayList<String>()
        contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
        text?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
        repeat(childCount) { index ->
            getChild(index)?.effectiveLabel(depth + 1)?.takeIf(String::isNotBlank)?.let(parts::add)
        }
        return parts.distinct().joinToString(" ")
    }

    private companion object {
        const val SEEK_BAR_CLASS = "android.widget.SeekBar"
        const val MAX_LABEL_DEPTH = 12
        const val NODE_TIMEOUT_MILLIS = 5_000L
        const val NODE_POLL_MILLIS = 50L
    }
}
