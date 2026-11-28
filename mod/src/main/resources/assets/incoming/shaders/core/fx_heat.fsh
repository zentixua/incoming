#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:scene.glsl>
#moj_import <incoming:heat.glsl>
#moj_import <incoming:see.glsl>

// Conventional flash/fire light on the world and heat haze above fire (client/fx/layer/HeatPass): fullscreen pass after
// pall, before layer quads, using a frame copy (Sampler0); writes the whole pixel.
// - Haze: air refractive index n − 1 ∝ density ∝ 1/T; hot gas above fire/ball and in nozzle jets bends rays by ∫∇n·dl.
//   Temperature fluctuations break gas into eddies from outer scale ℓ downward (Kolmogorov); angle of arrival after
//   path L is σ = δn·√(5.82·L/ℓ)·(ℓ/D)^(1/6) per axis (hazeAngle; D — resolved spot at gas). Eddies faster than eye
//   integration blur together (hazeSeen). Background shifts by this angle × (1 − s/D) (s — gas distance, D — world
//   distance). Shift in fractions of eye resolution (visual acuity; binoculars use a pixel): presentation: HeatPass.
//   Gas is a column along its axis (fire/ball upward, nozzle along jet); radius grows along axis, temperature excess
//   and speed fall as 1/x beyond their reference positions; eddy pattern uses noise octaves shrinking threefold from ℓ
//   while larger than the resolved spot, carried by gas (two layers half a cycle apart, switching at zero weight).
// - Firestorm: hot air stands only above the cells that burn (stormShift): along the ray through the hot layer, each step
//   reads the fire map's burning share there and takes the Heskestad plume of a source of that share at its height; the
//   plumes cover their share of the ground (radius² / spacing²), and the variances of the steps add up. Air above cells
//   that do not burn is still.
// - Nuclear shock: post-front air compressed (Rankine–Hugoniot), n − 1 ∝ density; a ray crossing the front sphere bends
//   toward center by the extra-path derivative (thin shell: Dawson function, nuclear/model/ShockOptics, checked by
//   ShockOpticsGlslTest), averaged over pixel width at intersection. Near tangency, pixels; away from it, nothing:
//   front appears as a thin edge ring against sky and ground. No shift below FRONT_SHIFT_MIN.
// - Light: blast fireball, pool flame, burning debris and substation oil are Lambertian spheres at black-body radiance
//   (L·R²/max(d², R²), mushBallFace — direct cosine-weighted beam, air-scattered and ground-reflected), as nuclear
//   fireball world light (fx_pall_composite): block color is pixel divided by open-sky light (relit). Engine flame uses
//   three uniform glowing axial segments (core, flame, tail to Draper point): direct light is exact segment integral
//   above a face (heatSegment); scattered/reflected light as a sphere at each part's center.
// - Shadows: blocks occlude direct light — world point to source ray through the block window around the eye
//   (heatBlocked, HeatPass.blocked; window — Solids): occluder known exactly, on-screen or not. No shadows beyond window.

