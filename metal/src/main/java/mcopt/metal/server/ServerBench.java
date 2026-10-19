package mcopt.metal.server;

import java.util.Arrays;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;

/**
 * The benchmark scene of -Dmcopt.server.bench=N: a stone arena at y=200 over the origin with N villagers, N cows, N chickens,
 * N/2 husks and N/4 iron golems, a ring of hoppers circulating items, a wheat field, and flowing water. Built by commands once
 * a player is in the world; 300 ticks of warm-up, then 1000 timed ticks; the result is printed and the game stops.
 */
public final class ServerBench {
	private ServerBench() { }

	private static final int BUILD_AT = 200, WARMUP = 300, MEASURE = Integer.getInteger("mcopt.server.benchTicks", 1000);
	private static int tick;
	private static long t0;
	private static final long[] samples = new long[MEASURE];
	private static int n;

	private static void run(MinecraftServer server, String cmd) {
		CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(src, cmd);
	}

	public static void start(MinecraftServer server) {
		t0 = System.nanoTime();
		if (server.getPlayerCount() == 0) return;
		tick++;
		if (tick == 1) {
			for (String c : new String[] {"gamerule doMobSpawning false", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
				"time set noon", "difficulty normal", "gamemode creative @a", "tp @a 0 201 0"}) run(server, c);
		}
		if (tick == BUILD_AT) build(server);
	}

	private static void build(MinecraftServer server) {
		int k = ServerOpt.BENCH;
		String[] cmds = {
			"fill -24 199 -24 24 199 24 stone", "fill -24 200 -24 24 203 24 air", "fill -25 200 -25 25 203 -25 stone", "fill -25 200 25 25 203 25 stone",
			"fill -25 200 -25 -25 203 25 stone", "fill 25 200 -25 25 203 25 stone",
			// hopper ring (x -8..8, z -8..8, y 200): each side points to the next
			"fill -8 200 -8 7 200 -8 hopper[facing=east]", "fill 8 200 -8 8 200 7 hopper[facing=south]",
			"fill 8 200 8 -7 200 8 hopper[facing=west]", "fill -8 200 8 -8 200 -7 hopper[facing=north]",
			// farm
			"fill 12 199 12 22 199 22 farmland", "fill 12 200 12 22 200 22 wheat[age=3]", "fill 11 199 11 23 199 11 water", "setblock 0 200 0 stone",
			// water channel
			"setblock -20 202 -20 water", "fill -20 200 -20 -20 200 -10 stone",
		};
		for (String c : cmds) run(server, c);
		for (int i = 0; i < k; i++) {
			int x = (i * 7) % 40 - 20, z = (i * 13) % 40 - 20;
			if (Math.abs(x) < 10 && Math.abs(z) < 10 || x > 10 && z > 10) { x += 17; z -= 17; }
			run(server, "summon villager " + x + " 200 " + z + " {PersistenceRequired:1b}");
			run(server, "summon cow " + (x + 1) + " 200 " + z + " {PersistenceRequired:1b}");
			run(server, "summon chicken " + x + " 200 " + (z + 1) + " {PersistenceRequired:1b}");
			if (i % 2 == 0) run(server, "summon husk " + (x - 1) + " 200 " + z + " {PersistenceRequired:1b}");
			if (i % 4 == 0) run(server, "summon iron_golem " + x + " 200 " + (z - 1) + " {PersistenceRequired:1b}");
		}
		String[] items = {"stone", "dirt", "oak_log", "sand", "gravel", "coal", "iron_ingot", "gold_ingot", "apple", "bread", "carrot", "potato"};
		for (int i = 0; i < 150; i++) {
			run(server, "summon item " + (-7 + i % 15) + " 201 -8 {Item:{id:\"" + items[i % items.length] + "\",count:" + (1 + i % 5) + "}}");
		}
		System.out.println("mcopt-bench: scene built (" + k + " per kind)");
	}

	public static void end(MinecraftServer server) {
		if (tick <= BUILD_AT + WARMUP) return;
		samples[n++] = System.nanoTime() - t0;
		if (n == MEASURE) {
			long[] s = samples.clone();
			Arrays.sort(s);
			double sum = 0;
			for (long v : s) sum += v;
			int entities = 0;
			for (var level : server.getAllLevels()) for (var e : level.getAllEntities()) entities++;
			System.out.printf("mcopt-bench: RESULT mean=%.2f median=%.2f p95=%.2f p99=%.2f max=%.2f ms over %d ticks, %d entities%n",
				sum / MEASURE / 1e6, s[MEASURE / 2] / 1e6, s[MEASURE * 95 / 100] / 1e6, s[MEASURE * 99 / 100] / 1e6, s[MEASURE - 1] / 1e6, MEASURE, entities);
			System.out.flush();
			new Thread(() -> System.exit(0), "mcopt-bench exit").start();
		}
	}
}
