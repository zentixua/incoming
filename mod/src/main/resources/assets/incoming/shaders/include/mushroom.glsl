// Volumetric mushroom (client/nuclear/MushroomField frame data, nuclear/model/MushroomModel laws): cap is fireball
// rolled into a ring with a dome over its hole (before rollup, fireball sphere: zero-radius ring), stem, dust skirt
// at its base; water bursts add a hollow water column with spray crown (nuclear/model/WaterModel). Analytic field: functions of this frame's mushroom data and
// position; billows from shared pall noise tile (VolumeNoise), in mushroom-body coordinates and size units. Shared by volume
// (fx_pall), accumulation (fx_pall_resolve), effects-layer quads (fx.vsh: mushroom occlusion before particle).
// Scalar laws copy MushroomModel/MushroomField (MushroomGlslTest evaluates and compares with Java).
//
// Mushroom table (MushroomField.TEXELS RGBA32F texels per mushroom; row k is mushroom k, MUSH_BB_ROW is black-body
// light): blocks, eye-relative; heights above epicenter ground.
//   0 = (epicenter x, z; ground y; 1 if mushroom present)
//   1 = (full wind drift x, z; stabilized top, height share scales drift; bounds radius)
//   2 = (cap-ring center height; cap radius; ring major radius; bounds top)
//   3 = (ring-section horizontal/vertical semiaxes; dome center; dome horizontal semiaxis)
//   4 = (dome vertical semiaxis; cap extinction, 1/block; cap billow tile, blocks; seconds since detonation)
//   5 = (cap albedo ω' rgb; asymmetry g')
//   6 = (stem top; height-mean stem radius; extra ground dust flare beyond stem base; cross-stem optical depth)
//   7 = (stem ω' rgb; g')
//   8 = (skirt ground center; half-width; height; extinction, 1/block)
//   9 = (skirt ω' rgb; g')
//  10 = (ambient air, K; core temperature excess, K; cap bottom; stem billow tile)
//  11 = (stem-jet rise, blocks, MushroomModel.stemRise; storm smoke top, same as its pall columns;
//        fire-zone radius; inflow/stem merge height, blocks, MushroomField.foot)
//  12 = (under-mushroom fire light rgb, like pall: storm fire map/frame light; cap churn clock, rad)
//  13 = (water-column wall radial center; wall half-thickness; wall top at crown center; extinction, 1/block;
//        0 if no column)
//  14 = (column ω' rgb; g')
//  15 = (crown outer radius; half-height; wall-jet rise at column top, blocks; column billow
//        tile, blocks)
//  16 = (cap-edge core-temperature excess share: sphere 1, ring MushroomModel.EDGE_HEAT; edge-billow extent
//        relative to ring: smooth sphere before rollup; ball-surface pattern frame position from −1 (absent) to 0 and frame
//        index with fraction; pattern versus billow-tongue share, 1 − ring share, 0 means pattern not read; MushroomField.ballSurface)
//  17 = (shadow tile in sun-perpendicular plane: u/v origin and sides relative to epicenter, blocks; side 0 means no shadow;
//        MUSH_SHADOW_RECT)
//  18 = (world top above epicenter ground, shadow-map top layer; ground reflectance under mushroom rgb, top of dust layer
//        at stem base, MushroomField.groundUnder; MUSH_SHADOW_TOP)
//  19 = (stem-base inflow, MushroomField.foot: dust-layer top at stem wall, blocks; ground extinction
//        × mushroom share, 1/block; inflow share; stem-air rise since inflow began, blocks; 0 means no inflow)
//  20..35 = 64 heights of lifted-dust optical column; 36..67 = column above band edge at 128 radii (FootColumn)

const int MUSHROOMS = 4;
const int MUSH_BB_ROW = 4;
// table black-body light: node count, logarithmic edge temperatures (K) (MushroomField.BB_*)
const float MUSH_BB_NODES = 256.0;
const float MUSH_BB_FROM = 500.0;
const float MUSH_BB_TO = 30000.0;
// billow edge displacement/softness for cap, stem, skirt, in part-billow units (MushroomField: LOBES_*,
// EDGE_*): cap uses smaller ring-section semiaxis, stem radius at height, skirt smaller semiaxis
const float MUSH_CAP_LOBES = 0.4;
const float MUSH_CAP_EDGE = 0.07;
const float MUSH_STEM_LOBES = 0.4;
const float MUSH_STEM_EDGE = 0.2;
const float MUSH_SKIRT_LOBES = 0.8;
const float MUSH_SKIRT_EDGE = 0.25;
// same for water column, wall-thickness units (MushroomField: LOBES_COLUMN, EDGE_COLUMN)
const float MUSH_COLUMN_LOBES = 0.6;
const float MUSH_COLUMN_EDGE = 0.15;
// ring/dome union smoothing in cap-billow units (smooth minimum lowers edge by at most one
// quarter of it)
const float MUSH_BLEND = 0.25;
// cap-edge billow tongues (MushroomModel.SKIN): edge heating ±this share over billow extent; protrusion is
// fresh hot gas from inside, trough already mixed; sphere uses baked pattern (mushBallPattern in fx_pall), tongues use
// their share during rollup
const float MUSH_SKIN = 0.09 / 0.35;
// cap-ring major radius is this cap-radius share after rollup (MushroomModel.RING): defines ring share
const float MUSH_RING = 0.55;
// Cap churn (mushCapLobes): two billow-pattern layers, each lives this many churn-clock radians (churn:
// MushroomModel.churn, radians of own section traveled by cap matter). Billows rise out of cap by layer
// midlife, sink back by end, over their eddy turnover: half-section billow (MushroomField: half-section
// bubble) turns over in (1/2)^(2/3) clock radians by Kolmogorov law; up/down take twice that
const float MUSH_CYCLE = 1.26;
// one layer's billow top/bottom (mushLobes in fx_pall: 0.5 large, 0.175 small), extent fractions
const float MUSH_LOBES_TOP = 0.675;
// layer submergence when absent, billow-extent units: beyond full extent (±MUSH_LOBES_TOP),
// so outgoing layer does not protrude above newly born layer and pattern change is invisible
const float MUSH_SINK = 1.4;
// deepest cap billow penetration (upper of two layers with mushRise correction: up to 1/6 below one layer bottom),
// extent units: deeper than this displaced edge, cap is fully dense
const float MUSH_LOBES_DEEP = MUSH_LOBES_TOP + 1.0 / 6.0;
// matter extent beyond unbillowed edge (outward displacement up to 0.68 extent, edge softness, cap also smooth
// minimum), billow units: cap, stem, skirt
const float MUSH_CAP_REACH = 0.68 * MUSH_CAP_LOBES + MUSH_CAP_EDGE + 0.25 * MUSH_BLEND;
const float MUSH_STEM_REACH = 0.68 * MUSH_STEM_LOBES + MUSH_STEM_EDGE;
const float MUSH_SKIRT_REACH = 0.68 * MUSH_SKIRT_LOBES + MUSH_SKIRT_EDGE;
// column also includes wall/crown smooth minimum
const float MUSH_COLUMN_REACH = 0.68 * MUSH_COLUMN_LOBES + MUSH_COLUMN_EDGE + 0.25 * MUSH_BLEND;

