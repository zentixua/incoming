package ua.zentix.incoming.client.fx.layer;

/**
 * Baked-puff geometry in world space, shared by rendering and fill calculation. Turning the view without moving
 * the eye leaves it unchanged. out holds four eye-relative (x, y, z) vertices in UV order: (1, 1), (1, 0), (0, 0), (0, 1).
 */
public final class PuffGeometry {
    private PuffGeometry() {}

    /**
     * Puff facing the eye position, with world up projected onto its plane. Directly above or below the eye,
     * vertical does not define its rotation; there its width follows world X, independent of the camera direction.
     */
    public static void face(double x, double y, double z, double halfA, double halfB, double[] out) {
        double h = Math.hypot(x, z), d = Math.hypot(h, y);
        double lx = h > 1e-9 ? z / h : 1, lz = h > 1e-9 ? -x / h : 0;
        double fx = d > 1e-9 ? x / d : 0, fy = d > 1e-9 ? y / d : 0, fz = d > 1e-9 ? z / d : 1;
        corners(x, y, z, lx * halfA, 0, lz * halfA,
                fy * lz * halfB, (fz * lx - fx * lz) * halfB, -fy * lx * halfB, out);
    }

    /** Horizontal puff; the seed sets its angle from world X, independently of the view direction. */
    public static void flat(double x, double y, double z, double halfA, double halfB, double angle, double[] out) {
        double c = Math.cos(angle), s = Math.sin(angle);
        corners(x, y, z, c * halfA, 0, s * halfA, -s * halfB, 0, c * halfB, out);
    }

    /**
     * Puff lighting basis in two UV2 shorts instead of FULL_BRIGHT, which is constant for puffs and restored in fx.vsh.
     * Two circles encode the horizontal width axis and the elevation of the center ray. Each stores
     * sin/(|cos| + |sin|) in signed 15 bits (±16383); cos is reconstructed as 1 − |sin|, then the pair is normalized.
     * Bit 15 is the width-cosine sign, bit 31 is UV mirroring; the elevation cosine is nonnegative. Each angle's error
     * is below 0.0035°, without atan2 during packing or sin/cos in the shader.
     */
    static int faceLight(double x, double y, double z, boolean mirror) {
        double h = Math.hypot(x, z);
        int pitch = h + Math.abs(y) > 1e-9 ? unit(y / (h + Math.abs(y))) : 0;
        return (h > 1e-9 ? yaw(z, -x) : 0) | pitch << 16 | (mirror ? 0x80000000 : 0);
    }

    /** Horizontal puff: the same yaw circle, with elevation code −16384 (outside its range) marking the horizontal plane. */
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
