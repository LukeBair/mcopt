package mcopt.metal.shade;

/**
 * mcopt's built-in shaders (docs/SHADERS.md), stage 1: a screen-space pass after the level. Opt-in with
 * {@code -Dmcopt.shade=true}; with it off nothing here runs and the picture is vanilla's.
 */
public final class Shade {
	public static final boolean ON = Boolean.getBoolean("mcopt.shade");
	/** Sun shadow darkening 0..1. */
	static final float SHADOW = f("mcopt.shade.shadow", 0.55f);
	/** Ambient occlusion strength 0..1. */
	static final float AO = f("mcopt.shade.ao", 0.6f);
	/** Tonemap / grade amount 0..1. */
	static final float GRADE = f("mcopt.shade.grade", 0.5f);
	/** Contact shadow reach in blocks. */
	static final float SHADOW_LEN = f("mcopt.shade.shadowLen", 24f);
	/** Ambient occlusion radius in blocks. */
	static final float AO_RADIUS = f("mcopt.shade.aoRadius", 1.2f);
	/** 1 normals, 2 shadow, 3 ao; 0 the lit picture. */
	static final int DEBUG = Integer.getInteger("mcopt.shade.debug", 0);

	/** Self-test: write the main target (before the GUI) to run/screenshots/NAME.png after this many frames (0 off), then once. Works with shade off too. */
	public static final int SHOT = Integer.getInteger("mcopt.shade.shot", 0);
	static final String SHOT_NAME = System.getProperty("mcopt.shade.shotName", "shade");
	private static int frames;
	private static boolean shot;

	/** Called each level frame after the shade pass. */
	public static void maybeShot(com.mojang.blaze3d.pipeline.RenderTarget main) {
		if (SHOT <= 0 || shot || ++frames < SHOT) return;
		shot = true;
		net.minecraft.client.Screenshot.takeScreenshot(main, img -> {
			try (img) {
				java.nio.file.Path dir = java.nio.file.Path.of("screenshots");
				java.nio.file.Files.createDirectories(dir);
				img.writeToFile(dir.resolve(SHOT_NAME + ".png"));
				System.out.println("[mcopt] shade: wrote " + dir.toAbsolutePath().resolve(SHOT_NAME + ".png"));
			} catch (java.io.IOException e) {
				e.printStackTrace();
			}
		});
	}

	private static final String[] SOURCES = {"post/shade.fsh", "post/shade_blit.fsh"};

	/** The listing of shader files with mcopt's own added (see ShadeShaderManagerMixin). */
	public static java.util.Map<net.minecraft.resources.Identifier, net.minecraft.server.packs.resources.Resource> addSources(
		java.util.Map<net.minecraft.resources.Identifier, net.minecraft.server.packs.resources.Resource> listed) {
		java.util.Map<net.minecraft.resources.Identifier, net.minecraft.server.packs.resources.Resource> out = new java.util.HashMap<>(listed);
		for (String name : SOURCES) {
			String path = "assets/mcopt/shaders/" + name;
			out.put(net.minecraft.resources.Identifier.fromNamespaceAndPath("mcopt", "shaders/" + name),
				new net.minecraft.server.packs.resources.Resource(null, () -> {
					java.io.InputStream in = Shade.class.getClassLoader().getResourceAsStream(path);
					if (in == null) throw new java.io.FileNotFoundException(path);
					return in;
				}));
		}
		return out;
	}

	private Shade() {
	}

	private static float f(String key, float def) {
		String v = System.getProperty(key);
		if (v == null) return def;
		try {
			return Float.parseFloat(v);
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
