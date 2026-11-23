// Nuclear dust wave (client/fx/layer/DustWallPass; unified-wave probe): one presentation model (presentation, not physical) of a
// detonation's ground dust over its whole lifetime: born at zero on the contact line, a small uneven hump, settled within about
// a second, the layer behind it, its haze thinning and drifting with the wind for hours, filled with matte, billowed dust, and
// its light. Every ground point's dust is one function of its own age since the front passed it.
// Physics enters only as inputs the screen shows (DustWallPass.Frame): where the front is and when it passed a radius
// (table: the mod's in-game front), the settled layer height there and how much dust was lofted, the world wind, the
// fireball, sun and sky light. Everything else (hump, rise, settling, billows, their motion, light response) is this model's.
// Shared verbatim by the mod's pass (fx_dustwall.fsh) and the shader harness.
//
// Positions are relative to ground zero's ground point, blocks (= metres); the eye is DwEye from there.

uniform sampler3D DwNoise;
// (eye x, y, z from ground zero's ground point; seconds since the burst on the detonation clock)
uniform vec4 DwEye;
// (front radius now; 0; envelope top, blocks; table reach)
uniform vec4 DwRing;
uniform vec4 PallYoung;
// table (DW_NODES × (1 + DW_AGES), RGBA32F, PallField.lifetime): node i at radius i / (DW_NODES − 1) × reach. Row 0: (front
// arrival, model s since the burst; dust over hard ground relative to soil, PallField.paved; 0; 0). Row 1 + k, age node k
// (age = DwLife.y · ((1 + DwLife.x / DwLife.y)^(k / (DW_AGES − 1)) − 1)): (the presentation layer top, blocks; the column's
// vertical optical depth the transfer reads; both slopes per age node)
uniform sampler2D Sampler2;
// (the table's last age, model s; its age scale, model s; drift of the lofted dust, blocks per model second of its age: the
// detonation's wind × PallField.DRIFT)
uniform vec4 DwLife;
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
// (wind x, z, blocks/s; 0; 0): unused, the drift is DwLife.zw
uniform vec4 DwWind;
// fire light from below on dust over a fully burning zone (linear, PallField.fireLight through pallFlame); the zone's share per
// node is the table's row 0 z
uniform vec3 DwFire;
// presentation: the dust's exposure (1 while the fireball lights it; DW_ADAPT once only sun and sky do, following the
// fireball's light down: NukePall.youngWall), like the eye adapting after the flash: the low sun's dust read as dark smoke
uniform float DwAdapt;
// the light's detail level (the preset's rung, DustWallPass): 0 the textured level's full light (two shadow taps of two reads
// each), 1 one read per shadow tap (the billows over the next 12 m; the 70 m swell shadow dropped), 2 the two-stream light of
// the haze level on the textured density (no shadow taps)
uniform float DwDetail;
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

#moj_import <incoming:wall_ownership.glsl>
// the pall's accepted light law (two Eddington streams after delta-Eddington in the slab: pallDiffuse, PallLight)
#moj_import <incoming:pall.glsl>
// presentation exposure of the wall's light (Silo S3E09 dust band: display luma median ~0.35)
const float DW_EXPOSURE = 0.22;
// head timing, seconds of age (presentation): rise e-fold at the contact line, hump hold, width of the hump's fall
const float DW_RISE = 0.12, DW_HOLD = 0.25, DW_FALL = 0.3;
// most the bulges, the billowed surface and its soft edge (DW_HAZE) lift the dust over its envelope, shares (the ray bound,
// dwBound)
// (the top behind the head is uneven at several scales, no cap: swells up to ±DW_BULGE of the layer at 150–400 m, billows,
// rising puffs and a thin veil; on the head the swells are small, so its crest, 1.4–1.6 of the layer, still stands out)
const float DW_BULGE = 0.35, DW_BILLOW = 0.15, DW_HAZE = 1.2;
// presentation (est.): the wind's shear stretches the haze's features along the wind, ×(1 + a / DW_SHEAR_S); the slow
// plumes over hot ground (the fire zone, the law's buoyant rise) lift the swells' crests by up to DW_PLUME of the top over
// the first minutes, and sink back as the ground cools
const float DW_SHEAR_S = 300.0, DW_PLUME = 0.6;

// First and last occupied distances and the sum of distances weighted by visible opacity.
vec3 gDwDepth = vec3(0.0);
// Pixel footprint at the sample, blocks.
float gDwFoot = 0.0;
// set by dwDensity at its sample: the billowed dust surface above it, its full extinction and its share of sand (the light
// reads them)
float gDwSurf = 0.0, gDwSigma = 0.0, gDwSand = 0.0;
// set by dwDensity: the swell at the sample (dwBulge, share of the layer): sags sit in the shade of the swells around them
float gDwSwell = 0.0;

// table row 0 at ground radius r: (front arrival, model s; paved share)
vec3 dwRow0(float r) {
    float x = clamp(r / max(DwRing.w, 1.0), 0.0, 1.0) * (DW_NODES - 1.0);
    int i = int(min(floor(x), DW_NODES - 2.0));
    return mix(texelFetch(Sampler2, ivec2(i, 0), 0).xyz, texelFetch(Sampler2, ivec2(i + 1, 0), 0).xyz, x - float(i));
}
float dwArrival(float r) {
    return dwRow0(r).x;
}

const float DW_AGES = 32.0;
// the table's age coordinate of age a (model s): log-spaced nodes, fine at birth, coarse over hours
float dwAgeU(float a) {
    return log(1.0 + max(a, 0.0) / DwLife.y) / log(1.0 + DwLife.x / DwLife.y) * (DW_AGES - 1.0);
}
// one node's law at age coordinate u: cubic Hermite between the age nodes (values and slopes stored: C1 in age, no kink at a
// node); (presentation layer top, blocks; optical depth)
vec2 dwLawAt(int i, float u) {
    float k = clamp(floor(u), 0.0, DW_AGES - 2.0), f = clamp(u - k, 0.0, 1.0);
    // (u at the last node: f = 1 of the last interval)
    vec4 p0 = texelFetch(Sampler2, ivec2(i, 1 + int(k)), 0), p1 = texelFetch(Sampler2, ivec2(i, 2 + int(k)), 0);
    float f2 = f * f, f3 = f2 * f;
    float h00 = 2.0 * f3 - 3.0 * f2 + 1.0, h10 = f3 - 2.0 * f2 + f, h01 = -2.0 * f3 + 3.0 * f2, h11 = f3 - f2;
    return max(h00 * p0.xy + h10 * p0.zw + h01 * p1.xy + h11 * p1.zw, vec2(0.0));
}
// the law at ground radius r and age a (model s since the front passed there): (presentation layer top, blocks; the column's
// vertical optical depth for the transfer); none before arrival and past the table's last age (fading over its last node)
vec2 dwLaw(float r, float a) {
    if (a <= 0.0) return vec2(0.0);
    // past the table's last age the top stays, the depth is gone (faded over the last node)
    float u = min(dwAgeU(a), DW_AGES - 1.0);
    float x = clamp(r / max(DwRing.w, 1.0), 0.0, 1.0) * (DW_NODES - 1.0);
    int i = int(min(floor(x), DW_NODES - 2.0));
    vec2 v = mix(dwLawAt(i, u), dwLawAt(i + 1, u), x - float(i));
    v.y *= 1.0 - smoothstep(DW_AGES - 2.0, DW_AGES - 1.0, dwAgeU(a));
    return v;
}

