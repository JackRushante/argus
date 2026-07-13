package dev.argus.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.argus.automation.vm.ArgusAppViewModel
import dev.argus.automation.vm.AutomationDetailViewModel
import dev.argus.automation.vm.AutomationListViewModel
import dev.argus.automation.vm.ChatViewModel
import dev.argus.automation.vm.DetailEvent
import dev.argus.automation.vm.ExecutionLogViewModel
import dev.argus.automation.vm.OnboardingEvent
import dev.argus.automation.vm.OnboardingViewModel
import dev.argus.automation.vm.SettingsViewModel
import dev.argus.ui.model.AutomationDetailCallbacks
import dev.argus.ui.model.AutomationListCallbacks
import dev.argus.ui.model.ChatCallbacks
import dev.argus.ui.model.ExecutionLogCallbacks
import dev.argus.ui.model.OnboardingCallbacks
import dev.argus.ui.model.SettingsCallbacks
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.model.StepKind
import dev.argus.ui.screens.AutomationDetailScreen
import dev.argus.ui.screens.AutomationListScreen
import dev.argus.ui.screens.ChatScreen
import dev.argus.ui.screens.ExecutionLogScreen
import dev.argus.ui.screens.OnboardingScreen
import dev.argus.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private object Routes {
    const val CHAT = "chat"
    const val LIST = "list"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{id}"
    const val ONBOARDING = "onboarding"
    const val LOG_PATTERN = "$LOG?automationId={automationId}&automationName={automationName}"

    fun detail(id: String): String = "detail/${Uri.encode(id)}"

    fun log(automationId: String, automationName: String): String =
        "$LOG?automationId=${Uri.encode(automationId)}&automationName=${Uri.encode(automationName)}"
}

private data class TopDestination(val route: String, val label: String, val icon: ImageVector)

private val TopDestinations = listOf(
    TopDestination(Routes.CHAT, "Chat", Icons.Rounded.ChatBubble),
    TopDestination(Routes.LIST, "Automazioni", Icons.Rounded.Bolt),
    TopDestination(Routes.LOG, "Log", Icons.Rounded.History),
    TopDestination(Routes.SETTINGS, "Sistema", Icons.Rounded.Tune),
)
private val TopLevelRoutes = TopDestinations.mapTo(hashSetOf()) { it.route }

