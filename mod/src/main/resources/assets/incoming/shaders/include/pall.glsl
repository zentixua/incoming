// Pall after the blast wave (client/nuclear/PallField — laws, client/fx/layer/PallPass — passes): the fire-zone haze of one
// detonation from a radius table, the matter the pall volume draws besides the mushrooms, the Wilson shell and the firestorm
// (the ground dust, spray and base surge are the dust pass's, dustwall.glsl). Shared by the volume (fx_pall) and the squares of
// the effects layer (fx.vsh: what the pall covers in front of a particle). Numbers and laws are copies of PallField and
// VolumeNoise.shape (PallGlslTest computes these functions and checks them against Java).
//
// Detonation slot — mat4 by columns:
//   [0] = (epicenter x, z from the eye; ground under the epicenter from the eye by y; how far the table goes, blocks)
//   [1] = (drift by wind x, z of what was lifted at the moment of detonation, blocks (billow noise drifts with it); travel of
//          the oldest smoke parcel downwind, blocks (the band's axis length); 1 — slot exists, 2 — and there is hard ground or sand under its pall: channels
//          b, a of the relief map are read)
//   [2] = (wind x, z — unit; the slot's half of the shared terrain texture, pallGroundUv; v of the haze row in the table)
//   [3] = (radius of the fire zone, blocks; noise tile, blocks; seconds after detonation; 1 — there is water under the pall and
//          its map, channel g of the slot's relief map, PallGround; 0 — everywhere land)
// Table: one row per slot, the haze; across the width rgb by radius 0..reach (attenuation at full strength, height, fire light
// from below), a by travel past the fire zone's downwind edge 0..S[1].z (the smoke parcels' strength,
// nuclear/model/SmokeParcels).

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

// Airlight along view direction dir, on screen: the air's own light in front of distant matter. It is the sky vanilla
// draws there without its sun, moon and stars, computed from the frame's sky and fog colours, never sampled from the
// frame (a sampled frame carries the sun and nearby blocks into the cap). Vanilla's sky (LevelRenderer.renderSky,
// FogRenderer FOG_SKY): a disc 16 above the eye, radius 512, in the sky colour, fogged into the fog colour by its
// cylinder distance, smoothstep from 0 to the render distance; the vertex distance runs 16 at the centre to 512 at the
// rim, interpolated (the 45° fan's chords make the rim up to 8 % nearer, ignored); past the rim and below the horizon —
// the clear colour, the fog colour. Copy — NukeExposure.airLight
// (sky: vanilla's sky colour; fog: its fog colour, w the render distance)
vec3 pallAirLightOf(vec3 dir, vec3 sky, vec4 fog) {
    float across = length(dir.xz);
    if (dir.y <= 0.0 || across * 16.0 >= 512.0 * dir.y) return fog.rgb;
    float d = 16.0 + (512.0 - 16.0) * (16.0 * across / dir.y) / 512.0;
    return mix(sky, fog.rgb, d < fog.w ? smoothstep(0.0, fog.w, d) : 1.0);
}

// Two world distances behind pixels are the same place: within 3 % and half a block (the young dust wall's march, resolve
// and composite judge it alike; a looser rule in one pass let dust from behind a foreground edge through another)
bool pallSamePlace(float a, float b) {
    return abs(a - b) <= 0.03 * max(min(a, b), 1.0) + 0.5;
}

// albedo of the ground under the pall: land and water (PallLight.LAND, SEA)
const float PALL_LAND = 0.2;
const float PALL_SEA = 0.06;

