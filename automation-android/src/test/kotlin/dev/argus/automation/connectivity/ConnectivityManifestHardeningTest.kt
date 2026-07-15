package dev.argus.automation.connectivity

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectivityManifestHardeningTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `bluetooth acl receiver and runtime permission are declared`() {
        assertTrue("android.permission.BLUETOOTH_CONNECT" in manifest)
        assertTrue("dev.argus.automation.connectivity.BluetoothConnectivityReceiver" in manifest)
        assertTrue("android.bluetooth.device.action.ACL_CONNECTED" in manifest)
        assertTrue("android.bluetooth.device.action.ACL_DISCONNECTED" in manifest)
    }

    @Test
    fun `power is not falsely declared as a manifest implicit receiver`() {
        assertFalse("android.intent.action.ACTION_POWER_CONNECTED" in manifest)
        assertFalse("android.intent.action.ACTION_POWER_DISCONNECTED" in manifest)
    }

    @Test
    fun `sentinel declares the Android 14 special use foreground service contract`() {
        assertTrue("android.permission.FOREGROUND_SERVICE" in manifest)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in manifest)
        assertTrue("dev.argus.automation.connectivity.ConnectivitySentinelService" in manifest)
        assertTrue("android:foregroundServiceType=\"specialUse\"" in manifest)
        assertTrue("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in manifest)
    }
}
