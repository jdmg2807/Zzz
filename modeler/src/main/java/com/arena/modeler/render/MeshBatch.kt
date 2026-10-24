package com.arena.modeler.render

import android.opengl.GLES30
import com.arena.modeler.core.M4
import com.arena.modeler.core.V3
import com.arena.modeler.scene.MeshData
import com.arena.modeler.scene.SceneNode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws every mesh of the document with a small PBR-ish shader (two directional lights, Blinn
 * highlight, fresnel rim and emission). Geometry is uploaded once per mesh and reused; a mesh is
 * only re-uploaded when the node points at a different [MeshData] or its vertex count changed.
 */
class MeshBatch {

    private var program = -1
    private var uMVP = -1
    private var uModel = -1
    private var uNormalMat = -1
    private var uColor = -1
    private var uMetallic = -1
    private var uRoughness = -1
    private var uEmission = -1
    private var uCam = -1
    private var uSelected = -1

    private class Entry(var mesh: MeshData?, var vbo: Int, var ebo: Int, var indexCount: Int, var vertexCount: Int)

    private val cache = HashMap<Int, Entry>()
    private val mvp = FloatArray(16)
    private val normal = FloatArray(9)

    private fun ensureProgram() {
        if (program >= 0) return
        program = GlUtil.program(VERTEX, FRAGMENT)
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uModel = GLES30.glGetUniformLocation(program, "uModel")
        uNormalMat = GLES30.glGetUniformLocation(program, "uNormalMat")
        uColor = GLES30.glGetUniformLocation(program, "uColor")
        uMetallic = GLES30.glGetUniformLocation(program, "uMetallic")
        uRoughness = GLES30.glGetUniformLocation(program, "uRoughness")
        uEmission = GLES30.glGetUniformLocation(program, "uEmission")
        uCam = GLES30.glGetUniformLocation(program, "uCam")
        uSelected = GLES30.glGetUniformLocation(program, "uSelected")
    }

