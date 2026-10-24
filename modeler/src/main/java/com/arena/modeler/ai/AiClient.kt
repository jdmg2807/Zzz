package com.arena.modeler.ai

import com.arena.modeler.scene.SceneDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One call from the app to "a model": builds the prompt, asks the provider, parses the answer and
 * returns a ready-to-use scene. Running on [Dispatchers.IO] keeps the viewport responsive while
 * the model thinks.
 */
object AiClient {

    class AiException(message: String) : Exception(message)

    suspend fun generate(
        idea: String,
        provider: AiProvider,
        apiKey: String,
        model: String,
        previous: SceneDocument? = null
    ): SceneDocument = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw AiException("Falta la API key de ${provider.label}. Añádela en Ajustes.")
        }
        val system = AiSchema.SYSTEM_PROMPT
        val user = if (previous != null) {
            AiSchema.refinePrompt(idea, AiSchema.sceneToJson(previous))
        } else {
            AiSchema.userPrompt(idea)
        }
        val reply = try {
            AiHttp.ask(provider, apiKey, model, system, user)
        } catch (e: AiHttp.HttpError) {
            throw AiException(e.message ?: "El proveedor respondió con un error")
        } catch (e: Exception) {
            throw AiException("No se pudo conectar con ${provider.label}: ${e.message ?: "error de red"}")
        }
        val doc = try {
            AiSchema.parseScene(reply.text, idea.trim().take(32).ifBlank { "Modelo IA" })
        } catch (e: IllegalArgumentException) {
            throw AiException(e.message ?: "La respuesta no tenía un modelo válido")
        }
        doc
    }
}
