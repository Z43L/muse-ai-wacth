package com.wally.watchchat

import android.app.Activity
import android.app.RemoteInput
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import java.util.Locale

import android.util.Log

/**
 * La app ES el avatar: Wally a pantalla completa para hablar por voz.
 *
 * - Tocas la pantalla y hablas (RemoteInput por voz).
 * - Tu mensaje va al buzón; cuando llega mi respuesta, el reloj la lee
 *   en voz alta (síntesis de voz del propio reloj).
 * - No es una llamada en vivo real: la latencia ronda los ~30 s por el buzón.
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

    // Síntesis de voz del reloj para leer mis respuestas.
    val tts = remember {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine.language = Locale("es", "ES")
            }
        }
        engine
    }
    DisposableEffect(Unit) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    fun speak(text: String) {
        tts.stop()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wally-reply")
    }

    // Entrada por voz con RemoteInput, el patrón estándar en Wear OS.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("WallyWatch", "voiceLauncher result code=${result.resultCode}, data=${result.data}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultsBundle = RemoteInput.getResultsFromIntent(result.data)
            Log.d("WallyWatch", "resultsBundle=$resultsBundle")
            val text = resultsBundle?.getCharSequence(VOICE_KEY)?.toString()
            Log.d("WallyWatch", "voiceLauncher extracted text='$text'")
            if (!text.isNullOrBlank()) {
                tts.stop()
                vm.send(text)
            }
        }
    }

    fun launchVoiceInput() {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val remoteInputs = listOf(
            RemoteInput.Builder(VOICE_KEY).setLabel("Habla con Wally").build()
        )
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        voiceLauncher.launch(intent)
    }

    // Cuando llega mi respuesta, la lee en voz alta.
    var wasTyping by remember { mutableStateOf(false) }
    LaunchedEffect(typing) {
        if (wasTyping && !typing) {
            messages.lastOrNull { !it.isUser }?.text?.let { speak(it) }
        }
        wasTyping = typing
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { if (!typing) launchVoiceInput() }
    ) {
        WallyAvatar(busy = typing, fullscreen = true)
        Text(
            if (typing) "Wally está respondiendo…" else "Toca para hablar",
            style = MaterialTheme.typography.caption3,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
        )
    }
}
