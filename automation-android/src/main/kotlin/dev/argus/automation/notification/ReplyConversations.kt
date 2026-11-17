package dev.argus.automation.notification

import dev.argus.engine.notification.ObservedConversation
import dev.argus.engine.safety.ReplyChannels

/**
 * Una conversazione osservata a cui una regola potrà rispondere, quindi candidata alla whitelist: di
 * WhatsApp (come prima) o di Telegram, Signal e Google Messaggi con lo shortcut di conversazione
 * dell'app (0.8, [ReplyChannels]).
 */
internal fun ObservedConversation.isReplyConversation(): Boolean =
    ReplyChannels.isReplyPackage(packageName) && ReplyChannels.isStableConversation(packageName, id)
