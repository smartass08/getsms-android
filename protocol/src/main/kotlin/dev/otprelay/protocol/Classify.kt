package dev.otprelay.protocol

enum class SecretKind { PIN, PASSWORD, INVALID }

internal fun trimSecret(input: String): String =
    input.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }

fun classify(input: String): SecretKind {
    val value = trimSecret(input)
    return when {
        value.length in 6..12 && value.all { it in '0'..'9' } -> SecretKind.PIN
        value.length in 16..128 && value.all { it in '!'..'~' } -> SecretKind.PASSWORD
        else -> SecretKind.INVALID
    }
}
