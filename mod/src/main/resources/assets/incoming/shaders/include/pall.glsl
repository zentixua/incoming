// Pall after the blast wave (client/nuclear/PallField — laws, client/fx/layer/PallPass — passes): the field of one
// detonation from a radius table. Shared by the volume (fx_pall) and the squares of the effects layer (fx.vsh: what the pall
// covers in front of a particle). Numbers and laws are copies of PallField and VolumeNoise.shape (PallGlslTest computes
// these functions and checks them against Java).
//
// Detonation slot — mat4 by columns:
//   [0] = (epicenter x, z from the eye; ground under the epicenter from the eye by y; how far the table goes, blocks)
//   [1] = (drift by wind x, z of what was lifted at the moment of detonation, blocks — for dust and spray its share by their
//          age (haze row, a), for the basic wave all of it; length of the haze trail, blocks; 1 — slot exists)
//   [2] = (wind x, z — unit; v of the dust row in the table; v of the haze row)
//   [3] = (attenuation of dense dust — a measure of puff density, 1/block; noise tile, blocks; seconds after detonation;
//          v of the spray row)
// Table: across the width — radius 0..reach; rows — dust (attenuation at ground, height, smoke share; 0), spray over water
// (attenuation over water, its height — own layer; 0; 0) and haze (attenuation, height, fire light from below; share of dust
// drift at the same radius — PallField.share: wind carries dust from the arrival of the front, at the front it stays in
// place). Dust and spray younger than the positive phase belong to the wall head (pallWall*); the table at the front hands
// them over to it.
// Water of slot W — vec4: (radius of the basic wave, its top — blocks; its attenuation in the densest part, 1/block, 0 — no
// wave; 1 — there is water under the pall and its map — channel g of the slot's relief map, PallGround; 0 — everywhere land).

// Substance of the pall (client/nuclear/PallLight; PallGlslTest checks): single-scattering albedo ω' by channel and asymmetry
// g' after delta-Eddington (the forward peak f = g² goes into unscattered light, the remainder g' = g / (1 + g)). The
// attenuation of the table (by visibility, as the eye sees it) — already σ' of delta-Eddington: both the view ray and the
// light to the point use it. ω' — from the color of the thick layer (fx_pall: DUST, SMOKE, HAZE, MIST; storm soot — SOOT;
// in linear — pallLinear) by inverting the two-stream reflection: thick pall — the same color as the wall of dust and spray.
// Dust, smoke and haze — g = 0.6, water droplets — 0.85, soot — 0.5.
const vec3 PALL_DUST_W = vec3(0.8687934, 0.8099112, 0.7286264);
const vec3 PALL_SMOKE_W = vec3(0.5520492, 0.5170717, 0.4837395);
const vec3 PALL_HAZE_W = vec3(0.7175592, 0.6833941, 0.6480812);
const vec3 PALL_MIST_W = vec3(0.9948032, 0.9964194, 0.9973450);
const vec3 PALL_SOOT_W = vec3(0.4368484, 0.4180439, 0.4008365);
const float PALL_DUST_G = 0.375;
const float PALL_MIST_G = 0.4594595;
const float PALL_SOOT_G = 0.3333333;
// Scale of the height of clean air in units of the haze height scale (Sight.HAZE_HEIGHT / AIR_HEIGHT)
const float PALL_AIR_HEIGHTS = 0.15;

// Mean over a segment of the attenuation share at its start, when the segment rises by x height scales: (1 − e^−x) / x
// (Sight.along). Going down more than 80 scales — as 80, so that exp does not overflow float (the limit is e^88): even at
// the smallest effects_scale 0.005 this is a descent of 480 blocks, more than the height of the world
float pallAlong(float x) {
    if (abs(x) < 1e-3) return 1.0 - 0.5 * x;
    float y = max(x, -80.0);
    return (1.0 - exp(-y)) / y;
}

// Thickness of air from the eye over t blocks along a ray with vertical share dy (Sight.Air.transmittance): near the eye —
// haze, clean air and weather — haze, clean, weather (1/block); kh — 1 / haze height scale (1/block). A segment behind the
// eye (t < 0) — empty
float pallAirDepth(float t, float dy, float haze, float clean, float weather, float kh) {
    float d = max(t, 0.0), rise = dy * d * kh;
    return d * (haze * pallAlong(rise) + clean * pallAlong(rise * PALL_AIR_HEIGHTS) + weather);
}

// Share of the light of a point at t blocks from the eye along a ray with vertical share dy, reaching through the air of the
// frame air (uniform PallAir: PallPass, Sight.Air in the model's blocks — effects_scale times smaller than meters): haze,
// clean air and weather at the eye, 1/block; 1 / haze height scale, 1/block. Haze in the distance: the mushroom, the pall and
// the wall fade into the sky behind them — through the air to them, which thins with height (a cap at 5 km is seen through
// the haze of the lower kilometers, not through 5 km of haze at ground level)
float pallAir(vec4 air, float t, float dy) {
    return exp(-pallAirDepth(t, dy, air.x, air.y, air.z, air.w));
}

// albedo of the ground under the pall: land and water (PallLight.LAND, SEA)
const float PALL_LAND = 0.2;
const float PALL_SEA = 0.06;

// Pall of the slot at a point (pallField)
struct PallAt {
    // attenuation without noise (1/block): dust, haze, water droplets (spray and the basic wave)
    vec3 sigma;
    // columns above the point (attenuation × blocks): the same
    vec3 column;
    // columns of the whole pall above the floor of the layer (pallField: floorUp; a point below it — above the point):
    // thickness of the layer in which the point is
    vec3 slab;
    // share of smoke in the dust; light of fires from below
    float smoke;
    float fire;
    // height of the point in shares of the pall: dust, haze
    vec2 height;
    // share of the densest dust (with water droplets — over all that is lifted)
    float dense;
    // share of water there, from where dust and spray were lifted (in the first seconds, while the drift is small — under
    // the point)
    float wet;
    // how much the dust layer is mixed (PallField.mixed): tufts of the young layer spread out (pallMixed)
    float mixed;
};

