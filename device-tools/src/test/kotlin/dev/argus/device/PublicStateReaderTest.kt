package dev.argus.device

import dev.argus.engine.model.StateKeys
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicStateReaderTest {
    @Test
    fun `every registered key is read from the public source with the registry format`() {
        val source = FakePublicStateSource(
            battery = BatteryReading(level = 87, scale = 100, status = 2),
            screen = true,
            ringer = 2,
            filter = 1,
            airplane = 0,
            wifi = true,
            bluetooth = false,
        )

        val values = PublicStateReader(source).read(StateKeys.ALL.keys)

        assertEquals(
            mapOf(
                StateKeys.RINGER to "normal",
                StateKeys.WIFI to "on",
                StateKeys.BLUETOOTH to "off",
                StateKeys.DND to "off",
                StateKeys.AIRPLANE to "off",
                StateKeys.BATTERY to "87",
                StateKeys.CHARGING to "true",
                StateKeys.SCREEN to "on",
            ),
            values,
        )
    }

    @Test
    fun `only the requested keys are read`() {
        val source = FakePublicStateSource(
            battery = BatteryReading(50, 100, 3),
            screen = false,
            ringer = 0,
        )

        val values = PublicStateReader(source).read(setOf(StateKeys.SCREEN))

        assertEquals(mapOf(StateKeys.SCREEN to "off"), values)
        assertEquals(listOf("screen"), source.calls)
    }

    @Test
    fun `battery and charging share a single read of the sticky intent`() {
        val source = FakePublicStateSource(battery = BatteryReading(20, 100, 2))

        val values = PublicStateReader(source).read(setOf(StateKeys.BATTERY, StateKeys.CHARGING))

        assertEquals(mapOf(StateKeys.BATTERY to "20", StateKeys.CHARGING to "true"), values)
        assertEquals(listOf("battery"), source.calls)
    }

    @Test
    fun `an empty request reads nothing`() {
        val source = FakePublicStateSource()

        assertEquals(emptyMap(), PublicStateReader(source).read(emptySet()))
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun `a key outside the registry is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PublicStateReader(FakePublicStateSource()).read(setOf("brightness"))
        }
    }

    @Test
    fun `ringer modes map to the registry values and anything else is absent`() {
        assertEquals("silent", PublicStateReader.parseRinger(0))
        assertEquals("vibrate", PublicStateReader.parseRinger(1))
        assertEquals("normal", PublicStateReader.parseRinger(2))
        assertNull(PublicStateReader.parseRinger(-1))
        assertNull(PublicStateReader.parseRinger(3))
    }

    @Test
    fun `interruption filters map like zen mode and alarms only or unknown stay absent`() {
        assertEquals("off", PublicStateReader.parseDnd(1))
        assertEquals("priority", PublicStateReader.parseDnd(2))
        assertEquals("total", PublicStateReader.parseDnd(3))
        // ALARMS (4) e UNKNOWN (0): come il lettore Shizuku, nessun valore nel dominio.
        assertNull(PublicStateReader.parseDnd(4))
        assertNull(PublicStateReader.parseDnd(0))
        assertNull(PublicStateReader.parseDnd(99))
    }

    @Test
    fun `airplane mode accepts only zero and one`() {
        assertEquals("off", PublicStateReader.parseAirplane(0))
        assertEquals("on", PublicStateReader.parseAirplane(1))
        assertNull(PublicStateReader.parseAirplane(2))
        assertNull(PublicStateReader.parseAirplane(-1))
    }

    @Test
    fun `battery level is a percentage at the edges and absent when the intent is incoherent`() {
        assertEquals(0, PublicStateReader.parseBatteryLevel(BatteryReading(0, 100, 3)))
        assertEquals(100, PublicStateReader.parseBatteryLevel(BatteryReading(100, 100, 5)))
        assertEquals(50, PublicStateReader.parseBatteryLevel(BatteryReading(127, 254, 3)))
        // Scala diversa da 100: si normalizza, senza uscire da 0-100.
        assertEquals(99, PublicStateReader.parseBatteryLevel(BatteryReading(99, 100, 2)))
        assertNull(PublicStateReader.parseBatteryLevel(BatteryReading(-1, 100, 2)))
        assertNull(PublicStateReader.parseBatteryLevel(BatteryReading(50, -1, 2)))
        assertNull(PublicStateReader.parseBatteryLevel(BatteryReading(50, 0, 2)))
        assertNull(PublicStateReader.parseBatteryLevel(BatteryReading(101, 100, 2)))
    }

    @Test
    fun `charging follows the battery status exactly like the dumpsys reader`() {
        assertEquals(true, PublicStateReader.parseCharging(2))
        assertEquals(true, PublicStateReader.parseCharging(5))
        assertEquals(false, PublicStateReader.parseCharging(3))
        assertEquals(false, PublicStateReader.parseCharging(4))
        // UNKNOWN (1) o valore fuori range: assente, mai «false» inventato.
        assertNull(PublicStateReader.parseCharging(1))
        assertNull(PublicStateReader.parseCharging(-1))
        assertNull(PublicStateReader.parseCharging(0))
    }

    @Test
    fun `incoherent battery data leaves battery absent but keeps charging`() {
        val source = FakePublicStateSource(battery = BatteryReading(level = -1, scale = 100, status = 2))

        val values = PublicStateReader(source).read(setOf(StateKeys.BATTERY, StateKeys.CHARGING))

        assertEquals(mapOf(StateKeys.CHARGING to "true"), values)
    }

    @Test
    fun `missing bluetooth permission leaves the key absent and never touches the adapter`() {
        val source = FakePublicStateSource(bluetooth = true, bluetoothPermission = false)

        val values = PublicStateReader(source).read(setOf(StateKeys.BLUETOOTH, StateKeys.SCREEN))

        assertEquals(emptyMap(), values.filterKeys { it == StateKeys.BLUETOOTH })
        assertTrue("bluetooth" !in source.calls)
    }

    @Test
    fun `without a bluetooth adapter the key is absent and never invented`() {
        val source = FakePublicStateSource(bluetooth = null, bluetoothPermission = true)

        assertEquals(emptyMap(), PublicStateReader(source).read(setOf(StateKeys.BLUETOOTH)))
    }

    @Test
    fun `an unreadable key stays absent while the others are still read`() {
        val source = FakePublicStateSource(
            screen = null,
            ringer = 1,
            wifi = null,
            airplane = null,
            filter = 0,
            battery = null,
        )

        val values = PublicStateReader(source).read(StateKeys.ALL.keys - StateKeys.BLUETOOTH)

        assertEquals(mapOf(StateKeys.RINGER to "vibrate"), values)
        assertTrue(values.values.none(String::isEmpty))
    }

    @Test
    fun `a system api that throws makes only that key absent`() {
        val source = FakePublicStateSource(
            screen = true,
            ringer = 2,
            wifi = true,
            throwing = setOf("wifi", "battery", "bluetooth"),
            bluetooth = true,
            battery = BatteryReading(80, 100, 2),
        )

        val values = PublicStateReader(source).read(StateKeys.ALL.keys)

        assertEquals(
            mapOf(StateKeys.SCREEN to "on", StateKeys.RINGER to "normal"),
            values.filterKeys { it == StateKeys.SCREEN || it == StateKeys.RINGER },
        )
        assertNull(values[StateKeys.WIFI])
        assertNull(values[StateKeys.BATTERY])
        assertNull(values[StateKeys.CHARGING])
        assertNull(values[StateKeys.BLUETOOTH])
    }

    @Test
    fun `public mappings agree with the Shizuku parsers so both paths give the same value`() {
        (-1..3).forEach { mode ->
            assertEquals(StateReader.parseRinger("$mode"), PublicStateReader.parseRinger(mode), "ringer $mode")
        }
        // zen_mode 0/1/2/3 corrisponde ai filtri di interruzione ALL/PRIORITY/NONE/ALARMS.
        listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4).forEach { (zen, filter) ->
            assertEquals(StateReader.parseDnd("$zen"), PublicStateReader.parseDnd(filter), "dnd $zen")
        }
        (0..6).forEach { status ->
            assertEquals(
                StateReader.parseBattery("status: $status\nlevel: 50").charging,
                PublicStateReader.parseCharging(status),
                "charging $status",
            )
        }
        listOf(0, 1, 50, 99, 100, 101, -1).forEach { level ->
            assertEquals(
                StateReader.parseBattery("status: 3\nlevel: $level").level,
                PublicStateReader.parseBatteryLevel(BatteryReading(level, 100, 3)),
                "level $level",
            )
        }
        listOf(0, 1, 2, 3).forEach { raw ->
            assertEquals(StateReader.parseBooleanSetting("$raw"), PublicStateReader.parseAirplane(raw), "airplane $raw")
        }
        assertEquals(StateReader.parseWifi("1"), PublicStateReader.parseOnOff(true))
        assertEquals(StateReader.parseWifi("0"), PublicStateReader.parseOnOff(false))
    }

    @Test
    fun `the library manifest declares the normal Wi-Fi state permission and nothing dangerous`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue("android.permission.ACCESS_WIFI_STATE" in manifest)
        listOf("LOCATION", "BLUETOOTH", "READ_PHONE", "SMS", "CONTACTS", "NOTIFICATION_POLICY")
            .forEach { assertTrue(it !in manifest, "il modulo non deve dichiarare permessi $it") }
    }
}

private class FakePublicStateSource(
    private val battery: BatteryReading? = null,
    private val screen: Boolean? = null,
    private val ringer: Int? = null,
    private val filter: Int? = null,
    private val airplane: Int? = null,
    private val wifi: Boolean? = null,
    private val bluetooth: Boolean? = null,
    private val bluetoothPermission: Boolean = true,
    private val throwing: Set<String> = emptySet(),
) : PublicStateSource {
    val calls = mutableListOf<String>()

    private fun <T> record(name: String, value: T?): T? {
        calls += name
        if (name in throwing) throw SecurityException("simulated: $name")
        return value
    }

    override fun battery() = record("battery", battery)
    override fun screenInteractive() = record("screen", screen)
    override fun ringerMode() = record("ringer", ringer)
    override fun interruptionFilter() = record("dnd", filter)
    override fun airplaneMode() = record("airplane", airplane)
    override fun wifiEnabled() = record("wifi", wifi)
    override fun canReadBluetooth() = bluetoothPermission
    override fun bluetoothEnabled() = record("bluetooth", bluetooth)
}
