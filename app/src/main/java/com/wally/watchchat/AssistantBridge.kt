package com.wally.watchchat

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Puente entre la UI del reloj y "el asistente".
 *
 * A día de hoy NO existe una API pública para hablar con el Muse real desde
 * una app propia, así que el prototipo trae dos implementaciones:
 *
 *  - [SimulatedBridge]: respuestas de mentira, para probar la UI en el reloj.
 *  - [DataLayerBridge]: envía el mensaje a la app companion del móvil por
 *    Data Layer (MessageClient) y espera su respuesta. La app del móvil NO
 *    está incluida en este prototipo: habría que escribirla aparte.
 */
/**
 * Respuesta del asistente: texto + opcionalmente un mp3 con la respuesta
 * locutada con la voz del asistente (se reproduce en el reloj en lugar
 * del TTS del sistema).
 */
data class Reply(
    val text: String,
    val audioFile: java.io.File? = null
)

interface AssistantBridge {
    suspend fun getReply(prompt: String, history: List<ChatMessage>): Reply
}

/** Respuestas simuladas: solo para ver la UI funcionando en el reloj. */
class SimulatedBridge : AssistantBridge {
    override suspend fun getReply(prompt: String, history: List<ChatMessage>): Reply {
        // Pausa para que se vea el indicador de "escribiendo..."
        kotlinx.coroutines.delay(900)
        val text = when {
            prompt.contains("hola", ignoreCase = true) ->
                "Hola. Soy el prototipo: la UI funciona, pero aún no hablo con el Wally de verdad."
            prompt.contains("hora", ignoreCase = true) -> {
                val t = java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                "En el reloj son las $t."
            }
            else -> "Recibido: \"$prompt\". (Respuesta simulada: aquí iría el puente real.)"
        }
        return Reply(text)
    }
}

/**
 * Envía el mensaje al móvil por Data Layer y espera la respuesta.
 *
 * Protocolo: el reloj envía a [MESSAGE_PATH] el texto "requestId|prompt".
 * La app companion del móvil debe responder a [REPLY_PATH] con
 * "requestId|respuesta", que recoge [ChatListenerService] y entrega a
 * [ReplyBus].
 */
class DataLayerBridge(private val context: Context) : AssistantBridge {

    override suspend fun getReply(prompt: String, history: List<ChatMessage>): Reply =
        withContext(Dispatchers.IO) {
            val node = runCatching {
                withTimeout(5_000) {
                    Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()
                }
            }.getOrNull() ?: return@withContext Reply("No hay móvil conectado al reloj.")

            val requestId = UUID.randomUUID().toString()
            val deferred = ReplyBus.register(requestId)

            runCatching {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, MESSAGE_PATH, "$requestId|$prompt".toByteArray())
                    .await()
            }.onFailure {
                ReplyBus.complete(requestId, "")
                return@withContext Reply("No se pudo enviar al móvil: ${it.message}")
            }

            Reply(
                withTimeoutOrNull(25_000) { deferred.await() }
                    ?: "El móvil no respondió a tiempo."
            )
        }

    companion object {
        const val MESSAGE_PATH = "/wally/chat"
        const val REPLY_PATH = "/wally/chat-reply"
    }
}
