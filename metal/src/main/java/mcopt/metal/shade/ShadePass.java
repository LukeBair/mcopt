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
 * Stage 1 of mcopt's built-in shaders: one full-screen pass (screen-space sun shadows, ambient occlusion, grade) over the
 * finished level image into a scratch target, and a copy back. Runs from GameRenderer.applyPostEffects' tail.
 */
public final class ShadePass {
	private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putVec4().putVec4().putVec4().get();
	private static RenderPipeline shade;
	private static RenderPipeline blit;
	private static GpuBuffer ubo;
	private static boolean failed;

	private ShadePass() {
	}

	private static void init() {
		BindGroupLayout inputs = BindGroupLayout.builder()
			.withUniform("ColorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadeInfo", UniformType.UNIFORM_BUFFER)
			.build();
		BindGroupLayout blitInputs = BindGroupLayout.builder().withUniform("ColorSampler", UniformType.COMBINED_IMAGE_SAMPLER).build();
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
		if (RenderSystem.getCompiledPipelineNullable(shade) == null || RenderSystem.getCompiledPipelineNullable(blit) == null) {
			throw new IllegalStateException("mcopt shade: pipeline did not compile");
		}
	}

	public static void run(RenderTarget main, GraphicsResourceAllocator pool, CameraRenderState camera, SkyRenderState sky, float rain) {
		if (failed || main.getDepthTextureView() == null) return;
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
		float day = smooth(-0.04f, 0.28f, sun.y) * (1f - rain);
		camera.viewRotationMatrix.transformDirection(sun);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(proj).putMat4f(inv)
				.putVec4(sun.x, sun.y, sun.z, day)
				.putVec4(Shade.SHADOW, Shade.AO, Shade.GRADE, Shade.SHADOW_LEN)
				.putVec4(Shade.DEBUG, Shade.AO_RADIUS, 1f / main.width, 1f / main.height)
				.get();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), data);
		}

		RenderTargetDescriptor desc = new RenderTargetDescriptor(main.width, main.height,
			new RenderTargetDescriptor.TextureProperties(null, GpuFormat.RGBA8_UNORM), null);
		RenderTarget scratch = pool.acquire(desc);
		try {
			CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			try (RenderPass pass = enc.createRenderPass(() -> "mcopt shade", scratch.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(shade));
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("ShadeInfo", ubo);
				pass.setUniform("ColorSampler", main.getColorTextureView(), nearest);
				pass.setUniform("DepthSampler", main.getDepthTextureView(), nearest);
				pass.draw(3, 1, 0, 0);
			}
			try (RenderPass pass = enc.createRenderPass(() -> "mcopt shade resolve", main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(blit));
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("ColorSampler", scratch.getColorTextureView(), nearest);
				pass.draw(3, 1, 0, 0);
			}
		} finally {
			pool.release(desc, scratch);
		}
	}

	private static float smooth(float a, float b, float x) {
		float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
		return t * t * (3f - 2f * t);
	}
}
