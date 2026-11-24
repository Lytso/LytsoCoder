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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lytso.code.data.adb.AdbConnectManager
import lytso.code.data.adb.DeviceAccess
import lytso.code.data.adb.RootShell
import lytso.code.data.adb.ShizukuShell
import lytso.code.data.files.FileExplorerApi
import lytso.code.data.security.AiPermissionPolicy
import lytso.code.data.security.AiPermissionsClient
import lytso.code.theme.AppColors
import lytso.code.theme.QuotaGreen
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

private val COMMON_BLOCKABLE_TOOLS = listOf(
    "run_command",
    "search_web",
    "read_url",
    "generate_image",
    "call_mcp_tool",
    "edit_file",
    "view_file",
    "list_dir",
    "grep_search",
    "find",
    "unknown_tool"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiPermissionsAndExplorerScreen(
    cardBg: Color,
    cardBorder: BorderStroke,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by AiPermissionsClient.state.collectAsState()
    val deviceMode by DeviceAccess.mode.collectAsState()
    val rootShellState by RootShell.state.collectAsState()
    val adbState by AdbConnectManager.state.collectAsState()
    val shizukuStatus by ShizukuShell.status.collectAsState()

    var selectedTab by remember(initialTab) { mutableStateOf(initialTab.coerceIn(0, 2)) }
    var busy by remember { mutableStateOf(false) }
    var customRoots by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showAddRootDialog by remember { mutableStateOf(false) }
    var customBlockedToolInput by remember { mutableStateOf("") }

    var hasAllFilesPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        )
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasAllFilesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        AdbConnectManager.init(context)
        DeviceAccess.init(context)
        AiPermissionsClient.refresh()
        ShizukuShell.refresh()
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

    val effectiveRootGranted = snapshot.rootGranted || rootShellState.check == RootShell.Check.GRANTED
    val effectiveSuPath = snapshot.suPath.ifBlank { rootShellState.suPath }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top navigation tabs: Device Access | AI & Tools | Explorer
        val tabs = listOf("Device Access", "AI & Tools", "Explorer")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { idx, label ->
                SegmentedButton(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    shape = SegmentedButtonDefaults.itemShape(idx, tabs.size)
                ) {
                    Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // Hero Mode Selector Card — clean, didactic, live status pill
                val (modeBadgeText, modeBadgeColor) = when (deviceMode) {
                    DeviceAccess.Mode.ADB ->
                        if (adbState.connectedDevice != null) "Connected" to QuotaGreen
                        else "Setup needed" to AppColors.warning
                    DeviceAccess.Mode.SHIZUKU ->
                        if (shizukuStatus.ready) "Ready" to QuotaGreen
                        else "Setup needed" to AppColors.warning
                    DeviceAccess.Mode.ROOT -> when {
                        effectiveRootGranted && snapshot.policy.allowRoot -> "Active" to QuotaGreen
                        effectiveRootGranted -> "Guarded" to AppColors.warning
                        rootShellState.check == RootShell.Check.CHECKING -> "Checking…" to MaterialTheme.colorScheme.primary
                        else -> "Not granted" to AppColors.danger
                    }
                }
                val modeIcon = when (deviceMode) {
                    DeviceAccess.Mode.ADB -> Icons.Outlined.PhonelinkSetup
                    DeviceAccess.Mode.SHIZUKU -> Icons.Outlined.Bolt
                    DeviceAccess.Mode.ROOT -> Icons.Outlined.AdminPanelSettings
                }
                val suCmd = if (AdbConnectManager.inChroot) "android-su -c" else "su -c"

                Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(modeBadgeColor.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(modeIcon, null, tint = modeBadgeColor, modifier = Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Device Access Mode", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Choose how LytsoCoder controls this phone",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Pill(modeBadgeText, modeBadgeColor)
                        }

                        val modes = DeviceAccess.Mode.entries
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = m == deviceMode,
                                    onClick = { DeviceAccess.setMode(m) },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                                ) {
                                    Text(m.label, fontSize = 12.5.sp)
                                }
                            }
                        }

                        Hint(
                            when (deviceMode) {
                                DeviceAccess.Mode.ADB ->
                                    "Runs as shell over Wireless debugging (pair once, auto-reconnects). In chat: \"use adb\"."
                                DeviceAccess.Mode.SHIZUKU ->
                                    "Runs through the Shizuku app with zero pairing. In chat: \"use shizuku\" (rish -c …)."
                                DeviceAccess.Mode.ROOT ->
                                    "Runs as root via su (Magisk / KernelSU / APatch) with instant 0ms AI guard. In chat: \"use su\" ($suCmd …)."
                            }
                        )
                    }
                }

                // Strictly contextual section — only shows controls for the selected mode!
                when (deviceMode) {
                    DeviceAccess.Mode.ADB -> AdbSection(cardBg, cardBorder)
                    DeviceAccess.Mode.SHIZUKU -> ShizukuSection(cardBg, cardBorder)
                    DeviceAccess.Mode.ROOT -> UnifiedRootControlSection(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        snapshot = snapshot,
                        rootShellState = rootShellState,
                        effectiveRootGranted = effectiveRootGranted,
                        effectiveSuPath = effectiveSuPath,
                        busy = busy,
                        onToggleAllowRoot = { enable ->
                            applyPolicy(
                                snapshot.policy.copy(allowRoot = enable),
                                if (enable) "AI root access enabled (0ms hot-reload)" else "AI root access blocked (su guard active)"
                            )
                        },
                        onCheckRoot = {
                            scope.launch {
                                busy = true
                                RootShell.check()
                                AiPermissionsClient.refresh()
                                busy = false
                            }
                        }
                    )
                }
            }

            1 -> {
                // 1. Modular AI Capabilities
                SectionLabel("MODULAR AI CAPABILITIES")
                Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CapabilityToggleRow(
                            icon = Icons.Outlined.Terminal,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "Shell command execution",
                            subtitle = "Allow AI to execute terminal commands",
                            checked = snapshot.policy.allowShellExec,
                            enabled = !busy,
                            onCheckedChange = { applyPolicy(snapshot.policy.copy(allowShellExec = it), "Shell policy updated") }
                        )
                        HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        CapabilityToggleRow(
                            icon = Icons.Outlined.EditNote,
                            iconTint = AppColors.accentTeal,
                            title = "File write & edit",
                            subtitle = "Allow creating, editing and deleting files",
                            checked = snapshot.policy.allowFileWrite,
                            enabled = !busy,
                            onCheckedChange = { applyPolicy(snapshot.policy.copy(allowFileWrite = it), "File write policy updated") }
                        )
                        HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        CapabilityToggleRow(
                            icon = Icons.Outlined.Public,
                            iconTint = AppColors.accentIndigo,
                            title = "Network & web access",
                            subtitle = "Allow web search, downloads and API calls",
                            checked = snapshot.policy.allowNetwork,
                            enabled = !busy,
                            onCheckedChange = { applyPolicy(snapshot.policy.copy(allowNetwork = it), "Network policy updated") }
                        )
                        HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(QuotaGreen.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.FolderOpen, null, tint = QuotaGreen, modifier = Modifier.size(17.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("All files storage access", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (hasAllFilesPermission) "Granted · full /sdcard & custom folder access"
                                    else "Required to browse internal storage outside app home",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
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

                // 2. Tool Blocker & Auto-Interrupt
                val blocked = snapshot.policy.blockedTools
                SectionLabel("BLOCKED TOOLS (AUTO-INTERRUPT)")
                Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto-stop unauthorized tools", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Abruptly interrupts selected tools during or after a reply so the AI continues seamlessly.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Pill(
                                if (blocked.isEmpty()) "0 blocked" else "${blocked.size} blocked",
                                if (blocked.isEmpty()) MaterialTheme.colorScheme.primary else AppColors.danger
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            COMMON_BLOCKABLE_TOOLS.forEach { toolName ->
                                val isBlocked = toolName in blocked
                                FilterChip(
                                    selected = isBlocked,
                                    enabled = !busy,
                                    onClick = {
                                        val nextList = if (isBlocked) blocked - toolName else blocked + toolName
                                        applyPolicy(
                                            snapshot.policy.copy(blockedTools = nextList),
                                            if (isBlocked) "Unblocked $toolName" else "Blocked $toolName (auto-interrupt active)"
                                        )
                                    },
                                    label = {
                                        Text(
                                            toolName,
                                            fontSize = 11.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    },
                                    leadingIcon = if (isBlocked) {
                                        { Icon(Icons.Outlined.Block, null, modifier = Modifier.size(14.dp), tint = AppColors.danger) }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AppColors.danger.copy(alpha = 0.16f),
                                        selectedLabelColor = AppColors.danger
                                    )
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = customBlockedToolInput,
                                onValueChange = { customBlockedToolInput = it },
                                singleLine = true,
                                label = { Text("Custom tool or MCP name", fontSize = 12.sp) },
                                placeholder = { Text("e.g. mcp_browser or server/tool", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                enabled = !busy && customBlockedToolInput.isNotBlank(),
                                onClick = {
                                    val norm = customBlockedToolInput.trim().lowercase()
                                    if (norm.isNotBlank() && norm !in blocked) {
                                        applyPolicy(
                                            snapshot.policy.copy(blockedTools = blocked + norm),
                                            "Blocked $norm"
                                        )
                                    }
                                    customBlockedToolInput = ""
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Block", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        val customBlocked = blocked.filterNot { it in COMMON_BLOCKABLE_TOOLS }
                        if (customBlocked.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                customBlocked.forEach { toolName ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(AppColors.danger.copy(alpha = 0.08f))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Outlined.Block, null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            toolName,
                                            fontSize = 12.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            enabled = !busy,
                                            onClick = {
                                                applyPolicy(
                                                    snapshot.policy.copy(blockedTools = blocked - toolName),
                                                    "Unblocked $toolName"
                                                )
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Outlined.DeleteOutline, "Unblock", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Tip: Tap the trash icon on any tool or System: card in chat to delete it, or expand a card to block that tool in one tap.",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            2 -> {
                // 1. Modular File Explorer Roots (shared with AI & Mini-Apps)
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

                // 2. Official ~/lytso_home/ Workspace Architecture
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

/**
 * Single unified Root control panel shown ONLY when DeviceAccess.Mode.ROOT is active.
 * Combines the 0ms hot-reload `ai_bin_guard` toggle, Magisk/KernelSU/APatch verification, and inline `id` test
 * without any duplicate cards.
 */
@Composable
private fun UnifiedRootControlSection(
    cardBg: Color,
    cardBorder: BorderStroke,
    snapshot: lytso.code.data.security.AiPermissionsSnapshot,
    rootShellState: RootShell.State,
    effectiveRootGranted: Boolean,
    effectiveSuPath: String,
    busy: Boolean,
    onToggleAllowRoot: (Boolean) -> Unit,
    onCheckRoot: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var testingId by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val aiRootActive = snapshot.policy.allowRoot && effectiveRootGranted
    val isChecking = busy || rootShellState.check == RootShell.Check.CHECKING

    Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background((if (aiRootActive) QuotaGreen else if (effectiveRootGranted) AppColors.warning else AppColors.danger).copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (aiRootActive) Icons.Outlined.VerifiedUser else Icons.Outlined.AdminPanelSettings,
                        null,
                        tint = if (aiRootActive) QuotaGreen else if (effectiveRootGranted) AppColors.warning else AppColors.danger,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Allow Root (su) for AI", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    val statusLine = when {
                        isChecking -> "Verifying su binary…"
                        effectiveRootGranted -> "Verified · ${effectiveSuPath.ifBlank { "su" }}"
                        snapshot.rootBinaryFound || rootShellState.check == RootShell.Check.DENIED ->
                            "su found, permission denied in root manager"
                        else -> "No su binary found (device not rooted)"
                    }
                    Text(statusLine, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = aiRootActive,
                    enabled = !isChecking && effectiveRootGranted,
                    onCheckedChange = onToggleAllowRoot,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Live guard indicator pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background((if (aiRootActive) QuotaGreen else AppColors.warning).copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (aiRootActive) QuotaGreen else AppColors.warning)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (aiRootActive) "su / sudo / tsu forwarded to AI shell (0ms hot-reload)"
                    else "su / sudo / tsu blocked in AI PATH (guard active)",
                    fontSize = 11.5.sp,
                    color = if (aiRootActive) QuotaGreen else AppColors.warning,
                    fontWeight = FontWeight.Medium
                )
            }

            rootShellState.message?.takeIf { !effectiveRootGranted }?.let { errMsg ->
                Text(errMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

            // Quick actions: Check root + Test (id)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    enabled = !isChecking,
                    onClick = onCheckRoot,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (effectiveRootGranted) "Re-check" else "Check root", fontSize = 12.5.sp, maxLines = 1)
                }

                if (effectiveRootGranted) {
                    OutlinedButton(
                        enabled = !testingId,
                        onClick = {
                            testingId = true
                            scope.launch {
                                val r = DeviceAccess.shell("id", 20, DeviceAccess.Mode.ROOT)
                                testResult = r.output.trim().ifBlank { "exit code ${r.exitCode}" }
                                testingId = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (testingId) "Running…" else "Test (id)", fontSize = 12.5.sp, maxLines = 1)
                    }
                }
            }

            testResult?.let { Mono(it) }
        }
    }

    // Compact security notice
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Root grants full system control (Magisk, KernelSU, APatch). Toggle off anytime for instant 0ms lockout.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
        }
    }

    // Compact terminal usage card
    Surface(shape = RoundedCornerShape(14.dp), color = cardBg, border = cardBorder, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Use in terminal", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            if (AdbConnectManager.inChroot) {
                Mono("android-su -c 'pm list packages'\nandroid-su            # interactive")
                Hint("In the Ubuntu chroot, android-su runs commands as Android root through LytsoCoder.")
            } else {
                Mono("su -c 'pm list packages'\nsu")
                Hint("Uses the device's verified su binary directly through the AI root guard.")
            }
        }
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
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
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
