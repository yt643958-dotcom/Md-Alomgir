package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ModuleRoute
import com.example.data.UserProfileRepository
import com.example.security.configureAppCheck
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutOperator
import com.example.ui.chat.TerminalChatScreen
import com.example.ui.chat.TerminalChatViewModel
import com.example.ui.components.CrtScanlineOverlay
import com.example.ui.components.RiadNavigationDrawerContent
import com.example.ui.components.RiadSplashOverlay
import com.example.ui.components.RiadTopBar
import com.example.ui.crypto.CryptoHashScreen
import com.example.ui.scanner.VulnScannerScreen
import com.example.ui.scanner.VulnScannerViewModel
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.RiadAiTheme
import com.example.ui.theme.VoidBackground
import com.example.ui.voice.LiveVoiceScreen
import com.example.ui.voice.LiveVoiceViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory: Configure Firebase App Check with launch intent before UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            RiadAiTheme {
                RiadAuthGate()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
    }
}

@Composable
private fun RiadAuthGate() {
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var refreshTick by remember { mutableStateOf(0) }
    val user = currentUser

    if (user == null) {
        AuthScreen(
            onAuthSuccess = { refreshTick++ }
        )
    } else {
        val context = LocalContext.current
        LaunchedEffect(user.uid, refreshTick) {
            runCatching {
                UserProfileRepository(context).upsertSignedInOperator(
                    email = user.email.orEmpty(),
                    displayName = user.displayName.orEmpty(),
                    photoUrl = user.photoUrl?.toString()
                )
            }
        }
        key(user.uid) {
            RiadAiAppShell(currentUser = user)
        }
    }
}

@Composable
private fun RiadAiAppShell(currentUser: FirebaseUser) {
    var currentRoute by rememberSaveable { mutableStateOf(ModuleRoute.TERMINAL_CHAT) }
    var showSplash by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val chatViewModel: TerminalChatViewModel = viewModel()
    val liveVoiceViewModel: LiveVoiceViewModel = viewModel()
    val vulnScannerViewModel: VulnScannerViewModel = viewModel()

    val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val liveState by liveVoiceViewModel.uiState.collectAsStateWithLifecycle()
    val scannerState by vulnScannerViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (showSplash) {
            delay(1500)
            showSplash = false
        }
    }

    // Return to primary Terminal Chat when pressing system back on secondary modules
    BackHandler(enabled = currentRoute != ModuleRoute.TERMINAL_CHAT) {
        currentRoute = ModuleRoute.TERMINAL_CHAT
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBackground)
    ) {
        val isExpandedScreen = maxWidth >= 840.dp

        val mainContent: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VoidBackground)
            ) {
                RiadTopBar(
                    currentRoute = currentRoute,
                    showMenuButton = !isExpandedScreen,
                    ttsEnabled = chatState.ttsAutoBriefing,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                    onToggleTts = chatViewModel::toggleTtsAutoBriefing
                )

                Crossfade(
                    targetState = currentRoute,
                    label = "module_crossfade",
                    modifier = Modifier.weight(1f)
                ) { route ->
                    when (route) {
                        ModuleRoute.TERMINAL_CHAT -> {
                            TerminalChatScreen(
                                uiState = chatState,
                                onUpdateDraft = chatViewModel::updateDraft,
                                onSendMessage = chatViewModel::sendMessage,
                                onStopGeneration = chatViewModel::stopGeneration,
                                onClearSession = chatViewModel::clearSession,
                                onSpeakMessage = chatViewModel::speakMessage,
                                onAppendTranscript = chatViewModel::appendVoiceTranscript,
                                onToggleSttLanguage = chatViewModel::toggleSttLanguage,
                                onDismissError = chatViewModel::dismissError
                            )
                        }
                        ModuleRoute.LIVE_VOICE -> {
                            LiveVoiceScreen(
                                uiState = liveState,
                                onConnect = liveVoiceViewModel::connectAndStartConversation,
                                onDisconnect = liveVoiceViewModel::disconnect,
                                onToggleMute = liveVoiceViewModel::toggleMute,
                                onUpdateDirective = liveVoiceViewModel::updateTextDirective,
                                onSendDirective = liveVoiceViewModel::sendTextDirective,
                                onClearTranscripts = liveVoiceViewModel::clearTranscripts
                            )
                        }
                        ModuleRoute.VULN_SCANNER -> {
                            VulnScannerScreen(
                                uiState = scannerState,
                                onUpdateSource = vulnScannerViewModel::updateSourceInput,
                                onSelectDepth = vulnScannerViewModel::selectDepth,
                                onLoadPreset = vulnScannerViewModel::loadPreset,
                                onRunScan = vulnScannerViewModel::runStaticAnalysis
                            )
                        }
                        ModuleRoute.CRYPTO_HASH -> {
                            CryptoHashScreen()
                        }
                    }
                }
            }
        }

        if (isExpandedScreen) {
            PermanentNavigationDrawer(
                drawerContent = {
                    RiadNavigationDrawerContent(
                        currentRoute = currentRoute,
                        onSelectRoute = { currentRoute = it },
                        operatorEmail = currentUser.email,
                        operatorName = currentUser.displayName,
                        onSignOut = { signOutOperator(credentialManager, scope) }
                    )
                }
            ) {
                mainContent()
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = Color.Black.copy(alpha = 0.72f),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = ElevatedSurface
                    ) {
                        RiadNavigationDrawerContent(
                            currentRoute = currentRoute,
                            onSelectRoute = { route ->
                                currentRoute = route
                                scope.launch { drawerState.close() }
                            },
                            operatorEmail = currentUser.email,
                            operatorName = currentUser.displayName,
                            onSignOut = {
                                scope.launch { drawerState.close() }
                                signOutOperator(credentialManager, scope)
                            }
                        )
                    }
                }
            ) {
                mainContent()
            }
        }

        // Subtle CRT Scanline Overlay (non-interactive Canvas)
        CrtScanlineOverlay()

        // Terminal Boot-up Splash Overlay
        RiadSplashOverlay(visible = showSplash)
    }
}
