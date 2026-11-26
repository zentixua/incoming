// Young nuclear dust wall (client/fx/layer/DustWallPass): from the front to DustWallPass.SPAN seconds behind it, one presentation
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
// (front radius now; inner radius of the young wall: the front DustWallPass.SPAN seconds ago; envelope top, blocks; table reach)
uniform vec4 DwRing;
uniform vec4 PallYoung;
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

// (take 33's wall_ownership.glsl, frozen with this copy)
// Slot zero alone can hand its lofted dust and head to the young-wall pass. Its four
// radii are the inner and full handoff, fireball footprint and last table node.
const float DW_NODES = 128.0;
float wallFootprint(float cut, float r) {
    if (cut <= 0.0) return 1.0;
    return smoothstep(cut - 40.0, cut, r);
}

float wallShare(vec4 ring, float r) {
    if (ring.w <= 0.0) return 0.0;
    float footprint = wallFootprint(ring.z, r);
    float outer = footprint * (1.0 - smoothstep(ring.w * (1.0 - 1.0 / (DW_NODES - 1.0)), ring.w, r));
    if (r >= ring.y) return outer;
    if (r <= ring.x) return 0.0;
    return smoothstep(ring.x, ring.y, r) * outer;
}
// presentation exposure of the wall's light (Silo S3E09 dust band: display luma median ~0.35)
const float DW_EXPOSURE = 0.22;
// head timing, seconds of age (presentation): rise e-fold at the contact line, hump hold, width of the hump's fall
const float DW_RISE = 0.12, DW_HOLD = 0.25, DW_FALL = 0.3;
// most the bulges, the billowed surface and its soft edge (DW_HAZE) lift the dust over its envelope, shares (the ray bound,
// dwBound)
// (the top behind the head is uneven at several scales, no cap: swells up to ±DW_BULGE of the layer at 150–400 m, billows,
// rising puffs and a thin veil; on the head the swells are small, so its crest, 1.4–1.6 of the layer, still stands out)
const float DW_BULGE = 0.35, DW_BILLOW = 0.15, DW_HAZE = 1.2;

// First and last occupied distances and the sum of distances weighted by visible opacity.
vec3 gDwDepth = vec3(0.0);
// Pixel footprint at the sample, blocks.
float gDwFoot = 0.0;
// set by dwDensity at its sample: the billowed dust surface above it, its full extinction and its share of sand (the light
// reads them)
float gDwSurf = 0.0, gDwSigma = 0.0, gDwSand = 0.0;
// set by dwDensity: the swell at the sample (dwBulge, share of the layer): sags sit in the shade of the swells around them
float gDwSwell = 0.0;

