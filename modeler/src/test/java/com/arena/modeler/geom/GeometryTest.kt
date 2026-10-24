package com.arena.modeler.geom

import com.arena.modeler.scene.Primitives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del motor de geometría. Se ejecutan en la JVM (`:modeler:testDebugUnitTest`) porque
 * estas clases no dependen de Android: así un cambio que rompa la malla falla en CI y no en el
 * teléfono del usuario.
 */
class GeometryTest {

    private fun box() = EditableMesh.box(1f, 1f, 1f)

    @Test
    fun `todas las primitivas son mallas válidas`() {
        for (kind in Primitives.Kind.values()) {
            val mesh = EditableMesh.fromPrimitive(kind)
            assertTrue("$kind sin caras", mesh.faces.isNotEmpty())
            assertTrue("$kind sin vértices", mesh.vertexCount >= 3)
            val check = MeshOps.validate(mesh)
            assertTrue("$kind inválida: ${check.problems}", check.ok)
            assertEquals("$kind tiene caras degeneradas", 0, check.degenerateFaces)
            assertTrue("$kind sin triángulos", mesh.triangleCount() > 0)
        }
    }

    @Test
    fun `el cubo tiene seis caras y doce triángulos`() {
        val cube = box()
        assertEquals(8, cube.vertexCount)
        assertEquals(6, cube.faces.size)
        assertEquals(12, cube.triangleCount())
    }

    @Test
    fun `extruir cada cara de un cubo añade caras y volumen`() {
        val out = MeshOps.extrude(box(), 0.4f)
        assertTrue(out.faces.size > 6)
        assertTrue(MeshOps.validate(out).ok)
        assertTrue("el volumen debe crecer", out.volume() > box().volume())
    }

    @Test
    fun `inset añade un anillo interior`() {
        val out = MeshOps.inset(box(), 0.1f)
        assertTrue(out.faces.size > 6)
        assertTrue(MeshOps.validate(out).ok)
    }

    @Test
    fun `el bisel redondea sin caras degeneradas`() {
        val out = MeshOps.bevel(box(), 0.08f, 1)
        assertTrue("el bisel debe añadir caras", out.faces.size > 6)
        val check = MeshOps.validate(out)
        assertTrue(check.ok)
        assertEquals(0, check.degenerateFaces)
    }

    @Test
    fun `subdividir multiplica las caras por cuatro`() {
        val out = MeshOps.subdivide(box(), 1, smooth = false)
        assertEquals(6 * 4, out.faces.size)
        assertTrue(MeshOps.validate(out).ok)
    }

    @Test
    fun `catmull clark mantiene la forma cerrada`() {
        val out = MeshOps.catmullClark(box(), smooth = true)
        assertTrue(out.faces.size >= 6)
        val check = MeshOps.validate(out)
        assertTrue(check.ok)
    }

    @Test
    fun `espejar reconstruye una malla simétrica`() {
        val out = MeshOps.mirror(box(), MeshOps.Axis.X, keepBoth = false)
        assertTrue(out.faces.isNotEmpty())
        assertTrue("debe añadir la mitad reflejada", out.vertexCount > box().vertexCount)
        assertTrue(MeshOps.validate(out).ok)
    }

    @Test
    fun `reducir agrupa triángulos coplanares en polígonos`() {
        // El cubo del visor llega triangulado (12 triángulos): reducir debe dejarlo en 6 caras.
        val triangles = EditableMesh.fromMeshData(box().toMeshData(), mergeCoplanar = false)
        assertEquals(12, triangles.faces.size)
        val out = MeshOps.reduce(triangles)
        assertTrue("reduce debería quitar caras", out.faces.size < triangles.faces.size)
        assertEquals(6, out.faces.size)
        assertTrue(MeshOps.validate(out).ok)
    }

    @Test
    fun `suavizar conserva la topología`() {
        val original = MeshOps.subdivide(EditableMesh.sphere(0.5f, 12, 8), 1, smooth = false)
        val out = MeshOps.smoothMesh(original, 2, 0.5f)
        assertEquals(original.vertexCount, out.vertexCount)
        assertEquals(original.faces.size, out.faces.size)
    }

    @Test
    fun `el pincel de esculpido mueve sólo la zona tocada`() {
        val sphere = EditableMesh.sphere(0.5f, 24, 16)
        val out = MeshOps.sculpt(sphere, floatArrayOf(0f, 0.5f, 0f), 0.2f, 0.15f, MeshOps.SculptMode.INFLATE)
        assertEquals("el número de vértices no cambia", sphere.vertexCount, out.vertexCount)
        var maxDelta = 0f
        var farDelta = 0f
        for (v in 0 until out.vertexCount) {
            val dx = out.x(v) - sphere.x(v)
            val dy = out.y(v) - sphere.y(v)
            val dz = out.z(v) - sphere.z(v)
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (sphere.y(v) > 0.35f && kotlin.math.abs(sphere.x(v)) < 0.15f) maxDelta = maxOf(maxDelta, d)
            if (sphere.y(v) < -0.35f) farDelta = maxOf(farDelta, d)
        }
        assertTrue("el pincel debe deformar la superficie", maxDelta > 0.001f)
        assertTrue("el pincel no debe tocar el otro lado", farDelta < 0.0005f)
    }

