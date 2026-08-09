package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRecentsTest {
    @Test
    fun usageMovesProfilesToTheFrontWithoutDuplicatesAndCapsHistory() {
        var recents = ProfileRecents()
        repeat(7) { recents = recents.recordPrinter("printer-$it") }
        recents = recents.recordPrinter("printer-4")

        assertEquals(
            listOf("printer-4", "printer-6", "printer-5", "printer-3", "printer-2"),
            recents.printerIds,
        )
    }

    @Test
    fun threeProfileKindsRoundTripIndependently() = withStore { file, store ->
        val expected = ProfileRecents(
            printerIds = listOf("printer-b", "printer-a"),
            filamentIds = listOf("pla"),
            slicingIds = listOf("quality"),
        )

        store.save(expected)

        assertEquals(expected, ProfileRecentStore(file).load())
        assertFalse(store.storageUnavailable)
    }

    @Test
    fun corruptPrimaryRecoversLastKnownGoodRecentProfiles() = withStore { file, store ->
        store.save(ProfileRecents(printerIds = listOf("first")))
        store.save(ProfileRecents(printerIds = listOf("second", "first")))
        file.writeText("{broken")

        val recovered = ProfileRecentStore(file)
        assertEquals(listOf("first"), recovered.load().printerIds)
        assertFalse(recovered.storageUnavailable)
    }

    @Test
    fun unreadableRecentProfilesAreNotOverwritten() = withStore { file, store ->
        file.parentFile?.mkdirs()
        file.writeText("{broken-primary")
        File(file.parentFile, "${file.name}.bak").writeText("{broken-backup")

        assertEquals(ProfileRecents(), store.load())
        assertTrue(store.storageUnavailable)
    }

    private fun withStore(block: (File, ProfileRecentStore) -> Unit) {
        val directory = Files.createTempDirectory("duckyslicer-profile-recents-").toFile()
        try {
            val file = File(directory, "recent_profiles.json")
            block(file, ProfileRecentStore(file))
        } finally {
            directory.deleteRecursively()
        }
    }
}
