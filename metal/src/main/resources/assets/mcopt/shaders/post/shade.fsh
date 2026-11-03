#version 330
#extension GL_ARB_separate_shader_objects : require

// mcopt shade, stage 1: screen-space sun shadows, ambient occlusion and a gentle grade on the finished level image. The shadow and
// ao come from shade_occ.fsh (usually at half res), upsampled here by depth so they don't bleed across edges. Reads the main target's
// colour and (reverse-Z) depth. Written for mcopt; see docs/SHADERS.md.

uniform sampler2D ColorSampler;
uniform sampler2D DepthSampler;
uniform sampler2D OccSampler;
uniform sampler2D CloudSampler; // the cloud cell map: r cloud, g density (ShadeClouds)
uniform sampler2D SkyMapSampler; // the sky map (ShadeSky.setMap): top half cover/2, darkness, cirrus, veil; bottom half fog, fog top, 0, 1
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
    vec4 CloudX;   // x rain 0..1, y vivid sunrise/sunset (1, rare), z god ray shafts, w their strength
    vec4 VividL;   // vivid palette: sunlight on the clouds
    vec4 VividS;   // vivid palette: shaded undersides
    vec4 VividK;   // vivid palette: sky tint toward the horizon
    vec4 SkyA;     // the sky's weather (ShadeSky): x cumulus cover (1 the map as vanilla, 2 overcast), y cumulonimbus, z cirrus, w cirrostratus
    vec4 SkyB;     // x fog 0..1, y fog top (world y), zw the camera's place on the high cloud layer (blocks, wrapped at 4096)
    vec4 SkyC;     // xy the camera's world x, z (wrapped at 4096), z seconds (wrapping hourly)
    vec4 SkyD;     // x sky map on, y its cells per side, z cell size (blocks), w storm list on
    vec4 SkyE;     // xy camera - map origin - wind drift (blocks), z storm count, w unused
    vec4 SunT;     // xyz the true sun direction in world space (not negated at night), w its elevation (sin)
};

