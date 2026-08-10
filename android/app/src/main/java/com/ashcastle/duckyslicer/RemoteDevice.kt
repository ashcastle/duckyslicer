package com.ashcastle.duckyslicer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val MAX_REMOTE_CREDENTIAL_BYTES = 8 * 1_024
private const val MAX_REMOTE_RESPONSE_BYTES = 1 * 1_024 * 1_024
private const val MAX_REMOTE_GCODE_BYTES = 2L * 1_024 * 1_024 * 1_024
private const val MAX_REMOTE_PATH_LENGTH = 1_024

enum class RemoteDeviceKind {
    OCTOPRINT,
    KLIPPER,
}

data class RemoteDeviceProfile(
    val id: String,
    val name: String,
    val kind: RemoteDeviceKind,
    val baseUrl: String,
    val hasCredential: Boolean = false,
    val credentialKey: String? = null,
) {
    fun normalized(): RemoteDeviceProfile = copy(
        name = name.trim(),
        baseUrl = normalizeRemoteBaseUrl(baseUrl),
    )

    fun validate(): String? {
        if (id.length !in 1..128) return "address_invalid"
        if (name.trim().isEmpty()) return "name_required"
        if (name.length > 200 || baseUrl.length > 2_048) return "address_invalid"
        val uri = runCatching { URI(normalizeRemoteBaseUrl(baseUrl)) }.getOrNull()
            ?: return "address_invalid"
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "address_invalid"
        }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return "address_invalid"
        }
        if (uri.scheme == "http" && !isPrivateOrLocalHost(uri.host)) {
            return "cleartext_not_local"
        }
        return null
    }
}

data class RemoteDeviceDraft(
    val id: String? = null,
    val name: String = "",
    val kind: RemoteDeviceKind = RemoteDeviceKind.OCTOPRINT,
    val baseUrl: String = "http://",
    val credential: String = "",
)

data class RemoteDeviceStatus(
    val state: String,
    val fileName: String? = null,
    val progressPercent: Int? = null,
)

data class RemoteUpload(
    val profileId: String,
    val remotePath: String,
    val displayName: String,
)

internal fun remoteResultBelongsToSelection(
    operationProfileId: String,
    selectedProfileId: String?,
): Boolean = operationProfileId == selectedProfileId

internal fun normalizeRemoteBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "http://$trimmed"
}

internal fun isPrivateOrLocalHost(host: String): Boolean {
    val normalized = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
    if (normalized == "localhost" || normalized.endsWith(".local")) return true
    val isAddressLiteral = normalized.contains(':') || normalized.all { it.isDigit() || it == '.' }
    if (!isAddressLiteral) return false
    val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return false
    return isPrivateOrLocalAddress(address)
}

private fun isPrivateOrLocalAddress(address: InetAddress): Boolean =
    !address.isMulticastAddress && (
        address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || isCarrierGradeNat(address.address) ||
            isUniqueLocalIpv6(address.address)
        )

private fun isCarrierGradeNat(bytes: ByteArray): Boolean =
    bytes.size == 4 && bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xc0 == 0x40

private fun isUniqueLocalIpv6(bytes: ByteArray): Boolean =
    bytes.size == 16 && bytes[0].toInt() and 0xfe == 0xfc

internal data class ResolvedRemoteEndpoint(
    val uri: URI,
    val hostHeader: String?,
)

internal fun resolveRemoteEndpoint(
    endpoint: URI,
    addressResolver: (String) -> List<InetAddress>,
): ResolvedRemoteEndpoint {
    if (!endpoint.scheme.equals("http", ignoreCase = true)) {
        return ResolvedRemoteEndpoint(endpoint, null)
    }
    val originalHost = endpoint.host?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("cleartext_not_local")
    val addresses = try {
        addressResolver(originalHost)
    } catch (failure: Exception) {
        throw IllegalArgumentException("cleartext_not_local", failure)
    }
    require(addresses.isNotEmpty() && addresses.all(::isPrivateOrLocalAddress)) {
        "cleartext_not_local"
    }
    val pinnedAddress = addresses.first()
    val pinnedHost = requireNotNull(pinnedAddress.hostAddress)
        .replace("%", "%25")
        .asAuthorityHost()
    val port = endpoint.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    val query = endpoint.rawQuery?.let { "?$it" }.orEmpty()
    val pinnedUri = URI.create(
        "${endpoint.scheme.lowercase()}://$pinnedHost$port${endpoint.rawPath.orEmpty()}$query",
    )
    val hostHeader = originalHost.asAuthorityHost() + port
    return ResolvedRemoteEndpoint(pinnedUri, hostHeader)
}

