package com.wally.watchchat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    // Puente activo: el buzón real (repo privado de GitHub + cron del servidor).
    // Necesita el token en MailboxConfig. Para la demo sin red, usa:
    //   SimulatedBridge()
    // (y la vía Data Layer sigue disponible en DataLayerBridge para una
    // futura app companion en el móvil).
    private val bridge: AssistantBridge = MailboxBridge()

    private val idGen = AtomicLong(0)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _typing = MutableStateFlow(false)
    val typing: StateFlow<Boolean> = _typing.asStateFlow()

    init {
        addMessage("Hola, soy Wally (prototipo). Pulsa el botón y háblame.", isUser = false)
        send("Hola Wally desde el reloj")
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        Log.d("WallyWatch", "ChatViewModel.send: prompt='$text'")
        if (text.isEmpty() || _typing.value) {
            Log.d("WallyWatch", "ChatViewModel.send ignored: text.isEmpty=${text.isEmpty()}, typing=${_typing.value}")
            return
        }

        addMessage(text, isUser = true)
        _typing.value = true

        viewModelScope.launch {
            val history = _messages.value.toList()
            Log.d("WallyWatch", "ChatViewModel: calling bridge.getReply...")
            val reply = try {
                bridge.getReply(text, history)
            } catch (e: Exception) {
                Log.e("WallyWatch", "ChatViewModel: bridge exception", e)
                "Error del puente: ${e.message}"
            }
            Log.d("WallyWatch", "ChatViewModel: bridge reply='$reply'")
            addMessage(reply, isUser = false)
            _typing.value = false
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        _messages.update { it + ChatMessage(idGen.incrementAndGet(), text, isUser) }
    }
}
