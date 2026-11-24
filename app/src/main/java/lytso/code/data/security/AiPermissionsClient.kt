package lytso.code.data.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import lytso.code.data.daemon.IdeApiClient
import lytso.code.data.preferences.AuthPreferences
import lytso.code.domain.model.ToolType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AiPermissionPolicy(
    val allowRoot: Boolean = false,
    val allowAllFiles: Boolean = true,
    val allowShellExec: Boolean = true,
    val allowFileWrite: Boolean = true,
    val allowNetwork: Boolean = true,
    val blockedTools: List<String> = emptyList()
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
        val blockedArr = o.optJSONArray("blockedTools")
        val blocked = mutableListOf<String>()
        if (blockedArr != null) {
            for (i in 0 until blockedArr.length()) {
                val item = blockedArr.optString(i, "").trim().lowercase()
                if (item.isNotBlank() && item !in blocked) blocked.add(item)
            }
        }
        return AiPermissionPolicy(
            allowRoot = o.optBoolean("allowRoot", false),
            allowAllFiles = o.optBoolean("allowAllFiles", true),
            allowShellExec = o.optBoolean("allowShellExec", true),
            allowFileWrite = o.optBoolean("allowFileWrite", true),
            allowNetwork = o.optBoolean("allowNetwork", true),
            blockedTools = blocked
        )
    }

    /**
     * Checks whether a specific tool should be abruptly interrupted according to the active
     * blockedTools list or modular AI capability switches.
     */
    fun isToolBlocked(toolName: String, toolType: ToolType? = null, command: String = ""): Boolean {
        val policy = _state.value.policy
        if (toolType == ToolType.BASH && !policy.allowShellExec) return true
        if (toolType == ToolType.EDIT_FILE && !policy.allowFileWrite) return true
        if ((toolType == ToolType.SEARCH_WEB || toolType == ToolType.READ_URL) && !policy.allowNetwork) return true
        if (policy.blockedTools.isEmpty()) return false
        val normName = toolName.trim().lowercase()
        val normCmd = command.trim().lowercase()
        return policy.blockedTools.any { rawRule ->
            val rule = rawRule.trim().lowercase()
            if (rule.isBlank()) return@any false
            normName == rule ||
                (normName.isNotBlank() && normName.endsWith("_$rule")) ||
                (toolType == ToolType.MCP && normCmd.contains(rule)) ||
                (rule.contains("/") && normCmd.contains(rule))
        }
    }

    suspend fun blockTool(toolName: String): Result<AiPermissionsSnapshot> {
        val norm = toolName.trim().lowercase()
        if (norm.isBlank()) return Result.success(_state.value)
        val cur = _state.value.policy
        if (norm in cur.blockedTools) return Result.success(_state.value)
        val next = cur.copy(blockedTools = cur.blockedTools + norm)
        _state.value = _state.value.copy(policy = next)
        return update(next)
    }

    suspend fun unblockTool(toolName: String): Result<AiPermissionsSnapshot> {
        val norm = toolName.trim().lowercase()
        val cur = _state.value.policy
        if (norm !in cur.blockedTools) return Result.success(_state.value)
        val next = cur.copy(blockedTools = cur.blockedTools.filterNot { it == norm })
        _state.value = _state.value.copy(policy = next)
        return update(next)
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
            val blockedJson = JSONArray()
            policy.blockedTools.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().forEach {
                blockedJson.put(it)
            }
            val payload = JSONObject()
                .put("allowRoot", policy.allowRoot)
                .put("allowAllFiles", policy.allowAllFiles)
                .put("allowShellExec", policy.allowShellExec)
                .put("allowFileWrite", policy.allowFileWrite)
                .put("allowNetwork", policy.allowNetwork)
                .put("blockedTools", blockedJson)
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
