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
 */
public final class ShadeShadow {
	/** Depth range along the light, blocks. The eye sits half of it from the cascade's centre. */
	static final float RANGE = 512f;
	private static GpuTexture tex;
	private static GpuTextureView view;
	private static GpuBuffer proj;
	private static GpuTextureView atlas;
	private static GpuSampler atlasSampler;
	private static boolean failed, valid;
	private static boolean logged;
	/** The sun's angle as the last shade pass saw it (SkyRenderState.sunAngle); NaN before the first. */
	static volatile float sunAngle = Float.NaN;
	/** Camera-relative world -> shadow clip, of the map drawn this frame. */
	static final Matrix4f CLIP = new Matrix4f();
	/** Direction to the light, world space. */
	static final Vector3f LIGHT = new Vector3f(0, 1, 0);

	private ShadeShadow() {
	}

	public static void atlas(GpuTextureView a, GpuSampler s) {
		atlas = a;
		atlasSampler = s;
	}

	static GpuTextureView view() {
		return valid ? view : null;
	}

	static void endFrame() {
		valid = false;
	}

	/** Level render head. */
	public static void render() {
		valid = false;
		if (failed || !Shade.ON || !Shade.MAP || Shade.suppress || Float.isNaN(sunAngle) || atlas == null) return;
		OwnTerrain own = OwnTerrain.get();
		Minecraft mc = Minecraft.getInstance();
		if (own == null || mc.level == null) return;
		try {
			draw(own, mc);
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			System.out.println("[mcopt] shade: shadow map disabled, " + e);
			e.printStackTrace();
		}
	}

	private static void draw(OwnTerrain own, Minecraft mc) {
		int res = Shade.MAP_RES;
		if (tex == null) {
			tex = RenderSystem.getDevice().createTexture(() -> "mcopt shadow map", 15, GpuFormat.D32_FLOAT, res, res, 1, 1);
			view = RenderSystem.getDevice().createTextureView(tex);
			proj = RenderSystem.getDevice().createBuffer(() -> "mcopt shadow projection", 136, 64);
		}
		double sa = sunAngle;
		Vector3f l = new Vector3f((float) -Math.sin(sa), (float) Math.cos(sa), 0f);
		if (l.y < -0.05f) l.negate();  // the moon lights the night
		LIGHT.set(l);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
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
		CLIP.identity().translation(0, 0, 1).scale(1, 1, -1).mul(ortho).mul(lookAt);
		if (!logged) {
			logged = true;
			System.out.println("[mcopt] shade: shadow map " + res + " radius " + radius + " texel " + texel + " light " + l);
		}
		if (!own.shadowCull(CLIP, l.x, l.y, l.z)) return;
		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var data = Std140Builder.onStack(stack, 64).putMat4f(CLIP).get();
			enc.writeToBuffer(proj.slice(), data);
		}
		try (RenderPass pass = enc.createRenderPass(com.mojang.renderpearl.api.commands.RenderPassDescriptor.builder(() -> "mcopt shadow map").withDepthAttachment(view, OptionalDouble.of(0.0)).build())) {
			own.shadowDraw(CLIP, atlas, atlasSampler, proj.slice());
		}
		valid = true;
	}
}
