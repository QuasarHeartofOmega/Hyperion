package dev.hyperion.entity.path;

/**
 * Immutable-снапшот проходимости области мира — то, что уходит в воркер.
 *
 * <p>Снимается главным потоком (только он имеет право читать мир), после чего
 * не меняется: воркер видит обычный {@code boolean[]} и не трогает ни одного
 * объекта Minecraft. Это то же разделение, что в occlusion-culling
 * ({@code OcclusionSnapshots}) и в анализе сущностей ({@code HyperionEntitySystem}).
 *
 * <p>Семантика клетки — «здесь можно <b>стоять</b>»: под ногами сплошной куб,
 * над ногами свободно на всю высоту моба. Именно поэтому 6-связный A* по такому
 * снапшоту не может провести моба через провал или в стену.
 */
public final class PathSnapshot {

    private final int originX;
    private final int originY;
    private final int originZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final boolean[] standable;

    public PathSnapshot(int originX, int originY, int originZ,
                        int sizeX, int sizeY, int sizeZ, boolean[] standable) {
        if (standable.length != sizeX * sizeY * sizeZ) {
            throw new IllegalArgumentException("grid size mismatch: " + standable.length
                    + " != " + sizeX + "*" + sizeY + "*" + sizeZ);
        }
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.standable = standable;
    }

    /** Вид для A*: вне снапшота всегда false — путь не может уйти в неизвестность. */
    public BlockSampler sampler() {
        return BlockSampler.ofGrid(originX, originY, originZ, sizeX, sizeY, sizeZ, standable);
    }

    public boolean contains(int x, int y, int z) {
        return x >= originX && x < originX + sizeX
                && y >= originY && y < originY + sizeY
                && z >= originZ && z < originZ + sizeZ;
    }

    public int cells() {
        return standable.length;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }
}
