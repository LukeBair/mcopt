package mcopt.metal.shade;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.WeatherData;

/**
 * The sky's weather for the shaders: how much of each kind of cloud is up and how thick the fog is (shade.fsh draws them). By
 * default it follows vanilla's weather and the biomes around the player: fair-weather cumulus, cirrus on some days, a cirrostratus
 * veil building ahead of rain, cumulonimbus towers in thunderstorms (and on humid tropical afternoons), and fog on some mornings in
 * the temperate, wet and coastal biomes, more often after rain. /weather can force a type (WeatherCommand mixin); a weather mod can
 * replace the whole model with setProvider. What's drawn eases toward the target, so changes roll in rather than snap.
 */
public final class ShadeSky {
	/** The amount of each layer. All 0..1 but cumulus, 0..2 (1 is the cloud map as vanilla draws it, 2 overcast). */
	public static final class State {
		public float cumulus = 1f, cumulonimbus, cirrus, cirrostratus, fog;
		/** World y of the fog's top. */
		public float fogTop = 72f;

		void set(State o) {
			cumulus = o.cumulus;
			cumulonimbus = o.cumulonimbus;
			cirrus = o.cirrus;
			cirrostratus = o.cirrostratus;
			fog = o.fog;
			fogTop = o.fogTop;
		}
	}

	/**
	 * Sets the sky the drawn one eases toward, each client tick. The target comes in holding the built-in model's answer (vanilla
	 * weather and biomes), so a provider can adjust it or overwrite every field.
	 */
	public interface Provider {
		void sky(ClientLevel level, BlockPos camera, State target);
	}

	/** A kind of sky /weather can hold. */
	public enum Type { CUMULUS, CUMULONIMBUS, CIRRUS, CIRROSTRATUS, CLEAR }

	/** What the shaders draw now. */
	static final State now = new State();
	private static final State target = new State();
	private static Provider provider;
	// /weather's overrides, until a game time. Single player: the command runs on the integrated server, in this JVM.
	private static volatile Type type;
	private static volatile long typeUntil, fogUntil, fastUntil;
	// The climate around the player, eased: base temperature, downfall, share of ocean, and the valley floor's height.
	private static float temp = 0.7f, wet = 0.5f, ocean, floor = Float.NaN;
	private static long lastRain = Long.MIN_VALUE / 2;
	private static int sinceSample = 1000;
	private static ClientLevel lastLevel;

	private ShadeSky() {
	}

	/** Replaces the built-in model (null restores it). */
	public static void setProvider(Provider p) {
		provider = p;
	}

	/** /weather <type> [duration]: holds that sky until game time `until`. */
	public static void force(Type t, long until) {
		type = t;
		typeUntil = until;
		fastUntil = until;
	}

	/** /weather fog [duration]: fog until game time `until`. */
	public static void forceFog(long until) {
		fogUntil = until;
		fastUntil = until;
	}

	/** /weather clear, rain or thunder: back to the model (quickly, so the change shows). */
	public static void release(long now) {
		type = null;
		fogUntil = 0;
		fastUntil = now + 200;
	}

	/** Each client tick (ClientLevel.animateTick, around the player at x, y, z). */
	public static void tick(ClientLevel level, int x, int y, int z) {
		long time = level.getGameTime();
		if (level != lastLevel) {
			lastLevel = level;
			floor = Float.NaN;
			sinceSample = 1000;
		}
		if (++sinceSample >= 20) {
			sinceSample = 0;
			sample(level, x, z);
		}
		if (level.getRainLevel(1f) > 0.2f) lastRain = time;
		BlockPos cam = new BlockPos(x, y, z);
		model(level, time, target);
		if (provider != null) provider.sky(level, cam, target);
		Type t = time < typeUntil ? type : null;
		if (t != null) {
			target.cumulonimbus = 0f;
			target.cirrus = 0f;
			target.cirrostratus = 0f;
			switch (t) {
				case CUMULUS -> target.cumulus = 1.2f;
				case CUMULONIMBUS -> {
					target.cumulus = 1f;
					target.cumulonimbus = 1f;
				}
				case CIRRUS -> {
					target.cumulus = 0.35f;
					target.cirrus = 1f;
				}
				case CIRROSTRATUS -> {
					target.cumulus = 0.3f;
					target.cirrus = 0.4f;
					target.cirrostratus = 1f;
				}
				case CLEAR -> target.cumulus = 0f;
			}
		}
		if (time < fogUntil) target.fog = 1f;
		// About 30 s for the model's changes; 3 s right after a /weather.
		float k = time < fastUntil + 100 ? 0.035f : 0.0035f;
		now.cumulus += (target.cumulus - now.cumulus) * k;
		now.cumulonimbus += (target.cumulonimbus - now.cumulonimbus) * k;
		now.cirrus += (target.cirrus - now.cirrus) * k;
		now.cirrostratus += (target.cirrostratus - now.cirrostratus) * k;
		now.fog += (target.fog - now.fog) * k;
		now.fogTop += (target.fogTop - now.fogTop) * Math.max(k, 0.01f);
	}