// share of density at the ground at height x of the pall (PallField.profile)
float pallProfile(float x) {
    if (x <= 0.0) return smoothstep(-1.0, -0.4, x);
    return (1.0 - smoothstep(0.45, 1.0, x)) * (0.55 + 0.45 * exp(-2.5 * x));
}

// integral of the profile from x to the top, in shares of the height (PallField.column)
float pallColumn(float x) {
    float xa = clamp(x, 0.0, 1.0);
    float above = 0.548 * pow(1.0 - xa, 2.45) * (1.0 + 0.83 * xa);
    if (x >= 0.0) return above;
    float f = clamp((-x - 0.4) / 0.6, 0.0, 1.0);
    return above + min(-x, 0.4) + 0.6 * (f - f * f * f + 0.5 * f * f * f * f);
}

// edge of the table: the pall fades toward it (PallField.edge)
float pallEdge(float u) {
    return 1.0 - smoothstep(0.96, 1.0, u);
}

// basic wave by radius (PallField.surgeProfile): u — share of its radius; a rim rolls at the edge — denser
float pallSurge(float u) {
    return (0.35 + 0.65 * smoothstep(0.2, 0.85, u)) * (1.0 - smoothstep(0.9, 1.05, u));
}

// top of the basic wave by radius, share of its height (PallField.surgeTop): the rim at the edge is higher
float pallSurgeTop(float u) {
    return 0.55 + 0.45 * smoothstep(0.3, 0.95, u);
}

// share of water under the point rel (from the epicenter along the ground) by the slot's map (PallGround.water); no map
// (G.x = 0) — land
float pallWater(sampler2D ground, vec4 G, vec2 rel) {
    if (G.x <= 0.0) return 0.0;
    return clamp(textureLod(ground, rel / (2.0 * G.x) + 0.5, 0.0).g, 0.0, 1.0);
}

// Field of slot S at point p (from the eye), without noise (the average over noise is that); ground, G — relief and water
// maps of the slot; W — water of the slot; floorUp — floor of the layer under the point (top of the relief by the map,
// blocks above the ground under the epicenter; 0 — ground under the epicenter): slab is computed from it, and above a hill
// the layer rises from it — the dust is lifted by the wind from its slope (it drains into the valley: there the layer is
// from the ground under the epicenter, pallProfile; PallField.sigma is the same). Substance — by the water where it was
// lifted from (PallField: dust blown onto water stays dust): dust and spray — at the point, minus the drift (share by the
// age of the dust — by the node of the point itself, PallField.source), where the table is also read; haze — at its source on
// the axis of the trail (there are no fire over water), only if it is here; the basic wave — water lifted at the epicenter,
// over whatever it rolls on (W.z = 0 — there is none). Without water under the pall (W.w = 0) the map is not read.
// bumps — top of the dust layer above the point in shares of the table's top (bumps of the top, PallField.BUMPS; without
// noise — 1)
PallAt pallField(sampler2D table, sampler2D ground, mat4 S, vec4 G, vec4 W, vec3 p, float floorUp, float bumps) {
    vec2 q = p.xz - S[0].xy;
    float hill = max(floorUp, 0.0);
    float above = p.y - S[0].z - hill, reach = S[0].w;
    floorUp -= hill;
    vec2 lift = q - S[1].xy * texture(table, vec2(min(length(q) / reach, 1.0), S[2].w)).a;
    float r = length(lift);
    float ud = r / reach;
    vec4 dust = texture(table, vec2(min(ud, 1.0), S[2].z));
    float topD = max(dust.g, 1.0) * bumps, edge = pallEdge(ud);
    float sd = dust.r * edge, ss = 0.0, topS = 1.0;
    float len = S[1].z, along = clamp(dot(q, S[2].xy), 0.0, len);
    vec2 source = q - S[2].xy * along;
    float uh = length(source) / reach;
    vec4 haze = texture(table, vec2(min(uh, 1.0), S[2].w));
    float topH = max(haze.g, 1.0), sh = haze.r * (1.0 - 0.5 * along / max(len, 1.0)) * pallEdge(uh), fire = haze.b;
    float wet = 0.0, sg = 0.0, topG = 1.0;
    if (W.w > 0.5) {
        // spray — its own table row (its own height): only the detonation under whose pall there is water reads it
        vec4 spray = texture(table, vec2(min(ud, 1.0), S[3].w));
        ss = spray.r * edge;
        topS = max(spray.g, 1.0);
        if (sd + ss > 0.0) wet = pallWater(ground, G, lift);
        sd *= 1.0 - wet;
        ss *= wet;
        if (sh + fire > 0.0) {
            float dry = 1.0 - pallWater(ground, G, source);
            sh *= dry;
            fire *= dry;
        }
    }
    if (W.z > 0.0) {
        // the basic wave is carried entirely by the drift: it was lifted in the first seconds
        float ug = length(q - S[1].xy) / max(W.x, 1.0);
        sg = W.z * pallSurge(ug);
        topG = max(W.y * pallSurgeTop(ug), 1.0);
    }
    PallAt a;
    a.height = vec2(above / topD, above / topH);
    float hg = above / topG, hs = above / topS;
    a.sigma = vec3(sd * pallProfile(a.height.x), sh * pallProfile(a.height.y), ss * pallProfile(hs) + sg * pallProfile(hg));
    a.column = vec3(sd * topD * pallColumn(a.height.x), sh * topH * pallColumn(a.height.y),
            ss * topS * pallColumn(hs) + sg * topG * pallColumn(hg));
    a.slab = vec3(sd * topD * pallColumn(min(a.height.x, floorUp / topD)), sh * topH * pallColumn(min(a.height.y, floorUp / topH)),
            ss * topS * pallColumn(min(hs, floorUp / topS)) + sg * topG * pallColumn(min(hg, floorUp / topG)));
    a.smoke = dust.b;
    a.fire = fire * exp(-3.0 * max(a.height.y, 0.0));
    a.dense = (sd + ss + sg) / max(S[3].x, 1e-6);
    a.wet = wet;
    a.mixed = dust.a;
    return a;
}

