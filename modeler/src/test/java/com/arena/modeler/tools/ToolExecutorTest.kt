package com.arena.modeler.tools

import com.arena.modeler.scene.SceneDocument
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las herramientas son el único camino por el que la IA toca la escena: aquí se comprueba que
 * validan los argumentos, que no lanzan excepciones al usuario y que no dejan la escena a medias.
 */
class ToolExecutorTest {

    private fun executor(doc: SceneDocument) = ToolExecutor(doc)

    private fun call(tool: String, vararg args: Pair<String, Any?>): ToolCall {
        val json = JSONObject()
        for ((k, v) in args) json.put(k, v)
        return ToolCall(tool, json)
    }

    @Test
    fun `create_primitive añade un objeto con malla`() {
        val doc = SceneDocument()
        val result = executor(doc).execute(call("create_primitive", "kind" to "cube"))
        assertTrue(result.message, result.ok)
        assertEquals(1, doc.allNodes().size)
        assertNotNull(doc.allNodes().first().mesh)
        assertEquals(12, doc.totalTriangles())
        assertTrue(result.nodeId >= 0)
    }

    @Test
    fun `una herramienta desconocida no lanza`() {
        val doc = SceneDocument()
        val result = executor(doc).execute(call("ejecutar_codigo", "code" to "rm -rf /"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("desconocida"))
        assertTrue(doc.allNodes().isEmpty())
    }

    @Test
    fun `los argumentos fuera de rango se rechazan`() {
        val doc = SceneDocument()
        val ex = executor(doc)
        assertTrue(ex.execute(call("create_primitive", "kind" to "cube")).ok)
        val facesBefore = doc.allNodes().first().mesh?.triangleCount
        val result = ex.execute(call("extrude", "object" to "Cubo.1", "distance" to 99.0))
        assertFalse("99 m está fuera del rango permitido", result.ok)
        assertTrue(result.message.contains("inválidos"))
        assertEquals(facesBefore, doc.allNodes().first().mesh?.triangleCount)
    }

    @Test
    fun `un tipo inexistente de primitiva se rechaza`() {
        val doc = SceneDocument()
        val result = executor(doc).execute(call("create_primitive", "kind" to "nave_espacial"))
        assertFalse(result.ok)
        assertTrue(doc.allNodes().isEmpty())
    }

    @Test
    fun `extrude trabaja por nombre y aumenta las caras`() {
        val doc = SceneDocument()
        val ex = executor(doc)
        ex.execute(call("create_primitive", "kind" to "cube", "name" to "Base"))
        val before = doc.allNodes().first().mesh!!.triangleCount
        val result = ex.execute(call("extrude", "object" to "Base", "distance" to 0.3))
        assertTrue(result.message, result.ok)
        assertTrue(doc.allNodes().first().mesh!!.triangleCount > before)
    }

    @Test
    fun `delete_object quita el objeto de la escena`() {
        val doc = SceneDocument()
        val ex = executor(doc)
        ex.execute(call("create_primitive", "kind" to "sphere", "name" to "Bola"))
        val result = ex.execute(call("delete_object", "object" to "Bola"))
        assertTrue(result.message, result.ok)
        assertTrue(doc.allNodes().isEmpty())
    }

    @Test
    fun `la booleana reemplaza el primer objeto y borra el segundo`() {
        val doc = SceneDocument()
        val ex = executor(doc)
        ex.execute(call("create_primitive", "kind" to "cube", "name" to "A"))
        ex.execute(call("create_primitive", "kind" to "cube", "name" to "B"))
        assertEquals(2, doc.allNodes().size)
        val result = ex.execute(call("boolean", "object" to "A", "other" to "B", "operation" to "union"))
        assertTrue(result.message, result.ok)
        assertEquals(1, doc.allNodes().size)
        assertEquals("A", doc.allNodes().first().name)
    }

    @Test
    fun `create_mesh acepta vértices y caras`() {
        val doc = SceneDocument()
        val ex = executor(doc)
        val vertices = org.json.JSONArray()
            .put(org.json.JSONArray().put(0).put(0).put(0))
            .put(org.json.JSONArray().put(1).put(0).put(0))
            .put(org.json.JSONArray().put(0).put(1).put(0))
        val faces = org.json.JSONArray().put(org.json.JSONArray().put(0).put(1).put(2))
        val result = ex.execute(
            ToolCall("create_mesh", JSONObject().put("vertices", vertices).put("faces", faces).put("name", "Tri"))
        )
        assertTrue(result.message, result.ok)
        assertEquals(1, doc.totalTriangles())
    }

    @Test
    fun `sculpt recibe un punto y no rompe la malla`() {
        val doc = SceneDocument()
        val ex = executor(doc)
        ex.execute(call("create_primitive", "kind" to "sphere", "name" to "S"))
        val before = doc.allNodes().first().mesh!!.triangleCount
        val result = ex.execute(
            ToolCall(
                "sculpt",
                JSONObject()
                    .put("object", "S")
                    .put("center", org.json.JSONArray().put(0).put(0.5).put(0))
                    .put("radius", 0.2)
                    .put("strength", 0.05)
            )
        )
        assertTrue(result.message, result.ok)
        assertEquals(before, doc.allNodes().first().mesh!!.triangleCount)
    }

    @Test
    fun `inspect_scene describe la escena y no la modifica`() {
        val doc = SceneDocument()
        val ex = executor(doc)
        ex.execute(call("create_primitive", "kind" to "cube", "name" to "Cubo"))
        val summary = ex.inspectScene()
        assertTrue(summary.contains("Cubo"))
        assertEquals(1, doc.allNodes().size)
    }

    @Test
    fun `parseCall acepta tool o name`() {
        val ex = executor(SceneDocument())
        assertEquals("extrude", ex.parseCall(JSONObject("{\"tool\":\"extrude\",\"args\":{}}")).tool)
        assertEquals("extrude", ex.parseCall(JSONObject("{\"name\":\"extrude\"}")).tool)
    }

    @Test
    fun `el catálogo de herramientas se puede describir para el prompt`() {
        val catalog = ToolSpec.promptCatalog()
        assertTrue(catalog.contains("create_primitive"))
        assertTrue(catalog.contains("export_model"))
        assertEquals(18, ToolSpec.tools.size)
    }
}