// The light-emitting blocks nearest the camera (ShadeLights): view-space centre and emission 0..1, and hue.
layout(std140) uniform ShadeLights {
    vec4 LightPos[32];
    vec4 LightCol[32];
    vec4 RayA[12];   // god ray shafts (ShadeRays): top end (canopy gap), view space; w width (blocks)
    vec4 RayB[12];   // bottom end (ground); w brightness
    vec4 StormA[16]; // storm list (ShadeSky.setStorms): xy centre relative to the camera (blocks), z core radius, w top (world y)
    vec4 StormB[16]; // x anvil radius, yz anvil shift (blocks), w stage 0..2
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

// The view ray's direction through uv: two points on it (5 blocks out and the near plane), so the eye's own offset drops out. The
// level projection carries the view bob's translation, which puts the eye a few centimetres off the origin; a direction from the
// origin to a near point swung with it, and the clouds and sky bobbed far more than the world.
vec3 viewDirAt(vec2 uv) {
    return normalize(viewPosAt(uv, 0.01) - viewPosAt(uv, 1.0));
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

// The sky at a point (rel: blocks from the camera, horizontally): cover (0..2), darkness (0..1), cirrus, veil. From the sky map,
// blended between the four nearest cells (clamped at its edge) when there is one, else the scalar sky.
vec4 skyAt(vec2 rel) {
    if (SkyD.x < 0.5) return vec4(SkyA.x, 0.0, SkyA.z, SkyA.w);
    vec2 p = (rel + SkyE.xy) / SkyD.z - 0.5;
    vec2 i = floor(p), f = fract(p);
    int n = int(SkyD.y + 0.5) - 1;
    ivec2 a = clamp(ivec2(i), 0, n), b = clamp(ivec2(i) + 1, 0, n);
    vec4 v = mix(mix(texelFetch(SkyMapSampler, a, 0), texelFetch(SkyMapSampler, ivec2(b.x, a.y), 0), f.x),
        mix(texelFetch(SkyMapSampler, ivec2(a.x, b.y), 0), texelFetch(SkyMapSampler, b, 0), f.x), f.y);
    return vec4(v.x * 2.0, v.y, v.z, v.w);
}

// The fog at a point: density 0..1 and top (world y).
vec2 fogAt(vec2 rel) {
    if (SkyD.x < 0.5) return vec2(SkyB.x, SkyB.y);
    vec2 p = (rel + SkyE.xy) / SkyD.z - 0.5;
    vec2 i = floor(p), f = fract(p);
    int n = int(SkyD.y + 0.5) - 1;
    ivec2 a = clamp(ivec2(i), 0, n) + ivec2(0, n + 1), b = clamp(ivec2(i) + 1, 0, n) + ivec2(0, n + 1);
    vec4 v = mix(mix(texelFetch(SkyMapSampler, a, 0), texelFetch(SkyMapSampler, ivec2(b.x, a.y), 0), f.x),
        mix(texelFetch(SkyMapSampler, ivec2(a.x, b.y), 0), texelFetch(SkyMapSampler, b, 0), f.x), f.y);
    return vec2(v.x, v.y * 448.0 - 64.0);
}

// Rain thickens the clouds: more of each mass counts as cloud, and it is denser. So does cover past 1 (overcast) and darkness.
float thick(vec4 sky) {
    return max(CloudX.x, max(sky.x - 1.0, sky.y));
}

// A rain deck is deep everywhere, not only over the map's dense hearts: thickness also sets a floor under the density.
float rainDensity(float g, vec4 sky) {
    float t = thick(sky);
    return min(1.0, max(g + 0.3 * t, 0.8 * t));
}

// Cover under 1 keeps only the denser hearts of the map's clouds (fewer, smaller cumulus); over 1 fills in around them.
bool cloudCovered(vec4 m, vec4 sky) {
    return (m.r > 0.5 && (sky.x >= 1.0 || m.g > 1.0 - sky.x)) || m.g > mix(1.1, 0.3, thick(sky));
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Cumulonimbus (SkyA.y): one storm at most per 32x32 cells of the map, at a random spot, the first ones to grow as SkyA.y rises.
// A tower (a core rising up to ~130 blocks, with a skirt of lower cloud around it) and, once mature, an anvil: a flat slab spreading
// from its top, carried downwind (+x). Returns the tower's column (x bottom, y top) and the anvil's (z bottom, w top); top below
// bottom where there's none.
vec4 stormAt(vec2 cell) {
    vec4 none = vec4(0.0, -1.0, 0.0, -1.0);
    if (SkyA.y <= 0.0) return none;
    vec2 c = mod(cell, CloudO.w);
    vec2 sc = floor(c / 32.0);
    float s = hash12(sc + 17.0);
    if (s > 0.7) return none;
    float grow = clamp((SkyA.y - 0.7 * s) / 0.5, 0.0, 1.0);
    if (grow <= 0.0) return none;
    // (centred so the widest anvil, carried downwind, still fits in its 32 cells)
    vec2 ctr = sc * 32.0 + 14.0 + 4.0 * vec2(hash12(sc + 3.1), hash12(sc + 7.7));
    float core = 3.5 + 1.5 * hash12(sc + 11.3);
    float r = length(c + 0.5 - ctr) + 0.8 * (hash12(c) - 0.5);
    float base = CloudP.y;
    float peak = base + 4.0 + floor(grow * (70.0 + 50.0 * hash12(sc + 5.9)) / 2.0) * 2.0;
    vec4 o = none;
    if (r < core) {
        o.x = base - 6.0 - floor(grow * 4.0 / 2.0) * 2.0;
        o.y = base + 4.0 + floor((peak - base - 4.0) * (1.0 - 0.35 * (r / core) * (r / core)) / 2.0) * 2.0;
    } else if (r < core + 3.0) {
        o.x = base - 4.0;
        o.y = base + 4.0 + floor(grow * 30.0 * (1.0 - (r - core) / 3.0) / 2.0) * 2.0;
    }
    float mature = smoothstep(0.55, 1.0, grow);
    float ar = core + mature * (4.0 + 3.0 * hash12(sc + 13.7));
    float ra = length(c + 0.5 - ctr - vec2(1.5 * mature, 0.0)) + 0.8 * (hash12(c + 1.0) - 0.5);
    if (mature > 0.0 && ra < ar) {
        o.w = peak + 2.0;
        o.z = o.w - 4.0 - floor(10.0 * (1.0 - ra / ar) / 2.0) * 2.0;
    }
    return o;
}

// The storms of a list (ShadeSky.setStorms) at a 12-block cell (centre rel to the camera), in stormAt's layout: the union over the
// list. Each is a tower with a skirt and, once mature, an anvil carried by its shift; a stage past 1 shrinks it away.
vec4 stormList(vec2 rel, vec2 cell) {
    vec4 o = vec4(0.0, -1.0, 0.0, -1.0);
    int count = int(SkyE.z + 0.5);
    vec2 c = mod(cell, CloudO.w);
    float j0 = 9.6 * (hash12(c) - 0.5), j1 = 9.6 * (hash12(c + 1.0) - 0.5);
    float base = CloudP.y;
    for (int i = 0; i < 16; i++) {
        if (i >= count) break;
        vec4 a = StormA[i], b = StormB[i];
        vec2 dc = rel - a.xy;
        float reach = max(a.z * 1.6, b.x) + length(b.yz) + 6.0;
        if (dot(dc, dc) > reach * reach) continue;
        float growth = min(b.w, 1.0), fade = clamp(b.w - 1.0, 0.0, 1.0);
        float core = a.z * (1.0 - 0.4 * fade);
        float gr = growth * (1.0 - 0.6 * fade);
        float peak = base + 4.0 + floor(max(a.w - base - 4.0, 0.0) * gr / 2.0) * 2.0;
        float r = length(dc) + j0;
        vec2 t = vec2(0.0, -1.0);
        if (r < core) {
            t.x = base - 6.0 - floor(growth * 4.0 / 2.0) * 2.0;
            t.y = base + 4.0 + floor((peak - base - 4.0) * (1.0 - 0.35 * (r / core) * (r / core)) / 2.0) * 2.0;
        } else if (r < core * 1.6) {
            t.x = base - 4.0;
            t.y = base + 4.0 + floor(gr * 30.0 * (1.0 - (r - core) / (core * 0.6)) / 2.0) * 2.0;
        }
        if (t.y > t.x) {
            if (o.y > o.x) { o.x = min(o.x, t.x); o.y = max(o.y, t.y); }
            else o.xy = t;
        }
        float mature = smoothstep(0.55, 1.0, growth);
        float ar = (a.z + (b.x - a.z) * mature) * (1.0 - fade);
        float ra = length(rel - a.xy - b.yz * mature) + j1;
        if (mature > 0.0 && ar > 1.0 && ra < ar) {
            float top = peak + 2.0;
            float bot = top - 4.0 - floor(10.0 * (1.0 - ra / ar) / 2.0) * 2.0;
            if (o.w > o.z) { o.z = min(o.z, bot); o.w = max(o.w, top); }
            else o.zw = vec2(bot, top);
        }
    }
    return o;
}

// The ray o + d t through the slab lo..hi, within [tCur, tOut]; false if it misses.
bool slab(vec3 o, vec3 d, float lo, float hi, float tCur, float tOut, out float tin, out float tout) {
    if (abs(d.y) < 1e-5) {
        tin = tCur; tout = (o.y >= lo && o.y <= hi) ? tOut : -1.0;
    } else {
        float ta = (lo - o.y) / d.y, tb = (hi - o.y) / d.y;
        tin = max(tCur, min(ta, tb)); tout = min(tOut, max(ta, tb));
    }
    return hi > lo && tin <= tout;
}

// A cell's column. The type (a slow regional field, ShadeClouds) sets the kind of cloud: flat stratus sheets (0), ordinary
// cumulus (0.5), towering cumulus (1). Density sets how much of that it reaches.
void cloudColumn(float g, float type, out float top, out float bot) {
    float base = CloudP.y;
    float hs = mix(0.3, 1.9, type);
    top = base + 4.0 + floor(g * 9.0 * hs) * 2.0;
    bot = base - floor(g * 3.0 * mix(0.5, 1.5, type) + 2.0 * CloudX.x + 4.0 * max(g - 0.6, 0.0)) * 2.0;
}

// How low the true sun is: 1 around the horizon, 0 once it is high (or long down), so the dusk work costs nothing then.
float duskK() {
    return (1.0 - smoothstep(0.1, 0.4, SunT.w)) * smoothstep(-0.3, -0.12, SunT.w);
}

// The direct sunlight reaching height y: the sun's light through the air, orange then red then gone as it sinks (the longer path
// takes the blue first), and higher up the sun sets later (a fudge for the earth's curve), so cirrus and storm tops stay lit
// after the low cumulus have gone grey. A vivid one (CloudX.y) takes its palette's light.
vec3 sunlightAt(float y) {
    float e = SunT.w + 0.0006 * max(y - CloudP.y, 0.0);
    float air = min(1.0 / (max(e, 0.0) + 0.05), 20.0);
    float up = smoothstep(-0.07, 0.02, e);
    vec3 t = exp(-vec3(0.06, 0.16, 0.42) * air) * up;
    return mix(t * 1.15, VividL.rgb * up * clamp(t.r * 1.6, 0.0, 1.0), CloudX.y);
}

// Light scattered by a cloud toward the viewer, cs the cosine to the sun: a strong forward lobe and a weak back one (Henyey-Greenstein,
// g 0.6 and -0.15), scaled to 0.7 looking straight into the sun.
float phaseHG(float cs) {
    float f = 0.64 / pow(1.36 - 1.2 * cs, 1.5), b = 0.9775 / pow(1.0225 + 0.3 * cs, 1.5);
    return (0.7 * f + 0.3 * b) * 0.1;
}

// The colour of a cloud face at h: vanilla's face tones, darker low down and in thick clouds, with the light of the time of day.
vec3 cloudShade(vec3 h, vec3 d, vec3 sunW, bool side, int axis, vec2 stepv, float tin, float g, float top, float bot, vec3 sky, vec4 sk, bool storm) {
    vec3 n;
    if (side) n = axis == 0 ? vec3(-stepv.x, 0.0, 0.0) : vec3(0.0, 0.0, -stepv.y);
    else if (tin <= 1e-3 && h.y > bot && h.y < top) n = vec3(0.0, 1.0, 0.0); // inside a cloud
    else n = vec3(0.0, d.y > 0.0 ? -1.0 : 1.0, 0.0);
    // Height and thickness from the smooth density, so tones grade across neighbouring columns instead of stepping per cell.
    vec4 sm = cloudMapAt(h.xz);
    g = rainDensity(sm.g, sk);
    float hs = mix(0.3, 1.9, sm.b);
    float sTop = CloudP.y + 4.0 + g * 18.0 * hs, sBot = CloudP.y - g * 6.0 * mix(0.5, 1.5, sm.b) - 4.0 * CloudX.x;
    // A storm's tower and anvil are shaded over their own height: a dark base, tops bright in the sun even in the rain.
    if (storm) { sTop = top; sBot = min(bot, CloudP.y - 6.0); g = 1.0; }
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
    float wet = max(CloudX.x, max(0.6 * (sk.x - 1.0), sk.y)) * (storm ? 1.0 - 0.7 * hf : 1.0);
    if (storm) amb *= mix(0.55, 1.05, hf);
    light *= 1.0 - 0.8 * wet;
    behind *= 1.0 - wet;
    vec3 am = amb * mix(vec3(1.0), mix(vec3(1.0, 0.8, 0.78), VividS.rgb, viv), low * 0.6) * mix(1.0, mix(0.45, 0.75, hf), wet);
    vec3 col = CloudCol.rgb * (am + light + sunC * behind);
    // Sunrise and sunset: the true sun's light at this height, shadowed by the cloud between it and the face, with scattered
    // light at the rims and the sky's colour in the shade. Only when the sun is low.
    float dk = duskK();
    if (dk > 0.001) {
        vec3 sd = SunT.xyz, sl = sunlightAt(h.y);
        // Three probes toward the sun (8, 20 and 40 blocks out, rising with it) add up the cloud in the way.
        vec2 hz = sd.xz / max(length(sd.xz), 1e-3);
        float rise = clamp(sd.y / max(length(sd.xz), 1e-3), -4.0, 4.0), od = 0.0;
        for (int i = 0; i < 3; i++) {
            float s = i == 0 ? 8.0 : (i == 1 ? 20.0 : 40.0), seg = i == 0 ? 10.0 : (i == 1 ? 14.0 : 24.0);
            vec2 pxz = h.xz + hz * s;
            float py = h.y + rise * s;
            vec4 pm = cloudMapAt(pxz);
            if (!cloudCovered(pm, sk)) continue;
            float pg = rainDensity(pm.g, sk);
            float pt = CloudP.y + 4.0 + pg * 18.0 * mix(0.3, 1.9, pm.b), pb = CloudP.y - pg * 6.0 * mix(0.5, 1.5, pm.b) - 4.0 * CloudX.x;
            od += pg * seg * 0.08 * smoothstep(pb - 1.0, pb + 3.0, py) * (1.0 - smoothstep(pt - 3.0, pt + 1.0, py));
        }
        float tr = exp(-od);
        float lam = clamp(dot(n, sd) * 0.75 + 0.25, 0.0, 1.0);
        // (thin edges facing away from the sun darken a little)
        float powder = mix(1.0, mix(0.8, 1.0, 1.0 - exp(-2.0 * g)), 1.0 - lam);
        vec3 alb = max(CloudCol.rgb, vec3(0.55));
        float dry = 1.0 - 0.8 * wet;
        vec3 dir = sl * alb * (lam * tr * powder * 0.95) * dry;
        vec3 rimL = sl * (phaseHG(dot(d, sd)) * 1.6 * (1.0 - g) * tr * (1.0 - wet));
        // The shade picks up the sky's colour, so dusk clouds aren't flat grey where the sun doesn't reach.
        vec3 ambD = mix(CloudCol.rgb * am, sky * am, 0.25);
        col = mix(col, ambD + dir + rimL, dk);
    }
    // A little haze toward the sky with distance, so far clouds sit in the air rather than on it.
    return mix(col, sky, smoothstep(0.0, CloudP.z, tin) * 0.35);
}

float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), f.x), mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0)), f.x), f.y);
}

