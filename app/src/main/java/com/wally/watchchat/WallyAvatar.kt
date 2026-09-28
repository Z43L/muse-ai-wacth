package com.wally.watchchat

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * La imagen de Wally: su avatar 3D animado, en bucle y recortado en círculo.
 * En reposo pone la animación "idle"; mientras espera una respuesta
 * (busy=true) cambia a la animación "working".
 */
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
@Composable
fun WallyAvatar(
    busy: Boolean,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w("WallyWatch", "ExoPlayer avatar error", error)
                }
            })
        }
    }

    LaunchedEffect(busy) {
        runCatching {
            val resId = if (busy) R.raw.wally_working else R.raw.wally_idle
            val uri = Uri.parse("android.resource://${context.packageName}/$resId")
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
        }.onFailure { e ->
            Log.e("WallyWatch", "Error al reproducir avatar", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                if (fullscreen) resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        modifier = if (fullscreen) {
            modifier.fillMaxSize()
        } else {
            modifier
                .size(76.dp)
                .clip(CircleShape)
        }
    )
}
