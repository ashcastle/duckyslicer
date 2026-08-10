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
}
