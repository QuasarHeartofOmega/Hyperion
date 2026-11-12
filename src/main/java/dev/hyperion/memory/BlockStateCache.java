package dev.hyperion.memory;

import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionMetrics;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.collection.IdList;

/**
 * Плоские предрасчитанные свойства blockstate.
 *
 * <p>Идея из спеки, но с исправлениями:
 * <ul>
 *   <li>размер массивов берётся из {@code Block.STATE_IDS.size()}, а не хардкод 65536
 *       (в 1.21.4.states больше 26k, а с модами может быть сколько угодно);</li>
 *   <li>никаких {@code state.getCollisionShape(null, null)} — в 1.21.4 это NPE;
 *       используются no-arg методы {@code isOpaqueFullCube()/isOpaque()/blocksMovement()},
 *       которые не требуют мира;</li>
 *   <li>{@code getMaterial()} удалён из игры в 1.20+, поэтому прозрачность берётся
 *       из {@code isOpaque()};</li>
 *   <li>инициализация ленивая и потокобезопасная, а не в статическом блоке
 *       (в static-инициализаторе реестры ещё не заполнены).</li>
 * </ul>
 *
 * <p>Чтение — один массивный доступ по int-индексу, без аллокаций и без хеш-лукапа.
 */
public final class BlockStateCache {

    private static volatile boolean ready;
    private static boolean[] opaqueFullCube = new boolean[0];
    private static boolean[] opaque = new boolean[0];
    private static boolean[] blocksMovement = new boolean[0];
    private static boolean[] air = new boolean[0];
    private static int[] luminance = new int[0];
    private static float[] hardness = new float[0];
    private static int size;

    private BlockStateCache() {
    }

    public static void requestInit() {
        if (!ready) init();
    }

    public static synchronized void init() {
        if (ready) return;
        if (!HyperionConfig.get().blockStateCache) {
            ready = true;
            return;
        }
        long start = System.nanoTime();
        try {
            IdList<BlockState> ids = Block.STATE_IDS;
            int n = ids.size();
            boolean[] oc = new boolean[n];
            boolean[] op = new boolean[n];
            boolean[] bm = new boolean[n];
            boolean[] ar = new boolean[n];
            int[] lum = new int[n];
            float[] hard = new float[n];
            int filled = 0;
            for (int i = 0; i < n; i++) {
                BlockState state = ids.get(i);
                if (state == null) continue;
                oc[i] = state.isOpaqueFullCube();
                op[i] = state.isOpaque();
                bm[i] = state.blocksMovement();
                ar[i] = state.isAir();
                lum[i] = state.getLuminance();
                hard[i] = state.getBlock().getHardness();
                filled++;
            }
            opaqueFullCube = oc;
            opaque = op;
            blocksMovement = bm;
            air = ar;
            luminance = lum;
            hardness = hard;
            size = n;
            ready = true;

            HyperionMetrics.BLOCKSTATE_CACHE_ENTRIES = filled;
            HyperionMetrics.BLOCKSTATE_CACHE_INIT_NANOS = System.nanoTime() - start;
            Hyperion.LOGGER.info("[Hyperion] BlockStateCache: {} состояний предрасчитано за {} мс (~{} КБ)",
                    filled, String.format("%.1f", (System.nanoTime() - start) / 1e6), estimateKb());
        } catch (Throwable t) {
            // Реестры могли быть ещё не готовы — попробуем при первом обращении.
            Hyperion.LOGGER.warn("[Hyperion] BlockStateCache отложен: {}", t.toString());
        }
    }

    private static int estimateKb() {
        return (size * (4 + 4 + 4) /* boolean[] ~1 байт, int 4, float 4 */) / 1024;
    }

    public static boolean isReady() {
        return ready && size > 0;
    }

    /** Главный горячий метод: непрозрачный полный куб (перекрывает обзор). */
    public static boolean isOpaqueFullCube(BlockState state) {
        if (!ready) init();
        int id = Block.getRawIdFromState(state);
        return id >= 0 && id < size ? opaqueFullCube[id] : state.isOpaqueFullCube();
    }

    public static boolean isOpaqueFullCube(int rawId) {
        return rawId >= 0 && rawId < size && opaqueFullCube[rawId];
    }

    public static boolean isOpaque(BlockState state) {
        if (!ready) init();
        int id = Block.getRawIdFromState(state);
        return id >= 0 && id < size ? opaque[id] : state.isOpaque();
    }

    public static boolean blocksMovement(BlockState state) {
        if (!ready) init();
        int id = Block.getRawIdFromState(state);
        return id >= 0 && id < size ? blocksMovement[id] : state.blocksMovement();
    }

    public static boolean isAir(BlockState state) {
        if (!ready) init();
        int id = Block.getRawIdFromState(state);
        return id >= 0 && id < size ? air[id] : state.isAir();
    }

    public static int luminance(BlockState state) {
        if (!ready) init();
        int id = Block.getRawIdFromState(state);
        return id >= 0 && id < size ? luminance[id] : state.getLuminance();
    }

    public static float hardness(BlockState state) {
        if (!ready) init();
        int id = Block.getRawIdFromState(state);
        return id >= 0 && id < size ? hardness[id] : state.getBlock().getHardness();
    }

    public static int entries() {
        return HyperionMetrics.BLOCKSTATE_CACHE_ENTRIES;
    }

    public static void logStats() {
        if (entries() > 0) {
            Hyperion.LOGGER.info("[Hyperion] BlockStateCache: {} записей, инициализация {} мс",
                    entries(), String.format("%.1f", HyperionMetrics.BLOCKSTATE_CACHE_INIT_NANOS / 1e6));
        }
    }
}
