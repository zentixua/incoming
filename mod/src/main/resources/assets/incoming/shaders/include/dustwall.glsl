// Young nuclear dust wall (client/fx/layer/DustWallPass): from the front to DW_SPAN seconds behind it, one presentation
// model (presentation, not physical): a dense dust top over the ground (its envelope: born at zero on the contact line, a
// small uneven hump, settled within about a second) filled with matte, billowed dust, and its light.
// Physics enters only as inputs the screen shows (DustWallPass.Frame): where the front is and when it passed a radius
// (table: the mod's in-game front), the settled layer height there and how much dust was lofted, the world wind, the
// fireball, sun and sky light. Everything else (hump, rise, settling, billows, their motion, light response) is this model's.
// Shared verbatim by the mod's pass (fx_dustwall.fsh) and the shader harness.
//
// Positions are relative to ground zero's ground point, blocks (= metres); the eye is DwEye from there.

uniform sampler3D DwNoise;
// (eye x, y, z from ground zero's ground point; seconds since the burst on the detonation clock)
uniform vec4 DwEye;
// (front radius now; inner radius of the young wall: the front DW_SPAN seconds ago; envelope top, blocks; table reach)
uniform vec4 DwRing;
// table (DW_NODES × 1, RGBA32F): node i at radius i / (DW_NODES − 1) × reach: (front arrival, s since burst; settled layer
// height, blocks; lofted dust extinction, 1/block; 0)
uniform sampler2D Sampler2;
// (fireball centre from ground zero's ground point, radius)
uniform vec4 DwBall;
// light colours (linear, frame units): fireball at its surface, sun, sky from above, ground bounce
uniform vec3 DwBallLight;
uniform vec3 DwSunLight;
uniform vec3 DwSunDir;
uniform vec3 DwSkyLight;
uniform vec3 DwGroundLight;
// dust single-scattering albedo, and the colour of a thick layer of it (the soil's bulk colour: what multiply scattered
// light takes on), the pall's dust at this soil
uniform vec3 DwAlbedo;
uniform vec3 DwBulk;
// (wind x, z, blocks/s; 0; 0)
uniform vec4 DwWind;

const int DW_NODES = 32;
// seconds of wall behind the front this model draws; the pall draws what is older (crossfade over the last DW_FADE s)
const float DW_SPAN = 24.0;
const float DW_FADE = 6.0;
// presentation exposure of the wall's light (Silo S3E09 dust band: display luma median ~0.35)
const float DW_EXPOSURE = 0.2;
// head timing, seconds of age (presentation): rise e-fold at the contact line, hump hold, width of the hump's fall
const float DW_RISE = 0.12, DW_HOLD = 0.3, DW_FALL = 0.4;
// most the billowed surface and the bulges lift the dust over its envelope, shares (the ray bound, dwBound)
const float DW_BULGE = 0.25, DW_BILLOW = 0.16, DW_HAZE = 1.3;

// pixel footprint at the sample, blocks (set by the march)
float gDwFoot = 0.0;
// set by dwDensity at its sample: the billowed dust surface above it and its full extinction (the light reads them)
float gDwSurf = 0.0, gDwSigma = 0.0;

vec4 dwNode(float r) {
    float x = clamp(r / max(DwRing.w, 1.0), 0.0, 1.0) * float(DW_NODES - 1);
    int i = int(min(floor(x), float(DW_NODES - 2)));
    return mix(texelFetch(Sampler2, ivec2(i, 0), 0), texelFetch(Sampler2, ivec2(i + 1, 0), 0), x - float(i));
}

// value noise from DwNoise, a 128³ lattice of random values (DustWallPass.lattice), one trilinear fetch at the smoothstep-
// warped coordinate: C1 value noise with a period of 128 cells (1.7 km at the finest 13 m octave), no visible repeat
const float DW_LATTICE = 128.0;
float dwNoise3(vec3 x) {
    vec3 i = floor(x), f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return textureLod(DwNoise, (i + f + 0.5) / DW_LATTICE, 0.0).r;
}

// visible share of an octave of frequency f (1/block) at this footprint: detail finer than a pixel fades to its mean
float dwLod(float f) {
    return 1.0 - smoothstep(0.35, 0.8, f * gDwFoot);
}

