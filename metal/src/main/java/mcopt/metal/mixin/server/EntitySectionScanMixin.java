package mcopt.metal.mixin.server;

import java.util.ConcurrentModificationException;
import java.util.List;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dmcopt.server.entityIndex: EntitySection.getEntities(bb, consumer), the scan every entity query makes over a section, walks the
 * entity list by index instead of through Guava's unmodifiable iterator wrapper (an interface call chain per entity). Same entities
 * in the same order; a change to the list during the scan still throws ConcurrentModificationException, as the ArrayList iterator did.
 */
@Mixin(EntitySection.class)
abstract class EntitySectionScanMixin<T extends EntityAccess> {
	@Shadow @Final private ClassInstanceMultiMap<T> storage;

	@SuppressWarnings("unchecked")
	@Inject(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/Continuation;", at = @At("HEAD"), cancellable = true)
	private void mcopt$scan(AABB bb, AbortableIterationConsumer<T> output, CallbackInfoReturnable<Continuation> cir) {
		if (!mcopt.metal.server.ServerOpt.active()) return;
		List<T> all = (List<T>) ((ClassInstanceMultiMapAccessor) this.storage).mcopt$allInstances();
		int n = all.size();
		for (int i = 0; i < n; i++) {
			T entity = all.get(i);
			if (entity.getBoundingBox().intersects(bb)) {
				if (output.accept(entity).shouldAbort()) {
					cir.setReturnValue(Continuation.ABORT);
					return;
				}
				if (all.size() != n) throw new ConcurrentModificationException();
			}
		}
		cir.setReturnValue(Continuation.CONTINUE);
	}
}