    fun draw(node: SceneNode, viewProj: FloatArray, camera: V3, selected: Boolean, doubleSided: Boolean) {
        val mesh = node.mesh ?: return
        if (mesh.indices.isEmpty()) return
        ensureProgram()
        val entry = upload(node, mesh)
        val model = node.worldMatrix()
        M4.multiply(mvp, viewProj, model)
        M4.normalMatrix(normal, model)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniformMatrix3fv(uNormalMat, 1, false, normal, 0)
        GLES30.glUniform3f(uCam, camera.x, camera.y, camera.z)
        GLES30.glUniform1f(uSelected, if (selected) 1f else 0f)
        val mat = node.material
        if (mat != null) {
            GLES30.glUniform3f(uColor, mat.baseColor[0], mat.baseColor[1], mat.baseColor[2])
            GLES30.glUniform1f(uMetallic, mat.metallic)
            GLES30.glUniform1f(uRoughness, mat.roughness)
            GLES30.glUniform3f(uEmission, mat.emission[0], mat.emission[1], mat.emission[2])
        } else {
            GLES30.glUniform3f(uColor, 0.78f, 0.8f, 0.86f)
            GLES30.glUniform1f(uMetallic, 0f)
            GLES30.glUniform1f(uRoughness, 0.6f)
            GLES30.glUniform3f(uEmission, 0f, 0f, 0f)
        }
        if (doubleSided || (mat?.doubleSided == true)) GLES30.glDisable(GLES30.GL_CULL_FACE)
        else GLES30.glEnable(GLES30.GL_CULL_FACE)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, entry.vbo)
        val stride = 8 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 6 * 4)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, entry.ebo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, entry.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Uploads (or re-uploads) the interleaved vertex buffer of [mesh] for [node]. */
    private fun upload(node: SceneNode, mesh: MeshData): Entry {
        val existing = cache[node.id]
        if (existing != null && existing.mesh === mesh &&
            existing.indexCount == mesh.indices.size && existing.vertexCount == mesh.vertexCount
        ) {
            return existing
        }
        val entry = existing ?: Entry(null, 0, 0, 0, 0)
        if (entry.vbo == 0) {
            val ids = IntArray(2)
            GLES30.glGenBuffers(2, ids, 0)
            entry.vbo = ids[0]
            entry.ebo = ids[1]
            cache[node.id] = entry
        }
        val count = mesh.vertexCount
        val interleaved = FloatArray(count * 8)
        val normals = mesh.normals
        val uvs = mesh.uvs
        for (i in 0 until count) {
            val o = i * 8
            interleaved[o] = mesh.positions[i * 3]
            interleaved[o + 1] = mesh.positions[i * 3 + 1]
            interleaved[o + 2] = mesh.positions[i * 3 + 2]
            if (normals != null && normals.size >= (i + 1) * 3) {
                interleaved[o + 3] = normals[i * 3]
                interleaved[o + 4] = normals[i * 3 + 1]
                interleaved[o + 5] = normals[i * 3 + 2]
            } else {
                interleaved[o + 4] = 1f
            }
            if (uvs != null && uvs.size >= (i + 1) * 2) {
                interleaved[o + 6] = uvs[i * 2]
                interleaved[o + 7] = uvs[i * 2 + 1]
            }
        }
        val vb = ByteBuffer.allocateDirect(interleaved.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(interleaved)
        vb.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, entry.vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, interleaved.size * 4, vb, GLES30.GL_STATIC_DRAW)

        val ib = ByteBuffer.allocateDirect(mesh.indices.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(mesh.indices)
        ib.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, entry.ebo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        entry.mesh = mesh
        entry.indexCount = mesh.indices.size
        entry.vertexCount = count
        return entry
    }

    /** Called after a context loss: the old names are meaningless in the new context. */
    fun reset() {
        cache.clear()
        program = -1
    }

    fun invalidate() {
        for (e in cache.values) {
            if (e.vbo != 0) GLES30.glDeleteBuffers(2, intArrayOf(e.vbo, e.ebo), 0)
        }
        cache.clear()
    }

    private companion object {
        val VERTEX = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUV;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform mat3 uNormalMat;
            out vec3 vNormal;
            out vec3 vWorld;
            out vec2 vUV;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vWorld = world.xyz;
                vNormal = normalize(uNormalMat * aNormal);
                vUV = aUV;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 300 es
            precision mediump float;
            in vec3 vNormal;
            in vec3 vWorld;
            in vec2 vUV;
            uniform vec3 uColor;
            uniform float uMetallic;
            uniform float uRoughness;
            uniform vec3 uEmission;
            uniform vec3 uCam;
            uniform float uSelected;
            out vec4 fragColor;
            void main() {
                vec3 N = normalize(vNormal);
                if (!gl_FrontFacing) N = -N;
                vec3 V = normalize(uCam - vWorld);
                vec3 L1 = normalize(vec3(0.45, 0.85, 0.35));
                vec3 L2 = normalize(vec3(-0.55, 0.30, -0.70));
                float d1 = max(dot(N, L1), 0.0);
                float d2 = max(dot(N, L2), 0.0);
                vec3 base = uColor;
                vec3 ambient = base * 0.28;
                vec3 diffuse = base * (d1 * 0.80 + d2 * 0.30);
                vec3 H = normalize(L1 + V);
                float shininess = mix(10.0, 110.0, 1.0 - uRoughness);
                float spec = pow(max(dot(N, H), 0.0), shininess) * mix(0.05, 0.85, uMetallic);
                float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.16;
                vec3 color = ambient + diffuse + vec3(spec) + vec3(rim) + uEmission;
                color = mix(color, vec3(0.30, 0.62, 1.0), uSelected * 0.18);
                color = pow(color, vec3(1.0 / 2.2));
                fragColor = vec4(color, 1.0);
            }
        """.trimIndent()
    }
}
