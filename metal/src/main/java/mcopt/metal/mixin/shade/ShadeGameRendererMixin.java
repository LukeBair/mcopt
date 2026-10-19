package mcopt.metal.mixin.shade;

import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeDepth;
import mcopt.metal.shade.ShadePass;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** mcopt shade (-Dmcopt.shade=true): the built-in shader pass runs after vanilla's post effects, before the GUI. */
@Mixin(GameRenderer.class)
abstract class ShadeGameRendererMixin {
	@Shadow @Final private RenderTarget mainRenderTarget;
	@Shadow @Final private CrossFrameResourcePool resourcePool;
	@Shadow @Final private GameRenderState gameRenderState;

	@Inject(method = "render3dHud", at = @At("HEAD"))
	private void mcopt$shadeDepth(CallbackInfo ci) {
		if (Shade.ON) ShadeDepth.capture(this.mainRenderTarget);
	}

	@Inject(method = "applyPostEffects", at = @At("TAIL"))
	private void mcopt$shade(CallbackInfo ci) {
		if (!Shade.ON && Shade.SHOT <= 0) return;
		var level = this.gameRenderState.levelRenderState;
		if (Shade.ON && !Shade.suppress) ShadePass.run(this.mainRenderTarget, this.resourcePool, level.cameraRenderState, level.skyRenderState, 1f - level.skyRenderState.rainBrightness);
		Shade.maybeShot(this.mainRenderTarget);
		ShadeDepth.endFrame();
	}
}
