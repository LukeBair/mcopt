package mcopt.metal.mixin.shade;

import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.shade.RenderScale;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render scale (RenderScale): from just before the level render to just after the post effects (mcopt's shade pass included),
 * GameRenderer's main and hand targets are our smaller ones; then the level is stretched onto the window's and the GUI draws as usual.
 */
@Mixin(GameRenderer.class)
abstract class ShadeRenderScaleMixin {
	@Shadow @Final @Mutable private RenderTarget mainRenderTarget;
	@Shadow @Final @Mutable private RenderTarget hud3DTarget;
	@Unique private RenderTarget mcopt$windowMain, mcopt$windowHud;

	@Inject(method = "render", at = @At("HEAD"))
	private void mcopt$restore(CallbackInfo ci) {
		this.mcopt$unswap();  // (a level render that threw last frame)
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void mcopt$scaleBegin(CallbackInfo ci) {
		RenderTarget level = RenderScale.level(this.mainRenderTarget);
		if (level == null) return;
		this.mcopt$windowMain = this.mainRenderTarget;
		this.mcopt$windowHud = this.hud3DTarget;
		this.mainRenderTarget = level;
		this.hud3DTarget = RenderScale.hud(this.hud3DTarget);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void mcopt$scaleEnd(CallbackInfo ci) {
		if (this.mcopt$windowMain == null) return;
		RenderTarget window = this.mcopt$windowMain;
		this.mcopt$unswap();
		RenderScale.present(window);
	}

	@Unique
	private void mcopt$unswap() {
		if (this.mcopt$windowMain == null) return;
		this.mainRenderTarget = this.mcopt$windowMain;
		this.hud3DTarget = this.mcopt$windowHud;
		this.mcopt$windowMain = this.mcopt$windowHud = null;
	}
}
