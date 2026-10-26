package ua.zentix.incoming.client.fx.layer;

/**
 * Геометрия запечённого клуба в мире: одна у слоя и у расчёта заливки. Поворот взгляда при неподвижном глазе её не
 * меняет. В out — четыре вершины от глаза (x, y, z), в порядке UV: (1, 1), (1, 0), (0, 0), (0, 1).
 */
public final class PuffGeometry {
    private PuffGeometry() {}

    /**
     * Клуб лицом к положению глаза; верх — проекция вертикали мира на его плоскость. Прямо над глазом и под ним
     * вертикаль не задаёт поворота: там ось ширины — X мира, независимо от направления камеры.
     */
    public static void face(double x, double y, double z, double halfA, double halfB, double[] out) {
        double h = Math.hypot(x, z), d = Math.hypot(h, y);
        double lx = h > 1e-9 ? z / h : 1, lz = h > 1e-9 ? -x / h : 0;
        double fx = d > 1e-9 ? x / d : 0, fy = d > 1e-9 ? y / d : 0, fz = d > 1e-9 ? z / d : 1;
        corners(x, y, z, lx * halfA, 0, lz * halfA,
                fy * lz * halfB, (fz * lx - fx * lz) * halfB, -fy * lx * halfB, out);
    }

    /** Клуб на горизонтали; угол от оси X мира задан зерном клуба, а не направлением взгляда. */
    public static void flat(double x, double y, double z, double halfA, double halfB, double angle, double[] out) {
        double c = Math.cos(angle), s = Math.sin(angle);
        corners(x, y, z, c * halfA, 0, s * halfA, -s * halfB, 0, c * halfB, out);
    }

    private static void corners(double x, double y, double z, double ax, double ay, double az, double bx, double by, double bz, double[] out) {
        out[0] = x - ax - bx;
        out[1] = y - ay - by;
        out[2] = z - az - bz;
        out[3] = x - ax + bx;
        out[4] = y - ay + by;
        out[5] = z - az + bz;
        out[6] = x + ax + bx;
        out[7] = y + ay + by;
        out[8] = z + az + bz;
        out[9] = x + ax - bx;
        out[10] = y + ay - by;
        out[11] = z + az - bz;
    }
}
