package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppBlocking
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.AppInfoUi
import dev.argus.ui.model.AppRef
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.VarRow
import dev.argus.ui.model.VarSensitivity
import dev.argus.ui.model.iconFor
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusCode
import dev.argus.ui.theme.ArgusCodeSmall
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Regola dell'approvazione e del dettaglio (tavole «Approvazione · generativa», «Approvazione · P4 con shell»
// e «Dettaglio · armata»). La regola si rende SEMPRE dai tipi (RuleRender, invariante 4), mai dalla prosa del
// modello, come linea del tempo QUANDO → SOLO SE → VARIABILI → ALLORA:
//  - il contenitore del trigger ha la forma del tipo di trigger;
//  - il comando shell è un CodeBlock integrale, monospace, che va a capo, con l'etichetta dei privilegi
//    (invariante 6);
//  - l'albero del programma è TUTTO espanso: in approvazione nessun nodo si può chiudere.
// Accanto a ogni package d'app: il nome dell'app e «Cambia app».
// =============================================================================

/** Il testo del trigger senza il prefisso «Quando:»/«When:»: l'intestazione del passo lo dice già. */
internal fun triggerText(line: String): String =
    listOf("Quando: ", "When: ").firstOrNull { line.startsWith(it) }?.let { line.removePrefix(it) } ?: line

/** Forma del contenitore del trigger dal suo `iconKey` (la forma dice il tipo, il colore lo stato). */
internal fun triggerShapeFor(iconKey: String): TriggerShape = when (iconKey) {
    "time" -> TriggerShape.Circle
    "notification" -> TriggerShape.Squircle
    "geofence" -> TriggerShape.Drop
    "phone" -> TriggerShape.Rhombus
    "connectivity" -> TriggerShape.Arch
    else -> TriggerShape.Cookie
}

/** Il programma da mostrare: l'albero P4 oppure, per un RuleRender costruito a mano, le azioni piatte. */
internal fun programOf(rule: RuleRender): List<ProgramNode> =
    rule.program.ifEmpty { rule.actions.map { ProgramNode.Leaf(it) } }

/** Vero se l'albero contiene un blocco se/ripeti: il passo si intitola «programma completo». */
internal fun hasControlFlow(nodes: List<ProgramNode>): Boolean = nodes.any { it !is ProgramNode.Leaf }

private val NodeSize = 44.dp
private val ItemOuter = 18.dp
private val ItemInner = 4.dp

