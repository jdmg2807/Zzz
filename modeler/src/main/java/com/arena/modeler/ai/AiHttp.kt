package com.arena.modeler.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Minimal HTTP client for the two providers, on purpose: `HttpURLConnection` is in the platform,
 * so the app has no networking dependency and the APK stays small.
 *
 * Both endpoints are called from a background thread by [AiClient]; nothing here touches the UI.
 */
object AiHttp {

    class Reply(val text: String, val model: String, val usage: String?)

    class HttpError(val code: Int, val providerMessage: String, val raw: String) :
        Exception(providerMessage)

    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val GOOGLE_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    /** Sends the prompt to the selected provider and returns the model's text answer. */
    fun ask(
        provider: AiProvider,
        apiKey: String,
        model: String,
        system: String,
        user: String
    ): Reply = when (provider) {
        AiProvider.OPENROUTER -> openRouter(apiKey, model, system, user)
        AiProvider.GOOGLE_AI_STUDIO -> google(apiKey, model, system, user)
    }

    // ------------------------------------------------------------------ providers

    private fun openRouter(apiKey: String, model: String, system: String, user: String): Reply {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.65)
            put("max_tokens", 4096)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
            // OpenRouter uses these two to attribute the traffic; harmless if empty.
            "HTTP-Referer" to "https://github.com/jdmg2807/Zzz",
            "X-Title" to "VoxelForge 3D"
        )
        val raw = post(OPENROUTER_URL, headers, body.toString())
        val json = JSONObject(raw)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            val providerError = json.optJSONObject("error")?.optString("message")
            throw HttpError(200, providerError ?: "OpenRouter no devolvió ninguna respuesta", raw)
        }
        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val content = message?.opt("content")?.toString() ?: ""
        if (content.isBlank()) {
            throw HttpError(200, "La respuesta del modelo vino vacía. Prueba con otro modelo.", raw)
        }
        val usage = json.optJSONObject("usage")?.let {
            val p = it.optInt("prompt_tokens", 0)
            val c = it.optInt("completion_tokens", 0)
            if (p + c > 0) "$p+$c tokens" else null
        }
        return Reply(content, json.optString("model", model), usage)
    }

    private fun google(apiKey: String, model: String, system: String, user: String): Reply {
        val url = String.format(Locale.US, GOOGLE_URL, model)
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", user)))
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.65)
                put("maxOutputTokens", 8192)
                // Gemini can force JSON output; the prompt already asks for it, but this helps.
                put("responseMimeType", "application/json")
            })
        }
        val headers = mapOf(
            "x-goog-api-key" to apiKey,
            "Content-Type" to "application/json"
        )
        val raw = post(url, headers, body.toString())
        val json = JSONObject(raw)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason")
            throw HttpError(
                200,
                if (!blocked.isNullOrBlank()) "Google bloqueó la petición: $blocked"
                else "Google AI Studio no devolvió ninguna respuesta",
                raw
            )
        }
        val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                text.append(parts.optJSONObject(i)?.optString("text") ?: "")
            }
        }
        if (text.isBlank()) {
            val finish = candidates.optJSONObject(0)?.optString("finishReason")
            throw HttpError(
                200,
                "La respuesta vino vacía" + (if (!finish.isNullOrBlank()) " (motivo: $finish)" else "") +
                    ". Prueba con otra descripción.",
                raw
            )
        }
        val meta = json.optJSONObject("usageMetadata")
        val usage = meta?.let {
            val p = it.optInt("promptTokenCount", 0)
            val c = it.optInt("candidatesTokenCount", 0)
            if (p + c > 0) "$p+$c tokens" else null
        }
        return Reply(text.toString(), model, usage)
    }

    // ------------------------------------------------------------------ transport

    /** Simple GET, used to download the model catalogue of a provider. */
    fun get(url: String, headers: Map<String, String>): String =
        request(url, "GET", headers, null, 30_000, 45_000)

    private fun post(url: String, headers: Map<String, String>, body: String): String =
        request(url, "POST", headers, body, 30_000, 180_000)

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""
            if (code !in 200..299) {
                throw HttpError(code, explain(code, text), text)
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    /** Turns the provider's error body into something a person can act on. */
    private fun explain(code: Int, raw: String): String {
        val providerMessage = runCatching {
            val json = JSONObject(raw)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message")
                ?: ""
        }.getOrDefault("")
        val detail = providerMessage.ifBlank { raw.take(300) }
        return when (code) {
            401 -> "La API key no es válida o fue revocada. Revísala en Ajustes. ($detail)"
            402 -> "La cuenta no tiene crédito suficiente para ese modelo. ($detail)"
            403 -> "La clave no tiene permiso para este modelo. ($detail)"
            404 -> "Ese modelo ya no está disponible en el proveedor. " +
                "Pulsa «Actualizar» en la lista de modelos y elige otro. ($detail)"
            429 -> "Demasiadas peticiones o cuota agotada. Espera un momento o cambia de modelo. ($detail)"
            in 500..599 -> "El proveedor está fallando (error $code). Inténtalo de nuevo en un minuto."
            else -> "Error $code del proveedor. $detail"
        }
    }
}
