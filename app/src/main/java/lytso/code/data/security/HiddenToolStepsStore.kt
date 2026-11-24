package lytso.code.data.security

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists user-deleted tool cards and `System:` notices per conversation so tapping the trash icon
 * hides them immediately and keeps them gone across live stream frames and app restarts.
 */
object HiddenToolStepsStore {
    private const val PREFS_NAME = "lytso_hidden_tool_steps"
    private const val KEY_SET = "hidden_keys"

    private val hiddenKeys = ConcurrentHashMap.newKeySet<String>()
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
                    hiddenKeys.addAll(saved)
                    loaded = true
                }
            }
        }
    }

    private fun stepKey(conversationId: String, stepIndex: Int): String = "$conversationId#step:$stepIndex"
    private fun idKey(conversationId: String, toolId: String): String = "$conversationId#id:$toolId"

    fun hide(conversationId: String, stepIndex: Int?, toolId: String? = null) {
        var changed = false
        if (stepIndex != null) {
            if (hiddenKeys.add(stepKey(conversationId, stepIndex))) changed = true
        }
        if (!toolId.isNullOrBlank()) {
            if (hiddenKeys.add(idKey(conversationId, toolId))) changed = true
        }
        if (changed) persist()
    }

    fun isHidden(conversationId: String, stepIndex: Int?, toolId: String? = null): Boolean {
        if (stepIndex != null && hiddenKeys.contains(stepKey(conversationId, stepIndex))) return true
        if (!toolId.isNullOrBlank() && hiddenKeys.contains(idKey(conversationId, toolId))) return true
        return false
    }

    fun clearConversation(conversationId: String) {
        val prefix = "$conversationId#"
        val removed = hiddenKeys.removeIf { it.startsWith(prefix) }
        if (removed) persist()
    }

    private fun persist() {
        val ctx = appContext ?: return
        val snapshot = hiddenKeys.toSet()
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SET, snapshot)
            .apply()
    }
}
