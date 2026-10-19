package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Uniforms are bound lazily at the next draw, and only when the slot's content actually changed: the frontend re-sends
 * every uniform on each pipeline switch, but Metal's argument slots survive pipeline changes, so most of that is a no-op.
 */
final class MetalRenderPass implements RenderPassBackend {
	private static final int MAX_UNIFORMS = MetalConst.PUSH_CONSTANTS_INDEX;

	private final MetalEncoder encoder;
	private final long enc;
	private final int areaX, areaY, areaWidth, areaHeight;
	private @Nullable MetalPipeline pipeline;
	private final Object[] pending = new Object[MAX_UNIFORMS];
	private final Object[] bound = new Object[MAX_UNIFORMS];
	private int dirtyUpTo;
	/** Metal draw calls issued, multi-draws counted per element, and indices drawn (terrain multi-draws only counted while tracing); for the trace. */
	int draws;
	long indices;
	/** Texel buffers need an MTLTexture view per (buffer, range); build each once per pass. */
	private final Map<GpuBufferSlice, Long> texelViews = new HashMap<>();
	/** The frontend's index buffer, restored after a split. */
	private long indexHandle;
	private int indexInt;
	/** What the frontend set in the encoder besides uniforms, put back after someone splits the pass (restoreAfterSplit). */
	private final int[] scissor = new int[4];
	private final @Nullable GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[4];
	private final byte[] pushConstants = new byte[128];
	private int pushConstantsLength;

	private final boolean hasDepth;
	/** Shaderpack runtime: draws this pass's pipelines with its own (see MetalHooks); null on the backend's own path. */
	private final @Nullable PassDelegate delegate;
	/** With a delegate: the current pipeline's uniform slots (index -> slot, -1 unbound); null drops its draws. */
	private int @Nullable [] slots;

