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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Puente por "buzón": usa un repo privado de GitHub como tablón entre el
 * reloj y Wally.
 *
 * 1. Escribe tu mensaje en inbox.json con status "pending".
 * 2. Un cron del lado del servidor lo lee cada minuto, responde escribiendo
 *    en outbox.json y marca el inbox como "done".
 * 3. Este puente hace polling a outbox.json hasta ver la respuesta.
 *
 * Necesita un token en [MailboxConfig] (fine-grained PAT con permiso
 * Contents: lectura y escritura, SOLO en el repo del buzón).
 */
class MailboxBridge : AssistantBridge {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    override suspend fun getReply(prompt: String, history: List<ChatMessage>): String =
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
                return@withContext "No pude escribir en el buzón: ${e.message}"
            }

            // Reacciona en cuanto cambia el outbox (polling corto con cache buster).
            val deadline = System.currentTimeMillis() + 120_000
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
                            return@withContext text.ifEmpty { "(respuesta vacía)" }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WallyWatch", "MailboxBridge: polling outbox.json exception", e)
                    lastError = e.message
                    if (e.message?.contains("HTTP 4") == true) {
                        return@withContext "Error de GitHub: ${e.message}"
                    }
                }
                delay(2_500)
            }
            "Wally no respondió a tiempo (~2 min)." +
                (if (lastError != null) " Último error: $lastError" else "")
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
