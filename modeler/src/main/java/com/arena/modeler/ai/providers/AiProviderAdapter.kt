package com.arena.modeler.ai.providers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Una entrada del catálogo de modelos, tal como la publica el proveedor.
 *
 * Los precios son **por millón de tokens** y son `null` cuando el proveedor no los publica: un
 * precio desconocido nunca se trata como cero (regla del pliego: no clasificar como gratis lo que
 * no se sabe).
 */
class ModelInfo(
    val id: String,
    val label: String,
    val providerId: String,
    val providerLabel: String,
    /** USD por millón de tokens de entrada, `null` = desconocido. */
    val inputPricePerMillion: Double?,
    /** USD por millón de tokens de salida, `null` = desconocido. */
    val outputPricePerMillion: Double?,
    val contextWindow: Int?,
    val supportsText: Boolean = true,
    val supportsImages: Boolean = false,
    val supportsTools: Boolean = false,
    /** Límite conocido publicado por el proveedor (p. ej. "50 req/día"), o null. */
    val limitsNote: String? = null,
    /** Proveedor de origen del modelo cuando es un agregador (OpenRouter). */
    val upstream: String? = null
) {
    /** Gratis solo con datos que lo confirmen: ambos precios conocidos y a cero. */
    val isFree: Boolean
        get() = inputPricePerMillion != null && outputPricePerMillion != null &&
            inputPricePerMillion == 0.0 && outputPricePerMillion == 0.0

    /** Se paga seguro: algún precio conocido por encima de cero. */
    val isPaid: Boolean
        get() = (inputPricePerMillion ?: 0.0) > 0.0 || (outputPricePerMillion ?: 0.0) > 0.0

    /** Ni confirmado gratis ni confirmado de pago. */
    val isUnknownPrice: Boolean
        get() = !isFree && !isPaid

    fun priceLabel(): String = when {
        isFree -> "Gratis"
        isPaid -> "${formatPrice(inputPricePerMillion)} / ${formatPrice(outputPricePerMillion)} por millón"
        else -> "Precio desconocido"
    }

    fun capabilitiesLabel(): String {
        val caps = ArrayList<String>()
        if (supportsText) caps.add("texto")
        if (supportsImages) caps.add("imágenes")
        if (supportsTools) caps.add("herramientas")
        return if (caps.isEmpty()) "sin datos" else caps.joinToString(", ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("providerId", providerId)
        put("providerLabel", providerLabel)
        if (inputPricePerMillion != null) put("inputPrice", inputPricePerMillion) else put("inputPrice", JSONObject.NULL)
        if (outputPricePerMillion != null) put("outputPrice", outputPricePerMillion) else put("outputPrice", JSONObject.NULL)
        if (contextWindow != null) put("context", contextWindow) else put("context", JSONObject.NULL)
        put("text", supportsText)
        put("images", supportsImages)
        put("tools", supportsTools)
        if (limitsNote != null) put("limits", limitsNote)
        if (upstream != null) put("upstream", upstream)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelInfo = ModelInfo(
            id = json.optString("id"),
            label = json.optString("label"),
            providerId = json.optString("providerId"),
            providerLabel = json.optString("providerLabel"),
            inputPricePerMillion = if (json.isNull("inputPrice")) null else json.optDouble("inputPrice"),
            outputPricePerMillion = if (json.isNull("outputPrice")) null else json.optDouble("outputPrice"),
            contextWindow = if (json.isNull("context")) null else json.optInt("context"),
            supportsText = json.optBoolean("text", true),
            supportsImages = json.optBoolean("images", false),
            supportsTools = json.optBoolean("tools", false),
            limitsNote = if (json.isNull("limits")) null else json.optString("limits"),
            upstream = if (json.isNull("upstream")) null else json.optString("upstream")
        )

        private fun formatPrice(value: Double?): String = when {
            value == null -> "?"
            value == 0.0 -> "0"
            value < 0.01 -> "<0,01"
            else -> String.format(java.util.Locale.US, "%.2f", value).replace('.', ',')
        }

        /** Lista lista para guardar en disco. */
        fun listToJson(models: List<ModelInfo>): String {
            val arr = JSONArray()
            for (m in models) arr.put(m.toJson())
            return arr.toString()
        }

        fun listFromJson(raw: String): List<ModelInfo> = runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<ModelInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val m = fromJson(arr.getJSONObject(i))
                if (m.id.isNotBlank()) out.add(m)
            }
            out
        }.getOrDefault(emptyList())
    }
}

/** Un mensaje del chat. Las imágenes van como bytes + tipo MIME (se envían en base64). */
class ChatMessage(
    val role: Role,
    val text: String,
    val images: List<ImageAttachment> = emptyList(),
    val at: Long = System.currentTimeMillis()
) {
    enum class Role { USER, ASSISTANT, SYSTEM }

    class ImageAttachment(val bytes: ByteArray, val mime: String)

    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role.name.lowercase())
        put("text", text)
        put("at", at)
        put("images", images.size)
    }
}

/** Respuesta de un proveedor: texto + uso + modelo real usado. */
class ProviderReply(val text: String, val model: String, val usage: String?, val raw: String = "")

class ProviderException(
    val kind: Kind,
    message: String,
    val detail: String = ""
) : Exception(message) {
    enum class Kind { AUTH, QUOTA, RATE_LIMIT, NOT_FOUND, NETWORK, BLOCKED, BAD_RESPONSE, UNKNOWN }
}

/**
 * Interfaz común de los proveedores. Añadir uno nuevo (p. ej. un servicio de generación de mallas)
 * consiste en implementar esta interfaz; la app no conoce ningún detalle concreto de la API.
 */
interface AiProviderAdapter {

    val id: String
    val label: String
    val keyHint: String
    val keyUrl: String
    val keyHelp: String

    /** Descarga el catálogo de modelos disponibles para esa clave. */
    fun fetchModels(apiKey: String): List<ModelInfo>

    /** Comprobación barata de que la clave funciona (lista de modelos o petición mínima). */
    fun checkKey(apiKey: String): String

    /**
     * Conversación completa. [model] es el id exacto del proveedor; [messages] incluye el historial.
     */
    fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.4,
        maxTokens: Int = 4096
    ): ProviderReply
}
