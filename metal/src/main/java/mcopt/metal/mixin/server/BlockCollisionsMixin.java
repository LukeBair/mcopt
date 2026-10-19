package mcopt.metal.mixin.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Cursor3D;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.function.BiFunction;
import mcopt.metal.server.ServerOpt;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jspecify.annotations.Nullable;

/**
 * -Dmcopt.server.collide=true|verify: the block-collision iterator behind every entity move, support-block and
 * "is there a block here" query, walking the same positions in the same order (x fastest, then y, then z, as Cursor3D) and returning
 * the same shapes, without the per-position division/modulo of Cursor3D and without reading a block state in a section that only
 * holds air (LevelChunk.getBlockState returns air there; air has no collision shape and is not suffocating, so vanilla rejects it
 * too). Chunks that aren't a plain LevelChunk (and debug worlds) take vanilla's reads.
 * verify: also steps a vanilla Cursor3D alongside and counts any position or type that differs, and checks every skipped
 * position really is air.
 */
@Mixin(BlockCollisions.class)
abstract class BlockCollisionsMixin<T> extends com.google.common.collect.AbstractIterator<T> {
	@Shadow @Final private AABB box;
	@Shadow @Final private CollisionContext context;
	@Shadow @Final private BlockPos.MutableBlockPos pos;
	@Shadow @Final private VoxelShape entityShape;
	@Shadow @Final private CollisionGetter collisionGetter;
	@Shadow @Final private boolean onlySuffocatingBlocks;
	@Shadow @Final private BiFunction<BlockPos.MutableBlockPos, VoxelShape, T> resultProvider;
	@Shadow @Final private Cursor3D cursor;
	@Shadow abstract @Nullable BlockGetter getChunk(int x, int z);

	@Unique private int mcopt$minX, mcopt$minY, mcopt$minZ, mcopt$w, mcopt$h, mcopt$d, mcopt$end, mcopt$index;
	@Unique private int mcopt$x, mcopt$y, mcopt$z; // current position's offsets inside the box
	@Unique private int mcopt$sy = Integer.MIN_VALUE;
	@Unique private LevelChunkSection mcopt$section; // null: air only or out of range
	@Unique private LevelChunk mcopt$lc;