// Haze of the slot at a point (pallField)
struct PallAt {
    // attenuation without noise (1/block)
    float sigma;
    // column above the point (attenuation × blocks)
    float column;
    // column of the whole haze above the floor of the layer (pallField: floorUp; a point below it — above the point):
    // thickness of the layer in which the point is
    float slab;
    // light of fires from below
    float fire;
    // height of the point in shares of the haze
    float height;
    // share of water under the point (the ground under the layer: land or water)
    float wet;
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

// share of water under the point rel (from the epicenter along the ground) by the slot's map (PallGround.water); no map
// (G.x = 0) — land
// A slot's map in the slots' shared terrain texture (PallPass.ground: slot k's square map at x = k·side, k = S[2].z), clamped to
// its own texel centres so bilinear filtering never reads the other slot's map; a single map (side × side) is read whole.
vec2 pallGroundUv(sampler2D ground, mat4 S, vec2 uv) {
    vec2 size = vec2(textureSize(ground, 0));
    float h = 0.5 / size.y;
    return vec2((clamp(uv.x, h, 1.0 - h) + S[2].z) * size.y / size.x, uv.y);
}
float pallWater(sampler2D ground, mat4 S, vec4 G, vec2 rel) {
    if (G.x <= 0.0) return 0.0;
    return clamp(textureLod(ground, pallGroundUv(ground, S, rel / (2.0 * G.x) + 0.5), 0.0).g, 0.0, 1.0);
}

// soil under the point rel (from the epicenter along the ground) by the slot's map: x — share of hard ground (PallGround.hard: rock,
// roofs, roads, snow — no soil on top, only debris dust is lofted there), y — share of sand (PallGround.sand: desert dust, not the
// reference loamy soil); both fractions of the ground; no map or neither under the pall (S[1].w < 1.5) — reference soil
vec2 pallSoil(sampler2D ground, mat4 S, vec4 G, vec2 rel) {
    if (G.x <= 0.0 || S[1].w < 1.5) return vec2(0.0);
    return clamp(textureLod(ground, pallGroundUv(ground, S, rel / (2.0 * G.x) + 0.5), 0.0).ba, 0.0, 1.0);
}

// Albedo ω' of lofted dust: sand's share of the lofted mass (PallField.sandShare) — sand and hard ground are fractions of the ground as the
// map holds them; hard ground lofts only debris dust, paved of the soil's mass — darkened by soot from fires (smoke share)
vec3 pallDustW(float sand, float hard, float paved, float smoke) {
    float share = sand / max(1.0 + hard * (paved - 1.0), 1e-6);
    return mix(mix(PALL_DUST_W, PALL_SAND_W, clamp(share, 0.0, 1.0)), PALL_SMOKE_W, smoke);
}

// Smoke parcels (nuclear/model/SmokeParcels): a parcel widens by this share of its shift downwind over the fire zone's radius
const float PALL_PARCEL_SPREAD = 0.2;
// nodes per table row (PallField.BINS)
const float PALL_BINS = 128.0;

// Haze of slot S at point p (from the eye), without noise (the average over noise is that); ground, G — relief and water maps
// of the slot; floorUp — floor of the layer under the point (top of the relief by the map, blocks above the ground under the
// epicenter; 0 — ground under the epicenter): slab is computed from it, and above a hill the layer rises from it
// (PallField.sigma is the same). The haze is the newest smoke parcel over the point: across the wind the fire zone's smoke
// abreast of the band's axis (the point's place along the wind, from the epicenter to the oldest parcel), widened by the
// parcel's travel past the zone's downwind edge, at that parcel's strength (the row's channel a, by travel; over the zone the
// smoke leaving now); its source water is read on the fire zone: there are no fires over water. The fires light only the smoke
// above them. Without water under the pall (S[3].w = 0) the map is not read
PallAt pallField(sampler2D table, sampler2D ground, mat4 S, vec4 G, vec3 p, float floorUp) {
    vec2 q = p.xz - S[0].xy;
    float hill = max(floorUp, 0.0);
    float above = p.y - S[0].z - hill, reach = S[0].w;
    floorUp -= hill;
    float len = S[1].z, down = dot(q, S[2].xy), along = clamp(down, 0.0, len), travel = clamp(down - S[3].x, 0.0, len);
    float widen = 1.0 + PALL_PARCEL_SPREAD * travel / max(S[3].x, 1.0);
    vec2 source = (q - S[2].xy * along) / widen;
    float uh = length(source) / reach;
    vec4 haze = texture(table, vec2(min(uh, 1.0), S[2].w));
    float carried = texture(table, vec2((travel / max(len, 1.0) * (PALL_BINS - 1.0) + 0.5) / PALL_BINS, S[2].w)).a;
    // the fires light only the smoke above them: the zone's fire share at the point itself (PallField.fire, 0.85–1.05 of its
    // radius), on the row's fire light read at the parcel's source
    float top = max(haze.g, 1.0), sh = haze.r * carried * pallEdge(uh);
    float fire = haze.b * (1.0 - smoothstep(0.85 * S[3].x, 1.05 * S[3].x, length(q)));
    float wet = 0.0;
    if (S[3].w > 0.5 && sh + fire > 0.0) {
        sh *= 1.0 - pallWater(ground, S, G, source);
        wet = pallWater(ground, S, G, q);
        fire *= 1.0 - wet;
    }
    PallAt a;
    a.height = above / top;
    a.sigma = sh * pallProfile(a.height);
    a.column = sh * top * pallColumn(a.height);
    a.slab = sh * top * pallColumn(min(a.height, floorUp / top));
    a.fire = fire * exp(-3.0 * max(a.height, 0.0));
    a.wet = wet;
    return a;
}

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
    return S[0].z + textureLod(ground, pallGroundUv(ground, S, (xz - S[0].xy) / (2.0 * G.x) + 0.5), 0.0).r;
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

// Share of outside air at point p (from the eye): the Minecraft sky light / 15 in the point's block (OpenAir) — haze and dust
// are where the air is connected to the open sky; in an adit — no. at — the lower corner of the window
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

// presentation: a surface lit far above white (the ball's light on the world by day: tens of whites for seconds at 1 Mt a
// few km out) washes toward white as film and the eye do: channel c of a pixel of luminance lum moves toward lum by
// 1 − 1/lum. The added PALL_CROSS share alone leaves the colour's ratios almost as they were however bright the light, so
// the hue-kept ceiling showed dark green leaves as neon green and shaded red earth as pink stripes
float pallOverWhite(float c, float lum) {
    if (lum <= 1.0) return c;
    return mix(c, lum, 1.0 - 1.0 / lum);
}

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

// ---------------------------------------------------------------- Wilson cloud (client/nuclear/BlastWall)
// The Wilson cloud of the slot — vec4 (BlastWall.slot): radius of the middle of the shell from the detonation point, its
// half-thickness (blocks), attenuation in the middle (1/block; 0 — no cloud), height of the detonation point above the ground
// under the epicenter.

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
