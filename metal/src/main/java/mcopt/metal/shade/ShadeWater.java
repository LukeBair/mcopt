package mcopt.metal.shade;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.system.MemoryUtil;

/**
 * Blocky ocean waves and shore foam. When a chunk is meshed, the water at the open sea's surface (sea level, open to the sky, in an
 * ocean, beach or river biome) is tagged in its vertex alpha: 255 - k, k 1 for open water, 2..5 for water touching 1..4 non-water blocks
 * (the shore, foam). terrain.metal's own_vs reads the tag and raises or lowers the surface at each block corner in 1/32-block steps
 * (half a texture pixel) by a wave field moving over the sea (per corner, so neighbouring blocks always meet), and own_fs draws
 * blocky foam on the shore blocks. Untagged water is vanilla's.
 * The tag costs at most 5/255 of the water's alpha if the waves are switched off before the chunk is meshed again.
 */
public final class ShadeWater {
	/** Bytes this adds to the terrain's DrawFrame (terrain.metal: float4 wave). */
	public static final int FRAME_BYTES = 16;

	private ShadeWater() {
	}

	/** Tagging is on: decided per chunk at meshing time. */
	public static boolean tagging() {
		return Shade.ON && Shade.WAVES;
	}

	/** The tag (0: none) for the fluid at pos. Called on the meshing threads. */
	public static int tag(BlockAndTintGetter level, BlockPos pos, FluidState fluid) {
		if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) return 0;
		ClientLevel cl = Minecraft.getInstance().level;
		if (cl == null || pos.getY() != cl.getSeaLevel() - 1) return 0;
		BlockPos up = pos.above();
		if (!level.getFluidState(up).isEmpty() || !level.getBlockState(up).isAir()) return 0;
		try {
			var biome = cl.getBiome(pos);
			// (rivers too: they meet the sea, and untagged water beside tagged leaves a seam)
			if (!biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_BEACH) && !biome.is(BiomeTags.IS_DEEP_OCEAN) && !biome.is(BiomeTags.IS_RIVER)) return 0;
		} catch (RuntimeException e) {
			return 0;
		}
		int shore = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (!level.getFluidState(pos.relative(d)).is(FluidTags.WATER)) shore++;
		}
		return 1 + shore;
	}

	/** Wraps a vertex consumer so the fluid's vertices carry the tag in their alpha (FluidRenderer's addVertex only). */
	public static VertexConsumer tagged(VertexConsumer out, int tag) {
		return new Tagged(out, tag);
	}

	/** Writes the wave parameters at d (terrain.metal DrawFrame.wave): amplitude (blocks), foam, 0, 0. All 0 when off. */
	public static void put(long d) {
		ClientLevel level = Minecraft.getInstance().level;
		if (!Shade.ON || !Shade.WAVES || !Shade.enabled || level == null) {
			MemoryUtil.memSet(d, 0, FRAME_BYTES);
			return;
		}
		float rain = level.getRainLevel(1f), thunder = level.getThunderLevel(1f);
		// Calm: 2 steps of 1/32 either way; rain doubles it; a storm adds three more.
		float amp = (2f + 2f * rain + 3f * thunder) / 32f;
		MemoryUtil.memPutFloat(d, amp);
		MemoryUtil.memPutFloat(d + 4, 1f + rain + thunder);
		MemoryUtil.memPutFloat(d + 8, 0f);
		MemoryUtil.memPutFloat(d + 12, 0f);
	}

	private record Tagged(VertexConsumer out, int tag) implements VertexConsumer {
		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
			if (color >>> 24 == 255) color = color & 0x00FFFFFF | (255 - this.tag) << 24;
			this.out.addVertex(x, y, z, color, u, v, overlayCoords, lightCoords, nx, ny, nz);
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			return this.out.addVertex(x, y, z);
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this.out.setColor(r, g, b, a);
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this.out.setColor(color);
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this.out.setUv(u, v);
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this.out.setUv1(u, v);
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this.out.setUv2(u, v);
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this.out.setUv3(u, v);
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this.out.setNormal(x, y, z);
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this.out.setLineWidth(width);
		}
	}
}