private fun String.asAuthorityHost(): String =
    if (contains(':') && !(startsWith('[') && endsWith(']'))) "[$this]" else this

private fun resolveRemoteAddresses(host: String): List<InetAddress> =
    InetAddress.getAllByName(host).toList()

internal interface RemoteCredentialStore {
    fun contains(key: String): Boolean
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun prune(allowedKeys: Set<String>)
}

class RemoteDeviceStore internal constructor(
    private val file: File,
    private val secrets: RemoteCredentialStore,
) {
    private val durableDevices = DurableJsonFile(file, MAX_REMOTE_DEVICE_BYTES)

    constructor(context: Context) : this(
        File(context.filesDir, "remote_devices.json"),
        SecureCredentialStore(context.applicationContext),
    )

    @Volatile
    var storageUnavailable: Boolean = false
        private set

    @Volatile
    var credentialCleanupPending: Boolean = false
        private set

    @Synchronized
    fun load(): List<RemoteDeviceProfile> {
        val stored = durableDevices.read(::parseProfiles, ::isCompatibleRoot)
        storageUnavailable = !stored.status.mutationSafe
        val profiles = stored.value.orEmpty()
        credentialCleanupPending = false
        if (!storageUnavailable) {
            try {
                secrets.prune(profiles.mapNotNullTo(HashSet(), RemoteDeviceProfile::credentialKey))
            } catch (_: Exception) {
                // Metadata remains usable and every referenced credential is intact.
                // Retry orphan cleanup on the next successful load.
                credentialCleanupPending = true
            }
        }
        return profiles
    }

    @Synchronized
    fun save(draft: RemoteDeviceDraft): RemoteDeviceProfile {
        require(draft.credential.toByteArray(StandardCharsets.UTF_8).size <= MAX_REMOTE_CREDENTIAL_BYTES) {
            "credential_too_large"
        }
        val candidate = RemoteDeviceProfile(
            id = draft.id ?: UUID.randomUUID().toString(),
            name = draft.name,
            kind = draft.kind,
            baseUrl = draft.baseUrl,
        ).normalized()
        candidate.validate()?.let { throw IllegalArgumentException(it) }

        val existing = load()
        check(!storageUnavailable) { "saved_data_unreadable" }
        val previous = existing.firstOrNull { it.id == candidate.id }
        val endpointChanged = previous != null && (
            previous.kind != candidate.kind || previous.baseUrl != candidate.baseUrl
        )
        val suppliedCredential = draft.credential.trim().takeIf(String::isNotEmpty)
        val stagedCredential = suppliedCredential?.let { newCredentialKey() to it }
        val stagedCredentialKey = stagedCredential?.first
        val retainedCredentialKey = previous?.credentialKey
            ?.takeIf { !endpointChanged && secrets.contains(it) }
        val credentialKey = stagedCredentialKey ?: retainedCredentialKey
        val profile = candidate.copy(
            hasCredential = credentialKey != null,
            credentialKey = credentialKey,
        )
        val profiles = existing.filterNot { it.id == profile.id } + profile
        require(profiles.size <= MAX_REMOTE_DEVICES) { "too_many_remote_devices" }
        stagedCredential?.let { (key, value) -> secrets.put(key, value) }
        try {
            write(profiles.sortedBy { it.name.lowercase() })
        } catch (failure: Exception) {
            if (stagedCredentialKey != null) {
                try {
                    secrets.remove(stagedCredentialKey)
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
        return load().first { it.id == profile.id }
    }

    @Synchronized
    fun delete(profileId: String) {
        val existing = load()
        check(!storageUnavailable) { "saved_data_unreadable" }
        val removedCredentialKey = existing.firstOrNull { it.id == profileId }?.credentialKey
        write(existing.filterNot { it.id == profileId })
        // Refresh the metadata backup before pruning the deleted profile's key.
        // A crash on either side therefore leaves one complete generation.
        load()
        check(!storageUnavailable) { "saved_data_unreadable" }
        removedCredentialKey?.let(secrets::remove)
    }

    fun credential(profile: RemoteDeviceProfile): String = profile.credentialKey
        ?.let(secrets::get)
        .orEmpty()

    private fun write(profiles: List<RemoteDeviceProfile>) {
        val values = JSONArray()
        profiles.forEach { profile ->
            values.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("kind", profile.kind.name)
                    .put("baseUrl", profile.baseUrl)
                    .apply {
                        profile.credentialKey?.let { put("credentialKey", it) }
                    },
            )
        }
        durableDevices.write(
            JSONObject().put("version", REMOTE_DEVICE_SCHEMA_VERSION).put("devices", values),
            ::parseProfiles,
            ::isCompatibleRoot,
        )
        storageUnavailable = false
    }

    private fun parseProfiles(root: JSONObject): List<RemoteDeviceProfile>? {
        val schemaVersion = root.optInt("version", 0)
        if (schemaVersion !in 1..REMOTE_DEVICE_SCHEMA_VERSION) return null
        val values = root.optJSONArray("devices") ?: return null
        if (values.length() > MAX_REMOTE_DEVICES) return null
        val ids = HashSet<String>()
        val credentialKeys = HashSet<String>()
        val profiles = ArrayList<RemoteDeviceProfile>(values.length())
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: return null
            val id = value.optString("id")
            val kind = runCatching {
                RemoteDeviceKind.valueOf(value.optString("kind"))
            }.getOrNull() ?: return null
            val candidate = RemoteDeviceProfile(
                id = id,
                name = value.optString("name"),
                kind = kind,
                baseUrl = value.optString("baseUrl"),
            ).normalized()
            if (!ids.add(id) || candidate.validate() != null) return null
            val credentialKey = if (schemaVersion == 1) {
                id.takeIf(secrets::contains)
            } else if (!value.has("credentialKey") || value.isNull("credentialKey")) {
                null
            } else {
                value.optString("credentialKey")
                    .takeIf { it == id || GENERATED_CREDENTIAL_KEY.matches(it) }
                    ?: return null
            }
            if (credentialKey != null && !credentialKeys.add(credentialKey)) return null
            profiles += candidate.copy(
                hasCredential = credentialKey?.let(secrets::contains) == true,
                credentialKey = credentialKey,
            )
        }
        return profiles
    }

    private fun isCompatibleRoot(root: JSONObject): Boolean =
        root.optInt("version", 0) <= REMOTE_DEVICE_SCHEMA_VERSION

    private fun newCredentialKey(): String = "credential-${UUID.randomUUID()}"

    private companion object {
        const val REMOTE_DEVICE_SCHEMA_VERSION = 2
        const val MAX_REMOTE_DEVICE_BYTES = 256 * 1_024
        const val MAX_REMOTE_DEVICES = 128
        val GENERATED_CREDENTIAL_KEY = Regex(
            "credential-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
    }
}

private class SecureCredentialStore(context: Context) : RemoteCredentialStore {
    private val preferences = context.getSharedPreferences("remote_device_credentials", Context.MODE_PRIVATE)

    override fun contains(key: String): Boolean {
        requireCredentialKey(key)
        return preferences.contains(key)
    }

    override fun put(key: String, value: String) {
        requireCredentialKey(key)
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REMOTE_CREDENTIAL_BYTES) {
            "credential_too_large"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(
            byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + payload,
            Base64.NO_WRAP,
        )
        check(preferences.edit().putString(key, encoded).commit()) { "credential_write_failed" }
    }

    override fun get(key: String): String? {
        requireCredentialKey(key)
        val encoded = preferences.getString(key, null) ?: return null
        if (encoded.length > MAX_REMOTE_CREDENTIAL_BYTES * 4) return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.isNotEmpty())
            val ivLength = combined[0].toInt() and 0xff
            require(ivLength in 12..32 && combined.size > ivLength + 1)
            val payloadOffset = ivLength + 1
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, combined.copyOfRange(1, payloadOffset)),
            )
            val plaintext = cipher.doFinal(combined.copyOfRange(payloadOffset, combined.size))
            require(plaintext.size <= MAX_REMOTE_CREDENTIAL_BYTES)
            String(plaintext, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override fun remove(key: String) {
        requireCredentialKey(key)
        check(preferences.edit().remove(key).commit()) { "credential_delete_failed" }
    }

    override fun prune(allowedKeys: Set<String>) {
        val stale = preferences.all.keys - allowedKeys
        if (stale.isEmpty()) return
        val editor = preferences.edit()
        stale.forEach(editor::remove)
        check(editor.commit()) { "credential_delete_failed" }
    }

    private fun requireCredentialKey(key: String) {
        require(key.length in 1..MAX_REMOTE_CREDENTIAL_KEY_LENGTH && key.none(Char::isISOControl)) {
            "credential_key_invalid"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "duckyslicer.remote-device.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val MAX_REMOTE_CREDENTIAL_KEY_LENGTH = 200
    }
}

class RemoteDeviceClient(
    private val timeoutMillis: Int,
    private val addressResolver: (String) -> List<InetAddress> = ::resolveRemoteAddresses,
) {
    fun status(profile: RemoteDeviceProfile, credential: String): RemoteDeviceStatus {
        profile.validate()?.let { throw IllegalArgumentException(it) }
        return when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> octoPrintStatus(profile, credential)
            RemoteDeviceKind.KLIPPER -> moonrakerStatus(profile, credential)
        }
    }

    fun upload(
        profile: RemoteDeviceProfile,
        credential: String,
        gcode: File,
        onProgress: (Int) -> Unit = {},
    ): RemoteUpload {
        require(gcode.isFile) { "gcode_missing" }
        require(gcode.length() in 1..MAX_REMOTE_GCODE_BYTES) { "gcode_size_invalid" }
        val endpoint = when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> "/api/files/local"
            RemoteDeviceKind.KLIPPER -> "/server/files/upload"
        }
        val fields = when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> mapOf("select" to "true", "print" to "false")
            RemoteDeviceKind.KLIPPER -> mapOf("root" to "gcodes", "path" to "")
        }
        val response = SliceArtifactLease.acquire(gcode).use {
            multipart(profile, credential, endpoint, fields, gcode, onProgress)
        }
        val remotePath = when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> response.optJSONObject("files")
                ?.optJSONObject("local")?.optString("path")
            RemoteDeviceKind.KLIPPER -> response.optJSONObject("result")
                ?.optJSONObject("item")?.optString("path")
        }.orEmpty().ifBlank { gcode.name }.let(::safeRemotePath)
        return RemoteUpload(profile.id, remotePath, gcode.name)
    }

    fun start(profile: RemoteDeviceProfile, credential: String, upload: RemoteUpload) {
        require(upload.profileId == profile.id) { "upload_device_mismatch" }
        val remotePath = safeRemotePath(upload.remotePath)
        when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> request(
                profile,
                credential,
                "POST",
                "/api/files/local/${encodePath(remotePath)}",
                "{\"command\":\"select\",\"print\":true}",
            )
            RemoteDeviceKind.KLIPPER -> request(
                profile,
                credential,
                "POST",
                "/printer/print/start?filename=${encodeQuery(remotePath)}",
            )
        }
    }

    fun pause(profile: RemoteDeviceProfile, credential: String) = command(profile, credential, "pause")
    fun resume(profile: RemoteDeviceProfile, credential: String) = command(profile, credential, "resume")
    fun cancel(profile: RemoteDeviceProfile, credential: String) = command(profile, credential, "cancel")

    private fun command(profile: RemoteDeviceProfile, credential: String, command: String) {
        when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> {
                val octoCommand = if (command == "cancel") {
                    "{\"command\":\"cancel\"}"
                } else {
                    "{\"command\":\"pause\",\"action\":\"$command\"}"
                }
                request(profile, credential, "POST", "/api/job", octoCommand)
            }
            RemoteDeviceKind.KLIPPER -> request(
                profile,
                credential,
                "POST",
                "/printer/print/$command",
            )
        }
    }

    private fun octoPrintStatus(profile: RemoteDeviceProfile, credential: String): RemoteDeviceStatus {
        val response = request(profile, credential, "GET", "/api/job")
        return RemoteDeviceStatus(
            state = response.optString("state", "Unknown").take(200),
            fileName = response.optJSONObject("job")?.optJSONObject("file")?.optString("name")
                ?.take(MAX_REMOTE_PATH_LENGTH)?.takeIf(String::isNotBlank),
            progressPercent = response.optJSONObject("progress")?.optDouble("completion")
                ?.takeIf(Double::isFinite)?.toInt()?.coerceIn(0, 100),
        )
    }

    private fun moonrakerStatus(profile: RemoteDeviceProfile, credential: String): RemoteDeviceStatus {
        val response = request(
            profile,
            credential,
            "GET",
            "/printer/objects/query?print_stats&virtual_sdcard",
        )
        val status = response.optJSONObject("result")?.optJSONObject("status")
        val printStats = status?.optJSONObject("print_stats")
        val progress = status?.optJSONObject("virtual_sdcard")?.optDouble("progress")
        return RemoteDeviceStatus(
            state = printStats?.optString("state", "unknown")?.take(200) ?: "unknown",
            fileName = printStats?.optString("filename")?.take(MAX_REMOTE_PATH_LENGTH)
                ?.takeIf(String::isNotBlank),
            progressPercent = progress?.takeIf(Double::isFinite)?.times(100)?.toInt()
                ?.coerceIn(0, 100),
        )
    }

    private fun request(
        profile: RemoteDeviceProfile,
        credential: String,
        method: String,
        path: String,
        body: String? = null,
    ): JSONObject {
        val connection = open(profile, credential, path)
        return try {
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            connection.readJsonResponse()
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }

    private fun multipart(
        profile: RemoteDeviceProfile,
        credential: String,
        path: String,
        fields: Map<String, String>,
        file: File,
        onProgress: (Int) -> Unit,
    ): JSONObject {
        val boundary = "DuckySlicer-${UUID.randomUUID()}"
        val preamble = buildString {
            fields.forEach { (name, value) ->
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                append(value)
                append("\r\n")
            }
            append("--$boundary\r\n")
            append(
                "Content-Disposition: form-data; name=\"file\"; " +
                    "filename=\"${safeHeaderFileName(file.name)}\"\r\n",
            )
            append("Content-Type: text/x-gcode\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val closing = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val connection = open(profile, credential, path).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(preamble.size.toLong() + file.length() + closing.size)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                output.write(preamble)
                var sent = 0L
                var lastProgress = -1
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        val progress = ((sent * 100) / file.length().coerceAtLeast(1L))
                            .toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                }
                output.write(closing)
            }
            connection.readJsonResponse()
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }

    private fun open(
        profile: RemoteDeviceProfile,
        credential: String,
        path: String,
    ): HttpURLConnection {
        profile.validate()?.let { throw IllegalArgumentException(it) }
        require(credential.toByteArray(StandardCharsets.UTF_8).size <= MAX_REMOTE_CREDENTIAL_BYTES) {
            "credential_too_large"
        }
        val endpoint = resolveRemoteEndpoint(URI(profile.baseUrl + path), addressResolver)
        val url = endpoint.uri.toURL()
        val connection = if (endpoint.hostHeader != null) {
            url.openConnection(Proxy.NO_PROXY)
        } else {
            url.openConnection()
        } as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        endpoint.hostHeader?.let { connection.setRequestProperty("Host", it) }
        if (credential.isNotBlank()) {
            when (profile.kind) {
                RemoteDeviceKind.OCTOPRINT -> connection.setRequestProperty("X-Api-Key", credential)
                RemoteDeviceKind.KLIPPER -> {
                    if (credential.count { it == '.' } == 2) {
                        connection.setRequestProperty("Authorization", "Bearer $credential")
                    } else {
                        connection.setRequestProperty("X-Api-Key", credential)
                    }
                }
            }
        }
        return connection
    }
}

