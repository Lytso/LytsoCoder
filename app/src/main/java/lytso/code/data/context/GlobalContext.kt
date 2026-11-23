package lytso.code.data.context

import android.content.Context
import android.content.SharedPreferences
import lytso.code.BuildConfig
import lytso.code.data.preferences.AuthPreferences
import lytso.code.data.remote.PromptExpansion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * What every chat is told about the app, for every agent alike, hidden in front of the user's message (the chat never
 * shows it). Two parts, both in Settings → Agents → Global context:
 *
 * - The built-in context, written by the app: where the agent runs (Termux on Android, local mode only) and how to
 *   show images, videos and files in this chat. Read-only, on by default, can be turned off.
 * - The user's own entries. Each goes either into the first message of a chat or into every message, and is sent to
 *   Jarvis only if the user says so (Jarvis has strict rules that a note meant for normal chats could break).
 *
 * The first-message part goes once per chat: the app remembers which version each chat got and sends it again only
 * when it changed (app update, edited entry), after Claude compacted the chat, or when the first message was edited.
 */
object GlobalContext {

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val text: String,
        val enabled: Boolean = true,
        /** Sent with every message instead of only the first one of a chat. */
        val everyMessage: Boolean = false,
        /** Also told to Jarvis (its chats and the chats it starts for tasks). */
        val forJarvis: Boolean = false
    )

    private const val MAX_REMEMBERED_CHATS = 600

    private var sp: SharedPreferences? = null
    private var filesDir: String = "/data/data/${BuildConfig.APPLICATION_ID}/files"

    private val _builtInEnabled = MutableStateFlow(true)
    val builtInEnabled: StateFlow<Boolean> = _builtInEnabled.asStateFlow()

    private val _builtInCustomText = MutableStateFlow<String?>(null)
    val builtInCustomText: StateFlow<String?> = _builtInCustomText.asStateFlow()

    private val _globalSystemPrompt = MutableStateFlow<String?>(null)
    /** Default system prompt applied to every non-OpenCode chat (Antigravity, etc.) that has no per-chat override. */
    val globalSystemPrompt: StateFlow<String?> = _globalSystemPrompt.asStateFlow()

    private val _openCodeGlobalSystemPrompt = MutableStateFlow<String?>(null)
    /** Default system prompt applied to every OpenCode chat that has no per-chat override. */
    val openCodeGlobalSystemPrompt: StateFlow<String?> = _openCodeGlobalSystemPrompt.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** chat id → fingerprint of the first-message context it got (oldest first, trimmed). */
    private val sent = LinkedHashMap<String, String>()

    /** chat id → custom per-chat system prompt override (oldest first, capped). */
    private val chatPrompts = LinkedHashMap<String, String>()

    private var runsOnPhoneTestOverride: Boolean? = null
    private var rootActiveTestOverride: Boolean? = null
    private val _customExplorerRoots = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val customExplorerRoots: StateFlow<List<Pair<String, String>>> = _customExplorerRoots.asStateFlow()

    fun setCustomExplorerRoots(roots: List<Pair<String, String>>) {
        _customExplorerRoots.value = roots
    }

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("global_context", Context.MODE_PRIVATE)
        filesDir = context.applicationContext.filesDir.absolutePath
        lytso.code.data.render.RenderBundle.init(context)
        _builtInEnabled.value = p.getBoolean("builtin_enabled", true)
        _builtInCustomText.value = p.getString("builtin_custom_text", null)?.takeIf { it.isNotBlank() }
        _globalSystemPrompt.value = p.getString("global_system_prompt", null)?.trim()?.takeIf { it.isNotBlank() }
        _openCodeGlobalSystemPrompt.value = p.getString("opencode_global_system_prompt", null)?.trim()?.takeIf { it.isNotBlank() }
        _entries.value = runCatching {
            val arr = JSONArray(p.getString("entries", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = o.optString("title"),
                    text = o.optString("text"),
                    enabled = o.optBoolean("enabled", true),
                    everyMessage = o.optBoolean("everyMessage", false),
                    forJarvis = o.optBoolean("forJarvis", false)
                )
            }
        }.getOrDefault(emptyList())
        runCatching {
            val o = JSONObject(p.getString("sent", "{}") ?: "{}")
            synchronized(sent) { o.keys().forEach { k -> sent[k] = o.getString(k) } }
        }
        runCatching {
            val o = JSONObject(p.getString("chat_system_prompts", "{}") ?: "{}")
            synchronized(chatPrompts) {
                o.keys().forEach { k ->
                    val v = o.optString(k)
                    if (v.isNotBlank()) chatPrompts[k] = v
                }
            }
        }
        sp = p
    }

    internal fun resetForTest(
        filesDirOverride: String = "/data/user/0/lytso.cdev/files",
        runsOnPhoneOverride: Boolean? = true,
        rootActiveOverride: Boolean? = null
    ) {
        sp = null
        filesDir = filesDirOverride
        runsOnPhoneTestOverride = runsOnPhoneOverride
        rootActiveTestOverride = rootActiveOverride
        _customExplorerRoots.value = emptyList()
        _builtInEnabled.value = true
        _builtInCustomText.value = null
        _globalSystemPrompt.value = null
        _openCodeGlobalSystemPrompt.value = null
        _entries.value = emptyList()
        synchronized(sent) { sent.clear() }
        synchronized(chatPrompts) { chatPrompts.clear() }
    }

    // ------------------------------------------------------------------ settings

    fun setBuiltInEnabled(on: Boolean) {
        _builtInEnabled.value = on
        sp?.edit()?.putBoolean("builtin_enabled", on)?.apply()
    }

    fun isBuiltInCustomized(): Boolean = !_builtInCustomText.value.isNullOrBlank()

    fun setBuiltInText(text: String?) {
        val cleaned = text?.trim()?.takeIf { it.isNotBlank() && it != defaultBuiltInText() }
        _builtInCustomText.value = cleaned
        sp?.edit()?.apply {
            if (cleaned == null) remove("builtin_custom_text") else putString("builtin_custom_text", cleaned)
        }?.apply()
    }

    fun resetBuiltInText() = setBuiltInText(null)

    fun setGlobalSystemPrompt(prompt: String?) {
        val cleaned = prompt?.trim()?.takeIf { it.isNotBlank() }
        _globalSystemPrompt.value = cleaned
        sp?.edit()?.apply {
            if (cleaned == null) remove("global_system_prompt") else putString("global_system_prompt", cleaned)
        }?.apply()
    }

    fun setOpenCodeGlobalSystemPrompt(prompt: String?) {
        val cleaned = prompt?.trim()?.takeIf { it.isNotBlank() }
        _openCodeGlobalSystemPrompt.value = cleaned
        sp?.edit()?.apply {
            if (cleaned == null) remove("opencode_global_system_prompt") else putString("opencode_global_system_prompt", cleaned)
        }?.apply()
    }

    fun globalSystemPromptFor(agent: lytso.code.domain.model.AgentKind?): StateFlow<String?> =
        if (agent == lytso.code.domain.model.AgentKind.OPENCODE) openCodeGlobalSystemPrompt else globalSystemPrompt

    /** Precedence: per-chat override -> [convOverride] -> agent's global system prompt -> null (agent default). */
    fun effectiveSystemPrompt(
        chatId: String?,
        convOverride: String? = null,
        agent: lytso.code.domain.model.AgentKind? = null
    ): String? {
        chatSystemPrompt(chatId)?.takeIf { it.isNotBlank() }?.let { return it }
        convOverride?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val global = if (agent == lytso.code.domain.model.AgentKind.OPENCODE) {
            _openCodeGlobalSystemPrompt.value
        } else {
            _globalSystemPrompt.value
        }
        return global?.takeIf { it.isNotBlank() }
    }

    fun chatSystemPrompt(chatId: String?): String? {
        if (chatId.isNullOrBlank()) return null
        return synchronized(chatPrompts) { chatPrompts[chatId] }
    }

    fun setChatSystemPrompt(chatId: String?, prompt: String?) {
        if (chatId.isNullOrBlank()) return
        val cleaned = prompt?.trim()?.takeIf { it.isNotBlank() }
        synchronized(chatPrompts) {
            chatPrompts.remove(chatId)
            if (cleaned != null) {
                chatPrompts[chatId] = cleaned
                while (chatPrompts.size > MAX_REMEMBERED_CHATS) chatPrompts.remove(chatPrompts.keys.first())
            }
        }
        persistChatPrompts()
    }

    fun copyChatSystemPrompt(fromChatId: String?, toChatId: String?) {
        val existing = chatSystemPrompt(fromChatId) ?: return
        setChatSystemPrompt(toChatId, existing)
    }

    fun deleteChatSystemPrompt(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        val removed = synchronized(chatPrompts) { chatPrompts.remove(chatId) != null }
        if (removed) persistChatPrompts()
    }

    private fun persistChatPrompts() {
        val p = sp ?: return
        val o = JSONObject()
        synchronized(chatPrompts) { chatPrompts.forEach { (k, v) -> o.put(k, v) } }
        p.edit().putString("chat_system_prompts", o.toString()).apply()
    }

    fun upsert(entry: Entry) {
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        if (i >= 0) list[i] = entry else list += entry
        saveEntries(list)
    }

    fun delete(id: String) = saveEntries(_entries.value.filter { it.id != id })

    private fun saveEntries(list: List<Entry>) {
        _entries.value = list
        val p = sp ?: return
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("title", e.title).put("text", e.text).put("enabled", e.enabled)
                    .put("everyMessage", e.everyMessage).put("forJarvis", e.forJarvis)
            )
        }
        p.edit().putString("entries", arr.toString()).apply()
    }

    // ------------------------------------------------------------------ the built-in text

    /** The bridge on this phone: the agent runs in the app's Linux system (Termux packages), not on a server. */
    val runsOnPhone: Boolean
        get() = runsOnPhoneTestOverride ?: !AuthPreferences.isRemoteEndpoint()

    /** Effective built-in context (customized if edited in Settings, otherwise [defaultBuiltInText]). */
    fun builtInText(h: String = "##"): String {
        val custom = _builtInCustomText.value?.takeIf { it.isNotBlank() } ?: return defaultBuiltInText(h)
        if (h == "##") return custom
        return custom.lines().joinToString("\n") { line ->
            if (line.startsWith("## ")) "$h " + line.removePrefix("## ") else line
        }
    }

    /** Default built-in context for the current setup. [h]: the heading level of its sections. */
    fun defaultBuiltInText(h: String = "##"): String = buildString {
        val permSnap = lytso.code.data.security.AiPermissionsClient.state.value
        val policy = permSnap.policy
        val rootActive = rootActiveTestOverride ?: (policy.allowRoot && permSnap.rootGranted)
        val extraRoots = _customExplorerRoots.value

        if (runsOnPhone) {
            append("$h Where you run\n")
            append("- Android, in the LytsoCoder app's Linux system (Termux packages): /tmp, /usr, /etc are not usable.\n")
            append("- Temporary files: use ~/lytso_home/tmp or \$TMPDIR ($filesDir/usr/tmp), never /tmp. Install packages with `pkg install <name>`.\n")
            append("- Home is \$HOME ($filesDir/home); phone storage is /sdcard.\n")
            if (rootActive) {
                append("- Root access: ENABLED (superuser active). You have verified root privileges via `su` and are authorized to execute privileged commands and system modifications when requested (always snapshot critical changes to ~/lytso_home/backups/ first).\n")
            } else {
                append("- Root access: DISABLED. You operate strictly without superuser privileges (standard Termux user only; `su`/`sudo` are blocked). Do not attempt privileged commands.\n")
            }
            val caps = listOf(
                "Shell=${if (policy.allowShellExec) "ENABLED" else "DISABLED"}",
                "FileWrite=${if (policy.allowFileWrite) "ENABLED" else "DISABLED"}",
                "Network=${if (policy.allowNetwork) "ENABLED" else "DISABLED"}",
                "AllFilesStorage=${if (policy.allowAllFiles) "ENABLED" else "STANDARD"}"
            ).joinToString(", ")
            append("- Active AI capabilities: $caps.\n\n")
        }
        append("$h Workspace layout (~/lytso_home/ — STRICT)\n")
        append("Never drop loose files in \$HOME. Organize all work strictly inside ~/lytso_home/:\n")
        append("- `~/lytso_home/projects/` — Active projects, GitHub forks and repos (each in its own subfolder).\n")
        append("- `~/lytso_home/keys/` — Protected secrets: keystores (.jks), .env files, SSH keys, API tokens.\n")
        append("- `~/lytso_home/scripts/` — Reusable automation, deploy and device scripts (Shell, Python, Node).\n")
        append("- `~/lytso_home/logs/` — Execution logs, build outputs, debug traces and error reports.\n")
        append("- `~/lytso_home/dumps/` — Extractions, memory/DB dumps, decompiled APKs and raw analyses.\n")
        append("- `~/lytso_home/tmp/` — Temporary files, disposable downloads and quick scratch tests.\n")
        append("- `~/lytso_home/builds/` — Final compiled artifacts (signed APKs, binaries, release packages).\n")
        append("- `~/lytso_home/tools/` — Custom libraries, local binaries, SDKs and non-standard utilities.\n")
        append("- `~/lytso_home/cloud/` — Cloud VM configs, remote SSH profiles and external build scripts.\n")
        append("- `~/lytso_home/backups/` — Automatic snapshots created before critical or root modifications.\n")
        if (extraRoots.isNotEmpty()) {
            append("- Additional user-authorized explorer directories (explicitly granted): ")
            append(extraRoots.joinToString(", ") { (name, path) -> "`$path` ($name)" })
            append(".\n")
        }
        append("\n")
        append("$h Showing things in this chat (paths absolute, no spaces, each on its own line)\n")
        append("- Image: `![caption](/abs/path/pic.png)` · Video: `![caption](/abs/path/clip.mp4)` (plays in the chat)\n")
        append("- File: `[name](/abs/path/file.ext)` (the user taps to open)\n")
        append("Show images, videos and documents you create this way instead of only naming the path.\n\n")
        append("$h Asking the user\n")
        append("Request vague or ambiguous? Don't guess: ask with your question tool (e.g. AskUserQuestion), all at once, with a few options.\n\n")
        append("$h Node projects\n")
        append("New projects: use pnpm (faster, less disk) unless the user says otherwise. Existing npm projects (package-lock.json) stay on npm.")
    }.trimEnd()

    // ------------------------------------------------------------------ what goes where

    private fun active(): List<Entry> = _entries.value.filter { it.enabled && it.text.isNotBlank() }

    private fun entriesBlock(list: List<Entry>): String = list.joinToString("\n\n") { e ->
        (if (e.title.isNotBlank()) "### ${e.title.trim()}\n" else "") + e.text.trim()
    }

    /** Rewrites any legacy package path (/data/user/0/ai.antigem/..., /data/data/ai.antigem/...) to this app's real data dir. */
    fun sanitizeLegacyAppPath(raw: String): String {
        if (raw.isBlank()) return raw
        val currentRoot = filesDir.trimEnd('/').removeSuffix("/files")
        if (currentRoot.isBlank()) return raw
        val legacyPrefixes = listOf(
            "/data/user/0/ai.antigem",
            "/data/data/ai.antigem",
            "/data/user/0/ai.antidev",
            "/data/data/ai.antidev"
        )
        var out = raw
        for (prefix in legacyPrefixes) {
            if (out.contains(prefix)) {
                out = out.replace(prefix, currentRoot)
            }
        }
        return out
    }

    /** Authoritative live permissions & root block always appended to firstMessageBody() so toggling Root updates active chats immediately. */
    fun livePermissionsSection(h: String = "##"): String? {
        if (!runsOnPhone) return null
        val permSnap = lytso.code.data.security.AiPermissionsClient.state.value
        val policy = permSnap.policy
        val rootActive = rootActiveTestOverride ?: (policy.allowRoot && permSnap.rootGranted)
        return buildString {
            append("$h Live AI Permissions & Root Status (authoritative — supersedes any earlier status in this chat)\n")
            if (rootActive) {
                val suLoc = permSnap.suPath.ifBlank { "su" }
                append("- Root access (su/sudo): ENABLED (verified superuser active via `$suLoc`). You ARE authorized to execute `su -c ...` and privileged commands immediately in this session.\n")
            } else {
                append("- Root access (su/sudo): DISABLED. Superuser elevation (`su`, `tsu`, `sudo`) is actively blocked by LytsoCoder policy. Do not attempt privileged commands.\n")
            }
            val caps = listOf(
                "Shell=${if (policy.allowShellExec) "ENABLED" else "DISABLED"}",
                "FileWrite=${if (policy.allowFileWrite) "ENABLED" else "DISABLED"}",
                "Network=${if (policy.allowNetwork) "ENABLED" else "DISABLED"}",
                "AllFilesStorage=${if (policy.allowAllFiles) "ENABLED" else "STANDARD"}"
            ).joinToString(", ")
            append("- Active AI capabilities: $caps.")
        }
    }

    /** The first-message context of a normal chat, or null when there is nothing to send. */
    fun firstMessageBody(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) {
            parts += sanitizeLegacyAppPath(builtInText())
            livePermissionsSection()?.let { parts += it }
        }
        // Settings → STEM & diagrams: the extra blocks this chat renders (normal chats only, not Jarvis)
        lytso.code.data.render.RenderBundle.promptSection(canReadFiles = runsOnPhone)?.let { parts += it }
        val own = active().filter { !it.everyMessage }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** What every message of a normal chat carries (including per-chat or global custom system prompt when [includeChatSystemPrompt] is true), or null. */
    fun everyMessageBody(chatId: String? = null, includeChatSystemPrompt: Boolean = true): String? {
        val blocks = mutableListOf<String>()
        val chatPrompt = if (includeChatSystemPrompt) effectiveSystemPrompt(chatId) else null
        if (!chatPrompt.isNullOrBlank()) {
            blocks += "[Chat system prompt, set by the user for this chat. It REPLACES your default persona, style and system instructions wherever they differ (keep only your tool and safety rules). Follow it strictly in every reply.]\n$chatPrompt"
        }
        val own = active().filter { it.everyMessage }
        if (own.isNotEmpty()) {
            blocks += "[The user's standing instructions, sent with every message]\n" + entriesBlock(own)
        }
        return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Changes whenever the first-message context changes. */
    fun fingerprint(): String = Integer.toHexString((firstMessageBody() ?: "").hashCode())

    /**
     * A normal chat message with the hidden context in front. [chatId]: the chat on screen (blank for a new one);
     * [hasEarlierMessages]: the chat already has messages. Returns the prompt and, when the first-message context was
     * included, its fingerprint (record it with [markSent] once the chat id is known).
     */
    fun wrap(
        chatId: String?,
        hasEarlierMessages: Boolean,
        text: String,
        includeChatSystemPrompt: Boolean = true
    ): Pair<String, String?> {
        val first = firstMessageBody()
        val fp = fingerprint()
        val known = chatId?.takeIf { it.isNotBlank() }?.let { id -> synchronized(sent) { sent[id] } }
        val sendFirst = first != null && (!hasEarlierMessages || known != fp)
        val every = everyMessageBody(chatId, includeChatSystemPrompt)
        if (!sendFirst && every == null) return text to null
        val note = buildString {
            if (sendFirst) {
                append(
                    when {
                        !hasEarlierMessages -> "[LytsoCoder app context: from the app the user chats through, not written by the user. Follow it for this whole chat; it is not repeated.]"
                        known != null -> "[LytsoCoder app context, updated: it replaces the earlier copy in this chat. Follow it from now on.]"
                        else -> "[LytsoCoder app context: from the app the user chats through. Follow it from now on in this chat.]"
                    }
                ).append("\n\n").append(first)
            }
            if (every != null) {
                if (sendFirst) append("\n\n")
                append(every)
            }
        }
        if (sendFirst && !chatId.isNullOrBlank()) markSent(chatId, fp)
        return (PromptExpansion.hidden(note) + text) to (if (sendFirst) fp else null)
    }

    fun markSent(chatId: String, fp: String) {
        if (chatId.isBlank()) return
        synchronized(sent) {
            sent.remove(chatId)
            sent[chatId] = fp
            while (sent.size > MAX_REMEMBERED_CHATS) sent.remove(sent.keys.first())
        }
        persistSent()
    }

    /** The chat lost its first-message context (compacted, first message edited): send it again with the next message. */
    fun forget(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        val removed = synchronized(sent) { sent.remove(chatId) } != null
        if (removed) persistSent()
    }

    private fun persistSent() {
        val p = sp ?: return
        val o = JSONObject()
        synchronized(sent) { sent.forEach { (k, v) -> o.put(k, v) } }
        p.edit().putString("sent", o.toString()).apply()
    }

    // ------------------------------------------------------------------ Jarvis

    private const val JARVIS_PRIORITY =
        "These are the user's notes for normal chats, shared with Jarvis. Follow them where they fit; if one conflicts with the Jarvis guide above, the Jarvis guide wins."

    /** A section of the Jarvis guide (first message of a Jarvis chat): the built-in context and the user's Jarvis-enabled first-message entries. */
    fun jarvisGuideSection(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += "## LytsoCoder app context (for you and every chat you start)\n" + builtInText("###")
        val own = active().filter { it.forJarvis && !it.everyMessage }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n$JARVIS_PRIORITY\n\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** The user's Jarvis-enabled every-message entries, for the note in front of every Jarvis message. */
    fun jarvisEveryMessage(): String? {
        val own = active().filter { it.forJarvis && it.everyMessage }
        if (own.isEmpty()) return null
        return "user's standing instructions (the Jarvis rules win on conflict): " + own.joinToString(" | ") { e ->
            (if (e.title.isNotBlank()) "${e.title.trim()}: " else "") + e.text.trim().replace("\n", " ")
        }
    }

    /** Changes whenever what Jarvis' guide carries from here changes (the guide is then sent again). */
    fun jarvisFingerprint(): String = Integer.toHexString(((jarvisGuideSection() ?: "") + (jarvisEveryMessage() ?: "")).hashCode())

    /** The context for a chat Jarvis starts for a task: the built-in context and the user's Jarvis-enabled entries. */
    fun taskBody(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += "[LytsoCoder app context: from the app, not the user. Follow it for this whole chat.]\n\n" + builtInText()
        val own = active().filter { it.forJarvis }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** A follow-up Jarvis sends to a task chat: the user's Jarvis-enabled every-message entries in front. */
    fun taskFollowUp(text: String): String {
        val own = active().filter { it.forJarvis && it.everyMessage }
        if (own.isEmpty()) return text
        return PromptExpansion.hidden("[The user's standing instructions, sent with every message]\n" + entriesBlock(own)) + text
    }
}