struct Mush {
    vec3 base;
    vec2 drift;
    float endTop;
    float boundR;
    float capY;
    float capR;
    float ringR;
    float boundTop;
    float ringA;
    float ringB;
    float domeY;
    float domeR;
    float domeH;
    float capSigma;
    float capTile;
    float seconds;
    vec4 capMat;
    float stemTop;
    float stemR;
    float stemFlare;
    float stemTau;
    vec4 stemMat;
    float skirtR;
    float skirtW;
    float skirtH;
    float skirtSigma;
    vec4 skirtMat;
    float tAmb;
    float dT;
    float capBot;
    float stemTile;
    float stemRise;
    float fireTop;
    float fireR;
    vec3 fire;
    float churn;
    float colMid;
    float colHalf;
    float colTop;
    float colSigma;
    vec4 colMat;
    float crownR;
    float crownH;
    float colRise;
    float colTile;
    float edge;
    float billows;
    float ballFrame;
    float ballShare;
    float footTop;
    float footSigma;
    float footShare;
    float footRise;
    float footMerge;
};

Mush mushLoad(sampler2D data, int k) {
    vec4 t0 = texelFetch(data, ivec2(0, k), 0), t1 = texelFetch(data, ivec2(1, k), 0), t2 = texelFetch(data, ivec2(2, k), 0);
    vec4 t3 = texelFetch(data, ivec2(3, k), 0), t4 = texelFetch(data, ivec2(4, k), 0), t6 = texelFetch(data, ivec2(6, k), 0);
    vec4 t8 = texelFetch(data, ivec2(8, k), 0), t10 = texelFetch(data, ivec2(10, k), 0), t11 = texelFetch(data, ivec2(11, k), 0);
    vec4 t12 = texelFetch(data, ivec2(12, k), 0), t13 = texelFetch(data, ivec2(13, k), 0), t15 = texelFetch(data, ivec2(15, k), 0);
    vec4 t16 = texelFetch(data, ivec2(16, k), 0), t19 = texelFetch(data, ivec2(19, k), 0);
    // table epicenter is x/z/ground y (like pall slot); position becomes x/y/z
    return Mush(t0.xzy, t1.xy, t1.z, t1.w, t2.x, t2.y, t2.z, t2.w, t3.x, t3.y, t3.z, t3.w, t4.x, t4.y, t4.z, t4.w,
            texelFetch(data, ivec2(5, k), 0), t6.x, t6.y, t6.z, t6.w, texelFetch(data, ivec2(7, k), 0), t8.x, t8.y, t8.z, t8.w,
            texelFetch(data, ivec2(9, k), 0), t10.x, t10.y, t10.z, t10.w, t11.x, t11.y, t11.z, t12.rgb, t12.w,
            t13.x, t13.y, t13.z, t13.w, texelFetch(data, ivec2(14, k), 0), t15.x, t15.y, t15.z, t15.w, t16.x, t16.y, t16.z, t16.w,
            t19.x, t19.y, t19.z, t19.w, t11.w);
}

// whether mushroom k is in frame
bool mushOn(sampler2D data, int k) {
    return texelFetch(data, ivec2(0, k), 0).w > 0.5;
}

// wind-drift share at height h (MushroomModel.driftShare): top drifts more than base
float mushShare(float h, float endTop) {
    return clamp(h / max(endTop, 1.0), 0.0, 1.0);
}

// stem radius at height fraction w (MushroomModel.stemRadius): mean stemR, one third wider at cap, ground dust flare
// flare beyond stem base
float mushStemR(float w, float stemR, float flare) {
    float x = clamp(w, 0.0, 1.0);
    float base = pow(1.0 - x, 6.0);
    return stemR * (0.85 + 0.45 * x * x) + flare * base;
}

// Stem-base inflow (MushroomField.footWidth, footShare, footDensity, footBand): ground-level wind toward stem
// (afterwinds) carries wave-dust layer into stem and turns upward along wall, corner flow (ψ = x·z:
// streamline following distant layer top top rises along wall, x·z → top²). Layer-air band outside stem wall
// at height h: top/√(1 + (h/top)²) (same, smoothed near ground; ⚠ planar corner, stem radius comparable to
// layer depth). Dust is layer dust: ground extinction sigma, height share footShare (narrower band, diluted by stem,
// ⚠ (est.)), minus dust still held by layer at this height (pallProfile): layer plus inflow at wall is
// sigma·footShare, no layer-top step. Where layer plus inflow is no denser than stem, dust mixes into it
// (StemDust carries it): band tapers from layer top to wall by height merge, where layer plus inflow matches
// stem (Java calculates it, MushroomField.foot); no layer dust above inflow dust front (rise) yet
float mushFootWidth(float h, float top) {
    float t = max(top, 1.0), u = h / t;
    return t / sqrt(1.0 + u * u);
}

