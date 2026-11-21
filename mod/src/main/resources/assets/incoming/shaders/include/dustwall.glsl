// Young nuclear dust wall (client/fx/layer/DustWallPass): from the front to ~10 s behind it, one presentation model
// (presentation, not physical): its own birth at the contact line, rise, small uneven hump, settle, billows and light.
// Physics enters only as inputs the screen shows (DustWallPass.Frame): where the front is and when it passed a radius
// (DwTable: the mod's in-game front), how much dust a radius lofts and the settled layer height there (the pall's
// own layer at the handoff, so the old pall takes over without a seam), the world wind, the fireball and sun light.
// The model owns everything else: hump 1.3–1.55x the layer, settles in about 1 s, billows 15–100 m fixed in the air mass.
// Shared verbatim by the mod's pass (fx_dustwall.fsh) and the shader harness (tools/dustwall_harness.py).
//
// Positions are relative to ground zero's ground point, blocks (= metres); the eye is DwEye from there.

uniform sampler3D DwNoise;
// (eye x, y, z from ground zero's ground point; seconds since the burst on the detonation clock)
uniform vec4 DwEye;
// (front radius now; inner radius of the young wall: the front DW_SPAN seconds ago; envelope top, blocks; table reach)
uniform vec4 DwRing;
// table (DW_NODES × 1, RGBA32F): node i at radius i / (DW_NODES − 1) × reach: (front arrival, s since burst; settled layer
// height, blocks; lofted dust extinction at the ground, 1/block; 0)
uniform sampler2D Sampler2;
// key light (fireball) direction from ground zero's sky point is per sample; (fireball centre from ground zero, radius)
uniform vec4 DwBall;
// light colours (linear, frame units): fireball at the wall, sun, sky from above, ground bounce
uniform vec3 DwBallLight;
uniform vec3 DwSunLight;
uniform vec3 DwSunDir;
uniform vec3 DwSkyLight;
uniform vec3 DwGroundLight;
// dust single-scattering albedo (the pall's dust colour at this soil)
uniform vec3 DwAlbedo;
// (wind x, z, m/s; seed offset of the noise; 0)
uniform vec4 DwWind;

const int DW_NODES = 32;
// seconds of wall behind the front this model draws; the pall draws what is older (handoff over the last DW_FADE s)
const float DW_SPAN = 10.0;
const float DW_FADE = 2.5;
// presentation exposure of the wall's light (Silo S3E09 dust band: display luma median 0.21–0.26)
const float DW_EXPOSURE = 0.2;

// pixel footprint at the sample, blocks (set by the march)
float gDwFoot = 0.0;

vec4 dwNode(float r) {
    float x = clamp(r / max(DwRing.w, 1.0), 0.0, 1.0) * float(DW_NODES - 1);
    int i = int(min(floor(x), float(DW_NODES - 2)));
    return mix(texelFetch(Sampler2, ivec2(i, 0), 0), texelFetch(Sampler2, ivec2(i + 1, 0), 0), x - float(i));
}

// non-repeating smooth value noise in 2D (large shapes: the 64-voxel tile would repeat every tile, a visible lattice
// at 150–300 m), two octaves, 0..1
float dwHash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}
float dwValue(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(dwHash(i), dwHash(i + vec2(1.0, 0.0)), f.x), mix(dwHash(i + vec2(0.0, 1.0)), dwHash(i + vec2(1.0, 1.0)), f.x), f.y);
}
// value noise from DwNoise, a 128³ lattice of random values (DustWallPass.lattice), one trilinear fetch at the smoothstep-
// warped coordinate: the same C1 value noise as eight hashed corners, at an eighth of the cost; its period, 128 lattice
// cells (1.7 km at the finest 13 m octave), is far beyond any visible repeat
const float DW_LATTICE = 128.0;
float dwNoise3(vec3 x) {
    vec3 i = floor(x), f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return textureLod(DwNoise, (i + f + 0.5) / DW_LATTICE, 0.0).r;
}
float dwLarge(vec2 xz, float scale, float k) {
    vec2 p = xz / scale + k * 17.0;
    return 0.667 * dwNoise3(vec3(p, k * 53.0)) + 0.333 * dwNoise3(vec3(mat2(0.8, -0.6, 0.6, 0.8) * p * 2.03 + 5.1, k * 53.0 + 7.0));
}
// the tile sampled in a rotated frame: tiles at different scales and rotations do not line up, so no lattice shows
const mat3 DW_ROT_A = mat3(0.8, 0.0, 0.6, 0.0, 1.0, 0.0, -0.6, 0.0, 0.8);
const mat3 DW_ROT_B = mat3(0.28, 0.0, -0.96, 0.0, 1.0, 0.0, 0.96, 0.0, 0.28);