    @Test
    fun `el barrido de un perfil genera un tubo cerrado`() {
        val path = listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(0f, 0.5f, 0f),
            floatArrayOf(0f, 1f, 0f)
        )
        val profile = Curves.circle(8, 0.1f)
        val tube = MeshOps.sweep(profile, path, caps = true)
        assertTrue(tube.faces.size >= 16)
        assertTrue(MeshOps.validate(tube).ok)
    }

    @Test
    fun `las curvas empiezan y terminan en los extremos`() {
        val points = listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 1f, 0f),
            floatArrayOf(2f, 0f, 0f)
        )
        val samples = Curves.evaluate(points, Curves.Kind.CATMULL_ROM, 16)
        assertEquals(16, samples.size)
        assertEquals(0f, samples.first()[0], 1e-3f)
        assertEquals(2f, samples.last()[0], 1e-3f)
    }

    @Test
    fun `la diferencia booleana quita material`() {
        val a = EditableMesh.box(1f, 1f, 1f)
        val b = EditableMesh.box(0.5f, 0.5f, 0.5f)
        val result = Csg.subtract(a, b)
        assertTrue(result.faces.isNotEmpty())
        assertTrue(MeshOps.validate(result).ok)
        assertTrue("la resta debe tener menos volumen", result.volume() < a.volume())
    }

    @Test
    fun `la unión booleana suma los dos cuerpos`() {
        val a = EditableMesh.box(1f, 1f, 1f)
        val b = EditableMesh.box(1f, 1f, 1f).translate(0.5f, 0f, 0f)
        val result = Csg.union(a, b)
        assertTrue(MeshOps.validate(result).ok)
        assertTrue(result.volume() > a.volume())
    }

    @Test
    fun `la intersección de dos cajas solapadas es menor que ambas`() {
        val a = EditableMesh.box(1f, 1f, 1f)
        val b = EditableMesh.box(1f, 1f, 1f).translate(0.5f, 0f, 0f)
        val result = Csg.intersect(a, b)
        assertTrue(result.faces.isNotEmpty())
        assertTrue(MeshOps.validate(result).ok)
        assertTrue("la intersección debe ser menor que A", result.volume() < a.volume())
        assertTrue("la intersección debe ser menor que B", result.volume() < b.volume())
    }

    @Test
    fun `la validación detecta caras degeneradas`() {
        val mesh = EditableMesh()
        val a = mesh.addVertex(0f, 0f, 0f)
        val b = mesh.addVertex(1f, 0f, 0f)
        val c = mesh.addVertex(0f, 1f, 0f)
        mesh.addFace(a, b, c)
        mesh.addFace(a, a, b)
        val check = MeshOps.validate(mesh)
        assertEquals(1, check.degenerateFaces)
        assertTrue(check.ok)
        val repaired = MeshOps.dropDegenerate(mesh)
        assertEquals(1, repaired.faces.size)
    }

    @Test
    fun `la validación detecta índices fuera de rango`() {
        val mesh = EditableMesh()
        mesh.addVertex(0f, 0f, 0f)
        mesh.addVertex(1f, 0f, 0f)
        mesh.addVertex(0f, 1f, 0f)
        mesh.addFace(0, 1, 9)
        val check = MeshOps.validate(mesh)
        assertFalse(check.ok)
        assertTrue(check.problems.isNotEmpty())
    }

    @Test
    fun `el intercambio con MeshData conserva la geometría`() {
        val mesh = EditableMesh.sphere(0.5f, 16, 12)
        val data = mesh.toMeshData(smooth = true)
        val back = EditableMesh.fromMeshData(data)
        assertEquals(mesh.triangleCount(), back.triangleCount())
        assertTrue(MeshOps.validate(back).ok)
    }

    @Test
    fun `el soldado une vértices duplicados`() {
        val a = EditableMesh.box(1f, 1f, 1f)
        val data = a.toMeshData()
        val duplicated = EditableMesh()
        for (v in data.positions) duplicated.positions.add(v)
        for (v in data.positions) duplicated.positions.add(v)
        for (f in a.faces) duplicated.addFace(f.copyOf())
        for (f in a.faces) duplicated.addFace(f.map { it + a.vertexCount }.toIntArray())
        assertTrue(duplicated.vertexCount > a.vertexCount)
        val welded = duplicated.weld(1e-4f)
        assertEquals(a.vertexCount, welded.vertexCount)
        assertEquals("las caras duplicadas no se fusionan solas", a.faces.size * 2, welded.faces.size)
    }
}
