package lytso.code.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.launch
import lytso.code.data.files.FileExplorerApi
import lytso.code.data.security.AiPermissionPolicy
import lytso.code.data.security.AiPermissionsClient
import lytso.code.theme.AppColors
import lytso.code.ui.components.bouncyVerticalScroll

private val WORKSPACE_FOLDERS = listOf(
    "projects/" to "Active repos & forks",
    "keys/" to "Keystores, .env, SSH & tokens",
    "scripts/" to "Shell, Python & Node automations",
    "logs/" to "Build & execution logs",
    "dumps/" to "Extractions, APKs & DB dumps",
    "tmp/" to "Disposable scratch & downloads",
    "builds/" to "Compiled APKs & release outputs",
    "tools/" to "Custom binaries & local SDKs",
    "cloud/" to "Remote VMs & SSH profiles",
    "backups/" to "Pre-change & root snapshots"
)

@Composable
fun AiPermissionsAndExplorerScreen(
    cardBg: Color,
    cardBorder: BorderStroke
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by AiPermissionsClient.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var customRoots by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showAddRootDialog by remember { mutableStateOf(false) }

    val hasAllFilesPermission = remember(snapshot.loaded) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
    }

    LaunchedEffect(Unit) {
        AiPermissionsClient.refresh()
        customRoots = FileExplorerApi.customRoots()
    }

    fun applyPolicy(next: AiPermissionPolicy, label: String) {
        scope.launch {
            busy = true
            val res = AiPermissionsClient.update(next)
            busy = false
            res.onSuccess {
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, err.message ?: "Failed to update policy", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Dedicated Root Access Control (Termux)
        SectionLabel("AI ROOT ACCESS (TERMUX)")
        Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background((if (snapshot.policy.allowRoot) AppColors.danger else MaterialTheme.colorScheme.primary).copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.AdminPanelSettings,
                            null,
                            tint = if (snapshot.policy.allowRoot) AppColors.danger else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Allow Root (su) for AI", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        val statusText = when {
                            !snapshot.loaded -> "Checking device root…"
                            snapshot.rootGranted -> "Device root verified (${snapshot.suPath.ifBlank { "su" }})"
                            snapshot.rootBinaryFound -> "su found, but root permission denied to app"
                            else -> "Device is not rooted"
                        }
                        Text(statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = snapshot.policy.allowRoot && snapshot.rootGranted,
                        enabled = !busy && snapshot.rootGranted,
                        onCheckedChange = { enable ->
                            applyPolicy(
                                snapshot.policy.copy(allowRoot = enable),
                                if (enable) "AI root access enabled (hub reloaded)" else "AI root access blocked (su guard active)"
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = if (snapshot.rootGranted) AppColors.success else AppColors.warning
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (snapshot.policy.allowRoot && snapshot.rootGranted) "su forwarded to AI shell"
                            else "su / tsu / sudo blocked in AI PATH",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = { scope.launch { busy = true; AiPermissionsClient.refresh(); busy = false } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Re-check", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // 2. Granular AI Capabilities
        SectionLabel("MODULAR AI CAPABILITIES")
        Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CapabilityToggleRow(
                    title = "Shell command execution",
                    subtitle = "Allow AI to execute terminal commands",
                    checked = snapshot.policy.allowShellExec,
                    enabled = !busy,
                    onCheckedChange = { applyPolicy(snapshot.policy.copy(allowShellExec = it), "Shell policy updated") }
                )
                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                CapabilityToggleRow(
                    title = "File write & edit",
                    subtitle = "Allow creating, editing and deleting files",
                    checked = snapshot.policy.allowFileWrite,
                    enabled = !busy,
                    onCheckedChange = { applyPolicy(snapshot.policy.copy(allowFileWrite = it), "File write policy updated") }
                )
                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                CapabilityToggleRow(
                    title = "Network & web access",
                    subtitle = "Allow web search, downloads and API calls",
                    checked = snapshot.policy.allowNetwork,
                    enabled = !busy,
                    onCheckedChange = { applyPolicy(snapshot.policy.copy(allowNetwork = it), "Network policy updated") }
                )
                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("All files storage access", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (hasAllFilesPermission) "Granted · full /sdcard & custom folder access"
                            else "Required to browse internal storage outside app home",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!hasAllFilesPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Button(
                            onClick = {
                                runCatching {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    } else {
                        Switch(
                            checked = snapshot.policy.allowAllFiles,
                            enabled = !busy,
                            onCheckedChange = { applyPolicy(snapshot.policy.copy(allowAllFiles = it), "Storage policy updated") },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }

        // 3. Modular File Explorer Roots (shared with AI & Mini-Apps)
        SectionLabel("MODULAR FILE EXPLORER DIRECTORIES")
        Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Custom folders", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Visible in Files explorer, AI agents and Mini-Apps.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { showAddRootDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
                if (customRoots.isEmpty()) {
                    Text(
                        "Default roots: Home, Lytso Home, Projects and Internal Storage. Add any extra directory (e.g. /sdcard/Download).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    customRoots.forEach { (name, path) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val res = FileExplorerApi.removeCustomRoot(path)
                                        if (res.ok) customRoots = FileExplorerApi.customRoots()
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // 4. Official ~/lytso_home/ Workspace Architecture
        SectionLabel("AI WORKSPACE (~/LYTSO_HOME/)")
        Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Auto-created and enforced in the AI's built-in system instructions:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WORKSPACE_FOLDERS.forEach { (dir, desc) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            dir,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(86.dp)
                        )
                        Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showAddRootDialog) {
        var name by remember { mutableStateOf("") }
        var path by remember { mutableStateOf("/sdcard") }
        AlertDialog(
            onDismissRequest = { showAddRootDialog = false },
            title = { Text("Add explorer directory", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Label (optional)") },
                        placeholder = { Text("e.g. Downloads") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        singleLine = true,
                        label = { Text("Absolute path") },
                        placeholder = { Text("/sdcard/Download") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = path.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val res = FileExplorerApi.addCustomRoot(name.trim(), path.trim())
                            if (res.ok) {
                                customRoots = FileExplorerApi.customRoots()
                                showAddRootDialog = false
                                Toast.makeText(context, "Directory added", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, res.error ?: "Invalid directory", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Add", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRootDialog = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp)
    )
}

@Composable
private fun CapabilityToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
