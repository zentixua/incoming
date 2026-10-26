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

    /**
     * Базис света клуба в двух short UV2 вместо FULL_BRIGHT (он у клубов постоянный и восстанавливается в fx.vsh):
     * две окружности — горизонтальная ось ширины и подъём луча к середине. У каждой — sin/(|cos| + |sin|) в знаковых
     * 15 битах (±16383), cos восстанавливается как 1 − |sin|, затем пара нормируется. Бит 15 — знак cos ширины, бит 31 —
     * отражение UV; cos подъёма неотрицателен. Ошибка каждого угла меньше 0,0035°, без atan2 при записи и sin/cos в шейдере.
     */
    static int faceLight(double x, double y, double z, boolean mirror) {
        double h = Math.hypot(x, z);
        int pitch = h + Math.abs(y) > 1e-9 ? unit(y / (h + Math.abs(y))) : 0;
        return (h > 1e-9 ? yaw(z, -x) : 0) | pitch << 16 | (mirror ? 0x80000000 : 0);
    }

    /** Лежащий клуб: та же горизонтальная окружность, код подъёма −16384 (вне её диапазона) — плоскость горизонтальна. */
    static int flatLight(double angle, boolean mirror) {
        return yaw(Math.cos(angle), Math.sin(angle)) | 0x40000000 | (mirror ? 0x80000000 : 0);
    }

    private static int yaw(double c, double s) {
        return unit(s / (Math.abs(c) + Math.abs(s))) | (c < 0 ? 0x8000 : 0);
    }

    private static int unit(double q) {
        return (int) Math.round(q * 16383) & 0x7fff;
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
