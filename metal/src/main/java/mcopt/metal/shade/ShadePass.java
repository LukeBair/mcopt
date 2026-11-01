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
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

/**
 * Stage 1 of mcopt's built-in shaders over the finished level image: the shadow / ambient occlusion pass into a (by default half-res)
 * target, then a full-screen pass that upsamples it, applies it and grades into a scratch target, and a copy back. Runs from
 * GameRenderer.applyPostEffects' tail.
 */
public final class ShadePass {
	private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putVec4().putVec4().putVec4().putMat4f().putVec4().putVec4().putVec4().putVec4().get();
	private static RenderPipeline occ;
	private static RenderPipeline shade;
	private static RenderPipeline blit;
	private static GpuBuffer ubo;
	private static boolean failed, logged;
	private static int logFrames;

	private ShadePass() {
	}

	private static void init() {
		BindGroupLayout occInputs = BindGroupLayout.builder()
			.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadeInfo", UniformType.UNIFORM_BUFFER)
			.build();
		BindGroupLayout inputs = BindGroupLayout.builder()
			.withUniform("ColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("OccSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("HandSampler", UniformType.COMBINED_IMAGE_SAMPLER)
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
		if (RenderSystem.getCompiledPipelineNullable(occ) == null || RenderSystem.getCompiledPipelineNullable(shade) == null || RenderSystem.getCompiledPipelineNullable(blit) == null) {
			throw new IllegalStateException("mcopt shade: pipeline did not compile");
		}
	}

	public static void run(RenderTarget main, GraphicsResourceAllocator pool, CameraRenderState camera, SkyRenderState sky, float rain) {
		if (failed || ShadeDepth.view() == null) return;
		try {
			if (shade == null) init();
			apply(main, pool, camera, sky, rain);
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			System.out.println("[mcopt] shade: disabled, " + e);
			e.printStackTrace();
		}
	}

	private static void apply(RenderTarget main, GraphicsResourceAllocator pool, CameraRenderState camera, SkyRenderState sky, float rain) {
		Matrix4f proj = new Matrix4f(camera.projectionMatrix);
		Matrix4f inv = proj.invert(new Matrix4f());
		proj.set(camera.projectionMatrix);
		// Direction to the sun in world space (SkyRenderer: Y -90, X sunAngle on +Y), then into view space.
		Vector3f sun = new Vector3f((float) -Math.sin(sky.sunAngle), (float) Math.cos(sky.sunAngle), 0f);
		if (Shade.SHOT > 0 && !logged && ++logFrames > 300) { logged = true; System.out.println("[mcopt] shade: sunAngle " + sky.sunAngle + " rain " + rain); }
		ShadeShadow.sunAngle = sky.sunAngle;
		boolean sunny = net.minecraft.client.Minecraft.getInstance().level != null && Shade.sunny(net.minecraft.client.Minecraft.getInstance().level);
		float day = sunny ? smooth(-0.04f, 0.28f, sun.y) * (1f - rain) : 0f;
		float elev = sun.y;
		float sunW = smooth(-0.08f, 0.15f, elev), warm = lerp(0.3f, 1f, 1f - smooth(0.05f, 0.55f, elev));
		float tr = lerp(0.84f, lerp(1f, 1.0f, warm), sunW), tg = lerp(0.89f, lerp(1f, 0.74f, warm), sunW), tb = lerp(1f, lerp(1f, 0.50f, warm), sunW);
		float tint = !sunny ? 0f : Shade.LIGHT * lerp(0.4f, 1f, sunW) * (1f - rain);
		if (elev < -0.05f) sun.negate();  // the moon's direction at night
		camera.viewRotationMatrix.transformDirection(sun);

		GpuTextureView shadowView = ShadeShadow.view();
		Matrix4f shadowM = new Matrix4f(ShadeShadow.CLIP).mul(camera.viewRotationMatrix.invert(new Matrix4f()));
		int scale = Shade.HALF ? 2 : 1;
		int ow = (main.width + scale - 1) / scale, oh = (main.height + scale - 1) / scale;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		float vanilla = mc.options.getEffectiveRenderDistance() * 16f;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(proj).putMat4f(inv)
				.putVec4(sun.x, sun.y, sun.z, day)
				.putVec4(Shade.SHADOW, Shade.AO, Shade.GRADE, Shade.SHADOW_LEN)
				.putVec4(Shade.debugNow, Shade.AO_RADIUS, 1f / main.width, 1f / main.height)
				.putMat4f(shadowM)
				.putVec4(2f * Shade.MAP_RADIUS / Shade.MAP_RES, 1f / Shade.MAP_RES, shadowView != null ? 1f : 0f, ShadeShadow.RANGE)
				.putVec4(tr, tg, tb, tint)
				.putVec4(scale, vanilla, ow, oh)
				.putVec4(Shade.SOFT ? 1f : 0f, 0f, 0f, 0f)
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

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float smooth(float a, float b, float x) {
		float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
		return t * t * (3f - 2f * t);
	}
}
