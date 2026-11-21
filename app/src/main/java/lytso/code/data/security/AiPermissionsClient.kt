package lytso.code.data.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import lytso.code.data.daemon.IdeApiClient
import lytso.code.data.preferences.AuthPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class AiPermissionPolicy(
    val allowRoot: Boolean = false,
    val allowAllFiles: Boolean = false,
    val allowShellExec: Boolean = true,
    val allowFileWrite: Boolean = true,
    val allowNetwork: Boolean = true
)

data class AiPermissionsSnapshot(
    val policy: AiPermissionPolicy = AiPermissionPolicy(),
    val rootBinaryFound: Boolean = false,
    val rootGranted: Boolean = false,
    val suPath: String = "",
    val loaded: Boolean = false,
    val error: String? = null
)

/**
 * Client for `/api/ai/permissions` on the IDE bridge: verifies real device root access and applies
 * modular AI capability controls (including the real Termux `su` PATH guard).
 */
object AiPermissionsClient {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val _state = MutableStateFlow(AiPermissionsSnapshot())
    val state: StateFlow<AiPermissionsSnapshot> = _state.asStateFlow()

    private val baseUrl: String get() = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')

    private fun parsePolicy(o: JSONObject?): AiPermissionPolicy {
        if (o == null) return AiPermissionPolicy()
        return AiPermissionPolicy(
            allowRoot = o.optBoolean("allowRoot", false),
            allowAllFiles = o.optBoolean("allowAllFiles", false),
            allowShellExec = o.optBoolean("allowShellExec", true),
            allowFileWrite = o.optBoolean("allowFileWrite", true),
            allowNetwork = o.optBoolean("allowNetwork", true)
        )
    }

    suspend fun refresh(): Result<AiPermissionsSnapshot> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/api/ai/permissions").get().build()
            IdeApiClient.okHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (!resp.isSuccessful || json == null) {
                    val err = json?.optString("error")?.ifBlank { null } ?: "Bridge returned ${resp.code}"
                    val snap = _state.value.copy(loaded = true, error = err)
                    _state.value = snap
                    return@withContext Result.failure(Exception(err))
                }
                val snap = AiPermissionsSnapshot(
                    policy = parsePolicy(json.optJSONObject("policy")),
                    rootBinaryFound = json.optBoolean("rootBinary", false),
                    rootGranted = json.optBoolean("rootGranted", false),
                    suPath = json.optString("suPath", ""),
                    loaded = true,
                    error = null
                )
                _state.value = snap
                Result.success(snap)
            }
        } catch (e: Exception) {
            val snap = _state.value.copy(loaded = true, error = e.message ?: "Bridge unreachable")
            _state.value = snap
            Result.failure(e)
        }
    }

    suspend fun update(policy: AiPermissionPolicy): Result<AiPermissionsSnapshot> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("allowRoot", policy.allowRoot)
                .put("allowAllFiles", policy.allowAllFiles)
                .put("allowShellExec", policy.allowShellExec)
                .put("allowFileWrite", policy.allowFileWrite)
                .put("allowNetwork", policy.allowNetwork)
            val req = Request.Builder()
                .url("$baseUrl/api/ai/permissions")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            IdeApiClient.okHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (!resp.isSuccessful || json == null || !json.optBoolean("success", false)) {
                    val err = json?.optString("error")?.ifBlank { null } ?: "Update failed (${resp.code})"
                    val snap = _state.value.copy(
                        rootBinaryFound = json?.optBoolean("rootBinary", _state.value.rootBinaryFound) ?: _state.value.rootBinaryFound,
                        rootGranted = json?.optBoolean("rootGranted", _state.value.rootGranted) ?: _state.value.rootGranted,
                        error = err
                    )
                    _state.value = snap
                    return@withContext Result.failure(Exception(err))
                }
                val csrf = json.optString("csrfToken", "")
                if (csrf.isNotBlank()) {
                    lytso.code.data.remote.core.AgyCsrfManager.instance.setCachedToken(AuthPreferences.currentHubUrl, csrf)
                }
                val snap = AiPermissionsSnapshot(
                    policy = parsePolicy(json.optJSONObject("policy")),
                    rootBinaryFound = json.optBoolean("rootBinary", false),
                    rootGranted = json.optBoolean("rootGranted", false),
                    suPath = json.optString("suPath", ""),
                    loaded = true,
                    error = null
                )
                _state.value = snap
                Result.success(snap)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
