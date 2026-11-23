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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.ui.window.DialogProperties
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
                            if (base.isNotBlank()) openCodeDefaultBase = base
                            if (full.isNotBlank()) dynamicServerPrompt = full
                        }
                    }
                } else {
                    val urlStr = "$bridgeBase/api/system-prompt?conversationId=${conversationId ?: "active"}&workspaceDir=$wsParam"
                    val request = okhttp3.Request.Builder().url(urlStr).get().build()
                    lytso.code.data.remote.AgyBridgeService.instance.client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val json = org.json.JSONObject(body)
                            val p = json.optString("systemPrompt")
                            if (p.isNotBlank()) dynamicServerPrompt = p
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
            dynamicServerPrompt?.takeIf { it.isNotBlank() } ?: openCodeDefaultBase
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
            """.trimIndent()
        }
    }

    val hasCustomActive = perChatTrimmed != null || globalTrimmed != null
    val activeSourceBadge = when {
        perChatTrimmed != null -> "This chat"
        globalTrimmed != null -> if (isOpenCode) "OpenCode Global" else "Global"
        else -> "Default"
    }

    // Exact text sent in systemInstruction: when custom/global is set, OpenCode sends ONLY that prompt (zero extra boilerplate).
    val shownActivePrompt = perChatTrimmed ?: globalTrimmed ?: defaultSystemPrompt
    val editableSeedPrompt = if (isOpenCode) {
        perChatTrimmed ?: globalTrimmed ?: openCodeDefaultBase
    } else {
        shownActivePrompt.trim()
    }

    fun approxTokens(text: String): Int = ((text.trim().length + 3) / 4).coerceAtLeast(0)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // ----- Header -----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(accentColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isOpenCode) "OpenCode · System Prompt" else "Antigravity · System Prompt",
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = activeSourceBadge,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (hasCustomActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (hasCustomActive) accentColor else MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                        Text(
                            text = when {
                                isOpenCode && hasCustomActive ->
                                    "Exclusive systemInstruction · no extra OpenCode prompt or # Your Model"
                                isOpenCode ->
                                    "OpenCode default · # Your Model removed · never duplicated in input"
                                hasCustomActive ->
                                    "Custom system prompt active · replaces default <identity>"
                                else ->
                                    "Using Antigravity built-in default (<identity>)"
                            },
                            fontSize = 11.5.sp,
                            color = if (hasCustomActive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selectedTab == 0) {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("LytsoCoder System Prompt", shownActivePrompt)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Active prompt copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy",
                                tint = accentColor,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ----- Segmented Tabs (Active View vs Edit) -----
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            Triple(0, "Active Prompt", Icons.Outlined.Visibility),
                            Triple(1, "Edit Override", Icons.Outlined.Edit)
                        ).forEach { (tabIdx, label, icon) ->
                            val selected = selectedTab == tabIdx
                            Surface(
                                onClick = { selectedTab = tabIdx },
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) accentColor else Color.Transparent,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (selectedTab == 0) {
                    // ----- Tab 0: Maximized readable viewer of exact active systemInstruction -----
                    val viewTokens = approxTokens(shownActivePrompt)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isOpenCode && hasCustomActive) {
                                "Sent alone in systemInstruction (exact)"
                            } else {
                                "Active systemInstruction"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${shownActivePrompt.length} chars · ~$viewTokens tokens",
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = shownActivePrompt,
                                    fontSize = 13.sp,
                                    lineHeight = 19.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                } else {
                    // ----- Tab 1: Maximized Editor with clear scope selector -----
                    if (onSaveGlobalPrompt != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                0 to "This chat only",
                                1 to if (isOpenCode) "OpenCode Global" else "Antigravity Global"
                            ).forEach { (scopeId, label) ->
                                val selected = editScope == scopeId
                                Surface(
                                    onClick = { editScope = scopeId },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) accentColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.5.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    val activeDraft = if (editScope == 1) globalDraft else chatDraft
                    val draftTokens = approxTokens(activeDraft)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                editScope == 1 && isOpenCode -> "1. Chat  ›  2. OpenCode Global  ›  3. Default"
                                editScope == 1 -> "1. Chat  ›  2. Antigravity Global  ›  3. Default"
                                isOpenCode -> "Replaces entire systemInstruction in real time"
                                else -> "Overrides only this chat (priority #1)"
                            },
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = accentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${activeDraft.length} chars · ~$draftTokens tok",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = activeDraft,
                        onValueChange = {
                            if (editScope == 1) globalDraft = it else chatDraft = it
                        },
                        placeholder = {
                            Text(
                                text = when {
                                    editScope == 1 && isOpenCode ->
                                        "Empty = OpenCode default prompt (without # Your Model).\nWrite here to set the exact systemInstruction for all OpenCode chats..."
                                    editScope == 1 ->
                                        "Empty = Antigravity default <identity>.\nWrite here to set Antigravity Global system prompt..."
                                    isOpenCode && globalTrimmed != null ->
                                        "Empty = inherits OpenCode Global.\nWrite here to set the exact systemInstruction ONLY for this chat..."
                                    isOpenCode ->
                                        "Write the exact systemInstruction for this OpenCode chat (replaces OpenCode's default completely in real time)..."
                                    globalTrimmed != null ->
                                        "Empty = inherits Global System Prompt.\nWrite here to override ONLY this chat..."
                                    else ->
                                        "Write the system prompt for this chat..."
                                },
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ----- Bottom Action Bar -----
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
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RestartAlt,
                                contentDescription = "Clear",
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                        }
                    } else if (selectedTab == 1 && isOpenCode) {
                        TextButton(
                            onClick = {
                                if (editScope == 1) globalDraft = openCodeDefaultBase else chatDraft = openCodeDefaultBase
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Load default", fontSize = 13.sp, color = accentColor, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text("Close", fontSize = 13.5.sp, maxLines = 1, softWrap = false)
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
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Edit", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                        } else {
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
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    when {
                                        editScope == 1 && globalDraft.isBlank() -> "Reset Global"
                                        editScope == 1 -> "Save Global"
                                        chatDraft.isBlank() -> if (globalTrimmed != null) "Use Global" else "Use Default"
                                        else -> "Save"
                                    },
                                    fontSize = 13.5.sp,
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
