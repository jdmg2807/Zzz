package com.arena.modeler.render

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Shader compilation and the tiny amount of GL bookkeeping the renderer needs. */
object GlUtil {

    private const val TAG = "VoxelForge/GL"

    fun program(vertexSource: String, fragmentSource: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "No se pudo enlazar el programa: " + GLES30.glGetProgramInfoLog(program))
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Error de shader: " + GLES30.glGetShaderInfoLog(shader) + "\n" + source)
        }
        return shader
    }

    fun buffer(data: FloatArray, count: Int): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(count * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        fb.put(data, 0, count)
        fb.position(0)
        return fb
    }

    fun buffer(data: ByteArray): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        bb.put(data)
        bb.position(0)
        return bb
    }
}

/**
 * Immediate-mode line renderer (batched into one VBO): grid, axes, bounding boxes, bones and the
 * transform gizmo all go through here. Lines keep their own shader so they can ignore lighting.
 */
class GLLine {

    private var program = -1
    private var aPos = -1
    private var aColor = -1
    private var uMVP = -1
    private var vbo = 0
    private var data = FloatArray(7 * 2 * 4096)
    private var used = 0
    private var floatBuffer: FloatBuffer? = null

    fun begin() {
        if (program < 0) init()
        used = 0
    }

    /** Drops every GL name; call it when the EGL context was recreated. */
    fun reset() {
        program = -1
        vbo = 0
        floatBuffer = null
        used = 0
    }

    private fun init() {
        program = GlUtil.program(VERTEX, FRAGMENT)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
    }

    private fun ensure(extra: Int) {
        if (used + extra <= data.size) return
        var size = data.size
        while (size < used + extra) size *= 2
        data = data.copyOf(size)
    }

    fun line(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float, a: Float = 1f
    ) {
        ensure(14)
        data[used++] = x0; data[used++] = y0; data[used++] = z0
        data[used++] = r; data[used++] = g; data[used++] = b; data[used++] = a
        data[used++] = x1; data[used++] = y1; data[used++] = z1
        data[used++] = r; data[used++] = g; data[used++] = b; data[used++] = a
    }

    /** 12 edges of an axis-aligned box. */
    fun box(
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        r: Float, g: Float, b: Float, a: Float = 1f
    ) {
        // Bottom
        line(minX, minY, minZ, maxX, minY, minZ, r, g, b, a)
        line(maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a)
        line(maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a)
        line(minX, minY, maxZ, minX, minY, minZ, r, g, b, a)
        // Top
        line(minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a)
        line(maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a)
        line(maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a)
        line(minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a)
        // Verticals
        line(minX, minY, minZ, minX, maxY, minZ, r, g, b, a)
        line(maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a)
        line(maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a)
        line(minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a)
    }

    /** Three axis-aligned crosses, used to show bones and origins. */
    fun cross(x: Float, y: Float, z: Float, size: Float, r: Float, g: Float, b: Float) {
        line(x - size, y, z, x + size, y, z, r, g, b)
        line(x, y - size, z, x, y + size, z, r, g, b)
        line(x, y, z - size, x, y, z + size, r, g, b)
    }

    fun draw(mvp: FloatArray) {
        if (used == 0) return
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        if (floatBuffer == null || floatBuffer!!.capacity() < used) {
            floatBuffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        val fb = floatBuffer!!
        fb.clear()
        fb.put(data, 0, used)
        fb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, used * 4, fb, GLES30.GL_DYNAMIC_DRAW)
        val stride = 7 * 4
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(aColor)
        GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, used / 7)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glDisableVertexAttribArray(aColor)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        used = 0
    }

    private companion object {
        val VERTEX = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec4 aColor;
            uniform mat4 uMVP;
            out vec4 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 300 es
            precision mediump float;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vColor.rgb, vColor.a);
            }
        """.trimIndent()
    }
}
