package dev.argus.shizuku

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShizukuGatewayInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun permissionAndUserServiceShellRoundTrip() = runBlocking {
        val gateway = ShizukuGateway(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shell = ShizukuPrivilegedShell(context, gateway, scope)
        try {
            if (gateway.status() != ShizukuGatewayStatus.AUTHORIZED) {
                assertEquals(
                    "Approva il dialog Shizuku sul dispositivo",
                    ShizukuPermissionResult.GRANTED,
                    withTimeout(60_000) { gateway.requestPermission() },
                )
            }

            val echo = withTimeout(40_000) {
                shell.run(listOf("/system/bin/echo", "argus"))
            }
            assertTrue(echo.successful)
            assertEquals("argus", echo.stdoutText.trim())

            val identity = withTimeout(40_000) {
                shell.run(listOf("/system/bin/id"))
            }
            assertTrue(identity.successful)
            assertTrue(
                "UserService deve essere shell o root: ${identity.stdoutText.trim()}",
                "uid=2000(shell)" in identity.stdoutText || "uid=0(root)" in identity.stdoutText,
            )
        } finally {
            shell.close()
            scope.cancel()
            gateway.close()
        }
    }
}
