package mcopt.metal.shade;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * God rays in forests: no volumetric light, just a few shafts placed where the canopy has a hole. Every 40 frames (or when the
 * camera has moved 4 blocks) in a forest, taiga or jungle, the columns around the camera are checked against the heightmaps: open to
 * the sky down to the ground (no leaves over it) while the columns 3 blocks away on most sides are roofed by leaves at least 4 blocks
 * up. The nearest such gaps (no two within 4 blocks) become shafts from the canopy's height down to the ground along the sunlight,
 * and shade.fsh draws each as a soft glow. They show while the sun is low but up (morning and late afternoon), not in rain.
 */
public final class ShadeRays {
	static final int MAX = 12;
	/** Search radius around the camera, blocks. */
	private static final int RADIUS = 28;
	/** Per gap: x, z (column centre), canopy y, ground y. */
	static final float[] GAPS = new float[MAX * 4];
	static int count;
	private static double scanX = Double.NaN, scanZ;
	private static int sinceScan;
	private static ClientLevel scanLevel;

	private ShadeRays() {
	}

	/** Shaft strength 0..1 for the sun's height (sin of its elevation) and rain: low but up, morning and late afternoon. */
	static float strength(float sunY, float rain) {
		float rise = smooth(0.04f, 0.16f, sunY), fall = 1f - smooth(0.42f, 0.72f, sunY);
		return count == 0 ? 0f : rise * fall * (1f - rain);
	}

	private static float smooth(float a, float b, float x) {
		float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	static void update(Vec3 cam) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !Shade.RAYS) {
			count = 0;
			return;
		}
		boolean moved = Double.isNaN(scanX) || Math.abs(cam.x - scanX) > 4 || Math.abs(cam.z - scanZ) > 4;
		if (moved || level != scanLevel || ++sinceScan >= 40) scan(level, cam);
	}

	private static void scan(ClientLevel level, Vec3 cam) {
		sinceScan = 0;
		scanLevel = level;
		scanX = cam.x;
		scanZ = cam.z;
		count = 0;
		BlockPos at = BlockPos.containing(cam);
		var biome = level.getBiome(at);
		if (!biome.is(BiomeTags.IS_FOREST) && !biome.is(BiomeTags.IS_TAIGA) && !biome.is(BiomeTags.IS_JUNGLE)) return;
		List<int[]> found = new ArrayList<>();
		int cx = at.getX(), cz = at.getZ();
		for (int z = cz - RADIUS; z <= cz + RADIUS; z++) {
			for (int x = cx - RADIUS; x <= cx + RADIUS; x++) {
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				if (top != ground) continue;  // leaves over it
				int roofed = 0, canopy = 0;
				for (int k = 0; k < 4; k++) {
					int nx = x + (k == 0 ? 3 : k == 1 ? -3 : 0), nz = z + (k == 2 ? 3 : k == 3 ? -3 : 0);
					int nTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz);
					int nGround = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
					if (nTop - nGround >= 4 && nTop - ground >= 4) {
						roofed++;
						canopy += nTop;
					}
				}
				if (roofed < 3) continue;
				int dx = x - cx, dz = z - cz;
				found.add(new int[] {dx * dx + dz * dz, x, z, canopy / roofed, ground});
			}
		}
		found.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int[] f : found) {
			if (count >= MAX) break;
			boolean near = false;
			for (int n = 0; n < count && !near; n++) {
				near = Math.abs(GAPS[n * 4] - (f[1] + 0.5f)) < 4 && Math.abs(GAPS[n * 4 + 1] - (f[2] + 0.5f)) < 4;
			}
			if (near) continue;
			GAPS[count * 4] = f[1] + 0.5f;
			GAPS[count * 4 + 1] = f[2] + 0.5f;
			GAPS[count * 4 + 2] = f[3];
			GAPS[count * 4 + 3] = f[4];
			count++;
		}
	}
}