// frame copy (linear sampling) and world distance behind pixel
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
// direct source visibility from half-resolution pass (fx_heat_shadow): ball codes (r), jet codes (g),
// sample world distance along view (b)
uniform sampler2D Sampler2;
// a see-through surface in front of the world (SceneDepth: Sampler1 g, its distance; FxGlass its own light and transmittance,
// own texture unit): the frame copy holds the window over what lies behind it, so the bend reaches only as far as the window
// (as without see-through), and light on the world behind it relights that world, not the window
uniform sampler2D FxGlass;
uniform float FxSee;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform vec3 PallOpenSky;
uniform vec4 PallFaceShade;
// air haze and frame fog (as fx_pall_composite): extinction, fog start/end, shape
uniform vec4 Haze;
// hot gas: column i — eye-relative base (xyz), base radius (w; 0 — absent); axial length, base eddy ℓ, base gas speed,
// (n₀ − 1)·fluctuation share; axis from base (xyz), radius growth per axis meter; base temperature excess, K, and axial
// positions beyond which excess and speed fall as 1/x
uniform mat4 HeatHot;
uniform mat4 HeatShape;
uniform mat4 HeatAxis;
uniform mat4 HeatGas;
// eddy pattern transport: cycle fraction and cycle index for two layers (x, y and z, w)
uniform vec4 HeatClock;
// shock fronts: columns 0 and 1 — eye-relative center (xyz), radius (w; 0 — absent); 2 and 3 — index jump at four heights
// evenly spaced from ground-angle sine to top
uniform mat4 HeatFront;
// compression e-folding thickness and ground-angle sine above center: front 0 (x, y), front 1 (z, w)
uniform vec4 HeatFrontShape;
// 1 — conventional front: presentation: capped shift; 0 — previous nuclear optics
uniform vec2 HeatFrontChemical;
// front present (x > 0); pixel angle, rad; light present (z > 0), gas present (w > 0)
uniform vec4 HeatFrame;
// shadow-pass visibility present (x > 0; otherwise no shadows)
uniform vec4 HeatShadow;
// largest shift, pixels: the pass runs and copies the frame only around its sources (HeatArea, margin SHIFT_REACH)
uniform float HeatReach;
// firestorm fields (HeatPass.Frame.storm), slots 0 and 1: column k — eye-relative map centre on the ground (x, ground y, z),
// map half-side (w; 0 — absent); column 2 + k — map cell, source spacing, top of the hot air above the ground, fire-model trust
uniform mat4 HeatStorm;
// slot fire maps (FireStorm): burning share of the cell's area (r), mip levels — clusters of cells; zero beyond the map
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;

const float CROSS = 0.15;
// haze law — HeatPass constants copied (checked by HazeGlslTest)
const float HAZE_T_AIR = 288.15;
const float HAZE_COOL = 20.0;
const float HAZE_KOLMOGOROV = 5.82;
const float HAZE_ACUITY = 2.9e-4;
const float HAZE_PUPIL = 4e-3;
const float HAZE_EYE_S = 0.05;
const float HAZE_SWIRL = 0.3;
const float HAZE_CYCLE = 2.0;
const float HAZE_CONE_FLAT = 1e-6;
const float HAZE_EDDY = 0.2;
const float HAZE_AMP = 1.108e-4;
// firestorm plume law — FirePlume copied (checked by HazeGlslTest): Heskestad excess and axis speed coefficients, a fire
// block's convective heat, W, flame excess cap, K
const float STORM_EXCESS = 0.244459;
const float STORM_SPEED = 0.1028045;
const float STORM_BLOCK_WATTS = 122500.0;
const float STORM_FLAME_EXCESS = 784.2638;
const float STORM_PI = 3.14159265;
// steps along the ray through the hot layer, geometric from STORM_NEAR blocks: near cells are resolved one by one, far ones
// read the map's mip level as wide as the step
const int STORM_STEPS = 8;
const float STORM_NEAR = 1.0;
// front shift below this pixel fraction is invisible: omitted (HeatPass.SHIFT_MIN)
const float FRONT_SHIFT_MIN = 0.05;
// presentation: conventional front's weak ripple amplified, summed shift capped at three pixels, including zoom.
const float CHEMICAL_FRONT_GAIN = 24.0;
const float CHEMICAL_FRONT_MAX_PX = 3.0;

float chemicalFrontScale(float pixels) {
    float shown = CHEMICAL_FRONT_GAIN * pixels / CHEMICAL_FRONT_MAX_PX;
    return CHEMICAL_FRONT_GAIN / sqrt(1.0 + shown * shown);
}

out vec4 fragColor;

float heatHash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z) * 2.0 - 1.0;
}

// Value noise in [-1, 1] with smooth grid-node interpolation (standard deviation ~0.45)
float heatNoise(vec3 p) {
    vec3 i = floor(p), f = p - i;
    vec3 u = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(heatHash(i), heatHash(i + vec3(1, 0, 0)), u.x), mix(heatHash(i + vec3(0, 1, 0)), heatHash(i + vec3(1, 1, 0)), u.x), u.y);
    float b = mix(mix(heatHash(i + vec3(0, 0, 1)), heatHash(i + vec3(1, 0, 1)), u.x), mix(heatHash(i + vec3(0, 1, 1)), heatHash(i + vec3(1, 1, 1)), u.x), u.y);
    return mix(a, b, u.z);
}

