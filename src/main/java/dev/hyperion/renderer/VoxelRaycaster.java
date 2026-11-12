package dev.hyperion.renderer;

import dev.hyperion.core.util.PackedPos;

/**
 * Воксельный DDA-рейкаст (Amanatides &amp; Woo) по снапшотам секций.
 *
 * <p>Работает только с immutable-данными {@link OcclusionSnapshots}, поэтому может
 * исполняться в любом количестве рабочих потоков параллельно с рендером.
 */
public final class VoxelRaycaster {

    public static final int CLEAR = 0;
    public static final int BLOCKED = 1;
    public static final int UNKNOWN = 2;

    private static final int MAX_STEPS = 512;

    private VoxelRaycaster() {
    }

    public static int trace(SectionLookup snapshots,
                            double x0, double y0, double z0,
                            double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;

        int ix = (int) Math.floor(x0);
        int iy = (int) Math.floor(y0);
        int iz = (int) Math.floor(z0);
        int targetX = (int) Math.floor(x1);
        int targetY = (int) Math.floor(y1);
        int targetZ = (int) Math.floor(z1);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY
                : ((stepX > 0 ? (ix + 1 - x0) : (x0 - ix)) * tDeltaX);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY
                : ((stepY > 0 ? (iy + 1 - y0) : (y0 - iy)) * tDeltaY);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : ((stepZ > 0 ? (iz + 1 - z0) : (z0 - iz)) * tDeltaZ);

        boolean unknown = false;
        long cachedKey = Long.MIN_VALUE;
        SectionSnapshot cached = null;

        for (int step = 0; step < MAX_STEPS; step++) {
            if (ix == targetX && iy == targetY && iz == targetZ) {
                return unknown ? UNKNOWN : CLEAR;
            }

            long key = PackedPos.section(ix >> 4, iy >> 4, iz >> 4);
            if (key != cachedKey) {
                cachedKey = key;
                cached = snapshots.get(key);
            }
            if (cached == null) {
                unknown = true;
            } else if (!cached.isAllAir()) {
                int index = PackedPos.sectionIndex(ix & 15, iy & 15, iz & 15);
                if (cached.isOpaque(index)) {
                    return BLOCKED;
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    if (tMaxX > 1.0) return unknown ? UNKNOWN : CLEAR;
                    ix += stepX;
                    tMaxX += tDeltaX;
                } else {
                    if (tMaxZ > 1.0) return unknown ? UNKNOWN : CLEAR;
                    iz += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    if (tMaxY > 1.0) return unknown ? UNKNOWN : CLEAR;
                    iy += stepY;
                    tMaxY += tDeltaY;
                } else {
                    if (tMaxZ > 1.0) return unknown ? UNKNOWN : CLEAR;
                    iz += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }
        return unknown ? UNKNOWN : CLEAR;
    }
}
