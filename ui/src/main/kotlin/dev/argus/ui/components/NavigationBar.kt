package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Barra di navigazione (tavola «Automazioni», sezione nav). Stesse 4 tab di sempre.
// Indicatore a pillola `secondaryContainer`, etichetta sempre visibile (selezionata 700 / `onSurface`,
// le altre 500 / `onSurfaceVariant`), fondo `nav`.
// =============================================================================

/** Badge su una voce della barra. */
sealed interface NavBadge {
    /** Conteggio su pillola ambra (es. regole in approvazione). */
    data class Count(val value: Int) : NavBadge
    /** Punto esclamativo rosso: problemi da correggere. */
    data object Alert : NavBadge
    /** Punto rosso senza testo: contenuto non letto. */
    data object Dot : NavBadge
}

/**
 * Voce della barra.
 *
 * @param badgeDescription cosa significa il badge, per TalkBack («2 in approvazione»): la voce viene letta
 *   «Automazioni, 2 in approvazione». Obbligatorio quando c'e' un [badge]: un badge muto non dice nulla.
 * @param testTag tag per i test strumentati (`nav_<route>`).
 */
data class ArgusNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val badge: NavBadge? = null,
    val badgeDescription: String? = null,
    val testTag: String? = null,
)

/**
 * Barra di navigazione in basso. [selectedRoute] = route della voce attiva (`null` = nessuna).
 * Il padding per i gesti di sistema lo mette `NavigationBar` stessa (insets della finestra).
 */
@Composable
fun ArgusNavigationBar(
    items: List<ArgusNavItem>,
    selectedRoute: String?,
    onSelect: (route: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = ArgusTheme.semantic
    NavigationBar(modifier = modifier, containerColor = ArgusTheme.surfaces.nav, tonalElevation = 0.dp) {
        items.forEach { item ->
            val selected = item.route == selectedRoute
            NavigationBarItem(
                modifier = (if (item.testTag != null) Modifier.testTag(item.testTag) else Modifier)
                    .semantics {
                        if (item.badge != null && item.badgeDescription != null) {
                            contentDescription = "${item.label}, ${item.badgeDescription}"
                        }
                    },
                selected = selected,
                onClick = { onSelect(item.route) },
                alwaysShowLabel = true,
                icon = {
                    when (val badge = item.badge) {
                        null -> Icon(item.icon, contentDescription = null)
                        is NavBadge.Count -> BadgedBox(
                            badge = {
                                Badge(containerColor = semantic.attention, contentColor = semantic.onAttention) {
                                    Text("${badge.value}", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                        ) { Icon(item.icon, contentDescription = null) }
                        NavBadge.Alert -> BadgedBox(
                            badge = {
                                Badge(containerColor = scheme.error, contentColor = scheme.onError) {
                                    Text("!", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                        ) { Icon(item.icon, contentDescription = null) }
                        NavBadge.Dot -> BadgedBox(badge = { Badge(containerColor = scheme.error) }) {
                            Icon(item.icon, contentDescription = null)
                        }
                    }
                },
                label = {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = scheme.onSecondaryContainer,
                    selectedTextColor = scheme.onSurface,
                    indicatorColor = scheme.secondaryContainer,
                    unselectedIconColor = scheme.onSurfaceVariant,
                    unselectedTextColor = scheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@ArgusThemePreviews
@Composable
private fun NavigationBarPreview() {
    val items = listOf(
        ArgusNavItem("chat", "Chat", Icons.Rounded.ChatBubble, NavBadge.Dot, "messaggi non letti"),
        ArgusNavItem("list", "Automazioni", Icons.Rounded.Bolt, NavBadge.Count(2), "2 in approvazione"),
        ArgusNavItem("log", "Log", Icons.Rounded.History),
        ArgusNavItem("settings", "Sistema", Icons.Rounded.Tune, NavBadge.Alert, "problemi da correggere"),
    )
    ArgusPreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            ArgusNavigationBar(items, selectedRoute = "list", onSelect = {})
            ArgusNavigationBar(items, selectedRoute = null, onSelect = {})
        }
    }
}
