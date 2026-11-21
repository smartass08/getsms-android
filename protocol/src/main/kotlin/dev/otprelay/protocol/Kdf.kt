package dev.otprelay.protocol

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

class RelayKeys(val accessToken: ByteArray, val encKey: ByteArray) {
    val bearer: String get() = B64.encode(accessToken)
}

fun deriveMaster(password: String): ByteArray {
    require(classify(password) == SecretKind.PASSWORD) { "Invalid relay password" }
    val bytes = trimSecret(password).toByteArray(Charsets.US_ASCII)
    return try {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3)
            .withMemoryAsKB(65536)
            .withParallelism(4)
            .withSalt("otp-relay/v1/argon2id/salt".toByteArray(Charsets.US_ASCII))
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        ByteArray(32).also { generator.generateBytes(bytes, it) }
    } finally {
        bytes.fill(0)
    }
}

fun deriveKeys(master: ByteArray): RelayKeys {
    require(master.size == 32)
    return RelayKeys(
        hkdfSha256(master, "otp-relay/v1/access-token".toByteArray(), ByteArray(0), 32),
        hkdfSha256(master, "otp-relay/v1/sms-key".toByteArray(), ByteArray(0), 32),
    )
}
