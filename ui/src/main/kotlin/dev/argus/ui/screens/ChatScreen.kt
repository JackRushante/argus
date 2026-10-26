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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.engine.safety.Severity
import dev.argus.ui.components.ConfirmationState
import dev.argus.ui.components.DestructiveConfirmDialog
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
import dev.argus.ui.preview.Fixtures
import dev.argus.ui.presentation.ChatExampleGroup
import dev.argus.ui.presentation.ChatExamples
import dev.argus.ui.presentation.secondaryText
import dev.argus.ui.R
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

@Composable
fun ChatScreen(
    state: ChatState,
    callbacks: ChatCallbacks,
    modifier: Modifier = Modifier,
) {
    val brainDown = state.brainReachable == false
    // B42: la chat non si blocca mai. Il campo e l'invio sono disabilitati SOLO durante l'invio:
    // nessun probe di connessione automatico (zero rete all'avvio/entrando in Chat), quindi un
    // "irraggiungibile" vecchio non può impedire di riprovare; si toglie al prossimo invio riuscito.
    val inputEnabled = !state.sending
    // «Risposta grezza» (0.3.6): il testo vive nello stato (solo memoria); qui si decide solo se il dialogo è aperto.
    var showRawResponse by remember { mutableStateOf(false) }
    val rawAvailable = state.rawResponse != null && !state.sending
    // Nuovo invio o nuova conversazione: la risposta grezza sparisce e il dialogo non deve riaprirsi da solo.
    LaunchedEffect(state.rawResponse != null) {
        if (state.rawResponse == null) showRawResponse = false
    }
    // 0.4.0: galleria «Altri esempi» e conferma di «Svuota conversazione» (stato effimero della schermata).
    var showExamples by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(ConfirmationState<Unit>()) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                // B48: svuotare cancella messaggi e avvisi; se c'è qualcosa da perdere si chiede conferma.
                onClearConversation = {
                    if (hasClearableContent(state)) {
                        confirmClear = confirmClear.ask(Unit)
                    } else {
                        callbacks.onClearConversation()
                    }
                },
                onCheckConnection = callbacks::onCheckConnection,
            )

            // Zona messaggi (o empty state coi suggerimenti).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.items.isEmpty() && !state.sending) {
                    ChatEmptyState(callbacks, onMoreExamples = { showExamples = true })
                } else {
                    val listState = rememberLazyListState()
                    AutoScrollToNewItems(listState, itemCount = state.items.size)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Chiavi stabili (B44): le card per draftId, il resto per posizione.
                        itemsIndexed(
                            state.items,
                            key = { index, item -> chatItemKey(index, item) },
                        ) { _, item -> ChatItemRow(item, callbacks) }
                        // Senza errore l'azione sta sotto l'ultimo messaggio; con un errore, sotto il suo banner.
                        if (rawAvailable && state.error == null) {
                            item(key = "raw-response-action") {
                                RawResponseAction(onClick = { showRawResponse = true })
                            }
                        }
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
                    state.error != null && !state.sending ->
                        ChatErrorBanner(state.error, callbacks::onRetry, callbacks::onOpenSystem)
                }
                if (rawAvailable && state.error != null) {
                    RawResponseAction(onClick = { showRawResponse = true })
                }
                // §4: attesa one-shot, nessuno streaming. elapsed onesto e visibile.
                if (state.sending) {
                    LatencyIndicator(
                        elapsedSec = state.sendingElapsedSec ?: 0,
                        expectedRangeLabel = stringResource(R.string.chat_expected_range),
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
    if (confirmClear.isOpen) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.ui40_chat_clear_title),
            body = stringResource(R.string.ui40_chat_clear_body),
            confirmLabel = stringResource(R.string.ui40_chat_clear_confirm),
            onConfirm = { confirmClear = confirmClear.confirm { callbacks.onClearConversation() } },
            onDismiss = { confirmClear = confirmClear.cancel() },
        )
    }
    if (showExamples) {
        ChatExamplesSheet(
            groups = ChatExamples.groups(),
            // Riempie il campo di testo, senza inviare: l'utente rilegge, adatta e poi invia.
            onPick = { text ->
                showExamples = false
                callbacks.onInputChange(text)
            },
            onDismiss = { showExamples = false },
        )
    }
    val rawText = state.rawResponse
    if (showRawResponse && rawText != null) {
        RawResponseDialog(
            raw = rawText,
            onCopy = {
                callbacks.onCopyRawResponse()
                showRawResponse = false
            },
            onDismiss = { showRawResponse = false },
        )
    }
}

/**
 * Vero se «Svuota conversazione» ha qualcosa da perdere: messaggi e avvisi. Le proposte in attesa
 * (DraftCard) restano comunque, perché sono il canale di approvazione.
 */
internal fun hasClearableContent(state: ChatState): Boolean =
    state.items.any { it !is ChatItem.DraftCard }

/** Chiave stabile di una voce della conversazione: draftId per le card, indice per il resto. */
internal fun chatItemKey(index: Int, item: ChatItem): String =
    if (item is ChatItem.DraftCard) "draft:${item.draftId}" else "item:$index"

