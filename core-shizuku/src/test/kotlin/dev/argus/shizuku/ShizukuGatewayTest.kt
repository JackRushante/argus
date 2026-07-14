package dev.argus.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

private class FakeShizukuApi(
    var installed: Boolean = false,
    var alive: Boolean = false,
    var old: Boolean = false,
    var granted: Boolean = false,
    var rationale: Boolean = false,
) : ShizukuApi {
    private val binderListeners = mutableSetOf<() -> Unit>()
    private val deadListeners = mutableSetOf<() -> Unit>()
    private val permissionListeners = mutableSetOf<(Int, Int) -> Unit>()
    var lastRequestCode: Int = 0
    var requestCount: Int = 0
    val permissionListenerCount: Int get() = permissionListeners.size

    override fun managerInstalled() = installed
    override fun binderAlive() = alive
    override fun preV11() = old
    override fun permissionGranted() = granted
    override fun shouldShowPermissionRationale() = rationale
    override fun requestPermission(requestCode: Int) {
        lastRequestCode = requestCode
        requestCount++
    }
    override fun addBinderReceivedListener(listener: () -> Unit) { binderListeners += listener }
    override fun removeBinderReceivedListener(listener: () -> Unit) { binderListeners -= listener }
    override fun addBinderDeadListener(listener: () -> Unit) { deadListeners += listener }
    override fun removeBinderDeadListener(listener: () -> Unit) { deadListeners -= listener }
    override fun addPermissionResultListener(listener: (Int, Int) -> Unit) {
        permissionListeners += listener
    }
    override fun removePermissionResultListener(listener: (Int, Int) -> Unit) {
        permissionListeners -= listener
    }

    fun deliverPermission(requestCode: Int, result: Int) {
        permissionListeners.toList().forEach { it(requestCode, result) }
    }
}
