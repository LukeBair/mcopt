package mcopt.metal.mixin.server;

import java.util.List;
import java.util.Set;
import mcopt.metal.server.ServerOpt;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Each server mixin applies only when its -Dmcopt.server.* switch is set (ServerOpt.mixinEnabled); by default nothing changes. */
public final class ServerMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return ServerOpt.mixinEnabled(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