@Composable
fun ArgusNavHost() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val appViewModel: ArgusAppViewModel = hiltViewModel()
    val chatViewModel: ChatViewModel = hiltViewModel()
    val listViewModel: AutomationListViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    val onboardingCompleted by appViewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()

    var showContactEditor by rememberSaveable { mutableStateOf(false) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destinationRoute = backStackEntry?.destination?.route
    val currentBaseRoute = destinationRoute?.substringBefore('?')?.substringBefore('/')
    val showBottomBar = onboardingCompleted && currentBaseRoute in TopLevelRoutes
    LaunchedEffect(onboardingCompleted, destinationRoute) {
        if (!onboardingCompleted && destinationRoute != null &&
            currentBaseRoute != Routes.ONBOARDING
        ) {
            navController.navigate(Routes.ONBOARDING) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        } else if (onboardingCompleted && currentBaseRoute == Routes.CHAT) {
            chatViewModel.refreshHealth()
        }
    }
    var unreadChat by rememberSaveable { mutableStateOf(false) }
    var observedChatItems by rememberSaveable { mutableIntStateOf(chatState.items.size) }
    LaunchedEffect(chatState.items.size, currentBaseRoute) {
        when {
            currentBaseRoute == Routes.CHAT -> unreadChat = false
            chatState.items.size > observedChatItems -> unreadChat = true
        }
        observedChatItems = chatState.items.size
    }

    val showMessage: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.showSnackbar(text, duration = SnackbarDuration.Short)
        }
    }
    val openIntent: (Intent) -> Unit = { intent ->
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { showMessage("Impostazione di sistema non disponibile.") }
    }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissionRefresh += 1
        settingsViewModel.refresh()
    }
    val requestNotificationAccess = {
        val runtimePermissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (runtimePermissionMissing) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            openIntent(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        } else {
            permissionRefresh += 1
            settingsViewModel.refresh()
        }
    }
    val openBatterySettings = {
        // L'esenzione diretta appartiene a P1 ed è policy-sensitive sul Play Store.
        // In P0-B apriamo soltanto la pagina di sistema, lasciando la scelta esplicita all'utente.
        openIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        Unit
    }
    val openLocationSettings = {
        openIntent(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRefresh += 1
        openLocationSettings()
    }
    val requestLocationAccess = {
        val foregroundGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (foregroundGranted) {
            openLocationSettings()
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }
    val openShizukuManager = {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) {
            openIntent(launch)
        } else {
            openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")))
        }
    }

    fun switchTop(route: String) {
        if (route == Routes.CHAT) unreadChat = false
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = '?' !in route
        }
    }

    MessageEffect(listViewModel.messages, snackbarHostState)
    MessageEffect(settingsViewModel.messages, snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                ArgusBottomBar(
                    currentRoute = currentBaseRoute,
                    pendingCount = listState.pendingCount,
                    needsReviewCount = listState.needsReviewCount,
                    chatUnread = unreadChat,
                    onSelect = ::switchTop,
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (onboardingCompleted) Routes.CHAT else Routes.ONBOARDING,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.CHAT) {
                ChatScreen(
                    state = chatState,
                    callbacks = object : ChatCallbacks {
                        override fun onInputChange(text: String) = chatViewModel.onInputChange(text)
                        override fun onSend() = chatViewModel.onSend()
                        override fun onCancelPending() = chatViewModel.onCancelPending()
                        override fun onOpenDraft(draftId: String) {
                            navController.navigate(Routes.detail(draftId))
                        }
                        override fun onRetry() = chatViewModel.onRetry()
                    },
                    modifier = Modifier.testTag("screen_chat"),
                )
            }

            composable(Routes.LIST) {
                AutomationListScreen(
                    state = listState,
                    callbacks = object : AutomationListCallbacks {
                        override fun onOpen(id: String) {
                            navController.navigate(Routes.detail(id))
                        }
                        override fun onToggleEnabled(id: String, enabled: Boolean) {
                            listViewModel.onToggleEnabled(id, enabled)
                        }
                        override fun onFilter(f: StatusFilter) = listViewModel.onFilter(f)
                        override fun onEmptyCta() = switchTop(Routes.CHAT)
                        override fun onBannerTap() = switchTop(Routes.SETTINGS)
                    },
                    modifier = Modifier.testTag("screen_list"),
                )
            }

            composable(
                route = Routes.LOG_PATTERN,
                arguments = listOf(
                    navArgument("automationId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("automationName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                val viewModel: ExecutionLogViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                MessageEffect(viewModel.messages, snackbarHostState)
                ExecutionLogScreen(
                    state = state,
                    callbacks = object : ExecutionLogCallbacks {
                        override fun onExpand(id: String) = viewModel.onExpand(id)
                        override fun onClearFilter() = viewModel.onClearFilter()
                        override fun onOpenAutomation(id: String) {
                            navController.navigate(Routes.detail(id))
                        }
                        override fun onSendNow(logId: String) = viewModel.onSendNow(logId)
                    },
                    modifier = Modifier.testTag("screen_log"),
                )
            }

            composable(Routes.SETTINGS) {
                RefreshOnResume(settingsViewModel::refresh)
                LaunchedEffect(permissionRefresh) { settingsViewModel.refresh() }
                SettingsScreen(
                    state = settingsState,
                    callbacks = object : SettingsCallbacks {
                        override fun onEditBridgeUrl(url: String) = Unit
                        override fun onSaveBridge(url: String, bearerToken: String?) {
                            settingsViewModel.saveBridge(url, bearerToken)
                        }
                        override fun onTestConnection() = settingsViewModel.testConnection()
                        override fun onOpenShizukuFix() {
                            if (settingsState.shizuku == ShizukuStatus.RUNNING_NOT_AUTHORIZED) {
                                settingsViewModel.requestShizukuPermission()
                            } else {
                                openShizukuManager()
                            }
                        }
                        override fun onOpenBatteryFix() {
                            openBatterySettings()
                        }
                        override fun onOpenNotificationAccessFix() = requestNotificationAccess()
                        override fun onOpenLocationFix() = requestLocationAccess()
                        override fun onRemoveContact(conversationId: String) {
                            settingsViewModel.removeContact(conversationId)
                        }
                        override fun onAddContact() {
                            showContactEditor = true
                        }
                        override fun onBudgetChange(maxPerHour: Int) {
                            settingsViewModel.onBudgetChange(maxPerHour)
                        }
                        override fun onRevokePrivacy() = settingsViewModel.revokePrivacy()
                        override fun onRerunOnboarding() {
                            navController.navigate(Routes.ONBOARDING)
                        }
                    },
                    modifier = Modifier.testTag("screen_settings"),
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                val viewModel: AutomationDetailViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is DetailEvent.Close -> {
                                navController.popBackStack()
                                showMessage(event.message)
                            }
                            is DetailEvent.Message -> showMessage(event.text)
                            is DetailEvent.OpenChat -> {
                                chatViewModel.prefillEdit(
                                    text = event.prompt,
                                    automationId = event.automationId,
                                    automationFingerprint = event.automationFingerprint,
                                    draftId = event.draftId,
                                    draftRevision = event.draftRevision,
                                    baseDraft = event.baseDraft,
                                )
                                switchTop(Routes.CHAT)
                            }
                        }
                    }
                }
                when {
                    state.loading -> LoadingScreen()
                    state.missing || state.detail == null -> MissingDetailScreen {
                        navController.popBackStack()
                    }
                    else -> {
                        val detail = requireNotNull(state.detail)
                        AutomationDetailScreen(
                            state = detail,
                            callbacks = object : AutomationDetailCallbacks {
                                override fun onArm() = viewModel.onArm()
                                override fun onReject() = viewModel.onReject()
                                override fun onSetEnabled(enabled: Boolean) {
                                    viewModel.onSetEnabled(enabled)
                                }
                                override fun onDelete() = viewModel.onDelete()
                                override fun onAskEdit() = viewModel.onAskEdit()
                                override fun onRunNow() = viewModel.onRunNow()
                                override fun onOpenFullLog() {
                                    state.automationId?.let {
                                        switchTop(Routes.log(it, detail.name))
                                    }
                                }
                                override fun onBack() {
                                    navController.popBackStack()
                                }
                            },
                            modifier = Modifier.testTag("screen_detail"),
                        )
                    }
                }
            }

            composable(Routes.ONBOARDING) {
                val viewModel: OnboardingViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                RefreshOnResume(viewModel::refresh)
                LaunchedEffect(permissionRefresh) { viewModel.refresh() }
                BackHandler { viewModel.onBack() }
                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is OnboardingEvent.Message -> showMessage(event.text)
                            OnboardingEvent.Close, OnboardingEvent.Complete -> {
                                if (!navController.popBackStack()) {
                                    navController.navigate(Routes.CHAT) {
                                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                                    }
                                }
                            }
                        }
                    }
                }
                OnboardingScreen(
                    state = state,
                    callbacks = object : OnboardingCallbacks {
                        override fun onStepCta(kind: StepKind) {
                            when (kind) {
                                StepKind.WELCOME_PRIVACY -> viewModel.acceptPrivacy()
                                StepKind.BRAIN_CONFIG -> Unit // Il dialog è gestito dallo schermo.
                                StepKind.SHIZUKU -> {
                                    when (viewModel.currentShizukuStatus()) {
                                        dev.argus.shizuku.ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED ->
                                            viewModel.requestShizukuPermission()
                                        else -> openShizukuManager()
                                    }
                                }
                                StepKind.NOTIFICATION_ACCESS -> requestNotificationAccess()
                                StepKind.BATTERY_OEM -> openBatterySettings()
                                StepKind.BACKGROUND_LOCATION -> requestLocationAccess()
                            }
                        }
                        override fun onSaveBridge(url: String, bearerToken: String?) {
                            viewModel.saveBridge(url, bearerToken)
                        }
                        override fun onSkip(kind: StepKind) = viewModel.onSkip(kind)
                        override fun onNext() = viewModel.onNext()
                        override fun onBack() = viewModel.onBack()
                        override fun onFinish() = viewModel.onFinish()
                    },
                    modifier = Modifier.testTag("screen_onboarding"),
                )
            }
        }
    }

    if (showContactEditor) {
        ContactEditorDialog(
            onDismiss = { showContactEditor = false },
            onSave = { name, id ->
                settingsViewModel.addContact(name, id)
                showContactEditor = false
            },
        )
    }
}

