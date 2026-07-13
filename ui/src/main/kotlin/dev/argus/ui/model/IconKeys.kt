package dev.argus.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Mappa gli `iconKey`/`triggerIconKey` (stringhe nei contratti §6) sugli `ImageVector`
 * di `Icons.Rounded.*`, seguendo la mappa icone di docs/design/README.md §9.
 * Chiavi note assenti → si sceglie l'icona piu' vicina; fallback generico Icons.Rounded.Bolt.
 */
fun iconFor(key: String): ImageVector = when (key) {
    "notification" -> Icons.Rounded.Notifications
    "time" -> Icons.Rounded.Schedule
    "geofence" -> Icons.Rounded.MyLocation
    "phone" -> Icons.Rounded.Call
    "connectivity" -> Icons.Rounded.Wifi
    "wifi_off" -> Icons.Rounded.WifiOff
    "bluetooth" -> Icons.Rounded.Bluetooth
    "dnd" -> Icons.Rounded.DoNotDisturbOn
    "shell" -> Icons.Rounded.Terminal
    "generative" -> Icons.Rounded.SmartToy
    "notify" -> Icons.Rounded.NotificationsActive
    "cloud" -> Icons.Rounded.CloudUpload
    else -> Icons.Rounded.Bolt
}
