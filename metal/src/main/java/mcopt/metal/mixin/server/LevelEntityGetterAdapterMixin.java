package mcopt.metal.mixin.server;

import mcopt.metal.server.CollidableSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelEntityGetterAdapter.class)
abstract class LevelEntityGetterAdapterMixin<T extends EntityAccess> implements CollidableSource.Getter {
	@Shadow @Final private EntitySectionStorage<T> sectionStorage;

	@Override
	public void mcopt$collidables(AABB bb, AbortableIterationConsumer<Entity> out) {
		this.sectionStorage.forEachAccessibleNonEmptySection(bb, section -> {
			return ((CollidableSource.Section) section).mcopt$collidables(bb, out)
				? net.minecraft.util.Continuation.ABORT : net.minecraft.util.Continuation.CONTINUE;
		});
	}
}
