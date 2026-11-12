package dev.hyperion.chunk;

import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionExecutors;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.util.MpscQueue;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionFile;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Асинхронный read-ahead чанков.
 *
 * <p>Что реально происходит:
 * <ol>
 *   <li>Главный (или ванильный worker) поток просит NBT чанка у {@link RegionFile}.
 *       Миксин спрашивает у нас кэш. Попадание — отдаём уже распакованные байты из памяти,
 *       диск и inflate не нужны.</li>
 *   <li>Промах — ваниль читает сама, а мы ставим в I/O-пул задачи на соседние чанки
 *       (read-ahead радиуса R).</li>
 *   <li>Воркер вызывает <em>тот же самый</em> {@code synchronized} метод RegionFile,
 *       то есть корректно синхронизируется с записью, читает и распаковывает поток
 *       в памяти и кладёт в кэш.</li>
 * </ol>
 *
 * <p>Почему это безопасно: мы не трогаем внутренности RegionFile и не меняем формат
 * сохранения; все чтения идут через штатный синхронизированный метод. Кэш инвалидируется
 * при записи чанка и при закрытии файла, плюс TTL.
 *
 * <p>Чего здесь нет (и не может быть в моде): «полностью асинхронной системы чанков»
 * с параллельным тиком блоков — состояние мира принадлежит главному потоку.
 */
public final class HyperionChunkSystem {

    private static final HyperionChunkSystem INSTANCE = new HyperionChunkSystem();
    private static final int MAX_CHUNK_BYTES = 16 * 1024 * 1024;

    /** Ставится воркером, чтобы миксин не запускал рекурсивный prefetch/кэш-логику. */
    private static final ThreadLocal<Boolean> INTERNAL_READ = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Key> fifo = new ConcurrentLinkedQueue<>();
    private final Set<Key> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong cachedBytes = new AtomicLong();
    /** Очередь запросов на построение снапшотов/лог из воркеров. */
    private final MpscQueue<String> workerLog = new MpscQueue<>(128);

    private HyperionChunkSystem() {
    }

    public static HyperionChunkSystem get() {
        return INSTANCE;
    }

    public static boolean isInternalRead() {
        return INTERNAL_READ.get();
    }

    // ------------------------------------------------------------------ API для миксина

    /** Вызывается на читающем потоке. Возвращает распакованный NBT или null (промах). */
    public byte[] lookup(String fileKey, int chunkX, int chunkZ) {
        Key key = new Key(fileKey, chunkX, chunkZ);
        Entry entry = cache.get(key);
        if (entry == null) {
            HyperionMetrics.CHUNK_CACHE_MISSES.increment();
            return null;
        }
        if (System.currentTimeMillis() - entry.created > HyperionConfig.get().chunkCacheTtlMs) {
            remove(key);
            HyperionMetrics.CHUNK_CACHE_MISSES.increment();
            return null;
        }
        HyperionMetrics.CHUNK_CACHE_HITS.increment();
        HyperionMetrics.CHUNK_BYTES_SAVED.add(entry.data.length);
        return entry.data;
    }

    /** Планирует read-ahead соседних чанков того же region-файла. */
    public void prefetchNeighbours(RegionFile file, String fileKey, ChunkPos center) {
        HyperionConfig cfg = HyperionConfig.get();
        if (!cfg.chunkPrefetch || !HyperionExecutors.isRunning()) return;

        int radius = cfg.chunkPrefetchRadius;
        if (radius <= 0) return;

        int baseX = center.x & ~31;
        int baseZ = center.z & ~31;
        int limit = cfg.chunkPrefetchInFlight;

        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = center.x + dx;
                    int cz = center.z + dz;
                    // Только внутри этого region-файла: у соседа другой RegionFile и другой замок.
                    if ((cx & ~31) != baseX || (cz & ~31) != baseZ) continue;
                    if (inFlight.size() >= limit) return;

                    Key key = new Key(fileKey, cx, cz);
                    if (cache.containsKey(key) || !inFlight.add(key)) continue;

                    HyperionMetrics.CHUNK_PREFETCH_QUEUED.increment();
                    final int fx = cx;
                    final int fz = cz;
                    try {
                        HyperionExecutors.io().execute(() -> readIntoCache(file, fileKey, fx, fz, key));
                    } catch (Throwable t) {
                        inFlight.remove(key);
                        return; // пул остановлен
                    }
                }
            }
        }
    }

    public void invalidate(String fileKey, int chunkX, int chunkZ) {
        remove(new Key(fileKey, chunkX, chunkZ));
    }

    public void invalidateFile(String fileKey) {
        Iterator<Key> it = cache.keySet().iterator();
        while (it.hasNext()) {
            Key key = it.next();
            if (key.file.equals(fileKey)) {
                Entry removed = cache.remove(key);
                if (removed != null) cachedBytes.addAndGet(-removed.data.length);
            }
        }
    }

    // ------------------------------------------------------------------ воркер

    private void readIntoCache(RegionFile file, String fileKey, int chunkX, int chunkZ, Key key) {
        INTERNAL_READ.set(Boolean.TRUE);
        try {
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            byte[] data;
            try (DataInputStream in = file.getChunkInputStream(pos)) {
                if (in == null) {
                    return; // чанка ещё нет на диске — это нормально
                }
                data = readLimited(in);
            }
            if (data != null && data.length > 0) {
                put(key, data);
                HyperionMetrics.CHUNK_PREFETCH_DONE.increment();
            }
        } catch (Throwable t) {
            HyperionMetrics.CHUNK_PREFETCH_FAILED.increment();
            workerLog.offer("prefetch " + fileKey + " [" + chunkX + "," + chunkZ + "]: " + t);
        } finally {
            INTERNAL_READ.set(Boolean.FALSE);
            inFlight.remove(key);
        }
    }

    private static byte[] readLimited(DataInputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_CHUNK_BYTES) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void put(Key key, byte[] data) {
        HyperionConfig cfg = HyperionConfig.get();
        Entry previous = cache.put(key, new Entry(data, System.currentTimeMillis()));
        if (previous != null) {
            cachedBytes.addAndGet(-previous.data.length);
        } else {
            fifo.offer(key);
        }
        cachedBytes.addAndGet(data.length);
        evict(cfg.chunkCacheMaxEntries, cfg.chunkCacheSizeMb * 1024L * 1024L);
    }

    private void evict(int maxEntries, long maxBytes) {
        while (cache.size() > maxEntries || cachedBytes.get() > maxBytes) {
            Key oldest = fifo.poll();
            if (oldest == null) break;
            remove(oldest);
        }
    }

    private void remove(Key key) {
        Entry removed = cache.remove(key);
        if (removed != null) {
            cachedBytes.addAndGet(-removed.data.length);
        }
    }

    // ------------------------------------------------------------------ сервис

    public void shutdown() {
        cache.clear();
        fifo.clear();
        inFlight.clear();
        cachedBytes.set(0);
        String msg;
        while ((msg = workerLog.poll()) != null) {
            Hyperion.LOGGER.debug("[Hyperion][chunk] {}", msg);
        }
    }

    public int cachedChunks() {
        return cache.size();
    }

    public long cachedMegabytes() {
        return cachedBytes.get() / (1024 * 1024);
    }

    public int inFlight() {
        return inFlight.size();
    }

    public int drainWorkerLog() {
        int n = 0;
        String msg;
        while ((msg = workerLog.poll()) != null && n < 8) {
            Hyperion.LOGGER.debug("[Hyperion][chunk] {}", msg);
            n++;
        }
        return n;
    }

    private record Key(String file, int x, int z) {
    }

    private record Entry(byte[] data, long created) {
    }
}
