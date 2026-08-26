package com.ashcastle.duckyslicer

import android.accessibilityservice.AccessibilityService
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
import org.junit.Assert.assertFalse
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
    fun workspaceCameraPresetActionsAreReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = context.getString(R.string.view_options)
        val presets = setOf(
            context.getString(R.string.view_isometric),
            context.getString(R.string.view_top),
            context.getString(R.string.view_front),
            context.getString(R.string.view_right),
        )
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            val controls = waitForNodes(setOf(options))
            val optionsAction = controls.first { it.isClickable && it.effectiveLabel() == options }
            assertTrue(optionsAction.isFocusable)
            assertTrue(optionsAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val presetNodes = waitForNodes(presets)
            presets.forEach { label ->
                assertTrue(
                    "$label must be a reachable camera preset",
                    presetNodes.any { it.isClickable && it.effectiveLabel() == label },
                )
            }
        }
    }

    @Test
    fun selectedObjectStlExportIsReachableFromWorkspaceMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val menu = context.getString(R.string.menu)
        val export = context.getString(R.string.export_selected_stl)
        launchHarness(AccessibilityHarnessActivity.SCREEN_MODEL_TRANSFORM).use {
            val menuButton = waitForNode(menu) { it.isClickable }
            assertTrue(menuButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val exportAction = waitForNode(export) { it.isClickable }
            assertTrue(exportAction.isVisibleToUser)
            assertTrue(exportAction.isFocusable)
            assertTrue(exportAction.isEnabled)
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
        val featureColor = context.getString(R.string.preview_color_feature)
        val filamentColor = context.getString(R.string.preview_color_filament)
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
            val colorNodes = waitForNodes(setOf(featureColor, filamentColor))
            assertEquals(1, colorNodes.count { it.isClickable && it.effectiveLabel() == featureColor })
            val filamentNode = colorNodes.single {
                it.isClickable && it.effectiveLabel() == filamentColor
            }
            assertTrue(filamentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }

    @Test
    fun previewLayerPauseActionHasAStableStatefulName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val addLabel = context.getString(R.string.add_layer_pause, 300)
        val removeLabel = context.getString(R.string.remove_layer_pause, 300)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PREVIEW).use {
            val addNode = waitForNodes(setOf(addLabel)).single {
                it.isClickable && it.effectiveLabel() == addLabel
            }
            assertTrue(addNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val removeNode = waitForNodes(setOf(removeLabel)).single {
                it.isClickable && it.effectiveLabel() == removeLabel
            }
            assertTrue(removeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }

    @Test
    fun previewLayerFilamentChangeExposesSelectionAndRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val addLabel = context.getString(R.string.add_layer_filament_change, 300)
        val chooseLabel = context.getString(R.string.choose_layer_filament)
        val filamentTwo = context.getString(R.string.preview_filament_number, 2)
        val removeLabel = context.getString(R.string.remove_layer_filament_change_height, 60.05f)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PREVIEW).use {
            val addNode = scrollUntilClickable(addLabel)
            assertTrue(addNode.isFocusable)
            assertTrue(addNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                waitForNodes(setOf(chooseLabel)).any { it.effectiveLabel().contains(chooseLabel) },
            )
            val filamentNode = waitForNodes(setOf(filamentTwo)).single {
                it.isClickable && it.effectiveLabel().contains(filamentTwo)
            }
            assertTrue(filamentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val removeNode = scrollUntilClickable(removeLabel)
            assertTrue(removeNode.isFocusable)
            assertTrue(removeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }

    @Test
    fun previewLayerCustomGCodeExposesEditingApplyAndRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val addLabel = context.getString(R.string.add_layer_custom_gcode, 300)
        val title = context.getString(R.string.layer_custom_gcode_title, 300)
        val fieldLabel = context.getString(R.string.gcode)
        val applyLabel = context.getString(R.string.layer_custom_gcode_apply)
        val removeLabel = context.getString(R.string.remove_layer_custom_gcode_height, 60.05f)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PREVIEW).use {
            val addNode = scrollUntilClickable(addLabel)
            assertTrue(addNode.isFocusable)
            assertTrue(addNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                waitForNode(title) { node -> node.effectiveLabel().contains(title) }.isVisibleToUser,
            )
            replaceEditableText(fieldLabel, "M117 Inspect")
            val applyNode = waitForNodes(setOf(applyLabel)).single {
                it.isClickable && it.effectiveLabel() == applyLabel
            }
            assertTrue(applyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val removeNode = scrollUntilClickable(removeLabel)
            assertTrue(removeNode.isFocusable)
            assertTrue(removeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
    fun savedRemoteCredentialCanBeExplicitlyRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val edit = context.getString(R.string.edit_device)
        val remove = context.getString(R.string.remove_saved_access_key)
        val save = context.getString(R.string.save)
        launchHarness(AccessibilityHarnessActivity.SCREEN_DEVICE).use {
            clickNamedAction(edit)
            val removeAction = scrollUntilNode(remove) { node ->
                node.isEnabled && node.isClickable && node.isCheckable
            }
            val matchingActions = currentNodes().filter { node ->
                node.isVisibleToUser &&
                    node.isEnabled &&
                    node.isClickable &&
                    node.isCheckable &&
                    node.effectiveLabel().contains(remove)
            }
            assertEquals(
                "The saved-key control must expose one merged accessibility action",
                1,
                matchingActions.size,
            )
            assertTrue(removeAction.isCheckable)
            assertEquals(AccessibilityNodeInfo.CHECKED_STATE_FALSE, removeAction.checked)

            assertTrue(removeAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                waitForNode(remove) { node ->
                    node.isClickable &&
                        node.isCheckable &&
                        node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
                }.isVisibleToUser,
            )
            clickNamedAction(save)
            assertTrue(
                waitForNode(TEST_REMOTE_CREDENTIAL_REMOVAL_SAVED) { node ->
                    node.effectiveLabel() == TEST_REMOTE_CREDENTIAL_REMOVAL_SAVED
                }.isVisibleToUser,
            )
        }
    }

    @Test
    fun remoteDeviceDeletionRequiresExplicitConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val delete = context.getString(R.string.delete_device)
        val prompt = context.getString(R.string.confirm_delete_device, TEST_DEVICE_LABEL)
        val keep = context.getString(R.string.keep_device)
        launchHarness(AccessibilityHarnessActivity.SCREEN_DEVICE).use {
            clickNamedAction(delete)
            waitForNodes(setOf(prompt, keep))
            assertTrue(waitForSingleNamedAction(delete).isFocusable)
            assertFalse(
                "Opening delete confirmation must not dispatch deletion",
                currentNodes().any { it.effectiveLabel().contains(TEST_REMOTE_DELETE_DISPATCHED) },
            )

            clickNamedAction(keep)
            waitForLabelsGone(setOf(prompt, keep))
            assertFalse(
                "Dismissing delete confirmation must not dispatch deletion",
                currentNodes().any { it.effectiveLabel().contains(TEST_REMOTE_DELETE_DISPATCHED) },
            )

            clickNamedAction(delete)
            waitForNodes(setOf(prompt, keep))
            assertTrue(waitForSingleNamedAction(delete).isFocusable)
            clickNamedAction(delete)
            assertTrue(
                waitForNode(TEST_REMOTE_DELETE_DISPATCHED) {
                    it.effectiveLabel() == TEST_REMOTE_DELETE_DISPATCHED
                }.isVisibleToUser,
            )
        }
    }

    @Test
    fun livePrintCancellationRequiresExplicitConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cancel = context.getString(R.string.cancel_print)
        val prompt = context.getString(R.string.confirm_cancel_print)
        val keep = context.getString(R.string.keep_printing)
        launchHarness(AccessibilityHarnessActivity.SCREEN_DEVICE_PRINTING).use {
            clickNamedAction(cancel)
            waitForNodes(setOf(prompt, keep))
            assertTrue(waitForSingleNamedAction(cancel).isFocusable)
            assertFalse(
                "Opening print cancellation confirmation must not dispatch cancellation",
                currentNodes().any { it.effectiveLabel().contains(TEST_REMOTE_CANCEL_DISPATCHED) },
            )

            clickNamedAction(keep)
            waitForLabelsGone(setOf(prompt, keep))
            assertFalse(
                "Keeping the print must not dispatch cancellation",
                currentNodes().any { it.effectiveLabel().contains(TEST_REMOTE_CANCEL_DISPATCHED) },
            )

            clickNamedAction(cancel)
            waitForNodes(setOf(prompt, keep))
            assertTrue(waitForSingleNamedAction(cancel).isFocusable)
            clickNamedAction(cancel)
            assertTrue(
                waitForNode(TEST_REMOTE_CANCEL_DISPATCHED) {
                    it.effectiveLabel() == TEST_REMOTE_CANCEL_DISPATCHED
                }.isVisibleToUser,
            )
        }
    }

    @Test
    fun remoteDeviceTelemetryExposesTemperaturesAndPrintTimesAsText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nozzle = context.getString(R.string.nozzle_temperature)
        val bed = context.getString(R.string.bed_temperature)
        val elapsed = context.getString(R.string.remote_elapsed_time, "")
        val remaining = context.getString(R.string.remote_remaining_time, "")
        val progress = context.getString(R.string.print_progress, 40)
        launchHarness(AccessibilityHarnessActivity.SCREEN_DEVICE_TELEMETRY).use {
            val nodes = waitForNodes(
                setOf(nozzle, bed, elapsed.trim(), remaining.trim(), progress),
            )
            assertTrue(nodes.any { it.isVisibleToUser && it.effectiveLabel().contains(nozzle) })
            assertTrue(nodes.any { it.isVisibleToUser && it.effectiveLabel().contains(bed) })
            assertTrue(nodes.any { it.isVisibleToUser && it.effectiveLabel().contains(elapsed.trim()) })
            assertTrue(nodes.any { it.isVisibleToUser && it.effectiveLabel().contains(remaining.trim()) })
            val progressNode = nodes.first {
                it.isVisibleToUser &&
                    it.effectiveLabel().contains(progress) &&
                    it.rangeInfo != null
            }
            assertEquals(0.4f, checkNotNull(progressNode.rangeInfo).current, 0.01f)
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
        val newLabel = context.getString(R.string.new_project)
        val openLabel = context.getString(R.string.open_project)
        val saveLabel = context.getString(R.string.save_project)
        val saveOptionsLabel = context.getString(R.string.project_save_options)
        val saveAsLabel = context.getString(R.string.save_project_as)
        val unsavedLabel = context.getString(R.string.linked_project_unsaved)
        val confirmation = context.getString(R.string.replace_project_title)
        val unsavedWarning = context.getString(
            R.string.replace_project_unsaved_body,
            "Linked-project.duckyproject",
        )
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT).use {
            val nodes = waitForNodes(
                setOf(newLabel, openLabel, saveLabel, saveOptionsLabel, unsavedLabel),
            )
            val newProject = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel().contains(newLabel)
            }
            val open = nodes.firstOrNull { it.isClickable && it.effectiveLabel().contains(openLabel) }
            val save = nodes.firstOrNull { it.isClickable && it.effectiveLabel().contains(saveLabel) }
            val saveOptions = nodes.firstOrNull {
                it.isClickable && it.effectiveLabel() == saveOptionsLabel
            }
            assertNotNull("New project must be an explicit action", newProject)
            assertNotNull("Open project must be an explicit action", open)
            assertNotNull("Save project must be an explicit action", save)
            assertNotNull("Save project options must be an explicit action", saveOptions)
            assertTrue(
                "A linked project with local changes must expose its unsaved state",
                nodes.any { it.effectiveLabel().contains(unsavedLabel) },
            )
            assertTrue(checkNotNull(newProject).isFocusable)
            assertTrue(checkNotNull(open).isFocusable)
            assertTrue(checkNotNull(save).isFocusable)
            assertTrue(checkNotNull(saveOptions).isFocusable)
            assertTrue(saveOptions.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Save project as must be reachable from the split action",
                waitForNodes(setOf(saveAsLabel)).any {
                    it.isClickable && it.effectiveLabel().contains(saveAsLabel)
                },
            )
            assertTrue(
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK,
                ),
            )
            val reopened = scrollUntilClickable(openLabel)
            assertTrue(reopened.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Replacing a non-empty project must require confirmation",
                waitForNodes(setOf(confirmation, unsavedWarning)).let { warningNodes ->
                    warningNodes.any { it.effectiveLabel().contains(confirmation) } &&
                        warningNodes.any { it.effectiveLabel().contains(unsavedWarning) }
                },
            )
        }
    }

    @Test
    fun recentProjectIsFocusableAndUsesTheExistingReplacementWarning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recentHeading = context.getString(R.string.recent_projects)
        val recentName = "Recent duck.duckyproject"
        val replaceTitle = context.getString(R.string.replace_project_title)
        val replaceWarning = context.getString(
            R.string.replace_project_unsaved_body,
            "Linked-project.duckyproject",
        )
        val actionsLabel = context.getString(R.string.recent_project_actions, recentName)
        val removeLabel = context.getString(R.string.remove_recent_project)
        val removedNotice = context.getString(R.string.recent_project_removed)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT_RECENT).use {
            assertTrue(
                "Recent projects must have a visible section heading",
                waitForNodes(setOf(recentHeading)).any {
                    it.effectiveLabel().contains(recentHeading)
                },
            )
            val recent = scrollUntilClickable(recentName)
            assertTrue("A recent project must be keyboard and switch-access focusable", recent.isFocusable)
            assertTrue(recent.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "A recent project must not bypass the current-project replacement warning",
                waitForNodes(setOf(replaceTitle, replaceWarning)).let { nodes ->
                    nodes.any { it.effectiveLabel().contains(replaceTitle) } &&
                        nodes.any { it.effectiveLabel().contains(replaceWarning) }
                },
            )
            assertTrue(
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK,
                ),
            )
            val actions = scrollUntilClickable(actionsLabel)
            assertTrue("Recent project actions must be focusable", actions.isFocusable)
            assertTrue(actions.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val remove = scrollUntilClickable(removeLabel)
            assertTrue("Remove from recent must be focusable", remove.isFocusable)
            assertTrue(remove.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Removing a recent project must provide feedback",
                waitForNodes(setOf(removedNotice)).any {
                    it.effectiveLabel().contains(removedNotice)
                },
            )
        }
    }

    @Test
    fun dirtyEmptyLinkedProjectRequiresConfirmationBeforeOpenOrNew() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val newLabel = context.getString(R.string.new_project)
        val openLabel = context.getString(R.string.open_project)
        val replaceTitle = context.getString(R.string.replace_project_title)
        val replaceWarning = context.getString(
            R.string.replace_project_unsaved_body,
            "Linked-project.duckyproject",
        )
        val newTitle = context.getString(R.string.new_project_title)
        val newWarning = context.getString(
            R.string.new_project_unsaved_body,
            "Linked-project.duckyproject",
        )
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT_DIRTY_EMPTY).use {
            val open = scrollUntilClickable(openLabel)
            assertTrue("Open must remain actionable for a dirty empty project", open.isEnabled)
            assertTrue(open.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Opening over dirty linked settings must require confirmation",
                waitForNodes(setOf(replaceTitle, replaceWarning)).let { nodes ->
                    nodes.any { it.effectiveLabel().contains(replaceTitle) } &&
                        nodes.any { it.effectiveLabel().contains(replaceWarning) }
                },
            )
            assertTrue(
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK,
                ),
            )

            val newProject = scrollUntilClickable(newLabel)
            assertTrue("New must remain actionable for a dirty empty project", newProject.isEnabled)
            assertTrue(newProject.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Resetting dirty linked settings must require confirmation",
                waitForNodes(setOf(newTitle, newWarning)).let { nodes ->
                    nodes.any { it.effectiveLabel().contains(newTitle) } &&
                        nodes.any { it.effectiveLabel().contains(newWarning) }
                },
            )
        }
    }

    @Test
    fun newProjectActionRequiresConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val newLabel = context.getString(R.string.new_project)
        val confirmation = context.getString(R.string.new_project_title)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT).use {
            val action = scrollUntilClickable(newLabel)
            assertTrue(action.isFocusable)
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Clearing a non-empty project must require confirmation",
                waitForNodes(setOf(confirmation)).any {
                    it.effectiveLabel().contains(confirmation)
                },
            )
        }
    }

    @Test
    fun projectObjectActionsExposeDuplicateAndConfirmedRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelName = "accessibility.stl"
        val actions = context.getString(R.string.object_actions, modelName)
        val duplicate = context.getString(R.string.duplicate_object)
        val remove = context.getString(R.string.remove_model)
        val confirmation = context.getString(R.string.remove_object_title)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT).use {
            val actionsButton = waitForNode(actions) {
                it.isClickable && it.effectiveLabel() == actions
            }
            assertTrue("Each project object needs a focusable actions button", actionsButton.isFocusable)
            assertTrue(actionsButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            val menuNodes = waitForNodes(setOf(duplicate, remove))
            assertTrue(menuNodes.any { it.isClickable && it.effectiveLabel() == duplicate })
            val removeAction = menuNodes.first {
                it.isClickable && it.effectiveLabel() == remove
            }
            assertTrue(removeAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Object removal from the project list must require confirmation",
                waitForNodes(setOf(confirmation)).any {
                    it.effectiveLabel().contains(confirmation)
                },
            )
        }
    }

    @Test
    fun projectObjectRenameRequiresAValidEditableName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actions = context.getString(R.string.object_actions, "accessibility.stl")
        val rename = context.getString(R.string.rename_object)
        val name = context.getString(R.string.object_name)
        val save = context.getString(R.string.save)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT).use {
            val actionsButton = waitForNode(actions) {
                it.isClickable && it.effectiveLabel() == actions
            }
            assertTrue(actionsButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val renameAction = waitForNode(rename) {
                it.isClickable && it.effectiveLabel() == rename
            }
            assertTrue(renameAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            replaceEditableText(name, "renamed-model.stl")
            val saveAction = waitForNode(save) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == save
            }
            assertTrue("A valid object name must be directly saveable", saveAction.isFocusable)
        }
    }

    @Test
    fun projectObjectCanChooseAnotherPlateAsItsMoveTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actions = context.getString(R.string.object_actions, "accessibility.stl")
        val move = context.getString(R.string.move_object)
        val secondPlate = context.getString(R.string.plate_number, 2)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT_PLATES).use {
            val actionsButton = waitForNode(actions) {
                it.isClickable && it.effectiveLabel() == actions
            }
            assertTrue(actionsButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val moveAction = waitForNode(move) {
                it.isClickable && it.effectiveLabel() == move
            }
            assertTrue(moveAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Moving an object must expose every other plate as a direct target",
                waitForNodes(setOf(secondPlate)).any {
                    it.isClickable && it.effectiveLabel() == secondPlate
                },
            )
        }
    }

    @Test
    fun projectObjectCanChooseAnotherPlateAsItsCopyTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actions = context.getString(R.string.object_actions, "accessibility.stl")
        val copy = context.getString(R.string.copy_to_plate)
        val secondPlate = context.getString(R.string.plate_number, 2)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PROJECT_PLATES).use {
            val actionsButton = waitForNode(actions) {
                it.isClickable && it.effectiveLabel() == actions
            }
            assertTrue(actionsButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val copyAction = waitForNode(copy) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == copy
            }
            assertTrue(copyAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(
                "Copying an object must expose every other plate as a direct target",
                waitForNodes(setOf(secondPlate)).any {
                    it.isClickable && it.effectiveLabel() == secondPlate
                },
            )
        }
    }

    @Test
    fun plateSwitcherExposesSelectionAddAndConfirmedRemovalActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstLabel = context.getString(R.string.plate_number, 1)
        val secondLabel = context.getString(R.string.plate_number, 2)
        val addLabel = context.getString(R.string.add_plate)
        val actionsLabel = context.getString(R.string.plate_actions)
        val removeLabel = context.getString(R.string.remove_plate)
        val removeTitle = context.getString(R.string.remove_plate_title)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PLATES).use {
            val nodes = waitForNodes(setOf(firstLabel, secondLabel, addLabel, actionsLabel))
            val first = nodes.first { it.isClickable && it.effectiveLabel() == firstLabel }
            val second = nodes.first { it.isClickable && it.effectiveLabel() == secondLabel }
            val add = nodes.first { it.isClickable && it.effectiveLabel() == addLabel }
            val actions = nodes.first { it.isClickable && it.effectiveLabel() == actionsLabel }
            assertEquals("1/2", first.stateDescription?.toString())
            assertTrue(second.isFocusable)
            assertTrue(add.isFocusable)
            assertTrue(actions.isFocusable)

            assertTrue(second.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNode(secondLabel) {
                it.isClickable && it.stateDescription?.toString() == "2/2"
            }
            val selectedActions = waitForNode(actionsLabel) { it.isClickable && it.isEnabled }
            assertTrue(selectedActions.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val selectedRemove = waitForNode(removeLabel) { it.isClickable && it.isEnabled }
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
    fun plateSwitcherDuplicatesTheSelectedPlateAndSelectsTheCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val duplicateLabel = context.getString(R.string.duplicate_plate)
        val platesLabel = context.getString(R.string.plates)
        launchHarness(AccessibilityHarnessActivity.SCREEN_PLATES).use {
            val duplicate = waitForNode(duplicateLabel) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == duplicateLabel
            }
            assertTrue(duplicate.isFocusable)
            assertTrue(duplicate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNode(platesLabel) {
                it.stateDescription?.toString() == "3/3"
            }
        }
    }

    @Test
    fun plateSwitcherRenamesAndReordersTheSelectedPlate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renameLabel = context.getString(R.string.rename_plate)
        val nameLabel = context.getString(R.string.plate_name)
        val moveLaterLabel = context.getString(R.string.move_plate_next)
        val actionsLabel = context.getString(R.string.plate_actions)
        val platesLabel = context.getString(R.string.plates)
        val customName = "Main body"
        launchHarness(AccessibilityHarnessActivity.SCREEN_PLATES).use {
            val actions = waitForNode(actionsLabel) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == actionsLabel
            }
            assertTrue(actions.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val rename = waitForNode(renameLabel) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == renameLabel
            }
            assertTrue(rename.isFocusable)
            assertTrue(rename.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            replaceEditableText(nameLabel, customName)
            val done = waitForNode(context.getString(R.string.done)) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == context.getString(R.string.done)
            }
            assertTrue(done.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNode(customName) { it.isClickable && it.effectiveLabel() == customName }

            val updatedActions = waitForNode(actionsLabel) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == actionsLabel
            }
            assertTrue(updatedActions.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val moveLater = waitForNode(moveLaterLabel) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == moveLaterLabel
            }
            assertTrue(moveLater.isFocusable)
            assertTrue(moveLater.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNode(platesLabel) { it.stateDescription?.toString() == "2/2" }
        }
    }

    @Test
    fun sliceAllPlatesIsExplicitAndBatchProgressNamesTheCurrentPlate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sliceAll = context.getString(R.string.slice_all_plates)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SLICE_ALL).use {
            val action = waitForNode(sliceAll) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == sliceAll
            }
            assertTrue(action.isFocusable)
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNode(TEST_SLICE_ALL_REQUESTED_LABEL) {
                it.effectiveLabel() == TEST_SLICE_ALL_REQUESTED_LABEL
            }
        }

        val progress = context.getString(R.string.slicing_all_plates_progress, 2, 3, 37)
        val cancel = context.getString(R.string.cancel)
        launchHarness(AccessibilityHarnessActivity.SCREEN_SLICE_ALL_PROGRESS).use {
            waitForNode(progress) { it.effectiveLabel() == progress }
            val cancelAction = waitForNode(cancel) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == cancel
            }
            assertTrue(cancelAction.isFocusable)
        }
    }

    @Test
    fun allPlateGcodeExportIsExplicitAndReportsFileProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exportOptions = context.getString(R.string.export_options)
        val exportAll = context.getString(R.string.export_all_gcode)
        launchHarness(AccessibilityHarnessActivity.SCREEN_GCODE_EXPORT_ALL).use {
            val options = waitForNode(exportOptions) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == exportOptions
            }
            assertTrue(options.isFocusable)
            assertTrue(options.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val action = waitForNode(exportAll) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == exportAll
            }
            assertTrue(action.isFocusable)
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNode(TEST_EXPORT_ALL_REQUESTED_LABEL) {
                it.effectiveLabel() == TEST_EXPORT_ALL_REQUESTED_LABEL
            }
        }

        val progress = context.getString(R.string.exporting_gcode_files, 2, 3)
        val cancel = context.getString(R.string.cancel_gcode_export)
        launchHarness(AccessibilityHarnessActivity.SCREEN_GCODE_EXPORT_ALL_PROGRESS).use {
            waitForNode(progress) { it.effectiveLabel() == progress }
            val cancelAction = waitForNode(cancel) {
                it.isClickable && it.isEnabled && it.effectiveLabel() == cancel
            }
            assertTrue(cancelAction.isFocusable)
            assertEquals(progress, cancelAction.stateDescription?.toString())
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
    fun userProfileDeletionRequiresConfirmationAndKeepsBuiltInsProtected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val printerProfile = context.getString(R.string.printer_profile)
        val profileList = context.getString(R.string.profile_list)
        val myProfiles = context.getString(R.string.my_profiles)
        val userProfile = "My accessibility printer"
        val deleteNamed = context.getString(R.string.delete_profile_named, userProfile)
        val delete = context.getString(R.string.delete_profile)
        val keep = context.getString(R.string.keep_profile)
        launchHarness(AccessibilityHarnessActivity.SCREEN_WORKSPACE_PROFILES).use {
            assertTrue(
                waitForNode(printerProfile) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            assertTrue(
                waitForNode(profileList) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            assertTrue(
                waitForNode(myProfiles) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )

            val deleteAction = waitForSingleNamedAction(deleteNamed)
            assertTrue("A user profile must expose one named delete action", deleteAction.isEnabled)
            assertTrue(deleteAction.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForNodes(setOf(delete, keep, userProfile))
            clickNamedAction(keep)
            assertTrue(waitForSingleNamedAction(deleteNamed).isEnabled)

            clickNamedAction(deleteNamed)
            clickNamedAction(delete)
            waitForLabelsGone(setOf(deleteNamed))
            waitForAnyNode("selectable built-in profile") { node ->
                node.isCheckable && node.isClickable
            }
        }
    }

    @Test
    fun userProfileEditorExposesInPlaceSaveWhileBuiltInsRemainProtected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val printerProfile = context.getString(R.string.printer_profile)
        val profileList = context.getString(R.string.profile_list)
        val myProfiles = context.getString(R.string.my_profiles)
        val saveChanges = context.getString(R.string.save_profile_changes)
        val userProfile = "My accessibility printer"
        launchHarness(AccessibilityHarnessActivity.SCREEN_WORKSPACE_PROFILES).use {
            assertTrue(
                waitForNode(printerProfile) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            assertTrue(
                "Built-in profiles must not expose in-place persistence",
                currentNodes().none { it.effectiveLabel() == saveChanges },
            )

            assertTrue(
                waitForNode(profileList) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            assertTrue(
                waitForNode(myProfiles) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            assertTrue(
                waitForNode(userProfile) { it.isClickable && it.isCheckable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )

            val update = waitForNode(saveChanges) { it.isClickable }
            assertTrue("A user profile must expose an in-place save action", update.isEnabled)
            assertTrue(update.isFocusable)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun userProfileCanBeRenamedWithoutSelectingIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val printerProfile = context.getString(R.string.printer_profile)
        val profileList = context.getString(R.string.profile_list)
        val myProfiles = context.getString(R.string.my_profiles)
        val profileName = context.getString(R.string.profile_name)
        val rename = context.getString(R.string.rename_profile)
        val originalName = "My accessibility printer"
        val renamedName = "Renamed accessibility printer"
        val renameNamed = context.getString(R.string.rename_profile_named, originalName)
        launchHarness(AccessibilityHarnessActivity.SCREEN_WORKSPACE_PROFILES).use {
            assertTrue(
                waitForNode(printerProfile) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            assertTrue(
                waitForNode(profileList) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            assertTrue(
                waitForNode(myProfiles) { it.isClickable }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            clickNamedAction(renameNamed)

            waitForNodes(setOf(rename, profileName, originalName))
            replaceEditableText(profileName, renamedName)
            clickNamedAction(rename)

            val renamed = waitForNode(renamedName) { it.isClickable && it.isCheckable }
            assertFalse("Renaming a library entry must not select it", renamed.isChecked)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun filamentProfileExposesAProjectColorPickerAndStickyApplyActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filamentProfile = context.getString(R.string.filament_profile)
        val filamentColor = context.getString(R.string.filament_color)
        val toolColor = context.getString(R.string.filament_color_for_tool, 1)
        val firstColor = context.getString(R.string.filament_color_option, 1)
        val secondColor = context.getString(R.string.filament_color_option, 2)
        val customColor = context.getString(R.string.custom_filament_color)
        val useColor = context.getString(R.string.use_filament_color)
        val revert = context.getString(R.string.revert_changes)
        val apply = context.getString(R.string.apply_changes)
        launchHarness(AccessibilityHarnessActivity.SCREEN_WORKSPACE_PROFILES).use {
            tapCenter(waitForNode(filamentProfile) { it.isClickable })
            val picker = scrollUntilClickable(filamentColor)
            assertTrue("Filament color must be editable from the active slot profile", picker.isFocusable)
            tapCenter(picker)

            val pickerNodes = waitForNodes(
                setOf(toolColor, firstColor, secondColor, customColor),
            )
            assertTrue(pickerNodes.any { it.isHeading && it.effectiveLabel().contains(toolColor) })
            assertTrue(
                "The current project color must expose its selected state",
                pickerNodes.any {
                    it.isCheckable && it.isChecked &&
                        it.effectiveLabel().contains(firstColor)
                },
            )
            assertTrue(
                "Custom color must be exposed as an editable field",
                pickerNodes.any { it.isEditable && it.effectiveLabel().contains(customColor) },
            )
            replaceEditableText(customColor, "#01A2FF")
            assertTrue(waitForNode(useColor) { it.isClickable }.performAction(
                AccessibilityNodeInfo.ACTION_CLICK,
            ))

            val actions = waitForNodes(setOf(revert, apply))
            assertTrue(actions.any { it.isClickable && it.effectiveLabel() == revert })
            assertTrue(actions.any { it.isClickable && it.effectiveLabel() == apply })
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
    fun supportFlowRatiosAreSearchableAdjustableSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slicingProfile = context.getString(R.string.slicing_profile)
        val supports = context.getString(R.string.supports)
        val searchSettings = context.getString(R.string.search_settings)
        val labels = listOf(
            context.getString(R.string.support_flow_ratio),
            context.getString(R.string.support_interface_flow_ratio),
        )
        launchHarness(AccessibilityHarnessActivity.SCREEN_WORKSPACE_PROFILES).use {
            tapCenter(waitForNode(slicingProfile) { it.isClickable })
            tapCenter(waitForNode(supports) { it.isClickable })
            labels.forEach { label ->
                replaceEditableText(searchSettings, label)
                val slider = scrollUntilNode(
                    label,
                    scrollAnchorLabel = searchSettings,
                    timeoutMillis = EXTENDED_SCROLL_TIMEOUT_MILLIS,
                ) { node -> node.className?.toString() == SEEK_BAR_CLASS }
                assertTrue("$label must be an adjustable support setting", slider.isEnabled)
            }
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

            val strengthTab = waitForNode(strength) { node ->
                node.isClickable && node.effectiveLabel() == strength
            }
            assertTrue(
                "A range must expose Orca-style setting categories",
                strengthTab.isVisibleToUser,
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

            val revertButton = waitForNode(revert) { it.isClickable }
            val applyButton = waitForNode(apply) { it.isClickable }
            assertTrue(
                "Apply must retain the requested 70/30 visual priority",
                applyButton.screenBounds().width() > revertButton.screenBounds().width() * 2,
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
        var observedLabels = emptyList<String>()
        do {
            val nodes = currentNodes()
            observedLabels = nodes.map { it.effectiveLabel() }.filter { it.isNotBlank() }.distinct()
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
        throw AssertionError(
            "Timed out waiting for accessibility labels: $labels; observed: ${observedLabels.take(40)}",
        )
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
        var observed = emptyList<String>()
        do {
            val nodes = currentNodes()
            observed = nodes.map { node ->
                "${node.effectiveLabel()}:${node.stateDescription}:${node.isVisibleToUser}"
            }.filter { it.isNotBlank() }.distinct().take(40)
            nodes.firstOrNull { node ->
                matches(node) && node.isVisibleToUser && node.effectiveLabel().contains(label)
            }?.let { return it }
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for accessibility node: $label; observed: $observed")
    }

    private fun waitForAnyNode(
        description: String,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        do {
            currentNodes().firstOrNull { node ->
                node.isVisibleToUser && matches(node)
            }?.let { return it }
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for accessibility node: $description")
    }

    private fun clickNamedAction(label: String) {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        do {
            val accepted = currentNodes()
                .filter { node ->
                    node.isVisibleToUser && node.isClickable && node.effectiveLabel() == label
                }
                .asReversed()
                .any { node -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            if (accepted) return
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out clicking accessibility action: $label")
    }

    private fun waitForSingleNamedAction(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        var observedCount = 0
        do {
            val actions = currentNodes().filter { node ->
                node.isVisibleToUser &&
                    node.isEnabled &&
                    node.isClickable &&
                    node.effectiveLabel() == label
            }
            observedCount = actions.size
            if (actions.size == 1) return actions.single()
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(
            "Expected one visible accessibility action for $label; observed $observedCount",
        )
    }

    private fun waitForLabelsGone(labels: Set<String>) {
        val deadline = SystemClock.elapsedRealtime() + NODE_TIMEOUT_MILLIS
        do {
            val visibleLabels = currentNodes()
                .filter { node -> node.isVisibleToUser }
                .map { node -> node.effectiveLabel() }
            if (labels.none { label -> visibleLabels.any { it.contains(label) } }) return
            SystemClock.sleep(NODE_POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for accessibility labels to disappear: $labels")
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
