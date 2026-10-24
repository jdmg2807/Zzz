package com.arena.modeler.io

import com.arena.modeler.geom.EditableMesh
import com.arena.modeler.scene.AnimationClip
import com.arena.modeler.scene.SceneDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ida y vuelta por todos los formatos que la app promete: lo que se exporta tiene que poder
 * volver a abrirse con la misma geometría. Es la única prueba que respalda el mensaje «exportado
 * y verificado» de la interfaz.
 */
class FormatRoundTripTest {

    private fun sample(withAnimation: Boolean = true): SceneDocument {
        val doc = SceneDocument("Prueba")
        val cube = doc.newNode("Cubo.1")
        cube.setEditableMesh(EditableMesh.box(1f, 1f, 1f))
        cube.material = doc.materialFor(floatArrayOf(0.2f, 0.6f, 0.9f, 1f))
        val sphere = doc.newNode("Esfera.1")
        sphere.setEditableMesh(EditableMesh.sphere(0.4f, 12, 8))
        sphere.material = doc.materialFor()
        sphere.position = com.arena.modeler.core.V3(1.5f, 0.5f, 0f)
        if (withAnimation) {
            AnimationClip.demo(doc, cube, 2f)
        }
        return doc
    }

    @Test
    fun `GLB conserva geometría materiales y animación`() {
        val doc = sample()
        val bytes = Gltf.writeGlb(doc)
        assertTrue("el GLB debe tener cabecera glTF", String(bytes, 0, 4, Charsets.ISO_8859_1) == "glTF")

        val back = Gltf.read(bytes, "prueba.glb")
        assertEquals(doc.allNodes().count { it.mesh != null }, back.allNodes().count { it.mesh != null })
        assertEquals(doc.totalTriangles(), back.totalTriangles())
        assertTrue("los materiales deben sobrevivir", back.materials.isNotEmpty())
        assertTrue("la animación debe sobrevivir", back.animations.isNotEmpty())
        assertTrue(back.animations.first().channels.isNotEmpty())
    }

    @Test
    fun `glTF de texto se relee igual`() {
        val doc = sample(withAnimation = false)
        val bytes = Gltf.writeGltf(doc).toString(Charsets.UTF_8)
        assertTrue(bytes.contains("\"asset\""))
        val back = ModelFormats.read(bytes.toByteArray(Charsets.UTF_8), "prueba.gltf")
        assertEquals(doc.totalTriangles(), back.totalTriangles())
    }

    @Test
    fun `FBX ASCII conserva la jerarquía y la animación`() {
        val doc = sample()
        val fbx = Fbx.write(doc)
        assertTrue(String(fbx, 0, 20, Charsets.ISO_8859_1).contains("FBX"))
        val back = ModelFormats.read(fbx, "prueba.fbx")
        assertEquals(doc.allNodes().size, back.allNodes().size)
        assertEquals(doc.totalTriangles(), back.totalTriangles())
        assertTrue("la animación debe sobrevivir", back.animations.isNotEmpty())
    }

    @Test
    fun `OBJ conserva los triángulos`() {
        val doc = sample(withAnimation = false)
        val result = ModelFormats.build(doc, ExportFormat.OBJ)
        val text = result.bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("v "))
        assertTrue(text.contains("f "))
        assertTrue("debe generar el .mtl", result.companions.isNotEmpty())

        val back = ModelFormats.read(result.bytes, result.fileName)
        assertEquals(doc.totalTriangles(), back.totalTriangles())
    }

    @Test
    fun `STL binario y ASCII conservan los triángulos`() {
        val doc = sample(withAnimation = false)
        for (format in listOf(ExportFormat.STL_BINARY, ExportFormat.STL_ASCII)) {
            val result = ModelFormats.build(doc, format)
            val back = ModelFormats.read(result.bytes, result.fileName)
            assertEquals("$format", doc.totalTriangles(), back.totalTriangles())
        }
    }

    @Test
    fun `PLY ASCII y binario conservan los triángulos`() {
        val doc = sample(withAnimation = false)
        for (format in listOf(ExportFormat.PLY_ASCII, ExportFormat.PLY_BINARY)) {
            val result = ModelFormats.build(doc, format)
            val back = ModelFormats.read(result.bytes, result.fileName)
            assertEquals("$format", doc.totalTriangles(), back.totalTriangles())
        }
    }

    @Test
    fun `exportar verifica antes de confirmar`() {
        val doc = sample()
        val result = ModelFormats.export(doc, ExportFormat.GLB)
        assertTrue(result.bytes.size > 64)
        val check = ModelFormats.verify(result.bytes, result.fileName, doc)
        assertTrue(check.detail, check.supported)
        assertEquals(doc.totalTriangles(), ModelFormats.read(result.bytes, result.fileName).totalTriangles())
    }

    @Test
    fun `verificar un archivo ilegible no miente`() {
        val garbage = "no soy un modelo 3D".toByteArray()
        val check = ModelFormats.verify(garbage, "roto.glb", null)
        assertFalse(check.supported)
    }

    @Test
    fun `exportar una escena vacía falla en lugar de decir que salió bien`() {
        val empty = SceneDocument("Vacío")
        var failed = false
        try {
            ModelFormats.export(empty, ExportFormat.GLB)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("una escena vacía no puede verificar geometría", failed)
    }

    @Test
    fun `los nombres peligrosos se sanean`() {
        assertEquals("mi_modelo", ModelFormats.safeName("mi modelo"))
        assertEquals("modelo", ModelFormats.safeName(""))
        assertFalse(ModelFormats.safeName("../../etc/passwd").contains("/"))
    }
}
