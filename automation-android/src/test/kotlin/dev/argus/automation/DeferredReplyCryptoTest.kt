package dev.argus.automation

import dev.argus.data.DeferredReplyStore
import dev.argus.data.entities.DeferredReplyEntity
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.test.runTest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeferredReplyCryptoTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = DeferredReplyCipher { key }

    @Test
    fun `round trip never exposes plaintext and randomizes iv`() {
        val plaintext = "ok amore, arrivo alle 19:30"
        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertEquals(plaintext, cipher.decrypt(first))
        assertEquals(plaintext, cipher.decrypt(second))
        assertNotEquals(first, second, "IV casuale: lo stesso testo non produce lo stesso payload")
        assertFalse(plaintext in first)
    }

    @Test
    fun `tampered or truncated payloads are rejected`() {
        val encoded = cipher.encrypt("testo riservato")
        val tampered = buildString {
            append(encoded.dropLast(4))
            append(if (encoded.last() == 'A') "BBBB" else "AAAA")
        }
        assertFailsWith<Exception> { cipher.decrypt(tampered) }
        assertFailsWith<IllegalArgumentException> { cipher.decrypt("QUJD") }
    }

    @Test
    fun `sink persists ciphertext with trigger metadata and ttl`() = runTest {
        val store = RecordingDeferredStore()
        val sink = PersistentDeferredReplySink(
            store = store,
            cipher = cipher,
            ttlMillis = 1_000,
            nowMillis = { 5_000 },
        )

        assertTrue(sink.defer(context(), "risposta generata"))

        val saved = store.saved.single()
        assertEquals("execution-1", saved.executionId)
        assertEquals(0, saved.actionIndex)
        assertEquals("com.whatsapp", saved.packageName)
        assertEquals(5_000, saved.createdAtMillis)
        assertEquals(6_000, saved.expiresAtMillis)
        assertNull(saved.consumedAtMillis)
        assertFalse("risposta generata" in saved.payload, "il payload persistito è solo ciphertext")
        assertEquals("risposta generata", cipher.decrypt(saved.payload))
    }

    @Test
    fun `sink fails closed without a notification event or on storage and crypto errors`() = runTest {
        val store = RecordingDeferredStore()
        val sink = PersistentDeferredReplySink(store, cipher, ttlMillis = 1_000) { 5_000 }
        val timeContext = context().copy(
            event = TriggerEvent.TimeFired(
                AutomationId("automation-1"),
                ApprovalFingerprint("0".repeat(64)),
            ),
        )
        assertFalse(sink.defer(timeContext, "testo"))
        assertEquals(emptyList(), store.saved)

        store.result = false
        assertFalse(sink.defer(context(), "testo"))

        store.result = true
        store.failure = IllegalStateException("db offline")
        assertFalse(sink.defer(context(), "testo"))

        val brokenCipher = DeferredReplyCipher { error("keystore offline") }
        assertFalse(
            PersistentDeferredReplySink(RecordingDeferredStore(), brokenCipher, 1_000) { 5_000 }
                .defer(context(), "testo"),
        )
    }

    private fun context() = FireContext(
        event = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            conversationId = "shortcut:com.whatsapp:hash",
            isGroup = false,
            notificationKey = "sbn:1",
        ),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("notification:event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private class RecordingDeferredStore : DeferredReplyStore {
        val saved = mutableListOf<DeferredReplyEntity>()
        var result = true
        var failure: Exception? = null

        override suspend fun save(entity: DeferredReplyEntity): Boolean {
            failure?.let { throw it }
            if (result) saved += entity
            return result
        }

        override suspend fun firstActionable(
            executionId: String,
            nowMillis: Long,
        ): DeferredReplyEntity? = null

        override suspend fun markConsumed(
            executionId: String,
            actionIndex: Int,
            atMillis: Long,
        ): Boolean = false

        override suspend fun purgeExpired(nowMillis: Long): Int = 0
        override suspend fun clear(): Int = 0
    }
}
