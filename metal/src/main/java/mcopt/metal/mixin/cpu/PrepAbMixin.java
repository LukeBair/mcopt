package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.Ab;
import mcopt.metal.cpu.Cull;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dmcopt.cpu.cull=ab: times FeatureRenderDispatcher.prepareFrame (where entity geometry is built) with the lever alternately off/on per frame. */
@Mixin(FeatureRenderDispatcher.class)
abstract class PrepAbMixin {
	@Unique private static final Ab mcopt$ab = new Ab("cull.prepareFrame");

	@Inject(method = "prepareFrame", at = @At("HEAD"))
	private void mcopt$begin(CallbackInfoReturnable<?> cir) {
		if (Cull.AB) Cull.active = mcopt$ab.begin();
	}

	@Inject(method = "prepareFrame", at = @At("RETURN"))
	private void mcopt$end(CallbackInfoReturnable<?> cir) {
		if (Cull.AB) {
			mcopt$ab.end();
			if (Ab.frame % 600 == 1 && Cull.active) System.out.println("mcopt-cpu: cull quads culled " + Cull.culled + " kept " + Cull.kept);
		}
	}
}
