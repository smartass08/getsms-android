package dev.otprelay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dev.otprelay.protocol.B64
import dev.otprelay.protocol.deriveKeys
import dev.otprelay.protocol.encodeFrame
import dev.otprelay.protocol.sealEnvelopeRandom
import java.security.GeneralSecurityException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            try {
                // Await a sibling so a blocking Keystore call cannot hold the broadcast open.
                withTimeout(8_000L) { scope.async { prepareDelivery(app, intent) }.await() }
            } catch (_: TimeoutCancellationException) {
                // WorkManager may already have accepted the request.
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: RuntimeException) {
                // The preferences may be unavailable. Do not log broadcast details.
            } finally {
                scope.cancel()
                pending.finish()
            }
        }
    }

    private suspend fun prepareDelivery(context: Context, intent: Intent) {
        val store = SecretStore(context)
        val tag = store.configurationTag() ?: return
        try {
            enqueueMessages(context, intent, store, tag)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: GeneralSecurityException) {
            store.recordResult(context.getString(R.string.send_failed), System.currentTimeMillis(), tag)
        } catch (_: RuntimeException) {
            store.recordResult(context.getString(R.string.send_failed), System.currentTimeMillis(), tag)
        }
    }

    private suspend fun enqueueMessages(context: Context, intent: Intent, store: SecretStore, tag: String) {
        val parts = linkedMapOf<String, StringBuilder>()
        for (message in Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
            if (message == null) continue
            val part = message.displayMessageBody ?: continue
            parts.getOrPut(message.displayOriginatingAddress.orEmpty()) { StringBuilder() }.append(part)
        }
        if (parts.isEmpty()) return

        val master = store.loadCredentials(tag)?.master
        if (master == null) {
            store.recordResult(context.getString(R.string.key_unavailable), System.currentTimeMillis(), tag)
            return
        }

        val keys = try {
            deriveKeys(master)
        } finally {
            master.fill(0)
        }
        try {
            val requests = parts.map { (sender, body) ->
                coroutineContext.ensureActive()
                val createdAt = System.currentTimeMillis()
                val frame = encodeFrame(createdAt, sender, body.toString())
                val blob = try {
                    B64.encode(sealEnvelopeRandom(keys.encKey, frame))
                } finally {
                    frame.fill(0)
                }
                OneTimeWorkRequestBuilder<SendWorker>()
                    .addTag(tag)
                    .setInputData(workDataOf(SendWorker.BLOB to blob, SendWorker.CREATED_AT to createdAt))
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        }
                    }
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
            }
            coroutineContext.ensureActive()
            WorkManager.getInstance(context).enqueue(requests).await()
        } finally {
            keys.accessToken.fill(0)
            keys.encKey.fill(0)
        }
    }
}
