package lytso.code.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import lytso.code.data.preferences.AuthPreferences
import lytso.code.domain.model.AgentKind
import lytso.code.theme.ClaudeTerracotta

/** Default initial harness prompt used by OpenCode (systemInstruction.parts[0]). */
const val DEFAULT_OPENCODE_SYSTEM_PROMPT = """You are an AI agent running in OpenCode, a coding agent harness. Help the user accomplish their goals using the tools you have available.

# Harness
- Responses are rendered as GitHub-flavored Markdown.
- `<system-update>` blocks are harness instructions, not user-authored content. Read and follow them.
- Background work is reported as harness input: user-role messages containing `<background-result>` XML. They are not the user speaking, and never something you write yourself: do not reproduce the XML in your own output. Distinguish them by the `<background-result>` opening tag.
- Prefer parallelizing independent tool calls.
- Prefer dedicated tools over shell commands; fall back to the shell when a tool cannot do what you need.
- Do not chain shell commands with separators like `echo "====";` or `printf '---'`; the output becomes noisy in a way that makes the user's side of the conversation worse.
- Use the write tool to create files or completely replace their content. Prefer using the edit tool for targeted changes.
- Use the edit tool for targeted changes to existing text files. It replaces the exact text in `oldString` with `newString`, and the values must differ. By default, `oldString` must occur exactly once. If it occurs multiple times, include more surrounding context to make it unique or set `replaceAll` to true to replace every occurrence.

# Communication
- Use clear file paths when referring to files.
- Keep responses clear and concise, and avoid unnecessary technical jargon.

# Working in codebases
- Keep changes consistent with the structure, naming, style, and patterns of the surrounding code.
- Treat unfamiliar files or changes as potential user work and investigate before deleting or overwriting them."""

