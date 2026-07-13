package dev.argus.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.argus.ui.model.AutomationDetailCallbacks
import dev.argus.ui.model.AutomationListCallbacks
import dev.argus.ui.model.AutomationListState
import dev.argus.ui.model.ChatCallbacks
import dev.argus.ui.model.ChatItem
import dev.argus.ui.model.EngineBanner
import dev.argus.ui.model.ExecutionLogCallbacks
import dev.argus.ui.model.ExecutionLogState
import dev.argus.ui.model.OnboardingCallbacks
import dev.argus.ui.model.SettingsCallbacks
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.model.StepKind
import dev.argus.ui.model.StepStatus
import dev.argus.ui.preview.Fixtures
import dev.argus.ui.screens.AutomationDetailScreen
import dev.argus.ui.screens.AutomationListScreen
import dev.argus.ui.screens.ChatScreen
import dev.argus.ui.screens.ExecutionLogScreen
import dev.argus.ui.screens.OnboardingScreen
import dev.argus.ui.screens.SettingsScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =============================================================================
// NavHost demo (M2 Task 12). Scaffold con bottom nav a 4 voci (Chat/Automazioni/
// Log/Sistema, icone §9, badge pending/needs-review) + NavHost che monta i 6
// schermi stateless su `Fixtures`. Dettaglio e Onboarding sono schermi PUSH
// (senza bottom nav), come da design §7.
//
// I callback fanno navigazione demo e aggiornano stato LOCALE (remember) per far
// "sentire vivo" il giro: chat con invio e DraftCard dopo latenza simulata (§4),
// toggle lista che riordina, filtri, apertura Dettaglio da chat/lista, Onboarding
// da Sistema. È una demo su dati finti — nessun engine/rete/Shizuku (P0-B).
//
// Le affordance di nav additive sono cablate qui: la CTA empty-state della Lista
// (onEmptyCta → Chat), il tap del banner salute (onBannerTap → Sistema), il back
// del Dettaglio (onBack → popBackStack) e "Invia ora" del Log (onSendNow, demo).
// =============================================================================

private object Routes {
    const val CHAT = "chat"
    const val LIST = "list"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{id}"
    const val ONBOARDING = "onboarding"
    fun detail(id: String) = "detail/$id"
}

private data class TopDest(val route: String, val label: String, val icon: ImageVector)

/** Ordine bottom nav (design §7/§9): Chat · Automazioni · Log · Sistema. */
private val TopDestinations = listOf(
    TopDest(Routes.CHAT, "Chat", Icons.Rounded.ChatBubble),
    TopDest(Routes.LIST, "Automazioni", Icons.Rounded.Bolt),
    TopDest(Routes.LOG, "Log", Icons.Rounded.History),
    TopDest(Routes.SETTINGS, "Sistema", Icons.Rounded.Tune),
)
private val TopLevelRoutes = TopDestinations.map { it.route }.toSet()

