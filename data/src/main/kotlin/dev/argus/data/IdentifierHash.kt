package dev.argus.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun identifierHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
    val alphabet = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xff
            append(alphabet[unsigned ushr 4])
            append(alphabet[unsigned and 0x0f])
        }
    }
}
