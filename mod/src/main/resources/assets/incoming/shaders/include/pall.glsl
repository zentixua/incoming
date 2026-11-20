// Pall after the blast wave (client/nuclear/PallField — laws, client/fx/layer/PallPass — passes): the field of one
// detonation from a radius table. Shared by the volume (fx_pall) and the squares of the effects layer (fx.vsh: what the pall
// covers in front of a particle). Numbers and laws are copies of PallField and VolumeNoise.shape (PallGlslTest computes
// these functions and checks them against Java).
//
// Detonation slot — mat4 by columns:
//   [0] = (epicenter x, z from the eye; ground under the epicenter from the eye by y; how far the table goes, blocks)
//   [1] = (drift by wind x, z of what was lifted at the moment of detonation, blocks — for dust and spray its share by their
//          age (haze row, a), for the basic wave all of it; length of the haze trail, blocks; 1 — slot exists, 2 — and there
//          is hard ground under its pall: channel b of the relief map is read)
//   [2] = (wind x, z — unit; v of the dust row in the table; v of the haze row)
//   [3] = (attenuation of dense dust — a measure of puff density, 1/block; noise tile, blocks; seconds after detonation;
//          v of the spray row)
// Table: across the width — radius 0..reach; rows — dust (attenuation at ground, height, smoke share; 0), spray over water
// (attenuation over water, its height — own layer; dust over hard ground against soil, PallField.paved; 0) and haze (attenuation, height, fire light from below; share of dust
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
// Dust, smoke and haze — g = 0.6, water droplets — 0.85, soot — 0.5. Dust is what the wave lofts: the reference dry loamy soil under
// grass and forest (DUST) or desert sand (SAND), by the slot map's sand share of the soil where it was lofted (pallSoil, pallDustW).
const vec3 PALL_DUST_W = vec3(0.8687934, 0.8099112, 0.7286264);
const vec3 PALL_SAND_W = vec3(0.9345197, 0.9149708, 0.8875681);
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
    // albedo ω' of the dust here (pallDustW: soil where it was lofted, soot)
    vec3 dustW;
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

// soil under the point rel (from the epicenter along the ground) by the slot's map: x — share of hard ground (PallGround.hard: rock,
// roofs, roads, snow — no soil on top, only debris dust is lofted there), y — share of sand (PallGround.sand: desert dust, not the
// reference loamy soil); both fractions of the ground; no map or neither under the pall (S[1].w < 1.5) — reference soil
vec2 pallSoil(sampler2D ground, mat4 S, vec4 G, vec2 rel) {
    if (G.x <= 0.0 || S[1].w < 1.5) return vec2(0.0);
    return clamp(textureLod(ground, rel / (2.0 * G.x) + 0.5, 0.0).ba, 0.0, 1.0);
}

// Albedo ω' of lofted dust: sand's share of the lofted mass (PallField.sandShare) — sand and hard ground are fractions of the ground as the
// map holds them; hard ground lofts only debris dust, paved of the soil's mass — darkened by soot from fires (smoke share)
vec3 pallDustW(float sand, float hard, float paved, float smoke) {
    float share = sand / max(1.0 + hard * (paved - 1.0), 1e-6);
    return mix(mix(PALL_DUST_W, PALL_SAND_W, clamp(share, 0.0, 1.0)), PALL_SMOKE_W, smoke);
}

