package dev.argus.brain

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportExceptionTest {
    @Test fun `bridge typealiases preserve constructor shape and kind identity`() {
        val error: TransportException = BridgeException(BridgeErrorKind.AUTH, "x", statusCode = 401)
        assertTrue(error is IOException)
        assertEquals(TransportErrorKind.AUTH, error.kind)
        assertEquals(401, error.statusCode)
        // I typealias sono identità di tipo, non tipi distinti.
        assertTrue(BridgeErrorKind.AUTH === TransportErrorKind.AUTH)
    }

    @Test fun `new rate limit and budget kinds exist and default statusCode is null`() {
        val rate = TransportException(TransportErrorKind.RATE_LIMIT, "slow down")
        val budget = TransportException(TransportErrorKind.BUDGET, "no funds")
        assertEquals(TransportErrorKind.RATE_LIMIT, rate.kind)
        assertEquals(TransportErrorKind.BUDGET, budget.kind)
        assertNull(rate.statusCode)
        assertNull(budget.statusCode)
    }

    @Test fun `provider message defaults to null and never reaches message or toString`() {
        assertNull(TransportException(TransportErrorKind.HTTP, "Provider HTTP 400", statusCode = 400).providerMessage)
        val exception = TransportException(
            TransportErrorKind.HTTP,
            "Provider HTTP 400",
            statusCode = 400,
            providerMessage = "unknown model",
        )
        assertEquals("unknown model", exception.providerMessage)
        assertFalse("unknown model" in exception.message.orEmpty())
        assertFalse("unknown model" in exception.toString())
    }

    @Test fun `exception toString never leaks tokens`() {
        // Il messaggio dell'eccezione è sempre nostro; la causa può contenere dettagli ma non
        // deve trapelare dal message/toString del confine.
        val exception = TransportException(
            kind = TransportErrorKind.HTTP,
            message = "Bridge HTTP 500",
            cause = IOException("Bearer sk-test-not-a-real-key-0123456789"),
        )
        assertFalse(exception.message!!.contains("sk-"))
        assertFalse(exception.toString().contains("sk-"))
    }
}
