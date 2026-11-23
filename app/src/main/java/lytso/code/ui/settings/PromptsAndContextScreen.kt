package lytso.code.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
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
import lytso.code.data.agent.opencode.OpenCodeBridgeClient
import lytso.code.data.context.GlobalContext
import lytso.code.theme.ClaudeTerracotta
import lytso.code.ui.components.DEFAULT_OPENCODE_SYSTEM_PROMPT
import lytso.code.ui.components.OpenCodeAccent
import lytso.code.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.launch

enum class PromptsHubTab(val label: String) {
    IDENTITY("Identity"),
    BUILT_IN("Built-in"),
    CUSTOM("Custom")
}

/** Unified entry row on Settings → Agents for the Prompts & Context hub. */
@Composable
fun PromptsAndContextEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val globalPrompt by GlobalContext.globalSystemPrompt.collectAsState()
    val openCodeGlobalPrompt by GlobalContext.openCodeGlobalSystemPrompt.collectAsState()
    val builtInOn by GlobalContext.builtInEnabled.collectAsState()
    val entries by GlobalContext.entries.collectAsState()
    val hasIdentity = !globalPrompt.isNullOrBlank() || !openCodeGlobalPrompt.isNullOrBlank()
    val activeRules = entries.count { it.enabled }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Prompts & Context", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(if (hasIdentity) "Identity: Custom" else "Identity: Default")
                        append(" · Built-in: ")
                        append(if (builtInOn) "On" else "Off")
                        if (activeRules > 0) append(" · $activeRules rules")
                    },
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Unified hub for all AI prompt & context layers:
 * 1. Identity (Global System Prompt · role: system · highest priority)
 * 2. Built-in (Android/Termux environment & media · role: user · 1st message)
 * 3. Custom (Standing user rules · role: user · 1st or every message, full-screen editor)
 */
@Composable
fun PromptsAndContextScreen(
    initialTab: PromptsHubTab = PromptsHubTab.IDENTITY,
    onSyncOpenCodeGlobalPrompt: ((String?) -> Unit)? = null
) {
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }
    val entries by GlobalContext.entries.collectAsState()
    var editingRule by remember { mutableStateOf<GlobalContext.Entry?>(null) }

    // Full-screen editor for creating/editing a Custom rule (replaces cramped AlertDialog)
    val currentRule = editingRule
    if (currentRule != null) {
        BackHandler { editingRule = null }
        CustomRuleFullScreenEditor(
            entry = currentRule,
            isNew = entries.none { it.id == currentRule.id },
            onSave = { updated ->
                GlobalContext.upsert(updated)
                editingRule = null
            },
            onDelete = {
                GlobalContext.delete(currentRule.id)
                editingRule = null
            },
            onBack = { editingRule = null }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Segmented 3-layer hub tabs
        PromptsHubTabBar(
            activeTab = activeTab,
            customCount = entries.count { it.enabled },
            onSelect = { activeTab = it }
        )

        Spacer(Modifier.height(12.dp))

        when (activeTab) {
            PromptsHubTab.IDENTITY -> IdentityTabContent(onSyncOpenCodeGlobalPrompt = onSyncOpenCodeGlobalPrompt)
            PromptsHubTab.BUILT_IN -> BuiltInTabContent()
            PromptsHubTab.CUSTOM -> CustomRulesTabContent(
                entries = entries,
                onAdd = { editingRule = GlobalContext.Entry(title = "", text = "") },
                onAddTemplate = { title, text ->
                    editingRule = GlobalContext.Entry(title = title, text = text)
                },
                onEdit = { editingRule = it },
                onToggle = { entry, checked -> GlobalContext.upsert(entry.copy(enabled = checked)) }
            )
        }
    }
}

