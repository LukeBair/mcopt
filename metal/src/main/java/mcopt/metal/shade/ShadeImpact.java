package mcopt.metal.shade;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Things hitting the water (any entity: the client's own splash effect, Entity.doWaterSplashEffect, runs for all of them). Each
 * impact is kept for a few seconds and handed to terrain.metal (DrawFrame.hit) to ring the surface; the heavier ones (volume of the
 * bounding box times speed) also throw extra droplets, spray and bubbles. Fast swimmers leave a trail of bubbles.
 */
public final class ShadeImpact {
	/** Bytes this adds to the terrain's DrawFrame (terrain.metal: float4 hit[16]). */
	public static final int BYTES = 16 * 16;
	private static final int MAX = 16;
	/** How far impacts are kept and trails drawn, blocks. */
	private static final double KEEP = 64, TRAIL = 48;
	/** The most bubbles a tick's trails make. */
	private static final int BUBBLES = 40;
	/** Impact sites, newest first: x, surface y, z; start (game ticks); strength 0..1. */
	private static final double[] X = new double[MAX], Y = new double[MAX], Z = new double[MAX];
	private static final long[] START = new long[MAX];
	private static final float[] STRENGTH = new float[MAX];
	private static int count;
	private static ClientLevel last;

	private ShadeImpact() {
	}

	/** Seconds of life: terrain.metal's 1.4 + 1.8 * strength. */
	private static double life(float s) {
		return 1.4 + 1.8 * s;
	}

	/** The entity e has just touched water (Entity.doWaterSplashEffect, client side). */
	public static void enter(Entity e) {
		if (!Shade.ON || !Shade.enabled || !(e.level() instanceof ClientLevel level)) return;
		Entity cam = Minecraft.getInstance().getCameraEntity();
		if (cam == null || e.distanceToSqr(cam) > KEEP * KEEP) return;
		AABB bb = e.getBoundingBox();
		Vec3 m = e.getDeltaMovement();
		double dx = e.getX() - e.xo, dy = e.getY() - e.yo, dz = e.getZ() - e.zo;
		double speed = Math.min(1.6, Math.max(m.length(), Math.sqrt(dx * dx + dy * dy + dz * dz)));
		// heaviness: the box's volume times the speed it hits at (a thin arrow's box is mostly air: a quarter of it)
		double h = bb.getXsize() * bb.getYsize() * bb.getZsize() * speed * (e instanceof Projectile ? 0.25 : 1.0);
		if (h < 0.001) return;
		// (item ~0.1, arrow ~0.2, player jumping in ~0.45, a player or cow falling 20 blocks ~0.8..1)
		float s = (float) Math.min(1.0, 0.08 + 0.92 * Math.sqrt(h / 1.6));
		double x = e.getX(), z = e.getZ();
		double y = bb.minY + Math.min(e.getFluidHeight(FluidTags.WATER), bb.getYsize());
		long now = level.getGameTime();
		if (level != last) {
			last = level;
			count = 0;
		}
		// (a rider and its mount, or a thing bobbing at the surface, report together: one site per spot)
		for (int i = 0; i < count; i++) {
			if (now - START[i] <= 6 && Math.abs(X[i] - x) < 1.2 && Math.abs(Z[i] - z) < 1.2) {
				if (STRENGTH[i] >= s) return;
				System.arraycopy(X, i + 1, X, i, count - i - 1);
				System.arraycopy(Y, i + 1, Y, i, count - i - 1);
				System.arraycopy(Z, i + 1, Z, i, count - i - 1);
				System.arraycopy(START, i + 1, START, i, count - i - 1);
				System.arraycopy(STRENGTH, i + 1, STRENGTH, i, count - i - 1);
				count--;
				break;
			}
		}
		int n = Math.min(count, MAX - 1);
		System.arraycopy(X, 0, X, 1, n);
		System.arraycopy(Y, 0, Y, 1, n);
		System.arraycopy(Z, 0, Z, 1, n);
		System.arraycopy(START, 0, START, 1, n);
		System.arraycopy(STRENGTH, 0, STRENGTH, 1, n);
		X[0] = x;
		Y[0] = y;
		Z[0] = z;
		START[0] = now;
		STRENGTH[0] = s;
		count = n + 1;
		if (s >= 0.4f && e.distanceToSqr(cam) < TRAIL * TRAIL) burst(level, x, y, z, s);
	}

	/** Player-sized and up: droplets thrown up and out in a ring, spray for the big ones, pops on the surface, bubbles just under it. */
	private static void burst(ClientLevel level, double x, double y, double z, float s) {
		RandomSource r = level.getRandom();
		float k = (s - 0.4f) / 0.6f;
		int drops = 8 + Math.round(28 * k);
		for (int i = 0; i < drops; i++) {
			double a = r.nextDouble() * Math.PI * 2, rad = 0.2 + 0.5 * s * r.nextDouble(), out = 0.04 + 0.12 * s * r.nextDouble();
			level.addParticle(ParticleTypes.SPLASH, x + Math.cos(a) * rad, y, z + Math.sin(a) * rad,
				Math.cos(a) * out, 0.2 + 0.3 * s + r.nextDouble() * 0.2, Math.sin(a) * out);
		}
		if (s > 0.65f) {
			int puffs = 2 + Math.round(6 * (s - 0.65f) / 0.35f);
			for (int i = 0; i < puffs; i++) {
				double a = r.nextDouble() * Math.PI * 2, rad = 0.3 + 0.6 * s * r.nextDouble();
				level.addParticle(ParticleTypes.CLOUD, x + Math.cos(a) * rad, y + 0.1, z + Math.sin(a) * rad,
					Math.cos(a) * 0.03, 0.05 + r.nextDouble() * 0.07, Math.sin(a) * 0.03);
			}
		}
		int pops = 2 + Math.round(6 * s);
		for (int i = 0; i < pops; i++) {
			level.addParticle(ParticleTypes.BUBBLE_POP, x + r.nextGaussian() * 0.5 * s, y, z + r.nextGaussian() * 0.5 * s, 0, 0, 0);
		}
		int bubbles = 6 + Math.round(30 * s);
		for (int i = 0; i < bubbles; i++) {
			level.addParticle(ParticleTypes.BUBBLE, x + r.nextGaussian() * (0.3 + 0.4 * s), y - 0.2 - r.nextDouble() * (0.6 + 1.2 * s),
				z + r.nextGaussian() * (0.3 + 0.4 * s), r.nextGaussian() * 0.03, r.nextGaussian() * 0.03 - 0.02, r.nextGaussian() * 0.03);
		}
	}

	/** Each client tick (ClientLevel.animateTick, around the player at x, y, z): bubble trails behind fast things under water. */
	public static void tick(ClientLevel level, int x, int y, int z) {
		if (!Shade.ON || !Shade.enabled) {
			count = 0;
			return;
		}
		RandomSource r = level.getRandom();
		int left = BUBBLES;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Entity e : level.entitiesForRendering()) {
			if (left <= 0) break;
			if (e.isSpectator() || e.isRemoved() || !e.isInWater() || e.distanceToSqr(x, y, z) > TRAIL * TRAIL) continue;
			double dx = e.getX() - e.xo, dy = e.getY() - e.yo, dz = e.getZ() - e.zo;
			double speed = Math.max(e.getDeltaMovement().length(), Math.sqrt(dx * dx + dy * dy + dz * dz));
			if (speed < 0.25 || !level.getFluidState(m.set(e.getX(), e.getY(0.5), e.getZ())).is(FluidTags.WATER)) continue;
			// (faster and bigger leaves more; slowing down it thins out and stops at 0.2)
			double want = Math.min(8.0, (speed - 0.2) * 6.0 * Math.sqrt(e.getBbWidth() * e.getBbHeight()));
			int n = Math.min(left, (int) (want + r.nextDouble()));
			AABB bb = e.getBoundingBox();
			for (int i = 0; i < n; i++) {
				double back = r.nextDouble() * 1.5;
				level.addParticle(ParticleTypes.BUBBLE, bb.minX + r.nextDouble() * bb.getXsize() - dx * back, bb.minY + r.nextDouble() * bb.getYsize() - dy * back,
					bb.minZ + r.nextDouble() * bb.getZsize() - dz * back, r.nextGaussian() * 0.02, r.nextGaussian() * 0.02, r.nextGaussian() * 0.02);
			}
			left -= n;
		}
	}

	/** Writes the impacts at d (terrain.metal DrawFrame.hit): x and z (mod 1024), age (seconds), strength; the live ones first, then zeros. */
	public static void put(long d) {
		ClientLevel level = Minecraft.getInstance().level;
		MemoryUtil.memSet(d, 0, BYTES);
		Entity cam = Minecraft.getInstance().getCameraEntity();
		if (!Shade.ON || !Shade.enabled || level == null || level != last || cam == null) return;
		double now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		int w = 0;
		for (int i = 0; i < count; i++) {
			double age = (now - START[i]) / 20.0;
			if (age >= life(STRENGTH[i]) || cam.distanceToSqr(X[i], Y[i], Z[i]) > KEEP * KEEP) continue;
			long p = d + w++ * 16L;
			MemoryUtil.memPutFloat(p, (float) Math.floorMod((long) Math.floor(X[i]), 1024L) + (float) (X[i] - Math.floor(X[i])));
			MemoryUtil.memPutFloat(p + 4, (float) Math.floorMod((long) Math.floor(Z[i]), 1024L) + (float) (Z[i] - Math.floor(Z[i])));
			MemoryUtil.memPutFloat(p + 8, (float) Math.max(0.0, age));
			MemoryUtil.memPutFloat(p + 12, STRENGTH[i]);
		}
	}
}
