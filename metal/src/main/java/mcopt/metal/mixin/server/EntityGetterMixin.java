package mcopt.metal.mixin.server;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import mcopt.metal.server.CollidableSource;
import mcopt.metal.server.ServerOpt;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dmcopt.server.collideIndex=true|verify: getEntityCollisions (every entity move, door/boat/placement check) visits all entities
 * in the boxes' sections, but Entity.canBeCollidedWith is false for every class that doesn't override it, so only entities of
 * classes that do (boats, minecarts, shulkers, ...) can contribute a shape. Those are kept in a second list per entity section
 * (EntitySectionMixin), in the section's order, and only they are visited. Sources whose class overrides canCollideWith (boats,
 * minecarts, wind charges: they also collide with pushable entities) take vanilla's path. Same shapes in the same order.
 * verify: also runs vanilla's query and counts any difference in the entity list.
 */
@Mixin(EntityGetter.class)
public interface EntityGetterMixin {
	@Inject(method = "getEntityCollisions", at = @At("HEAD"), cancellable = true)
	default void mcopt$collisions(@Nullable Entity source, AABB testArea, CallbackInfoReturnable<List<VoxelShape>> cir) {
		if (!((Object) this instanceof Level level) || testArea.getSize() < 1.0E-7) return;
		if (source != null && !CollidableSource.defaultSource(source.getClass())) return;
		if (!(((LevelEntitiesAccessor) level).mcopt$entities() instanceof CollidableSource.Getter getter)) return;
		Predicate<Entity> canCollide = source == null ? EntitySelector.CAN_BE_COLLIDED_WITH : EntitySelector.NO_SPECTATORS.and(source::canCollideWith);
		AABB box = testArea.inflate(1.0E-7);
		Profiler.get().incrementCounter("getEntities");
		List<Entity> colliding = new ArrayList<>();
		getter.mcopt$collidables(box, entity -> {
			if (entity != source && canCollide.test(entity)) colliding.add(entity);
			return net.minecraft.util.Continuation.CONTINUE;
		});
		for (EnderDragonPart dragonPart : level.dragonParts()) {
			if (dragonPart != source && dragonPart.parentMob != source && canCollide.test(dragonPart) && box.intersects(dragonPart.getBoundingBox())) {
				colliding.add(dragonPart);
			}
		}
		if (ServerOpt.COLLIDE_INDEX_VERIFY) {
			List<Entity> vanilla = level.getEntities(source, box, canCollide);
			ServerOpt.count(vanilla.equals(colliding) && identical(vanilla, colliding) ? "collideIndex.same" : "collideIndex.mismatch");
		}
		if (colliding.isEmpty()) {
			cir.setReturnValue(List.of());
			return;
		}
		ImmutableList.Builder<VoxelShape> shapes = ImmutableList.builderWithExpectedSize(colliding.size());
		for (Entity entity : colliding) shapes.add(Shapes.create(entity.getBoundingBox()));
		cir.setReturnValue(shapes.build());
	}

	private static boolean identical(List<Entity> a, List<Entity> b) {
		for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
		return true;
	}
}
