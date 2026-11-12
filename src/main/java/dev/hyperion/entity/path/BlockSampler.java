package dev.hyperion.entity.path;

/**
 * Источник проходимости блоков для асинхронного A*.
 *
 * <p>Реализация обязана быть потокобезопасной и <b>не обращаться к живому миру</b>:
 * в воркер передаётся immutable-снапшот, снятый главным потоком.
 */
@FunctionalInterface
public interface BlockSampler {

    /** true, если в клетке можно стоять/проходить (не сплошной блок). */
    boolean passable(int x, int y, int z);

    /** Снапшот из плоского массива — то, что реально уходит в воркер. */
    static BlockSampler ofGrid(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ, boolean[] passable) {
        return (x, y, z) -> {
            int lx = x - originX;
            int ly = y - originY;
            int lz = z - originZ;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) return false;
            return passable[(ly * sizeZ + lz) * sizeX + lx];
        };
    }
}
