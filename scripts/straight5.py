import numpy as np
def chord_edge(al, thr=0.5, rays=360, tol=0.015):
    """Longest straight piece of the silhouette as a share of its mean diameter: silhouette radius r(θ) along rays from the
    alpha centroid (outermost texel ≥ thr), the longest arc of rays whose points all lie within tol·diameter of the chord
    between the arc's ends."""
    n = al.shape[0]
    yy, xx = np.mgrid[0:n, 0:n]; w = al.sum(); cx = (xx * al).sum() / w; cy = (yy * al).sum() / w
    pts = []
    steps = np.arange(0, n, 0.5)
    for t in np.linspace(0, 2 * np.pi, rays, endpoint=False):
        dx, dy = np.cos(t), np.sin(t)
        xs = cx + steps * dx; ys = cy + steps * dy
        ok = (xs >= 0) & (xs <= n - 1) & (ys >= 0) & (ys <= n - 1)
        xs, ys = xs[ok], ys[ok]
        v = al[ys.round().astype(int), xs.round().astype(int)]
        inside = np.nonzero(v >= thr)[0]
        r = steps[inside[-1]] if len(inside) else 0.0
        pts.append((cx + r * dx, cy + r * dy))
    p = np.array(pts); d = 2 * np.mean(np.hypot(p[:, 0] - cx, p[:, 1] - cy))
    best = 0.0
    for i in range(rays):
        for L in range(3, rays // 2):
            seg = p[[(i + k) % rays for k in range(L + 1)]]
            a, b = seg[0], seg[-1]; ab = b - a; ln = np.hypot(*ab)
            if ln < 1e-6: continue
            dev = np.abs((seg[:, 0] - a[0]) * ab[1] - (seg[:, 1] - a[1]) * ab[0]) / ln
            if dev.max() > tol * d: break
            best = max(best, ln / d)
    return round(best, 3)