// Unevenness of the top of the dust layer (PallField.BUMPS): the top above the point — (1 ± it) of the table's top
const float PALL_BUMPS = 0.3;
// Fluctuations of density of the mixed layer — share of the former (PallField.MIXED)
const float PALL_MIXED = 0.2;

// Puffs of the pall in the mixed layer (PallField.clumps): density multiplier shape (on average 1), fluctuations fade from the
// tufts of the young layer toward PALL_MIXED by mixed; average — 1
float pallMixed(float shape, float mixed) {
    return 1.0 + (shape - 1.0) * (1.0 - (1.0 - PALL_MIXED) * mixed);
}

// Shape of puffs by noise (VolumeNoise.shape): n — channels of the noise tile (puffs, small edges), fill — how solid the pall is;
// the average over the tile — 1, density — smoothly from gaps to twice (at the edge three times) denser than average.
float pallShape(vec2 n, float fill) {
    return smoothstep(0.4 - 0.45 * fill, 0.7, n.x - 0.3 * (1.0 - n.y)) / (0.3 + 0.225 * fill);
}

// Small puffs (VolumeNoise.detail): r — the puff channel of the tile four times finer; density multiplier 0.3..1.7, on average
// over the tile — 1 (the channel is uniform), so the average density of the field stays its density. The pall is a cascade:
// large puffs, small ones inside them; finer than what a ray distinguishes (pixel, step), small ones — their average, 1
// (fx_pall: fineAt).
float pallDetail(float r) {
    return 0.3 + 1.4 * smoothstep(0.2, 0.8, r);
}

// Segment of a ray (from the eye, dir — unit) in the box [lo, hi]: (entry, exit); entry > exit — misses.
vec2 pallBox(vec3 dir, vec3 lo, vec3 hi) {
    vec3 inv = 1.0 / (dir + vec3(equal(dir, vec3(0.0))) * 1e-6);
    vec3 a = lo * inv, b = hi * inv;
    vec3 t0 = min(a, b), t1 = max(a, b);
    return vec2(max(max(t0.x, t0.y), max(t0.z, 0.0)), min(min(t1.x, t1.y), t1.z));
}

// ---------------------------------------------------------------- relief under the pall (client/nuclear/PallGround)
// Map of the slot: r — heights of the top of the relief above the ground under the epicenter, g — share of water (pallWater);
// the middle of the map is the epicenter, the edges — as at the edge; G = (half-side of the map; bottom and top of the map;
// cell — blocks), G.x = 0 — there is no map.

// top of the relief under point xz (from the eye) — from the eye by y (PallGround.at)
float pallGroundAt(sampler2D ground, mat4 S, vec4 G, vec2 xz) {
    return S[0].z + textureLod(ground, (xz - S[0].xy) / (2.0 * G.x) + 0.5, 0.0).r;
}

// Relief under point xz (from the eye) above the ground under the epicenter, blocks: the floor of the pall layer (pallField:
// floorUp); no map — the ground under the epicenter
float pallRelief(sampler2D ground, mat4 S, vec4 G, vec2 xz) {
    if (G.x <= 0.0) return 0.0;
    return pallGroundAt(ground, S, G, xz) - S[0].z;
}

// Where the ray dir from the eye (unit) goes under the slot's relief, up to end; 1e9 — it does not. There is no world behind
// the pixel: the ray has passed everything drawn (further than from along the ground from the eye — the draw distance) and
// hit nothing, so we search only farther. Steps grow with distance (far away a map cell is smaller on screen), the start is
// shifted by a jitter of the step, the last is at end; between a step above the relief and a step below it — by a secant.
// The map is coarser than the world: if at the start it is above the ray (the ray passed over the real relief), there it
// drops under the ray and, four cells farther, again as is.
float pallFloor(sampler2D ground, mat4 S, vec4 G, vec3 dir, float from, float end, int steps, float jitter) {
    if (G.x <= 0.0 || S[1].w < 0.5) return 1e9;
    // frame of the map: along the ground — the map, above — the top of the relief, below — without edge (a ray downward ends under the relief)
    vec2 span = pallBox(dir, vec3(S[0].x - G.x, -1e6, S[0].y - G.x), vec3(S[0].x + G.x, S[0].z + G.z, S[0].y + G.x));
    float fade = 4.0 * G.w, near = from / max(length(dir.xz), 1e-3);
    float ta = max(max(span.x, near), 1.0);
    float tb = min(span.y, end);
    // below the lowest place of the map the ray is surely under the relief (and where the map is lowered at the start — no longer)
    if (dir.y < 0.0) tb = min(tb, max((S[0].z + G.y) / dir.y, ta) + fade);
    if (tb <= ta) return 1e9;
    float g0 = pallGroundAt(ground, S, G, dir.xz * ta);
    // lower only where the ray has passed the drawn world; entered the map farther — until then it went over the unknown
    float lift = max(g0 + 1.0 - dir.y * ta, 0.0) * step(ta, near + 1.0);
    float ratio = pow(tb / ta, 1.0 / float(steps));
    float prevT = ta, prevD = dir.y * ta - g0 + lift;
    float t = ta * pow(ratio, jitter);
    for (int i = 0; i <= steps; i++) {
        float tt = min(t, tb);
        vec3 q = dir * tt;
        float d = q.y - pallGroundAt(ground, S, G, q.xz) + lift * max(1.0 - (tt - ta) / fade, 0.0);
        // the start is already under the map (the top of the frame — by heights in R16F, the texture may be a bit higher) —
        // the ray ends at the start
        if (d <= 0.0) return prevT + (tt - prevT) * max(prevD, 0.0) / max(prevD - d, 1e-6);
        prevT = tt;
        prevD = d;
        t *= ratio;
    }
    return 1e9;
}

