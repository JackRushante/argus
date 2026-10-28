package dev.argus.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.engine.safety.Severity
import dev.argus.ui.R
import dev.argus.ui.components.ArgusBottomSheet
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusCardGroup
import dev.argus.ui.components.ArgusChatTopBar
import dev.argus.ui.components.ArgusIconButton
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.ChatBrandMark
import dev.argus.ui.components.ChatDraftCard
import dev.argus.ui.components.ChatWaitCard
import dev.argus.ui.components.CodeBlock
import dev.argus.ui.components.ConfirmationState
import dev.argus.ui.components.DestructiveConfirmDialog
import dev.argus.ui.components.IconBadge
import dev.argus.ui.components.SectionLabel
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
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Schermo Chat (handoff §6.1, design §7.1; redesign A, tavole «Chat · primo avvio / bozza / in attesa»).
// L'LLM compila le regole; la chat e' il punto d'ingresso. Vincoli cardine:
//  - §4 NIENTE STREAMING: attesa one-shot lunga e onesta (scheda con tempo trascorso e barra dei tempi),
//    nessun cursore/typing; input disabilitato durante l'attesa.
//  - Invariante 1: l'ARM NON avviene qui. La card bozza porta solo «Rivedi e approva», che apre il
//    Dettaglio (onOpenDraft). Un ERROR compare per intero come «Non armabile: <motivo>», gli avvisi si contano.
//  - §5.4 la bozza generativa porta i chip «generativa» + «esce verso il cloud».
// Stateless: solo (state, callbacks). L'overflow in header e' nav host-owned.
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
    // L'errore sta nel flusso della conversazione, dalla parte di Argus; "servizio irraggiungibile" ha il suo
    // banner sopra il campo e prende il posto dell'errore (come prima).
    val error = state.error?.takeIf { !state.sending && !brainDown }

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

            // Zona messaggi (o stato vuoto coi suggerimenti).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.items.isEmpty() && !state.sending) {
                    ChatEmptyState(
                        callbacks = callbacks,
                        onMoreExamples = { showExamples = true },
                        error = error,
                    )
                } else {
                    ChatConversation(
                        state = state,
                        callbacks = callbacks,
                        error = error,
                        rawAvailable = rawAvailable,
                        onShowRaw = { showRawResponse = true },
                    )
                }
            }

            if (brainDown) {
                BrainDownBanner(callbacks::onRetry, Modifier.padding(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Small))
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
 * Quante voci ha la lista della conversazione: i messaggi più le code (attesa, errore, «Risposta grezza»).
 * Serve allo scroll automatico: una coda che compare è una voce nuova come un messaggio.
 */
internal fun chatListSize(itemCount: Int, sending: Boolean, hasError: Boolean, hasRawAction: Boolean): Int =
    itemCount + (if (sending) 1 else 0) + (if (hasError) 1 else 0) + (if (hasRawAction) 1 else 0)

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
    ArgusChatTopBar(
        title = "Argus",
        subtitle = stringResource(R.string.chat_subtitle),
        leading = { ChatBrandMark(size = 36.dp) },
        actions = {
            Box {
                ArgusIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.chat_more),
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("chat_overflow"),
                )
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
        },
    )
}

// -----------------------------------------------------------------------------
// Conversazione
// -----------------------------------------------------------------------------

@Composable
private fun ChatConversation(
    state: ChatState,
    callbacks: ChatCallbacks,
    error: ChatError?,
    rawAvailable: Boolean,
    onShowRaw: () -> Unit,
) {
    val listState = rememberLazyListState()
    AutoScrollToNewItems(
        listState,
        itemCount = chatListSize(state.items.size, state.sending, error != null, rawAvailable),
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large),
    ) {
        // Chiavi stabili (B44): le card per draftId, il resto per posizione.
        itemsIndexed(
            state.items,
            key = { index, item -> chatItemKey(index, item) },
        ) { _, item -> ChatItemRow(item, callbacks) }
        // §4: attesa one-shot, nessuno streaming. Il tempo trascorso e' onesto e visibile.
        if (state.sending) {
            item(key = "chat-wait") {
                ChatWaitCard(
                    elapsedSec = state.sendingElapsedSec ?: 0,
                    timeoutSec = state.sendingTimeoutSec,
                    expectedRangeLabel = stringResource(R.string.chat_expected_range),
                    onCancel = callbacks::onCancelPending,
                )
            }
        }
        if (error != null) {
            item(key = "chat-error") {
                ChatErrorBubble(error, callbacks::onRetry, callbacks::onOpenSystem)
            }
        }
        // Senza errore l'azione sta sotto l'ultimo messaggio; con un errore, sotto il suo messaggio.
        if (rawAvailable) {
            item(key = "raw-response-action") {
                RawResponseAction(onClick = onShowRaw)
            }
        }
    }
}

