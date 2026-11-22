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
// height, blocks; lofted dust extinction, 1/block; dust over hard ground relative to soil, PallField.paved)
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
// light takes on): the pall's reference soil and sand
uniform vec3 DwAlbedo;
uniform vec3 DwBulk;
uniform vec3 DwSandAlbedo;
uniform vec3 DwSandBulk;
// ground radius of the fireball now, blocks: no dust under it (PallField.young zeroes its nodes; this cuts between them)
uniform float DwCut;
// (wind x, z, blocks/s; 0; 0)
uniform vec4 DwWind;
// lowest and highest ground of the terrain map under the wall, blocks above ground zero's ground point (0, 0: no map)
uniform vec2 DwRelief;


// the ground under ground point xz, the share of open air at q (both from ground zero's ground point) and the soil there
// (hard ground, sand: shares of the ground): the pall's terrain map and outdoor-air window, defined before this file by its
// user (fx_dustwall.fsh: dwFloorEye, dwOpenEye over pallRelief, pallOpen from eye-relative points; dwSoilAt over pallSoil);
// dust lies on the local ground and never indoors, and over rock, roads and roofs only debris dust rises
float dwFloor(vec2 xz) {
    return dwFloorEye(xz - DwEye.xz);
}
float dwOpen(vec3 q) {
    return dwOpenEye(q - DwEye.xyz);
}

const int DW_NODES = 128;
// seconds of wall behind the front this model draws; the pall draws what is older (crossfade over the last DW_FADE s)
const float DW_SPAN = 24.0;
const float DW_FADE = 6.0;
// presentation exposure of the wall's light (Silo S3E09 dust band: display luma median ~0.35)
const float DW_EXPOSURE = 0.2;
// head timing, seconds of age (presentation): rise e-fold at the contact line, hump hold, width of the hump's fall
const float DW_RISE = 0.12, DW_HOLD = 0.25, DW_FALL = 0.3;
// most the bulges, the billowed surface and its soft edge (DW_HAZE) lift the dust over its envelope, shares (the ray bound,
// dwBound)
// (the top behind the head is uneven at several scales, no cap: swells up to ±DW_BULGE of the layer at 150–400 m, billows,
// rising puffs and a thin veil; on the head the swells are small, so its crest, 1.4–1.6 of the layer, still stands out)
const float DW_BULGE = 0.22, DW_BILLOW = 0.15, DW_HAZE = 1.2;

// pixel footprint at the sample, blocks (set by the march)
float gDwFoot = 0.0;
// set by dwDensity at its sample: the billowed dust surface above it, its full extinction and its share of sand (the light
// reads them)
float gDwSurf = 0.0, gDwSigma = 0.0, gDwSand = 0.0;
// set by dwDensity: the sample is in the young head (sampled twice per step: it moves across the steps at the front's speed,
// faster than the history follows)
bool gDwHead = false;
// set by dwDensity: the sample's dust is older than 2 s (calm, settled: its light changes slowly along the ray)
bool gDwOld = false;

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

