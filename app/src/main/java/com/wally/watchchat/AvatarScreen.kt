package com.wally.watchchat

import android.Manifest
import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La app ES el avatar: Wally a pantalla completa para hablar por voz.
 *
 * Gestos:
 * - Doble toque: hablar (RemoteInput por voz).
 * - Deslizar hacia abajo (o girar la corona hacia abajo): panel de
 *   actividad con lo que está haciendo el agente en este momento
 *   (etapa publicada por el cron en status.json del buzón).
 *
 * - Tu mensaje va al buzón; cuando llega mi respuesta, el reloj reproduce
 *   el audio que genero con mi voz (mp3 descargado del buzón). Si la
 *   respuesta no trae audio, se lee con el TTS del reloj como reserva.
 * - No es una llamada en vivo real: la latencia ronda los ~30-60 s por el
 *   buzón (el audio se genera en el servidor en cada respuesta).
 */
@Composable
fun AvatarApp() {
    val vm: ChatViewModel = viewModel()
    MaterialTheme {
        AvatarScreen(vm)
    }
}

@Composable
fun AvatarScreen(vm: ChatViewModel) {
    val context = LocalContext.current
    val typing by vm.typing.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()

    var showProcess by remember { mutableStateOf(false) }
    var agentStage by remember { mutableStateOf<AgentStatus?>(null) }
    // Acumuladores para que el panel solo se abra/cierre con gestos
    // deliberados (no con roces accidentales de la corona o el dedo).
    var crownDownAcc by remember { mutableStateOf(0f) }
    var crownUpAcc by remember { mutableStateOf(0f) }

    // Síntesis de voz del reloj: solo como reserva si la respuesta
    // no trae audio locutado por Wally.
    val tts = remember {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine.language = Locale("es", "ES")
            }
        }
        engine
    }

    // Reproductor para el audio que genera Wally (su voz de verdad).
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopSpeaking() {
        tts.stop()
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }

    DisposableEffect(Unit) {
        onDispose {
            stopSpeaking()
            tts.shutdown()
        }
    }

    fun speakReply(msg: ChatMessage) {
        stopSpeaking()
        val file = msg.audioFile
        if (file != null && file.exists()) {
            // Voz de Wally generada en el servidor.
            Log.d("WallyWatch", "AvatarScreen: reproduciendo audio ${file.name}")
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { stopSpeaking() }
                start()
            }
        } else {
            // Reserva: TTS del reloj.
            Log.d("WallyWatch", "AvatarScreen: sin audio, usando TTS del reloj")
            tts.speak(msg.text, TextToSpeech.QUEUE_FLUSH, null, "wally-reply")
        }
    }

    var isListeningVoice by remember { mutableStateOf(false) }

    // Launcher de reserva por Activity si el SpeechRecognizer directo falla
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListeningVoice = false
        Log.d("WallyWatch", "voiceLauncher result code=${result.resultCode}, data=${result.data}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val speechResults = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val directText = speechResults?.firstOrNull()
            val remoteText = RemoteInput.getResultsFromIntent(result.data)
                ?.getCharSequence(VOICE_KEY)?.toString()

            val text = directText ?: remoteText
            Log.d("WallyWatch", "voiceLauncher extracted text='$text'")
            if (!text.isNullOrBlank()) {
                stopSpeaking()
                vm.send(text)
            }
        }
    }

    fun launchFallbackVoiceIntent() {
        val directSpeechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Escuchando tu pregunta para Wally…")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
        try {
            voiceLauncher.launch(directSpeechIntent)
        } catch (e: Exception) {
            Log.w("WallyWatch", "Direct ACTION_RECOGNIZE_SPEECH failed, using RemoteInput fallback", e)
            val remoteIntent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            val remoteInputs = listOf(
                RemoteInput.Builder(VOICE_KEY).setLabel("Habla con Wally").build()
            )
            RemoteInputIntentHelper.putRemoteInputsExtra(remoteIntent, remoteInputs)
            remoteIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            voiceLauncher.launch(remoteIntent)
        }
    }

    fun startDirectSpeechRecognizer() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("WallyWatch", "RECORD_AUDIO no concedido aún, usando fallback intent")
            launchFallbackVoiceIntent()
            return
        }

        val recognizer = runCatching {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else null
        }.getOrNull()

        if (recognizer == null) {
            launchFallbackVoiceIntent()
            return
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("WallyWatch", "SpeechRecognizer: listo y escuchando...")
                isListeningVoice = true
            }

            override fun onBeginningOfSpeech() {
                Log.d("WallyWatch", "SpeechRecognizer: habla detectada")
                isListeningVoice = true
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d("WallyWatch", "SpeechRecognizer: fin de habla, procesando envío automático...")
                isListeningVoice = false
            }

            override fun onError(error: Int) {
                Log.w("WallyWatch", "SpeechRecognizer error code: $error")
                isListeningVoice = false
                runCatching { recognizer.destroy() }
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    launchFallbackVoiceIntent()
                }
            }

            override fun onResults(results: Bundle?) {
                isListeningVoice = false
                runCatching { recognizer.destroy() }
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                Log.d("WallyWatch", "SpeechRecognizer resultado final: '$text'")
                if (!text.isNullOrBlank()) {
                    stopSpeaking()
                    vm.send(text) // <--- ENVÍO AUTOMÁTICO INMEDIATO
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }

        runCatching {
            recognizer.startListening(intent)
        }.onFailure { e ->
            Log.e("WallyWatch", "speechRecognizer.startListening falló", e)
            runCatching { recognizer.destroy() }
            launchFallbackVoiceIntent()
        }
    }

    fun launchVoiceInput() {
        stopSpeaking()
        startDirectSpeechRecognizer()
    }

    var gestureDetectedNotice by remember { mutableStateOf(false) }

    // Reaccionar al evento de doble pellizco del servicio de gestos
    LaunchedEffect(Unit) {
        GestureService.gestureEvents.collect {
            gestureDetectedNotice = true
            if (!typing && !showProcess) {
                stopSpeaking()
                launchVoiceInput()
            }
            delay(3_000)
            gestureDetectedNotice = false
        }
    }

    // Reaccionar al disparo recibido desde MainActivity (para activar micro)
    val autoRecordTime by MainActivity.autoRecordTrigger.collectAsStateWithLifecycle()
    LaunchedEffect(autoRecordTime) {
        if (autoRecordTime > 0L) {
            gestureDetectedNotice = true
            if (!typing && !showProcess) {
                stopSpeaking()
                launchVoiceInput()
            }
            delay(3_000)
            gestureDetectedNotice = false
        }
    }

    // Reaccionar a envío automático capturado desde segundo plano
    val autoSendText by MainActivity.autoSendTrigger.collectAsStateWithLifecycle()
    LaunchedEffect(autoSendText) {
        val text = autoSendText
        if (!text.isNullOrBlank()) {
            stopSpeaking()
            vm.send(text)
            MainActivity.autoSendTrigger.value = null
        }
    }

    // Cuando llega mi respuesta, se reproduce: mi voz generada si trae
    // audio, o el TTS del reloj como reserva.
    var wasTyping by remember { mutableStateOf(false) }
    LaunchedEffect(typing) {
        if (wasTyping && !typing) {
            // Al llegar la respuesta se cierra el panel para ver al avatar.
            showProcess = false
            messages.lastOrNull { !it.isUser }?.let { speakReply(it) }
        }
        wasTyping = typing
    }

    // Etapa en vivo del agente: se consulta al buzón mientras Wally trabaja
    // o mientras el panel de actividad está abierto (cada 3 s).
    LaunchedEffect(typing, showProcess) {
        if (!typing && !showProcess) {
            agentStage = null
            return@LaunchedEffect
        }
        while (true) {
            agentStage = withContext(Dispatchers.IO) {
                runCatching { vm.agentStatus() }.getOrNull()
            }
            delay(3_000)
        }
    }

    val stage = agentStage
    val stageAgeSec =
        if (stage == null) Long.MAX_VALUE else System.currentTimeMillis() / 1000 - stage.ts
    val stageFresh = stageAgeSec in 0..120
    val stageLabel = when (stage?.stage) {
        "pensando" -> "💭 Pensando la respuesta…"
        "locutando" -> "🎙️ Generando el audio…"
        "publicando" -> "📤 Publicando la respuesta…"
        "listo" -> "✅ Respuesta lista"
        else -> null
    }
    val hint = when {
        isListeningVoice -> "🎙️ Escuchando tu pregunta…"
        gestureDetectedNotice -> "🤏 ¡Doble pellizco! Escuchando…"
        typing && stageFresh && stageLabel != null -> stageLabel
        typing -> "Wally está respondiendo…"
        else -> "Pellizca 2 veces para hablar"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Doble toque para hablar, con ventana generosa (650 ms, 120 px)
            // porque en la pantalla pequeña del reloj el doble toque del
            // sistema es demasiado estricto con el pulgar.
            .pointerInput(Unit) {
                var lastTapAt = 0L
                var lastTapPos = Offset.Zero
                detectTapGestures(
                    onTap = { pos ->
                        val now = SystemClock.uptimeMillis()
                        val dt = now - lastTapAt
                        val dist = (pos - lastTapPos).getDistance()
                        if (dt < 650 && dist < 120f) {
                            lastTapAt = 0L
                            if (!typing && !showProcess) launchVoiceInput()
                        } else {
                            lastTapAt = now
                            lastTapPos = pos
                        }
                    }
                )
            }
            // Deslizar hacia abajo: abre el panel solo con un gesto largo
            // y deliberado (más de 220 px acumulados).
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        acc += dragAmount
                        if (acc > 220 && !showProcess) {
                            showProcess = true
                            acc = 0f
                        }
                    }
                )
            }
            // Corona: también exige un giro largo y deliberado para abrir
            // (o cerrar, girando hacia arriba con el panel abierto).
            .onRotaryScrollEvent {
                val d = it.verticalScrollPixels
                if (d > 0f) {
                    crownUpAcc = 0f
                    crownDownAcc += d
                    if (crownDownAcc > 500 && !showProcess) {
                        showProcess = true
                        crownDownAcc = 0f
                    }
                } else if (d < 0f) {
                    crownDownAcc = 0f
                    if (showProcess) {
                        crownUpAcc -= d
                        if (crownUpAcc > 500) {
                            showProcess = false
                            crownUpAcc = 0f
                        }
                    }
                }
                true
            }
    ) {
        WallyAvatar(busy = typing, fullscreen = true)
        Text(
            hint,
            style = MaterialTheme.typography.caption3,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
        )

        if (showProcess) {
            ProcessPanel(
                stageLabel = if (stageFresh) stageLabel else null,
                stageAgeSec = stageAgeSec,
                messages = messages,
                typing = typing,
                onClose = { showProcess = false }
            )
        }
    }
}

