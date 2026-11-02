package mcopt.metal.mixin.shade;

import mcopt.metal.shade.ShadeSplash;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** mcopt shade: splashes from crashing breakers and tall waterfalls, with the level's own ambient particles each tick. */
@Mixin(ClientLevel.class)
abstract class ShadeClientLevelMixin {
	@Inject(method = "animateTick", at = @At("HEAD"))
	private void mcopt$splash(int x, int y, int z, CallbackInfo ci) {
		ShadeSplash.tick((ClientLevel) (Object) this, x, y, z);
	}
}
