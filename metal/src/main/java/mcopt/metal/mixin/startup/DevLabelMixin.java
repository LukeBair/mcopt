package mcopt.metal.mixin.startup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dmcopt.dev.label=TEXT (development only): says who launched this instance and what it is testing, so a person looking at one
 * of several test clients knows what it is. The window title ends with [TEXT], and a local chat line (shown only on this client,
 * never sent to the server) repeats it each time a level loads.
 */
@Mixin(Minecraft.class)
abstract class DevLabelMixin {
	private static final String LABEL = System.getProperty("mcopt.dev.label");

	@Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
	private void mcopt$titleLabel(CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(cir.getReturnValue() + " [" + LABEL + "]");
	}

	@Inject(method = "setLevel", at = @At("TAIL"))
	private void mcopt$chatLabel(ClientLevel level, CallbackInfo ci) {
		((Minecraft) (Object) this).gui.hud.getChat().addClientSystemMessage(Component.literal("[mcopt test] " + LABEL));
		System.out.println("[mcopt] dev label: " + LABEL);
	}
}
