package lytso.code.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lytso.code.data.context.GlobalContext
import lytso.code.theme.ClaudeTerracotta
import lytso.code.ui.components.bouncyVerticalScroll

/** The row on Settings → Agents that opens [GlobalSystemPromptScreen]. */
@Composable
fun GlobalSystemPromptEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val globalPrompt by GlobalContext.globalSystemPrompt.collectAsState()
    val isActive = !globalPrompt.isNullOrBlank()
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
                    .background((if (isActive) ClaudeTerracotta else MaterialTheme.colorScheme.primary).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    null,
                    tint = if (isActive) ClaudeTerracotta else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("System prompt (global default)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isActive) "Custom global active · replaces agent <identity>"
                    else "Using agent default · tap to set for all chats",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings → Agents → System prompt: sets the global default system prompt used by every chat that does not define
 * its own per-chat override, plus a clear guide comparing System Prompt vs Global Context.
 */
@Composable
fun GlobalSystemPromptScreen(onOpenGlobalContext: (() -> Unit)? = null) {
    val context = LocalContext.current
    val savedGlobal by GlobalContext.globalSystemPrompt.collectAsState()
    var draft by remember(savedGlobal) { mutableStateOf(savedGlobal.orEmpty()) }
    val isActive = !savedGlobal.isNullOrBlank()
    val hasUnsavedChanges = draft.trim() != (savedGlobal ?: "")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ----- Status & Editor -----
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Global System Prompt", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isActive) "Active · replaces default <identity> & persona"
                            else "Off · chats use the agent's built-in default",
                            fontSize = 12.sp,
                            color = if (isActive) ClaudeTerracotta else muted,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val psychologyInline = mapOf(
                    "psychology" to InlineTextContent(
                        Placeholder(15.sp, 15.sp, PlaceholderVerticalAlign.TextCenter)
                    ) {
                        Icon(
                            Icons.Outlined.Psychology,
                            contentDescription = "System prompt icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                )
                Text(
                    text = buildAnnotatedString {
                        append("Applies automatically to every new or existing chat that has no custom prompt of its own. Any chat where you already set a per-chat override (the ")
                        appendInlineContent("psychology", "[system prompt]")
                        append(" icon in the chat bar) keeps its own prompt untouched.")
                    },
                    inlineContent = psychologyInline,
                    fontSize = 12.5.sp,
                    lineHeight = 17.5.sp,
                    color = muted
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 7,
                    maxLines = 15,
                    placeholder = {
                        Text(
                            "e.g. You are a concise senior systems engineer. Always answer directly without filler...",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    ),
                    shape = RoundedCornerShape(12.dp),
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
                                GlobalContext.setGlobalSystemPrompt(null)
                                Toast.makeText(context, "Global system prompt cleared (using agent default)", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("Reset to default", color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp)
                        }
                    } else {
                        Spacer(Modifier.width(4.dp))
                    }
                    Button(
                        onClick = {
                            val cleaned = draft.trim().takeIf { it.isNotBlank() }
                            GlobalContext.setGlobalSystemPrompt(cleaned)
                            Toast.makeText(
                                context,
                                if (cleaned == null) "Global system prompt cleared" else "Global system prompt saved",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        enabled = hasUnsavedChanges,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Global", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ----- Precedence Card -----
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Priority order (which prompt wins)", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                PriorityStepRow(
                    step = "1",
                    title = "Per-chat override",
                    showPsychologyIcon = true,
                    titleSuffix = " icon in chat",
                    detail = "Highest priority. Always wins for that single chat, even if Global is set."
                )
                PriorityStepRow(
                    step = "2",
                    title = "Global system prompt (this screen)",
                    detail = "Used automatically by every chat whose own override is empty."
                )
                PriorityStepRow(
                    step = "3",
                    title = "Agent built-in default",
                    detail = "Used only when both the chat override and Global are empty (e.g. Antigravity <identity>)."
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ----- Didactic comparison of the 3 prompt/context types -----
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("System Prompt vs. Global Context", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                }
                GuideComparisonItem(
                    badge = "role: system · strongest",
                    title = "System Prompt (this screen / ",
                    showPsychologyIcon = true,
                    titleSuffix = " in chat)",
                    description = "Replaces the model's core identity and default instructions (<identity>). Best for defining who the assistant is, its persona, and how it behaves."
                )
                GuideComparisonItem(
                    badge = "role: user · 1st message",
                    title = "Global context → Built-in & First message",
                    description = "Sent hidden once at the start of each chat. Ideal for environment info (Termux, \$TMPDIR, /sdcard, how to show files) and general preferences without erasing the system prompt."
                )
                GuideComparisonItem(
                    badge = "role: user · every turn",
                    title = "Global context → Every message",
                    description = "Added hidden before every user message. Best for short rules the model must never forget in long chats (uses a few extra tokens per turn)."
                )
                if (onOpenGlobalContext != null) {
                    OutlinedButton(
                        onClick = onOpenGlobalContext,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Global context")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PriorityStepRow(
    step: String,
    title: String,
    detail: String,
    showPsychologyIcon: Boolean = false,
    titleSuffix: String = ""
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(step, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (showPsychologyIcon) {
                val inline = mapOf(
                    "psychology" to InlineTextContent(Placeholder(15.sp, 15.sp, PlaceholderVerticalAlign.TextCenter)) {
                        Icon(
                            Icons.Outlined.Psychology,
                            contentDescription = "System prompt icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                )
                Text(
                    text = buildAnnotatedString {
                        append(title)
                        append(" (")
                        appendInlineContent("psychology", "[system prompt]")
                        append(titleSuffix)
                        append(")")
                    },
                    inlineContent = inline,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GuideComparisonItem(
    badge: String,
    title: String,
    description: String,
    showPsychologyIcon: Boolean = false,
    titleSuffix: String = ""
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(12.dp)
    ) {
        Text(
            text = badge,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(6.dp))
        if (showPsychologyIcon) {
            val inline = mapOf(
                "psychology" to InlineTextContent(Placeholder(15.sp, 15.sp, PlaceholderVerticalAlign.TextCenter)) {
                    Icon(
                        Icons.Outlined.Psychology,
                        contentDescription = "System prompt icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
            Text(
                text = buildAnnotatedString {
                    append(title)
                    appendInlineContent("psychology", "[system prompt]")
                    append(titleSuffix)
                },
                inlineContent = inline,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(2.dp))
        Text(description, fontSize = 12.sp, lineHeight = 16.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
