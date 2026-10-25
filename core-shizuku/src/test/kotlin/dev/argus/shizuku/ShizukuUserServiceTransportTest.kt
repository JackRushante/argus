package dev.argus.shizuku

import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ShizukuUserServiceTransportTest {
    private var now = 0L

    private fun authorizedApi() = FakeShizukuApi(installed = true, alive = true, granted = true)

    private suspend fun ShizukuUserServiceTransport.failureCode(): String =
        assertFailsWith<ShizukuShellException> { connectedService() }.code

    @Test
    fun `not authorized or not running fails fast as unavailable without binding`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true, granted = false)
        val binder = FakeBinder()
        ShizukuGateway(api).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })

            assertEquals("shizuku_unavailable", transport.failureCode())
            api.alive = false
            assertEquals("shizuku_unavailable", transport.failureCode())
            api.installed = false
            assertEquals("shizuku_unavailable", transport.failureCode())
            assertEquals(0, binder.bindCalls)
        }
    }

    @Test
    fun `a Shizuku older than the user service is reported as unsupported`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true, old = true, granted = true)
        val binder = FakeBinder()
        ShizukuGateway(api).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })

            assertEquals("shizuku_unsupported", transport.failureCode())
            assertEquals(0, binder.bindCalls)
        }
    }

    @Test
    fun `a bind that never completes times out then later requests fail fast for 60 seconds`() = runTest {
        val binder = FakeBinder { awaitCancellation() }
        ShizukuGateway(authorizedApi()).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })

            assertEquals("shizuku_timeout", transport.failureCode())
            assertEquals(1, binder.bindCalls)

            // Dentro la finestra: nessun nuovo bind, nessuna attesa, codice "unavailable".
            now += 30_000L
            assertEquals("shizuku_unavailable", transport.failureCode())
            now += 29_999L
            assertEquals("shizuku_unavailable", transport.failureCode())
            assertEquals(1, binder.bindCalls)

            // Scaduta la finestra si ritenta un bind.
            now += 1L
            assertEquals("shizuku_timeout", transport.failureCode())
            assertEquals(2, binder.bindCalls)
        }
    }

    @Test
    fun `a failed bind is unavailable and also opens the breaker`() = runTest {
        val binder = FakeBinder { throw IllegalStateException("Bind UserService fallito") }
        ShizukuGateway(authorizedApi()).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })

            assertEquals("shizuku_unavailable", transport.failureCode())
            assertEquals("shizuku_unavailable", transport.failureCode())
            assertEquals(1, binder.bindCalls)

            now += 60_000L
            assertEquals("shizuku_unavailable", transport.failureCode())
            assertEquals(2, binder.bindCalls)
        }
    }

    @Test
    fun `any change of Shizuku state clears the block so the next request binds again`() = runTest {
        val api = authorizedApi()
        val binder = FakeBinder { throw IllegalStateException("Bind UserService fallito") }
        ShizukuGateway(api).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })
            transport.failureCode()
            transport.failureCode()
            assertEquals(1, binder.bindCalls)

            // Shizuku si ferma e riparte: stato diverso, quindi blocco azzerato.
            api.alive = false
            gateway.status()
            api.alive = true
            gateway.status()

            transport.failureCode()
            assertEquals(2, binder.bindCalls)
        }
    }

    @Test
    fun `a binder event with an unchanged status also clears the block`() = runTest {
        val api = authorizedApi()
        val binder = FakeBinder { throw IllegalStateException("Bind UserService fallito") }
        ShizukuGateway(api).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })
            transport.failureCode()
            transport.failureCode()
            assertEquals(1, binder.bindCalls)

            api.deliverBinderReceived()

            transport.failureCode()
            assertEquals(2, binder.bindCalls)
        }
    }

    @Test
    fun `a successful bind closes the breaker and the live service is reused`() = runTest {
        var failNext = true
        val service = FakeService(uid = 2000)
        val binder = FakeBinder {
            if (failNext) throw IllegalStateException("Bind UserService fallito")
            service
        }
        ShizukuGateway(authorizedApi()).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })
            transport.failureCode()

            now += 60_000L
            failNext = false
            assertSame(service, transport.connectedService())
            assertSame(service, transport.connectedService())
            assertEquals(2, binder.bindCalls)

            // Il successo ha azzerato il blocco: un nuovo guasto riparte da un solo tentativo.
            binder.live = null
            failNext = true
            assertEquals("shizuku_unavailable", transport.failureCode())
            assertEquals(3, binder.bindCalls)
            assertEquals("shizuku_unavailable", transport.failureCode())
            assertEquals(3, binder.bindCalls)
        }
    }

    @Test
    fun `the identity is read once per service session after the bind`() = runTest {
        val service = FakeService(uid = 0)
        val binder = FakeBinder { service }
        ShizukuGateway(authorizedApi()).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })
            assertNull(gateway.observeIdentity().value)

            transport.connectedService()
            transport.connectedService()
            transport.connectedService()

            assertEquals(ShizukuIdentity(0), gateway.observeIdentity().value)
            assertEquals(true, gateway.observeIdentity().value?.runsAsRoot)
            assertEquals(1, service.uidCalls)
        }
    }

    @Test
    fun `a shell uid is not root and a new session reads the identity again`() = runTest {
        val first = FakeService(uid = 2000)
        val second = FakeService(uid = 0)
        var next: IPrivilegedShellService = first
        val binder = FakeBinder { next }
        ShizukuGateway(authorizedApi()).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })

            transport.connectedService()
            assertEquals(false, gateway.observeIdentity().value?.runsAsRoot)

            // Il servizio muore e Shizuku ne avvia uno nuovo (ora come root).
            binder.live = null
            next = second
            transport.connectedService()

            assertEquals(true, gateway.observeIdentity().value?.runsAsRoot)
            assertEquals(1, first.uidCalls)
            assertEquals(1, second.uidCalls)
        }
    }

    @Test
    fun `an unreadable identity leaves it unknown without failing the bind`() = runTest {
        val negative = FakeService(uid = -1)
        val broken = FakeService(uid = 0, failUid = true)
        var next: IPrivilegedShellService = negative
        val binder = FakeBinder { next }
        ShizukuGateway(authorizedApi()).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })

            assertSame(negative, transport.connectedService())
            assertNull(gateway.observeIdentity().value)

            binder.live = null
            next = broken
            assertSame(broken, transport.connectedService())
            assertNull(gateway.observeIdentity().value)
        }
    }

    @Test
    fun `losing Shizuku clears the identity of the bound service`() = runTest {
        val api = authorizedApi()
        val binder = FakeBinder { FakeService(uid = 0) }
        ShizukuGateway(api).use { gateway ->
            val transport = ShizukuUserServiceTransport(binder, gateway, clock = { now })
            transport.connectedService()
            assertEquals(ShizukuIdentity(0), gateway.observeIdentity().value)

            api.alive = false
            gateway.status()

            assertNull(gateway.observeIdentity().value)
        }
    }
}

private class FakeBinder(
    private val onBind: suspend () -> IPrivilegedShellService = { FakeService(uid = 2000) },
) : UserServiceBinder {
    var live: IPrivilegedShellService? = null
    var bindCalls = 0

    override fun liveService(): IPrivilegedShellService? = live

    override fun detach() {
        live = null
    }

    override suspend fun bind(): IPrivilegedShellService {
        bindCalls++
        return onBind().also { live = it }
    }

    override fun invalidate(remote: IPrivilegedShellService) {
        if (live === remote) live = null
    }

    override fun close() = Unit
}

private class FakeService(
    private val uid: Int,
    private val failUid: Boolean = false,
) : IPrivilegedShellService.Stub() {
    var uidCalls = 0

    override fun execute(
        command: Array<out String>?,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): Bundle = Bundle()

    override fun executeToFile(
        command: Array<out String>?,
        stdoutDestination: ParcelFileDescriptor?,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): Bundle = Bundle()

    override fun uid(): Int {
        uidCalls++
        if (failUid) throw IllegalStateException("remote")
        return uid
    }

    override fun destroy() = Unit
}
