package mcopt.metal.shade;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

/**
 * Stage 1 of mcopt's built-in shaders over the finished level image: the shadow / ambient occlusion pass into a (by default half-res)
 * target, then a full-screen pass that upsamples it, applies it and grades into a scratch target, and a copy back. Runs from
 * GameRenderer.applyPostEffects' tail.
 */
public final class ShadePass {
	private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putVec4().putVec4().putVec4().putMat4f().putVec4().putVec4().putVec4().putVec4()
		.putMat4f().putVec4().putVec4().putVec4().putVec4().putMat4f().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();
	private static final int LIGHTS_SIZE = (ShadeLights.MAX * 2 + ShadeRays.MAX * 2) * 16;
	private static RenderPipeline occ;
	private static RenderPipeline shade;
	private static RenderPipeline blit;
	private static GpuBuffer ubo;
	private static GpuBuffer lights;
	private static boolean failed, logged;
	private static int logFrames;

	private ShadePass() {
	}

	private static void init() {
		BindGroupLayout occInputs = BindGroupLayout.builder()
			.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowSampler2", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadeInfo", UniformType.UNIFORM_BUFFER)
			.build();
		BindGroupLayout inputs = BindGroupLayout.builder()
			.withUniform("ColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("OccSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("HandSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CloudSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadeLights", UniformType.UNIFORM_BUFFER)
			.withUniform("ShadeInfo", UniformType.UNIFORM_BUFFER)
			.build();
		BindGroupLayout blitInputs = BindGroupLayout.builder().withUniform("ColorSampler", UniformType.COMBINED_IMAGE_SAMPLER).build();
		occ = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("mcopt", "pipeline/shade_occ"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("mcopt", "post/shade_occ"))
			.withBindGroupLayout(occInputs)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build();
		shade = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("mcopt", "pipeline/shade"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("mcopt", "post/shade"))
			.withBindGroupLayout(inputs)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build();
		blit = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("mcopt", "pipeline/shade_blit"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("mcopt", "post/shade_blit"))
			.withBindGroupLayout(blitInputs)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build();
		ubo = RenderSystem.getDevice().createBuffer(() -> "mcopt shade info", 136, UBO_SIZE);
		lights = RenderSystem.getDevice().createBuffer(() -> "mcopt shade lights", 136, LIGHTS_SIZE);
		if (RenderSystem.getCompiledPipelineNullable(occ) == null || RenderSystem.getCompiledPipelineNullable(shade) == null || RenderSystem.getCompiledPipelineNullable(blit) == null) {
			throw new IllegalStateException("mcopt shade: pipeline did not compile");
		}
	}

	public static void run(RenderTarget main, GraphicsResourceAllocator pool, LevelRenderState level, float rain) {
		CameraRenderState camera = level.cameraRenderState;
		SkyRenderState sky = level.skyRenderState;
		if (failed || ShadeDepth.view() == null) return;
		try {
			if (shade == null) init();
			apply(main, pool, level, camera, sky, rain);
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			System.out.println("[mcopt] shade: disabled, " + e);
			e.printStackTrace();
		}
	}

	/** This frame's level projection as renderLevel set it (with the view bob), and whether it was captured since the last pass. */
	private static final Matrix4f LEVEL_PROJ = new Matrix4f();
	private static boolean LEVEL_PROJ_SET;

	public static void levelProjection(Matrix4f m) {
		LEVEL_PROJ.set(m);
		LEVEL_PROJ_SET = true;
	}

	/** Chance that a sunrise or sunset is a vivid one. */
	private static final double VIVID_CHANCE = 0.035;
	/** Every sunrise and sunset vivid, for testing. */
	private static final boolean FORCE_VIVID = Boolean.getBoolean("mcopt.shade.forceVivid");

	/**
	 * Vivid palettes, each as seen in real skies: the sunlight on the clouds' lit sides, their shaded undersides, and a tint for the sky
	 * toward the horizon. A vivid event uses one of them throughout.
	 */
	private static final float[][] VIVID = {
		// Fiery: deep orange light, red-orange undersides.
		{1f, 0.42f, 0.18f, 0.92f, 0.36f, 0.28f, 1.12f, 0.86f, 0.74f},
		// Pink and magenta: salmon light, mauve undersides.
		{1f, 0.52f, 0.5f, 0.86f, 0.42f, 0.7f, 1.08f, 0.86f, 1f},
		// Golden hour: gold light, peach undersides.
		{1f, 0.74f, 0.3f, 1f, 0.66f, 0.46f, 1.1f, 1f, 0.78f},
		// Violet twilight: rose light, lavender undersides.
		{0.96f, 0.5f, 0.62f, 0.58f, 0.44f, 0.86f, 1f, 0.84f, 1.12f},
		// Smoky red: a low red sun through haze, crimson undersides.
		{1f, 0.3f, 0.16f, 0.72f, 0.24f, 0.3f, 1.14f, 0.8f, 0.78f},
	};

