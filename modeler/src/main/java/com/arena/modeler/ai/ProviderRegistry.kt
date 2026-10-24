package com.arena.modeler.ai

import com.arena.modeler.ai.providers.AiProviderAdapter
import com.arena.modeler.ai.providers.GoogleAdapter
import com.arena.modeler.ai.providers.OpenRouterAdapter

/**
 * Registro de proveedores. La app solo habla con [AiProviderAdapter]; aquí se decide cuál se usa.
 * Añadir un proveedor nuevo es añadir una línea, sin tocar la interfaz ni el chat.
 */
object ProviderRegistry {

    private val adapters: List<AiProviderAdapter> = listOf(
        GoogleAdapter(),
        OpenRouterAdapter()
    )

    fun all(): List<AiProviderAdapter> = adapters

    fun byId(id: String): AiProviderAdapter =
        adapters.firstOrNull { it.id == id } ?: adapters.first()

    fun of(provider: AiProvider): AiProviderAdapter = byId(provider.id)

    /** Id interno (el que se guarda en ajustes) a partir del adaptador. */
    fun toLegacy(adapter: AiProviderAdapter): AiProvider = AiProvider.fromId(adapter.id)
}
