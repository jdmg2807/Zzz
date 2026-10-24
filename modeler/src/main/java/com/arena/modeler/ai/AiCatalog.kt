package com.arena.modeler.ai

/**
 * The two ways of getting AI into the app, both with the user's own key: OpenRouter (one key for
 * dozens of models) and Google AI Studio (the free tier of Gemini). Nothing is proxied through a
 * server of ours; the phone talks to the provider directly.
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val keyTitle: String,
    val keyHint: String,
    val keyHelp: String,
    val keyUrl: String
) {
    OPENROUTER(
        "openrouter",
        "OpenRouter",
        "API key de OpenRouter",
        "sk-or-v1-…",
        "Una sola clave da acceso a Gemini, GPT, Claude, DeepSeek, Llama y más.",
        "https://openrouter.ai/keys"
    ),
    GOOGLE_AI_STUDIO(
        "google",
        "Google AI Studio",
        "API key de Google AI Studio",
        "AIza…",
        "Tiene nivel gratuito con Gemini. Se genera en AI Studio, sin tarjeta.",
        "https://aistudio.google.com/apikey"
    );

    companion object {
        fun fromId(id: String): AiProvider = values().firstOrNull { it.id == id } ?: OPENROUTER
        fun all(): List<AiProvider> = values().toList()
    }
}

/**
 * Textos fijos de la interfaz de IA. **No hay catálogo de modelos escrito a mano**: la lista
 * siempre se descarga del proveedor (ver [ModelCatalog]) para no ofrecer modelos que ya no
 * existen ni precios inventados.
 */
object AiCatalog {

    /**
     * Ideas shown as chips in the panel so the feature is discoverable without typing anything.
     */
    val ideas: List<String> = listOf(
        "Un zorro de estilo voxel, sentado",
        "Una espada mágica con hoja flotante",
        "Un dron de exploración con cuatro rotores girando",
        "Una casa pequeña con techo a dos aguas",
        "Un árbol estilizado de tres copas",
        "Un cohete con aleta y llama animada"
    )
}