float fbm(vec2 p) {
    return 0.5 * vnoise(p) + 0.3 * vnoise(p * 2.03 + 5.2) + 0.2 * vnoise(p * 4.01 + 9.7);
}

// The high cloud layer, ~190 blocks above the cumulus: cirrus (SkyA.z), long fibrous streaks along the wind with hooked ends, and
// cirrostratus (SkyA.w), a thin milky veil with a 22-degree halo around the sun or moon. Sampled in 4-block cells so it stays
// blocky like the rest. Returns colour and coverage; only for rays to the sky.
vec4 highClouds(vec3 d, vec3 sunW) {
    if (SkyD.x < 0.5 && SkyA.z + SkyA.w <= 0.003) return vec4(0.0);
    float h = CloudP.y + 190.0 - CloudO.y;
    if (h <= 0.0 || d.y < 0.01) return vec4(0.0);
    float t = h / d.y;
    vec4 sk = skyAt(d.xz * t);
    if (sk.z + sk.w <= 0.003) return vec4(0.0);
    vec2 p = floor((SkyB.zw + d.xz * t) / 4.0) * 4.0;
    float horizon = smoothstep(0.01, 0.12, d.y) * (1.0 - smoothstep(3000.0, 7000.0, t));
    // Streaks: noise stretched along the wind, bent by a slower field (the hooks), in patches.
    vec2 q = vec2(p.x / 260.0, (p.y + 40.0 * vnoise(p / 380.0)) / 34.0);
    float fib = fbm(q);
    float patches = smoothstep(0.35, 0.65, vnoise(p / 900.0 + 3.0));
    float cirrus = sk.z * smoothstep(0.5, 0.78, fib) * mix(0.3, 1.0, patches) * 0.8;
    float veil = sk.w * (0.32 + 0.14 * vnoise(p / 520.0) + 0.12 * smoothstep(0.55, 0.8, fib));
    float a = clamp(max(cirrus, veil) + 0.5 * min(cirrus, veil), 0.0, 0.9) * horizon;
    // Lit: white by day; high up, they catch the sunrise and sunset colours early and keep them after the sun is down.
    float day = CloudP.w;
    float low = (1.0 - smoothstep(0.0, 0.3, sunW.y)) * smoothstep(-0.22, 0.0, sunW.y);
    vec3 dusk = mix(vec3(1.0, 0.52, 0.34), VividL.rgb, CloudX.y);
    vec3 c = CloudCol.rgb * 1.05;
    c = mix(c, dusk * max(CloudCol.rgb, vec3(0.65)), low * 0.8);
    c += vec3(pow(max(dot(d, sunW), 0.0), 8.0) * 0.35 * max(day, low));
    // Sunrise and sunset: the light up there (it lasts longest at this height), forward-scattered toward the sun.
    float dk = duskK();
    if (dk > 0.001) {
        vec3 sl = sunlightAt(CloudP.y + 190.0);
        vec3 cD = mix(CloudCol.rgb * 1.05, max(CloudCol.rgb, vec3(0.65)) * sl * 1.1, clamp(sl.r, 0.0, 1.0) * 0.85);
        cD += sl * phaseHG(dot(d, SunT.xyz)) * 0.5;
        c = mix(c, cD, dk);
    }
    return vec4(c * (1.0 - 0.45 * CloudX.x), a);
}