float mushFootShare(float h, float top) {
    float u = h / max(top, 1.0);
    return 1.0 / (1.0 + u * u);
}

// layer/inflow together at wall, height h: layer dust below inflow dust front rise, absent above
float mushFootBoth(float h, float top, float sigma, float rise) {
    float t = max(top, 1.0);
    return sigma * mushFootShare(h, t) * clamp((rise - h) / t, 0.0, 1.0);
}

// inflow extinction at height h without edge share: share is inflow share (narrower band at edge, dust density stays layer's;
// air carries without dilution)
float mushFootDensity(float h, float top, float sigma, float share, float rise) {
    if (h < 0.0) return 0.0;
    if (share <= 0.0) return 0.0;
    return max(mushFootBoth(h, top, sigma, rise) - sigma * pallProfile(h / max(top, 1.0)), 0.0);
}

// inflow-band share at height h: tapers from layer top (or half merge height if lower) to
// merge height merge (density does not: already stem density at merge) (+1e-6: ground-level merge has no edge, equal-edge smoothstep undefined)
float mushFootNarrow(float h, float top, float merge) {
    return 1.0 - smoothstep(min(top, 0.5 * merge), merge + 1e-6, h);
}

// inflow band outside stem wall at height h: tapers to wall by merge height
float mushFootBand(float h, float top, float share, float merge) {
    if (share <= 0.0) return 0.0;
    return share * mushFootWidth(max(h, 0.0), top) * mushFootNarrow(h, top, merge);
}

// presentation: weak azimuthal inflow share; no measured stem-base swirl data.
const float MUSH_FOOT_TURN = 0.6;

// inflow share at rho from axis for stem radius r: at wall/outward; stem interior has own dust (StemDust), from
// inflow filling whole stem would give carpet density throughout it up to inflow dust front: a ring across stem
float mushFootWall(float rho, float r) {
    return smoothstep(0.6 * r, r, rho);
}

// Carpet-light share at point: exactly 1 in pure incoming flow at entrance, 0 in original stem interior without inflow;
// by merge height, original stem light. Blends source function, not finished pixel color over smoke.
float mushFootLightShare(float h, float top, float merge, float carried, float total) {
    return clamp(carried / max(total, 1e-7), 0.0, 1.0) * mushFootNarrow(h, top, merge);
}

// Moving carpet-air share at base: only outer stem edge/nearest carpet, up to merge height.
// Flow map does not reach cap, stem interior or distant haze.
float mushFootInfluence(float h, float rho, float r, float top, float share, float merge) {
    if (h < 0.0) return 0.0;
    if (share <= 0.0) return 0.0;
    if (merge <= 0.0) return 0.0;
    if (h >= merge) return 0.0;
    float t = max(top, 1.0);
    return share * mushFootWall(rho, r) * (1.0 - smoothstep(r + t, r + 2.0 * t, rho)) * mushFootNarrow(h, t, merge);
}

// Flow turn from horizontal inflow to rise. Turn geometry (est.); path from model stemRise.
float mushFootUp(float h, float top) {
    float t = max(top, 1.0);
    return smoothstep(0.25 * t, 1.25 * t, h);
}

// ring-section position measure: 1 at edge (rho horizontal from axis, y from ring center)
float mushRing(float rho, float y, float ringR, float a, float b) {
    float u = (rho - ringR) / max(a, 1e-3), v = y / max(b, 1e-3);
    return sqrt(u * u + v * v);
}

// ellipse position measure (dome, skirt section): 1 at edge
float mushEllipse(float x, float y, float rx, float ry) {
    float u = x / max(rx, 1e-3), v = y / max(ry, 1e-3);
    return sqrt(u * u + v * v);
}

// Signed distance to ellipse edge, semiaxes rx/ry, blocks (negative inside): (measure − 1) divided by its
// slope, exact at edge where billows displace it; inside, no deeper than smaller semiaxis
float mushEllipseDist(float x, float y, float rx, float ry) {
    vec2 r = max(vec2(rx, ry), vec2(1e-3));
    vec2 u = vec2(x, y) / r;
    float s = length(u), slope = length(u / r);
    float deep = -min(r.x, r.y);
    if (slope < 1e-9) return deep;
    return max((s - 1.0) * s / slope, deep);
}

// polynomial smooth minimum, k smoothing width: ring/dome one surface; lowers by at most k/4
float mushSmin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / max(k, 1e-3), 0.0, 1.0);
    return b + (a - b) * h - k * h * (1.0 - h);
}

// Edge density: 1 inside, 0 outside, softened over ±e blocks (d signed distance to billow-displaced edge)
float mushEdge(float d, float e) {
    return smoothstep(-e, e, -d);
}

// Eye-relative p in mushroom body: x/z from mushroom axis at this height (with drift), y above epicenter ground
vec3 mushLocal(Mush m, vec3 p) {
    float h = p.y - m.base.y;
    vec2 xz = p.xz - m.base.xz - m.drift * mushShare(h, m.endTop);
    return vec3(xz.x, h, xz.y);
}

// Cap-billow pattern layer j (0/1) age by churn clock, life fraction 0..1: layers half-life apart
float mushLayerAge(float churn, float j) {
    float c = churn / MUSH_CYCLE + 0.5 * j;
    return c - floor(c);
}

// Layer share at age age: zero at birth/end, 1 at midpoint; two layers sum to 1
float mushLayerWeight(float age) {
    return 1.0 - abs(2.0 * age - 1.0);
}

// Pattern index for layer j, unique per life per layer
float mushLayerSeed(float churn, float j) {
    return 2.0 * floor(churn / MUSH_CYCLE + 0.5 * j) + j;
}

