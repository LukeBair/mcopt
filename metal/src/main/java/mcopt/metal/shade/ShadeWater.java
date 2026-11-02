package mcopt.metal.shade;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.system.MemoryUtil;

/**
 * Blocky ocean waves, breaking foam and swash. When a chunk is meshed, the water at the sea's surface (a source at sea level, open to
 * the sky) near an ocean or beach biome is tagged in its vertex alpha, per vertex: 255 - t, t 1..100 for sea water (1 + k + 5s + 20q,
 * all at the vertex's block corner: k 0..4 its distance from the shore (the nearest non-water column at sea level, blocks; 4: 4 or
 * more), s 0..3 how shallow the water is and q 0..4 how many of the 4 columns around it are ocean or beach). Breakers roll in along
 * k and crash at the shore, in step with the swash. terrain.metal's own_vs raises or lowers the surface at each corner in
 * 1/32-block steps (half a texture pixel) by a wave field moving over the sea, scaled by q (so the waves fade out over a block at a
 * biome's edge instead of leaving a seam) and taller in the shallows, where their crests break into foam; own_fs draws the foam.
 * Corners are shared by neighbouring blocks, so the surface stays closed.
 * Swash: on beach blocks (a sturdy top at sea level, air above) within 3 blocks of sea water, a sheet of that water's top face is
 * laid half a texture pixel over the block, tagged t 101..125 (101 + d + 5q, d 0..4 the corner's distance from the water in blocks).
 * own_fs shows it only out to how far the wave at that spot runs up the beach, in 1/32-block steps, with foam along its edge.
 * Untagged water is vanilla's. Toggling the waves remeshes (ShadeScreen), as a stale tag would show (sheets, alpha) until then.
 */
public final class ShadeWater {
	/** Bytes this adds to the terrain's DrawFrame (terrain.metal: float4 wave). */
	public static final int FRAME_BYTES = 16;
	/** How far up the beach swash sheets are laid, blocks. */
	private static final int SWASH = 3;
	/** Swash sheets are laid (switched off for now: the spill over the beach didn't read in game; to be redone another way). */
	private static final boolean SHEETS = false;

	private ShadeWater() {
	}

	/** Tagging is on: decided per chunk at meshing time. */
	public static boolean tagging() {
		return Shade.ON && Shade.WAVES;
	}

	/** How far out breakers are tracked (Sea.shore), blocks. */
	private static final int BREAK = 4;

	/**
	 * Sea water's surroundings for its per-corner tags: ocean and depth for the 3x3 columns around it (x fastest), and each of its 4
	 * corners' distance from the shore (corner x + 2 z).
	 */
	public record Sea(int x, int z, int[] shore, boolean[] ocean, int[] depth) {
		int tag(float vx, float vz) {
			int cx = corner(vx, this.x), cz = corner(vz, this.z), q = 0, depth = 0;
			for (int dz = cz - 1; dz <= cz; dz++) {
				for (int dx = cx - 1; dx <= cx; dx++) {
					int i = dx + 1 + (dz + 1) * 3;
					if (this.ocean[i]) q++;
					depth += this.depth[i];
				}
			}
			// shallow 0 (4 deep, averaged over the corner's columns; land counts 0) .. 3
			int s = depth >= 14 ? 0 : depth >= 10 ? 1 : depth >= 6 ? 2 : 3;
			return 1 + this.shore[cx + 2 * cz] + 5 * s + 20 * q;
		}
	}

	/** The block corner (0 or 1 along this axis) a section-relative vertex coordinate sits on, for the block at world coordinate b. */
	private static int corner(float v, int b) {
		return Math.max(0, Math.min(1, Math.round(v) - (b & 15)));
	}

