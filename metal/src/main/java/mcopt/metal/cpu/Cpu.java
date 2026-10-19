package mcopt.metal.cpu;

import java.lang.reflect.Field;

/**
 * Opt-in CPU-side render-loop work. Every lever here is off unless its -Dmcopt.cpu.* property names it, and with
 * none set none of the mixins in mcopt.metal.mixin.cpu apply, so the game is unchanged.
 *
 * <ul>
 * <li>{@code -Dmcopt.cpu.loadingFps=N}: the world-loading screen (shown while the level already exists, so vanilla's 60 fps menu
 * cap doesn't apply and it renders uncapped) at most N fps.</li>
 * </ul>
 */
public final class Cpu {
	/** -Dmcopt.cpu.model=true|ab: ModelPart.compile with an indexed cube loop. */
	public static final boolean MODEL_AB = "ab".equals(System.getProperty("mcopt.cpu.model"));
	public static final boolean MODEL = Boolean.getBoolean("mcopt.cpu.model") || MODEL_AB;
	public static boolean modelActive = true;
	/** -Dmcopt.cpu.abShots=texel|model: same-run picture check (AbShots). */
	public static final boolean AB_SHOTS = !System.getProperty("mcopt.cpu.abShots", "").isEmpty();
	/** -Dmcopt.cpu.leash=true|ab: shouldRender's leash check through Mob.getLeashData first (LeashMixin). */
	public static final boolean LEASH_AB = "ab".equals(System.getProperty("mcopt.cpu.leash"));
	public static final boolean LEASH = Boolean.getBoolean("mcopt.cpu.leash") || LEASH_AB;
	public static boolean leashActive = true;
	public static final int LOADING_FPS = Integer.getInteger("mcopt.cpu.loadingFps", 0);
	/** -Dmcopt.cpu.pass=true: native render-pass begin without per-pass allocations (mcmetal.m, mc_cpu_flags). */
	public static final boolean PASS_AB = "ab".equals(System.getProperty("mcopt.cpu.pass"));
	public static final boolean PASS = Boolean.getBoolean("mcopt.cpu.pass") || PASS_AB;
	/** -Dmcopt.cpu.cmdAhead=true: the next frame's command buffer made on a background queue after each commit (mcmetal.m). */
	public static final boolean CMD_AHEAD = Boolean.getBoolean("mcopt.cpu.cmdAhead");

	/** mc_cpu_flags' bits, with the pass lever as given. */
	public static int nativeFlags(boolean pass) {
		return (pass ? 1 : 0) | (CMD_AHEAD ? 2 : 0);
	}

	/** pass=ab: time in the native pass begin, lever on odd frames. */
	public static final Ab PASS_TIMER = PASS_AB ? new Ab("renderBegin") : null;
	/**
	 * -Dmcopt.cpu.fence=true|stats: MetalEncoder.createFence right after a submit, with nothing recorded since, names that submit
	 * rather than the next one (see there). stats: fences made and moved, render-thread time blocked in GpuFence.awaitCompletion
	 * per frame, every 5 s, without the change; true,stats: with it.
	 */
	private static final String FENCE_PROP = System.getProperty("mcopt.cpu.fence", "");
	public static final boolean FENCE = FENCE_PROP.equals("true") || FENCE_PROP.equals("true,stats");
	public static final boolean FENCE_STATS = FENCE_PROP.endsWith("stats");
	public static boolean fenceActive = true;
	private static long fenceMade, fenceMoved, fenceWaits, fenceWaitNs, fenceFirstSubmit = -1, fenceLastPrint = System.nanoTime();

	public static void fenceMade(boolean moved) {
		fenceMade++;
		if (moved) fenceMoved++;
	}

	public static void fenceWaited(long ns) {
		fenceWaits++;
		fenceWaitNs += ns;
	}

	/** Each submit (stats only). */
	public static void fenceTick(long submit) {
		if (fenceFirstSubmit < 0) fenceFirstSubmit = submit;
		long now = System.nanoTime();
		if (now - fenceLastPrint > 5_000_000_000L && submit > fenceFirstSubmit) {
			double frames = submit - fenceFirstSubmit;
			System.out.printf("mcopt-cpu: fence %s made %d moved %d, blocking waits %.3f/frame, blocked %.1f us/frame (%s)%n", FENCE ? "on" : "off",
				fenceMade, fenceMoved, fenceWaits / frames, fenceWaitNs / 1e3 / frames, phase());
			fenceLastPrint = now;
			fenceMade = fenceMoved = fenceWaits = fenceWaitNs = 0;
			fenceFirstSubmit = submit;
		}
	}

	private Cpu() {
	}

	private static Field benchPhase;
	private static boolean benchLooked;

	/** The bench mod's current phase when it's loaded (stats only), else "game". */
	static String phase() {
		if (!benchLooked) {
			benchLooked = true;
			try {
				benchPhase = Class.forName("mcopt.bench.Bench").getDeclaredField("phase");
				benchPhase.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException absent) {
				benchPhase = null;
			}
		}
		if (benchPhase == null) return "game";
		try {
			return String.valueOf(benchPhase.get(null));
		} catch (ReflectiveOperationException e) {
			return "game";
		}
	}
}
