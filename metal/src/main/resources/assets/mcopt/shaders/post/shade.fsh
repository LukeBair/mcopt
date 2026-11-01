#version 330
#extension GL_ARB_separate_shader_objects : require

// mcopt shade, stage 1: screen-space sun shadows, ambient occlusion and a gentle grade on the finished level image. The shadow and
// ao come from shade_occ.fsh (usually at half res), upsampled here by depth so they don't bleed across edges. Reads the main target's
// colour and (reverse-Z) depth. Written for mcopt; see docs/SHADERS.md.

uniform sampler2D ColorSampler;
uniform sampler2D DepthSampler;
uniform sampler2D OccSampler;
uniform sampler2D HandSampler;  // the main target's depth, which by now has the first-person hand in it

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
    vec4 Opts;     // x wide soft shadows (1) or solid with a slightly soft edge (0)
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

vec3 tonemap(vec3 c, float amount) {
    // Soft S-curve around mid grey plus a little saturation: keeps blacks and whites where vanilla has them.
    vec3 s = c * c * (3.0 - 2.0 * c);
    c = mix(c, s, 0.8 * amount);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, 1.0 + 0.22 * amount);
    return clamp(c, 0.0, 1.0);
}

// Normal from the smaller depth step on each axis (the "Normals" view only; shade_occ.fsh has its own).
vec3 normalAt(vec2 uv, vec3 p, vec2 texel) {
    vec3 pl = viewPosAt(uv - vec2(texel.x, 0.0), textureLod(DepthSampler, uv - vec2(texel.x, 0.0), 0.0).r);
    vec3 pr = viewPosAt(uv + vec2(texel.x, 0.0), textureLod(DepthSampler, uv + vec2(texel.x, 0.0), 0.0).r);
    vec3 pu = viewPosAt(uv + vec2(0.0, texel.y), textureLod(DepthSampler, uv + vec2(0.0, texel.y), 0.0).r);
    vec3 pd = viewPosAt(uv - vec2(0.0, texel.y), textureLod(DepthSampler, uv - vec2(0.0, texel.y), 0.0).r);
    vec3 ex = abs(pr.z - p.z) < abs(p.z - pl.z) ? pr - p : p - pl;
    vec3 ey = abs(pu.z - p.z) < abs(p.z - pd.z) ? pu - p : p - pd;
    vec3 n = normalize(cross(ex, ey));
    return dot(n, p) > 0.0 ? -n : n;
}

void main() {
    vec4 src = texture(ColorSampler, texCoord);
    vec3 col = src.rgb;
    vec2 texel = Params1.zw;
    float d0;
    float z0 = viewDepthAt(texCoord, d0);
    // The hand and held item are drawn into the main colour after the level depth was copied, their depth merged into the main
    // depth: where that differs from the copy, leave the pixel unshaded, or the terrain behind would show through the item.
    float hd = textureLod(HandSampler, texCoord, 0.0).r;
    if (hd > 0.0 && hd != d0) d0 = 0.0;
    int debug = int(Params1.x + 0.5);

    vec3 occ = vec3(0.0);
    vec3 n = vec3(0.0, 1.0, 0.0);
    if (d0 > 0.0) {
        // Bilateral upsample: the four nearest occlusion pixels, bilinear weights scaled down by how far each one's depth is from ours.
        vec2 pos = floor(texCoord / texel) / Occ.x;
        vec2 base = floor(pos), f = pos - base;
        float wsum = 0.0, best = 1e9;
        vec3 sum = vec3(0.0), nearest = vec3(0.0);
        float tol = 0.03 * abs(z0) + 0.05;
        for (int i = 0; i < 4; i++) {
            vec2 o = vec2(float(i & 1), float(i >> 1));
            vec2 h = min(base + o, Occ.zw - 1.0);
            float dh;
            float zh = viewDepthAt((h * Occ.x + 0.5) * texel, dh);
            if (dh <= 0.0) continue;
            vec3 v = textureLod(OccSampler, (h + 0.5) / Occ.zw, 0.0).rgb;
            float dz = abs(zh - z0);
            if (dz < best) { best = dz; nearest = v; }
            vec2 b = mix(1.0 - f, f, o);
            float w = b.x * b.y * exp(-dz / tol);
            sum += v * w;
            wsum += w;
        }
        // No neighbour on our surface (a thin edge): the closest one in depth.
        occ = wsum > 1e-3 ? sum / wsum : nearest;
        if (debug == 1) n = normalAt(texCoord, viewPosAt(texCoord, d0), texel);
    }
    float shadow = occ.r, ao = occ.g, lit = occ.b;

    if (debug == 1) { fragColor = vec4(n * 0.5 + 0.5, 1.0); return; }
    if (debug == 2) { fragColor = vec4(vec3(1.0 - shadow), 1.0); return; }
    if (debug == 3) { fragColor = vec4(vec3(1.0 - ao), 1.0); return; }

    // Shadow: darker and cooler (the sky fills it); ao: darker, neutral.
    vec3 shadowTint = vec3(0.40, 0.50, 0.78);
    col *= mix(vec3(1.0), shadowTint, shadow * Params0.x);
    col *= 1.0 - ao * Params0.y * 0.9;
    // Light colour: warm low sun, white noon, cool moon, only where the light reaches (facing it and not in shadow).
    col *= mix(vec3(1.0), Tint.rgb / dot(Tint.rgb, vec3(0.3333)), Tint.a * lit * (1.0 - shadow)) * (1.0 + 0.12 * lit * (1.0 - shadow) * Tint.a);

    col = tonemap(col, Params0.z);
    fragColor = vec4(col, src.a);
}