// Index fluctuation (standard deviation) in gas with temperature excess excess, K; amp is (n₀ − 1)·fluctuation share (HeatPass.dn)
float hazeDn(float excess, float amp) {
    float t0 = HAZE_T_AIR / (HAZE_T_AIR + excess);
    return amp * t0 * (1.0 - t0);
}

// Firestorm source of burning share f (fire blocks f·spacing²) at height z above the ground: Heskestad axis excess, K
// (FirePlume.excess), axis gas speed, m/s (FirePlume.axisSpeed), plume radius (source radius or 0.12·z, as HeatPass.plume) and
// the share of the ground its plumes cover at that height
float stormExcess(float f, float spacing, float z) {
    return min(STORM_EXCESS * pow(f * spacing * spacing * STORM_BLOCK_WATTS, 2.0 / 3.0) * pow(max(z, 0.1), -5.0 / 3.0), STORM_FLAME_EXCESS);
}

float stormSpeed(float f, float spacing, float z) {
    return STORM_SPEED * pow(f * spacing * spacing * STORM_BLOCK_WATTS, 1.0 / 3.0) * pow(max(z, 0.1), -1.0 / 3.0);
}

float stormRadius(float f, float spacing, float z) {
    return max(sqrt(f * spacing * spacing / STORM_PI), 0.12 * z);
}

float stormCover(float f, float spacing, float z) {
    float r = stormRadius(f, spacing, z);
    return min(1.0, STORM_PI * r * r / (spacing * spacing));
}

// Ray angle of arrival (axis standard deviation), rad: path len in gas with fluctuation dn, eddies to eddy, spot aperture (HeatPass.hazeAngle)
float hazeAngle(float dn, float len, float eddy, float aperture) {
    if (aperture < eddy) return dn * sqrt(HAZE_KOLMOGOROV * len / eddy) * pow(eddy / aperture, 1.0 / 6.0);
    return dn * sqrt(HAZE_KOLMOGOROV * len / eddy) * eddy / aperture;
}

// Haze share visible to the eye: eddies carried past the ray faster than eye integration blur together (HeatPass.hazeSeen)
float hazeSeen(float eddy, float speed) {
    float tau = eddy / max(speed, 1e-6);
    if (tau < HAZE_EYE_S) return sqrt(tau / HAZE_EYE_S);
    return 1.0;
}

// Ray inside column: f(t) = a·t² − 2b·t + c < 0 over [t0, t1], one interval (frustum is convex) — entry (hazeIn),
// exit (hazeOut); empty if exit no farther than entry (HeatPass.coneSpan)
float hazeInUp(float a, float b, float disc, float t0) {
    if (disc <= 0.0) return 1e30;
    return max(t0, (b - sqrt(disc)) / a);
}

float hazeOutUp(float a, float b, float disc, float t1) {
    if (disc <= 0.0) return -1e30;
    return min(t1, (b + sqrt(disc)) / a);
}

float hazeInDown(float a, float b, float disc, float t0) {
    if (disc <= 0.0) return t0;
    if (t0 < (b + sqrt(disc)) / a) return t0;
    return max(t0, (b - sqrt(disc)) / a);
}

float hazeOutDown(float a, float b, float disc, float t0, float t1) {
    if (disc <= 0.0) return t1;
    if (t0 < (b + sqrt(disc)) / a) return min(t1, (b + sqrt(disc)) / a);
    return t1;
}

float hazeInFlat(float b, float c, float t0) {
    if (b > 1e-12) return max(t0, c / (2.0 * b));
    if (b < -1e-12) return t0;
    if (c < 0.0) return t0;
    return 1e30;
}

float hazeOutFlat(float b, float c, float t1) {
    if (b > 1e-12) return t1;
    if (b < -1e-12) return min(t1, c / (2.0 * b));
    if (c < 0.0) return t1;
    return -1e30;
}

