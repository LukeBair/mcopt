package mcopt.metal.shade;

/**
 * mcopt's built-in shaders (docs/SHADERS.md), stage 1: a screen-space pass after the level. Opt-in with
 * {@code -Dmcopt.shade=true}; with it off nothing here runs and the picture is vanilla's.
 */
public final class Shade {
	public static final boolean ON = Boolean.getBoolean("mcopt.shade");
	/** Sun shadow darkening 0..1. */
	static float SHADOW = f("mcopt.shade.shadow", 0.85f);
	/** Ambient occlusion strength 0..1. */
	static float AO = f("mcopt.shade.ao", 0.8f);
	/** Tonemap / grade amount 0..1. */
	static float GRADE = f("mcopt.shade.grade", 0.7f);
	/** Contact shadow reach in blocks. */
	static float SHADOW_LEN = f("mcopt.shade.shadowLen", 24f);
	/** Ambient occlusion radius in blocks. */
	static float AO_RADIUS = f("mcopt.shade.aoRadius", 1.2f);
	/** The sun shadow map (needs mcopt.own; one cascade around the camera). */
	static boolean MAP = Boolean.parseBoolean(System.getProperty("mcopt.shade.map", "true"));
	static final int MAP_RES = Integer.getInteger("mcopt.shade.mapRes", 2048);
	/** Redraw the shadow map at least every this many frames (it is also redrawn when the camera moves a block or the sun moves). */
	static int MAP_EVERY = Integer.getInteger("mcopt.shade.mapEvery", 6);
	/** Half the cascade's width in blocks. */
	static float MAP_RADIUS = f("mcopt.shade.mapRadius", 128f);
	/** The level has a sun (the overworld): no sky light in the Nether or the End, so no sun shadows or light colour there. */
	static boolean sunny(net.minecraft.client.multiplayer.ClientLevel level) {
		return level.dimensionType().hasSkyLight();
	}

	/** Shadows and ambient occlusion at half resolution (upsampled by depth): about a quarter of their cost. */
	static boolean HALF = Boolean.parseBoolean(System.getProperty("mcopt.shade.half", "true"));
	/** Wide soft shadow edges (16-tap filter, a little grainy) instead of solid shadows with a slightly soft edge (4 lookups). */
	static boolean SOFT = Boolean.getBoolean("mcopt.shade.soft");
	/** mcopt's stylized clouds instead of vanilla's (ShadeClouds). */
	static boolean CLOUDS = Boolean.parseBoolean(System.getProperty("mcopt.shade.clouds", "true"));
	/** Blocky ocean waves and shore foam (ShadeWater; whether water is tagged for them is decided when it's meshed). */
	public static boolean WAVES = Boolean.parseBoolean(System.getProperty("mcopt.shade.waves", "true"));
	/** God rays through canopy gaps in forests (ShadeRays). */
	static boolean RAYS = false;  // (switched off for now: the shafts weren't reading in game; mcopt.shade.rays is ignored)
	/** How far the stylized clouds reach, blocks. */
	static float CLOUD_DIST = f("mcopt.shade.cloudDist", 640f);
	/** Coloured block light strength 0..1 (ShadeLights). */
	static float BLOCK_LIGHT = f("mcopt.shade.blockLight", 0.6f);
	/** Sun / moon light colour by time of day, 0..1. */
	static float LIGHT = f("mcopt.shade.light", 0.8f);
	/** 1 normals, 2 shadow, 3 ao; 0 the lit picture. */
	static final int DEBUG = Integer.getInteger("mcopt.shade.debug", 0);

	/** Self-test: after the world has rendered for a while, write the main target (before the GUI) to run/screenshots/NAME_STEP.png once per step of mcopt.shade.shotSeq (comma list of off,on,d1,d2,d3), all in one launch. */
	public static final int SHOT = Integer.getInteger("mcopt.shade.shot", 0);
	static final String SHOT_NAME = System.getProperty("mcopt.shade.shotName", "shade");
	static final String[] SEQ = System.getProperty("mcopt.shade.shotSeq", "on").split(",");
	static final long SHOT_DELAY = Long.getLong("mcopt.shade.shotDelay", 20L);
	/** The shade pass is skipped (the "off" step). */
	public static boolean suppress;
	/** The settings screen's on/off: with mcopt.shade on at launch, the pass can be switched off and on live. */
	public static boolean enabled = true;
	/** Freeze the camera at this yaw/pitch while a shot sequence runs (yaw 999 = leave alone). */
	static final float YAW = f("mcopt.shade.shotYaw", 56f), PITCH = f("mcopt.shade.shotPitch", 5f);
	/** Self-test: set the time of day (ticks) when the shot sequence starts and stop the clock (-1: leave). */
	static final int SHOT_TIME = Integer.getInteger("mcopt.shade.shotTime", -1);
	/** Self-test: teleport to "x,y,z" when the sequence starts. */
	static final String SHOT_POS = System.getProperty("mcopt.shade.shotPos", "");
	/** Self-test: a command to run when the sequence starts (e.g. "execute in minecraft:the_nether run tp @s 0 70 0"). */
	static final String SHOT_CMD = System.getProperty("mcopt.shade.shotCmd", "");
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
			if (!SHOT_POS.isEmpty()) mc.player.connection.sendCommand("execute in minecraft:overworld run tp @s " + SHOT_POS.replace(',', ' '));
			if (!SHOT_CMD.isEmpty()) mc.player.connection.sendCommand(SHOT_CMD.replace('#', ' '));
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
		var p = net.minecraft.client.Minecraft.getInstance().player;
		if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(suppress ? "shade OFF" : "shade ON"));
		debugNow = name.startsWith("d") ? Integer.parseInt(name.substring(1)) : 0;
	}

	private static final String[] SOURCES = {"post/shade.fsh", "post/shade_occ.fsh", "post/shade_blit.fsh"};

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

	/** The settings screen's reset: the built-in defaults above (ignoring -D overrides). */
	static void reset() {
		SHADOW = 0.85f;
		AO = 0.8f;
		GRADE = 0.7f;
		SHADOW_LEN = 24f;
		AO_RADIUS = 1.2f;
		MAP = true;
		MAP_RADIUS = 128f;
		MAP_EVERY = 6;
		LIGHT = 0.8f;
		HALF = true;
		SOFT = false;
		CLOUDS = true;
		WAVES = true;
		RAYS = false;
		CLOUD_DIST = 640f;
		BLOCK_LIGHT = 0.6f;
		debugNow = 0;
		enabled = true;
	}

	static String pct(double v) {
		return Math.round(v * 100) + "%";
	}

	static String blocks(double v) {
		return v < 10 ? String.format(java.util.Locale.ROOT, "%.2f", v) : Long.toString(Math.round(v));
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
