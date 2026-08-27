package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogAvailabilityTest {
    @Test
    fun profileStoreRetainsBundledCatalogAvailabilitySeparatelyFromProfileData() {
        val directory = Files.createTempDirectory("duckyslicer-catalog-status-").toFile()
        try {
            val healthyCatalog = ProfileCatalog(sourceRevision = "pinned-revision")
            val healthyStore = ProfileStore(directory.resolve("healthy.json")) {
                ProfileCatalogLoadResult(
                    catalog = healthyCatalog,
                    bundledCatalogUnavailable = false,
                )
            }
            assertEquals("pinned-revision", healthyStore.load().sourceRevision)
            assertFalse(healthyStore.bundledCatalogUnavailable)

            val fallbackCatalog = ProfileCatalog()
            val fallbackStore = ProfileStore(directory.resolve("fallback.json")) {
                ProfileCatalogLoadResult(
                    catalog = fallbackCatalog,
                    bundledCatalogUnavailable = true,
                )
            }
            assertEquals(fallbackCatalog, fallbackStore.load())
            assertTrue(fallbackStore.bundledCatalogUnavailable)
            assertFalse(fallbackStore.storageUnavailable)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun profileLibraryStateKeepsFallbackUsableWhileExposingItsStatus() {
        val state = ProfileLibraryState(
            busy = false,
            catalog = ProfileCatalog(),
            catalogLoaded = true,
            bundledCatalogUnavailable = true,
            recentsLoaded = true,
        )

        assertTrue(state.catalog.printers.isNotEmpty())
        assertTrue(state.catalog.filaments.isNotEmpty())
        assertTrue(state.catalog.slicing.isNotEmpty())
        assertTrue(state.bundledCatalogUnavailable)
        assertFalse(state.storageUnavailable)
    }
}
