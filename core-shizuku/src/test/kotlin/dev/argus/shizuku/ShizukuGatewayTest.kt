package dev.argus.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuGatewayTest {
    @Test
    fun `status is fail closed across install binder version and permission`() {
        val api = FakeShizukuApi()
        ShizukuGateway(api).use { gateway ->
            assertEquals(ShizukuGatewayStatus.NOT_INSTALLED, gateway.status())

            api.installed = true
            assertEquals(ShizukuGatewayStatus.INSTALLED_NOT_RUNNING, gateway.status())

            api.alive = true
            api.old = true
            assertEquals(ShizukuGatewayStatus.UNSUPPORTED, gateway.status())

            api.old = false
            assertEquals(ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED, gateway.status())

            api.granted = true
            assertEquals(ShizukuGatewayStatus.AUTHORIZED, gateway.status())
        }
    }

    @Test
    fun `permission request waits for matching result and refreshes status`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true)
        ShizukuGateway(api).use { gateway ->
            val result = async { gateway.requestPermission() }
            runCurrent()

            api.granted = true
            api.deliverPermission(api.lastRequestCode + 1, PackageManager.PERMISSION_GRANTED)
            runCurrent()
            assertEquals(false, result.isCompleted)
            api.deliverPermission(api.lastRequestCode, PackageManager.PERMISSION_GRANTED)

            assertEquals(ShizukuPermissionResult.GRANTED, result.await())
            assertEquals(ShizukuGatewayStatus.AUTHORIZED, gateway.status())
        }
    }

    @Test
    fun `permission rationale does not open a second dialog`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true, rationale = true)
        ShizukuGateway(api).use { gateway ->
            assertEquals(ShizukuPermissionResult.RATIONALE_REQUIRED, gateway.requestPermission())
            assertEquals(0, api.lastRequestCode)
        }
    }

    @Test
    fun `permission request proceeds after rationale was shown`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true, rationale = true)
        ShizukuGateway(api).use { gateway ->
            val result = async { gateway.requestPermission(rationaleShown = true) }
            runCurrent()

            api.granted = true
            api.deliverPermission(api.lastRequestCode, PackageManager.PERMISSION_GRANTED)

            assertEquals(ShizukuPermissionResult.GRANTED, result.await())
            assertEquals(1, api.requestCount)
        }
    }

    @Test
    fun `permission request times out fail closed and removes temporary listener`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true)
        ShizukuGateway(api).use { gateway ->
            val baselineListeners = api.permissionListenerCount

            assertEquals(ShizukuPermissionResult.UNAVAILABLE, gateway.requestPermission())
            assertEquals(baselineListeners, api.permissionListenerCount)
        }
    }

    @Test
    fun `permission requests are serialized and cancellation removes listener`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true)
        ShizukuGateway(api).use { gateway ->
            val baselineListeners = api.permissionListenerCount
            val first = async { gateway.requestPermission() }
            val second = async { gateway.requestPermission() }
            runCurrent()

            assertEquals(1, api.requestCount)
            assertEquals(baselineListeners + 1, api.permissionListenerCount)

            first.cancelAndJoin()
            runCurrent()
            assertEquals(2, api.requestCount)
            assertEquals(baselineListeners + 1, api.permissionListenerCount)

            second.cancelAndJoin()
            assertEquals(baselineListeners, api.permissionListenerCount)
        }
    }

    @Test
    fun `an unanswered permission request reports awaiting the user while the legacy call stays unavailable`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true)
        ShizukuGateway(api).use { gateway ->
            val baselineListeners = api.permissionListenerCount

            assertEquals(ShizukuPermissionOutcome.AWAITING_USER, gateway.requestPermissionOutcome())
            assertEquals(baselineListeners, api.permissionListenerCount)

            assertEquals(ShizukuPermissionResult.UNAVAILABLE, gateway.requestPermission())
        }
    }

    @Test
    fun `unavailable states are not reported as awaiting the user`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = false)
        ShizukuGateway(api).use { gateway ->
            assertEquals(ShizukuPermissionOutcome.UNAVAILABLE, gateway.requestPermissionOutcome())
            assertEquals(0, api.requestCount)
        }
    }

    @Test
    fun `a permission answered after the timeout updates the status by itself`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true)
        ShizukuGateway(api).use { gateway ->
            assertEquals(ShizukuPermissionOutcome.AWAITING_USER, gateway.requestPermissionOutcome())
            val requestCode = api.lastRequestCode
            assertEquals(ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED, gateway.observeStatus().value)

            api.granted = true
            api.deliverPermission(requestCode, PackageManager.PERMISSION_GRANTED)

            assertEquals(ShizukuGatewayStatus.AUTHORIZED, gateway.observeStatus().value)
        }
    }

    @Test
    fun `granted and denied answers keep their own outcome`() = runTest {
        val api = FakeShizukuApi(installed = true, alive = true)
        ShizukuGateway(api).use { gateway ->
            val denied = async { gateway.requestPermissionOutcome() }
            runCurrent()
            api.deliverPermission(api.lastRequestCode, PackageManager.PERMISSION_DENIED)
            assertEquals(ShizukuPermissionOutcome.DENIED, denied.await())

            val granted = async { gateway.requestPermissionOutcome() }
            runCurrent()
            api.granted = true
            api.deliverPermission(api.lastRequestCode, PackageManager.PERMISSION_GRANTED)
            assertEquals(ShizukuPermissionOutcome.GRANTED, granted.await())
        }
    }

    @Test
    fun `identity tells whether the commands of the rules run as root`() {
        assertEquals(true, ShizukuIdentity(0).runsAsRoot)
        assertEquals(false, ShizukuIdentity(2000).runsAsRoot)
    }

    @Test
    fun `identity is null until the service reports it and clears when Shizuku goes away`() {
        val api = FakeShizukuApi(installed = true, alive = true, granted = true)
        ShizukuGateway(api).use { gateway ->
            assertNull(gateway.observeIdentity().value)

            gateway.publishIdentity(ShizukuIdentity(2000))
            assertEquals(ShizukuIdentity(2000), gateway.observeIdentity().value)
            assertEquals(false, gateway.observeIdentity().value?.runsAsRoot)

            gateway.publishIdentity(ShizukuIdentity(0))
            assertEquals(true, gateway.observeIdentity().value?.runsAsRoot)

            api.alive = false
            gateway.status()
            assertNull(gateway.observeIdentity().value)
        }
    }

    @Test
    fun `binder events clear the identity and always move the status epoch`() {
        val api = FakeShizukuApi(installed = true, alive = true, granted = true)
        ShizukuGateway(api).use { gateway ->
            gateway.publishIdentity(ShizukuIdentity(0))
            val before = gateway.statusEpoch()

            // Riavvio rapido di Shizuku: lo stato letto resta AUTHORIZED, ma è un'altra istanza.
            api.deliverBinderDead()

            assertNull(gateway.observeIdentity().value)
            assertTrue(gateway.statusEpoch() > before)
            val afterDead = gateway.statusEpoch()

            gateway.publishIdentity(ShizukuIdentity(2000))
            api.deliverBinderReceived()

            assertNull(gateway.observeIdentity().value)
            assertTrue(gateway.statusEpoch() > afterDead)
        }
    }

    @Test
    fun `status epoch moves on a real change of state and not on a re-read`() {
        val api = FakeShizukuApi(installed = true, alive = true)
        ShizukuGateway(api).use { gateway ->
            val start = gateway.statusEpoch()
            gateway.status()
            gateway.status()
            assertEquals(start, gateway.statusEpoch())

            api.granted = true
            gateway.status()
            assertTrue(gateway.statusEpoch() > start)
        }
    }
}
