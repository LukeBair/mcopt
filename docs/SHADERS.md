# Shaders on the Metal path

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