@Composable
private fun ChatItemRow(item: ChatItem, callbacks: ChatCallbacks) {
    when (item) {
        is ChatItem.UserMessage -> MessageBubble(item.text, item.timeLabel, fromUser = true)
        is ChatItem.AssistantMessage -> MessageBubble(item.text, item.timeLabel, fromUser = false)
        is ChatItem.SystemNotice -> NoticeBubble(item.text, isError = item.kind == NoticeKind.ERROR)
        is ChatItem.DraftCard -> ChatDraftCard(
            draft = item,
            onOpenDraft = callbacks::onOpenDraft,
            modifier = Modifier.testTag("chat_draft_card"),
        )
    }
}

/** Angolo piccolo (6) sul lato di chi parla: utente = basso a destra, Argus = basso a sinistra. */
private fun bubbleShape(fromUser: Boolean): RoundedCornerShape = if (fromUser) {
    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = ArgusRadius.Xs, bottomStart = 22.dp)
} else {
    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = ArgusRadius.Xs)
}

@Composable
private fun MessageBubble(text: String, timeLabel: String, fromUser: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (fromUser) scheme.primaryContainer else ArgusTheme.surfaces.card
    val fg = if (fromUser) scheme.onPrimaryContainer else scheme.onSurface
    Row(Modifier.fillMaxWidth()) {
        if (fromUser) Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit),
        ) {
            Box(
                Modifier
                    .clip(bubbleShape(fromUser))
                    .background(bg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(text, color = fg, style = MaterialTheme.typography.bodyLarge)
            }
            if (timeLabel.isNotBlank()) {
                Text(
                    timeLabel,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W400,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
        if (!fromUser) Spacer(Modifier.weight(1f))
    }
}

/**
 * Avviso di sistema nella conversazione (annullata, non supportata, ...), dalla parte di Argus. ERROR = fondo
 * rosso con il testo normale; INFO = fondo `card2`. L'icona accompagna sempre il colore.
 */
@Composable
private fun NoticeBubble(text: String, isError: Boolean) {
    val semantic = ArgusTheme.semantic
    val scheme = MaterialTheme.colorScheme
    val bg: Color
    val tint: Color
    val icon: ImageVector
    if (isError) {
        bg = semantic.error.bg; tint = semantic.error.fg; icon = Icons.Rounded.Error
    } else {
        bg = ArgusTheme.surfaces.card2; tint = scheme.primary; icon = Icons.Rounded.Info
    }
    Row(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .clip(bubbleShape(fromUser = false))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
    }
}

// -----------------------------------------------------------------------------
// Banner brain-down / errori chat (§9, copy verbatim)
// -----------------------------------------------------------------------------

@Composable
private fun BrainDownBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ArgusTheme.semantic.error.bg)
            .heightIn(min = 56.dp)
            .padding(start = ArgusSpacing.Medium, end = ArgusSpacing.Unit, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        IconBadge(Icons.Rounded.CloudOff, scheme.errorContainer, scheme.onErrorContainer, size = 34.dp, iconSize = 20.dp)
        Text(
            stringResource(R.string.chat_brain_down),
            color = scheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W500,
            modifier = Modifier.weight(1f),
        )
        ArgusButton(stringResource(R.string.chat_retry), onRetry, style = ButtonStyle.Text)
    }
}

/**
 * §9: errori chat resi senza allarmismo e SPIEGATI col loro rimedio (B11), come un messaggio di Argus nella
 * conversazione. Ogni variante ha il suo testo; Auth, Budget e NotConfigured portano a Sistema; il dettaglio
 * tecnico (il codice) si apre a richiesta e va a capo.
 */
