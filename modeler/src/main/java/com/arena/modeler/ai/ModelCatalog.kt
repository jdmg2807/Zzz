package com.arena.modeler.ai

import com.arena.modeler.ai.providers.ModelInfo
import com.arena.modeler.ai.providers.ProviderException
import com.arena.modeler.core.ObjectStoreFiles
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Catálogo de modelos por proveedor, **siempre dinámico**: se descarga de la API y se guarda en
 * disco con su fecha. Nunca se inventan modelos ni precios.
 *
 * Reglas que aplica (Fase 4 del pliego):
 * - «Gratis» solo si el proveedor publica precio 0 de entrada **y** de salida (o marca la entrada
 *   como gratuita de forma explícita). Un precio desconocido va a «Precio desconocido».
 * - Si la consulta falla, se conserva la última lista buena y se expone como **desactualizada**.
 * - La UI puede forzar la actualización al abrir el selector y con un botón.
 */
class ModelCatalog(private val files: ObjectStoreFiles) {

    enum class PriceFilter(val label: String) {
        ALL("Todos"),
        FREE("Gratis"),
        PAID("De pago"),
        UNKNOWN("Precio desconocido")
    }

    class Snapshot(
        val models: List<ModelInfo>,
        val updatedAt: Long,
        val stale: Boolean,
        val error: String? = null
    ) {
        val isEmpty: Boolean get() = models.isEmpty()
    }

    private val cache = HashMap<String, Snapshot>()
    private val listeners = ArrayList<(String) -> Unit>()

    fun snapshot(providerId: String): Snapshot =
        cache[providerId] ?: loadFromDisk(providerId).also { cache[providerId] = it }

    fun allKnown(): List<ModelInfo> = cache.values.flatMap { it.models }

    fun find(providerId: String, modelId: String): ModelInfo? =
        snapshot(providerId).models.firstOrNull { it.id == modelId }

    /** Descarga la lista. Si falla, devuelve la anterior marcada como desactualizada. */
    fun refresh(providerId: String, apiKey: String): Snapshot {
        val adapter = ProviderRegistry.byId(providerId)
        return try {
            val models = adapter.fetchModels(apiKey)
            val snapshot = Snapshot(models, System.currentTimeMillis(), stale = false)
            cache[providerId] = snapshot
            saveToDisk(providerId, snapshot)
            notify(providerId)
            snapshot
        } catch (e: ProviderException) {
            val previous = snapshot(providerId)
            val stale = previous.copyWith(stale = true, error = e.message ?: "Error de consulta")
            cache[providerId] = stale
            stale
        } catch (e: Exception) {
            val previous = snapshot(providerId)
            val stale = previous.copyWith(stale = true, error = e.message ?: "Error de consulta")
            cache[providerId] = stale
            stale
        }
    }

    /** Filtra por precio y por texto de búsqueda (id o nombre). */
    fun filter(models: List<ModelInfo>, filter: PriceFilter, query: String): List<ModelInfo> {
        val q = query.trim().lowercase(Locale.ROOT)
        return models.filter { m ->
            val priceOk = when (filter) {
                PriceFilter.ALL -> true
                PriceFilter.FREE -> isFreeForFilter(m)
                PriceFilter.PAID -> !isFreeForFilter(m) && !m.isUnknownPrice
                PriceFilter.UNKNOWN -> m.isUnknownPrice
            }
            val queryOk = q.isEmpty() ||
                m.id.lowercase(Locale.ROOT).contains(q) ||
                m.label.lowercase(Locale.ROOT).contains(q) ||
                (m.upstream?.lowercase(Locale.ROOT)?.contains(q) ?: false)
            priceOk && queryOk
        }
    }

    /**
     * Para Google no hay precio publicado, pero sí un nivel gratuito declarado por el proveedor:
     * el filtro «Gratis» lo usa y la fila lo explica con su `limitsNote`, sin mentir sobre el precio.
     */
    fun isFreeForFilter(model: ModelInfo): Boolean =
        model.isFree || model.limitsNote?.contains("gratuito", ignoreCase = true) == true

    fun freeCount(models: List<ModelInfo>): Int = models.count { isFreeForFilter(it) }

    fun paidCount(models: List<ModelInfo>): Int = models.count { !isFreeForFilter(it) && !it.isUnknownPrice }

    fun unknownCount(models: List<ModelInfo>): Int = models.count { it.isUnknownPrice }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    private fun notify(providerId: String) {
        for (l in listeners) l(providerId)
    }

    // ------------------------------------------------------------------ disco

    private fun cacheFile(providerId: String) = files.file("catalogo_$providerId.json")

    private fun saveToDisk(providerId: String, snapshot: Snapshot) {
        runCatching {
            val root = JSONObject()
            root.put("updatedAt", snapshot.updatedAt)
            root.put("models", JSONArray(snapshot.models.map { it.toJson() }))
            cacheFile(providerId).writeText(root.toString())
        }
    }

    private fun loadFromDisk(providerId: String): Snapshot {
        val file = cacheFile(providerId)
        if (!file.exists()) return Snapshot(emptyList(), 0L, stale = true)
        return runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("models") ?: JSONArray()
            val models = ArrayList<ModelInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val m = ModelInfo.fromJson(arr.getJSONObject(i))
                if (m.id.isNotBlank()) models.add(m)
            }
            Snapshot(models, root.optLong("updatedAt", 0L), stale = true)
        }.getOrElse { Snapshot(emptyList(), 0L, stale = true) }
    }

    /** Borra la caché de un proveedor (por si el usuario quiere empezar de cero). */
    fun clear(providerId: String) {
        cache.remove(providerId)
        runCatching { cacheFile(providerId).delete() }
    }

    private fun Snapshot.copyWith(stale: Boolean, error: String?): Snapshot =
        Snapshot(models, updatedAt, stale, error)

    companion object {
        /** Texto relativo sencillo para la fecha de actualización. */
        fun updatedLabel(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
            if (updatedAt <= 0L) return "nunca actualizado"
            val minutes = (now - updatedAt) / 60_000L
            return when {
                minutes < 1 -> "actualizado hace un momento"
                minutes < 60 -> "actualizado hace $minutes min"
                minutes < 60 * 24 -> "actualizado hace ${minutes / 60} h"
                else -> "actualizado hace ${minutes / (60 * 24)} días"
            }
        }
    }
}