// Cap-edge mean correction (low is weaker layer share): edge is upper of two layers, lowered by MUSH_SINK × (1 −
// share); without correction cap sinks one third of extent halfway through layer lives. Mean upper of two uniforms
// over ±h (h = 1/2), offsets −(1 − w)·K: −low·K + h·(2 − e)³/24, e = |w₀ − w₁|·K/h (rest = 2 − e), while e < 2
float mushRise(float low) {
    float rest = max(2.0 - 2.0 * MUSH_SINK * (1.0 - 2.0 * low), 0.0);
    return MUSH_SINK * low - rest * rest * rest / 48.0;
}

// Circulation share at radius rho from mushroom axis: 0 at axis (dome apex is stagnation point, only rising flow),
// full at ring axis
float mushTaper(float rho, float ringR) {
    return smoothstep(0.0, max(ringR, 1.0), rho);
}

// Circulation share at ring-section (u, v), semiaxis units: Rankine vortex, solid rotation inside section,
// irrotational flow outside, angular speed falls as 1/r². Solid rotation outside too would carry dome above ring
// through mushroom axis, mirroring/folding pattern there
float mushSwirl(float u, float v) {
    return 1.0 / max(u * u + v * v, 1.0);
}

// Origin of matter now at section (u, v) after ang radians around ring axis: clockwise in plane
// “outward from mushroom axis, up”, rising inside, outward over top, downward outside; backward is counterclockwise
float mushWasU(float u, float v, float ang) {
    return cos(ang) * u - sin(ang) * v;
}

float mushWasV(float u, float v, float ang) {
    return sin(ang) * u + cos(ang) * v;
}

// billow units, blocks (MushroomField.capUnit, skirtUnit): cap smaller ring-section semiaxis; skirt smaller section semiaxis
float mushCapUnit(Mush m) {
    return min(m.ringA, m.ringB);
}

float mushSkirtUnit(Mush m) {
    return min(m.skirtW, m.skirtH);
}

// water-column billow unit, blocks (MushroomField.columnUnit): wall thickness
float mushColumnUnit(Mush m) {
    return 2.0 * m.colHalf;
}

// Semi-infinite matter-layer diffuse reflectance R∞ (MushroomField.wallReflectance): two streams with
// hemispheric-mean closure (Toon et al. 1989, table 1: γ₁ = 2 − ω(1 + g), γ₂ = ω(1 − g)), (1 − s)/(1 + s),
// s = √((1 − ω)/(1 − ωg)), from 0 for pure absorption to 1 for pure scattering. Not Eddington: its backscatter
// γ₂ = (ω(4 − 3g) − 1)/4 is negative for ω < 1/(4 − 3g), making R∞ negative (droplet-free sphere: down to −0.07)
float mushWallR(float w, float g) {
    float s = sqrt(max(1.0 - w, 0.0) / max(1.0 - w * g, 1e-4));
    return (1.0 - s) / (1.0 + s);
}

// stem radius at body height h
float mushStemAt(Mush m, float h) {
    return mushStemR(h / max(m.capBot, 1.0), m.stemR, m.stemFlare);
}

// Cap-billow pattern position (from cap axis/height center, blocks) for body q after ring core circled section
// by turn radians: elliptical section circulation around ring axis (mushWasU, mushWasV), weaker outside section
// (mushSwirl), tapering at mushroom axis (mushTaper). Pattern follows matter: for matter's current point, sample pattern
// where matter used to be
vec3 mushFlow(Mush m, vec3 q, float turn) {
    float rho = length(q.xz);
    float a = max(m.ringA, 1.0), b = max(m.ringB, 1.0);
    float u = (rho - m.ringR) / a, v = (q.y - m.capY) / b;
    float ang = turn * mushTaper(rho, m.ringR) * mushSwirl(u, v);
    float r = max(m.ringR + a * mushWasU(u, v, ang), 0.0);
    vec2 around = rho > 1e-3 ? q.xz / rho : vec2(1.0, 0.0);
    return vec3(around.x * r, b * mushWasV(u, v, ang), around.y * r);
}

// Churn-clock offset at cap q of mushroom k, layer-life units: billows churn in half-cap patches, not all at once (one
// clock for whole cap would make whole pattern breathe: twice as many billows every half-life, then back).
// Smooth: where offset changes, a layer's same life has different rotation (mushFlow), stretching pattern, at section
// edge no more than 0.6 of its size by life end
float mushPhase(Mush m, vec3 q, float k) {
    vec3 x = vec3(q.x, q.y - m.capY, q.z) / max(m.capR, 1.0);
    return 0.25 * (sin(1.3 * x.x + 0.9 * x.y + k) + sin(1.1 * x.z - 1.2 * x.y + 2.1 + 0.7 * k)
            + sin(0.8 * x.x + 1.0 * x.z + 4.2 + 1.3 * k));
}

// Unbillowed water-column wall at body q (rho from axis): hollow cylinder from water (slightly below too) to crown
// center; signed distance no farther than true distance, radial/vertical
float mushWall(Mush m, vec3 q, float rho) {
    return max(abs(rho - m.colMid) - m.colHalf, max(q.y - m.colTop, -q.y - 0.02 * m.colTop));
}

// Signed unbillowed cap/stem/skirt/water-column edge distances at body q, blocks (stem radial at this height;
// top/bottom from mushStemSigma; absent column far away): column wall/crown one surface;
// crown ring with elliptical section centered on wall center (WaterModel.Column)
vec4 mushDist(Mush m, vec3 q) {
    float rho = length(q.xz);
    float ring = mushEllipseDist(rho - m.ringR, q.y - m.capY, m.ringA, m.ringB);
    float dome = mushEllipseDist(rho, q.y - m.domeY, m.domeR, m.domeH);
    float column = 1e9;
    if (m.colSigma > 0.0) {
        float crown = mushEllipseDist(rho - m.colMid, q.y - m.colTop, m.crownR - m.colMid, m.crownH);
        column = mushSmin(mushWall(m, q, rho), crown, MUSH_BLEND * mushColumnUnit(m));
    }
    return vec4(mushSmin(ring, dome, MUSH_BLEND * mushCapUnit(m)), rho - mushStemAt(m, q.y),
            mushEllipseDist(rho - m.skirtR, q.y, m.skirtW, m.skirtH), column);
}

