package dev.argus.automation.notification

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationListenerWatchdogTest {
    private var armed = true
    private var granted = true
    private var connected = false
    private var rebinds = 0
    private val watchdog = NotificationListenerWatchdog(
        notificationRulesArmed = { armed },
        accessGranted = { granted },
        connected = { connected },
        requestRebind = { rebinds++ },
    )

    @Test
    fun `asks for a rebind when notification rules are armed and the listener is not connected`() = runTest {
        assertTrue(watchdog.check())
        assertEquals(1, rebinds)
    }

    @Test
    fun `does nothing when connected, without access or without notification rules`() = runTest {
        connected = true
        assertFalse(watchdog.check())
        connected = false
        granted = false
        assertFalse(watchdog.check())
        granted = true
        armed = false
        assertFalse(watchdog.check())
        assertEquals(0, rebinds)
    }

    @Test
    fun `the connection flag follows the service callbacks`() {
        NotificationListenerConnection.onConnected()
        assertTrue(NotificationListenerConnection.isConnected)
        NotificationListenerConnection.onDisconnected()
        assertFalse(NotificationListenerConnection.isConnected)
    }
}
