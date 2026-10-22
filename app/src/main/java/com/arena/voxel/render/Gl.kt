package com.arena.voxel.render

import android.opengl.GLES20
import android.opengl.GLES30
import com.arena.voxel.core.Log

object Gl {

    fun compile(name: String, type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Error compilando $name: $log")
        }
        return id
    }

    fun link(name: String, vertSrc: String, fragSrc: String): Int {
        val vs = compile("$name.vert", GLES20.GL_VERTEX_SHADER, vertSrc)
        val fs = compile("$name.frag", GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Error enlazando $name: $log")
        }
        return program
    }

    fun checkError(tag: String) {
        var err = GLES30.glGetError()
        while (err != GLES20.GL_NO_ERROR) {
            Log.w("GL error $tag: 0x${Integer.toHexString(err)}")
            err = GLES30.glGetError()
        }
    }
}
