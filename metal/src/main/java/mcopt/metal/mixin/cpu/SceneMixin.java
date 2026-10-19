package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.Scene;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.cpu.scene=true: drives Scene (the development scene) from the client tick. */
@Mixin(Minecraft.class)
abstract class SceneMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void mcopt$scene(CallbackInfo ci) {
		Scene.tick((Minecraft) (Object) this);
	}
}
