package mcopt.metal.chunk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in chunk-pipeline and server-tick changes. Every switch is a -Dmcopt.chunk.NAME property; with none set no mixin
 * of this package applies (ChunkMixinPlugin), so the game is unchanged.
 *
 * <ul>
 * <li>{@code saveSkip=probe|true} (C2): a chunk write whose bytes equal what the region file already holds, apart from the
 *     LastUpdate stamp, is skipped ({@code true}); {@code probe} compares and counts but always writes. Its own package
 *     and mixin config: mcopt.metal.chunkio, mcopt-metal-chunkio.mixins.json.</li>
 * <li>{@code parse=true|verify} (C4): chunk-section palettes decoded without DFU (FastPalette); verify decodes both ways, keeps
 *     vanilla's result and counts mismatches.</li>
 * <li>{@code serialize=true|verify} (C2/C4): chunk-section palettes encoded for saving without DFU; verify encodes both
 *     ways and compares the serialized bytes.</li>
 * <li>{@code poi=true|verify} (D1): AcquirePoi's POI search as loops (PoiScan), same results and side effects in the same
 *     order; verify runs vanilla for real and checks the loops against it on every call.</li>
 * <li>{@code ticks=true} (D3): ServerLevel.tickChunk without per-pick overhead, same random ticks in the same order.</li>
 * <li>{@code chunkGet=true} (D2/D5): a chunk's section index offset computed once instead of per block access.</li>
 * <li>{@code light=true|verify} (C3): light section maps published as base + delta snapshots instead of full clones
 *     (LightSnapshots); verify checks every snapshot against vanilla's clone. Off (one log line) when -Dmcopt.alloc.lightMap is
 *     on: that one makes the same maps copy-on-write, and the two can't run together.</li>
 * <li>{@code stats=true}: count what the switches did (bench reports read {@link #snapshot()}).</li>
 * </ul>
 */
public final class ChunkOpt {
	private ChunkOpt() { }

	public static final String SAVE_SKIP = mcopt.metal.chunkio.ChunkIo.SAVE_SKIP; // saveSkip lives in mcopt.metal.chunkio
	public static final boolean SAVE_SKIP_ON = mcopt.metal.chunkio.ChunkIo.SAVE_SKIP_ON;
	public static final boolean SAVE_PROBE = mcopt.metal.chunkio.ChunkIo.SAVE_PROBE;
	public static final String PARSE = prop("parse");
	public static final boolean PARSE_VERIFY = "verify".equals(PARSE);
	public static final boolean PARSE_FAST = "true".equals(PARSE) || PARSE_VERIFY;
	public static final boolean SERIALIZE_VERIFY = "verify".equals(prop("serialize"));
	public static final boolean SERIALIZE = "true".equals(prop("serialize")) || SERIALIZE_VERIFY;
	public static final boolean POI_VERIFY = "verify".equals(prop("poi"));
	public static final boolean POI = "true".equals(prop("poi")) || POI_VERIFY;
	public static final boolean TICKS = Boolean.getBoolean("mcopt.chunk.ticks");
	public static final boolean CHUNK_GET = Boolean.getBoolean("mcopt.chunk.chunkGet");
	/**
	 * -Dmcopt.alloc.lightMap makes the light engine's section map copy-on-write; light (below) replaces the same
	 * snapshots with base + delta maps. The two can't run together (the integrated server crashed at world load), so with
	 * alloc.lightMap on, light switches itself off and says so once.
	 */
	public static final boolean ALLOC_LIGHT_MAP = Boolean.getBoolean("mcopt.alloc.lightMap");
	private static final boolean LIGHT_REQUESTED = "true".equals(prop("light")) || "verify".equals(prop("light"));
	public static final boolean LIGHT_VERIFY = "verify".equals(prop("light")) && !ALLOC_LIGHT_MAP;
	public static final boolean LIGHT = LIGHT_REQUESTED && !ALLOC_LIGHT_MAP;
	public static final boolean STATS = Boolean.getBoolean("mcopt.chunk.stats") || SAVE_PROBE || PARSE_VERIFY || POI_VERIFY || LIGHT_VERIFY || SERIALIZE_VERIFY;

	static {
		if (LIGHT_REQUESTED && ALLOC_LIGHT_MAP) {
			System.out.println("mcopt-chunk: light off: mcopt.alloc.lightMap is on (both replace the light engine's section-map copies; they can't run together)");
		}
	}

	private static String prop(String name) {
		String v = System.getProperty("mcopt.chunk." + name);
		return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
	}

	/** Whether the mixin named (simple class name) should apply: each one belongs to exactly one switch. */
	public static boolean mixinEnabled(String simpleName) {
		return switch (simpleName) {
			case "PalettedContainerFactoryMixin" -> PARSE_FAST || SERIALIZE;
			case "AcquirePoiMixin", "PoiSectionAccessor", "SectionStorageAccess" -> POI;
			case "ServerLevelTickMixin", "LevelRandAccessor" -> TICKS;
			case "ChunkAccessSectionMixin" -> CHUNK_GET;
			case "DataLayerStorageMapMixin", "BlockLightMapMixin", "SkyLightMapMixin" -> LIGHT;
			default -> false;
		};
	}


	static {
		// -Dmcopt.chunk.statsLog=true: the counters on stdout when the JVM exits (startup-only runs have no per-phase report).
		if (STATS && Boolean.getBoolean("mcopt.chunk.statsLog")) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("mcopt-chunk: stats " + snapshot()), "mcopt-chunk stats"));
		}
	}

	public static void count(String key, long n) {
		mcopt.metal.chunkio.ChunkIo.count(key, n); // one set of counters for every chunk switch
	}

	public static void count(String key) {
		count(key, 1);
	}

	/** Cumulative counters since start, sorted by name (the bench stores one per phase and diffs them). */
	public static Map<String, Object> snapshot() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("flags", Map.of("saveSkip", SAVE_SKIP, "parse", PARSE,
			"light", LIGHT_VERIFY ? "verify" : LIGHT ? "true" : LIGHT_REQUESTED ? "off (alloc.lightMap)" : ""));
		out.putAll(mcopt.metal.chunkio.ChunkIo.counters());
		return out;
	}
}
