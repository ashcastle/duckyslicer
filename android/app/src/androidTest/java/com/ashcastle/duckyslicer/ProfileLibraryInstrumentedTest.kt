package com.ashcastle.duckyslicer

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileLibraryInstrumentedTest {
    @Test
    fun clearingRetainedOwnerFlushesRecentProfilesBeforeDebounce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileRoot = File(context.filesDir, "profiles")
        val owner = ViewModelStore()
        profileRoot.deleteRecursively()
        try {
            val application = context.applicationContext as Application
            val library = ViewModelProvider(
                owner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[ProfileLibraryViewModel::class.java]
            waitUntil("profile recents did not load") {
                library.state.value.recentsLoaded
            }
            val selection = SliceOptions()
                .selectPrinter(PrinterProfile.U1_06)
                .selectFilament(FilamentProfile.PETG)
                .selectQuality(QualityProfile.DRAFT_06)
            assertTrue(library.recordSelection(selection))
            val dirty = library.state.value
            assertTrue(dirty.recentsRevision > dirty.persistedRecentsRevision)

            // Clear immediately, before the 350 ms recent-profile save can run.
            owner.clear()

            val restored = ProfileRecentStore(context).load()
            assertEquals(PrinterProfile.U1_06.id, restored.printerIds.firstOrNull())
            assertEquals(FilamentProfile.PETG.id, restored.filamentIds.firstOrNull())
            assertEquals(QualityProfile.DRAFT_06.id, restored.slicingIds.firstOrNull())
        } finally {
            owner.clear()
            profileRoot.deleteRecursively()
        }
    }

    @Test
    fun profileSaveAndRecentSelectionSurviveImmediateActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileRoot = File(context.filesDir, "profiles")
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        profileRoot.deleteRecursively()
        projectRoot.deleteRecursively()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retainedLibrary: ProfileLibraryViewModel
                lateinit var retainedProject: ProjectTransferViewModel
                scenario.onActivity { activity ->
                    retainedLibrary = ViewModelProvider(activity)[ProfileLibraryViewModel::class.java]
                    retainedProject = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                }
                waitUntil("profile and project state did not load") {
                    retainedLibrary.state.value.catalogLoaded &&
                        retainedLibrary.state.value.recentsLoaded &&
                        retainedProject.state.value.restored
                }
                val session = retainedProject.state.value
                val profileName = "Recreation ${UUID.randomUUID()}"
                assertTrue(
                    retainedLibrary.saveSlicing(
                        profileName,
                        session.sliceOptions.copy(fillDensity = 0.39f),
                        session.sessionRevision,
                    ),
                )
                assertTrue("The profile save must be active before recreation", retainedLibrary.state.value.busy)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedLibrary,
                        ViewModelProvider(recreated)[ProfileLibraryViewModel::class.java],
                    )
                    assertSame(
                        retainedProject,
                        ViewModelProvider(recreated)[ProjectTransferViewModel::class.java],
                    )
                }

                waitUntil("saved profile did not reach the retained catalog") {
                    !retainedLibrary.state.value.busy &&
                        retainedLibrary.state.value.catalog.slicing.any { it.name == profileName }
                }
                waitUntil("saved profile was not selected in its originating project session") {
                    retainedProject.state.value.sliceOptions.quality.name == profileName
                }
                val saved = retainedLibrary.state.value.catalog.slicing.single {
                    it.name == profileName
                }
                waitUntil("saved profile did not reach the retained recent list") {
                    retainedLibrary.state.value.recents.slicingIds.firstOrNull() == saved.id
                }

                assertEquals(0.39f, retainedProject.state.value.sliceOptions.fillDensity)
                assertTrue(ProfileStore(context).load().slicing.any { it.id == saved.id })
            }
        } finally {
            profileRoot.deleteRecursively()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun lateProfileSaveCannotReplaceNewerProjectSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileRoot = File(context.filesDir, "profiles")
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        profileRoot.deleteRecursively()
        projectRoot.deleteRecursively()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var library: ProfileLibraryViewModel
                lateinit var project: ProjectTransferViewModel
                scenario.onActivity { activity ->
                    library = ViewModelProvider(activity)[ProfileLibraryViewModel::class.java]
                    project = ViewModelProvider(activity)[ProjectTransferViewModel::class.java]
                }
                waitUntil("profile and project state did not load") {
                    library.state.value.catalogLoaded && project.state.value.restored
                }
                val initial = project.state.value
                val profileName = "Stale ${UUID.randomUUID()}"
                assertTrue(
                    library.saveSlicing(
                        profileName,
                        initial.sliceOptions.copy(fillDensity = 0.39f),
                        initial.sessionRevision,
                    ),
                )
                assertTrue("The profile save must be active before the newer edit", library.state.value.busy)
                assertTrue(
                    project.updateSession(
                        initial.history,
                        initial.history,
                        initial.sliceOptions,
                        initial.sliceOptions.copy(fillDensity = 0.44f),
                    ),
                )

                scenario.recreate()
                waitUntil("late profile save did not reach the retained catalog") {
                    !library.state.value.busy &&
                        library.state.value.catalog.slicing.any { it.name == profileName }
                }
                waitUntil("profile-save completion was not consumed") {
                    library.state.value.completion == null
                }

                assertEquals(0.44f, project.state.value.sliceOptions.fillDensity)
                assertTrue(project.state.value.sliceOptions.quality.name != profileName)
            }
        } finally {
            profileRoot.deleteRecursively()
            projectRoot.deleteRecursively()
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError(message)
    }
}