// Stem density at height h without edge share: cross-stem depth over width; fades at stem top, absent underground
float mushStemSigma(Mush m, float h) {
    if (h < -0.02 * m.capBot || h > m.stemTop) return 0.0;
    // grounded sphere cap bottom below ground (capBot < 0): fade width at least one block
    float topFade = clamp((m.stemTop - h) / max(0.05 * m.capBot + 1.0, 1.0), 0.0, 1.0);
    return m.stemTau / (2.0 * mushStemAt(m, h)) * topFade;
}

// inflow band outside stem wall at body height h, blocks, and inflow radius: stem plus this band
float mushFootBandAt(Mush m, float h) {
    return mushFootBand(h, m.footTop, m.footShare, m.footMerge);
}

float mushFootAt(Mush m, float h) {
    return mushStemAt(m, h) + mushFootBandAt(m, h);
}

float mushFootSigma(Mush m, float h) {
    return mushFootDensity(h, m.footTop, m.footSigma, m.footShare, min(m.footRise, m.stemTop));
}

const int MUSH_FOOT_COLUMNS = 20;
const int MUSH_FOOT_NODES = 64;
const int MUSH_FOOT_EDGES = 36;
const int MUSH_FOOT_EDGE_NODES = 128;

// Two CPU FootColumn tables: height integral and portion above tapered-band edge at given radius.
float mushFootTable(sampler2D data, int k, int first, int nodes, float fraction) {
    float u = clamp(fraction, 0.0, 1.0) * float(nodes - 1);
    int i = min(int(u), nodes - 2), j = i + 1;
    vec4 a = texelFetch(data, ivec2(first + i / 4, k), 0), b = texelFetch(data, ivec2(first + j / 4, k), 0);
    return mix(a[i % 4], b[j % 4], u - float(i));
}

float mushFootColumn(sampler2D data, int k, Mush m, float h) {
    return mushFootTable(data, k, MUSH_FOOT_COLUMNS, MUSH_FOOT_NODES, h / max(m.footMerge, 1e-6));
}

// band(h) narrows monotonically. No dust above edge at this radius: subtract col(hEdge), not whole-wall shadow.
float mushFootRemoved(sampler2D data, int k, Mush m, float offset) {
    return mushFootTable(data, k, MUSH_FOOT_EDGES, MUSH_FOOT_EDGE_NODES,
            max(offset, 0.0) / max(mushFootBandAt(m, 0.0), 1e-6));
}

// Noiseless cap/stem/skirt/water-column extinction at q, billow mean (edge blurred over billow extent): for
// light-path depth/layer quads; absent column skips edge (zero-thickness wall has zero-width edge)
vec4 mushMean(Mush m, vec3 q) {
    vec4 d = mushDist(m, q);
    float r = mushStemAt(m, q.y), rho = length(q.xz), soft = 0.5 * MUSH_STEM_LOBES * r;
    float stem = mushStemSigma(m, q.y) * mushEdge(d.y, soft);
    // No addition above merge: avoid evaluating carpet profile at every whole-cap/stem depth sample.
    if (m.footShare > 0.0 && q.y >= 0.0 && q.y < m.footMerge) {
        float band = mushFootBandAt(m, q.y);
        if (rho < r + band + soft) stem = max(stem, mushFootSigma(m, q.y) * mushFootWall(rho, r) * mushEdge(rho - r - band, soft));
    }
    vec4 s = vec4(m.capSigma * mushEdge(d.x, 0.5 * MUSH_CAP_LOBES * m.billows * mushCapUnit(m)), stem,
            m.skirtSigma * mushEdge(d.z, 0.5 * MUSH_SKIRT_LOBES * mushSkirtUnit(m)), 0.0);
    if (m.colSigma > 0.0) s.w = m.colSigma * mushEdge(d.w, 0.5 * MUSH_COLUMN_LOBES * mushColumnUnit(m));
    return s;
}

// Distance to mushroom matter from q, world blocks, conservative (0 inside/near): empty-space ray step. Ellipse
// measure grows no faster than distance / smaller semiaxis, so (measure − 1) times it stays before edge;
// stem radius height variation bounded by its slope; column wall already conservative; height-based
// drift stretches body by at most 1 + drift / top
float mushGap(Mush m, vec3 q) {
    float rho = length(q.xz);
    float gap = 1e9;
    if (m.capSigma > 0.0) {
        float ring = (mushRing(rho, q.y - m.capY, m.ringR, m.ringA, m.ringB) - 1.0) * min(m.ringA, m.ringB);
        float dome = (mushEllipse(rho, q.y - m.domeY, m.domeR, m.domeH) - 1.0) * min(m.domeR, m.domeH);
        gap = min(ring, dome) - MUSH_CAP_REACH * mushCapUnit(m);
    }
    if (m.stemTau > 0.0) {
        // Above merge, original stem gap. Below, actual band only; taper derivative
        // bounded by 1.5/(merge-start), width derivative by 2/(3√3).
        float slope = (1.0 + MUSH_STEM_REACH) * max(0.9 * m.stemR, 6.0 * m.stemFlare) / max(m.capBot, 1.0);
        float foot = mushFootBandAt(m, q.y);
        if (m.footShare > 0.0 && q.y < m.footMerge) {
            float span = m.footMerge - min(m.footTop, 0.5 * m.footMerge) + 1e-6;
            slope += m.footShare * (0.385 + 1.5 * max(m.footTop, 1.0) / span);
        }
        float radial = (rho - (1.0 + MUSH_STEM_REACH) * mushStemAt(m, q.y) - foot) / sqrt(1.0 + slope * slope);
        gap = min(gap, max(radial, max(q.y - m.stemTop, -0.02 * m.capBot - q.y)));
    }
    if (m.skirtSigma > 0.0) {
        float u = mushSkirtUnit(m);
        gap = min(gap, (mushEllipse(rho - m.skirtR, q.y, m.skirtW, m.skirtH) - 1.0) * u - MUSH_SKIRT_REACH * u);
    }
    if (m.colSigma > 0.0) {
        float a = m.crownR - m.colMid;
        float crown = (mushRing(rho, q.y - m.colTop, m.colMid, a, m.crownH) - 1.0) * min(a, m.crownH);
        gap = min(gap, min(mushWall(m, q, rho), crown) - MUSH_COLUMN_REACH * mushColumnUnit(m));
    }
    return max(gap, 0.0) / (1.0 + length(m.drift) / max(m.endTop, 1.0));
}