/**
 * Regola come linea del tempo.
 *
 * @param geofenceLabel etichetta al posto delle coordinate («Posizione: quella attuale…»), sotto il trigger.
 * @param estimate stima delle chiamate AI al giorno, sotto le azioni (solo regole generative).
 * @param appInfo nome e presenza sul telefono di ogni package (chiave = package).
 * @param onChangeApp tocco su «Cambia app» di un package; `null` = il tasto non c'è.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalRuleTimeline(
    rule: RuleRender,
    modifier: Modifier = Modifier,
    geofenceLabel: String? = null,
    estimate: String? = null,
    appInfo: Map<String, AppInfoUi> = emptyMap(),
    onChangeApp: ((AppRef) -> Unit)? = null,
) {
    val program = programOf(rule)
    val hasConditions = rule.conditionLines.isNotEmpty()
    val hasVars = rule.vars.isNotEmpty()
    Column(modifier = modifier.fillMaxWidth()) {
        TimelineStep(
            node = {
                TriggerContainer(iconFor(rule.triggerIconKey), triggerShapeFor(rule.triggerIconKey), TriggerTone.Normal)
            },
            last = !hasConditions && !hasVars && program.isEmpty(),
        ) {
            StepTitle(stringResource(R.string.rule_section_when))
            Text(
                triggerText(rule.triggerLine),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            rule.triggerAppRef?.let { ref ->
                AppRefLine(ref, appInfo[ref.packageName], onChangeApp, Modifier.padding(top = ArgusSpacing.Small))
            }
            geofenceLabel?.let { GeofenceLabel(it, Modifier.padding(top = 10.dp)) }
        }
        if (hasConditions) {
            TimelineStep(
                node = {
                    StepNode(Icons.Rounded.FilterAlt, ArgusTheme.surfaces.card2, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(ArgusRadius.Md), ring = true)
                },
                last = !hasVars && program.isEmpty(),
            ) {
                StepTitle(stringResource(R.string.rule_section_only_if))
                rule.conditionLines.forEach {
                    Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (hasVars) {
            TimelineStep(
                node = {
                    StepNode(Icons.Rounded.DataObject, ArgusTheme.surfaces.card2, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(ArgusRadius.Md), ring = true)
                },
                last = program.isEmpty(),
            ) {
                StepTitle(stringResource(R.string.rule_section_variables))
                Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
                    rule.vars.forEachIndexed { index, v ->
                        ApprovalVarItem(v, groupShape(groupPositionOf(index, rule.vars.size), ItemOuter, ItemInner))
                    }
                }
            }
        }
        if (program.isNotEmpty()) {
            val generative = rule.isGenerative
            TimelineStep(
                node = {
                    if (generative) {
                        StepNode(iconFor("generative"), ArgusTheme.semantic.generative.bg, ArgusTheme.semantic.generative.fg, CircleShape)
                    } else {
                        StepNode(Icons.Rounded.Bolt, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, CircleShape)
                    }
                },
                last = true,
            ) {
                StepTitle(
                    if (hasControlFlow(program)) stringResource(R.string.rda_ap_then_full)
                    else stringResource(R.string.rule_section_then),
                )
                ProgramLevel(program, 0, ArgusTheme.surfaces.card, appInfo, onChangeApp)
                estimate?.let { EstimateStrip(it, Modifier.padding(top = ArgusSpacing.Small)) }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Linea del tempo
// -----------------------------------------------------------------------------

@Composable
private fun TimelineStep(
    node: @Composable () -> Unit,
    last: Boolean,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(NodeSize).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            node()
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 6.dp)
                        .width(2.dp)
                        .weight(1f)
                        .heightIn(min = 12.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(top = 2.dp, bottom = if (last) 0.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun StepNode(icon: ImageVector, container: Color, content: Color, shape: Shape, ring: Boolean = false) {
    Box(
        modifier = Modifier
            .size(NodeSize)
            .clip(shape)
            .background(container)
            .then(if (ring) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun GeofenceLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ItemOuter))
            .background(ArgusTheme.surfaces.card)
            .padding(horizontal = ArgusSpacing.Medium, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EstimateStrip(text: String, modifier: Modifier = Modifier) {
    val role = ArgusTheme.semantic.generative
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(role.bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Insights, contentDescription = null, tint = role.fg, modifier = Modifier.size(18.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

// -----------------------------------------------------------------------------
// Variabili (solo la DEFINIZIONE, mai un valore a runtime)
// -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalVarItem(v: VarRow, shape: Shape) {
    val semantic = ArgusTheme.semantic
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ArgusTheme.surfaces.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(v.name, color = MaterialTheme.colorScheme.onSurface, style = ArgusCode)
        Text(
            "${v.typeLabel} · ${v.provenanceLabel}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (v.isExternal) {
                StatusChip(semantic.pending, Icons.Rounded.WarningAmber, v.integrityLabel)
            } else {
                StatusChip(semantic.armed, Icons.Rounded.CheckCircle, v.integrityLabel)
            }
            when (v.sensitivity) {
                VarSensitivity.SECRET -> StatusChip(semantic.needsReview, Icons.Rounded.Lock, v.confidentialityLabel)
                VarSensitivity.PRIVATE -> StatusChip(semantic.disabled, Icons.Rounded.VisibilityOff, v.confidentialityLabel)
                VarSensitivity.PUBLIC -> StatusChip(semantic.disabled, Icons.Rounded.Public, v.confidentialityLabel)
            }
        }
        if (v.sourceText.isNotBlank()) {
            Text(v.sourceText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        v.valueText?.let { value ->
            Text(
                stringResource(R.string.rule_var_value_label),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            // Il letterale approvato si mostra INTEGRALE: a capo, mai troncato.
            Text(value, color = semantic.codeText, style = ArgusCodeSmall)
        }
    }
}

// -----------------------------------------------------------------------------
// Albero del programma (tutto espanso)
// -----------------------------------------------------------------------------

/**
 * Un livello dell'albero. Le foglie e i blocchi dello stesso livello formano un gruppo segmentato (angoli
 * 18 fuori, 4 dentro, 3 dp di fessura); i livelli annidati alternano `card` e `card2` per restare leggibili.
 */
