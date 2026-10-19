package mcopt.metal.mixin.shade;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.Map;
import mcopt.metal.shade.Shade;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Without Fabric API nothing registers this jar's assets/ as a resource pack, so vanilla's shader loader would never see
 * mcopt's shader sources; hand them to its listing (only with -Dmcopt.shade=true).
 */
@Mixin(ShaderManager.class)
abstract class ShadeShaderManagerMixin {
	@ModifyExpressionValue(method = "loadConfigs", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/packs/resources/ResourceManager;listResources(Ljava/lang/String;Lnet/minecraft/server/packs/resources/ResourceManager$Selector;)Ljava/util/Map;"))
	private static Map<Identifier, Resource> mcopt$shadeSources(Map<Identifier, Resource> listed) {
		return Shade.ON ? Shade.addSources(listed) : listed;
	}
}
