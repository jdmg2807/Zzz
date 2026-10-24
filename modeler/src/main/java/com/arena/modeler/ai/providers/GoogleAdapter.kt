package com.arena.modeler.ai.providers

import com.arena.modeler.core.Text64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/**
 * Adaptador de **Google AI Studio / Gemini API** (`generativelanguage.googleapis.com/v1beta`).
 *
 * - Catálogo: `GET /v1beta/models` (nombre, displayName, límites de tokens, métodos soportados).
 * - Chat: `POST /v1beta/models/{model}:generateContent` con `systemInstruction`, historial y
 *   `inline_data` para las imágenes.
 */
class GoogleAdapter : AiProviderAdapter {

    override val id = "google"
    override val label = "Google AI Studio"
    override val keyHint = "AIza…"
    override val keyUrl = "https://aistudio.google.com/apikey"
    override val keyHelp = "Gemini. Tiene nivel gratuito con Flash y Gemma; los modelos Pro piden facturación."

    override fun fetchModels(apiKey: String): List<ModelInfo> {
        if (apiKey.isBlank()) throw ProviderException(
            ProviderException.Kind.AUTH,
            "Falta la API key de Google AI Studio"
        )
        val raw = Http.get("$BASE/models?pageSize=1000", mapOf("x-goog-api-key" to apiKey))
        val root = JSONObject(raw)
        val data = root.optJSONArray("models") ?: JSONArray()
        val out = ArrayList<ModelInfo>(data.length())
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val fullName = m.optString("name")
            val modelId = fullName.removePrefix("models/")
            if (modelId.isBlank()) continue
            val lower = modelId.lowercase(Locale.ROOT)
            val methods = m.optJSONArray("supportedGenerationMethods")
            val canGenerate = methods == null || (0 until methods.length())
                .any { methods.optString(it).equals("generateContent", ignoreCase = true) }
            if (!canGenerate) continue
            if (listOf("embedding", "aqa", "imagen", "veo", "tts", "audio", "live").any { lower.contains(it) }) continue

            val display = m.optString("displayName").ifBlank { modelId }
            val context = m.optInt("inputTokenLimit", 0).takeIf { it > 0 }
            // Google no publica precio por modelo en esta API. Se marca lo que se sabe con certeza:
            // el nivel gratuito existe para Flash/Lite/Gemma, y eso NO es «precio cero», así que el
            // precio queda como desconocido y el filtro «Gratis» se basa en el nivel gratuito.
            val freeTier = lower.contains("flash") || lower.contains("gemma")
            out.add(
                ModelInfo(
                    id = modelId,
                    label = display,
                    providerId = id,
                    providerLabel = label,
                    inputPricePerMillion = null,
                    outputPricePerMillion = null,
                    contextWindow = context,
                    supportsText = true,
                    supportsImages = true,
                    supportsTools = true,
                    limitsNote = if (freeTier) "Nivel gratuito de AI Studio (con cuota)" else "Requiere facturación",
                    upstream = "Google"
                )
            )
        }
        if (out.isEmpty()) {
            throw ProviderException(
                ProviderException.Kind.BAD_RESPONSE,
                "Google no devolvió ningún modelo de texto para esta clave"
            )
        }
        return out.sortedWith(
            compareByDescending<ModelInfo> { it.limitsNote?.contains("gratuito") == true }
                .thenBy { it.label.lowercase(Locale.ROOT) }
        )
    }

    override fun checkKey(apiKey: String): String {
        val models = fetchModels(apiKey)
        val free = models.count { it.limitsNote?.contains("gratuito") == true }
        return "Conexión correcta: ${models.size} modelos disponibles ($free con nivel gratuito)."
    }

    override fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int
    ): ProviderReply {
        if (apiKey.isBlank()) throw ProviderException(
            ProviderException.Kind.AUTH,
            "Falta la API key de Google AI Studio"
        )
        val body = JSONObject()
        body.put(
            "system_instruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
        )
        val contents = JSONArray()
        for (m in messages) {
            val parts = JSONArray()
            if (m.text.isNotBlank()) parts.put(JSONObject().put("text", m.text))
            for (img in m.images) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", img.mime)
                            .put("data", Text64.encode(img.bytes))
                    )
                )
            }
            contents.put(
                JSONObject()
                    .put("role", if (m.role == ChatMessage.Role.ASSISTANT) "model" else "user")
                    .put("parts", parts)
            )
        }
        body.put("contents", contents)
        body.put(
            "generationConfig",
            JSONObject()
                .put("temperature", temperature)
                .put("maxOutputTokens", maxTokens)
        )
        val modelId = model.removePrefix("models/")
        val url = String.format(Locale.US, "$BASE/models/%s:generateContent", modelId)
        val raw = Http.post(url, mapOf("x-goog-api-key" to apiKey), body.toString())

        val json = JSONObject(raw)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason")
            if (!blocked.isNullOrBlank()) {
                throw ProviderException(
                    ProviderException.Kind.BLOCKED,
                    "Google bloqueó la petición por sus filtros de contenido ($blocked)"
                )
            }
            throw ProviderException(
                ProviderException.Kind.BAD_RESPONSE,
                "Google no devolvió ninguna respuesta"
            )
        }
        val candidate = candidates.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) text.append(parts.optJSONObject(i)?.optString("text") ?: "")
        }
        if (text.isBlank()) {
            val finish = candidate?.optString("finishReason")
            if (finish.equals("MAX_TOKENS", ignoreCase = true)) {
                throw ProviderException(
                    ProviderException.Kind.BAD_RESPONSE,
                    "La respuesta se cortó por longitud. Pide algo más pequeño o sube el límite."
                )
            }
            throw ProviderException(
                ProviderException.Kind.BAD_RESPONSE,
                "La respuesta vino vacía" + (if (!finish.isNullOrBlank()) " ($finish)" else "")
            )
        }
        val usage = json.optJSONObject("usageMetadata")?.let {
            val p = it.optInt("promptTokenCount", 0)
            val c = it.optInt("candidatesTokenCount", 0)
            if (p + c > 0) "$p+$c tokens" else null
        }
        return ProviderReply(text.toString(), modelId, usage, raw)
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/**
 * Cliente HTTP mínimo compartido por los adaptadores (`HttpURLConnection`, sin dependencias) que
 * traduce cada fallo a un [ProviderException] con categoría, para que la UI distinga «clave
 * incorrecta» de «cuota agotada» o «sin conexión».
 */
