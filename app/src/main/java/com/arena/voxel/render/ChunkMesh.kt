package com.arena.voxel.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Growable float buffer used while meshing chunks. */
class FloatList(initial: Int = 4096) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        if (size + 6 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = a
        data[size++] = b
        data[size++] = c
        data[size++] = d
        data[size++] = e
        data[size++] = f
    }

    fun isEmpty() = size == 0

    fun toArray(): FloatArray = data.copyOf(size)
}

/** One VBO holding all quads of a chunk (6 floats per vertex: x, y, z, u, v, shade). */
class ChunkMesh {

    companion object {
        const val STRIDE = 6 * 4
    }

    private var vbo = 0
    var count = 0
        private set
    var empty = true
        private set

    fun upload(vertices: FloatArray) {
        if (vertices.isEmpty()) {
            empty = true
            count = 0
            return
        }
        if (vbo == 0) {
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            vbo = ids[0]
        }
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(vertices)
        buffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, buffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        count = vertices.size / 6
        empty = false
    }

    fun draw() {
        if (empty || count == 0 || vbo == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, STRIDE, 12)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(2, 1, GLES20.GL_FLOAT, false, STRIDE, 20)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (vbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
            vbo = 0
        }
        count = 0
        empty = true
    }
}
