package ua.zentix.incoming.client.far;

import java.util.Arrays;

/** Angular Beer–Lambert transport through nearby Gaussians. A central ray is not a screen-wide fog value. */
final class VeilField {
    static final int COLS = 32, ROWS = 72;
    private static final int POINTS = (COLS + 1) * (ROWS + 1);
    private final double[] tau = new double[POINTS], red = new double[POINTS], green = new double[POINTS], blue = new double[POINTS];
    private final double[] rays = new double[3 * POINTS];
    final float[] rgba = new float[4 * POINTS];
    double width, height;
    private boolean initialized;

    void clear() {
        Arrays.fill(tau, 0); Arrays.fill(red, 0); Arrays.fill(green, 0); Arrays.fill(blue, 0);
        initialized = false;
    }

    void begin(FarView view, double aspect) {
        if (initialized) return;
        height = Math.tan(view.edge()) / Math.sqrt(1 + aspect * aspect);
        width = height * aspect;
        for (int y = 0; y <= ROWS; y++) for (int x = 0; x <= COLS; x++) {
            double u = width * (2.0 * x / COLS - 1), v = height * (2.0 * y / ROWS - 1);
            double fx = view.forward().x + u * view.left().x + v * view.up().x;
            double fy = view.forward().y + u * view.left().y + v * view.up().y;
            double fz = view.forward().z + u * view.left().z + v * view.up().z;
            double length = Math.sqrt(fx * fx + fy * fy + fz * fz);
            int i = 3 * (y * (COLS + 1) + x);
            rays[i] = fx / length; rays[i + 1] = fy / length; rays[i + 2] = fz / length;
        }
        initialized = true;
    }

    void add(double px, double py, double pz, double sh, double sv, double ext, double share, float r, float g, float b) {
        for (int i = 0; i < POINTS; i++) {
            double depth = SmokePuffs.sightDepth(px, py, pz, sh, sv, ext, rays[3*i], rays[3*i+1], rays[3*i+2]);
            // Retain the existing geometric transition between shell sprites and the part around the eye.
            double alpha = -Math.expm1(-depth) * share;
            double t = -Math.log(Math.max(1e-300, 1 - alpha));
            tau[i] += t; red[i] += t * r; green[i] += t * g; blue[i] += t * b;
        }
    }

    float[] finish() {
        for (int i = 0; i < POINTS; i++) {
            float alpha = (float) -Math.expm1(-tau[i]);
            rgba[4*i] = tau[i] > 0 ? (float) (red[i] / tau[i]) : 0;
            rgba[4*i+1] = tau[i] > 0 ? (float) (green[i] / tau[i]) : 0;
            rgba[4*i+2] = tau[i] > 0 ? (float) (blue[i] / tau[i]) : 0;
            rgba[4*i+3] = alpha;
        }
        return rgba;
    }

    /** Same diagonal split and linear alpha interpolation as the two GPU triangles. */
    static double alpha(float[] field, double u, double v, double width, double height) {
        double x = Math.clamp((u / width + 1) * COLS / 2, 0, COLS - 1e-9);
        double y = Math.clamp((v / height + 1) * ROWS / 2, 0, ROWS - 1e-9);
        int ix = (int) x, iy = (int) y, i = iy * (COLS + 1) + ix;
        double a = x - ix, b = y - iy;
        double ll = field[4*i+3], ul = field[4*(i+COLS+1)+3], ur = field[4*(i+COLS+2)+3], lr = field[4*(i+1)+3];
        return b >= a ? ll * (1-b) + ul * (b-a) + ur * a : ll * (1-a) + ur * b + lr * (a-b);
    }
}
