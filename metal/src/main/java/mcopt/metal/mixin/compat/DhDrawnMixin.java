package mcopt.metal.mixin.compat;

import mcopt.metal.compat.DhDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the frames Distant Horizons drew its LODs (its depth texture is only current then; see DhDepth). Optional: a DH without it is left as is. */
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer", remap = false)
abstract class DhDrawnMixin {
	@Inject(method = "copyToMcTexture", at = @At("HEAD"), require = 0)
	private void mcopt$drawn(CallbackInfo ci) {
		DhDepth.drawn();
	}
}