@Composable
private fun MessageEffect(messages: Flow<String>, host: SnackbarHostState) {
    LaunchedEffect(messages, host) {
        messages.collect { host.showSnackbar(it, duration = SnackbarDuration.Short) }
    }
}

@Composable
private fun RefreshOnResume(onResume: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, onResume) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MissingDetailScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Regola non disponibile", style = MaterialTheme.typography.titleMedium)
            Text("Potrebbe essere stata eliminata o sostituita da una revisione più recente.")
            Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Indietro")
            }
        }
    }
}

@Composable
private fun ContactEditorDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    // Il conversation ID è PII: l'editor effimero non lo salva nel Bundle di Activity.
    var name by remember { mutableStateOf("") }
    var conversationId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi alla whitelist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Inserisci il nome visibile e l'identificatore stabile della conversazione.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nome") },
                )
                OutlinedTextField(
                    value = conversationId,
                    onValueChange = { conversationId = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Conversation ID") },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), conversationId.trim()) },
                enabled = name.isNotBlank() && conversationId.isNotBlank(),
            ) { Text("Aggiungi") }
        },
    )
}

@Composable
private fun ArgusBottomBar(
    currentRoute: String?,
    pendingCount: Int,
    needsReviewCount: Int,
    chatUnread: Boolean,
    onSelect: (String) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        TopDestinations.forEach { destination ->
            NavigationBarItem(
                modifier = Modifier.testTag("nav_${destination.route}"),
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination.route) },
                icon = {
                    when {
                        destination.route == Routes.LIST && pendingCount > 0 ->
                            BadgedBox(badge = { Badge { Text("$pendingCount") } }) {
                                Icon(destination.icon, contentDescription = destination.label)
                            }
                        destination.route == Routes.SETTINGS && needsReviewCount > 0 ->
                            BadgedBox(badge = { Badge { Text("!") } }) {
                                Icon(destination.icon, contentDescription = destination.label)
                            }
                        destination.route == Routes.CHAT && chatUnread ->
                            BadgedBox(badge = { Badge() }) {
                                Icon(destination.icon, contentDescription = destination.label)
                            }
                        else -> Icon(destination.icon, contentDescription = destination.label)
                    }
                },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
