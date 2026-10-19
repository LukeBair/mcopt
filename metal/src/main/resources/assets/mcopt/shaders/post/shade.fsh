#version 330
#extension GL_ARB_separate_shader_objects : require

// mcopt shade, stage 1: screen-space sun shadows, ambient occlusion and a gentle grade on the finished level image.
// Reads the main target's colour and (reverse-Z) depth. Written for mcopt; see docs/SHADERS.md.

uniform sampler2D ColorSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ShadeInfo {
    mat4 Proj;
    mat4 InvProj;
    vec4 Sun;      // xyz: direction to the sun in view space, w: daylight 0..1
    vec4 Params0;  // x shadow strength, y ao strength, z grade amount, w shadow length (blocks)
    vec4 Params1;  // x debug mode, y ao radius (blocks), z texel width, w texel height
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float viewDepthAt(vec2 uv, out float d) {
    d = textureLod(DepthSampler, uv, 0.0).r;
    // Only z and w of InvProj * (x, y, d, 1) matter for the linear depth.
    float z = InvProj[2].z * d + InvProj[3].z;
    float w = InvProj[2].w * d + InvProj[3].w;
    return z / w;
}

vec3 viewPosAt(vec2 uv, float d) {
    vec4 p = InvProj * vec4(uv * 2.0 - 1.0, d, 1.0);
    return p.xyz / p.w;
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 tonemap(vec3 c, float amount) {
    // Soft S-curve around mid grey plus a little saturation: keeps blacks and whites where vanilla has them.
    vec3 s = c * c * (3.0 - 2.0 * c);
    c = mix(c, s, 0.55 * amount);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, 1.0 + 0.22 * amount);
    return clamp(c, 0.0, 1.0);
}

void main() {
    vec4 src = texture(ColorSampler, texCoord);
    vec3 col = src.rgb;
    vec2 texel = Params1.zw;
    float d0;
    float z0 = viewDepthAt(texCoord, d0);
    int debug = int(Params1.x + 0.5);

    float shadow = 0.0;
    float ao = 0.0;
    vec3 n = vec3(0.0, 1.0, 0.0);

    if (d0 > 0.0) {
        vec3 p = viewPosAt(texCoord, d0);

        // Normal from the smaller depth step on each axis, so silhouettes don't smear it.
        vec3 pl = viewPosAt(texCoord - vec2(texel.x, 0.0), textureLod(DepthSampler, texCoord - vec2(texel.x, 0.0), 0.0).r);
        vec3 pr = viewPosAt(texCoord + vec2(texel.x, 0.0), textureLod(DepthSampler, texCoord + vec2(texel.x, 0.0), 0.0).r);
        vec3 pu = viewPosAt(texCoord + vec2(0.0, texel.y), textureLod(DepthSampler, texCoord + vec2(0.0, texel.y), 0.0).r);
        vec3 pd = viewPosAt(texCoord - vec2(0.0, texel.y), textureLod(DepthSampler, texCoord - vec2(0.0, texel.y), 0.0).r);
        vec3 ex = abs(pr.z - p.z) < abs(p.z - pl.z) ? pr - p : p - pl;
        vec3 ey = abs(pu.z - p.z) < abs(p.z - pd.z) ? pu - p : p - pd;
        n = normalize(cross(ex, ey));
        if (dot(n, p) > 0.0) n = -n;

        float dist = length(p);
        float jitter = hash12(gl_FragCoord.xy);

        // Contact shadow: march toward the sun in view space, compare with the depth buffer.
        float day = Sun.w;
        if (Params0.x > 0.0 && day > 0.0) {
            float len = Params0.w;
            const int STEPS = 20;
            float stepLen = len / float(STEPS);
            vec3 L = normalize(Sun.xyz);
            vec3 o = p + n * (0.02 + dist * 0.0015);
            float hit = 0.0;
            for (int i = 0; i < STEPS; i++) {
                float t = (float(i) + 0.5 + jitter) * stepLen;
                // Steps grow with distance so short and long casters are both found.
                t = t * (0.35 + 0.65 * float(i + 1) / float(STEPS)) * 1.6;
                vec3 q = o + L * t;
                if (q.z > -0.05) break;
                vec4 c = Proj * vec4(q, 1.0);
                vec2 uv = c.xy / c.w * 0.5 + 0.5;
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) break;
                float dd2;
                float sz = viewDepthAt(uv, dd2);
                if (dd2 <= 0.0) continue; // sky
                float diff = sz - q.z; // > 0: the scene is closer to the camera than the ray point
                float thick = 0.25 + t * 0.35;
                if (diff > 0.03 && diff < thick) { hit = 1.0; break; }
            }
            // Fade shadows out toward the far side of the view, and for surfaces that face away from the sun
            // (vanilla's face shading already darkens those).
            float facing = smoothstep(-0.1, 0.35, dot(n, L));
            shadow = hit * facing * day * (1.0 - smoothstep(60.0, 160.0, dist));
        }

        // Ambient occlusion: a ring of samples in view space, tested against the surface tangent plane.
        if (Params0.y > 0.0) {
            float radius = Params1.y;
            float acc = 0.0;
            float rot = jitter * 6.2831853;
            const int AO_N = 10;
            for (int i = 0; i < AO_N; i++) {
                float a = rot + float(i) * 2.399963;
                float r = (float(i) + 0.5) / float(AO_N);
                vec2 off = vec2(cos(a), sin(a)) * r * r;
                // radius in blocks -> uv offset at this depth
                vec2 uv = texCoord + off * radius / max(-p.z, 0.5) * vec2(Proj[0][0], Proj[1][1]) * 0.5;
                float sd;
                float sz = viewDepthAt(uv, sd);
                if (sd <= 0.0) continue;
                vec3 sp = viewPosAt(uv, sd);
                vec3 v = sp - p;
                float vl = length(v);
                float occ = max(dot(n, v) / max(vl, 1e-4) - 0.12, 0.0);
                acc += occ * (1.0 - smoothstep(radius * 0.6, radius * 2.0, vl));
            }
            ao = clamp(acc / float(AO_N) * 2.2, 0.0, 1.0) * (1.0 - smoothstep(48.0, 128.0, dist));
        }
    }

    if (debug == 1) { fragColor = vec4(n * 0.5 + 0.5, 1.0); return; }
    if (debug == 2) { fragColor = vec4(vec3(1.0 - shadow), 1.0); return; }
    if (debug == 3) { fragColor = vec4(vec3(1.0 - ao), 1.0); return; }

    // Shadow: darker and cooler (the sky fills it); ao: darker, neutral.
    vec3 shadowTint = vec3(0.70, 0.80, 1.0);
    col *= mix(vec3(1.0), shadowTint * (1.0 - 0.18 * Params0.x), shadow * Params0.x);
    col *= 1.0 - ao * Params0.y * 0.6;

    col = tonemap(col, Params0.z);
    fragColor = vec4(col, src.a);
}
