package mcopt.metal.shade;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

/**
 * Splashes where the water churns (ShadeWater): spray and droplets thrown up along the shore as each breaker crashes, in step with the
 * shader's (the same breakerPhase, from the same clock), and a constant splash and mist at the foot of every tall waterfall (FALL
 * blocks or more, growing with the fall). The sites near the player are found every 2 seconds (or after moving 8 blocks); particles are spawned each tick.
 */
public final class ShadeSplash {
	/** How far from the player sites are found, blocks (horizontally; waterfalls also within this vertically). */
	private static final int RADIUS = 32;
	/** terrain.metal's BREAK_CRASH, and the crash's peak just after it (own_vs' crash term), as a share of the wave. */
	private static final double CRASH = 0.42;
	private static final double W = Math.PI * 2 / 1024;
	/** Shore sites: sea water touching the beach (x, z, and the direction to the land ×100: dx, dz). */
	private static final List<int[]> shore = new ArrayList<>();
	/** Waterfall feet: x, the surface's block y, z, intensity 0..7 (ShadeWater.power). */
	private static final List<int[]> falls = new ArrayList<>();
	private static int scanX = Integer.MIN_VALUE, scanZ, sinceScan;
	private static ClientLevel scanLevel;

	private ShadeSplash() {
	}

	/** Each client tick (ClientLevel.animateTick, around the player at x, y, z). */
	public static void tick(ClientLevel level, int x, int y, int z) {
		if (!ShadeWater.tagging() || !Shade.enabled) {
			shore.clear();
			falls.clear();
			scanLevel = null;
			return;
		}
		if (level != scanLevel || ++sinceScan >= 40 || Math.abs(x - scanX) > 8 || Math.abs(z - scanZ) > 8) scan(level, x, y, z);
		RandomSource r = level.getRandom();
		float storm = Math.min(1f, (level.getRainLevel(1f) + level.getThunderLevel(1f)) / 1.6f);
		// the shader's clock (GameTime * 1200: seconds, wrapping each 24000-tick day) now and a tick ago
		double t = (level.getGameTime() % 24000L) / 20.0;
		int sea = level.getSeaLevel();
		for (int[] s : shore) {
			double px = (s[0] & 1023) + 0.5, pz = (s[1] & 1023) + 0.5;
			double c = phase(px, pz, t), was = phase(px, pz, t - 0.05);
			if (Math.floor(c - CRASH) == Math.floor(was - CRASH)) continue;
			// (crossed the crash this tick; a 171-cycle day keeps the phase continuous across the wrap)
			float size = size(Math.floor(c));
			double dx = s[2] / 100.0, dz = s[3] / 100.0;
			double cx = s[0] + 0.5 + dx * 0.35, cz = s[1] + 0.5 + dz * 0.35, top = sea - 1 + 0.95;
			int drops = Math.round(5 * size * (1 + 1.5f * storm));
			for (int i = 0; i < drops; i++) {
				level.addParticle(ParticleTypes.SPLASH, cx + r.nextGaussian() * 0.35, top, cz + r.nextGaussian() * 0.35,
					dx * 0.08 + r.nextGaussian() * 0.04, 0.2 + r.nextDouble() * 0.15, dz * 0.08 + r.nextGaussian() * 0.04);
			}
			int spray = r.nextFloat() < 0.6f * size ? 1 + Math.round(2 * storm) : 0;
			for (int i = 0; i < spray; i++) {
				level.addParticle(ParticleTypes.CLOUD, cx + r.nextGaussian() * 0.3, top + 0.1, cz + r.nextGaussian() * 0.3,
					dx * 0.03 + r.nextGaussian() * 0.01, 0.04 + r.nextDouble() * 0.04, dz * 0.03 + r.nextGaussian() * 0.01);
			}
			if (r.nextFloat() < 0.5f) level.addParticle(ParticleTypes.BUBBLE_POP, cx + r.nextGaussian() * 0.4, top, cz + r.nextGaussian() * 0.4, 0, 0, 0);
		}
		for (int[] f : falls) {
			// (more and higher the farther it falls: f[3], 0..7)
			double cx = f[0] + 0.5, top = f[1] + 0.95, cz = f[2] + 0.5, p = f[3] / 7.0;
			int drops = 1 + (int) Math.round(3 * p);
			for (int i = 0; i < drops; i++) {
				double a = r.nextDouble() * Math.PI * 2, out = 0.05 + r.nextDouble() * 0.1;
				level.addParticle(ParticleTypes.SPLASH, cx + Math.cos(a) * 0.5, top, cz + Math.sin(a) * 0.5,
					Math.cos(a) * out, 0.15 + 0.15 * p + r.nextDouble() * 0.2, Math.sin(a) * out);
			}
			if (r.nextFloat() < 0.15f + 0.45f * p) {
				level.addParticle(ParticleTypes.CLOUD, cx + r.nextGaussian() * 0.6, top + 0.2 + r.nextDouble() * 0.5, cz + r.nextGaussian() * 0.6,
					r.nextGaussian() * 0.02, 0.03 + r.nextDouble() * 0.04, r.nextGaussian() * 0.02);
			}
			if (r.nextFloat() < 0.6f) level.addParticle(ParticleTypes.BUBBLE_POP, cx + r.nextGaussian() * 1.2, top, cz + r.nextGaussian() * 1.2, 0, 0, 0);
		}
	}