// The halo a cirrostratus veil makes around the sun or moon: a ring 22 degrees out, reddish on its inner edge.
vec3 halo(vec3 d, vec3 sunW) {
    float veil = skyAt(vec2(0.0)).w;
    if (veil <= 0.003) return vec3(0.0);
    float ang = acos(clamp(dot(d, sunW), -1.0, 1.0));
    vec3 ring = exp(-pow((vec3(ang) - vec3(0.378, 0.384, 0.392)) / 0.016, vec3(2.0)));
    return ring * veil * 0.22 * mix(0.3, 1.0, CloudP.w) * (1.0 - CloudX.x) * smoothstep(0.0, 0.1, d.y);
}

// Fog (SkyB.x): a layer filling the low ground up to SkyB.y (its top swelling and sinking a few blocks as it drifts), thinning out
// above. The ray's optical depth through it in closed form: G is the density's integral over height.
float fogG(float y, float top) {
    const float H = 5.0;
    return y <= top ? y : top + H * (1.0 - exp(-(y - top) / H));
}

vec3 fog(vec3 col, vec3 d, float dist, vec3 sunW) {
    float len = min(dist, 3000.0);
    float y0 = CloudO.y;
    // With a sky map the ray is split in three, each part with the fog of the ground under its middle.
    int segs = SkyD.x > 0.5 ? 3 : 1;
    float od = 0.0;
    for (int i = 0; i < 3; i++) {
        if (i >= segs) break;
        float s0 = len * float(i) / float(segs), s1 = len * float(i + 1) / float(segs);
        vec2 mid = d.xz * min(segs == 1 ? len : 0.5 * (s0 + s1), 300.0);
        vec2 f = fogAt(mid);
        float top = f.y + 4.0 * (vnoise((SkyC.xy + mid) / 48.0 + SkyC.z * 0.01) - 0.5);
        float ya = y0 + d.y * s0, yb = y0 + d.y * s1, seg = s1 - s0;
        od += f.x * (abs(yb - ya) < 1e-3 ? seg * (fogG(ya + 1e-3, top) - fogG(ya, top)) / 1e-3 : seg * (fogG(yb, top) - fogG(ya, top)) / (yb - ya));
    }
    float a = 1.0 - exp(-od * 0.08);
    // Grey-white with the sky's light (vanilla's cloud colour: dim at night, greyer in rain), glowing toward the sun.
    vec3 fc = CloudCol.rgb * 0.88;
    fc *= mix(vec3(1.0), Tint.rgb / max(dot(Tint.rgb, vec3(0.3333)), 0.05), 0.3 * Sun.w);
    fc += Tint.rgb * pow(max(dot(d, sunW), 0.0), 6.0) * 0.3 * Sun.w;
    return mix(col, fc, a);
}

