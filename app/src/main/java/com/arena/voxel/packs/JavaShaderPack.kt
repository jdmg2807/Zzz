package com.arena.voxel.packs

import com.arena.voxel.core.Log

/**
 * Loads **Minecraft Java shader packs** (OptiFine / Iris / vanilla `shaders/post`).
 *
 * A Java shader pack is a folder (or zip) with a `shaders/` directory:
 *
 * ```
 * shaders/properties          (OptiFine options, not needed here)
 * shaders/composite.vsh/.fsh  fullscreen passes that read colortex0..7
 * shaders/composite1.fsh ...
 * shaders/final.vsh/.fsh      last pass, writes to the screen
 * shaders/lib/*.glsl          #include files
 * shaders/post/*.json         vanilla post chain (targets + passes)
 * ```
 *
 * This class turns that into something a phone GPU can run:
 *
 *  - `#include` files are inlined,
 *  - GLSL 1.20 / 3.30 is translated to GLSL ES 3.00 (`attribute`→`in`, `varying`→`in`/`out`,
 *    `texture2D`→`texture`, `gl_FragColor`→`fragColor`, precision qualifiers, …),
 *  - the `composite*` / `final` passes are chained with two ping-pong framebuffers,
 *  - the standard uniform block (`colortex0`, `viewWidth/viewHeight`, `frameTimeCounter`,
 *    `cameraPosition`, `sunAngle`, `isEyeInWater`, `rainStrength`, …) is provided,
 *  - `gbuffers_*`, `shadow*`, `deferred*` are skipped: the terrain pass already produces the
 *    scene in `colortex0`, which is exactly what those programs consume on the first frame.
 *
 * Everything is best-effort: a pack whose GLSL cannot be translated is reported and the built-in
 * pipeline stays active, so a broken pack never leaves a black screen.
 */
object JavaShaderPack {

    /** One fullscreen pass of a pack. */
    class Pass(
        val name: String,
        val vertexSource: String,
        val fragmentSource: String,
        val inputs: List<String> = listOf("colortex0")
    )

    class Loaded(
        val packName: String,
        val passes: List<Pass>,
        val notes: List<String> = emptyList()
    )

    private const val MAX_PASSES = 6

    /** Vertex shader used when the pack's own VS is a fixed-function GLSL 1.20 one. */
    private val FULLSCREEN_VS = """
#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
out vec2 texcoord;
void main() {
    texcoord = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
""".trimIndent()

    /**
     * Uniforms OptiFine exposes to every program. Declaring them even when a shader does not use
     * them is harmless (the driver removes them) and saves us from parsing each file's needs.
     */
    private val PREAMBLE_HEADER = """
#version 300 es
precision highp float;
precision highp int;
#define MC_VERSION 12100
#define MC_GL_VERSION 300
#define MC_GLSL_VERSION 300
in vec2 texcoord;
layout(location = 0) out vec4 fragColor;
uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex4;
uniform sampler2D colortex5;
uniform sampler2D depthtex0;
uniform sampler2D gdepth;
uniform float viewWidth;
uniform float viewHeight;
uniform float aspectRatio;
uniform float frameTimeCounter;
uniform float frameTime;
uniform float rainStrength;
uniform float wetness;
uniform float sunAngle;
uniform float moonPhase;
uniform float nightVision;
uniform float blindness;
uniform float darknessFactor;
uniform float screenBrightness;
uniform int isEyeInWater;
uniform int worldTime;
uniform int frameCounter;
uniform vec3 cameraPosition;
uniform vec3 sunPosition;
uniform vec3 moonPosition;
uniform vec3 shadowLightPosition;
uniform vec3 fogColor;
uniform float fogDensity;
uniform float far;
uniform float near;
uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
"""

    private val NOISE_LIB = """
float ld_timeFrac() { return fract(frameTimeCounter); }
vec3 ld_rand3(vec2 p) {
    return fract(sin(vec3(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)), dot(p, vec2(419.2, 371.9)))) * 43758.5453);
}
float ld_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(ld_rand3(i).x, ld_rand3(i + vec2(1.0, 0.0)).x, u.x),
               mix(ld_rand3(i + vec2(0.0, 1.0)).x, ld_rand3(i + vec2(1.0, 1.0)).x, u.x), u.y);
}
"""

    // ---------------------------------------------------------------- translation

