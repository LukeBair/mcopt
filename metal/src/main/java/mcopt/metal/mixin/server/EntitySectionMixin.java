package mcopt.metal.mixin.server;

import java.util.ArrayList;
import mcopt.metal.server.CollidableSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dmcopt.server.collideIndex: a second list per entity section holding only entities whose class can be collided with (see EntityGetterMixin). */
@Mixin(EntitySection.class)
abstract class EntitySectionMixin<T extends EntityAccess> implements CollidableSource.Section {
	@Unique private final ArrayList<Entity> mcopt$collidables = new ArrayList<>();

	@Inject(method = "add", at = @At("RETURN"))
	private void mcopt$add(T entity, CallbackInfo ci) {
		if (entity instanceof Entity e && CollidableSource.collidable(e.getClass())) mcopt$collidables.add(e);
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void mcopt$remove(T entity, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && entity instanceof Entity e && CollidableSource.collidable(e.getClass())) mcopt$collidables.remove(e);
	}

	@Override
	public boolean mcopt$collidables(AABB bb, AbortableIterationConsumer<Entity> out) {
		ArrayList<Entity> list = mcopt$collidables;
		for (int i = 0; i < list.size(); i++) {
			Entity entity = list.get(i);
			if (entity.getBoundingBox().intersects(bb) && out.accept(entity).shouldAbort()) return true;
		}
		return false;
	}
}
