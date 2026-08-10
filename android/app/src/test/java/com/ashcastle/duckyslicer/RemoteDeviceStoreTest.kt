package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceStoreTest {
    @Test
    fun credentialsUseGenerationsAndDoNotFollowAChangedEndpoint() = withStore { file, secrets, store ->
        val original = store.save(
            RemoteDeviceDraft(
                id = "printer",
                name = "Printer",
                kind = RemoteDeviceKind.OCTOPRINT,
                baseUrl = "http://127.0.0.1:5000",
                credential = "first-secret",
            ),
        )

        assertTrue(original.hasCredential)
        assertNotEquals(original.id, original.credentialKey)
        assertEquals("first-secret", store.credential(original))
        assertEquals(2, JSONObject(file.readText()).getInt("version"))

        val renamed = store.save(
            RemoteDeviceDraft(
                id = original.id,
                name = "Renamed",
                kind = original.kind,
                baseUrl = original.baseUrl,
            ),
        )
        assertEquals(original.credentialKey, renamed.credentialKey)
        assertEquals("first-secret", store.credential(renamed))

        val rebound = store.save(
            RemoteDeviceDraft(
                id = original.id,
                name = "Replacement",
                kind = RemoteDeviceKind.KLIPPER,
                baseUrl = "http://127.0.0.1:7125",
            ),
        )
        assertFalse(rebound.hasCredential)
        assertNull(rebound.credentialKey)
        assertEquals("", store.credential(rebound))
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun legacyProfileCredentialsMigrateWithoutEnteringPlaintextMetadata() =
        withStore { file, secrets, store ->
            secrets.put("legacy", "legacy-secret")
            file.writeText(
                JSONObject()
                    .put("version", 1)
                    .put(
                        "devices",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "legacy")
                                .put("name", "Legacy")
                                .put("kind", RemoteDeviceKind.OCTOPRINT.name)
                                .put("baseUrl", "http://127.0.0.1:5000"),
                        ),
                    )
                    .toString(),
            )

            val legacy = store.load().single()
            assertEquals("legacy", legacy.credentialKey)
            assertEquals("legacy-secret", store.credential(legacy))

            val migrated = store.save(
                RemoteDeviceDraft(
                    id = legacy.id,
                    name = legacy.name,
                    kind = legacy.kind,
                    baseUrl = legacy.baseUrl,
                    credential = "replacement-secret",
                ),
            )

            assertNotEquals(legacy.id, migrated.credentialKey)
            assertEquals("replacement-secret", store.credential(migrated))
            assertFalse(file.readText().contains("replacement-secret"))
            assertEquals(2, JSONObject(file.readText()).getInt("version"))
            assertEquals(setOf(migrated.credentialKey), secrets.values.keys)
        }

    @Test
    fun failedMetadataCommitCannotBindAStagedCredentialToTheOldProfile() =
        withStore { file, secrets, store ->
            val original = store.save(
                RemoteDeviceDraft(
                    id = "transactional",
                    name = "Original",
                    kind = RemoteDeviceKind.OCTOPRINT,
                    baseUrl = "http://127.0.0.1:5000",
                    credential = "original-secret",
                ),
            )
            val originalKey = requireNotNull(original.credentialKey)
            secrets.afterPut = { _, value ->
                if (value == "staged-secret") {
                    assertTrue(file.delete())
                    assertTrue(file.mkdir())
                    File(file, "blocks-repair").writeText("keep")
                }
            }

            assertThrows(IllegalStateException::class.java) {
                store.save(
                    RemoteDeviceDraft(
                        id = original.id,
                        name = "Replacement",
                        kind = RemoteDeviceKind.KLIPPER,
                        baseUrl = "http://127.0.0.1:7125",
                        credential = "staged-secret",
                    ),
                )
            }

            assertEquals(mapOf(originalKey to "original-secret"), secrets.values)
            val recoveredStore = RemoteDeviceStore(file, secrets)
            val recovered = recoveredStore.load().single()
            assertTrue(recoveredStore.storageUnavailable)
            assertEquals(original.baseUrl, recovered.baseUrl)
            assertEquals("original-secret", recoveredStore.credential(recovered))
        }

    @Test
    fun deletingAProfileRemovesItsExactCredentialAfterMetadataIsDurable() =
        withStore { _, secrets, store ->
            val saved = store.save(
                RemoteDeviceDraft(
                    id = "deleted",
                    name = "Deleted printer",
                    kind = RemoteDeviceKind.OCTOPRINT,
                    baseUrl = "http://127.0.0.1:5000",
                    credential = "delete-me",
                ),
            )
            val credentialKey = requireNotNull(saved.credentialKey)

            store.delete(saved.id)

            assertFalse(secrets.contains(credentialKey))
            assertTrue(store.load().isEmpty())
        }

    @Test
    fun deleteRetainsCredentialWhenBackupRefreshFailsAfterMetadataCommit() =
        withStore { file, secrets, store ->
            val deleted = store.save(
                RemoteDeviceDraft(
                    id = "deleted",
                    name = "Deleted printer",
                    kind = RemoteDeviceKind.OCTOPRINT,
                    baseUrl = "http://127.0.0.1:5000",
                    credential = "keep-until-durable",
                ),
            )
            store.save(
                RemoteDeviceDraft(
                    id = "retained",
                    name = "Retained printer",
                    kind = RemoteDeviceKind.KLIPPER,
                    baseUrl = "http://127.0.0.1:7125",
                    credential = "retained-secret",
                ),
            )
            val deletedCredentialKey = requireNotNull(deleted.credentialKey)
            val backup = File(file.parentFile, "${file.name}.bak")
            var containsCalls = 0
            secrets.afterContains = {
                containsCalls += 1
                if (containsCalls == 5) {
                    assertTrue(backup.delete())
                    assertTrue(backup.mkdir())
                    File(backup, "blocks-refresh").writeText("keep")
                }
            }

            assertThrows(IllegalStateException::class.java) {
                store.delete(deleted.id)
            }

            assertTrue(store.storageUnavailable)
            assertTrue(secrets.contains(deletedCredentialKey))
            assertFalse(file.readText().contains("\"deleted\""))
            assertTrue(backup.isDirectory)
        }

    @Test
    fun orphanCleanupFailureKeepsProfilesVisibleAndRetriesLater() =
        withStore { file, secrets, store ->
            file.writeText(
                JSONObject()
                    .put("version", 2)
                    .put(
                        "devices",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "visible")
                                .put("name", "Visible printer")
                                .put("kind", RemoteDeviceKind.OCTOPRINT.name)
                                .put("baseUrl", "http://127.0.0.1:5000"),
                        ),
                    )
                    .toString(),
            )
            val orphanKey = "credential-00000000-0000-4000-8000-000000000000"
            secrets.put(orphanKey, "orphan")
            secrets.failPrune = true

            assertEquals("visible", store.load().single().id)
            assertTrue(store.credentialCleanupPending)
            assertTrue(secrets.contains(orphanKey))

            secrets.failPrune = false
            assertEquals("visible", store.load().single().id)
            assertFalse(store.credentialCleanupPending)
            assertFalse(secrets.contains(orphanKey))
        }

    private fun withStore(
        block: (File, FakeCredentialStore, RemoteDeviceStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("duckyslicer-remote-store-").toFile()
        try {
            val file = File(directory, "remote_devices.json")
            val secrets = FakeCredentialStore()
            block(file, secrets, RemoteDeviceStore(file, secrets))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeCredentialStore : RemoteCredentialStore {
        val values = LinkedHashMap<String, String>()
        var afterPut: ((String, String) -> Unit)? = null
        var afterContains: (() -> Unit)? = null
        var failPrune: Boolean = false

        override fun contains(key: String): Boolean {
            afterContains?.invoke()
            return key in values
        }

        override fun put(key: String, value: String) {
            values[key] = value
            afterPut?.invoke(key, value)
        }

        override fun get(key: String): String? = values[key]

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun prune(allowedKeys: Set<String>) {
            check(!failPrune) { "credential_delete_failed" }
            values.keys.retainAll(allowedKeys)
        }
    }
}
