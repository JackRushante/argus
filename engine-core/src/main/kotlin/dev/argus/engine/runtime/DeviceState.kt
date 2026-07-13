// DeviceState.kt
package dev.argus.engine.runtime
data class GeoPoint(val lat: Double, val lng: Double)
data class DeviceState(
    val values: Map<String, String> = emptyMap(),   // chiavi da StateKeys, es. "ringer" -> "normal"
    val foregroundApp: String? = null,
    val location: GeoPoint? = null,
)
