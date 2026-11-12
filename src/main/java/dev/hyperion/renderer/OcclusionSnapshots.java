package dev.hyperion.renderer;

import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.util.MpscQueue;
import dev.hyperion.core.util.PackedPos;
import dev.hyperion.memory.BlockStateCache;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище снапшотов секций для асинхронного occlusion-culling.
 *
 * <p>Разделение обязанностей:
 * <ul>
 *   <li>воркеры, наткнувшись на неизвестную секцию, кладут её ключ в lock-free очередь
 *       запросов и возвращают UNKNOWN (сущность рисуется — fail-open);</li>
 *   <li>главный поток раз в тик строит не больше {@code budget} снапшотов;</li>
 *   <li>устаревшие снапшоты удаляются по TTL, а также мгновенно при изменении блока.</li>
 * </ul>
 */
public final class OcclusionSnapshots implements SectionLookup {

    private static final OcclusionSnapshots INSTANCE = new OcclusionSnapshots();

    private final Map<Long, SectionSnapshot> snapshots = new ConcurrentHashMap<>();
    private final MpscQueue<Long> requested = new MpscQueue<>(8192);
    private final Set<Long> requestedSet = ConcurrentHashMap.newKeySet();
    private int evictionCounter;

    private OcclusionSnapshots() {
    }

    public static OcclusionSnapshots get() {
        return INSTANCE;
    }

    /** Только чтение — вызывается из воркеров. */
    @Override
    public SectionSnapshot get(long key) {
        SectionSnapshot snapshot = snapshots.get(key);
        if (snapshot == null) {
            request(key);
            return null;
        }
        if (System.currentTimeMillis() - snapshot.created() > HyperionConfig.get().occlusionSnapshotTtlMs) {
            request(key);
            return snapshot; // устаревший лучше, чем ничего: результат всё равно fail-open
        }
        return snapshot;
    }

    private void request(long key) {
        if (requestedSet.add(key)) {
            if (!requested.offer(key)) {
                requestedSet.remove(key);
            }
        }
    }

    /** Главный поток, раз в клиентский тик. */
    public int pump(ClientWorld world, int budget) {
        int built = 0;
        while (built < budget) {
            Long key = requested.poll();
            if (key == null) break;
            requestedSet.remove(key);
            SectionSnapshot existing = snapshots.get(key);
            long now = System.currentTimeMillis();
            if (existing != null && now - existing.created() <= HyperionConfig.get().occlusionSnapshotTtlMs) {
                continue;
            }
            SectionSnapshot snapshot = build(world, key, now);
            if (snapshot != null) {
                snapshots.put(key, snapshot);
                built++;
            }
        }
        if (++evictionCounter >= 20) {
            evictionCounter = 0;
            evictExpired();
        }
        HyperionMetrics.CULL_SNAPSHOTS_BUILT.add(built);
        return built;
    }

    private SectionSnapshot build(ClientWorld world, long key, long now) {
        try {
            int sx = PackedPos.sectionX(key);
            int sy = PackedPos.sectionY(key);
            int sz = PackedPos.sectionZ(key);

            Chunk chunk = world.getChunk(sx, sz, ChunkStatus.FULL, false);
            if (!(chunk instanceof WorldChunk worldChunk)) return null;

            int sectionIndex = sy - (world.getBottomY() >> 4);
            ChunkSection[] sections = worldChunk.getSectionArray();
            if (sectionIndex < 0 || sectionIndex >= sections.length) return null;
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                return new SectionSnapshot(new long[64], now, true);
            }

            long[] bits = new long[64];
            boolean any = false;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (BlockStateCache.isOpaqueFullCube(state)) {
                            SectionSnapshot.set(bits, PackedPos.sectionIndex(x, y, z));
                            any = true;
                        }
                    }
                }
            }
            return new SectionSnapshot(bits, now, !any);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Мгновенная инвалидация при изменении блока (миксин ClientWorld#setBlockState). */
    public void invalidateBlock(BlockPos pos) {
        long key = PackedPos.section(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        snapshots.remove(key);
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        long ttl = HyperionConfig.get().occlusionSnapshotTtlMs * 4L;
        Iterator<Map.Entry<Long, SectionSnapshot>> it = snapshots.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().created() > ttl) {
                it.remove();
            }
        }
    }

    public int size() {
        return snapshots.size();
    }

    public int pending() {
        return requested.size();
    }

    public void clear() {
        snapshots.clear();
        requested.clear();
        requestedSet.clear();
    }
}
