package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOperationViewModelTest {
    @Test
    fun resultsAreVisibleOnlyForTheirOriginatingProfile() {
        val completed = RemoteOperationState()
            .beginRemoteOperation(1, "printer-a")
            .finishRemoteOperation(
                1,
                "printer-a",
                RemoteOperationOutcome.Refreshed(RemoteDeviceStatus("printing")),
            )

        assertEquals("printing", completed.statusFor("printer-a")?.state)
        assertEquals(RemoteOperationMessage.CONNECTED, completed.messageFor("printer-a"))
        assertNull(completed.statusFor("printer-b"))
        assertNull(completed.messageFor("printer-b"))
    }

    @Test
    fun staleCompletionCannotFinishANewerOperation() {
        val firstComplete = RemoteOperationState()
            .beginRemoteOperation(1, "printer-a")
            .finishRemoteOperation(
                1,
                "printer-a",
                RemoteOperationOutcome.Refreshed(RemoteDeviceStatus("idle")),
            )
        val secondActive = firstComplete.beginRemoteOperation(2, "printer-a")
        val afterLateFirst = secondActive.finishRemoteOperation(
            1,
            "printer-a",
            RemoteOperationOutcome.Commanded("printing", RemoteOperationMessage.STARTED),
        )

        assertEquals(secondActive, afterLateFirst)
        assertTrue(afterLateFirst.busy)
        assertEquals(2, afterLateFirst.operationId)
    }

    @Test
    fun invalidatedUploadCannotBecomePrintable() {
        val active = RemoteOperationState()
            .beginRemoteOperation(3, "printer-a", RemoteNetworkOperationKind.UPLOAD)
            .withRemoteUploadProgress(3, "printer-a", 42)
        val invalidated = active.invalidateRemoteUpload()
        val afterLateProgress = invalidated.withRemoteUploadProgress(3, "printer-a", 80)
        val completed = afterLateProgress.finishRemoteOperation(
            3,
            "printer-a",
            RemoteOperationOutcome.Uploaded(
                RemoteUpload("printer-a", "stale.gcode", "stale.gcode"),
            ),
        )

        assertNull(invalidated.uploadProgress)
        assertTrue(invalidated.cancellationRequested)
        assertEquals(invalidated, afterLateProgress)
        assertFalse(completed.busy)
        assertNull(completed.uploadFor("printer-a"))
        assertNull(completed.messageFor("printer-a"))
    }

    @Test
    fun explicitUploadCancellationStopsProgressAndReportsOneTerminalNotice() {
        val active = RemoteOperationState()
            .beginRemoteOperation(7, "printer-a", RemoteNetworkOperationKind.UPLOAD)
            .withRemoteUploadProgress(7, "printer-a", 35)
        val canceling = active.withRemoteRequestCancellationRequested(7, "printer-a")
        val duplicate = canceling.withRemoteRequestCancellationRequested(7, "printer-a")
        val afterLateProgress = duplicate.withRemoteUploadProgress(7, "printer-a", 90)
        val completed = afterLateProgress.finishRemoteOperation(
            7,
            "printer-a",
            RemoteOperationOutcome.RequestCanceled(RemoteNetworkOperationKind.UPLOAD),
        )

        assertTrue(canceling.cancellationRequested)
        assertNull(canceling.uploadProgress)
        assertEquals(canceling, duplicate)
        assertEquals(canceling, afterLateProgress)
        assertFalse(completed.busy)
        assertFalse(completed.cancellationRequested)
        assertEquals(
            RemoteOperationMessage.UPLOAD_CANCELED,
            completed.messageFor("printer-a"),
        )
        assertNull(completed.invalidateRemoteUpload().messageFor("printer-a"))
    }

    @Test
    fun invalidatingAnActiveUploadKeepsItsCancellationSilent() {
        val invalidated = RemoteOperationState()
            .beginRemoteOperation(8, "printer-a", RemoteNetworkOperationKind.UPLOAD)
            .invalidateRemoteUpload()
        val completed = invalidated.finishRemoteOperation(
            8,
            "printer-a",
            RemoteOperationOutcome.RequestCanceled(RemoteNetworkOperationKind.UPLOAD),
        )

        assertTrue(invalidated.cancellationRequested)
        assertFalse(completed.busy)
        assertNull(completed.uploadFor("printer-a"))
        assertNull(completed.messageFor("printer-a"))
    }

    @Test
    fun refreshCancellationRejectsDuplicatesAndLateSuccess() {
        val previousStatus = RemoteStatusSnapshot("printer-a", RemoteDeviceStatus("idle"))
        val active = RemoteOperationState(status = previousStatus)
            .beginRemoteOperation(9, "printer-a", RemoteNetworkOperationKind.REFRESH)
        val canceling = active.withRemoteRequestCancellationRequested(9, "printer-a")
        val duplicate = canceling.withRemoteRequestCancellationRequested(9, "printer-a")
        val staleOperation = canceling.withRemoteRequestCancellationRequested(8, "printer-a")
        val completed = canceling.finishRemoteOperation(
            9,
            "printer-a",
            RemoteOperationOutcome.Refreshed(RemoteDeviceStatus("printing")),
        )

        assertTrue(canceling.requestCancellationRequestedFor("printer-a"))
        assertEquals(canceling, duplicate)
        assertEquals(canceling, staleOperation)
        assertFalse(completed.busy)
        assertEquals("idle", completed.statusFor("printer-a")?.state)
        assertEquals(RemoteOperationMessage.REQUEST_CANCELED, completed.messageFor("printer-a"))
    }

    @Test
    fun commandCancellationCannotApplyALateStateChange() {
        val active = RemoteOperationState(
            status = RemoteStatusSnapshot("printer-a", RemoteDeviceStatus("printing")),
        ).beginRemoteOperation(10, "printer-a", RemoteNetworkOperationKind.COMMAND)
        val completed = active
            .withRemoteRequestCancellationRequested(10, "printer-a")
            .finishRemoteOperation(
                10,
                "printer-a",
                RemoteOperationOutcome.Commanded("paused", RemoteOperationMessage.PAUSED),
            )

        assertEquals("printing", completed.statusFor("printer-a")?.state)
        assertEquals(RemoteOperationMessage.REQUEST_CANCELED, completed.messageFor("printer-a"))
    }

    @Test
    fun commandCompletionRetainsFileAndUpdatesState() {
        val uploaded = RemoteOperationState(
            upload = RemoteUpload("printer-a", "duck.gcode", "duck.gcode"),
        )
        val completed = uploaded.beginRemoteOperation(4, "printer-a").finishRemoteOperation(
            4,
            "printer-a",
            RemoteOperationOutcome.Commanded("printing", RemoteOperationMessage.STARTED),
        )

        assertEquals("printing", completed.statusFor("printer-a")?.state)
        assertEquals("duck.gcode", completed.statusFor("printer-a")?.fileName)
        assertEquals(RemoteOperationMessage.STARTED, completed.messageFor("printer-a"))
    }

    @Test
    fun profileSaveSelectsTheDurableResultAndClearsOldPrinterState() {
        val old = RemoteDeviceProfile(
            "printer-a",
            "Printer A",
            RemoteDeviceKind.OCTOPRINT,
            "http://127.0.0.1:5000",
        )
        val saved = RemoteDeviceProfile(
            "printer-b",
            "Printer B",
            RemoteDeviceKind.KLIPPER,
            "http://127.0.0.1:7125",
        )
        val completed = RemoteOperationState(
            profiles = listOf(old),
            profilesLoaded = true,
            selectedProfileId = old.id,
            status = RemoteStatusSnapshot(old.id, RemoteDeviceStatus("idle")),
            upload = RemoteUpload(old.id, "old.gcode", "old.gcode"),
        ).beginRemoteOperation(5, saved.id).finishRemoteOperation(
            5,
            saved.id,
            RemoteOperationOutcome.ProfileSaved(saved, listOf(old, saved)),
        )

        assertEquals(saved, completed.selectedProfile())
        assertNull(completed.status)
        assertNull(completed.upload)
        assertEquals(RemoteOperationMessage.PROFILE_SAVED, completed.messageFor(old.id))
        assertFalse(completed.busy)
    }

    @Test
    fun deletingTheSelectedProfileChoosesTheFirstRemainingProfile() {
        val first = RemoteDeviceProfile(
            "printer-a",
            "Printer A",
            RemoteDeviceKind.OCTOPRINT,
            "http://127.0.0.1:5000",
        )
        val second = RemoteDeviceProfile(
            "printer-b",
            "Printer B",
            RemoteDeviceKind.KLIPPER,
            "http://127.0.0.1:7125",
        )
        val completed = RemoteOperationState(
            profiles = listOf(first, second),
            profilesLoaded = true,
            selectedProfileId = second.id,
            status = RemoteStatusSnapshot(second.id, RemoteDeviceStatus("printing")),
            upload = RemoteUpload(second.id, "old.gcode", "old.gcode"),
        ).beginRemoteOperation(6, second.id).finishRemoteOperation(
            6,
            second.id,
            RemoteOperationOutcome.ProfileDeleted(second.id, listOf(first)),
        )

        assertEquals(first, completed.selectedProfile())
        assertNull(completed.status)
        assertNull(completed.upload)
        assertEquals(RemoteOperationMessage.PROFILE_DELETED, completed.messageFor(first.id))
    }
}
