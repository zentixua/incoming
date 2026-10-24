import numpy as np
def hough_edge(al, steep=0.15, bins=72):
    """Share of the tile size covered by one straight steep alpha edge: texels with |grad alpha| >= steep (per texel,
    at 128-texel scale) vote for the line through them across their gradient (angle bin, offset 1 texel); the best line's
    votes (offset ±1) / tile size."""
    n = al.shape[0]
    a = al.astype(float)
    gx = np.zeros_like(a); gy = np.zeros_like(a)
    gx[:, 1:-1] = (a[:, 2:] - a[:, :-2]) / 2; gy[1:-1, :] = (a[2:, :] - a[:-2, :]) / 2
    g = np.hypot(gx, gy) * n / 128
    ys, xs = np.nonzero(g >= steep)
    if len(xs) == 0: return 0.0
    th = np.arctan2(gy[ys, xs], gx[ys, xs])
    k = np.round((th + np.pi) / (2 * np.pi) * bins).astype(int) % bins
    best = 0
    for b in range(bins):
        sel = (k == b) | (k == (b + 1) % bins) | (k == (b - 1) % bins)
        if sel.sum() < 0.1 * n: continue
        t = 2 * np.pi * b / bins - np.pi
        rho = np.round(xs[sel] * np.cos(t) + ys[sel] * np.sin(t)).astype(int)
        cnt = np.bincount(rho - rho.min())
        c3 = cnt + np.r_[cnt[1:], 0] + np.r_[0, cnt[:-1]]
        best = max(best, c3.max())
    return round(best / n, 2)
