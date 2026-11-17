package com.chaoslab.app.render

object Shaders {

    private const val PALETTE_FN = """
uniform vec3 uC0;
uniform vec3 uC1;
uniform vec3 uC2;
vec3 palette(float t) {
    return t < 0.5 ? mix(uC0, uC1, t * 2.0) : mix(uC1, uC2, (t - 0.5) * 2.0);
}
"""

    val PARTICLE_VS = """#version 300 es
layout(location = 0) in vec4 aPos;
uniform mat4 uMVP;
uniform float uPointScale;
out float vT;
out float vFade;
void main() {
    vec4 p = uMVP * vec4(aPos.xyz, 1.0);
    gl_Position = p;
    float sz = uPointScale / max(p.w, 1.0);
    gl_PointSize = clamp(sz, 1.0, 16.0);
    vT = aPos.w;
    vFade = clamp(sz * 0.6, 0.35, 1.0);
}
"""

    val PARTICLE_FS = """#version 300 es
precision mediump float;
in float vT;
in float vFade;
uniform float uIntensity;
$PALETTE_FN
out vec4 fragColor;
void main() {
    vec2 d = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(d, d);
    if (r2 > 1.0) discard;
    float a = exp(-r2 * 4.0);
    fragColor = vec4(palette(vT) * a * uIntensity * vFade, 1.0);
}
"""

    val TRAIL_VS = """#version 300 es
layout(location = 0) in vec4 aPos;
uniform mat4 uMVP;
out float vT;
out float vA;
void main() {
    gl_Position = uMVP * vec4(aPos.xyz, 1.0);
    if (aPos.w >= 0.0) { vT = aPos.w; vA = 1.0; }
    else { vT = -1.0 - aPos.w; vA = 0.0; }
}
"""

    val TRAIL_FS = """#version 300 es
precision mediump float;
in float vT;
in float vA;
uniform float uIntensity;
$PALETTE_FN
out vec4 fragColor;
void main() {
    fragColor = vec4(palette(vT) * vA * uIntensity, 1.0);
}
"""

    val BODY_VS = """#version 300 es
layout(location = 0) in vec4 aPos;
layout(location = 1) in vec4 aInfo;
uniform mat4 uMVP;
uniform float uProjScale;
uniform float uHalo;
uniform float uMaxPoint;
out vec3 vColor;
flat out int vKind;
flat out int vSel;
void main() {
    vec4 p = uMVP * vec4(aPos.xyz, 1.0);
    gl_Position = p;
    float k = aInfo.x;
    vSel = k > 9.5 ? 1 : 0;
    if (k > 9.5) k -= 10.0;
    vKind = int(k + 0.5);
    vColor = aInfo.yzw;
    float sz = aPos.w * 2.0 * uHalo * uProjScale / max(p.w, 0.5);
    gl_PointSize = clamp(sz, 3.0, uMaxPoint);
}
"""

    val BODY_FS = """#version 300 es
precision highp float;
in vec3 vColor;
flat in int vKind;
flat in int vSel;
uniform float uHalo;
uniform int uPass;
out vec4 fragColor;
void main() {
    vec2 pc = gl_PointCoord * 2.0 - 1.0;
    float d = length(pc) * uHalo;
    if (uPass == 0) {
        if (d > 1.0) discard;
        if (vKind == 2) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        vec3 n = vec3(pc * uHalo, sqrt(max(0.0, 1.0 - d * d)));
        if (vKind == 1) {
            vec3 c = mix(vColor, vec3(1.0), 0.55 + 0.45 * n.z);
            fragColor = vec4(c, 1.0);
            return;
        }
        float l = max(dot(n, normalize(vec3(-0.5, 0.6, 0.65))), 0.0);
        fragColor = vec4(vColor * (0.16 + 0.95 * l), 1.0);
        return;
    }
    if (d < 1.0) discard;
    vec3 c = vec3(0.0);
    if (vKind == 1) {
        c = vColor * exp(-(d - 1.0) * 1.5) * 0.85 + vec3(1.0) * exp(-(d - 1.0) * 5.0) * 0.45;
        c *= 1.0 - smoothstep(uHalo * 0.8, uHalo, d);
    } else if (vKind == 2) {
        float ring = exp(-pow((d - 1.45) * 6.5, 2.0));
        float glow = exp(-(d - 1.0) * 1.9) * 0.28;
        c = vec3(1.0, 0.62, 0.25) * ring * 1.5 + vec3(0.55, 0.35, 1.0) * glow;
        c *= 1.0 - smoothstep(uHalo * 0.8, uHalo, d);
    } else {
        c = vColor * exp(-(d - 1.0) * 4.0) * 0.35;
    }
    if (vSel == 1) {
        c += vec3(0.3, 0.9, 1.0) * exp(-pow((d - uHalo * 0.78) * 5.0, 2.0)) * 0.9;
    }
    fragColor = vec4(c, 0.0);
}
"""

    val GRID_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
uniform float uExtent;
out float vA;
out float vDepth;
void main() {
    gl_Position = uMVP * vec4(aPos, 1.0);
    float r = length(aPos.xz) / uExtent;
    vA = 1.0 - smoothstep(0.5, 1.0, r);
    vDepth = clamp(-aPos.y / 120.0, 0.0, 1.0);
}
"""

    val GRID_FS = """#version 300 es
precision mediump float;
in float vA;
in float vDepth;
uniform vec3 uColor;
out vec4 fragColor;
void main() {
    vec3 c = mix(uColor, vec3(0.75, 0.45, 1.0), vDepth);
    fragColor = vec4(c * vA * (0.12 + 0.3 * vDepth), 1.0);
}
"""

    val SKY_VS = """#version 300 es
layout(location = 0) in vec4 aPos;
uniform mat4 uMVP;
out float vB;
void main() {
    gl_Position = uMVP * vec4(aPos.xyz, 1.0);
    vB = aPos.w;
    gl_PointSize = 1.0 + aPos.w * 2.2;
}
"""

    val SKY_FS = """#version 300 es
precision mediump float;
in float vB;
out vec4 fragColor;
void main() {
    vec2 d = gl_PointCoord * 2.0 - 1.0;
    float a = max(0.0, 1.0 - dot(d, d));
    fragColor = vec4(vec3(0.75, 0.82, 1.0) * a * vB * 0.7, 1.0);
}
"""
}
