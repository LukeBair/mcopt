package mcopt.metal.mixin.shade;

import mcopt.metal.shade.ShadeScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A "Shaders..." button at the end of Video Settings, so the shader settings open without Mod Menu too. */
@Mixin(VideoSettingsScreen.class)
abstract class ShadeVideoSettingsMixin extends OptionsSubScreen {
	private ShadeVideoSettingsMixin() {
		super(null, null, null);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void mcopt$shadeButton(CallbackInfo ci) {
		this.list.addBig(Button.builder(Component.literal("mcopt Shaders..."), b -> this.minecraft.gui.setScreen(new ShadeScreen(this))).build());
	}
}