// Window of outside air near the eye (client/fx/layer/OpenAir), blocks
const vec3 PALL_OPEN_SIZE = vec3(96.0, 64.0, 96.0);

// Share of outside air at point p (from the eye): the Minecraft sky light / 15 in the point's block (OpenAir) — dust, haze and
// the head of the wall are where the air is connected to the open sky; in an adit — no. at — the lower corner of the window
// from the eye (w — 1, if the window exists), wrap — where this corner is in the texture (the window in it modulo its size).
// Beyond the window — above the coarse relief map of the slot S (ground, G: pallRelief) — outside air, below — the share of
// the block where the ray from the eye left the window.
float pallOpen(sampler3D open, vec4 at, vec3 wrap, sampler2D ground, mat4 S, vec4 G, vec3 p) {
    if (at.w < 0.5) return 1.0;
    vec3 q = p - at.xyz;
    if (any(lessThan(q, vec3(0.0))) || any(greaterThanEqual(q, PALL_OPEN_SIZE))) {
        // above the relief — open (the pall beyond the mouth of an adit is seen from its depth); below it — along the ray: all places
        // of the pall and shadows are on rays from the eye, and the ray goes through air, so a long adit and a big cave beyond the
        // window stay closed
        if (p.y > S[0].z + pallRelief(ground, S, G, p.xz)) return 1.0;
        vec3 e = -at.xyz, d = q - e, t = vec3(1e9);
        if (abs(d.x) > 1e-6) t.x = (step(0.0, d.x) * PALL_OPEN_SIZE.x - e.x) / d.x;
        if (abs(d.y) > 1e-6) t.y = (step(0.0, d.y) * PALL_OPEN_SIZE.y - e.y) / d.y;
        if (abs(d.z) > 1e-6) t.z = (step(0.0, d.z) * PALL_OPEN_SIZE.z - e.z) / d.z;
        q = clamp(e + d * clamp(min(min(t.x, t.y), t.z), 0.0, 1.0), vec3(0.5), PALL_OPEN_SIZE - 0.5);
    }
    // integer: mod on float at the edge (x = 96 with a reciprocal one ulp smaller) would give 96 — past the texture
    return texelFetch(open, (ivec3(floor(q)) + ivec3(wrap)) % ivec3(PALL_OPEN_SIZE), 0).r;
}

// Distance to the world behind the pixel (SceneDepth), fit for the pall: sky, and also NaN, infinity and “behind the eye”
// (foreign LOD depth, far plane) — 1e9, there is no world behind the pixel. Otherwise the ray would go through kilometers
// of dust, or a NaN would get into the accumulated pall — a black frame.
float pallScene(float d) {
    if (d < 0.0) return 1e9;
    if (d < 1e8) return d;
    return 1e9;
}

// How much the distances d and e to the world are one place (1 — one, toward 0 — different): the weight of a pixel of the pall
// frame of its neighbor on screen (fx_pall_resolve, fx_pall_composite) — at the edge of an object the pall of the sky does not
// lie on the wall and vice versa
float pallAlike(float d, float e) {
    float rel = abs(d - e) / max(min(d, e), 0.1);
    return 1.0 / (1.0 + 400.0 * rel * rel);
}

// Slope of the inverse distance to the world at pixel at of the full frame (per pixel in x and y): on a plane 1/distance is
// linear on screen, and the place of the neighbor on the same surface — pallAhead. On each axis — the difference with the
// neighbor that is closer to the pixel itself: at the edge of an object the other neighbor is background
vec2 pallSlope(sampler2D scene, ivec2 at, ivec2 size, float depth) {
    float w = 1.0 / depth;
    float r = 1.0 / pallScene(texelFetch(scene, clamp(at + ivec2(1, 0), ivec2(0), size - 1), 0).r) - w;
    float l = w - 1.0 / pallScene(texelFetch(scene, clamp(at - ivec2(1, 0), ivec2(0), size - 1), 0).r);
    float u = 1.0 / pallScene(texelFetch(scene, clamp(at + ivec2(0, 1), ivec2(0), size - 1), 0).r) - w;
    float d = w - 1.0 / pallScene(texelFetch(scene, clamp(at - ivec2(0, 1), ivec2(0), size - 1), 0).r);
    vec2 s = vec2(r, u);
    if (abs(l) < abs(r)) s.x = l;
    if (abs(d) < abs(u)) s.y = d;
    // a ridge or a gap one pixel wide (both neighbors are another place, on opposite sides beyond the pallAlike tolerance, 5%):
    // the slope of the surface cannot be learned here, a slope across the edge would lead “the same place” to the background
    if (l * r < 0.0 && min(abs(l), abs(r)) > 0.05 * w) s.x = 0.0;
    if (d * u < 0.0 && min(abs(d), abs(u)) > 0.05 * w) s.y = 0.0;
    return s;
}

// Distance to the world that would be on the same surface as the pixel (distance depth, slope — pallSlope), at offset
// full-frame pixels from it: a neighbor is the same place if its distance is close to this one (pallAlike), not to the pixel's
// own distance: near the ground far away under a grazing ray the distance changes by tens of percent over a few pixels, and
// without the slope flat ground would come out as an “edge” at every row of rays
float pallAhead(float depth, vec2 slope, vec2 offset) {
    return 1.0 / max(1.0 / depth + dot(slope, offset), 1e-9);
}

