package mcopt.metal.shade;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.OptionalDouble;
import mcopt.metal.own.OwnTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

/**
 * The sun shadow map (docs/SHADERS.md, stage 2): one orthographic cascade around the camera, texel-snapped so it does not shimmer, drawn
 * from our terrain arena (OwnTerrain.shadowCull / shadowDraw) at the head of the level render. Reverse-Z depth: larger is nearer the light.
 *
 * The map is only ever drawn at sun angles that are whole multiples of STEP, and the two either side of the real angle are blended
 * (BLEND). Drawing at the exact angle re-rasterized every edge on a slightly turned grid each time the sun crept on, and they flickered;
 * a fixed angle's map is identical from draw to draw, and the crossfade moves the shadows smoothly between them.
 */
public final class ShadeShadow {
	/** Depth range along the light, blocks. The eye sits half of it from the cascade's centre. */
	static final float RANGE = 512f;
	/** Sun angle between maps, radians (~0.86 degrees, about 3 seconds of daylight). */
	private static final double STEP = 0.015;
	private static final Slot[] SLOTS = {new Slot(0), new Slot(1)};
	private static GpuTextureView atlas;
	private static GpuSampler atlasSampler;
	private static boolean failed;
	private static int errors;
	private static boolean logged;
	private static int sinceDraw;
	private static long frame;
	/** The sun's angle as the last shade pass saw it (SkyRenderState.sunAngle); NaN before the first. */
	static volatile float sunAngle = Float.NaN;
	/** Camera-relative world -> shadow clip for this frame's camera, of the map at the angle below the sun's and the one above. */
	static final Matrix4f CLIP = new Matrix4f(), CLIP2 = new Matrix4f();
	/** How far the sun is from the lower map's angle toward the upper's, 0..1. */
	static float BLEND;
	private static GpuTextureView view, view2;

	/** One map: its texture and where (sun angle step, camera) it was drawn. */
	private static final class Slot {
		final int id;
		GpuTexture tex;
		GpuTextureView view;
		GpuBuffer proj;
		final Matrix4f clip = new Matrix4f();
		double x, y, z;
		long k = Long.MIN_VALUE, at;
		float radius;
		boolean ok;

		Slot(int id) {
			this.id = id;
		}
	}

	private ShadeShadow() {
	}

	public static void atlas(GpuTextureView a, GpuSampler s) {
		atlas = a;
		atlasSampler = s;
	}

	/** The lower map, or null when there's none this frame. */
	static GpuTextureView view() {
		return view;
	}

	/** The upper map (the lower one again when only one could be drawn, with BLEND 0). */
	static GpuTextureView view2() {
		return view2;
	}

	static void endFrame() {
		view = view2 = null;
	}

