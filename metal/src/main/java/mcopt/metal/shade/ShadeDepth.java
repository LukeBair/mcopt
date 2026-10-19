package mcopt.metal.shade;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/**
 * A copy of the level's depth, taken right before vanilla clears the main depth buffer for the first-person hand
 * (GameRenderer.render3dHud). The shade passes (and later the shadow lookups) read this instead of the main target's
 * depth, which by applyPostEffects holds only the hand.
 */
public final class ShadeDepth {
	private static GpuTexture tex;
	private static GpuTextureView view;
	private static boolean valid;

	private ShadeDepth() {
	}

	public static void capture(RenderTarget main) {
		if (main.getDepthTexture() == null) return;
		int w = main.width, h = main.height;
		if (tex == null || tex.getWidth(0) != w || tex.getHeight(0) != h) {
			release();
			tex = RenderSystem.getDevice().createTexture(() -> "mcopt shade depth", 15, GpuFormat.D32_FLOAT, w, h, 1, 1);
			view = RenderSystem.getDevice().createTextureView(tex);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getDepthTexture(), tex, 0, 0, 0, 0, 0, w, h);
		valid = true;
	}

	/** The captured level depth for this frame, or null if the hook did not run. */
	public static GpuTextureView view() {
		return valid ? view : null;
	}

	/** Called once the frame's shade work is done. */
	public static void endFrame() {
		valid = false;
	}

	private static void release() {
		if (view != null) view.close();
		if (tex != null) tex.close();
		view = null;
		tex = null;
	}
}
