package dev.otprelay.protocol

import java.io.File
import javax.crypto.AEADBadTagException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VectorsTest {
    private val vectors = JSONObject(File(System.getProperty("otprelay.vectors")).readText())

    @Test
    fun classification() = forEachVector("classify") { vector ->
        val input = vector.getString("input")
        assertEquals(input, vector.getString("kind"), classify(input).name.lowercase())
    }

    @Test
    fun keyDerivation() = forEachVector("derive") { vector ->
        val master = deriveMaster(vector.getString("password"))
        assertArrayEquals(vector.hex("master_hex"), master)
        val keys = deriveKeys(master)
        assertArrayEquals(B64.decode(vector.getString("access_token_b64url")), keys.accessToken)
        assertEquals(vector.getString("access_token_b64url"), keys.bearer)
        assertArrayEquals(vector.hex("enc_key_hex"), keys.encKey)
    }

    @Test
    fun frames() = forEachVector("frame") { vector ->
        val name = vector.getString("name")
        val timestamp = vector.getLong("received_at_ms")
        val encoded = encodeFrame(
            timestamp,
            vector.getString("input_sender"),
            vector.getString("input_body"),
            vector.getBoolean("test_flag"),
        )
        val expected = vector.hex("frame_hex")
        assertArrayEquals(name, expected, encoded)
        assertEquals(
            name,
            SmsFrame(
                timestamp.toULong(),
                vector.getString("expected_sender"),
                vector.getString("expected_body"),
                vector.getInt("flags"),
            ),
            decodeFrame(expected),
        )
    }

    @Test
    fun envelopes() = forEachVector("envelope") { vector ->
        val name = vector.getString("name")
        val key = vector.hex("enc_key_hex")
        val frame = vector.hex("frame_hex")
        val blob = vector.getString("blob_b64url")
        assertEquals(name, blob, B64.encode(sealEnvelope(key, frame, vector.hex("nonce_hex"))))
        assertArrayEquals(name, frame, openEnvelope(key, B64.decode(blob)))
    }

    @Test
    fun invalidEnvelopes() = forEachVector("envelope_invalid") { vector ->
        val reason = vector.getString("reason")
        val key = vector.hex("enc_key_hex")
        val blob = B64.decode(vector.getString("blob_b64url"))
        val exception = when (reason) {
            "tag_flipped", "aad_mismatch" -> AEADBadTagException::class.java
            else -> IllegalArgumentException::class.java
        }
        assertThrows(reason, exception) { openEnvelope(key, blob) }
    }

    private fun JSONObject.hex(field: String): ByteArray =
        getString(field).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun forEachVector(field: String, action: (JSONObject) -> Unit) {
        val cases = vectors.getJSONArray(field)
        for (i in 0 until cases.length()) action(cases.getJSONObject(i))
    }
}