// Place p along the axis (full-frame pixels, the pixel center is +0.5) in the lattice of rays of the pall frame, factor times
// smaller: the ray of its pixel t goes through the center of the full-frame pixel floor((t + 0.5)·factor) (fx_pall) — an
// integer t where the ray
float pallLattice(float p, float factor) {
    return (p - 0.5 - floor(0.5 * factor)) / factor;
}

// A pall pixel is valid: light is finite (also in half precision of the frame), transmittance 0..1; NaN is not valid.
bool pallValid(vec4 v) {
    return all(lessThan(abs(v.rgb), vec3(6.0e4))) && v.a >= 0.0 && v.a <= 1.0;
}

// Glare of fires under the dust, like at the wall of dust (CloudPuffs.burn — its light; fx_pall takes it so the pall beyond the
// front continues the color of the wall): r — along the ground from the epicenter, up — share of the dust height 0..1 (the
// bottom is darker: ground and debris), glow — heat of fires 0..1, fireR — edge of the ignition zone (soft, from 0.92 to 1.04);
// r and fireR — in the same units (blocks).
float pallBurn(float r, float up, float glow, float fireR) {
    float e = max(fireR, 1e-3);
    return (1.0 - smoothstep(0.92 * e, 1.04 * e, r)) * (0.95 - 0.45 * up) * glow;
}

// ---------------------------------------------------------------- light in the pall (client/nuclear/PallLight)
// Two Eddington streams in a flat layer after delta-Eddington: radiance I(τ, μ) = I0 + μ·I1, μ — cosine to the vertical (up —
// plus), τ — thickness from the top of the layer down. Laws and derivation — PallLight (PallGlslTest checks the numbers).

// Light of the pall is computed in linear shares (transfer is linear in radiance); the Minecraft frame — screen values (sRGB
// without conversion): screen v is v^2.2 of linear (PallLight.GAMMA). A pure power: the conversion of a product is the product of conversions
float pallLinear(float v) {
    return pow(max(v, 0.0), 2.2);
}

vec3 pallLinear3(vec3 v) {
    return vec3(pallLinear(v.r), pallLinear(v.g), pallLinear(v.b));
}

float pallDisplay(float v) {
    return pow(max(v, 0.0), 0.4545455);
}

// Light that on screen adds x to a white wall over the sky light base (also on screen: the glare of the wall of dust, the glow
// of fires — CloudPuffs adds them on screen), in linear: (base + x)^γ − base^γ (PallLight.lift)
float pallLift(float base, float x) {
    return pow(max(base + x, 0.0), 2.2) - pow(max(base, 0.0), 2.2);
}

vec3 pallLift3(float base, vec3 x) {
    return vec3(pallLift(base, x.r), pallLift(base, x.g), pallLift(base, x.b));
}

// Light of fire (fires, storm, tornado), which on screen adds x to a white wall over the sky light base, in linear — not
// brighter than the flame itself: its color on screen is flame, the radiance — pallLinear(flame). A scattering substance is
// not brighter than its source (per channel): a white wall, all in fire, has the color of the fire, not brighter. A glare that
// on screen already reaches the color of the flame (a wall of dust above fires at night — CloudPuffs: on screen it is the color
// of the fire itself), converted through the color of the dust (/ DUST, pallLift), gave light several times brighter than the
// flame (PallLight.flame)
vec3 pallFlame(float base, vec3 x, vec3 flame) {
    return min(pallLift3(base, x), vec3(pallLinear(flame.r), pallLinear(flame.g), pallLinear(flame.b)));
}

// Attenuation of scattered light with thickness: k² = 3(1 − ω)(1 − ωg) (in thick dust — e^(−kτ), not e^(−τ))
float pallK(float w, float g) {
    return sqrt(max(3.0 * (1.0 - w) * (1.0 - w * g), 1e-6));
}

// Cosine of the beam in the particular solution (pallDiffuse): for a beam that fades the same way as scattered light (kμ = 1),
// the denominator of the particular solution k²μ² − 1 vanishes. In the band |k²μ² − 1| < 0.05 the beam is shifted to its
// edge — all of it: in the particular solution, in its transmission and on the ground. The solution remains a solution of the
// equation, the cosine — within 2.5%; a clamped denominator at the former μ was not a solution: for smoke (k > 1) near noon
// the light in the layer came out several times wrong
float pallBeamMu(float k, float mu) {
    float d = k * k * mu * mu - 1.0;
    if (abs(d) >= 0.05) return mu;
    if (d < 0.0) return 0.9746794 / k;
    return 1.0246951 / k;
}

