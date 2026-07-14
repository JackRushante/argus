package dev.argus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.engine.safety.Severity
import dev.argus.ui.components.LatencyIndicator
import dev.argus.ui.components.RuleCard
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.ChatCallbacks
import dev.argus.ui.model.ChatError
import dev.argus.ui.model.ChatItem
import dev.argus.ui.model.ChatState
import dev.argus.ui.model.DraftCardStatus
import dev.argus.ui.model.NoticeKind
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.UiWarning
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic
import dev.argus.ui.theme.RolePair

// =============================================================================
// Schermo Chat (handoff §6.1, design §7.1). L'LLM compila le regole; la chat è
// il punto d'ingresso. Vincoli cardine:
//  - §4 NIENTE STREAMING: attesa one-shot lunga e onesta (LatencyIndicator con
//    tempo trascorso), nessun cursore/typing; input disabilitato durante l'attesa.
//  - §3 l'ARM NON avviene qui: la DraftCard porta solo "Rivedi e approva →" che
//    apre il Dettaglio (onOpenDraft). Nessun percorso di armamento in chat.
//  - §5.4 la DraftCard generativa porta i badge generativa + "esce verso il cloud"
//    (via RuleCard); ERROR nelle issues ⇒ fascia "Non armabile: <motivo>".
// Stateless: solo (state, callbacks). L'overflow in header è nav host-owned.
// =============================================================================

/**
 * Esempi cliccabili dell'empty state. Rivisti su feedback di Lorenzo (2026-07-14): niente
 * riferimenti personali e SOLO regole armabili con le capability correnti (trigger time o
 * notification — il geofence del prototipo §1a torna quando P2 ne abilita la registrazione).
 */
private val ChatSuggestions = listOf(
    "Ogni giorno alle 8 togli il Non disturbare e alza la suoneria.",
    "Dopo le 23 controlla la suoneria e metti Non disturbare.",
    "Se un contatto in whitelist scrive su WhatsApp tra le 9 e le 18, rispondi tu che sono occupato.",
)

@Composable
fun ChatScreen(
    state: ChatState,
    callbacks: ChatCallbacks,
    modifier: Modifier = Modifier,
) {
    val brainDown = state.brainReachable == false
    val inputEnabled = !state.sending && !brainDown

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                onClearConversation = callbacks::onClearConversation,
                onCheckConnection = callbacks::onCheckConnection,
            )

            // Zona messaggi (o empty state coi suggerimenti).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.items.isEmpty() && !state.sending) {
                    ChatEmptyState(callbacks)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.items) { item -> ChatItemRow(item, callbacks) }
                    }
                }
            }

            // Zona stato/notice + attesa, sopra l'input.
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    brainDown -> BrainDownBanner(callbacks::onRetry)
                    state.error != null && !state.sending -> ChatErrorBanner(state.error, callbacks::onRetry)
                }
                // §4: attesa one-shot, nessuno streaming. elapsed onesto e visibile.
                if (state.sending) {
                    LatencyIndicator(
                        elapsedSec = state.sendingElapsedSec ?: 0,
                        expectedRangeLabel = "di solito 10-30 s",
                        onCancel = callbacks::onCancelPending,
                    )
                }
            }

            ChatInputBar(
                input = state.input,
                enabled = inputEnabled,
                sending = state.sending,
                onInputChange = callbacks::onInputChange,
                onSend = callbacks::onSend,
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Header
// -----------------------------------------------------------------------------

@Composable
private fun ChatHeader(
    onClearConversation: () -> Unit,
    onCheckConnection: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Argus", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
            Text(
                "chat · compilatore di regole",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("chat_overflow")) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Altro", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Svuota conversazione") },
                    onClick = {
                        menuOpen = false
                        onClearConversation()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Verifica connessione Hermes") },
                    onClick = {
                        menuOpen = false
                        onCheckConnection()
                    },
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Voci della conversazione
// -----------------------------------------------------------------------------

@Composable
private fun ChatItemRow(item: ChatItem, callbacks: ChatCallbacks) {
    when (item) {
        is ChatItem.UserMessage -> MessageBubble(item.text, item.timeLabel, fromUser = true)
        is ChatItem.AssistantMessage -> MessageBubble(item.text, item.timeLabel, fromUser = false)
        is ChatItem.SystemNotice -> NoticeBanner(item.text, isError = item.kind == NoticeKind.ERROR)
        is ChatItem.DraftCard -> DraftCardItem(item, callbacks::onOpenDraft)
    }
}

@Composable
private fun MessageBubble(text: String, timeLabel: String, fromUser: Boolean) {
    // Angolo 4dp sul lato mittente (design §5.4): utente=basso-dx, assistant=basso-sx.
    val shape = if (fromUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)
    }
    val bg = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth()) {
        if (fromUser) Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
        ) {
            Box(
                Modifier
                    .clip(shape)
                    .background(bg)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                Text(text, color = fg, style = MaterialTheme.typography.bodyLarge)
            }
            if (timeLabel.isNotBlank()) {
                Text(
                    timeLabel,
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                )
            }
        }
        if (!fromUser) Spacer(Modifier.weight(1f))
    }
}

