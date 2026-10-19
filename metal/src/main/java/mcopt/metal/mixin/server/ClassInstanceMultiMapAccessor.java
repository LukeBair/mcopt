package mcopt.metal.mixin.server;

import java.util.List;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClassInstanceMultiMap.class)
public interface ClassInstanceMultiMapAccessor {
	@Accessor("allInstances")
	List<?> mcopt$allInstances();
}