@Composable
internal fun ChatErrorBubble(
    error: ChatError,
    onRetry: () -> Unit,
    onOpenSystem: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val semantic = ArgusTheme.semantic
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(initiallyExpanded) }
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
        modifier = modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .clip(bubbleShape(fromUser = false))
            .background(semantic.error.bg)
            .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = ArgusSpacing.Small)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Error, contentDescription = null, tint = semantic.error.fg, modifier = Modifier.size(20.dp))
            Text(text, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        if (detail != null) {
            // Dettaglio tecnico espandibile con il codice: atteso (l'LLM sbaglia il JSON), tono calmo.
            Row(
                modifier = Modifier
                    .padding(start = 24.dp)
                    .defaultMinSize(minHeight = ArgusSpacing.TouchTarget)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(start = 6.dp, end = ArgusSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit),
            ) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = semantic.error.fg,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    if (expanded) stringResource(R.string.chat_error_detail_hide) else stringResource(R.string.chat_error_detail_show),
                    color = semantic.error.fg,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (expanded) {
                CodeBlock(text = detail, modifier = Modifier.padding(start = 30.dp))
            }
        }
        // TooLong: si risolve accorciando il testo, "riprova" non servirebbe a nulla.
        if (error !is ChatError.TooLong) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (opensSystem) {
                    ArgusButton(stringResource(R.string.chat_open_system), onOpenSystem, style = ButtonStyle.Text)
                }
                ArgusButton(
                    text = stringResource(R.string.chat_retry),
                    onClick = onRetry,
                    style = ButtonStyle.Text,
                    icon = Icons.Rounded.Refresh,
                )
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
    ArgusButton(
        text = stringResource(R.string.chat_raw_action),
        onClick = onClick,
        style = ButtonStyle.Text,
        modifier = Modifier.testTag("chat_raw_action").semantics(mergeDescendants = true) {},
    )
}

/**
 * Dialogo con la risposta grezza del modello: avviso sui dati personali, testo selezionabile e
 * scorrevole, copia per la segnalazione (versione, provider, modello e codice d'errore li aggiunge la copia).
 */
@Composable
private fun RawResponseDialog(raw: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ArgusTheme.surfaces.sheet,
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
                            fontFamily = dev.argus.ui.theme.ArgusMono,
                        )
                    }
                }
            }
        },
        dismissButton = {
            ArgusButton(stringResource(R.string.chat_raw_close), onDismiss, style = ButtonStyle.Text)
        },
        confirmButton = {
            ArgusButton(
                text = stringResource(R.string.chat_raw_copy),
                onClick = onCopy,
                modifier = Modifier.testTag("chat_raw_copy").semantics(mergeDescendants = true) {},
            )
        },
    )
}

// -----------------------------------------------------------------------------
// Stato vuoto (suggerimenti) e input
// -----------------------------------------------------------------------------

/** Un suggerimento dello stato vuoto: testo, icona e forma del contenitore (cerchio per l'orario, squircle per la notifica). */
private data class SuggestionSpec(val text: String, val icon: ImageVector, val shape: RoundedCornerShape)

