package com.arena.modeler.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Catálogo de herramientas que la IA puede pedir. Cada herramienta declara sus argumentos con
 * tipo, rango y obligatoriedad: el modelo **no puede** ejecutar código, solo enviar una llamada
 * como `{"tool":"extrude","args":{"object":"Cubo.1","distance":0.2}}` que la app valida antes de
 * tocar la escena.
 *
 * Este archivo es la única fuente de verdad: el prompt del sistema se genera a partir de él, así
 * que lo que se le ofrece al modelo y lo que el ejecutor acepta no pueden desincronizarse.
 */
object ToolSpec {

    enum class Kind { NUMBER, INTEGER, STRING, BOOLEAN, VECTOR3, STRING_LIST, POINT_LIST, INT_LIST, FACE_LIST }

    class Param(
        val name: String,
        val kind: Kind,
        val required: Boolean = false,
        val min: Double? = null,
        val max: Double? = null,
        val default: Any? = null,
        val description: String = "",
        val options: List<String>? = null
    )

    class Tool(
        val name: String,
        val description: String,
        val params: List<Param>,
        /** Si modifica la escena (y por tanto debe ser reversible y confirmable). */
        val mutates: Boolean = true
    )

    private fun num(name: String, required: Boolean = false, min: Double? = null, max: Double? = null, default: Double? = null, desc: String = "") =
        Param(name, Kind.NUMBER, required, min, max, default, desc)

    private fun int(name: String, required: Boolean = false, min: Int? = null, max: Int? = null, default: Int? = null, desc: String = "") =
        Param(name, Kind.INTEGER, required, min?.toDouble(), max?.toDouble(), default, desc)

    private fun str(name: String, required: Boolean = false, default: String? = null, desc: String = "", options: List<String>? = null) =
        Param(name, Kind.STRING, required, null, null, default, desc, options)

    private fun bool(name: String, default: Boolean = false, desc: String = "") =
        Param(name, Kind.BOOLEAN, false, null, null, default, desc)

    private val vector3 = Param("position", Kind.VECTOR3, false, null, null, null, "[x, y, z]")
    private val obj = str("object", true, null, "Nombre del objeto en la escena")

