package mcopt.metal.shade;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/**
 * Render scale (-Dmcopt.renderScale, "Render scale" in the mcopt Shaders menu): the level (terrain, entities, the hand, vanilla's post
 * effects and mcopt's shade pass) renders into targets this fraction of the window's size, then is stretched (bilinear) onto the
 * window's target before the GUI, which stays at full resolution. 1 renders as vanilla does. ShadeRenderScaleMixin swaps GameRenderer's
 * main and hand targets for ours around the level render.
 */
public final class RenderScale {
	/** Fraction of the window's width and height the level renders at, 0.5..1. */
	public static float SCALE = clamp(Float.parseFloat(System.getProperty("mcopt.renderScale", "1")));
	private static RenderTarget level, hud;
	private static RenderPipeline blit;
	private static boolean failed;

	private RenderScale() {
	}

	static float clamp(float v) {
		return Float.isNaN(v) ? 1f : Math.max(0.5f, Math.min(1f, v));
	}

	/** The level's main target for this frame, sized and cleared, or null to render at full size. */
	public static RenderTarget level(RenderTarget main) {
		if (failed || SCALE >= 0.999f || main.getColorTexture() == null || main.getDepthTexture() == null) {
			free();
			return null;
		}
		int w = Math.max(1, Math.round(main.width * SCALE)), h = Math.max(1, Math.round(main.height * SCALE));
		try {
			if (level == null) level = new TextureTarget("mcopt scaled level", w, h, main.getColorTexture().getFormat(), main.getDepthTexture().getFormat());
			else if (level.width != w || level.height != h) level.resize(w, h);
			// the frame's clear, as vanilla gives the main target (reverse-Z: depth 0 is far)
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(level.getColorTexture(), new Vector4f(0f, 0f, 0f, 1f),
				level.getDepthTexture(), 0.0);
			return level;
		} catch (RuntimeException e) {
			failed = true;
			System.out.println("[mcopt] render scale: disabled, " + e);
			free();
			return null;
		}
	}

	/** The hand's target at the level's size (its depth is merged into the level's), matching the window-sized one's formats. */
	public static RenderTarget hud(RenderTarget windowHud) {
		if (level == null) return windowHud;
		if (hud == null) {
			var color = windowHud.getColorTexture() != null ? windowHud.getColorTexture().getFormat() : null;
			var depth = windowHud.getDepthTexture() != null ? windowHud.getDepthTexture().getFormat() : null;
			hud = new TextureTarget("mcopt scaled hand", level.width, level.height, color, depth);
		} else if (hud.width != level.width || hud.height != level.height) {
			hud.resize(level.width, level.height);
		}
		return hud;
	}

	/** Stretches the level onto the window's target (bilinear). */
	public static void present(RenderTarget main) {
		if (level == null) return;
		if (blit == null) {
			blit = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath("mcopt", "pipeline/render_scale"))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("mcopt", "post/shade_blit"))
				.withBindGroupLayout(BindGroupLayout.builder().withUniform("ColorSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withColorTargetState(ColorTargetState.DEFAULT)
				.build();
		}
		var enc = RenderSystem.getDevice().createCommandEncoder();
		try (RenderPass pass = enc.createRenderPass(() -> "mcopt render scale", main.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(blit));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("ColorSampler", level.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void free() {
		if (level != null) level.destroyBuffers();
		if (hud != null) hud.destroyBuffers();
		level = hud = null;
	}
}
