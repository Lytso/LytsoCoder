package lytso.code

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import lytso.code.theme.GeminiTheme
import lytso.code.ui.chat.ChatScreen
import lytso.code.ui.browser.BrowserScreen
import lytso.code.ui.chat.ChatViewModel
import lytso.code.ui.chat.ChatViewModelHolder
import lytso.code.ui.ide.IdeScreen

enum class AppViewMode {
    CHAT,
    IDE,
    TERMINAL,
    BROWSER,
    NOTES,
    FILES,
    /** The Run screen: a folder's commands and their live output (runs keep going on other screens) */
    RUN
}

class MainActivity : ComponentActivity() {

    companion object {
        private var instanceRef: java.lang.ref.WeakReference<MainActivity>? = null

        val currentInstance: MainActivity?
            get() = instanceRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

        fun showToast(message: String) {
            lytso.code.ui.components.AppToastHelper.showToast(message)
        }
    }

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instanceRef = java.lang.ref.WeakReference(this)
        lytso.code.data.jarvis.JarvisPreferences.init(this)
        lytso.code.data.context.GlobalContext.init(this)
        lytso.code.data.memory.AgentMemory.init(this)
        lytso.code.data.egm.EgmManager.init(this)
        lytso.code.data.remote.inspector.NetworkInspectorManager.isEnabled =
            chatViewModel.authPreferences.getNetworkInspectorEnabledSync()
        lytso.code.data.remote.inspector.NetworkInspectorManager.isFloatingBubbleEnabled =
            chatViewModel.authPreferences.getFloatingNetworkInspectorEnabledSync()

        try {
            android.webkit.WebView.enableSlowWholeDocumentDraw()
        } catch (_: Exception) {
        }

        if (lytso.code.ui.components.PermissionUtils.hasNotificationPermission(this)) {
            lytso.code.data.service.LytsoCoderService.start(this)
            lytso.code.data.local.LocalServerManager.autoStartOnAppLaunch(this)
        }
        lytso.code.data.daemon.IdeDaemonManager.init(this)
        lytso.code.ui.browser.BrowserSessionManager.instance.init(this)
        lytso.code.data.local.LocalTerminalBridge.instance.init(this)
        lytso.code.data.notes.NotesRepository.init(this)
        lytso.code.data.files.ExplorerPrefs.init(this)
        // temporary video / attachment / share copies left from earlier runs
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { lytso.code.data.files.CacheJanitor.trimAll(applicationContext, force = true) }
        resetTerminalLauncherOnce()
        lytso.code.data.remote.AndroidLocalBridgeServer.instance.start(this)
        lytso.code.data.jarvis.JarvisController.attach(application, chatViewModel)
        lytso.code.data.agent.ChatCompletionMonitor.start(application, chatViewModel)
        lytso.code.data.memory.MemorySync.start(chatViewModel)
        handleIncomingFileIntent(intent)
        handleJarvisIntent(intent)

        setContent {
            val initialThemeMode = remember { chatViewModel.authPreferences.getThemeModeSync() }
            val themeMode by chatViewModel.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = lytso.code.theme.isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }
            // the startup splash follows the app's own light / dark mode
            LaunchedEffect(themeMode) { lytso.code.theme.ThemeManager.syncSystemNightMode(themeMode) }

            val requestedMode by chatViewModel.requestedViewMode.collectAsState()
            val initialViewMode = remember {
                when (chatViewModel.requestedViewMode.value) {
                    "IDE" -> AppViewMode.IDE
                    "TERMINAL" -> AppViewMode.TERMINAL
                    "BROWSER" -> AppViewMode.BROWSER
                    "NOTES" -> AppViewMode.NOTES
                    "FILES" -> AppViewMode.FILES
                    "RUN" -> AppViewMode.RUN
                    else -> AppViewMode.CHAT
                }
            }
            var currentViewMode by remember { mutableStateOf(initialViewMode) }
            var previousViewMode by remember { mutableStateOf(AppViewMode.CHAT) }