// the density transfer (presentation): the drawn column's vertical optical depth from the law's. τ itself up to 1 (thin haze
// hides what the law's optical depth hides, Koschmieder); compressive above it, the slope falling from 1 smoothly to
// DW_G_SLOPE over DW_G_KNEE (C1 at τ = 1; the matte, semi-transparent wall: the law's columns of tens to hundreds would draw
// an opaque skin). Fitted to take 33's drawn column at 4.5 km from 1 Mt, ages 1–24 s (tools/uw_probe/probe_gl.py)
const float DW_G_SLOPE = 0.073, DW_G_KNEE = 0.5;
float dwTransfer(float tau) {
    if (tau <= 1.0) return tau;
    float x = tau - 1.0;
    return 1.0 + DW_G_SLOPE * x + (1.0 - DW_G_SLOPE) * DW_G_KNEE * (1.0 - exp(-x / DW_G_KNEE));
}
// ∫ of the noise-free vertical profile (dwDensity's prof · strat · the ground fade) over a settled layer of top h, blocks
// (fit within 0.7 % for h ≥ 6): σ = τ_d · profile / dwProfileInt(h) gives the column τ_d
float dwProfileInt(float h) {
    return max(0.9483 * h - 2.854, 1.0);
}

// the ground point the air at ground point p came from: p minus the drift of its dust since its own birth (the law's drift:
// the detonation's wind × DRIFT, from the front's arrival there). Fixed point q = p − D·age(q), two steps from
// q₀ = p − D·age(p); the drift is a small share of the front's speed, so it converges at once
vec2 dwSource(vec2 p) {
    vec2 D = DwLife.zw;
    if (dot(D, D) <= 0.0) return p;
    vec2 q = p - D * max(DwEye.w - dwArrival(length(p)), 0.0);
    q = p - D * max(DwEye.w - dwArrival(length(q)), 0.0);
    return p - D * max(DwEye.w - dwArrival(length(q)), 0.0);
}

// decaying turbulence (presentation, by the decaying-turbulence law: velocity ∝ (1 + a/τ)^(−0.6), energy exponent 1.2,
// Comte-Bellot & Corrsin 1966; τ 300 s (est.): the young layer's eddies, take 33's ~25 s violent phase on top): the residual
// eddies' share of their first strength, and their phase (∫ of the speed: eddies turn over ever more slowly, never stop)
const float DW_EDDY_S = 300.0;
float dwEddyAmp(float a) {
    return pow(1.0 + max(a, 0.0) / DW_EDDY_S, -0.6);
}
float dwEddyPhase(float a) {
    float x = max(a, 0.0) / DW_EDDY_S;
    // (a series below x = 0.01: the difference of powers cancels in float32)
    return x < 0.01 ? max(a, 0.0) * (1.0 - 0.3 * x + 0.14 * x * x) : DW_EDDY_S / 0.4 * (pow(1.0 + x, 0.4) - 1.0);
}
// contrast of the billow texture (presentation): the turbulence decays and fine dust dilutes, the haze smooths over minutes
// to hours (≥ 0.98 over take 33's 24 s)
float dwContrast(float a) {
    return pow(1.0 + max(a, 0.0) / 600.0, -0.5);
}
// the textured share of the dust at age a (the haze level takes the rest): the billows' turbulence has decayed and the fines
// have diluted; the noise-free column with the pall's closed-form light is the same dust at a level that costs a twentieth.
// A quintic smooth step over [DW_HAZE_FROM, DW_HAZE_TO] (C2: exactly 1 before and 0 after, so outside the window only one
// level is evaluated; the C3 rule holds over its 36 s, a ψ-ratio step over 24 s had slope ratios to 19; presentation: the
// texture holds through take 33's ages to 24 s)
const float DW_HAZE_FROM = 24.0, DW_HAZE_TO = 60.0;
float dwTextured(float a) {
    float x = clamp((a - DW_HAZE_FROM) / (DW_HAZE_TO - DW_HAZE_FROM), 0.0, 1.0);
    return 1.0 - x * x * x * (x * (6.0 * x - 15.0) + 10.0);
}
// a smooth max(a − c, 0) (softplus, width 1 s): no kink in any age function
float dwAfter(float a, float c) {
    float x = a - c;
    return x > 20.0 ? x : log(1.0 + exp(x));
}

