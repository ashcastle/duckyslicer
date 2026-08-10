package com.ashcastle.duckyslicer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun appSettingsExposeNamedSlidersWholeRowSwitchesAndHeadings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sliderLabels = setOf(
            context.getString(R.string.toolpath_visibility_control),
            context.getString(R.string.toolpath_depth_contrast_control),
            context.getString(R.string.connection_timeout_control),
        )
        val switchLabels = setOf(
            context.getString(R.string.keep_screen_awake),
            context.getString(R.string.confirm_remote_print),
        )
        launchHarness(AccessibilityHarnessActivity.SCREEN_SETTINGS).use {
            val nodes = waitForNodes(sliderLabels + switchLabels)
            sliderLabels.forEach { label ->
                assertEquals(
                    "$label must identify exactly one adjustable Settings control",
                    1,
                    nodes.count {
                        it.className?.toString() == SEEK_BAR_CLASS &&
                            it.effectiveLabel().contains(label)
                    },
                )
            }
            switchLabels.forEach { label ->
                assertEquals(
                    "$label must expose one whole-row toggle action",
                    1,
                    nodes.count { it.isClickable && it.isCheckable && it.effectiveLabel().contains(label) },
                )
            }
            assertTrue(
                "Settings must be navigable by screen-reader headings",
                nodes.any {
                    it.isHeading && it.effectiveLabel().contains(context.getString(R.string.preview_settings))
                },
            )
        }
    }

    @Test
    fun appSettingsOpenTheBundledPrivacyPolicyOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privacyLabel = context.getString(R.string.privacy_policy)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SETTINGS).use {
            val privacyButton = scrollUntilClickable(privacyLabel)
            assertTrue(
                "The privacy policy action must open its bundled document",
                privacyButton.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            val nodes = waitForNodes(setOf(PRIVACY_DOCUMENT_HEADING))
            assertTrue(
                "The offline privacy document must expose its English heading",
                nodes.any { it.effectiveLabel().contains(PRIVACY_DOCUMENT_HEADING) },
            )
        }
    }

    @Test
    fun appSettingsExposeAVisibleSupportDetailsAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supportLabel = context.getString(R.string.save_support_details)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SETTINGS).use {
            val supportButton = scrollUntilClickable(supportLabel)
            assertTrue("Support details must be a visible user action", supportButton.isVisibleToUser)
            assertTrue("Support details must be keyboard and switch-access focusable", supportButton.isFocusable)
        }
    }

    @Test
    fun projectActionsAreVisibleAndOpeningConfirmsReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val openLabel = context.getString(R.string.open_project)
        val saveLabel = context.getString(R.string.save_project)
        val confirmation = context.getString(R.string.replace_project_title)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT).use {
            val nodes = waitForNodes(setOf(openLabel, saveLabel))
            val open = nodes.firstOrNull { it.isClickable && it.effectiveLabel().contains(openLabel) }
            val save = nodes.firstOrNull { it.isClickable && it.effectiveLabel().contains(saveLabel) }
            assertNotNull("Open project must be an explicit action", open)
            assertNotNull("Save project must be an explicit action", save)
            assertTrue(checkNotNull(open).isFocusable)
            assertTrue(checkNotNull(save).isFocusable)
            assertTrue(open.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Replacing a non-empty project must require confirmation",
                waitForNodes(setOf(confirmation)).any { it.effectiveLabel().contains(confirmation) },
            )
        }
    }

    @Test
    fun largeTextLandscapeKeepsMenuClearOfScrollableWorkspaceSheet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val menuLabel = context.getString(R.string.menu)
        val profileLabel = context.getString(R.string.printer_profile)
        val profilesHeading = context.getString(R.string.profiles)
        launchHarness(AccessibilityHarnessActivity.SCREEN_WORKSPACE).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            val nodes = waitForNodes(
                setOf(menuLabel, profileLabel, profilesHeading),
                requireLandscape = true,
            )
            val menu = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel().contains(menuLabel)
            }
            val printerProfile = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel().contains(profileLabel)
            }
            assertNotNull("The import menu must remain actionable at 200% text in landscape", menu)
            assertNotNull("The workspace sheet must remain actionable at 200% text in landscape", printerProfile)
            checkNotNull(menu)
            checkNotNull(printerProfile)
            assertTrue("The import menu must remain visible to accessibility services", menu.isVisibleToUser)
            assertTrue("The profile row must remain visible to accessibility services", printerProfile.isVisibleToUser)
            assertTrue("The import menu must remain keyboard and switch-access focusable", menu.isFocusable)
            assertTrue("The profile row must remain keyboard and switch-access focusable", printerProfile.isFocusable)
            assertTrue(
                "The height-limited sheet must not cover the import menu: " +
                    "menu=${menu.screenBounds()}, profile=${printerProfile.screenBounds()}",
                !Rect.intersects(menu.screenBounds(), printerProfile.screenBounds()),
            )
            assertTrue(
                "Profiles must expose a heading for rotor navigation",
                nodes.any { it.isHeading && it.effectiveLabel().contains(profilesHeading) },
            )
            assertTrue(
                "Reading order must reach the menu before the scrollable profile sheet",
                nodes.indexOf(menu) < nodes.indexOf(printerProfile),
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

    private fun waitForNodes(
        labels: Set<String>,
        requireLandscape: Boolean = false,
    ): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val nodes = currentNodes()
            val windowBounds = nodes.firstOrNull()?.screenBounds()
            val orientationMatches = !requireLandscape ||
                (windowBounds != null && windowBounds.width() > windowBounds.height())
            if (
                orientationMatches &&
                labels.all { expected -> nodes.any { it.effectiveLabel().contains(expected) } }
            ) {
                return nodes
            }
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for accessibility labels: $labels")
    }

    private fun scrollUntilClickable(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val nodes = currentNodes()
            nodes.firstOrNull { node ->
                node.isClickable && node.isVisibleToUser && node.effectiveLabel().contains(label)
            }?.let { return it }
            val scrollable = nodes.firstOrNull { node ->
                node.actionList.any { action ->
                    action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
            }
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out scrolling to accessibility action: $label")
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

    private fun AccessibilityNodeInfo.screenBounds(): Rect = Rect().also(::getBoundsInScreen)

    private companion object {
        const val SEEK_BAR_CLASS = "android.widget.SeekBar"
        const val PRIVACY_DOCUMENT_HEADING = "DuckySlicer Privacy Policy"
        const val MAX_LABEL_DEPTH = 12
        const val NODE_TIMEOUT_MILLIS = 5_000L
        const val NODE_POLL_MILLIS = 50L
    }
}
