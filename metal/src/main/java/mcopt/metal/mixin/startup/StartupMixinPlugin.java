package mcopt.metal.mixin.startup;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Each startup mixin applies only when its -Dmcopt.startup.* flag asks for it; with none set the game is unchanged. */
public final class StartupMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		// System properties only: loading mcopt.metal.Startup here would load game classes before their own mixins apply.
		if (mixinClassName.endsWith("NarratorMixin")) return "lazy".equals(System.getProperty("mcopt.startup.narrator"));
		if (mixinClassName.endsWith("CrashPreloadMixin")) return "async".equals(System.getProperty("mcopt.startup.crashPreload"));
		if (mixinClassName.endsWith("BlockCacheMixin") || mixinClassName.endsWith("BootstrapCacheMixin")) return !System.getProperty("mcopt.startup.blockCache", "").isEmpty();
		if (mixinClassName.endsWith("FadeMixin")) return System.getProperty("mcopt.startup.fadeMs") != null;
		if (mixinClassName.endsWith("DevLabelMixin")) return System.getProperty("mcopt.dev.label") != null;
		return false;
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
