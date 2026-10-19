package mcopt.metal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dmcopt.startup.psoCache=true: compiled render pipeline states kept in an MTLBinaryArchive at
 * &lt;game dir&gt;/mcopt-cache/pso-&lt;key&gt;.bin, so a warm launch skips Metal's pipeline compile (the shader libraries are still
 * built from source, which Metal's own cache already makes cheap). The file name carries the key: mod version, macOS build, GPU
 * name and the native library, so any change starts a fresh archive and the old ones are deleted. A file Metal rejects (corrupt,
 * or from another driver) is deleted and the archive starts empty; nothing here can fail a launch.
 * The archive is written five seconds after the last new pipeline, and at exit.
 */
final class MetalPsoCache {
	static final boolean ON = Boolean.parseBoolean(System.getProperty("mcopt.startup.psoCache", "false"));
	private static Path file;
	private static ScheduledExecutorService saver;
	private static ScheduledFuture<?> pending;
	private static int lastMisses;

	static Object[] stats() {
		if (file == null) return new Object[] {0, 0};
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(4, 12);
			Native.psoStats(out);
			return new Object[] {MemoryUtil.memGetInt(out), MemoryUtil.memGetInt(out + 4)};
		}
	}

	static void open(long ctx, String gpuName) {
		if (!ON) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long os = stack.nmalloc(1, 64);
			Native.osBuild(os, 64);
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("mcopt-metal")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
			md.update(("mcopt-pso-1|" + version + "|" + MemoryUtil.memUTF8(os) + "|" + gpuName + "|" + nativeLibraryHash()).getBytes(StandardCharsets.UTF_8));
			String key = HexFormat.of().formatHex(md.digest()).substring(0, 16);
			Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mcopt-cache");
			Files.createDirectories(dir);
			Path f = dir.resolve("pso-" + key + ".bin");
			try (var old = Files.newDirectoryStream(dir, "pso-*")) {
				for (Path p : old) if (!p.getFileName().toString().startsWith("pso-" + key)) Files.deleteIfExists(p);
			}
			int result = Native.psoOpen(ctx, MemoryUtil.memAddress(stack.UTF8(f.toString())));
			if (result < 0) return;
			file = f;
			saver = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "mcopt-pso-save");
				t.setDaemon(true);
				return t;
			});
			Runtime.getRuntime().addShutdownHook(new Thread(MetalPsoCache::save, "mcopt-pso-exit"));
			System.out.println("mcopt-metal: pipeline cache " + (result == 1 ? "loaded " : "empty, ") + f.getFileName());
		} catch (Throwable t) {
			System.out.println("mcopt-metal: pipeline cache off: " + t);
		}
	}

	/** Called after every pipeline compile: schedules a save once misses stop arriving. */
	static void compiled() {
		if (file == null) return;
		synchronized (MetalPsoCache.class) {
			int misses = (Integer) stats()[1];
			if (misses == lastMisses) return;
			lastMisses = misses;
			if (pending != null) pending.cancel(false);
			pending = saver.schedule(MetalPsoCache::save, 5, TimeUnit.SECONDS);
		}
	}

	private static void save() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Native.psoSave(MemoryUtil.memAddress(stack.UTF8(file.toString())));
		} catch (Throwable t) {
			// a cache that can't be written is just a slower launch
		}
	}

	private static String nativeLibraryHash() throws Exception {
		try (var in = Native.class.getResourceAsStream("/natives/libmcmetal.dylib")) {
			if (in == null) return "none";
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[1 << 16];
			for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
			return HexFormat.of().formatHex(md.digest());
		} catch (IOException e) {
			return "none";
		}
	}
}
