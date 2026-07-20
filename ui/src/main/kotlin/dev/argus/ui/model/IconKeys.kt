package dev.argus.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
    // Azioni con icona dedicata: evitano che azioni rischiose condividano il
    // fallback generico Bolt con quelle benigne (carry-over review Unit B, design §9).
    "ringer" -> Icons.AutoMirrored.Rounded.VolumeUp
    "launch_app" -> Icons.AutoMirrored.Rounded.Launch
    "open_url" -> Icons.Rounded.Link
    "tap" -> Icons.Rounded.TouchApp
    "input_text" -> Icons.Rounded.Keyboard
    "whatsapp_reply" -> Icons.AutoMirrored.Rounded.Reply
    "control_flow" -> Icons.Rounded.AccountTree // P4: if/while/wait — nodi di flusso
    else -> Icons.Rounded.Bolt
}
