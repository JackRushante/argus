package dev.argus.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S6: il foglio avvisa che la chiave salvata si toglie quando cambia l'origine del server. */
class ServerOriginTest {
    @Test
    fun `a different path on the same server keeps the origin`() {
        assertTrue(sameServerOrigin("https://api.example.com/v1", "https://api.example.com/v2"))
        assertTrue(sameServerOrigin("https://API.example.com/v1", " https://api.example.com:443/v1 "))
        assertTrue(sameServerOrigin("http://192.168.1.20:8080/v1", "http://192.168.1.20:8080"))
    }

    @Test
    fun `another scheme host or port is another origin`() {
        assertFalse(sameServerOrigin("https://api.example.com/v1", "http://api.example.com/v1"))
        assertFalse(sameServerOrigin("https://api.example.com/v1", "https://api.example.com:8443/v1"))
        assertFalse(sameServerOrigin("https://api.example.com/v1", "http://127.0.0.1:11434/v1"))
        assertFalse(sameServerOrigin("https://api.example.com/v1", "https://api.example.com.evil.net/v1"))
    }

    @Test
    fun `an address that does not parse counts as another origin`() {
        assertFalse(sameServerOrigin("https://api.example.com/v1", ""))
        assertFalse(sameServerOrigin("https://api.example.com/v1", "api.example.com/v1"))
        assertFalse(sameServerOrigin("", ""))
    }
}