	/** terrain.metal's breakerPhase before its floor: c, the wave number plus the share of it gone (p: world xz mod 1024; t: seconds). */
	private static double phase(double px, double pz, double t) {
		return t * (171.0 / 1200.0) + (px * 6.0 + pz * 4.0) / 1024.0 + 0.08 * Math.sin(px * W * 3.0 + pz * W * 2.0)
			+ 0.05 * Math.sin(pz * W * 5.0 - px * W * 3.0);
	}

	/** terrain.metal's breakerSize: wave n's size, 0.7..1.3 (in floats, as the GPU works it out). */
	private static float size(double n) {
		float f = (float) n;
		float x = fract(f * 0.1031f), y = fract(f * 1.37f * 0.1031f), z = fract(f * 0.71f * 0.1031f);
		float d = x * (y + 33.33f) + y * (z + 33.33f) + z * (x + 33.33f);
		x += d;
		y += d;
		z += d;
		return 0.7f + 0.6f * fract((x + y) * z);
	}

	private static float fract(float v) {
		return v - (float) Math.floor(v);
	}

	private static void scan(ClientLevel level, int px, int py, int pz) {
		scanLevel = level;
		scanX = px;
		scanZ = pz;
		sinceScan = 0;
		shore.clear();
		falls.clear();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		// the shore: sea-level water open to the sky with land beside it, by an ocean or beach
		int y = level.getSeaLevel() - 1;
		if (Math.abs(py - y) <= RADIUS * 2) {
			for (int z = pz - RADIUS; z <= pz + RADIUS; z++) {
				for (int x = px - RADIUS; x <= px + RADIUS; x++) {
					FluidState f = level.getFluidState(m.set(x, y, z));
					if (!f.is(FluidTags.WATER) || !f.isSource() || !level.getBlockState(m.set(x, y + 1, z)).isAir()) continue;
					int lx = 0, lz = 0, land = 0;
					for (int dz = -1; dz <= 1; dz++) {
						for (int dx = -1; dx <= 1; dx++) {
							if ((dx | dz) == 0 || level.getFluidState(m.set(x + dx, y, z + dz)).is(FluidTags.WATER)) continue;
							lx += dx;
							lz += dz;
							land++;
						}
					}
					if (land == 0 || !ShadeWater.ocean(level, m.set(x, y, z))) continue;
					double len = Math.max(1e-3, Math.sqrt(lx * lx + lz * lz));
					shore.add(new int[] {x, z, (int) Math.round(lx / len * 100), (int) Math.round(lz / len * 100)});
				}
			}
		}
		// waterfall feet: the bottom of each falling column FALL blocks or taller, in the sections around the player
		int r = RADIUS >> 4, cx = px >> 4, cy = py >> 4, cz = pz >> 4;
		for (int sx = cx - r; sx <= cx + r; sx++) {
			for (int sz = cz - r; sz <= cz + r; sz++) {
				if (!(level.getChunkSource().getChunk(sx, sz, false) instanceof LevelChunk chunk)) continue;
				LevelChunkSection[] sections = chunk.getSections();
				for (int sy = cy - r; sy <= cy + r; sy++) {
					int i = sy - level.getMinSectionY();
					if (i < 0 || i >= sections.length) continue;
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.getStates().maybeHas(s -> ShadeWater.falling(s.getFluidState()))) continue;
					for (int ly = 0; ly < 16; ly++) {
						for (int lz = 0; lz < 16; lz++) {
							for (int lx = 0; lx < 16; lx++) {
								if (!ShadeWater.falling(section.getBlockState(lx, ly, lz).getFluidState())) continue;
								int wx = (sx << 4) + lx, wy = (sy << 4) + ly, wz = (sz << 4) + lz;
								if (ShadeWater.falling(level.getFluidState(m.set(wx, wy - 1, wz)))) continue;
								int h = ShadeWater.fallen(level, m, wx, wy, wz, ShadeWater.CAP) + 1;
								if (h < ShadeWater.FALL) continue;
								falls.add(new int[] {wx, ShadeWater.surfaceUnder(level, m, wx, wy, wz), wz, ShadeWater.power(h)});
							}
						}
					}
				}
			}
		}
	}
}
