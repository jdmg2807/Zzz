package com.arena.voxel.render

import android.opengl.GLES20
import java.util.HashMap

class ShaderProgram(val name: String, val id: Int) {

    private val locations = HashMap<String, Int>()

    fun use() = GLES20.glUseProgram(id)

    private fun loc(uniform: String): Int {
        locations[uniform]?.let { return it }
        val l = GLES20.glGetUniformLocation(id, uniform)
        locations[uniform] = l
        return l
    }

    fun setMat4(uniform: String, m: FloatArray) = GLES20.glUniformMatrix4fv(loc(uniform), 1, false, m, 0)

    fun set1f(uniform: String, v: Float) = GLES20.glUniform1f(loc(uniform), v)

    fun set2f(uniform: String, a: Float, b: Float) = GLES20.glUniform2f(loc(uniform), a, b)

    fun set3f(uniform: String, a: Float, b: Float, c: Float) = GLES20.glUniform3f(loc(uniform), a, b, c)

    fun set4f(uniform: String, a: Float, b: Float, c: Float, d: Float) = GLES20.glUniform4f(loc(uniform), a, b, c, d)

    fun set1i(uniform: String, v: Int) = GLES20.glUniform1i(loc(uniform), v)

    companion object {
        fun create(name: String, vertSrc: String, fragSrc: String): ShaderProgram =
            ShaderProgram(name, Gl.link(name, vertSrc, fragSrc))
    }
}
