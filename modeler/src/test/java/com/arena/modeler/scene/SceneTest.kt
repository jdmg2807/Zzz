package com.arena.modeler.scene

import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3
import com.arena.modeler.geom.EditableMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {

    private fun docWithCube(): Pair<SceneDocument, SceneNode> {
        val doc = SceneDocument("Prueba")
        val node = doc.newNode("Cubo.1")
        node.setEditableMesh(EditableMesh.box())
        node.material = doc.materialFor()
        return doc to node
    }

    @Test
    fun `añadir un nodo actualiza los totales`() {
        val (doc, node) = docWithCube()
        assertEquals(1, doc.allNodes().size)
        assertEquals(8, doc.totalVertices())
        assertEquals(12, doc.totalTriangles())
        assertEquals(node.id, doc.nodeById(node.id)?.id)
    }

    @Test
    fun `los nombres nuevos no se repiten`() {
        val (doc, _) = docWithCube()
        assertEquals("Cubo.2", doc.nextNodeName("Cubo"))
        doc.newNode(doc.nextNodeName("Cubo"))
        assertEquals("Cubo.3", doc.nextNodeName("Cubo"))
    }

    @Test
    fun `la matriz del mundo aplica la transformación del padre`() {
        val doc = SceneDocument()
        val parent = doc.newNode("Padre")
        parent.position = V3(1f, 0f, 0f)
        val child = doc.newNode("Hijo")
        child.parent = parent
        parent.children.add(child)
        child.position = V3(0f, 2f, 0f)
        val world = child.worldMatrix()
        assertEquals(1f, world[12], 1e-4f)
        assertEquals(2f, world[13], 1e-4f)
    }

    @Test
    fun `las bounds del mundo siguen a la posición`() {
        val (_, node) = docWithCube()
        val before = node.worldBounds()
        assertNotNull(before)
        node.position = V3(3f, 0f, 0f)
        node.markDirty()
        val after = node.worldBounds()!!
        assertEquals(before!![0] + 3f, after[0], 1e-3f)
        assertEquals(before[3] + 3f, after[3], 1e-3f)
        assertTrue(after[3] > after[0])
    }

    @Test
    fun `quitar del padre deja el nodo fuera de la escena`() {
        val (doc, node) = docWithCube()
        node.removeFromParent()
        assertNull(doc.nodeById(node.id))
        assertTrue(doc.allNodes().isEmpty())
    }

    @Test
    fun `un nodo sin malla no aporta triángulos`() {
        val doc = SceneDocument()
        doc.newNode("Vacío")
        assertEquals(0, doc.totalTriangles())
        assertEquals(0, doc.totalVertices())
    }

    @Test
    fun `el giro de demostración dura lo pedido y anima el nodo`() {
        val (doc, node) = docWithCube()
        val clip = AnimationClip.demo(doc, node, 4f)
        assertEquals(4f, clip.duration(), 1e-4f)
        assertTrue(clip.channels.isNotEmpty())

        clip.apply(doc, 0f)
        assertEquals(1f, node.rotation.w, 1e-3f)
        assertEquals(0f, node.position.y, 1e-3f)

        clip.apply(doc, 1f)   // cuarto de vuelta + rebote de 0.2 m
        assertEquals(0.2f, node.position.y, 1e-2f)
        assertTrue("debe haber rotado", kotlin.math.abs(node.rotation.y) > 0.1f)
    }

    @Test
    fun `añadir claves crea un canal por propiedad`() {
        val (doc, node) = docWithCube()
        val clip = AnimationClip("Manual")
        doc.animations.add(clip)
        node.position = V3(0f, 1f, 0f)
        clip.keyNode(node, 0f, AnimPath.TRANSLATION)
        node.position = V3(0f, 2f, 0f)
        clip.keyNode(node, 1f, AnimPath.TRANSLATION)
        assertEquals(1, clip.channels.size)
        assertEquals(1f, clip.duration(), 1e-4f)

        node.position = V3(0f, 3f, 0f)
        clip.keyNode(node, 0f, AnimPath.TRANSLATION)   // reemplaza la clave en el mismo instante
        assertEquals(1, clip.channels.size)
        clip.apply(doc, 0f)
        assertEquals(3f, node.position.y, 1e-3f)
    }

    @Test
    fun `el canal de rotación normaliza el cuaternión al aplicarlo`() {
        val (doc, node) = docWithCube()
        val clip = AnimationClip("Giro")
        doc.animations.add(clip)
        node.rotation = Quat(0f, 0f, 0f, 2f)   // sin normalizar
        clip.keyNode(node, 0f, AnimPath.ROTATION)
        node.rotation = Quat()
        clip.apply(doc, 0f)
        assertEquals(1f, node.rotation.w, 1e-4f)
    }

    @Test
    fun `parsePath entiende los nombres de glTF`() {
        assertEquals(AnimPath.TRANSLATION, AnimationClip.parsePath("translation"))
        assertEquals(AnimPath.ROTATION, AnimationClip.parsePath("rotation"))
        assertEquals(AnimPath.SCALE, AnimationClip.parsePath("scale"))
        assertNull(AnimationClip.parsePath("peso"))
    }

    @Test
    fun `la malla editable se cachea y se invalida`() {
        val (_, node) = docWithCube()
        val first = node.editableMesh()
        val second = node.editableMesh()
        assertTrue("debe devolver la misma instancia cacheada", first === second)
        node.setEditableMesh(EditableMesh.sphere(0.5f, 12, 8))
        assertTrue("la caché debe invalidarse", node.editableMesh() !== first)
    }
}