@Composable
fun ArgusNavHost() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showToast: (String) -> Unit = { msg ->
        scope.launch { snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short) }
    }
    // Cambia tab top-level preservando lo stato (stesso pattern della bottom nav).
    val switchTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // --- Stato demo "vivo", persistente attraverso i cambi di tab ---
    var chat by remember { mutableStateOf(Fixtures.chatEmpty) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var rows by remember { mutableStateOf(Fixtures.listRows) }
    var listFilter by remember { mutableStateOf(StatusFilter.ALL) }
    var logState by remember { mutableStateOf(Fixtures.logState) }
    var onb by remember { mutableStateOf(Fixtures.onboardingState) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TopLevelRoutes

    val pendingCount = rows.count { it.status == StatusBadge.PENDING_APPROVAL }
    val needsReviewCount = rows.count { it.status == StatusBadge.NEEDS_REVIEW }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                ArgusBottomBar(
                    currentRoute = currentRoute,
                    pendingCount = pendingCount,
                    needsReviewCount = needsReviewCount,
                    onSelect = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            modifier = Modifier.padding(innerPadding),
        ) {
            // --- Chat (§6.1) ---
            composable(Routes.CHAT) {
                ChatScreen(
                    state = chat,
                    callbacks = object : ChatCallbacks {
                        override fun onInputChange(text: String) { chat = chat.copy(input = text) }
                        override fun onSend() {
                            val text = chat.input.trim()
                            if (text.isEmpty() || chat.sending) return
                            chat = chat.copy(
                                items = chat.items + ChatItem.UserMessage(text, "ora"),
                                input = "",
                                sending = true,
                                sendingElapsedSec = 0,
                            )
                            // §4: attesa one-shot simulata (nessuno streaming), poi DraftCard canned.
                            sendJob = scope.launch {
                                for (t in 1..3) {
                                    delay(650)
                                    chat = chat.copy(sendingElapsedSec = t)
                                }
                                chat = chat.copy(
                                    items = chat.items + Fixtures.cannedAssistantReply + Fixtures.cannedDraft(),
                                    sending = false,
                                    sendingElapsedSec = null,
                                )
                            }
                        }
                        override fun onCancelPending() {
                            sendJob?.cancel()
                            chat = chat.copy(sending = false, sendingElapsedSec = null)
                        }
                        override fun onOpenDraft(draftId: String) {
                            navController.navigate(Routes.detail(draftId))
                        }
                        override fun onRetry() { showToast("Riconnessione a Hermes…") }
                    },
                )
            }

            // --- Automazioni · lista (§6.2) ---
            composable(Routes.LIST) {
                val visibleRows = rows.filter { row ->
                    when (listFilter) {
                        StatusFilter.ALL -> true
                        StatusFilter.ARMED -> row.status == StatusBadge.ARMED
                        StatusFilter.PENDING -> row.status == StatusBadge.PENDING_APPROVAL
                        StatusFilter.DISABLED -> row.status == StatusBadge.DISABLED
                        StatusFilter.NEEDS_REVIEW -> row.status == StatusBadge.NEEDS_REVIEW
                    }
                }
                AutomationListScreen(
                    state = AutomationListState(
                        rows = visibleRows,
                        filter = listFilter,
                        // Demo: banner non-NONE così onBannerTap → Sistema è dimostrabile.
                        banner = EngineBanner.BATTERY_NOT_EXEMPT,
                        loading = false,
                    ),
                    callbacks = object : AutomationListCallbacks {
                        override fun onOpen(id: String) { navController.navigate(Routes.detail(id)) }
                        override fun onToggleEnabled(id: String, enabled: Boolean) {
                            rows = rows.map {
                                if (it.id == id) {
                                    it.copy(
                                        enabled = enabled,
                                        status = if (enabled) StatusBadge.ARMED else StatusBadge.DISABLED,
                                    )
                                } else {
                                    it
                                }
                            }
                        }
                        override fun onFilter(f: StatusFilter) { listFilter = f }
                        // Empty-state "Vai in chat" → cambia tab su Chat (nav host-owned).
                        override fun onEmptyCta() { switchTab(Routes.CHAT) }
                        // Banner salute → Sistema (nav host-owned).
                        override fun onBannerTap() { switchTab(Routes.SETTINGS) }
                    },
                )
            }

            // --- Log esecuzioni (§6.4) ---
            composable(Routes.LOG) {
                ExecutionLogScreen(
                    state = logState,
                    callbacks = object : ExecutionLogCallbacks {
                        override fun onExpand(id: String) { /* espansione gestita nello schermo (effimera) */ }
                        override fun onClearFilter() { logState = logState.copy(filterAutomationName = null) }
                        override fun onOpenAutomation(id: String) {
                            // Scopo reale (P0-B): apre l'automazione dalla riga di log.
                            showToast("Apro l'automazione")
                        }
                        override fun onSendNow(logId: String) {
                            // Riga DEFERRED "Invia ora" (E13): consegna manuale simulata (demo).
                            showToast("Risposta inviata")
                        }
                    },
                )
            }

            // --- Sistema · settings (§6.5) ---
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    state = Fixtures.settingsAllGreen,
                    callbacks = object : SettingsCallbacks {
                        override fun onEditBridgeUrl(url: String) { showToast("Modifica indirizzo bridge") }
                        override fun onTestConnection() { showToast("Hermes raggiungibile · 14 s") }
                        override fun onOpenShizukuFix() { showToast("Apro la guida Shizuku") }
                        override fun onOpenBatteryFix() { showToast("Apro le impostazioni batteria") }
                        override fun onOpenNotificationAccessFix() { showToast("Apro l'accesso alle notifiche") }
                        override fun onOpenLocationFix() { showToast("Apro i permessi di posizione") }
                        override fun onRemoveContact(conversationId: String) { showToast("Contatto rimosso dalla whitelist") }
                        override fun onAddContact() { showToast("Scegli un contatto da aggiungere") }
                        override fun onBudgetChange(maxPerHour: Int) { showToast("Budget aggiornato: $maxPerHour/ora") }
                        override fun onRerunOnboarding() {
                            onb = Fixtures.onboardingState // reset del wizard
                            navController.navigate(Routes.ONBOARDING)
                        }
                    },
                )
            }

            // --- Automazione · dettaglio (§6.3, PUSH) ---
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                val detail = id?.let { Fixtures.detailsById[it] } ?: Fixtures.detailsById.values.first()
                AutomationDetailScreen(
                    state = detail,
                    callbacks = object : AutomationDetailCallbacks {
                        override fun onArm() {
                            showToast("Regola armata: ${detail.name}")
                            navController.popBackStack()
                        }
                        override fun onReject() {
                            showToast("Bozza rifiutata")
                            navController.popBackStack()
                        }
                        override fun onSetEnabled(enabled: Boolean) {
                            showToast(if (enabled) "Regola attivata" else "Regola disattivata")
                        }
                        override fun onDelete() {
                            showToast("Regola eliminata: ${detail.name}")
                            navController.popBackStack()
                        }
                        override fun onAskEdit() {
                            showToast("Apro la chat per modificare la regola")
                            navController.navigate(Routes.CHAT) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        override fun onRunNow() { showToast("Esecuzione di prova avviata") }
                        override fun onOpenFullLog() {
                            navController.navigate(Routes.LOG) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        override fun onBack() { navController.popBackStack() }
                    },
                )
            }

            // --- Onboarding / permessi (§6.6, PUSH da Sistema → "Ripeti configurazione") ---
            composable(Routes.ONBOARDING) {
                // Avanza il wizard: marca lo step corrente e passa al successivo (o conclude).
                fun advance(markCurrent: StepStatus) {
                    val i = onb.currentIndex
                    val steps = onb.steps.toMutableList()
                    steps[i] = steps[i].copy(status = markCurrent)
                    if (i >= steps.lastIndex) {
                        showToast("Configurazione completata")
                        navController.popBackStack()
                        return
                    }
                    steps[i + 1] = steps[i + 1].copy(status = StepStatus.IN_PROGRESS)
                    val canFinish = steps[0].status == StepStatus.DONE && steps[1].status == StepStatus.DONE
                    onb = onb.copy(steps = steps, currentIndex = i + 1, canFinish = canFinish)
                }
                OnboardingScreen(
                    state = onb,
                    callbacks = object : OnboardingCallbacks {
                        override fun onStepCta(kind: StepKind) { advance(StepStatus.DONE) }
                        override fun onSkip(kind: StepKind) { advance(StepStatus.SKIPPED) }
                        override fun onNext() { advance(StepStatus.DONE) }
                        override fun onBack() {
                            if (onb.currentIndex > 0) {
                                onb = onb.copy(currentIndex = onb.currentIndex - 1)
                            } else {
                                navController.popBackStack()
                            }
                        }
                        override fun onFinish() {
                            showToast("Configurazione completata")
                            navController.popBackStack()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ArgusBottomBar(
    currentRoute: String?,
    pendingCount: Int,
    needsReviewCount: Int,
    onSelect: (String) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        TopDestinations.forEach { dest ->
            NavigationBarItem(
                selected = currentRoute == dest.route,
                onClick = { onSelect(dest.route) },
                icon = {
                    when {
                        // Badge conteggio pending su Automazioni (§9).
                        dest.route == Routes.LIST && pendingCount > 0 ->
                            BadgedBox(badge = { Badge { Text("$pendingCount") } }) {
                                Icon(dest.icon, contentDescription = dest.label)
                            }
                        // "!" su Sistema se c'è una regola NEEDS_REVIEW (§9).
                        dest.route == Routes.SETTINGS && needsReviewCount > 0 ->
                            BadgedBox(badge = { Badge { Text("!") } }) {
                                Icon(dest.icon, contentDescription = dest.label)
                            }
                        // Puntino su Chat (§9).
                        dest.route == Routes.CHAT ->
                            BadgedBox(badge = { Badge() }) {
                                Icon(dest.icon, contentDescription = dest.label)
                            }
                        else -> Icon(dest.icon, contentDescription = dest.label)
                    }
                },
                label = { Text(dest.label) },
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
