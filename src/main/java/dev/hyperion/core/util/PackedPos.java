package dev.hyperion.core.util;

/**
 * Упаковка координат в long без аллокаций (аналог ChunkPos.toLong / SectionPos.asLong,
 * но с фиксированной разрядностью и без объектов).
 */
public final class PackedPos {

    private PackedPos() {
    }

    /** Регион 32x32 блока (2x2 чанка) — единица партиционирования сущностей. */
    public static final int REGION_SHIFT = 5;

    public static long region(int blockX, int blockZ) {
        return chunk(blockX >> REGION_SHIFT, blockZ >> REGION_SHIFT);
    }

    public static long chunk(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int x(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    public static int z(long packed) {
        return (int) (packed >>> 32);
    }

    /** Ключ секции 16x16x16: X (26 бит) | Z (26 бит) | Y (12 бит). */
    public static long section(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFFFL) | (((long) sz & 0x3FFFFFFL) << 26) | (((long) sy & 0xFFFL) << 52);
    }

    public static int sectionX(long key) {
        return (int) (key << 38 >> 38);
    }

    public static int sectionZ(long key) {
        return (int) (key << 12 >> 38);
    }

    public static int sectionY(long key) {
        return (int) (key >> 52);
    }

    /** Индекс блока внутри секции 16^3. */
    public static int sectionIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }
}