float hazeIn(float a, float b, float c, float t0) {
    if (a > HAZE_CONE_FLAT) return hazeInUp(a, b, b * b - a * c, t0);
    if (a < -HAZE_CONE_FLAT) return hazeInDown(a, b, b * b - a * c, t0);
    return hazeInFlat(b, c, t0);
}

float hazeOut(float a, float b, float c, float t0, float t1) {
    if (a > HAZE_CONE_FLAT) return hazeOutUp(a, b, b * b - a * c, t1);
    if (a < -HAZE_CONE_FLAT) return hazeOutDown(a, b, b * b - a * c, t0, t1);
    return hazeOutFlat(b, c, t1);
}

// One layer's eddy pattern at p (from column base, blocks): octaves shrinking threefold from eddy while larger than
// aperture, Kolmogorov weights (wavefront tilt from size s eddies ∝ s^(−1/6)); gas traveled travel blocks along axis,
// size s eddy changed by 0.3·travel/ℓ·(ℓ/s)^(2/3); each cycle has its own pattern offset.
// Axis standard deviation as heatNoise.
vec2 hazeNoise(vec3 p, vec3 axis, float eddy, float travel, float cycle, float aperture) {
    vec2 sum = vec2(0.0);
    float norm = 0.0;
    vec3 seed = vec3(cycle * 17.31, cycle * 5.17, cycle * 11.73);
    for (int k = 0; k < 3; k++) {
        float s = eddy * pow(3.0, -float(k));
        if (k > 0 && s < aperture) break;
        float w = pow(3.0, float(k) / 6.0);
        vec3 q = p / s - axis * (travel / s) + seed;
        float turn = HAZE_SWIRL * travel / eddy * pow(eddy / s, 2.0 / 3.0);
        sum += w * vec2(heatNoise(q + vec3(0.0, 0.0, turn)), heatNoise(q + vec3(17.3, 5.1, turn + 31.7)));
        norm += w * w;
    }
    return sum / sqrt(norm);
}

// Boundary i of STORM_STEPS steps over the ray's part t0..t1: geometric from STORM_NEAR (from the entry if farther), even
// if the whole part is nearer
float stormStep(float t0, float t1, int i) {
    if (i == 0) return t0;
    float near = max(t0, STORM_NEAR), share = float(i) / float(STORM_STEPS);
    if (t1 > near) return near * pow(t1 / near, share);
    return t0 + (t1 - t0) * share;
}

