package com.wally.watchchat

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Puente por "buzón": usa un repo privado de GitHub como tablón entre el
 * reloj y Wally.
 *
 * 1. Escribe tu mensaje en inbox.json con status "pending".
 * 2. Un cron del lado del servidor lo lee cada 15 s, genera la respuesta
 *    (texto + mp3 locutado con la voz de Wally), la escribe en outbox.json
 *    (campo "audio": "audio/<id>.mp3") y marca el inbox como "done".
 * 3. Este puente hace polling a outbox.json hasta ver la respuesta; si trae
 *    audio, lo descarga a la caché y lo devuelve para reproducirlo.
 *
 * Necesita un token en [MailboxConfig] (fine-grained PAT con permiso
 * Contents: lectura y escritura, SOLO en el repo del buzón).
 */

/** Etapa de trabajo del agente, publicada por el cron en status.json. */
data class AgentStatus(val stage: String, val detail: String, val ts: Long)

class MailboxBridge(private val cacheDir: File) : AssistantBridge {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    override suspend fun getReply(prompt: String, history: List<ChatMessage>): Reply =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            Log.d("WallyWatch", "MailboxBridge: sending prompt with id=$id ('$prompt')")
            try {
                putJson(
                    "inbox.json",
                    JSONObject()
                        .put("id", id)
                        .put("ts", System.currentTimeMillis() / 1000)
                        .put("status", "pending")
                        .put("text", prompt)
                        .toString()
                )
                Log.d("WallyWatch", "MailboxBridge: putJson inbox.json SUCCESS for id=$id")
            } catch (e: Exception) {
                Log.e("WallyWatch", "MailboxBridge: putJson FAILED for id=$id", e)
                return@withContext Reply("No pude escribir en el buzón: ${e.message}")
            }

            // Reacciona en cuanto cambia el outbox (polling corto con cache buster).
            val deadline = System.currentTimeMillis() + 180_000
            var lastError: String? = null
            while (System.currentTimeMillis() < deadline) {
                try {
                    Log.d("WallyWatch", "MailboxBridge: polling outbox.json...")
                    val out = getJson("outbox.json")
                    if (out != null) {
                        val replyTo = out.optString("reply_to")
                        Log.d("WallyWatch", "MailboxBridge: outbox reply_to=$replyTo (expecting $id)")
                        if (replyTo == id) {
                            val text = out.optString("text", "")
                            Log.d("WallyWatch", "MailboxBridge: match found! text='$text'")
                            val audioFile = downloadAudio(out.optString("audio", ""), id)
                            return@withContext Reply(
                                text.ifEmpty { "(respuesta vacía)" },
                                audioFile
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WallyWatch", "MailboxBridge: polling outbox.json exception", e)
                    lastError = e.message
                    if (e.message?.contains("HTTP 4") == true) {
                        return@withContext Reply("Error de GitHub: ${e.message}")
                    }
                }
                delay(2_500)
            }
            Reply(
                "Wally no respondió a tiempo (~3 min)." +
                    (if (lastError != null) " Último error: $lastError" else "")
            )
        }

    /**
     * Lee la etapa actual del agente (status.json, escrito por el cron).
     * Devuelve null si no hay estado o no se pudo leer.
     */
    suspend fun getAgentStatus(): AgentStatus? = withContext(Dispatchers.IO) {
        try {
            val json = getJson("status.json") ?: return@withContext null
            AgentStatus(
                json.optString("stage", ""),
                json.optString("detail", ""),
                json.optLong("ts", 0)
            )
        } catch (e: Exception) {
            Log.w("WallyWatch", "getAgentStatus: no se pudo leer", e)
            null
        }
    }

    /**
     * Descarga el mp3 de la respuesta (campo "audio" del outbox, p. ej.
     * "audio/<id>.mp3") a la caché. Devuelve el fichero o null si falla
     * (entonces se usa el TTS del reloj como antes).
     */
    private fun downloadAudio(audioPath: String, id: String): File? {
        if (audioPath.isBlank()) return null
        return try {
            val meta = apiGet(audioPath) ?: return null
            val content = meta.optString("content", "").replace("\\s".toRegex(), "")
            if (content.isEmpty()) return null
            val bytes = Base64.decode(content, Base64.DEFAULT)
            val file = File(cacheDir, "wally_reply_$id.mp3")
            file.writeBytes(bytes)
            Log.d("WallyWatch", "MailboxBridge: audio descargado (${bytes.size} B) -> ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e("WallyWatch", "MailboxBridge: no se pudo descargar el audio", e)
            null
        }
    }

    /** GET del fichero en el repo; devuelve el JSON de la API (content+sha) o null. */
    private fun apiGet(path: String): JSONObject? {
        val cb = System.currentTimeMillis()
        val req = Request.Builder()
            .url("${MailboxConfig.API}/repos/${MailboxConfig.REPO}/contents/$path?cb=$cb")
            .header("Authorization", "Bearer ${MailboxConfig.TOKEN}")
            .header("Accept", "application/vnd.github+json")
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 404) return null
                throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
            }
            val bodyStr = resp.body?.string() ?: return null
            return JSONObject(bodyStr)
        }
    }

    /** Contenido JSON decodificado del fichero, o null si no se pudo leer. */
    private fun getJson(path: String): JSONObject? {
        val meta = apiGet(path) ?: return null
        val content = meta.optString("content", "").replace("\\s".toRegex(), "")
        if (content.isEmpty()) return null
        return JSONObject(String(Base64.decode(content, Base64.DEFAULT)))
    }

    /** PUT del fichero (crea o actualiza; necesita el sha si ya existe). */
    private fun putJson(path: String, json: String) {
        val sha = apiGet(path)?.optString("sha", null)
        val payload = JSONObject()
            .put("message", "wally-watch: update $path")
            .put(
                "content",
                Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
            )
        if (!sha.isNullOrEmpty()) payload.put("sha", sha)
        val req = Request.Builder()
            .url("${MailboxConfig.API}/repos/${MailboxConfig.REPO}/contents/$path")
            .header("Authorization", "Bearer ${MailboxConfig.TOKEN}")
            .header("Accept", "application/vnd.github+json")
            .header("Cache-Control", "no-cache")
            .put(payload.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                throw RuntimeException("GitHub PUT $path -> HTTP ${resp.code}: $errBody")
            }
        }
    }
}