@Composable
fun CustomSystemPromptDialog(
    initialPrompt: String?,
    globalSystemPrompt: String? = null,
    activeSystemPrompt: String? = null,
    conversationId: String? = null,
    workspaceDir: String? = null,
    agentKind: AgentKind = AgentKind.AGY,
    openCodeAgent: String? = null,
    onSavePrompt: (String?) -> Unit,
    onSaveGlobalPrompt: ((String?) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isOpenCode = agentKind == AgentKind.OPENCODE
    val accentColor = if (isOpenCode) OpenCodeAccent else ClaudeTerracotta
    val perChatTrimmed = initialPrompt?.trim()?.takeIf { it.isNotBlank() }
    val globalTrimmed = globalSystemPrompt?.trim()?.takeIf { it.isNotBlank() }

    var selectedTab by remember { mutableStateOf(if (perChatTrimmed != null) 1 else 0) }
    // 0 = This chat, 1 = Global (for this agent)
    var editScope by remember { mutableStateOf(0) }
    var chatDraft by remember { mutableStateOf(perChatTrimmed ?: "") }
    var globalDraft by remember { mutableStateOf(globalTrimmed ?: "") }

    var dynamicServerPrompt by remember { mutableStateOf<String?>(activeSystemPrompt) }
    var openCodeDefaultBase by remember { mutableStateOf(DEFAULT_OPENCODE_SYSTEM_PROMPT) }
    var openCodeExtraPreview by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(conversationId, workspaceDir, agentKind, openCodeAgent) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val cleanWs = workspaceDir?.removePrefix("file://").orEmpty()
                val wsParam = java.net.URLEncoder.encode(cleanWs, "UTF-8")
                val bridgeBase = AuthPreferences.currentBridgeHttpUrl.removeSuffix("/")
                if (isOpenCode) {
                    val sidParam = java.net.URLEncoder.encode(conversationId ?: "", "UTF-8")
                    val agentParam = java.net.URLEncoder.encode(openCodeAgent ?: "build", "UTF-8")
                    val urlStr = "$bridgeBase/api/opencode/system-prompt?sessionId=$sidParam&workspaceDir=$wsParam&agent=$agentParam"
                    val request = okhttp3.Request.Builder().url(urlStr).get().build()
                    lytso.code.data.remote.AgyBridgeService.instance.client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val json = org.json.JSONObject(body)
                            val base = json.optString("defaultBasePrompt").trim()
                            val full = json.optString("defaultFullPrompt").trim()
                            if (base.isNotBlank()) {
                                openCodeDefaultBase = base
                            }
                            if (full.isNotBlank()) {
                                dynamicServerPrompt = full
                                val separator = "\n\n---\n\n"
                                if (base.isNotBlank() && full.startsWith(base) && full.length > base.length) {
                                    openCodeExtraPreview = full.removePrefix(base).removePrefix(separator).trim().takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    }
                } else {
                    val urlStr = "$bridgeBase/api/system-prompt?conversationId=${conversationId ?: "active"}&workspaceDir=$wsParam"
                    // the bridge client sends the security header and has timeouts that fit a remote server
                    val request = okhttp3.Request.Builder().url(urlStr).get().build()
                    lytso.code.data.remote.AgyBridgeService.instance.client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val json = org.json.JSONObject(body)
                            val p = json.optString("systemPrompt")
                            if (p.isNotBlank()) {
                                dynamicServerPrompt = p
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val cleanWorkspacePath = workspaceDir?.removePrefix("file://")?.takeIf { it.isNotBlank() }
        ?: "/data/data/${lytso.code.BuildConfig.APPLICATION_ID}/files/home/projects"

    val defaultSystemPrompt = remember(dynamicServerPrompt, openCodeDefaultBase, conversationId, cleanWorkspacePath, isOpenCode) {
        if (isOpenCode) {
            dynamicServerPrompt?.takeIf { it.isNotBlank() } ?: buildString {
                append(openCodeDefaultBase)
                append("\n\n---\n\n")
                append("Here is some useful information about the environment you are running in:\n")
                append("<env>\n")
                append("  Working directory: $cleanWorkspacePath\n")
                append("  Workspace root folder: $cleanWorkspacePath\n")
                append("  Platform: linux\n")
                append("</env>\n\n")
                append("When you create a worktree outside the current working directory and intend to use it as your primary working directory, consider using `execute` to call `tools.opencode.session.move` and make the worktree the session's working directory.")
            }
        } else {
            dynamicServerPrompt?.takeIf { it.isNotBlank() } ?: """
<identity>
You are LytsoCoder, a powerful agentic AI coding assistant working on Advanced Agentic Coding.
You are pair programming with a USER to solve their coding task. The task may require creating a new codebase, modifying or debugging an existing codebase, or simply answering a question.
The USER will send you requests, which you must always prioritize addressing. User requests are enclosed within <USER_REQUEST> tags.
</identity>

<user_information>
The USER's OS version is linux.
The user has active workspace:
$cleanWorkspacePath
App Data Directory: /data/data/${lytso.code.BuildConfig.APPLICATION_ID}/files/home/.gemini/antigravity-cli
Conversation ID: ${conversationId ?: "active"}
</user_information>

<skills>
You can use specialized 'skills' to help you with complex tasks.
Available skills:
- agy-customizations (~/.gemini/antigravity-cli/builtin/skills/agy-customizations/SKILL.md)
- antigravity-guide (~/.gemini/antigravity-cli/builtin/skills/antigravity_guide/SKILL.md)
- generative_ui (~/.gemini/antigravity-cli/builtin/skills/generative_ui/SKILL.md)
- migrate-workflows (~/.gemini/antigravity-cli/builtin/skills/migrate-workflows/SKILL.md)
- permissioned-github (~/.gemini/antigravity-cli/builtin/skills/permissioned-github/SKILL.md)
</skills>

<subagents>
Available subagents:
- self: Subagent that inherits the parent agent's full configuration.
- research: Research subagent with read-only tools for exploring codebase and docs.
</subagents>

<messaging>
You are connected to a messaging system where you may receive messages from: agents, background tasks, user-queued messages.
</messaging>

<artifacts>
Artifact Directory Path: ~/.gemini/antigravity-cli/brain/${conversationId ?: "active"}
</artifacts>

<slash_commands>
Available slash commands: /goal, /schedule, /browser, /plan, /grill-me, /teamwork-preview, /learn
</slash_commands>

<guidelines>
- Maintain documentation integrity. Preserve all existing comments and docstrings.
- Format responses in github-style markdown with clickable file:// links.
</guidelines>

<communication_style>
- Keep your responses concise.
- Format your responses in github-style markdown.
- Create clickable links for all files and code symbols.
</communication_style>

<tool_declarations>
1. view_file: View contents of a file (text or binary). Supports StartLine, EndLine, ContentOffset.
2. replace_file_content: Edit a single contiguous block of code in a file using TargetContent and ReplacementContent.
3. write_to_file: Create new file or overwrite existing with TargetFile, CodeContent, Description, ArtifactMetadata.
4. run_command: Execute shell commands directly on the user's Linux system with CommandLine, Cwd, WaitMsBeforeAsync.
5. grep_search: Perform ripgrep regex or literal pattern search across files and directories.
6. find_by_name: Search for files and subdirectories by name, glob pattern, or file extension.
7. list_dir: List recursive children, file sizes, and subdirectories of a target directory.
8. ask_question: Render interactive multiple-choice question modal to clarify requirements or preferences.
9. invoke_subagent: Spawn concurrent specialized subagents (research, self) with isolated prompts.
10. define_subagent: Dynamically declare and configure a new subagent type with custom system prompt and tools.
11. manage_subagents: List, inspect live states, or terminate running subagents.
12. schedule: Set one-shot timers or recurring cron tasks for background notification wakeups.
13. search_web: Query the web for documentation, libraries, and real-time internet search results.
14. read_url_content: Fetch and convert public webpage URLs into markdown format.
15. generate_image: Generate or edit UI mockups, visual assets, and images with AspectRatio and Prompt.
</tool_declarations>
            """.trimIndent()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.84f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                val activeSourceLabel = if (isOpenCode) {
                    when {
                        perChatTrimmed != null -> "Per-chat override (this OpenCode chat)"
                        globalTrimmed != null -> "OpenCode Global system prompt"
                        else -> "OpenCode default harness prompt"
                    }
                } else {
                    when {
                        perChatTrimmed != null -> "Per-chat override (this chat only)"
                        globalTrimmed != null -> "Global system prompt (Antigravity)"
                        else -> "Agent default (<identity>)"
                    }
                }

                val shownActivePrompt = if (isOpenCode) {
                    val effectiveBase = perChatTrimmed ?: globalTrimmed ?: openCodeDefaultBase
                    val extra = openCodeExtraPreview
                    when {
                        perChatTrimmed != null || globalTrimmed != null -> {
                            if (!extra.isNullOrBlank()) "$effectiveBase\n\n---\n\n$extra" else effectiveBase
                        }
                        else -> defaultSystemPrompt
                    }
                } else {
                    perChatTrimmed ?: globalTrimmed ?: defaultSystemPrompt
                }

                val editableSeedPrompt = if (isOpenCode) {
                    perChatTrimmed ?: globalTrimmed ?: openCodeDefaultBase
                } else {
                    shownActivePrompt.trim()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isOpenCode) "OpenCode System Prompt" else "Chat System Prompt",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Active: $activeSourceLabel",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (perChatTrimmed != null || globalTrimmed != null) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (selectedTab == 0) {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("LytsoCoder System Prompt", shownActivePrompt)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Active prompt copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy Prompt",
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (isOpenCode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = OpenCodeAccent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, OpenCodeAccent.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "OpenCode mode · Live systemInstruction.parts[0] · # Your Model hidden",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = OpenCodeAccent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = accentColor,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        selectedContentColor = accentColor,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(
                                    text = "Active Prompt",
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        selectedContentColor = accentColor,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text(
                                    text = "Custom Override",
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (selectedTab == 0) {
                    // Currently active prompt for this chat (Per-chat -> Agent Global -> Agent Default)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = shownActivePrompt,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                } else {
                    // Scope selector (This chat vs Agent Global) when global callback is provided
                    if (onSaveGlobalPrompt != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val scopes = listOf(
                                    0 to "This chat",
                                    1 to if (isOpenCode) "OpenCode Global" else "Global (AGY)"
                                )
                                scopes.forEach { (scopeId, label) ->
                                    val selected = editScope == scopeId
                                    Surface(
                                        onClick = { editScope = scopeId },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) accentColor else Color.Transparent,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 7.dp, horizontal = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 12.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val activeDraft = if (editScope == 1) globalDraft else chatDraft

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                text = if (isOpenCode) {
                                    "Priority: 1. This OpenCode Chat  →  2. OpenCode Global  →  3. Default"
                                } else {
                                    "Priority: 1. This Chat  →  2. Global (Settings)  →  3. Default"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    editScope == 1 && isOpenCode ->
                                        "Applies to all OpenCode chats without a per-chat override. Replaces OpenCode's initial prompt (parts[0]) in real time."
                                    editScope == 1 ->
                                        "Applies to all Antigravity chats without a per-chat override."
                                    isOpenCode && globalTrimmed != null ->
                                        "Applies only to this OpenCode chat in real time. Leave empty to inherit OpenCode Global."
                                    isOpenCode ->
                                        "Applies only to this OpenCode chat in real time (replaces parts[0], keeping Code Mode, skills & <env>)."
                                    globalTrimmed != null ->
                                        "Applies only to this chat. Leave empty to inherit your Global System Prompt."
                                    else ->
                                        "Applies only to this chat. Set a Global default in Settings → Prompts & Context."
                                },
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = activeDraft,
                        onValueChange = {
                            if (editScope == 1) globalDraft = it else chatDraft = it
                        },
                        placeholder = {
                            Text(
                                text = when {
                                    editScope == 1 && isOpenCode ->
                                        "Empty = OpenCode default harness prompt.\nWrite here to set OpenCode Global system prompt..."
                                    editScope == 1 ->
                                        "Empty = Antigravity default <identity>.\nWrite here to set Antigravity Global system prompt..."
                                    isOpenCode && globalTrimmed != null ->
                                        "Empty = inheriting OpenCode Global prompt.\nWrite here to override ONLY this OpenCode chat..."
                                    isOpenCode ->
                                        "Write your custom OpenCode initial prompt here (replaces parts[0] in real time)..."
                                    globalTrimmed != null ->
                                        "Empty = inheriting Global System Prompt.\nWrite here to override ONLY this chat..."
                                    else ->
                                        "e.g. You are an expert Android Kotlin engineer. Write clean Compose code with 0 bugs..."
                                },
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val currentEditDraft = if (editScope == 1) globalDraft else chatDraft

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTab == 1 && currentEditDraft.isNotBlank()) {
                        TextButton(
                            onClick = {
                                if (editScope == 1) globalDraft = "" else chatDraft = ""
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RestartAlt,
                                contentDescription = "Clear",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    } else if (selectedTab == 1 && isOpenCode) {
                        TextButton(
                            onClick = {
                                if (editScope == 1) globalDraft = openCodeDefaultBase else chatDraft = openCodeDefaultBase
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("Load default", fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("Close", fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                        if (selectedTab == 0) {
                            Button(
                                onClick = {
                                    if (chatDraft.isBlank()) {
                                        chatDraft = editableSeedPrompt
                                    }
                                    editScope = 0
                                    selectedTab = 1
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Override", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                        } else if (selectedTab == 1) {
                            Button(
                                onClick = {
                                    if (editScope == 1 && onSaveGlobalPrompt != null) {
                                        onSaveGlobalPrompt(globalDraft.ifBlank { null })
                                    } else {
                                        onSavePrompt(chatDraft.ifBlank { null })
                                    }
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    when {
                                        editScope == 1 && globalDraft.isBlank() -> "Reset Global"
                                        editScope == 1 -> "Save Global"
                                        chatDraft.isBlank() -> if (globalTrimmed != null) "Use Global" else "Use Default"
                                        else -> "Save"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