object Http {

    fun get(url: String, headers: Map<String, String>): String = request(url, "GET", headers, null, 30_000, 60_000)

    fun post(url: String, headers: Map<String, String>, body: String): String =
        request(url, "POST", headers, body, 30_000, 180_000)

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeout: Int,
        readTimeout: Int
    ): String {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                this.connectTimeout = connectTimeout
                this.readTimeout = readTimeout
                doOutput = body != null
                setRequestProperty("Accept", "application/json")
                for ((k, v) in headers) setRequestProperty(k, v)
            }
        } catch (e: Exception) {
            throw ProviderException(ProviderException.Kind.NETWORK, "No se pudo crear la conexión: ${e.message}")
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""
            if (code !in 200..299) throw errorFor(code, text)
            return text
        } catch (e: ProviderException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ProviderException(ProviderException.Kind.NETWORK, "El proveedor tardó demasiado en responder")
        } catch (e: Exception) {
            throw ProviderException(
                ProviderException.Kind.NETWORK,
                "Fallo de conexión: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Traduce el error HTTP + cuerpo del proveedor a la categoría que muestra la interfaz. */
    fun errorFor(code: Int, raw: String): ProviderException {
        val message = runCatching {
            val json = JSONObject(raw)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message")
                .ifBlank { "" }
        }.getOrDefault("")
        val detail = message.ifBlank { raw.take(300) }
        // Google devuelve el motivo en error.details[].reason además del texto.
        val reason = runCatching {
            JSONObject(raw).optJSONObject("error")?.optJSONArray("details")
                ?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("reason").takeIf { !it.isNullOrBlank() }
                    }.joinToString(",")
                }
        }.getOrNull().orEmpty()

        val kind = when {
            code == 401 || code == 403 -> ProviderException.Kind.AUTH
            code == 402 -> ProviderException.Kind.QUOTA
            code == 429 || reason.contains("RATE_LIMIT", true) || reason.contains("QUOTA", true) ->
                ProviderException.Kind.RATE_LIMIT
            code == 404 -> ProviderException.Kind.NOT_FOUND
            code >= 500 -> ProviderException.Kind.NETWORK
            else -> ProviderException.Kind.UNKNOWN
        }
        val friendly = when (kind) {
            ProviderException.Kind.AUTH -> "La API key no es válida o no tiene permiso para este modelo"
            ProviderException.Kind.QUOTA -> "La cuenta se quedó sin crédito para este modelo"
            ProviderException.Kind.RATE_LIMIT -> "Se alcanzó el límite de solicitudes o la cuota"
            ProviderException.Kind.NOT_FOUND -> "Ese modelo ya no está disponible en el proveedor"
            ProviderException.Kind.NETWORK -> "El proveedor está fallando (error $code)"
            else -> "Error $code del proveedor"
        }
        return ProviderException(kind, friendly, detail)
    }
}
