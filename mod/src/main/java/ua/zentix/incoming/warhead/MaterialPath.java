package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Geometric path inside a block shape, before applying its represented material thickness. */
public final class MaterialPath {
    private MaterialPath() {}

    /**
     * Path axis length from {@code at}, length {@code len} along {@code dir}, inside block {@code b}'s {@code
     * shape}, m.
     */
    public static double inside(VoxelShape shape, BlockPos b, Vec3 at, Vec3 dir, double len) {
        if (shape.isEmpty()) return 0;
        double sum = 0;
        for (AABB box : shape.toAabbs()) {
            AABB w = box.move(b);
            // Axis segment inside the box: intersection of intervals on three axes.
            double lo = 0, hi = len;
            double[] p = {at.x, at.y, at.z}, d = {dir.x, dir.y, dir.z}, mn = {w.minX, w.minY, w.minZ}, mx = {w.maxX, w.maxY, w.maxZ};
            for (int k = 0; k < 3 && lo < hi; k++) {
                if (Math.abs(d[k]) < 1e-12) {
                    if (p[k] < mn[k] || p[k] > mx[k]) hi = lo;
                    continue;
                }
                double a = (mn[k] - p[k]) / d[k], c = (mx[k] - p[k]) / d[k];
                lo = Math.max(lo, Math.min(a, c));
                hi = Math.min(hi, Math.max(a, c));
            }
            if (hi > lo) sum += hi - lo;
        }
        return Math.min(len, sum);
    }

}