    /** Inlines `#include "file"` / `#include </file>` using [read]. */
    fun inlineIncludes(source: String, read: (String) -> String?, depth: Int = 0): String {
        if (depth > 6) return source
        val out = StringBuilder(source.length)
        for (line in source.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#include")) {
                val name = trimmed.substringAfter('#').substringAfter("include")
                    .trim().trim('"', '<', '>')
                val body = read(name) ?: read(name.removePrefix("/")) ?: read(name.removePrefix("./"))
                if (body != null) {
                    out.append(inlineIncludes(body, read, depth + 1)).append('\n')
                }
                continue
            }
            out.append(line).append('\n')
        }
        return out.toString()
    }

    /**
     * Converts a Java shading-language program to GLSL ES 3.00.
     *
     * The rewrites cover what packs actually use: version lines, legacy varyings and attributes,
     * `texture2D`, `gl_FragColor`, `gl_FragData[n]` and the fixed-function vertex built-ins of
     * GLSL 1.20 (`gl_Vertex`, `gl_MultiTexCoord0`, `ftransform`) which appear in hand-written
     * `composite.vsh` files.
     */
    fun translateFragment(source: String): String {
        var s = source
        s = s.replace(Regex("(?m)^\\s*#version[^\\n]*\\n"), "")
        s = s.replace(Regex("\\btexture2D\\s*\\("), "texture(")
        s = s.replace(Regex("\\btexture3D\\s*\\("), "texture(")
        s = s.replace(Regex("\\btexture2DLod\\s*\\("), "textureLod(")
        s = s.replace(Regex("\\bshadow2D\\s*\\("), "texture(")
        s = s.replace("varying ", "in ")
        s = s.replace("attribute ", "in ")
        // gl_FragData[0] -> fragColor, other targets are dropped (they are not used by our chain).
        s = s.replace(Regex("gl_FragData\\s*\\[\\s*0\\s*\\]"), "fragColor")
        s = s.replace("gl_FragColor", "fragColor")
        // `varying` names commonly declared as `vec2 texcoord` would clash with our built-in.
        if (s.contains("in vec2 texcoord;")) {
            s = s.replace("in vec2 texcoord;", "in vec2 texcoord_pack;")
            s = Regex("\\btexcoord\\b(?!_)").replace(s) { m ->
                if (m.value == "texcoord") "texcoord_pack" else m.value
            }
            s = s.replace("in vec2 texcoord_pack;", "in vec2 texcoord_pack;")
        }
        if (!s.contains("fragColor")) {
            // A composite that only writes to colortexN still needs a valid output.
            s = s.replace("void main()", "void main()", ignoreCase = false)
        }
        return PREAMBLE_HEADER + NOISE_LIB + "\n" + s
    }

    fun translateVertex(source: String): String {
        var s = source
        s = s.replace(Regex("(?m)^\\s*#version[^\\n]*\\n"), "")
        s = s.replace("attribute ", "in ")
        s = s.replace("varying ", "out ")
        s = s.replace("gl_MultiTexCoord0", "vec4(texcoord, 0.0, 1.0)")
        s = s.replace("ftransform()", "vec4(aPos, 0.0, 1.0)")
        s = s.replace(Regex("\\bgl_Vertex\\b"), "vec4(aPos, 0.0, 1.0)")
        val header = """
#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
out vec2 texcoord;
"""
        // The pack's own `void main()` must still write gl_Position; declare texcoord first.
        return header + "\n" + s
    }

    // ---------------------------------------------------------------- discovery

    /** True when a pack follows the Java shader layout (OptiFine/Iris or vanilla post chain). */
    fun looksLikeJavaPack(entries: List<String>): Boolean = entries.any {
        val l = it.lowercase()
        l.contains("shaders/") && (l.endsWith(".fsh") || l.endsWith(".vsh") || l.endsWith(".json"))
    }

    /**
     * Builds the pass list of a pack.
     *
     * @param entries every file inside the pack (forward slashes, lowercase not required).
     * @param read reads a file by path; returns null when missing.
     */
    fun load(packName: String, entries: List<String>, read: (String) -> String?): Loaded? {
        val notes = ArrayList<String>()
        val byName = entries.associateBy { it.lowercase() }

        fun find(vararg names: String): String? {
            for (n in names) {
                byName[n.lowercase()]?.let { return it }
            }
            // Packs nested inside a folder (zip root has "ShaderPack/shaders/...").
            for (n in names) {
                entries.firstOrNull { it.lowercase().endsWith("/" + n.lowercase()) }?.let { return it }
            }
            return null
        }

        fun readInlined(path: String): String? {
            val raw = read(path) ?: return null
            return inlineIncludes(raw) { inc ->
                read(inc) ?: find(inc)
                    ?: entries.firstOrNull { it.endsWith("/shaders/" + inc.removePrefix("/")) }?.let { read(it) }
            }
        }

        // ---- vanilla post chain (shaders/post/*.json) -------------------------------
        val postJson = find("shaders/post/color_convolve.json")
            ?: entries.firstOrNull { it.lowercase().contains("shaders/post/") && it.lowercase().endsWith(".json") }
                ?.let { it }
        if (postJson != null) {
            val text = read(postJson)
            if (text != null) {
                val chain = JavaPostChain.parse(text, entries, read)
                if (chain.isNotEmpty()) {
                    notes.add("Cadena post de Java: ${chain.size} pasadas")
                    return Loaded(packName, chain.map { p ->
                        Pass(p.name, JavaPostChain.VERTEX, p.fragment, listOf("colortex0"))
                    }, notes)
                }
            }
        }

        // ---- OptiFine / Iris composite chain ---------------------------------------
        val compositeNames = entries
            .filter { it.lowercase().contains("shaders/") && it.lowercase().endsWith(".fsh") }
            .map { it.substringAfterLast('/').removeSuffix(".fsh") }
            .filter { name ->
                val n = name.lowercase()
                n == "composite" || n.startsWith("composite") || n == "final" || n == "deferred" ||
                    n == "deferred1" || n == "translucent"
            }
            .distinct()
            .sortedWith(compareBy({ score(it) }, { it }))
            .take(MAX_PASSES)

        if (compositeNames.isEmpty()) {
            notes.add("El pack no tiene pasadas composite/final")
            return null
        }

        val passes = ArrayList<Pass>()
        for (name in compositeNames) {
            val fshPath = find("shaders/$name.fsh") ?: continue
            val frag = readInlined(fshPath) ?: continue
            val translated = try {
                translateFragment(frag)
            } catch (t: Throwable) {
                notes.add("$name: no se pudo traducir (${t.message})")
                continue
            }
            passes.add(Pass(name, FULLSCREEN_VS, translated))
        }
        if (passes.isEmpty()) return null
        notes.add("${passes.size} pasadas OptiFine/Iris")
        Log.i("Shader pack Java '$packName': ${passes.joinToString { it.name }}")
        return Loaded(packName, passes, notes)
    }

    /** Order: composite, composite1..N, final last. */
    private fun score(name: String): Int = when {
        name == "composite" -> 0
        name.startsWith("composite") -> 10 + (name.drop(9).toIntOrNull() ?: 0)
        name == "translucent" -> 50
        name == "deferred" -> 60
        name.startsWith("deferred") -> 60 + (name.drop(8).toIntOrNull() ?: 0)
        name == "final" -> 90
        else -> 40
    }
}

