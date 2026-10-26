package ua.zentix.incoming.client.fx.smoke;

/** Conservative world-space support of a source, before emitting any puffs or reading terrain. */
public final class SmokeBounds {
    /** Four-sigma material edge, cluster offsets and the corners of a rotated puff square. */
    public static final double PUFF = 10;
    public double minX, minY, minZ, maxX, maxY, maxZ;

    public SmokeBounds() { clear(); }

    public void clear() {
        minX = minY = minZ = Double.POSITIVE_INFINITY;
        maxX = maxY = maxZ = Double.NEGATIVE_INFINITY;
    }

    public void box(double x0, double y0, double z0, double x1, double y1, double z1) {
        minX = Math.min(minX, x0); minY = Math.min(minY, y0); minZ = Math.min(minZ, z0);
        maxX = Math.max(maxX, x1); maxY = Math.max(maxY, y1); maxZ = Math.max(maxZ, z1);
    }

    public void puff(double x, double y, double z, double sh, double sv) {
        // Billboards turn with the camera: even a flat Gaussian can extend vertically on screen.
        double r = PUFF * Math.max(sh, sv);
        box(x - r, y - r, z - r, x + r, y + r, z + r);
    }

    public void move(double x, double y, double z) {
        minX += x; maxX += x; minY += y; maxY += y; minZ += z; maxZ += z;
    }

    public boolean empty() { return minX > maxX; }

    public double distanceSquared(double x, double y, double z) {
        double dx = Math.max(0, Math.max(minX - x, x - maxX));
        double dy = Math.max(0, Math.max(minY - y, y - maxY));
        double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    public double priority(double x, double y, double z) {
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        // A conservative old cloud can enclose the camera while its material is kilometres away.
        // Distance to that box is zero; using it would let empty margins displace nearby clouds.
        double cx = (minX + maxX) * 0.5 - x, cy = (minY + maxY) * 0.5 - y, cz = (minZ + maxZ) * 0.5 - z;
        return (dx * dx + dy * dy + dz * dz) / Math.max(1, cx * cx + cy * cy + cz * cz);
    }
}
