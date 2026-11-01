package mcopt.metal.shade;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's "configure" button opens the shader settings (loaded only when Mod Menu is installed). */
public final class ShadeModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ShadeScreen::new;
	}
}
