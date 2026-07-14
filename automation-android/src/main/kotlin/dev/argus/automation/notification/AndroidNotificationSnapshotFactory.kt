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
        )
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