// fBm of four octaves, 0..1 around 0.5; octaves finer than the pixel footprint fade to their mean (fq: first octave
// frequency, 1/block)
float dwFbm(vec3 p, float fq) {
    float a = 0.5, s = 0.0, fr = fq;
    for (int i = 0; i < 4; i++) {
        s += a * mix(0.5, dwNoise3(p), dwLod(fr));
        p = p * 2.07 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
        fr *= 2.07;
    }
    return s / 0.9375;
}
// billowed fBm, three octaves of |2n − 1| (rounded lumps with creases between them: the cauliflower of a dust top), 0..1
float dwBillow(vec3 p, float fq) {
    float a = 0.5, s = 0.0, fr = fq;
    for (int i = 0; i < 3; i++) {
        s += a * mix(0.42, abs(2.0 * dwNoise3(p) - 1.0), dwLod(fr));
        p = p * 2.13 + vec3(4.1, 2.7, 8.3);
        a *= 0.5;
        fr *= 2.13;
    }
    return s / 0.875;
}
// two octaves, 0..1 around 0.5
float dwFbm2(vec3 p, float fq) {
    return 0.6667 * mix(0.5, dwNoise3(p), dwLod(fq)) + 0.3333 * mix(0.5, dwNoise3(p * 2.07 + vec3(1.7, 9.2, 3.1)), dwLod(fq * 2.07));
}

// hump of the head over the settled layer along the front: 1.38–1.58 of the envelope (its visible crest 1.3–1.5x the layer
// behind), world-fixed lumps ~200 m
float dwHump(vec2 xz) {
    return 1.38 + 0.2 * smoothstep(0.3, 0.7, dwNoise3(vec3(xz / 190.0, 33.4)));
}

// the dust envelope at age a (s since the front passed) over settled height h with hump k (presentation): born at zero on
// the contact line, up with e-fold DW_RISE, held as a hump for DW_HOLD, down to the layer within about a second
float dwEnvelope(float a, float h, float k) {
    if (a <= 0.0) return 0.0;
    float x = max(a - DW_HOLD, 0.0) / DW_FALL;
    return h * (1.0 - exp(-a / DW_RISE)) * (1.0 + (k - 1.0) * exp(-x * x));
}

// the air mass the dust rides in: position the lofted air at q (age a, radius r, envelope top) came from — the texture is
// fixed in it, so motion carries billows and does not slide them through the dust (presentation). The post-shock wind
// whirls it hardest in the first seconds and calms with the wind (eddy energy e-fold 8 s, floor 30 %, violent first
// seconds e-fold 4 s); eddies 32 × 20 m turn with phase = integral of their energy, at the speed of real dust fronts
// (haboob footage: a 16-pixel patch decorrelates at ~0.45/s); the whole mass sloshes out with the wind and back with the
// negative phase, the top more (rolls); the world wind drifts it all
vec3 dwAir(vec3 q, float a, float r, float top, bool coarse, out float ph) {
    float E = exp(-a / 8.0), Ef = exp(-a / 4.0);
    float A = 0.3 + 0.7 * E + 0.6 * Ef;
    ph = 0.5 * (8.0 * (1.0 - E) + 0.25 * a + 3.5 * (1.0 - Ef));
    vec3 b = q / vec3(32.0, 20.0, 32.0);
    // one octave: a second one shears the 20 m billows into marbled, liquid-looking swirls
    vec3 w = vec3(dwNoise3(b + vec3(0.0, ph * 0.15, 0.0)), dwNoise3(b + vec3(5.2, 1.3 - ph * 0.14, 2.8)),
            dwNoise3(b + vec3(2.1, 7.7, ph * 0.15))) - 0.5;
    vec3 pw = q + w * vec3(40.0, 26.0, 40.0) * A * smoothstep(0.0, 0.45 * max(top, 1.0), q.y);
    vec2 rdir = q.xz / max(r, 1.0);
    float yy = clamp(q.y / max(top, 1.0), 0.0, 1.5);
    pw.xz -= rdir * (18.0 * exp(-a / 3.0) * sin(1.3 * a + 0.4) + 6.0 * sin(0.45 * a)) * yy;
    pw.xz -= DwWind.xy * a;
    return pw;
}

