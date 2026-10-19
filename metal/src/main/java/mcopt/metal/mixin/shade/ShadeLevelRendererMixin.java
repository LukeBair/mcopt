package mcopt.metal.mixin.shade;

import mcopt.metal.shade.ShadeShadow;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** mcopt shade: the sun shadow map is drawn before the level's own passes. */
@Mixin(LevelRenderer.class)
abstract class ShadeLevelRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcopt$shadowMap(CallbackInfo ci) {
		ShadeShadow.render();
	}
}
