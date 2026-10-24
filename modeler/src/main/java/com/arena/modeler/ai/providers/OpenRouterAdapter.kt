package com.arena.modeler.ai.providers

import com.arena.modeler.core.Text64
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Adaptador de **OpenRouter** (`openrouter.ai/api/v1`), un agregador con catálogo público.
 *
 * - Catálogo: `GET /api/v1/models` — trae `pricing.prompt` y `pricing.completion` en **dólares por
 *   token**, `context_length`, `architecture.input_modalities` y `supported_parameters`.
 * - Chat: `POST /chat/completions` con historial y mensajes multimodales.
 *
 * Los precios se convierten a dólares por **millón** de tokens para mostrarlos como en la web del
 * proveedor. Si un campo de precio no viene, queda `null` («Precio desconocido»), nunca cero.
 */
class OpenRouterAdapter : AiProviderAdapter {

    override val id = "openrouter"
    override val label = "OpenRouter"
    override val keyHint = "sk-or-v1-…"
    override val keyUrl = "https://openrouter.ai/keys"
    override val keyHelp = "Un catálogo enorme: Gemini, GPT, Claude, DeepSeek, Llama… Algunos con coste cero."

    private val extraHeaders = mapOf(
        "HTTP-Referer" to "https://github.com/jdmg2807/Zzz",
        "X-Title" to "VoxelForge 3D"
    )

    override fun fetchModels(apiKey: String): List<ModelInfo> {
        // El catálogo es público, pero si hay clave se envía para incluir modelos privados.
        val headers = HashMap(extraHeaders)
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        val raw = Http.get("$BASE/models", headers)
        return parseModels(raw, id, label)
    }

    override fun checkKey(apiKey: String): String {
        if (apiKey.isBlank()) throw ProviderException(
            ProviderException.Kind.AUTH,
            "Falta la API key de OpenRouter"
        )
        val headers = HashMap(extraHeaders)
        headers["Authorization"] = "Bearer $apiKey"
        // `key` devuelve el estado de la clave y su límite de crédito; no consume tokens.
        val raw = Http.get("$BASE/key", headers)
        val data = runCatching { JSONObject(raw).optJSONObject("data") }.getOrNull()
        val label = data?.optString("label").orEmpty().ifBlank { "sin etiqueta" }
        val limit = if (data != null && !data.isNull("limit")) data.optDouble("limit") else null
        val usage = data?.optDouble("usage", 0.0) ?: 0.0
        return if (limit == null) {
            "Conexión correcta ($label). Sin límite de crédito configurado."
        } else {
            String.format(
                Locale.US,
                "Conexión correcta (%s). Crédito usado: %.2f de %.2f USD.",
                label, usage, limit
            )
        }
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
            "Falta la API key de OpenRouter"
        )
        val body = JSONObject()
        body.put("model", model)
        body.put("temperature", temperature)
        body.put("max_tokens", maxTokens)
        val list = JSONArray()
        list.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (m in messages) {
            val role = if (m.role == ChatMessage.Role.ASSISTANT) "assistant" else "user"
            if (m.images.isEmpty()) {
                list.put(JSONObject().put("role", role).put("content", m.text))
            } else {
                val parts = JSONArray()
                if (m.text.isNotBlank()) {
                    parts.put(JSONObject().put("type", "text").put("text", m.text))
                }
                for (img in m.images) {
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put(
                                    "url",
                                    "data:${img.mime};base64,${Text64.encode(img.bytes)}"
                                )
                            )
                    )
                }
                list.put(JSONObject().put("role", role).put("content", parts))
            }
        }
        body.put("messages", list)

        val headers = HashMap(extraHeaders)
        headers["Authorization"] = "Bearer $apiKey"
        headers["Content-Type"] = "application/json"
        val raw = Http.post("$BASE/chat/completions", headers, body.toString())

        val json = JSONObject(raw)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            val err = json.optJSONObject("error")?.optString("message")
            throw ProviderException(
                ProviderException.Kind.BAD_RESPONSE,
                err?.takeIf { it.isNotBlank() } ?: "OpenRouter no devolvió ninguna respuesta"
            )
        }
        val choice = choices.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.opt("content")?.toString() ?: ""
        if (content.isBlank()) {
            throw ProviderException(
                ProviderException.Kind.BAD_RESPONSE,
                "La respuesta vino vacía. Prueba otro modelo."
            )
        }
        val usage = json.optJSONObject("usage")?.let {
            val p = it.optInt("prompt_tokens", 0)
            val c = it.optInt("completion_tokens", 0)
            if (p + c > 0) "$p+$c tokens" else null
        }
        return ProviderReply(content, json.optString("model", model), usage, raw)
    }

    companion object {

        private const val BASE = "https://openrouter.ai/api/v1"

        /** Separado para poder probarlo con un JSON de ejemplo sin tocar la red. */
        fun parseModels(raw: String, providerId: String, providerLabel: String): List<ModelInfo> {
            val root = JSONObject(raw)
            val data = root.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<ModelInfo>(data.length())
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                val id = m.optString("id")
                if (id.isBlank()) continue
                val pricing = m.optJSONObject("pricing")
                val prompt = price(pricing?.opt("prompt"))
                val completion = price(pricing?.opt("completion"))
                val architecture = m.optJSONObject("architecture")
                val modalities = architecture?.optJSONArray("input_modalities")
                val inputs = HashSet<String>()
                if (modalities != null) {
                    for (k in 0 until modalities.length()) inputs.add(modalities.optString(k).lowercase(Locale.ROOT))
                }
                val supported = m.optJSONArray("supported_parameters")
                val tools = supported != null && (0 until supported.length())
                    .any { supported.optString(it).equals("tools", ignoreCase = true) }
                val context = m.optInt("context_length", 0).takeIf { it > 0 }
                val free = id.endsWith(":free")
                out.add(
                    ModelInfo(
                        id = id,
                        label = m.optString("name").ifBlank { id },
                        providerId = providerId,
                        providerLabel = providerLabel,
                        inputPricePerMillion = if (free) 0.0 else prompt,
                        outputPricePerMillion = if (free) 0.0 else completion,
                        contextWindow = context,
                        supportsText = inputs.isEmpty() || inputs.contains("text"),
                        supportsImages = inputs.contains("image"),
                        supportsTools = tools,
                        limitsNote = if (free) "Gratis con límite de uso publicado por OpenRouter" else null,
                        upstream = id.substringBefore('/').takeIf { it.isNotBlank() }
                    )
                )
            }
            return out.sortedWith(
                compareByDescending<ModelInfo> { it.isFree }
                    .thenBy { it.label.lowercase(Locale.ROOT) }
            )
        }

        /** Precio por token (string o número) → precio por millón. `null` si no viene o no es válido. */
        private fun price(value: Any?): Double? {
            val d = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            } ?: return null
            if (d.isNaN() || d < 0.0) return null
            return d * 1_000_000.0
        }
    }

}