// bulges and dips of the mass in the air (≈110 m lumps): small on the fresh head, larger once it has settled
float dwBulge(vec3 pw, float a) {
    float L = dwFbm2(vec3(pw.xz / 110.0, 0.37), 0.0);
    return clamp((L - 0.5) * mix(1.6, 2.6, smoothstep(1.0, 5.0, a)), -DW_BULGE, DW_BULGE);
}

// the smooth dust top over ground point xz (no billows; the light's shadow surface), blocks; 0 where no young dust
float dwTopAt(vec2 xz) {
    float r = length(xz);
    if (r > DwRing.x + 2.0 || r < DwRing.y - 50.0) return 0.0;
    vec4 n0 = dwNode(r);
    float a = DwEye.w - n0.x;
    if (a <= 0.0 || n0.z <= 0.0) return 0.0;
    float top = dwEnvelope(a, n0.y, dwHump(xz)), ph;
    vec3 pw = dwAir(vec3(xz.x, 0.6 * top, xz.y), a, r, top, true, ph);
    return top * (1.0 + dwBulge(pw, a));
}

// highest the dust can reach above a point (blocks), below 0 where no young dust is
float dwBound(vec3 q) {
    float r = length(q.xz);
    if (r > DwRing.x + 2.0 || r < DwRing.y - 50.0) return -1.0;
    vec4 n0 = dwNode(r);
    float a = DwEye.w - n0.x;
    if (a < 0.0 || n0.z <= 0.0) return -1.0;
    // the envelope here (an older point inward is no higher: the hump is at the front) lifted by the bulges, billows and haze;
    // 1.6: the largest hump, for the forward lean of the crest
    float env = a < 1.5 ? n0.y * 1.6 : dwEnvelope(a, n0.y, 1.6);
    return env * (1.0 + DW_BULGE) * DW_HAZE + 6.0;
}

// dust extinction (1/block) at q; coarse: light paths (the billows' two first octaves, no streaks, no fine octaves)
float dwDensity(vec3 q, bool coarse) {
    if (!coarse) gDwSigma = 0.0;
    float r = length(q.xz);
    if (r > DwRing.x + 2.0 || r < DwRing.y - 50.0) return 0.0;
    vec4 n0 = dwNode(r);
    float a0 = DwEye.w - n0.x;
    if (a0 < 0.0 || n0.z <= 0.0) return 0.0;
    float h = n0.y, k = dwHump(q.xz);
    float top0 = max(dwEnvelope(a0, h, k), 1.0);
    if (q.y > top0 * (1.0 + DW_BULGE) * DW_HAZE + 6.0) return 0.0;
    // the crest curls forward over the contact line: higher up, the dust has the profile of a point a little inward (older),
    // so the hump overhangs a nose that is still being torn up from the ground; gone as the hump settles
    float yn = clamp(q.y / top0, 0.0, 1.5);
    // the young head billows forward and back as well as up: its face is cauliflower, not a smooth cut (lumps ~30 m,
    // ±20–40 m at the front, gone as the head settles); the lumps sit in the ground-fixed air of the head
    float bf = coarse || a0 > 4.0 ? 0.42 : dwBillow(q / vec3(30.0, 22.0, 30.0) + vec3(3.7, 0.0, 9.1), 1.0 / 22.0);
    float a = DwEye.w - dwNode(max(r - 30.0 * yn * yn * exp(-a0 / 1.2) + 120.0 * (bf - 0.42) * exp(-a0 / 1.5), 0.0)).x;
    if (a <= 0.0) return 0.0;
    float top = dwEnvelope(a, h, k), ph;
    vec3 pw = dwAir(q, a, r, top, coarse, ph);
    top *= 1.0 + dwBulge(pw, a);
    // texture fixed in the air mass: billows of ~20 × 13 m and finer, the rising and settling top reveals and hides them
    vec3 tq = vec3(pw.x, pw.y - 0.45 * ph, pw.z) / vec3(20.0, 13.0, 20.0);
    float n = coarse ? dwFbm2(tq, 0.0) : dwFbm(tq, 1.0 / 13.0);
    // cauliflower lumps of the top, ~35 m and finer, in the same air
    float bl = coarse ? 0.42 : dwBillow(vec3(pw.x, pw.y - 0.3 * ph, pw.z) / vec3(34.0, 22.0, 34.0) + 11.0, 1.0 / 22.0);
    // fibrous streaks combed along the radial wind, ~28 m along, 4.5 m across
    float fs = 0.5;
    if (!coarse) {
        float ral = length(pw.xz);
        fs = mix(0.5, dwFbm2(vec3(ral / 28.0, (pw.y - 0.25 * ph) / 5.0, atan(pw.z, pw.x) * ral / 4.5), 0.0), dwLod(1.0 / 2.2));
    }
    // the billowed surface: rises and dips by the texture (cauliflower billows, ragged where the dust is young), within
    // ±DW_BILLOW of the top: the hump's excess (+30–50 %) stays well above the billows, so the crest reads as a crest
    float torn = 1.0 + 0.5 * exp(-a / 0.25);
    float surf = top * (1.0 + clamp(((bl - 0.42) * 0.5 + (n - 0.5) * 0.35 + (fs - 0.5) * 0.15) * torn, -DW_BILLOW, DW_BILLOW));
    if (!coarse) gDwSurf = surf;
    float edge = 0.12 * top + 3.0;
    float prof = smoothstep(surf + 0.4 * edge, surf - edge, q.y);
    // fines haze over the core, thin, rising slowly over the hot ground
    float hz = top * (1.15 + 0.15 * (1.0 - exp(-a / 12.0)));
    float haze = 0.06 * smoothstep(hz, top * 0.8, q.y) * (0.4 + 1.2 * n);
    // coarse dust falls back (the core thins over seconds, fines stay); the young head is denser
    float amt = (0.45 + 0.55 * exp(-max(a - 1.0, 0.0) / 8.0)) * (1.0 + 0.5 * exp(-a / 1.1));
    float fade = 1.0 - smoothstep(DW_SPAN - DW_FADE, DW_SPAN, a);
    float sigma = n0.z * amt * fade;
    if (!coarse) gDwSigma = sigma;
    return sigma * smoothstep(0.0, 0.08, a) * (prof * (0.5 + 1.0 * n) * (0.7 + 0.6 * fs) + haze);
}