// Line segment o + dir·t (eye-relative o, unit dir) within mushroom bounds: radius boundR cylinder along drift-tilted
// axis (drift share grows with height, mushLocal; above endTop/below ground, boundR margin). In height-shifted
// coordinates axis is vertical and line remains straight: vertical cylinder around drift midpoint included drift-wide empty
// space in late windy frames. Entry ≥ exit means miss
vec2 mushLine(Mush m, vec3 o, vec3 dir) {
    vec3 base = m.base - o;
    vec2 k = m.drift / max(m.endTop, 1.0);
    vec2 d = dir.xz - k * dir.y, c = base.xz - k * base.y;
    float a = dot(d, d), b = dot(d, c), cc = dot(c, c) - m.boundR * m.boundR;
    vec2 t;
    if (a < 1e-8) {
        // axis-aligned line: whole if inside cylinder, miss outside
        if (cc > 0.0) return vec2(1.0, 0.0);
        t = vec2(-1e9, 1e9);
    } else {
        float disc = b * b - a * cc;
        if (disc <= 0.0) return vec2(1.0, 0.0);
        float sq = sqrt(disc);
        t = vec2(b - sq, b + sq) / a;
    }
    float lo = base.y - 0.05 * m.boundTop, hi = base.y + m.boundTop;
    if (abs(dir.y) > 1e-5) {
        float ta = lo / dir.y, tb = hi / dir.y;
        t = vec2(max(t.x, min(ta, tb)), min(t.y, max(ta, tb)));
    } else if (lo > 0.0 || hi < 0.0) {
        return vec2(1.0, 0.0);
    }
    return t;
}

// Eye-ray segment (unit dir) in mushroom bounds (mushLine): in front of eye only
vec2 mushSpan(Mush m, vec3 dir) {
    vec2 t = mushLine(m, vec3(0.0), dir);
    return vec2(max(t.x, 0.0), t.y);
}

// Octahedral direction map (x/y/z, y up), u/v 0..1 (NukeBallSurface.octU/octV, pack.py: octa_decode):
// upper hemisphere is central diamond, lower its corners
float mushOctU(float x, float y, float z) {
    float s = abs(x) + abs(y) + abs(z);
    if (y < 0.0) return 0.5 * ((1.0 - abs(z / s)) * sign(x) + 1.0);
    return 0.5 * (x / s + 1.0);
}

float mushOctV(float x, float y, float z) {
    float s = abs(x) + abs(y) + abs(z);
    if (y < 0.0) return 0.5 * ((1.0 - abs(x / s)) * sign(z) + 1.0);
    return 0.5 * (z / s + 1.0);
}

// Sphere-surface pattern visibility at frame position f (MushroomField.ballSurface): absent before luminosity minimum (f = −1),
// surface is smooth front; emerges through clearing front by breakaway (f = 0) (FireballModel.unmasked)
float mushBallContrast(float f) {
    return clamp(f + 1.0, 0.0, 1.0);
}

// temperature position in black-body light row (0..1)
float mushBbU(float kelvin) {
    float x = (log2(max(kelvin, MUSH_BB_FROM)) - log2(MUSH_BB_FROM)) / (log2(MUSH_BB_TO) - log2(MUSH_BB_FROM));
    return clamp(x, 0.0, 1.0);
}

// Lambertian-sphere light, radius R (squared r2), distance d (squared d2) from center, as surface-light share:
// R²/max(d², R²) (MushroomModel.light)
float mushBallFalloff(float d2, float r2) {
    return r2 / max(d2, r2);
}

// Ball light on world face as share of air-free ball light on facing white wall (MushroomField.ballFace): direct
// beam e^(−σd), face cosine (nv between face normal and ball direction); air-scattered is total direct/diffuse
// pulse light transmitted (Glasstone 1964, fig. 7.104: e^(−(D/V)^0.544), D/V = σd/3.912)
// minus direct, from sky around ball, face share (1 + nv)/2 (est.: forward scatter comes from ball side); plus
// ball light reflected from ground of albedo albedo onto horizontal, face ground share (1 − ny)/2. Under
// pall: direct/air-scattered ball light passes through it (through, e^(−τ/μ)); pall downward-scattered horizontal light diffuse per
// overhead light unit, face sky share (1 + ny)/2. vy is sine of ball elevation above point horizon. No terrain/building
// shadows. Closer than ~0.05 V, Glasstone formula below direct gives no diffuse contribution (MushroomField.ballFace).
float mushBallFace(float nv, float vy, float ny, float sd, float albedo, float through, float diffuse) {
    float direct = exp(-sd);
    float scattered = max(exp(-pow(max(sd, 0.0) / 3.912, 0.544)) - direct, 0.0);
    float beam = direct * max(nv, 0.0) + scattered * 0.5 * (1.0 + nv);
    float ground = direct * max(vy, 0.0) + scattered * 0.5 * (1.0 + vy);
    float sky = (direct + scattered) * diffuse;
    return beam * through + sky * 0.5 * (1.0 + ny) + albedo * (ground * through + sky) * 0.5 * (1.0 - ny);
}

