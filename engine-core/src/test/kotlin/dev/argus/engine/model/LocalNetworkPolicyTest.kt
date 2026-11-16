package dev.argus.engine.model

import dev.argus.engine.model.LocalNetworkPolicy.HostScope
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalNetworkPolicyTest {
    private fun assertScope(expected: HostScope, hosts: List<String>) {
        hosts.forEach { host -> assertEquals(expected, LocalNetworkPolicy.scopeOf(host), "host <$host>") }
    }

    @Test
    fun `this phone is loopback`() {
        assertScope(
            HostScope.LOOPBACK,
            listOf(
                "localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "127.255.255.255",
                "::1", "[::1]", "0:0:0:0:0:0:0:1", "0000:0000:0000:0000:0000:0000:0000:0001",
            ),
        )
    }

    @Test
    fun `private ranges and local names are the local network`() {
        assertScope(
            HostScope.LOCAL_NETWORK,
            listOf(
                // IPv4 private, link-local and CGNAT (VPN meshes), edges included.
                "10.0.0.0", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.10", "192.168.255.255",
                "169.254.1.1", "100.64.0.1", "100.127.255.254",
                // IPv6 ULA and link-local, with and without brackets, any case.
                "fc00::1", "fd12:3456:789a::1", "FD00::ABCD", "[fd00::1]", "fe80::1", "fe80::a:b:c:d", "febf::1",
                "::ffff:192.168.0.10",
                // Names reserved for local use, and single labels.
                "studio.local", "gpu-box.lan", "nas.home.arpa", "llm.internal", "app.localhost",
                "NAS.LOCAL", "nas", "gpu-box", "x1",
            ),
        )
    }

    @Test
    fun `public hosts and anything ambiguous are public`() {
        assertScope(
            HostScope.PUBLIC,
            listOf(
                // Public addresses and the edges just outside the private ranges.
                "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.0", "192.169.0.1", "100.63.255.255",
                "100.128.0.0", "169.255.0.1", "11.0.0.1", "0.0.0.0",
                // Public names, and local-looking names that are not.
                "example.com", "api.openai.com", "localhost.evil.example", "my.local.example.org",
                "192.168.0.10.evil.com", "10.0.0.1.nip.io", "notlocal.com", "local.example",
                // Forms a resolver might read differently: short, octal, hex, bare numbers.
                "127.1", "10.1", "010.0.0.1", "192.168.000.010", "0x7f.0.0.1", "0x7f000001", "3232235530",
                "256.1.1.1", "1.2.3.4.5",
                // Trailing dots: absolute names, never trusted.
                "nas.", "192.168.0.10.", "localhost.", "studio.local.",
                // Not a bare host.
                "", "10.0.0.1@evil.com", "nas:8080", "evil.com/x.local", "nas_box", "-nas", "nas-", ".local",
                "a..local", " nas",
                // IPv6 public, unspecified, mapped public, with zone id or malformed.
                "2001:db8::1", "::", "::ffff:8.8.8.8", "fe80::1%wlan0", "fec0::1", "fbff::1", "fd00:::1",
                "1:2:3:4:5:6:7:8:9", "fd00::1::2", "gd00::1", "fd000::1",
            ),
        )
    }

    @Test
    fun `cleartext for an AI endpoint is allowed only off the internet`() {
        listOf("localhost", "127.0.0.1", "192.168.0.10", "fd00::1", "studio.local", "nas").forEach {
            assertEquals(true, LocalNetworkPolicy.allowsLlmCleartext(it), it)
        }
        listOf("example.com", "8.8.8.8", "192.168.0.10.evil.com", "2001:db8::1", "nas.").forEach {
            assertEquals(false, LocalNetworkPolicy.allowsLlmCleartext(it), it)
        }
    }

    @Test
    fun `loopback check is strict`() {
        assertEquals(true, LocalNetworkPolicy.isLoopback("127.0.0.1"))
        assertEquals(false, LocalNetworkPolicy.isLoopback("192.168.0.10"))
        assertEquals(false, LocalNetworkPolicy.isLoopback("localhost.evil.example"))
    }
}
