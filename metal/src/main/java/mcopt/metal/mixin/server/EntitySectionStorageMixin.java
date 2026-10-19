package mcopt.metal.mixin.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.util.ArrayList;
import java.util.List;
import mcopt.metal.server.ServerOpt;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dmcopt.server.entityIndex=true|verify: every entity query (collisions, pushing, getEntitiesOfClass, hoppers...) finds the
 * entity sections of its box by walking the AVL tree of all section keys of each x slab and filtering by y and z. A box covers a
 * handful of sections, so they are looked up directly instead, in the order the tree walk visits them: per x, z in unsigned 22-bit
 * order (non-negative first), then y in unsigned 20-bit order. Same sections, same order, same abort handling. Boxes covering
 * more than 4096 sections take vanilla's walk. verify: also collects vanilla's visit order and counts differences.
 */
@Mixin(EntitySectionStorage.class)
abstract class EntitySectionStorageMixin<T extends EntityAccess> {
	@Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;
	@Shadow @Final private LongSortedSet sectionIds;

	@Inject(method = "forEachAccessibleNonEmptySection", at = @At("HEAD"), cancellable = true)
	private void mcopt$direct(AABB bb, AbortableIterationConsumer<EntitySection<T>> output, CallbackInfo ci) {
		int xMin = SectionPos.posToSectionCoord(bb.minX - 2.0);
		int yMin = SectionPos.posToSectionCoord(bb.minY - 4.0);
		int zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0);
		int xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0);
		int yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0);
		int zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0);
		long volume = (long) (xMax - xMin + 1) * (yMax - yMin + 1) * (zMax - zMin + 1);
		if (volume <= 0 || volume > 4096 || Math.abs(xMin) > 2_000_000 || Math.abs(xMax) > 2_000_000
			|| Math.abs(yMin) > 500_000 || Math.abs(yMax) > 500_000 || Math.abs(zMin) > 2_000_000 || Math.abs(zMax) > 2_000_000) {
			return; // vanilla
		}
		if (ServerOpt.ENTITY_INDEX_VERIFY) mcopt$verify(xMin, xMax, yMin, yMax, zMin, zMax);
		int zSplit = Math.max(zMin, 0), ySplit = Math.max(yMin, 0);
		for (int x = xMin; x <= xMax; x++) {
			// z: non-negative ascending, then negative ascending
			for (int zi = 0; zi <= zMax - zMin; zi++) {
				int z = zSplit <= zMax ? (zi <= zMax - zSplit ? zSplit + zi : zMin + (zi - (zMax - zSplit + 1))) : zMin + zi;
				for (int yi = 0; yi <= yMax - yMin; yi++) {
					int y = ySplit <= yMax ? (yi <= yMax - ySplit ? ySplit + yi : yMin + (yi - (yMax - ySplit + 1))) : yMin + yi;
					EntitySection<T> section = this.sections.get(SectionPos.asLong(x, y, z));
					if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && output.accept(section).shouldAbort()) {
						ci.cancel();
						return;
					}
				}
			}
		}
		ci.cancel();
	}

	private void mcopt$verify(int xMin, int xMax, int yMin, int yMax, int zMin, int zMax) {
		List<Long> vanilla = new ArrayList<>(), fast = new ArrayList<>();
		for (int x = xMin; x <= xMax; x++) {
			LongIterator it = this.sectionIds.subSet(SectionPos.asLong(x, 0, 0), SectionPos.asLong(x, -1, -1) + 1L).iterator();
			while (it.hasNext()) {
				long key = it.nextLong();
				int y = SectionPos.y(key), z = SectionPos.z(key);
				if (y >= yMin && y <= yMax && z >= zMin && z <= zMax && this.sections.get(key) != null) vanilla.add(key);
			}
		}
		int zSplit = Math.max(zMin, 0), ySplit = Math.max(yMin, 0);
		for (int x = xMin; x <= xMax; x++) {
			for (int zi = 0; zi <= zMax - zMin; zi++) {
				int z = zSplit <= zMax ? (zi <= zMax - zSplit ? zSplit + zi : zMin + (zi - (zMax - zSplit + 1))) : zMin + zi;
				for (int yi = 0; yi <= yMax - yMin; yi++) {
					int y = ySplit <= yMax ? (yi <= yMax - ySplit ? ySplit + yi : yMin + (yi - (yMax - ySplit + 1))) : yMin + yi;
					long key = SectionPos.asLong(x, y, z);
					if (this.sections.get(key) != null) fast.add(key);
				}
			}
		}
		ServerOpt.count(vanilla.equals(fast) ? "entityIndex.same" : "entityIndex.mismatch");
	}
}
