package mcopt.metal.mixin.cpu;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import mcopt.metal.cpu.Cull;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dmcopt.cpu.cull: marks the span of a group's geometry building and the render type being written (see Cull). */
@Mixin(RenderTypeFeatureRenderer.class)
abstract class CullGroupMixin {
	@Inject(method = "prepareGroup", at = @At("HEAD"))
	private void mcopt$begin(FeatureFrameContext context, List<?> submits, boolean strictlyOrdered, CallbackInfo ci) {
		Cull.beginGroup();
	}

	@Inject(method = "prepareGroup", at = @At("RETURN"))
	private void mcopt$end(FeatureFrameContext context, List<?> submits, boolean strictlyOrdered, CallbackInfo ci) {
		Cull.endGroup();
	}

	@Inject(method = "getVertexBuilder", at = @At("HEAD"))
	private void mcopt$type(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
		Cull.type(renderType);
	}
}