	/** The built-in model: vanilla's rain and thunder, the time of day, the day's roll and the climate around the player. */
	private static void model(ClientLevel level, long time, State s) {
		float rain = level.getRainLevel(1f), thunder = level.getThunderLevel(1f);
		long clock = level.getOverworldClockTime();
		// Days start at midnight (clock 18000), so a morning's fog and its afternoon's storms are the same day's.
		long day = Math.floorDiv(clock + 6000, 24000);
		float tod = Math.floorMod(clock, 24000L);  // 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight
		boolean tropical = temp >= 0.85f && wet >= 0.7f, dry = wet < 0.3f, cold = temp < 0.3f;

		// Fair weather: the day's mix of cumulus and cirrus. Dry lands get clearer skies.
		double r = hash(day, 1);
		float cumulus = r < 0.15 ? 0.35f : r < 0.35 ? 0.6f : 1f;
		float cirrus = hash(day, 2) < 0.35 ? (float) (0.5 + 0.5 * hash(day, 3)) : 0f;
		if (dry) cumulus *= 0.5f;
		if (cirrus > 0f) cumulus = Math.min(cumulus, 0.7f);
		// Ahead of rain: cirrus thickening into a veil (single player can see when the rain is due).
		float veil = 0f;
		int due = rainDue();
		if (due >= 0) {
			cirrus = Math.max(cirrus, 1f - Math.min(1f, (due - 6000) / 12000f));
			veil = 1f - Math.min(1f, due / 9000f);
		}
		// Afternoon storms: towers build late morning, peak mid afternoon and decay by evening.
		float storms = tropical ? 0.45f : (temp >= 0.5f && wet >= 0.4f && !cold ? 0.12f : 0f);
		float afternoon = smooth(3500f, 7500f, tod) * (1f - smooth(11000f, 13500f, tod));
		float cb = hash(day, 4) < storms ? afternoon : 0f;

		s.cumulus = lerp(cumulus * (1f - 0.6f * veil), 1f + 0.6f * rain, rain);
		s.cirrus = cirrus * (1f - rain);
		s.cirrostratus = Math.max(veil * (1f - rain), 0.8f * rain);
		s.cumulonimbus = cold ? 0f : Math.max(cb * (1f - rain), thunder);

		// Fog: some mornings in the temperate, wet and coastal biomes, more often within a day of rain; it builds after midnight and
		// burns off through the morning. A few foggy days stay grey all day. Light mist in rain.
		float chance = dry ? 0.03f : wet >= 0.8f ? 0.45f : cold ? 0.2f : (temp >= 0.3f && temp <= 1f ? 0.35f : 0.15f);
		chance = Math.max(chance, 0.2f * ocean);
		if (time - lastRain < 30000 && !dry) chance += 0.35f;
		float fog = 0f;
		if (hash(day, 5) < chance) {
			float morning = Math.max(smooth(17500f, 21000f, tod), 1f - smooth(1000f, 4500f, tod));
			fog = (float) (0.55 + 0.45 * hash(day, 6)) * (hash(day, 7) < 0.15 ? 1f : morning);
		}
		s.fog = Math.max(fog, dry ? 0f : 0.25f * rain);
		// The fog fills the valleys: a few blocks up from the valley floor around the player (or the sea), deeper when thick.
		float base = Float.isNaN(floor) ? level.getSeaLevel() : Math.max(floor, level.getSeaLevel() - 1);
		s.fogTop = base + 8f + 10f * s.fog;
	}

	/** Ticks until vanilla's rain starts, or -1 if it's raining or not known (only single player can see the server's timers). */
	private static int rainDue() {
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) return -1;
		WeatherData w = server.getWeatherData();
		if (w.isRaining() || w.getClearWeatherTime() > 0) return -1;
		return w.getRainTime();
	}

	/** The climate around (x, z): biomes at the centre and 8 points 48 blocks out, and the valley floor from the heightmap. */
	private static void sample(ClientLevel level, int x, int z) {
		float t = 0, d = 0, o = 0;
		int n = 0;
		for (int i = -1; i <= 1; i++) {
			for (int j = -1; j <= 1; j++) {
				Holder<Biome> b = level.getBiome(new BlockPos(x + i * 48, level.getSeaLevel(), z + j * 48));
				t += b.value().getBaseTemperature();
				d += downfall(b.value());
				o += b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER) ? 1 : 0;
				n++;
			}
		}
		float a = Float.isNaN(floor) ? 1f : 0.2f;
		temp += (t / n - temp) * a;
		wet += (d / n - wet) * a;
		ocean += (o / n - ocean) * a;
		// The valley floor: a low quantile of the ground heights on a grid out to 96 blocks (loaded chunks only).
		int[] h = new int[169];
		int m = 0, min = level.getMinY() + 1;
		for (int i = -6; i <= 6; i++) {
			for (int j = -6; j <= 6; j++) {
				int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + i * 16, z + j * 16);
				if (g > min) h[m++] = g;
			}
		}
		if (m < 8) return;
		java.util.Arrays.sort(h, 0, m);
		float f = h[m / 5];
		floor = Float.isNaN(floor) ? f : floor + (f - floor) * 0.2f;
	}

	private static Method DOWNFALL;
	private static Field CLIMATE;
	private static boolean noDownfall;

	/** The biome's downfall (private in vanilla; 0.5 if it can't be read). */
	private static float downfall(Biome b) {
		if (noDownfall) return 0.5f;
		try {
			if (CLIMATE == null) {
				CLIMATE = Biome.class.getDeclaredField("climateSettings");
				CLIMATE.setAccessible(true);
				DOWNFALL = CLIMATE.getType().getDeclaredMethod("downfall");
				DOWNFALL.setAccessible(true);
			}
			return (float) DOWNFALL.invoke(CLIMATE.get(b));
		} catch (ReflectiveOperationException | RuntimeException e) {
			noDownfall = true;
			System.out.println("[mcopt] shade: no biome downfall, " + e);
			return 0.5f;
		}
	}

	private static double hash(long day, int salt) {
		long h = day * 0x9E3779B97F4A7C15L + salt * 0xD1B54A32D192ED03L;
		h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53;
	}

	private static float smooth(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0f, 1f);
		return t * t * (3 - 2 * t);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
