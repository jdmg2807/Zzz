package com.arena.voxel.packs

/**
 * Built-in GLSL ES 3.0 shaders. Every one of them can be replaced by a shader pack file with
 * the same name (terrain.vsh, terrain.fsh, water.vsh, water.fsh, sky.vsh, sky.fsh,
 * post.vsh, post.fsh).
 */
object ShaderSources {

    val TERRAIN_VSH = """
#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in float aShade;

uniform mat4 uMVP;

out vec2 vUV;
out float vShade;
out vec3 vWorld;

void main() {
    vUV = aUV;
    vShade = aShade;
    vWorld = aPos;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
""".trimIndent()

    val TERRAIN_FSH = """
#version 300 es
precision mediump float;

in vec2 vUV;
in float vShade;
in vec3 vWorld;

uniform sampler2D uTex;
uniform vec3 uCam;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec3 uLightColor;

out vec4 fragColor;

void main() {
    vec4 t = texture(uTex, vUV);
    if (t.a < 0.5) discard;
    vec3 c = t.rgb * vShade * uLightColor;
    float d = distance(vWorld, uCam);
    float f = clamp((d - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    fragColor = vec4(mix(c, uFogColor, f), t.a);
}
""".trimIndent()

    val WATER_VSH = """
#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in float aShade;

uniform mat4 uMVP;
uniform float uTime;

out vec2 vUV;
out float vShade;
out vec3 vWorld;

void main() {
    vec3 p = aPos;
    if (aShade > 0.99) {
        p.y += sin(p.x * 1.3 + uTime * 1.6) * 0.05 + cos(p.z * 1.7 + uTime * 1.2) * 0.05;
    }
    vUV = aUV;
    vShade = aShade;
    vWorld = p;
    gl_Position = uMVP * vec4(p, 1.0);
}
""".trimIndent()

    val WATER_FSH = """
#version 300 es
precision mediump float;

in vec2 vUV;
in float vShade;
in vec3 vWorld;

uniform sampler2D uTex;
uniform vec3 uCam;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec3 uLightColor;
uniform float uTime;

out vec4 fragColor;

void main() {
    vec2 uv = vUV + vec2(sin(uTime * 0.8 + vWorld.z) * 0.02, cos(uTime * 0.6 + vWorld.x) * 0.02);
    vec4 t = texture(uTex, uv);
    vec3 c = t.rgb * (0.85 + vShade * 0.25) * uLightColor;
    float d = distance(vWorld, uCam);
    float f = clamp((d - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    fragColor = vec4(mix(c, uFogColor, f), 0.72);
}
""".trimIndent()

    val SKY_VSH = """
#version 300 es
layout(location = 0) in vec2 aPos;

uniform mat4 uInvVP;

out vec3 vDir;

void main() {
    vec4 p = uInvVP * vec4(aPos, 1.0, 1.0);
    vDir = p.xyz / p.w;
    gl_Position = vec4(aPos, 1.0, 1.0);
}
""".trimIndent()

    val SKY_FSH = """
#version 300 es
precision mediump float;

in vec3 vDir;

uniform vec3 uSunDir;
uniform vec3 uZenith;
uniform vec3 uHorizon;
uniform vec3 uSunColor;

out vec4 fragColor;

void main() {
    vec3 d = normalize(vDir);
    float h = clamp(d.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 c = mix(uHorizon, uZenith, pow(h, 0.75));
    float sun = max(dot(d, normalize(uSunDir)), 0.0);
    c += uSunColor * pow(sun, 220.0) * 1.4;
    c += uSunColor * pow(sun, 8.0) * 0.10;
    fragColor = vec4(c, 1.0);
}
""".trimIndent()

    val POST_VSH = """
#version 300 es
layout(location = 0) in vec2 aPos;

out vec2 vUV;

void main() {
    vUV = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
""".trimIndent()

    val POST_FSH = """
#version 300 es
precision mediump float;

in vec2 vUV;

uniform sampler2D uScene;
uniform float uTime;
uniform float uUnderwater;
uniform vec2 uResolution;

out vec4 fragColor;

void main() {
    vec2 uv = vUV;
    if (uUnderwater > 0.5) {
        uv.x += sin(uv.y * 24.0 + uTime * 2.0) * 0.0022;
        uv.y += cos(uv.x * 20.0 + uTime * 1.6) * 0.0022;
    }
    vec3 c = texture(uScene, uv).rgb;
    float vignette = smoothstep(0.92, 0.32, distance(vUV, vec2(0.5)));
    c *= mix(0.80, 1.0, vignette);
    c = mix(c, c * vec3(0.42, 0.68, 1.0), uUnderwater * 0.62);
    c = pow(c, vec3(1.02));
    fragColor = vec4(c, 1.0);
}
""".trimIndent()

    val LINE_VSH = """
#version 300 es
layout(location = 0) in vec3 aPos;

uniform mat4 uMVP;

void main() {
    gl_Position = uMVP * vec4(aPos, 1.0);
}
""".trimIndent()

    val LINE_FSH = """
#version 300 es
precision mediump float;

uniform vec4 uColor;

out vec4 fragColor;

void main() {
    fragColor = uColor;
}
""".trimIndent()

    fun all(): Map<String, String> = linkedMapOf(
        "terrain.vsh" to TERRAIN_VSH,
        "terrain.fsh" to TERRAIN_FSH,
        "water.vsh" to WATER_VSH,
        "water.fsh" to WATER_FSH,
        "sky.vsh" to SKY_VSH,
        "sky.fsh" to SKY_FSH,
        "post.vsh" to POST_VSH,
        "post.fsh" to POST_FSH
    )
}
