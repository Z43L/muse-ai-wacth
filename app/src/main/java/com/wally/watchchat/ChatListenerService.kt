package com.wally.watchchat

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Buzón de respuestas: empareja cada requestId con quien lo está esperando.
 * Lo rellena [ChatListenerService] cuando llega la respuesta del móvil.
 */
object ReplyBus {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun register(id: String): CompletableDeferred<String> =
        pending.getOrPut(id) { CompletableDeferred() }

    fun complete(id: String, text: String) {
        pending.remove(id)?.complete(text)
    }
}

/**
 * Servicio que escucha los mensajes que la app companion del móvil
 * envía de vuelta por Data Layer (path /wally/chat-reply).
 *
 * Declarado en el AndroidManifest con el intent-filter MESSAGE_RECEIVED.
 */
class ChatListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == DataLayerBridge.REPLY_PATH) {
            val raw = String(event.data, Charsets.UTF_8)
            val sep = raw.indexOf('|')
            if (sep > 0) {
                ReplyBus.complete(raw.substring(0, sep), raw.substring(sep + 1))
            }
        }
    }
}
