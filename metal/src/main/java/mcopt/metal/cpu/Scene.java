package mcopt.metal.cpu;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * -Dmcopt.cpu.scene=true: a development scene for the entity/block-entity A/B levers (no effect on gameplay otherwise, and the
 * mixin applies only with the flag). From the title screen it opens (or creates) the superflat world "mcopt-scene", then builds a
 * crowded, frozen scene in front of a spectator camera: chests, shulker boxes, signs, banners, beds, enchanting tables and
 * campfires, item frames with items, dropped items, armor stands and named mobs. Everything is fixed (NoAI, no gravity, noon),
 * so the same picture and the same extract/submit work every frame.
 */
public final class Scene {
	public static final boolean ON = Boolean.getBoolean("mcopt.cpu.scene");
	/** -Dmcopt.cpu.sceneShots=cull: four frames (lever off, on, off, on) grabbed once the scene has settled: screenshots/scene-*.png. */
	public static final String SHOTS_WHAT = System.getProperty("mcopt.cpu.sceneShots", "");
	public static final boolean SHOTS = !SHOTS_WHAT.isEmpty();
	private static final String[] NAMES = {"scene-off1.png", "scene-on1.png", "scene-off2.png", "scene-on2.png"};
	private static int shotFrames, shotStep = -1;
	private static final boolean FIRE = Boolean.getBoolean("mcopt.cpu.sceneFire");
	private static boolean creating;
	private static int ticksInWorld, waitTitle;
	private static boolean built;
	private static int ok, failed;

	private Scene() {
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			ticksInWorld = 0;
			if (!creating && mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null && ++waitTitle > 40) {
				creating = true;
				open(mc);
			}
			return;
		}
		MinecraftServer srv = mc.getSingleplayerServer();
		if (srv == null || mc.player == null) return;
		ticksInWorld++;
		if (!built && ticksInWorld == 200) {
			built = true;
			srv.execute(() -> build(srv));
		}
	}

	private static void open(Minecraft mc) {
		var flows = mc.createWorldOpenFlows();
		if (mc.getLevelSource().levelExists("mcopt-scene")) {
			flows.openWorld("mcopt-scene", () -> { });
			return;
		}
		LevelSettings settings = new LevelSettings("mcopt-scene", GameType.CREATIVE,
			new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
		flows.createFreshLevel("mcopt-scene", settings, new WorldOptions(1L, false, false), Scene::flat, mc.gui.screen());
	}

	private static WorldDimensions flat(HolderLookup.Provider registries) {
		return registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
	}

	private static void run(MinecraftServer srv, CommandSourceStack src, String cmd) {
		try {
			srv.getCommands().performPrefixedCommand(src, cmd);
			ok++;
		} catch (RuntimeException e) {
			failed++;
			System.out.println("mcopt-scene: command failed: " + cmd + ": " + e);
		}
	}

	private static void build(MinecraftServer srv) {
		CommandSourceStack src = srv.createCommandSourceStack().withSuppressedOutput();
		List<String> c = new ArrayList<>();
		c.add("kill @e[type=!player]");
		c.add("time set 6000");
		c.add("weather clear");
		c.add("fill -30 -60 -5 30 -50 45 air");
		final int y = -60;
		String[] mobs = {"cow", "pig", "sheep", "villager", "chicken", "armor_stand", "wolf", "iron_golem"};
		String[] items = {"diamond_sword", "apple", "golden_apple", "compass", "stone", "oak_planks"};
		for (int x = -24; x <= 24; x++) {
			for (int r = 0; r < 3; r++) c.add("setblock " + x + " " + y + " " + (6 + 2 * r) + " chest[facing=south]");
			c.add("setblock " + x + " " + y + " 12 shulker_box");
			c.add("setblock " + x + " " + y + " 14 oak_sign[rotation=8]{front_text:{messages:[\"Hello world\",\"mcopt scene\",\"line three\",\"x=" + x + "\"]}}");
			c.add("setblock " + x + " " + y + " 16 white_banner[rotation=" + (Math.floorMod(x, 16)) + "]");
			if ((x & 1) == 0) {
				c.add("setblock " + x + " " + y + " 18 red_bed[facing=south,part=foot]");
				c.add("setblock " + x + " " + y + " 19 red_bed[facing=south,part=head]");
				c.add("setblock " + x + " " + y + " 21 enchanting_table");
				if (FIRE) c.add("setblock " + x + " " + y + " 4 campfire");
			}
		}
		c.add("fill -25 -60 30 25 -57 30 stone");
		for (int x = -24; x <= 24; x++) {
			for (int h = 0; h < 3; h++) {
				c.add("summon item_frame " + x + " " + (y + h) + " 29 {Facing:2b,Item:{id:\"minecraft:" + items[(x + h + 100) % items.length] + "\",count:1}}");
			}
			for (int r = 0; r < 3; r++) {
				String m = mobs[Math.floorMod(x + r * 3, mobs.length)];
				String name = (x + r) % 5 == 0 ? ",CustomName:\"Mob " + x + "\",CustomNameVisible:1b" : "";
				c.add("summon " + m + " " + x + ".5 " + y + " " + (23 + 2 * r) + ".5 {NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b" + name + "}");
			}
			c.add("summon item " + x + ".5 " + y + " 2.5 {NoGravity:1b,Age:-32768s,PickupDelay:32767s,Item:{id:\"minecraft:" + items[Math.floorMod(x, items.length)] + "\",count:1}}");
		}
		for (String s : c) run(srv, src, s);
		run(srv, src, "gamemode spectator @a");
		run(srv, src, "tick freeze");
		run(srv, src, "tp @a 0.5 -55 -3 0 22");
		System.out.println("mcopt-scene: built, " + ok + " commands ok, " + failed + " failed");
	}

	private static void lever(boolean on) {
		if (SHOTS_WHAT.equals("cull")) Cull.active = on;
	}

	/** Frame start (AbFrameMixin): sets the lever for the frame being grabbed. */
	public static void frameStart() {
		if (!SHOTS || !built || ticksInWorld < 400) return;
		shotFrames++;
		shotStep = shotFrames >= 20 && shotFrames < 24 ? shotFrames - 20 : -1;
		if (shotStep >= 0) lever(shotStep % 2 == 1);
	}

	/** Frame end (AbFrameMixin): grabs the frame this step drew. */
	public static void frameEnd() {
		if (shotStep < 0) return;
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.Screenshot.grab(mc.gameDirectory, NAMES[shotStep], mc.gameRenderer.mainRenderTarget(), 1, message -> { });
		System.out.println("mcopt-scene: grabbed " + NAMES[shotStep]);
		if (shotStep == 3) lever(true);
		shotStep = -1;
	}
}