vec4 dwNode(float r) {
    float x = clamp(r / max(DwRing.w, 1.0), 0.0, 1.0) * (DW_NODES - 1.0);
    int i = int(min(floor(x), DW_NODES - 2.0));
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
    float A = 0.3 + 0.7 * E + 0.9 * Ef;
    ph = 0.5 * (25.0 * (1.0 - E) + 0.25 * a) + 3.5 * (1.0 - Ef);
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
    float out_ = 25.0 * (1.0 - exp(-a / 1.0)) - 15.0 * smoothstep(2.0, 8.0, a);
    float in_ = 3.0 * clamp(2500.0 / max(r, 1.0), 0.3, 2.0) * max(a - 5.0, 0.0) * (1.0 - 0.7 * min(yy, 1.0));
    pw.xz -= rdir * (out_ * (0.3 + 0.7 * yy) - in_);
    // residual turbulence behind the front: neighbouring parcels circle on slow orbits (20 m, 5 m/s; phases varying over
    // ~150 m), so the settled dust keeps swirling and drifting sideways as real dust does, never frozen, without the
    // endless shear a steady flow field would stretch it into; they turn either way (sense and ellipse from the phase field,
    // zero mean): orbits of one sense everywhere read as the whole mass turning around ground zero
    float sw = 6.2832 * dwNoise3(vec3(q.xz / 150.0, 71.3)), hand = sin(3.0 * sw);
    pw.xz -= 20.0 * vec2(cos(0.25 * a + sw), hand * sin(0.25 * a + sw)) * (1.0 - exp(-a / 1.5)) * (0.6 + 0.4 * yy);
    // gusts behind the front: the hit air keeps surging forward and sideways in ~200 m gust cells whose push changes over
    // a few seconds, strongest just after the front and calming over ~15 s (presentation)
    vec2 gp = q.xz / 220.0 + vec2(0.0, 0.35 * a);
    vec2 gust = vec2(dwNoise3(vec3(gp, 3.3)), dwNoise3(vec3(gp + 7.7, 9.1))) - 0.5;
    pw.xz -= gust * 30.0 * (1.0 - exp(-a / 0.8)) * exp(-a / 15.0) * (0.5 + 0.5 * yy);
    pw.xz -= DwWind.xy * a;
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
float dwBulge(vec3 pw, float a) {
    vec2 p = pw.xz / 220.0;
    float w = dwNoise3(vec3(p * 0.4, 4.4)) - 0.5;
    p += 0.8 * vec2(w, -w);
    float L = 0.6 * dwNoise3(vec3(p, 7.1 + 0.08 * a)) + 0.4 * dwNoise3(vec3(pw.xz / 90.0, 0.37 + 0.12 * a));
    // hollows opening in the blanket: in patches ~160 m across the layer sinks by up to 40 % over 3–10 s behind the front,
    // drifting slowly (presentation: the mass shifting as the blast wind reverses)
    float hollow = smoothstep(0.62, 0.8, dwNoise3(vec3(pw.xz / 160.0, 2.2 + 0.03 * a))) * smoothstep(3.0, 10.0, a);
    return DW_BULGE * dwSoft((L - 0.5) * 4.0) * mix(0.2, 1.0, smoothstep(1.5, 4.0, a)) - 0.4 * hollow;
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
    float r = length(xz);
    if (r > min(DwRing.x + 2.0, DwRing.w) || r < DwRing.y - 50.0) return 0.0;
    vec4 n0 = dwNode(r);
    float a = DwEye.w - n0.x;
    if (a <= 0.0 || n0.z <= 0.0) return 0.0;
    // without the hump: its shadow would sweep over the near dust at the front's speed (lit patches sliding at the passage)
    float top = dwEnvelope(a, n0.y, 1.0), ph;
    vec3 pw = dwAir(vec3(xz.x, 0.6 * top, xz.y), dwClock(a, xz), r, top, ph);
    return top * (1.0 + dwBulge(pw, a));
}

// highest the dust can reach above the local ground at a point (blocks), below 0 where no young dust is
float dwBound(vec3 q) {
    float r = length(q.xz);
    if (r > min(DwRing.x + 2.0, DwRing.w) || r < max(DwRing.y - 50.0, DwCut)) return -1.0;
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
    // One model, per-point birth (LOOK Trait 0): the front is a line of births running over the ground. Each ground point
    // tears up its own dust when the front reaches it: its seed (world-fixed, `dwSeeds`) sets its birth a little
    // after the front's arrival (lagging patches open clefts in the front line), how much it raises, its column's height
    // and overshoot; its column rises, overshoots and settles by its own age and stays where it was born. Nothing rides
    // with the front: a ramp surface shared by every point read as one mass carried out from the centre
    // (light paths take the mean point: their shade is smooth)
    vec3 seed = coarse ? vec3(0.5) : dwSeeds(q.xz);
    float s1 = seed.x, s2 = seed.y;
    float delay = 0.12 * clamp(1.6 * s2 - 0.55, 0.0, 1.0) * (0.6 + 0.8 * s1);
    float h = n0.y * mix(0.88, 1.02, s2), k = dwHump(seed.z);
    float top0 = max(dwEnvelope(a0, h, k), 1.0), ceiling = top0 * (1.0 + DW_BULGE) * DW_HAZE + 6.0;
    if (q.y > ceiling) return 0.0;
    // higher up the dust has the profile of a point a little outward (younger): the face is a ramp from the ground up
    // into the crest, leaning back, never ahead of the front
    float yn = clamp(q.y / top0, 0.0, 1.5);
    float a = DwEye.w - dwNode(r + 40.0 * yn * yn * exp(-a0 / 1.5)).x - delay;
    if (a <= 0.0) return 0.0;
    float top = dwEnvelope(a, h, k), env = top, ph;
    vec3 pw = dwAir(q, dwClock(a, q.xz), r, top, ph);
    float swell = dwBulge(pw, a);
    if (!coarse) gDwSwell = swell;
    top *= 1.0 + swell;
    // One frame for the texture: fixed in the air across the ground and carried by the dust's own rise and fall in height
    // (height as a share of this point's envelope, scaled to the settled layer): each column's billows are born at the
    // ground as the front reaches it, rise into the crest and settle with it, and stay where they were torn up. Sliced
    // by a fixed height, the 470 m/s head swept through new air every frame (the far head churned, take 23); riding with
    // the front, the texture slid outward with it (take 26: 2–10 px a frame, "too mobile") and, at the eye, crossed its
    // rays in one or two frames as the front passed under it (the lower frame fluttered)
    float yc = q.y * h / max(env, 2.0) + (pw.y - q.y);
    // billows of ~20 × 13 m and finer (light paths see the two first octaves)
    vec3 tq = vec3(pw.x, yc - 0.45 * ph, pw.z) / vec3(20.0, 13.0, 20.0);
    float n = coarse ? dwFbm2(tq, 0.0) : dwFbm(tq, 1.0 / 13.0);
    // cauliflower lumps of the top, ~35 m and finer, in the same frame
    float bl = coarse ? 0.42 : dwBillow(vec3(pw.x, yc - 0.3 * ph, pw.z) / vec3(34.0, 22.0, 34.0) + 11.0, 1.0 / 22.0);
    // fibrous streaks combed along the radial wind, ~28 m along, 4.5 m across
    float fs = 0.5;
    if (!coarse) {
        float ral = length(pw.xz);
        float angle = pw.x == 0.0 ? sign(pw.z) * 1.57079633 : atan(pw.z, pw.x);
        fs = mix(0.5, dwFbm2(vec3(ral / 28.0, (yc - 0.25 * ph) / 5.0, angle * ral / 4.5), 0.0), dwLod(1.0 / 2.2));
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
    // coarse dust falls back (the core thins over seconds, fines stay); the young head is denser
    float amt = (0.45 + 0.55 * exp(-max(a - 1.0, 0.0) / 8.0)) * (1.0 + 0.5 * exp(-a / 1.1));
    float fade = wallShare(PallYoung, r);
    // over hard ground only debris dust (the pall's own reading: PallField.paved of the soil's mass), sand pales it
    vec2 soil = dwSoilAt(q.xz);
    float paved = 1.0 + soil.x * (n0.w - 1.0);
    float sigma = n0.z * amt * fade * paved * mix(0.75, 1.25, 0.55 * s1 + 0.45 * s2);
    if (!coarse) {
        sigma *= dwOpen(g);
        gDwSand = clamp(soil.y / max(paved, 1e-6), 0.0, 1.0);
    }
    if (!coarse) gDwSigma = sigma;
    // coarse dust low and dense, finer above (stratified); a thin veil of fines over the uneven top (following it, no
    // shelf); here and there a puff lifted above the top that thins into the veil and drifts with the wind
    float strat = 1.25 - 0.5 * clamp(q.y / max(top, 1.0), 0.0, 1.0);
    float veil = (1.0 - smoothstep(surf, surf * 1.12 + 4.0, q.y)) * smoothstep(surf * 0.8, surf, q.y) * 0.03;
    float puff = coarse || q.y < top * 0.8 ? 0.0 : dwPuff(vec3(pw.x, q.y, pw.z), top, a) * (1.0 - smoothstep(ceiling - 8.0, ceiling, q.y));
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
            + DwSkyLight * (0.03 + 0.15 * exp(-0.3 * depth));
    vec3 albedo = mix(DwAlbedo, DwSandAlbedo, gDwSand), bulk = mix(DwBulk, DwSandBulk, gDwSand);
    return DW_EXPOSURE * (albedo * single + bulk * multiple + bulk * DwGroundLight);
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
    // sample grid fixed along the ray from the eye: 3.5-block steps to 3.5/K blocks, then geometric (step K × distance:
    // 6.5 m at 1 km, 26 m at 4 km); the history averages the moving offset within each step (a coarser grid, averaged,
    // lost the billows' fine detail)
    float k = floor(dwGridK(t0));
    // n: cells to the next sample (strides grow over empty air); kLast: the last sampled cell
    float n = 1.0, kLast = k - 1.0;
    vec3 light = vec3(0.0), c = vec3(0.0), airLight = dwAirLight(rd);
    float T = 1.0;
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
        float s = dwDensity(q, false);
        if (s > 1e-5) {
            if (k - kLast > 1.0) {
                // a stride landed in dust: single cells from just after the last empty sample
                k = kLast + 1.0; n = 1.0;
                kLast = k - 1.0;
                continue;
            }
            c = dwLight(q, rd);
            // the thin soft edge, veil and puffs over the dense top: four cells per sample (at grazing views they are
            // hundreds of metres of ray)
            float cells = hq > gDwSurf + 2.0 ? 4.0 : 1.0;
            float span = min(dwGridT(k + cells), t1) - t;
            float al = exp(-s * span), air = dwAirT(tt, rd.y);
            // the air between the eye and the dust (haze, weather) shows its own light in front of it, as for the pall
            light += T * (1.0 - al) * (air * c + (1.0 - air) * airLight);
            if (T == 1.0) gDwDepth.x = max(t, t0);
            gDwDepth.y = t + span;
            gDwDepth.z += (T * (1.0 - al)) * tt;
            T *= al;
            kLast = k + cells - 1.0; k += cells; n = 1.0;
        } else {
            kLast = k; k += n; n = min(n * 2.0, 8.0);
        }
    }
    return vec4(light, T);
}
