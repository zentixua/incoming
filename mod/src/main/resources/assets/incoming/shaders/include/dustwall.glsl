// Nuclear dust wave (client/fx/layer/DustWallPass): one presentation model (presentation, not physical) of a
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
// light colours (linear, frame units): sun, sky from above, ground bounce
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
// water droplets (spray, base surge): single-scattering albedo and the colour of a thick layer (CloudPuffs.SPRAY)
uniform vec3 DwMistAlbedo;
uniform vec3 DwMistBulk;
// presentation: the dust's exposure (1 while a fireball lights it; DW_ADAPT once only sun and sky do, following the
// fireball's light down: DustSources), like the eye adapting after the flash: the low sun's dust read as dark smoke
uniform float DwAdapt;
// the light's detail level (the preset's rung, DustWallPass): 0 the textured level's full light (two shadow taps of two reads
// each), 1 one read per shadow tap (the billows over the next 12 m; the 70 m swell shadow dropped), 2 the two-stream light of
// the haze level on the textured density (no shadow taps)
uniform float DwDetail;

// Rows of one source in the source table (DustWallPass, PallField.lifetime): row 0 per node (front arrival, model s since the
// burst; dust over hard ground relative to soil, PallField.paved; the fire zone's share; 0), rows 1 + k per age node k (age =
// DwLife.y · ((1 + DwLife.x / DwLife.y)^(k / (DW_AGES − 1)) − 1)): (the presentation layer top, blocks; the column's vertical
// optical depth the transfer reads; both slopes per age node); row 1 + DW_AGES per node: water spray (extinction where the
// blast wind strips the densest spray, 1/block; stripping height, blocks: PallField.spray, sprayFrom); rows DW_SIGHT_ROW + k
// per age interval k, k + 1: the optical depth a camera inside the dust sees (PallField.sighted) and its slope at node k, then
// at node k + 1; the last row: the source's frame inputs (DustSources, read by dwLoad). Node i at radius i / (DW_NODES − 1) ×
// reach.
const int DW_SPRAY_ROW = 33, DW_SIGHT_ROW = 34, DW_PARAMS_ROW = 65, DW_ROWS = 66;
const float DW_NODES = 128.0;
// the source table: the rows above per source (DW_NODES × DW_ROWS each, RGBA32F)
uniform sampler2D Sampler2;
// the height over which the dust thins to its floor at the current march sample (dwFloor: by the fine tile's share)
float gDwFloorSoft = 3.0;
// presentation: the camera's share of the inside sight in the source being read (PallField.sight: 0 outside its dust, where
// every ray reads the law through the transfer; dwLoad from the frame inputs, set by the harness)
float DwSight = 0.0;
#ifdef DW_SOURCES
// Several detonations: one table (Sampler2) holds every source of the frame at DW_ROWS rows each; the march
// sums their extinction and extinction-weighted light at every sample (each source's light finished before the next). The
// inputs of the source being read are the globals below, loaded by dwLoad (one load per source per ray when they do not overlap).
uniform float DwSourceCount;
// the table slot of the frame's source k: DwSlotsA..D hold sources 0–3, 4–7, 8–11, 12–15
uniform vec4 DwSlotsA;
uniform vec4 DwSlotsB;
uniform vec4 DwSlotsC;
uniform vec4 DwSlotsD;
// the terrain atlas (DustWallPass, PallGround per source: height above ground zero's ground, r; DW_GROUND_SIDE² texels a tile in
// a row of tiles) and the material atlas (the server's committed ground material, ClientNuclear.Active.sourceMaterial: r water,
// g sand, b hard ground, a known; a fine and a coarse tile of DW_MATERIAL_SIDE² cells per source, one tile row per source)
uniform sampler2D Sampler3;
// the eye-centred ground tiles' places (dwTerrainTile; DustWallPass from DustGround)
uniform vec4 DwTerrain0;
uniform vec4 DwTerrain1;
uniform sampler2D Sampler4;
uniform sampler3D PallOpen;
uniform vec4 PallOpenAt;
uniform vec3 PallOpenWrap;
const float DW_GROUND_SIDE = 128.0, DW_MATERIAL_SIDE = 256.0;
// the source being read: eye from its ground zero's ground point and its clock (s); (front radius; fade-in share of a source
// first drawn already aged, 0..1; envelope top; table reach); (table's last age; its age scale; drift per model second, xz);
// fireball (centre from ground zero's ground point, radius) and its light; the fire light from below on a fully burning
// zone; the fireball's ground footprint (no dust under it); lowest and highest ground of its terrain map; the base surge of a
// water burst (radius, top, densest extinction, blocks; 0); its terrain map (half side, bottom, top, cell; blocks); its
// material tiles (origin x, z from ground zero, cell, cells across)
vec4 DwEye = vec4(0.0), DwRing = vec4(0.0), DwLife = vec4(1.0, 0.05, 0.0, 0.0), DwBall = vec4(0.0, 0.0, 0.0, 1.0);
vec3 DwBallLight = vec3(0.0), DwFire = vec3(0.0);
float DwCut = 0.0;
vec2 DwRelief = vec2(0.0);
vec4 DwSurge = vec4(0.0), DwGround = vec4(0.0), DwMatFine = vec4(0.0), DwMatCoarse = vec4(0.0);
int gDwRow = 0, gDwSource = -1, gDwSlot = 0;
// the per-ray tuft of each source (dwMarch; T6)
float gDwTuftK[16] = float[16](0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
float gDwTuftDK[16] = float[16](0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
float gDwTuft = 0.0, gDwTuftD = 0.0;
vec4 dwParam(int x) {
    return texelFetch(Sampler2, ivec2(x, gDwRow + DW_PARAMS_ROW), 0);
}
// load source k's inputs (no fetch when it is already loaded)
void dwLoad(int k) {
    if (k == gDwSource) return;
    gDwSource = k;
    float slot = DwSlotsA[k % 4];
    if (k >= 4) slot = DwSlotsB[k % 4];
    if (k >= 8) slot = DwSlotsC[k % 4];
    if (k >= 12) slot = DwSlotsD[k % 4];
    gDwSlot = int(slot + 0.5);
    gDwRow = gDwSlot * DW_ROWS;
    DwEye = dwParam(0);
    DwRing = dwParam(1);
    DwLife = dwParam(2);
    DwBall = dwParam(3);
    vec4 b = dwParam(4), f = dwParam(5), r = dwParam(6);
    DwBallLight = b.rgb;
    DwCut = b.a;
    DwFire = f.rgb;
    DwSight = f.a;
    DwRelief = r.xy;
    DwSurge = dwParam(7);
    DwGround = dwParam(8);
    DwMatFine = dwParam(9);
    DwMatCoarse = dwParam(10);
    gDwTuft = gDwTuftK[k];
    gDwTuftD = gDwTuftDK[k];
}
// the ground the dust lies on around the eye (client/nuclear/DustGround, the last two tiles of the terrain atlas): k 0 the
// fine tile over the drawn chunks, 1 the coarse one over the near kilometres; T: origin from the eye (x, z), cell, the tiles'
// height reference minus the eye's height (0 cell: off). Returns (height above the source's ground zero ground, share: the
// known share × the fade over the tile's outer 8 cells; 0 outside)
vec2 dwTerrainTile(vec4 T, float k, vec2 xz) {
    if (T.z <= 0.0) return vec2(0.0);
    vec2 u = (xz - DwEye.xz - T.xy) / T.z - 0.5;
    if (any(lessThan(u, vec2(0.0))) || any(greaterThan(u, vec2(DW_GROUND_SIDE - 1.0)))) return vec2(0.0);
    vec2 size = vec2(textureSize(Sampler3, 0));
    vec2 t = textureLod(Sampler3, (vec2(size.x - (2.0 - k) * DW_GROUND_SIDE, 0.0) + u + 0.5) / size, 0.0).rg;
    float edge = clamp(min(min(u.x, u.y), min(DW_GROUND_SIDE - 1.0 - u.x, DW_GROUND_SIDE - 1.0 - u.y)) / 8.0, 0.0, 1.0);
    return vec2(t.r + T.w + DwEye.y, t.g * edge);
}
// the terrain under ground point xz (from ground zero's ground point), blocks above that ground: the eye's fine tile where it
// knows the ground, else its coarse tile, else the source's own map (its edge beyond it), each level fading into the next by its
// known share (DustGround.floor: the same blend)
// (program size: every inlined copy of the floor costs AMD's compiler code, +11 KB with a copy in every light path; the full
// floor is read once per march sample by dwSourceDensity's caller, and its density, light and light paths take it, gDwFloorAt)
// the source's own map alone
float dwFloorMap(vec2 xz) {
    if (DwGround.x <= 0.0) return 0.0;
    vec2 uv = clamp(xz / (2.0 * DwGround.x) + 0.5, 0.5 / DW_GROUND_SIDE, 1.0 - 0.5 / DW_GROUND_SIDE);
    vec2 size = vec2(textureSize(Sampler3, 0));
    vec2 at = (vec2(float(gDwSlot) * DW_GROUND_SIDE, 0.0) + uv * DW_GROUND_SIDE) / size;
    return textureLod(Sampler3, at, 0.0).r;
}
float dwFloorCoarse(vec2 xz) {
    vec2 c = dwTerrainTile(DwTerrain1, 1.0, xz);
    return c.y >= 0.999 ? c.x : mix(dwFloorMap(xz), c.x, c.y);
}
float dwFloor(vec2 xz) {
    vec2 f = dwTerrainTile(DwTerrain0, 0.0, xz);
    // the dust thins to the ground over half a metre where the fine tile knows it, three where the floor is a coarser estimate
    // (a 3 m thinning on the true ground left an eye 1.6 m up looking through clear air under the young dust)
    gDwFloorSoft = mix(3.0, 0.5, f.y);
    return f.y >= 0.999 ? f.x : mix(dwFloorCoarse(xz), f.x, f.y);
}
// the share of open air at q (from ground zero's ground point): Minecraft's sky light / 15 inside the outdoor-air window
// around the eye (rooms, caves, adits; fx/layer/OpenAir); beyond it the dust is outdoors (the pall's test there compares with
// the coarse relief map, and samples moving along the ray flipped dust near the ground on and off)
float dwOpen(vec3 q) {
    if (PallOpenAt.w < 0.5) return 1.0;
    vec3 w = q - DwEye.xyz - PallOpenAt.xyz;
    if (any(lessThan(w, vec3(0.0))) || any(greaterThanEqual(w, vec3(96.0, 64.0, 96.0)))) return 1.0;
    return texelFetch(PallOpen, (ivec3(floor(w)) + ivec3(PallOpenWrap)) % ivec3(96, 64, 96), 0).r;
}
// the ground material at ground point xz (from ground zero), shares: water, sand, hard ground (a cell the server has not
// committed yet holds the detonation's ground-zero material until it does: it commits each cell before the front reaches it,
// so no point with dust changes on screen)
vec3 dwMaterialTile(vec4 tile, float row, vec2 xz) {
    vec2 c = (xz - tile.xy) / tile.z;
    vec2 size = vec2(textureSize(Sampler4, 0));
    vec2 at = (vec2(row * DW_MATERIAL_SIDE, float(gDwSlot) * DW_MATERIAL_SIDE) + clamp(c, vec2(0.5), vec2(tile.w - 0.5))) / size;
    vec4 m = textureLod(Sampler4, at, 0.0);
    return m.rgb;
}
vec3 dwMaterial(vec2 xz) {
    if (DwMatFine.z > 0.0) {
        vec2 c = (xz - DwMatFine.xy) / DwMatFine.z;
        if (all(greaterThanEqual(c, vec2(1.0))) && all(lessThan(c, vec2(DwMatFine.w - 1.0)))) return dwMaterialTile(DwMatFine, 0.0, xz);
    }
    if (DwMatCoarse.z <= 0.0) return vec3(0.0);
    return dwMaterialTile(DwMatCoarse, 1.0, xz);
}
// presentation: the eye height over the drawn world's edge up to which a ray with no world behind it is drawn as from the
// ground (DustWallPass.HORIZON_LIFT; 0 when an LOD mod drew the far ground this frame: no strip to close)
uniform float DwHorizonLift;
// where the ray dir from the eye (unit) goes under the terrain map of the nearest source (source 0), searched past the drawn
// world (from: the render distance along the ground or up/down) in 64 steps growing with distance and a secant; 1e9 when it
// does not: a pixel with no world behind it ends there (the pall's pallFloor law on the dust's own map; 24 steps stepped over
// a 64-block ridge 400 blocks out in about half the frames: tools/dust_wall_contract_gl.py relief).
// Minecraft draws no ground there, so a ray that met that ground ended in front of a wave standing on it, and the wave floated
// over a strip of sky as wide as the angle between the drawn edge and its foot (15 kt from 2 km, 12 chunks: a ray 0.5° down
// ended 315 m out, the wall 400 m out; the strip shrank as the wall came in). Presentation: a ray under the undrawn ground's
// horizon along its bearing is moved toward that horizon (dir), dir.y + w × the rise it needs to clear the ground, w full for
// an eye up to DwHorizonLift over the floor at the drawn edge and none from twice that: a standing eye's strip shows the
// horizon ray, the wave's foot on the drawn edge; from a hill or a roof main's ray end. Rows stay in order and each changes
// at its own eye height (one raised ray for the whole strip flipped it within 5 cm of eye height; a fixed raise angle drew the
// wave below its foot from a hill: tools/dust_wall_lift_threshold_probe.py, dust_wall_horizon_probe.py). The first pass
// measures the horizon to the map's edge; a ray moved part way is searched again (a dynamic second pass of the same loop: a
// second loop of floor reads was unrolled by AMD's compiler, 114 → 150 KB)
float dwRayFloor(inout vec3 dir, float from, float jitter) {
    dwLoad(0);
    if (DwGround.x <= 0.0) return 1e9;
    float near = from / max(max(length(dir.xz), abs(dir.y)), 1e-3);
    // the eye's height over the floor at the drawn edge along this ray, the move's weight
    vec3 qe = DwEye.xyz + dir * near;
    float he = DwEye.y - dwFloorCoarse(qe.xz);
    float w = DwHorizonLift > 0.0 ? 1.0 - smoothstep(DwHorizonLift, 2.0 * DwHorizonLift, he) : 0.0;
    vec3 rd = dir;
    float result = 1e9;
    int passes = 1;
    for (int pass = 0; pass < passes; pass++) {
        // the first pass with a weight measures the horizon past the ground the ray meets
        bool horizon = pass == 0 && w > 0.0;
        vec2 span = pallBox(rd, vec3(-DwEye.x - DwGround.x, -1e6, -DwEye.z - DwGround.x),
                vec3(-DwEye.x + DwGround.x, DwGround.z - DwEye.y, -DwEye.z + DwGround.x));
        float ta = max(max(span.x, near), 1.0), tb = span.y;
        if (rd.y < 0.0 && !horizon) tb = min(tb, max((DwGround.y - DwEye.y) / rd.y, ta) + 4.0 * DwGround.w);
        if (tb <= ta) break;
        float ratio = pow(tb / ta, 1.0 / 64.0);
        vec3 q0 = DwEye.xyz + rd * ta;
        // the ray ends on the floor the dust lies on (the eye's coarse tile, then the map). The pall's law (pallFloor): where
        // that floor at the start stands over the ray (the ray passed over the real ground of the drawn chunks), it drops under
        // the ray there and is as it was four map cells farther; without it every ray past the drawn chunks under a map
        // standing over the eye ends at their edge with no dust
        float fade = 4.0 * DwGround.w, d0 = q0.y - dwFloorCoarse(q0.xz);
        float lift = max(1.0 - d0, 0.0) * step(ta, near + 1.0);
        float prevT = ta, prevD = d0 + lift, top = (0.5 - d0) / ta;
        float t = ta * pow(ratio, jitter);
        bool under = prevD <= 0.0;
        for (int i = 0; i <= 64; i++) {
            float tt = min(t, tb);
            vec3 q = DwEye.xyz + rd * tt;
            float f = q.y - dwFloorCoarse(q.xz);
            float d = f + lift * max(1.0 - (tt - ta) / fade, 0.0);
            top = max(top, (0.5 - f) / tt);
            if (d <= 0.0) {
                under = true;
                if (!horizon) {
                    result = prevT + (tt - prevT) * max(prevD, 0.0) / max(prevD - d, 1e-6);
                    break;
                }
            }
            prevT = tt;
            prevD = d;
            t *= ratio;
        }
        if (!horizon || !under) break;
        // under the ground: toward the horizon by the weight; fully there it ends nowhere, part way it is searched again
        rd = dir + vec3(0.0, w * top, 0.0);
        if (w < 0.999) passes = 2;
    }
    float len = length(rd);
    dir = rd / len;
    if (result < 1e9) return result * len;
    return 1e9;
}
#else
// One source (the shader harness, tools/uw_probe/probe_gl.py, tools/dust_wall_gl.py): its inputs DwEye, DwRing, DwLife, DwBall,
// DwBallLight, DwCut, DwFire, DwRelief and DwSurge are the harness's uniforms, declared before this file
const int gDwRow = 0;
const float DwSourceCount = 1.0;
void dwLoad(int k) {
}
float gDwTuft = 0.0, gDwTuftD = 0.0;
// the ground, open air and material from the harness's stubs (dwFloorEye, dwOpenEye, dwSoilAt from eye-relative points)
float dwFloor(vec2 xz) {
    return dwFloorEye(xz - DwEye.xz);
}
float dwFloorCoarse(vec2 xz) {
    return dwFloorEye(xz - DwEye.xz);
}
float dwFloorMap(vec2 xz) {
    return dwFloorEye(xz - DwEye.xz);
}
float dwOpen(vec3 q) {
    return dwOpenEye(q - DwEye.xyz);
}
vec3 dwMaterial(vec2 xz) {
    vec2 soil = dwSoilAt(xz);
    return vec3(0.0, soil.y, soil.x);
}
#endif

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
const float DW_SHEAR_S = 300.0, DW_PLUME = 2.5;

// First and last occupied distances and the sum of distances weighted by visible opacity.
vec3 gDwDepth = vec3(0.0);
// Pixel footprint at the sample, blocks.
float gDwFoot = 0.0;
// set by dwDensity at its sample: the billowed dust surface above it, its full extinction and its share of sand (the light
// reads them)
float gDwSurf = 0.0, gDwSigma = 0.0, gDwSand = 0.0;
// the last dust sample's composition (dwDustTint): its height as a share of its column below the top (1 at the ground), its
// ground point's soil seed and the hard-ground share of its source point
float gDwLow = 0.5, gDwSoil = 0.5, gDwHard = 0.0;
// the ground under the current march sample (dwSourceDensity: its caller's dwFloor), read by its density and light
float gDwFloorAt = 0.0;
// diagnostics (DustWallDiag.probe, fx_dustwall: DwProbe): the k-th occupied sample's terms of this ray; the light's terms of
// the last sample lit (shadow depths toward the ball and the sun, depth into the layer, the swell's lift)
float gDwProbeK = -1.0;
vec4 gDwProbeA = vec4(0.0), gDwProbeB = vec4(0.0), gDwProbeC = vec4(0.0);
float gDwPrTaub = 0.0, gDwPrTaus = 0.0, gDwPrDepth = 0.0, gDwPrLift = 0.0;
// set by dwDensity: the swell at the sample (dwBulge, share of the layer): sags sit in the shade of the swells around them
float gDwSwell = 0.0;

// table row 0 at ground radius r: (front arrival, model s; paved share)
// diagnostics (harness, DW_DIAG): texture fetches of the current ray
float gDwFetches = 0.0;
vec3 dwRow0(float r) {
#ifdef DW_DIAG
    gDwFetches += 2.0;
#endif
    float x = clamp(r / max(DwRing.w, 1.0), 0.0, 1.0) * (DW_NODES - 1.0);
    int i = int(min(floor(x), DW_NODES - 2.0));
    return mix(texelFetch(Sampler2, ivec2(i, gDwRow), 0).xyz, texelFetch(Sampler2, ivec2(i + 1, gDwRow), 0).xyz, x - float(i));
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
#ifdef DW_DIAG
    gDwFetches += 2.0;
#endif
    float k = clamp(floor(u), 0.0, DW_AGES - 2.0), f = clamp(u - k, 0.0, 1.0);
    // (u at the last node: f = 1 of the last interval)
    vec4 p0 = texelFetch(Sampler2, ivec2(i, gDwRow + 1 + int(k)), 0), p1 = texelFetch(Sampler2, ivec2(i, gDwRow + 2 + int(k)), 0);
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
// the optical depth a camera inside the dust sees at node i, age coordinate u (presentation, PallField.sighted: about 200 m at
// one minute, 700 m by five, then the law's own thinning; the haze level's fade by age, as the law's own nodes): the cubic
// Hermite of its age interval's row (both nodes in one texel)
float dwSightAt(int i, float u) {
#ifdef DW_DIAG
    gDwFetches += 1.0;
#endif
    float k = clamp(floor(u), 0.0, DW_AGES - 2.0), f = clamp(u - k, 0.0, 1.0);
    vec4 p = texelFetch(Sampler2, ivec2(i, gDwRow + DW_SIGHT_ROW + int(k)), 0);
    float f2 = f * f, f3 = f2 * f;
    return max((2.0 * f3 - 3.0 * f2 + 1.0) * p.x + (f3 - 2.0 * f2 + f) * p.y + (-2.0 * f3 + 3.0 * f2) * p.z + (f3 - f2) * p.w, 0.0);
}
// dwLaw's depth as a camera inside the dust sees it (the same reading: radius nodes, the table's end)
float dwSight(float r, float a) {
    if (a <= 0.0) return 0.0;
    float u = min(dwAgeU(a), DW_AGES - 1.0);
    float x = clamp(r / max(DwRing.w, 1.0), 0.0, 1.0) * (DW_NODES - 1.0);
    int i = int(min(floor(x), DW_NODES - 2.0));
    return mix(dwSightAt(i, u), dwSightAt(i + 1, u), x - float(i)) * (1.0 - smoothstep(DW_AGES - 2.0, DW_AGES - 1.0, dwAgeU(a)));
}

// the density transfer (presentation): the textured young level's drawn column's vertical optical depth from the law's (the
// haze level draws the law's whole column held under what the screen shows, dwHazeDepth; between them by dwTextured:
// PallField.column). τ itself up to 1 (thin haze
// hides what the law's optical depth hides, Koschmieder); compressive above it, the slope falling from 1 smoothly to
// DW_G_SLOPE over DW_G_KNEE (C1 at τ = 1; the matte, semi-transparent wall: the law's columns of tens to hundreds would draw
// an opaque skin). Fitted to take 33's drawn column at 4.5 km from 1 Mt, ages 1–24 s (tools/uw_probe/probe_gl.py)
const float DW_G_SLOPE = 0.073, DW_G_KNEE = 0.5;
// the haze level's drawn column (PallField.hazeDepth): the law's optical depth, softly held under the depth a ray leaves the
// dust at (dwMarch stops at a transmittance of 1 %: ln 100): deeper dust shows nothing more from any view; the eighth-power
// soft minimum keeps the law within 1 % up to τ 3.3
const float DW_SEEN = 4.6051702;
float dwHazeDepth(float tau) {
    float u = tau / DW_SEEN, u2 = u * u, u4 = u2 * u2;
    return tau / sqrt(sqrt(sqrt(1.0 + u4 * u4)));
}
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

// Water is a material of the same wave: where the source ground is water, the blast wind strips spray from the
// crests instead of soil (PallField: spray, sprayEnvelope, sprayTop; WaterModel.spray, SPRAY_LAG): white droplets, born by
// the same birth and hump, lower (the stripping height, sinking to 0.6 of it as large drops fall), thinning over 30 s and gone
// within minutes. The spray column at radius r and age a: (top, blocks; vertical optical depth, the pall's profile column)
const float DW_SPRAY_LAG = 0.5, DW_SPRAY_S = 30.0, DW_SPRAY_SINK = 0.6, DW_SPRAY_GONE = 0.0015;
vec2 dwSprayLaw(float r, float a) {
    if (a <= DW_SPRAY_LAG) return vec2(1.0, 0.0);
    float x = clamp(r / max(DwRing.w, 1.0), 0.0, 1.0) * (DW_NODES - 1.0);
    int i = int(min(floor(x), DW_NODES - 2.0));
    vec2 sp = mix(texelFetch(Sampler2, ivec2(i, gDwRow + DW_SPRAY_ROW), 0).xy, texelFetch(Sampler2, ivec2(i + 1, gDwRow + DW_SPRAY_ROW), 0).xy,
            x - float(i));
    float env = smoothstep(DW_SPRAY_LAG, DW_SPRAY_LAG + 2.5, a) * exp(-a / DW_SPRAY_S);
    float top = max(sp.y * (DW_SPRAY_SINK + (1.0 - DW_SPRAY_SINK) * exp(-a / DW_SPRAY_S)), 1.0);
    // (the envelope fades to zero over its last tenth above the pall's GONE share: no cut)
    return vec2(top, sp.x * env * smoothstep(DW_SPRAY_GONE, 2.0 * DW_SPRAY_GONE, env) * top * 0.548);
}
// the ground point the air at ground point p came from: p minus the drift of its dust since its own birth (the law's drift:
// the detonation's wind × DRIFT, from the front's arrival there). Fixed point q = p − D·age(q), two steps from
// q₀ = p − D·age(p); the drift is a small share of the front's speed, so it converges at once
vec2 dwSource(vec2 p) {
    vec2 D = DwLife.zw;
    if (dot(D, D) <= 0.0) return p;
    float ap = max(DwEye.w - dwArrival(length(p)), 0.0);
    vec2 q = p - D * ap;
#ifndef DW_KEEP_DRIFT3
    // young dust (under a minute): two steps (one left 2 % of the drift: the drift speed over the front's; two leave
    // ~0.04 %); the third step's 2 fetches a sample bought nothing there
    if (ap < 60.0) return p - D * max(DwEye.w - dwArrival(length(q)), 0.0);
#endif
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
#ifdef DW_DIAG
    gDwFetches += 1.0;
#endif
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

// the slow plumes over hot ground (presentation, est.): where the swell field's large-scale value L is high, a tuft rises
// over the first minute to DW_PLUME of the top (hot: the fire zone's share, half outside it) and sinks back over ~20 min as
// the ground cools; the young level and the haze level read the same function (the puffs' successors from ~15 s: T6)
// T6, the young level's tufts at horizon scale (presentation, est.; the puffs' successors from ~10 s): read once per ray at
// its column's ground point (where the ray comes down to 1.4 of the layer at the eye, 0.6–8 km out: the horizon line is the
// far layer's top seen edge-on), from the swell field's crests, and lifting the top only around that distance along the ray
// (a 1 km Gaussian), so the near field keeps its own top. gDwTuft: the lift there (share of the top); gDwTuftD: its distance
#ifdef DW_DIAG
float gDwTuftTop = 0.0, gDwTuftCrest = 0.0;
#endif
// lift: up to DW_TUFT of the top on the swell field's crests (L 0.5–0.7, a 1.5 power: about half the columns lift, a few
// stand tall), half outside the fire zone. Harness (`probe_gl.py --only tuft`): the crest-squared 0.6–0.82 form lifted
// the far top by 0.4–0.8 px on average (column std 1.7–2.5 px at 720 rows); this form by 11–14 px (std 17–22 px without
// the fire weight). Presentation, est.: hot ground's thermals carry dust a few hundred metres up within a minute
const float DW_TUFT = 8.0, DW_TUFT_W = 1000.0, DW_TUFT_LO = 0.5, DW_TUFT_HI = 0.7;
float dwTuftAt(vec3 g) {
    if (gDwTuft <= 0.0) return 0.0;
    float d = (length(g.xz - DwEye.xz) - gDwTuftD) / DW_TUFT_W;
    return gDwTuft * exp(-d * d);
}

float dwPlume(float L, float a, float hot) {
    float tuft = smoothstep(0.62, 0.84, L);
    return DW_PLUME * hot * tuft * tuft * (1.0 - exp(-a / 40.0)) * exp(-a / 1500.0);
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

// highest the lofted ground matter can reach above the local ground at a point (blocks), below 0 where none is
float dwDustBound(vec3 g) {
#ifdef DW_ANALYTIC
    // the harness's analytic media (tools/uw_probe/probe_gl.py: the march's accuracy)
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
    float full = env * (1.0 + DW_BULGE + dwTuftAt(g)) * DW_HAZE + 6.0;
    // the haze level's crests and plumes (dwHaze: top × (1 + swell + plume), its soft edge to 1.3 of it)
    if (dwTextured(a) < 0.999) {
        // the swell and plume over this ground point (the same fields; the eddies shift the air by up to ~40 m against their
        // 220 m scale: margins of 0.15 on the swell and a lower tuft threshold), so the plumes' room stands only over plumes
        // (a bound over every point with the tallest plume's room tripled the strides of the young view's rays)
        float L;
        float sw = dwSwellField(q, a, L);
        float pl = DW_PLUME * (0.5 + 0.5 * dwRow0(r).z) * smoothstep(0.56, 0.8, L) * (1.0 - exp(-a / 30.0)) * exp(-a / 1200.0);
        float reach = env * (1.0 + max(sw + 0.15, 0.0) + pl) * DW_HAZE * 1.12 + 6.0;
        // (the puffs of the first ~20 s keep the old room over every point)
        full = a < 20.0 ? max(full, reach) : reach;
    }
#ifdef DW_BOUND_SWELL
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

// highest the wave's matter can reach above the local ground at g (blocks; the lofted matter and the base surge's ring),
// below 0 where none is
float dwBound(vec3 g) {
    float b = dwDustBound(g);
    if (DwSurge.z <= 0.0) return b;
    vec2 c = g.xz - DwLife.zw * max(DwEye.w, 0.0);
    return length(c) < 1.05 * DwSurge.x ? max(b, DwSurge.y * 1.3 + 4.0) : b;
}

// noise-free mode (the consumers' reference, the thin tail, a ray's far extra sources): mean seeds and texture, no swells or puffs
bool gDwMean = false;
// the ground material at the last dust sample's source point (water, sand, hard ground: dwMaterial)
vec3 gDwMatNow = vec3(0.0);

// diagnostics: why the last density was zero (1 ring/cut, 2 before arrival, 3 no law, 4 above the ceiling, 5 lean-back age, 6 thin profile)
float gDwWhy = 0.0;
// the sample's law column as a camera outside the dust sees it (dwDustDensity: the source ground's material, no inside sight)
float gDwLawOut = 0.0;
// set by dwDensity at its sample: the textured share (dwTextured; gDwForceF ≥ 0 forces it: harness), and the haze level's
// column there: its drawn optical depth, the depth above the sample, the fire zone's share under it, its top
float gDwF = 1.0, gDwForceF = -1.0, gDwHazeTc = 0.0, gDwHazeAbove = 0.0, gDwHazeFire = 0.0, gDwHazeTop = 0.0;

// share of a column's optical depth above relative height x of its top (the profile's stratification, 1.25 − 0.5x)
float dwColumnAbove(float x) {
    float u = clamp(x, 0.0, 1.0);
    return 1.25 * (1.0 - u) - 0.25 * (1.0 - u * u);
}

// The haze level (noise-free): the same column (the law's top and depth, the same profile, ground fade,
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
    float plume = dwPlume(L, a, 0.5 + 0.5 * row.z);
    float top = max(dwEnvelope(a, h, 1.65) * (1.0 + swell + plume), 1.0);
    if (q.y > top * 1.3 + 6.0) return 0.0;
    float edge = 0.22 * top + 4.0;
    float prof = 1.0 - smoothstep(top - edge, top + 0.4 * edge, q.y);
    float strat = 1.25 - 0.5 * clamp(q.y / top, 0.0, 1.0);
    float paved = 1.0 + gDwMatNow.z * (row.y - 1.0);
    float k = (1.0 + 0.5 * exp(-a / 1.1)) * paved * (1.0 - smoothstep(DwRing.w * (1.0 - 1.0 / (DW_NODES - 1.0)), DwRing.w, r));
    // the haze level's column (PallField.column): seen from outside, the law's whole column here (its material, paved share
    // and fade) held under what the screen shows (dwHazeDepth); a camera inside the dust takes it to the textured level's
    // reading of its inside sight by its share (the inside view as the transfer of the sight draws it)
    float out_ = dwHazeDepth(gDwLawOut * k);
    float tc = DwSight > 0.0 ? out_ + DwSight * (dwTransfer(law.y) * k - out_) : out_;
    // (normalised over its own swollen top: the swells and plumes reshape the column, its optical depth stays the law's
    // through g, so the consumers' noise-free reference holds)
    float sigma = tc / dwProfileInt(top) * prof * strat * smoothstep(0.0, gDwFloorSoft, q.y) * smoothstep(0.0, 0.08, a);
    if (!coarse) {
        sigma *= dwOpen(g);
        gDwSand = clamp(gDwMatNow.y / max(paved, 1e-6), 0.0, 1.0);
        gDwHazeTc = tc;
        // (the depth for the light reads the smooth top: swells drifting over the camera darkened the whole near field)
        gDwHazeAbove = tc * dwColumnAbove(q.y / max(dwEnvelope(a, h, 1.65), 1.0));
        gDwHazeFire = row.z;
        gDwHazeTop = top;
    }
    return sigma;
}
// the noise's gain on the column's extinction that keeps its mean transmission over the billow tile the noise-free column's
// (equal mean extinction gives a higher mean transmission, by Jensen). The noisy column's mean optical depth is
// DW_NOISE_MEAN below the noise-free one (the swells' hollows, the texture's means), and a Gamma(k) model of its spread:
// E[e^(−κτm)] = (1 + κτ/k)^(−k) = e^(−τ) for κ = k(e^(τ/k) − 1)/τ; above τ ≈ 4 nothing shows through either way, so κ holds
// its value there (a soft min) instead of growing. Fitted on 11 columns (tools/uw_probe/probe_gl.py: thin, 0.05 ≤ τ ≤ 6)
const float DW_NOISE_K = 12.0, DW_NOISE_MEAN = 1.058;
float dwNoiseGain(float tau) {
    float t = 4.0 * tau / (4.0 + tau);
    float gain = t < 1e-4 ? 1.0 : DW_NOISE_K * (exp(t / DW_NOISE_K) - 1.0) / t;
    return DW_NOISE_MEAN * gain;
}

// The base surge of a water burst (a second source term of the same model; PallField.surge, WaterModel.surge*):
// the fallen water column's ring of droplet fog, expanding and fading by its law, carried whole by the wind from the burst
// (lofted in the first seconds); a denser, higher roller at its rim (PallField.surgeProfile, surgeTop), billowed by the
// wave's texture. Extinction at g (from ground zero's ground point), hq above the local ground
float dwSurge(vec3 g, float hq, bool coarse) {
    if (DwSurge.z <= 0.0) return 0.0;
    vec2 c = g.xz - DwLife.zw * max(DwEye.w, 0.0);
    float u = length(c) / max(DwSurge.x, 1.0);
    if (u > 1.05) return 0.0;
    float top = max(DwSurge.y * (0.55 + 0.45 * smoothstep(0.3, 0.95, u)), 1.0);
    if (hq > top * 1.3 + 4.0) return 0.0;
    float radial = (0.35 + 0.65 * smoothstep(0.2, 0.85, u)) * (1.0 - smoothstep(0.9, 1.05, u));
    float billows = 0.5;
    if (!coarse && !gDwMean) billows = dwFbm(vec3(c.x, 0.7 * hq, c.y) / vec3(46.0, 30.0, 46.0) + 23.0, 1.0 / 30.0);
    return DwSurge.z * radial * pallProfile(hq / top) * smoothstep(0.0, 3.0, hq) * (0.3 + 1.4 * billows);
}

// set by dwDensity: the share of the sample's extinction that is water droplets (spray, surge): the light's material
float gDwWet = 0.0;

// lofted ground matter's extinction (1/block) at g (soil dust, sand, spray by the source ground's material); coarse: light
// paths (the billows' two first octaves, no streaks, no fine octaves)
float dwDustDensity(vec3 g, bool coarse) {
    // (a sample with no dust here, a water burst's surge alone, has no column above it: not the previous sample's top)
    if (!coarse) {
        gDwSigma = 0.0;
        gDwSurf = 0.0;
    }
#ifdef DW_ANALYTIC
    gDwSurf = 1e9;
    gDwF = gDwForceF >= 0.0 ? gDwForceF : 0.0;
    return dwAnalytic(g);
#endif
    // heights above the local ground (the destination's terrain); the ground point the air came from (the law's drift)
    // (light paths take the sample's own ground: their taps lie within ~70 m of it and see the large shapes only)
    float hq = g.y - gDwFloorAt;
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
    // the source ground's material (one fetch): over water the column is spray's, at a shore both by share
    vec3 mat = dwMaterial(q.xz);
    gDwMatNow = mat;
    // the law's column as a camera outside the dust sees it (the haze level's, dwHaze), then a camera inside the dust takes
    // its share of the inside sight's depth (DwSight)
    // (the column of a source point whose ground is water by share: the dust law's and the spray's by share, a shore point
    // carrying both; dust carried over water stays dust: the share is the source ground's)
    vec2 sp = mat.x > 0.0 ? dwSprayLaw(r, a0) : vec2(1.0, 0.0);
    gDwLawOut = mix(law.y, sp.y, mat.x);
    if (DwSight > 0.0) law.y = mix(law.y, dwSight(r, a0), DwSight);
    law = vec2(mix(law.x, sp.x, mat.x), mix(law.y, sp.y, mat.x));
    if (law.y <= 0.0) return 0.0;
    // the haze level where the texture has faded, blended by the same fade function (no seam in time or space)
    float F = gDwForceF >= 0.0 ? gDwForceF : dwTextured(a0);
    if (!coarse) gDwF = F;
    float haze = 0.0;
    // (the cheapest level lights the textured dust with the haze level's two streams: its column is read too)
    if (F < 0.999) {
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
    vec3 seed = vec3(0.5);
#ifdef DW_KEEP_SEEDS
    if (!coarse && !mean) seed = dwSeeds(q.xz);
#else
    // the seeds' 60–200 m variation fades to its mean from 2 to 3 km from the eye (a pixel there spans 4–7 m at HIGH; the
    // seeds' 6 fetches a sample bought detail the far texture no longer shows)
    float seedFar = smoothstep(2000.0, 3000.0, distance(g, DwEye.xyz));
    if (!coarse && !mean && seedFar < 1.0) seed = mix(dwSeeds(q.xz), vec3(0.5), seedFar);
#endif
    float s1 = seed.x, s2 = seed.y;
    float delay = 0.12 * clamp(1.6 * s2 - 0.55, 0.0, 1.0) * (0.6 + 0.8 * s1);
    float h = law.x * mix(0.88, 1.02, s2), k = dwHump(seed.z);
    float top0 = max(dwEnvelope(a0, h, k), 1.0), ceiling = top0 * (1.0 + DW_BULGE + dwTuftAt(g)) * DW_HAZE + 6.0;
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
    float swellL = 0.5;
    float swell = mean ? 0.0 : dwSwellField(pw.xz, a, swellL);
    if (!coarse) gDwSwell = swell;
    top *= 1.0 + swell + dwTuftAt(g);
    if (!coarse) {
        gDwLow = 1.0 - clamp(q.y / max(top, 1.0), 0.0, 1.0);
        gDwSoil = s1;
        gDwHard = gDwMatNow.z;
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
    // occupancy from the texture's first octaves: the finer ones can lift the billowed top at most by their summed weights
    // (fBm 0.4375 of 0.9375, billows 0.375 of 0.875), so above that top, its soft edge and veil only a puff holds dust and the
    // texture is not read (the young view's rays crossed ~20 such clear cells each at the full texture's cost)
    if (!coarse && !mean) {
        vec3 bq = vec3(pw.x, yc - 0.3 * ph, pw.z) / vec3(34.0, 22.0, 34.0) + 11.0;
        float n1 = 0.5 * mix(0.5, dwNoise3(tq), dwLod(1.0 / 13.0)), b1 = 0.5 * mix(0.42, abs(2.0 * dwNoise3(bq) - 1.0), dwLod(1.0 / 22.0));
        float nMax = mix(0.5, (n1 + 0.4375) / 0.9375, C), bMax = mix(0.42, (b1 + 0.375) / 0.875, C);
        float tornMax = 1.0 + 0.5 * exp(-a / 0.25);
        float surfMax = top * (1.0 + DW_BILLOW * dwSoft(((bMax - 0.42) * 3.0 + (nMax - 0.5) * 2.0) * tornMax));
        if (q.y > max(surfMax + 0.4 * (0.22 * top + 4.0), surfMax * 1.12 + 4.0)) {
            gDwSurf = surfMax;
            float pf = q.y < top * 0.8 ? 0.0 : dwPuff(vec3(pw.x, q.y, pw.z), top, a);
            if (pf <= 0.0) {
                gDwWhy = 7.0;
                return F >= 0.999 ? 0.0 : (1.0 - F) * haze;
            }
            float taup = dwTransfer(law.y) * (1.0 + 0.5 * exp(-a / 1.1));
            float sigp = taup / dwProfileInt(h) * mix(1.0, mix(0.75, 1.25, 0.55 * s1 + 0.45 * s2), C) * dwNoiseGain(taup) * dwOpen(g);
            gDwSigma = sigp;
            gDwLow = 0.0;
            float texp = sigp * pf * (1.0 - smoothstep(ceiling - 8.0, ceiling, q.y)) * smoothstep(0.0, 0.08, a);
            return F >= 0.999 ? texp : mix(haze, texp, F);
        }
    }
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
    if (!coarse) {
        // the composition follows the billows too: the folds between lumps hold the coarse, earthier dust the lumps' outer
        // skin has shed (the texture's value: gaps low, lumps high), half and half with the height in the column
        gDwLow = 0.5 * gDwLow + 0.5 * (1.0 - clamp((n - 0.2) / 0.6, 0.0, 1.0));
    }
    // a soft top: billow tops thin out over ~a quarter of the layer (an opaque top popped where dips passed in and out of
    // view at grazing angles)
    float edge = 0.22 * top + 4.0;
    float prof = 1.0 - smoothstep(surf - edge, surf + 0.4 * edge, q.y);
    // the drawn column: the law's vertical optical depth through the one transfer (dwTransfer), laid in this profile
    // normalised over the settled layer (dwProfileInt); settling and dilution are the law's alone. The young head is denser
    // (presentation, take 33's: ×1.5 at birth, e-fold 1.1 s)
    float taud = dwTransfer(law.y) * (1.0 + 0.5 * exp(-a / 1.1));
    // over hard ground only debris dust (the pall's own reading: PallField.paved of the soil's mass), sand pales it
    float paved = 1.0 + gDwMatNow.z * (dwRow0(r).y - 1.0);
    // (the haze level read the open-air window already; it multiplies both)
    float sigma = taud / dwProfileInt(h) * paved * mix(1.0, mix(0.75, 1.25, 0.55 * s1 + 0.45 * s2), C)
            * (1.0 - smoothstep(DwRing.w * (1.0 - 1.0 / (DW_NODES - 1.0)), DwRing.w, r));
    if (!coarse && !mean) sigma *= dwNoiseGain(taud);
    if (!coarse) {
        sigma *= dwOpen(g);
        gDwSand = clamp(gDwMatNow.y / max(paved, 1e-6), 0.0, 1.0);
    }
    if (!coarse) gDwSigma = sigma;
    // coarse dust low and dense, finer above (stratified); a thin veil of fines over the uneven top (following it, no
    // shelf); here and there a puff lifted above the top that thins into the veil and drifts with the wind
    float strat = 1.25 - 0.5 * clamp(q.y / max(top, 1.0), 0.0, 1.0);
    float veil = (1.0 - smoothstep(surf, surf * 1.12 + 4.0, q.y)) * smoothstep(surf * 0.8, surf, q.y) * 0.03;
    float puff = coarse || mean || q.y < top * 0.8 ? 0.0 : dwPuff(vec3(pw.x, q.y, pw.z), top, a) * (1.0 - smoothstep(ceiling - 8.0, ceiling, q.y));
    // the lowest metres thin to the ground (soft where no terrain stands under the dust)
    float textured = sigma * smoothstep(0.0, 0.08, a) * smoothstep(0.0, gDwFloorSoft, q.y)
            * (prof * strat * (0.5 + 1.0 * n) * (0.7 + 0.6 * fs) + (veil + puff) * (0.6 + 0.8 * n));
    return F >= 0.999 ? textured : mix(haze, textured, F);
}

// extinction of the wave's matter at g (1/block): the lofted ground matter and a water burst's base surge, × the share of a
// source first drawn already aged that has faded in (DwRing.y: the share still to fade in; DustSources, 1 s)
float dwDensity(vec3 g, bool coarse) {
    gDwMatNow = vec3(0.0);
    float d = dwDustDensity(g, coarse);
    float wet = gDwMatNow.x;
    if (DwSurge.z > 0.0) {
        float sg = dwSurge(g, g.y - gDwFloorAt, coarse) * dwOpen(g);
        wet = (d * wet + sg) / max(d + sg, 1e-12);
        d += sg;
        if (!coarse) gDwSigma += sg;
    }
    if (!coarse) gDwWet = wet;
    return d * (1.0 - DwRing.y);
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
    return max(dwTopAt(p.xz) - (p.y - gDwFloorAt), 0.0) * gDwSigma * 0.8 + near;
}

// light of a dust sample at q (presentation, follows the light on ground-coloured dust): single scattering of the fireball
// and the sun (forward-peaked: edges toward the ball glow), and multiply scattered light that diffuses in from every lit face
// and takes on the soil's bulk colour (dense dust is darker inside but stays warm dust-coloured, never black); sky light
// enters from the top, ground bounce from below
// presentation: the sky light the dust takes, at the sky's luminance in the sun's colour (golden-hour haze: the reference's
// warm amber; a blue sky drew the low sun's dust as grey-blue smoke, its luminance alone as beige fog, saturation 0.15–0.27
// against the Silo frame's 0.45); white light at noon stays white
vec3 dwSkyOnDust() {
    float sky = dot(DwSkyLight, vec3(0.2126, 0.7152, 0.0722)), sun = dot(DwSunLight, vec3(0.2126, 0.7152, 0.0722));
    vec3 tint = sun > 1e-4 ? DwSunLight / sun : vec3(1.0);
    return sky * mix(vec3(1.0), tint, smoothstep(-0.05, 0.05, DwSunDir.y));
}

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
    vec3 w = mix(mix(PALL_DUST_W, PALL_SAND_W, gDwSand), PALL_MIST_W, gDwWet);
    float gp = mix(PALL_DUST_G, PALL_MIST_G, gDwWet), tc = max(gDwHazeTc, 1e-4), tau = clamp(gDwHazeAbove, 0.0, tc);
    float sunUp = smoothstep(-0.05, 0.05, DwSunDir.y), muS = max(DwSunDir.y, 0.12);
    vec3 fS = DwSunLight * sunUp;
    vec3 lb = normalize(DwBall.xyz - q + vec3(0.0, 1e-3, 0.0));
    float muB = clamp(lb.y, 0.12, 1.0);
    vec3 fB = dwBallLight(q);
    vec3 top = dwSkyOnDust();
    vec3 bottom = DwFire * gDwHazeFire * (1.0 - gDwWet);
    float c = gp * -rd.y;
    vec3 d = vec3(pallDiffuse(w.r, gp, tau, tc, PALL_LAND, muS, fS.r, muB, fB.r, top.r, bottom.r, c),
            pallDiffuse(w.g, gp, tau, tc, PALL_LAND, muS, fS.g, muB, fB.g, top.g, bottom.g, c),
            pallDiffuse(w.b, gp, tau, tc, PALL_LAND, muS, fS.b, muB, fB.b, top.b, bottom.b, c));
    vec3 single = 0.25 * (fS * dwHG(gp, dot(rd, DwSunDir)) * exp(-tau / muS) + fB * dwHG(gp, dot(rd, lb)) * exp(-tau / muB));
    return DW_HAZE_EXPOSURE * max(DwAdapt, 1.0) * w * (single + max(d, vec3(0.0)));
}

// presentation (est.), the dust's colour by what it is made of (moderate: the coarsest dust 15/21/30 % darker than the finest
// in red/green/blue; the textured level 1–3 % lower in luma and under a degree redder than one soil colour): the coarse, moister soil the blast tears up stays low and reads darker and earthier, the fines that
// reach the billow tops paler (size sorting: settling speed ∝ d², the column's coarse fraction falls with height, as `strat`
// lays its density); each ground point's soil differs a little (its seed, ~60 m patches: redder or greyer); dust from hard
// ground (roads, roofs, rock) is grey debris. Weighted by the column's density, the tint's mean is about 0.98/0.95/0.92.
// The composition follows the height in the column and the billows half and half (dwDustDensity: gDwLow).
// One soil colour draws the face as one tone whose rolls read only by light (chroma ±2.7 over the face against
// ±9.8 on the reference's wall, corr(L, C) ≈ 0)
const vec3 DW_EARTH = vec3(0.90, 0.84, 0.76), DW_FINES = vec3(1.06, 1.07, 1.08);
vec3 dwDustTint() {
    vec3 t = mix(DW_FINES, DW_EARTH, gDwLow) * mix(vec3(1.04, 1.0, 0.95), vec3(0.96, 0.99, 1.04), gDwSoil);
    return mix(vec3(1.0), t, 1.0 - gDwWet);
}
vec3 dwHardGrey(vec3 c) {
    return mix(c, vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))), 0.5 * gDwHard * (1.0 - gDwWet));
}

vec3 dwTexturedLight(vec3 q, vec3 rd) {
    vec3 lb = normalize(DwBall.xyz - q + vec3(0.0, 1e-3, 0.0));
    float taub, taus;
#ifdef DW_KEEP_MEDIUM_TAPS
    bool oneTap = DwDetail > 1.5;
#else
    bool oneTap = DwDetail > 0.5;
#endif
    float depth = max(gDwSurf - (q.y - gDwFloorAt), 0.0) * gDwSigma;
    // MEDIUM and LOW: one shadow tap, toward the brighter of the fireball and the sun; the other light takes its depth to the
    // layer's top along its own direction (closed form, no fetch; the slant floor as the haze light's). One depth for both
    // held their ratio over the face: one hue, the rolls read by luminance alone (step 8: chroma detail 2.7 against HIGH's two
    // taps 3.8). Where the lights are within ×1.5 of each other both taps are taken and the assignment blends, so the light
    // has no step where the brighter one changes (review step 8: a hard switch stepped 12.9/255 at the median, an arc
    // sweeping over the face as the ball cools; tools/uw_probe/onetap_flip.py)
    float wb = 1.0;
    if (oneTap) {
        float ball = dot(dwBallLight(q), vec3(1.0)), sun = dot(DwSunLight, vec3(1.0)) * smoothstep(-0.05, 0.05, DwSunDir.y);
        wb = smoothstep(0.4, 0.6, ball / max(ball + sun, 1e-6));
    }
    float tapb = !oneTap || wb > 0.0 ? dwShadow(q, lb) : 0.0;
    float taps = !oneTap || wb < 1.0 ? dwShadow(q, DwSunDir) : 0.0;
    if (oneTap) {
        taub = mix(depth / max(lb.y, 0.12), tapb, wb);
        taus = mix(taps, depth / max(DwSunDir.y, 0.12), wb);
    } else {
        taub = tapb;
        taus = taps;
    }
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
            + dwSkyOnDust() * (0.03 + 0.15 * exp(-0.3 * depth));
    vec3 tint = dwDustTint();
    vec3 albedo = dwHardGrey(mix(mix(DwAlbedo, DwSandAlbedo, gDwSand), DwMistAlbedo, gDwWet) * tint);
    vec3 bulk = dwHardGrey(mix(mix(DwBulk, DwSandBulk, gDwSand), DwMistBulk, gDwWet) * tint);
    gDwPrTaub = taub;
    gDwPrTaus = taus;
    gDwPrDepth = depth;
    gDwPrLift = lift;
    return DW_EXPOSURE * max(DwAdapt, 1.0) * (albedo * single + bulk * multiple + bulk * DwGroundLight);
}

// light of a sample: the textured level's (take 33's) and the haze level's, blended by the textured share
vec3 dwLight(vec3 q, vec3 rd) {
    vec3 haze = gDwF < 0.999 ? dwHazeLight(q, rd) : vec3(0.0);
    if (gDwF <= 0.001) return haze;
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

// longest stride over clear air inside the dust's bound, blocks
#ifdef DW_GAP_OVERRIDE
const float DW_GAP = DW_GAP_OVERRIDE;
#else
const float DW_GAP = 24.0;
#endif
// the clear stride inside the bound per light detail level (MEDIUM and LOW 36 blocks: the march's accuracy holds at each rung; 48 at LOW
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

// The ray's occupied intervals (the readers' prefix: wall_layer.glsl wallTo): A from the first occupied
// sample to the last one before the first clear gap longer than max(48, 0.1 t) blocks, B the rest (later gaps merge into B).
// Without such a gap A and B meet at the bend of the ray's optical depth (dwKnot): one homogeneous slab spread a dense wall's
// opacity over the thin haze in front of it up to the eye, so a reader inside the haze (a smoke quad 700 blocks out, the
// pall rays' end) saw 0.1 of transmission on a ray that stayed in the haze and 0.7 on its neighbour that left the haze's top
// first and so had a gap: jagged grey "spires" along the haze's top, flipping with the sample jitter (hotfix-pall, 28.11).
// gDwSplit: (A's exit, B's entry, the transmittance at A's exit, the luminance of the light in front of it); B empty while
// gDwInB is false and no knot is taken (dwMarch closes A at the ray's last occupied depth)
vec4 gDwSplit = vec4(0.0);
// the march's light in front of the tail segment (dwSegment)
vec3 gDwSegLight = vec3(0.0);
bool gDwInB = false;
float gDwLastEnd = -1.0;
const vec3 DW_LUMA = vec3(0.2126, 0.7152, 0.0722);
// knot candidates: where the transmittance in front of a sample first falls to each level (optical depth 0.03 to 3.84, each
// twice the last; above that 2 % shows through): depth, transmittance, light's luminance in front of it; levels 0-3 in the
// A vectors, 4-7 in the B ones (vectors, not arrays: an array indexed by the count spilled the march to scratch on AMD's
// compiler, 108 dwords against its 80)
const int DW_KNOTS = 8;
vec4 gDwKnotZA = vec4(0.0), gDwKnotZB = vec4(0.0), gDwKnotTA = vec4(0.0), gDwKnotTB = vec4(0.0), gDwKnotLA = vec4(0.0), gDwKnotLB = vec4(0.0);
int gDwKnots = 0;
// diagnostics (DustWallDiag.split false): the split at a clear gap alone
bool gDwKnotsOn = true;
// an occupied sample over [t, end] with the transmittance T and light in front of it
void dwInterval(float t, float end, float T, vec3 light) {
    if (gDwLastEnd >= 0.0 && !gDwInB && t - gDwLastEnd > max(48.0, 0.1 * t)) {
        gDwInB = true;
        gDwSplit = vec4(gDwLastEnd, t, T, dot(light, DW_LUMA));
    }
    // (levels 0.03 · 2^j of optical depth: T ≤ e^(−0.03 · 2^j); a sample crossing several levels takes the first)
    if (gDwKnots < DW_KNOTS && T <= exp(-0.03 * exp2(float(gDwKnots)))) {
        float lum = dot(light, DW_LUMA);
        if (gDwKnots < 4) {
            gDwKnotZA[gDwKnots] = t; gDwKnotTA[gDwKnots] = T; gDwKnotLA[gDwKnots] = lum;
        } else {
            gDwKnotZB[gDwKnots - 4] = t; gDwKnotTB[gDwKnots - 4] = T; gDwKnotLB[gDwKnots - 4] = lum;
        }
        gDwKnots += 1;
        while (gDwKnots < DW_KNOTS && T <= exp(-0.03 * exp2(float(gDwKnots)))) gDwKnots += 1;
    }
    gDwLastEnd = max(gDwLastEnd, end);
}
// The ray without a clear gap: A and B meet at the candidate farthest from the chord of the optical depth between the first
// occupied depth and the last (the profile's bend: thin haze in front of a dense wall, or a dense near layer in front of
// thin haze), so the readers' two homogeneous pieces follow the ray's own transmittance. Within 0.02 of the chord (a uniform
// slab) A stays whole. Continuous in the profile: two candidates trade places where they fit equally well
void dwKnot(float T) {
    float z0 = gDwDepth.x, z1 = gDwLastEnd, tau = -log(max(T, 1e-7));
    if (z1 <= z0) return;
    float best = 0.02;
    for (int j = 0; j < DW_KNOTS; j++) {
        if (j >= gDwKnots) break;
        vec3 kn = j < 4 ? vec3(gDwKnotZA[j], gDwKnotTA[j], gDwKnotLA[j]) : vec3(gDwKnotZB[j - 4], gDwKnotTB[j - 4], gDwKnotLB[j - 4]);
        if (kn.x <= z0 || kn.x >= z1) continue;
        float dev = abs(tau * (kn.x - z0) / (z1 - z0) + log(max(kn.y, 1e-7)));
        if (dev > best) {
            best = dev;
            gDwSplit = vec4(kn.x, kn.x, kn.y, kn.z);
        }
    }
}

// Several sources on one ray (DwSourceCount, at most DW_FRAME_SOURCES): each source's ray interval (its slab of ground and
// envelope, its disc of dust), and the sum of their extinction and extinction-weighted light at a sample. A source's light is
// finished before the next is loaded (its globals: dwDensity's surface, share, material). Per ray the DW_RAY_SOURCES nearest
// sources whose interval it crosses are drawn in full; any further ones by their noise-free column (the haze's mean: lower
// detail, never ending before its law; the bounded per-pixel work)
const int DW_FRAME_SOURCES = 16, DW_RAY_SOURCES = 8;
vec2 gDwSpan[16] = vec2[16](vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0),
        vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0));
// whether the ray draws source k in full (one of its DW_RAY_SOURCES nearest crossings)
bool gDwFull[16] = bool[16](true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true);
// the density of the loaded source at q: in full, or its noise-free column when this ray takes it as a far extra
float dwSourceDensity(vec3 q, int k, float floorAt) {
    gDwFloorAt = floorAt;
    bool was = gDwMean;
    if (!gDwFull[k]) gDwMean = true;
    float s = dwDensity(q, false);
    gDwMean = was;
    return s;
}
// the interval [t0, t1] of the ray rd from the eye through the loaded source's dust (its vertical slab and disc), up to tEnd
vec2 dwRaySpan(vec3 rd, float tEnd) {
    vec3 eye = DwEye.xyz;
    float lo = DwRelief.x - 1.0, hi = DwRelief.y + DwRing.z;
    float t0 = 0.0, t1 = tEnd;
    if (abs(rd.y) < 1e-5) {
        if (eye.y > hi || eye.y < lo) return vec2(1.0, 0.0);
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
        if (C > 0.0) return vec2(1.0, 0.0);
    } else {
        float disc = Bq * Bq - A * C;
        if (disc < 0.0) return vec2(1.0, 0.0);
        float sq = sqrt(disc);
        t0 = max(t0, (-Bq - sq) / A);
        t1 = min(t1, (-Bq + sq) / A);
    }
    return vec2(t0, t1);
}
// the loaded source's tuft for this ray (T6): at its column's ground point, from the swell field's crest there (4 fetches)
void dwRayTuft(int k, vec3 rd) {
    gDwTuft = 0.0;
    vec3 eye = DwEye.xyz;
    float re = length(eye.xz), ae = DwEye.w - dwArrival(re);
    float band = 1.4 * max(dwLaw(re, ae).x, 20.0), hl = length(rd.xz);
    // (only an eye above the layer sees the far top edge-on: from inside it every downward ray's column point clamped to
    // 600 m and the 1 km lift raised the dust in front of the camera by screen column, vertical bands, Silo take 24.11)
    if (hl > 1e-3 && eye.y > band) {
        float dc = rd.y < -1e-4 ? (eye.y - band) / -rd.y * hl : 8000.0;
        // (a ray meeting the band nearer than ~1 km looks down into the near dust, not at the far top edge-on: no tuft there.
        // Clamped to 600 m, every such ray took the tuft of its azimuth's column point and lifted the near dust by screen
        // column: vertical bands down to the frame's foot)
        float far = smoothstep(600.0, 1200.0, dc);
        dc = clamp(dc, 600.0, 8000.0);
        vec2 gc = dwSource(eye.xz + rd.xz / hl * dc);
        float rc = length(gc), ac = DwEye.w - dwArrival(rc);
        if (ac > 8.0 && dwTextured(ac) > 0.001) {
            float L;
            dwSwellField(gc, ac, L);
            float crest = smoothstep(DW_TUFT_LO, DW_TUFT_HI, L);
            gDwTuft = far * DW_TUFT * crest * sqrt(crest) * (1.0 - exp(-(ac - 8.0) / 15.0)) * (0.5 + 0.5 * dwRow0(rc).z);
            gDwTuftD = dc;
#ifdef DW_DIAG
            gDwTuftTop = dwLaw(rc, ac).x;
            gDwTuftCrest = L;
#endif
        }
    }
#ifdef DW_SOURCES
    gDwTuftK[k] = gDwTuft;
    gDwTuftDK[k] = gDwTuftD;
#endif
}
// extinction (1/block) of every source covering ray distance tt, and their light weighted by it (premultiplied by
// extinction): x the sum, rgb of the second the light sum. mean: the noise-free column
vec4 dwSamples(vec3 rd, float tt, int sources) {
    float s = 0.0;
    vec3 c = vec3(0.0);
    for (int k = 0; k < sources; k++) {
        if (tt < gDwSpan[k].x || tt > gDwSpan[k].y) continue;
        dwLoad(k);
        vec3 q = DwEye.xyz + rd * tt;
        float sk = dwSourceDensity(q, k, dwFloor(q.xz));
        gDwTailEvals += 1;
        if (sk <= 1e-7) continue;
        c += sk * dwLight(q, rd);
        s += sk;
    }
    return vec4(c, s);
}

// the noise-free column integrated over [ta, tb] along the ray in n Gauss–Legendre segments of 4 nodes, light at each node:
// the thin tail past the step cap. Returns premultiplied light; T and the depth moments advance
vec3 dwSegment(vec3 rd, float ta, float tb, int n, inout float T, vec3 airLight, int sources) {
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
            vec4 v = dwSamples(rd, tt, sources);
            float sg = v.a;
            if (sg <= 1e-7) continue;
            float al = exp(-sg * dt);
            vec3 c = v.rgb / sg;
            float air = dwAirT(tt, rd.y);
            dwInterval(tt, tt + dt, T, gDwSegLight + light);
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

// source m's part of a march sample at ray distance tt (dwMarch): none / above its bound (kNext) / clear inside / matter
// the march's search for a span's first dense cell (dwMarch): extinction only, no light. The search runs through the march's
// one call of the density: every call site inlines the whole density (on AMD a second one took the program from 114 to 137 KB)
bool gDwDensityOnly = false;
void dwAccumulate(int m, vec3 rd, float tt, float t, float k, float n, inout float s, inout vec3 c, inout bool none,
        inout bool inside, inout float kNext, inout float hqMax) {
    dwLoad(m);
    vec3 q = DwEye.xyz + rd * tt;
    float bound = dwBound(q);
    if (bound < 0.0) return;
    none = false;
    float fl = dwFloor(q.xz), hq = q.y - fl;
    if (hq > bound) {
        // above the dust here, going down: its bound over the highest ground of the map (rising ground ahead may lift the
        // dust toward the ray), onto the next grid cell (a stride that lands in dust backs up)
        float next = k + n;
        if (rd.y < 0.0) next = max(k + 1.0, ceil(dwGridK(t + max(q.y - DwRelief.y - bound, 0.0) / -rd.y)));
        kNext = min(kNext, next);
        return;
    }
    inside = true;
    float sm = dwSourceDensity(q, m, fl);
    if (sm > 1e-5) {
        if (!gDwDensityOnly) c += sm * dwLight(q, rd);
        s += sm;
        hqMax = max(hqMax, hq - gDwSurf);
    }
}

// march of one ray from the eye (direction rd, unit) up to tEnd blocks (the world behind the pixel); jitter in [0, 1);
// foot: pixel size per block of distance. Returns premultiplied light and transmittance.
// Samples sit on a grid fixed along the ray from the eye (geometric steps from the eye, independent of where the
// dust starts), so they do not slide through the texture as the front grows; every sample and span ends at tEnd. The ray
// covers the whole supported distance: empty air is skipped in growing strides, thin dust is taken in growing spans, and
// what the step cap leaves of the ray is integrated from the noise-free column (no far haze is dropped).
// Every source of the frame (DwSourceCount) is marched on the same grid: a sample sums the sources whose interval covers it.
vec4 dwMarch(vec3 rd, float tEnd, float jitter, float foot, int maxSteps) {
    gDwSamples = 0;
    gDwIters = 0;
    gDwTailEvals = 0;
    gDwKinds = vec4(0.0);
    gDwCapLeft = 0.0;
    gDwTailTau = 0.0;
    gDwInB = false;
    gDwLastEnd = -1.0;
    gDwSplit = vec4(0.0);
    gDwKnots = 0;
    int sources = int(min(DwSourceCount, float(DW_FRAME_SOURCES)));
    float t0 = 1e30, t1 = 0.0;
    int crossed = 0;
    for (int k = 0; k < sources; k++) {
        dwLoad(k);
        vec2 span = dwRaySpan(rd, tEnd);
        gDwSpan[k] = span;
        gDwFull[k] = true;
        if (span.x >= span.y) continue;
        crossed += 1;
        t0 = min(t0, span.x);
        t1 = max(t1, span.y);
        dwRayTuft(k, rd);
    }
    // more crossings than DW_RAY_SOURCES: the ones entered last take their noise-free column
    if (crossed > DW_RAY_SOURCES) {
        for (int k = 0; k < sources; k++) {
            if (gDwSpan[k].x >= gDwSpan[k].y) continue;
            int nearer = 0;
            for (int m = 0; m < sources; m++) {
                if (gDwSpan[m].x < gDwSpan[m].y && (gDwSpan[m].x < gDwSpan[k].x || gDwSpan[m].x == gDwSpan[k].x && m < k)) nearer += 1;
            }
            gDwFull[k] = nearer < DW_RAY_SOURCES;
        }
    }
    if (t0 >= t1) return vec4(0.0, 0.0, 0.0, 1.0);
    vec3 light = vec3(0.0), airLight = dwAirLight(rd);
    float tMarch = t1;
    // sample grid fixed along the ray from the eye: 3.5-block steps to 3.5/K blocks, then geometric (step K × distance:
    // 6.5 m at 1 km, 26 m at 4 km); the history averages the moving offset within each step (a coarser grid, averaged,
    // lost the billows' fine detail)
    float k = floor(dwGridK(t0));
    // n: cells to the next sample (strides grow over empty air and thin dust); kLast: the last sampled cell
    float n = 1.0, kLast = k - 1.0;
    float T = 1.0, t = t0;
    bool inDust = false, capped = false;
    // the state before the last multi-cell thin span (the veil over the top, or thin dust whose span grew). The span's own
    // thin sample says nothing about the cells it covers: near the wall's leaning face a 14-28 m span from the veil over the
    // billowed top lands up to 18 m deep in the layer, past the lit skin (light 0.10 there against 0.32 in the skin; a third
    // of the face's rays with the front 120 m away), and which ray skips moves with its per-frame offset: the history keeps
    // those 3:1 jumps as dark specks. A dense sample right after such a span searches the span's cells for the first dense
    // one by extinction alone (searchTo), goes back, and takes the thin sample again with its span ending there (capK): the
    // skin is sampled at its own cell, for a few density evaluations, not a lit sample per cell
    float spanK = -1.0, spanT = 1.0, spanEnd = -1.0, spanSamples = 0.0, capK = -1.0, searchTo = -1.0;
    vec3 spanLight = vec3(0.0), spanDepth = vec3(0.0);
    vec4 spanSplit = vec4(0.0);
    bool spanInB = false;
    int spanKnots = 0;
    // the step cap counts occupied samples (each with its light), not strides over clear air: a cap on every stride
    // handed the far thin spans to the noise-free tail, which saw none of them (the march's accuracy: 0.29 at HIGH)
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
        gDwFoot = max(tt * foot, dt * 0.3);
        // every source here: no dust at its radius (none), the ray above its bound (the next cell it may enter), clear inside
        // its bound, or matter (its extinction and light); one source (the common case) without the per-source arrays
        float s = 0.0, hqMax = -1e9;
        vec3 c = vec3(0.0);
        bool none = true, inside = false;
        float kNext = 1e30;
        for (int m = 0; m < sources; m++) {
            if (tt < gDwSpan[m].x || tt > gDwSpan[m].y) continue;
            dwAccumulate(m, rd, tt, t, k, n, s, c, none, inside, kNext, hqMax);
        }
        if (searchTo >= 0.0) {
            // the search for the span's first dense cell (below), one cell per pass, extinction only
            bool hit = s * dt >= mix(0.1, 0.01, gDwF);
            if (!hit && k + 1.0 < searchTo) {
                k += 1.0;
                continue;
            }
            float first = hit ? k : searchTo;
            gDwDensityOnly = false;
            if (first < searchTo) {
                // back to the span's start: its thin sample again, its span ending at that cell
                k = spanK; T = spanT; light = spanLight; gDwDepth = spanDepth; gDwLastEnd = spanEnd; gDwInB = spanInB;
                gDwSplit = spanSplit; gDwSamples = int(spanSamples); gDwKnots = spanKnots;
                capK = first;
            } else {
                k = searchTo;
            }
            kLast = k - 1.0; n = 1.0;
            spanK = -1.0;
            searchTo = -1.0;
            continue;
        }
        if (none) {
#ifdef DW_DIAG
            gDwKinds.x += 1.0;
#endif
            // no dust at this radius: long strides, never past another source's span entry ahead (a short span, a small
            // burst or a steep ray through a far slab, is never stepped over)
            float kn = k + n;
            for (int m = 0; m < sources; m++) {
                if (gDwSpan[m].x > tt && gDwSpan[m].x < gDwSpan[m].y) kn = min(kn, max(floor(dwGridK(gDwSpan[m].x)), k + 1.0));
            }
            kLast = k; k = kn; n = min(n * 2.0, 16.0); inDust = false;
            spanK = -1.0;
            capK = -1.0;
            continue;
        }
        if (!inside) {
#ifdef DW_DIAG
            if (rd.y < 0.0) gDwKinds.z += 1.0; else gDwKinds.y += 1.0;
#endif
            kLast = k;
            k = rd.y < 0.0 ? kNext : k + n;
            n = rd.y < 0.0 ? 1.0 : min(n * 2.0, 8.0);
            inDust = false;
            spanK = -1.0;
            capK = -1.0;
            continue;
        }
        if (s > 1e-5) {
            if (!inDust && k - kLast > 1.0) {
                // a stride from empty air landed in dust: single cells from just after the last empty sample
                k = kLast + 1.0; n = 1.0;
                kLast = k - 1.0;
                inDust = true;
                continue;
            }
            bool dense = s * dt >= mix(0.1, 0.01, gDwF);
            if (dense && spanK >= 0.0 && k - spanK > 1.0) {
                // a multi-cell thin span ended in dense dust: search its cells for the first dense one (extinction only)
                searchTo = k;
                k = spanK + 1.0;
                gDwDensityOnly = true;
                continue;
            }
            inDust = true;
            gDwSamples += 1;
            c /= s;
            // the thin soft edge, veil and puffs over the dense top: four cells per sample (at grazing views they are
            // hundreds of metres of ray); thin dust: the span grows while each cell holds little optical depth. A dense
            // sample spans its own cell: with the span grown over the thin samples before it (7 m), the sample where a ray
            // enters the wall's face stood for the lit skin and the next one landed 9-17 blocks deep in its shadow
            float cells = dense ? 1.0 : max(hqMax > 2.0 ? 4.0 : 1.0, n);
            bool cut = capK > k;
            if (cut) cells = min(cells, capK - k);
            capK = -1.0;
            spanK = -1.0;
            if (cells > 1.0 && !dense && !cut) {
                spanK = k; spanT = T; spanLight = light; spanDepth = gDwDepth; spanEnd = gDwLastEnd; spanInB = gDwInB;
                spanSplit = gDwSplit; spanSamples = float(gDwSamples - 1); spanKnots = gDwKnots;
            }
            float span = min(dwGridT(k + cells), tMarch) - t;
            float al = exp(-s * span), air = dwAirT(tt, rd.y);
            if (float(gDwSamples - 1) == gDwProbeK) {
                gDwProbeA = vec4(s, dot(c, DW_LUMA), T, tt);
                gDwProbeB = vec4(span, gDwPrTaub, gDwPrTaus, gDwPrDepth);
                gDwProbeC = vec4(0.0, gDwPrLift, gDwF, hqMax);
            }
            dwInterval(t, t + span, T, light);
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
#endif
            // inside the dust's bound but clear here: strides no longer than DW_GAP blocks, so a thin span the bound admits
            // (a swell's crest, another source's layer) is never stepped over (the march's accuracy: five 50-block spans were missed at LOW)
            kLast = k; k += n; n = min(n * 2.0, max(1.0, floor(dwGap() / max(dt, 1e-3))));
            inDust = false;
            spanK = -1.0;
            capK = -1.0;
        }
    }
    gDwDensityOnly = false;
    // the rest of the ray past the step cap (the thin tail), from the noise-free column
    if (T >= 0.01 && capped) {
        float from = t;
        if (from < t1) {
            float before = T;
            gDwSegLight = light;
            light += dwSegment(rd, from, t1, 8, T, airLight, sources);
            gDwCapLeft = before - T;
        }
    }
    // no clear gap: A is the whole occupied span, or A and B meet at the profile's bend
    if (!gDwInB) {
        gDwSplit = vec4(gDwLastEnd, gDwLastEnd, T, dot(light, DW_LUMA));
        if (gDwKnotsOn) dwKnot(T);
    }
    return vec4(light, T);
}