// Forest god rays: the glow along the view ray (from the eye, up to the scene at tMax) from each shaft, by how close the ray passes
// to the shaft's axis (closest points between the two segments), faded toward the shaft's ends.
float godRays(vec3 dir, float tMax) {
    int count = int(CloudX.z + 0.5);
    float sum = 0.0;
    for (int i = 0; i < 12; i++) {
        if (i >= count) break;
        vec3 a = RayA[i].xyz, ab = RayB[i].xyz - a;
        float ee = dot(ab, ab), f = dot(ab, dir), c = dot(dir, -a), bb = dot(ab, -a);
        float den = ee - f * f;
        float s = den > 1e-4 ? clamp((f * c - bb) / den, 0.0, 1.0) : 0.0;
        float t = clamp(f * s + c, 0.0, tMax);
        s = clamp((f * t - bb) / max(ee, 1e-4), 0.0, 1.0);
        float d = length(dir * t - (a + ab * s));
        float w = RayA[i].w;
        float ends = smoothstep(0.0, 0.12, s) * (1.0 - smoothstep(0.75, 1.0, s));
        // Shafts right in front of the camera are thinned out, so walking through one doesn't wash out the view.
        float near = smoothstep(1.0, 4.0, t);
        sum += (1.0 - smoothstep(0.15 * w, w, d)) * ends * near * RayB[i].w;
    }
    return sum;
}