// Image shift behind the firestorms' hot air, pixels: dir ray to world (dist), res resolved angle, wa/wb pattern layer weights.
// The ray's part inside each field's hot layer is cut into STORM_STEPS steps; each adds the angle variance of its path
// through plumes (path × cover) at its own burning share, excess, eddies (EDDY of the plume radius) and gas speed; the
// eddy pattern is read once, at the variance-weighted point of both fields. No burning cell on the ray — no shift.
vec2 stormShift(vec3 dir, float dist, float res, float wa, float wb) {
    if (HeatStorm[0].w <= 0.0 && HeatStorm[1].w <= 0.0) return vec2(0.0);
    float sum = 0.0, at = 0.0, eddySum = 0.0, speedSum = 0.0;
    // one loop over both fields' steps, its bound from a uniform (HeatFrame.w is 1 here): compilers keep it a loop instead of
    // unrolling 2 × STORM_STEPS bodies (AMD code size, tools/amd_shader_check.py)
    int steps = int(HeatFrame.w) * 2 * STORM_STEPS;
    for (int j = 0; j < steps; j++) {
        int k = j / STORM_STEPS, i = j - k * STORM_STEPS;
        vec4 m = HeatStorm[k];
        if (m.w <= 0.0) continue;
        vec4 s = HeatStorm[2 + k];
        // the ray inside the slab ground..top and the map square
        vec3 lo = vec3(m.x - m.w, m.y, m.z - m.w) / dir, hi = vec3(m.x + m.w, m.y + s.z, m.z + m.w) / dir;
        vec3 near3 = min(lo, hi), far3 = max(lo, hi);
        float t0 = max(0.0, max(near3.x, max(near3.y, near3.z))), t1 = min(dist, min(far3.x, min(far3.y, far3.z)));
        if (t1 <= t0) continue;
        float from = stormStep(t0, t1, i), to = stormStep(t0, t1, i + 1);
        float len = to - from, mid = 0.5 * (from + to);
        vec3 p = dir * mid;
        vec2 uv = (p.xz - m.xz) / (2.0 * m.w) + 0.5;
        float lod = log2(max(0.5 * len * length(dir.xz) / s.x, 1.0));
        float f = textureLod(Sampler4, uv, lod).r;
        if (k == 0) f = textureLod(Sampler3, uv, lod).r;
        f *= s.w;
        if (f <= 1e-5 || len <= 0.0) continue;
        float z = p.y - m.y;
        float excess = stormExcess(f, s.y, z);
        float eddy = HAZE_EDDY * stormRadius(f, s.y, z), speed = stormSpeed(f, s.y, z);
        // ⚠ shape: the hot air fades out between twice the cutoff excess and the cutoff, not at a step
        float angle = smoothstep(HAZE_COOL, 2.0 * HAZE_COOL, excess) * hazeAngle(hazeDn(excess, HAZE_AMP), len * stormCover(f, s.y, z), eddy,
                max(HAZE_PUPIL, res * mid)) * hazeSeen(eddy, speed) * (1.0 - mid / max(dist, mid + 1e-3)) / res;
        float v = angle * angle;
        sum += v;
        at += v * mid;
        eddySum += v * eddy;
        speedSum += v * speed;
    }
    // below one hundredth of a pixel, skip noise (cost)
    if (sum < 1e-4) return vec2(0.0);
    float mid = at / sum, eddy = eddySum / sum, speed = speedSum / sum, aperture = max(HAZE_PUPIL, res * mid);
    vec3 p = dir * mid, up = vec3(0.0, 1.0, 0.0);
    vec2 n = wa * hazeNoise(p, up, eddy, speed * HeatClock.x * HAZE_CYCLE, HeatClock.y, aperture)
            + wb * hazeNoise(p, up, eddy, speed * HeatClock.z * HAZE_CYCLE, HeatClock.w, aperture);
    return sqrt(sum) / (0.45 * sqrt(wa * wa + wb * wb)) * n;
}

// Image shift behind hot gas, pixels: dir ray to world (dist) within column i (frustum along axis from base through
// length), strength hazeAngle at segment midpoint, direction from local eddy pattern
vec2 heatShift(vec3 dir, float dist) {
    vec2 shift = vec2(0.0);
    float res = min(HAZE_ACUITY, HeatFrame.y);
    float wa = 1.0 - abs(2.0 * HeatClock.x - 1.0);
    float wb = 1.0 - wa;
    for (int i = 0; i < 4; i++) {
        vec4 h = HeatHot[i];
        if (h.w <= 0.0) continue;
        vec4 s = HeatShape[i];
        vec4 ax = HeatAxis[i];
        vec4 g = HeatGas[i];
        vec3 a = ax.xyz;
        // along axis, between base and end: ray point t has axial position t·c − ba
        float c = dot(dir, a), ba = dot(h.xyz, a);
        float t0 = 0.0, t1 = dist;
        if (abs(c) > 1e-5) {
            float ta = ba / c, tb = (ba + s.x) / c;
            t0 = max(t0, min(ta, tb));
            t1 = min(t1, max(ta, tb));
        } else if (ba > 0.0 || ba + s.x < 0.0) {
            continue;
        }
        if (t1 <= t0) continue;
        // across axis: |t·d⊥ − b⊥|² < r(t)², r(t) = r₀ + growth·(t·c − ba), one quadratic equation
        vec3 dp = dir - a * c, bp = h.xyz - a * ba;
        float r0 = h.w - ax.w * ba, grow = ax.w * c;
        float qa = dot(dp, dp) - grow * grow, qb = dot(dp, bp) + r0 * grow, qc = dot(bp, bp) - r0 * r0;
        float lo = hazeIn(qa, qb, qc, t0);
        t1 = hazeOut(qa, qb, qc, t0, t1);
        t0 = lo;
        float len = t1 - t0;
        if (len <= 0.0) continue;
        float mid = 0.5 * (t0 + t1);
        float u = clamp(mid * c - ba, 0.0, s.x);
        float excess = g.x * min(1.0, g.y / max(u, 1e-3));
        if (excess < HAZE_COOL) continue;
        float eddy = s.y * (h.w + ax.w * u) / h.w;
        float speed = s.z * min(1.0, g.z / max(u, 1e-3));
        float aperture = max(HAZE_PUPIL, res * mid);
        // fluctuations fade toward column edge and end (⚠ shape: (1 − ρ²/r²)² across instead of Gaussian, axial fade from 0.6 length):
        // a uniform cylinder would end haze at its wall with a straight line
        float r = h.w + ax.w * u;
        vec3 off = dir * mid - h.xyz - a * u;
        float edge = clamp(1.0 - dot(off, off) / (r * r), 0.0, 1.0);
        edge = edge * edge * (1.0 - smoothstep(0.6 * s.x, s.x, u));
        float k = edge * hazeAngle(hazeDn(excess, s.w), len, eddy, aperture) * hazeSeen(eddy, speed) * (1.0 - mid / max(dist, mid + 1e-3)) / res;
        // below one hundredth of a pixel, skip noise (cost)
        if (k < 0.01) continue;
        vec3 p = dir * mid - h.xyz;
        vec2 n = wa * hazeNoise(p, a, eddy, speed * HeatClock.x * HAZE_CYCLE, HeatClock.y, aperture)
                + wb * hazeNoise(p, a, eddy, speed * HeatClock.z * HAZE_CYCLE, HeatClock.w, aperture);
        // noise standard deviation ~0.45 per axis; two-layer mixture divided by √(wa² + wb²)
        shift += k / (0.45 * sqrt(wa * wa + wb * wb)) * n;
    }
    return shift + stormShift(dir, dist, res, wa, wb);
}

