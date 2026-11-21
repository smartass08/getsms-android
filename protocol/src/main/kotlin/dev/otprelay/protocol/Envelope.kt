package dev.otprelay.protocol

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val NONCE_SIZE = 12
private const val TAG_SIZE = 16
private const val ENVELOPE_OVERHEAD = 1 + NONCE_SIZE + TAG_SIZE
private val aad = "otp-relay/v1/sms".toByteArray() + VERSION
private val random = SecureRandom()

fun sealEnvelope(encKey: ByteArray, frame: ByteArray, nonce: ByteArray): ByteArray {
    require(validFrameSize(frame.size))
    val ciphertext = envelopeCipher(Cipher.ENCRYPT_MODE, encKey, nonce).doFinal(frame)
    return byteArrayOf(VERSION) + nonce + ciphertext
}

fun sealEnvelopeRandom(encKey: ByteArray, frame: ByteArray): ByteArray =
    sealEnvelope(encKey, frame, ByteArray(NONCE_SIZE).also(random::nextBytes))

fun openEnvelope(encKey: ByteArray, blob: ByteArray): ByteArray {
    require(validFrameSize(blob.size - ENVELOPE_OVERHEAD) && blob[0] == VERSION)
    val nonce = blob.copyOfRange(1, 1 + NONCE_SIZE)
    val frame = envelopeCipher(Cipher.DECRYPT_MODE, encKey, nonce)
        .doFinal(blob, 1 + NONCE_SIZE, blob.size - 1 - NONCE_SIZE)
    decodeFrame(frame)
    return frame
}

private fun envelopeCipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
    require(key.size == 32 && nonce.size == NONCE_SIZE)
    return Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        updateAAD(aad)
    }
}
