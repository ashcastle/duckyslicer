package com.ashcastle.duckyslicer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
) {
    fun normalized(): RemoteDeviceProfile = copy(
        name = name.trim(),
        baseUrl = normalizeRemoteBaseUrl(baseUrl),
    )

    fun validate(): String? {
        if (name.trim().isEmpty()) return "name_required"
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

private fun normalizeRemoteBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "http://$trimmed"
}

internal fun isPrivateOrLocalHost(host: String): Boolean {
    val normalized = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
    if (normalized == "localhost" || normalized.endsWith(".local")) return true
    val isAddressLiteral = normalized.contains(':') || normalized.all { it.isDigit() || it == '.' }
    if (!isAddressLiteral) return false
    val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return false
    return address.isAnyLocalAddress || address.isLoopbackAddress ||
        address.isLinkLocalAddress || address.isSiteLocalAddress ||
        isCarrierGradeNat(address.address)
}

private fun isCarrierGradeNat(bytes: ByteArray): Boolean =
    bytes.size == 4 && bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xc0 == 0x40

class RemoteDeviceStore(context: Context) {
    private val file = File(context.filesDir, "remote_devices.json")
    private val secrets = SecureCredentialStore(context.applicationContext)

    fun load(): List<RemoteDeviceProfile> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val values = JSONObject(file.readText()).optJSONArray("devices") ?: JSONArray()
            buildList {
                repeat(values.length()) { index ->
                    val value = values.optJSONObject(index) ?: return@repeat
                    val id = value.optString("id")
                    val kind = runCatching {
                        RemoteDeviceKind.valueOf(value.optString("kind"))
                    }.getOrNull() ?: return@repeat
                    val profile = RemoteDeviceProfile(
                        id = id,
                        name = value.optString("name"),
                        kind = kind,
                        baseUrl = value.optString("baseUrl"),
                        hasCredential = secrets.contains(id),
                    ).normalized()
                    if (profile.validate() == null) add(profile)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(draft: RemoteDeviceDraft): RemoteDeviceProfile {
        val profile = RemoteDeviceProfile(
            id = draft.id ?: UUID.randomUUID().toString(),
            name = draft.name,
            kind = draft.kind,
            baseUrl = draft.baseUrl,
            hasCredential = draft.credential.isNotBlank() || draft.id?.let(secrets::contains) == true,
        ).normalized()
        profile.validate()?.let { throw IllegalArgumentException(it) }

        val profiles = load().filterNot { it.id == profile.id } + profile
        write(profiles.sortedBy { it.name.lowercase() })
        if (draft.credential.isNotBlank()) secrets.put(profile.id, draft.credential.trim())
        return profile.copy(hasCredential = secrets.contains(profile.id))
    }

    fun delete(profileId: String) {
        write(load().filterNot { it.id == profileId })
        secrets.remove(profileId)
    }

    fun credential(profileId: String): String = secrets.get(profileId).orEmpty()

    private fun write(profiles: List<RemoteDeviceProfile>) {
        val values = JSONArray()
        profiles.forEach { profile ->
            values.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("kind", profile.kind.name)
                    .put("baseUrl", profile.baseUrl),
            )
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(JSONObject().put("version", 1).put("devices", values).toString())
        check(temporary.renameTo(file) || runCatching {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }.isSuccess) { "remote_profile_write_failed" }
    }
}

private class SecureCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("remote_device_credentials", Context.MODE_PRIVATE)

    fun contains(id: String): Boolean = preferences.contains(id)

    fun put(id: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(
            byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + payload,
            Base64.NO_WRAP,
        )
        check(preferences.edit().putString(id, encoded).commit()) { "credential_write_failed" }
    }

    fun get(id: String): String? {
        val encoded = preferences.getString(id, null) ?: return null
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
            String(cipher.doFinal(combined.copyOfRange(payloadOffset, combined.size)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun remove(id: String) {
        preferences.edit().remove(id).apply()
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
    }
}

class RemoteDeviceClient(private val timeoutMillis: Int) {
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
        val endpoint = when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> "/api/files/local"
            RemoteDeviceKind.KLIPPER -> "/server/files/upload"
        }
        val fields = when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> mapOf("select" to "true", "print" to "false")
            RemoteDeviceKind.KLIPPER -> mapOf("root" to "gcodes", "path" to "")
        }
        val response = multipart(profile, credential, endpoint, fields, gcode, onProgress)
        val remotePath = when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> response.optJSONObject("files")
                ?.optJSONObject("local")?.optString("path")
            RemoteDeviceKind.KLIPPER -> response.optJSONObject("result")
                ?.optJSONObject("item")?.optString("path")
        }.orEmpty().ifBlank { gcode.name }
        return RemoteUpload(profile.id, remotePath, gcode.name)
    }

    fun start(profile: RemoteDeviceProfile, credential: String, upload: RemoteUpload) {
        require(upload.profileId == profile.id) { "upload_device_mismatch" }
        when (profile.kind) {
            RemoteDeviceKind.OCTOPRINT -> request(
                profile,
                credential,
                "POST",
                "/api/files/local/${encodePath(upload.remotePath)}",
                "{\"command\":\"select\",\"print\":true}",
            )
            RemoteDeviceKind.KLIPPER -> request(
                profile,
                credential,
                "POST",
                "/printer/print/start?filename=${encodeQuery(upload.remotePath)}",
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
            state = response.optString("state", "Unknown"),
            fileName = response.optJSONObject("job")?.optJSONObject("file")?.optString("name")
                ?.takeIf(String::isNotBlank),
            progressPercent = response.optJSONObject("progress")?.optDouble("completion")
                ?.takeUnless(Double::isNaN)?.toInt()?.coerceIn(0, 100),
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
            state = printStats?.optString("state", "unknown") ?: "unknown",
            fileName = printStats?.optString("filename")?.takeIf(String::isNotBlank),
            progressPercent = progress?.takeUnless(Double::isNaN)?.times(100)?.toInt()?.coerceIn(0, 100),
        )
    }

    private fun request(
        profile: RemoteDeviceProfile,
        credential: String,
        method: String,
        path: String,
        body: String? = null,
    ): JSONObject {
        val connection = open(profile, credential, path).apply {
            requestMethod = method
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.bufferedWriter().use { it.write(body) }
            }
        }
        return connection.readJsonResponse()
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
        return connection.readJsonResponse()
    }

    private fun open(
        profile: RemoteDeviceProfile,
        credential: String,
        path: String,
    ): HttpURLConnection {
        val connection = URI(profile.baseUrl + path).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
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
        val raw = (if (code in 200..299) inputStream else errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw RemoteDeviceException(code)
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } finally {
        disconnect()
    }
}

class RemoteDeviceException(val statusCode: Int) : Exception("remote_request_failed_$statusCode")

private fun safeHeaderFileName(value: String): String =
    value.replace(Regex("[\\r\\n\\\"]"), "_")

private fun encodeQuery(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun encodePath(value: String): String = value.split('/').joinToString("/") { encodeQuery(it) }