// Scattered light at depth tau of the layer of thickness tc (albedo w, asymmetry g, ground under it with albedo ground): I0 + c·I1.
// c = g·μ of the eye — what the point scatters to the eye (without ω); c = ±2/3 — flux up or down, divided by π. Sources:
// two beams from above (cosine to the vertical mu, strength f — what the white wall reflects to the beam), sky above top and
// light from below bottom (radiance up, over what the ground reflects). The solution is in decaying exponentials: e^(−kτ) from
// the top and e^(−k(tc − τ)) from the bottom — without overflow in a thick layer; k ≥ 10⁻³ — so that the matrix is not degenerate
// even for a non-absorbing fog (k = 0).
// A beam that fades the same way as scattered light (kμ = 1) — with a shifted cosine (pallBeamMu): it does not divide by 0.
float pallDiffuse(float w, float g, float tau, float tc, float ground, float mu1, float f1, float mu2, float f2, float top, float bottom, float c) {
    float q = 1.0 - w * g;
    float k = sqrt(max(3.0 * (1.0 - w) * q, 1e-6));
    float p = 0.6666667 * k / q;
    float e = exp(-k * tc);
    float m1 = pallBeamMu(k, mu1), m2 = pallBeamMu(k, mu2);
    float a1 = 0.75 * w * m1 * m1 * f1 * (1.0 + g * (1.0 - w)) / (k * k * m1 * m1 - 1.0);
    float b1 = 0.75 * w * m1 * f1 - 3.0 * m1 * (1.0 - w) * a1;
    float a2 = 0.75 * w * m2 * m2 * f2 * (1.0 + g * (1.0 - w)) / (k * k * m2 * m2 - 1.0);
    float b2 = 0.75 * w * m2 * f2 - 3.0 * m2 * (1.0 - w) * a2;
    float e1 = exp(-tc / m1), e2 = exp(-tc / m2);
    float r1 = top - a1 - a2 + 0.6666667 * (b1 + b2);
    float r2 = bottom + ground * (m1 * f1 * e1 + m2 * f2 * e2) - ((1.0 - ground) * a1 + 0.6666667 * (1.0 + ground) * b1) * e1
            - ((1.0 - ground) * a2 + 0.6666667 * (1.0 + ground) * b2) * e2;
    float m11 = 1.0 + p, m12 = e * (1.0 - p), m21 = e * (1.0 - ground - (1.0 + ground) * p), m22 = 1.0 - ground + (1.0 + ground) * p;
    float det = m11 * m22 - m12 * m21;
    float c1 = (r1 * m22 - m12 * r2) / det, c2 = (m11 * r2 - m21 * r1) / det;
    float x = exp(-k * tau), y = exp(-k * (tc - tau));
    float t1 = exp(-tau / m1), t2 = exp(-tau / m2);
    float i0 = c1 * x + c2 * y + a1 * t1 + a2 * t2;
    float i1 = k / q * (c2 * y - c1 * x) + b1 * t1 + b2 * t2;
    return i0 + c * i1;
}

// ---------------------------------------------------------------- head of the dust layer (client/nuclear/BlastWall)
// The head is the youngest, lowest and densest part of the same dust layer as the pall (laws — nuclear/model/DustModel): a
// wedge behind the blast wave front, over its path during the positive phase, as a ring around the epicenter (the dust at
// the front has not yet been carried by the wind — PallField.share). At the leading edge the layer has zero height; the lifted
// part grows behind as the dynamic pressure impulse, the top — as u^(5/6) (Kuhl); behind the wedge the place passes to the
// table of the pall. Laws — copies of BlastWall (PallGlslTest checks). The wall slot V — mat4 by columns (BlastWall.slot):
//   [0] = (leading edge of the dust from the epicenter, length of the wedge, its top at the end — blocks; attenuation scale,
//          1/block; 0 — no head)
//   [1] = (angle of the rim, rad; over water — spray: its lag behind the edge of the dust, blocks; share of the top;
//          attenuation, 1/block)
//   [2] = (puffs — two phases of noise along the ring: the circumference of the head in tiles for each; weight of the first;
//          noise tile, blocks)
//   [3] = (phase numbers — each has its own puffs; attenuation of dust at the ground at the end of the wave phase at the rim
//          and at the end of the wedge — shares of the scale)
// The Wilson cloud of the slot — vec4 (same place): radius of the middle of the shell from the detonation point, its half-thickness
// (blocks), attenuation in the middle (1/block; 0 — no cloud), height of the detonation point above the ground under the epicenter.

// Lifted to the share s of the positive phase (DustModel.lifted): dynamic pressure impulse, ∫(1 − x)²e^(−2x)dx over all
float pallLifted(float s) {
    float x = clamp(s, 0.0, 1.0), y = 1.0 - x;
    return (0.25 - exp(-2.0 * x) * (y * y / 2.0 - y / 2.0 + 0.25)) / 0.2161662;
}

// Top of the wedge by the share u of its length behind the edge, share of the top at the end (BlastWall.top): u^(5/6), behind the wedge — all
float pallWallTop(float u) {
    return pow(clamp(u, 0.0, 1.0), 0.8333333);
}

// Share of the path of dust with the wind behind the front for the share u of the positive phase (BlastWall.carriedShare): u·e^(1 − u);
// behind the phase the wind is rarefaction (reverse), and the share falls to zero
float pallWallCarried(float u) {
    float x = max(u, 0.0);
    return x * exp(1.0 - x);
}

// Place of the dust of the head in its own coordinates (BlastWall.origin): point (rel — from the epicenter along the ground, z — above
// the relief, blocks) minus the path with the wind behind the front — carried (blocks) for the share u of the phase; at the ground the
// air stands still, higher it goes by the 1/7 law of the turbulent boundary layer (z against the top at the end of the wedge top), up —
// 0.02–0.08 of the path (Kuhl §3.4: vertical speed in the layer — 0.02 u∞ at the bottom and 0.04–0.12 at the top). (x, height, z), blocks
vec3 pallWallOrigin(vec2 rel, float z, float u, float top, float carried) {
    float zr = clamp(z / max(top, 0.001), 0.0, 1.0);
    float x = carried * pallWallCarried(u) * pow(zr, 1.0 / 7.0);
    vec2 out1 = rel / max(length(rel), 1e-3);
    return vec3(rel.x - out1.x * x, z - x * (0.02 + 0.06 * zr), rel.y - out1.y * x);
}

// Density at the ground by the share u of the wedge against its density at its end (BlastWall.shape): the lifted part in the layer of the top pallWallTop
float pallWallShape(float u) {
    if (u <= 0.0) return 0.0;
    float x = min(u, 1.0);
    return pallLifted(x) / pow(max(x, 0.0001), 0.8333333);
}

