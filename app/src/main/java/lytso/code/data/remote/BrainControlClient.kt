package lytso.code.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lytso.code.data.preferences.AuthPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class BrainMutationResult(
    val success: Boolean = false,
    val modified: Int = 0,
    val restarted: Boolean = false,
    val csrfToken: String? = null,
    val error: String? = null
)

@Serializable
data class ArtifactDeleteResult(
    val success: Boolean = false,
    val deletedFiles: List<String> = emptyList(),
    val redactedSteps: Int = 0,
    val restarted: Boolean = false,
    val csrfToken: String? = null,
    val error: String? = null
)

@Serializable
private data class EditStepRequest(
    val stepIndex: Int,
    val role: String,
    val text: String,
    val preserveHidden: Boolean = true
)

@Serializable
private data class DeleteThinkingRequest(
    val stepIndices: List<Int>
)

@Serializable
private data class DeleteArtifactRequest(
    val uri: String,
    val name: String
)

/**
 * Bridge client for direct conversation brain/SQLite mutations (non-destructive edits, thinking-step pruning,
 * and obsolete artifact deletion with context redaction).
 */
object BrainControlClient {
    private val jsonType = "application/json".toMediaType()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Pausing and restarting the managed agy --hub can take a few seconds on Android.
    private val slowClient by lazy {
        AgyBridgeService.instance.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun baseUrl(): String = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')

    private suspend inline fun <reified T> post(path: String, payloadJson: String): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${baseUrl()}$path")
                .post(payloadJson.toRequestBody(jsonType))
                .build()
            slowClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful && !raw.trimStart().startsWith("{")) {
                    error("HTTP ${resp.code}: ${raw.take(120)}")
                }
                json.decodeFromString<T>(raw)
            }
        }
    }

    suspend fun editAgyStep(
        convId: String,
        stepIndex: Int,
        role: String,
        text: String,
        preserveHidden: Boolean = true
    ): Result<BrainMutationResult> {
        val body = json.encodeToString(
            EditStepRequest(
                stepIndex = stepIndex,
                role = role,
                text = text,
                preserveHidden = preserveHidden
            )
        )
        return post<BrainMutationResult>("/api/conversations/$convId/steps/edit", body).mapCatching {
            if (!it.success) error(it.error ?: "Failed to update step")
            it
        }
    }

    suspend fun deleteAgyThinking(
        convId: String,
        stepIndices: List<Int>
    ): Result<BrainMutationResult> {
        val body = json.encodeToString(DeleteThinkingRequest(stepIndices = stepIndices))
        return post<BrainMutationResult>("/api/conversations/$convId/thinking/delete", body).mapCatching {
            if (!it.success) error(it.error ?: "Failed to delete thinking step")
            it
        }
    }

    suspend fun deleteAgyArtifact(
        convId: String,
        uri: String,
        name: String
    ): Result<ArtifactDeleteResult> {
        val body = json.encodeToString(DeleteArtifactRequest(uri = uri, name = name))
        return post<ArtifactDeleteResult>("/api/conversations/$convId/artifacts/delete", body).mapCatching {
            if (!it.success) error(it.error ?: "Failed to delete artifact")
            it
        }
    }
}
