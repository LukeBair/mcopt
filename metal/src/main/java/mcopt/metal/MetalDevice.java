package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class MetalDevice implements GpuDeviceBackend {
	private final long ctx;
	private final MetalEncoder encoder;
	private final DeviceInfo info;

	MetalDevice() throws BackendCreationException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long name = stack.nmalloc(1, 256), err = stack.nmalloc(1, 1024);
			this.ctx = Native.create(name, 256, err, 1024);
			if (this.ctx == 0) throw new BackendCreationException("Metal unavailable: " + MemoryUtil.memUTF8(err), BackendCreationException.Reason.PLATFORM_ERROR);
			this.info = new DeviceInfo(
				MemoryUtil.memUTF8(name), "Apple", "Metal 3 (mcopt)", true, "Metal", 1.0F,
				new DeviceLimits(16, 256, 16384, Native.maxBufferLength(this.ctx), Integer.MAX_VALUE, 8, Integer.MAX_VALUE),
				// Every multi-draw form is a native loop of Metal draws, so all of them are "supported".
				new DeviceFeatures(true, true, true, true, true, true, true, true),
				Set.of(),
				new HintsAndWorkarounds(false, false, true, false),
				DeviceType.INTEGRATED);
		}
		MetalPsoCache.open(this.ctx, this.info.name());
		// -Dmcopt.startup.report=true (measurement only): the pipeline creation time by step, on exit.
		if (Boolean.getBoolean("mcopt.startup.report")) Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("mcopt-metal startup: " + MetalPipeline.compileStats()
			+ " jvmUptimeMs=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime())));
		this.encoder = new MetalEncoder(this.ctx);
		// -Dmcopt.metal.cbdiag=MS: stall diagnostics on stderr (big buffer allocations, slow command buffers); see mcmetal.m.
		String diag = System.getProperty("mcopt.metal.cbdiag");
		if (diag != null) Native.diagEnable(Double.parseDouble(diag));
		// -Dmcopt.metal.gpuCount[=PATH]: measurement only, per render encoder and debug group: samples passing depth (visibility
		// counts), draws and primitives per pipeline, one JSON line each to PATH (true: gpucount.jsonl in the game dir); see mcmetal.m.
		// -Dmcopt.metal.gpuCount.every=N: only every Nth frame (the frames between run as without the flag).
		// -Dmcopt.metal.gpuCount.groups=false: one visibility slot per encoder (no new slot at debug groups).
		if (MetalPipeline.GPU_COUNT) Native.gpuCountEnable(MetalPipeline.GPU_COUNT_PATH, Integer.getInteger("mcopt.metal.gpuCount.every", 1),
			!"false".equals(System.getProperty("mcopt.metal.gpuCount.groups")));
		if (mcopt.metal.cpu.Cpu.PASS || mcopt.metal.cpu.Cpu.CMD_AHEAD) Native.cpuFlags(mcopt.metal.cpu.Cpu.nativeFlags(mcopt.metal.cpu.Cpu.PASS)); // opt-in: see mc_cpu_flags
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
		return new MetalSurface(this.ctx, this.encoder, windowHandle);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		return MetalSampler.create(this.ctx, u, v, min, mag, maxAnisotropy, maxLod);
	}

	/**
	 * -Dmcopt.metal.residentAnim: sprite animation frame textures kept in a residency set the queue carries. Each frame is a small texture
	 * drawn into its atlas only on the ticks that need it; between those uses its pages go idle and the system compresses them, and the
	 * first pass of the next tick that samples them waits while they come back (on the base chips ~0.4-0.6 ms of GPU in the blocks atlas's
	 * mip-0 animation pass, the same draws ~10 us on the mips after it). Resident, they stay wired. Pure residency: nothing drawn changes.
	 */
	private static long residentBytes, residentCount;

	@Override
	public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int layers, int mips) {
		// MTLTextureUsage: ShaderRead 1, RenderTarget 4. Copies need no usage bit in Metal.
		int mtlUsage = ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 ? 1 : 0) | ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 ? 4 : 0);
		if (mcopt.metal.own.AnimCopy.creatingAnimatedAtlas) mtlUsage |= 2;  // ShaderWrite (-Dmcopt.own.int.atlasWrite)
		boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		long handle = Native.textureNew(this.ctx, MetalConst.pixelFormat(format), width, height, layers, mips, Math.max(1, mtlUsage), cube ? 1 : 0);
		if (handle == 0) throw new IllegalStateException("Couldn't create " + width + "x" + height + " " + format + " texture " + label);
		MetalTexture texture = new MetalTexture(this.encoder, handle, usage, label == null ? "" : label, format, width, height, layers, mips);
		texture.shaderWrite = (mtlUsage & 2) != 0;
		if (ResidentAnim.creating && Native.textureResident(this.ctx, handle, 1)) {
			texture.resident = true;
			long bytes = 0;
			for (int m = 0; m < mips; m++) bytes += (long) Math.max(1, width >> m) * Math.max(1, height >> m) * format.blockSize() * Math.max(1, layers);
			texture.residentBytes = bytes;
			residentBytes += bytes;
			if (++residentCount % 100 == 0) System.out.println("mcopt-metal: " + residentCount + " animation frame textures resident, " + residentBytes / 1024 + " KB");
		}
		return texture;
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
		return new MetalTexture.View(this.encoder, (MetalTexture) texture, baseMip, mips);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
		MetalEvents.Operation event = MetalEvents.begin("allocate", -1, size);
		try {
			return new MetalBuffer(this.encoder, Native.bufferNew(this.ctx, size), usage, size);
		} finally {
			MetalEvents.end(event);
		}
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
		// A brand-new buffer isn't referenced by any command yet, so the data can go straight into its memory.
		MetalBuffer buffer = new MetalBuffer(this.encoder, Native.bufferNew(this.ctx, data.remaining()), usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.address, data.remaining());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
		// Called on a worker thread by the frontend; Metal pipeline creation is thread-safe, so compile right here.
		MetalPipeline pipeline = MetalPipeline.compile(this.encoder, info);
		return () -> pipeline;
	}

	@Override
	public void close() {
		this.encoder.waitIdle();
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new MetalTimestamps.Pool(size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return MetalTimestamps.calibrationOffset(this.ctx);
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.info;
	}

	static void residentReleased(MetalTexture t) {
		residentBytes -= t.residentBytes;
		residentCount--;
	}

	/** The animation frame textures kept resident so far (count, bytes). */
	public static long[] residentAnimation() {
		return new long[] {residentCount, residentBytes};
	}
}