// light on a white wall facing the fireball at q (Lambertian sphere of radius DwBall.w, DwBallLight at its surface, R²/d²)
vec3 dwBallLight(vec3 q) {
    vec3 d = DwBall.xyz - q;
    return DwBallLight * min(DwBall.w * DwBall.w / max(dot(d, d), 1.0), 1.0);
}

// dual Henyey–Greenstein dust phase, g 0.6 forward / −0.2 back, × 4π (1 for isotropic)
float dwPhase(float c) {
    float f = (1.0 - 0.36) / pow(1.36 - 1.2 * c, 1.5), k = (1.0 - 0.04) / pow(1.04 + 0.4 * c, 1.5);
    return 0.75 * f + 0.25 * k;
}

// optical depth toward a light in direction l from q: the billows over the next ~25 m (one coarse density tap: lit tops,
// shaded undersides, the face toward a low light lit through its short way out) and the smooth top ~70 m away (the hump
// and bulges throw long shadows)
float dwShadow(vec3 q, vec3 l) {
    vec3 p = q + l * 70.0;
    return max(dwTopAt(p.xz) - p.y, 0.0) * gDwSigma * 0.8 + dwDensity(q + l * 12.0, true) * 26.0;
}

// light of a dust sample at q (presentation, follows the light on ground-coloured dust): single scattering of the fireball
// and the sun (forward-peaked: edges toward the ball glow), and multiply scattered light that diffuses in from every lit face
// and takes on the soil's bulk colour (dense dust is darker inside but stays warm dust-coloured, never black); sky light
// enters from the top, ground bounce from below
vec3 dwLight(vec3 q, vec3 rd) {
    vec3 lb = normalize(DwBall.xyz - q);
    float taub = dwShadow(q, lb), taus = dwShadow(q, DwSunDir);
    float depth = max(gDwSurf - q.y, 0.0) * gDwSigma;
    float ms = 0.3 + 0.3 * exp(-0.12 * depth);
    float sunUp = smoothstep(-0.05, 0.05, DwSunDir.y);
    vec3 single = dwBallLight(q) * (0.3 * min(dwPhase(dot(rd, lb)), 6.0) * exp(-taub))
            + DwSunLight * (0.3 * min(dwPhase(dot(rd, DwSunDir)), 6.0) * exp(-taus)) * sunUp;
    vec3 multiple = dwBallLight(q) * ms * (0.45 + 0.55 * exp(-0.25 * taub)) + DwSunLight * ms * (0.45 + 0.55 * exp(-0.25 * taus)) * sunUp
            + DwSkyLight * (0.06 + 0.3 * exp(-0.3 * depth));
    return DW_EXPOSURE * (DwAlbedo * single + DwBulk * multiple + DwBulk * DwGroundLight);
}