private fun HttpURLConnection.readJsonResponse(): JSONObject {
    return try {
        val code = responseCode
        if (code !in 200..299) throw RemoteDeviceException(code)
        val reportedLength = contentLengthLong
        require(reportedLength < 0 || reportedLength <= MAX_REMOTE_RESPONSE_BYTES) {
            "remote_response_too_large"
        }
        val bytes = inputStream?.use { it.readBoundedBytes(MAX_REMOTE_RESPONSE_BYTES) }
            ?: ByteArray(0)
        if (bytes.isEmpty()) JSONObject() else {
            parseBoundedJsonObject(bytes, MAX_REMOTE_RESPONSE_BYTES)
        }
    } finally {
        disconnect()
    }
}

private fun InputStream.readBoundedBytes(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1_024))
    val buffer = ByteArray(16 * 1_024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "remote_response_too_large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

class RemoteDeviceException(val statusCode: Int) : Exception("remote_request_failed_$statusCode")

private fun safeHeaderFileName(value: String): String =
    value.replace(Regex("[\\r\\n\\\"]"), "_")

private fun safeRemotePath(value: String): String {
    val normalized = value.trim().replace('\\', '/')
    require(normalized.length in 1..MAX_REMOTE_PATH_LENGTH) { "remote_path_invalid" }
    require(!normalized.startsWith('/') && normalized.none(Char::isISOControl)) {
        "remote_path_invalid"
    }
    require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "remote_path_invalid"
    }
    return normalized
}

private fun encodeQuery(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun encodePath(value: String): String = value.split('/').joinToString("/") { encodeQuery(it) }
