package ua.zentix.incoming.client.fx.layer;

import org.joml.Vector3fc;

/** Conservative anisotropic Gaussian bounds, clipped before perspective division. */
public final class GaussianBounds {
    private final ProjectedBounds clip = new ProjectedBounds();
    private final float[] corners = new float[16], face = new float[4];
    private final double[] centre = new double[3], radius = new double[3];

    public boolean project(double x, double y, double z, double sh, double sv, double ext,
                           Vector3fc forward, Vector3fc left, Vector3fc up, double width, double height, double[] result) {
        return project(x,y,z,sh,sv,ext,Double.POSITIVE_INFINITY,forward,left,up,width,height,result);
    }

    public boolean project(double x, double y, double z, double sh, double sv, double ext, double support,
                           Vector3fc forward, Vector3fc left, Vector3fc up, double width, double height, double[] result) {
        // This peak bounds every ray orientation; outside the ellipsoid, omitted alpha is at most 1e-7.
        double peak = ext / (2 * Math.PI * sh * Math.min(sh, sv));
        double cut = Math.min(support,Math.sqrt(Math.max(0, 2 * Math.log(Math.max(peak / 1e-7, 1)))));
        centre[0] = x; centre[1] = y; centre[2] = z;
        radius[0] = radius[2] = cut * sh; radius[1] = cut * sv;
        double x0 = 1, y0 = 1, x1 = 0, y1 = 0;
        for (int axis = 0; axis < 3; axis++) for (int side = -1; side <= 1; side += 2) {
            int a = (axis + 1) % 3, b = (axis + 2) % 3;
            for (int j = 0; j < 4; j++) {
                int sa = j < 2 ? -1 : 1, sb = j == 0 || j == 3 ? -1 : 1;
                double px = centre[0] + radius[0] * (axis == 0 ? side : a == 0 ? sa : sb);
                double py = centre[1] + radius[1] * (axis == 1 ? side : a == 1 ? sa : sb);
                double pz = centre[2] + radius[2] * (axis == 2 ? side : a == 2 ? sa : sb);
                corners[4*j] = (float) ((px*left.x()+py*left.y()+pz*left.z()) / width);
                corners[4*j+1] = (float) ((px*up.x()+py*up.y()+pz*up.z()) / height);
                corners[4*j+2] = 0;
                corners[4*j+3] = (float) (px*forward.x()+py*forward.y()+pz*forward.z());
            }
            clip.quad(corners, face, 0);
            if (face[2] <= face[0] || face[3] <= face[1]) continue;
            x0 = Math.min(x0, face[0]); y0 = Math.min(y0, face[1]);
            x1 = Math.max(x1, face[2]); y1 = Math.max(y1, face[3]);
        }
        if (x1 <= x0 || y1 <= y0) return false;
        result[0] = (2*x0-1)*width; result[1] = (2*y0-1)*height;
        result[2] = (2*x1-1)*width; result[3] = (2*y1-1)*height;
        return true;
    }
}
