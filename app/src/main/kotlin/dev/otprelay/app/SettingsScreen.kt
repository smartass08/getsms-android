package dev.otprelay.app

import android.content.SharedPreferences
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.otprelay.protocol.B64
import dev.otprelay.protocol.SecretKind
import dev.otprelay.protocol.classify
import dev.otprelay.protocol.deriveKeys
import dev.otprelay.protocol.deriveMaster
import dev.otprelay.protocol.encodeFrame
import dev.otprelay.protocol.sealEnvelopeRandom
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class PasswordDerivationMemoryException(cause: OutOfMemoryError) : Exception(cause)

@Composable
internal fun SettingsScreen(smsGranted: Boolean, onGrantSms: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SecretStore(context) }
    var server by remember(store) { mutableStateOf(store.loadUrl().orEmpty()) }
    var password by remember { mutableStateOf("") }
    var revealPassword by remember { mutableStateOf(false) }
    var configured by remember(store) { mutableStateOf(store.isConfigured()) }
    var lastDelivery by remember(store) { mutableStateOf(store.lastDelivery()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            configured = store.isConfigured()
            lastDelivery = store.lastDelivery()
        }
        store.registerListener(listener)
        onDispose { store.unregisterListener(listener) }
    }

    fun perform(errorMessage: String, action: suspend () -> Unit) {
        busy = true
        feedback = null
        scope.launch {
            try {
                action()
            } catch (_: PasswordDerivationMemoryException) {
                feedback = "Not enough memory to process the password. Close other apps and try again."
            } catch (_: GeneralSecurityException) {
                feedback = errorMessage
            } catch (_: ProviderException) {
                feedback = errorMessage
            } catch (_: IOException) {
                feedback = errorMessage
            } catch (_: IllegalArgumentException) {
                feedback = errorMessage
            } finally {
                busy = false
            }
        }
    }

    Scaffold { insets ->
        Column(
            Modifier
                .padding(insets)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password / Token") },
                supportingText = {
                    if (configured && password.isEmpty()) {
                        Text("Saved — enter a new value to replace")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (revealPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { revealPassword = !revealPassword }) {
                        Text(if (revealPassword) "Hide" else "Show")
                    }
                },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedButton(
                onClick = {
                    password = generatePassword()
                    revealPassword = true
                    feedback = null
                },
                enabled = !busy,
            ) { Text("Generate") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val url = relayUrl(server)
                        val input = password
                        when {
                            url == null -> feedback = if (BuildConfig.DEBUG) {
                                "Enter an HTTPS URL or an HTTP URL for 10.0.2.2, localhost, or 127.0.0.1."
                            } else {
                                "Enter an HTTPS URL."
                            }
                            (input.isNotEmpty() || !configured) && classify(input) != SecretKind.PASSWORD ->
                                feedback = "Enter 16 to 128 printable ASCII characters without spaces."
                            else -> perform("Could not save the settings. Enter your password and try again.") {
                                val replacing = configured
                                val changed = withContext(Dispatchers.Default) {
                                    val master = if (input.isEmpty()) store.unwrapMaster() else deriveMasterForSave(input)
                                    try {
                                        store.save(url, master ?: throw IOException("Missing relay key"))
                                    } finally {
                                        master?.fill(0)
                                    }
                                }
                                server = url
                                password = ""
                                revealPassword = false
                                feedback = if (replacing && changed) {
                                    "Settings saved. Messages still queued with the previous settings will not be sent."
                                } else {
                                    "Settings saved."
                                }
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text("Save") }
                OutlinedButton(
                    onClick = {
                        perform(context.getString(R.string.key_unavailable)) {
                            val tag = store.configurationTag() ?: throw IOException("Missing relay settings")
                            val outcome = withContext(Dispatchers.IO) { sendTest(store, tag) }
                            val result = context.getString(outcome.messageId)
                            store.recordResult(result, System.currentTimeMillis(), tag)
                            feedback = result
                        }
                    },
                    enabled = configured && !busy,
                ) { Text("Send test") }
                OutlinedButton(
                    onClick = {
                        perform("Could not clear the settings. Try again.") {
                            withContext(Dispatchers.IO) { store.clear() }
                            server = ""
                            password = ""
                            revealPassword = false
                            configured = false
                            lastDelivery = null
                            feedback = "Settings cleared."
                        }
                    },
                    enabled = configured && !busy,
                ) { Text("Clear") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            feedback?.let { Text(it) }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text(if (smsGranted) "SMS permission granted." else "SMS permission not granted.")
                    if (!smsGranted) {
                        TextButton(onClick = onGrantSms) { Text("Grant") }
                        if (Build.VERSION.SDK_INT >= 35) {
                            Text(
                                "If Android blocks the permission, open App info → ⋮ → Allow restricted settings. " +
                                    "Authenticate, then grant SMS permission.",
                            )
                        }
                    }
                    Text(if (configured) "Relay configured." else "Relay not configured.")
                    val delivery = lastDelivery
                    if (delivery != null) {
                        val date = Date(delivery.atMs)
                        val day = DateFormat.getDateFormat(context).format(date)
                        val time = DateFormat.getTimeFormat(context).format(date)
                        Text("Last delivery: $day $time")
                        Text(delivery.result)
                    } else {
                        Text("No delivery attempts.")
                    }
                }
            }
        }
    }
}

private fun deriveMasterForSave(password: String): ByteArray = try {
    deriveMaster(password)
} catch (error: OutOfMemoryError) {
    throw PasswordDerivationMemoryException(error)
}

private fun generatePassword(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val random = SecureRandom()
    return buildString(24) {
        repeat(24) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

private fun sendTest(store: SecretStore, tag: String): RelayClient.Outcome {
    val credentials = store.loadCredentials(tag) ?: throw IOException("Missing relay key")
    val keys = try {
        deriveKeys(credentials.master)
    } finally {
        credentials.master.fill(0)
    }
    return try {
        val frame = encodeFrame(System.currentTimeMillis(), "otp-relay", "Test 123456", testFlag = true)
        val blob = B64.encode(sealEnvelopeRandom(keys.encKey, frame))
        RelayClient.send(credentials.url, keys.bearer, blob)
    } finally {
        keys.accessToken.fill(0)
        keys.encKey.fill(0)
    }
}