    val tools: List<Tool> = listOf(
        Tool(
            "create_primitive",
            "Crea una forma base editable con la base apoyada en el suelo.",
            listOf(
                str("kind", true, "cube", "Forma", listOf("cube", "sphere", "cylinder", "cone", "torus", "plane", "icosphere")),
                str("name", false, null, "Nombre del objeto"),
                Param("position", Kind.VECTOR3, false, null, null, null, "[x, y, z]"),
                Param("scale", Kind.VECTOR3, false, null, null, null, "Tamaño [x, y, z]"),
                str("color", false, "#B9BEC9", "Color hexadecimal del material")
            )
        ),
        Tool(
            "create_mesh",
            "Crea una malla a partir de vértices y caras (índices desde 0). Úsalo para formas que no son primitivas.",
            listOf(
                str("name", false, "Malla", "Nombre del objeto"),
                Param("vertices", Kind.POINT_LIST, true, null, null, null, "Lista de puntos [[x,y,z], ...] (máx. 2000)"),
                Param("faces", Kind.FACE_LIST, true, null, null, null, "Lista de caras [[0,1,2], ...] con 3 o 4 índices"),
                str("color", false, "#B9BEC9", "Color hexadecimal del material")
            )
        ),
        Tool(
            "create_curve",
            "Crea un tubo siguiendo una curva Bézier/Catmull-Rom definida por puntos de control.",
            listOf(
                Param("points", Kind.POINT_LIST, true, null, null, null, "Puntos de control [[x,y,z], ...] (2 a 12)"),
                num("radius", false, 0.005, 0.5, 0.06, "Radio del tubo"),
                int("sides", false, 3, 32, 8, "Lados del tubo"),
                str("curve", false, "catmull-rom", "Tipo de curva", listOf("catmull-rom", "bezier", "polyline")),
                str("name", false, null, "Nombre del objeto")
            )
        ),
        Tool(
            "extrude",
            "Extruye las caras de un objeto. En un objeto cerrado extrusiona cada cara por su normal.",
            listOf(obj, num("distance", true, -3.0, 3.0, null, "Distancia (negativa hacia dentro)"))
        ),
        Tool(
            "inset",
            "Encoge las caras creando un anillo interior (bisel de caras).",
            listOf(obj, num("amount", true, 0.001, 1.0, null, "Grosor del anillo"))
        ),
        Tool(
            "bevel",
            "Redondea las aristas y vértices del objeto.",
            listOf(obj, num("amount", true, 0.001, 0.5, null, "Grosor del bisel"), int("segments", false, 1, 2, 1, "Pasadas"))
        ),
        Tool(
            "subdivide",
            "Subdivide la malla con Catmull-Clark (suaviza y multiplica las caras por 4 por pasada).",
            listOf(obj, int("iterations", false, 1, 2, 1, "Pasadas"), bool("smooth", true, "Suavizar"))
        ),
        Tool(
            "boolean",
            "Operación booleana entre dos objetos. El resultado reemplaza al primero.",
            listOf(
                obj,
                str("other", true, null, "Nombre del segundo objeto"),
                str("operation", true, "difference", "Operación", listOf("union", "difference", "intersection"))
            )
        ),
        Tool(
            "smooth_mesh",
            "Suaviza la malla acercando cada vértice a la media de sus vecinos.",
            listOf(obj, int("iterations", false, 1, 10, 1, "Pasadas"), num("strength", false, 0.05, 1.0, 0.5, "Intensidad"))
        ),
        Tool(
            "mirror_mesh",
            "Refleja el objeto respecto a un eje.",
            listOf(obj, str("axis", false, "x", "Eje", listOf("x", "y", "z")))
        ),
        Tool(
            "sculpt",
            "Desplaza la superficie con un pincel esférico (esculpido).",
            listOf(
                obj,
                Param("center", Kind.VECTOR3, true, null, null, null, "Centro del pincel en el espacio del objeto"),
                num("radius", true, 0.02, 3.0, null, "Radio del pincel"),
                num("strength", true, -0.5, 0.5, null, "Intensidad (negativa hunde)"),
                str("mode", false, "inflate", "Modo", listOf("inflate", "smooth", "pinch"))
            )
        ),
        Tool(
            "reduce_mesh",
            "Reduce polígonos agrupando caras coplanares.",
            listOf(obj)
        ),
        Tool(
            "assign_material",
            "Cambia el material del objeto (color, metalicidad, rugosidad, emisión).",
            listOf(
                obj,
                str("color", false, null, "Color hexadecimal #RRGGBB"),
                num("metallic", false, 0.0, 1.0, null, "Metalicidad"),
                num("roughness", false, 0.02, 1.0, null, "Rugosidad"),
                str("emission", false, null, "Color de emisión #RRGGBB")
            )
        ),
        Tool(
            "transform_object",
            "Mueve, rota (grados) o escala un objeto.",
            listOf(
                obj,
                Param("position", Kind.VECTOR3, false, null, null, null, "[x, y, z]"),
                Param("rotation", Kind.VECTOR3, false, null, null, null, "[gradosX, gradosY, gradosZ]"),
                Param("scale", Kind.VECTOR3, false, null, null, null, "[sx, sy, sz]")
            )
        ),
        Tool(
            "duplicate_object",
            "Duplica un objeto, opcionalmente desplazado.",
            listOf(obj, Param("offset", Kind.VECTOR3, false, null, null, null, "[x, y, z]"), str("name", false, null, "Nombre nuevo"))
        ),
        Tool(
            "delete_object",
            "Elimina un objeto de la escena. Es destructivo: se puede deshacer.",
            listOf(obj)
        ),
        Tool(
            "inspect_scene",
            "Devuelve un resumen de la escena (objetos, mallas, materiales). No modifica nada.",
            emptyList(),
            mutates = false
        ),
        Tool(
            "export_model",
            "Exporta la escena a un archivo de modelo.",
            listOf(
                str("format", false, "glb", "Formato", listOf("glb", "gltf", "obj", "stl", "ply", "fbx")),
                str("name", false, null, "Nombre del archivo")
            )
        )
    )

    fun find(name: String): Tool? = tools.firstOrNull { it.name == name }

    /** JSON Schema compacto que se incrusta en el prompt del sistema. */
    fun promptCatalog(): String {
        val arr = JSONArray()
        for (t in tools) {
            val o = JSONObject()
            o.put("tool", t.name)
            o.put("desc", t.description)
            val params = JSONArray()
            for (p in t.params) {
                val po = JSONObject()
                po.put("name", p.name)
                po.put("type", typeName(p.kind))
                po.put("required", p.required)
                if (p.min != null) po.put("min", p.min)
                if (p.max != null) po.put("max", p.max)
                if (p.default != null) po.put("default", p.default)
                if (p.options != null) po.put("options", JSONArray(p.options))
                if (p.description.isNotBlank()) po.put("desc", p.description)
                params.put(po)
            }
            o.put("params", params)
            arr.put(o)
        }
        return arr.toString(1)
    }

    private fun typeName(kind: Kind): String = when (kind) {
        Kind.NUMBER -> "number"
        Kind.INTEGER -> "integer"
        Kind.STRING -> "string"
        Kind.BOOLEAN -> "boolean"
        Kind.VECTOR3 -> "[number, number, number]"
        Kind.STRING_LIST -> "[string]"
        Kind.POINT_LIST -> "[[number, number, number]]"
        Kind.INT_LIST -> "[integer]"
        Kind.FACE_LIST -> "[[integer]]"
    }
}
