package mcopt.metal.server;

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** The entity-collision index (-Dmcopt.server.collideIndex): duck interface of EntitySection and LevelEntityGetterAdapter. */
public final class CollidableSource {
	private CollidableSource() { }

	public interface Section {
		/** The entities of this section whose class can be collided with, in the section's order, whose box intersects bb; true if out aborted. */
		boolean mcopt$collidables(AABB bb, AbortableIterationConsumer<Entity> out);
	}

	public interface Getter {
		void mcopt$collidables(AABB bb, AbortableIterationConsumer<Entity> out);
	}

	private static final ClassValue<Boolean> COLLIDABLE = new ClassValue<>() {
		@Override protected Boolean computeValue(Class<?> type) {
			return declaredIn(type, "canBeCollidedWith") != Entity.class;
		}
	};
	private static final ClassValue<Boolean> DEFAULT_SOURCE = new ClassValue<>() {
		@Override protected Boolean computeValue(Class<?> type) {
			return declaredIn(type, "canCollideWith") == Entity.class;
		}
	};

	private static Class<?> declaredIn(Class<?> type, String method) {
		try {
			return type.getMethod(method, Entity.class).getDeclaringClass();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null; // unknown: treat as overridden (collidable, non-default source)
		}
	}

	/** Whether instances of this class can ever return true from canBeCollidedWith (Entity's version is always false). */
	public static boolean collidable(Class<?> entityClass) {
		return COLLIDABLE.get(entityClass);
	}

	/** Whether a source of this class uses Entity.canCollideWith (candidate.canBeCollidedWith(source) && not the same vehicle). */
	public static boolean defaultSource(Class<?> sourceClass) {
		return DEFAULT_SOURCE.get(sourceClass);
	}
}
