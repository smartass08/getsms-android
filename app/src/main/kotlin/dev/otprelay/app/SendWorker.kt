package dev.otprelay.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.otprelay.protocol.deriveKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val blob = inputData.getString(BLOB) ?: return@withContext Result.failure()
        val createdAt = inputData.getLong(CREATED_AT, 0)
        val store = SecretStore(applicationContext)
        val tag = store.configurationTag() ?: return@withContext Result.failure()
        if (tag !in tags) return@withContext Result.failure()

        fun report(message: Int, result: Result): Result {
            store.recordResult(applicationContext.getString(message), System.currentTimeMillis(), tag)
            return result
        }

        if (isExpired(createdAt)) return@withContext report(R.string.send_expired, Result.failure())
        val credentials = store.loadCredentials(tag)
            ?: return@withContext report(R.string.key_unavailable, Result.failure())

        val keys = try {
            deriveKeys(credentials.master)
        } finally {
            credentials.master.fill(0)
        }
        val outcome = try {
            if (tag != store.configurationTag()) return@withContext Result.failure()
            RelayClient.send(credentials.url, keys.bearer, blob)
        } finally {
            keys.accessToken.fill(0)
            keys.encKey.fill(0)
        }
        when (outcome) {
            RelayClient.Outcome.Success -> report(outcome.messageId, Result.success())
            RelayClient.Outcome.PermanentFailure -> report(outcome.messageId, Result.failure())
            RelayClient.Outcome.RetryableFailure ->
                if (runAttemptCount < 8 && !isExpired(createdAt)) {
                    report(R.string.send_retry, Result.retry())
                } else {
                    report(R.string.send_failed, Result.failure())
                }
        }
    }

    private fun isExpired(createdAt: Long): Boolean =
        createdAt <= 0 || System.currentTimeMillis() - createdAt >= 15 * 60 * 1000L

    internal companion object {
        const val BLOB = "blob"
        const val CREATED_AT = "created_at"
    }
}
