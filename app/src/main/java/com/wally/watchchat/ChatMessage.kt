package com.wally.watchchat

/** Un mensaje del chat. isUser=true lo escribió la persona, false el asistente. */
data class ChatMessage(
    val id: Long,
    val text: String,
    val isUser: Boolean,
    val timestampMillis: Long = System.currentTimeMillis()
)