	private static boolean ocean(ClientLevel cl, BlockPos p) {
		try {
			var biome = cl.getBiome(p);
			return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_BEACH);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** A water source at p with no fluid above: the sea's surface when p is at sea level. */
	private static boolean surface(BlockAndTintGetter level, BlockPos p) {
		FluidState f = level.getFluidState(p);
		return f.is(FluidTags.WATER) && f.isSource() && level.getFluidState(p.above()).isEmpty();
	}

	/** Water blocks from p down, 0..4. */
	private static int depth(BlockAndTintGetter level, BlockPos.MutableBlockPos p) {
		int n = 0, y = p.getY();
		while (n < 4 && level.getFluidState(p.setY(y - n)).is(FluidTags.WATER)) n++;
		p.setY(y);
		return n;
	}

	/** The surroundings of the fluid at pos for tagging, or null to leave it untagged. Called on the meshing threads. */
	public static Sea sea(BlockAndTintGetter level, BlockPos pos, FluidState fluid) {
		if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) return null;
		ClientLevel cl = Minecraft.getInstance().level;
		if (cl == null || pos.getY() != cl.getSeaLevel() - 1) return null;
		BlockPos up = pos.above();
		if (!level.getFluidState(up).isEmpty() || !level.getBlockState(up).isAir()) return null;
		boolean[] ocean = new boolean[9];
		int[] depth = new int[9];
		boolean any = false;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 9; i++) {
			m.set(pos.getX() + i % 3 - 1, pos.getY(), pos.getZ() + i / 3 - 1);
			ocean[i] = ocean(cl, m);
			any |= ocean[i];
			depth[i] = depth(level, m);
		}
		if (!any) return null;
		// the columns out to BREAK + 1 around it that are land at sea level (x fastest), then each corner's distance to the nearest:
		// 0 when one touches the corner
		int r = BREAK + 1, side = 2 * r + 1;
		boolean[] land = new boolean[side * side];
		boolean anyLand = false;
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				boolean l = !level.getFluidState(m.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz)).is(FluidTags.WATER);
				land[dx + r + (dz + r) * side] = l;
				anyLand |= l;
			}
		}
		int[] shore = {BREAK, BREAK, BREAK, BREAK};
		if (anyLand) {
			for (int c = 0; c < 4; c++) {
				int cx = c & 1, cz = c >> 1, best = BREAK;
				for (int dz = -r; dz <= r; dz++) {
					for (int dx = -r; dx <= r; dx++) {
						if (!land[dx + r + (dz + r) * side]) continue;
						// (column dx spans [dx, dx + 1] from the block's corner 0; its distance from corner cx)
						int ex = dx >= cx ? dx - cx : cx - dx - 1, ez = dz >= cz ? dz - cz : cz - dz - 1;
						best = Math.min(best, Math.max(ex, ez));
					}
				}
				shore[c] = best;
			}
		}
		return new Sea(pos.getX(), pos.getZ(), shore, ocean, depth);
	}

	/** Wraps a vertex consumer so the fluid's vertices carry their tags in their alpha (FluidRenderer's addVertex only). */
	public static VertexConsumer tagged(VertexConsumer out, Sea sea) {
		return new Tagged(out, sea);
	}

	/**
	 * A swash sheet over the block at pos if it's a beach block near the sea: the nearest sea water's top face (vanilla's own vertices:
	 * texture, tint, light) moved onto this block's top. Called on the meshing threads for every non-fluid block.
	 */
	public static void swash(BlockAndTintGetter level, BlockPos pos, BlockState state, FluidRenderer fluids, FluidRenderer.Output out) {
		if (!SHEETS) return;
		ClientLevel cl = Minecraft.getInstance().level;
		if (cl == null || pos.getY() != cl.getSeaLevel() - 1) return;
		if (!level.getBlockState(pos.above()).isAir() || !state.isFaceSturdy(level, pos, Direction.UP)) return;
		int r = SWASH + 1, side = 2 * r + 1;
		boolean[] wet = new boolean[side * side];
		boolean any = false;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				boolean w = surface(level, m.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz));
				wet[dx + r + (dz + r) * side] = w;
				any |= w;
			}
		}
		if (!any) return;
		// each of the 3x3 columns' distance to the nearest sea water (Chebyshev, at most r), and the nearest to this block
		int[] dist = new int[9];
		int nearX = 0, nearZ = 0;
		for (int i = 0; i < 9; i++) {
			int cx = i % 3 - 1, cz = i / 3 - 1, best = r;
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (!wet[dx + r + (dz + r) * side]) continue;
					int d = Math.max(Math.abs(dx - cx), Math.abs(dz - cz));
					if (d < best) {
						best = d;
						if (i == 4) {
							nearX = dx;
							nearZ = dz;
						}
					}
				}
			}
			dist[i] = best;
		}
		if (dist[4] > SWASH) return;
		boolean[] ocean = new boolean[9];
		boolean anyOcean = false;
		for (int i = 0; i < 9; i++) {
			ocean[i] = ocean(cl, m.set(pos.getX() + i % 3 - 1, pos.getY(), pos.getZ() + i / 3 - 1));
			anyOcean |= ocean[i];
		}
		if (!anyOcean) return;
		BlockPos water = pos.offset(nearX, 0, nearZ);
		Capture top = new Capture();
		fluids.tesselate(level, water, l -> top, level.getBlockState(water), level.getFluidState(water));
		if (top.n < 4) return;
		VertexConsumer b = out.getBuilder(ChunkSectionLayer.TRANSLUCENT);
		int lx = pos.getX() & 15, lz = pos.getZ() & 15;
		float ly = (pos.getY() & 15) + 1f + 1f / 32f;
		for (int v = 0; v < 4; v++) {
			int fx = corner(top.x[v], water.getX()), fz = corner(top.z[v], water.getZ()), d = r, q = 0;
			for (int dz = fz - 1; dz <= fz; dz++) {
				for (int dx = fx - 1; dx <= fx; dx++) {
					int i = dx + 1 + (dz + 1) * 3;
					d = Math.min(d, dist[i]);
					if (ocean[i]) q++;
				}
			}
			int tag = 101 + Math.min(d, 4) + 5 * q;
			b.addVertex(lx + fx, ly, lz + fz, top.color[v] & 0x00FFFFFF | (255 - tag) << 24, top.u[v], top.v[v], top.overlay[v], top.light[v], 0f, 1f, 0f);
		}
	}

	/** Writes the wave parameters at d (terrain.metal DrawFrame.wave): amplitude (blocks, 0: flat), foam, tags present (1/0), 0. */
	public static void put(long d) {
		ClientLevel level = Minecraft.getInstance().level;
		MemoryUtil.memSet(d, 0, FRAME_BYTES);
		if (!tagging()) return;
		MemoryUtil.memPutFloat(d + 8, 1f);  // (tags are decoded, the alpha restored, even while the waves are flat)
		if (!Shade.enabled || level == null) return;
		float rain = level.getRainLevel(1f), thunder = level.getThunderLevel(1f);
		// Calm: 3 steps of 1/32 either way; rain doubles it; a thunderstorm doubles it again (12 steps, 3/8 of a block).
		float amp = (3f + 3f * rain + 6f * thunder) / 32f;
		MemoryUtil.memPutFloat(d, amp);
		MemoryUtil.memPutFloat(d + 4, 1f + rain + thunder);
	}

	/** The first upward face's four vertices FluidRenderer emits (the water's top). */
	private static final class Capture implements VertexConsumer {
		final float[] x = new float[4], z = new float[4], u = new float[4], v = new float[4];
		final int[] color = new int[4], overlay = new int[4], light = new int[4];
		int n;

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
			if (this.n >= 4 || ny < 0.5f) return;
			this.x[this.n] = x;
			this.z[this.n] = z;
			this.u[this.n] = u;
			this.v[this.n] = v;
			this.color[this.n] = color;
			this.overlay[this.n] = overlayCoords;
			this.light[this.n] = lightCoords;
			this.n++;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}

	private record Tagged(VertexConsumer out, Sea sea) implements VertexConsumer {
		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
			if (color >>> 24 == 255) color = color & 0x00FFFFFF | (255 - this.sea.tag(x, z)) << 24;
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