// Where the head behind the wedge passes to the pall, in lengths of the wedge from the leading edge of the dust (BlastWall.HANDOFF_FROM,
// HANDOFF_TO): from its end to one and a half lengths
const float PALL_WALL_HANDOFF_FROM = 1.0;
const float PALL_WALL_HANDOFF_TO = 1.5;

// Share of the head behind the wedge (BlastWall.handoff)
float pallWallFade(float u) {
    return 1.0 - smoothstep(PALL_WALL_HANDOFF_FROM, PALL_WALL_HANDOFF_TO, u);
}

// Point in the frame of the head (BlastWall.sigma): s — beyond the leading edge of the dust, z — above the relief (blocks), len, top —
// length and top of the wedge of the slot, lag — lag of the spray of the slot. Outside — 0, inside — 1
float pallWallInside(float s, float z, float len, float top, float lag) {
    if (s < -1.0) return 0.0;
    if (s > PALL_WALL_HANDOFF_TO * len + lag) return 0.0;
    if (z > top) return 0.0;
    if (z < -0.5 * top) return 0.0;
    return 1.0;
}

// Attenuation multiplier of the head at s blocks beyond the edge of the dust with the lag of the spray (BlastWall.density): dust — from
// the share edge at the edge to end at the end of the wedge of length len (× scale; dust lifted closer to the epicenter — of its own
// density), spray — its own; wet — share of water under the point
float pallWallDensity(float s, float len, float scale, float edge, float end, float spray, float wet) {
    float dust = scale * (edge + (end - edge) * clamp(s / max(len, 0.001), 0.0, 1.0));
    return dust + (spray - dust) * wet;
}

// Share of the density of the head without noise (BlastWall.mean): s — beyond the leading edge of the dust (blocks; ahead — less than zero),
// z — above the relief (blocks), len — length of the wedge, top — its top at the end. By height — the profile of the pall up to the top
// of the wedge here; under the relief of the map — up to half the top (the map is coarser than the world: in the lowlands under it the head does not hang)
float pallWallMean(float s, float z, float len, float top) {
    float u = s / max(len, 0.001);
    if (u <= 0.0) return 0.0;
    float h = top * pallWallTop(u), k = pallWallShape(u) * pallWallFade(u);
    if (z < 0.0) return k * smoothstep(-0.5 * top, -0.25 * top, z);
    return k * pallProfile(z / max(h, 0.001));
}

// Head of the wall at a point
struct WallAt {
    // with the correction for water under the point: length of the wedge, its top at the end, shift back (blocks), attenuation here
    // (pallWallDensity, 1/block); P.w = 0 — the point is outside the head
    vec4 P;
    // beyond the leading edge of the dust and above the relief, blocks
    float s;
    float z;
    // share of water under the point: over water the head is spray, lower and behind
    float wet;
    // the point from the axis of the ring (the epicenter: the dust at the front has not yet been carried by the wind — PallField.share), blocks
    vec2 rel;
};

// Head of the wall of slot S, V at point p (from the eye): floorUp — top of the relief under the point above the ground under the
// epicenter (blocks), ground, G, W — relief and water maps of the slot and their numbers, water of the slot (the map is read for
// water only if it is under the pall). Attenuation of dust — from the edge toward the end of the wedge (dust lifted closer to the
// epicenter — of its own density); spray — its own
WallAt pallWallAt(sampler2D ground, mat4 S, mat4 V, vec4 G, vec4 W, vec3 p, float floorUp) {
    WallAt h;
    h.rel = p.xz - S[0].xy;
    h.s = V[0].x - length(h.rel);
    h.z = p.y - S[0].z - floorUp;
    h.wet = 0.0;
    h.P = vec4(V[0].y, V[0].z, 0.0, 0.0);
    if (V[0].w <= 0.0 || pallWallInside(h.s, h.z, V[0].y, V[0].z, V[1].y) <= 0.0) return h;
    if (W.w > 0.5) h.wet = pallWater(ground, G, p.xz - S[0].xy);
    float lag = V[1].y * h.wet;
    h.P = vec4(V[0].y, V[0].z * mix(1.0, V[1].z, h.wet), lag, pallWallDensity(h.s - lag, V[0].y, V[0].w, V[3].z, V[3].w, V[1].w, h.wet));
    return h;
}

// Segments of the ray dir from the eye on [ta, tb] where the head of the slot may be: the ring from the leading edge of the dust to one
// and a half lengths of the wedge beyond it (with the spray), by height — from the bottom of the relief map to its top with the head.
// (x, y) and (z, w) — entry and exit; exit ≤ entry — misses
vec4 pallWallSpans(mat4 S, mat4 V, vec4 G, vec3 dir, float ta, float tb) {
    vec4 none = vec4(1.0, 0.0, 1.0, 0.0);
    if (V[0].w <= 0.0 || S[1].w < 0.5) return none;
    vec2 c = S[0].xy;
    float top = V[0].z, outer = V[0].x + 1.0, inner = max(V[0].x - PALL_WALL_HANDOFF_TO * V[0].y - V[1].y, 0.0);
    // by height: the relief map (without it — the ground under the epicenter) with the head
    float lo = S[0].z + min(G.y, 0.0) - 0.5 * top, hi = S[0].z + max(G.z, 0.0) + top;
    if (abs(dir.y) > 1e-6) {
        float y0 = lo / dir.y, y1 = hi / dir.y;
        ta = max(ta, min(y0, y1));
        tb = min(tb, max(y0, y1));
    } else if (lo > 0.0 || hi < 0.0) {
        return none;
    }
    float a = dot(dir.xz, dir.xz), b = dot(dir.xz, c);
    if (a < 1e-10 || tb <= ta) return none;
    float cc = dot(c, c), disc = b * b - a * (cc - outer * outer);
    if (disc <= 0.0) return none;
    float q = sqrt(disc);
    vec2 out1 = vec2(b - q, b + q) / a;
    vec4 spans = vec4(max(out1.x, ta), min(out1.y, tb), 1.0, 0.0);
    float din = b * b - a * (cc - inner * inner);
    if (inner > 0.0 && din > 0.0) {
        float qi = sqrt(din);
        vec2 in1 = vec2(b - qi, b + qi) / a;
        spans = vec4(max(out1.x, ta), min(in1.x, tb), max(in1.y, ta), min(out1.y, tb));
    }
    return spans;
}