const float DW_K = 0.0055, DW_S = 2.5;
float dwGridT(float k) {
    float kb = 1.0 / DW_K;
    return k < kb ? k * DW_S : DW_S / DW_K * pow(1.0 + DW_K, k - kb);
}
float dwGridK(float t) {
    float tb = DW_S / DW_K;
    return t < tb ? t / DW_S : 1.0 / DW_K + log(t / tb) / log(1.0 + DW_K);
}

// march of one ray from the eye (direction rd, unit) up to tEnd blocks (the world behind the pixel); jitter in [0, 1);
// foot: pixel size per block of distance. Returns premultiplied light and transmittance; depth (out): the distance the
// ray's dust sits at, weighted by what it shows (the history reprojects by it), 0 for none.
// Samples sit on a grid fixed along the ray from the eye (geometric steps from the eye, independent of where the
// wall's span starts), so they do not slide through the texture as the front grows.
vec4 dwMarch(vec3 rd, float tEnd, float jitter, float foot, int maxSteps, out float depth) {
    depth = 0.0;
    vec3 eye = DwEye.xyz;
    float top = DwRing.z;
    // vertical slab [0, top]
    float t0 = 0.0, t1 = tEnd;
    if (abs(rd.y) < 1e-5) {
        if (eye.y > top || eye.y < 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        float ta = (top - eye.y) / rd.y, tb = -eye.y / rd.y;
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
    // sample grid fixed along the ray from the eye: 2.5-block steps to 2.5/K blocks, then geometric (step K × distance:
    // 5.5 m at 1 km, 22 m at 4 km); the history averages the moving offset within each step
    float k = floor(dwGridK(t0));
    // n: cells to the next sample (strides grow over empty air); kLast: the last sampled cell
    float n = 1.0, kLast = k - 1.0;
    vec3 light = vec3(0.0), c = vec3(0.0);
    float T = 1.0, wsum = 0.0;
    for (int i = 0; i < 400; i++) {
        float t = dwGridT(k);
        if (i >= maxSteps || t > t1 || T < 0.02) break;
        float dt = dwGridT(k + 1.0) - t;
        float tt = t + dt * jitter;
        vec3 q = eye + rd * tt;
        float bound = dwBound(q);
        if (bound < 0.0) {
            // no young dust at this radius: long strides
            kLast = k; k += n; n = min(n * 2.0, 16.0);
            continue;
        }
        if (q.y > bound) {
            if (rd.y < 0.0) {
                // above the dust here: down to its bound, onto the next grid cell
                kLast = k;
                k = max(k + 1.0, ceil(dwGridK(t + (q.y - bound) / -rd.y)));
                n = 1.0;
            } else {
                kLast = k; k += n; n = min(n * 2.0, 8.0);
            }
            continue;
        }
        gDwFoot = max(tt * foot, dt * 0.3);
        float s = dwDensity(q, false);
        if (s > 1e-5) {
            if (k - kLast > 1.0) {
                // a stride landed in dust: single cells from just after the last empty sample
                k = kLast + 1.0; n = 1.0;
                kLast = k - 1.0;
                continue;
            }
            c = dwLight(q, rd);
            // the thin fines haze over the dense top: two cells per sample (at grazing views a 20–30 m haze layer is
            // hundreds of metres of ray)
            float cells = q.y > gDwSurf + 2.0 ? 2.0 : 1.0;
            float span = dwGridT(k + cells) - t;
            float al = exp(-s * span);
            float w = T * (1.0 - al);
            light += w * c;
            depth += w * tt;
            wsum += w;
            T *= al;
            kLast = k + cells - 1.0; k += cells; n = 1.0;
        } else {
            kLast = k; k += n; n = min(n * 2.0, 8.0);
        }
    }
    depth = wsum > 1e-4 ? depth / wsum : 0.0;
    return vec4(light, T);
}
