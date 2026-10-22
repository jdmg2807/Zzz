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
layout(location = 3) in float aSky;
layout(location = 4) in float aBlock;

uniform mat4 uMVP;

out vec2 vUV;
out float vShade;
out float vSky;
out float vBlock;
out vec3 vWorld;

void main() {
    vUV = aUV;
    vShade = aShade;
    vSky = aSky;
    vBlock = aBlock;
    vWorld = aPos;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
""".trimIndent()

    val TERRAIN_FSH = """
#version 300 es
precision mediump float;

in vec2 vUV;
in float vShade;
in float vSky;
in float vBlock;
in vec3 vWorld;

uniform sampler2D uTex;
uniform vec3 uCam;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec3 uLightColor;
uniform float uDay;

out vec4 fragColor;

void main() {
    vec4 t = texture(uTex, vUV);
    if (t.a < 0.5) discard;

    // Combine moon/ambient, sky light (attenuated by uDay) and warm block light.
    vec3 light = vec3(0.085, 0.10, 0.19);
    light = max(light, uLightColor * (vSky * uDay));
    light = max(light, vec3(1.0, 0.74, 0.46) * vBlock);

    vec3 c = t.rgb * vShade * light;
    float d = distance(vWorld, uCam);
    float f = clamp((d - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    f = f * f * (3.0 - 2.0 * f);
    fragColor = vec4(mix(c, uFogColor, f), t.a);
}
""".trimIndent()

    val WATER_VSH = """
#version 300 es
precision mediump float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in float aShade;
layout(location = 3) in float aSky;
layout(location = 4) in float aBlock;

uniform mat4 uMVP;
uniform float uTime;

out vec2 vUV;
out float vShade;
out float vSky;
out float vBlock;
out vec3 vWorld;

void main() {
    vec3 p = aPos;
    // Gentle swell on the surface quads only (shade stores the face factor).
    p.y += sin(p.x * 0.9 + uTime * 1.4) * 0.035 + cos(p.z * 1.1 + uTime * 1.1) * 0.035;
    vUV = aUV;
    vShade = aShade;
    vSky = aSky;
    vBlock = aBlock;
    vWorld = p;
    gl_Position = uMVP * vec4(p, 1.0);
}
""".trimIndent()

    val WATER_FSH = """
#version 300 es
precision mediump float;

in vec2 vUV;
in float vShade;
in float vSky;
in float vBlock;
in vec3 vWorld;

uniform sampler2D uTex;
uniform vec3 uCam;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform vec3 uLightColor;
uniform vec3 uSunDir;
uniform float uTime;
uniform float uDay;

out vec4 fragColor;

void main() {
    vec2 uv = vUV + vec2(sin(uTime * 0.8 + vWorld.z * 0.7) * 0.018, cos(uTime * 0.6 + vWorld.x * 0.7) * 0.018);
    vec4 t = texture(uTex, uv);

    vec3 light = vec3(0.085, 0.10, 0.19);
    light = max(light, uLightColor * (vSky * uDay));
    light = max(light, vec3(1.0, 0.74, 0.46) * vBlock);

    vec3 base = t.rgb * (0.85 + vShade * 0.25) * light;

    // Fresnel: water reflects the sky at grazing angles.
    vec3 viewDir = normalize(uCam - vWorld);
    float fres = pow(1.0 - clamp(viewDir.y, 0.0, 1.0), 3.0);
    vec3 skyTint = uFogColor * 1.15 * max(uDay, 0.12);
    vec3 c = mix(base, skyTint, fres * 0.65);

    // Specular highlight from the sun.
    vec3 h = normalize(uSunDir + viewDir);
    float spec = pow(max(dot(vec3(0.0, 1.0, 0.0), h), 0.0), 160.0);
    c += vec3(1.0, 0.96, 0.86) * spec * 0.55 * uDay;

    float d = distance(vWorld, uCam);
    float f = clamp((d - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    float alpha = mix(0.72, 0.94, fres);
    fragColor = vec4(mix(c, uFogColor, f * f * (3.0 - 2.0 * f)), alpha);
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
precision highp float;

in vec3 vDir;

uniform vec3 uSunDir;
uniform vec3 uZenith;
uniform vec3 uHorizon;
uniform vec3 uSunColor;
uniform vec3 uCam;
uniform float uTime;
uniform float uCloudY;
uniform float uCloudCover;
uniform float uBrightness;

out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += vnoise(p) * a;
        p *= 2.03;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec3 d = normalize(vDir);
    float h = clamp(d.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 c = mix(uHorizon, uZenith, pow(h, 0.8));

    // Sun glow + disc.
    vec3 sunDir = normalize(uSunDir);
    float sunDot = max(dot(d, sunDir), 0.0);
    c += uSunColor * pow(sunDot, 8.0) * 0.14 * uBrightness;
    c += uSunColor * smoothstep(0.9992, 0.9997, sunDot) * 1.6;

    // Moon: opposite the sun, with a soft crescent.
    vec3 moonDir = -sunDir;
    float moonDot = max(dot(d, moonDir), 0.0);
    float moon = smoothstep(0.9990, 0.9996, moonDot);
    vec3 moonShift = normalize(moonDir + vec3(0.035, 0.03, 0.0));
    float shadow = smoothstep(0.9990, 0.9996, max(dot(d, moonShift), 0.0));
    c += vec3(0.92, 0.94, 1.0) * moon * (1.0 - shadow * 0.75) * (1.0 - uBrightness) * 1.3;

    // Stars.
    if (uBrightness < 0.55 && d.y > 0.0) {
        vec2 sp = d.xz / max(d.y, 0.15) * 42.0;
        float s = hash21(floor(sp));
        float star = step(0.9965, s) * (1.0 - uBrightness / 0.55);
        star *= 0.55 + 0.45 * sin(uTime * 2.0 + s * 40.0);
        c += vec3(star) * 1.1;
    }

    // Flat cloud layer, Minecraft style, drifting with the wind.
    if (d.y > 0.015) {
        float t = (uCloudY - uCam.y) / d.y;
        if (t > 0.0) {
            vec2 p = (uCam.xz + d.xz * t) * 0.045 + vec2(uTime * 0.004, uTime * 0.0016);
            float n = fbm(p);
            float cover = smoothstep(1.05 - uCloudCover, 1.32 - uCloudCover, n);
            float fade = smoothstep(0.015, 0.22, d.y) * exp(-t * 0.0016);
            vec3 cloudColor = mix(vec3(0.78, 0.80, 0.86), vec3(1.0, 1.0, 1.0), uBrightness);
            c = mix(c, cloudColor, cover * fade * 0.9);
        }
    }

    fragColor = vec4(c, 1.0);
}
""".trimIndent()

    val POST_VSH = """
#version 300 es
precision mediump float;
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
        uv.x += sin(uv.y * 26.0 + uTime * 2.0) * 0.0022;
        uv.y += cos(uv.x * 22.0 + uTime * 1.6) * 0.0022;
    }
    vec3 c = texture(uScene, uv).rgb;

    // Subtle contrast + saturation, closer to the Java look.
    c = (c - 0.5) * 1.045 + 0.5;
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(luma), c, 1.07);

    // Vignette.
    float vignette = smoothstep(0.95, 0.35, distance(vUV, vec2(0.5)));
    c *= mix(0.86, 1.0, vignette);

    // Underwater tint.
    c = mix(c, c * vec3(0.42, 0.68, 1.05), uUnderwater * 0.62);

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
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