// Field of slot S at point p (from the eye), without noise (the average over noise is that); ground, G — relief and water
// maps of the slot; W — water of the slot; floorUp — floor of the layer under the point (top of the relief by the map,
// blocks above the ground under the epicenter; 0 — ground under the epicenter): slab is computed from it, and above a hill
// the layer rises from it — the dust is lifted by the wind from its slope (it drains into the valley: there the layer is
// from the ground under the epicenter, pallProfile; PallField.sigma is the same). Substance — by the water where it was
// lifted from (PallField: dust blown onto water stays dust): dust and spray — at the point, minus the drift (share by the
// age of the dust — by the node of the point itself, PallField.source), where the table is also read; haze — at its source on
// the axis of the trail (there are no fire over water), only if it is here; the basic wave — water lifted at the epicenter,
// over whatever it rolls on (W.z = 0 — there is none). Without water under the pall (W.w = 0) the map is not read. Dust over
// hard ground where it was lifted — only debris dust (pallHard, spray row b: PallField.soil).
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
    vec2 soil = sd > 0.0 ? pallSoil(ground, S, G, lift) : vec2(0.0);
    float hard = soil.x;
    vec4 spray = vec4(0.0);
    if (W.w > 0.5 || hard > 0.0) spray = texture(table, vec2(min(ud, 1.0), S[3].w));
    sd *= 1.0 + hard * (spray.b - 1.0);
    if (W.w > 0.5) {
        // spray — its own table row (its own height): only the detonation under whose pall there is water reads it
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
    a.dustW = pallDustW(soil.y, hard, spray.b, dust.b);
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
// the pixel: the ray has passed everything drawn (further than from along the ground or up/down from the eye — the draw
// distance) and hit nothing, so we search only farther. Steps grow with distance (far away a map cell is smaller on screen),
// the start is shifted by a jitter of the step, the last is at end; between a step above the relief and a step below it — by
// a secant.
// The map is coarser than the world: if at the start it is above the ray (the ray passed over the real relief), there it
// drops under the ray and, four cells farther, again as is.
float pallFloor(sampler2D ground, mat4 S, vec4 G, vec3 dir, float from, float end, int steps, float jitter) {
    if (G.x <= 0.0 || S[1].w < 0.5) return 1e9;
    // frame of the map: along the ground — the map, above — the top of the relief, below — without edge (a ray downward ends under the relief)
    vec2 span = pallBox(dir, vec3(S[0].x - G.x, -1e6, S[0].y - G.x), vec3(S[0].x + G.x, S[0].z + G.z, S[0].y + G.x));
    // drawn chunks fill a cylinder around the eye: within the render distance across and the same distance up and down
    // (Sodium culls sections vertically too), so a ray has passed them once it is that far out on either axis — straight
    // down from high above the drawn ground it is under the relief at once
    float fade = 4.0 * G.w, near = from / max(max(length(dir.xz), abs(dir.y)), 1e-3);
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

// Block light in the window (OpenAir channel g: Minecraft's block light — torches, lamps, lava, fire — and, with LambDynamicLights,
// light carried by entities as the larger of the two, MovingLight) at eye-relative p, as a light level 0..15, linear between
// voxel centres (texture filtering; the window wraps in the texture, as GL_REPEAT); 0 outside the window or without it. For the
// air the dust is in: next to a wall it fades toward the wall's 0
float pallBlockLevel(sampler3D open, vec4 at, vec3 wrap, vec3 p) {
    if (at.w < 0.5) return 0.0;
    vec3 q = p - at.xyz;
    if (any(lessThan(q, vec3(0.5))) || any(greaterThan(q, PALL_OPEN_SIZE - 0.5))) return 0.0;
    return 15.0 * texture(open, (q + wrap) / PALL_OPEN_SIZE).g;
}

// Block light level a world surface was drawn with, at p, a point in the air just in front of it: between the eight voxel centres
// around p, only those with light. An opaque block holds none (the light engine keeps no light inside it) yet is half of the
// voxels around a point on its face: with them a lit floor would read half its light. Where lit air meets dark air the level
// reads at most one level high (the light engine steps by one level per block)
float pallSurfaceLevel(sampler3D open, vec4 at, vec3 wrap, vec3 p) {
    if (at.w < 0.5) return 0.0;
    vec3 q = p - at.xyz - 0.5;
    if (any(lessThan(q, vec3(0.0))) || any(greaterThanEqual(q, PALL_OPEN_SIZE - 1.0))) return 0.0;
    vec3 b = floor(q), f = q - b;
    float sum = 0.0, weight = 0.0;
    for (int c = 0; c < 8; c++) {
        ivec3 o = ivec3(c & 1, (c >> 1) & 1, c >> 2);
        float v = texelFetch(open, (ivec3(b) + o + ivec3(wrap)) % ivec3(PALL_OPEN_SIZE), 0).g;
        vec3 w3 = mix(1.0 - f, f, vec3(o));
        float w = w3.x * w3.y * w3.z * step(0.5 / 255.0, v);
        sum += w * v;
        weight += w;
    }
    return weight > 0.0 ? 15.0 * sum / weight : 0.0;
}

// Minecraft's lightmap (LightTexture: block light level along x, skylight along y; this frame's time of day, brightness setting,
// night vision, darkness) at levels (block, sky), as it lights a white block on screen; bilinear between levels
vec3 pallLightmap(sampler2D lightmap, float block, float sky) {
    vec2 l = clamp(vec2(block, sky), 0.0, 15.0), b = min(floor(l), 14.0), f = l - b;
    ivec2 i = ivec2(b);
    vec3 a = mix(texelFetch(lightmap, i, 0).rgb, texelFetch(lightmap, i + ivec2(1, 0), 0).rgb, f.x);
    vec3 c = mix(texelFetch(lightmap, i + ivec2(0, 1), 0).rgb, texelFetch(lightmap, i + ivec2(1, 1), 0).rgb, f.x);
    return mix(a, c, f.y);
}

// Flame colour of torches, lanterns and fire in linear light, luminance 1: a candle-like flame near 1900 K, red : green : blue
// about 1 : 0.6 : 0.4 (the warm block-light colour shaderpacks give their light in fog: Complementary 1 : 0.59 : 0.43, Bliss
// 1 : 0.75 : 0.65). Vanilla's lightmap is no source of hue here: its block light is white at level 15 (LightTexture 1.21.1:
// (b, b·((0.6b + 0.4)·0.6 + 0.4), b·(0.6b² + 0.4)) with b = 1), so the dust around a torch turned grey instead of orange
const vec3 PALL_LAMP_HUE = vec3(1.0, 0.6, 0.4) / 0.6708;

// Light of blocks at level `level` in pall units (linear; a white wall under noon daylight = 1, as the lightmap's open sky): what
// Minecraft adds to a white block for it, over the lightmap's dark floor (level 0 everywhere: night-sky ambient and the
// brightness setting's lift, which the pall's sky light already holds). Its luminance is the lamp's strength (time of day,
// brightness setting, night vision and darkness all reach it), its colour the flame's (PALL_LAMP_HUE)
vec3 pallLampLight(sampler2D lightmap, float level) {
    if (level <= 0.0) return vec3(0.0);
    vec3 lit = pallLightmap(lightmap, level, 0.0), dark = pallLightmap(lightmap, 0.0, 0.0);
    float added = dot(max(pow(lit, vec3(2.2)) - pow(dark, vec3(2.2)), 0.0), vec3(0.2126, 0.7152, 0.0722));
    return PALL_LAMP_HUE * added;
}

// The pall's shadow on a world pixel lit by Minecraft from sky (level sky) and from blocks (level block). Minecraft adds the two
// in its lightmap (LightTexture: block light's colour plus the sky's, clamped); the pall shades only the sky's part — it stands
// between the sky and the ground, not between a torch and the floor beside it. keptSky: the pixel's multiplier with sky light
// alone (on screen); returned: with block light kept, (s·sky + block) / lightmap(block, sky) in linear light, where s is keptSky
// in linear. No block light — keptSky itself. Under a shaderpack the world is lit by the pack's own model, not the lightmap: the
// same split of a pixel's light by Minecraft's levels is taken as the estimate (Iris packs read the same block and sky levels)
float pallKeepLamps(float keptSky, sampler2D lightmap, float block, float sky) {
    if (block <= 0.0 || keptSky >= 1.0) return keptSky;
    const vec3 Y = vec3(0.2126, 0.7152, 0.0722);
    float floor0 = dot(pow(pallLightmap(lightmap, 0.0, 0.0), vec3(2.2)), Y);
    float fromSky = dot(pow(pallLightmap(lightmap, 0.0, sky), vec3(2.2)), Y) - floor0;
    float fromBlocks = dot(pow(pallLightmap(lightmap, block, 0.0), vec3(2.2)), Y) - floor0;
    float both = dot(pow(pallLightmap(lightmap, block, sky), vec3(2.2)), Y) - floor0;
    if (fromBlocks <= 0.0 || both <= 1e-5) return keptSky;
    float kept = min((pow(keptSky, 2.2) * fromSky + fromBlocks) / both, 1.0);
    return max(pow(kept, 1.0 / 2.2), keptSky);
}

// Distance to the world behind the pixel (SceneDepth), fit for the pall: sky, and also NaN, infinity and “behind the eye”
// (foreign LOD depth, far plane) — 1e9, there is no world behind the pixel. Otherwise the ray would go through kilometers
// of dust, or a NaN would get into the accumulated pall — a black frame.
float pallScene(float d) {
    if (d < 0.0) return 1e9;
    if (d < 1e8) return d;
    return 1e9;
}

// Nearer than this, a distance behind the pixel (SceneDepth) is the first-person hand, not the world. Vanilla draws the hand
// after the layer, over it; Iris draws it inside the world pass into world depth with its depth range × 0.125
// (HandRenderer.DEPTH), so at the layer its depth reads as 2n/(1 + n/f − 0.125·(1 − n/f)) ≤ 0.1143 m with the vanilla near
// plane n = 0.05 (any far plane f; PallHandTest). The world comes this close only at the screen edge of a wide view under a
// ceiling or wall a hand's breadth away, where the dust in front is centimetres thick and shows nothing either. The pall
// leaves the hand as vanilla does: no dust, no shadow, no history from it
const float PALL_HAND = 0.12;

bool pallHand(float d) {
    return d < PALL_HAND;
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
// bottom is darker: ground and debris), glow — flame of the fires 0..1 (PallField.fireLight: grows from the flash over
// twenty minutes), fireR — edge of the ignition zone (soft, from 0.92 to 1.04);
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

// display (pallShown): above-white light adds this share of its excess as white to all channels (film/eye overexposure);
// Rec. 709 luminance
const float PALL_CROSS = 0.15;
const vec3 PALL_LUMA = vec3(0.2126, 0.7152, 0.0722);

// Film-like shoulder: display values to 0.6 unchanged, brighter smoothly approach 1 (same slope at 0.6). Frame clips
// above 1: at night fire-lit billows would merge into flat orange. Use brightest channel, scale others equally to
// preserve hue (independent channels turned white mist pink in ball light)
vec3 shoulder(vec3 c) {
    float m = max(max(c.r, c.g), c.b);
    if (m <= 0.6) return c;
    return c * ((0.6 + 0.4 * (1.0 - exp(-(m - 0.6) / 0.4))) / m);
}

// Display: pall material color is light light / opacity cover (source function for uniform material), converted to
// display and premultiplied by opacity; blends with world like other translucent frame content. Above white
// (fireball, incandescence, night glow): material light adds over background — displayed material no brighter than
// white; overexposure becomes pixel coverage (cover·L, capped at full pixel), colored by its light, PALL_CROSS toward white.
// Transmittance remains real: same background visible, overlaid light saturates toward white (frame RGBA16F,
// layer quads behind it take light from frame, fx.fsh: before). Otherwise a distant ball left with cover by air haze
// (10 km clear — 0.14) would be a pale sky patch however bright it shines. Copy — NukeExposure.pixel
vec3 pallShown(vec3 light, float cover) {
    float shownCover = cover;
    vec3 medium = light / max(cover, 1e-4);
    float lum = dot(medium, PALL_LUMA);
    if (lum > 1.0 && cover > 1e-4) {
        shownCover = min(cover * lum, 1.0);
        medium *= cover / shownCover;
    }
    medium += vec3(PALL_CROSS * max(dot(medium, PALL_LUMA) - 1.0, 0.0));
    return shoulder(vec3(pallDisplay(medium.r), pallDisplay(medium.g), pallDisplay(medium.b))) * shownCover;
}

// Inverse of shoulder below its ceiling (brightest channel under 1)
vec3 unshoulder(vec3 c) {
    float m = max(max(c.r, c.g), c.b);
    if (m <= 0.6) return c;
    return c * ((0.6 - 0.4 * log(max(1.0 - (m - 0.6) / 0.4, 1e-6))) / m);
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
//   [1] = (path of the dust with the wind behind the front, blocks — BlastWall.carried; over water — spray: its lag behind the edge
//          of the dust, blocks; share of the top; attenuation, 1/block)
//   [2] = (share of the positive phase at the end of the wedge; saltation e-folds over the wedge length; share of dust rising at
//          once behind the front — pallWallDevelop; rise of the heated layer after the phase — pallWallHeated: lift)
//   [3] = (dust over hard ground against soil at the rim and at the end of the wedge — BlastWall.pavedShare; attenuation of dust
//          at the ground at the end of the wave phase at the rim and at the end of the wedge — shares of the scale)
// The Wilson cloud of the slot — vec4 (same place): radius of the middle of the shell from the detonation point, its half-thickness
// (blocks), attenuation in the middle (1/block; 0 — no cloud), height of the detonation point above the ground under the epicenter.

// presentation (BlastWall.LOFT_GAIN): the lofting burst at the front runs twice as fast — dust thrown up twice as fast, the layer at its
// phase-end top and mass after half the phase; within sources (GRABLE: 6–25 m in the first tenths of a second)
const float PALL_WALL_LOFT_GAIN = 2.0;

// Layer top by phase share x, share of its top at the phase end (DustModel.loftTop): rises at the ground's friction velocity — fastest right
// behind the front, standing by the phase end, (x·e^(1 − x))^0.88
float pallLoftTop(float x) {
    float c = clamp(x, 0.0, 1.0);
    return pow(c * exp(1.0 - c), 0.88);
}

// Lofted mass by phase share x, share of the phase total (DustModel.loftMass): 1 − (1 − x)^8, most of it right behind the front
float pallLoftMass(float x) {
    return 1.0 - pow(1.0 - clamp(x, 0.0, 1.0), 8.0);
}

// Top of the wedge by the share u of its length behind the edge, share of the top at the end (BlastWall.top): the lofting burst at the dust
// age there — u of the share ph of the phase at the wedge end; none ahead of the edge, all behind the wedge
float pallWallTop(float u, float ph) {
    if (u <= 0.0) return 0.0;
    float p = PALL_WALL_LOFT_GAIN * max(ph, 0.001);
    return pallLoftTop(min(u, 1.0) * p) / pallLoftTop(p);
}

// Share of the wedge where the layer top reaches the share zr of the top at the end (BlastWall.face): inverse of pallWallTop, x·e^(1 − x) = y
// by two Newton steps from a guess
float pallWallFace(float zr, float ph) {
    float p = PALL_WALL_LOFT_GAIN * max(ph, 0.001), y = min(pow(clamp(zr, 0.0, 1.0) * pallLoftTop(p), 1.0 / 0.88), 1.0);
    float x0 = mix(y / 2.7182818 * (1.0 + 0.6 * y), 1.0 - sqrt(max(2.0 * (1.0 - y), 0.0)), step(0.7, y)), e0 = exp(1.0 - x0);
    float x1 = clamp(x0 - (x0 * e0 - y) / max((1.0 - x0) * e0, 0.001), 0.0, 1.0), e1 = exp(1.0 - x1);
    float x2 = clamp(x1 - (x1 * e1 - y) / max((1.0 - x1) * e1, 0.001), 0.0, 1.0);
    return min(x2 / p, 1.0);
}

// Top over dust of the phase share ph against its top at the end of the phase (BlastWall.heated): after the phase the heated ground lifts
// the layer, h² = h₁² + b²·x (DustModel.Column.height) — √(1 + lift·(ph − 1)); before — 1
float pallWallHeated(float ph, float lift) {
    return sqrt(1.0 + lift * max(ph - 1.0, 0.0));
}

// Share of the path of dust with the wind behind the front for the share u of the positive phase (BlastWall.carriedShare): u·e^(1 − u);
// behind the phase the wind is rarefaction (reverse), and the share falls to zero
float pallWallCarried(float u) {
    float x = max(u, 0.0);
    return x * exp(1.0 - x);
}

// The dust parcel of the head in its own coordinates (BlastWall.origin): where it was lifted — point (rel — from the epicenter along the
// ground, blocks) minus its path with the wind behind the front, carried (blocks) for the share u of the phase (at the ground the air
// stands still, higher it goes by the 1/7 law of the turbulent boundary layer); and the share of the layer top over the place, local
// (blocks), at which it rides (z — above the relief): the dusty layer grows as one body, a parcel keeps its share while the top over it
// rises. Billows in these coordinates ride the parcels: thrown up at the front, lagging behind it, rising ever slower. (x, share, z)
vec3 pallWallOrigin(vec2 rel, float z, float u, float local, float carried) {
    float zr = z / max(local, 0.001);
    float x = carried * pallWallCarried(u) * pow(clamp(zr, 0.0, 1.0), 1.0 / 7.0);
    vec2 out1 = rel / max(length(rel), 1e-3);
    return vec3(rel.x - out1.x * x, zr, rel.y - out1.y * x);
}

// Density at the ground by the share u of the wedge against its density at its end (BlastWall.shape): the mass lofted by then (pallLoftMass)
// in the layer of the top pallWallTop
float pallWallShape(float u, float ph) {
    if (u <= 0.0) return 0.0;
    float x = min(u, 1.0), p = PALL_WALL_LOFT_GAIN * max(ph, 0.001);
    return pallLoftMass(x * p) / pallLoftMass(p) / pallWallTop(x, ph);
}

// Where the head behind the wedge passes to the pall, in lengths of the wedge from the leading edge of the dust (BlastWall.HANDOFF_FROM,
// HANDOFF_TO): from its end to one and a half lengths
const float PALL_WALL_HANDOFF_FROM = 1.0;
const float PALL_WALL_HANDOFF_TO = 1.5;

// Share of the head behind the wedge (BlastWall.handoff)
float pallWallFade(float u) {
    return 1.0 - smoothstep(PALL_WALL_HANDOFF_FROM, PALL_WALL_HANDOFF_TO, u);
}

// Highest top of the head (BlastWall.ceiling): the heated layer at the end of the hand-over to the pall — top at the end of the wedge, share
// of the phase there, rise of the heated layer
float pallWallCeiling(float top, float ph, float lift) {
    return top * pallWallHeated(PALL_WALL_HANDOFF_TO * ph, lift);
}

// Point in the frame of the head (BlastWall.sigma): s — beyond the leading edge of the dust, z — above the relief (blocks), len, top —
// length and top of the wedge of the slot, ceiling — its highest top (pallWallCeiling), lag — lag of the spray of the slot. Outside — 0,
// inside — 1
float pallWallInside(float s, float z, float len, float top, float ceiling, float lag) {
    if (s < -1.0) return 0.0;
    if (s > PALL_WALL_HANDOFF_TO * len + lag) return 0.0;
    if (z > ceiling) return 0.0;
    if (z < -0.5 * top) return 0.0;
    return 1.0;
}

// Saltation development at the share u of the wedge behind the edge (BlastWall.develop): the share prompt rises at once (suspension,
// debris), the rest as 1 − e^(−age/SALTATION_S); rate — e-folds over the wedge length
float pallWallDevelop(float u, float rate, float prompt) {
    return 1.0 - (1.0 - prompt) * exp(-max(u, 0.0) * rate);
}

// Attenuation multiplier of the head at s blocks beyond the edge of the dust with the lag of the spray (BlastWall.density): dust — from
// the share edge at the edge to end at the end of the wedge of length len (× scale; dust lifted closer to the epicenter — of its own
// density), developing behind the front (pallWallDevelop: rate, prompt), thinner over hard ground (share hard; dust there against
// soil — pavedEdge at the edge to pavedEnd at the end: debris dust only); spray — its own; wet — share of water under the point
float pallWallDensity(float s, float len, float scale, float edge, float end, float spray, float wet, float rate, float prompt,
        float pavedEdge, float pavedEnd, float hard) {
    float v = s / max(len, 0.001), u = clamp(v, 0.0, 1.0);
    float soil = 1.0 + hard * (pavedEdge + (pavedEnd - pavedEdge) * u - 1.0);
    float dust = scale * (edge + (end - edge) * u) * pallWallDevelop(v, rate, prompt) * soil;
    return dust + (spray - dust) * wet;
}

// Share of the density of the head without noise (BlastWall.mean): s — beyond the leading edge of the dust (blocks; ahead — less than zero),
// z — above the relief (blocks), len — length of the wedge, top — its top at the end, ph — share of the phase at its end, lift — rise of
// the heated layer (pallWallHeated). By height — the profile of the pall up to the top here; behind the wedge the heated layer spreads
// the same dust higher; under the relief of the map — up to half that local top (the map is coarser than the world: in the lowlands under
// it the head does not hang; with the full top a dense curb stood on the leading edge)
float pallWallMean(float s, float z, float len, float top, float ph, float lift) {
    float u = s / max(len, 0.001);
    if (u <= 0.0) return 0.0;
    float heat = pallWallHeated(u * ph, lift);
    float h = max(top * pallWallTop(u, ph) * heat, 0.001), k = pallWallShape(u, ph) * pallWallFade(u) / heat;
    if (z < 0.0) return k * smoothstep(-0.5 * h, -0.25 * h, z);
    return k * pallProfile(z / h);
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
    // share of hard ground under the point (pallSoil): only debris dust there; sand share of the soil (its colour)
    float hard;
    float sand;
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
    h.hard = 0.0;
    h.sand = 0.0;
    h.P = vec4(V[0].y, V[0].z, 0.0, 0.0);
    if (V[0].w <= 0.0 || pallWallInside(h.s, h.z, V[0].y, V[0].z, pallWallCeiling(V[0].z, V[2].x, V[2].w), V[1].y) <= 0.0) return h;
    if (W.w > 0.5) h.wet = pallWater(ground, G, h.rel);
    vec2 soil = pallSoil(ground, S, G, h.rel);
    h.hard = soil.x;
    h.sand = soil.y;
    float lag = V[1].y * h.wet;
    h.P = vec4(V[0].y, V[0].z * mix(1.0, V[1].z, h.wet), lag, pallWallDensity(h.s - lag, V[0].y, V[0].w, V[3].z, V[3].w, V[1].w, h.wet,
            V[2].y, V[2].z, V[3].x, V[3].y, h.hard));
    return h;
}

// ---------------------------------------------------------------- head lobes (client/nuclear/WallLobes, WallLobeTable)
// The head is one explicit field of billow lobes, built once per frame on the CPU (WallLobes: the only implementation of its laws) and
// read here: the march composites each lobe of its pixel's screen tile at its depth (fx_pall), the layer quads and source probes
// (pall_front.glsl) and the composite (fx_pall_composite) walk the same records by lattice cell (pallLobeWalk), the eye's dust
// (NukePall) uses the Java field itself. A lobe is a parcel of dust lofted at the front, carried outward by the wind behind the front at
// its height share and riding its share of the local top — thrown up at the front, lagging behind it, rising ever slower: the roll-back
// is the lobes' own motion; its parent carries the law's head column above the ground over its lattice cell, three children on its upper
// half share it (the crest's lumps, churning). Density A·e^(−q²), q the normalized distance (forward, back, across, up kernel radii):
// the chord is closed-form, so every reader sums the same optical depths. The lofted front is opaque (σ ~5/block), so noise inside it
// never shows and a dent moves its mass: what shows is each lobe's rounded, soft-edged surface.

// Layout of the lobe rows in the frame's object table (the mushroom table, MushroomField.WIDTH texels wide; copy of WallLobeTable):
// records of four texels from row PALL_LOBE_ROW; the directory row; the screen tiles' rows
const int PALL_LOBE_WIDTH = 256;
const int PALL_LOBE_ROW = 5;
const int PALL_LOBE_PER_ROW = 64;
const int PALL_LOBE_DIR_ROW = 197;
const int PALL_LOBE_ENTRY = 5;
const int PALL_LOBE_TILE_ROW = 198;
const int PALL_LOBE_TILE_CAP = 64;
const int PALL_LOBE_PER_CELL = 4;
// presentation numbers of the lobes (WallLobes): spacing in wedge-end tops, radii in spacings and local tops, centre height shares,
// size spread, children's seat and size in the parent's radii (here for the head's bounds only)
const float PALL_LOBE = 1.25;
const float PALL_LOBE_WIDE = 0.6;
const float PALL_LOBE_TALL = 0.6;
const float PALL_LOBE_LOW = 0.25;
const float PALL_LOBE_RISE = 0.2;
const float PALL_LOBE_SIZE = 0.3;
const float PALL_LOBE_CHILD_OUT = 0.85;
const float PALL_LOBE_CHILD_SIZE = 0.4;
// a lobe's density is Gaussian, A·e^(−q²), cut at q = PALL_LOBE_CUT kernel radii; the dense lobe shows to about PALL_LOBE_SEEN of them,
// with a soft edge (its optical depth runs from 10 to 0.1 over a third of that radius: the turbulent fringe of dust); the presentation
// sizes above are seen radii (WallLobes)
const float PALL_LOBE_CUT = 3.0;
const float PALL_LOBE_SEEN = 2.5;
// walk samples per head span at most (pallLobeWalk): at half a spacing the 3×3 cells around each sample hold every lobe the ray crosses;
// a walk stops behind this optical depth of lobes already passed
const int PALL_LOBE_WALK = 40;
const float PALL_LOBE_OPAQUE = 9.0;

// Lattice of slot V: spacing (blocks, a power of two) and the next octave's share (WallLobes.tile)
vec2 pallLobeTile(mat4 V) {
    float k = log2(max(PALL_LOBE * V[0].z, 1.0)) + 0.5, k0 = floor(k);
    return vec2(exp2(k0), smoothstep(0.85, 1.0, k - k0));
}

// Cells around the ring in lattice row j (WallLobes.row)
float pallLobeRow(int j) {
    return max(floor(6.2831853 * (float(j) + 0.5) + 0.5), 3.0);
}

// Lobe: centre (from the eye), outward unit at it, kernel radii (forward, back, across, up; blocks), peak extinction A (1/block; 0 — none),
// slot, wedge share, optical depths through the other lobes up from its top and toward the sun from its sunward side (WallLobeTable.light),
// the ground under it (water, hard, sand shares)
struct WallLobe {
    vec3 c;
    vec3 er;
    vec4 R;
    float A;
    int slot;
    float u;
    float up;
    float sun;
    float wet;
    float hard;
    float sand;
};

// Record `index` of the table t; c0, c1 — ground zero (x, z from the eye) of slots 0 and 1 (the outward unit)
WallLobe pallLobeRecord(sampler2D t, int index, vec2 c0, vec2 c1) {
    ivec2 at = ivec2((index % PALL_LOBE_PER_ROW) * 4, PALL_LOBE_ROW + index / PALL_LOBE_PER_ROW);
    vec4 a = texelFetch(t, at, 0), b = texelFetch(t, at + ivec2(1, 0), 0), k = texelFetch(t, at + ivec2(2, 0), 0), g = texelFetch(t, at + ivec2(3, 0), 0);
    WallLobe L;
    L.c = a.xyz;
    L.A = a.w;
    L.R = max(b, vec4(0.25));
    L.slot = int(k.x + 0.5);
    L.u = k.y;
    L.up = k.z;
    L.sun = k.w;
    L.wet = g.x;
    L.hard = g.y;
    L.sand = g.z;
    vec2 c = c0;
    if (L.slot == 1) c = c1;
    vec2 out1 = L.c.xz - c;
    out1 /= max(length(out1), 1e-3);
    L.er = vec3(out1.x, 0.0, out1.y);
    return L;
}

// Error function (Abramowitz and Stegun 7.1.26, error below 1.5·10⁻⁷; WallLobes.erf)
float pallErf(float x) {
    float a = abs(x), t = 1.0 / (1.0 + 0.3275911 * a);
    float y = 1.0 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * exp(-a * a);
    return sign(x) * y;
}

// ∫e^(−q²) over the part of [lo, hi] (ray length) inside one half-lobe, within the cut: normalized ray alpha + t·beta, q² = q_min² +
// |beta|²(t − ts)², so e^(−q_min²)·√π/(2|beta|)·(erf(|beta|(t1 − ts)) − erf(|beta|(t0 − ts))) (WallLobes.part)
float pallLobePart(vec3 alpha, vec3 beta, float lo, float hi) {
    float B = dot(beta, beta);
    if (B < 1e-12 || hi <= lo) return 0.0;
    float ts = -dot(alpha, beta) / B, q2 = dot(alpha, alpha) - B * ts * ts;
    if (q2 >= PALL_LOBE_CUT * PALL_LOBE_CUT) return 0.0;
    float w = sqrt((PALL_LOBE_CUT * PALL_LOBE_CUT - q2) / B), x0 = max(lo, ts - w) - ts, x1 = min(hi, ts + w) - ts;
    if (x1 <= x0) return 0.0;
    float b = sqrt(B);
    return exp(-q2) * 0.886226925 / b * (pallErf(b * x1) - pallErf(b * x0));
}

// Optical depth of lobe L along the ray o + t·d over [ta, tb]: its forward and back halves (split where the ray crosses the plane through
// its centre across the radius), each a closed-form chord (WallLobes.tau)
float pallLobeTau(WallLobe L, vec3 o, vec3 d, float ta, float tb) {
    if (L.A <= 0.0 || tb <= ta) return 0.0;
    vec3 et = vec3(-L.er.z, 0.0, L.er.x), q = o - L.c;
    vec3 a = vec3(dot(q, L.er), dot(q, et), q.y), b = vec3(dot(d, L.er), dot(d, et), d.y);
    // the ray's part ahead of the centre plane (forward half) and behind it
    vec2 ahead = vec2(1e30, -1e30), behind = vec2(-1e30, 1e30);
    if (b.x > 1e-9) {
        ahead = vec2(-a.x / b.x, 1e30);
        behind = vec2(-1e30, -a.x / b.x);
    } else if (b.x < -1e-9) {
        ahead = vec2(-1e30, -a.x / b.x);
        behind = vec2(-a.x / b.x, 1e30);
    } else if (a.x >= 0.0) {
        ahead = vec2(-1e30, 1e30);
        behind = vec2(1e30, -1e30);
    }
    vec3 f = vec3(L.R.x, L.R.z, L.R.w), k = vec3(L.R.y, L.R.z, L.R.w);
    return L.A * (pallLobePart(a / f, b / f, max(ta, ahead.x), min(tb, ahead.y)) + pallLobePart(a / k, b / k, max(ta, behind.x), min(tb, behind.y)));
}

// Outward normal of lobe L's density surface through p
vec3 pallLobeNormal(WallLobe L, vec3 p) {
    vec3 et = vec3(-L.er.z, 0.0, L.er.x), q = p - L.c;
    float x = dot(q, L.er), rx = L.R.y;
    if (x >= 0.0) rx = L.R.x;
    vec3 g = L.er * (x / (rx * rx)) + et * (dot(q, et) / (L.R.z * L.R.z)) + vec3(0.0, q.y / (L.R.w * L.R.w), 0.0);
    return dot(g, g) > 1e-20 ? normalize(g) : vec3(0.0, 1.0, 0.0);
}

// How far a child's kernel reaches out from its parent's centre, in the parent's kernel radii, at least the parent's cut (WallLobes.CHILD_REACH)
float pallLobeChildReach() {
    return max(PALL_LOBE_CUT, PALL_LOBE_CHILD_OUT * PALL_LOBE_SEEN + PALL_LOBE_CHILD_SIZE * PALL_LOBE_CUT);
}

// Highest a lobe of slot V reaches above the relief (blocks): the law's highest local top (the lofting burst peaks at half the wedge,
// pallLoftTop's maximum 1; the heated layer at the handoff's end) times the highest centre share and vertical reach of a child on its
// parent (WallLobes.ceiling)
float pallLobeCeiling(mat4 V) {
    float p = PALL_WALL_LOFT_GAIN * max(V[2].x, 0.001), peak = 1.0;
    if (p >= 1.0) peak = pallLoftTop(p);
    return V[0].z / peak * pallWallHeated(PALL_WALL_HANDOFF_TO * V[2].x, V[2].w)
            * (PALL_LOBE_LOW + PALL_LOBE_RISE + PALL_LOBE_TALL / PALL_LOBE_SEEN * (1.0 + 0.5 * PALL_LOBE_SIZE) * pallLobeChildReach());
}

// Farthest a lobe of slot V reaches behind its centre (blocks): a child at the back of a parent of the larger lattice (WallLobes.reach)
float pallLobeReach(mat4 V) {
    return 2.0 * pallLobeTile(V).x * PALL_LOBE_WIDE / PALL_LOBE_SEEN * (1.0 + 0.5 * PALL_LOBE_SIZE) * pallLobeChildReach();
}

#ifdef PALL_LOBE_COLLECT
// Head lobes on the march's ray (fx_pall main records them from its tile): the nearest LOBES by the ray distance nearest their centres,
// sorted; record index, optical depth on the ray; the ray part they were taken on (gLobeFrom, gLobeTo). The march composites each at its
// depth (lobesBefore), lit where the ray enters it
const int LOBES = 12;
float gLobeT[LOBES] = float[LOBES](0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
float gLobeTau[LOBES] = float[LOBES](0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
int gLobeIndex[LOBES] = int[LOBES](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
int gLobes = 0, gLobeNext = 0;
float gLobeFrom = 0.0, gLobeTo = 0.0;

// record a lobe on the ray: its record index, optical depth on the ray, the ray distance nearest its centre
void pallLobeFound(int index, float tau, float t) {
    int at = gLobes;
    if (gLobes == LOBES) {
        if (t >= gLobeT[LOBES - 1]) return;
        at = LOBES - 1;
    } else {
        gLobes += 1;
    }
    for (int m = 0; m < LOBES; m++) {
        if (at <= 0 || gLobeT[at - 1] <= t) break;
        gLobeT[at] = gLobeT[at - 1];
        gLobeTau[at] = gLobeTau[at - 1];
        gLobeIndex[at] = gLobeIndex[at - 1];
        at -= 1;
    }
    gLobeT[at] = t;
    gLobeTau[at] = tau;
    gLobeIndex[at] = index;
}
#endif

// Lobes of slot k (S, V) near the ray o + t·dir (o from the eye) on [ta, tb], read from table t: their optical depth there. Samples along
// the ray half a spacing apart (at most PALL_LOBE_WALK, spread over longer spans); at each, the parcel's lattice cell (its origin: the
// point minus the carry at a lobe's middle height share) and the 3×3 cells around it, each cell's parent and children once per lattice
// (the directory's rows: WallLobeTable). A lobe whose surface the ray crosses lies in one of those cells (WallLobesTest). Stops once the
// lobes passed are opaque (PALL_LOBE_OPAQUE)
float pallLobeWalk(sampler2D t, int k, mat4 S, mat4 V, vec3 o, vec3 dir, float ta, float tb) {
    if (V[0].w <= 0.0 || S[1].w < 0.5 || tb <= ta) return 0.0;
    float tau = 0.0, behind = 0.0, len = max(V[0].y, 0.001), end = tb;
    float carry = V[1].x * pow(PALL_LOBE_LOW + 0.5 * PALL_LOBE_RISE, 0.1428571);
    vec2 centre = S[0].xy;
    int seen[24];
    int seenN = 0;
    for (int lv = 0; lv < 2; lv++) {
        int e = (2 * k + lv) * PALL_LOBE_ENTRY;
        vec4 head = texelFetch(t, ivec2(e, PALL_LOBE_DIR_ROW), 0);
        float D = head.x;
        int j0 = int(head.z + 0.5), rows = int(head.w + 0.5);
        if (head.y <= 0.0 || D <= 0.0 || rows <= 0) continue;
        int samples = int(clamp(ceil((tb - ta) / (0.5 * D)), 1.0, float(PALL_LOBE_WALK)));
        float ds = (tb - ta) / float(samples);
        seenN = 0;
        for (int m = 0; m < samples; m++) {
            float s = ta + (float(m) + 0.5) * ds;
            if (s > end + ds) break;
            if (behind > PALL_LOBE_OPAQUE) {
                end = min(end, s);
                break;
            }
            vec2 rel = o.xz + dir.xz * s - centre;
            float r = length(rel), a = atan(rel.y, rel.x);
            if (a < 0.0) a += 6.2831853;
            float ro = r - carry * pallWallCarried((V[0].x - r) / len * V[2].x);
            int jc = int(floor(ro / D));
            for (int dj = -1; dj <= 1; dj++) {
                int row = jc + dj - j0;
                if (row < 0 || row >= rows) continue;
                float n = pallLobeRow(jc + dj);
                int base = int(texelFetch(t, ivec2(e + 1 + row / 4, PALL_LOBE_DIR_ROW), 0)[row % 4] + 0.5);
                int i0 = int(floor(a / 6.2831853 * n));
                for (int di = -1; di <= 1; di++) {
                    int i = int(mod(float(i0 + di), n));
                    int key = row * 4096 + i;
                    bool done = false;
                    for (int q = 0; q < seenN; q++) done = done || seen[q] == key;
                    if (done) continue;
                    if (seenN < 24) {
                        seen[seenN] = key;
                        seenN += 1;
                    } else {
                        // oldest first: cells far behind the ray's walk are not met again
                        for (int q = 0; q < 23; q++) seen[q] = seen[q + 1];
                        seen[23] = key;
                    }
                    for (int c = 0; c < PALL_LOBE_PER_CELL; c++) {
                        WallLobe L = pallLobeRecord(t, base + i * PALL_LOBE_PER_CELL + c, centre, centre);
                        float lt = pallLobeTau(L, o, dir, ta, tb);
                        if (lt <= 1e-4) continue;
                        tau += lt;
                        if (dot(L.c - o, dir) < s) behind += lt;
                    }
                }
            }
        }
    }
    return tau;
}

// Segments of the ray dir from the eye on [ta, tb] where the head of the slot may be: the ring from the leading edge of the dust to one
// and a half lengths of the wedge beyond it (with the spray), by height — from the bottom of the relief map to its top with the head.
// (x, y) and (z, w) — entry and exit; exit ≤ entry — misses
vec4 pallWallSpans(mat4 S, mat4 V, vec4 G, vec3 dir, float ta, float tb) {
    vec4 none = vec4(1.0, 0.0, 1.0, 0.0);
    if (V[0].w <= 0.0 || S[1].w < 0.5) return none;
    vec2 c = S[0].xy;
    // the lobes: up to their highest reach, back to the farthest lobe's back (pallLobeCeiling, pallLobeReach)
    float top = V[0].z, ceiling = pallLobeCeiling(V), outer = V[0].x + 1.0;
    float inner = max(V[0].x - PALL_WALL_HANDOFF_TO * V[0].y - V[1].y - pallLobeReach(V), 0.0);
    // by height: the relief map (without it — the ground under the epicenter) with the head
    float lo = S[0].z + min(G.y, 0.0) - 0.5 * top, hi = S[0].z + max(G.z, 0.0) + ceiling;
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

// Optical depth of the head of slot k on the ray segment [ta, tb] (dir from the eye): its lobes (table t) over each of its spans
// (pallWallSpans) — what the head covers in front of the square of the layer (fx.vsh), a source (fx_probe) or the world
// (fx_pall_composite): the same lobes the march draws
float pallWallTau(sampler2D t, int k, mat4 S, mat4 V, vec4 G, vec3 dir, float ta, float tb) {
    vec4 spans = pallWallSpans(S, V, G, dir, ta, tb);
    float tau = 0.0;
    if (spans.y > spans.x) tau += pallLobeWalk(t, k, S, V, vec3(0.0), dir, spans.x, spans.y);
    if (spans.w > spans.z) tau += pallLobeWalk(t, k, S, V, vec3(0.0), dir, spans.z, spans.w);
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
// Fire map: R — flame, G — smoke supply into the columns, B — G × risen share of the column (fireRisen); reduced copies — clusters of
// sources (light and smoke at height h — from the level where the map cell is the width of the column, or the glow there).

// drift of the column at height h above the ground, blocks per unit of the ratio of wind to rise (FireStorm.bend)
float fireBend(float h, float hb) {
    float x = max(h, 0.0);
    return x * (1.0 + x / hb);
}

// half-width of the column at height h, blocks (FireStorm.width): at the ground — already cells, higher it grows
// Coarse feed maps cover the full ignition zone; source columns retain their world-space spacing.
float fireSourceCell(float cell) { return min(32.0, cell); }

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

// column smoke feed at height h: feed g below the column head at top·b/g (risen share in channel B), none above; the head is
// a soft cap a fifth of its height (FireStorm.feedAt)
float fireRisen(float h, float top, float g, float b) {
    float head = top * clamp(b / max(g, 1e-6), 0.0, 1.0);
    return g * (1.0 - smoothstep(0.8 * head, head + 1.0, h));
}

// place on the fire map of the slot under point xz (from the eye)
vec2 fireUv(mat4 S, mat4 F, vec2 xz) {
    return (xz - S[0].xy) / (2.0 * F[0].x) + 0.5;
}

// ---------------------------------------------------------------- near pall volume (froxels: PallPass, fx_pall_froxel*)
// The pall within PALL_FROXEL_SLICES view-axis slices from the eye is a volume aligned with the screen, as engines draw fog
// a player stands in (Wronski, Assassin's Creed IV 2014; Frostbite; Unreal): each cell (froxel) is a tile of PallFroxel.x
// full-frame pixels on screen and one slice in depth. Slice boundaries grow geometrically, z_k = PallFroxel.y · r^k with
// r = (PallFroxel.z / PallFroxel.y)^(1 / slices): a slice is as deep as it is far times (r − 1), like the tile is wide.
// Slice 0 starts at the eye. Each slice is one tile of a 2D atlas, PALL_FROXEL_COLUMNS tiles across: no 3D render target
// (GL 3.2 core everywhere, as Minecraft's own targets). Two volumes: the medium in each cell (fx_pall_froxel: emission, rgb,
// and extinction, a; jittered within the cell and averaged over frames by world-space reprojection) and the pall seen up to
// each slice's far boundary (fx_pall_froxel_sum: linear light, rgb, and the share of what lies behind still
// seen, a). The composite reads the second at each full-frame pixel's own depth: no screen-space history at silhouettes,
// the edge of a block cuts the volume at the block itself
const int PALL_FROXEL_SLICES = 64;
const int PALL_FROXEL_COLUMNS = 8;

// log r: depth growth per slice
float froxelGrowth(vec4 F) {
    return log(F.z / F.y) / float(PALL_FROXEL_SLICES);
}

// view-axis depth of slice boundary k (0..slices; 0 is the eye)
float froxelBoundary(vec4 F, float k) {
    return k <= 0.0 ? 0.0 : F.y * exp(k * froxelGrowth(F));
}

// tile of slice k at cell xy (cell centers at integers, clamped to the tile: no bleeding into the neighbouring slice)
vec2 froxelUv(ivec2 atlas, vec2 xy, int k) {
    ivec2 tile = atlas / ivec2(PALL_FROXEL_COLUMNS, PALL_FROXEL_SLICES / PALL_FROXEL_COLUMNS);
    vec2 c = clamp(xy, vec2(0.0), vec2(tile - 1)) + 0.5 + vec2(k % PALL_FROXEL_COLUMNS, k / PALL_FROXEL_COLUMNS) * vec2(tile);
    return c / vec2(atlas);
}

// volume v at cell xy (fractional, centers at integers) and fractional slice s (slice centers at integers), bilinear within
// a slice and linear between slices
vec4 froxelLerp(sampler2D v, vec2 xy, float s) {
    ivec2 atlas = textureSize(v, 0);
    s = clamp(s, 0.0, float(PALL_FROXEL_SLICES - 1));
    int k = int(floor(s));
    vec4 a = textureLod(v, froxelUv(atlas, xy, k), 0.0);
    vec4 b = textureLod(v, froxelUv(atlas, xy, min(k + 1, PALL_FROXEL_SLICES - 1)), 0.0);
    return mix(a, b, s - float(k));
}

// Near pall seen in front of a point at view-axis depth z behind full-frame pixel position pixel (center +0.5): integrated
// volume sum (fx_pall_froxel_sum, slice k holds the pall to boundary k + 1); before boundary 1, from nothing at the eye.
// Beyond the volume's far boundary, all of it
vec4 froxelNear(sampler2D sum, vec4 F, vec2 pixel, float z) {
    vec2 xy = pixel / F.x - 0.5;
    float first = froxelBoundary(F, 1.0);
    if (z < first) return mix(vec4(0.0, 0.0, 0.0, 1.0), froxelLerp(sum, xy, 0.0), max(z, 0.0) / first);
    return froxelLerp(sum, xy, log(min(z, F.z) / F.y) / froxelGrowth(F) - 1.0);
}

// View-axis depth where the near volume ends on the ray dir from the eye (axis — its cosine to the view axis): the volume's
// far boundary, or the last slice boundary before the ray enters a slot's dust-wall head (pallWallSpans), with the slots'
// fields S0/S1, heads V0/V1 and relief maps G0/G1. The head is a roller a few dozen blocks thick that moves with the wave:
// one point per cell averaged over frames drew it as clumps and holes, and in front of it the ground took the volume's
// thinner head; the rays march it per pixel from here (fx_pall), behind the volume's own part of the ray (froxelNear up to
// this depth), so the order along the ray stays: near air, the head, the pall behind it. Every reader of the volume and the
// rays takes the same boundary: the composite, the reduced-frame mix and the march
float froxelEnd(vec4 F, vec3 dir, float axis, mat4 S0, mat4 V0, vec4 G0, mat4 S1, mat4 V1, vec4 G1) {
    float far = F.z / axis;
    vec4 h0 = pallWallSpans(S0, V0, G0, dir, 0.0, far), h1 = pallWallSpans(S1, V1, G1, dir, 0.0, far);
    float a = far;
    if (h0.y > h0.x) {
        a = min(a, h0.x);
    } else if (h0.w > h0.z) {
        a = min(a, h0.z);
    }
    if (h1.y > h1.x) {
        a = min(a, h1.x);
    } else if (h1.w > h1.z) {
        a = min(a, h1.z);
    }
    if (a >= far) return F.z;
    // whole slices only: the slice the ray enters the head in is the rays'
    float z = a * axis;
    if (z <= F.y) return 0.0;
    return froxelBoundary(F, floor(log(z / F.y) / froxelGrowth(F)));
}

// Near volume (froxelNear: linear light, rgb, and the share behind still seen, a) in front of a pall frame (pallShown display
// light premultiplied, rgb; transmittance, a). One medium's display is that of its summed light, as one ray's (fx_pall main):
// adding two displays instead is darker wherever the near air is dimmer than the pall behind it (display is concave: a lit
// pall over a dark street, looking up). The frame's light comes back exactly while its medium is below the shoulder's ceiling
// (no overexposure, pallShown); brighter (fireball, glow over white) the near volume goes over its display as is
vec4 froxelOver(vec4 near, vec4 far) {
    float cover = 1.0 - far.a, seen = near.a;
    vec4 over = vec4(pallShown(near.rgb, 1.0 - seen) + seen * far.rgb, seen * far.a);
    vec3 medium = far.rgb / max(cover, 1e-4);
    float top = max(max(medium.r, medium.g), medium.b);
    if (cover <= 1e-4 || top >= 0.85) return over;
    vec3 farLight = pallLinear3(unshoulder(medium)) * cover;
    vec4 joined = vec4(pallShown(near.rgb + seen * farLight, 1.0 - seen * far.a), seen * far.a);
    return mix(joined, over, smoothstep(0.75, 0.85, top));
}

// FlashLight.opening: vanilla skylight brightness without an ambient floor. (est.) Aperture throughput.
float flashOpening(float sky) {
    float x = clamp(sky, 0.0, 1.0);
    return x / (4.0 - 3.0 * x);
}