// a smooth ramp in age from 0 to 1 centred on c, width w (logistic: never exactly flat, no kink at its ends, where a
// smoothstep's slope ends; C3's rule)
float dwRamp(float a, float c, float w) {
    return 1.0 / (1.0 + exp(-(a - c) / w));
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
    // (MEDIUM and LOW: the finest octave at its mean)
    int octaves = DwDetail > 0.5 ? 3 : 4;
    for (int i = 0; i < 4; i++) {
        if (i >= octaves) {
            s += a * 0.5;
            continue;
        }
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
    // (LOW: the finest billow octave at its mean)
    int octaves = DwDetail > 1.5 ? 2 : 3;
    for (int i = 0; i < 3; i++) {
        if (i >= octaves) {
            s += a * 0.42;
            continue;
        }
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

// a ground point's seeds (0..1, mean ~0.5): x ~60 m, y ~200 m, z the overshoot's (~40–160 m); smooth two-octave noise on a
// plane oblique to the noise lattice, domain-warped by one shared ~600 m field, so no cell, row or period shows. One value
// per lattice cell (value noise flattens at its nodes) laid the wall's birth line as a staircase of identical steps and
// scallops along the front
float dwSeedAt(vec2 p, float scale, float k) {
    vec3 o = vec3(p.x, p.y, 0.43 * p.x - 0.71 * p.y) / scale + vec3(0.0, 0.0, k);
    return 0.65 * dwNoise3(o) + 0.35 * dwNoise3(o * 2.03 + 5.3);
}
vec3 dwSeeds(vec2 xz) {
    vec2 w = vec2(dwNoise3(vec3(xz / 600.0, 3.1)), dwNoise3(vec3(xz / 600.0, 8.7))) - 0.5;
    vec2 p = xz + w * 240.0;
    float s1 = dwSeedAt(p, 60.0, 17.3), s2 = dwSeedAt(p, 200.0, 29.1);
    return vec3(s1, s2, 0.6 * s2 + 0.4 * s1);
}

// overshoot of a ground point's column over the settled layer for its seed: 1.54–1.76 of the envelope (its visible crest
// ~1.4–1.6x the layer behind), so the hump is uneven along the front
float dwHump(float seed) {
    return 1.54 + 0.22 * smoothstep(0.25, 0.75, seed);
}

// the dust envelope at age a (s since the front passed) over settled height h with hump k (presentation): born at zero on
// the contact line, up with e-fold DW_RISE, held as a hump for DW_HOLD, down to the layer within about a second
float dwEnvelope(float a, float h, float k) {
    if (a <= 0.0) return 0.0;
    float x = max(a - DW_HOLD, 0.0) / DW_FALL;
    return h * (1.0 - exp(-a / DW_RISE)) * (1.0 + (k - 1.0) * exp(-x * x));
}

// the head's motion clock (presentation, Artem 22.11 on take 23: the head read as a too-mobile super-matter): the young
// air's eddies, gusts and outflow run 2.5x slower at the front, 4x slower beyond ~3 km from the eye (far detail must not
// churn), and come up to the real rate over ~2 s of age; its rate never exceeds 1, so nothing speeds up later
float dwClock(float a, vec2 xz) {
    float slow = mix(0.4, 0.25, smoothstep(800.0, 3000.0, length(xz - DwEye.xz)));
    return a - (1.0 - slow) * 2.0 * (1.0 - exp(-max(a, 0.0) / 2.0));
}

// the air mass the dust rides in: position the lofted air at q (age a, radius r, envelope top) came from — the texture is
// fixed in it, so motion carries billows and does not slide them through the dust (presentation). The post-shock wind
// whirls it hardest in the first seconds and calms with the wind (eddy energy e-fold 25 s: the lofted layer stays turbulent for tens of seconds, floor 30 %, violent first
// seconds e-fold 4 s); eddies 32 × 20 m turn with phase = integral of their energy: violent while the wave's wind
// blows, then at the speed of real dust fronts (haboob drone footage: a 16-pixel patch decorrelates at ~0.45/s); the whole mass sloshes out with the wind and back with the
// negative phase, the top more (rolls); the world wind drifts it all
vec3 dwAir(vec3 q, float a, float r, float top, out float ph) {
    float E = exp(-a / 25.0), Ef = exp(-a / 4.0);
    float A = 0.3 * dwEddyAmp(a) + 0.7 * E + 0.9 * Ef;
    ph = 0.5 * (25.0 * (1.0 - E) + 0.25 * dwEddyPhase(a)) + 3.5 * (1.0 - Ef);
    vec3 b = q / vec3(32.0, 20.0, 32.0);
    // one octave: a second one shears the 20 m billows into marbled, liquid-looking swirls
    vec3 w = vec3(dwNoise3(b + vec3(0.0, ph * 0.15, 0.0)), dwNoise3(b + vec3(5.2, 1.3 - ph * 0.14, 2.8)),
            dwNoise3(b + vec3(2.1, 7.7, ph * 0.15))) - 0.5;
    vec3 pw = q + w * vec3(40.0, 26.0, 40.0) * A * smoothstep(0.0, 0.45 * max(top, 1.0), q.y);
    vec2 rdir = q.xz / max(r, 1.0);
    float yy = clamp(q.y / max(top, 1.0), 0.0, 1.5);
    // the blast wind behind the front (presentation, by the time since the front passed here): the positive phase throws
    // the dust out (~25 m over ~2 s, the dust stays near where it was torn up), the weaker, longer negative phase stalls
    // it and brings part of it back over ~6 s, the top further than the base (rolls fold over); then the afterwind draws
    // the low dust in toward the rising fireball, a few m/s, stronger closer in
    float out_ = 25.0 * (1.0 - exp(-a / 1.0)) - 15.0 * dwRamp(a, 5.0, 1.0);
    // (dying out over ~2 min as the ball rises away, est.: take 33 drew it for 24 s only)
    float aw = dwAfter(a, 5.0) / 120.0;
    float drawn = aw < 1e-3 ? aw * (1.0 - 0.5 * aw) : 1.0 - exp(-aw);
    float in_ = 3.0 * clamp(2500.0 / max(r, 1.0), 0.3, 2.0) * 120.0 * drawn * (1.0 - 0.7 * min(yy, 1.0));
    pw.xz -= rdir * (out_ * (0.3 + 0.7 * yy) - in_);
    // residual turbulence behind the front: neighbouring parcels circle on slow orbits (20 m, 5 m/s; phases varying over
    // ~150 m), so the settled dust keeps swirling and drifting sideways as real dust does, never frozen, without the
    // endless shear a steady flow field would stretch it into; they turn either way (sense and ellipse from the phase field,
    // zero mean): orbits of one sense everywhere read as the whole mass turning around ground zero
    float sw = 6.2832 * dwNoise3(vec3(q.xz / 150.0, 71.3)), hand = sin(3.0 * sw);
    float orbit = 0.25 * dwEddyPhase(a);
    pw.xz -= 20.0 * vec2(cos(orbit + sw), hand * sin(orbit + sw)) * (1.0 - exp(-a / 1.5)) * (0.6 + 0.4 * yy);
    // gusts behind the front: the hit air keeps surging forward and sideways in ~200 m gust cells whose push changes over
    // a few seconds, strongest just after the front and calming over ~15 s (presentation)
    vec2 gp = q.xz / 220.0 + vec2(0.0, 0.35 * a);
    vec2 gust = vec2(dwNoise3(vec3(gp, 3.3)), dwNoise3(vec3(gp + 7.7, 9.1))) - 0.5;
    pw.xz -= gust * 30.0 * (1.0 - exp(-a / 0.8)) * exp(-a / 15.0) * (0.5 + 0.5 * yy);
    // (the wind's drift is the law's, dwSource: q is already the ground point the air came from)
    return pw;
}

// smooth saturation to ±1 (no flat shelves where a clamp would cut the tops)
float dwSoft(float x) {
    return x / sqrt(1.0 + x * x);
}

// swells and sags of the mass in the air (Silo: higher here, lower there): domain-warped ≈350 m swells carrying ≈120 m
// lumps, up to ±DW_BULGE of the layer behind the head, a fifth of that on the head, growing in over 1.5–4 s (the crest
// stands out of a layer near its mean right behind it); they
// swell and sag slowly as the dust settles
// the swell field at air-mass point xz (4 fetches; L: its large-scale value 0..1, for the haze level's plumes)
float dwSwellField(vec2 xz, float a, out float L) {
    vec2 p = xz / 220.0;
    float w = dwNoise3(vec3(p * 0.4, 4.4)) - 0.5;
    p += 0.8 * vec2(w, -w);
    float e = dwEddyPhase(a);
    L = 0.6 * dwNoise3(vec3(p, 7.1 + 0.08 * e)) + 0.4 * dwNoise3(vec3(xz / 90.0, 0.37 + 0.12 * e));
    // hollows opening in the blanket: in patches ~160 m across the layer sinks by up to 40 % over 3–10 s behind the front,
    // drifting slowly (presentation: the mass shifting as the blast wind reverses)
    float hollow = smoothstep(0.62, 0.8, dwNoise3(vec3(xz / 160.0, 2.2 + 0.03 * e))) * dwRamp(a, 6.5, 1.2);
    return DW_BULGE * dwSoft((L - 0.5) * 4.0) * mix(0.2, 1.0, dwRamp(a, 2.75, 0.45)) - 0.4 * hollow;
}
float dwBulge(vec3 pw, float a) {
    float L;
    return dwSwellField(pw.xz, a, L);
}

// a buoyant tongue above the top: about one 110 m cell in three lifts a rounded column of dust up to ~35 % of the layer
// above the top (inside the ray's bound), 1–15 s after the front, over ~6 s, thinning as it rises over ~15 s (presentation)
float dwPuff(vec3 pw, float top, float a) {
    vec2 cell = floor(pw.xz / 110.0);
    float h1 = dwNoise3(vec3(cell * 1.37, 91.2)), h2 = dwNoise3(vec3(cell * 1.37 + 0.5, 47.7));
    if (h1 < 0.5) return 0.0;
    float born = 1.0 + 14.0 * h2, age = a - born;
    if (age <= 0.0) return 0.0;
    // (each puff stays inside its own cell: grown past it, it was cut by the cell's edges into rectangles with seams)
    vec2 c = (cell + 0.5) * 110.0 + (vec2(h2, h1) - 0.5) * 20.0;
    float rise = 0.35 * top * (1.0 - exp(-age / 6.0)) * (h1 - 0.4) * 1.7;
    // (no wider than the layer leaves room under the ray's bound: a thin layer's tongue was cut flat)
    float radius = min(min(14.0 + 4.0 * age, 36.0), 0.43 * top + 7.0);
    vec2 d = vec2(length(pw.xz - c), (pw.y - top * 0.9 - rise) * 1.2);
    return 0.8 * exp(-age / 15.0) * (1.0 - smoothstep(0.5, 1.0, length(d) / radius)) * smoothstep(0.0, 1.0, age);
}

// the smooth dust top over ground point xz (no billows; the light's shadow surface), blocks above the local ground; 0 where
// no young dust
float dwTopAt(vec2 xz) {
    vec2 q = dwSource(xz);
    float r = length(q);
    if (r > min(DwRing.x + 2.0, DwRing.w) || r < DwCut - 50.0) return 0.0;
    float a = DwEye.w - dwArrival(r);
    vec2 law = dwLaw(r, a);
    if (a <= 0.0 || law.y <= 0.0) return 0.0;
    // without the hump: its shadow would sweep over the near dust at the front's speed (lit patches sliding at the passage)
    float top = dwEnvelope(a, law.x, 1.0), ph;
    vec3 pw = dwAir(vec3(q.x, 0.6 * top, q.y), dwClock(a, q), r, top, ph);
    return top * (1.0 + dwBulge(pw, a));
}

// highest the dust can reach above the local ground at a point (blocks), below 0 where no young dust is
float dwBound(vec3 g) {
#ifdef DW_ANALYTIC
    // the harness's analytic media (tools/uw_probe/probe_gl.py: E8 accuracy of the march)
    return dwAnalyticBound(g);
#endif
#ifdef DW_BOUND_ONESTEP
    vec2 q = g.xz - DwLife.zw * max(DwEye.w - dwArrival(length(g.xz)), 0.0);
#else
    vec2 q = dwSource(g.xz);
#endif
    float r = length(q);
    if (r > min(DwRing.x + 2.0, DwRing.w) || r < DwCut) return -1.0;
    float a = DwEye.w - dwArrival(r);
    if (a < 0.0) return -1.0;
    vec2 law = dwLaw(r, a);
    if (law.y <= 0.0) return -1.0;
    // the envelope here (an older point inward is no higher: the hump is at the front) lifted by the bulges, billows and edge;
    // 1.6: the largest hump, for the forward lean of the crest
    float env = a < 1.5 ? law.x * 1.8 : dwEnvelope(a, law.x, 1.8);
    float full = env * (1.0 + DW_BULGE) * DW_HAZE + 6.0;
    // the haze level's crests and plumes (dwHaze: top × (1 + swell + plume), its soft edge to 1.3 of it)
    if (dwTextured(a) < 0.999) full = max(full, env * (1.0 + DW_BULGE + DW_PLUME) * 1.3 + 6.0);
#ifndef DW_BOUND_WIDE
    // the swell here (dwBulge at the ground point, without the eddies' shift: ±0.12 of margin for it), its billowed top and
    // soft edge and veil over it: the dust's own reach. A ray above the swollen top strides as over clear air instead of
    // evaluating the whole texture at every 24-block cell up to the widest swell's reach (the young view's cost: 52 strides a
    // ray, 40 of them in clear air under the old bound). Puffs rise above it only in the cells that hold one (dwPuff, h1 ≥ 0.5,
    // its cell and the eddies' reach around it)
    if (a > 1.5 && dwTextured(a) > 0.001) {
        float sw = dwBulge(vec3(q.x, 0.0, q.y), a);
        float reach = env * (1.0 + max(sw + 0.12, -0.3)) * (1.0 + DW_BILLOW) * 1.12 + 0.1 * env + 6.0;
        if (a < 30.0) {
            vec2 c0 = floor((q - 40.0) / 110.0), c1 = floor((q + 40.0) / 110.0);
            float h = max(max(dwNoise3(vec3(c0 * 1.37, 91.2)), dwNoise3(vec3(c1 * 1.37, 91.2))),
                    max(dwNoise3(vec3(vec2(c0.x, c1.y) * 1.37, 91.2)), dwNoise3(vec3(vec2(c1.x, c0.y) * 1.37, 91.2))));
            if (h >= 0.5) reach = full;
        }
        return min(reach, full);
    }
#endif
    return full;
}

// noise-free mode (the consumers' reference, the thin tail and plan B's far level): mean seeds and texture, no swells or puffs
bool gDwMean = false;
// diagnostics: why the last density was zero (1 ring/cut, 2 before arrival, 3 no law, 4 above the ceiling, 5 lean-back age, 6 thin profile)
float gDwWhy = 0.0;
// set by dwDensity at its sample: the textured share (dwTextured; gDwForceF ≥ 0 forces it: harness), and the haze level's
// column there: its drawn optical depth, the depth above the sample, the fire zone's share under it, its top
float gDwF = 1.0, gDwForceF = -1.0, gDwHazeTc = 0.0, gDwHazeAbove = 0.0, gDwHazeFire = 0.0, gDwHazeTop = 0.0;

// share of a column's optical depth above relative height x of its top (the profile's stratification, 1.25 − 0.5x)
float dwColumnAbove(float x) {
    float u = clamp(x, 0.0, 1.0);
    return 1.25 * (1.0 - u) - 0.25 * (1.0 - u * u);
}

// The haze level (noise-free): the same column (the law's top and depth through dwTransfer, the same profile, ground fade,
// soil, open air and table end), no seeds, billows, swells or puffs. q: the source ground point and height above the ground
float dwHaze(vec3 g, vec3 q, float r, float a, vec2 law, bool coarse) {
    float h = law.x * 0.95;
    // the haze's own structure: the textured level's swell field (the same fields: no seam across the blend) at its air-mass
    // point, stretched along the wind; its crests rise as plumes. Its matter moves with the air (q: the law's drift)
    vec2 wd = dot(DwLife.zw, DwLife.zw) > 1e-12 ? normalize(DwLife.zw) : vec2(1.0, 0.0);
    vec2 xs = q.xz + wd * (dot(q.xz, wd) * (1.0 / (1.0 + a / DW_SHEAR_S) - 1.0));
    float L;
    float swell = dwSwellField(xs, a, L);
    vec3 row = dwRow0(r);
    float hot = 0.3 + 0.7 * row.z;
    float plume = DW_PLUME * hot * smoothstep(0.58, 0.85, L) * (1.0 - exp(-a / 45.0)) * exp(-a / 900.0);
    float top = max(dwEnvelope(a, h, 1.65) * (1.0 + swell + plume), 1.0);
    if (q.y > top * 1.3 + 6.0) return 0.0;
    float edge = 0.22 * top + 4.0;
    float prof = 1.0 - smoothstep(top - edge, top + 0.4 * edge, q.y);
    float strat = 1.25 - 0.5 * clamp(q.y / top, 0.0, 1.0);
    vec2 soil = dwSoilAt(q.xz);
    float paved = 1.0 + soil.x * (row.y - 1.0);
    float tc = dwTransfer(law.y) * (1.0 + 0.5 * exp(-a / 1.1)) * paved
            * (1.0 - smoothstep(DwRing.w * (1.0 - 1.0 / (DW_NODES - 1.0)), DwRing.w, r));
    // (normalised over its own swollen top: the swells and plumes reshape the column, its optical depth stays the law's
    // through g, so the consumers' noise-free reference holds)
    float sigma = tc / dwProfileInt(top) * prof * strat * smoothstep(0.0, 3.0, q.y) * smoothstep(0.0, 0.08, a);
    if (!coarse) {
        sigma *= dwOpen(g);
        gDwSand = clamp(soil.y / max(paved, 1e-6), 0.0, 1.0);
        gDwHazeTc = tc;
        gDwHazeAbove = tc * dwColumnAbove(q.y / top);
        gDwHazeFire = row.z;
        gDwHazeTop = top;
    }
    return sigma;
}
// the noise's gain on the column's extinction that keeps its mean transmission over the billow tile the noise-free column's
// (review N6: equal mean extinction gives a higher mean transmission, by Jensen). The noisy column's mean optical depth is
// DW_NOISE_MEAN below the noise-free one (the swells' hollows, the texture's means), and a Gamma(k) model of its spread:
// E[e^(−κτm)] = (1 + κτ/k)^(−k) = e^(−τ) for κ = k(e^(τ/k) − 1)/τ; above τ ≈ 4 nothing shows through either way, so κ holds
// its value there (a soft min) instead of growing. Fitted on 11 columns (tools/uw_probe/probe_gl.py: thin, 0.05 ≤ τ ≤ 6)
const float DW_NOISE_K = 12.0, DW_NOISE_MEAN = 1.058;
float dwNoiseGain(float tau) {
    float t = 4.0 * tau / (4.0 + tau);
    float gain = t < 1e-4 ? 1.0 : DW_NOISE_K * (exp(t / DW_NOISE_K) - 1.0) / t;
    return DW_NOISE_MEAN * gain;
}

// dust extinction (1/block) at g; coarse: light paths (the billows' two first octaves, no streaks, no fine octaves)
float dwDensity(vec3 g, bool coarse) {
    if (!coarse) gDwSigma = 0.0;
#ifdef DW_ANALYTIC
    gDwSurf = 1e9;
    gDwF = gDwForceF >= 0.0 ? gDwForceF : 0.0;
    return dwAnalytic(g);
#endif
    // heights above the local ground (the destination's terrain); the ground point the air came from (the law's drift)
    float hq = g.y - dwFloor(g.xz);
    vec2 src = dwSource(g.xz);
    vec3 q = vec3(src.x, hq, src.y);
    float r = length(q.xz);
    gDwWhy = 1.0;
    if (r > DwRing.x + 2.0 || r < DwCut) return 0.0;
    float a0 = DwEye.w - dwArrival(r);
    gDwWhy = 2.0;
    if (a0 < 0.0) return 0.0;
    vec2 law = dwLaw(r, a0);
    gDwWhy = 3.0;
    if (law.y <= 0.0) return 0.0;
    gDwWhy = 6.0;
    // the haze level where the texture has faded, blended by the same fade function (no seam in time or space)
    float F = gDwForceF >= 0.0 ? gDwForceF : dwTextured(a0);
    if (!coarse) gDwF = F;
    float haze = 0.0;
    // (the cheapest level lights the textured dust with the haze level's two streams: its column is read too)
    if (F < 0.999 || (DwDetail > 1.5 && !coarse)) {
        haze = dwHaze(g, q, r, a0, law, coarse);
        if (F <= 0.001) {
            if (!coarse) {
                gDwSigma = haze;
                gDwSurf = gDwHazeTop;
                gDwSwell = 0.0;
            }
            return haze;
        }
    }
    bool mean = gDwMean;
    // One model, per-point birth (LOOK Trait 0): the front is a line of births running over the ground. Each ground point
    // tears up its own dust when the front reaches it: its seed (world-fixed, `dwSeeds`) sets its birth a little
    // after the front's arrival (lagging patches open clefts in the front line), how much it raises, its column's height
    // and overshoot; its column rises, overshoots and settles by its own age and stays where it was born (carried only by
    // the wind's drift, dwSource). Nothing rides with the front. The layer behind it, its haze and fade are the same column
    // at later ages: its top and optical depth are the law's at its age (dwLaw), nothing switches at any age.
    // (light paths take the mean point: their shade is smooth)
    vec3 seed = coarse || mean ? vec3(0.5) : dwSeeds(q.xz);
    float s1 = seed.x, s2 = seed.y;
    float delay = 0.12 * clamp(1.6 * s2 - 0.55, 0.0, 1.0) * (0.6 + 0.8 * s1);
    float h = law.x * mix(0.88, 1.02, s2), k = dwHump(seed.z);
    float top0 = max(dwEnvelope(a0, h, k), 1.0), ceiling = top0 * (1.0 + DW_BULGE) * DW_HAZE + 6.0;
    gDwWhy = 4.0;
    if (q.y > ceiling) return 0.0;
    gDwWhy = 6.0;
    // higher up the dust has the profile of a point a little outward (younger): the face is a ramp from the ground up
    // into the crest, leaning back, never ahead of the front
    float yn = clamp(q.y / top0, 0.0, 1.5);
    float a = DwEye.w - dwArrival(r + 40.0 * yn * yn * exp(-a0 / 1.5)) - delay;
    gDwWhy = 5.0;
    if (a <= 0.0) return 0.0;
    gDwWhy = 6.0;
    float top = dwEnvelope(a, h, k), env = top, ph;
    vec3 pw = dwAir(q, dwClock(a, q.xz), r, top, ph);
    float swell = mean ? 0.0 : dwBulge(pw, a);
    if (!coarse) gDwSwell = swell;
    top *= 1.0 + swell;
    // above the highest billowed top, its soft edge and veil (dwSoft ≤ 1: surf ≤ top·(1 + DW_BILLOW)), only a puff can hold dust:
    // the texture is not read there (the young view's rays crossed ~20 such clear cells each, at the full texture's cost)
    if (!coarse && !mean && q.y > top * (1.0 + DW_BILLOW) * 1.12 + 4.0 + 0.4 * (0.22 * top + 4.0)) {
        gDwSurf = top * (1.0 + DW_BILLOW);
        float pf = dwPuff(vec3(pw.x, q.y, pw.z), top, a);
        if (pf <= 0.0) {
            gDwWhy = 7.0;
            return F >= 0.999 ? 0.0 : (1.0 - F) * haze;
        }
        float taup = dwTransfer(law.y) * (1.0 + 0.5 * exp(-a / 1.1));
        float sigp = taup / dwProfileInt(h) * mix(1.0, mix(0.75, 1.25, 0.55 * s1 + 0.45 * s2), dwContrast(a)) * dwNoiseGain(taup) * dwOpen(g);
        gDwSigma = sigp;
        float texp = sigp * pf * (1.0 - smoothstep(ceiling - 8.0, ceiling, q.y)) * smoothstep(0.0, 0.08, a);
        return F >= 0.999 ? texp : mix(haze, texp, F);
    }
    // One frame for the texture: fixed in the air across the ground and carried by the dust's own rise and fall in height
    // (height as a share of this point's envelope, scaled to the settled layer): each column's billows are born at the
    // ground as the front reaches it, rise into the crest and settle with it, and stay where they were torn up. Sliced
    // by a fixed height, the 470 m/s head swept through new air every frame (the far head churned, take 23); riding with
    // the front, the texture slid outward with it (take 26: 2–10 px a frame, "too mobile") and, at the eye, crossed its
    // rays in one or two frames as the front passed under it (the lower frame fluttered)
    float yc = q.y * h / max(env, 2.0) + (pw.y - q.y);
    // the texture's contrast fades as the turbulence decays and the fines dilute (dwContrast)
    float C = dwContrast(a);
    // billows of ~20 × 13 m and finer (light paths see the two first octaves)
    vec3 tq = vec3(pw.x, yc - 0.45 * ph, pw.z) / vec3(20.0, 13.0, 20.0);
    float n = mean ? 0.5 : mix(0.5, coarse ? dwFbm2(tq, 0.0) : dwFbm(tq, 1.0 / 13.0), C);
    // cauliflower lumps of the top, ~35 m and finer, in the same frame
    float bl = coarse || mean ? 0.42 : mix(0.42, dwBillow(vec3(pw.x, yc - 0.3 * ph, pw.z) / vec3(34.0, 22.0, 34.0) + 11.0, 1.0 / 22.0), C);
    // fibrous streaks combed along the radial wind, ~28 m along, 4.5 m across
    float fs = 0.5;
    // (the streaks are HIGH's: MEDIUM and LOW keep their mean)
    if (!coarse && !mean && DwDetail < 0.5) {
        float ral = length(pw.xz);
        float angle = pw.x == 0.0 ? sign(pw.z) * 1.57079633 : atan(pw.z, pw.x);
        fs = mix(0.5, dwFbm2(vec3(ral / 28.0, (yc - 0.25 * ph) / 5.0, angle * ral / 4.5), 0.0), dwLod(1.0 / 2.2) * C);
    }
    // the billowed surface: rises and dips by the texture (cauliflower billows, ragged where the dust is young), within
    // DW_BILLOW of the top: the hump's excess (+30–50 %) stays well above the billows, so the crest reads as a crest
    float torn = 1.0 + 0.5 * exp(-a / 0.25);
    float surf = top * (1.0 + DW_BILLOW * dwSoft(((bl - 0.42) * 3.0 + (n - 0.5) * 2.0) * torn));
    if (!coarse) gDwSurf = surf;
    // a soft top: billow tops thin out over ~a quarter of the layer (an opaque top popped where dips passed in and out of
    // view at grazing angles)
    float edge = 0.22 * top + 4.0;
    float prof = 1.0 - smoothstep(surf - edge, surf + 0.4 * edge, q.y);
    // the drawn column: the law's vertical optical depth through the one transfer (dwTransfer), laid in this profile
    // normalised over the settled layer (dwProfileInt); settling and dilution are the law's alone. The young head is denser
    // (presentation, take 33's: ×1.5 at birth, e-fold 1.1 s)
    float taud = dwTransfer(law.y) * (1.0 + 0.5 * exp(-a / 1.1));
    // over hard ground only debris dust (the pall's own reading: PallField.paved of the soil's mass), sand pales it
    vec2 soil = dwSoilAt(q.xz);
    float paved = 1.0 + soil.x * (dwRow0(r).y - 1.0);
    // (the haze level read the open-air window already; it multiplies both)
    float sigma = taud / dwProfileInt(h) * paved * mix(1.0, mix(0.75, 1.25, 0.55 * s1 + 0.45 * s2), C)
            * (1.0 - smoothstep(DwRing.w * (1.0 - 1.0 / (DW_NODES - 1.0)), DwRing.w, r));
    if (!coarse && !mean) sigma *= dwNoiseGain(taud);
    if (!coarse) {
        sigma *= dwOpen(g);
        gDwSand = clamp(soil.y / max(paved, 1e-6), 0.0, 1.0);
    }
    if (!coarse) gDwSigma = sigma;
    // coarse dust low and dense, finer above (stratified); a thin veil of fines over the uneven top (following it, no
    // shelf); here and there a puff lifted above the top that thins into the veil and drifts with the wind
    float strat = 1.25 - 0.5 * clamp(q.y / max(top, 1.0), 0.0, 1.0);
    float veil = (1.0 - smoothstep(surf, surf * 1.12 + 4.0, q.y)) * smoothstep(surf * 0.8, surf, q.y) * 0.03;
    float puff = coarse || mean || q.y < top * 0.8 ? 0.0 : dwPuff(vec3(pw.x, q.y, pw.z), top, a) * (1.0 - smoothstep(ceiling - 8.0, ceiling, q.y));
    // the lowest metres thin to the ground (soft where no terrain stands under the dust)
    float textured = sigma * smoothstep(0.0, 0.08, a) * smoothstep(0.0, 3.0, q.y)
            * (prof * strat * (0.5 + 1.0 * n) * (0.7 + 0.6 * fs) + (veil + puff) * (0.6 + 0.8 * n));
    return F >= 0.999 ? textured : mix(haze, textured, F);
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
    float near = dwDensity(q + l * 12.0, true) * 26.0;
    if (DwDetail > 0.5) return near;
    vec3 p = q + l * 70.0;
    return max(dwTopAt(p.xz) - (p.y - dwFloor(p.xz)), 0.0) * gDwSigma * 0.8 + near;
}

// light of a dust sample at q (presentation, follows the light on ground-coloured dust): single scattering of the fireball
// and the sun (forward-peaked: edges toward the ball glow), and multiply scattered light that diffuses in from every lit face
// and takes on the soil's bulk colour (dense dust is darker inside but stays warm dust-coloured, never black); sky light
// enters from the top, ground bounce from below
// Henyey–Greenstein phase of asymmetry g, mean 1 over the sphere (cosine c of the scattering angle)
float dwHG(float g, float c) {
    return (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * c, 1.5);
}

// presentation: exposure of the haze level's light, held to the textured level's at their blend (no step in brightness;
// tools/uw_probe/probe_gl.py: light), and, in both levels, sky light reaching the dust at its luminance without the sky's
// blue (the low sun's frames drew dust under a blue sky as grey-blue smoke; the reference haze is warm: Silo S3E09, haboob)
const float DW_HAZE_EXPOSURE = 0.62;
// light of a haze-level sample (the pall's law, PallLight): two Eddington streams after delta-Eddington in the drawn column at
// the sample's depth below its top, from the sun and fireball beams, sky light from above, fire light from below, ground of
// land albedo; plus the beams' single scattering. No shadow taps
vec3 dwHazeLight(vec3 q, vec3 rd) {
    vec3 w = mix(PALL_DUST_W, PALL_SAND_W, gDwSand);
    float gp = PALL_DUST_G, tc = max(gDwHazeTc, 1e-4), tau = clamp(gDwHazeAbove, 0.0, tc);
    float sunUp = smoothstep(-0.05, 0.05, DwSunDir.y), muS = max(DwSunDir.y, 0.12);
    vec3 fS = DwSunLight * sunUp;
    vec3 lb = normalize(DwBall.xyz - q + vec3(0.0, 1e-3, 0.0));
    float muB = clamp(lb.y, 0.12, 1.0);
    vec3 fB = dwBallLight(q);
    vec3 top = vec3(dot(DwSkyLight, vec3(0.2126, 0.7152, 0.0722)));
    vec3 bottom = DwFire * gDwHazeFire;
    float c = gp * -rd.y;
    vec3 d = vec3(pallDiffuse(w.r, gp, tau, tc, PALL_LAND, muS, fS.r, muB, fB.r, top.r, bottom.r, c),
            pallDiffuse(w.g, gp, tau, tc, PALL_LAND, muS, fS.g, muB, fB.g, top.g, bottom.g, c),
            pallDiffuse(w.b, gp, tau, tc, PALL_LAND, muS, fS.b, muB, fB.b, top.b, bottom.b, c));
    vec3 single = 0.25 * (fS * dwHG(gp, dot(rd, DwSunDir)) * exp(-tau / muS) + fB * dwHG(gp, dot(rd, lb)) * exp(-tau / muB));
    return DW_HAZE_EXPOSURE * max(DwAdapt, 1.0) * w * (single + max(d, vec3(0.0)));
}

vec3 dwTexturedLight(vec3 q, vec3 rd) {
    vec3 lb = normalize(DwBall.xyz - q + vec3(0.0, 1e-3, 0.0));
    float taub = dwShadow(q, lb), taus = dwShadow(q, DwSunDir);
    float depth = max(gDwSurf - (q.y - dwFloor(q.xz)), 0.0) * gDwSigma;
    float ms = 0.3 + 0.3 * exp(-0.12 * depth);
    // presentation: the swells read from above as in the Silo frame, raised dust catching more light and sags in the shade
    // of the swells around them (an occlusion of the light the dust scatters, by the local swell)
    float lift = clamp(1.0 + 1.2 * gDwSwell, 0.55, 1.45);
    ms *= lift;
    float sunUp = smoothstep(-0.05, 0.05, DwSunDir.y);
    // thin dust toward the fireball glows (forward peak: the crest's rim against the ball); the low sun lights the faces
    // turned to it
    vec3 single = lift * dwBallLight(q) * (0.4 * min(dwPhase(dot(rd, lb)), 3.0) * exp(-taub))
            + DwSunLight * (0.5 * min(dwPhase(dot(rd, DwSunDir)), 6.0) * exp(-taus)) * sunUp;
    vec3 multiple = dwBallLight(q) * ms * (0.45 + 0.55 * exp(-0.25 * taub)) + DwSunLight * ms * (0.45 + 0.55 * exp(-0.25 * taus)) * sunUp
            + vec3(dot(DwSkyLight, vec3(0.2126, 0.7152, 0.0722))) * (0.03 + 0.15 * exp(-0.3 * depth));
    vec3 albedo = mix(DwAlbedo, DwSandAlbedo, gDwSand), bulk = mix(DwBulk, DwSandBulk, gDwSand);
    return DW_EXPOSURE * max(DwAdapt, 1.0) * (albedo * single + bulk * multiple + bulk * DwGroundLight);
}

// light of a sample: the textured level's (take 33's) and the haze level's, blended by the textured share
vec3 dwLight(vec3 q, vec3 rd) {
    vec3 haze = gDwF < 0.999 || DwDetail > 1.5 ? dwHazeLight(q, rd) : vec3(0.0);
    if (gDwF <= 0.001 || DwDetail > 1.5) return haze;
    return mix(haze, dwTexturedLight(q, rd), gDwF);
}

const float DW_K = 0.0065, DW_S = 3.5;
float dwGridT(float k) {
    float kb = 1.0 / DW_K;
    return k < kb ? k * DW_S : DW_S / DW_K * pow(1.0 + DW_K, k - kb);
}
float dwGridK(float t) {
    float tb = DW_S / DW_K;
    return t < tb ? t / DW_S : 1.0 / DW_K + log(t / tb) / log(1.0 + DW_K);
}

// detail levels of the march (DwFar): x — 1: plan B's far level (beyond the footprint where a pixel spans y blocks, the
// largest billow, the ray integrates the noise-free column in one segment, blended in over a factor of 2), 0: the full march
uniform vec4 DwFar;
// longest stride over clear air inside the dust's bound, blocks
#ifdef DW_GAP_OVERRIDE
const float DW_GAP = DW_GAP_OVERRIDE;
#else
const float DW_GAP = 24.0;
#endif
// the clear stride inside the bound per light detail level (MEDIUM and LOW 36 blocks: E8 holds at each rung; 48 at LOW
// missed thin spans by 0.034)
float dwGap() {
    float k = DwDetail > 0.5 ? 1.5 : 1.0;
    return DW_GAP * k;
}
// diagnostics of the last march (harness): occupied samples, step-cap exits with material left, tail optical depth
int gDwSamples = 0, gDwIters = 0, gDwTailEvals = 0;
// diagnostics: strides with no dust at the radius, above the bound going up, above it going down, clear inside it
vec4 gDwKinds = vec4(0.0);
float gDwWhyHist[8] = float[8](0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
float gDwWhyHq = 0.0;
float gDwCapLeft = 0.0, gDwTailTau = 0.0;

// the noise-free column integrated over [ta, tb] along the ray in n Gauss–Legendre segments of 4 nodes, light at each node:
// the thin tail past the step cap and plan B's far level. Returns premultiplied light; T and the depth moments advance
vec3 dwSegment(vec3 eye, vec3 rd, float ta, float tb, int n, inout float T, vec3 airLight) {
    vec3 light = vec3(0.0);
    if (tb <= ta) return light;
    const vec4 X = vec4(-0.8611363, -0.3399810, 0.3399810, 0.8611363), W = vec4(0.3478548, 0.6521452, 0.6521452, 0.3478548);
    bool was = gDwMean;
    gDwMean = true;
    // log-spaced segments (the eye's side is denser in angle)
    float la = log(max(ta, 1.0)), lb = log(max(tb, 1.0));
    for (int j = 0; j < 8; j++) {
        if (j >= n) break;
        float s0 = exp(mix(la, lb, float(j) / float(n))), s1 = exp(mix(la, lb, float(j + 1) / float(n)));
        if (j == 0) s0 = ta;
        if (j == n - 1) s1 = tb;
        float half_ = 0.5 * (s1 - s0), mid = 0.5 * (s1 + s0);
        for (int m = 0; m < 4; m++) {
            float tt = mid + half_ * X[m], dt = half_ * W[m];
            vec3 q = eye + rd * tt;
            float sg = dwDensity(q, false);
            gDwTailEvals += 1;
            if (sg <= 1e-7) continue;
            float al = exp(-sg * dt);
            vec3 c = dwLight(q, rd);
            float air = dwAirT(tt, rd.y);
            light += T * (1.0 - al) * (air * c + (1.0 - air) * airLight);
            if (T == 1.0) gDwDepth.x = tt;
            gDwDepth.y = tt + dt;
            gDwDepth.z += (T * (1.0 - al)) * tt;
            gDwTailTau += sg * dt;
            T *= al;
        }
    }
    gDwMean = was;
    return light;
}

// march of one ray from the eye (direction rd, unit) up to tEnd blocks (the world behind the pixel); jitter in [0, 1);
// foot: pixel size per block of distance. Returns premultiplied light and transmittance.
// Samples sit on a grid fixed along the ray from the eye (geometric steps from the eye, independent of where the
// dust starts), so they do not slide through the texture as the front grows; every sample and span ends at tEnd. The ray
// covers the whole supported distance: empty air is skipped in growing strides, thin dust is taken in growing spans, and
// what the step cap leaves of the ray is integrated from the noise-free column (no far haze is dropped, review finding 14).
vec4 dwMarch(vec3 rd, float tEnd, float jitter, float foot, int maxSteps) {
    vec3 eye = DwEye.xyz;
    gDwSamples = 0;
    gDwIters = 0;
    gDwTailEvals = 0;
    gDwKinds = vec4(0.0);
    gDwCapLeft = 0.0;
    gDwTailTau = 0.0;
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
    // the disc of dust: the front's circle, widened by the oldest dust's drift; a vertical ray stays at its own radius
    vec2 oc = eye.xz;
    float R = DwRing.x + 4.0 + length(DwLife.zw) * max(DwEye.w, 0.0);
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
    vec3 light = vec3(0.0), c = vec3(0.0), airLight = dwAirLight(rd);
    // plan B: the noisy march ends where a pixel spans twice the largest billow, the far level takes the rest
    float tB = DwFar.x > 0.5 ? DwFar.y / max(foot, 1e-6) : 1e30;
    float tMarch = min(t1, 2.0 * tB);
    // sample grid fixed along the ray from the eye: 3.5-block steps to 3.5/K blocks, then geometric (step K × distance:
    // 6.5 m at 1 km, 26 m at 4 km); the history averages the moving offset within each step (a coarser grid, averaged,
    // lost the billows' fine detail)
    float k = floor(dwGridK(t0));
    // n: cells to the next sample (strides grow over empty air and thin dust); kLast: the last sampled cell
    float n = 1.0, kLast = k - 1.0;
    float T = 1.0, t = t0;
    bool inDust = false, capped = false;
    // the step cap counts occupied samples (each with its light), not strides over clear air: a cap on every stride
    // handed the far thin spans to the noise-free tail, which saw none of them (E8: 0.29 at HIGH)
    for (int i = 0; i < 640; i++) {
        gDwIters = i + 1;
        t = dwGridT(k);
        if (t >= tMarch || T < 0.01) break;
        if (gDwSamples >= maxSteps || i == 639) {
            capped = true;
            break;
        }
        float dt = min(dwGridT(k + 1.0), tMarch) - t;
        float tt = t + dt * jitter;
        vec3 q = eye + rd * tt;
        float bound = dwBound(q);
        if (bound < 0.0) {
#ifdef DW_DIAG
            gDwKinds.x += 1.0;
#endif
            // no dust at this radius: long strides
            kLast = k; k += n; n = min(n * 2.0, 16.0); inDust = false;
            continue;
        }
        float hq = q.y - dwFloor(q.xz);
        if (hq > bound) {
#ifdef DW_DIAG
            if (rd.y < 0.0) gDwKinds.z += 1.0; else gDwKinds.y += 1.0;
#endif
            if (rd.y < 0.0) {
                // above the dust here: down to its bound over the highest ground of the map (rising ground ahead may lift the
                // dust toward the ray), onto the next grid cell (a stride that lands in dust backs up)
                kLast = k;
                k = max(k + 1.0, ceil(dwGridK(t + max(q.y - DwRelief.y - bound, 0.0) / -rd.y)));
                n = 1.0;
            } else {
                kLast = k; k += n; n = min(n * 2.0, 8.0);
            }
            inDust = false;
            continue;
        }
        gDwFoot = max(tt * foot, dt * 0.3);
        // plan B's blend: the noise fades to the column's mean over [tB, 2 tB]
        float wB = smoothstep(tB, 2.0 * tB, tt);
        float s;
        if (wB > 0.0) {
            float sn = wB < 1.0 ? dwDensity(q, false) : 0.0;
            gDwMean = true;
            float sm = dwDensity(q, false);
            gDwMean = false;
            s = mix(sn, sm, wB);
        } else s = dwDensity(q, false);
        if (s > 1e-5) {
            if (!inDust && k - kLast > 1.0) {
                // a stride from empty air landed in dust: single cells from just after the last empty sample
                k = kLast + 1.0; n = 1.0;
                kLast = k - 1.0;
                inDust = true;
                continue;
            }
            inDust = true;
            gDwSamples += 1;
            c = dwLight(q, rd);
            // the thin soft edge, veil and puffs over the dense top: four cells per sample (at grazing views they are
            // hundreds of metres of ray); thin dust: the span grows while each cell holds little optical depth
            float cells = max(hq > gDwSurf + 2.0 ? 4.0 : 1.0, n);
            float span = min(dwGridT(k + cells), tMarch) - t;
            float al = exp(-s * span), air = dwAirT(tt, rd.y);
            // the air between the eye and the dust (haze, weather) shows its own light in front of it, as for the pall
            light += T * (1.0 - al) * (air * c + (1.0 - air) * airLight);
            if (T == 1.0) gDwDepth.x = max(t, t0);
            gDwDepth.y = t + span;
            gDwDepth.z += (T * (1.0 - al)) * tt;
            T *= al;
            kLast = k + cells - 1.0; k += cells;
            // (the haze level is smooth: its spans grow while a cell holds under 0.1 of optical depth)
            n = s * dt < mix(0.1, 0.01, gDwF) ? min(cells * 2.0, 8.0) : 1.0;
        } else {
#ifdef DW_DIAG
            gDwKinds.w += 1.0;
            gDwWhyHist[int(gDwWhy) % 8] += 1.0;
            if (gDwWhy > 6.5) gDwWhyHist[3] += 1.0;
            gDwWhyHq += q.y - dwFloor(q.xz);
#endif
            // inside the dust's bound but clear here: strides no longer than DW_GAP blocks, so a thin span the bound admits
            // (a swell's crest, another source's layer) is never stepped over (E8: five 50-block spans were missed at LOW)
            kLast = k; k += n; n = min(n * 2.0, max(1.0, floor(dwGap() / max(dt, 1e-3))));
            inDust = false;
        }
    }
    // the rest of the ray: past the step cap (the thin tail) or plan B's far level, from the noise-free column
    if (T >= 0.01) {
        float from = capped ? t : tMarch;
        if (from < t1) {
            float before = T;
            light += dwSegment(eye, rd, from, t1, capped ? 8 : 4, T, airLight);
            if (capped) gDwCapLeft = before - T;
        }
    }
    return vec4(light, T);
}