	/**
	 * The vivid palette (an index into VIVID) for the sunrise or sunset nearest now, or -1 for an ordinary one. Each is its own roll:
	 * the day count (game time, less however far into the day the sun is) and which half of the day, hashed, so it's stable for
	 * the whole event.
	 */
	private static int vivid(long gameTime, float sunAngle) {
		double frac = (sunAngle / (2 * Math.PI)) % 1.0;
		if (frac < 0) frac += 1.0;
		long day = Math.round((gameTime - frac * 24000.0) / 24000.0);
		long event = day * 2 + (Math.sin(sunAngle) < 0 ? 0 : 1);
		long h = event * 0x9E3779B97F4A7C15L;
		h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		if (!FORCE_VIVID && (h >>> 11) * 0x1.0p-53 >= VIVID_CHANCE) return -1;
		return (int) Math.floorMod(h ^ (h >>> 32), (long) VIVID.length);
	}

	private static void apply(RenderTarget main, GraphicsResourceAllocator pool, LevelRenderState level, CameraRenderState camera, SkyRenderState sky, float rain) {
		// The projection the level was drawn with (view bobbing and nausea included, ShadeLevelProjectionMixin): the camera's own
		// leaves out the bob, and reconstructing with it put clouds and shadows off by the bob, swaying and trailing as you walk.
		Matrix4f proj = new Matrix4f(LEVEL_PROJ_SET ? LEVEL_PROJ : camera.projectionMatrix);
		LEVEL_PROJ_SET = false;
		Matrix4f inv = proj.invert(new Matrix4f());
		// Direction to the sun in world space (SkyRenderer: Y -90, X sunAngle on +Y), then into view space.
		Vector3f sun = new Vector3f((float) -Math.sin(sky.sunAngle), (float) Math.cos(sky.sunAngle), 0f);
		if (Shade.SHOT > 0 && !logged && ++logFrames > 300) { logged = true; System.out.println("[mcopt] shade: sunAngle " + sky.sunAngle + " rain " + rain); }
		ShadeShadow.sunAngle = sky.sunAngle;
		boolean sunny = net.minecraft.client.Minecraft.getInstance().level != null && Shade.sunny(net.minecraft.client.Minecraft.getInstance().level);
		float day = sunny ? smooth(-0.04f, 0.28f, sun.y) * (1f - rain) : 0f;
		float elev = sun.y;
		float sunW = smooth(-0.08f, 0.15f, elev), warm = lerp(0.3f, 1f, 1f - smooth(0.05f, 0.55f, elev));
		int pal = vivid(level.gameTime, sky.sunAngle);
		float vivid = pal >= 0 ? 1f - rain : 0f;
		float[] vp = pal >= 0 ? VIVID[pal] : VIVID[0];
		// A vivid event's light colours the terrain too: toward the palette's light, normalized to the ordinary warm light's level.
		float tr = lerp(0.84f, lerp(1f, 1.0f, warm), sunW), tg = lerp(0.89f, lerp(1f, lerp(0.74f, vp[1] / vp[0], vivid), warm), sunW), tb = lerp(1f, lerp(1f, lerp(0.50f, vp[2] / vp[0], vivid), warm), sunW);
		float rain0 = rain;
		float tint = !sunny ? 0f : Shade.LIGHT * lerp(0.4f, 1f, sunW) * (1f - rain);
		if (elev < -0.05f) sun.negate();  // the moon's direction at night
		camera.viewRotationMatrix.transformDirection(sun);

		GpuTextureView shadowView = ShadeShadow.view();
		GpuTextureView shadowView2 = ShadeShadow.view2();
		Matrix4f shadowM = new Matrix4f(ShadeShadow.CLIP).mul(camera.viewRotationMatrix.invert(new Matrix4f()));
		Matrix4f shadowM2 = new Matrix4f(ShadeShadow.CLIP2).mul(camera.viewRotationMatrix.invert(new Matrix4f()));
		int scale = Shade.HALF ? 2 : 1;
		int ow = (main.width + scale - 1) / scale, oh = (main.height + scale - 1) / scale;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		float vanilla = mc.options.getEffectiveRenderDistance() * 16f;
		Matrix4f viewInv = camera.viewRotationMatrix.invert(new Matrix4f());
		// Clouds: the camera's place on the wrapping cell map, drifting +x at vanilla's 12 blocks per 400 ticks.
		GpuTextureView cloudMap = ShadeClouds.active() ? ShadeClouds.view() : null;
		int cells = ShadeClouds.size();
		double period = cells * (double) ShadeClouds.CELL;
		double drift = cells > 0 ? (level.gameTime % (cells * 400L) + level.worldPartialTicks) * (ShadeClouds.CELL / 400.0) : 0;
		float ox = cells > 0 ? (float) floorMod(camera.pos.x - drift, period) : 0f, oz = cells > 0 ? (float) floorMod(camera.pos.z, period) : 0f;
		int cc = level.cloudColor;
		// The sky's weather (ShadeSky). The high layer drifts faster than the low clouds (its periods divide 4096 blocks, so the wrap
		// doesn't jump); a veil or fog around the camera softens the sun's shadows.
		ShadeSky.State sk = ShadeSky.now;
		double hx = (level.gameTime % 40000L + level.worldPartialTicks) * 0.1024, hz = (level.gameTime % 160000L + level.worldPartialTicks) * 0.0256;
		float inFog = sk.fog * (1f - smooth(sk.fogTop, sk.fogTop + 6f, (float) camera.pos.y));
		float shadowK = Shade.SHADOW * (1f - 0.45f * sk.cirrostratus) * (1f - 0.7f * inFog);
		ShadeLights.update(camera.pos);
		ShadeRays.update(camera.pos);
		float rays = sunny ? ShadeRays.strength(elev, rain) : 0f;
		writeLights(camera, sky.sunAngle);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(proj).putMat4f(inv)
				.putVec4(sun.x, sun.y, sun.z, day)
				.putVec4(shadowK, Shade.AO, Shade.GRADE, Shade.SHADOW_LEN)
				.putVec4(Shade.debugNow, Shade.AO_RADIUS, 1f / main.width, 1f / main.height)
				.putMat4f(shadowM)
				.putVec4(2f * Shade.MAP_RADIUS / Shade.MAP_RES, 1f / Shade.MAP_RES, shadowView != null ? 1f : 0f, ShadeShadow.RANGE)
				.putVec4(tr, tg, tb, tint)
				.putVec4(scale, vanilla, ow, oh)
				.putVec4(Shade.SOFT ? 1f : 0f, ShadeShadow.BLEND, 0f, 0f)
				.putMat4f(viewInv)
				.putVec4(cloudMap != null ? 1f : 0f, level.cloudHeight, Shade.CLOUD_DIST, sunW)
				.putVec4(ox, (float) camera.pos.y, oz, cells)
				.putVec4((cc >> 16 & 255) / 255f, (cc >> 8 & 255) / 255f, (cc & 255) / 255f, (cc >>> 24) / 255f)
				.putVec4(ShadeLights.count, Shade.BLOCK_LIGHT, camera.fogType == FogType.WATER ? 1f : 0f, Math.max(day, 0.15f))
				.putMat4f(shadowM2)
				.putVec4(rain0, vivid, rays > 0f ? ShadeRays.count : 0f, rays)
				.putVec4(vp[0], vp[1], vp[2], 0f)
				.putVec4(vp[3], vp[4], vp[5], 0f)
				.putVec4(vp[6], vp[7], vp[8], 0f)
				.putVec4(sk.cumulus, sk.cumulonimbus, sk.cirrus, sk.cirrostratus)
				.putVec4(sk.fog, sk.fogTop, (float) floorMod(camera.pos.x - hx, 4096), (float) floorMod(camera.pos.z - hz, 4096))
				.putVec4((float) floorMod(camera.pos.x, 4096), (float) floorMod(camera.pos.z, 4096), (level.gameTime % 72000L + level.worldPartialTicks) / 20f, 0f)
				.get();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), data);
		}

		RenderTargetDescriptor desc = new RenderTargetDescriptor(main.width, main.height,
			new RenderTargetDescriptor.TextureProperties(null, GpuFormat.RGBA8_UNORM), null);
		RenderTargetDescriptor occDesc = new RenderTargetDescriptor(ow, oh,
			new RenderTargetDescriptor.TextureProperties(null, GpuFormat.RGBA8_UNORM), null);
		RenderTarget scratch = pool.acquire(desc);
		RenderTarget occTarget = pool.acquire(occDesc);
		try {
			CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			try (RenderPass pass = enc.createRenderPass(() -> "mcopt shade occlusion", occTarget.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(occ));
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("ShadeInfo", ubo);
				pass.setUniform("DepthSampler", ShadeDepth.view(), nearest);
				pass.setUniform("ShadowSampler", shadowView != null ? shadowView : ShadeDepth.view(), nearest);
				pass.setUniform("ShadowSampler2", shadowView2 != null ? shadowView2 : ShadeDepth.view(), nearest);
				pass.draw(3, 1, 0, 0);
			}
			try (RenderPass pass = enc.createRenderPass(() -> "mcopt shade", scratch.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(shade));
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("ShadeInfo", ubo);
				pass.setUniform("ColorSampler", main.getColorTextureView(), nearest);
				pass.setUniform("DepthSampler", ShadeDepth.view(), nearest);
				pass.setUniform("OccSampler", occTarget.getColorTextureView(), nearest);
				pass.setUniform("HandSampler", main.getDepthTextureView(), nearest);
				pass.setUniform("CloudSampler", cloudMap != null ? cloudMap : occTarget.getColorTextureView(), nearest);
				pass.setUniform("ShadeLights", lights);
				pass.draw(3, 1, 0, 0);
			}
			try (RenderPass pass = enc.createRenderPass(() -> "mcopt shade resolve", main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(blit));
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("ColorSampler", scratch.getColorTextureView(), nearest);
				pass.draw(3, 1, 0, 0);
			}
		} finally {
			pool.release(occDesc, occTarget);
			pool.release(desc, scratch);
		}
	}

	/** The nearest emitters into the lights buffer, moved into view space (camera-relative, rotated) so the shader can use them as is. */
	private static void writeLights(CameraRenderState camera, float sunAngle) {
		Vector3f v = new Vector3f();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder b = Std140Builder.onStack(stack, LIGHTS_SIZE);
			float[] p = ShadeLights.POS, c = ShadeLights.COL;
			for (int i = 0; i < ShadeLights.MAX; i++) {
				if (i < ShadeLights.count) {
					v.set((float) (p[i * 4] - camera.pos.x), (float) (p[i * 4 + 1] - camera.pos.y), (float) (p[i * 4 + 2] - camera.pos.z));
					camera.viewRotationMatrix.transformDirection(v);
					b.putVec4(v.x, v.y, v.z, p[i * 4 + 3]);
				} else {
					b.putVec4(0f, 0f, 0f, 0f);
				}
			}
			for (int i = 0; i < ShadeLights.MAX; i++) b.putVec4(c[i * 4], c[i * 4 + 1], c[i * 4 + 2], 0f);
			// God ray shafts (ShadeRays): from the canopy gap down to the ground along the sunlight, ends in view space; w the width
			// and a per-shaft brightness.
			float sx = (float) -Math.sin(sunAngle), sy = Math.max(0.05f, (float) Math.cos(sunAngle));
			float[] g = ShadeRays.GAPS;
			Vector3f e = new Vector3f();
			for (int i = 0; i < ShadeRays.MAX * 2; i++) {
				int n = i % ShadeRays.MAX;
				if (n >= ShadeRays.count) {
					b.putVec4(0f, 0f, 0f, 0f);
					continue;
				}
				float top = g[n * 4 + 2], ground = g[n * 4 + 3];
				float drop = i < ShadeRays.MAX ? 0f : top - ground;
				float wx = g[n * 4] - sx / sy * drop, wy = top - drop, wz = g[n * 4 + 1];
				e.set((float) (wx - camera.pos.x), (float) (wy - camera.pos.y), (float) (wz - camera.pos.z));
				camera.viewRotationMatrix.transformDirection(e);
				int h = Float.floatToIntBits(g[n * 4] * 31f + g[n * 4 + 1]) * 0x9E3779B1;
				b.putVec4(e.x, e.y, e.z, i < ShadeRays.MAX ? 0.9f + ((h >>> 24) / 255f) * 0.8f : 0.55f + ((h >>> 16 & 255) / 255f) * 0.45f);
			}
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(lights.slice(), b.get());
		}
	}

	private static double floorMod(double a, double m) {
		return a - Math.floor(a / m) * m;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float smooth(float a, float b, float x) {
		float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
		return t * t * (3f - 2f * t);
	}
}
