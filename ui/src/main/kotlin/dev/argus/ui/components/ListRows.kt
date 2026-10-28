package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Riga lista (tavola «Card regola», «Riga log»), contenitori icona, intestazioni di sezione.
// =============================================================================

/**
 * Riga di lista: [leading] (di norma un [TriggerContainer] o un [IconBadge]), titolo, sottotitolo, [meta]
 * (chip, orari) e [trailing] (interruttore, chevron).
 *
 * L'area tappabile e' tutto tranne [trailing], che ha il proprio tocco (es. [ArgusSwitch]). Altezza minima 56 dp.
 * Senza [onClick] la riga e' solo informativa. Va inserita in una card senza padding (`ArgusCardGroup`).
 *
 * @param titleSuffix elementi dopo il titolo sulla stessa riga (es. il triangolo «Ha avvisi»).
 * @param titleColor `onSurface` di default; `onSurfaceVariant` per le righe «rumore utile» (mai trasparenza).
 * @param subtitleColor `onSurfaceVariant` di default; per un esito colorato usare il `fg` del ruolo.
 */
@Composable
fun ArgusListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    titleSuffix: (@Composable RowScope.() -> Unit)? = null,
    meta: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentPadding: PaddingValues = PaddingValues(ArgusSpacing.Medium),
) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                    else Modifier,
                )
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
        ) {
            if (leading != null) leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f, fill = false),
                        color = titleColor,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (titleSuffix != null) titleSuffix()
                }
                if (subtitle != null) {
                    Text(subtitle, color = subtitleColor, style = MaterialTheme.typography.bodyMedium)
                }
                if (meta != null) meta()
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(end = ArgusSpacing.Medium), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

/** Triangolo «Ha avvisi» da passare come `titleSuffix` di [ArgusListRow]: [contentDescription] per TalkBack. */
@Composable
fun RowScope.WarningMark(contentDescription: String) {
    Icon(
        Icons.Rounded.WarningAmber,
        contentDescription = contentDescription,
        tint = ArgusTheme.semantic.pending.fg,
        modifier = Modifier.size(18.dp),
    )
}

/**
 * Contenitore tondo/quadrato con un'icona dentro: icona di stato nelle righe del log (30-32 dp, cerchio),
 * icona dei nodi (28 dp), ecc. [contentDescription] solo se l'icona porta un significato non scritto altrove.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 18.dp,
    shape: Shape = CircleShape,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier.size(size).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(iconSize))
    }
}

// ---------------------------------------------------------------------------------------------
// Contenitori espressivi dei trigger: il COLORE dice lo stato, la FORMA dice il tipo di trigger.
// ---------------------------------------------------------------------------------------------

/** Forma del contenitore per tipo di trigger (tavola «Contenitori trigger espressivi»). */
enum class TriggerShape {
    /** Orario. */
    Circle,
    /** Notifica. */
    Squircle,
    /** Geofence. */
    Drop,
    /** Telefono / SMS. */
    Rhombus,
    /** Connettivita'. */
    Arch,
    /** Sensore / immediato. */
    Cookie,
}

/** Stato mostrato dal colore del contenitore: blu normale, ambra in attesa di te, arancio da rivedere, grigio disattivata. */
enum class TriggerTone { Normal, Pending, NeedsReview, Disabled }

/** Cookie a 9 lobi della tavola: cerchio centrale + 8 cerchi sul bordo, su una griglia 72 x 72. */
private object CookieShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val k = size.width / 72f
        fun circle(cx: Float, cy: Float, r: Float) = Path().apply {
            addOval(Rect(Offset((cx - r) * k, (cy - r) * k), Size(2 * r * k, 2 * r * k)))
        }
        var path = circle(36f, 36f, 26f)
        listOf(
            36f to 12f, 53f to 19f, 60f to 36f, 53f to 53f,
            36f to 60f, 19f to 53f, 12f to 36f, 19f to 19f,
        ).forEach { (cx, cy) ->
            val joined = Path()
            joined.op(path, circle(cx, cy, 11f), PathOperation.Union)
            path = joined
        }
        return Outline.Generic(path)
    }
}