@Composable
private fun ProgramLevel(
    nodes: List<ProgramNode>,
    depth: Int,
    container: Color,
    appInfo: Map<String, AppInfoUi>,
    onChangeApp: ((AppRef) -> Unit)?,
) {
    val nested = if (container == ArgusTheme.surfaces.card) ArgusTheme.surfaces.card2 else ArgusTheme.surfaces.card
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
        nodes.forEachIndexed { index, node ->
            val shape = groupShape(groupPositionOf(index, nodes.size), ItemOuter, ItemInner)
            when (node) {
                is ProgramNode.Leaf -> ActionItem(node.row, shape, container, appInfo, onChangeApp)
                is ProgramNode.IfNode -> ControlItem(
                    shape = shape,
                    container = container,
                    title = node.title,
                    conditionLines = node.conditionLines,
                ) {
                    BranchLabel(node.thenTitle)
                    Branch { ProgramLevel(node.then, depth + 1, nested, appInfo, onChangeApp) }
                    if (node.elseTitle != null) {
                        BranchLabel(node.elseTitle)
                        Branch { ProgramLevel(node.orElse, depth + 1, nested, appInfo, onChangeApp) }
                    }
                }
                is ProgramNode.WhileNode -> ControlItem(
                    shape = shape,
                    container = container,
                    title = node.title,
                    conditionLines = node.conditionLines,
                ) {
                    Branch { ProgramLevel(node.body, depth + 1, nested, appInfo, onChangeApp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionItem(
    row: ActionRow,
    shape: Shape,
    container: Color,
    appInfo: Map<String, AppInfoUi>,
    onChangeApp: ((AppRef) -> Unit)?,
) {
    val semantic = ArgusTheme.semantic
    val surfaces = ArgusTheme.surfaces
    val iconContainer = if (row.isGenerative) semantic.generative.bg else if (container == surfaces.card) surfaces.card2 else surfaces.card
    val iconTint = if (row.isGenerative) semantic.generative.fg else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium), verticalAlignment = Alignment.Top) {
            IconBadge(iconFor(row.iconKey), iconContainer, iconTint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                    if (row.isGenerative) GenerativeTag()
                    if (row.requiresLiveConfirm) LiveConfirmTag()
                }
                row.detail?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                row.appRef?.let { ref -> AppRefLine(ref, appInfo[ref.packageName], onChangeApp) }
            }
        }
        // Invariante 6: il comando shell è integrale, monospace e va a capo; mai troncato.
        if (row.isShell && row.shellCommand != null) {
            ShellCommandBlock(row.shellCommand)
        }
    }
}

@Composable
private fun ControlItem(
    shape: Shape,
    container: Color,
    title: String,
    conditionLines: List<String>,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium), verticalAlignment = Alignment.Top) {
            IconBadge(
                iconFor("control_flow"),
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
                size = 28.dp,
                iconSize = 16.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                conditionLines.forEach {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        content()
    }
}

@Composable
private fun BranchLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 40.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** Rami annidati: filetto a sinistra e rientro, così nessuna foglia annidata sembra di primo livello. */
@Composable
private fun Branch(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 14.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(Modifier.weight(1f).padding(start = 10.dp)) { content() }
    }
}

// -----------------------------------------------------------------------------
// Package d'app: nome, avviso «non installata», «Cambia app»
// -----------------------------------------------------------------------------

/**
 * Riga di un package d'app: il NOME dell'app (se il telefono lo sa), il package in monospace (è quello
 * che entra nell'impronta) e «Cambia app». Se l'app non è installata c'è un chip ambra con icona e testo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppRefLine(
    ref: AppRef,
    info: AppInfoUi?,
    onChangeApp: ((AppRef) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val name = info?.name?.takeIf { it.isNotBlank() && it != ref.packageName }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (name != null) {
                Text(name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            }
            if (info != null && !info.installed) {
                StatusChip(ArgusTheme.semantic.pending, Icons.Rounded.AppBlocking, stringResource(R.string.rda_ap_app_not_installed))
            }
        }
        Text(ref.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = ArgusCodeSmall)
        if (onChangeApp != null) {
            // L'etichetta per TalkBack dice QUALE app: una regola può nominarne più d'una.
            val description = stringResource(R.string.rda_ap_change_app_cd, name ?: ref.packageName)
            Row(
                modifier = Modifier
                    .heightIn(min = ArgusSpacing.TouchTarget)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onChangeApp(ref) }
                    .semantics { contentDescription = description }
                    .padding(horizontal = ArgusSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.rda_ap_change_app),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Anteprime
// -----------------------------------------------------------------------------

private val previewShell = ActionRow(
    iconKey = "shell",
    label = "Esegui comando shell",
    detail = "Output in esito",
    isShell = true,
    shellCommand = "am broadcast -a dev.argus.example.OTP_FORWARD -n com.example.desktopbridge/.OtpReceiver --es code \"{{otp}}\" --es origine sms --ei batteria {{batteria}} && log -t argus \"otp inoltrato\"",
    isGenerative = false,
    requiresLiveConfirm = false,
)

private val previewTree = RuleRender(
    triggerLine = "Quando: SMS ricevuto con testo che contiene \"codice\"",
    triggerIconKey = "phone",
    conditionLines = listOf("Solo tra le 07:00 e le 23:00 (Europe/Rome)"),
    actions = emptyList(),
    isGenerative = false,
    privacyNote = null,
    vars = listOf(
        VarRow("otp", "testo", "esterno", "segreto", "SMS", sourceText = "campo testo · estrazione: [0-9]{6}", isExternal = true, sensitivity = VarSensitivity.SECRET),
        VarRow("batteria", "numero", "attendibile", "pubblico", "stato dispositivo", sourceText = "Livello batteria [numero]"),
    ),
    program = listOf(
        ProgramNode.Leaf(ActionRow("notify", "Copia negli appunti", "Testo: otp · cancellato dopo 60 s se invariato", false, null, false, false)),
        ProgramNode.IfNode(
            title = "Se:",
            conditionLines = listOf("batteria > 15"),
            thenTitle = "Allora:",
            then = listOf(
                ProgramNode.WhileNode(
                    title = "Ripeti fino a 3 volte · 2000 ms tra le iterazioni",
                    conditionLines = listOf("esito ≠ \"ok\""),
                    body = listOf(ProgramNode.Leaf(previewShell)),
                ),
            ),
            elseTitle = "Altrimenti:",
            orElse = listOf(
                ProgramNode.Leaf(ActionRow("notify", "Mostra notifica", "«OTP copiato — inoltro saltato: batteria bassa»", false, null, false, false)),
            ),
        ),
    ),
)

private val previewApps = RuleRender(
    triggerLine = "Quando: apri YouTube",
    triggerIconKey = "app_event",
    conditionLines = emptyList(),
    actions = emptyList(),
    isGenerative = false,
    privacyNote = null,
    triggerAppRef = AppRef(0, "com.youtube"),
    program = listOf(
        ProgramNode.Leaf(
            ActionRow("launch_app", "Apri app", null, false, null, false, false, appRef = AppRef(1, "com.android.chrome")),
        ),
    ),
)

@ArgusThemePreviews
@Composable
private fun ApprovalRuleTreePreview() {
    ArgusPreviewSurface {
        ApprovalRuleTimeline(previewTree, Modifier.padding(16.dp))
    }
}

@ArgusThemePreviews
@Composable
private fun ApprovalRuleAppsPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp)) {
            ApprovalRuleTimeline(
                rule = previewApps,
                geofenceLabel = null,
                appInfo = mapOf(
                    "com.youtube" to AppInfoUi(name = null, installed = false),
                    "com.android.chrome" to AppInfoUi(name = "Chrome", installed = true),
                ),
                onChangeApp = {},
            )
        }
    }
}