// hump of the head over the settled layer along the front: 1.55–1.75 of the envelope (its visible crest 1.4–1.6x the layer
// behind), lumps ~200 m along the front that stay with their bearing as the front runs out and change slowly
float dwHump(vec2 xz) {
    return 1.55 + 0.2 * smoothstep(0.3, 0.7, dwNoise3(vec3(atan(xz.y, xz.x) * 4500.0 / 190.0, 33.4, 0.1 * DwEye.w)));
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
// seconds e-fold 4 s); eddies 32 × 20 m turn with phase = integral of their energy: violent while the wave's wind
// blows, then at the speed of real dust fronts (haboob drone footage: a 16-pixel patch decorrelates at ~0.45/s); the whole mass sloshes out with the wind and back with the
// negative phase, the top more (rolls); the world wind drifts it all
vec3 dwAir(vec3 q, float a, float r, float top, out float ph) {
    float E = exp(-a / 8.0), Ef = exp(-a / 4.0);
    float A = 0.3 + 0.7 * E + 0.9 * Ef;
    ph = 0.5 * (8.0 * (1.0 - E) + 0.25 * a) + 3.5 * (1.0 - Ef);
    vec3 b = q / vec3(32.0, 20.0, 32.0);
    // one octave: a second one shears the 20 m billows into marbled, liquid-looking swirls
    vec3 w = vec3(dwNoise3(b + vec3(0.0, ph * 0.15, 0.0)), dwNoise3(b + vec3(5.2, 1.3 - ph * 0.14, 2.8)),
            dwNoise3(b + vec3(2.1, 7.7, ph * 0.15))) - 0.5;
    vec3 pw = q + w * vec3(40.0, 26.0, 40.0) * A * smoothstep(0.0, 0.45 * max(top, 1.0), q.y);
    vec2 rdir = q.xz / max(r, 1.0);
    float yy = clamp(q.y / max(top, 1.0), 0.0, 1.5);
    // carried out by the post-shock wind (~60 m over its first seconds), then drifting back with the negative phase
    pw.xz -= rdir * (60.0 * (1.0 - exp(-a / 1.2)) * exp(-a / 6.0) + 8.0 * sin(0.45 * a)) * yy;
    // residual turbulence behind the front: neighbouring parcels circle on slow orbits (20 m, 5 m/s; phases varying over
    // ~150 m), so the settled dust keeps swirling and drifting sideways as real dust does, never frozen, without the
    // endless shear a steady flow field would stretch it into
    float sw = 6.2832 * dwNoise3(vec3(q.xz / 150.0, 71.3));
    pw.xz -= 20.0 * vec2(cos(0.25 * a + sw), sin(0.25 * a + sw)) * (1.0 - exp(-a / 1.5)) * (0.6 + 0.4 * yy);
    // gusts right behind the front: the hit air keeps surging forward and sideways in ~200 m gust cells whose push
    // changes over a few seconds, strongest just after the front and calming over ~6 s (presentation)
    vec2 gp = q.xz / 220.0 + vec2(0.0, 0.35 * a);
    vec2 gust = vec2(dwNoise3(vec3(gp, 3.3)), dwNoise3(vec3(gp + 7.7, 9.1))) - 0.5;
    pw.xz -= gust * 70.0 * (1.0 - exp(-a / 0.8)) * exp(-a / 6.0) * (0.5 + 0.5 * yy);
    pw.xz -= DwWind.xy * a;
    return pw;
}

// smooth saturation to ±1 (no flat shelves where a clamp would cut the tops)
float dwSoft(float x) {
    return x / sqrt(1.0 + x * x);
}

// swells and sags of the mass in the air (Silo: higher here, lower there): domain-warped ≈350 m swells carrying ≈120 m
// lumps, up to ±DW_BULGE of the layer behind the head, a third of that on the head (its crest stays readable); they
// swell and sag slowly as the dust settles
float dwBulge(vec3 pw, float a) {
    vec2 p = pw.xz / 350.0;
    float w = dwNoise3(vec3(p * 0.6, 4.4)) - 0.5;
    p += 0.7 * vec2(w, -w);
    float L = 0.6 * dwNoise3(vec3(p, 7.1 + 0.02 * a)) + 0.4 * dwNoise3(vec3(pw.xz / 120.0, 0.37 + 0.03 * a));
    return DW_BULGE * dwSoft((L - 0.5) * 4.0) * mix(0.33, 1.0, smoothstep(0.8, 2.5, a));
}

// a buoyant puff above the top: about one 70 m cell in six lifts a rounded column of dust ~25 % of the layer above the
// top, 1–9 s after the front, over ~4 s, thinning as it rises (presentation)
float dwPuff(vec3 pw, float top, float a) {
    vec2 cell = floor(pw.xz / 70.0);
    float h1 = dwNoise3(vec3(cell * 1.37, 91.2)), h2 = dwNoise3(vec3(cell * 1.37 + 0.5, 47.7));
    if (h1 < 0.62) return 0.0;
    float born = 1.0 + 8.0 * h2, age = a - born;
    if (age <= 0.0) return 0.0;
    vec2 c = (cell + 0.5) * 70.0 + (vec2(h2, h1) - 0.5) * 30.0;
    float rise = 0.25 * top * (1.0 - exp(-age / 2.5)) * (h1 - 0.4) * 2.5;
    float radius = 10.0 + 5.0 * age;
    vec2 d = vec2(length(pw.xz - c), (pw.y - top * 0.9 - rise) * 1.3);
    return 0.5 * exp(-age / 6.0) * (1.0 - smoothstep(0.5, 1.0, length(d) / radius)) * smoothstep(0.0, 1.0, age);
}

// the smooth dust top over ground point xz (no billows; the light's shadow surface), blocks above the local ground; 0 where
// no young dust
float dwTopAt(vec2 xz) {
    float r = length(xz);
    if (r > DwRing.x + 2.0 || r < DwRing.y - 50.0) return 0.0;
    vec4 n0 = dwNode(r);
    float a = DwEye.w - n0.x;
    if (a <= 0.0 || n0.z <= 0.0) return 0.0;
    // without the hump: its shadow would sweep over the near dust at the front's speed (lit patches sliding at the passage)
    float top = dwEnvelope(a, n0.y, 1.0), ph;
    vec3 pw = dwAir(vec3(xz.x, 0.6 * top, xz.y), a, r, top, ph);
    return top * (1.0 + dwBulge(pw, a));
}

// highest the dust can reach above the local ground at a point (blocks), below 0 where no young dust is
float dwBound(vec3 q) {
    float r = length(q.xz);
    if (r > DwRing.x + 2.0 || r < max(DwRing.y - 50.0, DwCut)) return -1.0;
    vec4 n0 = dwNode(r);
    float a = DwEye.w - n0.x;
    if (a < 0.0 || n0.z <= 0.0) return -1.0;
    // the envelope here (an older point inward is no higher: the hump is at the front) lifted by the bulges, billows and edge;
    // 1.6: the largest hump, for the forward lean of the crest
    float env = a < 1.5 ? n0.y * 1.8 : dwEnvelope(a, n0.y, 1.8);
    return env * (1.0 + DW_BULGE) * DW_HAZE + 6.0;
}

// dust extinction (1/block) at q; coarse: light paths (the billows' two first octaves, no streaks, no fine octaves)
float dwDensity(vec3 g, bool coarse) {
    if (!coarse) gDwSigma = 0.0;
    // heights above the local ground
    vec3 q = vec3(g.x, g.y - dwFloor(g.xz), g.z);
    float r = length(q.xz);
    if (r > DwRing.x + 2.0 || r < max(DwRing.y - 50.0, DwCut)) return 0.0;
    vec4 n0 = dwNode(r);
    float a0 = DwEye.w - n0.x;
    if (a0 < 0.0 || n0.z <= 0.0) return 0.0;
    float h = n0.y, k = dwHump(q.xz);
    float top0 = max(dwEnvelope(a0, h, k), 1.0);
    if (q.y > top0 * (1.0 + DW_BULGE) * DW_HAZE + 6.0) return 0.0;
    // the dust is born on the contact line and rises while the front runs on: higher up it has the profile of a point a
    // little outward (younger), so the face is a ramp from the ground up into the crest, leaning back, never ahead of the
    // front. The young head billows: its face is cut back by cauliflower lumps (~30 m, up to ~35 m deep, gone as the head
    // settles) that ride with the head (coordinates along the front, height and distance behind it) and roll back over it
    // at 25 m/s: fixed in the ground, the front would sweep each 30 m lump in two frames and the face would boil
    float yn = clamp(q.y / top0, 0.0, 1.5);
    float bf = 0.42;
    if (a0 < 4.0) {
        vec3 hf = vec3(atan(q.z, q.x) * DwRing.x / 30.0, q.y / 22.0, (DwRing.x - r + 25.0 * DwEye.w) / 30.0);
        bf = coarse ? 0.42 : dwBillow(hf + vec3(3.7, 0.0, 9.1), 1.0 / 22.0);
    }
    // (the lumps cut the upper face only: cut to the ground they opened holes through the base that the moving front
    // swept across the ray steps, popping from frame to frame)
    float a = DwEye.w - dwNode(r + (40.0 * yn * yn + 70.0 * max(bf - 0.3, 0.0) * smoothstep(0.2, 0.6, yn)) * exp(-a0 / 1.5)).x;
    if (!coarse) {
        gDwHead = a0 < 1.5;
        gDwOld = a0 > 2.0;
    }
    if (a <= 0.0) return 0.0;
    float top = dwEnvelope(a, h, k), ph;
    vec3 pw = dwAir(q, a, r, top, ph);
    top *= 1.0 + dwBulge(pw, a);
    // texture fixed in the air mass: billows of ~20 × 13 m and finer, the rising and settling top reveals and hides them
    vec3 tq = vec3(pw.x, pw.y - 0.45 * ph, pw.z) / vec3(20.0, 13.0, 20.0);
    // (light paths see the billows' two first octaves but not the head's lumps riding with the front: those made the
    // shadow toward the fireball, and the face's shade with it, change every frame: the approaching face shimmered)
    float n = coarse ? dwFbm2(tq, 0.0) : dwFbm(tq, 1.0 / 13.0);
    // cauliflower lumps of the top, ~35 m and finer, in the same air
    float bl = coarse ? 0.42 : dwBillow(vec3(pw.x, pw.y - 0.3 * ph, pw.z) / vec3(34.0, 22.0, 34.0) + 11.0, 1.0 / 22.0);
    // the young dust is combed along the radial post-shock wind: its texture (still fixed in the air) is stretched ~150 m
    // along the radius, ~20 m across. The face runs along the streaks at the front's speed and keeps each one's cross-
    // section for ~0.3 s; isotropic 20 m detail was new every ~2 frames at 470 m/s (the approaching face shimmered).
    // Handed over to the isotropic billows within 0.8–2 s, as the wind dies.
    float comb = 1.0 - smoothstep(0.8, 2.0, a0);
    if (comb > 0.0 && !coarse) {
        float ral = length(pw.xz);
        vec3 rq = vec3(ral, pw.y, atan(pw.z, pw.x) * ral);
        n = mix(n, dwFbm(vec3(rq.x / 150.0, (rq.y - 0.45 * ph) / 13.0, rq.z / 20.0) + 41.0, 1.0 / 13.0), comb);
        bl = mix(bl, dwBillow(vec3(rq.x / 200.0, (rq.y - 0.3 * ph) / 22.0, rq.z / 34.0) + 53.0, 1.0 / 22.0), comb);
    }
    // fibrous streaks combed along the radial wind, ~28 m along, 4.5 m across
    float fs = 0.5;
    if (!coarse) {
        float ral = length(pw.xz);
        fs = mix(0.5, dwFbm2(vec3(ral / 28.0, (pw.y - 0.25 * ph) / 5.0, atan(pw.z, pw.x) * ral / 4.5), 0.0), dwLod(1.0 / 2.2));
    }
    // the billowed surface: rises and dips by the texture (cauliflower billows, ragged where the dust is young), within
    // DW_BILLOW of the top: the hump's excess (+30–50 %) stays well above the billows, so the crest reads as a crest
    float torn = 1.0 + 0.5 * exp(-a / 0.25);
    // (on the young head the top takes its shape from the lumps riding with the front, the air's billows join as it
    // settles: the head sweeps through the air at the front's speed and, seen on the horizon line, an air-fixed ragged
    // top was redrawn every frame; in-game approach coherence 0.617 -> 0.731)
    float surf = top * (1.0 + DW_BILLOW * dwSoft(((bl - 0.42) * 3.0 + (n - 0.5) * 2.0) * torn) * smoothstep(0.6, 1.5, a0)
            + 0.12 * (bf - 0.42));
    if (!coarse) gDwSurf = surf;
    // a soft top: billow tops thin out over ~a quarter of the layer (an opaque top popped where dips passed in and out of
    // view at grazing angles)
    float edge = 0.22 * top + 4.0;
    float prof = 1.0 - smoothstep(surf - edge, surf + 0.4 * edge, q.y);
    // coarse dust falls back (the core thins over seconds, fines stay); the young head is denser
    float amt = (0.45 + 0.55 * exp(-max(a - 1.0, 0.0) / 8.0)) * (1.0 + 0.5 * exp(-a / 1.1));
    float fade = 1.0 - smoothstep(DW_SPAN - DW_FADE, DW_SPAN, a0);
    // over hard ground only debris dust (the pall's own reading: PallField.paved of the soil's mass), sand pales it
    vec2 soil = dwSoilAt(q.xz);
    float paved = 1.0 + soil.x * (n0.w - 1.0);
    float sigma = n0.z * amt * fade * paved;
    if (!coarse) {
        sigma *= dwOpen(g);
        gDwSand = clamp(soil.y / max(paved, 1e-6), 0.0, 1.0);
    }
    if (!coarse) gDwSigma = sigma;
    // coarse dust low and dense, finer above (stratified); a thin veil of fines over the uneven top (following it, no
    // shelf); here and there a puff lifted above the top that thins into the veil and drifts with the wind
    float strat = 1.25 - 0.5 * clamp(q.y / max(top, 1.0), 0.0, 1.0);
    float veil = (1.0 - smoothstep(surf, surf * 1.12 + 4.0, q.y)) * smoothstep(surf * 0.8, surf, q.y) * 0.03;
    float puff = coarse || q.y < top * 0.8 ? 0.0 : dwPuff(pw, top, a);
    // the lowest metres thin to the ground (soft where no terrain stands under the dust)
    return sigma * smoothstep(0.0, 0.08, a) * smoothstep(0.0, 3.0, q.y)
            * (prof * strat * (0.5 + 1.0 * n) * (0.7 + 0.6 * fs) + (veil + puff) * (0.6 + 0.8 * n));
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
    return max(dwTopAt(p.xz) - (p.y - dwFloor(p.xz)), 0.0) * gDwSigma * 0.8 + dwDensity(q + l * 12.0, true) * 26.0;
}

// light of a dust sample at q (presentation, follows the light on ground-coloured dust): single scattering of the fireball
// and the sun (forward-peaked: edges toward the ball glow), and multiply scattered light that diffuses in from every lit face
// and takes on the soil's bulk colour (dense dust is darker inside but stays warm dust-coloured, never black); sky light
// enters from the top, ground bounce from below
vec3 dwLight(vec3 q, vec3 rd) {
    vec3 lb = normalize(DwBall.xyz - q + vec3(0.0, 1e-3, 0.0));
    float taub = dwShadow(q, lb), taus = dwShadow(q, DwSunDir);
    float depth = max(gDwSurf - (q.y - dwFloor(q.xz)), 0.0) * gDwSigma;
    float ms = 0.3 + 0.3 * exp(-0.12 * depth);
    float sunUp = smoothstep(-0.05, 0.05, DwSunDir.y);
    // thin dust toward the fireball glows (forward peak: the crest's rim against the ball); the low sun lights the faces
    // turned to it
    vec3 single = dwBallLight(q) * (0.4 * min(dwPhase(dot(rd, lb)), 3.0) * exp(-taub))
            + DwSunLight * (0.5 * min(dwPhase(dot(rd, DwSunDir)), 6.0) * exp(-taus)) * sunUp;
    vec3 multiple = dwBallLight(q) * ms * (0.45 + 0.55 * exp(-0.25 * taub)) + DwSunLight * ms * (0.45 + 0.55 * exp(-0.25 * taus)) * sunUp
            + DwSkyLight * (0.03 + 0.15 * exp(-0.3 * depth));
    vec3 albedo = mix(DwAlbedo, DwSandAlbedo, gDwSand), bulk = mix(DwBulk, DwSandBulk, gDwSand);
    return DW_EXPOSURE * (albedo * single + bulk * multiple + bulk * DwGroundLight);
}

const float DW_K = 0.008, DW_S = 4.5;
const int DW_HEAD_SAMPLES = 2;
float dwGridT(float k) {
    float kb = 1.0 / DW_K;
    return k < kb ? k * DW_S : DW_S / DW_K * pow(1.0 + DW_K, k - kb);
}
float dwGridK(float t) {
    float tb = DW_S / DW_K;
    return t < tb ? t / DW_S : 1.0 / DW_K + log(t / tb) / log(1.0 + DW_K);
}

// march of one ray from the eye (direction rd, unit) up to tEnd blocks (the world behind the pixel); jitter in [0, 1);
// foot: pixel size per block of distance. Returns premultiplied light and transmittance.
// Samples sit on a grid fixed along the ray from the eye (geometric steps from the eye, independent of where the
// wall's span starts), so they do not slide through the texture as the front grows; every sample and span ends at tEnd.
vec4 dwMarch(vec3 rd, float tEnd, float jitter, float foot, int maxSteps) {
    vec3 eye = DwEye.xyz;
    // vertical slab from the lowest ground to the envelope top over the highest
    float lo = DwRelief.x - 1.0, hi = DwRelief.y + DwRing.z;
    float t0 = 0.0, t1 = tEnd;
    if (abs(rd.y) < 1e-5) {
        if (eye.y > hi || eye.y < lo) return vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        float ta = (hi - eye.y) / rd.y, tb = (lo - eye.y) / rd.y;
        t0 = max(t0, min(ta, tb));
        t1 = min(t1, max(ta, tb));
    }
    // annulus [inner, front]: clip to the outer circle (inner is skipped by the density's early return); a vertical ray
    // stays at its own radius
    vec2 oc = eye.xz;
    float R = DwRing.x + 4.0;
    float A = dot(rd.xz, rd.xz), Bq = dot(oc, rd.xz), C = dot(oc, oc) - R * R;
    if (A < 1e-10) {
        if (C > 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        float disc = Bq * Bq - A * C;
        if (disc < 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
        float sq = sqrt(disc);
        t0 = max(t0, (-Bq - sq) / A);
        t1 = min(t1, (-Bq + sq) / A);
    }
    if (t0 >= t1) return vec4(0.0, 0.0, 0.0, 1.0);
    // sample grid fixed along the ray from the eye: 4.5-block steps to 4.5/K blocks, then geometric (step K × distance:
    // 8 m at 1 km, 32 m at 4 km); the history averages the moving offset within each step (with history, flicker and the
    // approach's coherence match a 2.5-block grid, at two thirds of the cost)
    float k = floor(dwGridK(t0));
    // n: cells to the next sample (strides grow over empty air); kLast: the last sampled cell
    float n = 1.0, kLast = k - 1.0;
    vec3 light = vec3(0.0), c = vec3(0.0);
    float T = 1.0, lit = -1.0;
    for (int i = 0; i < 400; i++) {
        float t = dwGridT(k);
        if (i >= maxSteps || t >= t1 || T < 0.02) break;
        float dt = min(dwGridT(k + 1.0), t1) - t;
        float tt = t + dt * jitter;
        vec3 q = eye + rd * tt;
        float bound = dwBound(q);
        if (bound < 0.0) {
            // no young dust at this radius: long strides
            kLast = k; k += n; n = min(n * 2.0, 16.0);
            continue;
        }
        float hq = q.y - dwFloor(q.xz);
        if (hq > bound) {
            if (rd.y < 0.0) {
                // above the dust here: down to its bound over the highest ground of the map (rising ground ahead may lift the
                // dust toward the ray), onto the next grid cell (a stride that lands in dust backs up)
                kLast = k;
                k = max(k + 1.0, ceil(dwGridK(t + max(q.y - DwRelief.y - bound, 0.0) / -rd.y)));
                n = 1.0;
            } else {
                kLast = k; k += n; n = min(n * 2.0, 8.0);
            }
            continue;
        }
        gDwFoot = max(tt * foot, dt * 0.3);
        gDwHead = false;
        float s = dwDensity(q, false);
        if (gDwHead) {
            // the young head: DW_HEAD_SAMPLES samples spread over the step, averaged (its face turns opaque within a step and
            // runs 15 m a frame at the front's speed)
            float surf = gDwSurf, sigma = gDwSigma, sand = gDwSand, sum = s;
            for (int j = 1; j < DW_HEAD_SAMPLES; j++) sum += dwDensity(eye + rd * (t + dt * fract(jitter + float(j) / float(DW_HEAD_SAMPLES))), false);
            s = sum / float(DW_HEAD_SAMPLES);
            gDwSurf = surf;
            gDwSigma = sigma;
            gDwSand = sand;
        }
        if (s > 1e-5) {
            if (k - kLast > 1.0) {
                // a stride landed in dust: single cells from just after the last empty sample
                k = kLast + 1.0; n = 1.0;
                kLast = k - 1.0;
                continue;
            }
            // light at every sample in the young dust; in the settled dust every other grid cell (the history averages the
            // fixed grid's two parities; the light there changes over tens of metres)
            if (!gDwOld || lit < 0.0 || fract(k * 0.5) < 0.25) c = dwLight(q, rd);
            lit = 1.0;
            // the thin soft edge, veil and puffs over the dense top: four cells per sample (at grazing views they are
            // hundreds of metres of ray)
            float cells = hq > gDwSurf + 2.0 ? 4.0 : 1.0;
            float span = min(dwGridT(k + cells), t1) - t;
            float al = exp(-s * span);
            light += T * (1.0 - al) * c;
            T *= al;
            kLast = k + cells - 1.0; k += cells; n = 1.0;
        } else {
            kLast = k; k += n; n = min(n * 2.0, 8.0);
        }
    }
    return vec4(light, T);
}
