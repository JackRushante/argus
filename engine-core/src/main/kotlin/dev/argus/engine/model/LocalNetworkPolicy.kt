package dev.argus.engine.model

import java.util.Locale

/**
 * Where a host lives, judged from its text only (issue #19). The single source of truth for "may an AI
 * endpoint be reached over plain `http://`": the settings form, the provider store, the transports and
 * the OkHttp guard all ask [allowsLlmCleartext], so the layers cannot drift apart.
 *
 * Pure and syntactic: no DNS lookup ever (a lookup can block, and a name says nothing about where it
 * resolves). A name that could be public is [HostScope.PUBLIC]. Every form a resolver might read in an
 * unexpected way (octal or hex IPv4, short IPv4 like `127.1`, a bare number, a trailing dot that turns a
 * single label into a top-level domain) is [HostScope.PUBLIC] too: refusing is always the safe answer.
 */
object LocalNetworkPolicy {
    enum class HostScope {
        /** This phone: `localhost`, 127.0.0.0/8, `::1`. */
        LOOPBACK,

        /**
         * The user's own network: private IPv4 (10/8, 172.16/12, 192.168/16), link-local 169.254/16,
         * CGNAT 100.64/10 (VPN meshes), IPv6 ULA fc00::/7 and link-local fe80::/10, and names that are
         * local by definition (`.local`, `.lan`, `.home.arpa`, `.internal`, `.localhost`, one label).
         */
        LOCAL_NETWORK,

        /** Anything else, including what cannot be parsed. */
        PUBLIC,
    }

    /** Suffixes reserved for local use: a public host can never end with one. */
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localhost")

    private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    /**
     * Scope of [rawHost]: a bare host as OkHttp or java.net.URI give it, with or without the IPv6
     * brackets, any case. Ports, user info or paths are not part of a host and make it [HostScope.PUBLIC].
     */
    fun scopeOf(rawHost: String): HostScope {
        val host = rawHost.removeSurrounding("[", "]").lowercase(Locale.ROOT)
        if (host.isEmpty() || host.length > 253) return HostScope.PUBLIC
        if (':' in host) return ipv6Scope(host)
        parseIpv4(host)?.let { return ipv4Scope(it) }
        return nameScope(host)
    }

    /**
     * Plain `http://` towards an AI endpoint is allowed only on this phone or on the user's network: an
     * API key and the prompts must never cross the internet unencrypted.
     */
    fun allowsLlmCleartext(host: String): Boolean = scopeOf(host) != HostScope.PUBLIC

    fun isLoopback(host: String): Boolean = scopeOf(host) == HostScope.LOOPBACK

    private fun nameScope(host: String): HostScope {
        // "foo." is an absolute name: a single label with a trailing dot is a top-level domain.
        if (host.endsWith('.')) return HostScope.PUBLIC
        val labels = host.split('.')
        if (labels.any { !LABEL.matches(it) }) return HostScope.PUBLIC
        // Every label numeric = an IPv4 form we did not accept above (short, octal, a bare number).
        if (labels.all { label -> label.all(Char::isDigit) }) return HostScope.PUBLIC
        if (host == "localhost") return HostScope.LOOPBACK
        if (labels.size == 1) {
            // A single label is resolved on the local network; it must not look like a number (0x7f...).
            return if (host.first().isLetter()) HostScope.LOCAL_NETWORK else HostScope.PUBLIC
        }
        return if (LOCAL_SUFFIXES.any { host.endsWith(it) }) HostScope.LOCAL_NETWORK else HostScope.PUBLIC
    }

    /** Strict dotted decimal: four parts 0..255, no leading zeros (a resolver may read them as octal). */
    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            out[index] = value
        }
        return out
    }

    private fun ipv4Scope(ip: IntArray): HostScope {
        val (a, b) = ip
        return when {
            a == 127 -> HostScope.LOOPBACK
            a == 10 -> HostScope.LOCAL_NETWORK
            a == 172 && b in 16..31 -> HostScope.LOCAL_NETWORK
            a == 192 && b == 168 -> HostScope.LOCAL_NETWORK
            a == 169 && b == 254 -> HostScope.LOCAL_NETWORK
            a == 100 && b in 64..127 -> HostScope.LOCAL_NETWORK
            else -> HostScope.PUBLIC
        }
    }

    private fun ipv6Scope(host: String): HostScope {
        val groups = parseIpv6(host) ?: return HostScope.PUBLIC
        if (groups.dropLast(1).all { it == 0 } && groups.last() == 1) return HostScope.LOOPBACK
        // IPv4-mapped ::ffff:a.b.c.d: judged as the IPv4 address it carries.
        if (groups.take(5).all { it == 0 } && groups[5] == 0xffff) {
            val ip = intArrayOf(groups[6] shr 8, groups[6] and 0xff, groups[7] shr 8, groups[7] and 0xff)
            return ipv4Scope(ip)
        }
        val first = groups[0]
        return when {
            first and 0xfe00 == 0xfc00 -> HostScope.LOCAL_NETWORK // ULA fc00::/7
            first and 0xffc0 == 0xfe80 -> HostScope.LOCAL_NETWORK // link-local fe80::/10
            else -> HostScope.PUBLIC
        }
    }

    /** Eight 16-bit groups, or null. Accepts `::` once and a dotted IPv4 tail; no zone id (`%eth0`). */
    private fun parseIpv6(host: String): IntArray? {
        if (host.any { !(it.isDigit() || it in 'a'..'f' || it == ':' || it == '.') }) return null
        val doubleColon = host.indexOf("::")
        if (doubleColon >= 0 && host.indexOf("::", doubleColon + 1) >= 0) return null
        val head = if (doubleColon >= 0) host.substring(0, doubleColon) else host
        val tail = if (doubleColon >= 0) host.substring(doubleColon + 2) else ""
        val headGroups = groupsOf(head, allowIpv4Tail = doubleColon < 0) ?: return null
        val tailGroups = if (doubleColon >= 0) groupsOf(tail, allowIpv4Tail = true) ?: return null else emptyList()
        val total = headGroups.size + tailGroups.size
        if (doubleColon >= 0) {
            if (total > 7) return null
        } else if (total != 8) {
            return null
        }
        val out = IntArray(8)
        headGroups.forEachIndexed { index, value -> out[index] = value }
        tailGroups.forEachIndexed { index, value -> out[8 - tailGroups.size + index] = value }
        return out
    }

    private fun groupsOf(text: String, allowIpv4Tail: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        val out = ArrayList<Int>(8)
        parts.forEachIndexed { index, part ->
            if ('.' in part) {
                if (!allowIpv4Tail || index != parts.lastIndex) return null
                val ip = parseIpv4(part) ?: return null
                out += (ip[0] shl 8) or ip[1]
                out += (ip[2] shl 8) or ip[3]
            } else {
                if (part.isEmpty() || part.length > 4) return null
                out += part.toInt(16)
            }
        }
        return out
    }
}