	@Inject(method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V", at = @At("RETURN"), require = 0)
	private void mcopt$init(CollisionGetter g, CollisionContext c, AABB b, boolean o, BiFunction<BlockPos.MutableBlockPos, VoxelShape, T> r, CallbackInfo ci) {
		AABB box = this.box;
		mcopt$minX = Mth.floor(box.minX - 1.0E-7) - 1;
		int x1 = Mth.floor(box.maxX + 1.0E-7) + 1;
		mcopt$minY = Mth.floor(box.minY - 1.0E-7) - 1;
		int y1 = Mth.floor(box.maxY + 1.0E-7) + 1;
		mcopt$minZ = Mth.floor(box.minZ - 1.0E-7) - 1;
		int z1 = Mth.floor(box.maxZ + 1.0E-7) + 1;
		mcopt$w = x1 - mcopt$minX + 1;
		mcopt$h = y1 - mcopt$minY + 1;
		mcopt$d = z1 - mcopt$minZ + 1;
		mcopt$end = mcopt$w * mcopt$h * mcopt$d;
	}

	/**
	 * @author mcopt
	 * @reason same positions, order and shapes as vanilla with less per-position work (opt-in); see class doc.
	 */
	@Overwrite
	protected T computeNext() {
		if (!ServerOpt.active()) return mcopt$vanilla();
		final boolean verify = ServerOpt.COLLIDE_VERIFY;
		final int w = mcopt$w, h = mcopt$h, d = mcopt$d;
		while (mcopt$index < mcopt$end) {
			int cx = mcopt$x, cy = mcopt$y, cz = mcopt$z;
			// advance (x fastest, then y, then z)
			if (++mcopt$x == w) {
				mcopt$x = 0;
				if (++mcopt$y == h) {
					mcopt$y = 0;
					mcopt$z++;
				}
			}
			mcopt$index++;
			int x = mcopt$minX + cx, y = mcopt$minY + cy, z = mcopt$minZ + cz;
			int type = 0;
			if (cx == 0 || cx == w - 1) type++;
			if (cy == 0 || cy == h - 1) type++;
			if (cz == 0 || cz == d - 1) type++;
			if (verify) {
				if (!this.cursor.advance() || this.cursor.nextX() != x || this.cursor.nextY() != y || this.cursor.nextZ() != z || this.cursor.getNextType() != type) {
					ServerOpt.count("collide.mismatch");
				}
			}
			if (type == 3) continue;
			BlockGetter chunk = this.getChunk(x, z);
			if (chunk == null) continue;
			this.pos.set(x, y, z);
			BlockState blockState;
			if (chunk instanceof LevelChunk lc && !lc.getLevel().isDebug()) {
				int sy = y >> 4;
				if (sy != mcopt$sy || mcopt$lc != lc) {
					mcopt$sy = sy;
					mcopt$lc = lc;
					int idx = sy - lc.getMinSectionY();
					LevelChunkSection[] sections = lc.getSections();
					LevelChunkSection s = idx >= 0 && idx < sections.length ? sections[idx] : null;
					mcopt$section = s != null && !s.hasOnlyAir() ? s : null;
				}
				if (mcopt$section == null) {
					if (verify) {
						ServerOpt.count("collide.skipped");
						if (!chunk.getBlockState(this.pos).isAir()) ServerOpt.count("collide.mismatch");
					}
					continue;
				}
				blockState = mcopt$section.getBlockState(x & 15, y & 15, z & 15);
				if (verify && blockState != chunk.getBlockState(this.pos)) ServerOpt.count("collide.mismatch");
			} else {
				blockState = chunk.getBlockState(this.pos);
			}
			if ((!this.onlySuffocatingBlocks || blockState.isSuffocating(chunk, this.pos))
				&& (type != 1 || blockState.hasLargeCollisionShape())
				&& (type != 2 || blockState.is(Blocks.MOVING_PISTON))) {
				VoxelShape blockShape = this.context.getCollisionShape(blockState, this.collisionGetter, this.pos);
				if (blockShape == Shapes.block()) {
					if (this.box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
						return this.resultProvider.apply(this.pos, blockShape.move(this.pos));
					}
				} else {
					VoxelShape shape = blockShape.move(this.pos);
					if (!shape.isEmpty() && Shapes.joinIsNotEmpty(shape, this.entityShape, BooleanOp.AND)) {
						return this.resultProvider.apply(this.pos, shape);
					}
				}
			}
		}
		return this.endOfData();
	}

	@Unique
	private T mcopt$vanilla() {
		while (this.cursor.advance()) {
			int x = this.cursor.nextX();
			int y = this.cursor.nextY();
			int z = this.cursor.nextZ();
			int cursorFaceType = this.cursor.getNextType();
			if (cursorFaceType != 3) {
				BlockGetter chunk = this.getChunk(x, z);
				if (chunk != null) {
					this.pos.set(x, y, z);
					BlockState blockState = chunk.getBlockState(this.pos);
					if ((!this.onlySuffocatingBlocks || blockState.isSuffocating(chunk, this.pos))
						&& (cursorFaceType != 1 || blockState.hasLargeCollisionShape())
						&& (cursorFaceType != 2 || blockState.is(Blocks.MOVING_PISTON))) {
						VoxelShape blockShape = this.context.getCollisionShape(blockState, this.collisionGetter, this.pos);
						if (blockShape == Shapes.block()) {
							if (this.box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
								return this.resultProvider.apply(this.pos, blockShape.move(this.pos));
							}
						} else {
							VoxelShape shape = blockShape.move(this.pos);
							if (!shape.isEmpty() && Shapes.joinIsNotEmpty(shape, this.entityShape, BooleanOp.AND)) {
								return this.resultProvider.apply(this.pos, shape);
							}
						}
					}
				}
			}
		}
		return this.endOfData();
	}
}
