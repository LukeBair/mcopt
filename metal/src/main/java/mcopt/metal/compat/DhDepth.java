package mcopt.metal.compat;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Distant Horizons' depth for the shade pass. DH draws its LODs into its own colour and depth textures (BlazeDhMetaRenderer's
 * DhColorTexture / DhDepthTexture, reverse-Z, 0 to 1) and copies only the colour into the main target, so the level depth
 * ShadeDepth captures has nothing where a LOD is and the pass took them for sky. DH keeps its depth texture until its next
 * frame, so the pass samples it as it is, wherever the level depth is empty. Its projection is the game's with other clip planes
 * (RenderUtil.setClipPlanes), so a depth d is view depth m23 / (d + m22) and the x and y mapping is ours. DH is reached by
 * reflection (its API's renderProxy, the render parameters as ClientApi keeps them): no hard dependency, nothing here touches
 * a DH class unless the mod is loaded. See DhDrawnMixin, ShadePass.
 */
public final class DhDepth {
	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("distanthorizons");
	private static boolean drawn, failed, logged;
	private static GpuTextureView view;
	private static float a, b;
	private static Method viewOf;
	private static Field paramsField;

	private DhDepth() {
	}

	/** DH has copied its LODs into the main target this frame (DhDrawnMixin). */
	public static void drawn() {
		drawn = true;
	}

	public static void endFrame() {
		drawn = false;
		view = null;
	}

	/** Looks up this frame's depth: true if DH drew its LODs and its depth texture and projection could be read. */
	public static boolean refresh() {
		view = null;
		if (!PRESENT || failed || !drawn) return false;
		try {
			ClassLoader cl = DhDepth.class.getClassLoader();
			Object proxy = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed", true, cl).getField("renderProxy").get(null);
			if (proxy == null) return false;
			Class<?> proxyType = Class.forName("com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy", true, cl);
			Object result = proxyType.getMethod("getDhDepthTextureBlazeWrapper").invoke(proxy);
			Class<?> resultType = result.getClass();
			if (!(boolean) resultType.getField("success").get(result)) {
				return fail("DH has no depth texture to give: " + resultType.getField("message").get(result));
			}
			Object wrapper = resultType.getField("payload").get(result);
			if (viewOf == null) viewOf = wrapper.getClass().getMethod("getTextureView");
			Object v = viewOf.invoke(wrapper);
			if (!(v instanceof GpuTextureView tv)) return false;
			if (paramsField == null) {
				paramsField = Class.forName("com.seibel.distanthorizons.core.api.internal.ClientApi", true, cl).getDeclaredField("RENDER_PARAMS");
				paramsField.setAccessible(true);
			}
			Object params = paramsField.get(null);
			Object proj = params.getClass().getField("dhProjectionMatrix").get(params);
			float m22 = proj.getClass().getField("m22").getFloat(proj), m23 = proj.getClass().getField("m23").getFloat(proj);
			if (!(m22 > 0f && m23 > 0f)) return fail("DH's projection isn't reverse-Z as expected (m22 " + m22 + ", m23 " + m23 + ")");
			view = tv;
			a = m22;
			b = m23;
			if (!logged) {
				logged = true;
				System.out.println("[mcopt] shade: Distant Horizons depth in use, " + tv.getWidth(0) + "x" + tv.getHeight(0) + ", near " + near() + " far " + far()
					+ " (LODs to about " + reach() + " blocks)");
			}
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return fail(e.toString());
		}
	}

	private static boolean fail(String why) {
		failed = true;
		System.out.println("[mcopt] shade: Distant Horizons depth not used, " + why);
		return false;
	}

	/** DH's depth texture, valid after refresh() returned true. */
	public static GpuTextureView view() {
		return view;
	}

	/** Its projection's m22 and m23: a depth d is the view depth m23 / (d + m22) blocks. */
	public static float m22() {
		return a;
	}

	public static float m23() {
		return b;
	}

	/** How far the LODs reach, blocks: DH's far clip is twice its render radius plus 512 blocks. */
	public static float reach() {
		return Math.max(0f, far() * 0.5f - 512f);
	}

	private static float near() {
		return b / (a + 1f);
	}

	private static float far() {
		return b / a;
	}
}