// Thickness of the head of the slot without noise on the ray segment [ta, tb] (dir from the eye), steps steps on each of its segments
// (pallWallSpans): what the head covers in front of the square of the layer (fx.vsh). The floor — the relief of the map under each point
float pallWallTau(sampler2D ground, mat4 S, mat4 V, vec4 G, vec4 W, vec3 dir, float ta, float tb, int steps) {
    vec4 spans = pallWallSpans(S, V, G, dir, ta, tb);
    float tau = 0.0;
    for (int k = 0; k < 2; k++) {
        vec2 sp = k == 0 ? spans.xy : spans.zw;
        if (sp.y <= sp.x) continue;
        float ds = (sp.y - sp.x) / float(steps);
        for (int i = 0; i < steps; i++) {
            vec3 q = dir * (sp.x + (float(i) + 0.5) * ds);
            float floorUp = pallRelief(ground, S, G, q.xz);
            WallAt h = pallWallAt(ground, S, V, G, W, q, floorUp);
            if (h.P.w > 0.0) tau += h.P.w * pallWallMean(h.s - h.P.z, h.z, h.P.x, h.P.y) * ds;
        }
    }
    return tau;
}

// Wilson cloud of the slot on the ray dir from the eye up to end: up to two places where the ray passes the shell (x, z — where, blocks),
// and the thickness there (y, w; 0 — misses). The shell is Gaussian across: the thickness on the pass — √π·half-thickness·attenuation
// times the cosine of the ray to the shell; along the tangent — no more than that of a shell of radius r (cosine not less than 0.35), and
// farther from the middle — it fades. Below the ground under the epicenter there is no cloud
vec4 pallWilson(mat4 S, vec4 L, vec3 dir, float end) {
    vec4 none = vec4(0.0);
    if (L.z <= 0.0 || S[1].w < 0.5) return none;
    vec3 b = vec3(S[0].x, S[0].z + L.w, S[0].y);
    float along = dot(dir, b), q2 = max(dot(b, b) - along * along, 0.0), r = L.x, w = max(L.y, 1e-3);
    float base = 1.7724539 * w * L.z;
    vec4 hit = none;
    if (q2 < r * r) {
        float h = sqrt(r * r - q2);
        float tau = base / max(h / r, 0.35);
        hit = vec4(along - h, tau, along + h, tau);
    } else {
        float q = sqrt(q2);
        float x = (q - r) / w;
        hit = vec4(along, 2.0 * base / 0.35 * exp(-x * x), 1.0, 0.0);
    }
    // behind the eye, behind the world and under the ground — no
    if (hit.x < 0.0 || hit.x > end || dir.y * hit.x < S[0].z) hit.y = 0.0;
    if (hit.z < 0.0 || hit.z > end || dir.y * hit.z < S[0].z) hit.w = 0.0;
    return hit;
}

// ---------------------------------------------------------------- fire storm (client/nuclear/FireStorm)
// Laws — copies of FireStorm (PallGlslTest checks). The fire slot F — mat4 by columns; each detonation slot S has its own:
//   [0] = (half-side of the fire map, blocks (0 — there is no storm); cell of the map, blocks; top of the columns above the
//          ground under the epicenter, blocks; height at which the drift of a column is twice the direct one, blocks)
//   [1] = (drift of the column by wind x, z per block of rise — the wind against the rise of smoke; rise of smoke, blocks/s;
//          glow from below — strength with night and confidence)
//   [2] = (flame on surfaces — strength with day and confidence; attenuation in the column per unit of smoke supply, 1/block;
//          seconds after detonation; 0)
//   [3] = (light of fires on screen rgb; Minecraft clouds from the eye by y — flame on surfaces only below them)
// Fire map: R — flame, G — smoke supply into the columns, B — G × share of the column's rise; reduced copies — clusters of
// sources (light and smoke at height h — from the level where the map cell is the width of the column, or the glow there).

// drift of the column at height h above the ground, blocks per unit of the ratio of wind to rise (FireStorm.bend)
float fireBend(float h, float hb) {
    float x = max(h, 0.0);
    return x * (1.0 + x / hb);
}

// half-width of the column at height h, blocks (FireStorm.width): at the ground — already cells, higher it grows
float fireWidth(float h, float cell) {
    return 0.3 * cell + 0.22 * max(h, 0.0);
}

// level of the fire map where the cell is the width w (FireStorm.lod)
float fireLod(float w, float cell) {
    return log2(max(2.0 * w / cell, 1.0));
}

// map level for the glow at height h: light from an area about 2h wide (FireStorm.glowLod)
float fireGlowLod(float h, float cell) {
    return log2(max(2.0 * max(h, 6.0) / cell, 1.0));
}

// the column by height: from the lowlands to the top, where it spreads into haze (FireStorm.column)
float fireColumn(float h, float top) {
    return smoothstep(-40.0, 0.0, h) * (1.0 - smoothstep(0.65 * top, top, h));
}

// place on the fire map of the slot under point xz (from the eye)
vec2 fireUv(mat4 S, mat4 F, vec2 xz) {
    return (xz - S[0].xy) / (2.0 * F[0].x) + 0.5;
}