/** Banner centrato info/error (design §7.1). INFO=blu, ERROR=rosso. */
@Composable
private fun NoticeBanner(text: String, isError: Boolean) {
    val semantic = LocalArgusSemantic.current
    val bg: Color
    val fg: Color
    val icon: ImageVector
    if (isError) {
        bg = semantic.error.bg; fg = semantic.error.fg; icon = Icons.Rounded.Error
    } else {
        bg = MaterialTheme.colorScheme.primaryContainer; fg = MaterialTheme.colorScheme.onPrimaryContainer; icon = Icons.Rounded.Info
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .then(if (isError) Modifier.border(1.dp, fg.copy(alpha = 0.45f), RoundedCornerShape(12.dp)) else Modifier)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Card bozza: RuleCard(compact) + eventuale fascia ERROR "Non armabile" + footer
 * stato + CTA "Rivedi e approva →". §3: l'arm NON è qui; la CTA apre il Dettaglio.
 */
@Composable
private fun DraftCardItem(draft: ChatItem.DraftCard, onOpenDraft: (String) -> Unit) {
    val semantic = LocalArgusSemantic.current
    val errorIssue = draft.issues.firstOrNull { it.severity == Severity.ERROR }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // §5.1: il riassunto della regola viene da RuleRender (RuleCard), mai dalla prosa.
        RuleCard(draft.rule, compact = true)

        // ERROR ⇒ la bozza non è armabile: motivo esplicito (§5.2), ma resta rivedibile.
        if (errorIssue != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(semantic.error.bg)
                    .border(1.dp, semantic.error.fg.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Error, contentDescription = null, tint = semantic.error.fg, modifier = Modifier.size(16.dp))
                Text("Non armabile: ${errorIssue.text}", color = semantic.error.fg, style = MaterialTheme.typography.bodyMedium)
            }
        }

        DraftFooter(draft, onOpenDraft)
    }
}