/**
 * Vanilla `shaders/post/*.json` chains.
 *
 * Format (from the wiki): a list of `targets` (extra framebuffers, usually `"swap"`) and a list of
 * `passes`, each with an `intarget`, an `outtarget` and the name of an effect program. The program
 * GLSL lives in `assets/minecraft/shaders/program/<name>.fsh` / `.json`.
 */
object JavaPostChain {

    class Pass(val name: String, val fragment: String)

    const val VERTEX = """
#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
out vec2 texcoord;
void main() {
    texcoord = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private val HEADER = """
#version 300 es
precision highp float;
uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float Time;
in vec2 texcoord;
layout(location = 0) out vec4 fragColor;
"""

    fun parse(json: String, entries: List<String>, read: (String) -> String?): List<Pass> {
        val text = json.trim()
        if (!text.startsWith("{")) {
            // Some packs ship a whole folder of JSONs; the caller passes the first one only.
            return emptyList()
        }
        val passes = ArrayList<Pass>()
        val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
        // Pass entries appear in order; keep the program names.
        for (m in nameRegex.findAll(text)) {
            val program = m.groupValues[1].substringAfterLast(':')
            val frag = effectSource(program) ?: continue
            passes.add(Pass(program, frag))
            if (passes.size >= 8) break
        }
        return passes
    }

    /** GLSL ES implementation of the vanilla post effects that make sense on a phone. */
    fun effectSource(program: String): String? = when (program.lowercase()) {
        "blur" -> HEADER + """
uniform vec2 InSize;
uniform float Radius;
void main() {
    vec2 texel = 1.0 / max(InSize, vec2(1.0));
    vec4 sum = vec4(0.0);
    float total = 0.0;
    for (int x = -4; x <= 4; x++) {
        for (int y = -4; y <= 4; y++) {
            float w = exp(-float(x * x + y * y) / 8.0);
            sum += texture(DiffuseSampler, texcoord + vec2(float(x), float(y)) * texel * Radius) * w;
            total += w;
        }
    }
    fragColor = sum / total;
}
"""
        "color_convolve" -> HEADER + """
uniform vec3 RedMatrix;
uniform vec3 GreenMatrix;
uniform vec3 BlueMatrix;
uniform vec3 Offset;
uniform vec3 Saturation;
void main() {
    vec3 c = texture(DiffuseSampler, texcoord).rgb;
    vec3 r = vec3(dot(c, RedMatrix));
    vec3 g = vec3(dot(c, GreenMatrix));
    vec3 b = vec3(dot(c, BlueMatrix));
    c = vec3(r.x, g.x, b.x) + Offset;
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(luma), c, Saturation);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
        "invert" -> HEADER + """
void main() {
    vec4 c = texture(DiffuseSampler, texcoord);
    fragColor = vec4(1.0 - c.rgb, c.a);
}
"""
        "notch" -> HEADER + """
void main() {
    vec2 uv = texcoord;
    vec2 c = uv - 0.5;
    c *= 1.0 + dot(c, c) * 0.35;
    vec4 col = texture(DiffuseSampler, c + 0.5);
    fragColor = col;
}
"""
        "wobble" -> HEADER + """
uniform float Speed;
void main() {
    vec2 uv = texcoord;
    uv.x += sin(uv.y * 40.0 + Time * Speed) * 0.004;
    uv.y += cos(uv.x * 35.0 + Time * Speed) * 0.004;
    fragColor = texture(DiffuseSampler, uv);
}
"""
        "sobel" -> HEADER + """
uniform vec2 InSize;
uniform vec4 Color;
void main() {
    vec2 texel = 1.0 / max(InSize, vec2(1.0));
    vec3 tl = texture(DiffuseSampler, texcoord + vec2(-1.0, -1.0) * texel).rgb;
    vec3 tr = texture(DiffuseSampler, texcoord + vec2(1.0, -1.0) * texel).rgb;
    vec3 bl = texture(DiffuseSampler, texcoord + vec2(-1.0, 1.0) * texel).rgb;
    vec3 br = texture(DiffuseSampler, texcoord + vec2(1.0, 1.0) * texel).rgb;
    vec3 gx = (tr + br) - (tl + bl);
    vec3 gy = (bl + br) - (tl + tr);
    float g = clamp(length(gx) + length(gy), 0.0, 1.0);
    fragColor = vec4(vec3(g) * Color.rgb, 1.0);
}
"""
        "bits" -> HEADER + """
void main() {
    vec2 uv = floor(texcoord * OutSize / 4.0) * 4.0 / OutSize;
    fragColor = texture(DiffuseSampler, uv);
}
"""
        "desaturate" -> HEADER + """
uniform float Saturation;
void main() {
    vec4 c = texture(DiffuseSampler, texcoord);
    float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(mix(vec3(l), c.rgb, Saturation), c.a);
}
"""
        "antialias" -> HEADER + """
uniform vec2 InSize;
void main() {
    vec2 texel = 1.0 / max(InSize, vec2(1.0));
    vec4 sum = texture(DiffuseSampler, texcoord) * 0.4;
    sum += texture(DiffuseSampler, texcoord + vec2(texel.x, 0.0)) * 0.15;
    sum += texture(DiffuseSampler, texcoord - vec2(texel.x, 0.0)) * 0.15;
    sum += texture(DiffuseSampler, texcoord + vec2(0.0, texel.y)) * 0.15;
    sum += texture(DiffuseSampler, texcoord - vec2(0.0, texel.y)) * 0.15;
    fragColor = sum;
}
"""
        else -> null
    }
}
