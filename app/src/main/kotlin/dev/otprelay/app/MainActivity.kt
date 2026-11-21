package dev.otprelay.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var smsGranted by mutableStateOf(false)
    private val requestSmsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        smsGranted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SettingsScreen(smsGranted) { requestSmsPermission.launch(Manifest.permission.RECEIVE_SMS) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        smsGranted = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }
}