	/** Level render head. */
	public static void render() {
		view = view2 = null;
		if (failed || !Shade.ON || !Shade.MAP || !Shade.enabled || Shade.suppress || Float.isNaN(sunAngle) || atlas == null) return;
		OwnTerrain own = OwnTerrain.get();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) atlas = null;
		if (own == null || mc.level == null || !Shade.sunny(mc.level)) return;
		try {
			draw(own, mc);
		} catch (RuntimeException | LinkageError e) {
			if (++errors >= 5) failed = true;
			System.out.println("[mcopt] shade: shadow map error " + errors + (failed ? ", disabled" : "") + ", " + e);
			e.printStackTrace();
		}
	}

	private static void draw(OwnTerrain own, Minecraft mc) {
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		double x = sunAngle / STEP;
		long k = (long) Math.floor(x);
		float blend = (float) (x - k);
		float radius = Shade.MAP_RADIUS;
		Slot lo = find(k, radius), hi = find(k + 1, radius);
		// A missing angle is drawn now. Otherwise one map a frame is refreshed when it's due (terrain changes) or the camera has
		// wandered 4 blocks from where it was drawn; the snapped grid makes the redraw identical where nothing changed, so no pop.
		frame++;
		boolean due = ++sinceDraw >= Math.max(1, Shade.MAP_EVERY), drew = false;
		if (lo == null) {
			lo = drawInto(other(hi), k, own, mc, cam);
			drew = true;
		}
		if (hi == null) hi = drawInto(other(lo), k + 1, own, mc, cam);
		else if (!drew && lo != null && (due || far(lo, cam) || far(hi, cam))) {
			Slot stale = far(lo, cam) ? lo : far(hi, cam) ? hi : lo.at <= hi.at ? lo : hi;
			Slot fresh = drawInto(stale, stale.k, own, mc, cam);
			if (stale == lo) lo = fresh;
			else hi = fresh;
		}
		if (lo == null && hi == null) return;
		if (lo == null || hi == null) {
			Slot one = lo != null ? lo : hi;
			follow(one, cam, CLIP);
			CLIP2.set(CLIP);
			view = view2 = one.view;
			BLEND = 0f;
			return;
		}
		follow(lo, cam, CLIP);
		follow(hi, cam, CLIP2);
		view = lo.view;
		view2 = hi.view;
		BLEND = blend;
	}

	private static Slot find(long k, float radius) {
		for (Slot s : SLOTS) if (s.ok && s.k == k && s.radius == radius && s.tex != null) return s;
		return null;
	}

	/** The slot that isn't keep (keep may be null). */
	private static Slot other(Slot keep) {
		return keep == SLOTS[0] ? SLOTS[1] : SLOTS[0];
	}

	private static boolean far(Slot s, Vec3 cam) {
		return s != null && (Math.abs(cam.x - s.x) > 4 || Math.abs(cam.y - s.y) > 4 || Math.abs(cam.z - s.z) > 4);
	}

	/** The slot's map, moved to this frame's camera. */
	private static void follow(Slot s, Vec3 cam, Matrix4f out) {
		out.set(s.clip).translate((float) (cam.x - s.x), (float) (cam.y - s.y), (float) (cam.z - s.z));
	}

	/** Draws the map for sun angle k * STEP into the slot; returns it, or null if there was nothing to draw. */
	private static Slot drawInto(Slot slot, long k, OwnTerrain own, Minecraft mc, Vec3 cam) {
		int res = Shade.MAP_RES;
		slot.ok = false;
		if (slot.tex == null) {
			slot.tex = RenderSystem.getDevice().createTexture(() -> "mcopt shadow map " + slot.id, 15, GpuFormat.D32_FLOAT, res, res, 1, 1);
			slot.view = RenderSystem.getDevice().createTextureView(slot.tex);
			slot.proj = RenderSystem.getDevice().createBuffer(() -> "mcopt shadow projection " + slot.id, 136, 64);
		}
		double sa = k * STEP;
		Vector3f l = new Vector3f((float) -Math.sin(sa), (float) Math.cos(sa), 0f);
		if (l.y < -0.05f) l.negate();  // the moon lights the night
		Vector3f f = new Vector3f(l).negate();
		Vector3f ref = Math.abs(l.y) > 0.99f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
		Vector3f r = f.cross(ref, new Vector3f()).normalize();
		Vector3f u = r.cross(f, new Vector3f());
		float radius = Shade.MAP_RADIUS, texel = 2f * radius / res;
		double a = cam.x * r.x + cam.y * r.y + cam.z * r.z, b = cam.x * u.x + cam.y * u.y + cam.z * u.z;
		double da = Math.floor(a / texel) * texel - a, db = Math.floor(b / texel) * texel - b;
		Vector3d c = new Vector3d(r).mul(da).add(new Vector3d(u).mul(db));
		Vector3f center = new Vector3f((float) c.x, (float) c.y, (float) c.z);
		Vector3f eye = new Vector3f(l).mul(RANGE * 0.5f).add(center);
		Matrix4f ortho = new Matrix4f().setOrtho(-radius, radius, -radius, radius, 0f, RANGE, true);
		Matrix4f lookAt = new Matrix4f().lookAt(eye, center, ref);
		Matrix4f clip = new Matrix4f().translation(0, 0, 1).scale(1, 1, -1).mul(ortho).mul(lookAt);
		if (!logged) {
			logged = true;
			System.out.println("[mcopt] shade: shadow map " + res + " radius " + radius + " texel " + texel + " step " + STEP);
		}
		if (!own.shadowCull(clip, l.x, l.y, l.z)) return null;
		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var data = Std140Builder.onStack(stack, 64).putMat4f(clip).get();
			enc.writeToBuffer(slot.proj.slice(), data);
		}
		try (RenderPass pass = enc.createRenderPass(com.mojang.renderpearl.api.commands.RenderPassDescriptor.builder(() -> "mcopt shadow map").withDepthAttachment(slot.view, OptionalDouble.of(0.0)).build())) {
			own.shadowDraw(clip, atlas, atlasSampler, slot.proj.slice());
		}
		slot.clip.set(clip);
		slot.x = cam.x;
		slot.y = cam.y;
		slot.z = cam.z;
		slot.k = k;
		slot.radius = radius;
		slot.ok = true;
		slot.at = frame;
		sinceDraw = 0;
		return slot;
	}
}