// Black-body light at T in frame units (noon white wall = 1), linear rgb (BlackBody)
vec3 mushBlackBody(sampler2D data, float kelvin) {
    vec2 size = vec2(textureSize(data, 0));
    float x = (mushBbU(kelvin) * (MUSH_BB_NODES - 1.0) + 0.5) / size.x;
    return texture(data, vec2(x, (float(MUSH_BB_ROW) + 0.5) / size.y)).rgb;
}

// presentation: fireball (client/nuclear/NukeExposure copy, checked by MushroomGlslTest), fire exposure and film
// curve like conventional ball (tools/bake_fx/pack.py). Ball brightness spans hundreds/thousands of white-wall units; frame
// clipped it to a flat disk. Camera exposed for fire: core brightness L (noon whites, without eye adaptation) on
// curve K·L^β, dim glow in frame measure with eye adaptation, smooth transition
const float MUSH_FILM_KEY = 0.233;
const float MUSH_FILM_FOLLOW = 0.15;
// above white: this channel-coupling share (pack.py: white)
const float MUSH_FILM_WHITE = 0.35;
// Film curve applies while ball remains a sphere; as it rolls into ring (ring share, MushroomModel.rollUp), smoothly
// return to old path (light with pall, overexposure to pixel coverage in one hue): curve-to-old transition at these ring shares
const float MUSH_FILM_ROLL_FROM = 0.15;
const float MUSH_FILM_ROLL_TO = 0.45;

// Ball exposure reduction relative to frame when core emits key (linear, white wall = 1, eye-adapted
// to sky by adapt: day 1, night ~1200)
float mushBallGain(float key, float adapt) {
    float r = pow(max(key, 0.0), 1.0 - MUSH_FILM_FOLLOW) * pow(max(adapt, 1e-9), MUSH_FILM_FOLLOW) / MUSH_FILM_KEY;
    return 1.0 / sqrt(1.0 + r * r);
}

// Mushroom emission share using film curve at ring share roll (NukeExposure.filmShare): whole sphere, none for rolled cap;
// cap uses old path, hot interior glows orange through haze in billow gaps
float mushFilmShare(float roll) {
    return 1.0 - smoothstep(MUSH_FILM_ROLL_FROM, MUSH_FILM_ROLL_TO, roll);
}

// Per-channel film curve (ACES, Narkowicz 2016 approximation): exposed light → linear 0..1
float mushFilm(float x) {
    float v = max(x, 0.0);
    return min(v * (2.51 * v + 0.03) / (v * (2.43 * v + 0.59) + 0.14), 1.0);
}

// Above-white channel coupling: white share for pixel whose brightest exposed channel is top
float mushFilmWhite(float top) {
    return MUSH_FILM_WHITE * (1.0 - exp(-max(top - 1.0, 0.0)));
}

// Light x (ball-exposed) on screen: per-channel curve/channel coupling, linear 0..1
vec3 mushFilm3(vec3 x) {
    vec3 d = vec3(mushFilm(x.r), mushFilm(x.g), mushFilm(x.b));
    return d + (1.0 - d) * mushFilmWhite(max(max(x.r, x.g), x.b));
}

// Ball on screen (NukeExposure.cap): surface light e (linear, eye adaptation adapt), exposed for same surface,
// caps ball light on surroundings (Wilson-cloud shell in fx_pall)
vec3 mushBallCap(vec3 e, float adapt) {
    return mushFilm3(e * mushBallGain(dot(e, vec3(0.2126, 0.7152, 0.0722)), adapt));
}

// Mushroom k transmittance along ray dir over [0, len], noiseless (steps steps), for layer quads before particle; air is
// frame air (pallAir)
float mushTransmit(sampler2D data, vec3 dir, float len, int steps, vec4 air) {
    float through = 1.0;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(data, k)) continue;
        Mush m = mushLoad(data, k);
        vec2 span = mushSpan(m, dir);
        span.y = min(span.y, len);
        if (span.y <= span.x) continue;
        float ds = (span.y - span.x) / float(steps), tau = 0.0;
        for (int i = 0; i < steps; i++) {
            tau += dot(mushMean(m, mushLocal(m, dir * (span.x + (float(i) + 0.5) * ds))), vec4(1.0)) * ds;
        }
        // mushroom in air haze fades into sky behind (pallAir): light/opacity × share transmitted through air, not
        // fog color; what lies behind becomes more visible
        through *= 1.0 - pallAir(air, 0.5 * (span.x + span.y), dir.y) * (1.0 - exp(-tau));
    }
    return through;
}

// ---------------------------------------------------------------- mushroom sunlight shadow (PallPass: fx_mush_shadow)
// Shadow map: mushroom k has MUSH_SHADOW × MUSH_SHADOW tile (rows k·MUSH_SHADOW…) in sun-perpendicular plane (axes
// mushShadowAxes, origin/sides in table texel MUSH_SHADOW_RECT, epicenter-relative). Texel is a sun-aligned line; stores
// sunlight share mushroom transmits on it to three heights above epicenter ground: ground, midpoint to
// world top, top (r/g/b; top in MUSH_SHADOW_TOP), relative to direct beam on horizontal ground: direct beam through
// noiseless mushroom depth above height plus forward scattering (mushSeen). Share can exceed 1: thin white
// matter low above layer under low sun scatters forward brighter than beam; map consumers clamp to 1
// (mushShadow). Sunlight above world point traverses matter on its line above it: mushroom matter sunward is above
// point, away from sun below (sun above horizon). Tile calculated once per frame (MUSH_SHADOW² lines), one sample per
// mushroom per world point. Used by world (fx_pall_composite) and pall sunlight (fx_pall: dust under cap shadowed like
// stem); not yet by layer particles.
const int MUSH_SHADOW = 128;
// mushroom-row texels: map tile (MushroomField.SHADOW_RECT), world top above epicenter ground (SHADOW_TOP)
const int MUSH_SHADOW_RECT = 17;
const int MUSH_SHADOW_TOP = 18;
// maximum steps per line within mushroom bounds (empty jumps to matter, mushGap, at least one step); stop after optical depth
// MUSH_SHADOW_DARK: dark below anyway, direct light e^−100, forward-scattered below one percent
// even for whitest matter, skirt/column mist, and 0.1 kt cap near end of life (ω' 0.9975: ~1 % through depth 80,
// 0.3 % through 100), even across full sky under low sun (over half at depth 16; MushroomGlslTest)
const int MUSH_SHADOW_STEPS = 128;
const float MUSH_SHADOW_DARK = 100.0;
// sun below this elevation (sine) casts shadows from this height: setting-sun mushroom shadow spans hundreds of kilometers;
// nearly horizontal lines cannot distinguish map-layer heights, and that sunlight is weak anyway
const float MUSH_SHADOW_LOW = 0.05;

