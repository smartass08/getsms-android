package dev.otprelay.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal const val VERSION: Byte = 1
internal const val FRAME_BLOCK = 256
internal const val MAX_FRAME = 4096
private const val MAX_SENDER = 64
private const val HEADER_SIZE = 14
private const val TRUNCATED = 1
private const val TEST = 2

data class SmsFrame(val receivedAtMs: ULong, val sender: String, val body: String, val flags: Int)

fun encodeFrame(receivedAtMs: Long, sender: String, body: String, testFlag: Boolean = false): ByteArray {
    val senderBytes = sender.toByteArray().truncateUtf8(MAX_SENDER)
    val rawBody = body.toByteArray()
    val bodyBytes = rawBody.truncateUtf8(MAX_FRAME - HEADER_SIZE - senderBytes.size)
    val flags = (if (bodyBytes.size < rawBody.size) TRUNCATED else 0) or (if (testFlag) TEST else 0)
    val length = HEADER_SIZE + senderBytes.size + bodyBytes.size
    val paddedLength = (length + FRAME_BLOCK - 1) / FRAME_BLOCK * FRAME_BLOCK
    return ByteBuffer.allocate(paddedLength)
        .put(VERSION)
        .put(flags.toByte())
        .putLong(receivedAtMs)
        .putShort(senderBytes.size.toShort())
        .put(senderBytes)
        .putShort(bodyBytes.size.toShort())
        .put(bodyBytes)
        .array()
}

fun decodeFrame(frame: ByteArray): SmsFrame {
    require(validFrameSize(frame.size))
    val buffer = ByteBuffer.wrap(frame)
    require(buffer.get() == VERSION)
    val flags = buffer.get().toInt()
    require(flags and (TRUNCATED or TEST).inv() == 0)
    val receivedAtMs = buffer.long.toULong()
    val senderLength = buffer.short.toInt() and 0xffff
    require(senderLength <= MAX_SENDER)
    val sender = buffer.readUtf8(senderLength)
    val bodyLength = buffer.short.toInt() and 0xffff
    require(bodyLength <= buffer.remaining())
    val body = buffer.readUtf8(bodyLength)
    while (buffer.hasRemaining()) {
        require(buffer.get() == 0.toByte())
    }
    return SmsFrame(receivedAtMs, sender, body, flags)
}

internal fun validFrameSize(size: Int): Boolean =
    size in FRAME_BLOCK..MAX_FRAME && size % FRAME_BLOCK == 0

private fun ByteArray.truncateUtf8(limit: Int): ByteArray {
    if (size <= limit) return this
    var cut = limit
    while (cut > 0 && this[cut].toInt() and 0xc0 == 0x80) cut--
    return copyOf(cut)
}

private fun ByteBuffer.readUtf8(length: Int): String {
    val bytes = ByteArray(length).also { get(it) }
    return try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (exception: CharacterCodingException) {
        throw IllegalArgumentException("Invalid UTF-8", exception)
    }
}
