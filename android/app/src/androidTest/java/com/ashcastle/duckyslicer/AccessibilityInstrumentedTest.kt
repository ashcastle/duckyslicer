package com.ashcastle.duckyslicer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
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
    fun cancelSupportDetailsSaveActionIsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.stop_support_details_save)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SUPPORT_EXPORT).use {
            val cancelButton = scrollUntilClickable(label, fastScroll = true)
            assertTrue(
                "The support-details cancel action must be visible",
                cancelButton.isVisibleToUser,
            )
            assertTrue(
                "The support-details cancel action must be keyboard and switch-access focusable",
                cancelButton.isFocusable,
            )
            val nodes = waitForNodes(setOf(label))
            assertEquals(
                "An active support-details save must expose one named cancel action",
                1,
                nodes.count { it.isClickable && it.effectiveLabel().contains(label) },
            )
        }
    }

    @Test
    fun cancelGcodeExportActionIsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.cancel_gcode_export)
        launchHarness(AccessibilityHarnessActivity.SCREEN_GCODE_EXPORT).use {
            val nodes = waitForNodes(setOf(label))
            assertEquals(
                "An active G-code export must expose one named cancel action",
                1,
                nodes.count { it.isClickable && it.effectiveLabel().contains(label) },
            )
        }
    }

    @Test
    fun cancelProjectExportActionIsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.cancel_project_export)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT_EXPORT).use {
            val nodes = waitForNodes(setOf(label))
            assertEquals(
                "An active project export must expose one named cancel action",
                1,
                nodes.count { it.isClickable && it.effectiveLabel().contains(label) },
            )
        }
    }

    @Test
    fun cancelProjectImportActionIsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.cancel_project_import)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT_IMPORT).use {
            val nodes = waitForNodes(setOf(label))
            assertEquals(
                "An active project import must expose one named cancel action",
                1,
                nodes.count { it.isClickable && it.effectiveLabel().contains(label) },
            )
        }
    }

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
    fun activeRemoteRequestExposesOneNamedStopAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stopLabel = context.getString(R.string.stop_remote_request)
        launchHarness(AccessibilityHarnessActivity.SCREEN_REMOTE_REQUEST).use {
            val nodes = waitForNodes(setOf(stopLabel))
            val actions = nodes.filter {
                it.isVisibleToUser && it.isFocusable && it.isClickable &&
                    it.effectiveLabel() == stopLabel
            }
            assertEquals(
                "An active remote request must expose one visible, focusable stop action",
                1,
                actions.size,
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
    fun appSettingsExposeSliceNotificationStateAndSystemSettingsAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actionLabel = context.getString(R.string.manage_slice_notifications)
        val expectedState = context.getString(
            if (sliceNotificationsEnabled(context)) {
                R.string.slice_notifications_on
            } else {
                R.string.slice_notifications_off
            },
        )
        launchHarness(AccessibilityHarnessActivity.SCREEN_SETTINGS).use {
            val action = scrollUntilClickable(actionLabel)
            assertTrue(action.effectiveLabel().contains(actionLabel))
            assertTrue(
                "Settings must expose the current slice notification state",
                currentNodes().any {
                    it.isVisibleToUser && it.effectiveLabel().contains(expectedState)
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
    fun appSettingsStreamTheLargeThirdPartyNoticeOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val noticesLabel = context.getString(R.string.third_party_notices)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SETTINGS).use {
            val noticesButton = scrollUntilClickable(noticesLabel, fastScroll = true)
            assertTrue(
                "The third-party notice action must open its bundled document",
                noticesButton.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            val nodes = waitForNodes(setOf(THIRD_PARTY_DOCUMENT_HEADING))
            assertTrue(
                "The streamed third-party document must expose its heading",
                nodes.any { it.effectiveLabel().contains(THIRD_PARTY_DOCUMENT_HEADING) },
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

    @Test
    fun objectSettingsExposeOrcaCategoriesAndStickyThirtySeventyActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.object_process_settings)
        val layerHeight = context.getString(R.string.layer_height)
        val speed = context.getString(R.string.speed)
        val outerWallSpeed = context.getString(R.string.outer_wall_speed)
        val revert = context.getString(R.string.revert_changes)
        val apply = context.getString(R.string.apply_changes)
        launchHarness(AccessibilityHarnessActivity.SCREEN_OBJECT_SETTINGS).use {
            var nodes = waitForNodes(setOf(title, layerHeight, speed))
            val layerOverride = nodes.firstOrNull {
                it.isCheckable && it.isClickable && it.effectiveLabel().contains(layerHeight)
            }
            assertNotNull("Layer height must expose an object override switch", layerOverride)
            tapCenter(checkNotNull(layerOverride))
            SystemClock.sleep(300)
            val overrideLabel = context.getString(R.string.object_setting_override_value, "0.20 mm")
            val afterToggle = currentNodes()
            assertTrue(
                "Layer override tap did not change the draft: bounds=${layerOverride.screenBounds()} " +
                    "labels=${afterToggle.map { it.effectiveLabel() }.filter(String::isNotBlank)}",
                afterToggle.any { it.effectiveLabel().contains(overrideLabel) },
            )

            nodes = waitForNodes(setOf(revert, apply))
            val revertButton = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel().contains(revert)
            }
            val applyButton = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel().contains(apply)
            }
            assertNotNull("Dirty object settings must expose Revert", revertButton)
            assertNotNull("Dirty object settings must expose Apply", applyButton)
            checkNotNull(revertButton)
            checkNotNull(applyButton)
            assertTrue(
                "Apply must retain the requested 70/30 visual priority",
                applyButton.screenBounds().width() > revertButton.screenBounds().width() * 2,
            )

            val speedTab = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel() == speed
            }
            assertNotNull("Object settings must expose the Speed category", speedTab)
            tapCenter(checkNotNull(speedTab))
            assertTrue(
                waitForNodes(setOf(outerWallSpeed)).any {
                    it.effectiveLabel().contains(outerWallSpeed)
                },
            )
        }
    }

    @Test
    fun shapePickerExposesEveryOrcaPrimitiveAndSizeControl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.add_shape)
        val cube = context.getString(R.string.shape_cube)
        val cylinder = context.getString(R.string.shape_cylinder)
        val sphere = context.getString(R.string.shape_sphere)
        val cone = context.getString(R.string.shape_cone)
        val disc = context.getString(R.string.shape_disc)
        val torus = context.getString(R.string.shape_torus)
        val size = context.getString(R.string.shape_size)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SHAPES).use {
            val nodes = waitForNodes(
                setOf(title, cube, cylinder, sphere, cone, disc, torus, size),
            )
            listOf(cube, cylinder, sphere, cone, disc, torus).forEach { label ->
                assertTrue(
                    "$label must be selectable",
                    nodes.any { it.isClickable && it.effectiveLabel() == label },
                )
            }
            assertTrue(
                "Shape size must expose an adjustable control",
                nodes.any {
                    it.className?.toString() == SEEK_BAR_CLASS &&
                        it.effectiveLabel().contains(size)
                },
            )
            assertTrue(
                "The selected shape must expose Add",
                nodes.any { it.isClickable && it.effectiveLabel() == title },
            )
        }
    }

    @Test
    fun modelTransformExposesIndependentAxesAndProportionLock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val more = context.getString(R.string.more_settings)
        val placement = context.getString(R.string.model_placement)
        val keepProportions = context.getString(R.string.keep_proportions)
        val axisLabels = listOf(
            context.getString(R.string.scale_x),
            context.getString(R.string.scale_y),
            context.getString(R.string.scale_z),
        )
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            val workspaceNodes = waitForNodes(setOf(more))
            val moreButton = workspaceNodes.firstOrNull {
                it.isClickable && it.effectiveLabel() == more
            }
            assertNotNull("A selected object must expose the model tools", moreButton)
            tapCenter(checkNotNull(moreButton))
            waitForNodes(setOf(placement))

            val proportionLock = scrollUntilClickable(keepProportions)
            assertTrue(
                "The proportion lock must expose one switch action",
                proportionLock.isCheckable,
            )
            axisLabels.forEach { label ->
                val axisControl = scrollUntilNode(label) {
                    it.className?.toString() == SEEK_BAR_CLASS
                }
                assertTrue(
                    "$label must remain visible and adjustable after scrolling",
                    axisControl.isVisibleToUser,
                )
            }
        }
    }

    @Test
    fun selectedObjectExposesPlaceOnFaceModeAndTouchGuidance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val placeOnFace = context.getString(R.string.lay_on_face)
        val hint = context.getString(R.string.lay_on_face_hint)
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            val tool = waitForNodes(setOf(placeOnFace)).firstOrNull {
                it.isClickable && it.effectiveLabel().contains(placeOnFace)
            }
            assertNotNull("A selected object must expose Place on face", tool)
            tapCenter(checkNotNull(tool))
            assertTrue(
                "Place on face mode must explain the next touch action",
                waitForNodes(setOf(hint)).any { it.effectiveLabel().contains(hint) },
            )
        }
    }

    @Test
    fun selectedObjectExposesMeasureModeAndTouchGuidance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val measure = context.getString(R.string.measure_model)
        val hint = context.getString(R.string.measure_hint)
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            val tool = waitForNodes(setOf(measure)).firstOrNull {
                it.isClickable && it.effectiveLabel().contains(measure)
            }
            assertNotNull("A selected object must expose Measure", tool)
            tapCenter(checkNotNull(tool))
            val nodes = waitForNodes(setOf(hint))
            assertTrue(
                "Measure mode must explain how to choose both surface points",
                nodes.any { it.effectiveLabel().contains(hint) },
            )
            val heading = waitForNode(measure) { it.isHeading }
            assertTrue(
                "Measure must expose its result panel as a navigable heading",
                heading.effectiveLabel().contains(measure),
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

    private fun scrollUntilClickable(
        label: String,
        fastScroll: Boolean = false,
    ): AccessibilityNodeInfo {
        return scrollUntilNode(label, fastScroll) { it.isClickable }
    }

    private fun waitForNode(
        label: String,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        do {
            currentNodes().firstOrNull { node ->
                matches(node) && node.isVisibleToUser && node.effectiveLabel().contains(label)
            }?.let { return it }
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for accessibility node: $label")
    }

    private fun scrollUntilNode(
        label: String,
        fastScroll: Boolean = false,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        do {
            val nodes = currentNodes()
            nodes.firstOrNull { node ->
                matches(node) && node.isVisibleToUser && node.effectiveLabel().contains(label)
            }?.let { return it }
            val scrollable = nodes.asSequence()
                .filter { node -> node.isVisibleToUser }
                .filter { node ->
                    node.actionList.any { action ->
                        action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    }
                }
                .maxByOrNull { node ->
                    val bounds = node.screenBounds()
                    bounds.width().toLong() * bounds.height()
            }
            if (scrollable != null) {
                swipeForward(scrollable, fastScroll)
            }
            SystemClock.sleep(SCROLL_SETTLE_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out scrolling to accessibility action: $label")
    }

    private fun currentNodes(): List<AccessibilityNodeInfo> {
        // The 3D workspace continuously renders; bounded polling must not wait for global idleness.
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

    private fun tapCenter(node: AccessibilityNodeInfo) {
        val bounds = node.screenBounds()
        executeShellInput("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun swipeForward(node: AccessibilityNodeInfo, fastScroll: Boolean) {
        val bounds = node.screenBounds()
        val travel = if (fastScroll) {
            bounds.height() * 2 / 5
        } else {
            bounds.height() / 6
        }.coerceAtLeast(1)
        val durationMillis = if (fastScroll) 120 else 220
        executeShellInput(
            "input swipe ${bounds.centerX()} ${bounds.centerY() + travel} " +
                "${bounds.centerX()} ${bounds.centerY() - travel} $durationMillis",
        )
    }

    private fun executeShellInput(commandText: String) {
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(commandText)
        ParcelFileDescriptor.AutoCloseInputStream(command).use { output ->
            while (output.read() != -1) Unit
        }
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
        const val THIRD_PARTY_DOCUMENT_HEADING = "DuckySlicer third-party licenses"
        const val MAX_LABEL_DEPTH = 12
        const val NODE_TIMEOUT_MILLIS = 5_000L
        const val NODE_POLL_MILLIS = 50L
        const val SCROLL_SETTLE_MILLIS = 200L
    }
}
