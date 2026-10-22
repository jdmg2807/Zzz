package com.arena.voxel.render

import android.opengl.GLES20
import android.opengl.GLES30
import com.arena.voxel.core.Log

/** Offscreen framebuffer (colour + depth texture) used for the post-processing shader pass. */
class RenderTarget {

    private var fbo = 0
    var colorTexture = 0
        private set
    private var depthTexture = 0
    private var width = 0
    private var height = 0
    var supported = true
        private set

    fun ensure(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (w == width && h == height && fbo != 0) return supported
        release()
        width = w
        height = h
        if (atlasFrameBuffer(w, h)) {
            supported = true
        } else {
            Log.w("Framebuffer no soportado, se desactiva el post-proceso")
            supported = false
            release()
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        return supported
    }

    private fun atlasFrameBuffer(w: Int, h: Int): Boolean {
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        fbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        colorTexture = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTexture, 0
        )

        GLES20.glGenTextures(1, tex, 0)
        depthTexture = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, w, h, 0,
            GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_INT, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_TEXTURE_2D, depthTexture, 0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        return status == GLES20.GL_FRAMEBUFFER_COMPLETE
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (colorTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(colorTexture), 0)
            colorTexture = 0
        }
        if (depthTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(depthTexture), 0)
            depthTexture = 0
        }
        width = 0
        height = 0
    }
}
