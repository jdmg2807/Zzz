#version 300 es
// Shader de ejemplo para VoxelCraft.
// Los shaders integrados son: terrain, water, sky y post (.vsh / .fsh).
// Este archivo reemplaza el pase de post-proceso (efecto de pantalla completa).
precision mediump float;

in vec2 vUV;

uniform sampler2D uScene;
uniform float uTime;
uniform float uUnderwater;
uniform vec2 uResolution;

out vec4 fragColor;

void main() {
    vec3 c = texture(uScene, vUV).rgb;

    // Sepia cinematográfico
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    vec3 sepia = vec3(luma * 1.15, luma * 0.95, luma * 0.72);
    c = mix(c, sepia, 0.72);

    // Viñeta
    float d = distance(vUV, vec2(0.5));
    c *= smoothstep(0.95, 0.30, d) * 0.35 + 0.65;

    // Ligero parpadeo de película antigua
    float grain = fract(sin(dot(vUV * uResolution + uTime, vec2(12.9898, 78.233))) * 43758.5453);
    c += (grain - 0.5) * 0.035;

    // Bajo el agua se mantiene el tono azul
    c = mix(c, c * vec3(0.45, 0.75, 1.05), uUnderwater * 0.6);

    fragColor = vec4(c, 1.0);
}
