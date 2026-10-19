package mcopt.metal.cpu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;

/**
 * -Dmcopt.cpu.cull=true|ab: back-face culling on the CPU for model-part cubes (entities, block entities) built in the feature
 * renderers' prepareGroup. (Item/block-model quads through VertexConsumer.putBakedQuad were tried and are slower: not done.) A polygon whose render type culls back faces (pipeline cull on) and which faces away from the eye by more than
 * ~1.1 degrees past edge-on is not written at all: the GPU would have discarded both its triangles before rasterization (front
 * face = counter-clockwise, as the Metal backend sets), so no pixel changes. Quads within the margin, render types that don't
 * cull and render types whose quads are sorted on upload are written as before.
 *
 * The eye is the origin of the model-view matrix in force at prepare time (its affine inverse applied to 0), in the space the
 * vertices are written in.
 */
public final class Cull {
	public static final boolean AB = "ab".equals(System.getProperty("mcopt.cpu.cull"));
	public static final boolean ON = Boolean.getBoolean("mcopt.cpu.cull") || AB;
	/** The lever for the current frame (always, unless cull=ab alternates it). */
	public static boolean active = true;
	/** cos^2 of the margin angle (about 1.15 degrees). */
	private static final float K2 = 4.0e-4F;

	private static boolean in;
	/** The current render type's quads may be culled. */
	public static boolean on;
	private static float ex, ey, ez;
	private static RenderType lastType;
	private static boolean lastOn;
	public static long culled, kept;

	private Cull() {
	}

	public static void beginGroup() {
		in = false;
		on = false;
		lastType = null;
		if (!ON || !active) return;
		Matrix4f mv = RenderSystem.getModelViewMatrixCopy();
		if ((mv.properties() & Matrix4f.PROPERTY_AFFINE) == 0) return;
		org.joml.Vector3f e = mv.invertAffine().transformPosition(0, 0, 0, new org.joml.Vector3f());
		if (!Float.isFinite(e.x) || !Float.isFinite(e.y) || !Float.isFinite(e.z)) return;
		ex = e.x;
		ey = e.y;
		ez = e.z;
		in = true;
	}

	public static void endGroup() {
		in = false;
		on = false;
		lastType = null;
	}

	/** A vertex builder was asked for this render type. */
	public static void type(RenderType t) {
		if (!in) return;
		if (t != lastType) {
			lastType = t;
			lastOn = t.pipeline().isCull() && !t.sortOnUpload();
		}
		on = lastOn;
	}

	/** Whether triangle (a, b, c), in vertex space, faces away from the eye by more than the margin. */
	public static boolean back(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz) {
		float ux = bx - ax, uy = by - ay, uz = bz - az;
		float vx = cx - ax, vy = cy - ay, vz = cz - az;
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float dx = ex - ax, dy = ey - ay, dz = ez - az;
		float t = nx * dx + ny * dy + nz * dz;
		if (t >= 0.0F) {
			kept++;
			return false;
		}
		float nn = nx * nx + ny * ny + nz * nz, dd = dx * dx + dy * dy + dz * dz;
		boolean b = t * t > K2 * nn * dd;
		if (b) culled++; else kept++;
		return b;
	}
}
