package mcopt.metal.server;

import java.util.Locale;

/**
 * Integrated-server tick optimizations (-Dmcopt.server.*). With none set no mixin of this package applies
 * (ServerMixinPlugin), so the game is unchanged.
 *
 * <ul>
 * <li>{@code bench=N}: build a busy scene (N mobs of each kind, hopper ring, farm) on a platform above the world, measure
 *     MSPT over 1000 ticks, print it, and stop the game. For A/B runs of the other switches; never part of a profile.</li>
 * </ul>
 */
public final class ServerOpt {
	private ServerOpt() { }

	public static final int BENCH = Integer.getInteger("mcopt.server.bench", 0);

	public static String prop(String name) {
		String v = System.getProperty("mcopt.server." + name);
		return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
	}

	/** Whether the mixin named (simple class name) should apply: each one belongs to exactly one switch. */
	public static boolean mixinEnabled(String simpleName) {
		return switch (simpleName) {
			case "ServerBenchMixin" -> BENCH > 0;
			default -> false;
		};
	}
}
