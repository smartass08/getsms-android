package dev.otprelay.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, salt: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val hashLength = mac.macLength
    require(length in 0..(255 * hashLength))
    mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(hashLength) else salt, mac.algorithm))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, mac.algorithm))
    prk.fill(0)
    val output = ByteArray(length)
    var block = ByteArray(0)
    var counter = 1
    for (offset in output.indices step hashLength) {
        mac.update(block)
        mac.update(info)
        block.fill(0)
        block = mac.doFinal(byteArrayOf(counter++.toByte()))
        block.copyInto(output, offset, endIndex = minOf(hashLength, length - offset))
    }
    block.fill(0)
    return output
}
