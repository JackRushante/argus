package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.RolePair

// =============================================================================
// Componenti di Sistema (redesign A, blocco LOG-SISTEMA; tavole «Sistema» e «Configura provider»).
// Stateless, solo token del tema. Servono la navigazione per sezione, le tessere compatte della salute,
// i riquadri del budget con barra e il chip di raggiungibilita' del Brain.
// =============================================================================

/** Voce della navigazione per sezione: [id] stabile, [badge] = conteggio ambra (es. «2 da correggere»). */
data class SystemNavItem(
    val id: String,
    val label: String,
    val badge: Int? = null,
    /** Lettura per TalkBack quando c'e' un conteggio («Salute, 2 da correggere»). */
    val badgeDescription: String? = null,
)

/**
 * Chip «Vai a una sezione» sotto il titolo di Sistema. Scorre in orizzontale se non entrano; ogni chip e' un
 * pulsante da 48 dp di area tattile (40 disegnati).
 */
@Composable
fun SystemSectionNav(
    items: List<SystemNavItem>,
    onSelect: (String) -> Unit,
    groupDescription: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ArgusSpacing.Large)
            .semantics { contentDescription = groupDescription },
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        items.forEach { item -> SystemNavChip(item, onClick = { onSelect(item.id) }) }
    }
}

@Composable
private fun SystemNavChip(item: SystemNavItem, onClick: () -> Unit) {
    val description = item.badgeDescription
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(ArgusTheme.surfaces.card2)
                .padding(start = 14.dp, end = if (item.badge != null) 8.dp else 14.dp)
                .then(
                    if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
        ) {
            Text(item.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            item.badge?.let { SystemCountBadge(it) }
        }
    }
}

/** Pillola ambra con un conteggio («2»): lo stesso ruolo caldo del badge della barra di navigazione. */
@Composable
fun SystemCountBadge(count: Int, modifier: Modifier = Modifier) {
    val semantic = ArgusTheme.semantic
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(semantic.attention)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), color = semantic.onAttention, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W700)
    }
}

/**
 * Tessera compatta della salute (meta' larghezza): icona in alto a sinistra, spunta di stato in alto a destra,
 * titolo e sottotitolo, e per le righe opt-in il pulsante «Attiva». I problemi da correggere e i testi lunghi
 * usano invece la tessera a tutta larghezza [HealthTile].
 *
 * @param onClick tocco sull'intera tessera (apre il pannello/permesso); `null` = solo informativa. Con
 *   [actionLabel] il tocco sta nel pulsante.
 */
@Composable
fun SystemHealthCompactTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    level: HealthTileLevel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    okContentDescription: String? = null,
) {
    val semantic = ArgusTheme.semantic
    val (container, glyph) = when (level) {
        HealthTileLevel.Ok -> semantic.armed.bg to semantic.armed.fg
        HealthTileLevel.Warn -> semantic.attentionContainer to semantic.attention
        HealthTileLevel.Neutral -> semantic.disabled.bg to semantic.disabled.fg
    }
    ArgusCard(
        modifier = modifier,
        shape = RoundedCornerShape(ArgusRadius.Lg),
        contentPadding = PaddingValues(14.dp),
        onClick = if (actionLabel == null) onClick else null,
    ) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                IconBadge(icon, container, glyph, size = 36.dp, iconSize = 20.dp, shape = RoundedCornerShape(ArgusRadius.Md - 2.dp))
                when (level) {
                    HealthTileLevel.Ok -> Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = okContentDescription,
                        tint = semantic.armed.fg,
                        modifier = Modifier.size(22.dp),
                    )
                    HealthTileLevel.Neutral -> Icon(
                        Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    HealthTileLevel.Warn -> Unit
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (actionLabel != null && onClick != null) {
                ArgusButton(actionLabel, onClick, style = ButtonStyle.Outlined)
            }
        }
    }
}

/**
 * Riquadro del budget: etichetta, valore ([value]), barra di avanzamento opzionale e nota. [progress] `null` =
 * nessun limite da mostrare (niente barra, mai una barra a zero che sembri «0 usato»).
 */
@Composable
fun SystemMeterCard(
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    footnote: String? = null,
    value: @Composable ColumnScope.() -> Unit,
) {
    ArgusCard(modifier = modifier, shape = RoundedCornerShape(ArgusRadius.Lg), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            value()
            if (progress != null) SystemProgressBar(progress)
            if (footnote != null) {
                Text(footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Valore grande di un riquadro con il suo suffisso («3» «/ 20»). */
@Composable
fun SystemBigValue(value: String, suffix: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit), verticalAlignment = Alignment.Bottom) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp))
        if (suffix != null) {
            Text(
                suffix,
                modifier = Modifier.padding(bottom = 3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Valore testuale medio (token, costo): 18/24 · 600. */
@Composable
fun SystemMediumValue(value: String, modifier: Modifier = Modifier) {
    Text(
        value,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 18.sp, lineHeight = 24.sp),
    )
}

/** Barra di avanzamento 8 dp: binario `field`, riempimento `primary`. Letta da TalkBack come intervallo 0..1. */
@Composable
fun SystemProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(ArgusTheme.surfaces.field)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Chip di stato con il pallino («raggiungibile»): il pallino e il testo dicono la stessa cosa, non solo il colore. */
@Composable
fun SystemDotChip(text: String, role: RolePair, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 26.dp)
            .clip(CircleShape)
            .background(role.bg)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(role.fg))
        Text(text, color = role.fg, style = MaterialTheme.typography.labelMedium)
    }
}

@ArgusThemePreviews
@Composable
private fun SystemTilesPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SystemSectionNav(
                items = listOf(
                    SystemNavItem("health", "Salute", badge = 2, badgeDescription = "Salute, 2 da correggere"),
                    SystemNavItem("brain", "Brain"),
                    SystemNavItem("privacy", "Privacy"),
                    SystemNavItem("whitelist", "Whitelist"),
                    SystemNavItem("budget", "Budget"),
                ),
                onSelect = {},
                groupDescription = "Vai a una sezione",
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemHealthCompactTile(
                    Icons.Rounded.BatterySaver, "Notifiche Argus", "consentite", HealthTileLevel.Ok,
                    modifier = Modifier.weight(1f), onClick = {}, okContentDescription = "Stato: a posto",
                )
                SystemHealthCompactTile(
                    Icons.Rounded.Sms, "Trigger SMS", "non attivi — solo SMS telefonici", HealthTileLevel.Neutral,
                    modifier = Modifier.weight(1f), onClick = {}, actionLabel = "Attiva",
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemMeterCard("Chiamate · ultima ora", Modifier.weight(1f), progress = 0.15f) { SystemBigValue("3", "/ 20") }
                SystemMeterCard("Chiamate · oggi", Modifier.weight(1f), progress = null) { SystemBigValue("11", "· illimitato") }
            }
            SystemMeterCard(
                "Token · mese (token-only)", Modifier.padding(horizontal = 16.dp), progress = 0.049f,
                footnote = "Limite: 48.500 / 1.000.000",
            ) { SystemMediumValue("in 42.000 · out 6.500") }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemDotChip("raggiungibile", ArgusTheme.semantic.armed)
                SystemDotChip("non raggiungibile", ArgusTheme.semantic.error)
                SystemDotChip("non verificato", ArgusTheme.semantic.disabled)
            }
        }
    }
}