// Dawson function F(x) = e^(−x²)·∫₀ˣ e^(t²) dt (ShockOptics.dawson)
float frontDawson(float x) {
    if (x > 10.0) return 0.5 / x * (1.0 + 0.5 / (x * x) + 0.75 / (x * x * x * x));
    float y = x * x;
    float p = 1.0 + y * (0.1049934947 + y * (0.0424060604 + y * (0.0072644182 + y * (0.0005064034 + y * 0.0001789971))));
    float q = 1.0 + y * (0.7715471019 + y * (0.2909738639 + y * (0.0694555761 + y * (0.0140005442 + y * (0.0008327945 + y * 0.0003579942)))));
    return x * p / q;
}

// Extra optical path per shell crossing (ShockOptics.path): impact parameter b, radius r, thickness l, jump dn
float frontPath(float b, float r, float l, float dn) {
    float rl = 2.0 * r * l;
    float a2 = max((r - b) * (r + b), 0.0) / rl;
    return dn * sqrt(rl) * frontDawson(sqrt(a2));
}

// Centerward deflection per crossing, averaged over pixel width w, rad (ShockOptics.bend)
float frontBend(float b, float r, float l, float dn, float w) {
    float lo = max(b - 0.5 * w, 0.0);
    float hi = b + 0.5 * w;
    float bend = (frontPath(lo, r, l, dn) - frontPath(hi, r, l, dn)) / max(hi - lo, 1e-6);
    float most = sqrt(2.0 * dn);
    return clamp(bend, -most, most);
}

// Front jump at q (from center, |q| = r): height interpolation among four nodes from ground sine low to top
float frontJump(vec4 dn, float low, float sine) {
    float k = clamp((sine - low) / max(1.0 - low, 1e-3), 0.0, 1.0) * 3.0;
    if (k < 1.0) return mix(dn.x, dn.y, k);
    if (k < 2.0) return mix(dn.y, dn.z, k - 1.0);
    return mix(dn.z, dn.w, k - 2.0);
}

