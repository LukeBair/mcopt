#version 330
#extension GL_ARB_separate_shader_objects : require

// mcopt shade, occlusion half: sun shadow, ambient occlusion and sun facing per pixel of a (usually half-res) target, which
// shade.fsh upsamples and applies. Reads the (reverse-Z) depth. Written for mcopt; see docs/SHADERS.md.

uniform sampler2D DepthSampler;
uniform sampler2D ShadowSampler;
uniform sampler2D ShadowSampler2;

layout(std140) uniform ShadeInfo {
    mat4 Proj;
    mat4 InvProj;
    vec4 Sun;      // xyz: direction to the sun in view space, w: daylight 0..1
    vec4 Params0;  // x shadow strength, y ao strength, z grade amount, w shadow length (blocks)
    vec4 Params1;  // x debug mode, y ao radius (blocks), z texel width, w texel height
    mat4 ShadowM;  // view space -> sun shadow map clip (reverse-Z depth)
    vec4 ShadowP;  // x map texel (blocks), y 1/resolution, z map valid, w depth range (blocks)
    vec4 Tint;     // rgb: light colour (sun or moon by time of day), a: strength
    vec4 Occ;      // x full-res pixels per occlusion pixel, y vanilla render distance (blocks), zw occlusion target size
    vec4 Opts;     // x wide soft shadows (1) or solid with a slightly soft edge (0), y shadow map blend
    mat4 ViewInv;  // view space -> world space rotation
    vec4 CloudP;   // x stylized clouds on, y cloud base height (world y), z reach (blocks), w sun height 0 (down) .. 1 (up)
    vec4 CloudO;   // x, z the camera's place on the cloud map (blocks, wrapped), y camera world y, w map cells per side
    vec4 CloudCol; // vanilla's cloud colour for the time and weather (rgb, a)
    vec4 LightP;   // x coloured lights in ShadeLights, y their strength, z camera under water (1), w daylight under water 0..1
    mat4 ShadowM2; // view space -> the second shadow map (the sun angle step above ShadowM's); Opts.y blends toward it
    vec4 CloudX;   // x rain 0..1, y vivid sunrise/sunset (1, rare)
    vec4 VividL;   // vivid palette: sunlight on the clouds
    vec4 VividS;   // vivid palette: shaded undersides
    vec4 VividK;   // vivid palette: sky tint toward the horizon
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

#define SMAP(uv, l) (which == 0 ? textureLod(ShadowSampler, uv, l) : textureLod(ShadowSampler2, uv, l))

// Sun shadow map lookup: PCF over a rotated Vogel disc, normal-offset and slope-scaled bias. Returns 0 lit .. 1 shadowed, and in
// edge how far inside the map the point is (1 inside, 0 at its border) so the caller can fade into the screen-space fallback.
float mapShadow(int which, vec3 p, vec3 n, vec3 L, float jitter, out float edge) {
    float cosT = clamp(dot(n, L), 0.05, 1.0);
    float tanT = sqrt(1.0 - cosT * cosT) / cosT;
    float texel = ShadowP.x;
    // Normal offset grows with the slope, so grazing surfaces do not self-shadow.
    vec3 q = p + n * texel * (2.0 + 2.0 * min(tanT, 3.0));
    vec4 c = (which == 0 ? ShadowM : ShadowM2) * vec4(q, 1.0);
    vec3 s = c.xyz / c.w;
    vec2 uv = s.xy * 0.5 + 0.5;
    edge = 1.0 - smoothstep(0.82, 0.98, max(abs(s.x), abs(s.y)));
    if (edge <= 0.0 || s.z <= 0.0 || s.z >= 1.0) { edge = 0.0; return 0.0; }
    float bias = (texel * (1.5 + 1.5 * min(tanT, 3.0))) / ShadowP.w;
    // Default: solid shadows with a slightly soft edge. Four lookups on a 1.5-texel grid blended bilinearly, so the edge ramps
    // over about 0.2 blocks with no noise and no stair-steps from the map's texels.
    if (Opts.x < 0.5) {
        float step = 1.5 * ShadowP.y;
        vec2 g = uv / step - 0.5;
        vec2 f = fract(g);
        vec2 c = (floor(g) + 0.5) * step;
        float s00 = (SMAP(c, 0.0).r > s.z + bias) ? 1.0 : 0.0;
        float s10 = (SMAP(c + vec2(step, 0.0), 0.0).r > s.z + bias) ? 1.0 : 0.0;
        float s01 = (SMAP(c + vec2(0.0, step), 0.0).r > s.z + bias) ? 1.0 : 0.0;
        float s11 = (SMAP(c + vec2(step), 0.0).r > s.z + bias) ? 1.0 : 0.0;
        return mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
    }
    float radius = ShadowP.y * 3.0;
    float acc = 0.0;
    const int N = 16;
    float rot = jitter * 6.2831853;
    // Four taps from the disc's outer ring first: when they agree (fully lit or fully shadowed, most of the screen) that's the
    // answer, and only penumbra pixels pay for all sixteen.
    for (int i = 12; i < N; i++) {
        float r = sqrt((float(i) + 0.5) / float(N));
        float a = rot + float(i) * 2.399963;
        vec2 o = vec2(cos(a), sin(a)) * r * radius;
        acc += (SMAP(uv + o, 0.0).r > s.z + bias) ? 1.0 : 0.0;
    }
    if (acc == 0.0 || acc == 4.0) return acc * 0.25;
    for (int i = 0; i < 12; i++) {
        float r = sqrt((float(i) + 0.5) / float(N));
        float a = rot + float(i) * 2.399963;
        vec2 o = vec2(cos(a), sin(a)) * r * radius;
        acc += (SMAP(uv + o, 0.0).r > s.z + bias) ? 1.0 : 0.0;
    }
    return acc / float(N);
}

void main() {
    // The full-res pixel this occlusion pixel stands for (the top-left of its block), so shade.fsh can find its depth again.
    vec2 texel = Params1.zw;
    vec2 uv0 = (floor(texCoord * Occ.zw) * Occ.x + 0.5) * texel;
    float d0;
    viewDepthAt(uv0, d0);

    float shadow = 0.0;
    float ao = 0.0;
    float lit = 0.0;
    vec3 n = vec3(0.0, 1.0, 0.0);

    if (d0 > 0.0) {
        vec3 p = viewPosAt(uv0, d0);

        // Normal from the smaller depth step on each axis, so silhouettes don't smear it.
        vec3 pl = viewPosAt(uv0 - vec2(texel.x, 0.0), textureLod(DepthSampler, uv0 - vec2(texel.x, 0.0), 0.0).r);
        vec3 pr = viewPosAt(uv0 + vec2(texel.x, 0.0), textureLod(DepthSampler, uv0 + vec2(texel.x, 0.0), 0.0).r);
        vec3 pu = viewPosAt(uv0 + vec2(0.0, texel.y), textureLod(DepthSampler, uv0 + vec2(0.0, texel.y), 0.0).r);
        vec3 pd = viewPosAt(uv0 - vec2(0.0, texel.y), textureLod(DepthSampler, uv0 - vec2(0.0, texel.y), 0.0).r);
        vec3 ex = abs(pr.z - p.z) < abs(p.z - pl.z) ? pr - p : p - pl;
        vec3 ey = abs(pu.z - p.z) < abs(p.z - pd.z) ? pu - p : p - pd;
        n = normalize(cross(ex, ey));
        if (dot(n, p) > 0.0) n = -n;

        float dist = length(p);
        // Only mcopt's / vanilla's own terrain: fade out over the last chunk of the render distance (Distant Horizons' LODs are beyond it).
        float near = 1.0 - smoothstep(Occ.y - 16.0, Occ.y, dist);
        lit = smoothstep(-0.1, 0.35, dot(n, normalize(Sun.xyz))) * near;
        // Wide soft: a per-pixel rotation for the PCF disc and the march; default: none, so shadows come out solid.
        float jitter = Opts.x > 0.5 ? hash12(gl_FragCoord.xy) : 0.5;

        // Shadow: the sun map where it covers, with the screen-space march kept as fine contact detail (and as the fallback outside it).
        float day = Sun.w;
        vec3 L = normalize(Sun.xyz);
        float facing = smoothstep(-0.1, 0.35, dot(n, L));
        // Faces turned from the sun are left to vanilla's face shading (facing 0), so they skip both lookups.
        if (Params0.x > 0.0 && day > 0.0 && facing > 0.0) {
            float edge = 0.0;
            float mapS = 0.0;
            bool useMap = ShadowP.z > 0.5;
            if (useMap) {
                // Two maps at fixed sun angles either side of the real one, crossfaded (ShadeShadow): no flicker as the sun moves.
                mapS = mapShadow(0, p, n, L, jitter, edge);
                if (Opts.y > 0.001) {
                    float edge2;
                    mapS = mix(mapS, mapShadow(1, p, n, L, jitter, edge2), Opts.y);
                    edge = min(edge, edge2);
                }
            }
            // Inside the map and fully shadowed already: the contact march can't change the result.
            bool march = !(useMap && mapS * edge >= 1.0);
            float len = useMap ? min(Params0.w, 3.0) : Params0.w;
            const int STEPS = 20;
            float stepLen = len / float(STEPS);
            vec3 o = p + n * (0.02 + dist * 0.0015);
            float hit = 0.0;
            for (int i = 0; march && i < STEPS; i++) {
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
            float s = useMap ? max(mapS * edge + hit * (1.0 - edge), hit * 0.6) : hit;
            shadow = s * facing * day * near * (useMap ? 1.0 : 1.0 - smoothstep(60.0, 160.0, dist));
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
                vec2 uv = uv0 + off * radius / max(-p.z, 0.5) * vec2(Proj[0][0], Proj[1][1]) * 0.5;
                float sd;
                float sz = viewDepthAt(uv, sd);
                if (sd <= 0.0) continue;
                vec3 sp = viewPosAt(uv, sd);
                vec3 v = sp - p;
                float vl = length(v);
                float occ = max(dot(n, v) / max(vl, 1e-4) - 0.12, 0.0);
                acc += occ * (1.0 - smoothstep(radius * 0.6, radius * 2.0, vl));
            }
            ao = clamp(acc / float(AO_N) * 2.2, 0.0, 1.0) * (1.0 - smoothstep(48.0, 128.0, dist)) * near;
        }
    }

    fragColor = vec4(shadow, ao, lit, 1.0);
}