/**
 * Panel de actividad: muestra en qué está el agente ahora mismo
 * (etapa publicada por el cron en status.json) y el último intercambio.
 * Se cierra deslizando hacia arriba o tocando ✕.
 */
@Composable
private fun ProcessPanel(
    stageLabel: String?,
    stageAgeSec: Long,
    messages: List<ChatMessage>,
    typing: Boolean,
    onClose: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val lastUser = messages.lastOrNull { it.isUser }
    val lastWally = messages.lastOrNull { !it.isUser }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        acc += dragAmount
                        if (acc < -150) {
                            onClose()
                            acc = 0f
                        }
                    }
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚙️ Actividad de Wally",
                    style = MaterialTheme.typography.body1,
                    color = Color.White
                )
                Text(
                    "✕",
                    style = MaterialTheme.typography.title2,
                    color = Color.White,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(16.dp)
                )
            }

            if (stageLabel != null) {
                Text(
                    stageLabel,
                    style = MaterialTheme.typography.body2,
                    color = Color(0xFF7CFC9A)
                )
                Text(
                    if (stageAgeSec < 5) "ahora mismo" else "hace $stageAgeSec s",
                    style = MaterialTheme.typography.caption3,
                    color = Color.White.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    if (typing) "⏳ Tu mensaje está en el buzón…" else "💤 En espera",
                    style = MaterialTheme.typography.body2,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            lastUser?.let {
                Text(
                    "🎤 Tú (${timeFmt.format(Date(it.timestampMillis))}): ${it.text.take(80)}",
                    style = MaterialTheme.typography.caption2,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            lastWally?.let {
                Text(
                    "🤖 Wally (${timeFmt.format(Date(it.timestampMillis))}): ${it.text.take(80)}",
                    style = MaterialTheme.typography.caption2,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                "Desliza ↑ o toca ✕ para cerrar",
                style = MaterialTheme.typography.caption3,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}