// Deflection of ray dir to world (dist) by fronts, rad, across-ray vector: each sphere crossing before world bends toward
// center, × (1 − s/D) gives the background shift seen by the eye
vec3 frontShift(vec3 dir, float dist, out vec3 chemical) {
    vec3 bend = vec3(0.0);
    chemical = vec3(0.0);
    for (int i = 0; i < 2; i++) {
        vec4 f = HeatFront[i];
        if (f.w <= 0.0) continue;
        float along = dot(f.xyz, dir);
        vec3 across = f.xyz - dir * along;
        float b = length(across);
        // a pixel just outside tangency still touches the front over half its width
        if (b < 1e-3 || b >= f.w + 0.5 * HeatFrame.y * max(along, 0.0)) continue;
        float chord = sqrt(max((f.w - b) * (f.w + b), 0.0));
        float l = i == 0 ? HeatFrontShape.x : HeatFrontShape.z;
        float low = i == 0 ? HeatFrontShape.y : HeatFrontShape.w;
        vec4 dn = HeatFront[2 + i];
        float angle = 0.0;
        for (int k = 0; k < 2; k++) {
            float s = k == 0 ? along - chord : along + chord;
            if (s <= 0.0 || s >= dist) continue;
            float jump = frontJump(dn, low, (dir.y * s - f.y) / f.w);
            angle += frontBend(b, f.w, l, jump, HeatFrame.y * s) * (1.0 - s / dist);
        }
        if (HeatFrontChemical[i] > 0.0) chemical += across / b * angle;
        else bend += across / b * angle;
    }
    return bend;
}

// Source visibility at pixel px with world distance depth (along view): of four nearest visibility-pass samples
// (half-resolution: sample t is pixel 2t), choose nearest world distance (background visibility does not spill onto an
// object edge); codes: ball 2 bits (0, ¼, ¾, 1), jet 1 bit
const vec4 HEAT_SEEN_CODES = vec4(0.0, 0.25, 0.75, 1.0);

void heatSeen(ivec2 px, float depth, out vec4 ballSeen, out vec4 jetSeen) {
    ivec2 lsize = textureSize(Sampler2, 0), f = px / 2;
    vec4 best = vec4(255.0, 15.0, 1e9, 0.0);
    float err = 1e30;
    for (int k = 0; k < 4; k++) {
        ivec2 t = clamp(f + ivec2(k % 2, k / 2), ivec2(0), lsize - 1);
        vec4 s = texelFetch(Sampler2, t, 0);
        float e = abs(s.b - depth);
        if (e < err) {
            err = e;
            best = s;
        }
    }
    int balls = int(best.r + 0.5), jets = int(best.g + 0.5);
    for (int i = 0; i < 4; i++) {
        ballSeen[i] = HEAT_SEEN_CODES[(balls >> (2 * i)) & 3];
        jetSeen[i] = float((jets >> i) & 1);
    }
}

