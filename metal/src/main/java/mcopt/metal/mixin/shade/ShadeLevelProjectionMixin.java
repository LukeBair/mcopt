package mcopt.metal.mixin.shade;

import mcopt.metal.shade.ShadePass;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The level's projection exactly as renderLevel uploads it (bob and nausea applied), for the shade pass's reconstruction. */
@Mixin(GameRenderer.class)
abstract class ShadeLevelProjectionMixin {
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f mcopt$levelProjection(Matrix4f m) {
		ShadePass.levelProjection(m);
		return m;
	}
}