/**
 * Scroll automatico (B44): quando arriva una voce nuova e l'utente era già in fondo (l'ultima voce
 * della lista PRECEDENTE era visibile), scorre sull'ultima. Chi ha scorso in alto per rileggere non
 * viene riportato giù.
 */
@Composable
private fun AutoScrollToNewItems(listState: LazyListState, itemCount: Int) {
    var previousCount by remember { mutableIntStateOf(itemCount) }
    LaunchedEffect(itemCount) {
        if (itemCount > previousCount) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val wasAtBottom = previousCount == 0 || lastVisible >= previousCount - 1
            if (wasAtBottom) listState.animateScrollToItem(itemCount - 1)
        }
        previousCount = itemCount
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
                stringResource(R.string.chat_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("chat_overflow")) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.chat_more), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_clear)) },
                    onClick = {
                        menuOpen = false
                        onClearConversation()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_check_connection)) },
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
                    color = MaterialTheme.colorScheme.secondaryText(),
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
                Text(stringResource(R.string.chat_not_armable, errorIssue.text), color = semantic.error.fg, style = MaterialTheme.typography.bodyMedium)
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
            StatusLine(semantic.pending, Icons.Rounded.Pending, stringResource(R.string.chat_draft_pending), true)
        DraftCardStatus.APPROVED ->
            StatusLine(semantic.armed, Icons.Rounded.Shield, stringResource(R.string.chat_draft_approved), false)
        DraftCardStatus.REJECTED ->
            StatusLine(semantic.disabled, Icons.Rounded.Block, stringResource(R.string.chat_draft_rejected), false)
        DraftCardStatus.SUPERSEDED ->
            StatusLine(semantic.disabled, Icons.Rounded.History, stringResource(R.string.chat_draft_superseded), false)
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
                Text(stringResource(R.string.chat_review_cta))
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
        Text(stringResource(R.string.chat_brain_down), color = semantic.error.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_retry)) }
    }
}

/**
 * §9: errori chat resi senza allarmismo e SPIEGATI col loro rimedio (B11). Ogni variante ha il suo
 * testo; Auth, Budget e NotConfigured portano a Sistema; il dettaglio tecnico mostra il codice.
 */
@Composable
private fun ChatErrorBanner(error: ChatError, onRetry: () -> Unit, onOpenSystem: () -> Unit) {
    val semantic = LocalArgusSemantic.current
    var expanded by remember { mutableStateOf(false) }
    val text = when (error) {
        ChatError.Timeout -> stringResource(R.string.chat_error_timeout)
        ChatError.BridgeUnreachable -> stringResource(R.string.chat_error_unreachable)
        is ChatError.MalformedReply -> stringResource(R.string.chat_error_malformed)
        ChatError.Auth -> stringResource(R.string.chat_error_auth)
        ChatError.Budget -> stringResource(R.string.chat_error_budget)
        ChatError.RateLimit -> stringResource(R.string.chat_error_rate_limit)
        ChatError.OutputTruncated -> stringResource(R.string.chat_error_output_truncated)
        ChatError.ContextTruncated -> stringResource(R.string.chat_error_context_truncated)
        is ChatError.ServerRejected -> serverRejectedText(error)
        is ChatError.ServiceUnavailable -> stringResource(R.string.chat_error_service_unavailable, error.status)
        is ChatError.TooLong -> stringResource(R.string.chat_error_too_long, error.chars, error.max)
        ChatError.NotConfigured -> stringResource(R.string.chat_error_not_configured)
        is ChatError.Internal -> stringResource(R.string.chat_error_internal, error.code)
    }
    val detail = chatErrorDetail(error)
    val opensSystem = error == ChatError.Auth || error == ChatError.Budget || error == ChatError.NotConfigured
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
        if (detail != null) {
            // Dettaglio tecnico espandibile con il codice: atteso (l'LLM sbaglia il JSON), tono calmo.
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (expanded) stringResource(R.string.chat_error_detail_hide) else stringResource(R.string.chat_error_detail_show))
            }
            if (expanded) {
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        // TooLong: si risolve accorciando il testo, "riprova" non servirebbe a nulla.
        if (error !is ChatError.TooLong) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (opensSystem) {
                    TextButton(onClick = onOpenSystem, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.chat_open_system))
                    }
                }
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_retry)) }
            }
        }
    }
}

/**
 * Rifiuto 4xx del server: col messaggio del provider (già ripulito) dice COSA è stato rifiutato; senza,
 * resta il consiglio generico sul contesto del modello.
 */
@Composable
private fun serverRejectedText(error: ChatError.ServerRejected): String {
    val providerMessage = error.providerMessage?.takeIf { it.isNotBlank() }
    return if (providerMessage != null) {
        stringResource(R.string.chat_error_server_rejected_message, error.status, providerMessage)
    } else {
        stringResource(R.string.chat_error_server_rejected, error.status)
    }
}

