package ua.zentix.incoming.client.fx.smoke;

import java.util.Arrays;

/**
 * Ground-layer Gaussians in one bounded volume, sharing SmokePass and PallPass. The vertical grid fits the
 * actual material independently of the horizontal extent: a decimetre layer must not become a two-metre cube.
 * Cell integrals in fx_smoke_fill conserve optical mass, including the turbulent redistribution.
 */
public final class DustGrid {
    private static final int WIDTH = 12, STAGED = 16384, HASH = 32768;
    private static final double REACH = 3;
    private static final double[] input = new double[STAGED * WIDTH], merged = new double[STAGED * WIDTH];
    private static final int[] hash = new int[HASH];
    private static final long[] keysX = new long[STAGED], keysY = new long[STAGED], keysZ = new long[STAGED];
    private static final SmokeGrid.Frame FRAME = new SmokeGrid.Frame();
    private static int count;
    private static boolean enabled;
    private static double eyeX, eyeY, eyeZ;

    private DustGrid() {}

    public static void begin(boolean on, double x, double y, double z) {
        enabled = on;
        count = 0;
        eyeX = x; eyeY = y; eyeZ = z;
    }

    public static boolean active() { return enabled; }

    /** Optical mass already includes the size-bin settling in the emitting model. No independent lifetime. */
    public static void add(double x, double y, double z, double sh, double sv, double dust,
                           float r, float g, float b, long seed, double seconds) {
        if (!(dust > 0 && sh > 0 && sv > 0)) return;
        if (count == STAGED) compact();
        int i = count++ * WIDTH;
        input[i] = x; input[i+1] = y; input[i+2] = z;
        input[i+3] = sh; input[i+4] = sv;
        input[i+5] = dust * SmokeGrid.dustK(r, g, b);
        input[i+6] = r; input[i+7] = g; input[i+8] = b;
        // Presentation: source-local redistribution moves with the model's centre and widths, never with the eye.
        input[i+9] = ((seed >>> 16) & 65535) * (2 * Math.PI / 65536) + seconds * 0.45;
        input[i+10] = ((seed >>> 40) & 65535) * (2 * Math.PI / 65536) - seconds * 0.31;
        input[i+11] = dust;
    }

    /** Coarsen only when necessary; moments and optical mass survive the bounded representation. */
    private static void compact() {
        if (count <= SmokeGrid.MAX_BLOBS) return;
        int n = count;
        for (int level = 0; ; level++) {
            Arrays.fill(hash, -1);
            int used = 0;
            double cell = Math.scalb(1.0, level), vertical = cell / 8;
            for (int k = 0; k < n; k++) {
                int a = k * WIDTH;
                long x = (long) Math.floor(input[a] / cell), y = (long) Math.floor(input[a+1] / vertical), z = (long) Math.floor(input[a+2] / cell);
                int slot = (int) (x * 73856093L ^ y * 19349663L ^ z * 83492791L) & (HASH - 1);
                while (hash[slot] >= 0 && (keysX[hash[slot]] != x || keysY[hash[slot]] != y || keysZ[hash[slot]] != z)) slot = (slot + 1) & (HASH - 1);
                int j = hash[slot];
                if (j < 0) {
                    j = used++;
                    hash[slot] = j; keysX[j] = x; keysY[j] = y; keysZ[j] = z;
                    System.arraycopy(input, a, merged, j * WIDTH, WIDTH);
                } else merge(merged, j * WIDTH, input, a);
            }
            if (used <= SmokeGrid.MAX_BLOBS) {
                System.arraycopy(merged, 0, input, 0, used * WIDTH);
                count = used;
                return;
            }
        }
    }

    static void merge(double[] into, int a, double[] from, int b) {
        double first = into[a+5], second = from[b+5], sum = first + second, w = second / sum;
        double dx = from[b] - into[a], dy = from[b+1] - into[a+1], dz = from[b+2] - into[a+2];
        double h2 = (1-w)*into[a+3]*into[a+3] + w*from[b+3]*from[b+3] + w*(1-w)*(dx*dx+dz*dz)/2;
        double v2 = (1-w)*into[a+4]*into[a+4] + w*from[b+4]*from[b+4] + w*(1-w)*dy*dy;
        into[a] += w*dx; into[a+1] += w*dy; into[a+2] += w*dz;
        into[a+3] = Math.sqrt(h2); into[a+4] = Math.sqrt(v2); into[a+5] = sum;
        for (int c = 6; c <= 8; c++) into[a+c] += w*(from[b+c]-into[a+c]);
        into[a+11] += from[b+11];
    }

    public static SmokeGrid.Frame finish() {
        compact();
        SmokeGrid.Frame f = FRAME;
        f.count = count;
        Arrays.fill(f.boxLo, Float.MAX_VALUE); Arrays.fill(f.boxHi, -Float.MAX_VALUE);
        Arrays.fill(f.min, Float.MAX_VALUE); Arrays.fill(f.max, -Float.MAX_VALUE);
        if (count == 0) return f;
        for (int k = 0; k < count; k++) {
            int a = k * WIDTH;
            for (int axis = 0; axis < 3; axis++) {
                double s = axis == 1 ? input[a+4] : input[a+3];
                double eye = axis == 0 ? eyeX : axis == 1 ? eyeY : eyeZ;
                f.min[axis] = Math.min(f.min[axis], (float) (input[a+axis] - eye - REACH*s));
                f.max[axis] = Math.max(f.max[axis], (float) (input[a+axis] - eye + REACH*s));
            }
        }
        // Two empty border cells keep trilinear interpolation from extending a clamped edge.
        double h = Math.max(0.02, Math.max(f.max[0]-f.min[0], f.max[2]-f.min[2]) / (SmokeGrid.N - 4));
        double v = Math.max(0.002, (f.max[1]-f.min[1]) / (SmokeGrid.N - 4));
        f.dustCellY = (float) v;
        for (int axis = 0; axis < 3; axis++) {
            double cell = axis == 1 ? v : h;
            f.corner[axis] = (float) (f.min[axis] - 2*cell);
            f.boxLo[axis] = f.min[axis] = f.corner[axis];
            f.boxHi[axis] = f.max[axis] = (float) (f.corner[axis] + SmokeGrid.N*cell);
        }
        f.corner[3] = (float) h;
        for (int k = 0; k < count; k++) {
            int a = k * WIDTH;
            f.cascade[k] = 0;
            put(f, 0, k, input[a]-eyeX, input[a+1]-eyeY, input[a+2]-eyeZ, input[a+3]);
            put(f, 1, k, input[a+4], 0, input[a+5], 0);
            put(f, 2, k, input[a+6], input[a+7], input[a+8], 0);
            put(f, 3, k, input[a+9] % (2*Math.PI), input[a+10] % (2*Math.PI), 0, 0);
        }
        SmokeGrid.layers(f);
        return f;
    }

    private static void put(SmokeGrid.Frame f, int row, int index, double a, double b, double c, double d) {
        int i = (row * SmokeGrid.MAX_BLOBS + index)*4;
        f.blobs[i] = (float) a; f.blobs[i+1] = (float) b; f.blobs[i+2] = (float) c; f.blobs[i+3] = (float) d;
    }
}
