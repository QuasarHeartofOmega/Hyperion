package dev.hyperion.entity.path;

/**
 * Проверка готового пути перед тем, как отдать его ванильной навигации.
 *
 * <p>Зачем отдельный шаг: A* считал по снапшоту, а снапшот к моменту применения
 * мог устареть (моб уже сдвинулся, блок сломали/поставили, чанк перезагрузился).
 * Поэтому каждый узел перепроверяется по <b>живому</b> миру, а сама цепочка —
 * на непрерывность. Не прошло проверку — возвращаем null и ваниль считает сама.
 *
 * <p>Класс намеренно не зависит от Minecraft: проходимость приходит через
 * {@link BlockSampler}, поэтому проверка покрыта автономными тестами.
 */
public final class PathValidator {

    private PathValidator() {
    }

    /**
     * Каждый шаг — ровно один блок по одной оси. Это то, что делает путь безопасным:
     * 6-связный A* по «клеткам, где можно стоять» даёт только шаг вверх на 1
     * (моб подпрыгивает) и шаг вниз на 1 (моб спадает), без диагоналей и без
     * «телепортов» через провалы.
     */
    public static boolean contiguous(long[] path) {
        if (path == null || path.length == 0) return false;
        for (int i = 1; i < path.length; i++) {
            int dx = Math.abs(AsyncPathfinder.absoluteX(path[i]) - AsyncPathfinder.absoluteX(path[i - 1]));
            int dy = Math.abs(AsyncPathfinder.absoluteY(path[i]) - AsyncPathfinder.absoluteY(path[i - 1]));
            int dz = Math.abs(AsyncPathfinder.absoluteZ(path[i]) - AsyncPathfinder.absoluteZ(path[i - 1]));
            if (dx + dy + dz != 1) return false;
        }
        return true;
    }

    /** Полная проверка: концы совпадают, шаги непрерывны, все клетки проходимы сейчас. */
    public static boolean validate(long[] path, BlockSampler standable,
                                   int sx, int sy, int sz, int tx, int ty, int tz, int maxNodes) {
        if (path == null || path.length == 0 || path.length > maxNodes) return false;
        if (!isCell(path[0], sx, sy, sz)) return false;
        if (!isCell(path[path.length - 1], tx, ty, tz)) return false;
        if (!contiguous(path)) return false;
        for (long cell : path) {
            if (!standable.passable(AsyncPathfinder.absoluteX(cell),
                    AsyncPathfinder.absoluteY(cell),
                    AsyncPathfinder.absoluteZ(cell))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCell(long packed, int x, int y, int z) {
        return AsyncPathfinder.absoluteX(packed) == x
                && AsyncPathfinder.absoluteY(packed) == y
                && AsyncPathfinder.absoluteZ(packed) == z;
    }
}
