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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import lytso.code.data.preferences.AuthPreferences
import lytso.code.theme.ClaudeTerracotta

@Composable
fun CustomSystemPromptDialog(
    initialPrompt: String?,
    globalSystemPrompt: String? = null,
    activeSystemPrompt: String? = null,
    conversationId: String? = null,
    workspaceDir: String? = null,
    onSavePrompt: (String?) -> Unit,
    onSaveGlobalPrompt: ((String?) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val perChatTrimmed = initialPrompt?.trim()?.takeIf { it.isNotBlank() }
    val globalTrimmed = globalSystemPrompt?.trim()?.takeIf { it.isNotBlank() }
    var selectedTab by remember { mutableStateOf(if (perChatTrimmed != null) 1 else 0) }
    var promptText by remember { mutableStateOf(perChatTrimmed ?: "") }
    var dynamicServerPrompt by remember { mutableStateOf<String?>(activeSystemPrompt) }

    LaunchedEffect(conversationId, workspaceDir) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val wsParam = java.net.URLEncoder.encode(workspaceDir ?: "", "UTF-8")
                val bridgeBase = AuthPreferences.currentBridgeHttpUrl.removeSuffix("/")
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
            } catch (e: Exception) {}
        }
    }

    val defaultSystemPrompt = remember(dynamicServerPrompt, conversationId, workspaceDir) {
        dynamicServerPrompt?.takeIf { it.isNotBlank() } ?: """
<identity>
You are LytsoCoder, a powerful agentic AI coding assistant working on Advanced Agentic Coding.
You are pair programming with a USER to solve their coding task. The task may require creating a new codebase, modifying or debugging an existing codebase, or simply answering a question.
The USER will send you requests, which you must always prioritize addressing. User requests are enclosed within <USER_REQUEST> tags.
</identity>

<user_information>
The USER's OS version is linux.
The user has active workspace:
${workspaceDir ?: "/data/data/${lytso.code.BuildConfig.APPLICATION_ID}/files/home/projects/gemini"}
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.82f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                val activeSourceLabel = when {
                    perChatTrimmed != null -> "Per-chat override (this chat only)"
                    globalTrimmed != null -> "Global system prompt (Settings → Agents)"
                    else -> "Agent default (<identity>)"
                }
                val shownActivePrompt = perChatTrimmed ?: globalTrimmed ?: defaultSystemPrompt

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Chat System Prompt",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Active: $activeSourceLabel",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (perChatTrimmed != null || globalTrimmed != null) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
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
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    contentColor = ClaudeTerracotta,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "🧠 Active Prompt",
                                fontSize = 12.5.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = "✍️ Custom Override",
                                fontSize = 12.5.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (selectedTab == 0) {
                    // Currently active prompt for this chat (Per-chat -> Global -> Agent Default)
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
                    // Custom override prompt
                    Text(
                        text = "Replaces the agent's default <identity> (role: system). Leave empty to inherit your Global System Prompt" +
                            (if (globalTrimmed != null) " (active)" else " (none set → uses agent default)") +
                            ". Other chats with their own prompts always keep theirs.",
                        fontSize = 11.5.sp,
                        lineHeight = 15.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        placeholder = {
                            Text(
                                text = if (globalTrimmed != null) {
                                    "Empty = inheriting Global System Prompt.\nWrite here to override ONLY this chat..."
                                } else {
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
                            focusedBorderColor = ClaudeTerracotta,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTab == 1 && promptText.isNotBlank()) {
                        TextButton(
                            onClick = { promptText = "" },
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
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("Close", fontSize = 12.5.sp)
                        }
                        if (selectedTab == 0) {
                            Button(
                                onClick = {
                                    if (promptText.isBlank()) {
                                        promptText = shownActivePrompt.trim()
                                    }
                                    selectedTab = 1
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit as override", fontSize = 12.5.sp, color = Color.White)
                            }
                        } else if (selectedTab == 1) {
                            if (onSaveGlobalPrompt != null && promptText.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        onSaveGlobalPrompt(promptText.trim())
                                        onDismiss()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text("Set Global", fontSize = 12.sp)
                                }
                            }
                            Button(
                                onClick = {
                                    onSavePrompt(promptText.ifBlank { null })
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    if (promptText.isBlank()) "Use Global/Default" else "Save Chat",
                                    fontSize = 12.5.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
