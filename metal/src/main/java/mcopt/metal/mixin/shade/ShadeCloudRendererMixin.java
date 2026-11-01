package mcopt.metal.mixin.shade;

import mcopt.metal.shade.ShadeClouds;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** mcopt's stylized clouds (ShadeClouds) are drawn by the shade pass: vanilla's cloud mesh is skipped while they're on. */
@Mixin(CloudRenderer.class)
abstract class ShadeCloudRendererMixin {
	@Inject(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true)
	private void mcopt$skip(CallbackInfo ci) {
		if (ShadeClouds.active()) ci.cancel();
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
	private void mcopt$skipOit(CallbackInfo ci) {
		if (ShadeClouds.active()) ci.cancel();
	}
}
