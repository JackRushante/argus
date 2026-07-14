package dev.argus.automation.notification

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import dev.argus.engine.notification.NotificationSnapshot

/** Estrae solo primitive bounded dal framework; policy e hashing restano nel parser core. */
class AndroidNotificationSnapshotFactory {
    fun from(statusBarNotification: StatusBarNotification): NotificationSnapshot = from(
        notification = statusBarNotification.notification,
        packageName = statusBarNotification.packageName,
        notificationKey = statusBarNotification.key,
        postedAtMillis = statusBarNotification.postTime,
    )

    fun from(
        notification: Notification,
        packageName: String,
        notificationKey: String,
        postedAtMillis: Long,
    ): NotificationSnapshot {
        val extras = notification.extras
        val latestMessage = runCatching {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                messageBundles(extras),
            ).lastOrNull { !it.text.isNullOrBlank() }
        }.getOrNull()
        val people = people(extras)
        val personUris = buildList {
            latestMessage?.senderPerson?.uri?.let(::add)
            people.mapNotNullTo(this) { it.uri }
        }.distinct()
        val explicitGroup = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        } else {
            null
        }
        return NotificationSnapshot(
            packageName = packageName,
            notificationKey = notificationKey,
            shortcutId = notification.shortcutId,
            personUris = personUris,
            sender = latestMessage?.senderPerson?.name?.toString(),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            messageText = latestMessage?.text?.toString(),
            fallbackText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            isGroup = explicitGroup,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            postedAtMillis = postedAtMillis,
            latestMessageTimestampMillis = latestMessage?.timestamp,
            latestMessageFromSelf = latestMessage?.let { fromSelf(it, extras) } ?: false,
        )
    }

    /** Convenzione MessagingStyle: sender null = utente; altrimenti confronto con la user Person. */
    private fun fromSelf(
        message: Notification.MessagingStyle.Message,
        extras: Bundle,
    ): Boolean {
        val sender = message.senderPerson ?: return true
        val user = runCatching { messagingUser(extras) }.getOrNull() ?: return false
        val senderUri = sender.uri
        val userUri = user.uri
        if (senderUri != null && userUri != null) return senderUri == userUri
        val senderName = sender.name?.toString()
        val userName = user.name?.toString()
        return senderName != null && senderName == userName
    }

    @Suppress("DEPRECATION")
    private fun messagingUser(extras: Bundle): Person? =
        if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person::class.java)
        } else {
            extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON)
        }

    @Suppress("DEPRECATION")
    private fun messageBundles(extras: Bundle): Array<out Parcelable>? =
        if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }

    @Suppress("DEPRECATION")
    private fun people(extras: android.os.Bundle): List<Person> = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java).orEmpty()
        } else {
            extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST).orEmpty()
        }
    }.getOrDefault(emptyList())
}