@Composable
private fun ChatEmptyState(
    callbacks: ChatCallbacks,
    onMoreExamples: () -> Unit,
    error: ChatError?,
) {
    // Esempi cliccabili dello stato vuoto (feedback Lorenzo 2026-07-14): niente riferimenti personali e SOLO
    // regole armabili con le capability correnti (time/notification/geofence). Sono gli stessi testi della galleria.
    val suggestions = listOf(
        SuggestionSpec(stringResource(R.string.chat_suggestion_morning), Icons.Rounded.Alarm, RoundedCornerShape(percent = 50)),
        SuggestionSpec(stringResource(R.string.chat_suggestion_night), Icons.Rounded.NightsStay, RoundedCornerShape(percent = 50)),
        SuggestionSpec(stringResource(R.string.chat_suggestion_whatsapp), Icons.Rounded.Notifications, RoundedCornerShape(ArgusRadius.Md)),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = ArgusSpacing.Small, bottom = ArgusSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Section, Alignment.CenterVertically),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ArgusSpacing.Medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChatBrandMark(size = 72.dp)
            Text(
                stringResource(R.string.chat_empty_title),
                modifier = Modifier.padding(top = ArgusSpacing.Unit),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.chat_empty_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            SectionLabel(stringResource(R.string.chat_empty_try), Modifier.padding(start = ArgusSpacing.Unit))
            ArgusCardGroup(suggestions) { suggestion ->
                SuggestionRow(suggestion) {
                    // Stato vuoto: imposta l'input e invia (brief Task 7).
                    callbacks.onInputChange(suggestion.text)
                    callbacks.onSend()
                }
            }
            // 0.4.0 (F10): galleria con altre idee, divise per argomento.
            ArgusButton(
                text = stringResource(R.string.ui40_chat_more_examples),
                onClick = onMoreExamples,
                style = ButtonStyle.Text,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("chat_more_examples")
                    .semantics(mergeDescendants = true) {},
            )
        }
        if (error != null) {
            ChatErrorBubble(error, callbacks::onRetry, callbacks::onOpenSystem)
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: SuggestionSpec, onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.rda_lc_example_send_label), role = Role.Button, onClick = onTap)
            .defaultMinSize(minHeight = 72.dp)
            .padding(start = ArgusSpacing.Medium, end = 14.dp, top = ArgusSpacing.Medium, bottom = ArgusSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        IconBadge(
            icon = suggestion.icon,
            container = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
            size = 40.dp,
            iconSize = 20.dp,
            shape = suggestion.shape,
        )
        Text(
            suggestion.text,
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Galleria «Altri esempi» (F10): foglio dal basso con gli esempi per gruppo; toccarne uno lo passa a [onPick]. */
@Composable
private fun ChatExamplesSheet(
    groups: List<ChatExampleGroup>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ArgusBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.ui40_examples_title)) {
        ChatExamplesContent(groups, onPick)
    }
}

@Composable
internal fun ChatExamplesContent(groups: List<ChatExampleGroup>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.ui40_examples_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        groups.forEach { group ->
            SectionLabel(group.title, Modifier.padding(top = 6.dp, start = ArgusSpacing.Unit))
            ArgusCardGroup(group.examples) { example ->
                ExampleRow(example.text, example.requiresShizuku) { onPick(example.text) }
            }
        }
    }
}

@Composable
private fun ExampleRow(text: String, requiresShizuku: Boolean, onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shizukuCd = stringResource(R.string.ui40_example_shizuku_cd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onTap)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = ArgusSpacing.Medium, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        Text(
            text,
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (requiresShizuku) {
            // Etichetta testuale (non solo colore): l'esempio funziona solo con Shizuku attivo.
            Text(
                stringResource(R.string.ui40_example_shizuku_tag),
                color = scheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(scheme.primaryContainer)
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
    val scheme = MaterialTheme.colorScheme
    val surfaces = ArgusTheme.surfaces
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaces.page)
            .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Medium, top = ArgusSpacing.Small, bottom = ArgusSpacing.Medium),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        TextField(
            value = input,
            onValueChange = onInputChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(if (sending) stringResource(R.string.chat_input_sending) else stringResource(R.string.chat_input_placeholder))
            },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(percent = 50),
            maxLines = 4,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = surfaces.field,
                unfocusedContainerColor = surfaces.field,
                disabledContainerColor = surfaces.card2,
                focusedTextColor = scheme.onSurface,
                unfocusedTextColor = scheme.onSurface,
                disabledTextColor = scheme.onSurfaceVariant,
                focusedPlaceholderColor = scheme.onSurfaceVariant,
                unfocusedPlaceholderColor = scheme.onSurfaceVariant,
                disabledPlaceholderColor = scheme.onSurfaceVariant,
                cursorColor = scheme.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        FilledIconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
                disabledContainerColor = surfaces.card2,
                disabledContentColor = scheme.outline,
            ),
        ) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.chat_send), modifier = Modifier.size(22.dp))
        }
    }
}

// =============================================================================
// Anteprime: ogni stato, tema chiaro e scuro (le tavole «Chat · primo avvio / bozza / in attesa»).
// =============================================================================

