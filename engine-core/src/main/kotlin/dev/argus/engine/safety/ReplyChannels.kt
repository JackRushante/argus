package dev.argus.engine.safety

import dev.argus.engine.model.FeatureGate

/**
 * Le app di messaggi a cui Argus può rispondere da una notifica, e come si riconosce la conversazione.
 *
 * Due famiglie, con le stesse garanzie (chat 1:1, conversazione whitelistata, destinatario = chi ha
 * scritto, ricontrollo all'invio):
 *  - WhatsApp: `whatsapp_reply`. Gli id whitelistati prima della 0.8 restano validi così come sono.
 *  - [NOTIFICATION_REPLY_PACKAGES]: `notification_reply` (0.8). Qui la conversazione è SOLO quella che
 *    l'app dichiara con lo shortcut di conversazione della notifica (`Notification.getShortcutId`, la
 *    chiave del sistema «Conversazioni» di Android 11+), che il parser trasforma in
 *    `shortcut:<package>:<sha256>`. Le Person URI non bastano: per gli SMS sono numeri di telefono o
 *    lookup della rubrica che cambiano, e in un gruppo descrivono l'ultimo mittente, non la chat.
 *
 * Il package fa parte della chiave: lo stesso contatto su Telegram e su Signal è due voci di whitelist
 * diverse, e una chat Telegram non può mai far partire una risposta su un'altra app.
 *
 * Esclusa: Samsung Messaggi ([REFUSED_PACKAGES]). Non c'è modo di verificare qui che le sue notifiche
 * portino uno shortcut di conversazione stabile, il flag di gruppo e il mittente «io» di MessagingStyle;
 * senza questi la chat non si identifica, quindi il validator la rifiuta invece di lasciarla fallire allo
 * scatto.
 */
object ReplyChannels {
    val WHATSAPP_PACKAGES: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** Telegram (Play e sito), Signal, Google Messaggi. */
    val NOTIFICATION_REPLY_PACKAGES: Set<String> = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging",
    )

    /** App di messaggi valutate e rifiutate: nessuna chiave di conversazione su cui contare. */
    val REFUSED_PACKAGES: Set<String> = setOf("com.samsung.android.messaging")

    private val APP_NAMES: Map<String, String> = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal",
        "com.google.android.apps.messaging" to "Google Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
    )

    private val SHORTCUT_CONVERSATION = Regex("^shortcut:([A-Za-z][A-Za-z0-9_.]*):[0-9a-f]{64}$")

    fun isWhatsApp(pkg: String): Boolean = pkg in WHATSAPP_PACKAGES

    /** Package di `notification_reply`: solo con la feature accesa. */
    fun isNotificationReplyPackage(pkg: String): Boolean =
        FeatureGate.ACTION_NOTIFICATION_REPLY.enabled && pkg in NOTIFICATION_REPLY_PACKAGES

    /** Package a cui una risposta (statica o dell'AI) può andare. */
    fun isReplyPackage(pkg: String): Boolean = isWhatsApp(pkg) || isNotificationReplyPackage(pkg)

    /**
     * La conversazione [conversationId] appartiene a [pkg] con una chiave su cui contare. WhatsApp: come
     * prima (id whitelistati a mano compresi). Le altre app: solo lo shortcut di conversazione dello
     * stesso package.
     */
    fun isStableConversation(pkg: String, conversationId: String): Boolean {
        if (isWhatsApp(pkg)) return true
        val match = SHORTCUT_CONVERSATION.matchEntire(conversationId) ?: return false
        return match.groupValues[1] == pkg
    }

    /** Il package di una conversazione osservata dal parser (`<fonte>:<package>:<hash>`), se c'è. */
    fun packageOf(conversationId: String): String? {
        val parts = conversationId.split(':')
        return parts.getOrNull(1)?.takeIf { parts.size == 3 && it.isNotEmpty() }
    }

    /** Nome leggibile dell'app di messaggi, o null se non è una di queste. */
    fun appName(pkg: String): String? = APP_NAMES[pkg]
}
