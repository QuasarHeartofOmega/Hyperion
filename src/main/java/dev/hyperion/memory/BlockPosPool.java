package dev.hyperion.memory;

import dev.hyperion.core.HyperionConfig;
import net.minecraft.util.math.BlockPos;

/**
 * Пул курсоров BlockPos.
 *
 * <p>В спеке был один {@code ThreadLocal<MutableBlockPos>} на поток — это ломается
 * на вложенных циклах: внутренний цикл перезаписывает позицию внешнего.
 * Здесь thread-local <em>кольцо</em> из N курсоров: вложенные вызовы получают разные
 * объекты, аллокаций в рантайме нет.
 *
 * <p>Курсор нельзя сохранять дольше текущего выражения — это временный объект.
 */
public final class BlockPosPool {

    private static final class Ring {
        final BlockPos.Mutable[] cursors;
        int index;

        Ring(int size) {
            cursors = new BlockPos.Mutable[size];
            for (int i = 0; i < size; i++) {
                cursors[i] = new BlockPos.Mutable();
            }
        }
    }

    private static final ThreadLocal<Ring> POOL = ThreadLocal.withInitial(() -> {
        int size = HyperionConfig.get().blockPosPoolSize;
        return new Ring(size);
    });

    private BlockPosPool() {
    }

    public static BlockPos.Mutable acquire() {
        Ring ring = POOL.get();
        BlockPos.Mutable pos = ring.cursors[ring.index];
        ring.index = (ring.index + 1) % ring.cursors.length;
        return pos;
    }

    public static BlockPos.Mutable at(int x, int y, int z) {
        return acquire().set(x, y, z);
    }

    /** Явно освобождает все курсоры потока (например, при завершении воркера). */
    public static void release() {
        POOL.remove();
    }

    public static int ringSize() {
        return POOL.get().cursors.length;
    }
}
