package dev.otprelay.protocol

import java.util.Base64

object B64 {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray {
        val bytes = Base64.getUrlDecoder().decode(text)
        require(encode(bytes) == text) { "Non-canonical base64url" }
        return bytes
    }
}
