#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
# ]
# ///
"""Projectile models: smooth OBJ meshes and their textures drawn in code (fixed seed).

  uv run tools/gen_models.py   → mod/src/main/resources/assets/incoming/
        models/weapon/<model>_<part>.obj + .json (neoforge:obj loader), models/weapon/<model>.mtl,
        textures/block/weapon/<model>.png (block atlas: models rendered as blocks, as entities under shaders)

Geometry and UVs are built together so textures always match the mesh. Units are blocks (= meters), nose +Z,
up +Y, +X is port (as in client/render models). Each moving part has its own OBJ; the renderer rotates it about
a hinge (hinge coordinates in WeaponModels.java, also here in comments). Each smooth-surfaced part gets a simplified
<model>_<part>_lod1 copy (far models, FarModels): same surfaces with decimated mesh rows; discarded points are closer
than LOD_TOL of model size to the retained ones, normals from the full mesh, same UVs.

Models:
  drone   — Shahed-136 (×2): blunt-nose fuselage, clipped delta wing, wingtip fins, pusher propeller
            (blades + blurred disc), launch booster under the tail;
  missile — cruise missile (like Tomahawk, ×2): round body, wings extend from body, X-tail,
            belly air intake drops after launch, booster behind nozzle;
  bomber  — B-2: flying wing with sawtooth trailing edge, cockpit and nacelle hump, two pairs of bomb-bay doors;
  bomb    — GBU-57: ogival nose, yellow band, grid fins;
  icbm    — Minuteman III: three stages, black bands, fairing, four first-stage nozzles;
  rocket, rocket_rack — 9M22 rocket and Grad rack of 40 tubes;
  loiter  — Lancet-3 loitering munition (1.65 m): thin body, two X-shaped wing crosses
            (unfold after catapult), pusher propeller;
  interceptor — SAM interceptor missile (like 9M96): seeker fairing, cruciform control fins and stabilizers,
            luminous nozzle exit; no simplified copy (not rendered at a distance);
  sam     — SAM launcher above its block: plate and rack of four vertical containers (no simplified copy).
Requires numpy and Pillow.
"""
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "incoming")
MODELS = os.path.join(ASSETS, "models", "weapon")
TEXTURES = os.path.join(ASSETS, "textures", "block", "weapon")
rng = np.random.default_rng(1945)
# Simplified copy: how far (as a fraction of the largest model size) discarded mesh points can deviate from the remaining
#  surface. The distant model takes a copy as long as it is smaller than FarModels.COARSE_BELOW pixels: 0,5 % - less than a quarter of a pixel.
LOD_TOL = 0.005


def v3(x, y, z):
    return np.array([x, y, z], dtype=float)