/** Codice tecnico mostrato nel dettaglio espandibile (non localizzato: serve a segnalare il problema). */
internal fun chatErrorDetail(error: ChatError): String? = when (error) {
    ChatError.Timeout -> "timeout"
    ChatError.BridgeUnreachable -> "network"
    is ChatError.MalformedReply -> error.detail
    ChatError.Auth -> "auth"
    ChatError.Budget -> "budget"
    ChatError.RateLimit -> "rate_limit"
    ChatError.OutputTruncated -> "output_truncated"
    ChatError.ContextTruncated -> "context_truncated"
    is ChatError.ServerRejected -> "HTTP ${error.status}"
    is ChatError.ServiceUnavailable -> "HTTP ${error.status}"
    is ChatError.TooLong -> null
    ChatError.NotConfigured -> "not_configured"
    is ChatError.Internal -> error.code
}

// -----------------------------------------------------------------------------
// Risposta grezza dell'AI (0.3.6): per le segnalazioni, solo in memoria
// -----------------------------------------------------------------------------

/** Azione testuale «Risposta grezza» sotto l'ultimo messaggio o sotto l'errore dell'ultimo turno. */
@Composable
private fun RawResponseAction(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("chat_raw_action")) {
        Text(stringResource(R.string.chat_raw_action))
    }
}

/**
 * Dialogo con la risposta grezza del modello: avviso sui dati personali, testo selezionabile e
 * scorrevole, copia per la segnalazione (versione, provider, modello e codice d'errore li aggiunge la copia).
 */
@Composable
private fun RawResponseDialog(raw: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_raw_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.chat_raw_warning),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("chat_raw_text"),
                ) {
                    SelectionContainer {
                        Text(
                            raw,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_raw_close)) }
        },
        confirmButton = {
            Button(onClick = onCopy, modifier = Modifier.testTag("chat_raw_copy")) {
                Text(stringResource(R.string.chat_raw_copy))
            }
        },
    )
}

// -----------------------------------------------------------------------------
// Empty state (suggerimenti) e input
// -----------------------------------------------------------------------------

@Composable
private fun ChatEmptyState(callbacks: ChatCallbacks, onMoreExamples: () -> Unit) {
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
        Text(stringResource(R.string.chat_empty_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(R.string.chat_empty_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        Text(
            stringResource(R.string.chat_empty_try),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        // Esempi cliccabili dell'empty state (feedback Lorenzo 2026-07-14): niente riferimenti
        // personali e SOLO regole armabili con le capability correnti (time/notification/geofence).
        val suggestions = listOf(
            stringResource(R.string.chat_suggestion_morning),
            stringResource(R.string.chat_suggestion_night),
            stringResource(R.string.chat_suggestion_whatsapp),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s ->
                SuggestionChip(s) {
                    // Empty-state: imposta l'input e invia (brief Task 7).
                    callbacks.onInputChange(s)
                    callbacks.onSend()
                }
            }
            // 0.4.0 (F10): galleria con altre idee, divise per argomento.
            TextButton(
                onClick = onMoreExamples,
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag("chat_more_examples"),
            ) {
                Text(stringResource(R.string.ui40_chat_more_examples))
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

/** Galleria «Altri esempi» (F10): foglio dal basso con gli esempi per gruppo; toccarne uno lo passa a [onPick]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatExamplesSheet(
    groups: List<ChatExampleGroup>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        ChatExamplesContent(groups, onPick)
    }
}

@Composable
internal fun ChatExamplesContent(groups: List<ChatExampleGroup>, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.ui40_examples_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.ui40_examples_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        groups.forEach { group ->
            Text(
                group.title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 10.dp),
            )
            group.examples.forEach { example ->
                ExampleRow(example.text, example.requiresShizuku) { onPick(example.text) }
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun ExampleRow(text: String, requiresShizuku: Boolean, onTap: () -> Unit) {
    val shizukuCd = stringResource(R.string.ui40_example_shizuku_cd)
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (requiresShizuku) {
            // Etichetta testuale (non solo colore): l'esempio funziona solo con Shizuku attivo.
            Text(
                stringResource(R.string.ui40_example_shizuku_tag),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .semantics { contentDescription = shizukuCd },
            )
        }
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
                Text(if (sending) stringResource(R.string.chat_input_sending) else stringResource(R.string.chat_input_placeholder))
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
            Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.chat_send), modifier = Modifier.size(22.dp))
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
    override fun onOpenSystem() {}
    override fun onCopyRawResponse() {}
}

@Preview(name = "Chat · risposta grezza", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ChatRawResponsePreview() {
    ArgusTheme { ChatScreen(Fixtures.chatRawResponse, NoopChatCallbacks) }
}

@Preview(name = "Chat · errore interno", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ChatInternalErrorPreview() {
    ArgusTheme { ChatScreen(Fixtures.chatInternalError, NoopChatCallbacks) }
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

@Preview(name = "Chat · galleria esempi", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 900)
@Composable
private fun ChatExamplesPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatExamplesContent(ChatExamples.groups(dev.argus.ui.presentation.RenderLanguage.IT), onPick = {})
        }
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
