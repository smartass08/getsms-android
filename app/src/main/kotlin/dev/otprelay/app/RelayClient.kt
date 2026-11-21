package dev.otprelay.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal fun relayUrl(input: String): String? {
    val uri = try {
        URI(input.trim())
    } catch (_: java.net.URISyntaxException) {
        return null
    }
    val host = uri.host ?: return null
    val scheme = uri.scheme?.lowercase()
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.port != -1 && uri.port !in 1..65535) return null
    if (scheme != "https" && !(BuildConfig.DEBUG && scheme == "http" &&
                host.lowercase() in setOf("10.0.2.2", "localhost", "127.0.0.1"))) return null
    return uri.toASCIIString().trimEnd('/')
}

internal object RelayClient {
    enum class Outcome(val messageId: Int) {
        Success(R.string.sent),
        PermanentFailure(R.string.send_rejected),
        RetryableFailure(R.string.send_unavailable),
    }

    fun send(url: String, bearer: String, blob: String): Outcome {
        val base = relayUrl(url) ?: return Outcome.PermanentFailure
        return try {
            val connection = URI("$base/v1/messages").toURL().openConnection() as HttpURLConnection
            try {
                val request = """{"blob":"$blob"}""".toByteArray()
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.size)
                connection.setRequestProperty("Authorization", "Bearer $bearer")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(request) }
                when (connection.responseCode) {
                    204 -> Outcome.Success
                    429, in 500..599 -> Outcome.RetryableFailure
                    else -> Outcome.PermanentFailure
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            Outcome.RetryableFailure
        }
    }
}
