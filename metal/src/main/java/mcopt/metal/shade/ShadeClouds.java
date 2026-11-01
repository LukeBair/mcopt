package mcopt.metal.shade;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.io.InputStream;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * mcopt's stylized clouds: the vanilla cloud map (textures/environment/clouds.png, so resource packs still apply) as a texture of
 * cells, each with a density from its neighbourhood. shade.fsh ray-marches it as blocky columns whose height and shading follow the
 * density; vanilla's own cloud mesh is switched off while these are on (ShadeCloudRendererMixin).
 */
public final class ShadeClouds {
	/** Blocks per cloud cell, as vanilla. */
	static final float CELL = 12f;
	private static GpuTexture tex;
	private static GpuTextureView view;
	private static int size;
	private static boolean failed;

	private ShadeClouds() {
	}

	/** Our clouds replace vanilla's this frame (shaders on, clouds on in Video Settings, and the map loaded). */
	public static boolean active() {
		if (!Shade.ON || !Shade.enabled || Shade.suppress || !Shade.CLOUDS || failed) return false;
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.options.getCloudStatus() != CloudStatus.OFF && mc.level.dimensionType().hasSkyLight();
	}

	/** The cell map (r: cloud, g: density 0..1, b: cloud type 0..1), loading it on first use; null if it couldn't be. */
	static GpuTextureView view() {
		if (view == null && !failed) load();
		return view;
	}

	/** Cells along one side of the (square, wrapping) map. */
	static int size() {
		return size;
	}

	private static float smooth(float x) {
		return x * x * (3 - 2 * x);
	}

	private static void load() {
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("textures/environment/clouds.png"));
			NativeImage src = NativeImage.read(in)) {
			int w = src.getWidth(), h = src.getHeight();
			if (w != h) throw new IllegalStateException("clouds.png is not square: " + w + "x" + h);
			float[] occ = new float[w * h];
			for (int z = 0; z < h; z++) {
				for (int x = 0; x < w; x++) occ[z * w + x] = (src.getPixel(x, z) >>> 24) > 10 ? 1f : 0f;
			}
			// Density: the share of cloud in the 7x7 cells around (wrapping), so big masses read as thick and their edges as thin.
			final int R = 3;
			try (NativeImage out = new NativeImage(w, h, false)) {
				for (int z = 0; z < h; z++) {
					for (int x = 0; x < w; x++) {
						float sum = 0;
						for (int dz = -R; dz <= R; dz++) {
							for (int dx = -R; dx <= R; dx++) sum += occ[Math.floorMod(z + dz, h) * w + Math.floorMod(x + dx, w)];
						}
						int d = Math.round(255 * sum / ((2 * R + 1) * (2 * R + 1)));
						int c = occ[z * w + x] > 0 ? 255 : 0;
						out.setPixel(x, z, 0xFF000000 | c << 16 | d << 8);  // ARGB
					}
				}
				tex = RenderSystem.getDevice().createTexture(() -> "mcopt clouds", 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
				view = RenderSystem.getDevice().createTextureView(tex);
				RenderSystem.getDevice().createCommandEncoder().writeToTexture(tex, out);
				size = w;
			}
		} catch (Exception e) {
			failed = true;
			System.out.println("[mcopt] shade: clouds disabled, " + e);
		}
	}
}