@Preview(name = "Chiaro", group = "Tema", showBackground = true, widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Scuro", group = "Tema", showBackground = true, widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class ChatScreenPreviews

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

private val previewUserMessage = ChatItem.UserMessage(
    "Se Moglie mi scrive su WhatsApp tra le 18 e le 22, rispondile tu nel tono che abbiamo concordato.",
    "13:03",
)

private val previewConversation = listOf(
    previewUserMessage,
    ChatItem.AssistantMessage(
        "Ok. Ho preparato una regola: risponde solo a Moglie, solo nella fascia serale, generando il testo al momento. È generativa (il contenuto esce verso il cloud), quindi controllala e armala dal dettaglio.",
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

private fun previewState(
    items: List<ChatItem> = emptyList(),
    input: String = "",
    sending: Boolean = false,
    elapsed: Int? = null,
    reachable: Boolean? = true,
    error: ChatError? = null,
    timeout: Int? = null,
) = ChatState(
    items = items,
    input = input,
    sending = sending,
    sendingElapsedSec = elapsed,
    brainReachable = reachable,
    error = error,
    sendingTimeoutSec = timeout,
)

@ChatScreenPreviews
@Composable
private fun ChatEmptyPreview() {
    ArgusTheme { ChatScreen(previewState(reachable = null), NoopChatCallbacks) }
}

@ChatScreenPreviews
@Composable
private fun ChatConversationPreview() {
    ArgusTheme {
        ChatScreen(previewState(items = previewConversation), NoopChatCallbacks)
    }
}

@ChatScreenPreviews
@Composable
private fun ChatSendingPreview() {
    ArgusTheme {
        ChatScreen(
            previewState(items = listOf(previewUserMessage), sending = true, elapsed = 12, timeout = 65),
            NoopChatCallbacks,
        )
    }
}

@ChatScreenPreviews
@Composable
private fun ChatNotArmablePreview() {
    ArgusTheme {
        val blocked = (previewConversation.last() as ChatItem.DraftCard).copy(
            issues = listOf(
                UiWarning(Severity.ERROR, "shell_in_generative", "il tool 'shell.run' è vietato nelle regole generative."),
                UiWarning(Severity.WARNING, "read_plus_reply", "Legge dati dal dispositivo e li può inviare al mittente."),
            ),
        )
        ChatScreen(previewState(items = previewConversation.dropLast(1) + blocked), NoopChatCallbacks)
    }
}

@ChatScreenPreviews
@Composable
private fun ChatMalformedPreview() {
    ArgusTheme {
        ChatScreen(
            previewState(
                items = previewConversation + listOf(
                    ChatItem.UserMessage("Rendila valida anche il sabato mattina.", "13:05"),
                ),
                error = ChatError.MalformedReply("metaError=missing_field · path=\$.draft.trigger · offset=214"),
            ),
            NoopChatCallbacks,
        )
    }
}

@ChatScreenPreviews
@Composable
private fun ChatErrorsPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.padding(ArgusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large),
            ) {
                ChatErrorBubble(ChatError.MalformedReply("missing_field"), {}, {}, initiallyExpanded = true)
                ChatErrorBubble(ChatError.Auth, {}, {})
                ChatErrorBubble(ChatError.TooLong(40_000, 32_768), {}, {})
                NoticeBubble("Richiesta annullata.", isError = false)
                NoticeBubble("La compilazione non è riuscita.", isError = true)
            }
        }
    }
}

@ChatScreenPreviews
@Composable
private fun ChatBrainDownPreview() {
    ArgusTheme {
        ChatScreen(
            previewState(items = listOf(ChatItem.UserMessage("Crea una regola per il Wi-Fi.", "13:10")), reachable = false),
            NoopChatCallbacks,
        )
    }
}

@ChatScreenPreviews
@Composable
private fun ChatRawResponsePreview() {
    ArgusTheme { ChatScreen(Fixtures.chatRawResponse, NoopChatCallbacks) }
}

@ChatScreenPreviews
@Composable
private fun ChatInternalErrorPreview() {
    ArgusTheme { ChatScreen(Fixtures.chatInternalError, NoopChatCallbacks) }
}

@ChatScreenPreviews
@Composable
private fun ChatComposedPreview() {
    ArgusTheme {
        ChatScreen(previewState(items = previewConversation, input = "Rendila valida anche il sabato mattina."), NoopChatCallbacks)
    }
}

@ChatScreenPreviews
@Composable
private fun ChatExamplesPreview() {
    ArgusTheme {
        Surface(color = ArgusTheme.surfaces.sheet) {
            Column(Modifier.padding(ArgusSpacing.Large)) {
                ChatExamplesContent(ChatExamples.groups(dev.argus.ui.presentation.RenderLanguage.IT), onPick = {})
            }
        }
    }
}
