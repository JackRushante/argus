package dev.argus.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** La lettura dal Storage Access Framework non porta mai in memoria più del limite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentResolverDocumentIoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val io = ContentResolverDocumentIo(context, Dispatchers.Unconfined)

    @Test
    fun `a document up to the limit is read in full`() = runTest {
        val uri = Uri.parse("content://argus.test/at-limit")
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

        assertContentEquals(bytes, io.read(uri, maxBytes = 20_000))
    }

    @Test
    fun `a document over the limit returns null without being read to the end`() = runTest {
        val uri = Uri.parse("content://argus.test/over-limit")
        val endless = CountingStream()
        shadowOf(context.contentResolver).registerInputStream(uri, endless)

        assertNull(io.read(uri, maxBytes = 10_000))
        assertTrue(endless.served < 10_000 + 8 * 1024 + 1, "letti ${endless.served} byte da un flusso infinito")
        assertEquals(true, endless.served > 10_000)
    }

    private class CountingStream : InputStream() {
        var served = 0L
        override fun read(): Int {
            served++
            return 0x20
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            buffer.fill(0x20, offset, offset + length)
            served += length
            return length
        }
    }
}