// Unit shadow-sun direction: elevation at least MUSH_SHADOW_LOW, same horizontal direction
// (MushroomField.shadowSun)
vec3 mushShadowSun(vec3 sun) {
    float e = clamp(sun.y, MUSH_SHADOW_LOW, 1.0), h = length(sun.xz);
    if (h < 1e-6) return vec3(0.0, 1.0, 0.0);
    float c = sqrt(1.0 - e * e) / h;
    return vec3(sun.x * c, e, sun.z * c);
}

// Axes of plane perpendicular to sun s (MushroomField.shadowAxes): a perpendicular to s and world z (Minecraft sun moves
// in x/y plane, making a horizontal), b = s × a
void mushShadowAxes(vec3 s, out vec3 a, out vec3 b) {
    vec3 c = cross(s, vec3(0.0, 0.0, 1.0));
    if (dot(c, c) < 0.01) c = cross(s, vec3(1.0, 0.0, 0.0));
    a = normalize(c);
    b = cross(s, a);
}

// Cloud half-width across sun at elevation mu (sine; MushroomField.across): equal-area disk radius of shadow
// of radius-r, height-h column onto sun-perpendicular plane, πr²·mu from top, 2rh·√(1 − mu²) from side. Cap/skirt are flat,
// narrow strips under low sun; tall stem visible at full height from side. Ground-view sky share occluded by cloud
// follows shadow area (small solid angle is area / distance²).
float mushAcross(float r, float h, float mu) {
    return sqrt(r * (r * mu + 0.6366198 * h * sqrt(max(1.0 - mu * mu, 0.0))));
}

// Cloud forward-scattered light reaching horizontal ground relative to beam on it (beam cosine mu,
// as frame output), versus edgeless slab (MushroomField.seen): two streams assume infinite lateral slab, visible
// across whole hemisphere around sun from below, giving horizontal ground (1 + μ)/2 of its flux. Width-w cloud (half-width,
// mushAcross), distance d along sun line, appears as disk with hemisphere-flux share v = w²/(w² + d²); narrow cloud lies near
// sun, reaching ground like beam (v·μ); smooth between limits (cloud wholly above horizon no more than 12 % brighter than exact
// v·μ). Without this, thin stem/cap edge barely cast shadows: their forward-scattered light came from
// whole sky.
float mushSeen(float w, float d, float mu) {
    float v = w * w / max(w * w + d * d, 1e-6);
    return v * (mu + (1.0 - mu) * 0.5 * v) / mu;
}

// Sunlight above point at height h above epicenter ground, from three map layers: t0 above epicenter ground,
// t1 above midpoint to world top, t2 above top (top is world top above same ground); linear between layers, below epicenter
// ground as at ground, above top as at top (MushroomField.shadowLayer)
float mushShadowLayer(float h, float top, float t0, float t1, float t2) {
    float x = clamp(h / max(top, 1.0), 0.0, 1.0);
    if (x < 0.5) return t0 + (t1 - t0) * 2.0 * x;
    return t1 + (t2 - t1) * (2.0 * x - 1.0);
}

// Sunlight share at p as mushShadow below, but map-layer height at least low of world top above epicenter ground. Pall (fx_pall:
// pallSunAt) is airborne, while map layers are ground/midpoint/top: below midpoint map would blend ground
// shadow, including mushroom matter below point on sun line (under low sun, stem/skirt beneath carpet on lit
// side). With low = ½, pall points below midpoint take shadow from matter above midpoint (cap/almost whole stem, kilometers
// high); ⚠ stem between point/midpoint (hundreds of blocks) casts no shadow on carpet behind it
float mushShadowFrom(sampler2D data, sampler2D map, vec3 sun, vec3 p, float low) {
    vec3 a, b;
    mushShadowAxes(sun, a, b);
    float light = 1.0;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(data, k)) break;
        vec4 rect = texelFetch(data, ivec2(MUSH_SHADOW_RECT, k), 0);
        if (rect.z <= 0.0) continue;
        vec3 q = p - texelFetch(data, ivec2(0, k), 0).xzy;
        vec2 uv = (vec2(dot(q, a), dot(q, b)) - rect.xy) / rect.zw;
        if (uv.x <= 0.0 || uv.y <= 0.0 || uv.x >= 1.0 || uv.y >= 1.0) continue;
        // inside own tile: sample cannot enter neighboring tile (another mushroom)
        uv = clamp(uv, vec2(0.5 / float(MUSH_SHADOW)), vec2(1.0 - 0.5 / float(MUSH_SHADOW)));
        vec4 t = textureLod(map, vec2(uv.x, (float(k) + uv.y) / float(MUSHROOMS)), 0.0);
        float top = texelFetch(data, ivec2(MUSH_SHADOW_TOP, k), 0).x;
        light *= clamp(mushShadowLayer(max(q.y, low * top), top, t.r, t.g, t.b), 0.0, 1.0);
    }
    return light;
}

// Direct sunlight share frame mushrooms transmit to eye-relative p from shadow map map, including forward
// scattering (mushShadowSun, sun); outside tiles 1
float mushShadow(sampler2D data, sampler2D map, vec3 sun, vec3 p) {
    return mushShadowFrom(data, map, sun, p, 0.0);
}
