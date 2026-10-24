package com.arena.modeler.ai

import com.arena.modeler.core.ObjectStoreFiles
import com.arena.modeler.core.Text64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Historial de conversación **por proyecto**. Se guarda en disco (JSON) para que al reabrir un
 * proyecto el chat siga donde estaba, y para no perder contexto al cambiar de proveedor.
 */
class ChatStore(private val files: ObjectStoreFiles) {

    class Message(
        val id: Long,
        val role: Role,
        val text: String,
        val at: Long,
        /** Operaciones aplicadas en este turno (texto ya formateado), si hubo. */
        val operations: List<String> = emptyList(),
        val error: String? = null,
        val imagesAttached: Int = 0
    ) {
        enum class Role { USER, ASSISTANT, SYSTEM }

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("role", role.name.lowercase())
            put("text", text)
            put("at", at)
            put("ops", JSONArray(operations))
            if (error != null) put("error", error) else put("error", JSONObject.NULL)
            put("images", imagesAttached)
        }

        companion object {
            fun fromJson(json: JSONObject): Message = Message(
                id = json.optLong("id"),
                role = when (json.optString("role")) {
                    "user" -> Role.USER
                    "system" -> Role.SYSTEM
                    else -> Role.ASSISTANT
                },
                text = json.optString("text"),
                at = json.optLong("at"),
                operations = runCatching {
                    val arr = json.optJSONArray("ops") ?: JSONArray()
                    (0 until arr.length()).map { arr.optString(it) }
                }.getOrDefault(emptyList()),
                error = if (json.isNull("error")) null else json.optString("error"),
                imagesAttached = json.optInt("images", 0)
            )
        }
    }

    private val memory = HashMap<String, MutableList<Message>>()
    private var nextId = 1L

    private fun fileFor(project: String): File =
        files.file("chat_${sanitize(project)}.json")

    fun messages(project: String): List<Message> {
        memory[project]?.let { return it.toList() }
        val loaded = loadFromDisk(project)
        memory[project] = loaded.toMutableList()
        return loaded
    }

    fun append(project: String, message: Message): Message {
        val list = memory.getOrPut(project) { loadFromDisk(project).toMutableList() }
        val withId = if (message.id > 0) message else Message(
            nextId++,
            message.role,
            message.text,
            message.at,
            message.operations,
            message.error,
            message.imagesAttached
        )
        list.add(withId)
        if (list.size > 200) {
            // Se conserva lo reciente; el historial no es un archivo de auditoría.
            val trimmed = list.takeLast(200).toMutableList()
            memory[project] = trimmed
        }
        saveToDisk(project)
        return withId
    }

    fun clear(project: String) {
        memory[project] = ArrayList()
        runCatching { fileFor(project).delete() }
    }

    /** Historial listo para enviar al modelo: solo texto, sin operaciones internas. */
    fun toProviderHistory(project: String, maxMessages: Int = 12): List<com.arena.modeler.ai.providers.ChatMessage> =
        messages(project)
            .filter { it.role != Message.Role.SYSTEM && it.text.isNotBlank() && it.error == null }
            .takeLast(maxMessages)
            .map {
                com.arena.modeler.ai.providers.ChatMessage(
                    role = if (it.role == Message.Role.USER) {
                        com.arena.modeler.ai.providers.ChatMessage.Role.USER
                    } else {
                        com.arena.modeler.ai.providers.ChatMessage.Role.ASSISTANT
                    },
                    text = it.text,
                    at = it.at
                )
            }

    private fun saveToDisk(project: String) {
        runCatching {
            val arr = JSONArray()
            for (m in memory[project] ?: return) arr.put(m.toJson())
            val root = JSONObject()
            root.put("project", project)
            root.put("messages", arr)
            fileFor(project).writeText(Text64.encode(root.toString().toByteArray(Charsets.UTF_8)))
        }
    }

    private fun loadFromDisk(project: String): List<Message> {
        val file = fileFor(project)
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = String(Text64.decode(file.readText()), Charsets.UTF_8)
            val root = JSONObject(json)
            val arr = root.optJSONArray("messages") ?: JSONArray()
            val out = ArrayList<Message>(arr.length())
            for (i in 0 until arr.length()) {
                val m = Message.fromJson(arr.getJSONObject(i))
                if (m.id >= nextId) nextId = m.id + 1
                out.add(m)
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "proyecto" }.take(48)
}
