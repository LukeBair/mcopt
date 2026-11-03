package mcopt.metal.mixin.shade;

import mcopt.metal.shade.ShadeImpact;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** mcopt shade, an entity hitting the water (vanilla's splash effect, client and server): rings on the surface and a splash to match its weight. */
@Mixin(Entity.class)
abstract class ShadeEntityMixin {
	@Inject(method = "doWaterSplashEffect", at = @At("HEAD"))
	private void mcopt$impact(CallbackInfo ci) {
		ShadeImpact.enter((Entity) (Object) this);
	}
}
