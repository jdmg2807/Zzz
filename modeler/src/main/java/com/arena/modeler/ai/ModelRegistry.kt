package com.arena.modeler.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The model catalogue, fetched from the provider itself instead of hard-coded: both OpenRouter
 * and Google publish their live list, so the app can offer **any** model the account can use —
 * including ones released after this APK — plus a free/paid filter.
 *
 * The list is cached on disk so the picker still works offline and survives restarts.
 */
object ModelRegistry {

    /** One entry of the picker. */
    class ModelEntry(
        val id: String,
        val label: String,
        val free: Boolean,
        /** Context window in tokens, 0 when the provider does not say. */
        val context: Int,
        /** Short extra line: price, family or provider. */
        val note: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("label", label)
            put("free", free)
            put("context", context)
            put("note", note)
        }

        companion object {
            fun from(json: JSONObject): ModelEntry = ModelEntry(
                json.optString("id"),
                json.optString("label"),
                json.optBoolean("free", false),
                json.optInt("context", 0),
                json.optString("note")
            )
        }
    }

    class Result(val models: List<ModelEntry>, val fromNetwork: Boolean, val warning: String? = null)

    /** Filter chips of the picker. */
    enum class PriceFilter(val label: String) {
        ALL("Todos"),
        FREE("Gratis"),
        PAID("De pago")
    }

    // ------------------------------------------------------------------ fetching

    /**
     * Downloads the catalogue. [apiKey] may be empty for OpenRouter (its model list is public);
     * Google needs it because the list is per-account.
     */
    fun fetch(provider: AiProvider, apiKey: String): Result {
        val raw = when (provider) {
            AiProvider.OPENROUTER -> AiHttp.get(OPENROUTER_MODELS, openRouterHeaders(apiKey))
            AiProvider.GOOGLE_AI_STUDIO -> {
                if (apiKey.isBlank()) throw AiHttp.HttpError(401, "Falta la API key para consultar los modelos", "")
                AiHttp.get(GOOGLE_MODELS, mapOf("x-goog-api-key" to apiKey))
            }
        }
        val models = when (provider) {
            AiProvider.OPENROUTER -> parseOpenRouter(raw)
            AiProvider.GOOGLE_AI_STUDIO -> parseGoogle(raw)
        }
        if (models.isEmpty()) throw IllegalArgumentException("El proveedor devolvió una lista vacía")
        return Result(models, true)
    }

    /** Reads the cached list, or the bundled one when nothing was downloaded yet. */
    fun cached(provider: AiProvider): List<ModelEntry> {
        val file = ObjectStore.modelCacheFile(provider)
        if (file.exists()) {
            val parsed = runCatching {
                val arr = JSONArray(file.readText())
                val out = ArrayList<ModelEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val entry = ModelEntry.from(arr.getJSONObject(i))
                    if (entry.id.isNotBlank()) out.add(entry)
                }
                out
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return AiCatalog.models(provider).map {
            ModelEntry(it.id, it.label, it.free, 0, it.note)
        }
    }

    fun save(provider: AiProvider, models: List<ModelEntry>) {
        runCatching {
            val arr = JSONArray()
            for (m in models) arr.put(m.toJson())
            ObjectStore.modelCacheFile(provider).writeText(arr.toString())
        }
    }

    // ------------------------------------------------------------------ parsers

    private fun openRouterHeaders(apiKey: String): Map<String, String> {
        val headers = HashMap<String, String>()
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        headers["HTTP-Referer"] = "https://github.com/jdmg2807/Zzz"
        headers["X-Title"] = "VoxelForge 3D"
        return headers
    }

    /**
     * OpenRouter `/api/v1/models`: `data[]` with `id`, `name`, `context_length` and `pricing`
     * (US dollars **per token**, so 0 means free).
     */
    fun parseOpenRouter(raw: String): List<ModelEntry> {
        val root = JSONObject(raw)
        val data = root.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<ModelEntry>(data.length())
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id")
            if (id.isBlank()) continue
            val pricing = m.optJSONObject("pricing")
            val promptPrice = num(pricing?.opt("prompt"))
            val completionPrice = num(pricing?.opt("completion"))
            val free = id.endsWith(":free") || (promptPrice <= 0.0 && completionPrice <= 0.0)
            val name = m.optString("name").ifBlank { id }
            val context = m.optInt("context_length", 0)
            out.add(
                ModelEntry(
                    id = id,
                    label = name,
                    free = free,
                    context = context,
                    note = if (free) "Gratis" else priceLabel(promptPrice, completionPrice)
                )
            )
        }
        // Free first, then alphabetical by vendor so the picker feels ordered.
        return out.sortedWith(
            compareByDescending<ModelEntry> { it.free }.thenBy { it.label.lowercase(Locale.ROOT) }
        )
    }

    /**
     * Google `v1beta/models`: `models[]` with `name` = `models/<id>`, `displayName`,
     * `supportedGenerationMethods` and `inputTokenLimit`. Only text-generation models are kept.
     */
    fun parseGoogle(raw: String): List<ModelEntry> {
        val root = JSONObject(raw)
        val data = root.optJSONArray("models") ?: JSONArray()
        val out = ArrayList<ModelEntry>(data.length())
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val fullName = m.optString("name")
            val id = fullName.removePrefix("models/")
            if (id.isBlank()) continue
            val methods = m.optJSONArray("supportedGenerationMethods")
            val canGenerate = methods == null || (0 until methods.length())
                .any { methods.optString(it).equals("generateContent", ignoreCase = true) }
            if (!canGenerate) continue
            val lower = id.lowercase(Locale.ROOT)
            // Embeddings, images, video and audio models cannot produce a scene.
            if (listOf("embedding", "aqa", "imagen", "veo", "tts", "audio", "live", "native-audio")
                    .any { lower.contains(it) }
            ) continue
            val display = m.optString("displayName").ifBlank { id }
            val inputLimit = m.optInt("inputTokenLimit", 0)
            out.add(
                ModelEntry(
                    id = id,
                    label = display,
                    free = isFreeTierGoogle(lower),
                    context = inputLimit,
                    note = if (isFreeTierGoogle(lower)) "Nivel gratuito" else "De pago"
                )
            )
        }
        return out.sortedWith(
            compareByDescending<ModelEntry> { it.free }.thenBy { it.label.lowercase(Locale.ROOT) }
        )
    }

    /**
     * Gemini's API does not expose prices, so this is a documented rule of thumb: the small
     * `flash`/`flash-lite`/`gemma` models work on the AI Studio free tier, the `pro` ones need
     * billing once the free quota runs out.
     */
    private fun isFreeTierGoogle(lower: String): Boolean =
        lower.contains("flash") || lower.contains("gemma") || lower.contains("lite")

    private fun num(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    /** "$0.10 / M entrada" style label, using the per-token prices of the API. */
    private fun priceLabel(prompt: Double, completion: Double): String {
        fun perMillion(v: Double): String = when {
            v <= 0.0 -> "0"
            v * 1_000_000 < 0.01 -> "<0,01"
            else -> String.format(Locale.US, "%.2f", v * 1_000_000).replace('.', ',')
        }
        return "${perMillion(prompt)} / ${perMillion(completion)} \$ por millón"
    }

    // ------------------------------------------------------------------ endpoints

    private const val OPENROUTER_MODELS = "https://openrouter.ai/api/v1/models"
    private const val GOOGLE_MODELS = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
}
