package com.wally.watchchat

import android.app.Activity
import android.app.RemoteInput
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.input.RemoteInputIntentHelper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val VOICE_KEY = "wally_voice"
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ChatApp() {
    val vm: ChatViewModel = viewModel()
    MaterialTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            ChatScreen(vm)
        }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val typing by vm.typing.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    // Entrada por voz (y teclado) con RemoteInput, el patrón estándar en Wear OS.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = RemoteInput.getResultsFromIntent(result.data)
                ?.getCharSequence(VOICE_KEY)
                ?.toString()
            if (!text.isNullOrBlank()) vm.send(text)
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

    // Auto-scroll al último mensaje.
    LaunchedEffect(messages.size, typing) {
        val last = messages.lastIndex
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        Column(Modifier.fillMaxSize()) {
            // La imagen de Wally: su avatar 3D animado, siempre visible.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                WallyAvatar(busy = typing)
            }

            ScalingLazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                if (typing) {
                    item(key = "typing") {
                        Text(
                            "Wally está escribiendo…",
                            style = MaterialTheme.typography.caption3,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Button(
                onClick = { launchVoiceInput() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = !typing
            ) {
                Text("Hablar")
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val bg = if (msg.isUser) Color(0xFF0B5FFF) else Color(0xFF2A2A2A)
    val align = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val time = timeFmt.format(
        Instant.ofEpochMilli(msg.timestampMillis).atZone(ZoneId.systemDefault())
    )

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 160.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                msg.text,
                color = Color.White,
                style = MaterialTheme.typography.body2
            )
            Text(
                time,
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
