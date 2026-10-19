package mcopt.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.List;
import java.util.OptionalLong;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU timestamps for vanilla's query pools (F3 GPU utilisation, TimerQuery, the Tracy GPU profiler). A pool value is
 * nanoseconds on the GPU's own timestamp clock (DeviceInfo.timestampPeriod is 1), and getTimestampCalibrationOffset says how
 * to move that clock onto System.nanoTime's.
 *
 * A write is placed at an encoder boundary: Apple GPUs can only sample timestamps at the start and end of an encoder, not between
 * draws. A write at the start of a submit gets the start of the submit's first encoder; any later one gets the end of the encoder
 * before it; one from inside a render pass gets that pass's start. Once any write has been seen, the following
 * {@link #STICKY_SUBMITS} submits are sampled (a sample buffer and four sample points per encoder), so the cost is zero until the
 * game asks for GPU time; a write in a submit that was not sampled falls back to the command buffer's GPU time, which is exact
 * for its end and for its start. -Dmcopt.metal.gpuTimestamps=false returns the old stub (queries never resolve).
 */
final class MetalTimestamps {
	static final boolean ON = !"false".equals(System.getProperty("mcopt.metal.gpuTimestamps"));
	static final int STICKY_SUBMITS = 16;
	/** Cleared if the device has no stage-boundary sampling. */
	static volatile boolean sampling = true;

	record Request(Pool pool, int index, int group, boolean inPass) {
	}

	/** Pool values are ns since {@link #upBase}; the clock pair is taken on first use. */
	private static long gpuBase, upBase;
	private static double scale = 1.0;
	private static boolean based;

	private static synchronized long[] clock(long ctx) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, 16);
			Native.gpuClock(ctx, out);
			long gpu = MemoryUtil.memGetLong(out), up = MemoryUtil.memGetLong(out + 8);
			if (!based) {
				based = true;
				gpuBase = gpu;
				upBase = up;
			} else if (up - upBase > 500_000_000L && gpu > gpuBase) {
				scale = (double) (up - upBase) / (double) (gpu - gpuBase);  // GPU ticks to ns, from the longest baseline we have
			}
			return new long[] {gpu, up};
		}
	}

	/** Adding this to a pool value gives System.nanoTime's clock; re-measured so drift between the two clocks doesn't accumulate. */
	static long calibrationOffset(long ctx) {
		long[] now = clock(ctx);
		synchronized (MetalTimestamps.class) {
			return now[1] - (long) ((now[0] - gpuBase) * scale);
		}
	}

	static synchronized long toValue(long ticks) {
		return (long) ((ticks - gpuBase) * scale);
	}

	static void resolve(long ctx, List<Request> requests, long samples, int count, boolean release, long cmd) {
		clock(ctx);
		long[] raw = new long[Math.max(count, 0)];
		if (samples != 0 && count > 0) {
			long buffer = MemoryUtil.nmemAlloc(count * 8L);
			try {
				Native.profileReadRaw(ctx, samples, count, buffer, release);
				for (int i = 0; i < count; i++) raw[i] = MemoryUtil.memGetLong(buffer + i * 8L);
			} finally {
				MemoryUtil.nmemFree(buffer);
			}
		} else if (samples != 0 && release) {
			Native.release(samples);
		}
		long now = System.nanoTime();
		long gpuNs = (long) (Native.cmdGpuMicros(cmd) * 1000.0);
		for (Request r : requests) {
			long value = Long.MIN_VALUE;
			if (r.group >= 0) {
				int g = r.inPass ? Math.max(r.group - 1, 0) : r.group;
				long ticks = r.inPass || r.group == 0 ? boundary(raw, g, false) : boundary(raw, r.group - 1, true);
				if (ticks != 0) value = toValue(ticks);
			}
			if (value == Long.MIN_VALUE) {
				// not sampled: the command buffer finished about now and began gpuNs before; place a start-of-submit write there
				value = (r.group == 0 || r.group < 0 && r.index % 2 == 0 ? now - gpuNs : now) - currentUpBase();
			}
			r.pool.set(r.index, value);
		}
	}

	private static synchronized long currentUpBase() {
		return upBase;
	}

	/** The earliest (end = false) or latest (end = true) valid sample of encoder group g, 0 if none. */
	private static long boundary(long[] raw, int group, boolean end) {
		long best = 0;
		for (int k = 0; k < 4; k++) {
			int i = group * 4 + k;
			if (i >= raw.length || raw[i] == 0) continue;
			if (best == 0 || (end ? raw[i] > best : raw[i] < best)) best = raw[i];
		}
		return best;
	}

	static final class Pool implements GpuQueryPool {
		private final long[] values;
		private final boolean[] present;

		Pool(int size) {
			this.values = new long[size];
			this.present = new boolean[size];
		}

		synchronized void set(int index, long value) {
			this.values[index] = value;
			this.present[index] = true;
		}

		synchronized void invalidate(int index) {
			this.present[index] = false;
		}

		@Override
		public int size() {
			return this.values.length;
		}

		@Override
		public synchronized OptionalLong getValue(int index) {
			return this.present[index] ? OptionalLong.of(this.values[index]) : OptionalLong.empty();
		}

		@Override
		public synchronized OptionalLong[] getValues(int index, int count) {
			if (index < 0 || index + count > this.values.length) {
				throw new IndexOutOfBoundsException("getValues of " + count + " from " + index + " in a pool of " + this.values.length);
			}
			OptionalLong[] result = new OptionalLong[count];
			for (int i = 0; i < count; i++) result[i] = this.getValue(index + i);
			return result;
		}

		@Override
		public void close() {
		}
	}
}