private fun TriggerShape.clipShape(): Shape = when (this) {
    TriggerShape.Circle -> CircleShape
    TriggerShape.Squircle -> RoundedCornerShape(ArgusRadius.Md)
    TriggerShape.Drop -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = ArgusRadius.Xs)
    TriggerShape.Arch -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 8.dp, bottomStart = 8.dp)
    TriggerShape.Cookie -> CookieShape
    TriggerShape.Rhombus -> RoundedCornerShape(ArgusRadius.Sm) // usato ruotato di 45 gradi
}

@Composable
private fun triggerColors(tone: TriggerTone): Pair<Color, Color> {
    val s = ArgusTheme.semantic
    return when (tone) {
        TriggerTone.Normal -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        TriggerTone.Pending -> s.pending.bg to s.pending.fg
        TriggerTone.NeedsReview -> s.needsReview.bg to s.needsReview.fg
        TriggerTone.Disabled -> s.disabled.bg to s.disabled.fg
    }
}

/**
 * Contenitore 44 dp dell'icona di un trigger. Decorativo (l'informazione e' nel titolo e nel sottotitolo
 * della riga), quindi senza `contentDescription`.
 */
@Composable
fun TriggerContainer(
    icon: ImageVector,
    shape: TriggerShape,
    tone: TriggerTone,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val (container, content) = triggerColors(tone)
    if (shape == TriggerShape.Rhombus) {
        // Quadrato arrotondato ruotato di 45 gradi (34 dp dentro lo spazio 44), icona non ruotata.
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 34f / 44f).rotate(45f).clip(shape.clipShape()).background(container))
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 22f / 44f))
        }
    } else {
        IconBadge(icon, container, content, modifier, size = size, iconSize = size * 22f / 44f, shape = shape.clipShape())
    }
}

// ---------------------------------------------------------------------------------------------
// Intestazione di sezione.
// ---------------------------------------------------------------------------------------------

/** Intestazione di sezione: 11 sp, 700, maiuscolo, +0,08 em, `onSurfaceVariant`. Esposta a TalkBack come titolo. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
        overflow = TextOverflow.Clip,
    )
}

@ArgusThemePreviews
@Composable
private fun ListRowsPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Armate", Modifier.padding(start = 4.dp))
            ArgusCardGroup(listOf(0, 1, 2)) { i ->
                when (i) {
                    0 -> ArgusListRow(
                        title = "Casa · spegni Wi-Fi uscendo",
                        subtitle = "Geofence \"Casa\" · in uscita",
                        leading = { TriggerContainer(Icons.Rounded.LocationOn, TriggerShape.Drop, TriggerTone.Normal) },
                        titleSuffix = { WarningMark("Ha avvisi") },
                        meta = { StatusBadgeChip(dev.argus.ui.model.StatusBadge.ARMED) },
                        trailing = { ArgusSwitch(checked = true, onCheckedChange = {}, contentDescription = "Disattiva") },
                        onClick = {},
                    )
                    1 -> ArgusListRow(
                        title = "Notifica in attesa",
                        subtitle = "Notifica WhatsApp · Moglie",
                        leading = { TriggerContainer(Icons.Rounded.Notifications, TriggerShape.Squircle, TriggerTone.Pending) },
                        onClick = {},
                    )
                    else -> ArgusListRow(
                        title = "Ufficio · silenzia entrando",
                        subtitle = "Disattivata",
                        leading = { TriggerContainer(Icons.Rounded.Schedule, TriggerShape.Circle, TriggerTone.Disabled) },
                        trailing = { ArgusSwitch(checked = false, onCheckedChange = {}, contentDescription = "Riattiva") },
                        onClick = {},
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TriggerContainer(Icons.Rounded.Sms, TriggerShape.Rhombus, TriggerTone.NeedsReview)
                TriggerContainer(Icons.Rounded.Wifi, TriggerShape.Arch, TriggerTone.Normal)
                TriggerContainer(Icons.Rounded.Bolt, TriggerShape.Cookie, TriggerTone.Normal)
            }
        }
    }
}