// visible share of an octave of frequency f (1/block) at this footprint: detail finer than a pixel fades to its mean
float dwLod(float f) {
    return 1.0 - smoothstep(0.35, 0.8, f * gDwFoot);
}

// the top of the young wall at a point of age a (s since the front passed it), settled layer height h, blocks
// (presentation): born at zero at the contact line, rises with 0.22 s, a hump of hump × the layer that holds 0.3 s and
// settles with e-fold 0.33 s (about 1 s to the layer)
float dwTop(float a, float h, float hump) {
    float rise = 1.0 - exp(-max(a, 0.0) / 0.22);
    float over = exp(-max(a - 0.3, 0.0) / 0.33);
    return h * rise * (1.0 + (hump - 1.0) * over);
}

// air displacement of the lofted mass since the front passed (presentation): carried outward by the post-shock wind,
// then slowly back, plus large eddies turning with the decaying wind energy; the texture is fixed in this air
vec3 dwAir(vec3 q, float a, float r) {
    vec2 out2 = q.xz / max(r, 1.0);
    float carried = 22.0 * (1.0 - exp(-a / 0.6)) - 8.0 * (1.0 - exp(-a / 4.0));
    float E = 1.0 - exp(-a / 5.0);
    float ph = 0.18 * (6.0 * E + 0.15 * a);
    vec3 b = DW_ROT_B * q / vec3(113.0, 47.0, 113.0);
    vec3 w = vec3(texture(DwNoise, b + vec3(0.0, ph, 0.0)).r, texture(DwNoise, b + vec3(0.37, 0.61 - ph, 0.23)).r,
            texture(DwNoise, b + vec3(0.71, 0.13, ph)).r) - 0.5;
    float amp = min(a / 1.5, 1.0) * (0.6 + 0.4 * exp(-a / 6.0));
    vec3 p = q - vec3(out2.x, 0.0, out2.y) * carried + w * vec3(60.0, 26.0, 60.0) * amp;
    // world wind drifts the whole mass once lofted
    p.xz -= DwWind.xy * a;
    return p;
}

