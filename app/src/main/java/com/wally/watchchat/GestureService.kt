package com.wally.watchchat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Servicio en segundo plano optimizado para detección exclusiva del gesto de doble pellizco
 * (double pinch de pulgar con índice).
 *
 * Filtra y descarta completamente cualquier giro o rotación de la muñeca utilizando
 * la velocidad angular del giróscopo, asegurando que solo los pellizcos de los dedos
 * activen la grabación y el envío automático.
 */
class GestureService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var linearAccelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceSpeechRecognizer: SpeechRecognizer? = null

    // Giroscopio para ignorar rotaciones/giros de muñeca
    private var currentRotationalSpeed = 0f
    private val MAX_ROTATION_SPEED_FOR_PINCH = 1.9f // rad/s max (descarta giros de muñeca)

    // Historial de aceleración para derivada de pico rápida
    private var prevMagnitude = 0f
    private var lastPeakTime = 0L
    private var lastTriggerTime = 0L

    // Umbrales afinados exclusivamente para pellizcos de dedos (m/s^2)
    private val LINEAR_PEAK_THRESHOLD = 2.6f
    private val TOTAL_PEAK_DELTA_THRESHOLD = 2.4f
    private val JERK_THRESHOLD = 1.8f

    // Ventana de intervalo optimizada (60 ms - 650 ms)
    private val MIN_PINCH_INTERVAL_MS = 60L
    private val MAX_PINCH_INTERVAL_MS = 650L
    private val COOL_DOWN_MS = 700L

    override fun onCreate() {
        super.onCreate()
        Log.d("WallyGesture", "GestureService: iniciando detector exclusivo de pellizcos")

        runCatching {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WallyWatch::GestureWakeLock").apply {
                acquire() // Mantiene la CPU despierta de forma permanente
            }
        }.onFailure { e ->
            Log.w("WallyGesture", "Error al adquirir WakeLock", e)
        }

        runCatching {
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            val targetSensor = linearAccelSensor ?: accelSensor
            if (targetSensor != null) {
                sensorManager.registerListener(this, targetSensor, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d("WallyGesture", "GestureService: escuchando sensor ${targetSensor.name} a SENSOR_DELAY_FASTEST")
            } else {
                Log.e("WallyGesture", "GestureService: no se detectó acelerómetro")
            }

            if (gyroSensor != null) {
                sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d("WallyGesture", "GestureService: escuchando giróscopo para descartar giros de muñeca")
            }
        }.onFailure { e ->
            Log.e("WallyGesture", "Error al registrar sensores", e)
        }

        startForegroundServiceWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("WallyGesture", "GestureService: onTaskRemoved")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("WallyGesture", "GestureService: desregistrando sensores")
        runCatching { sensorManager.unregisterListener(this) }
        runCatching { serviceSpeechRecognizer?.destroy() }
        runCatching { wakeLock?.release() }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // 1. Actualizar la velocidad angular si el evento es del giróscopo
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]
            currentRotationalSpeed = sqrt(gx * gx + gy * gy + gz * gz)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < COOL_DOWN_MS) return

        // 2. Si la muñeca se está girando o rotando (rotación > 1.9 rad/s), descartar completamente
        if (currentRotationalSpeed > MAX_ROTATION_SPEED_FOR_PINCH) {
            return
        }

        val isLinear = event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val magnitude: Float = if (isLinear) {
            sqrt(x * x + y * y + z * z)
        } else {
            val total = sqrt(x * x + y * y + z * z)
            abs(total - SensorManager.GRAVITY_EARTH)
        }

        val deltaMag = abs(magnitude - prevMagnitude)
        prevMagnitude = magnitude

        val threshold = if (isLinear) LINEAR_PEAK_THRESHOLD else TOTAL_PEAK_DELTA_THRESHOLD
        val isSpike = magnitude > threshold || deltaMag > JERK_THRESHOLD

        if (isSpike) {
            val dt = now - lastPeakTime
            if (dt in MIN_PINCH_INTERVAL_MS..MAX_PINCH_INTERVAL_MS) {
                Log.d("WallyGesture", "🤏 DOBLE PELLIZCO DETECTADO! (magnitud=$magnitude, dt=$dt ms)")
                lastTriggerTime = now
                lastPeakTime = 0L
                onDoublePinchDetected()
            } else if (dt > MAX_PINCH_INTERVAL_MS || lastPeakTime == 0L) {
                lastPeakTime = now
                Log.d("WallyGesture", "Primer pellizco registrado (magnitud=$magnitude, delta=$deltaMag)")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onDoublePinchDetected() {
        vibrateConfirmation()

        // Notificar evento si la app está en primer plano
        _gestureEvents.tryEmit(Unit)

        if (MainActivity.isAppInForeground) {
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_AUTO_RECORD, true)
            }
            startActivity(activityIntent)
        } else {
            startBackgroundSpeechRecognition()
        }
    }

    private fun startBackgroundSpeechRecognition() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.w("WallyGesture", "RECORD_AUDIO no concedido, abriendo Activity para grabar")
                    launchActivityToRecord()
                    return@post
                }

                runCatching { serviceSpeechRecognizer?.destroy() }

                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    launchActivityToRecord()
                    return@post
                }

                updateNotification("🎙️ Escuchando tu pregunta…", "Habla ahora para Wally")

                val recognizer = runCatching {
                    SpeechRecognizer.createSpeechRecognizer(this)
                }.getOrNull()

                if (recognizer == null) {
                    launchActivityToRecord()
                    return@post
                }

                serviceSpeechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("WallyGesture", "Background SpeechRecognizer: listo")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d("WallyGesture", "Background SpeechRecognizer: habla detectada")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d("WallyGesture", "Background SpeechRecognizer: fin de habla")
                        updateNotification("⏳ Enviando a Wally…", "Procesando pregunta")
                    }

                    override fun onError(error: Int) {
                        Log.w("WallyGesture", "Background SpeechRecognizer error: $error")
                        updateNotification("Wally Gestos Activos 🤏", "Pellizca 2 veces para hablar")
                        runCatching { recognizer.destroy() }
                        if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                            launchActivityToRecord()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        updateNotification("Wally Gestos Activos 🤏", "Pellizca 2 veces para hablar")
                        runCatching { recognizer.destroy() }
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        Log.d("WallyGesture", "Background SpeechRecognizer resultado: '$text'")
                        if (!text.isNullOrBlank()) {
                            vibrateConfirmation()
                            launchActivityWithAutoSend(text)
                        } else {
                            launchActivityToRecord()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                }

                runCatching {
                    recognizer.startListening(intent)
                }.onFailure { e ->
                    Log.e("WallyGesture", "Background startListening falló", e)
                    runCatching { recognizer.destroy() }
                    launchActivityToRecord()
                }

            } catch (e: Throwable) {
                Log.e("WallyGesture", "Error al iniciar SpeechRecognizer en background", e)
                launchActivityToRecord()
            }
        }
    }

    private fun launchActivityWithAutoSend(text: String) {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_AUTO_SEND, text)
        }
        startActivity(activityIntent)
    }

    private fun launchActivityToRecord() {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_AUTO_RECORD, true)
        }
        startActivity(activityIntent)
    }

    private fun vibrateConfirmation() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 90), -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 90), -1)
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.e("WallyGesture", "Error al ejecutar vibración háptica", e)
        }
    }

    private fun startForegroundServiceWithNotification() {
        runCatching {
            val channelId = "wally_gesture_channel"
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Wally Gestos 🤏",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Escucha gestos de pellizco en segundo plano para hablar con Wally"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = createNotification("Wally Gestos Activos 🤏", "Pellizca 2 veces para hablar")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { e ->
            Log.e("WallyGesture", "Error al iniciar startForeground", e)
        }
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(title, text))
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "wally_gesture_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val EXTRA_AUTO_RECORD = "extra_auto_record"
        const val EXTRA_AUTO_SEND = "extra_auto_send"

        private val _gestureEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 5)
        val gestureEvents = _gestureEvents.asSharedFlow()
    }
}
