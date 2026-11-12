package dev.hyperion.renderer;

/**
 * Immutable-снапшот непрозрачности секции 16x16x16 — 4096 бит = 512 байт.
 *
 * <p>Строится <b>глажным потоком</b> из ванильного {@code ChunkSection} и после публикации
 * только читается воркерами. Это ключевой момент безопасности: рабочие потоки никогда
 * не обращаются к живому миру, они работают с копией битов.
 */
public final class SectionSnapshot {

    public static final SectionSnapshot EMPTY = new SectionSnapshot(new long[64], System.currentTimeMillis(), true);

    private final long[] bits;
    private final long created;
    private final boolean allAir;

    public SectionSnapshot(long[] bits, long created, boolean allAir) {
        this.bits = bits;
        this.created = created;
        this.allAir = allAir;
    }

    public boolean isOpaque(int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public boolean isAllAir() {
        return allAir;
    }

    public long created() {
        return created;
    }

    public static void set(long[] bits, int index) {
        bits[index >>> 6] |= 1L << (index & 63);
    }
}
