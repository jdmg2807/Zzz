package com.arena.modeler.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Math3DTest {

    @Test
    fun `la inversa deshace la matriz de un nodo`() {
        val model = M4.compose(
            FloatArray(16),
            V3(1.5f, -2f, 0.25f),
            Quat.euler(20f, 35f, -10f),
            V3(2f, 0.5f, 1.25f)
        )
        val inverse = FloatArray(16)
        assertTrue(M4.invert(inverse, model))
        // Un punto local transformado al mundo y de vuelta debe quedar igual.
        val world = M4.transformPoint(model, 0.3f, -0.7f, 1.1f)
        val back = M4.transformPoint(inverse, world[0], world[1], world[2])
        assertEquals(0.3f, back[0], 1e-3f)
        assertEquals(-0.7f, back[1], 1e-3f)
        assertEquals(1.1f, back[2], 1e-3f)
    }

    @Test
    fun `una matriz singular no se puede invertir`() {
        val m = FloatArray(16)
        assertFalse(M4.invert(FloatArray(16), m))
    }

    @Test
    fun `la composicion aplica traslacion rotacion y escala`() {
        val m = M4.compose(FloatArray(16), V3(2f, 0f, 0f), Quat(), V3(3f, 3f, 3f))
        val p = M4.transformPoint(m, 1f, 0f, 0f)
        assertEquals(5f, p[0], 1e-4f)
        assertEquals(0f, p[1], 1e-4f)
    }

    @Test
    fun `euler cero es la rotacion identidad`() {
        val q = Quat.euler(0f, 0f, 0f)
        assertEquals(1f, q.w, 1e-5f)
        assertEquals(0f, q.x, 1e-5f)
        assertEquals(0f, q.y, 1e-5f)
        assertEquals(0f, q.z, 1e-5f)
    }

    @Test
    fun `euler 90 grados en Y gira el eje X hacia -Z`() {
        val q = Quat.euler(0f, 90f, 0f)
        val m = FloatArray(16)
        q.toMatrix(m)
        val v = M4.transformPoint(m, 1f, 0f, 0f)
        assertEquals(0f, v[0], 1e-4f)
        assertEquals(0f, v[1], 1e-4f)
        assertEquals(-1f, v[2], 1e-4f)
    }

    @Test
    fun `Base64 ida y vuelta con bytes arbitrarios`() {
        val data = ByteArray(257) { (it * 37 + 11).toByte() }
        val text = Text64.encode(data)
        assertTrue(text.isNotEmpty())
        val back = Text64.decode(text)
        assertTrue(data.contentEquals(back))
    }

    @Test
    fun `Base64 tolera saltos de linea y relleno ausente`() {
        val data = "VoxelForge 3D".toByteArray()
        val text = Text64.encode(data)
        val wrapped = text.chunked(4).joinToString("\n").trimEnd('=')
        val back = Text64.decodeOrNull(wrapped)
        assertTrue(back != null)
        assertEquals("VoxelForge 3D", String(back!!))
    }

    @Test
    fun `Base64 rechaza caracteres inválidos`() {
        assertNull(Text64.decodeOrNull("esto no es base64!"))
    }

    @Test
    fun `el rayo de la cámara apunta al centro de la pantalla`() {
        val camera = com.arena.modeler.render.OrbitCamera()
        camera.distance = 5f
        camera.target.set(0f, 0f, 0f)
        val origin = V3()
        val dir = V3()
        camera.ray(0f, 0f, 1f, origin, dir)
        // Hacia el objetivo: la dirección debe tener componente negativa según la posición.
        assertTrue(dir.length() > 0.9f)
        val toTarget = (camera.target - origin).normalized()
        assertEquals(toTarget.x, dir.x, 1e-2f)
        assertEquals(toTarget.y, dir.y, 1e-2f)
        assertEquals(toTarget.z, dir.z, 1e-2f)
    }
}
