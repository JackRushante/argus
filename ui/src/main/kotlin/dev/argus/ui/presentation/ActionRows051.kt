package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.PrivilegedActionPolicy
import dev.argus.engine.model.PrivilegedActionPolicy.PermissionGroup
import dev.argus.ui.model.ActionRow

/**
 * Resa dell'approvazione e del dettaglio per le azioni privilegiate tipizzate 0.5.1 (controllo app,
 * aereo, posizione, NFC, risparmio energetico, blocco schermo, screenshot, permessi). Come ogni resa
 * di [RuleRenderMapper] parte dai TIPI, mai dalla prosa del modello, ed è bilingue via [RenderLanguage].
 *
 * Il package reale è SEMPRE visibile (nel dettaglio), anche quando il titolo usa il nome comune
 * dell'app («Forza l'arresto di YouTube»): l'utente approva il package, non un'etichetta. Per i
 * permessi compare anche il nome completo `android.permission.*`.
 */
internal object ActionRows051 {

    fun appControl(a: Action.AppControl, l: RenderLanguage): ActionRow {
        val app = appName(a.pkg)
        val label = when (a.op) {
            AppControlOp.FORCE_STOP -> l.pick("Force stop $app", "Forza l'arresto di $app")
            AppControlOp.FREEZE -> l.pick("Freeze (disable) $app", "Congela (disattiva) $app")
            AppControlOp.UNFREEZE -> l.pick("Unfreeze (re-enable) $app", "Scongela (riattiva) $app")
        }
        return row("app_control", label, a.pkg)
    }

    fun setAirplane(a: Action.SetAirplane, l: RenderLanguage) = row(
        "airplane",
        if (a.on) l.pick("Turn on airplane mode", "Attiva la modalità aereo")
        else l.pick("Turn off airplane mode", "Disattiva la modalità aereo"),
    )

    fun setLocation(a: Action.SetLocation, l: RenderLanguage) = row(
        "location_services",
        if (a.on) l.pick("Turn on location", "Attiva la posizione")
        else l.pick("Turn off location", "Disattiva la posizione"),
    )

    fun setNfc(a: Action.SetNfc, l: RenderLanguage) = row(
        "nfc",
        if (a.on) l.pick("Turn on NFC", "Attiva l'NFC") else l.pick("Turn off NFC", "Disattiva l'NFC"),
    )

    fun setBatterySaver(a: Action.SetBatterySaver, l: RenderLanguage) = row(
        "battery_saver",
        if (a.on) l.pick("Turn on battery saver", "Attiva il risparmio energetico")
        else l.pick("Turn off battery saver", "Disattiva il risparmio energetico"),
    )

    fun lockScreen(l: RenderLanguage) = row(
        "lock_screen",
        l.pick("Lock the screen", "Blocca lo schermo"),
        l.pick("turns the screen off and locks the phone", "spegne lo schermo e blocca il telefono"),
    )

    fun screenshot(l: RenderLanguage) = row(
        "screenshot",
        l.pick("Take a screenshot", "Cattura una schermata"),
        l.pick("saved in Pictures/Argus", "salvata in Pictures/Argus"),
    )

    fun appPermission(a: Action.AppPermission, l: RenderLanguage): ActionRow {
        val app = appName(a.pkg)
        val group = PrivilegedActionPolicy.groupOf(a.permission)
        val label = if (group == null) {
            // Fuori dall'elenco chiuso il validator la rifiuta; la resa resta fedele: nome intero.
            if (a.grant) l.pick("Grant ${a.permission} to $app", "Concedi ${a.permission} a $app")
            else l.pick("Revoke ${a.permission} from $app", "Revoca ${a.permission} a $app")
        } else if (a.grant) {
            l.pick("Grant the ${groupEn(group)} permission to $app", "Concedi ${groupIt(group)} a $app")
        } else {
            l.pick("Revoke the ${groupEn(group)} permission from $app", "Revoca ${groupIt(group)} a $app")
        }
        return row("app_permission", label, "${a.permission} · ${a.pkg}")
    }

    private fun groupEn(group: PermissionGroup): String = when (group) {
        PermissionGroup.LOCATION -> "location"
        PermissionGroup.CAMERA -> "camera"
        PermissionGroup.MICROPHONE -> "microphone"
        PermissionGroup.CONTACTS -> "contacts"
        PermissionGroup.SMS -> "SMS"
        PermissionGroup.PHONE -> "phone"
        PermissionGroup.CALENDAR -> "calendar"
        PermissionGroup.STORAGE -> "files and media"
        PermissionGroup.NOTIFICATIONS -> "notifications"
        PermissionGroup.BODY_SENSORS -> "body sensors"
        PermissionGroup.NEARBY_DEVICES -> "nearby devices"
    }

    /** Con l'articolo, per leggere bene dopo «Concedi» e «Revoca» («Revoca il microfono a …»). */
    private fun groupIt(group: PermissionGroup): String = when (group) {
        PermissionGroup.LOCATION -> "la posizione"
        PermissionGroup.CAMERA -> "la fotocamera"
        PermissionGroup.MICROPHONE -> "il microfono"
        PermissionGroup.CONTACTS -> "i contatti"
        PermissionGroup.SMS -> "gli SMS"
        PermissionGroup.PHONE -> "il telefono"
        PermissionGroup.CALENDAR -> "il calendario"
        PermissionGroup.STORAGE -> "i file e i contenuti multimediali"
        PermissionGroup.NOTIFICATIONS -> "le notifiche"
        PermissionGroup.BODY_SENSORS -> "i sensori del corpo"
        PermissionGroup.NEARBY_DEVICES -> "i dispositivi nelle vicinanze"
    }

    /** Nome comune di qualche app nota; per le altre il package stesso (nessuna risorsa Android in JVM). */
    internal fun appName(pkg: String): String = KNOWN_APPS[pkg] ?: pkg

    private val KNOWN_APPS: Map<String, String> = mapOf(
        "com.google.android.youtube" to "YouTube",
        "com.android.chrome" to "Chrome",
        "com.whatsapp" to "WhatsApp",
        "com.spotify.music" to "Spotify",
        "com.instagram.android" to "Instagram",
        "org.telegram.messenger" to "Telegram",
        "com.google.android.apps.maps" to "Maps",
        "com.google.android.gm" to "Gmail",
        "com.facebook.katana" to "Facebook",
        "com.netflix.mediaclient" to "Netflix",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.google.android.apps.photos" to "Photos",
    )

    private fun row(iconKey: String, label: String, detail: String? = null) = ActionRow(
        iconKey = iconKey,
        label = label,
        detail = detail,
        isShell = false,
        shellCommand = null,
        isGenerative = false,
        requiresLiveConfirm = false,
    )
}