	MetalRenderPass(MetalEncoder encoder, boolean hasDepth, int x, int y, int width, int height, @Nullable PassDelegate delegate) {
		this.encoder = encoder;
		this.enc = encoder.enc;
		this.hasDepth = hasDepth;
		this.delegate = delegate;
		this.areaX = x;
		this.areaY = y;
		this.areaWidth = width;
		this.areaHeight = height;
		this.enableScissor(x, y, width, height);
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(BackendRenderPipeline pipeline) {
		MetalPipeline p = (MetalPipeline) pipeline;
		if (this.delegate != null) {
			this.pipeline = p;
			this.slots = this.delegate.setPipeline(this.enc, p, p.uniforms);
			Arrays.fill(this.pending, 0, p.uniforms.size(), null);
			Arrays.fill(this.bound, null);
			this.dirtyUpTo = p.uniforms.size();
			return;
		}
		if (this.encoder.tracing()) this.encoder.trace("  pipeline", p.name, "after draws:", this.draws, "triangles:", this.indices / 3);
		this.pipeline = p;
		Native.pipeline(this.enc, this.hasDepth ? p.withDepth : p.withoutDepth(), this.hasDepth ? p.depthState : p.depthStateWithoutDepth(), p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
		Arrays.fill(this.pending, 0, p.uniforms.size(), null);
		this.dirtyUpTo = p.uniforms.size();
	}

	/**
	 * Far terrain (mcopt.metal.lod, -Dmcopt.lod) encoded its own draw into this pass and left its pipeline state behind: set the
	 * current pipeline's state again (the game's next draw may come without a setPipeline). Its own resources sit in slots no
	 * pipeline here binds, so the uniforms stay as they are.
	 */
	void reapplyPipeline() {
		MetalPipeline p = this.pipeline;
		if (p == null) return;
		if (this.delegate != null) {
			this.slots = this.delegate.setPipeline(this.enc, p, p.uniforms);
			return;
		}
		Native.pipeline(this.enc, this.hasDepth ? p.withDepth : p.withoutDepth(), this.hasDepth ? p.depthState : p.depthStateWithoutDepth(), p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
	}

	/** Called by the encoder when the frontend ends the pass. */
	void end() {
		if (this.delegate != null) this.delegate.end(this.enc);
	}

	/** With a delegate: false when this draw must not be encoded (pipeline not taken over, or the delegate says so). */
	private boolean delegated() {
		return this.delegate == null || this.slots != null && this.delegate.beforeDraw(this.enc);
	}

	/**
	 * Someone outside the backend split this pass (ended its render encoder and reopened it on the same attachments, as our
	 * own near terrain's occlusion does): the reopened encoder has no state, so everything the frontend set binds again
	 * (uniforms at the next draw, the rest now).
	 */
	void restoreAfterSplit() {
		Arrays.fill(this.bound, null);
		for (int i = this.pending.length - 1; i >= 0; i--) {
			if (this.pending[i] != null) {
				this.dirtyUpTo = Math.max(this.dirtyUpTo, i + 1);
				break;
			}
		}
		Native.scissor(this.enc, this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
		for (int slot = 0; slot < this.vertexBuffers.length; slot++) {
			GpuBufferSlice vb = this.vertexBuffers[slot];
			if (vb != null) Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vb.buffer()).handle, vb.offset());
		}
		if (this.indexHandle != 0) Native.index(this.enc, this.indexHandle, this.indexInt);
		if (this.pushConstantsLength > 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer bytes = stack.malloc(this.pushConstantsLength).put(this.pushConstants, 0, this.pushConstantsLength).flip();
				Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(bytes), this.pushConstantsLength);
			}
		}
		this.reapplyPipeline();
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		this.pending[index] = value;
		this.dirtyUpTo = Math.max(this.dirtyUpTo, index + 1);
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(value), value.remaining());
		if (value.remaining() <= this.pushConstants.length) {
			this.pushConstantsLength = value.remaining();
			value.get(value.position(), this.pushConstants, 0, this.pushConstantsLength);
		}
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		Native.scissor(this.enc, x, y, width, height);
		this.scissor[0] = x;
		this.scissor[1] = y;
		this.scissor[2] = width;
		this.scissor[3] = height;
	}

	@Override
	public void disableScissor() {
		this.enableScissor(this.areaX, this.areaY, this.areaWidth, this.areaHeight);
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer == null) return;
		if (MetalConst.VERTEX_BUFFER_BASE + slot > 30) throw new IllegalArgumentException("Metal backend supports vertex buffer slots 0-3, got " + slot);
		Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vertexBuffer.buffer()).handle, vertexBuffer.offset());
		this.vertexBuffers[slot] = vertexBuffer;
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		this.indexHandle = this.encoder.use(indexBuffer).handle;
		this.indexInt = indexType == IndexType.INT ? 1 : 0;
		Native.index(this.enc, this.indexHandle, this.indexInt);
	}

	private void bindUniforms(int drawCount) {
		this.draws += drawCount;
		if (this.dirtyUpTo == 0) return;
		if (MetalEncoder.LAZY_CLEARS) {
			// a texture still pending its deferred clear is about to be sampled: write the clear first (splits this pass, then everything binds again)
			for (int i = 0; i < this.dirtyUpTo; i++) {
				if (this.pending[i] instanceof TextureViewAndSampler ts && ts.view() instanceof MetalTexture.View v && v.metalTexture().hasPendingClear()) {
					this.encoder.flushClearInPass(v.metalTexture());
				}
			}
		}
		List<BindGroupLayout.UniformDescription> uniforms = Objects.requireNonNull(this.pipeline).uniforms;
		for (int i = 0; i < this.dirtyUpTo && i < uniforms.size(); i++) {
			Object value = this.pending[i];
			if (value == null) throw new IllegalStateException("Missing uniform " + uniforms.get(i).name());
			if (value.equals(this.bound[i])) continue;
			this.bound[i] = value;
			this.bindUniform(uniforms.get(i), i, value);
		}
		this.dirtyUpTo = 0;
	}

	private void bindUniform(BindGroupLayout.UniformDescription uniform, int index, Object value) {
		int i = index;
		if (this.delegate != null) {
			if (this.slots == null || index >= this.slots.length || this.slots[index] < 0) return;
			i = this.slots[index];
		}
		switch (uniform.type()) {
			case UNIFORM_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				Native.buffer(this.enc, i, this.encoder.use(slice.buffer()).handle, slice.offset());
			}
			case COMBINED_IMAGE_SAMPLER -> {
				TextureViewAndSampler ts = (TextureViewAndSampler) value;
				Native.texture(this.enc, i, ((MetalTexture.View) ts.view()).handle, ((MetalSampler) ts.sampler()).handle());
				if (this.encoder.encLogOn()) this.encoder.encBind(((MetalTexture.View) ts.view()).metalTexture());
				if (this.delegate != null) this.delegate.texture(this.enc, i, ts.view());
			}
			case TEXEL_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				var format = Objects.requireNonNull(uniform.gpuFormat());
				long view = TexelViews.ON && TexelViews.active ? TexelViews.get(this.encoder, slice, MetalConst.pixelFormat(format), format.blockSize()) // opt-in
					: this.texelViews.computeIfAbsent(slice, s -> {
					long handle = Native.textureBuffer(this.encoder.ctx, this.encoder.use(s.buffer()).handle, MetalConst.pixelFormat(format), s.offset(),
						s.length(), format.blockSize());
					this.encoder.releaseLater(handle);
					return handle;
				});
				Native.texture(this.enc, i, view, 0);
			}
		}
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		if (!this.delegated()) return;
		this.bindUniforms(1);
		this.indices += (long) indexCount * instanceCount;
		Native.drawIndexed(this.enc, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDrawIndexed(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		if (this.encoder.tracing()) for (int i = 0; i < drawCount; i++) this.indices += indexCounts.get(indexCounts.position() + i);
		Native.multiDrawIndexedSeparate(this.enc, MemoryUtil.memAddress(firstIndexOffsets), MemoryUtil.memAddress(indexCounts),
			MemoryUtil.memAddress(vertexOffsets), drawCount);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.drawIndexedIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (!this.delegated()) return;
		this.bindUniforms(1);
		this.indices += (long) vertexCount * instanceCount;
		Native.draw(this.enc, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDraw(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDrawSeparate(this.enc, MemoryUtil.memAddress(firstVertices), MemoryUtil.memAddress(vertexCounts), drawCount);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.drawIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		this.encoder.timestamp(pool, index, true);
	}
}
