#version 330
#extension GL_ARB_separate_shader_objects : require

// mcopt shade, stage 1: screen-space sun shadows, ambient occlusion and a gentle grade on the finished level image. The shadow and
// ao come from shade_occ.fsh (usually at half res), upsampled here by depth so they don't bleed across edges. Reads the main target's
// colour and (reverse-Z) depth. Written for mcopt; see docs/SHADERS.md.

uniform sampler2D ColorSampler;
uniform sampler2D DepthSampler;
uniform sampler2D OccSampler;
uniform sampler2D CloudSampler; // the cloud cell map: r cloud, g density (ShadeClouds)
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

// The light-emitting blocks nearest the camera (ShadeLights): view-space centre and emission 0..1, and hue.
layout(std140) uniform ShadeLights {
    vec4 LightPos[32];
    vec4 LightCol[32];
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

// Coloured block light: the emitters' hues near p, weighted by distance (a block's light reaches 15 blocks) and by facing.
// Returns the hue in rgb (brightest channel 1) and how strongly it applies in a.
vec4 blockLight(vec3 p, vec3 n) {
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    int count = int(LightP.x + 0.5);
    for (int i = 0; i < 32; i++) {
        if (i >= count) break;
        vec3 v = LightPos[i].xyz - p;
        float d = length(v);
        float reach = 15.0 * LightPos[i].w;
        if (d >= reach) continue;
        float a = 1.0 - d / reach;
        float facing = d < 0.9 ? 1.0 : clamp(dot(n, v / d) * 0.6 + 0.4, 0.0, 1.0);
        float w = a * a * facing;
        sum += LightCol[i].rgb * w;
        wsum += w;
    }
    if (wsum <= 1e-4) return vec4(0.0);
    vec3 hue = sum / wsum;
    return vec4(hue / max(hue.r, max(hue.g, hue.b)), clamp(wsum, 0.0, 1.0));
}

// The cloud map at a point, blended between the four nearest cells (r cover, g density, b type): the shapes are blocky, the
// shading and the shadows aren't.
vec4 cloudMapAt(vec2 xz) {
    vec2 p = xz / 12.0 - 0.5;
    vec2 i = floor(p), f = fract(p);
    float cells = CloudO.w;
    #define MAP(o) texelFetch(CloudSampler, ivec2(mod(i + o, cells)), 0)
    vec4 v = mix(mix(MAP(vec2(0.0)), MAP(vec2(1.0, 0.0)), f.x), mix(MAP(vec2(0.0, 1.0)), MAP(vec2(1.0)), f.x), f.y);
    #undef MAP
    return v;
}

// Rain thickens the clouds: more of each mass counts as cloud, and it is denser.
float rainDensity(float g) {
    return min(1.0, g + 0.3 * CloudX.x);
}

bool cloudCovered(vec4 m) {
    return m.r > 0.5 || m.g > mix(1.1, 0.3, CloudX.x);
}

// A cell's column. The type (a slow regional field, ShadeClouds) sets the kind of cloud: flat stratus sheets (0), ordinary
// cumulus (0.5), towering cumulus (1). Density sets how much of that it reaches.
void cloudColumn(float g, float type, out float top, out float bot) {
    float base = CloudP.y;
    float hs = mix(0.3, 1.9, type);
    top = base + 4.0 + floor(g * 9.0 * hs) * 2.0;
    bot = base - floor(g * 3.0 * mix(0.5, 1.5, type) + 2.0 * CloudX.x) * 2.0;
}

// The colour of a cloud face at h: vanilla's face tones, darker low down and in thick clouds, with the light of the time of day.
vec3 cloudShade(vec3 h, vec3 d, vec3 sunW, bool side, int axis, vec2 stepv, float tin, float g, float top, float bot, vec3 sky) {
    vec3 n;
    if (side) n = axis == 0 ? vec3(-stepv.x, 0.0, 0.0) : vec3(0.0, 0.0, -stepv.y);
    else if (tin <= 1e-3 && h.y > bot && h.y < top) n = vec3(0.0, 1.0, 0.0); // inside a cloud
    else n = vec3(0.0, d.y > 0.0 ? -1.0 : 1.0, 0.0);
    // Height and thickness from the smooth density, so tones grade across neighbouring columns instead of stepping per cell.
    vec4 sm = cloudMapAt(h.xz);
    g = rainDensity(sm.g);
    float hs = mix(0.3, 1.9, sm.b);
    float sTop = CloudP.y + 4.0 + g * 18.0 * hs, sBot = CloudP.y - g * 6.0 * mix(0.5, 1.5, sm.b) - 4.0 * CloudX.x;
    float hf = clamp((h.y - sBot) / max(sTop - sBot, 1.0), 0.0, 1.0);
    float face = n.y > 0.5 ? 1.0 : (n.y < -0.5 ? 0.66 : (abs(n.x) > 0.5 ? 0.9 : 0.82));
    float amb = face * mix(0.68, 1.0, hf) * mix(1.0, 0.8, g * (1.0 - hf));
    float day = CloudP.w;
    // Near the horizon the sun's light turns orange and the shaded sides pick up a rosy cast, as at dawn and dusk.
    float low = (1.0 - smoothstep(0.0, 0.35, sunW.y)) * smoothstep(-0.12, 0.02, sunW.y);
    // A vivid one (CloudX.y, rare) takes its palette's colours (ShadePass.VIVID): the light on top, the undersides' colour below,
    // and lights the clouds harder.
    float viv = CloudX.y;
    vec3 dusk = mix(vec3(1.0, 0.55, 0.28), mix(VividL.rgb, VividS.rgb, (1.0 - hf) * 0.6), viv);
    vec3 sunC = mix(mix(vec3(0.55, 0.62, 0.85), Tint.rgb, day), dusk * (1.0 + 0.35 * viv), low * 0.85);
    float diff = max(dot(n, sunW), 0.0);
    // Highlights: the sunlit faces and the rims of the tops glow in the light's colour.
    float rim = smoothstep(sTop - 4.0, sTop, h.y) * (n.y > 0.5 ? 0.3 : 1.0);
    vec3 light = sunC * (diff * (0.32 + 0.4 * rim + 0.45 * low) * mix(0.35, 1.0, max(day, low)));
    // Silver lining: thin clouds toward the sun light up from behind.
    float behind = pow(max(dot(d, sunW), 0.0), 6.0) * (1.0 - g) * mix(0.25, 0.8, max(day, low));
    // Rain: the sun's highlights go, and the clouds go a heavy grey, darkest underneath.
    float wet = CloudX.x;
    light *= 1.0 - 0.8 * wet;
    behind *= 1.0 - wet;
    vec3 col = CloudCol.rgb * (amb * mix(vec3(1.0), mix(vec3(1.0, 0.8, 0.78), VividS.rgb, viv), low * 0.6) * mix(1.0, mix(0.45, 0.75, hf), wet) + light + sunC * behind);
    // A little haze toward the sky with distance, so far clouds sit in the air rather than on it.
    return mix(col, sky, smoothstep(0.0, CloudP.z, tin) * 0.35);
}

// Stylized clouds: the cloud map as blocky columns, marched cell by cell (12 blocks) along the view ray. Each cloud cell's column
// grows with its density (thick masses tower, edges stay thin), in 2-block steps so it stays blocky. Returns colour and coverage.
vec4 clouds(vec3 dirV, float tLimit, vec3 sky) {
    vec3 d = normalize((ViewInv * vec4(dirV, 0.0)).xyz);
    vec3 sunW = normalize((ViewInv * vec4(Sun.xyz, 0.0)).xyz);
    float base = CloudP.y;
    float cells = CloudO.w;
    const float CELL = 12.0;
    float yMin = base - 14.0, yMax = base + 40.0;
    vec3 o = vec3(CloudO.x, CloudO.y, CloudO.z);
    // The slab the columns can occupy.
    float t0, t1;
    if (abs(d.y) < 1e-5) {
        if (o.y < yMin || o.y > yMax) return vec4(0.0);
        t0 = 0.0; t1 = 1e9;
    } else {
        float ta = (yMin - o.y) / d.y, tb = (yMax - o.y) / d.y;
        t0 = max(min(ta, tb), 0.0); t1 = max(ta, tb);
    }
    t1 = min(t1, tLimit);
    if (t0 >= t1) return vec4(0.0);

    vec2 q = o.xz + d.xz * t0;
    vec2 cell = floor(q / CELL);
    vec2 stepv = vec2(d.x >= 0.0 ? 1.0 : -1.0, d.z >= 0.0 ? 1.0 : -1.0);
    vec2 tDelta = vec2(abs(d.x) > 1e-6 ? CELL / abs(d.x) : 1e9, abs(d.z) > 1e-6 ? CELL / abs(d.z) : 1e9);
    vec2 nextB = (cell + max(stepv, 0.0)) * CELL;
    vec2 tNext = vec2(abs(d.x) > 1e-6 ? t0 + (nextB.x - q.x) / d.x : 1e9, abs(d.z) > 1e-6 ? t0 + (nextB.y - q.y) / d.z : 1e9);
    float tCur = t0;
    int axis = -1;
    // Coverage from optical depth: the ray's path through every cloud column it crosses, weighted by density. A ray grazing an
    // edge sees a sliver and stays see-through; one through the heart of a mass goes opaque. Colour is the first face hit.
    vec3 col = vec3(0.0);
    float tau = 0.0, tFirst = -1.0;
    for (int i = 0; i < 128; i++) {
        float tOut = min(min(tNext.x, tNext.y), t1);
        ivec2 c = ivec2(mod(cell, cells));
        vec4 m = texelFetch(CloudSampler, c, 0);
        if (cloudCovered(m)) {
            float g = rainDensity(m.g);
            float top, bot;
            cloudColumn(g, m.b, top, bot);
            float tin, tout;
            if (abs(d.y) < 1e-5) {
                tin = tCur; tout = (o.y >= bot && o.y <= top) ? tOut : -1.0;
            } else {
                float tb = (bot - o.y) / d.y, tt = (top - o.y) / d.y;
                tin = max(tCur, min(tb, tt)); tout = min(tOut, max(tb, tt));
            }
            if (tin <= tout) {
                if (tFirst < 0.0) {
                    tFirst = tin;
                    col = cloudShade(o + d * tin, d, sunW, tin <= tCur + 1e-4 && axis >= 0, axis, stepv, tin, g, top, bot, sky);
                }
                tau += (tout - tin) * mix(0.1, 0.4, g) * (1.0 + CloudX.x);
                if (tau > 5.0) break;
            }
        }
        if (tOut >= t1) break;
        tCur = tOut;
        if (tNext.x < tNext.y) { cell.x += stepv.x; tNext.x += tDelta.x; axis = 0; }
        else { cell.y += stepv.y; tNext.y += tDelta.y; axis = 1; }
    }
    if (tFirst < 0.0) return vec4(0.0);
    float fade = 1.0 - smoothstep(CloudP.z * 0.6, CloudP.z, tFirst);
    return vec4(col, (1.0 - exp(-tau)) * fade * clamp(CloudCol.a * 1.25, 0.0, 1.0));
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
    bool hand = hd > 0.0 && hd != d0;
    if (hand) d0 = 0.0;
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
    // Cloud shadows: where the sun's ray from this point passes through a cloud, the direct light dims (soft-edged, from the
    // blended map). Drifts with the clouds; only below them, and only where the sun lights the surface.
    if (CloudP.x > 0.5 && d0 > 0.0 && !hand && Sun.w > 0.0) {
        vec3 pw = (ViewInv * vec4(viewPosAt(texCoord, d0), 0.0)).xyz + CloudO.xyz;
        vec3 sunW = normalize((ViewInv * vec4(Sun.xyz, 0.0)).xyz);
        if (sunW.y > 0.05 && pw.y < CloudP.y) {
            vec4 m = cloudMapAt(pw.xz + sunW.xz * ((CloudP.y + 2.0 - pw.y) / sunW.y));
            float cover = max(smoothstep(0.3, 0.7, m.r), smoothstep(mix(1.1, 0.3, CloudX.x), mix(1.2, 0.4, CloudX.x), m.g));
            float direct = mix(0.35, 1.0, lit * (1.0 - shadow));
            col *= mix(1.0, 0.72, cover * Sun.w * direct * (1.0 - CloudX.x));
        }
    }
    // Light colour: warm low sun, white noon, cool moon, only where the light reaches (facing it and not in shadow).
    col *= mix(vec3(1.0), Tint.rgb / dot(Tint.rgb, vec3(0.3333)), Tint.a * lit * (1.0 - shadow)) * (1.0 + 0.12 * lit * (1.0 - shadow) * Tint.a);

    // Coloured block light: a hue shift (brightness kept) plus a faint glow, where the sun isn't the main light.
    if (d0 > 0.0 && LightP.x > 0.5 && LightP.y > 0.0) {
        vec3 p = viewPosAt(texCoord, d0);
        vec4 bl = blockLight(p, normalAt(texCoord, p, texel));
        if (bl.a > 0.0) {
            float s = LightP.y * bl.a * (1.0 - 0.75 * lit * (1.0 - shadow) * Sun.w);
            float l = dot(bl.rgb, vec3(0.2126, 0.7152, 0.0722));
            col *= mix(vec3(1.0), bl.rgb / max(l, 0.2), 0.45 * s);
            col += bl.rgb * col * (0.25 * s * s);
        }
    }

    // Stylized clouds in front of the sky and of terrain beyond them (never over the hand).
    if (CloudP.x > 0.5 && !hand && LightP.z < 0.5) {
        vec3 dirV = viewPosAt(texCoord, 0.5);
        float tLimit = CloudP.z;
        if (d0 > 0.0) tLimit = min(tLimit, length(viewPosAt(texCoord, d0)));
        vec4 cl = clouds(normalize(dirV), tLimit, src.rgb);
        col = mix(col, cl.rgb, cl.a);
    }

    // A vivid sunrise or sunset: the sky toward the horizon goes richer (saturation up, warmer), strongest near the sun.
    if (CloudX.y > 0.0 && d0 <= 0.0 && !hand && LightP.z < 0.5) {
        vec3 dW = normalize((ViewInv * vec4(viewPosAt(texCoord, 0.5), 0.0)).xyz);
        vec3 sW = normalize((ViewInv * vec4(Sun.xyz, 0.0)).xyz);
        float lowSun = (1.0 - smoothstep(0.0, 0.3, sW.y)) * smoothstep(-0.15, 0.0, sW.y);
        float k = CloudX.y * lowSun * (1.0 - smoothstep(0.0, 0.6, dW.y)) * mix(0.5, 1.0, max(dot(dW, sW), 0.0));
        float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
        col = mix(col, max(vec3(lum) + (col - vec3(lum)) * 1.9, 0.0) * VividK.rgb, k);
    }

    // Under water: murky (washed toward a dim blue-green, colours muted) and fogged with distance. Not over the hand.
    if (LightP.z > 0.5 && !hand) {
        float dist = d0 > 0.0 ? length(viewPosAt(texCoord, d0)) : 1e4;
        vec3 water = vec3(0.07, 0.26, 0.34) * mix(0.2, 1.0, LightP.w);
        float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
        col = mix(col, vec3(lum), 0.35) * mix(vec3(1.0), vec3(0.62, 0.86, 0.92), 0.8);
        col = mix(col, water, 1.0 - exp(-dist / 14.0));
    }

    col = tonemap(col, Params0.z);
    fragColor = vec4(col, src.a);
}
