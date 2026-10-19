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

	/** -Dmcopt.server.benchToggle=true: the bench flips the enabled switches on and off every 100 ticks (paired A/B inside one run). */
	public static final boolean TOGGLE = Boolean.getBoolean("mcopt.server.benchToggle");
	public static volatile boolean phaseOn = true;

	/** False only during the off phases of a toggling bench. */
	public static boolean active() {
		return !TOGGLE || phaseOn;
	}

	public static final boolean COLLIDE_VERIFY = "verify".equals(prop("collide"));
	public static final boolean COLLIDE = "true".equals(prop("collide")) || COLLIDE_VERIFY;
	public static final boolean COLLIDE_INDEX_VERIFY = "verify".equals(prop("collideIndex"));
	public static final boolean COLLIDE_INDEX = "true".equals(prop("collideIndex")) || COLLIDE_INDEX_VERIFY;
	public static final boolean ENTITY_INDEX_VERIFY = "verify".equals(prop("entityIndex"));
	public static final boolean ENTITY_INDEX = "true".equals(prop("entityIndex")) || ENTITY_INDEX_VERIFY;

	private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> COUNTERS = new java.util.concurrent.ConcurrentHashMap<>();

	static {
		if (COLLIDE_VERIFY || ENTITY_INDEX_VERIFY || COLLIDE_INDEX_VERIFY) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("mcopt-server: verify counters " + new java.util.TreeMap<>(COUNTERS)), "mcopt-server stats"));
		}
	}

	public static void count(String key) {
		COUNTERS.computeIfAbsent(key, k -> new java.util.concurrent.atomic.LongAdder()).increment();
	}

	public static String prop(String name) {
		String v = System.getProperty("mcopt.server." + name);
		return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
	}

	/** Whether the mixin named (simple class name) should apply: each one belongs to exactly one switch. */
	public static boolean mixinEnabled(String simpleName) {
		return switch (simpleName) {
			case "ServerBenchMixin" -> BENCH > 0;
			case "BlockCollisionsMixin" -> COLLIDE;
			case "EntityGetterMixin", "EntitySectionMixin", "LevelEntityGetterAdapterMixin", "LevelEntitiesAccessor" -> COLLIDE_INDEX;
			case "EntitySectionStorageMixin", "EntitySectionScanMixin", "ClassInstanceMultiMapAccessor" -> ENTITY_INDEX;
			default -> false;
		};
	}
}
