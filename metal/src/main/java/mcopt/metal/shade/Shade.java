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
	/** The sun shadow map (needs mcopt.own; one cascade around the camera). */
	static final boolean MAP = Boolean.parseBoolean(System.getProperty("mcopt.shade.map", "false"));
	static final int MAP_RES = Integer.getInteger("mcopt.shade.mapRes", 2048);
	/** Half the cascade's width in blocks. */
	static final float MAP_RADIUS = f("mcopt.shade.mapRadius", 128f);
	/** 1 normals, 2 shadow, 3 ao; 0 the lit picture. */
	static final int DEBUG = Integer.getInteger("mcopt.shade.debug", 0);

	/** Self-test: after the world has rendered for a while, write the main target (before the GUI) to run/screenshots/NAME_STEP.png once per step of mcopt.shade.shotSeq (comma list of off,on,d1,d2,d3), all in one launch. */
	public static final int SHOT = Integer.getInteger("mcopt.shade.shot", 0);
	static final String SHOT_NAME = System.getProperty("mcopt.shade.shotName", "shade");
	static final String[] SEQ = System.getProperty("mcopt.shade.shotSeq", "on").split(",");
	static final long SHOT_DELAY = Long.getLong("mcopt.shade.shotDelay", 20L);
	/** The shade pass is skipped (the "off" step). */
	public static boolean suppress;
	/** Freeze the camera at this yaw/pitch while a shot sequence runs (yaw 999 = leave alone). */
	static final float YAW = f("mcopt.shade.shotYaw", 56f), PITCH = f("mcopt.shade.shotPitch", 5f);
	/** Self-test: set the time of day (ticks) when the shot sequence starts and stop the clock (-1: leave). */
	static final int SHOT_TIME = Integer.getInteger("mcopt.shade.shotTime", -1);
	static int debugNow = DEBUG;
	private static long t0, tStep;
	private static int frames, step, pending = -1;

	/** Called each level frame after the shade pass. */
	public static void maybeShot(com.mojang.blaze3d.pipeline.RenderTarget main) {
		if (SHOT <= 0 || step >= SEQ.length) return;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		if (YAW < 999f) {
			mc.player.setYRot(YAW);
			mc.player.setXRot(PITCH);
			mc.player.yRotO = YAW;
			mc.player.xRotO = PITCH;
		}
		long now = System.nanoTime();
		if (++frames < SHOT) return;
		if (t0 == 0) {
			t0 = now;
			apply(SEQ[0]);
			if (SHOT_TIME >= 0) {
				mc.player.connection.sendCommand("gamerule advance_time false");
				mc.player.connection.sendCommand("time set " + SHOT_TIME);
			}
		}
		long wait = step == 0 ? SHOT_DELAY : 4;
		if (tStep == 0) tStep = now;
		if (now - tStep < wait * 1_000_000_000L) return;
		String name = SEQ[step];
		var p = mc.player;
		System.out.println("[mcopt] shade: shot " + name + " at " + p.getX() + "," + p.getY() + "," + p.getZ() + " yaw " + p.getYRot() + " pitch " + p.getXRot());
		int myStep = step++;
		tStep = 0;
		if (step < SEQ.length) apply(SEQ[step]);
		net.minecraft.client.Screenshot.takeScreenshot(main, img -> {
			try (img) {
				java.nio.file.Path dir = java.nio.file.Path.of("screenshots");
				java.nio.file.Files.createDirectories(dir);
				img.writeToFile(dir.resolve(SHOT_NAME + "_" + name + ".png"));
				System.out.println("[mcopt] shade: wrote " + dir.toAbsolutePath().resolve(SHOT_NAME + "_" + name + ".png"));
			} catch (java.io.IOException e) {
				e.printStackTrace();
			}
		});
	}

	private static void apply(String name) {
		suppress = name.equals("off");
		debugNow = name.startsWith("d") ? Integer.parseInt(name.substring(1)) : 0;
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