// value noise and fBm without a tile (hash lattice): no repeat anywhere on the ground, unlike a 3D noise tile
float dwH3(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
// fBm of four octaves; octaves finer than the pixel footprint fade to their mean (fq: first octave frequency, 1/block)
float dwFbm(vec3 p, float fq) {
    float a = 0.5, s = 0.0, fr = fq;
    for (int i = 0; i < 4; i++) {
        s += a * mix(0.5, dwNoise3(p), dwLod(fr));
        p = p * 2.07 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
        fr *= 2.07;
    }
    return s;
}
float dwFbm2(vec3 p, float fq) {
    return 0.6667 * (0.5 * mix(0.5, dwNoise3(p), dwLod(fq)) + 0.25 * mix(0.5, dwNoise3(p * 2.07 + vec3(1.7, 9.2, 3.1)), dwLod(fq * 2.07))) + 0.1667;
}

// heights of the dense core and of the fines haze over it for dust of age a with settled layer height h, blocks
// (presentation): born at the contact line and torn up within ~0.4 s (rise e-fold 0.18 s), overshooting into a hump of
// hump × the layer that holds to 0.45 s and settles with e-fold 0.3 s (about a second to the layer); the haze of fines
// stays and rises slowly over the hot ground
void dwTops(float a, float h, float hump, out float core, out float haze) {
    float up = h * (1.0 - exp(-max(a, 0.0) / 0.18));
    core = max(up * (1.0 + (hump - 1.0) * exp(-max(a - 0.45, 0.0) / 0.3)), 0.5);
    haze = up * (1.0 + 0.2 * (1.0 - exp(-a / 12.0)));
}

// rising wisps out of the layer: half of the 60 m cells release one 0.6–9 s after the front, 3–7 m/s, fading over ~14 s
float dwWisps(vec3 p, float top) {
    vec2 cell = floor(p.xz / 60.0);
    float h1 = dwH3(vec3(cell, 1.0)), h2 = dwH3(vec3(cell, 2.0)), h3 = dwH3(vec3(cell, 3.0)), h4 = dwH3(vec3(cell, 4.0));
    if (h1 > 0.5) return 0.0;
    vec2 c = (cell + 0.5) * 60.0 + (vec2(h2, h3) - 0.5) * 28.0;
    float tb = DwEye.w - dwNode(length(c)).x - (0.6 + h4 * 8.0);
    if (tb < 0.0) return 0.0;
    float dxz = length(p.xz - c);
    float w = 3.0 + 4.0 * h1;
    float dz = w * 5.0 * (1.0 - exp(-tb / 5.0));
    float yl = top * 0.7 + dz, Rl = 7.0 + 0.3 * dz + 1.2 * tb;
    return (40.0 / (Rl * Rl) + 0.12) * smoothstep(1.0, 0.0, length(vec2(dxz, (p.y - yl) * 1.1)) / Rl) * smoothstep(0.0, 1.2, tb) * exp(-tb / 14.0);
}

// highest the dust can reach above a point (blocks): the hump over the most uneven layer, its fray and wisps; below 0 where
// no young dust is
float dwBound(vec3 q) {
    float r = length(q.xz);
    if (r > DwRing.x + 2.0 || r < DwRing.y - 50.0) return -1.0;
    vec4 n0 = dwNode(r);
    if (DwEye.w < n0.x || n0.z <= 0.0) return -1.0;
    // the density ends 1.6 tops above the ground (dwDensity: yn0), the top at most the 1.55 hump; plus rising wisps
    return n0.y * 1.55 * 1.6 + 40.0;
}

// dust extinction (1/block) at q; coarse: light paths (no wisps, no streaks, no fine octaves)
float dwDensity(vec3 q, bool coarse) {
    float r = length(q.xz);
    if (r > DwRing.x + 2.0 || r < DwRing.y - 50.0) return 0.0;
    vec4 n0 = dwNode(r);
    float a0 = DwEye.w - n0.x;
    if (a0 < 0.0 || n0.z <= 0.0) return 0.0;
    // the hump varies along the front (1.3–1.55x), world-fixed lumps ~200 m
    float hump = 1.3 + 0.25 * smoothstep(0.25, 0.75, dwNoise3(vec3(q.xz / 190.0, 33.4)));
    float h = n0.y;
    float c0, h0;
    dwTops(a0, h, hump, c0, h0);
    float top0 = max(c0, h0);
    float pl = 0.0;
    if (!coarse && q.y > top0 * 0.5) pl = 0.05 * dwWisps(q, top0) * (0.4 + 1.2 * mix(0.5, dwNoise3(q / 8.0 + vec3(0.0, -DwEye.w * 0.6, 0.0)), dwLod(1.0 / 8.0)));
    float yn0 = q.y / max(top0, 1.0);
    if (yn0 > 1.6) return pl;
    // the top leans back toward ground zero: dust higher up was lifted a little earlier and carried back (the curl)
    float lean = 34.0 * yn0 * yn0 * clamp(a0 / 0.7, 0.0, 1.0);
    float a = DwEye.w - dwNode(r + lean).x;
    if (a < 0.0) return pl;
    float core, haze;
    dwTops(a, h, hump, core, haze);
    // eddies behind the front: energy decays over ~8 s (floor over the hot ground) plus the violent first seconds; their
    // phase is the integral of the energy, so they keep turning slowly after the front has gone (big rolls, no boiling)
    float E = exp(-a / 8.0), Ef = exp(-a / 4.0);
    float A = 0.3 + 0.7 * E + 0.6 * Ef;
    float ph = 8.0 * (1.0 - E) + 0.25 * a + 3.5 * (1.0 - Ef);
    vec3 pw = q;
    vec3 b = q / vec3(32.0, 20.0, 32.0);
    float fw = gDwFoot / 20.0;
    // light paths take the eddies' first octave only: shadows follow the same billows at a third of the fetches
    vec3 w = coarse ? 0.6667 * 0.75 * (vec3(dwNoise3(b + vec3(0.0, ph * 0.15, 0.0)), dwNoise3(b + vec3(5.2, 1.3 - ph * 0.14, 2.8)),
            dwNoise3(b + vec3(2.1, 7.7, ph * 0.15))) - 0.5)
            : vec3(dwFbm2(b + vec3(0.0, ph * 0.15, 0.0), fw), dwFbm2(b + vec3(5.2, 1.3 - ph * 0.14, 2.8), fw), dwFbm2(b + vec3(2.1, 7.7, ph * 0.15), fw)) - 0.5;
    float gnd = smoothstep(0.0, 0.45 * core, q.y);
    pw += w * vec3(55.0, 38.0, 55.0) * A * gnd;
    // the whole lofted mass sloshes: out with the wind, then back with the negative phase; the top moves more (rolls)
    vec2 rdir = q.xz / max(r, 1.0);
    float yy = clamp(q.y / max(core, 1.0), 0.0, 1.5);
    if (!coarse) {
        // (light paths skip it: the slosh moves the mass and its texture together by metres)
        float sl = (18.0 * exp(-a / 3.0) * sin(1.3 * a + 0.4) + 6.0 * sin(0.45 * a + dwNoise3(q / 90.0) * 6.0)) * yy;
        pw.xz -= rdir * sl;
        pw.y += 6.0 * Ef * sin(1.7 * a + dwNoise3(q / 60.0 + 3.0) * 6.0) * yy;
    }
    pw.xz -= DwWind.xy * a;
    // texture fixed in the air mass: the rising, settling top reveals and hides it, it does not slide through it
    vec3 tq = vec3(pw.x, pw.y - 0.9 * ph, pw.z) / vec3(20.0, 13.0, 20.0);
    float n = coarse ? 0.25 + 0.5 * dwNoise3(tq) : dwFbm(tq, 1.0 / 13.0);
    float fs = 0.5;
    if (!coarse) {
        // fibrous streaks combed along the radial wind, ~28 m along, 4.5 m across
        vec2 rv = pw.xz;
        float ral = length(rv), rac = atan(rv.y, rv.x) * ral;
        fs = mix(0.5, dwFbm2(vec3(ral / 28.0, (pw.y - 0.5 * ph) / 5.0, rac / 4.5), 0.0), dwLod(1.0 / 2.2));
    }
    // big soft lumps of the mass, carried by the eddies: ±30 % on the fresh head, ±45 % behind it
    float Lb = dwFbm2(vec3(pw.xz / 170.0, 0.0) + vec3(0.0, 0.06 * a + 0.02 * DwEye.w, 0.0), 0.0);
    core *= 1.0 + mix(0.66, 1.0, smoothstep(1.0, 5.0, a)) * (0.9 * smoothstep(0.2, 0.8, Lb) - 0.45);
    float yr = q.y / core;
    // the youngest dust is torn: tufts and gaps at the contact line, merging into one mass within half a second
    float torn = 1.0 + 1.6 * exp(-a / 0.25);
    float z = mix(yr, pw.y / core + ((n - 0.5) * 1.9 + (fs - 0.5) * 0.8) * torn, smoothstep(0.0, 0.6 / torn, yr));
    float prof = smoothstep(1.05, 0.55, z);
    float head = 1.0 + 0.7 * exp(-a / 1.1);
    float hz = haze * (0.7 + 0.6 * Lb);
    float zh = mix(q.y / hz, pw.y / hz + (n - 0.5) * 1.8, smoothstep(0.0, 0.6, q.y / hz));
    float hd = 0.07 * (0.1 + 1.8 * smoothstep(0.3, 0.7, Lb));
    float d = prof * (0.25 + 0.75 * exp(-max(a - 1.0, 0.0) / 6.0)) * head * (0.8 + 0.4 * Lb) + hd * smoothstep(1.05, 0.45, zh);
    float fade = 1.0 - smoothstep(DW_SPAN - DW_FADE, DW_SPAN, a);
    return n0.z * d * smoothstep(0.0, 0.12, a) * (0.3 + 1.4 * n) * (0.45 + 1.1 * fs) * fade + pl;
}

// fireball centre from ground zero's ground point
vec3 dwBallAt() {
    return DwBall.xyz;
}

// fireball light on a white wall facing it at q (pallBallLight: Lambertian sphere of radius DwBall.w, DwBallLight at its
// surface, R²/d²)
vec3 dwBallLight(vec3 q) {
    vec3 d = DwBall.xyz - q;
    return DwBallLight * min(DwBall.w * DwBall.w / max(dot(d, d), 1.0), 1.0);
}

// light of a dust sample at q with extinction s (presentation): the fireball's and the sun's light through three samples
// toward the ball (dense dust is dark inside, lit on the side facing the ball), shared by three scattering orders (single:
// a forward-peaked dust phase, so edges toward the ball glow; then two diffuse orders along ever thinner paths, so the
// shaded body stays warm dust-brown, never black or sky-blue); sky from above, ground bounce from below
float dwPhase(float c) {
    // dual Henyey–Greenstein, g 0.6 forward / −0.2 back, × 4π (1 for isotropic)
    float f = (1.0 - 0.36) / pow(1.36 - 1.2 * c, 1.5), k = (1.0 - 0.04) / pow(1.04 + 0.4 * c, 1.5);
    return 0.75 * f + 0.25 * k;
}
vec3 dwLight(vec3 q, vec3 rd, float s) {
    vec3 lb = normalize(dwBallAt() - q);
    // depth toward the ball over 90 m: a low fireball throws billow shadows of a hundred metres across the top
    float tau = 1.6 * (dwDensity(q + lb * 8.0, true) * 14.0 + dwDensity(q + lb * 26.0, true) * 26.0
            + dwDensity(q + lb * 70.0, true) * 55.0);
    float low = 0.55 + 0.45 * smoothstep(0.0, 40.0, q.y);
    float orders = 0.3 * min(dwPhase(dot(rd, lb)), 3.0) * exp(-tau) + 0.16 * exp(-tau * 0.3) + 0.12 * exp(-tau * 0.08);
    vec3 L = dwBallLight(q) * orders * low;
    // the sun lights the top: depth below the local surface along the sun stands in for its own light path
    float sunOrders = 0.3 * min(dwPhase(dot(rd, DwSunDir)), 3.0) * exp(-tau * 0.8) + 0.16 * exp(-tau * 0.25) + 0.12 * exp(-tau * 0.06);
    L += DwSunLight * sunOrders * low;
    L += DwSkyLight * 0.4 * (0.4 + 0.6 * smoothstep(0.0, 40.0, q.y)) * exp(-tau * 0.05) + DwGroundLight;
    // presentation: the wall is exposed like the reference footage of a fireball-lit dust front (dark matte body,
    // lit crests), not like a white wall facing the ball
    return DW_EXPOSURE * DwAlbedo * L;
}

const float DW_K = 0.0045, DW_S = 1.5;
float dwGridT(float k) {
    float kb = 1.0 / DW_K;
    return k < kb ? k * DW_S : DW_S / DW_K * pow(1.0 + DW_K, k - kb);
}
float dwGridK(float t) {
    float tb = DW_S / DW_K;
    return t < tb ? t / DW_S : 1.0 / DW_K + log(t / tb) / log(1.0 + DW_K);
}

// march of one ray from the eye (direction rd, unit) up to tEnd blocks (the world behind the pixel); jitter in [0, 1) fixed
// per pixel; foot: pixel size per block of distance. Returns premultiplied light and transmittance.
// Samples sit on a grid fixed along the ray from the eye (geometric steps from the eye, independent of where the
// wall's span starts), so they do not slide through the texture as the front grows.
vec4 dwMarch(vec3 rd, float tEnd, float jitter, float foot, int maxSteps) {
    vec3 eye = DwEye.xyz;
    float top = DwRing.z;
    // vertical slab [0, top]
    float t0 = 0.0, t1 = tEnd;
    if (abs(rd.y) < 1e-5) {
        if (eye.y > top || eye.y < -50.0) return vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        float ta = (top - eye.y) / rd.y, tb = (-50.0 - eye.y) / rd.y;
        t0 = max(t0, min(ta, tb));
        t1 = min(t1, max(ta, tb));
    }
    // annulus [inner, front]: clip to the outer circle (inner is skipped by the density's early return)
    vec2 oc = eye.xz;
    float A = dot(rd.xz, rd.xz), Bq = dot(oc, rd.xz), C = dot(oc, oc) - (DwRing.x + 4.0) * (DwRing.x + 4.0);
    float disc = Bq * Bq - A * C;
    if (disc < 0.0 || A < 1e-10) return vec4(0.0, 0.0, 0.0, 1.0);
    float sq = sqrt(disc);
    t0 = max(t0, (-Bq - sq) / A);
    t1 = min(t1, (-Bq + sq) / A);
    if (t0 >= t1) return vec4(0.0, 0.0, 0.0, 1.0);
    // sample grid fixed along the ray from the eye: 1.5-block steps to 1.5/K blocks, then geometric (step K × distance);
    // cell index k <-> distance (dwGridT, dwGridK)
    float k = floor(dwGridK(t0));
    // n: cells to the next sample (strides grow over empty air); kLast: the last sampled cell
    float n = 1.0, kLast = k - 1.0, lit = -1.0;
    vec3 light = vec3(0.0), c = vec3(0.0);
    float T = 1.0;
    for (int i = 0; i < 400; i++) {
        float t = dwGridT(k);
        if (i >= maxSteps || t > t1 || T < 0.03) break;
        float dt = dwGridT(k + 1.0) - t;
        float tt = t + dt * jitter;
        vec3 q = eye + rd * tt;
        float bound = dwBound(q);
        if (bound < 0.0 && q.y > 0.0) {
            // no young dust at this radius: long strides
            kLast = k; k += n; n = min(n * 2.0, 16.0);
            continue;
        }
        if (q.y > bound && rd.y < 0.0) {
            // above the dust here: down to its bound, onto the next grid cell
            kLast = k;
            k = max(k + 1.0, ceil(dwGridK(t + (q.y - bound) / -rd.y)));
            n = 1.0;
            continue;
        }
        gDwFoot = max(tt * foot, dt * 0.3);
        float s = q.y < 0.0 ? 0.0 : dwDensity(q, false);
        if (s > 1e-5) {
            if (k - kLast > 1.0) {
                // a stride landed in dust: single cells from just after the last empty sample
                k = kLast + 1.0; n = 1.0;
                kLast = k - 1.0;
                continue;
            }
            // light every other dust sample (the shadow toward the ball changes over metres, the step is ~metres)
            if (lit < 0.0 || fract(k * 0.5) < 0.25) c = dwLight(q, rd, s);
            lit = 1.0;
            // thin dust (haze of fines, wisps, fringes): two cells per sample
            float cells = s < 0.012 ? 2.0 : 1.0;
            float span = cells > 1.0 ? dwGridT(k + cells) - t : dt;
            float al = exp(-s * span);
            light += T * c * (1.0 - al);
            T *= al;
            kLast = k + cells - 1.0; k += cells; n = 1.0;
        } else {
            kLast = k; k += n; n = min(n * 2.0, 8.0);
        }
    }
    return vec4(light, T);
}
