package dev.argus.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.data.JournalRetentionPolicy
import dev.argus.ui.model.LogRetentionOptions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogRetentionStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `untouched store keeps the maintenance default`() {
        val store = LogRetentionStore(context)
        assertEquals(JournalRetentionPolicy.DEFAULT_RETENTION_DAYS, store.currentDays())
        assertEquals(30, store.days.value)
    }

    @Test
    fun `only seven thirty or ninety days are accepted and persisted`() {
        val store = LogRetentionStore(context)

        assertTrue(store.setDays(7))
        assertEquals(7, store.currentDays())
        assertEquals(7, store.days.value)
        // Un'istanza nuova (riavvio del processo) legge lo stesso valore.
        assertEquals(7, LogRetentionStore(context).currentDays())

        assertTrue(store.setDays(90))
        assertEquals(90, LogRetentionStore(context).currentDays())

        assertFalse(store.setDays(14))
        assertFalse(store.setDays(0))
        assertFalse(store.setDays(-30))
        assertEquals(90, store.currentDays())
    }

    @Test
    fun `a corrupted stored value falls back to the default`() {
        context.getSharedPreferences("argus_log_retention", Context.MODE_PRIVATE)
            .edit().putInt("retention_days", 5).commit()
        assertEquals(30, LogRetentionStore(context).currentDays())
    }

    @Test
    fun `the options match what the log screen offers`() {
        assertEquals(LogRetentionStore.OPTIONS, LogRetentionOptions.DAYS)
        assertEquals(LogRetentionStore.DEFAULT_DAYS, LogRetentionOptions.DEFAULT_DAYS)
    }
}
