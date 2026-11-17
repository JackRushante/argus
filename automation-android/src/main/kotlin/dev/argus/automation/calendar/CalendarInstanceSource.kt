package dev.argus.automation.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.argus.engine.runtime.CalendarInstance

/**
 * Le istanze del calendario del telefono che si sovrappongono a un intervallo. null = non leggibili
 * (permesso `READ_CALENDAR` assente, provider assente o errore): chi legge le tratta come UNKNOWN.
 */
fun interface CalendarInstanceSource {
    fun read(fromMillis: Long, toMillis: Long): List<CalendarInstance>?
}

/**
 * `CalendarContract.Instances`: una riga per ricorrenza, già espansa dal provider. Titolo e nome del
 * calendario restano in RAM, non entrano mai nei log.
 */
class AndroidCalendarInstanceSource(context: Context) : CalendarInstanceSource {
    private val appContext = context.applicationContext

    override fun read(fromMillis: Long, toMillis: Long): List<CalendarInstance>? {
        if (toMillis < fromMillis) return null
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, fromMillis)
            ContentUris.appendId(builder, toMillis)
        }.build()
        return try {
            appContext.contentResolver.query(uri, PROJECTION, null, null, SORT)?.use(::rows)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun rows(cursor: Cursor): List<CalendarInstance> = buildList {
        while (size < MAX_INSTANCES && cursor.moveToNext()) {
            add(
                CalendarInstance(
                    eventId = cursor.getLong(COLUMN_EVENT_ID),
                    beginMillis = cursor.getLong(COLUMN_BEGIN),
                    endMillis = cursor.getLong(COLUMN_END),
                    title = cursor.getStringOrNull(COLUMN_TITLE),
                    calendarName = cursor.getStringOrNull(COLUMN_CALENDAR),
                    allDay = cursor.getInt(COLUMN_ALL_DAY) != 0,
                    declined = cursor.getIntOrNull(COLUMN_SELF_STATUS) ==
                        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                    free = cursor.getIntOrNull(COLUMN_AVAILABILITY) == CalendarContract.Events.AVAILABILITY_FREE,
                    canceled = cursor.getIntOrNull(COLUMN_STATUS) == CalendarContract.Events.STATUS_CANCELED,
                ),
            )
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private companion object {
        /** Un limite alla RAM: una settimana di impegni ne ha molti meno. */
        const val MAX_INSTANCES = 2_000

        val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.STATUS,
        )
        const val COLUMN_EVENT_ID = 0
        const val COLUMN_BEGIN = 1
        const val COLUMN_END = 2
        const val COLUMN_TITLE = 3
        const val COLUMN_CALENDAR = 4
        const val COLUMN_ALL_DAY = 5
        const val COLUMN_SELF_STATUS = 6
        const val COLUMN_AVAILABILITY = 7
        const val COLUMN_STATUS = 8
        const val SORT = "${CalendarContract.Instances.BEGIN} ASC"
    }
}