void main() {
    ivec2 size = textureSize(Sampler1, 0);
    vec2 here = gl_FragCoord.xy;
    vec2 ndc0 = here / vec2(size) * 2.0 - 1.0;
    vec4 far0 = InvViewProj * vec4(ndc0, 1.0, 1.0);
    vec3 dir0 = normalize(far0.xyz / far0.w);
    float depth0 = pallScene(texelFetch(Sampler1, ivec2(here), 0).r);
    float dist0 = depth0 >= 1e8 ? 1e8 : depth0 / max(dot(dir0, PallForward), 1e-3);
    // refraction bends the frame copy, whose pixel holds a see-through surface over the world behind it: only air in front
    // of the surface may move it
    if (FxSee > 0.5) dist0 = min(dist0, texelFetch(Sampler1, ivec2(here), 0).g / max(dot(dir0, PallForward), 1e-3));
    vec2 shift = HeatFrame.w > 0.0 ? heatShift(dir0, dist0) : vec2(0.0);
    vec2 front = vec2(0.0);
    if (HeatFrame.x > 0.0) {
        vec3 chemical;
        vec3 bend = frontShift(dir0, dist0, chemical);
        if (dot(bend, bend) > 0.0 || dot(chemical, chemical) > 0.0) {
            // ray turn to pixels: ray step per screen pixel in x and y (linear within a pixel)
            vec2 ndc1 = (here + vec2(1.0, 0.0)) / vec2(size) * 2.0 - 1.0, ndc2 = (here + vec2(0.0, 1.0)) / vec2(size) * 2.0 - 1.0;
            vec4 f1 = InvViewProj * vec4(ndc1, 1.0, 1.0), f2 = InvViewProj * vec4(ndc2, 1.0, 1.0);
            vec3 dx = normalize(f1.xyz / f1.w) - dir0, dy = normalize(f2.xyz / f2.w) - dir0;
            float xx = dot(dx, dx), xy = dot(dx, dy), yy = dot(dy, dy), det = xx * yy - xy * xy;
            float bx = dot(bend, dx), by = dot(bend, dy);
            if (det > 0.0) front = vec2(yy * bx - xy * by, xx * by - xy * bx) / det;
            // Limit after projection: pixel angle varies across frame, resolution and field of view.
            // Sum conventional fronts BEFORE limiting; nuclear contribution is not limited.
            float cx = dot(chemical, dx), cy = dot(chemical, dy);
            if (det > 0.0) {
                vec2 ordinary = vec2(yy * cx - xy * cy, xx * cy - xy * cx) / det;
                front += ordinary * chemicalFrontScale(length(ordinary));
            }
        }
    }
    // front runs fullscreen on every nuclear frame: below-threshold shift leaves frame unchanged
    if (dot(front, front) >= FRONT_SHIFT_MIN * FRONT_SHIFT_MIN) shift += front;
    // the copy covers this pixel's rectangle only this far around (shimmer is sub-pixel, conventional fronts at most 3 px)
    float reach = length(shift);
    if (reach > HeatReach) shift *= HeatReach / reach;
    vec2 at = here + shift;
    vec3 world = dot(shift, shift) > 0.0 ? texture(Sampler0, at / vec2(size)).rgb : texelFetch(Sampler0, ivec2(here), 0).rgb;
    ivec2 px = clamp(ivec2(floor(at)), ivec2(0), size - 1);
    float depth = pallScene(texelFetch(Sampler1, px, 0).r);
    if (HeatFrame.z > 0.0 && depth < 1e8) {
        vec3 p = sceneAt(Sampler1, InvViewProj, PallForward, px, size);
        vec3 n = sceneNormal(Sampler1, InvViewProj, PallForward, px, size, p);
        // light applies to world light itself, excluding air haze and Minecraft fog in front
        float fd = Haze.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p);
        float seen = exp(-Haze.x * length(p)) * (1.0 - smoothstep(Haze.y, Haze.z, fd));
        vec3 open = PallOpenSky * faceShade(PallFaceShade, n);
        vec4 ballSeen = vec4(1.0), jetSeen = vec4(1.0);
        if (HeatShadow.x > 0.0) heatSeen(px, depth, ballSeen, jetSeen);
        vec3 added = heatLight(p, n, ballSeen, jetSeen) * seen;
        if (dot(added, LUMA) > 1e-6) {
            // behind a see-through surface: the world W = (frame − X) / T is lit, the surface's own light X stays
            vec4 glass = vec4(0.0, 0.0, 0.0, 1.0);
            if (FxSee > 0.5 && seeBehind(depth, pallScene(texelFetch(Sampler1, px, 0).g))) {
                glass = texelFetch(FxGlass, px, 0);
                world = clamp((world - glass.rgb) / max(glass.a, 1.0 / 255.0), 0.0, 1.0);
            }
            vec3 lit = vec3(relit(world.r, 1.0, open.r, added.r), relit(world.g, 1.0, open.g, added.g), relit(world.b, 1.0, open.b, added.b));
            // (the nuclear world's wash far above white, pallWashWhite, stays out of here: conventional light on the world
            // reaches a few whites at most, where the hue-kept ceiling reads as the fire's own colour)
            lit += vec3(CROSS * max(dot(lit, LUMA) - 1.0, 0.0));
            vec3 shown = vec3(pallDisplay(lit.r), pallDisplay(lit.g), pallDisplay(lit.b));
            world = glass.rgb + glass.a * (shown / max(max(max(shown.r, shown.g), shown.b), 1.0));
        }
    }
    fragColor = vec4(world, 1.0);
}
