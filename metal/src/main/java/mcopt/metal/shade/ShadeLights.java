package mcopt.metal.shade;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/**
 * Coloured block light: the light-emitting blocks near the camera, each with a hue (a table for the common ones, the block's map
 * colour otherwise). shade.fsh tints the pixels around each one by its hue, so sea lanterns read cool, torches warm, soul fire blue.
 * There's no occlusion (the hue can reach through a thin wall); it's a tint on top of vanilla's light, not a light of its own.
 */
public final class ShadeLights {
	static final int MAX = 32;
	/** Sections (16 blocks) around the camera's searched for emitters. */
	private static final int RADIUS = 2;
	/** World positions and hues of the nearest emitters: x, y, z, emission 0..1; r, g, b, 0. */
	static final float[] POS = new float[MAX * 4], COL = new float[MAX * 4];
	static int count;
	private static final List<long[]> found = new ArrayList<>();
	private static final Map<Block, float[]> HUES = new IdentityHashMap<>();
	private static double scanX = Double.NaN, scanY, scanZ;
	private static int sinceScan;
	private static ClientLevel scanLevel;

	static {
		float[] torch = {1f, 0.62f, 0.32f}, soul = {0.35f, 0.75f, 1f}, sea = {0.55f, 0.95f, 1f};
		hue(torch, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LANTERN, Blocks.CAMPFIRE, Blocks.FIRE, Blocks.JACK_O_LANTERN, Blocks.FURNACE,
			Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CANDLE);
		hue(soul, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH, Blocks.SOUL_LANTERN, Blocks.SOUL_CAMPFIRE, Blocks.SOUL_FIRE);
		hue(sea, Blocks.SEA_LANTERN, Blocks.CONDUIT, Blocks.BEACON);
		hue(new float[] {1f, 0.85f, 0.5f}, Blocks.GLOWSTONE, Blocks.REDSTONE_LAMP, Blocks.OCHRE_FROGLIGHT);
		hue(new float[] {1f, 0.3f, 0.22f}, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
		hue(new float[] {1f, 0.58f, 0.32f}, Blocks.SHROOMLIGHT);
		hue(new float[] {1f, 0.45f, 0.15f}, Blocks.LAVA, Blocks.MAGMA_BLOCK, Blocks.LAVA_CAULDRON);
		hue(new float[] {0.75f, 0.5f, 1f}, Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.CRYING_OBSIDIAN,
			Blocks.RESPAWN_ANCHOR, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY);
		hue(new float[] {0.95f, 0.92f, 1f}, Blocks.END_ROD);
		hue(new float[] {0.6f, 1f, 0.6f}, Blocks.VERDANT_FROGLIGHT);
		hue(new float[] {1f, 0.7f, 0.95f}, Blocks.PEARLESCENT_FROGLIGHT);
		hue(new float[] {0.6f, 0.9f, 0.75f}, Blocks.GLOW_LICHEN);
	}

	private ShadeLights() {
	}

	private static void hue(float[] c, Block... blocks) {
		for (Block b : blocks) HUES.put(b, c);
	}

	/** Called each frame before the shade pass: rescans every 20 frames, or when the camera has moved 2 blocks. */
	static void update(Vec3 cam) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || Shade.BLOCK_LIGHT <= 0f) {
			count = 0;
			return;
		}
		boolean moved = Double.isNaN(scanX) || Math.abs(cam.x - scanX) > 2 || Math.abs(cam.y - scanY) > 2 || Math.abs(cam.z - scanZ) > 2;
		if (moved || level != scanLevel || ++sinceScan >= 20) scan(level, cam);
	}

	private static void scan(ClientLevel level, Vec3 cam) {
		sinceScan = 0;
		scanLevel = level;
		scanX = cam.x;
		scanY = cam.y;
		scanZ = cam.z;
		found.clear();
		int cx = BlockPos.containing(cam).getX() >> 4, cy = BlockPos.containing(cam).getY() >> 4, cz = BlockPos.containing(cam).getZ() >> 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int sx = cx - RADIUS; sx <= cx + RADIUS; sx++) {
			for (int sz = cz - RADIUS; sz <= cz + RADIUS; sz++) {
				if (!(level.getChunkSource().getChunk(sx, sz, false) instanceof LevelChunk chunk)) continue;
				LevelChunkSection[] sections = chunk.getSections();
				for (int sy = cy - RADIUS; sy <= cy + RADIUS; sy++) {
					int i = sy - level.getMinSectionY();
					if (i < 0 || i >= sections.length) continue;
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.getStates().maybeHas(s -> s.getLightEmission() > 0)) continue;
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								BlockState s = section.getBlockState(x, y, z);
								int e = s.getLightEmission();
								if (e <= 0) continue;
								int wx = (sx << 4) + x, wy = (sy << 4) + y, wz = (sz << 4) + z;
								double dx = wx + 0.5 - cam.x, dy = wy + 0.5 - cam.y, dz = wz + 0.5 - cam.z;
								long d2 = (long) (dx * dx + dy * dy + dz * dz);
								found.add(new long[] {d2, wx, wy, wz, e});
							}
						}
					}
				}
			}
		}
		found.sort((a, b) -> Long.compare(a[0], b[0]));
		count = Math.min(MAX, found.size());
		for (int n = 0; n < count; n++) {
			long[] f = found.get(n);
			pos.set(f[1], f[2], f[3]);
			BlockState s = level.getBlockState(pos);
			float[] c = HUES.get(s.getBlock());
			if (c == null) c = fromMap(s.getMapColor(level, pos).col);
			POS[n * 4] = f[1] + 0.5f;
			POS[n * 4 + 1] = f[2] + 0.5f;
			POS[n * 4 + 2] = f[3] + 0.5f;
			POS[n * 4 + 3] = f[4] / 15f;
			COL[n * 4] = c[0];
			COL[n * 4 + 1] = c[1];
			COL[n * 4 + 2] = c[2];
		}
	}

	/** A hue from a block's map colour, brightened so its brightest channel is 1. */
	private static float[] fromMap(int rgb) {
		float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
		float m = Math.max(0.05f, Math.max(r, Math.max(g, b)));
		return new float[] {r / m, g / m, b / m};
	}
}