            LaunchedEffect(requestedMode) {
                requestedMode?.let { mode ->
                    when (mode) {
                        "IDE" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.IDE
                        }

                        "CHAT" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.CHAT
                        }

                        "TERMINAL" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.TERMINAL
                        }

                        "BROWSER" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.BROWSER
                        }

                        "NOTES" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.NOTES
                        }

                        "FILES" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.FILES
                        }

                        "RUN" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.RUN
                        }
                    }
                    chatViewModel.consumeRequestedViewMode()
                }
            }

            val floatingSwitcherOrientation by chatViewModel.floatingSwitcherOrientation.collectAsState()
            val floatingSwitcherItems by chatViewModel.floatingSwitcherItems.collectAsState()
            val floatingSwitcherAutoCollapseSec by chatViewModel.floatingSwitcherAutoCollapseSec.collectAsState()
            val floatingSwitcherPosX by chatViewModel.floatingSwitcherPosX.collectAsState()
            val floatingSwitcherPosY by chatViewModel.floatingSwitcherPosY.collectAsState()
            val isFloatingDiagnosticsEnabled by chatViewModel.isFloatingDiagnosticsEnabled.collectAsState()
            val isNetworkInspectorEnabled by chatViewModel.isNetworkInspectorEnabled.collectAsState()
            val isFloatingNetworkInspectorEnabled by chatViewModel.isFloatingNetworkInspectorEnabled.collectAsState()
            var showNetworkInspectorDialog by remember { mutableStateOf(false) }

            var pendingUpdateInfo by remember { mutableStateOf<lytso.code.data.updater.AppUpdateInfo?>(null) }
            val updateManager = remember { lytso.code.data.updater.AppUpdateManager(this@MainActivity) }

            LaunchedEffect(Unit) {
                updateManager.checkForUpdates().onSuccess { info ->
                    pendingUpdateInfo = info
                }
            }

            LaunchedEffect(isFloatingDiagnosticsEnabled) {
                if (isFloatingDiagnosticsEnabled) {
                    lytso.code.data.remote.core.LytsoCoderLiveDiagnostics.start()
                } else {
                    lytso.code.data.remote.core.LytsoCoderLiveDiagnostics.stop()
                }
            }

            // Jarvis (voice mode) needs to know which screen the user is on
            LaunchedEffect(currentViewMode) {
                lytso.code.data.jarvis.JarvisController.screen.value = currentViewMode.name.lowercase()
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            val view = androidx.compose.ui.platform.LocalView.current
            DisposableEffect(currentViewMode, useDarkTheme) {
                val window = (context as? android.app.Activity)?.window
                if (window != null && !view.isInEditMode) {
                    val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                    if (currentViewMode == AppViewMode.TERMINAL) {
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        window.navigationBarColor = android.graphics.Color.TRANSPARENT
                        insetsController.isAppearanceLightStatusBars = false
                        insetsController.isAppearanceLightNavigationBars = false
                    } else {
                        val bgArgb =
                            if (useDarkTheme) lytso.code.theme.ClaudeDarkBg.toArgb() else lytso.code.theme.ClaudeCream.toArgb()
                        // Transparent like Theme.kt: screens draw their own background behind it, and the
                        // sidebar + scrim can extend over it.
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        window.navigationBarColor = bgArgb
                        insetsController.isAppearanceLightStatusBars = !useDarkTheme
                        insetsController.isAppearanceLightNavigationBars = !useDarkTheme
                    }
                }
                onDispose {}
            }

            var showPermissionsDialog by remember {
                mutableStateOf(!lytso.code.ui.components.PermissionUtils.hasNotificationPermission(context))
            }

            var isInstalledState by remember {
                mutableStateOf(
                    lytso.code.data.local.LocalEnvironmentManager.isInstalled(
                        context
                    )
                )
            }
            var hasSkippedInstaller by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

            // Remote mode runs everything on the server: nothing to install on the phone
            val showFullScreenInstaller = !isInstalledState && !hasSkippedInstaller &&
                !lytso.code.data.preferences.AuthPreferences.currentRemoteMode

            GeminiTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showPermissionsDialog) {
                        lytso.code.ui.components.AppPermissionsDialog(
                            onDismissOrCompleted = {
                                showPermissionsDialog = false
                                lytso.code.data.service.LytsoCoderService.start(context)
                                lytso.code.data.local.LocalServerManager.autoStartOnAppLaunch(context)
                            }
                        )
                    } else if (showFullScreenInstaller) {
                        BackHandler {
                            hasSkippedInstaller = true
                        }
                        lytso.code.ui.settings.FullScreenLocalToolsInstaller(
                            authPreferences = chatViewModel.authPreferences,
                            onSkip = { hasSkippedInstaller = true },
                            onComplete = {
                                isInstalledState = true
                                hasSkippedInstaller = true
                                // before the first agent choice the server waits (the choice dialog starts it)
                                if (lytso.code.data.local.LocalServerManager.hasServerScript(context) &&
                                    lytso.code.data.agent.AgentPreferences.hasChosenSync(context)
                                ) {
                                    lytso.code.data.local.LocalServerManager.startServer(
                                        context,
                                        forceRestart = false
                                    )
                                }
                            }
                        )
                    } else {
                        BackHandler(enabled = currentViewMode != AppViewMode.CHAT && currentViewMode != AppViewMode.BROWSER) {
                            currentViewMode =
                                if (previousViewMode != currentViewMode && previousViewMode != AppViewMode.BROWSER) previousViewMode else AppViewMode.CHAT
                        }

                        val terminalSessions by lytso.code.data.local.LocalTerminalManager.sessions.collectAsState()
                        var hasEverOpenedTerminal by remember { mutableStateOf(false) }
                        if (currentViewMode == AppViewMode.TERMINAL) {
                            hasEverOpenedTerminal = true
                        }

                        val focusCoordinator = remember { lytso.code.ui.components.AppFocusCoordinator() }
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val imeInsets = androidx.compose.foundation.layout.WindowInsets.ime

                        var previousModeForFocus by remember { mutableStateOf(currentViewMode) }

                        LaunchedEffect(currentViewMode) {
                            if (previousModeForFocus != currentViewMode) {
                                val isKeyboardOpen = imeInsets.getBottom(density) > 0
                                focusCoordinator.onScreenLeaving(previousModeForFocus, isKeyboardOpen)
                                focusCoordinator.onScreenEntering(currentViewMode, isKeyboardOpen)
                                previousModeForFocus = currentViewMode
                            }
                        }

                        CompositionLocalProvider(
                            lytso.code.ui.components.LocalAppFocusCoordinator provides focusCoordinator
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Persistent Chat Screen (Never destroyed on toggle)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.CHAT
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else -20000f
                                        }
                                ) {
                                    ChatScreen(
                                        viewModel = chatViewModel,
                                        onNavigateToIde = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.IDE
                                        },
                                        onNavigateToTerminal = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        },
                                        onNavigateToBrowser = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.BROWSER
                                        },
                                        onNavigateToNotes = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.NOTES
                                        },
                                        onNavigateToFiles = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.FILES
                                        }
                                    )
                                }

                                // Persistent IDE Screen (Retains open tabs, daemon connection & state)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.IDE
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    IdeScreen(
                                        viewModel = chatViewModel,
                                        isVisible = currentViewMode == AppViewMode.IDE,
                                        onNavigateToChat = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.CHAT
                                        },
                                        onExecuteRunCommand = { cmd ->
                                            // Connect with terminal / chat execution
                                        },
                                        onNavigateToTerminal = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        },
                                        onNavigateToBrowser = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.BROWSER
                                        }
                                    )
                                }

                                // Persistent Terminal Screen (Retains terminal tmux sessions & state)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.TERMINAL
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    if (currentViewMode == AppViewMode.TERMINAL || (hasEverOpenedTerminal && terminalSessions.isNotEmpty())) {
                                        lytso.code.ui.components.LocalTerminalContent(
                                            onClose = {
                                                currentViewMode =
                                                    if (previousViewMode == AppViewMode.TERMINAL) AppViewMode.CHAT else previousViewMode
                                            }
                                        )
                                    }
                                }

                                // Persistent Web Preview Browser Screen (Retains open tabs, WebViews, state & navigation)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.BROWSER
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    BrowserScreen(
                                        isVisible = currentViewMode == AppViewMode.BROWSER,
                                        onClose = {
                                            currentViewMode =
                                                if (previousViewMode == AppViewMode.BROWSER) AppViewMode.CHAT else previousViewMode
                                        }
                                    )
                                }

                                // Persistent Notes Screen (Notes & to-do lists; open note and scroll survive switching away)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.NOTES
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    lytso.code.ui.notes.NotesScreen(
                                        isVisible = currentViewMode == AppViewMode.NOTES,
                                        onClose = {
                                            currentViewMode =
                                                if (previousViewMode == AppViewMode.NOTES) AppViewMode.CHAT else previousViewMode
                                        }
                                    )
                                }

                                // Persistent Files Screen (file explorer: folder, history and clipboard survive switching away)
                                var hasEverOpenedFiles by remember { mutableStateOf(false) }
                                if (currentViewMode == AppViewMode.FILES) hasEverOpenedFiles = true
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.FILES
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    if (hasEverOpenedFiles) {
                                        lytso.code.ui.files.FileExplorer(
                                            stateKey = "screen",
                                            // server / PC: a "This phone" tab too, to copy and move files between them
                                            allowPhoneTab = true,
                                            isVisible = currentViewMode == AppViewMode.FILES,
                                            onClose = {
                                                currentViewMode =
                                                    if (previousViewMode == AppViewMode.FILES) AppViewMode.CHAT else previousViewMode
                                            },
                                            onOpenedInIde = {
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.IDE
                                            },
                                            onOpenFolderInIde = { folder ->
                                                lytso.code.data.files.IdeFileLauncher.openFolderAsProject(folder)
                                                chatViewModel.onProjectChanged(folder)
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.IDE
                                            },
                                            onProjectCreated = { proj ->
                                                lytso.code.data.daemon.IdeDaemonManager.setActiveProject(proj)
                                                chatViewModel.onProjectChanged(proj.path)
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.IDE
                                            }
                                        )
                                    }
                                }

                                // Persistent Run Screen (a folder's commands and their live output; runs keep going on other screens)
                                var hasEverOpenedRun by remember { mutableStateOf(false) }
                                if (currentViewMode == AppViewMode.RUN) hasEverOpenedRun = true
                                LaunchedEffect(currentViewMode) {
                                    lytso.code.data.run.RunPanel.setVisible(currentViewMode == AppViewMode.RUN)
                                }
                                // the chat header's Run icon, the IDE's "Open Run panel" and the bubble ask for it
                                LaunchedEffect(Unit) {
                                    lytso.code.data.run.RunPanel.openRequests.collect {
                                        if (currentViewMode != AppViewMode.RUN) {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.RUN
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.RUN
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    if (hasEverOpenedRun) {
                                        lytso.code.ui.run.RunScreen(
                                            isVisible = currentViewMode == AppViewMode.RUN,
                                            onClose = {
                                                currentViewMode =
                                                    if (previousViewMode == AppViewMode.RUN) AppViewMode.CHAT else previousViewMode
                                            },
                                            onSendToChat = {
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.CHAT
                                            }
                                        )
                                    }
                                }

                                // Floating App Switcher Across Whole App (always on; renders on top of Chat, IDE, Terminal, Browser)
                                // Its Terminal needs a shell: SSH, server mode, or the local Linux tools (turned on here when
                                // installed but off); without any, it offers to install the Linux tools instead.
                                val switcherUseSsh by chatViewModel.useSshTerminal.collectAsState(initial = false)
                                val switcherToolsInstalled by chatViewModel.isLocalToolsInstalled.collectAsState(initial = false)
                                val switcherToolsEnabled by chatViewModel.isLocalToolsEnabled.collectAsState(initial = false)
                                var showSwitcherToolsInstall by remember { mutableStateOf(false) }
                                val openTerminalFromSwitcher: () -> Unit = {
                                    val ready = switcherUseSsh ||
                                        lytso.code.data.preferences.AuthPreferences.currentRemoteMode ||
                                        (switcherToolsInstalled && switcherToolsEnabled)
                                    when {
                                        ready -> {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        }
                                        switcherToolsInstalled -> {
                                            chatViewModel.setLocalToolsEnabled(true)
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        }
                                        else -> showSwitcherToolsInstall = true
                                    }
                                }
                                // Double-tap "back" target: the screen shown just before the current one, however we
                                // got here (switcher, close buttons, other in-app navigation). Tracked separately from
                                // previousViewMode because the close handlers don't update that.
                                var switcherBackTarget by remember { mutableStateOf<AppViewMode?>(null) }
                                var switcherLastSeenMode by remember { mutableStateOf(currentViewMode) }
                                LaunchedEffect(currentViewMode) {
                                    if (currentViewMode != switcherLastSeenMode) {
                                        switcherBackTarget = switcherLastSeenMode
                                        switcherLastSeenMode = currentViewMode
                                    }
                                }
                                lytso.code.ui.components.FloatingSwitcherWidget(
                                    currentViewMode = currentViewMode,
                                    orientation = floatingSwitcherOrientation,
                                    items = floatingSwitcherItems,
                                    autoCollapseTimeoutSec = floatingSwitcherAutoCollapseSec,
                                    savedPosXRatio = floatingSwitcherPosX,
                                    savedPosYRatio = floatingSwitcherPosY,
                                    onNavigateToChat = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.CHAT
                                    },
                                    onNavigateToIde = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.IDE
                                    },
                                    onNavigateToBrowser = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.BROWSER
                                    },
                                    onNavigateToTerminal = openTerminalFromSwitcher,
                                    onNavigateToNotes = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.NOTES
                                    },
                                    onNavigateToFiles = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.FILES
                                    },
                                    onNavigateToRun = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.RUN
                                    },
                                    onNavigateBack = {
                                        val target = switcherBackTarget
                                        when {
                                            target == null || target == currentViewMode -> Unit
                                            target == AppViewMode.TERMINAL -> openTerminalFromSwitcher()
                                            else -> {
                                                previousViewMode = currentViewMode
                                                currentViewMode = target
                                            }
                                        }
                                    },
                                    onPositionSaved = { x, y ->
                                        chatViewModel.saveFloatingSwitcherPosition(x, y)
                                    },
                                    onOrientationChange = { chatViewModel.setFloatingSwitcherOrientation(it) }
                                )
                                if (showSwitcherToolsInstall) {
                                    lytso.code.ui.settings.LocalToolsInstallDialog(
                                        authPreferences = chatViewModel.authPreferences,
                                        onOpenTerminal = {
                                            showSwitcherToolsInstall = false
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        },
                                        onDismiss = { showSwitcherToolsInstall = false }
                                    )
                                }

                                // Commands running: the screen stays on, and on other screens a bubble shows them (tap → Run)
                                lytso.code.ui.run.RunBubbleHost(isRunScreen = currentViewMode == AppViewMode.RUN)

                                // the built-in video player, full screen (from chat videos, tapped paths and Files)
                                lytso.code.ui.components.FullscreenVideoHost()

                                // Files shared or opened from other apps: ask what to do (open in the editor, save to a folder)
                                val incomingFiles by lytso.code.data.files.IncomingShare.pending.collectAsState()
                                val shareScope = rememberCoroutineScope()
                                incomingFiles?.let { items ->
                                    lytso.code.ui.files.IncomingShareDialog(
                                        items = items,
                                        onOpenInEditor = { item -> openIncomingInEditor(item) },
                                        onPreviewMarkdown = { item -> item.uri?.let { chatViewModel.loadMarkdownFromUri(context, it) } },
                                        onOpenSavedInIde = { path, name, size ->
                                            shareScope.launch {
                                                when (val r = lytso.code.data.files.IdeFileLauncher.open(path, name, size)) {
                                                    is lytso.code.data.files.IdeFileLauncher.Outcome.Opened -> {
                                                        previousViewMode = currentViewMode
                                                        currentViewMode = AppViewMode.IDE
                                                    }
                                                    is lytso.code.data.files.IdeFileLauncher.Outcome.Failed ->
                                                        lytso.code.ui.components.AppToastHelper.showToast(r.message, lytso.code.ui.components.ChatToastType.ERROR)
                                                    is lytso.code.data.files.IdeFileLauncher.Outcome.NeedsConfirm -> {
                                                        lytso.code.data.files.ExplorerNav.open("screen", path.substringBeforeLast('/'))
                                                        previousViewMode = currentViewMode
                                                        currentViewMode = AppViewMode.FILES
                                                    }
                                                }
                                            }
                                        },
                                        onShowInFiles = { folder ->
                                            lytso.code.data.files.ExplorerNav.open("screen", folder)
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.FILES
                                        },
                                        onDismiss = { lytso.code.data.files.IncomingShare.clear() }
                                    )
                                }

                                // Floating Video Player Overlay for active background/in-app playback controls
                                lytso.code.ui.components.FloatingVideoPlayerOverlay()

                                // Jarvis: voice orb (only when Jarvis is turned on in Settings)
                                lytso.code.ui.jarvis.JarvisOverlay()

                                // Real-time floating live diagnostics overlay (JVM Threads, OkHttp queues, connections, lag)
                                if (isFloatingDiagnosticsEnabled) {
                                    lytso.code.ui.components.FloatingDiagnosticsOverlay(
                                        onDismiss = {
                                            chatViewModel.setFloatingDiagnosticsEnabled(false)
                                        }
                                    )
                                }

                                // Floating Network Inspector Bubble (In-app draggable bubble across all screens)
                                if (isNetworkInspectorEnabled && isFloatingNetworkInspectorEnabled) {
                                    lytso.code.ui.components.FloatingNetworkInspectorBubble(
                                        onClick = { showNetworkInspectorDialog = true }
                                    )
                                }

                                // Gemmy: floating cartoon buddy (drag/fling, tap, double-tap, long-press menu; reacts to the agent working).
                                // Shown after its one-time intro was answered and while on in Settings → Appearance.
                                lytso.code.ui.components.GemmyOverlay(
                                    prefs = chatViewModel.authPreferences,
                                    agentWorking = chatViewModel.isCurrentChatActivelyRunning
                                )

                                // Global Network Inspector Dialog
                                if (showNetworkInspectorDialog) {
                                    lytso.code.ui.components.NetworkInspectorDialog(
                                        onDismiss = { showNetworkInspectorDialog = false }
                                    )
                                }

                                // Centralized In-App Update Dialog
                                pendingUpdateInfo?.let { updateInfo ->
                                    lytso.code.ui.updater.AppUpdateDialog(
                                        updateInfo = updateInfo,
                                        onDismiss = { pendingUpdateInfo = null }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        instanceRef = java.lang.ref.WeakReference(this)
        if (lytso.code.ui.components.PermissionUtils.hasNotificationPermission(this)) {
            lytso.code.data.service.LytsoCoderService.start(this)
            lytso.code.data.local.LocalServerManager.autoStartOnAppLaunch(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingFileIntent(intent)
        handleJarvisIntent(intent)
    }

    /**
     * One time after the update that made the separate terminal icon opt-in: if the icon is on (most users only got it by
     * confirming the old first-launch dialog, which had it preselected), switch it off and show that dialog again with
     * "hidden" preselected, so people who really use it can turn it back on with one tap.
     */
    private fun resetTerminalLauncherOnce() {
        val prefs = chatViewModel.authPreferences
        if (!prefs.takeTerminalLauncherResetSync()) return
        val launcher = lytso.code.data.preferences.TerminalLauncherManager
        if (!launcher.isLauncherEnabled(this)) return
        launcher.applyLauncherSetting(this, enabled = false, style = launcher.getLauncherStyle(this))
        lifecycleScope.launch {
            prefs.setTerminalLauncherEnabled(false)
            prefs.setHasSeenTerminalLauncherOnboarding(false)
        }
    }

    /** The Jarvis Quick Settings tile opened the app: start listening once the window is in front. */
    private fun handleJarvisIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(lytso.code.data.jarvis.JarvisTileService.EXTRA_TALK, false) != true) return
        intent.removeExtra(lytso.code.data.jarvis.JarvisTileService.EXTRA_TALK)
        window.decorView.postDelayed({
            if (lytso.code.data.jarvis.JarvisController.isAttached) lytso.code.data.jarvis.JarvisController.startListening()
        }, 700)
    }

    /**
     * Files opened with or shared to LytsoCoder. Shared conversations (.lytsocoder / .antigem) import directly; everything else (one file,
     * many files, or plain text / code) asks first: open in the code editor, preview, or save to a folder.
     */
    private fun handleIncomingFileIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val share = lytso.code.data.files.IncomingShare
        // links (OAuth callbacks, web pages) are not files
        val uris = share.urisOf(intent).filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isEmpty()) {
            if (action == Intent.ACTION_SEND) {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) share.offer(listOf(share.textItem(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))))
            }
            return
        }
        val items = uris.map { share.describe(this, it, intent.type) }
        if (items.size == 1) {
            val item = items.first()
            val lower = item.name.lowercase()
            if (lower.endsWith(".lytsocoder") || lower.endsWith(".antigem")) {
                chatViewModel.loadSharedConversationFromUri(this, item.uri!!)
                return
            }
            // a mini app package opened with / shared to "LytsoCoder" instead of "Mini app installer": install it
            if (lower.endsWith(".agapp") || intent.type == lytso.code.data.miniapps.MiniAppSigning.MIME) {
                startActivity(Intent(this, lytso.code.ui.miniapps.MiniAppInstallActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setDataAndType(item.uri, lytso.code.data.miniapps.MiniAppSigning.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                return
            }
            // exported trajectories can arrive without the .lytsocoder name: peek at the header
            val kind = item.kind
            if (kind == lytso.code.data.files.FileKind.DATA || kind == lytso.code.data.files.FileKind.TEXT ||
                kind == lytso.code.data.files.FileKind.BINARY
            ) {
                val header = try {
                    contentResolver.openInputStream(item.uri!!)?.bufferedReader(Charsets.UTF_8)?.use {
                        val chars = CharArray(150)
                        val read = it.read(chars, 0, 150)
                        if (read > 0) String(chars, 0, read) else ""
                    } ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (header.contains("\"lytsocoder_trajectory\"") || header.contains("\"antigem_trajectory\"")) {
                    chatViewModel.loadSharedConversationFromUri(this, item.uri!!)
                    return
                }
            }
        }
        share.offer(items)
    }

    /** "Open in code editor" from the share dialog. */
    private fun openIncomingInEditor(item: lytso.code.data.files.IncomingItem) {
        val uri = item.uri
        when {
            item.text != null -> {
                lytso.code.data.daemon.IdeDaemonManager.openOrSelectTab(
                    path = "shared-text:${System.currentTimeMillis()}/${item.name}",
                    name = item.name,
                    content = item.text,
                    isExternal = true
                )
                chatViewModel.requestViewMode("IDE")
            }
            // the IDE's image viewer reads a file path: keep a private copy for it
            item.kind == lytso.code.data.files.FileKind.IMAGE && uri != null -> lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val dir = java.io.File(cacheDir, "shared_open").apply { mkdirs() }
                    val dest = java.io.File(dir, item.name)
                    contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        lytso.code.data.daemon.IdeDaemonManager.openOrSelectTab(dest.absolutePath, item.name, "")
                        chatViewModel.requestViewMode("IDE")
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        lytso.code.ui.components.AppToastHelper.showToast("Could not open ${item.name}", lytso.code.ui.components.ChatToastType.ERROR)
                    }
                }
            }
            uri != null -> chatViewModel.openFileInIdeDirectly(this, uri)
        }
    }
}