@Composable
private fun PromptsHubTabBar(
    activeTab: PromptsHubTab,
    customCount: Int,
    onSelect: (PromptsHubTab) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PromptsHubTab.entries.forEach { tab ->
                val selected = tab == activeTab
                val label = if (tab == PromptsHubTab.CUSTOM && customCount > 0) {
                    "${tab.label} ($customCount)"
                } else {
                    tab.label
                }
                Surface(
                    onClick = { onSelect(tab) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerBadge(text: String, accent: Boolean = false) {
    val bg = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun IdentityTabContent(
    onSyncOpenCodeGlobalPrompt: ((String?) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 0 = Antigravity, 1 = OpenCode
    var selectedAgent by remember { mutableStateOf(0) }
    val isOpenCode = selectedAgent == 1
    val accentColor = if (isOpenCode) OpenCodeAccent else ClaudeTerracotta

    val savedAgyGlobal by GlobalContext.globalSystemPrompt.collectAsState()
    val savedOpenCodeGlobal by GlobalContext.openCodeGlobalSystemPrompt.collectAsState()
    val savedGlobal = if (isOpenCode) savedOpenCodeGlobal else savedAgyGlobal

    var draft by remember(selectedAgent, savedGlobal) { mutableStateOf(savedGlobal.orEmpty()) }
    val isActive = !savedGlobal.isNullOrBlank()
    val hasChanges = draft.trim() != (savedGlobal ?: "")

    fun saveOpenCodeGlobal(cleaned: String?) {
        GlobalContext.setOpenCodeGlobalSystemPrompt(cleaned)
        if (onSyncOpenCodeGlobalPrompt != null) {
            onSyncOpenCodeGlobalPrompt(cleaned)
        } else {
            scope.launch { runCatching { OpenCodeBridgeClient().setGlobalSystemPrompt(cleaned) } }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
    ) {
        // Agent selector: Antigravity vs OpenCode (each keeps its own isolated Global System Prompt)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    Triple(0, "Antigravity", !savedAgyGlobal.isNullOrBlank()),
                    Triple(1, "OpenCode", !savedOpenCodeGlobal.isNullOrBlank())
                ).forEach { (idx, label, customOn) ->
                    val selected = selectedAgent == idx
                    val pillColor = if (idx == 1) OpenCodeAccent else ClaudeTerracotta
                    Surface(
                        onClick = { selectedAgent = idx },
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) pillColor else Color.Transparent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (customOn) "$label · Custom" else label,
                                fontSize = 12.5.sp,
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

        Spacer(Modifier.height(10.dp))

        // Compact priority hierarchy strip (visual, zero walls of text)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LayerBadge("role: system", accent = true)
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isOpenCode) "1. Chat  ›  2. OpenCode Global  ›  3. Default" else "1. Chat  ›  2. Global  ›  3. Default",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (isOpenCode) "OpenCode Global System Prompt" else "Antigravity Global System Prompt",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        isOpenCode && isActive -> "Replaces OpenCode's initial prompt (parts[0]) in real time · # Your Model hidden"
                        isOpenCode -> "Using OpenCode default harness prompt · # Your Model hidden"
                        isActive -> "Replaces default <identity> for Antigravity chats without override"
                        else -> "Using Antigravity built-in <identity>"
                    },
                    fontSize = 12.sp,
                    color = if (isActive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LayerBadge(if (isActive) "Active" else "Default", accent = isActive)
        }

        Spacer(Modifier.height(10.dp))

        // Spacious open editor
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            minLines = 11,
            maxLines = 22,
            placeholder = {
                Text(
                    if (isOpenCode) {
                        "Define OpenCode's initial system prompt (parts[0]) for all OpenCode chats…\nCode Mode, skills & <env> stay active; # Your Model is removed."
                    } else {
                        "Define persona, tone and core rules for all Antigravity chats…"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                )
            },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive || draft.isNotBlank()) {
                TextButton(
                    onClick = {
                        draft = ""
                        if (isOpenCode) saveOpenCodeGlobal(null) else GlobalContext.setGlobalSystemPrompt(null)
                        Toast.makeText(
                            context,
                            if (isOpenCode) "Reset to OpenCode default" else "Reset to agent default",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Reset", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            } else if (isOpenCode) {
                TextButton(onClick = { draft = DEFAULT_OPENCODE_SYSTEM_PROMPT }) {
                    Text("Load default", color = OpenCodeAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Button(
                onClick = {
                    val cleaned = draft.trim().takeIf { it.isNotBlank() }
                    if (isOpenCode) saveOpenCodeGlobal(cleaned) else GlobalContext.setGlobalSystemPrompt(cleaned)
                    Toast.makeText(
                        context,
                        when {
                            isOpenCode && cleaned == null -> "OpenCode global system prompt cleared"
                            isOpenCode -> "OpenCode global system prompt saved"
                            cleaned == null -> "Global system prompt cleared"
                            else -> "Global system prompt saved"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                },
                enabled = hasChanges,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOpenCode) OpenCodeAccent else MaterialTheme.colorScheme.primary,
                    contentColor = if (isOpenCode) Color.White else MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BuiltInTabContent() {
    val context = LocalContext.current
    val builtInOn by GlobalContext.builtInEnabled.collectAsState()
    val builtInCustom by GlobalContext.builtInCustomText.collectAsState()
    val effectiveText = remember(builtInCustom) { GlobalContext.builtInText() }
    var draft by remember(effectiveText) { mutableStateOf(effectiveText) }
    val hasChanges = draft.trim() != effectiveText.trim()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
    ) {
        // Layer indicator + master switch
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LayerBadge("role: user · 1st msg", accent = true)
                        if (builtInCustom != null) LayerBadge("Customized")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Android/Termux environment, ~/lytso_home/ & media hints",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = builtInOn,
                    onCheckedChange = { GlobalContext.setBuiltInEnabled(it) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Spacious open editor (no AlertDialog!)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = builtInOn,
            minLines = 12,
            maxLines = 24,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (builtInCustom != null) {
                TextButton(
                    onClick = {
                        GlobalContext.resetBuiltInText()
                        Toast.makeText(context, "Built-in context restored", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Reset default", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Button(
                onClick = {
                    GlobalContext.setBuiltInText(draft)
                    Toast.makeText(context, "Built-in context saved", Toast.LENGTH_SHORT).show()
                },
                enabled = builtInOn && hasChanges,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CustomRulesTabContent(
    entries: List<GlobalContext.Entry>,
    onAdd: () -> Unit,
    onAddTemplate: (String, String) -> Unit,
    onEdit: (GlobalContext.Entry) -> Unit,
    onToggle: (GlobalContext.Entry, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
    ) {
        // Layer strip + New Rule CTA
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    LayerBadge("role: user · standing rules", accent = true)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Hidden notes attached to 1st or every message",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onAdd,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("No custom rules yet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Quick templates:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestionChip(
                            onClick = { onAddTemplate("Language", "Always reply concisely in Portuguese.") },
                            label = { Text("Language", fontSize = 12.sp) }
                        )
                        SuggestionChip(
                            onClick = { onAddTemplate("Git safety", "Never run git push or force-push without asking first.") },
                            label = { Text("Git safety", fontSize = 12.sp) }
                        )
                    }
                }
            }
        } else {
            entries.forEach { e ->
                Surface(
                    onClick = { onEdit(e) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = e.title.ifBlank { "Untitled rule" },
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = e.text.trim(),
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LayerBadge(if (e.everyMessage) "Every message" else "First message", accent = e.everyMessage)
                                if (e.forJarvis) LayerBadge("Jarvis")
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = e.enabled, onCheckedChange = { onToggle(e, it) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Full-screen editor for creating or editing a Custom Context Rule (zero cramped AlertDialogs). */
@Composable
private fun CustomRuleFullScreenEditor(
    entry: GlobalContext.Entry,
    isNew: Boolean,
    onSave: (GlobalContext.Entry) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    var title by remember(entry.id) { mutableStateOf(entry.title) }
    var text by remember(entry.id) { mutableStateOf(entry.text) }
    var everyMessage by remember(entry.id) { mutableStateOf(entry.everyMessage) }
    var forJarvis by remember(entry.id) { mutableStateOf(entry.forJarvis) }
    val canSave = text.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Editor top action bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isNew) "New rule" else "Edit rule",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    onSave(
                        entry.copy(
                            title = title.trim().ifBlank { text.trim().lineSequence().firstOrNull()?.take(36) ?: "Rule" },
                            text = text.trim(),
                            everyMessage = everyMessage,
                            forJarvis = forJarvis
                        )
                    )
                },
                enabled = canSave,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .bouncyVerticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Title") },
                placeholder = { Text("e.g. Language, Coding style") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Instruction") },
                placeholder = { Text("What the agent should know or always follow…") },
                minLines = 10,
                maxLines = 20,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Delivery frequency segmented selector
            Text("When to send", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(false to "First message", true to "Every message").forEach { (isEvery, label) ->
                        val selected = everyMessage == isEvery
                        Surface(
                            onClick = { everyMessage = isEvery },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Jarvis toggle
            Surface(
                onClick = { forJarvis = !forJarvis },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Include in Jarvis", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Jarvis rules still take priority on conflict",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = forJarvis, onCheckedChange = { forJarvis = it })
                }
            }

            if (!isNew) {
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete rule", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