// Stylized clouds: the cloud map as blocky columns, marched cell by cell (12 blocks) along the view ray. Each cloud cell's column
// grows with its density (thick masses tower, edges stay thin), in 2-block steps so it stays blocky. Returns colour and coverage.
vec4 clouds(vec3 dirV, float tLimit, vec3 sky) {
    vec3 d = normalize((ViewInv * vec4(dirV, 0.0)).xyz);
    vec3 sunW = normalize((ViewInv * vec4(Sun.xyz, 0.0)).xyz);
    float base = CloudP.y;
    float cells = CloudO.w;
    const float CELL = 12.0;
    float yMin = base - 20.0, yMax = base + ((SkyD.w > 0.5 ? SkyE.z > 0.0 : SkyA.y > 0.0) ? 136.0 : 40.0);
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
        vec2 rel = (cell + 0.5) * CELL - CloudO.xz;
        vec4 sk = skyAt(rel);
        bool has = cloudCovered(m, sk);
        float g = rainDensity(m.g, sk), top = 0.0, bot = 0.0;
        if (has) cloudColumn(g, m.b, top, bot);
        // A storm's tower joins the cumulus column; its anvil is a slab of its own above.
        vec4 st = SkyD.w > 0.5 ? stormList(rel, cell) : stormAt(cell);
        bool storm = st.y > st.x;
        if (storm) {
            bot = has ? min(bot, st.x) : st.x;
            top = has ? max(top, st.y) : st.y;
            g = 1.0;
            has = true;
        }
        float tin, tout;
        for (int k = 0; k < 2; k++) {
            bool anvil = k == 1;
            if (anvil ? !slab(o, d, st.z, st.w, tCur, tOut, tin, tout) : !(has && slab(o, d, bot, top, tCur, tOut, tin, tout))) continue;
            if (tFirst < 0.0 || tin < tFirst) {
                tFirst = tin;
                col = cloudShade(o + d * tin, d, sunW, tin <= tCur + 1e-4 && axis >= 0, axis, stepv, tin, anvil ? 1.0 : g,
                    anvil ? st.w : top, anvil ? st.z : bot, sky, sk, storm || anvil);
            }
            tau += (tout - tin) * mix(0.1, 0.4, anvil ? 0.8 : g) * (1.0 + CloudX.x);
        }
        if (tau > 5.0) break;
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
            vec2 at = pw.xz + sunW.xz * ((CloudP.y + 2.0 - pw.y) / sunW.y);
            vec4 m = cloudMapAt(at);
            vec4 sk = skyAt(at - CloudO.xz);
            float th = thick(sk);
            float keep = sk.x >= 1.0 ? 1.0 : smoothstep(0.9 - sk.x, 1.1 - sk.x, m.g);
            float cover = max(smoothstep(0.3, 0.7, m.r) * keep, smoothstep(mix(1.1, 0.3, th), mix(1.2, 0.4, th), m.g));
            vec2 sc = floor(at / 12.0);
            vec4 st = SkyD.w > 0.5 ? stormList((sc + 0.5) * 12.0 - CloudO.xz, sc) : stormAt(sc);
            if (st.y > st.x) cover = 1.0;
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

    // The high layer and its halo, on the sky only (behind everything).
    if (CloudP.x > 0.5 && d0 <= 0.0 && !hand && LightP.z < 0.5) {
        vec3 dW = normalize((ViewInv * vec4(viewDirAt(texCoord), 0.0)).xyz);
        vec3 sW = normalize((ViewInv * vec4(Sun.xyz, 0.0)).xyz);
        vec4 hc = highClouds(dW, sW);
        col = mix(col, hc.rgb, hc.a) + halo(dW, sW);
    }

    // Stylized clouds in front of the sky and of terrain beyond them (never over the hand).
    if (CloudP.x > 0.5 && !hand && LightP.z < 0.5) {
        vec3 dirV = viewDirAt(texCoord);
        float tLimit = CloudP.z;
        if (d0 > 0.0) tLimit = min(tLimit, length(viewPosAt(texCoord, d0)));
        vec4 cl = clouds(dirV, tLimit, src.rgb);
        col = mix(col, cl.rgb, cl.a);
    }

    // Fog, in front of everything but the hand (and not under water).
    if ((SkyB.x > 0.003 || SkyD.x > 0.5) && !hand && LightP.z < 0.5) {
        vec3 dW = normalize((ViewInv * vec4(viewDirAt(texCoord), 0.0)).xyz);
        vec3 sW = normalize((ViewInv * vec4(Sun.xyz, 0.0)).xyz);
        col = fog(col, dW, d0 > 0.0 ? length(viewPosAt(texCoord, d0)) : 1e4, sW);
    }

    // Forest god rays: warm sunlight added along the shafts, in front of whatever is behind them.
    if (CloudX.z > 0.5 && CloudX.w > 0.0 && !hand && LightP.z < 0.5) {
        vec3 dirV = viewDirAt(texCoord);
        float tMax = d0 > 0.0 ? length(viewPosAt(texCoord, d0)) : 1e4;
        float g = godRays(dirV, tMax);
        // Brighter looking toward the sun (light scattered forward), and in the sun's colour.
        float fwd = mix(0.45, 1.0, pow(max(dot(dirV, normalize(Sun.xyz)), 0.0), 3.0));
        col += Tint.rgb * vec3(1.0, 0.93, 0.78) * min(g, 1.5) * 0.22 * CloudX.w * fwd;
    }

    // A vivid sunrise or sunset: the sky toward the horizon goes richer (saturation up, warmer), strongest near the sun.
    if (CloudX.y > 0.0 && d0 <= 0.0 && !hand && LightP.z < 0.5) {
        vec3 dW = normalize((ViewInv * vec4(viewDirAt(texCoord), 0.0)).xyz);
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
