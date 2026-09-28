package com.wally.watchchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    companion object {
        val autoRecordTrigger = MutableStateFlow(0L)
        val autoSendTrigger = MutableStateFlow<String?>(null)
        var isAppInForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Solicitar permisos requeridos (micrófono y notificaciones)
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                200
            )
        }

        // Iniciar el servicio de detección de gestos en segundo plano de forma segura
        runCatching {
            val gestureIntent = Intent(this, GestureService::class.java)
            ContextCompat.startForegroundService(this, gestureIntent)
        }.onFailure { e ->
            Log.e("WallyWatch", "Error al iniciar GestureService desde MainActivity", e)
        }

        checkAutoRecord(intent)

        setContent { AvatarApp() }
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkAutoRecord(intent)
    }

    private fun checkAutoRecord(intent: Intent?) {
        if (intent?.getBooleanExtra(GestureService.EXTRA_AUTO_RECORD, false) == true) {
            intent.removeExtra(GestureService.EXTRA_AUTO_RECORD)
            autoRecordTrigger.value = System.currentTimeMillis()
        }
        val autoSendText = intent?.getStringExtra(GestureService.EXTRA_AUTO_SEND)
        if (!autoSendText.isNullOrBlank()) {
            intent.removeExtra(GestureService.EXTRA_AUTO_SEND)
            autoSendTrigger.value = autoSendText
        }
    }
}
