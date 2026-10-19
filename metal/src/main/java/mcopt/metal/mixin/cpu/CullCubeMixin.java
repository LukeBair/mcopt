package mcopt.metal.mixin.cpu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mcopt.metal.cpu.Cull;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dmcopt.cpu.cull: ModelPart.Cube.compile with back-facing polygons left out (see Cull). The first three vertices of a polygon
 * are transformed to test it; a kept polygon is written with the same positions, normal and order as vanilla.
 */
@Mixin(ModelPart.Cube.class)
abstract class CullCubeMixin {
	@Shadow @Final private ModelPart.Polygon[] polygons;

	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void mcopt$compile(PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords, int color, CallbackInfo ci) {
		if (!Cull.on) return;
		Matrix4f matrix = pose.pose();
		Vector3f s = new Vector3f();
		for (ModelPart.Polygon polygon : this.polygons) {
			ModelPart.Vertex[] vs = polygon.vertices();
			if (vs.length != 4) { // not a quad: write it as vanilla does
				mcopt$plain(pose, matrix, s, polygon, builder, lightCoords, overlayCoords, color);
				continue;
			}
			ModelPart.Vertex v0 = vs[0], v1 = vs[1], v2 = vs[2], v3 = vs[3];
			matrix.transformPosition(v0.worldX(), v0.worldY(), v0.worldZ(), s);
			float ax = s.x, ay = s.y, az = s.z;
			matrix.transformPosition(v1.worldX(), v1.worldY(), v1.worldZ(), s);
			float bx = s.x, by = s.y, bz = s.z;
			matrix.transformPosition(v2.worldX(), v2.worldY(), v2.worldZ(), s);
			float cx = s.x, cy = s.y, cz = s.z;
			if (Cull.back(ax, ay, az, bx, by, bz, cx, cy, cz)) continue;
			matrix.transformPosition(v3.worldX(), v3.worldY(), v3.worldZ(), s);
			float dx = s.x, dy = s.y, dz = s.z;
			Vector3f normal = pose.transformNormal(polygon.normal(), s);
			float nx = normal.x(), ny = normal.y(), nz = normal.z();
			builder.addVertex(ax, ay, az, color, v0.u(), v0.v(), overlayCoords, lightCoords, nx, ny, nz);
			builder.addVertex(bx, by, bz, color, v1.u(), v1.v(), overlayCoords, lightCoords, nx, ny, nz);
			builder.addVertex(cx, cy, cz, color, v2.u(), v2.v(), overlayCoords, lightCoords, nx, ny, nz);
			builder.addVertex(dx, dy, dz, color, v3.u(), v3.v(), overlayCoords, lightCoords, nx, ny, nz);
		}
		ci.cancel();
	}

	private static void mcopt$plain(PoseStack.Pose pose, Matrix4f matrix, Vector3f s, ModelPart.Polygon polygon, VertexConsumer builder, int lightCoords,
		int overlayCoords, int color) {
		Vector3f normal = pose.transformNormal(polygon.normal(), s);
		float nx = normal.x(), ny = normal.y(), nz = normal.z();
		for (ModelPart.Vertex vertex : polygon.vertices()) {
			Vector3f pos = matrix.transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), s);
			builder.addVertex(pos.x(), pos.y(), pos.z(), color, vertex.u(), vertex.v(), overlayCoords, lightCoords, nx, ny, nz);
		}
	}
}
