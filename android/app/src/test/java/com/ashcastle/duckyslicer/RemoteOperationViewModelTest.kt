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
            .beginRemoteOperation(3, "printer-a", uploadOperation = true)
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
        assertEquals(invalidated, afterLateProgress)
        assertFalse(completed.busy)
        assertNull(completed.uploadFor("printer-a"))
        assertNull(completed.messageFor("printer-a"))
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
