package com.arena.modeler.ai

import com.arena.modeler.ai.providers.AiProviderAdapter
import com.arena.modeler.ai.providers.ChatMessage
import com.arena.modeler.ai.providers.ProviderException
import com.arena.modeler.tools.ToolCall
import com.arena.modeler.tools.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Une "hablar con el modelo" con "hacer cosas en la escena", manteniendo las dos partes separadas:
 *
 * 1. El modelo **interpreta** el lenguaje y responde con un JSON: `{"reply": "...", "operations":[...]}`.
 * 2. La app **valida y ejecuta** esas operaciones con [com.arena.modeler.tools.ToolExecutor].
 *
 * El modelo nunca ve ni ejecuta código; solo puede pedir herramientas del catálogo. Si devuelve una
 * herramienta que no existe o argumentos inválidos, el ejecutor la rechaza y el usuario recibe el
 * motivo sin que la escena cambie.
 */
object ChatEngine {

    class ChatException(message: String, val kind: String = "error") : Exception(message)

    /** Resultado de una vuelta del chat: lo que dice el modelo + lo que pide hacer. */
    class Turn(
        val reply: String,
        val calls: List<ChatCall>,
        val model: String,
        val usage: String?
    ) {
        val hasOperations: Boolean get() = calls.isNotEmpty()
        val needsConfirmation: Boolean
            get() = calls.any { it.destructive }
    }

    class ChatCall(val call: ToolCall, val destructive: Boolean, val note: String)

    private val destructiveTools = setOf("delete_object", "boolean", "mirror_mesh", "reduce_mesh")

    /** Prompt del sistema: rol, formato de respuesta y catálogo de herramientas. */
    fun systemPrompt(sceneSummary: String): String = """
Eres el asistente de modelado 3D de VoxelForge 3D, una app móvil. Trabajas SOBRE LA ESCENA DEL
USUARIO ejecutando operaciones estructuradas. No puedes escribir código ni ejecutar nada: solo
puedes pedir herramientas de la lista.

RESPONDE SIEMPRE CON UN ÚNICO OBJETO JSON, sin texto fuera del JSON:
{
  "reply": "explicación breve en español de lo que vas a hacer o has entendido",
  "operations": [ { "tool": "nombre", "args": { ... } } ]
}

Reglas:
- Si el usuario solo pregunta o conversa, devuelve "operations": [] y explica en "reply".
- Usa nombres de objeto que EXISTAN (los tienes en la escena de abajo) o crea objetos nuevos con
  create_primitive / create_mesh / create_curve y luego modifícalos.
- Para crear algo con volumen, encadena operaciones: crear primitivas y después boolean, bevel,
  subdivide, transform_object… Esa es la forma de construir geometría real.
- Prefiere pocas operaciones bien pensadas antes que muchas operaciones destructivas.
- Los colores van en hexadecimal "#RRGGBB". Tamaños en metros, con Y hacia arriba y el suelo en 0.
- Si la petición es ambigua, pide una aclaración y no devuelvas operaciones.
- Nunca inventes herramientas que no estén en el catálogo.

ESCENA ACTUAL
$sceneSummary

CATÁLOGO DE HERRAMIENTAS (argumentos entre corchetes, "req" = obligatorio)
${ToolSpec.promptCatalog()}
""".trimIndent()

    /**
     * Envía la conversación y devuelve la interpretación. [history] debe terminar en el mensaje del
     * usuario; [history] incluye imágenes de referencia si el modelo las admite.
     */
    fun send(
        adapter: AiProviderAdapter,
        apiKey: String,
        model: String,
        sceneSummary: String,
        history: List<ChatMessage>,
        temperature: Double = 0.3
    ): Turn {
        if (apiKey.isBlank()) throw ChatException("Falta la API key de ${adapter.label}", "auth")
        if (history.isEmpty()) throw ChatException("No hay ningún mensaje que enviar", "empty")
        val reply = try {
            adapter.complete(
                apiKey = apiKey,
                model = model,
                systemPrompt = systemPrompt(sceneSummary),
                messages = history,
                temperature = temperature,
                maxTokens = 4096
            )
        } catch (e: ProviderException) {
            throw ChatException(e.message ?: "El proveedor falló", kindFor(e.kind))
        } catch (e: Exception) {
            throw ChatException("No se pudo conectar con ${adapter.label}: ${e.message}", "network")
        }
        return parse(reply.text, reply.model, reply.usage)
    }

    private fun kindFor(kind: ProviderException.Kind): String = when (kind) {
        ProviderException.Kind.AUTH -> "auth"
        ProviderException.Kind.QUOTA -> "quota"
        ProviderException.Kind.RATE_LIMIT -> "rate_limit"
        ProviderException.Kind.NOT_FOUND -> "not_found"
        ProviderException.Kind.NETWORK -> "network"
        ProviderException.Kind.BLOCKED -> "blocked"
        ProviderException.Kind.BAD_RESPONSE -> "bad_response"
        else -> "error"
    }

    /** Interpreta la respuesta del modelo. Público para poder probarlo sin red. */
    fun parse(raw: String, model: String = "", usage: String? = null): Turn {
        val jsonText = extractJson(raw)
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            // Algunos modelos responden solo con texto: se acepta como conversación sin operaciones.
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw ChatException("El modelo devolvió una respuesta vacía", "bad_response")
            return Turn(trimmed.take(4000), emptyList(), model, usage)
        }
        val reply = root.optString("reply").ifBlank { root.optString("message") }.ifBlank {
            "He preparado los cambios."
        }
        val ops = root.optJSONArray("operations") ?: root.optJSONArray("calls") ?: JSONArray()
        val calls = ArrayList<ChatCall>(ops.length())
        for (i in 0 until minOf(ops.length(), 24)) {
            val op = ops.optJSONObject(i) ?: continue
            val tool = op.optString("tool").ifBlank { op.optString("name") }
            if (tool.isBlank()) continue
            val spec = ToolSpec.find(tool)
            val args = op.optJSONObject("args") ?: op.optJSONObject("arguments") ?: JSONObject()
            calls.add(
                ChatCall(
                    ToolCall(tool, args),
                    destructive = tool in destructiveTools || spec?.name == "delete_object",
                    note = spec?.description ?: "Herramienta desconocida: no se ejecutará"
                )
            )
        }
        return Turn(reply, calls, model, usage)
    }

    /** Extrae el JSON aunque venga dentro de un bloque de código o con texto alrededor. */
    fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)
        val candidate = fenced?.groupValues?.get(1)?.trim() ?: trimmed
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return candidate
        return candidate.substring(start, end + 1)
    }

    /** Resumen corto que se muestra en el chat cuando se aplican operaciones. */
    fun describe(calls: List<ChatCall>): String =
        calls.joinToString("\n") { call ->
            val args = call.call.args
            val detail = args.keys().asSequence().joinToString(", ") { key ->
                "$key=${args.opt(key).toString().take(40)}"
            }
            "• ${call.call.tool}" + if (detail.isBlank()) "" else " ($detail)"
        }
}
