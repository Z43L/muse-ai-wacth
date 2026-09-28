package com.wally.watchchat

/** Un mensaje del chat. isUser=true lo escribió la persona, false el asistente.
 * audioFile: fichero mp3 con la respuesta locutada por el asistente
 * (si es null, se lee con el TTS del reloj como antes). */
data class ChatMessage(
    val id: Long,
    val text: String,
    val isUser: Boolean,
    val timestampMillis: Long = System.currentTimeMillis(),
    val audioFile: java.io.File? = null
)
