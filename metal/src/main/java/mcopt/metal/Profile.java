package mcopt.metal;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Flag profiles: one place that turns on a set of -Dmcopt.* switches without editing the launcher's JVM arguments.
 * <ul>
 * <li>{@code -Dmcopt.profile=NAME}, or {@code profile=NAME} in {@code <game dir>/config/mcopt.properties}, applies
 * the jar's {@code /mcopt/profiles/NAME.properties} (e.g. {@code recommended}: the measured, accepted wins).</li>
 * <li>Any {@code mcopt.*} key in config/mcopt.properties is applied too, and wins over the profile's value.</li>
 * <li>A flag given on the command line wins over both: a key is only set when System.getProperty(key) is still null.</li>
 * </ul>
 * With no profile named anywhere, {@code indie} applies (our own renderer); {@code profile=none} applies no profile,
 * exactly the old no-profile behaviour (vanilla's renderer). If config/mcopt.properties is absent it is written with the effective profile ({@code indie}, or what -Dmcopt.profile names)
 * and comment lines on how to turn it off and how to try far terrain. The retired Sodium-era profiles ({@link #RETIRED}) apply
 * {@code indie}. It runs first in every mixin config plugin
 * and in the preLaunch entrypoint, before any of our classes read a flag; the first call does the work, later calls
 * return at once.
 */
public final class Profile {
	private static boolean applied;
	/** Profiles from the Sodium builds (no own renderer, Sodium-only keys): a config still naming one gets {@link #DEFAULT}. */
	static final java.util.Set<String> RETIRED = java.util.Set.of("recommended", "alpha");
	static final String DEFAULT = "indie";

	private Profile() {
	}

	public static synchronized void apply() {
		if (applied) return;
		applied = true;
		applyFlags();
		distantHorizonsOnOpenGl();
	}

	/**
	 * Distant Horizons draws through the Metal backend with its default Blaze3D renderer (DhRenderApiMixin), next to our own
	 * terrain: it draws its LODs in LevelRenderer.prepareTranslucents and its fades in executeOutline / executeOit, none of
	 * which our renderer replaces. Its OpenGL renderer can't: DH picks that one when its config says renderingEngine =
	 * "OPEN_GL" (or when Iris is loaded, which needs Sodium and so never loads with this build), and on the Metal backend it
	 * then stops at startup ("API doesn't match"). Then mcopt.metal defaults to false (OpenGL, exactly as -Dmcopt.metal=false;
	 * no own renderer). An explicit mcopt.metal wins. Without DH nothing here runs.
	 */
	private static void distantHorizonsOnOpenGl() {
		if (System.getProperty("mcopt.metal") != null) return;
		String why;
		try {
			net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
			if (!loader.isModLoaded("distanthorizons")) return;
			why = loader.isModLoaded("iris") ? "with Iris" : dhOpenGlEngine() ? "set to its OpenGL renderer" : null;
		} catch (Throwable t) {
			return;
		}
		if (why == null) return;
		System.setProperty("mcopt.metal", "false");
		System.out.println("[mcopt] mcopt: Distant Horizons " + why + " needs OpenGL, using OpenGL; mcopt's other optimizations stay on (-Dmcopt.metal=true overrides)");
	}

	/** config/DistantHorizons.toml says renderingEngine = "OPEN_GL" (its default is AUTO: Blaze3D on 26.x). */
	private static boolean dhOpenGlEngine() {
		Path toml = gameDir().resolve("config").resolve("DistantHorizons.toml");
		if (!Files.isRegularFile(toml)) return false;
		try {
			for (String line : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
				String t = line.strip();
				if (t.startsWith("renderingEngine")) return t.replace(" ", "").startsWith("renderingEngine=\"OPEN_GL\"");
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[mcopt] profile: can't read " + toml + ": " + e);
		}
		return false;
	}

	private static void applyFlags() {
		Properties file = new Properties();
		Path cfg = gameDir().resolve("config").resolve("mcopt.properties");
		if (Files.isRegularFile(cfg)) {
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				file.load(r);
			} catch (IOException e) {
				System.out.println("[mcopt] profile: can't read " + cfg + ": " + e);
			}
		}
		String name = System.getProperty("mcopt.profile", file.getProperty("profile", "")).trim();
		if (name.isEmpty()) name = DEFAULT;
		if (RETIRED.contains(name)) {
			System.out.println("[mcopt] profile " + name + " was for the Sodium builds and is retired: using " + DEFAULT);
			name = DEFAULT;
		}
		if (!Files.exists(cfg)) writeDefault(cfg, name); // first launch: record the effective profile (none stays none)
		Map<String, String> flags = new TreeMap<>();
		if (!name.isEmpty() && !name.equals("none")) {
			Properties p = new Properties();
			try (InputStream in = Profile.class.getResourceAsStream("/mcopt/profiles/" + name + ".properties")) {
				if (in == null) {
					System.out.println("[mcopt] profile: no profile named '" + name + "' (ignored)");
				} else {
					p.load(in);
				}
			} catch (IOException e) {
				System.out.println("[mcopt] profile " + name + ": " + e);
			}
			p.stringPropertyNames().forEach(k -> flags.put(k, p.getProperty(k).trim()));
		}
		file.stringPropertyNames().stream().filter(k -> k.startsWith("mcopt.")).forEach(k -> flags.put(k, file.getProperty(k).trim()));
		if (flags.isEmpty()) return;
		StringBuilder set = new StringBuilder(), kept = new StringBuilder();
		flags.forEach((k, v) -> {
			if (System.getProperty(k) == null) {
				System.setProperty(k, v);
				set.append(' ').append(k).append('=').append(v);
			} else {
				kept.append(' ').append(k).append('=').append(System.getProperty(k));
			}
		});
		System.out.println("[mcopt] profile " + (name.isEmpty() ? "(config only)" : name) + ": set" + set
			+ (kept.isEmpty() ? "" : "; kept from the command line:" + kept));
	}

	private static int gpuCores = -2;

	/** The GPU's core count from the IORegistry (AGXAccelerator "gpu-core-count"), -1 if unreadable; read once. */
	public static synchronized int gpuCores() {
		if (gpuCores == -2) {
			String s = run("/usr/sbin/ioreg", "-rd1", "-c", "AGXAccelerator");
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"gpu-core-count\"\\s*=\\s*(\\d+)").matcher(s);
			gpuCores = m.find() ? Integer.parseInt(m.group(1)) : -1;
		}
		return gpuCores;
	}

	private static String run(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
			byte[] b = p.getInputStream().readAllBytes();
			p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
			return new String(b, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private static void writeDefault(Path cfg, String name) {
		String text = """
			# mcopt settings. Written on first launch; edit freely.
			#
			# The indie profile: mcopt's own terrain renderer on Apple Metal (no Sodium), plus measured startup and
			# thread-priority options. To turn all of it off (vanilla's renderer), change the next line to:  profile=none
			profile=%s
			#
			# Single switches win over the profile, e.g. our renderer off but the rest on:
			#mcopt.own=false
			# Far terrain (EXPERIMENTAL, off by default; for Macs with 10 or more GPU cores): remove the # below.
			#mcopt.lod=true
			""".formatted(name);
		try {
			Files.createDirectories(cfg.getParent());
			Files.writeString(cfg, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
			System.out.println("[mcopt] profile: wrote " + cfg + " (profile=" + name + ")");
		} catch (IOException e) {
			System.out.println("[mcopt] profile: can't write " + cfg + ": " + e);
		}
	}

	private static Path gameDir() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		} catch (Throwable t) {
			return Path.of("").toAbsolutePath();
		}
	}
}
