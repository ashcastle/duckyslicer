package com.ashcastle.duckyslicer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
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
    fun prepareGpuTextureComposesUnderWorkspaceControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            SystemClock.sleep(750)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertTrue(screenshot.width > 0 && screenshot.height > 0)
            val sampledColors = HashSet<Int>()
            for (y in 0 until screenshot.height step 24) {
                for (x in 0 until screenshot.width step 24) sampledColors += screenshot.getPixel(x, y)
            }
            assertTrue(
                "The GPU texture, model, and Compose controls must produce a composed frame",
                sampledColors.size >= 12,
            )
            instrumentation.targetContext.filesDir
                .resolve("prepare-renderer.png")
                .outputStream()
                .use { output -> screenshot.compress(Bitmap.CompressFormat.PNG, 100, output) }
        }
    }

    @Test
    fun prepareGpuTextureSurvivesActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use { scenario ->
            SystemClock.sleep(750)
            scenario.recreate()
            SystemClock.sleep(750)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val sampledColors = HashSet<Int>()
            for (y in 0 until screenshot.height step 24) {
                for (x in 0 until screenshot.width step 24) sampledColors += screenshot.getPixel(x, y)
            }
            assertTrue(
                "The recreated activity must restore the GPU model and Compose controls",
                sampledColors.size >= 12,
            )
        }
    }

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
    fun activeProfileTransfersExposeOneNamedStopAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val menu = context.getString(R.string.menu)
        val cases = listOf(
            AccessibilityHarnessActivity.SCREEN_PROFILE_IMPORT to
                context.getString(R.string.cancel_profile_import),
            AccessibilityHarnessActivity.SCREEN_PROFILE_EXPORT to
                context.getString(R.string.cancel_profile_export),
        )
        cases.forEach { (screen, cancelLabel) ->
            launchHarness(screen).use {
                val menuButton = waitForNode(menu) { it.isClickable }
                assertTrue(menuButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val nodes = waitForNodes(setOf(cancelLabel))
                assertEquals(
                    "An active profile transfer must expose one named stop action",
                    1,
                    nodes.count {
                        it.isClickable && it.isFocusable &&
                            it.effectiveLabel().contains(cancelLabel)
                    },
                )
            }
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
            assertEquals(
                "The notification settings action must retain its current state when scrolled",
                expectedState,
                action.stateDescription?.toString(),
            )
        }
    }

    @Test
    fun appSettingsOpenTheBundledPrivacyPolicyOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privacyLabel = context.getString(R.string.privacy_policy)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SETTINGS).use {
            val privacyButton = scrollUntilClickable(privacyLabel, fastScroll = true)
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
            val supportButton = scrollUntilClickable(supportLabel, fastScroll = true)
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
    fun plateSwitcherExposesSelectionAddAndConfirmedRemovalActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstLabel = context.getString(R.string.plate_number, 1)
        val secondLabel = context.getString(R.string.plate_number, 2)
        val addLabel = context.getString(R.string.add_plate)
        val removeLabel = context.getString(R.string.remove_plate)
        val removeTitle = context.getString(R.string.remove_plate_title)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PLATES).use {
            val nodes = waitForNodes(setOf(firstLabel, secondLabel, addLabel, removeLabel))
            val first = nodes.first { it.isClickable && it.effectiveLabel() == firstLabel }
            val second = nodes.first { it.isClickable && it.effectiveLabel() == secondLabel }
            val add = nodes.first { it.isClickable && it.effectiveLabel() == addLabel }
            val remove = nodes.first { it.isClickable && it.effectiveLabel() == removeLabel }
            assertEquals("1/2", first.stateDescription?.toString())
            assertTrue(second.isFocusable)
            assertTrue(add.isFocusable)
            assertTrue(remove.isFocusable)

            assertTrue(second.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNode(secondLabel) {
                it.isClickable && it.stateDescription?.toString() == "2/2"
            }
            val selectedRemove = waitForNode(removeLabel) { it.isClickable }
            assertTrue(selectedRemove.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                waitForNodes(setOf(removeTitle)).any {
                    it.effectiveLabel().contains(removeTitle)
                },
            )
            val confirm = waitForNode(removeLabel) { it.isClickable }
            assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNode(firstLabel) {
                it.isClickable && it.stateDescription?.toString() == "1/1"
            }
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
    fun collapsedWorkspaceProfilesHideAllCurrentProfileSummaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = context.getString(R.string.profiles)
        val printer = context.getString(R.string.printer_profile)
        val filament = context.getString(R.string.filament_profile)
        val slicing = context.getString(R.string.slicing_profile)
        launchHarness(AccessibilityHarnessActivity.SCREEN_WORKSPACE_PROFILES).use {
            val expanded = waitForNodes(setOf(profiles, printer, filament, slicing))
            val profileHeader = expanded.firstOrNull {
                it.isClickable && it.effectiveLabel().contains(profiles)
            }
            assertNotNull("The profile header must be collapsible", profileHeader)
            assertTrue(checkNotNull(profileHeader).performAction(AccessibilityNodeInfo.ACTION_CLICK))

            waitForNode(profiles) { node ->
                node.isClickable &&
                    node.stateDescription?.toString() == context.getString(R.string.collapsed_state)
            }
            val collapsed = currentNodes()
            listOf(printer, filament, slicing).forEach { hiddenLabel ->
                assertTrue(
                    "$hiddenLabel must not remain in the collapsed profile summary",
                    collapsed.none { it.effectiveLabel().contains(hiddenLabel) },
                )
            }
        }
    }

    @Test
    fun multiMaterialSupportUsesNamedFilamentPickersInsteadOfNumericSliders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slicingProfile = context.getString(R.string.slicing_profile)
        val supports = context.getString(R.string.supports)
        val supportBody = context.getString(R.string.support_filament)
        val supportInterface = context.getString(R.string.support_interface_filament)
        val supportBasePattern = context.getString(R.string.support_base_pattern)
        val supportInterfacePattern = context.getString(R.string.support_interface_pattern)
        val searchSettings = context.getString(R.string.search_settings)
        launchHarness(AccessibilityHarnessActivity.SCREEN_WORKSPACE_PROFILES).use {
            tapCenter(waitForNode(slicingProfile) { it.isClickable })
            tapCenter(waitForNode(supports) { it.isClickable })
            replaceEditableText(searchSettings, supportBody)

            val bodyPicker = scrollUntilNode(
                supportBody,
                scrollAnchorLabel = searchSettings,
                timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
            ) { node ->
                node.isClickable && !node.isEditable &&
                    !node.effectiveLabel().contains(supportBasePattern)
            }
            assertTrue(
                "Support body material must be a named picker, not a numeric seek bar",
                bodyPicker.className?.toString() != SEEK_BAR_CLASS,
            )
            assertTrue(bodyPicker.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val secondBodyMaterial = waitForNode("T2") {
                it.isClickable && it.isCheckable && it.effectiveLabel().contains("PETG")
            }
            assertTrue(secondBodyMaterial.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            replaceEditableText(searchSettings, supportInterface)

            val interfacePicker = scrollUntilNode(
                supportInterface,
                scrollAnchorLabel = searchSettings,
                timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
            ) { node ->
                node.isClickable && !node.isEditable &&
                    !node.effectiveLabel().contains(supportInterfacePattern)
            }
            assertTrue(
                "Support interface material must be independently selectable",
                interfacePicker.className?.toString() != SEEK_BAR_CLASS,
            )
            assertTrue(interfacePicker.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val secondInterfaceMaterial = waitForNode("T2") {
                it.isClickable && it.isCheckable && it.effectiveLabel().contains("PETG")
            }
            assertTrue(secondInterfaceMaterial.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            val selectedInterface = scrollUntilNode(
                supportInterface,
                scrollAnchorLabel = searchSettings,
                timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
            ) { node ->
                node.isClickable && !node.isEditable &&
                    !node.effectiveLabel().contains(supportInterfacePattern)
            }
            assertTrue(
                "The selected support interface must expose its tool and filament name",
                selectedInterface.effectiveLabel().contains("T2") &&
                    selectedInterface.effectiveLabel().contains("PETG"),
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
    fun heightRangeModifiersExposeRangeSettingsAndStickyActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.height_range_modifiers)
        val add = context.getString(R.string.add_height_range)
        val strength = context.getString(R.string.strength)
        val infill = context.getString(R.string.sparse_infill_density)
        val revert = context.getString(R.string.revert_changes)
        val apply = context.getString(R.string.apply_changes)
        launchHarness(AccessibilityHarnessActivity.SCREEN_HEIGHT_RANGE_MODIFIERS).use {
            val addButton = waitForNodes(setOf(title, add)).firstOrNull {
                it.isClickable && it.effectiveLabel() == add
            }
            assertNotNull("Height ranges must expose an Add action", addButton)
            assertTrue(checkNotNull(addButton).performAction(AccessibilityNodeInfo.ACTION_CLICK))

            val editorNodes = waitForNodes(setOf(strength))
            assertTrue(
                "A range must expose Orca-style setting categories",
                editorNodes.any { it.isClickable && it.effectiveLabel() == strength },
            )
            val infillControl = scrollUntilNode(
                infill,
                scrollAnchorLabel = strength,
                timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
            ) { it.isCheckable && it.isClickable }
            assertTrue("A range must expose its sparse infill override", infillControl.isVisibleToUser)
            val stageButton = scrollUntilClickable(
                add,
                scrollAnchorLabel = infill,
                timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
            )
            assertTrue(stageButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            val dirtyNodes = waitForNodes(setOf(revert, apply))
            val revertButton = dirtyNodes.firstOrNull {
                it.isClickable && it.effectiveLabel().contains(revert)
            }
            val applyButton = dirtyNodes.firstOrNull {
                it.isClickable && it.effectiveLabel().contains(apply)
            }
            assertNotNull("A staged range must expose Revert", revertButton)
            assertNotNull("A staged range must expose Apply", applyButton)
            assertTrue(
                "Apply must retain the requested 70/30 visual priority",
                checkNotNull(applyButton).screenBounds().width() >
                    checkNotNull(revertButton).screenBounds().width() * 2,
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
    fun auxiliaryShapePickerExposesRolesPlacementAndModifierDensity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.add_region)
        val cutout = context.getString(R.string.region_cutout)
        val settings = context.getString(R.string.region_settings)
        val blocker = context.getString(R.string.region_support_blocker)
        val enforcer = context.getString(R.string.region_support_enforcer)
        val size = context.getString(R.string.shape_size)
        val leftRight = context.getString(R.string.region_left_right)
        val frontBack = context.getString(R.string.region_front_back)
        val upDown = context.getString(R.string.region_up_down)
        val infill = context.getString(R.string.region_infill)
        launchHarness(AccessibilityHarnessActivity.SCREEN_AUXILIARY_SHAPE).use {
            val nodes = waitForNodes(
                setOf(title, cutout, settings, blocker, enforcer),
            )
            listOf(cutout, settings, blocker, enforcer).forEach { label ->
                assertTrue(
                    "$label must be selectable",
                    nodes.any { it.isClickable && it.effectiveLabel() == label },
                )
            }
            val settingsButton = nodes.first { it.isClickable && it.effectiveLabel() == settings }
            tapCenter(settingsButton)
            var scrollAnchor = settings
            listOf(size, leftRight, frontBack, upDown, infill).forEach { label ->
                val control = scrollUntilNode(
                    label,
                    scrollAnchorLabel = scrollAnchor,
                    timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
                ) { it.className?.toString() == SEEK_BAR_CLASS }
                assertTrue("$label must remain visible and adjustable", control.isVisibleToUser)
                scrollAnchor = label
            }
        }
    }

    @Test
    fun auxiliaryVolumeManagerExposesExistingRegionsRemovalAndAdd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.parts_and_regions)
        val cutout = context.getString(R.string.region_cutout)
        val settings = context.getString(R.string.region_settings)
        val add = context.getString(R.string.add_region)
        val removeCutout = context.getString(R.string.remove_region, cutout)
        val removeSettings = context.getString(R.string.remove_region, settings)
        val editCutout = context.getString(R.string.edit_region, cutout)
        val editSettings = context.getString(R.string.edit_region, settings)
        launchHarness(AccessibilityHarnessActivity.SCREEN_AUXILIARY_VOLUMES).use {
            val nodes = waitForNodes(
                setOf(
                    title,
                    cutout,
                    settings,
                    add,
                    editCutout,
                    editSettings,
                    removeCutout,
                    removeSettings,
                ),
            )
            assertTrue(nodes.any { it.isClickable && it.effectiveLabel() == add })
            assertTrue(nodes.any { it.isClickable && it.effectiveLabel().contains(editCutout) })
            assertTrue(nodes.any { it.isClickable && it.effectiveLabel().contains(editSettings) })
            assertTrue(nodes.any { it.isClickable && it.effectiveLabel().contains(removeCutout) })
            assertTrue(nodes.any { it.isClickable && it.effectiveLabel().contains(removeSettings) })
        }
    }

    @Test
    fun auxiliaryVolumeEditorExposesScalePlacementDensityAndApply() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = context.getString(R.string.region_settings)
        val title = context.getString(R.string.edit_region_title, settings)
        val scale = context.getString(R.string.scale)
        val leftRight = context.getString(R.string.region_left_right)
        val frontBack = context.getString(R.string.region_front_back)
        val upDown = context.getString(R.string.region_up_down)
        val infill = context.getString(R.string.region_infill)
        val apply = context.getString(R.string.apply_region_changes)
        launchHarness(AccessibilityHarnessActivity.SCREEN_AUXILIARY_VOLUME_EDIT).use {
            waitForNodes(setOf(title))
            var scrollAnchor = title
            listOf(scale, leftRight, frontBack, upDown, infill).forEach { label ->
                val control = scrollUntilNode(
                    label,
                    scrollAnchorLabel = scrollAnchor,
                    timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
                ) { it.className?.toString() == SEEK_BAR_CLASS }
                assertTrue("$label must remain visible and adjustable", control.isVisibleToUser)
                scrollAnchor = label
            }
            val applyButton = scrollUntilNode(
                apply,
                scrollAnchorLabel = scrollAnchor,
                timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
            ) { it.isClickable }
            assertTrue(applyButton.isVisibleToUser)
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

            val proportionLock = scrollUntilClickable(
                keepProportions,
                scrollAnchorLabel = placement,
                timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
            )
            assertTrue(
                "The proportion lock must expose one switch action",
                proportionLock.isCheckable,
            )
            var scrollAnchor = keepProportions
            axisLabels.forEach { label ->
                val axisControl = scrollUntilNode(label, scrollAnchorLabel = scrollAnchor) {
                    it.className?.toString() == SEEK_BAR_CLASS
                }
                assertTrue(
                    "$label must remain visible and adjustable after scrolling",
                    axisControl.isVisibleToUser,
                )
                scrollAnchor = label
            }
        }
    }

    @Test
    fun selectedObjectExposesAccessibleMoveAndScaleGizmoModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val move = context.getString(R.string.model_placement)
        val scale = context.getString(R.string.scale)
        val active = context.getString(R.string.active_transform_mode)
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            val nodes = waitForNodes(setOf(move, scale))
            val moveButton = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel() == move
            }
            val scaleButton = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel() == scale
            }
            assertNotNull("A selected object must expose direct move handles", moveButton)
            assertNotNull("A selected object must expose direct scale handles", scaleButton)
            assertEquals(
                "Move handles must expose their active state by default",
                active,
                checkNotNull(moveButton).stateDescription?.toString(),
            )

            tapCenter(checkNotNull(scaleButton))
            assertTrue(
                "The selected gizmo mode must be exposed to accessibility services",
                waitForNode(scale) {
                    it.isClickable && it.stateDescription?.toString() == active
                }.stateDescription?.toString() == active,
            )
        }
    }

    @Test
    fun directMoveGizmoDragCommitsOneTransformGesture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            SystemClock.sleep(750)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val redPixels = ArrayList<Pair<Int, Int>>()
            for (y in 0 until screenshot.height) {
                for (x in 0 until screenshot.width) {
                    val pixel = screenshot.getPixel(x, y)
                    if (
                        Color.red(pixel) >= 235 &&
                        Color.green(pixel) in 60..125 &&
                        Color.blue(pixel) in 65..135
                    ) {
                        redPixels += x to y
                    }
                }
            }
            assertTrue("The X move handle must be visible", redPixels.size >= 20)
            val top = redPixels.minOf { it.second }
            val endpointPixels = redPixels.filter { (_, y) -> y <= top + 42 }
            val startX = endpointPixels.sumOf { it.first } / endpointPixels.size
            val startY = endpointPixels.sumOf { it.second } / endpointPixels.size
            instrumentation.uiAutomation.executeShellCommand(
                "input swipe $startX $startY ${startX + 120} ${startY - 95} 300",
            ).close()

            assertTrue(
                "Dragging a visible axis handle must commit through project history",
                waitForNodes(setOf(TEST_TRANSFORM_COMMITTED_LABEL)).any {
                    it.effectiveLabel().contains(TEST_TRANSFORM_COMMITTED_LABEL)
                },
            )
        }
    }

    @Test
    fun simplifySheetExposesDetailControlCountsWarningAndActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.simplify_model)
        val detail = context.getString(R.string.simplify_detail_to_keep)
        val current = context.getString(R.string.simplify_current_faces, 100_000)
        val expected = context.getString(R.string.simplify_expected_faces, 50_000)
        val warning = context.getString(R.string.simplify_paint_warning)
        val cancel = context.getString(R.string.cancel)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SIMPLIFY).use {
            val nodes = waitForNodes(setOf(title, detail, current, expected, warning, cancel))
            assertTrue(nodes.any { it.isHeading && it.effectiveLabel() == title })
            assertTrue(
                "Detail retention must be adjustable and named",
                nodes.any {
                    it.className?.toString() == SEEK_BAR_CLASS &&
                        it.effectiveLabel().contains(detail)
                },
            )
            assertTrue(nodes.any { it.isClickable && it.isFocusable && it.effectiveLabel() == cancel })
            assertTrue(nodes.any { it.isClickable && it.isFocusable && it.effectiveLabel() == title })
            assertTrue(nodes.any { it.effectiveLabel().contains(current) })
            assertTrue(nodes.any { it.effectiveLabel().contains(expected) })
            assertTrue(nodes.any { it.effectiveLabel().contains(warning) })
        }
    }

    @Test
    fun splitPartsSheetRequiresAnExplicitVolumeChoiceAndApplyAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val splitParts = context.getString(R.string.split_to_parts)
        val hint = context.getString(R.string.split_parts_hint)
        val summary = context.getString(R.string.split_part_summary, 1, 2)
        val cancel = context.getString(R.string.cancel)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SPLIT_PARTS).use {
            val nodes = waitForNodes(setOf(splitParts, hint, summary, cancel))
            assertTrue(nodes.any { it.isHeading && it.effectiveLabel() == splitParts })
            assertTrue(nodes.any { it.effectiveLabel().contains(hint) })
            assertTrue(nodes.any { it.isCheckable && it.effectiveLabel().contains(summary) })
            assertTrue(nodes.any { it.isClickable && it.effectiveLabel() == cancel })
            assertTrue(nodes.any { it.isClickable && it.effectiveLabel() == splitParts })
        }
    }

    @Test
    fun selectedObjectExposesPlaceOnFaceModeAndTouchGuidance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val placeOnFace = context.getString(R.string.lay_on_face)
        val hint = context.getString(R.string.lay_on_face_hint)
        launchHarness(AccessibilityHarnessActivity.SCREEN_LAY_ON_FACE).use {
            val tool = waitForNodes(setOf(placeOnFace)).firstOrNull {
                it.isClickable && it.effectiveLabel().contains(placeOnFace)
            }
            assertNotNull("A selected object must expose Place on face", tool)
            tapCenter(checkNotNull(tool))
            assertTrue(
                "Place on face mode must explain the next touch action",
                waitForNodes(setOf(hint)).any { it.effectiveLabel().contains(hint) },
            )
            tapPrepareFixtureCenter()
            assertTrue(
                "GPU facet picking must apply Place on face for the touched model surface",
                waitForNodes(setOf(TEST_LAY_ON_FACE_SELECTED_LABEL)).any {
                    it.effectiveLabel().contains(TEST_LAY_ON_FACE_SELECTED_LABEL)
                },
            )
            val undo = waitForNode(context.getString(R.string.undo)) {
                it.isClickable && it.isEnabled
            }
            tapCenter(undo)
            assertTrue(
                "The applied face placement must be undoable",
                waitForNodes(setOf(TEST_LAY_ON_FACE_UNDONE_LABEL)).any {
                    it.effectiveLabel().contains(TEST_LAY_ON_FACE_UNDONE_LABEL)
                },
            )
        }
    }

    @Test
    fun failedPlaceOnFaceTapKeepsTheModeOpenForRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val placeOnFace = context.getString(R.string.lay_on_face)
        val hint = context.getString(R.string.lay_on_face_hint)
        launchHarness(AccessibilityHarnessActivity.SCREEN_LAY_ON_FACE_FAILURE).use {
            val tool = waitForNode(placeOnFace) { it.isClickable }
            tapCenter(tool)
            waitForNodes(setOf(hint))
            tapPrepareFixtureCenter()

            val nodes = waitForNodes(setOf(TEST_LAY_ON_FACE_FAILED_LABEL, hint))
            assertTrue(
                "A rejected surface must keep Place on face open so another face can be tapped",
                nodes.any { it.effectiveLabel().contains(hint) },
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

    @Test
    fun supportPaintModeKeepsGpuSceneAndTouchGuidance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paintSupport = context.getString(R.string.paint_support)
        val brushSize = context.getString(R.string.paint_brush_size)
        val hint = context.getString(R.string.support_paint_hint)
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            val tool = waitForNode(paintSupport) { it.isClickable }
            tapCenter(tool)
            assertTrue(
                "Support painting must explain its touch controls",
                waitForNodes(setOf(hint)).any { it.effectiveLabel().contains(hint) },
            )
            val brushSlider = waitForNode(brushSize) { node ->
                node.rangeInfo != null && node.actionList.any { action ->
                    action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
                }
            }
            assertTrue(
                "Support brush size must expose an adjustable bounded range",
                checkNotNull(brushSlider.rangeInfo).run {
                    current in min..max && min == 8f && max == 48f
                },
            )
            tapPrepareFixtureCenter()
            assertTrue(
                "GPU facet picking must invoke support paint for the touched model surface",
                waitForNodes(setOf(TEST_SUPPORT_PAINTED_LABEL)).any {
                    it.effectiveLabel().contains(TEST_SUPPORT_PAINTED_LABEL)
                },
            )
            SystemClock.sleep(500)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val sampledColors = HashSet<Int>()
            for (y in 0 until screenshot.height step 24) {
                for (x in 0 until screenshot.width step 24) sampledColors += screenshot.getPixel(x, y)
            }
            assertTrue(
                "Support painting must retain the composed GPU model and controls",
                sampledColors.size >= 12,
            )
        }
    }

    @Test
    fun manualBrimEditorKeepsGpuSceneComposed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.manual_brim_ears)

        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            val brimTool = waitForNodes(setOf(title)).firstOrNull {
                it.isClickable && it.effectiveLabel() == title
            }
            tapCenter(checkNotNull(brimTool))
            SystemClock.sleep(750)
            it.onActivity { activity ->
                assertTrue(
                    "Manual Brim editing must keep the GPU scene activity alive",
                    !activity.isFinishing && !activity.isDestroyed,
                )
            }
        }
    }

    private fun launchHarness(screen: String): ActivityScenario<AccessibilityHarnessActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ActivityScenario.launch<AccessibilityHarnessActivity>(
            Intent(context, AccessibilityHarnessActivity::class.java)
                .putExtra(AccessibilityHarnessActivity.EXTRA_SCREEN, screen),
        ).also { scenario ->
            // The landscape regression changes the shared emulator display orientation.
            // Reset once per scenario without forcing every recreated Activity back again.
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    private fun tapPrepareFixtureCenter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        listOf(0.415f, 0.425f, 0.435f, 0.445f).forEach { heightFraction ->
            executeShellInput(
                "input tap ${screenshot.width / 2} " +
                    "${(screenshot.height * heightFraction).toInt()}",
            )
            SystemClock.sleep(NODE_POLL_MILLIS)
        }
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
        scrollAnchorLabel: String? = null,
        timeoutMillis: Long = NODE_TIMEOUT_MILLIS,
    ): AccessibilityNodeInfo {
        return scrollUntilNode(label, fastScroll, scrollAnchorLabel, timeoutMillis) {
            it.isClickable
        }
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

    private fun replaceEditableText(label: String, value: String) {
        val field = waitForNode(label) { node ->
            node.isEditable && node.actionList.any { action ->
                action.id == AccessibilityNodeInfo.ACTION_SET_TEXT
            }
        }
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value,
            )
        }
        assertTrue("The settings search field must accept text", field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments,
        ))
        waitForNode(value) { node -> node.isEditable && node.text?.toString() == value }
    }

    private fun scrollUntilNode(
        label: String,
        fastScroll: Boolean = false,
        scrollAnchorLabel: String? = null,
        timeoutMillis: Long = NODE_TIMEOUT_MILLIS,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var lastDiagnostic = "no accessibility nodes"
        var scrollAttempts = 0
        var retainedScrollBounds: Rect? = null
        do {
            val nodes = currentNodes()
            val target = nodes.firstOrNull { node ->
                matches(node) && node.effectiveLabel().contains(label)
            }
            if (target?.isVisibleToUser == true) return target
            val anchor = scrollAnchorLabel?.let { expected ->
                nodes.firstOrNull { node ->
                    node.isVisibleToUser && node.effectiveLabel().contains(expected)
                }
            }
            val scrollable = target?.scrollableAncestor()
                ?: anchor?.scrollableAncestor()
                ?: nodes.asSequence()
                .filter { node -> node.isVisibleToUser && node.isScrollContainer() }
                .maxByOrNull { node -> node.screenBounds().run { width().toLong() * height() } }
            if (retainedScrollBounds == null && scrollable != null) {
                retainedScrollBounds = scrollable.screenBounds().takeUnless(Rect::isEmpty)
            }
            lastDiagnostic = nodes.asSequence()
                .filter { node ->
                    node.isScrollContainer() ||
                        node.effectiveLabel().contains(label) ||
                        (
                            scrollAnchorLabel != null &&
                                node.effectiveLabel().contains(scrollAnchorLabel)
                        )
                }
                .take(MAX_SCROLL_DIAGNOSTIC_NODES)
                .joinToString(separator = " | ") { node ->
                    "${node.className}:${node.isVisibleToUser}:${node.isScrollContainer()}:" +
                        "${node.screenBounds()}:${node.effectiveLabel().take(MAX_DIAGNOSTIC_LABEL_LENGTH)}"
                }
                .ifEmpty { "no matching target, anchor, or scrollable nodes" }
            retainedScrollBounds?.let { bounds ->
                scrollAttempts += 1
                swipeForward(bounds, fastScroll)
            }
            SystemClock.sleep(SCROLL_SETTLE_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(
            "Timed out scrolling to accessibility action: $label; " +
                "attempts=$scrollAttempts; nodes=$lastDiagnostic",
        )
    }

    private fun AccessibilityNodeInfo.scrollableAncestor(): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = this
        repeat(MAX_SCROLL_ANCESTOR_DEPTH) {
            val current = candidate ?: return null
            if (current.isScrollContainer()) return current
            candidate = current.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.isScrollContainer(): Boolean =
        isScrollable && className?.toString() != SEEK_BAR_CLASS &&
            actionList.any { action ->
                action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
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

    private fun swipeForward(bounds: Rect, fastScroll: Boolean) {
        val travel = if (fastScroll) {
            bounds.height() * 2 / 5
        } else {
            bounds.height() / 10
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
        const val MAX_SCROLL_ANCESTOR_DEPTH = 16
        const val MAX_SCROLL_DIAGNOSTIC_NODES = 12
        const val MAX_DIAGNOSTIC_LABEL_LENGTH = 80
        const val NODE_TIMEOUT_MILLIS = 5_000L
        const val EXTENDED_SCROLL_TIMEOUT_MILLIS = 10_000L
        const val NODE_POLL_MILLIS = 50L
        const val SCROLL_SETTLE_MILLIS = 200L
    }
}
