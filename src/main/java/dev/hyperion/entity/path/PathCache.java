package dev.hyperion.entity.path;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free кэш результатов поиска пути с TTL и мягким лимитом размера.
 *
 * <p>Ключ — квантованные координаты старта/цели (по умолчанию квант 1 блок),
 * значение — immutable-результат. Чтение — один lookup в ConcurrentHashMap,
 * без блокировок и без аллокаций, кроме ключа.
 */
public final class PathCache {

    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long ttlMillis;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public PathCache(int maxEntries, long ttlMillis) {
        this.maxEntries = Math.max(16, maxEntries);
        this.ttlMillis = Math.max(50, ttlMillis);
    }

    public AsyncPathfinder.Result get(int sx, int sy, int sz, int tx, int ty, int tz) {
        Key key = new Key(sx, sy, sz, tx, ty, tz);
        Entry entry = cache.get(key);
        if (entry == null || System.currentTimeMillis() - entry.created > ttlMillis) {
            if (entry != null) cache.remove(key);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.result;
    }

    /** Выбросить запись: путь перестал проходить проверку по живому миру. */
    public void remove(int sx, int sy, int sz, int tx, int ty, int tz) {
        cache.remove(new Key(sx, sy, sz, tx, ty, tz));
    }

    public void put(int sx, int sy, int sz, int tx, int ty, int tz, AsyncPathfinder.Result result) {
        if (cache.size() >= maxEntries) {
            evictExpiredOrSome();
        }
        cache.put(new Key(sx, sy, sz, tx, ty, tz), new Entry(result, System.currentTimeMillis()));
    }

    private void evictExpiredOrSome() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().created > ttlMillis);
        if (cache.size() >= maxEntries) {
            // Грубая, но дешёвая политика: сбрасываем половину записей.
            int toRemove = cache.size() / 2;
            var it = cache.keySet().iterator();
            while (it.hasNext() && toRemove-- > 0) {
                it.next();
                it.remove();
            }
        }
    }

    public int size() {
        return cache.size();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public double hitRate() {
        long h = hits.get();
        long m = misses.get();
        return h + m == 0 ? 0 : 100.0 * h / (h + m);
    }

    public void clear() {
        cache.clear();
    }

    private record Key(int sx, int sy, int sz, int tx, int ty, int tz) {
    }

    private record Entry(AsyncPathfinder.Result result, long created) {
    }
}
