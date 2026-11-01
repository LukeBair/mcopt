# Shaders on the Metal path

## How to test

Run: `./gradlew :metal:runClient -PmcoptArgs="-Dmcopt.shade=true"` (then pick a world), or put `mcopt.shade=true` on its own line in
`config/mcopt.properties`. That one switch turns on everything: the sun shadow map (and `mcopt.own`, which it draws from), screen-space contact
shadows, ambient occlusion, warm sun / cool shade / moon colour by time of day, and a filmic grade. Without it nothing extra runs and the picture
is vanilla's. Add `-Dmcopt.shade=false` on the command line to override a config line.

In game: Video Settings > **mcopt Shaders...** (or Mod Menu's configure button) has every setting below as a slider that applies live,
an on/off switch, a debug view picker and Reset to defaults. Closing the screen writes the values to `config/mcopt.properties`.
Shadow map resolution (`mcopt.shade.mapRes`) is launch-only.
`mcopt.shade.mapEvery` (default 6, "Shadow refresh" in the menu): the shadow map is reused between redraws, and redrawn when the
camera moves a block, the sun moves ~0.06 degrees, or after that many frames, so block edits can show in the shadows a few frames late.
`mcopt.shade.half` (default true, "Half-res lighting" in the menu): shadows and AO are worked out at half resolution in
`shade_occ.fsh`, then `shade.fsh` upsamples them by depth (the four nearest half-res pixels, weighted by how close their depth is
to the pixel's, so they don't bleed across edges), applies them and grades. Off runs the same two passes at full resolution.
`mcopt.shade.soft` (default false, "Wide soft shadows" in the menu): by default sun shadows are solid with a slightly soft edge
(four shadow map lookups on a 1.5-texel grid, blended bilinearly: about 0.2 blocks of ramp, no noise); on is the old 16-tap rotated
disc, wider and a little grainy. With it off the contact march is unjittered too, so shadows have no speckle.
Only mcopt's / vanilla's own terrain is shaded: Distant Horizons writes its LODs into the colour without depth, so they read as
sky, and the effects also fade out over the last chunk of the render distance. The first-person hand and held item are drawn after
the level depth is copied, so the composite reads the main depth (which by then holds only the hand) and leaves those pixels unshaded.

Measured at 3024x1898 (dev client, frozen camera, Metal System Trace): the shade pass is the frame's biggest cost, about 3.5 ms
median against about 1.8 ms for the rest of the frame's fragment work. Skipping the lookups on faces turned from the sun and the
four-tap PCF early-out took it from 4.7 ms to 3.5 ms; the shadow map draw is about 0.85 ms each time it runs.

Tuning (all optional): `mcopt.shade.shadow` 0..1 (0.85, shadow darkness), `.ao` 0..1 (0.8), `.grade` 0..1 (0.7, tonemap/saturation),
`.light` 0..1 (0.8, sun/moon colour), `.shadowLen` blocks (24, contact-shadow reach), `.aoRadius` blocks (1.2), `.map` true/false (shadow map),
`.mapRes` (2048), `.mapRadius` blocks (128, half-width of the one cascade), `.debug` 1 normals / 2 shadow / 3 AO.
Self-test: `.shot=100 .shotName=N .shotSeq=off,on,d2 .shotTime=1000 .shotPos=x,y,z .shotYaw/.shotPitch .shotDelay=40 .shotCmd=...`
(`#` is a space in shotCmd); it writes `run/screenshots/N_<step>.png` and says "shade ON/OFF" in chat per step.

Known limits: water and ice (translucent) write no depth, so they get no AO or shadow of their own (the ground under them does, a bit grainy);
leaves show dappled shadow noise; one cascade, so far shadows (>128 blocks) are screen-space only; Nether/End have no sun and fall back to
AO + grade only; entities do not cast into the map yet; no bloom or water shading yet.


Iris shaderpacks need Sodium, which mcopt replaces, so they don't run here. This note compares the two ways to get
shaders back and says which one we build first.

Facts both options start from:

- Near terrain (solid, cutout, translucent) is drawn by `own/OwnTerrain` and `own/terrain.metal`: hand-written MSL, GPU
  culling, indirect draws, 16-byte vertices that have no room for a block id, a normal or a material.
- Everything else (entities, sky, clouds, particles, hand, GUI) uses vanilla's GLSL pipelines, translated GLSL -> SPIR-V
  -> MSL in `MetalPipeline`.
- The hooks for a deeper runtime exist and are unused: `MetalHooks.Redirect`/`PassRedirector` (send a pass into other
  attachments), `PassDelegate` (take over a pass's pipelines), `MetalBridge` (native handles), `MetalEncoder.forgetClear`.
- Vanilla's main target has a reverse-Z D32 depth buffer; `GameRenderer.applyPostEffects` runs after the level and before
  the GUI.
- Rule: with shaders off the picture is exactly vanilla's. Shaders are opt-in.

## (a) A built-in shader pipeline, written by us

Staged. Each stage works on its own and is a smaller diff than the next.

1. **Screen-space post (built now).** One pass after the level, before the GUI, reading the main colour and depth:
   contact shadows from the sun (a ray march through the depth buffer toward the sun), ambient occlusion, a cool tint in
   shadow, a gentle tonemap and grade. No change to terrain or entity drawing. Cost: ~1 ms class full-screen pass.
   Limits: shadows only from what is on screen (no shadows from off-screen casters, thin lines at depth edges), no
   knowledge of which pixel is sunlit vs. cave, so the shadow darkening is gated by the sun's height and the surface
   brightness.
2. **G-buffer + shadow map.** Redirect the terrain/entity passes into a G-buffer (albedo, normal, material, light
   levels) through `PassRedirector`/`PassDelegate`; draw a second, culled terrain pass from the sun into a depth map
   (own terrain's cull already produces visible-section lists, so this is "run the cull with the sun's frustum and a
   depth-only vertex stage"); entities likewise through their vanilla pipelines with a shadow variant. A deferred
   lighting pass then does cascaded shadow sampling with PCF, sky/block light, fog, water. Cost: terrain.metal needs a
   second vertex entry and a G-buffer fragment entry (new outputs: normal, light) and the entity pipelines need MSL
   overrides. Roughly 2-3 weeks of careful work, most of it in `OwnTerrain` which other agents are changing.
3. **Water, bloom, volumetrics, TAA.** Normal-mapped water, bloom, cheap volumetric light from the shadow map.

What it costs: all the code is ours (no licensing question), runs at the speed of hand-written MSL, but it is one look.
There are no community packs. Every feature is written by us.

## (b) An OptiFine/Iris shaderpack loader

Load a pack the user installs in `shaderpacks/`: `shaders.properties`, `block.properties`, `gbuffers_*`, `shadow`,
`deferred*`, `composite*`, `final` programs, `colortex0-15`, `depthtex*`, `shadowtex*`, `#include`, `#define` option
screens, custom uniforms.

What it costs:

- A GLSL preprocessor and rewriter: packs are written in the GLSL 1.20-4.x *compatibility* profile (`gl_Vertex`,
  `gl_ModelViewMatrix`, `gl_FragData`, `attribute`/`varying`, `gl_TextureMatrix`). Each stage has to be rewritten to
  core GLSL (as Iris's `TransformPatcher` does) before shaderc -> SPIR-V -> SPIRV-Cross. Hundreds of special cases.
- A program/pass graph: every `gbuffers_*` program per draw type (terrain, water, entities, hand, sky, clouds,
  particles, beacon beams, damaged blocks, and their `_cutout`, `_solid` variants), which we would map onto vanilla's
  pipelines (`PassDelegate`) and own terrain.
- Own terrain cannot feed them. Packs read `mc_Entity` (block id from `block.properties`), `at_tangent`,
  `mc_midTexCoord`, `at_midBlock`, and the normal. The 16-byte vertex has none; it needs a wider vertex or a side buffer
  and the mesher has to emit the ids. That touches the mesher, arena sizing and memory (a bandwidth cost the project has
  been working to cut).
- Shadow pass: the same second terrain pass as option (a)-2, plus the pack's `shadow` program with its own vertex
  transforms (shadow distortion), `shadowcomp`, `shadowtex0/1`, `shadowcolor`.
- Uniform zoo (~200 Iris uniforms), buffer formats (`const int colortex0Format`), `drawbuffers`, flipped/mipmapped buffers,
  `uniform.*` custom expressions, `texture.properties`, `dimension.properties`. And the packs themselves: big ones
  (Complementary, BSL, SEUS) have been tuned for OpenGL drivers; translated shaders on Metal will hit translator and
  precision bugs one pack at a time.

Licensing: running a pack the user installed is fine. Nothing from a pack may be committed here; Complementary
restricts redistributing modified versions, so a translated copy in the repo is not an option and the loader must
read packs only from the user's folder.

## Recommendation

**Build (a), starting with stage 1 now.** Reasons:

- It delivers a visible result without touching the arena vertex format or `OwnTerrain`, which four other efforts are
  changing.
- Stage 2 (G-buffer + shadow map) is exactly the infrastructure (b) needs as well, so nothing is thrown away; (b)
  becomes "stage 2 + a GLSL rewriter + a wider terrain vertex" if the project later wants pack compatibility.
- (b) first would spend months on the rewriter and the mc_Entity vertex before the first lit pixel, and then each
  pack would still need individual fixes.

Next steps after stage 1, in order: sun depth pass over own terrain's cull (shadow map); G-buffer redirect for terrain
and entities; deferred lighting using the shadow map; water; then decide on (b) with the stage-2 passes as its
backbone, starting with a loader that runs only a pack's `composite*` and `final` programs.

## How to turn stage 1 on

`-Dmcopt.shade=true`. Tunables (all optional): `mcopt.shade.shadow` (0-1, default 0.55), `mcopt.shade.ao` (default
0.6), `mcopt.shade.grade` (default 0.5), `mcopt.shade.shadowLen` (blocks, default 24), `mcopt.shade.debug`
(1 normals, 2 shadow, 3 ao). Off: not a single draw is added.

Source: `metal/src/main/java/mcopt/metal/shade`, shaders in
`metal/src/main/resources/assets/mcopt/shaders/post/` (vanilla resolves shader ids through the resource system, so they
must live under `assets/<namespace>/shaders/`).

## Status (stage 1 verified)

Bug found and fixed: vanilla clears the main depth buffer in `GameRenderer.render3dHud` (before the first-person hand), so by
the tail of `applyPostEffects` the depth held only the hand and every terrain pixel read as sky (debug=1 was flat green,
no shadows/AO). `ShadeDepth` now copies the level depth (`copyTextureToTexture` into its own D32 texture) at the head of
`render3dHud`, and `ShadePass` samples that copy. The shadow map will use the same copy. Checked with debug=1: floors
green, walls pink/blue; debug=3 AO shows on trees and leaves; on vs. off shows subtle AO and grade.

Self-test (one launch, all views, camera frozen): `-Dmcopt.shade=true -Dmcopt.shade.shot=100 -Dmcopt.shade.shotName=NAME
-Dmcopt.shade.shotSeq=off,on,d1,d2,d3 -Dmcopt.shade.shotDelay=25` writes `run/screenshots/NAME_<step>.png`;
`shotYaw`/`shotPitch` set the frozen camera.

Known: translucent water and clouds-over-water write no depth, so water gets no AO/shadow and reads as sky in debug=1.
The test world's spawn is at noon (sunAngle 0), so contact shadows fall straight down; use a different time to judge
shadow side.

## Next steps (not done)

1. Sun shadow map. `own/OwnTerrain` (~3200 lines) and `terrain.metal` (~3500 lines) have many cull variants (own_cull,
   frag_cull*, occlusion, a1/a2 split). Plan: a separate `OwnShadow` class that reuses the arena buffers and section
   tables, runs the simple `own_cull` (with a sun-frustum `CullFrame`, no occlusion) and `own_finish`, then one indirect
   draw with a new `own_shadow_vs` (depth only, orthographic sun matrix texel-snapped to the shadow texel size,
   ~128 block radius, one cascade first) and an alpha-tested `own_shadow_fs` for cutout. Needs the vertex pulling
   code from `terrain.metal` factored into a shared function; keep that edit additive.
2. Sample it in `shade.fsh` (PCF, normal-offset bias), keep contact shadows for detail; sun/moon colour by time of day.
3. G-buffer redirect (PassRedirector/PassDelegate), water, bloom.

Update: sun shadow map works behind `-Dmcopt.shade.map=true` (needs `-Dmcopt.own=true`; `mapRes`, `mapRadius`). `ShadeShadow` (level render head) calls
OwnTerrain.shadowCull + shadowDraw (depth-only pass, position-only solid + alpha-tested cutout); shade.fsh samples it with PCF and normal-offset
bias, contact shadows kept as fine detail. Verified in screenshots (morning, `-Dmcopt.shade.shotTime=1000`): shadow mask shows tree and leaf
shadows, picture sane; leaves show some acne noise in d2. The "rain 1.0" was only the first frame's default (steady state 0.0).
Light colour by time of day: `mcopt.shade.light` (default 0.6): warm low sun, white noon, cool moon, applied only where lit.
Next: tune leaf acne/PCF softness, check dusk/night shots (shotTime=12500/18000), water (needs depth for translucent), bloom, G-buffer.
