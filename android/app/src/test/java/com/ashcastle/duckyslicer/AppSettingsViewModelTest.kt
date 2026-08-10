package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsViewModelTest {
    @Test
    fun settingsUpdatesNormalizeValuesAdvanceRevisionAndClearFailure() {
        val updated = requireNotNull(
            AppSettingsState(
                revision = 8,
                message = AppSettingsMessage.SAVE_FAILED,
            ).withUpdatedSettings(
                AppSettings(
                    toolpathOpacity = Float.NaN,
                    toolpathDepthContrast = 4f,
                    connectionTimeoutSeconds = 100,
                ),
            ),
        )

        assertEquals(9L, updated.revision)
        assertEquals(0.92f, updated.settings.toolpathOpacity)
        assertEquals(1f, updated.settings.toolpathDepthContrast)
        assertEquals(60, updated.settings.connectionTimeoutSeconds)
        assertNull(updated.message)
    }

    @Test
    fun equivalentNormalizedSettingsDoNotScheduleAnotherWrite() {
        val current = AppSettingsState(settings = AppSettings(connectionTimeoutSeconds = 60))

        assertNull(
            current.withUpdatedSettings(
                current.settings.copy(connectionTimeoutSeconds = 100),
            ),
        )
    }
}