def unit(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-9 else v


# =============================================================================== texture

class Canvas:
    """Model texture: RGBA float, areas are issued by shelves; draw in fractions of the region (u, v ∈ [0, 1])."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.rgb = np.zeros((h, w, 3))
        self.a = np.ones((h, w))
        self.x = self.y = self.row = 0

    def alloc(self, w, h):
        w, h = int(w), int(h)
        if self.x + w > self.w:
            self.x, self.y, self.row = 0, self.y + self.row + 2, 0
        if self.y + h > self.h:
            raise ValueError(f"texture {self.w}×{self.h} full ({w}×{h})")
        r = Region(self, self.x, self.y, w, h)
        self.x += w + 2
        self.row = max(self.row, h)
        return r

    def save(self, path):
        img = np.dstack([np.clip(self.rgb, 0, 1), self.a[..., None]])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        Image.fromarray((img * 255 + 0.5).astype(np.uint8), "RGBA").save(path)


def smooth_noise(h, w, cell, octaves=3):
    """Noise with large spots (like faded paint): the sum of octaves of an interpolated random grid."""
    out = np.zeros((h, w))
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        c = max(2, cell >> o)
        gh, gw = h // c + 2, w // c + 2
        g = rng.random((gh, gw)).astype(np.float32)
        img = Image.fromarray((g * 255).astype(np.uint8)).resize((gw * c, gh * c), Image.BICUBIC)
        out += amp * (np.asarray(img, dtype=float)[:h, :w] / 255 - 0.5)
        tot += amp
        amp *= 0.5
    return out / tot


class Region:
    def __init__(self, cv, x, y, w, h):
        self.cv, self.x, self.y, self.w, self.h = cv, x, y, w, h

    def uv(self, u, v):
        """Area shares → UV texture (with half a texel indentation so as not to interfere with the adjacent area)."""
        px = self.x + 0.5 + np.clip(u, 0, 1) * (self.w - 1)
        py = self.y + 0.5 + np.clip(v, 0, 1) * (self.h - 1)
        return px / self.cv.w, py / self.cv.h

    @property
    def rgb(self):
        return self.cv.rgb[self.y:self.y + self.h, self.x:self.x + self.w]

    @property
    def alpha(self):
        return self.cv.a[self.y:self.y + self.h, self.x:self.x + self.w]

    def grid(self):
        v, u = np.mgrid[0:self.h, 0:self.w]
        return (u + 0.5) / self.w, (v + 0.5) / self.h

    def paint(self, color, mottle=0.06, grain=0.035, cell=48):
        """Filling with paint: large burnout spots + small seed."""
        c = np.array(color, dtype=float) / 255
        n = smooth_noise(self.h, self.w, cell) * mottle * 2 + rng.normal(0, grain, (self.h, self.w))
        self.rgb[...] = np.clip(c[None, None, :] * (1 + n[..., None]), 0, 1)

    def mix(self, mask, color, k=1.0):
        c = np.array(color, dtype=float) / 255
        m = np.clip(mask * k, 0, 1)[..., None]
        self.rgb[...] = self.rgb * (1 - m) + c * m

    def darken(self, mask, k):
        self.rgb[...] = self.rgb * (1 - np.clip(mask * k, 0, 1))[..., None]

    def lines_u(self, us, dark=0.35, width=1):
        """Cutting lines along v (on u = const)."""
        for u in us:
            x = int(round(u * (self.w - 1)))
            for dx in range(width):
                if 0 <= x + dx < self.w:
                    self.rgb[:, x + dx] *= 1 - dark
            if x + width < self.w:
                self.rgb[:, x + width] = np.clip(self.rgb[:, x + width] * (1 + dark * 0.4), 0, 1)

    def lines_v(self, vs, dark=0.35, width=1, u0=0.0, u1=1.0):
        for v in vs:
            y = int(round(v * (self.h - 1)))
            a, b = int(u0 * self.w), int(math.ceil(u1 * self.w))
            for dy in range(width):
                if 0 <= y + dy < self.h:
                    self.rgb[y + dy, a:b] *= 1 - dark
            if y + width < self.h:
                self.rgb[y + width, a:b] = np.clip(self.rgb[y + width, a:b] * (1 + dark * 0.4), 0, 1)

    def rivets(self, vs, step_px=6, dark=0.25, u0=0.0, u1=1.0):
        for v in vs:
            y = int(round(v * (self.h - 1)))
            if not 0 <= y < self.h:
                continue
            for x in range(int(u0 * self.w) + 2, int(u1 * self.w) - 1, step_px):
                self.rgb[y, x] *= 1 - dark

    def rect(self, u0, v0, u1, v1, color, alpha=1.0):
        x0, x1 = int(u0 * self.w), max(int(u0 * self.w) + 1, int(u1 * self.w))
        y0, y1 = int(v0 * self.h), max(int(v0 * self.h) + 1, int(v1 * self.h))
        c = np.array(color, dtype=float) / 255
        self.rgb[y0:y1, x0:x1] = self.rgb[y0:y1, x0:x1] * (1 - alpha) + c * alpha

    def text(self, s, u, v, px, color, rot=False):
        """
        Stencil inscription in pixel font 3×5 (px is the “pixel” size of the font). rot - along the hull: on the left side
         (u < 0.5) the top of the letters looks up when read from the nose, on the right - turned in the other direction (read from the tail).
        """
        glyphs = [FONT.get(ch, FONT[" "]) for ch in s]
        cw = 4 * px
        img = np.zeros((5 * px, max(1, cw * len(glyphs))))
        for i, g in enumerate(glyphs):
            for r, row in enumerate(g):
                for c, bit in enumerate(row):
                    if bit == "#":
                        img[r * px:(r + 1) * px, i * cw + c * px:i * cw + (c + 1) * px] = 1
        if rot:
            img = np.rot90(img, 1 if u > 0.5 else 3)
        x0, y0 = int(u * self.w), int(v * self.h)
        h, w = img.shape
        h, w = min(h, self.h - y0), min(w, self.w - x0)
        if h <= 0 or w <= 0:
            return
        c = np.array(color, dtype=float) / 255
        m = img[:h, :w, None] * 0.9
        sl = self.rgb[y0:y0 + h, x0:x0 + w]
        sl[...] = sl * (1 - m) + c * m


FONT = {
    " ": ["...", "...", "...", "...", "..."],
    "-": ["...", "...", "###", "...", "..."],
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "###", "#..", "###"], "3": ["###", "..#", ".##", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"], "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"], "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"], "9": ["###", "#.#", "###", "..#", "###"],
    "A": [".#.", "#.#", "###", "#.#", "#.#"], "B": ["##.", "#.#", "##.", "#.#", "##."],
    "C": ["###", "#..", "#..", "#..", "###"], "D": ["##.", "#.#", "#.#", "#.#", "##."],
    "E": ["###", "#..", "##.", "#..", "###"], "F": ["###", "#..", "##.", "#..", "#.."],
    "G": ["###", "#..", "#.#", "#.#", "###"], "H": ["#.#", "#.#", "###", "#.#", "#.#"],
    "I": ["###", ".#.", ".#.", ".#.", "###"], "K": ["#.#", "##.", "#..", "##.", "#.#"],
    "L": ["#..", "#..", "#..", "#..", "###"], "M": ["#.#", "###", "###", "#.#", "#.#"],
    "N": ["##.", "#.#", "#.#", "#.#", "#.#"], "O": ["###", "#.#", "#.#", "#.#", "###"],
    "P": ["###", "#.#", "###", "#..", "#.."], "R": ["##.", "#.#", "##.", "#.#", "#.#"],
    "S": ["###", "#..", "###", "..#", "###"], "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "U": ["#.#", "#.#", "#.#", "#.#", "###"], "W": ["#.#", "#.#", "###", "###", "#.#"],
    "X": ["#.#", "#.#", ".#.", "#.#", "#.#"], "Y": ["#.#", "#.#", ".#.", ".#.", ".#."],
    ".": ["...", "...", "...", "...", ".#."],
}


# ============================================================================== Grid

def face(pts, uvs, normals, material):
    return material, [np.asarray(p, float) for p in pts], list(uvs), [unit(np.asarray(n, float)) for n in normals]


def quads(P, UV, N, material, smooth):
    """Mesh faces P[i][j] with UV[i][j] and normals N[i][j]: traversal - outward, along point normals."""
    out = []
    n_i, n_j = P.shape[0], P.shape[1]
    for i in range(n_i - 1):
        for j in range(n_j - 1):
            idx = [(i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)]
            pts = [P[a, b] for a, b in idx]
            fn = np.cross(pts[1] - pts[0], pts[3] - pts[0]) + np.cross(pts[3] - pts[2], pts[1] - pts[2])
            if np.linalg.norm(fn) < 1e-9:
                fn = np.cross(pts[2] - pts[0], pts[3] - pts[1])
            if np.linalg.norm(fn) < 1e-12:
                continue
            avg = sum(N[a, b] for a, b in idx)
            if np.dot(fn, avg) < 0:
                idx.reverse()
                pts.reverse()
                fn = -fn
            norms = [N[a, b] for a, b in idx] if smooth else [unit(fn)] * 4
            out.append(face(pts, [UV[a][b] for a, b in idx], norms, material))
    return out


def keep_rows(P, tol):
    """
    Which rows of grid P (first index) to retain: Ramer–Douglas–Peucker across all columns at once. Every discarded
row is closer than tol to the segments between adjacent retained rows in each column. End rows are always retained.
    """
    n = P.shape[0]
    keep = {0, n - 1}
    stack = [(0, n - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        A, AB = P[a], P[b] - P[a]
        L2 = np.maximum((AB * AB).sum(-1), 1e-18)
        worst, at = -1.0, -1
        for i in range(a + 1, b):
            AP = P[i] - A
            t = np.clip((AP * AB).sum(-1) / L2, 0, 1)
            d = float(np.linalg.norm(AP - t[:, None] * AB, axis=-1).max())
            if d > worst:
                worst, at = d, i
        if worst > tol:
            keep.add(at)
            stack += [(a, at), (at, b)]
    return sorted(keep)


class Grid:
    """Smooth surface of the part, as given by Model.grid: for a simplified copy."""

    def __init__(self, P, UV, N, material, smooth):
        self.P, self.UV, self.N, self.material, self.smooth = P, UV, N, material, smooth

    def coarse(self, tol):
        """Faces of the same surface with thinned rows according to both indices (half the tolerance for each)."""
        ki, kj = keep_rows(self.P, tol / 2), keep_rows(np.swapaxes(self.P, 0, 1), tol / 2)
        UV = [[self.UV[i][j] for j in kj] for i in ki]
        return quads(self.P[np.ix_(ki, kj)], UV, self.N[np.ix_(ki, kj)], self.material, self.smooth)


class Model:
    """Model: parts (each with its own OBJ), faces with positions, UV and normals."""

    def __init__(self, name, tex_w, tex_h, scale=1.0, lod=True):
        """
        scale - by how much to reduce when writing: the geometry below is given in its own units, in OBJ - meters;
        lod - write simplified copies of parts (the MLRS package does not have them: it stands with the player, they do not draw it in the distance).
        """
        self.name = name
        self.scale = scale
        self.cv = Canvas(tex_w, tex_h)
        self.parts = {}
        # part → its surfaces in order: Grid or finished face (the simplified copy does not touch them)
        self.lod = {} if lod else None

    def faces(self, part):
        return self.parts.setdefault(part, [])

    def add_face(self, part, pts, uvs, normals, material="paint"):
        f = face(pts, uvs, normals, material)
        self.faces(part).append(f)
        if self.lod is not None:
            self.lod.setdefault(part, []).append(f)

    # ---------------------------------------------------------------- surfaces

    def grid(self, part, P, UV, hint, material="paint", smooth=True):
        """
        Grid P[i][j] (np.array points) with UV[i][j]. Normals - by differences between neighbors (smoothly) or by faces.
        hint (p) - the approximate direction “outward” at a point: the side of the normal and the bypass are selected using it.
        """
        P = np.asarray(P, float)
        n_i, n_j = P.shape[0], P.shape[1]
        N = np.zeros_like(P)
        for i in range(n_i):
            for j in range(n_j):
                du = P[min(i + 1, n_i - 1), j] - P[max(i - 1, 0), j]
                dv = P[i, min(j + 1, n_j - 1)] - P[i, max(j - 1, 0)]
                n = np.cross(du, dv)
                h = hint(P[i, j])
                if np.linalg.norm(n) < 1e-7:
                    n = h
                elif np.dot(n, h) < 0:
                    n = -n
                N[i, j] = unit(n)
        self.faces(part).extend(quads(P, UV, N, material, smooth))
        if self.lod is not None:
            self.lod.setdefault(part, []).append(Grid(P, UV, N, material, smooth))

    def lathe(self, part, profile, region, seg=32, center=(0.0, 0.0), material="paint", roll=0.0):
        """
        Body of rotation along Z: profile - [(z, rx, ry)] from nose to tail (section - ellipse). u - along the circumference
         (0 - bottom, 0.5 - top), v - along the length of the profile (fraction of the length of the generatrix). Returns z → v for drawing.
        """
        prof = np.asarray(profile, float)
        arc = np.concatenate([[0], np.cumsum(np.hypot(np.diff(prof[:, 0]), np.diff((prof[:, 1] + prof[:, 2]) / 2)))])
        vs = arc / arc[-1]
        P, UV = [], []
        for k in range(seg + 1):
            a = -math.pi / 2 + 2 * math.pi * k / seg + roll
            P.append([v3(center[0] + rx * math.cos(a), center[1] + ry * math.sin(a), z) for z, rx, ry in prof])
            UV.append([region.uv(k / seg, v) for v in vs])
        cx, cy = center
        zs = prof[:, 0]
        self.grid(part, P, UV, lambda p: v3(p[0] - cx, p[1] - cy, 0) if math.hypot(p[0] - cx, p[1] - cy) > 1e-4
                  else v3(0, 0, 1 if p[2] > zs.mean() else -1), material)
        return lambda z: float(np.interp(-z, -zs, vs))

    def wing(self, part, root_le, S, C, N, span, root_chord, tip_chord, sweep, h_root, h_tip, region,
             ns=6, nc=10, material="paint", tip_cap=True, root_cap=False, flat=False):
        """
        Wing/keel/blade: section NACA 00xx. root_le - leading edge at the root; S - in span, C - from the front
         edge to the back, N - thickness. sweep — shift of the leading edge of the tip along C. Top (+N) is the upper half of the
         area, bottom is the lower half; u - along the span, v - along the chord.
        """
        root_le, S, C, N = (np.asarray(x, float) for x in (root_le, S, C, N))
        S, C, N = unit(S), unit(C), unit(N)
        cs = [(1 - math.cos(math.pi * i / nc)) / 2 for i in range(nc + 1)]

        def yt(c):
            return 10 * (0.2969 * math.sqrt(c) - 0.126 * c - 0.3516 * c ** 2 + 0.2843 * c ** 3 - 0.1036 * c ** 4)

        def point(s, c, side):
            le = root_le + S * span * s + C * sweep * s
            chord = root_chord + (tip_chord - root_chord) * s
            h = (h_root + (h_tip - h_root) * s) * (1 if not flat else 0.6)
            return le + C * chord * c + N * side * h * yt(c)

        for side, v0 in ((1, 0.0), (-1, 0.5)):
            P = [[point(i / ns, c, side) for c in cs] for i in range(ns + 1)]
            UV = [[region.uv(i / ns, v0 + 0.5 * c) for c in cs] for i in range(ns + 1)]
            self.grid(part, P, UV, lambda p, side=side: N * side + 1e-3 * S, material, smooth=not flat)
        for s, cap, out in ((1.0, tip_cap, S), (0.0, root_cap, -S)):
            if not cap:
                continue
            P = [[point(s, c, 1) for c in cs], [point(s, c, -1) for c in cs]]
            UV = [[region.uv(s, 0.5 * c) for c in cs], [region.uv(s, 0.5 + 0.5 * c) for c in cs]]
            self.grid(part, P, UV, lambda p, out=out: out, material, smooth=False)

    def box(self, part, lo, hi, region, material="paint"):
        lo, hi = np.asarray(lo, float), np.asarray(hi, float)
        corners = [v3(hi[0] if k & 1 else lo[0], hi[1] if k & 2 else lo[1], hi[2] if k & 4 else lo[2]) for k in range(8)]
        faces = [((0, 2, 6, 4), (-1, 0, 0)), ((1, 5, 7, 3), (1, 0, 0)), ((0, 4, 5, 1), (0, -1, 0)),
                 ((2, 3, 7, 6), (0, 1, 0)), ((0, 1, 3, 2), (0, 0, -1)), ((4, 6, 7, 5), (0, 0, 1))]
        for idx, n in faces:
            uvs = [region.uv(0, 0), region.uv(1, 0), region.uv(1, 1), region.uv(0, 1)]
            self.add_face(part, [corners[i] for i in idx], uvs, [n] * 4, material)

    def plate(self, part, corners, region, normal, material="paint", double=True):
        """Flat quadrilateral (rudder grille, propeller disc): on both sides, if double."""
        corners = [np.asarray(c, float) for c in corners]
        uvs = [region.uv(0, 0), region.uv(1, 0), region.uv(1, 1), region.uv(0, 1)]
        n = unit(np.asarray(normal, float))
        fn = np.cross(corners[1] - corners[0], corners[3] - corners[0])
        if np.dot(fn, n) < 0:
            corners, uvs = corners[::-1], uvs[::-1]
        self.add_face(part, corners, uvs, [n] * 4, material)
        if double:
            self.add_face(part, corners[::-1], uvs[::-1], [-n] * 4, material)

    # ---------------------------------------------------------------- entry

    def write(self):
        os.makedirs(MODELS, exist_ok=True)
        tex = f"incoming:block/weapon/{self.name}"
        with open(os.path.join(MODELS, f"{self.name}.mtl"), "w") as f:
            f.write(f"# {self.name}: paint and glowing parts (nozzle exit) — gen_models.py\n")
            f.write(f"newmtl paint\nKd 1 1 1 1\nmap_Kd {tex}\n\n")
            f.write(f"newmtl glow\nKd 1 1 1 1\nKa 1 1 1 1\nmap_Kd {tex}\n")
        stats = []
        for part, faces in self.parts.items():
            self.write_obj(f"{self.name}_{part}", f"{self.name}/{part}", faces, tex)
            stats.append(f"{part} {len(faces)}")
        if self.lod is not None:
            pts = np.array([p for faces in self.parts.values() for _, ps, _, _ in faces for p in ps])
            tol = LOD_TOL * float((pts.max(0) - pts.min(0)).max())
            for part, surfaces in self.lod.items():
                if not any(isinstance(s, Grid) for s in surfaces):
                    continue
                faces = [f for s in surfaces for f in (s.coarse(tol) if isinstance(s, Grid) else [s])]
                self.write_obj(f"{self.name}_{part}_lod1", f"{self.name}/{part}, simplified copy", faces, tex)
                stats.append(f"{part}_lod1 {len(faces)}")
        self.cv.save(os.path.join(TEXTURES, f"{self.name}.png"))
        print(f"{self.name}: {', '.join(stats)} faces; texture {self.cv.w}×{self.cv.h}")

    def write_obj(self, name, title, faces, tex):
        lines = [f"# {title} — generated by tools/gen_models.py, do not edit by hand", f"mtllib {self.name}.mtl"]
        vs, vts, vns, fs = [], [], [], []
        cur = None
        for mat, pts, uvs, ns in faces:
            if mat != cur:
                fs.append(f"usemtl {mat}")
                cur = mat
            ids = []
            for p, t, n in zip(pts, uvs, ns):
                vs.append(p)
                vts.append(t)
                vns.append(n)
                k = len(vs)
                ids.append(f"{k}/{k}/{k}")
            fs.append("f " + " ".join(ids))
        lines += [f"v {p[0] * self.scale:.4f} {p[1] * self.scale:.4f} {p[2] * self.scale:.4f}" for p in vs]
        lines += [f"vt {t[0]:.5f} {t[1]:.5f}" for t in vts]
        lines += [f"vn {n[0]:.4f} {n[1]:.4f} {n[2]:.4f}" for n in vns]
        lines += fs
        with open(os.path.join(MODELS, name + ".obj"), "w") as f:
            f.write("\n".join(lines) + "\n")
        with open(os.path.join(MODELS, name + ".json"), "w") as f:
            json.dump({"loader": "neoforge:obj", "model": f"incoming:models/weapon/{name}.obj", "flip_v": False,
                       "emissive_ambient": True, "automatic_culling": False,
                       "textures": {"particle": tex}}, f, indent=2)
            f.write("\n")


# =============================================================================== general painting techniques

def soot(region, v_from, k=0.55):
    """Soot to tail: darker from v_from to v = 1."""
    u, v = region.grid()
    m = np.clip((v - v_from) / max(1e-3, 1 - v_from), 0, 1) ** 1.5
    region.darken(m * (0.7 + 0.3 * smooth_noise(region.h, region.w, 12)), k)


def edge_wear(region, v_band, k=0.25, color=(170, 172, 170)):
    """Abrasion of the leading edge: light scratches on v ∈ [0, v_band] (and on the bottom - 0.5..0.5 + v_band)."""
    u, v = region.grid()
    vv = np.minimum(v, np.abs(v - 0.5))
    m = np.clip(1 - vv / v_band, 0, 1) * (smooth_noise(region.h, region.w, 6) > 0.05)
    region.mix(m, color, k)


def streaks(region, k=0.12, along_u=False):
    """Streaks of dirt along the stream."""
    h, w = region.h, region.w
    n = rng.random((1, w)) if not along_u else rng.random((h, 1))
    img = Image.fromarray((np.repeat(n, h, 0) if not along_u else np.repeat(n, w, 1)) * 255).convert("L")
    img = img.filter(ImageFilter.GaussianBlur(1.2))
    m = (np.asarray(img, float) / 255 - 0.5) * smooth_noise(h, w, 24)
    region.darken(np.clip(m * 4, 0, 1), k)


# ================================================================================= "Shahed-136"

def drone():
    m = Model("drone", 512, 768, scale=0.5)             # 3.6 m, span 2.6 m - like the real one
    body = m.cv.alloc(256, 320)
    prof = [(3.62, 0.0, 0.0), (3.58, 0.10, 0.10), (3.48, 0.20, 0.21), (3.3, 0.28, 0.30), (3.0, 0.33, 0.36),
            (2.6, 0.345, 0.375), (1.0, 0.35, 0.38), (-1.5, 0.35, 0.38), (-2.5, 0.33, 0.35), (-2.9, 0.27, 0.28),
            (-3.08, 0.22, 0.22), (-3.14, 0.16, 0.16), (-3.2, 0.10, 0.10), (-3.34, 0.07, 0.07), (-3.52, 0.0, 0.0)]
    zv = m.lathe("body", prof, body, seg=36)
    body.paint((48, 50, 53), mottle=0.07)
    body.lines_v([zv(3.0), zv(2.1), zv(0.6), zv(-1.1), zv(-2.5), zv(-3.08)], dark=0.45)
    body.lines_u([0.5], dark=0.3)
    body.rivets([zv(2.1) + 0.006, zv(0.6) + 0.006, zv(-1.1) + 0.006], step_px=5)
    body.rect(0.46, zv(2.05), 0.54, zv(1.55), (38, 40, 42), 0.8)          # warhead hatch
    body.lines_v([zv(2.05), zv(1.55)], dark=0.5, u0=0.46, u1=0.54)
    body.rect(0.0, zv(3.3), 1.0, zv(3.0), (60, 62, 64), 0.5)                # seeker fairing - different paint
    body.text("136-07", 0.31, zv(0.2), 3, (225, 225, 220), rot=True)
    body.text("136-07", 0.64, zv(0.2), 3, (225, 225, 220), rot=True)
    soot(body, zv(-2.3), 0.7)

    # wing: trimmed delta from the middle of the body to the tail; endings - keels
    wing = m.cv.alloc(256, 256)
    for side in (1, -1):
        m.wing("body", v3(0.25 * side, -0.12, 1.45), v3(side, 0, 0), v3(0, 0, -1), v3(0, 1, 0),
               span=2.3, root_chord=4.55, tip_chord=1.05, sweep=3.45, h_root=0.11, h_tip=0.045, region=wing, ns=8, nc=12)
    wing.paint((58, 60, 63), mottle=0.06)
    wing.lines_u([0.3, 0.62, 0.88], dark=0.25)
    wing.lines_v([0.36, 0.86], dark=0.35)                                      # elevons (top and bottom)
    edge_wear(wing, 0.03, 0.3)
    streaks(wing, 0.15)
    fin = m.cv.alloc(96, 96)
    for side in (1, -1):
        m.wing("body", v3(2.57 * side, -0.58, -1.95), v3(0, 1, 0), v3(0, 0, -1), v3(side, 0, 0),
               span=1.32, root_chord=1.12, tip_chord=0.78, sweep=0.34, h_root=0.05, h_tip=0.035, region=fin, ns=4, nc=8)
    fin.paint((56, 58, 61))
    fin.lines_v([0.4, 0.9], dark=0.3)
    edge_wear(fin, 0.05, 0.3)

    # propeller: two blades (rotates the renderer around the Z axis) and a blurred disk at high speeds
    blade = m.cv.alloc(64, 64)
    for side in (1, -1):
        m.wing("prop", v3(0.06 * side, 0, -3.36), v3(side, 0, 0), v3(0, -side, 0), v3(0, 0, 1),
               span=0.86, root_chord=0.2, tip_chord=0.11, sweep=0.04, h_root=0.03, h_tip=0.012, region=blade, ns=4, nc=6)
    blade.paint((32, 30, 28), mottle=0.1)
    disc = m.cv.alloc(128, 128)
    u, v = disc.grid()
    r = np.hypot(u - 0.5, v - 0.5) * 2
    disc.paint((40, 40, 40), mottle=0.0, grain=0.0)
    disc.alpha[...] = np.clip((1 - r) * 8, 0, 1) * np.clip((r - 0.12) * 8, 0, 1) * (0.28 + 0.18 * r) \
        * (0.8 + 0.2 * np.sin(np.arctan2(v - 0.5, u - 0.5) * 2) ** 2)
    m.plate("disc", [v3(0.95, 0.95, -3.37), v3(-0.95, 0.95, -3.37), v3(-0.95, -0.95, -3.37), v3(0.95, -0.95, -3.37)],
            disc, v3(0, 0, -1))
    # starting solid fuel booster under the tail (resets after acceleration)
    boost = m.cv.alloc(128, 160)
    bz = m.lathe("booster", [(-1.0, 0.0, 0.0), (-1.06, 0.12, 0.12), (-1.2, 0.17, 0.17), (-3.5, 0.17, 0.17),
                             (-3.62, 0.13, 0.13), (-3.8, 0.10, 0.10), (-3.86, 0.13, 0.13)],
                 boost, seg=16, center=(0.0, -0.58))
    boost.paint((70, 74, 60), mottle=0.08)
    boost.rect(0, bz(-1.5), 1, bz(-1.62), (200, 170, 40), 0.9)
    boost.lines_v([bz(-1.2), bz(-3.5)], dark=0.5)
    soot(boost, bz(-3.4), 0.8)
    m.box("booster", (-0.05, -0.44, -3.0), (0.05, -0.36, -1.4), boost)
    m.write()


# ============================================================================== Cruise missile

def missile():
    m = Model("missile", 512, 768, scale=0.5)         # "Tomahawk": 5.9 m + accelerator, Ø 0.52
    body = m.cv.alloc(256, 400)
    prof = [(5.92, 0.0, 0.0), (5.85, 0.14, 0.14), (5.6, 0.29, 0.29), (5.2, 0.40, 0.40), (4.6, 0.47, 0.47),
            (3.8, 0.5, 0.5), (-4.6, 0.5, 0.5), (-5.2, 0.47, 0.47), (-5.6, 0.40, 0.40), (-5.78, 0.33, 0.33),
            (-5.8, 0.24, 0.24), (-5.7, 0.2, 0.2)]
    zv = m.lathe("body", prof, body, seg=40)
    body.paint((168, 172, 174), mottle=0.05)
    body.rect(0, 0, 1, zv(4.4), (72, 74, 78), 1.0)                           # radio transparent radome
    body.lines_v([zv(4.4), zv(3.2), zv(1.4), zv(-0.3), zv(-2.4), zv(-4.3), zv(-5.2)], dark=0.4)
    body.rivets([zv(3.2) + 0.004, zv(-0.3) + 0.004, zv(-2.4) + 0.004], step_px=4)
    body.lines_u([0.5, 0.0], dark=0.2)
    body.rect(0.42, zv(1.3), 0.58, zv(0.5), (150, 154, 156), 0.9)           # wing gap
    body.lines_v([zv(1.3), zv(0.5)], dark=0.55, u0=0.42, u1=0.58)
    body.text("BGM 109", 0.23, zv(-0.6), 3, (40, 40, 40), rot=True)
    body.text("BGM 109", 0.70, zv(-0.6), 3, (40, 40, 40), rot=True)
    body.rect(0, zv(2.6), 1, zv(2.45), (190, 160, 40), 0.85)                 # yellow combat unit belt
    soot(body, zv(-4.8), 0.6)
    glow = m.cv.alloc(32, 32)
    glow.paint((255, 180, 90), mottle=0.1, grain=0.08)
    m.plate("body", [v3(0.2, 0.2, -5.7), v3(-0.2, 0.2, -5.7), v3(-0.2, -0.2, -5.7), v3(0.2, -0.2, -5.7)], glow,
            v3(0, 0, -1), material="glow", double=False)

    # wings: hinges (±0.28, 0.12, 1.0); in OBJ - opened, the renderer folds them along the body at the start
    wing = m.cv.alloc(256, 128)
    for side, part in ((1, "wing_l"), (-1, "wing_r")):
        m.wing(part, v3(0.42 * side, 0.12 * (1 if side > 0 else 0.6), 1.25), v3(side, 0, 0), v3(0, 0, -1), v3(0, 1, 0),
               span=2.2, root_chord=0.9, tip_chord=0.62, sweep=0.18, h_root=0.05, h_tip=0.03, region=wing, ns=6, nc=10)
    wing.paint((160, 164, 166), mottle=0.05)
    wing.lines_u([0.5], dark=0.2)
    edge_wear(wing, 0.04, 0.3, (200, 202, 200))

    # X-tail
    fin = m.cv.alloc(128, 96)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        S = v3(math.cos(a), math.sin(a), 0)
        Nn = v3(-math.sin(a), math.cos(a), 0)
        m.wing("body", S * 0.36 + v3(0, 0, -4.55), S, v3(0, 0, -1), Nn,
               span=1.05, root_chord=0.95, tip_chord=0.55, sweep=0.38, h_root=0.045, h_tip=0.025, region=fin, ns=4, nc=8)
    fin.paint((150, 154, 156))
    edge_wear(fin, 0.05, 0.3, (200, 202, 200))

    # ventral air intake: extends downwards (renderer moves along −Y)
    intake = m.cv.alloc(128, 96)
    m.lathe("intake", [(-1.7, 0.0, 0.0), (-1.72, 0.2, 0.15), (-1.9, 0.24, 0.2), (-3.0, 0.22, 0.18), (-3.35, 0.0, 0.0)],
            intake, seg=16, center=(0.0, -0.45))
    intake.paint((150, 154, 156))
    intake.rect(0, 0, 1, 0.07, (18, 18, 20), 1.0)
    # starting accelerator behind the nozzle
    boost = m.cv.alloc(160, 128)
    bz = m.lathe("booster", [(-5.72, 0.0, 0.0), (-5.74, 0.46, 0.46), (-6.5, 0.48, 0.48), (-6.62, 0.40, 0.40),
                             (-6.85, 0.34, 0.34), (-6.9, 0.38, 0.38)], boost, seg=24)
    boost.paint((60, 64, 58), mottle=0.07)
    boost.rect(0, bz(-5.9), 1, bz(-6.0), (210, 175, 40), 0.9)
    soot(boost, bz(-6.5), 0.8)
    m.write()


# ============================================================================ B-2

B2_SPAN = 26.2          # half-span
B2_NOSE = 10.5          # z nose
B2_TE = [(0.0, -6.2), (4.7, -3.2), (9.7, -6.5), (16.3, -2.4), (26.2, -8.7)]   # rear edge "saw" (x, z)


def b2_le(x):
    return B2_NOSE - 0.65 * abs(x)


def b2_te(x):
    xs, zs = zip(*B2_TE)
    return float(np.interp(abs(x), xs, zs))


def b2_half(x, c):
    """Half-thickness of the profile above (above the chord) and below: thick center section-fuselage, thin wing, humps of the cockpit and engine nacelles."""
    ax = abs(x)
    shape = 10 * (0.2969 * math.sqrt(c) - 0.126 * c - 0.3516 * c ** 2 + 0.2843 * c ** 3 - 0.1036 * c ** 4)
    top = shape * (1.9 * math.exp(-(ax / 4.2) ** 2) + 0.42 * (1 - 0.55 * ax / B2_SPAN))
    bot = shape * (0.75 * math.exp(-(ax / 4.5) ** 2) + 0.22 * (1 - 0.5 * ax / B2_SPAN))
    z = b2_le(x) + (b2_te(x) - b2_le(x)) * c
    cockpit = 0.45 * math.exp(-((ax / 1.6) ** 2) - ((z - 6.4) / 2.0) ** 2)
    nacelle = 0.5 * math.exp(-(((ax - 4.3) / 1.2) ** 2) - ((z - 0.2) / 3.6) ** 4)
    return top + cockpit + nacelle, bot


def bomber():
    m = Model("bomber", 1024, 1024)
    top = m.cv.alloc(1024, 400)
    bot = m.cv.alloc(1024, 400)
    xs = sorted(set([round(x, 3) for x in np.concatenate([
        np.linspace(-B2_SPAN, B2_SPAN, 61), [-x for x, _ in B2_TE], [x for x, _ in B2_TE],
        np.linspace(-6, 6, 25)])]))
    nc = 18
    cs = [(1 - math.cos(math.pi * i / nc)) / 2 for i in range(nc + 1)]
    zmin, zmax = -8.8, B2_NOSE

    def uvp(r, x, z):
        return r.uv((x + B2_SPAN) / (2 * B2_SPAN), (zmax - z) / (zmax - zmin))

    for side, r in ((1, top), (-1, bot)):
        P, UV = [], []
        for x in xs:
            row, uvr = [], []
            for c in cs:
                z = b2_le(x) + (b2_te(x) - b2_le(x)) * c
                ht, hb = b2_half(x, c)
                row.append(v3(x, ht if side > 0 else -hb, z))
                uvr.append(uvp(r, x, z))
            P.append(row)
            UV.append(uvr)
        m.grid("body", P, UV, lambda p, side=side: v3(0, side, 0.0001 * p[2]))
    for x in (-B2_SPAN, B2_SPAN):
        P = [[v3(x, b2_half(x, c)[0], b2_le(x) + (b2_te(x) - b2_le(x)) * c) for c in cs],
             [v3(x, -b2_half(x, c)[1], b2_le(x) + (b2_te(x) - b2_le(x)) * c) for c in cs]]
        UV = [[uvp(top, x, p[2]) for p in P[0]], [uvp(bot, x, p[2]) for p in P[1]]]
        m.grid("body", P, UV, lambda p, x=x: v3(math.copysign(1, x), 0, 0), smooth=False)

    # --- color: top - dark gray radio-absorbing, panels along lines parallel to the edges
    def paint_planform(r, color, lower):
        r.paint(color, mottle=0.05, grain=0.02, cell=96)
        img = Image.new("L", (r.w, r.h), 0)
        d = ImageDraw.Draw(img)

        def px(x, z):
            return ((x + B2_SPAN) / (2 * B2_SPAN) * (r.w - 1), (zmax - z) / (zmax - zmin) * (r.h - 1))

        def box(a, b, fill):
            d.rectangle([min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1])], fill)

        for off in (1.2, 2.6):                                              # along the leading edge
            d.line([px(-B2_SPAN + 0.1, b2_le(B2_SPAN) - off), px(0, B2_NOSE - off), px(B2_SPAN - 0.1, b2_le(B2_SPAN) - off)], 90, 1)
        for i in range(len(B2_TE) - 1):                                     # along the saw
            (x0, z0), (x1, z1) = B2_TE[i], B2_TE[i + 1]
            for s in (1, -1):
                d.line([px(s * x0, z0 + 0.9), px(s * x1, z1 + 0.9)], 90, 1)
                d.line([px(s * x0, z0 + 0.25), px(s * x1, z1 + 0.25)], 60, 1)
        for x in (2.2, 7.0, 12.0, 19.0):
            for s in (1, -1):
                d.line([px(s * x, b2_le(x) - 0.3), px(s * x, b2_te(x) + 0.3)], 55, 1)
        if not lower:
            # cabin: four glasses
            for a, b in ((-1.2, -0.35), (-0.3, 0.3), (0.35, 1.2)):
                d.polygon([px(a, 7.6), px(b, 7.6), px(b * 0.95, 6.9), px(a * 0.95, 6.9)], 255)
            # engine nacelle air intakes (geared) and exhaust - slots on the top surface
            for s in (1, -1):
                d.polygon([px(s * 3.0, 2.4), px(s * 3.5, 2.9), px(s * 4.3, 2.5), px(s * 5.1, 2.9), px(s * 5.7, 2.3),
                           px(s * 5.5, 1.9), px(s * 3.2, 1.9)], 255)
                d.polygon([px(s * 3.3, -3.6), px(s * 5.6, -3.0), px(s * 5.6, -3.5), px(s * 3.3, -4.2)], 200)
        else:
            # bomb bay doors (two compartments) - dark niches under the doors
            for s in (1, -1):
                box(px(s * 0.4, 3.1), px(s * 3.0, -1.6), 150)
            box(px(-0.5, 7.9), px(0.5, 6.3), 110)                 # front pillar niche
            for s in (1, -1):
                box(px(s * 6.0, 1.6), px(s * 7.6, -2.0), 110)     # niches of the main racks
        a = np.asarray(img, float) / 255
        glass = (a > 0.99).astype(float)
        vent = ((a > 0.7) & (a < 0.9)).astype(float)
        bay = ((a > 0.5) & (a < 0.7)).astype(float)
        wells = ((a > 0.38) & (a < 0.5)).astype(float)
        lines = ((a > 0.15) & (a < 0.38)).astype(float)
        r.darken(lines, 0.3)
        r.darken(wells, 0.2)
        r.darken(bay, 0.12)
        if not lower:
            r.mix(glass, (22, 28, 36), 1)
            u, v = r.grid()
            r.mix(glass * np.clip(1 - v * 2.5, 0, 1) * 0.6, (120, 130, 150), 0.5)   # glare on the glass
            r.mix(vent, (14, 14, 16), 1)
        streaks(r, 0.08)

    paint_planform(top, (58, 60, 64), False)
    paint_planform(bot, (64, 66, 70), True)
    # soot behind the exhaust on the upper surface
    u, v = top.grid()
    x = u * 2 * B2_SPAN - B2_SPAN
    z = zmax - v * (zmax - zmin)
    top.darken(np.exp(-((np.abs(x) - 4.4) / 1.3) ** 2) * np.clip((-3.4 - z) / 3, 0, 1), 0.45)

    # bomb bay doors: 4 plates, hinges on the outer edges of the compartments (x = ±0.4 and ±3.0, y ≈ bottom)
    door = m.cv.alloc(96, 160)
    door.paint((66, 68, 72), mottle=0.05)
    door.lines_u([0.02, 0.98], dark=0.5)
    door.lines_v([0.02, 0.98], dark=0.5)
    door.rivets([0.08, 0.92], step_px=5)
    for s in (1, -1):
        for part, xa, xb in ((f"door_{'l' if s > 0 else 'r'}_in", 0.4, 1.7), (f"door_{'l' if s > 0 else 'r'}_out", 1.7, 3.0)):
            # wing hinges: WeaponModels.DOOR_HINGE_* (x = ±0.4 internal, ±3.0 external; y - bottom of the wing there)
            ya = -b2_half(xa, 0.45)[1] - 0.04
            yb = -b2_half(xb, 0.45)[1] - 0.04
            m.plate(part, [v3(s * xa, ya, 3.1), v3(s * xb, yb, 3.1), v3(s * xb, yb, -1.6), v3(s * xa, ya, -1.6)],
                    door, v3(0, -1, 0))
    m.write()


# =============================================================================== Concrete bomb

def bomb():
    m = Model("bomb", 512, 256, scale=2 / 3)            # GBU-57: 6.2 m, Ø 0.8
    body = m.cv.alloc(256, 240)
    prof = [(4.65, 0.0, 0.0), (4.6, 0.12, 0.12), (4.3, 0.3, 0.3), (3.8, 0.47, 0.47), (3.1, 0.58, 0.58),
            (2.4, 0.61, 0.61), (-3.0, 0.61, 0.61), (-3.9, 0.52, 0.52), (-4.4, 0.4, 0.4), (-4.62, 0.3, 0.3), (-4.65, 0.0, 0.0)]
    zv = m.lathe("body", prof, body, seg=32)
    body.paint((92, 98, 76), mottle=0.07)
    body.rect(0, zv(2.5), 1, zv(2.25), (214, 178, 38), 0.95)                 # yellow stripe - equipped
    body.lines_v([zv(3.1), zv(0.4), zv(-2.2), zv(-3.9)], dark=0.4)
    body.lines_u([0.5], dark=0.25)
    body.rect(0.47, zv(0.9), 0.53, zv(-0.3), (60, 60, 58), 0.9)            # hanging ears
    body.text("GBU-57", 0.2, zv(1.8), 3, (220, 220, 210), rot=True)
    body.text("GBU-57", 0.72, zv(1.8), 3, (220, 220, 210), rot=True)
    streaks(body, 0.1)
    strake = m.cv.alloc(96, 64)
    grid = m.cv.alloc(96, 96)
    for k in range(4):
        a = k * math.pi / 2
        S = v3(math.cos(a), math.sin(a), 0)
        Nn = v3(-math.sin(a), math.cos(a), 0)
        m.wing("body", S * 0.55 + v3(0, 0, -0.2), S, v3(0, 0, -1), Nn, span=0.4, root_chord=1.8, tip_chord=1.4,
               sweep=0.3, h_root=0.03, h_tip=0.02, region=strake, ns=2, nc=6, flat=True)
        # lattice steering wheel: frame and grille - a plate with cutouts, slightly spaced from the body
        a2 = a + math.pi / 4
        S2 = v3(math.cos(a2), math.sin(a2), 0)
        T2 = v3(-math.sin(a2), math.cos(a2), 0)
        c0 = S2 * 0.42 + v3(0, 0, -4.1)
        m.plate("body", [c0 + T2 * 0.55, c0 + T2 * 0.55 + S2 * 1.0, c0 - T2 * 0.55 + S2 * 1.0, c0 - T2 * 0.55],
                grid, v3(0, 0, 1))
        m.plate("body", [c0 + T2 * 0.55 + v3(0, 0, -0.5), c0 + T2 * 0.55 + S2 * 1.0 + v3(0, 0, -0.5),
                         c0 + T2 * 0.55 + S2 * 1.0, c0 + T2 * 0.55], strake, T2)
        m.plate("body", [c0 - T2 * 0.55 + v3(0, 0, -0.5), c0 - T2 * 0.55 + S2 * 1.0 + v3(0, 0, -0.5),
                         c0 - T2 * 0.55 + S2 * 1.0, c0 - T2 * 0.55], strake, -T2)
        m.plate("body", [c0 + S2 * 1.0 + T2 * 0.55, c0 + S2 * 1.0 - T2 * 0.55, c0 + S2 * 1.0 - T2 * 0.55 + v3(0, 0, -0.5),
                         c0 + S2 * 1.0 + T2 * 0.55 + v3(0, 0, -0.5)], strake, S2)
    strake.paint((80, 86, 66))
    grid.paint((70, 74, 60))
    u, v = grid.grid()
    cell = ((np.abs(((u * 6) % 1) - 0.5) < 0.38) & (np.abs(((v * 6) % 1) - 0.5) < 0.38)
            & (u > 0.06) & (u < 0.94) & (v > 0.06) & (v < 0.94))
    ang = (((u + v) * 6) % 1 < 0.5)
    grid.alpha[...] = np.where(cell & ang, 0.0, 1.0)
    m.write()


# ================================================================================ ICBM

def icbm():
    m = Model("icbm", 512, 512)
    body = m.cv.alloc(320, 448)
    prof = [(9.2, 0.0, 0.0), (9.1, 0.12, 0.12), (8.6, 0.3, 0.3), (7.6, 0.5, 0.5), (6.6, 0.64, 0.64), (6.0, 0.7, 0.7),
            (3.7, 0.7, 0.7), (3.55, 0.8, 0.8), (-1.3, 0.8, 0.8), (-1.45, 0.9, 0.9), (-9.0, 0.9, 0.9), (-9.1, 0.84, 0.84),
            (-9.2, 0.0, 0.0)]
    zv = m.lathe("body", prof, body, seg=40)
    body.paint((226, 226, 222), mottle=0.04, grain=0.02)
    body.rect(0, zv(3.72), 1, zv(3.5), (24, 24, 26), 1)
    body.rect(0, zv(-1.28), 1, zv(-1.5), (24, 24, 26), 1)
    body.rect(0, zv(-8.1), 1, zv(-8.3), (24, 24, 26), 1)
    body.rect(0, 0, 1, zv(8.1), (80, 82, 84), 1)                            # fairing tip
    for k in range(4):                                                      # “checkers” for film theodolites
        body.rect(k / 4, zv(5.6), k / 4 + 0.125, zv(4.6), (24, 24, 26), 1)
        body.rect(k / 4 + 0.125, zv(1.2), k / 4 + 0.25, zv(0.2), (24, 24, 26), 1)
    body.lines_v([zv(6.0), zv(2.0), zv(-4.0), zv(-6.5)], dark=0.18)
    body.text("UNITED STATES", 0.44, zv(-2.0), 3, (30, 30, 34), rot=True)
    soot(body, zv(-8.5), 0.5)
    nz = m.cv.alloc(96, 96)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        cx, cy = 0.45 * math.cos(a), 0.45 * math.sin(a)
        m.lathe("body", [(-9.15, 0.12, 0.12), (-9.35, 0.16, 0.16), (-9.7, 0.3, 0.3), (-9.72, 0.28, 0.28)],
                nz, seg=14, center=(cx, cy))
    nz.paint((50, 48, 46), mottle=0.1)
    soot(nz, 0.3, 0.6)
    glow = m.cv.alloc(32, 32)
    glow.paint((255, 190, 110), mottle=0.1, grain=0.08)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        cx, cy = 0.45 * math.cos(a), 0.45 * math.sin(a)
        m.plate("body", [v3(cx + 0.26, cy + 0.26, -9.66), v3(cx - 0.26, cy + 0.26, -9.66), v3(cx - 0.26, cy - 0.26, -9.66),
                         v3(cx + 0.26, cy - 0.26, -9.66)], glow, v3(0, 0, -1), material="glow", double=False)
    m.write()

# =============================================================================== MLRS "Grad"

def rocket():
    """9M22 rocket (122 mm, 2.9 m; caliber slightly exaggerated, otherwise invisible in flight)."""
    m = Model("rocket", 256, 256)
    body = m.cv.alloc(160, 240)
    prof = [(1.45, 0.0, 0.0), (1.42, 0.02, 0.02), (1.25, 0.05, 0.05), (1.0, 0.085, 0.085), (0.85, 0.1, 0.1),
            (-1.38, 0.1, 0.1), (-1.44, 0.085, 0.085), (-1.5, 0.07, 0.07)]
    zv = m.lathe("body", prof, body, seg=16)
    body.paint((86, 94, 70), mottle=0.06)
    body.rect(0, 0, 1, zv(0.85), (118, 122, 112), 1)                        # head part - gray
    body.rect(0, zv(0.8), 1, zv(0.72), (214, 178, 38), 0.95)                # yellow stripe - equipped
    body.lines_v([zv(0.85), zv(0.2), zv(-0.9)], dark=0.45)
    body.text("9M22", 0.38, zv(-0.1), 2, (225, 225, 215), rot=True)
    soot(body, zv(-1.2), 0.7)
    fin = m.cv.alloc(48, 32)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        S = v3(math.cos(a), math.sin(a), 0)
        Nn = v3(-math.sin(a), math.cos(a), 0)
        # folding stabilizer feathers: open in flight, the projectile is not visible in the tube
        m.wing("body", S * 0.09 + v3(0, 0, -1.08), S, v3(0, 0, -1), Nn, span=0.13, root_chord=0.34, tip_chord=0.3,
               sweep=0.02, h_root=0.012, h_tip=0.01, region=fin, ns=1, nc=4, flat=True)
    fin.paint((70, 76, 58))
    glow = m.cv.alloc(16, 16)
    glow.paint((255, 196, 120), mottle=0.1, grain=0.08)
    m.plate("body", [v3(0.06, 0.06, -1.49), v3(-0.06, 0.06, -1.49), v3(-0.06, -0.06, -1.49), v3(0.06, -0.06, -1.49)],
            glow, v3(0, 0, -1), material="glow", double=False)
    m.write()


# package: 4 series for 10 pipes - the same numbers as LauncherEntity.ROCKET_COLUMNS/ROWS, TUBE_PITCH, TUBE_LENGTH
RACK_COLS, RACK_ROWS, TUBE_PITCH, TUBE_LENGTH = 10, 4, 0.3, 3.2


def rocket_rack():
    """Package of 40 pipes: open trunks (dark throat inside), couplers and frame. The nose of the shells is +Z."""
    m = Model("rocket_rack", 512, 512, lod=False)
    tube = m.cv.alloc(96, 256)
    bore = m.cv.alloc(32, 128)
    frame = m.cv.alloc(128, 64)
    r_out, r_in = TUBE_PITCH * 0.46, TUBE_PITCH * 0.38
    for row in range(RACK_ROWS):
        for col in range(RACK_COLS):
            x = (RACK_COLS - 1) * TUBE_PITCH / 2 - col * TUBE_PITCH
            y = 0.3 + row * TUBE_PITCH
            m.lathe("body", [(TUBE_LENGTH, r_out, r_out), (0.0, r_out, r_out)], tube, seg=10, center=(x, y))
            # inner wall: the same cylinder, normals inward (face to axis)
            P, UV = [], []
            for k in range(11):
                a = -math.pi / 2 + 2 * math.pi * k / 10
                P.append([v3(x + r_in * math.cos(a), y + r_in * math.sin(a), z) for z in (TUBE_LENGTH, 0.0)])
                UV.append([bore.uv(k / 10, 0), bore.uv(k / 10, 1)])
            m.grid("body", P, UV, lambda p, x=x, y=y: v3(x - p[0], y - p[1], 0))
            # ring ends front and back
            for z, n in ((TUBE_LENGTH, 1), (0.0, -1)):
                for k in range(10):
                    a0, a1 = -math.pi / 2 + 2 * math.pi * k / 10, -math.pi / 2 + 2 * math.pi * (k + 1) / 10
                    pts = [v3(x + r_out * math.cos(a0), y + r_out * math.sin(a0), z), v3(x + r_out * math.cos(a1), y + r_out * math.sin(a1), z),
                           v3(x + r_in * math.cos(a1), y + r_in * math.sin(a1), z), v3(x + r_in * math.cos(a0), y + r_in * math.sin(a0), z)]
                    m.plate("body", pts, frame, v3(0, 0, n), double=False)
    w = RACK_COLS * TUBE_PITCH + 0.1
    h = RACK_ROWS * TUBE_PITCH + 0.1
    x0, y0 = -w / 2, 0.3 - TUBE_PITCH / 2 - 0.05
    for z in (0.15, TUBE_LENGTH / 2 - 0.1, TUBE_LENGTH - 0.45):
        m.box("body", (x0, y0 - 0.08, z), (x0 + w, y0, z + 0.24), frame)
        m.box("body", (x0, y0 + h, z), (x0 + w, y0 + h + 0.08, z + 0.24), frame)
        m.box("body", (x0 - 0.08, y0 - 0.08, z), (x0, y0 + h + 0.08, z + 0.24), frame)
        m.box("body", (x0 + w, y0 - 0.08, z), (x0 + w + 0.08, y0 + h + 0.08, z + 0.24), frame)
    # longitudinal beams at the bottom - the package lies on them on the cradle
    for bx in (-0.9, 0.9):
        m.box("body", (bx - 0.07, y0 - 0.22, 0.0), (bx + 0.07, y0 - 0.08, TUBE_LENGTH), frame)
    tube.paint((78, 88, 62), mottle=0.08)
    tube.lines_v([0.03, 0.97], dark=0.4)
    soot(tube, 0.75, 0.6)                                                   # rear sections covered in soot from starts
    streaks(tube, 0.1)
    bore.paint((26, 26, 26), mottle=0.1)
    frame.paint((66, 74, 52), mottle=0.07)
    edge_wear(frame, 0.08, 0.2)
    m.write()


# =================================================================================== "Lancet"

def loiter():
    """"Lancet-3": 1,65 m (Wikipedia "ZALA Lancet"); the geometry below is in one and a half units. Nose at +Z."""
    m = Model("loiter", 256, 384, scale=1 / 1.5)
    body = m.cv.alloc(128, 192)
    prof = [(1.25, 0.0, 0.0), (1.22, 0.06, 0.06), (1.14, 0.11, 0.11), (1.0, 0.14, 0.14), (0.8, 0.15, 0.15),
            (-0.95, 0.15, 0.15), (-1.12, 0.12, 0.12), (-1.2, 0.07, 0.07), (-1.24, 0.0, 0.0)]
    zv = m.lathe("body", prof, body, seg=20)
    body.paint((128, 134, 124), mottle=0.06)
    body.rect(0, 0, 1, zv(1.1), (22, 26, 32), 1)                            # optical head glass
    body.rect(0.0, zv(1.1), 1.0, zv(1.03), (60, 62, 60), 0.9)
    body.lines_v([zv(0.8), zv(0.1), zv(-0.6), zv(-0.95)], dark=0.45)
    body.rect(0.44, zv(0.5), 0.56, zv(0.3), (90, 94, 88), 0.8)              # warhead hatch
    body.text("Z-35", 0.36, zv(-0.2), 2, (40, 40, 38), rot=True)
    soot(body, zv(-1.0), 0.4)
    # two wing crosses: front and rear, rotated to 45° (like the letter X, when viewed from the tail)
    wing = m.cv.alloc(96, 96)
    for z_le, span, chord, tip in ((0.62, 0.78, 0.36, 0.2), (-0.5, 0.72, 0.42, 0.24)):
        for k in range(4):
            a = math.pi / 4 + k * math.pi / 2
            S = v3(math.cos(a), math.sin(a), 0)
            Nn = v3(-math.sin(a), math.cos(a), 0)
            m.wing("wings", S * 0.13 + v3(0, 0, z_le), S, v3(0, 0, -1), Nn, span=span, root_chord=chord, tip_chord=tip,
                   sweep=0.1, h_root=0.02, h_tip=0.012, region=wing, ns=3, nc=6)
    wing.paint((120, 126, 116), mottle=0.05)
    wing.lines_u([0.5], dark=0.2)
    edge_wear(wing, 0.05, 0.25)
    # a push screw behind the tail (turns the renderer around the Z axis) and a blurred disk
    blade = m.cv.alloc(32, 32)
    for side in (1, -1):
        m.wing("prop", v3(0.03 * side, 0, -1.27), v3(side, 0, 0), v3(0, -side, 0), v3(0, 0, 1),
               span=0.32, root_chord=0.1, tip_chord=0.06, sweep=0.02, h_root=0.015, h_tip=0.008, region=blade, ns=3, nc=4)
    blade.paint((30, 30, 30), mottle=0.1)
    disc = m.cv.alloc(64, 64)
    u, v = disc.grid()
    r = np.hypot(u - 0.5, v - 0.5) * 2
    disc.paint((40, 40, 40), mottle=0.0, grain=0.0)
    disc.alpha[...] = np.clip((1 - r) * 8, 0, 1) * np.clip((r - 0.15) * 8, 0, 1) * (0.25 + 0.2 * r)
    m.plate("disc", [v3(0.36, 0.36, -1.28), v3(-0.36, 0.36, -1.28), v3(-0.36, -0.36, -1.28), v3(0.36, -0.36, -1.28)],
            disc, v3(0, 0, -1))
    m.write()


def interceptor():
    """
    SAM interceptor missile (4 m, caliber 0.26, like 9M96, slightly thicker, otherwise invisible in flight). Half-length
is 2.0, also InterceptorSpec.SAM.noseLength (nozzle exit: plume in InterceptorRenderer). Nose along +Z.
    """
    m = Model("interceptor", 256, 256, lod=False)
    body = m.cv.alloc(120, 224)
    prof = [(2.0, 0.0, 0.0), (1.95, 0.035, 0.035), (1.82, 0.075, 0.075), (1.6, 0.11, 0.11), (1.35, 0.13, 0.13),
            (-1.9, 0.13, 0.13), (-1.97, 0.115, 0.115), (-2.0, 0.095, 0.095)]
    zv = m.lathe("body", prof, body, seg=16)
    body.paint((204, 206, 198), mottle=0.04)
    body.rect(0, 0, 1, zv(1.6), (66, 68, 66), 1)                            # radio-transparent head fairing
    body.rect(0, zv(0.95), 1, zv(0.85), (214, 178, 38), 0.95)               # yellow stripe - warhead
    body.rect(0, zv(-0.2), 1, zv(-0.3), (120, 80, 50), 0.9)                 # brown - engine
    body.lines_v([zv(1.35), zv(0.5), zv(-0.9), zv(-1.6)], dark=0.4)
    body.text("9M96", 0.38, zv(0.2), 2, (40, 40, 38), rot=True)
    soot(body, zv(-1.6), 0.6)
    fin = m.cv.alloc(48, 32)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        S = v3(math.cos(a), math.sin(a), 0)
        Nn = v3(-math.sin(a), math.cos(a), 0)
        # rudders at the head and stabilizers at the tail - with a cross “X”, like a rocket in a container
        m.wing("body", S * 0.12 + v3(0, 0, 0.6), S, v3(0, 0, -1), Nn, span=0.16, root_chord=0.42, tip_chord=0.2,
               sweep=0.16, h_root=0.012, h_tip=0.008, region=fin, ns=1, nc=4, flat=True)
        m.wing("body", S * 0.12 + v3(0, 0, -1.42), S, v3(0, 0, -1), Nn, span=0.26, root_chord=0.46, tip_chord=0.22,
               sweep=0.18, h_root=0.014, h_tip=0.01, region=fin, ns=1, nc=4, flat=True)
    fin.paint((188, 190, 184))
    glow = m.cv.alloc(16, 16)
    glow.paint((255, 196, 120), mottle=0.1, grain=0.08)
    m.plate("body", [v3(0.085, 0.085, -1.99), v3(-0.085, 0.085, -1.99), v3(-0.085, -0.085, -1.99), v3(0.085, -0.085, -1.99)],
            glow, v3(0, 0, -1), material="glow", double=False)
    m.write()


# =============================================================================== SAM

# sAM package: four containers 2×2 above the cabin - same numbers as SamBlockEntity.CANISTER_OFFSET, PACK_FLOOR, CANISTER_LENGTH
SAM_OFFSET, SAM_FLOOR, SAM_CANISTER = 0.33, 1.1, 4.3


def sam():
    """
    SAM launcher above its block (block is the 1 × 1 × 1 m radar cab, block model): base plate and a rack of four
transport-launch containers in a 2×2 layout, vertical like 9M96 on S-350 and S-400: square tubes 0.62 × 0.62 × 4.3 m
(missile is 4 m, X-tail fits in the square), covers on top, rack held by three bands. Origin is the block bottom
center, +Y up, +Z forward (block FACING).
    """
    m = Model("sam", 256, 256, lod=False)
    side = m.cv.alloc(64, 240)
    frame = m.cv.alloc(64, 32)
    lid = m.cv.alloc(32, 32)
    half, top = 0.31, SAM_FLOOR + SAM_CANISTER
    m.box("pack", (-0.72, 1.0, -0.72), (0.72, SAM_FLOOR, 0.72), frame)
    for y in (1.55, 3.2, 4.85):
        m.box("pack", (-0.67, y, -0.67), (0.67, y + 0.16, 0.67), frame)
    up = v3(0, 1, 0)
    for cx in (-SAM_OFFSET, SAM_OFFSET):
        for cz in (-SAM_OFFSET, SAM_OFFSET):
            # sides: u - from left to right, when viewed from the outside, v - from top to bottom (the inscription is readable)
            for n in (v3(1, 0, 0), v3(-1, 0, 0), v3(0, 0, 1), v3(0, 0, -1)):
                r = np.cross(-n, up)
                c = v3(cx, 0, cz) + n * half
                m.plate("pack", [c - r * half + up * top, c + r * half + up * top, c + r * half + up * SAM_FLOOR,
                                 c - r * half + up * SAM_FLOOR], side, n, double=False)
            m.plate("pack", [v3(cx - half, top, cz - half), v3(cx + half, top, cz - half), v3(cx + half, top, cz + half),
                             v3(cx - half, top, cz + half)], frame, up, double=False)
            m.box("pack", (cx - 0.27, top, cz - 0.27), (cx + 0.27, top + 0.03, cz + 0.27), lid)
    side.paint((86, 96, 66), mottle=0.07)
    # stiffening ribs between the bands, a stencil and a strip “equipped” under the upper band
    side.lines_v([(top - y) / SAM_CANISTER for y in (4.0, 2.4)], dark=0.3)
    side.text("9M96", 0.25, 0.2, 2, (225, 225, 215))
    side.rect(0.25, 0.27, 0.75, 0.285, (214, 178, 38), 0.95)
    streaks(side, 0.12)
    frame.paint((62, 70, 50), mottle=0.06)
    edge_wear(frame, 0.12, 0.2)
    lid.paint((112, 118, 98), mottle=0.04)
    u, v = lid.grid()
    rr = np.hypot(u - 0.5, v - 0.5) * 2
    lid.darken(((rr > 0.72) & (rr < 0.86)) | ((rr < 0.72) & ((np.abs(u - 0.5) < 0.04) | (np.abs(v - 0.5) < 0.04))), 0.4)
    m.write()


if __name__ == "__main__":
    drone()
    missile()
    bomber()
    bomb()
    icbm()
    rocket()
    rocket_rack()
    loiter()
    interceptor()
    sam()
