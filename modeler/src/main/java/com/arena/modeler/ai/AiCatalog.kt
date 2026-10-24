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

/** One selectable model: the id sent to the API plus a friendly label. */
class AiModel(val id: String, val label: String, val note: String = "")

object AiCatalog {

    val openRouterModels: List<AiModel> = listOf(
        AiModel("google/gemini-2.0-flash-001", "Gemini 2.0 Flash", "rápido y económico"),
        AiModel("openai/gpt-4o-mini", "GPT-4o mini", "buen equilibrio"),
        AiModel("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", "mejor para formas complejas"),
        AiModel("deepseek/deepseek-chat", "DeepSeek V3", "muy bueno con JSON"),
        AiModel("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B", "abierto"),
        AiModel("qwen/qwen-2.5-72b-instruct", "Qwen 2.5 72B", "abierto")
    )

    val googleModels: List<AiModel> = listOf(
        AiModel("gemini-2.0-flash", "Gemini 2.0 Flash", "recomendado"),
        AiModel("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite", "el más rápido"),
        AiModel("gemini-2.5-flash", "Gemini 2.5 Flash", "nuevo, con mejor razonamiento"),
        AiModel("gemini-2.5-pro", "Gemini 2.5 Pro", "máxima calidad"),
        AiModel("gemini-1.5-flash", "Gemini 1.5 Flash", "compatibilidad")
    )

    fun models(provider: AiProvider): List<AiModel> = when (provider) {
        AiProvider.OPENROUTER -> openRouterModels
        AiProvider.GOOGLE_AI_STUDIO -> googleModels
    }

    fun defaultModel(provider: AiProvider): String = models(provider).first().id

    fun find(provider: AiProvider, id: String): AiModel? = models(provider).firstOrNull { it.id == id }

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