@Composable
private fun DraftFooter(draft: ChatItem.DraftCard, onOpenDraft: (String) -> Unit) {
    val semantic = LocalArgusSemantic.current
    // Stato bozza -> copy + colore semantico; la CTA compare solo quando è azionabile (PROPOSED).
    val (role, icon, label, showCta) = when (draft.status) {
        DraftCardStatus.PROPOSED ->
            StatusLine(semantic.pending, Icons.Rounded.Pending, "Bozza · in attesa di approvazione", true)
        DraftCardStatus.APPROVED ->
            StatusLine(semantic.armed, Icons.Rounded.Shield, "Approvata e armata", false)
        DraftCardStatus.REJECTED ->
            StatusLine(semantic.disabled, Icons.Rounded.Block, "Bozza rifiutata", false)
        DraftCardStatus.SUPERSEDED ->
            StatusLine(semantic.disabled, Icons.Rounded.History, "Sostituita da una proposta più recente", false)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = null, tint = role.fg, modifier = Modifier.size(15.dp))
            Text(label, color = role.fg, style = MaterialTheme.typography.bodyMedium)
        }
        if (showCta) {
            Button(
                onClick = { onOpenDraft(draft.draftId) },
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text("Rivedi e approva")
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private data class StatusLine(val role: RolePair, val icon: ImageVector, val label: String, val showCta: Boolean)

// -----------------------------------------------------------------------------
// Banner brain-down / errori chat (§9, copy verbatim)
// -----------------------------------------------------------------------------

@Composable
private fun BrainDownBanner(onRetry: () -> Unit) {
    val semantic = LocalArgusSemantic.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(semantic.error.bg)
            .border(1.dp, semantic.error.fg.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = semantic.error.fg, modifier = Modifier.size(20.dp))
        Text("Hermes irraggiungibile", color = semantic.error.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Riprova") }
    }
}

/** §9: errori chat resi senza allarmismo, sempre con CTA "riprova". */
@Composable
private fun ChatErrorBanner(error: ChatError, onRetry: () -> Unit) {
    val semantic = LocalArgusSemantic.current
    var expanded by remember { mutableStateOf(false) }
    val text = when (error) {
        ChatError.Timeout -> "Hermes non risponde (timeout 60 s). Il server potrebbe essere occupato — riprova."
        ChatError.BridgeUnreachable -> "Hermes irraggiungibile. Controlla la connessione al tailnet e riprova."
        is ChatError.MalformedReply -> "La risposta non conteneva una regola valida. Riprova o riformula."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(semantic.error.bg)
            .border(1.dp, semantic.error.fg.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Rounded.Error, contentDescription = null, tint = semantic.error.fg, modifier = Modifier.size(18.dp))
            Text(text, color = semantic.error.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (error is ChatError.MalformedReply) {
            // Dettaglio tecnico espandibile: atteso (l'LLM sbaglia il JSON), tono calmo.
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (expanded) "Nascondi dettaglio tecnico" else "Dettaglio tecnico")
            }
            if (expanded) {
                Text(
                    error.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Riprova") }
        }
    }
}

// -----------------------------------------------------------------------------
// Empty state (suggerimenti) e input
// -----------------------------------------------------------------------------

@Composable
private fun ChatEmptyState(callbacks: ChatCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
        Spacer(Modifier.size(12.dp))
        Text("Chiedi la tua prima regola", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(
            "Descrivi cosa vuoi che Argus faccia: proporrà una regola da rivedere e armare.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        Text(
            "Prova a chiedere",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatSuggestions.forEach { s ->
                SuggestionChip(s) {
                    // Empty-state: imposta l'input e invia (brief Task 7).
                    callbacks.onInputChange(s)
                    callbacks.onSend()
                }
            }
        }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun SuggestionChip(text: String, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onTap() }
            .heightIn(min = 48.dp)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    enabled: Boolean,
    sending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = enabled && input.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        TextField(
            value = input,
            onValueChange = onInputChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(if (sending) "In attesa della risposta…" else "Chiedi una regola…")
            },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        FilledIconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Invia", modifier = Modifier.size(22.dp))
        }
    }
}

// =============================================================================
// Preview — fixture inline (i Fixtures centralizzati arrivano in Unit F).
// =============================================================================

private val previewDraftRule = RuleRender(
    triggerLine = "Quando: notifica WhatsApp da \"Moglie\" (chat 1:1)",
    triggerIconKey = "notification",
    conditionLines = listOf("Solo tra le 18:00 e le 22:00 (Europe/Rome)"),
    actions = listOf(
        ActionRow("generative", "Rispondi con l'AI", null, false, null, isGenerative = true, requiresLiveConfirm = false),
    ),
    isGenerative = true,
    privacyNote = "Il testo della notifica verrà inviato a Hermes e ai provider cloud per generare la risposta.",
)

private val previewConversation = listOf(
    ChatItem.UserMessage(
        "Se Moglie mi scrive su WhatsApp tra le 18 e le 22, rispondile tu nel tono che abbiamo concordato.",
        "13:03",
    ),
    ChatItem.AssistantMessage(
        "Ok. Ho preparato una regola: risponde solo a Moglie, solo in quella fascia, generando il testo al momento. È generativa (il contenuto esce verso il cloud), quindi controllala e armala dal dettaglio.",
        "13:03",
    ),
    ChatItem.DraftCard(
        draftId = "a3",
        rule = previewDraftRule,
        issues = listOf(
            UiWarning(Severity.WARNING, "read_plus_reply", "Legge dati dal dispositivo e li può inviare al mittente."),
        ),
        status = DraftCardStatus.PROPOSED,
    ),
)

private object NoopChatCallbacks : ChatCallbacks {
    override fun onInputChange(text: String) {}
    override fun onSend() {}
    override fun onCancelPending() {}
    override fun onOpenDraft(draftId: String) {}
    override fun onRetry() {}
    override fun onClearConversation() {}
    override fun onCheckConnection() {}
}

@Preview(name = "Chat · conversazione + DraftCard", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ChatConversationPreview() {
    ArgusTheme {
        ChatScreen(
            ChatState(items = previewConversation, input = "", sending = false, sendingElapsedSec = null, brainReachable = true, error = null),
            NoopChatCallbacks,
        )
    }
}

@Preview(name = "Chat · sending (latency)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ChatSendingPreview() {
    ArgusTheme {
        ChatScreen(
            ChatState(
                items = listOf(ChatItem.UserMessage("Dopo le 23 metti Non disturbare.", "13:05")),
                input = "",
                sending = true,
                sendingElapsedSec = 12,
                brainReachable = true,
                error = null,
            ),
            NoopChatCallbacks,
        )
    }
}

@Preview(name = "Chat · empty (suggerimenti)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ChatEmptyPreview() {
    ArgusTheme {
        ChatScreen(
            ChatState(items = emptyList(), input = "", sending = false, sendingElapsedSec = null, brainReachable = null, error = null),
            NoopChatCallbacks,
        )
    }
}

@Preview(name = "Chat · brain-down", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ChatBrainDownPreview() {
    ArgusTheme {
        ChatScreen(
            ChatState(
                items = listOf(ChatItem.UserMessage("Crea una regola per il Wi-Fi.", "13:10")),
                input = "",
                sending = false,
                sendingElapsedSec = null,
                brainReachable = false,
                error = null,
            ),
            NoopChatCallbacks,
        )
    }
}

@Preview(name = "Chat · MalformedReply notice", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ChatMalformedPreview() {
    ArgusTheme {
        ChatScreen(
            ChatState(
                items = previewConversation,
                input = "",
                sending = false,
                sendingElapsedSec = null,
                brainReachable = true,
                error = ChatError.MalformedReply("JSONDecodeError: expected ',' at line 3 col 12 (rule.actions[0])"),
            ),
            NoopChatCallbacks,
        )
    }
}
