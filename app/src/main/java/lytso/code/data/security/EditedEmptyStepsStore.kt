package lytso.code.data.security

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists assistant step indices explicitly edited to blank/empty text so they stay visible
 * as editable empty message slots across reloads and app restarts instead of vanishing.
 */
object EditedEmptyStepsStore {
    private const val PREFS_NAME = "lytso_edited_empty_steps"
    private const val KEY_SET = "empty_step_keys"

    private val emptyKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    val saved = appContext
                        ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.getStringSet(KEY_SET, emptySet())
                        .orEmpty()
                    emptyKeys.addAll(saved)
                    loaded = true
                }
            }
        }
    }

    private fun key(conversationId: String, stepIndex: Int): String = "$conversationId#step:$stepIndex"

    fun markEmpty(conversationId: String, stepIndex: Int) {
        if (conversationId.isBlank() || stepIndex < 0) return
        if (emptyKeys.add(key(conversationId, stepIndex))) {
            persist()
        }
    }

    fun clearStep(conversationId: String, stepIndex: Int) {
        if (conversationId.isBlank() || stepIndex < 0) return
        if (emptyKeys.remove(key(conversationId, stepIndex))) {
            persist()
        }
    }

    fun isMarkedEmpty(conversationId: String, stepIndex: Int): Boolean {
        if (conversationId.isBlank() || stepIndex < 0) return false
        return emptyKeys.contains(key(conversationId, stepIndex))
    }

    fun clearConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        val prefix = "$conversationId#"
        if (emptyKeys.removeIf { it.startsWith(prefix) }) {
            persist()
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        val snapshot = emptyKeys.toSet()
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SET, snapshot)
            .apply()
    }
}
