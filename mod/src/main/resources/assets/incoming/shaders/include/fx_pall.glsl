#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:smoke.glsl>

// Post-blast pall (client/fx/layer/PallPass): eye ray through a low-resolution pixel to the world behind it (SceneDepth,
// including Distant Horizons LODs; no world — terrain under pall, PallGround), or pall bounds exit. Steps denser near
// the start (quadratic), offset by blue noise across frames (accumulation smooths grain, fx_pall_resolve). Density is
// tabulated burst field × wind-carried 3D noise billows: coarse, with fine detail where the ray resolves it. Light
// (client/nuclear/PallLight): pall above/below point is a flat slab, with Eddington two-stream transfer after
// delta-Eddington: sun and ball light (black body, as mushroom body) as beams from above, sky above, fires below dust
// (dust-wall light: pall continues its color at front) from below, ground beneath slab; local billows (noise samples
// toward key light) cause shadow or gap: deeper into slab or nearer edge. Toward eye, beams scatter with
// Henyey–Greenstein phase, diffuse light by Eddington; material uses dust, smoke, haze and mist albedo ω' and asymmetry
// g' (pall.glsl): thin pall glows by optical depth, thick pall has dust/spray wall color; light in dense dust decays
// slower than a direct beam. Light in linear shares (transfer linear in radiance); Minecraft frame uses display values:
// output pall material color (light / opacity) converted to display (pallDisplay), with film-like shoulder above 0.6.
// Display colors (dust/spray wall, soot, flame) converted to linear (pallLinear); fire reflection/glow added to display
// skylight uses conversion difference (pallLift); fire light no brighter than flame itself (pallFlame).
// Frame mushrooms (mushroom.glsl, MushroomField) share the ray: pall and mushroom steps interleave by distance, each
// step's light attenuating everything behind it. Mushroom is a non-tabulated field: cap material (fireball from frame
// one, rolling into its ring as one shape), stem, skirt (water burst also water column and crown), edges billowing with
// the same noise; light uses the same Eddington two streams in two slabs through the point (along sun — its beam,
// stem/skirt/column also ball beam; vertically — sky above, ground/fires below), with mean mushroom optical depth toward
// light, plus black-body emission by temperature (source function at depth is black-body radiance itself; near edge,
// with ω' share not supplied from inside).
// Output rgb is display pall color × opacity (pall light before world); a is world-light transmittance (extinction only:
// layer quads also use it, fx.fsh, where pall color is rgb / (1 − a); world pall shadow is in fx_pall_composite).
// Above-white light moves toward white (CROSS: incandescent is white-yellow, not saturated orange); overexposure becomes
// pixel coverage over the background behind matter (end of main).

// world distance behind pixel along view axis (full resolution)
uniform sampler2D Sampler0;
// Lifted dust light: azimuth × (radius, height, mushroom), this frame's prepass.
uniform sampler2D Sampler9;
uniform int PallFootReady;
// field table (PallField.row): 6 rows — dust, water spray and haze for two slots
uniform sampler2D Sampler1;
// 64×64 blue noise (BlueNoise)
uniform sampler2D Sampler2;
// billows (R), fine edges (G), seamless tile (VolumeNoise); own texture unit — PallPass
uniform sampler3D PallNoise;
// outdoor air near eye (pall.glsl: pallOpen, client/fx/layer/OpenAir); own texture unit
uniform sampler3D PallOpen;
uniform vec4 PallOpenAt;
uniform vec3 PallOpenWrap;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
// frame air (pall.glsl: pallAir)
uniform vec4 PallAir;
uniform mat4 PallSlot0;
uniform mat4 PallSlot1;
uniform vec3 PallMin;
uniform vec3 PallMax;
// sun direction (xyz), daytime sun strength 0..1 (w)
uniform vec4 PallSun;
// sun/sky light, linear (white wall at noon — 1); fire glow on a white wall beneath haze is in display values
uniform vec3 PallSunLight;
uniform vec3 PallSkyLight;
uniform vec3 PallFireLight;
// frame display skylight (white wall: day 1, night as wall billows, FarView.ambient): dust wall adds fire reflection
// on screen, pall adds its linear difference (pallLift)
uniform float PallBase;
// slot pall ball/fire light (CloudPuffs.pallLight): x cap radius (ball radius while a ball), y fire incandescence,
// z cap-center downwind drift, w fire zone edge (blocks); Hot is black-body radiance at visible cap temperature
// (rgb, linear, relative to noon white wall, without eye adaptation), cap center height above ground (w, blocks)
uniform vec4 PallGlow0;
uniform vec4 PallGlow1;
uniform vec4 PallHot0;
uniform vec4 PallHot1;
// reflection strength on smoke at frame skylight (CloudPuffs.glowOnSmoke)
uniform float PallGlowOnSmoke;
// x frame index (step jitter), y steps, z frame downscale factor, w eye dust extinction (1/block)
uniform vec4 PallFrame;
// vertical dust depth at eye: x above, y below to ground (NukePall.tick); with PallFrame.w determines ray reach (pallClip)
uniform vec2 PallEyeTau;
// firestorm (client/nuclear/FireStorm, pall.glsl): slot fire maps (R flame, G column smoke, B G × rise;
// mips aggregate sources), fire slots, storm bounds (denser ray steps there); frame whirls (up to four, per column:
// base/core radius; height/strength/seconds/swirl; tilt/flame/seed — PallPass.Frame)
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform mat4 PallFire0;
uniform mat4 PallFire1;
uniform vec3 PallFireMin;
uniform vec3 PallFireMax;
uniform mat4 PallWhirlPos;
uniform mat4 PallWhirlShape;
uniform mat4 PallWhirlLean;
// terrain/water under slot pall (client/nuclear/PallGround, pall.glsl: pallFloor, pallWater), maps and parameters;
// frame world ends no closer than PallFloorFrom horizontal blocks from eye
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
uniform float PallFloorFrom;
// slot water (pall.glsl): base surge and whether water exists beneath pall
uniform vec4 PallWater0;
uniform vec4 PallWater1;
// slot dust wall (pall.glsl, client/nuclear/BlastWall): rolling head and Wilson cloud
uniform mat4 PallWall0;
uniform mat4 PallWall1;
uniform vec4 PallWilson0;
uniform vec4 PallWilson1;
// frame mushrooms (MushroomField): mushroom and black-body light tables (mushroom.glsl)
uniform sampler2D Sampler7;
// sun mushroom shadow map (fx_mush_shadow, mushShadow): at night/without mushrooms, table contains no tiles
uniform sampler2D Sampler8;
// fireball surface pattern (NukeBallSurface): u, v octahedral direction map from ball center, third axis frames by rise
// path; 3D texture undeclared in JSON, bound manually (as PallNoise)
uniform sampler3D PallBall;
// texture pattern is p / BALL_P_MAX (NukeBallSurface.P_MAX)
const float BALL_P_MAX = 2.0;
// perceived light gain at frame skylight (Sight.adapted): mushroom emission in frame values
uniform float PallExposure;
// conventional blast smoke (smoke.glsl): eye position within billow noise tile modulo SMOKE_TILE (xyz), time s (w);
// smoke wind, blocks/s (xyz, at matter altitude of hundreds of blocks), two-phase noise period s (w); fireballs lighting
// smoke (as FxLights): column i eye-relative center/radius (0 absent), surface white-wall light (rgb, linear, eye-adapted)
uniform vec4 SmokeNoise;
uniform vec4 SmokeWind;
uniform mat4 SmokeBalls;
uniform mat4 SmokeBallLight;

out vec4 fragColor;

// Thick-layer color (determines material albedo, pall.glsl: PALL_DUST_W etc.): dust as dust wall, smoke, haze;
// mist white as spray wall over water (CloudPuffs.SPRAY: water pall continues its color)
const vec3 DUST = vec3(0.60, 0.53, 0.45);
const vec3 SMOKE = vec3(0.30, 0.27, 0.24);
const vec3 HAZE = vec3(0.44, 0.41, 0.38);
const vec3 MIST = vec3(0.902, 0.918, 0.929);
// fire flame beneath wall (CloudPuffs.FIRE)
const vec3 FIRE = vec3(1.0, 0.541, 0.180);
// firestorm/whirl column smoke: soot (thick-layer color; albedo PALL_SOOT_W); SOOT_LINEAR is its linear form (pallLinear)
const vec3 SOOT = vec3(0.21, 0.19, 0.17);
const vec3 SOOT_LINEAR = vec3(0.0322762, 0.0258975, 0.0202762);
// whirl flame hotter than city flame: yellower (display; WHIRL_FIRE_LINEAR in linear)
const vec3 WHIRL_FIRE = vec3(1.0, 0.58, 0.2);
const vec3 WHIRL_FIRE_LINEAR = vec3(1.0, 0.3016766, 0.0289912);
// regional glow share (fireAround): rest is glow beneath point, already covering half the sky near ground in the zone
const float AROUND = 0.6;
// terrain search steps for void ray (pallFloor): distant map cell no smaller than step
const int FLOOR_STEPS = 24;
// wind near whirl core, blocks/s (real whirls 10–50 m/s; faster is blurred rotation on screen)
const float WHIRL_SPEED = 15.0;
// above-white luminance adds this share of excess as white to all channels: film/eye overexposure
const float CROSS = 0.15;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// mushroom optical depth toward light: four samples at path fractions (segment midpoints), weighted by segment fractions
const vec4 MUSH_AT = vec4(0.025, 0.1, 0.275, 0.7);
const vec4 MUSH_PART = vec4(0.05, 0.1, 0.25, 0.6);

// frame mushrooms and ray interval in each
vec2 gMushSpan[MUSHROOMS];
int gMushes;
vec3 gDir;
int gFoot0, gFoot1;
vec2 gFootSpan0, gFootSpan1;
const int FOOT_LIGHT_AZ = 64;
const int FOOT_LIGHT_R = 16;
const int FOOT_LIGHT_H = 32;


float hg(float g, float c) {
    float k = 1.0 + g * g - 2.0 * g * c;
    return (1.0 - g * g) / (k * sqrt(k));
}

// Henyey–Greenstein phase for an extended disk source with angular-radius cosine mu0: along axis, use cone-averaged
// scattering rather than point-source peak, 2F/(1 − mu0), F = (1 − g²)/(2g)·(1/(1 − g) − 1/√(1 + g² − 2g·mu0))
// (phase integral over cone); farther off-axis, phase itself. A ball beside its Wilson shell spans tens of degrees;
// point-like droplet peak (g 0.85: 82 times mean) made the shell around the ball several times brighter than it
float hgDisc(float g, float c, float mu0) {
    float p = hg(g, c);
    if (1.0 - mu0 < 1e-3) return p;
    float f = (1.0 - g * g) / (2.0 * g) * (1.0 / (1.0 - g) - 1.0 / sqrt(1.0 + g * g - 2.0 * g * mu0));
    return min(p, 2.0 * f / (1.0 - mu0));
}

// Coarse-noise tile warp (tile fractions): tile repeats every tile blocks, visible as bands from above; smooth warp
// up to one sixth of a tile over two tiles breaks repetition (more stretches billows into bands), preserving value
// distribution (same noise elsewhere, mean billow shape remains 1)
vec3 warpAt(mat4 S, vec3 p) {
    vec3 q = vec3(p.x - S[0].x - S[1].x, 0.0, p.z - S[0].y - S[1].y) / S[3].y;
    vec2 w = texture(PallNoise, q * 0.07 + vec3(0.31, 0.5, 0.67)).rg - 0.5;
    return vec3(w.x, 0.0, w.y) * 0.3;
}

// Slot billows are a density multiplier averaging 1: noise drifts with dust, slowly billowing upward. Cascade: coarse
// tile billows (pallShape), fine billows inside at quarter tile size (pallDetail), while ray resolves them (fx_pall:
// slot, fine). Same field at any eye position — more detail nearby, not different billows.
// Point p (eye-relative) in noise tile
vec3 clumpUv(mat4 S, vec3 p, vec3 warp) {
    float tile = S[3].y, t = S[3].z;
    return vec3(p.x - S[0].x - S[1].x, (p.y - S[0].z) * 1.3 - 0.3 * min(t, 600.0), p.z - S[0].y - S[1].y) / tile + warp;
}

// coarse billows at tile position q
float coarseAt(mat4 S, vec3 q, float fill) {
    float tile = S[3].y, t = S[3].z;
    float r = texture(PallNoise, q).r;
    float g = texture(PallNoise, q * 3.1 + vec3(0.37, 0.11 * min(t, 600.0) / tile, 0.71)).g;
    return pallShape(vec2(r, g), fill);
}

// Pattern backflow near stem: ground air moves inward, then upward. Path length stemRise; turn direction and weak
// swirl are presentation: no vortex measurements. Two overlapping path segments (Vlachos 2010) keep the map from
// stretching into smooth arcs. Zero offset outside inflow zone — previous carpet and haze.
vec4 carpetFlow(Mush m, mat4 S, vec3 p) {
    float h = p.y - m.base.y;
    if (h < 0.0 || h >= m.footMerge) return vec4(0.0);
    vec3 q = mushLocal(m, p);
    float outer = 2.0 * m.stemR + 2.0 * m.stemFlare + 2.0 * m.footTop;
    if (any(greaterThan(abs(q.xz), vec2(outer)))) return vec4(0.0);
    float rho = length(q.xz), r = mushStemAt(m, q.y);
    float share = mushFootInfluence(q.y, rho, r, m.footTop, m.footShare, m.footMerge);
    if (share <= 0.0) return vec4(0.0);
    vec2 radial = q.xz / max(rho, 1.0);
    // Shift capped at a third of a tile (Valve, Water Flow 2010): otherwise map loses texture, stretching into arcs.
    float up = mushFootUp(q.y, m.footTop), cycle = max(S[3].y / 3.0, 1.0);
    float phase = m.stemRise / cycle + 0.5 * texture(PallNoise, vec3(q.x, 0.0, q.z) / (8.0 * cycle) + vec3(0.31, 0.23, 0.67)).r;
    vec2 inward = radial + 0.5 * MUSH_FOOT_TURN * vec2(-radial.y, radial.x);
    return vec4(vec3(inward.x * (1.0 - up), -up, inward.y * (1.0 - up)) * (cycle * share), phase);
}

// Cylinder checked once per pixel; outside it, original coordinates without looping over mushrooms.
vec4 carpetFlowAt(mat4 S, vec3 p) {
    if (gFoot0 < 0 && gFoot1 < 0) return vec4(0.0);
    float t = dot(p, gDir);
    if (S[0].x == PallSlot0[0].x && S[0].y == PallSlot0[0].y) {
        if (gFoot0 >= 0 && t >= gFootSpan0.x && t <= gFootSpan0.y) return carpetFlow(mushLoad(Sampler7, gFoot0), S, p);
    } else {
        if (gFoot1 >= 0 && t >= gFootSpan1.x && t <= gFootSpan1.y) return carpetFlow(mushLoad(Sampler7, gFoot1), S, p);
    }
    return vec4(0.0);
}

void carpetUv(mat4 S, vec3 p, vec3 warp, vec4 flow, out vec3 a, out vec3 b, out float blend) {
    a = clumpUv(S, p, warp);
    b = a;
    blend = 1.0;
    if (dot(flow.xyz, flow.xyz) < 1e-6) return;
    // clumpUv is an affine map. Both phases and all shadow samples reuse one evaluation.
    vec3 shift = flow.xyz * vec3(1.0, 1.3, 1.0) / S[3].y;
    b += shift * fract(flow.w + 0.5);
    a += shift * fract(flow.w);
    blend = mushLayerWeight(fract(flow.w));
}

float carpetCoarse(mat4 S, vec3 a, vec3 b, float blend, float fill) {
    float raw = coarseAt(S, a, fill);
    if (blend >= 1.0) return raw;
    return mix(coarseAt(S, b, fill), raw, blend);
}

// Dust-top bumps at p (PallField.BUMPS): thermals over heated ground at three times billow spacing (convective layer —
// about one and a half heights), drifting with dust, changing over an eddy turnover (~minute, DustModel.RISE_S:
// quarter noise tile per 60 s of game time, not model time: small effects scales accelerate model clocks, estimate);
// 1 ± PALL_BUMPS, mean 1 (uniform channel). Top, not holes: hollow between bumps is still layer top, lit like top
// (pallField: same layer optical depth above point)
float topBumps(mat4 S, vec3 p) {
    float tile = S[3].y, t = S[3].z;
    vec2 q = vec2(p.x - S[0].x - S[1].x, p.z - S[0].y - S[1].y) / tile;
    float n = texture(PallNoise, vec3(q.x * 0.3 + 0.41, 0.23 + t * (0.25 / 60.0), q.y * 0.3 + 0.17)).r;
    return 1.0 + PALL_BUMPS * (2.0 * n - 1.0);
}

// fine billows at tile position q (coarse multiplier)
float fineAt(vec3 q) {
    return pallDetail(textureLod(PallNoise, q * 4.0 + vec3(0.53, 0.29, 0.17), 0.0).r);
}

float carpetFine(vec3 a, vec3 b, float blend) {
    float raw = fineAt(a);
    if (blend >= 1.0) return raw;
    return mix(fineAt(b), raw, blend);
}

// Optical-depth deviation from mean toward light for one billow scale (size scale/8): samples at point (a), 0.06 and
// 0.22 scale toward light (b, c), weighted over 0.15 scale (farther billows are unrelated, their mean shadow is in the
// column above point); scale fractions × extinction
float clumpShade(float a, float b, float c) {
    return clamp(0.02 * (a - 1.0) + 0.06 * (b - 1.0) + 0.07 * (c - 1.0), -0.12, 0.24);
}

struct Sample {
    // local billowing pall: extinction (1/block), Σ extinction × albedo ω' (per channel), Σ extinction × asymmetry g'
    float sigma;
    vec3 albedo;
    float phase;
    // bottom fire light on slot haze (PallField.row): Σ extinction × ω' × linear fire light
    vec3 fire;
    // tabulated slab above point (no noise): depth above, whole-slab depth (pallField: slab), material (Σ depth × ω', × g'),
    // water beneath (Σ depth × water share)
    float tau;
    float slab;
    vec3 slabAlbedo;
    float slabPhase;
    float water;
    // ball/fire light (CloudPuffs.pallLight), linear: ball beam (rgb on white wall), Σ beam strength × vertical cosine and
    // × view-ray cosine; fires below dust give bottom light (rgb)
    vec3 cap;
    float capUp;
    float capView;
    // how much deeper ball diffuse light lies than sun/sky light: in wall head, beneath its ball-lit side (wall)
    float capDeep;
    vec3 burn;
    // local/neighbor billow shadow: excess depth over mean toward sun, ball (shadeCap), sky above, fires below
    float shadeSun;
    float shadeCap;
    float shadeUp;
    float shadeDown;
    // local pall billows (coarse × fine, mean 1): also billow wide storm smoke — same air mass
    float clumps;
};

Sample emptySample() {
    return Sample(0.0, vec3(0.0), 0.0, vec3(0.0), 0.0, 0.0, vec3(0.0), 0.0, 0.0, vec3(0.0), 0.0, 0.0, 0.0, vec3(0.0), 0.0, 0.0, 0.0, 0.0, 1.0);
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
// white; overexposure becomes pixel coverage (cover·L, capped at full pixel), colored by its light, CROSS toward white.
// Transmittance remains real: same background visible, overlaid light saturates toward white (frame RGBA16F,
// layer quads behind it take light from frame, fx.fsh: before). Otherwise a distant ball left with cover by air haze
// (10 km clear — 0.14) would be a pale sky patch however bright it shines. Copy — NukeExposure.pixel
vec3 pallShown(vec3 light, float cover) {
    float shownCover = cover;
    vec3 medium = light / max(cover, 1e-4);
    float lum = dot(medium, LUMA);
    if (lum > 1.0 && cover > 1e-4) {
        shownCover = min(cover * lum, 1.0);
        medium *= cover / shownCover;
    }
    medium += vec3(CROSS * max(dot(medium, LUMA) - 1.0, 0.0));
    return shoulder(vec3(pallDisplay(medium.r), pallDisplay(medium.g), pallDisplay(medium.b))) * shownCover;
}

// ---------------------------------------------------------------- dust-layer head (pall.glsl: pallWall*)

// Head billows at q in tile V[2].w, in dust coordinates (pallWallOrigin): (x, y) tile channels 0..1 (billows, fine edges,
// uniform as tile), z fine billows (multiplier, mean 1; fine is their share, as pall). Coarse-noise warp as pall (warpAt):
// on a kilometers-wide ring, repetition from above appears as a grid
vec3 wallNoise(vec3 q, float fine) {
    vec2 w = texture(PallNoise, q * 0.07 + vec3(0.31, 0.5, 0.67)).rg - 0.5;
    q += vec3(w.x, 0.0, w.y) * 0.3;
    vec2 n = textureLod(PallNoise, q, 0.0).rg;
    float detail = fine > 0.0 ? fineAt(q) : 1.0;
    return vec3(n, mix(1.0, detail, fine));
}

// Head at h (pallWallAt) with billows: extinction, 1/block. Billows as pall (pallShape: mean 1, dense dust more continuous —
// fill by density relative to dense, thinner toward wedge top), fine billows within while resolved (fine; 0 for shadow
// billows). Noise at dust origin (pallWallOrigin): billows form behind front where dust was lifted and stay there;
// relative to retreating front, edge dust moves up and back — a roller
float wallAt(mat4 V, WallAt h, float fine, float dense) {
    float s = h.s - h.P.z;
    float m = pallWallMean(s, h.z, h.P.x, h.P.y);
    if (m <= 0.0 || h.P.w <= 0.0) return 0.0;
    float u = s / max(h.P.x, 0.001);
    vec3 n = wallNoise(pallWallOrigin(h.rel, h.z, u * V[2].x, h.P.y, V[1].x) / max(V[2].w, 1.0) + vec3(0.618034, 0.414214, 0.732051), fine);
    float fill = clamp(h.P.w * m / max(dense, 1e-6) * 3.0, 0.0, 1.0) * (1.0 - smoothstep(0.4, 1.0, h.z / max(h.P.y * pallWallTop(u), 0.001)));
    return h.P.w * m * pallShape(n.xy, fill) * n.z;
}

// point h shifted by v (blocks): same underlying water/terrain, own extinction (edge to wedge end, 0 outside bounds),
// as BlastWall.sigma at each sample
WallAt wallMove(mat4 V, WallAt h, vec3 v) {
    WallAt m = h;
    m.rel = h.rel + v.xz;
    m.s = V[0].x - length(m.rel);
    m.z = h.z + v.y;
    m.P.w = pallWallInside(m.s, m.z, V[0].y, V[0].z, V[1].y) * pallWallDensity(m.s - m.P.z, V[0].y, V[0].w, V[3].z, V[3].w, V[1].w, m.wet);
    return m;
}

// Mean head depth (no billows) from h along unit v over len, n steps
float wallChord(mat4 V, WallAt h, vec3 v, float len, int n) {
    float sum = 0.0, ds = len / float(n);
    for (int i = 0; i < n; i++) {
        WallAt m = wallMove(V, h, v * ((float(i) + 0.5) * ds));
        sum += m.P.w * pallWallMean(m.s - m.P.z, m.z, m.P.x, m.P.y);
    }
    return sum * ds;
}

// Same, with denser cells near point (boundaries by squared index): light ray exits low wedge near point, then passes
// above it; uniform steps across full length would skip edge wedge between samples (NukePall.chordNear)
float wallChordNear(mat4 V, WallAt h, vec3 v, float len, int n) {
    float sum = 0.0, nn = float(n * n);
    for (int i = 0; i < n; i++) {
        float a = float(i * i) / nn, b = float((i + 1) * (i + 1)) / nn;
        WallAt m = wallMove(V, h, v * (0.5 * (a + b) * len));
        sum += m.P.w * pallWallMean(m.s - m.P.z, m.z, m.P.x, m.P.y) * (b - a);
    }
    return sum * len;
}

// Slot wall head at point h (pallWallAt), accumulated in s. Same dust as pall, denser with a roller: same light
// (wall light is front pall light, CloudPuffs.pallLight), same material (with smoke/soot in fire zone, spray over water).
// Head is not a flat slab: light enters sideways too — sun/sky diffuse light uses smaller depth to top or leading edge
// (as depth beneath slab top); ball light lies beneath nearest ball-lit side (capDeep): depth toward ball, or depth
// above point plus depth before ball still traversed by its beam to the top above point (ball is low behind head,
// toward epicenter: beam to top at leading edge crosses the whole head; using only depth above would turn the outside
// orange by day with light that cannot reach it). Sun/ball beams use mean-head depth toward them (nearby, actual
// billows: neighbor-billow shadow toward keyDir, otherwise flat head), not the column above point.
// fireR is fire zone edge (blocks), r ground range from epicenter
void wall(mat4 V, WallAt h, vec3 keyDir, vec3 sunDir, vec3 lightDir, float fireR, float r, float cone, bool pall, float dense, inout Sample s) {
    float tile = max(V[2].w, 1.0), fine = 1.0 - smoothstep(0.5, 1.0, cone * 32.0 / tile);
    float hd = wallAt(V, h, fine, dense);
    // neither head nor slot pall here — no light needed
    if (hd <= 0.0 && !pall) return;
    float hm = h.P.w * pallWallMean(h.s - h.P.z, h.z, h.P.x, h.P.y);
    // billow shadow: two samples toward key light (0.06 and 0.2 tile) plus local point, relative to local mean
    float dev = 0.0;
    if (hd > 0.0) {
        WallAt h1 = wallMove(V, h, keyDir * (0.06 * tile)), h2 = wallMove(V, h, keyDir * (0.2 * tile));
        float m1 = h1.P.w * pallWallMean(h1.s - h1.P.z, h1.z, h1.P.x, h1.P.y);
        float m2 = h2.P.w * pallWallMean(h2.s - h2.P.z, h2.z, h2.P.x, h2.P.y);
        dev = ((hd - hm) * 0.03 + (wallAt(V, h1, 0.0, dense) - m1) * 0.1 + (wallAt(V, h2, 0.0, dense) - m2) * 0.17) * tile;
        dev = clamp(dev, -3.0, 6.0);
    }
    // mean head depth above point (to local wedge top), toward leading edge (where wedge stays above point), below point
    // to terrain (ground/fires there; head below map only a coarse-map margin); toward light through whole head but no
    // higher than its top: long low wedge, full-length steps would pass above it (same as NukePall.dustLight)
    float reach = PALL_WALL_HANDOFF_TO * h.P.x + h.P.y, s0 = h.s - h.P.z, toTop = max(h.P.y - h.z, 0.0);
    vec2 out1 = h.rel / max(length(h.rel), 1e-3);
    float up = wallChord(V, h, vec3(0.0, 1.0, 0.0), max(h.P.y * pallWallTop(s0 / max(h.P.x, 0.001)) - h.z, 0.0), 4);
    float side = wallChord(V, h, vec3(out1.x, 0.0, out1.y), max(s0 - h.P.x * pow(clamp(h.z / max(h.P.y, 0.001), 0.0, 1.0), 1.2), 0.0), 4);
    float down = wallChord(V, h, vec3(0.0, -1.0, 0.0), max(h.z, 0.0), 2);
    float depth = min(up, side);
    float sunLen = sunDir.y > 0.05 ? min(reach, toTop / sunDir.y) : reach;
    float capLen = lightDir.y > 0.05 ? min(reach, toTop / lightDir.y) : reach;
    float toSun = wallChordNear(V, h, sunDir, sunLen, 4), toCap = wallChordNear(V, h, lightDir, capLen, 4);
    float smoke = 0.77 * (1.0 - smoothstep(0.92 * max(fireR, 1e-3), 1.04 * max(fireR, 1e-3), r));
    vec3 w = mix(mix(PALL_DUST_W, PALL_SMOKE_W, smoke), PALL_MIST_W, h.wet);
    float g = mix(PALL_DUST_G, PALL_MIST_G, h.wet);
    s.tau += depth;
    s.slab += depth + down;
    s.slabAlbedo += (depth + down) * w;
    s.slabPhase += (depth + down) * g;
    s.water += (depth + down) * h.wet;
    // beams travel s.tau / μ: head contribution there is diffuse-light depth, beam needs its own depth toward light
    s.shadeSun += toSun + dev * max(dot(keyDir, sunDir), 0.0) - depth / max(sunDir.y, 0.12);
    s.shadeCap += toCap + dev * max(dot(keyDir, lightDir), 0.0) - depth / clamp(lightDir.y, 0.12, 1.0);
    s.capDeep += min(toCap, up + max(toCap - up / clamp(lightDir.y, 0.12, 1.0), 0.0)) - depth;
    s.shadeUp += dev * max(keyDir.y, 0.0);
    s.shadeDown += dev * max(-keyDir.y, 0.0);
    s.sigma += hd;
    s.albedo += hd * w;
    s.phase += hd * g;
}

// Slot cap center (CloudPuffs.pallLight): above epicenter at H.w, downwind (S[2].xy) by G.z, as mushroom body
// (MushroomField.centre); ball is there while still a ball
vec3 pallBall(mat4 S, vec4 G, vec4 H) {
    return vec3(S[0].x + S[2].x * G.z, S[0].z + H.w, S[0].y + S[2].y * G.z);
}

// Ball light on a facing white wall at d from cap center, same as stem/particle light (MushroomModel.light,
// CloudPuffs.ball): Lambertian sphere of cap radius, black body at visible temperature, L·R²/d² (surface L)
float pallBallShare(float radius, float d) {
    return radius * radius / max(max(d * d, radius * radius), 1e-6);
}

// Slot ball light at d from cap center: G.x radius, H.rgb black-body light, × eye adaptation
vec3 pallBallLight(vec4 G, vec4 H, float d) {
    return H.rgb * (PallExposure * pallBallShare(G.x, d));
}

// Add slot pall at p to s; V slot dust wall; ground, R slot terrain/water map and parameters; W slot water;
// G, H slot dust-wall light (PallGlow, PallHot); dir view ray; cone smallest resolved size here (blocks)
void slot(mat4 S, mat4 V, sampler2D ground, vec4 R, vec4 W, vec4 G, vec4 H, vec3 p, vec3 sunDir, vec3 dir, float cone, vec4 inflow, inout Sample s) {
    if (S[1].w < 0.5) return;
    // layer floor is local terrain (PallGround map): valley pall is still a slab, fires/ground at its bottom, not epicenter
    // ground height; no map, epicenter ground
    float floorUp = pallRelief(ground, S, R, p.xz);
    PallAt f = pallField(Sampler1, ground, S, R, W, p, floorUp, topBumps(S, p));
    // Dust lifted out of the flat layer by inflow has the same material and billows. Its light is computed here too,
    // including self-shadowing: above the old top it does not become flat gray fill.
    if (inflow.w > 0.0 && length(inflow.xz - S[0].xy) < 1.0) {
        float oldDust = f.sigma.x + f.sigma.z;
        f.mixed *= oldDust / max(oldDust + inflow.w, 1e-7);
        f.sigma.x += inflow.w * (1.0 - f.wet);
        f.sigma.z += inflow.w * f.wet;
        int k = length(inflow.xz - PallSlot0[0].xy) < 1.0 ? gFoot0 : gFoot1;
        if (k >= 0) {
            Mush m = mushLoad(Sampler7, k);
            vec3 q = mushLocal(m, p);
            float rho = length(q.xz), r = mushStemAt(m, q.y), wall = mushFootWall(rho, r);
            float removed = mushFootRemoved(Sampler7, k, m, rho - r);
            float up = max(mushFootColumn(Sampler7, k, m, q.y) - removed, 0.0) * wall;
            float total = max(mushFootColumn(Sampler7, k, m, 0.0) - removed, 0.0) * wall;
            f.column.x += up * (1.0 - f.wet);
            f.column.z += up * f.wet;
            f.slab.x += total * (1.0 - f.wet);
            f.slab.z += total * f.wet;
        }
    }
    WallAt head = pallWallAt(ground, S, V, R, W, p, floorUp);
    vec3 dustW = mix(PALL_DUST_W, PALL_SMOKE_W, f.smoke);
    s.tau += f.column.x + f.column.y + f.column.z;
    s.slab += f.slab.x + f.slab.y + f.slab.z;
    s.slabAlbedo += f.slab.x * dustW + f.slab.y * PALL_HAZE_W + f.slab.z * PALL_MIST_W;
    s.slabPhase += (f.slab.x + f.slab.y) * PALL_DUST_G + f.slab.z * PALL_MIST_G;
    // dust over water only if lofted from water; mist always
    s.water += (f.slab.x + f.slab.y) * f.wet + f.slab.z;
    // Ball/fire light on dust (CloudPuffs.pallLight): ball beam from cap center (pallBallLight, linear, like sun on a facing
    // white wall), bottom fire light (pallBurn); fire strength is smoke reflection (× PallGlowOnSmoke) divided by dust color,
    // added to display skylight, converted to linear by difference (pallLift): thick dust displays the reflected color.
    // Fire light capped at flame (pallFlame): at night above fires, reflection already has flame color; inversion through
    // dust color would give three times flame brightness — ground dust above 1 and flat (shoulder). Daytime fires under
    // dust barely visible, like their haze glow (NukePall: fireLight): wall passes in seconds, pall hangs over burning city
    // for minutes. No underwater fires (spray wall does not take them either): water is sampled at dust origin
    float r = length(p.xz - S[0].xy);
    float burn = pallBurn(r, 0.0, G.y, G.w) * (1.0 - 0.75 * PallSun.w) * (1.0 - f.wet);
    vec3 toLight = pallBall(S, G, H) - p;
    float toLen = length(toLight);
    vec3 lightDir = toLight / max(toLen, 1e-3);
    vec3 cap = pallBallLight(G, H, toLen);
    float capI = cap.r + cap.g + cap.b;
    s.cap += cap;
    s.capUp += capI * lightDir.y;
    s.capView += capI * dot(dir, lightDir);
    vec3 burnLight = vec3(0.0);
    if (burn > 0.0) burnLight = pallFlame(PallBase, FIRE * (burn * PallGlowOnSmoke) / DUST, FIRE);
    s.burn += burnLight;
    // key light is sun, or while brighter, ball over epicenter or fires below dust (all nighttime dust light);
    // fires below mean direction toward them, not ball. Ball/fires share units: linear light on a white wall
    float lit = dot(cap, LUMA), fires = dot(burnLight, LUMA), heat = max(lit, fires);
    float key = clamp(heat / (heat + 0.5 * PallSun.w + 0.02), 0.0, 1.0);
    vec3 keyLight = lightDir;
    if (fires > lit) keyLight = vec3(0.0, -1.0, 0.0);
    vec3 keyDir = mix(sunDir, keyLight, key);
    keyDir /= max(length(keyDir), 1e-3);
    bool pall = f.sigma.x + f.sigma.y + f.sigma.z > 1e-7;
    if (head.P.w > 0.0) wall(V, head, keyDir, sunDir, lightDir, G.w, r, cone, pall, S[3].x, s);
    if (!pall) return;
    float fill = clamp(f.dense * 3.0, 0.0, 1.0) * (1.0 - smoothstep(0.4, 1.0, f.height.x));
    vec3 warp = warpAt(S, p);
    vec4 flow = carpetFlowAt(S, p);
    vec3 uvA, uvB;
    float phaseBlend;
    carpetUv(S, p, warp, flow, uvA, uvB, phaseBlend);
    vec3 lightUv = keyDir * vec3(1.0, 1.3, 1.0);
    // fine billows about 1/32 tile: fully visible while ray resolves twice finer (fine is share; 0 when below pixel/step —
    // mean 1, no sample)
    float tile = S[3].y, fine = 1.0 - smoothstep(0.5, 1.0, cone * 32.0 / tile);
    // young layer patches disperse as it mixes (pallMixed): old pall is an even blanket from above
    float raw = carpetCoarse(S, uvA, uvB, phaseBlend, fill), detail = fine > 0.0 ? carpetFine(uvA, uvB, phaseBlend) : 1.0;
    float coarse = pallMixed(raw, f.mixed), shape = pallMixed(raw * mix(1.0, detail, fine), f.mixed);
    s.clumps = shape;
    float d = f.sigma.x * shape, h = f.sigma.y * mix(1.0, shape, 0.5), m = f.sigma.z * shape;
    // Local/neighbor billow shadow: depth toward key light relative to mean (clumpShade). Density is coarse × fine;
    // depth deviation sums scales: coarse samples at 0.06 and 0.22 tile toward light, fine four times closer × containing
    // coarse density (nearly constant across a couple fine billows). Coarse shadows give large light/dark masses
    // (several optical depths in dense dust); fine shadows their nearby edges. Two samples per scale, not one: one cannot
    // distinguish local billow edge from depth, producing blotches instead of lit/shaded sides. Shadow noise samples:
    // two coarse (two channels each), one fine only while resolved
    float sigmaShade = f.sigma.x + f.sigma.z;
    float clump = clumpShade(coarse, pallMixed(carpetCoarse(S, uvA + lightUv * 0.06, uvB + lightUv * 0.06, phaseBlend, fill), f.mixed),
            pallMixed(carpetCoarse(S, uvA + lightUv * 0.22, uvB + lightUv * 0.22, phaseBlend, fill), f.mixed)) * sigmaShade * tile;
    if (fine > 0.0) {
        clump += fine * clumpShade(pallMixed(detail, f.mixed), pallMixed(carpetFine(uvA + lightUv * 0.015, uvB + lightUv * 0.015, phaseBlend), f.mixed),
                pallMixed(carpetFine(uvA + lightUv * 0.055, uvB + lightUv * 0.055, phaseBlend), f.mixed)) * sigmaShade * coarse * (0.25 * tile);
    }
    // shadow per light weighted by alignment with key light
    s.shadeSun += clump * max(dot(keyDir, sunDir), 0.0);
    s.shadeCap += clump * max(dot(keyDir, lightDir), 0.0);
    s.shadeUp += clump * max(keyDir.y, 0.0);
    s.shadeDown += clump * max(-keyDir.y, 0.0);
    vec3 sc = d * dustW + h * PALL_HAZE_W + m * PALL_MIST_W;
    s.sigma += d + h + m;
    s.albedo += sc;
    s.phase += (d + h) * PALL_DUST_G + m * PALL_MIST_G;
    if (f.fire > 0.0) s.fire += sc * pallFlame(PallBase, f.fire * PallFireLight, PallFireLight / max(PallFireLight.r, 1e-4));
}

// Pall slab material above/below point by component optical depths: per-channel albedo, asymmetry (a)
vec4 slabMatter(Sample s) {
    vec4 w = vec4(PALL_DUST_W, PALL_DUST_G);
    if (s.slab > 1e-6) w = vec4(s.slabAlbedo, s.slabPhase) / s.slab;
    return w;
}

// ground beneath slab: land or water
float slabGround(Sample s) {
    float water = s.slab > 1e-6 ? clamp(s.water / s.slab, 0.0, 1.0) : 0.0;
    return mix(PALL_LAND, PALL_SEA, water);
}

// Diffuse pall light at point from above (sun strength sun, ball, sky), I0 + c·I1 per channel (pallDiffuse; excluding
// point albedo), at depth shifted by billow shadow toward it. Solution linear in sources: ball uses its own solution
// at its own depth (capDeep, wall head), only while not vanishingly small
vec3 scattered(Sample s, float c, vec3 sun) {
    float slab = max(s.slab, s.tau);
    vec4 w = slabMatter(s);
    float g = w.a, ground = slabGround(s);
    float muS = max(PallSun.y, 0.12);
    float top = clamp(s.tau + s.shadeUp, 0.0, slab);
    vec3 d = vec3(pallDiffuse(w.r, g, top, slab, ground, muS, sun.r, 1.0, 0.0, PallSkyLight.r, 0.0, c),
            pallDiffuse(w.g, g, top, slab, ground, muS, sun.g, 1.0, 0.0, PallSkyLight.g, 0.0, c),
            pallDiffuse(w.b, g, top, slab, ground, muS, sun.b, 1.0, 0.0, PallSkyLight.b, 0.0, c));
    float capI = s.cap.r + s.cap.g + s.cap.b;
    if (capI > 1e-5) {
        float muC = clamp(s.capUp / capI, 0.12, 1.0);
        float slabC = slab + s.capDeep, topC = clamp(s.tau + s.capDeep + s.shadeUp, 0.0, slabC);
        d += vec3(pallDiffuse(w.r, g, topC, slabC, ground, muS, 0.0, muC, s.cap.r, 0.0, 0.0, c),
                pallDiffuse(w.g, g, topC, slabC, ground, muS, 0.0, muC, s.cap.g, 0.0, 0.0, c),
                pallDiffuse(w.b, g, topC, slabC, ground, muS, 0.0, muC, s.cap.b, 0.0, 0.0, c));
    }
    return max(d, vec3(0.0));
}

// Diffuse slab light from fires beneath dust (bottom light), at depth shifted by billow shadow toward them.
// This is fire: adds to point fire sum (main: fire), not overhead light
vec3 burnt(Sample s, float c) {
    if (s.burn.r + s.burn.g + s.burn.b <= 1e-5) return vec3(0.0);
    float slab = max(s.slab, s.tau);
    vec4 w = slabMatter(s);
    float g = w.a, ground = slabGround(s);
    float bottom = clamp(s.tau - s.shadeDown, 0.0, slab);
    vec3 d = vec3(pallDiffuse(w.r, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.r, c),
            pallDiffuse(w.g, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.g, c),
            pallDiffuse(w.b, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.b, c));
    return max(d, vec3(0.0));
}

// column position within map cell c (fractions from cell center, to ±0.25): unique per cell, avoiding rows
vec2 fireSpot(ivec2 c) {
    // sin-free hash (cheaper per ray step): map cells are integers up to a few hundred
    vec3 p = fract(vec3(vec2(c).xyx) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return (fract((p.xx + p.yz) * p.zy) - 0.5) * 0.5;
}

// Slot firestorm at eye-relative p: x column-smoke extinction (1/block), y bottom fire glow (light per unit whiteness),
// z x × column-smoke optical depth toward sun (column self-shadow). Glow comes from the full ~2h-wide area below point
// (map mip with that cell width), flickering; column is smoke feed below point, drifted with height (fireBend).
// While column narrower than cell (fireWidth), each of four nearest cells has its own column at its position: density
// is cell feed × cell area / column area (same smoke), clear between columns; wider columns use the mip with cell size
// equal to column width: smoke spreads and density falls with area. cone is smallest resolved size, blocks (pixel width,
// third of step): do not read finer mips — distant columns neither shimmer nor appear as sparse translucent “rain”
// stripes narrower than a step; they are broad and soft there. clumps are local pall billows (Sample.clumps), also used
// for broad smoke (columns have their own finer noise).
vec3 storm(sampler2D map, mat4 S, mat4 F, vec3 p, float cone, float clumps) {
    if (F[0].x <= 0.0) return vec3(0.0);
    float h = p.y - S[0].z, cell = F[0].y, top = F[0].z, t = F[2].z;
    vec2 rel = p.xz - S[0].xy;
    float flame = textureLod(map, fireUv(S, F, p.xz), max(fireGlowLod(h, cell), fireLod(cone, cell))).r;
    float glow = 0.0;
    if (flame > 1e-4) {
        float flicker = 0.55 + 0.9 * texture(PallNoise, vec3(rel / 700.0, t * 0.021)).r;
        // smoke below point attenuates fire light: bright at ground, three times weaker by one fifth of storm top;
        // remainder is dim red underside light up to pall top
        float up = max(h, 0.0);
        glow = F[1].w * flame * flicker * (0.75 * exp(-up / (0.2 * top)) + 0.25 * exp(-up / top));
    }
    float col = fireColumn(h, top);
    if (col <= 0.0) return vec3(0.0, glow, 0.0);
    vec2 shift = F[1].xy * fireBend(h, F[0].w);
    vec2 uv = fireUv(S, F, p.xz - shift);
    float w = fireWidth(h, cell), wide = max(w, cone);
    // wide column and “no fire nearby” in one sample: mip cell at least 64 blocks covers the four nearest column cells
    // (mean of nonnegative values: zero means no smoke in any); above unburned ground, no column/noise samples
    vec3 m = textureLod(map, uv, max(fireLod(wide, cell), 1.0)).rgb;
    if (m.g + m.b <= 1e-6) return vec3(0.0, glow, 0.0);
    // column builds with smoke rise: full feed G at ground, risen share B above
    float rise = smoothstep(0.0, 0.3 * top, h);
    float spread = smoothstep(0.4, 0.75, wide / cell);
    // billows rise at smoke speed and drift with column; same noise warps column lookup — billowing edge, not a flat stripe
    vec2 n = texture(PallNoise, vec3(rel.x - shift.x, h - 0.5 * F[1].z * min(t, 3000.0), rel.y - shift.y) / (2.0 * cell)).rg;
    // toward sun: horizontal column path grows by 1/cos sun elevation (high sun — capped at fourfold), no higher than column top
    vec3 sun = PallSun.xyz;
    float across = length(sun.xz), slant = 1.0 / max(across, 0.25);
    vec2 toSun = sun.xz / max(across, 1e-4);
    float feed = spread * mix(m.g, m.b, rise);
    // broad: smoke to column edge or top, averaged at half-path
    float tau = feed * 0.5 * min(wide * slant, max(top - h, 0.0) / max(sun.y, 0.1));
    float core = 0.6 * spread;
    if (spread < 1.0) {
        ivec2 size = textureSize(map, 0);
        vec2 g = fireUv(S, F, p.xz - shift + (n - 0.5) * (1.2 * w)) * vec2(size);
        ivec2 base = ivec2(floor(g - 0.5));
        float area = cell * cell / (3.14159 * w * w), pillars = 0.0, shade = 0.0, peak = 0.0;
        for (int j = 0; j <= 1; j++) {
            for (int i = 0; i <= 1; i++) {
                ivec2 c = base + ivec2(i, j);
                if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, size))) continue;
                vec3 mc = texelFetch(map, c, 0).rgb;
                vec2 d = (g - vec2(c) - 0.5 - fireSpot(c)) * cell / w;
                float axis = mix(mc.g, mc.b, rise) * area, r2 = dot(d, d), e = exp(-r2);
                pillars += axis * e;
                // Gaussian column depth toward sun: Gaussian across ray, erfc tail along it (via tanh),
                // sunward side bright, other side self-shadowed
                float ahead = dot(d, toSun);
                shade += axis * exp(-max(r2 - ahead * ahead, 0.0)) * (1.0 - tanh(1.2 * ahead)) * 0.886 * w * slant;
                peak = max(peak, e);
            }
        }
        feed += (1.0 - spread) * pillars;
        tau += (1.0 - spread) * shade;
        core += (1.0 - spread) * peak;
    }
    if (feed <= 1e-4) return vec3(0.0, glow, 0.0);
    // solid column core, edges break into billows; broad smoke uses pall billows
    float k = F[2].y * col * mix(pallShape(n, 0.2 + 0.7 * core), clumps, spread);
    return vec3(k * feed, glow, k * feed * k * tau);
}

// Regional glow (burning-city light scattered by surrounding dust/smoke, even without fire below point): full map fire
// as one city-sized volume source (mean map fire × area, 1×1 mip), 1/r² from center (soft inside fire zone), attenuated by
// smoke over fire (as local glow) and pall along path from zone edge; inside, path at least local glow width (nearer
// already counted there). Dust scattering phase makes it brighter toward an eye looking at city. sigma is local pall
// extinction (1/block), view is eye ray. x is city-side light (as storm y, from side), y path pall depth: diffuse light
// decays as e^(−kτ) (main, pallK) — early dense dust blocks nearly all distant fire light, nonabsorbing mist carries it far.
vec2 fireAround(sampler2D map, mat4 S, mat4 F, vec3 p, vec3 view, float sigma) {
    if (F[0].x <= 0.0) return vec2(0.0);
    float mean = textureLod(map, vec2(0.5), 16.0).r;
    if (mean <= 1e-5) return vec2(0.0);
    float side = F[0].x, top = F[0].z, zone = 0.6 * side;
    vec3 from = vec3(S[0].x, S[0].z, S[0].y), to = p - from;
    float h = max(to.y, 0.0), r2 = dot(to, to), rg = length(to.xz);
    float power = mean * 4.0 * side * side / 3.14159;
    float c = dot(to / max(sqrt(r2), 1.0), -view);
    float phase = mix(0.75 * hg(0.5, c) + 0.25 * hg(-0.2, c), 1.0, zone * zone / (zone * zone + r2));
    float smoke = 0.75 * exp(-h / (0.2 * top)) + 0.25 * exp(-h / top);
    float path = max(rg - zone, 2.0 * h + 2.0 * F[0].y);
    return vec2(AROUND * F[1].w * power / (zone * zone + r2) * min(phase, 3.0) * smoke, sigma * path);
}

// Fire whirl i (matrix column): dir ray entry/exit in surrounding cylinder (margin for upward widening and tilt);
// entry ≥ exit means miss
vec2 whirlSpan(int i, vec3 dir) {
    vec4 P = PallWhirlPos[i], A = PallWhirlShape[i], L = PallWhirlLean[i];
    if (A.y <= 0.0) return vec2(1.0, 0.0);
    vec2 c = P.xy + L.xy * 0.5 * A.x;
    float R = P.w * 2.6 + length(L.xy) * 0.5 * A.x;
    float a = dot(dir.xz, dir.xz), b = dot(dir.xz, c), cc = dot(c, c) - R * R;
    float disc = b * b - a * cc;
    if (a < 1e-8 || disc <= 0.0) return vec2(1.0, 0.0);
    float sq = sqrt(disc);
    vec2 t = vec2(b - sq, b + sq) / a;
    if (abs(dir.y) > 1e-5) {
        float ta = P.z / dir.y, tb = (P.z + A.x) / dir.y;
        t = vec2(max(t.x, min(ta, tb)), min(t.y, max(ta, tb)));
    } else if (P.z > 0.0 || P.z + A.x < 0.0) {
        return vec2(1.0, 0.0);
    }
    return vec2(max(t.x, 0.0), t.y);
}

// Whirl i at eye-relative q: x smoke extinction (1/block), y flame light per path block. Core widens upward; rotation
// is Lamb–Oseen (angular velocity (1 − e^(−r²/rc²))/r²: solid-body near axis, decays outward); noise rotates for two
// offset half-periods and blends (as map flow: otherwise twist accumulates endlessly into thin spirals), with curl
// noise displacement above (divergence-free field: billows tear, not drain); everything rises.
vec2 whirlAt(int i, vec3 q) {
    vec4 P = PallWhirlPos[i], A = PallWhirlShape[i], L = PallWhirlLean[i];
    float y = q.y - P.z, yn = y / A.x;
    if (yn < 0.0 || yn > 1.0) return vec2(0.0);
    vec2 rel = q.xz - P.xy - L.xy * y;
    float rc = P.w * (0.45 + 1.4 * yn * yn);
    float r2 = dot(rel, rel) / (rc * rc);
    if (r2 > 6.0) return vec2(0.0);
    // curl displacement of noise field ψ: v = (∂ψ/∂z, −∂ψ/∂x)
    float e = 0.04;
    vec3 c0 = vec3(rel.x / (4.0 * P.w), y / (1.5 * A.x) - 0.08 * A.z + L.w, rel.y / (4.0 * P.w));
    float psi = texture(PallNoise, c0).g;
    float dx = texture(PallNoise, c0 + vec3(e, 0.0, 0.0)).g - psi, dz = texture(PallNoise, c0 + vec3(0.0, 0.0, e)).g - psi;
    vec2 w = rel + vec2(dz, -dx) / e * 0.06 * rc;
    float vortex = r2 > 1e-4 ? (1.0 - exp(-r2)) / r2 : 1.0;
    float omega = A.w * 1.75 * WHIRL_SPEED / rc * vortex;
    float period = 3.0, ph = A.z / period;
    float a1 = omega * fract(ph) * period, a2 = omega * fract(ph + 0.5) * period;
    float k = 1.0 - abs(2.0 * fract(ph) - 1.0);
    vec2 w1 = mat2(cos(a1), sin(a1), -sin(a1), cos(a1)) * w, w2 = mat2(cos(a2), sin(a2), -sin(a2), cos(a2)) * w;
    float lift = y / (0.4 * A.x) - 0.35 * A.z + L.w;
    float n1 = texture(PallNoise, vec3(w1.x / (1.6 * P.w), lift, w1.y / (1.6 * P.w))).r;
    float n2 = texture(PallNoise, vec3(w2.x / (1.6 * P.w), lift + 0.5, w2.y / (1.6 * P.w))).r;
    float n = mix(n2, n1, k);
    float env = smoothstep(0.0, 0.06, yn) * (1.0 - smoothstep(0.7, 1.0, yn));
    float smoke = 0.2 * A.y * exp(-r2) * env * smoothstep(0.25, 0.7, n) * 2.0;
    float flame = 0.14 * L.z * A.y * exp(-2.5 * r2) * smoothstep(0.0, 0.03, yn) * (1.0 - smoothstep(0.1, 0.45, yn))
            * smoothstep(0.4, 0.75, n) * 3.0;
    return vec2(smoke, flame);
}

// Whirl i along ray [ta, tb]: light (rgb, linear), transmittance (a); lit is local pall/sun light (diffuse with soot
// asymmetry); fire is local fire light (fires beneath dust through slab and bottom glow; linear): with whirl flame,
// capped at flame. Thick whirl core (several optical depths across) reflects as thick soot layer, color SOOT rather than
// single-scattering albedo
vec4 whirl(int i, vec3 dir, float ta, float tb, float jitter, vec3 lit, vec3 fire, vec3 flame) {
    vec3 light = vec3(0.0);
    float tr = 1.0, ds = (tb - ta) / 10.0;
    for (int k = 0; k < 10; k++) {
        vec3 q = dir * (ta + (float(k) + jitter) * ds);
        vec2 w = whirlAt(i, q);
        if (w.x + w.y <= 1e-6) continue;
        float yn = (q.y - PallWhirlPos[i].z) / PallWhirlShape[i].x;
        // whirl smoke in skylight and base fire; flame emits
        float heat = 2.0 * PallWhirlLean[i].z * PallWhirlShape[i].y * (1.0 - yn) * (1.0 - yn);
        vec3 sc = SOOT_LINEAR * (lit + min(fire + pallFlame(PallBase, WHIRL_FIRE * heat, WHIRL_FIRE), flame));
        float a = exp(-w.x * ds);
        light += tr * (sc * (1.0 - a) + WHIRL_FIRE_LINEAR * (w.y * ds));
        tr *= a;
    }
    return vec4(light, tr);
}

// Pall ray reach from eye: beyond depth 12 along ray (e^−12), nothing visible, no steps spent. Eye dust is a layer:
// sigma eye extinction, tau vertical depth toward ray (above eye or below to ground), mu absolute vertical ray cosine.
// Layer is exponential with same total depth: ray depth tau/mu·(1 − e^(−t·mu·sigma/tau)), capped at tau/mu — a ray leaving
// the layer before depth 12 (toward cap above ground dust) is not clipped. Near-horizontal, 12/sigma as uniform dust;
// never clip closer. Eye-density-only clipping cut caps kilometers above thin dust: mushroom vanished when pall covered
// camera. Mushroom ray is never clipped: transmittance ends it behind dense dust. No depth but eye dust present
// (eye below coarse terrain map: under trees, between houses) — unknown layer, use 12/sigma as before
float pallClip(float sigma, float tau, float mu) {
    if (sigma <= 1e-4) return 1e6;
    if (tau <= 0.0) return 12.0 / sigma;
    if (tau <= 12.0 * mu) return 1e6;
    float x = 12.0 * mu / tau;
    if (x < 1e-3) return 12.0 / sigma * (1.0 + 0.5 * x);
    return -8.317766 * log2(max(1.0 - x, 1e-7)) / (x * sigma);
}

// Location of ray step u (0..1) along [t0, t1] with storm interval [fa, fb]: fraction a of steps before storm (denser
// near eye), b inside (uniform; denser near eye if storm starts there), rest beyond. No storm (a = 1, fa = t1) —
// simply denser near eye
float along(float u, float t0, float fa, float fb, float t1, float a, float b) {
    if (u < a) {
        float x = u / a;
        return t0 + (fa - t0) * x * x;
    }
    if (u < a + b) {
        float x = (u - a) / b;
        float k = a > 0.0 ? x : x * x;
        return fa + (fb - fa) * k;
    }
    return fb + (t1 - fb) * (u - a - b) / max(1.0 - a - b, 1e-6);
}

// Slot Wilson cloud (S, L — pallWilson), eye ray dir crossing at t with depth tau: light (rgb), transmittance (a).
// Post-front condensation fog uses mist droplets (MIST): sky, sun, ball (pallBallLight — cloud forms while ball
// brightest), with droplet phase; depth billows by burst-relative direction (shell breaks into bands and patches).
// Ball beside shell is extended (hgDisc); shell sees its surface no brighter than on-screen ball
// (mushBallCap — presentation: NukeExposure): shell surrounds the ball, and at its hundreds-of-white brightness it
// merged with the fire-exposed ball into one white disk
vec4 wilsonLight(mat4 S, vec4 G, vec4 H, vec4 L, vec3 dir, vec3 sunDir, float t, float tau) {
    if (tau <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
    vec3 q = dir * t;
    vec3 toBurst = vec3(S[0].x, S[0].z + L.w, S[0].y) - q;
    toBurst /= max(length(toBurst), 1e-3);
    tau *= 0.5 + textureLod(PallNoise, -toBurst * 1.7 + vec3(0.21, 0.47, 0.83), 0.0).r;
    vec3 toBall = pallBall(S, G, H) - q;
    float ballLen = length(toBall);
    // ball surface radiance for shell capped at on-screen ball brightness, then light there (L·R²/d²): shell/ball display
    // ratio matches reality (forward scattering near ball ~0.76·τ of its radiance), not shell hundreds of times brighter
    vec3 surface = H.rgb * PallExposure;
    vec3 cap = min(surface, mushBallCap(surface, PallExposure)) * pallBallShare(G.x, ballLen);
    float sinBall = min(G.x / max(ballLen, 1e-3), 1.0);
    vec3 l = PALL_MIST_W * (0.5 * PallSkyLight + PallSunLight * (0.25 * hg(PALL_MIST_G, dot(dir, sunDir)))
            + cap * (0.25 * hgDisc(PALL_MIST_G, dot(dir, toBall / max(ballLen, 1e-3)), sqrt(1.0 - sinBall * sinBall))));
    float tr = exp(-tau);
    return vec4(l * (1.0 - tr), tr);
}

// This pixel's ray (gDir above) and shared step data: sun, cosines, soot, brightest flame, pixel width
vec3 gSun;
float gMuS;
float gC;
float gMv;
vec3 gSootK;
float gSootSun;
vec3 gFlame;
float gPixel;
float gJitter;
// whirls: light/transmittance of each and ray entry (main); likewise Wilson crossings (two per slot)
mat4 gWhirlLight;
vec4 gWhirlIn;
mat4 gWilsonLight;
vec4 gWilsonIn;
// eye-to-step mushroom depth: actual billowing depth — skylight from eye side lost before point
float gTauEye;

// Add matter of opacity 1 − tr and light l (already × opacity) at eye distance t to ray. Eye sees pall, whirls and
// Wilson cloud even where mushroom fades in air haze (lost): their light weighted by transmit + lost. Air haze to t
// (as mushroom, pallAir) removes 1 − air of opacity: background behind entire ray visible there — in lost;
// ray transmittance stays real
void passMatter(float t, vec3 l, float tr, inout vec3 light, inout float transmit, inout float lost) {
    float air = pallAir(PallAir, t, gDir.y), seen = transmit + lost;
    light += seen * air * l;
    lost = lost * tr + seen * (1.0 - air) * (1.0 - tr);
    transmit *= tr;
}

// whirls and Wilson crossings entered before t add to ray light (each time)
void passesBefore(float t, inout vec3 light, inout float transmit, inout float lost) {
    for (int k = 0; k < 4; k++) {
        if (gWhirlIn[k] <= t) {
            passMatter(gWhirlIn[k], gWhirlLight[k].rgb, gWhirlLight[k].a, light, transmit, lost);
            gWhirlIn[k] = 1e9;
        }
        if (gWilsonIn[k] <= t) {
            passMatter(gWilsonIn[k], gWilsonLight[k].rgb, gWilsonLight[k].a, light, transmit, lost);
            gWilsonIn[k] = 1e9;
        }
    }
}


// outdoor-air share at p (pall.glsl: pallOpen); terrain beyond window uses first available slot map
float pallOpenHere(vec3 p) {
    if (PallSlot0[1].w > 0.5) return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler5, PallSlot0, PallGround0, p);
    return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler6, PallSlot1, PallGround1, p);
}
// Sun above eye-relative pall point p through mushrooms on its ray: shadow map as world (fx_pall_composite: shade),
// from matter above map midpoint (mushShadowFrom); no map at night
vec3 pallSunAt(vec3 p) {
    if (PallSun.w <= 0.0) return PallSunLight;
    return PallSunLight * mushShadowFrom(Sampler7, Sampler8, mushShadowSun(PallSun.xyz), p, 0.5);
}

void pallLighting(vec3 dir) {
    vec3 sunDir = PallSun.xyz;
    gDir = dir;
    gSun = sunDir;
    gMuS = max(sunDir.y, 0.12);
    gC = dot(dir, sunDir);
    // light path to eye opposes ray: its vertical cosine
    gMv = -dir.y;
    gSootK = vec3(pallK(PALL_SOOT_W.r, PALL_SOOT_G), pallK(PALL_SOOT_W.g, PALL_SOOT_G), pallK(PALL_SOOT_W.b, PALL_SOOT_G));
    gSootSun = 0.25 * hg(PALL_SOOT_G, gC);
    // Brightest frame flame, linear: fires beneath wall, slot storm flames, fires beneath haze (their light color).
    // Point fire is all fires summed: each capped individually (pallFlame), sum capped again — a point surrounded by flame
    // shines at flame brightness, not higher
    vec3 flame = max(pallLinear3(FIRE), max(pallLinear3(PallFire0[3].rgb), pallLinear3(PallFire1[3].rgb)));
    gFlame = max(flame, pallLinear3(PallFireLight / max(PallFireLight.r, 1e-4)));
}

// Shared source light for pall and dust lifted from it into stem base: needed above the former flat top too.
// inflow.xyz is its mushroom base, w lifted dust; ordinary pall uses 0.
vec4 pallSource(vec3 p, float cone, vec4 inflow, out vec3 dustLight) {
    vec3 dir = gDir, sunDir = gSun;
    float muS = gMuS, c = gC, mv = gMv;
    Sample s = emptySample();
    slot(PallSlot0, PallWall0, Sampler5, PallGround0, PallWater0, PallGlow0, PallHot0, p, sunDir, dir, cone, inflow, s);
    slot(PallSlot1, PallWall1, Sampler6, PallGround1, PallWater1, PallGlow1, PallHot1, p, sunDir, dir, cone, inflow, s);
    vec3 f0 = vec3(0.0), f1 = vec3(0.0);
    vec2 around0 = vec2(0.0), around1 = vec2(0.0);
    if (all(greaterThanEqual(p, PallFireMin)) && all(lessThanEqual(p, PallFireMax))) {
        f0 = storm(Sampler3, PallSlot0, PallFire0, p, cone, s.clumps);
        f1 = storm(Sampler4, PallSlot1, PallFire1, p, cone, s.clumps);
        around0 = fireAround(Sampler3, PallSlot0, PallFire0, p, dir, s.sigma);
        around1 = fireAround(Sampler4, PallSlot1, PallFire1, p, dir, s.sigma);
    }
    float soot = f0.x + f1.x;
    float st = s.sigma + soot;
    dustLight = vec3(0.0);
    if (st <= 1e-7 && inflow.w <= 0.0) return vec4(0.0);

    // local material: pall albedo/asymmetry (no pall here but storm smoke — use dust)
    vec3 w = PALL_DUST_W;
    float g = PALL_DUST_G;
    if (s.sigma > 1e-7) {
        w = s.albedo / s.sigma;
        g = s.phase / s.sigma;
    }
    // Above former flat top, slab still sets light for dust lifted by inflow.
    if (st <= 1e-7) {
        vec4 mat = slabMatter(s);
        w = mat.rgb;
        g = mat.a;
    }
    // Diffuse slab light and beams: sun/ball by Beer–Lambert through column above point and billow shadows,
    // Henyey–Greenstein phase (sphere mean 1, beam strength on white wall — hence ¼). Sun above pall first passes mushrooms
    // on its ray (shadow map, as world in fx_pall_composite): dust under cap is shadowed, as is cap-covered stem (mushSunTau)
    vec3 sun = pallSunAt(p);
    vec3 field = scattered(s, g * mv, sun);
    vec3 beams = sun * (0.25 * hg(g, c) * exp(-max(s.tau / muS + s.shadeSun, 0.0)));
    float capI = s.cap.r + s.cap.g + s.cap.b;
    if (capI > 1e-5) {
        float muC = clamp(s.capUp / capI, 0.12, 1.0);
        beams += s.cap * (0.25 * hg(g, clamp(s.capView / capI, -1.0, 1.0)) * exp(-max(s.tau / muC + s.shadeCap, 0.0)));
    }
    // Bottom light — fires beneath haze, storm glow — upward diffuse flux: point scatters toward eye by Eddington
    // ω'·(1/2 + 3/4·g'·μ) (brighter downward toward fire), beneath-point billow shadow as e^(−kτ) of diffuse light;
    // regional glow from side with its own phase (fireAround), half as from a bright half-space
    vec3 kd = vec3(pallK(w.r, g), pallK(w.g, g), pallK(w.b, g));
    vec3 under = (0.5 + 0.75 * g * mv) * exp(-kd * s.shadeDown);
    // storm/regional glow added to display skylight (as particles, fx.vsh), converted to linear by difference,
    // no brighter than flame (pallFlame)
    vec3 glow = vec3(0.0), around = vec3(0.0);
    if (f0.y + f1.y > 0.0) {
        vec3 strongest = f0.y >= f1.y ? PallFire0[3].rgb : PallFire1[3].rgb;
        glow = pallFlame(PallBase, f0.y * PallFire0[3].rgb + f1.y * PallFire1[3].rgb, strongest);
    }
    if (around0.x > 0.0) around += pallFlame(PallBase, 0.5 * around0.x * PallFire0[3].rgb, PallFire0[3].rgb) * exp(-kd * around0.y);
    if (around1.x > 0.0) around += pallFlame(PallBase, 0.5 * around1.x * PallFire1[3].rgb, PallFire1[3].rgb) * exp(-kd * around1.y);
    // point fire sums all fires, capped at flame: fires under dust through slab, bottom glow, fires under haze
    // (s.fire is their light × matter, divide by matter for light), regional glow
    vec3 below = burnt(s, g * mv);
    vec3 fire = min(below + under * (glow + s.fire / max(s.albedo, vec3(1e-7))) + around, gFlame);
    dustLight = w * (field + beams + fire);
    vec3 emit = s.albedo * (field + beams + fire);
    // firestorm column smoke (soot) in same light and self-shadow: direct light attenuated by column depth toward sun,
    // diffuse as e^(−kτ) of soot (sunward side and billow tops bright, opposite side and bottom dark: volumetric column,
    // not flat gray stripe); fire glow below
    if (soot > 0.0) {
        float self = (f0.z + f1.z) / soot;
        vec3 sootLit = field * exp(-gSootK * self) + sun * (gSootSun * exp(-max(s.tau / muS + s.shadeSun, 0.0) - self))
                + min(below * exp(-gSootK * self) + (0.5 + 0.75 * PALL_SOOT_G * mv) * glow + around, gFlame);
        emit += soot * PALL_SOOT_W * sootLit;
    }
    return vec4(emit, st);
}

void pallStep(float ta, float ds, float grid, inout vec3 light, inout float transmit, inout float lost) {
    passesBefore(ta, light, transmit, lost);
    vec3 p = gDir * ta;
    float open = pallOpenHere(p);
    if (open <= 0.0) return;
    float cone = max(ta * gPixel, 0.35 * grid);
    vec3 dustLight;
    vec4 matter = pallSource(p, cone, vec4(0.0), dustLight);
    if (matter.w <= 1e-7) return;
    float tr = exp(-matter.w * open * ds);
    passMatter(ta, matter.rgb * (1.0 - tr) / matter.w, tr, light, transmit, lost);
}

// Mean mushroom m depth from eye-relative p along v over len, four samples (MUSH_AT); parts selects cap, stem, skirt,
// water column
float mushTauOf(Mush m, vec3 p, vec3 v, float len, vec4 parts) {
    float tau = 0.0;
    for (int i = 0; i < 4; i++) {
        tau += dot(mushMean(m, mushLocal(m, p + v * (len * MUSH_AT[i]))), parts) * MUSH_PART[i];
    }
    return tau * len;
}

float mushTau(Mush m, vec3 p, vec3 v, float len) {
    return mushTauOf(m, p, v, len, vec4(1.0));
}

// Billow edge offset at c (from mushroom part center, blocks), fraction of amplitude, to ±0.68: noise channel ch
// (VolumeNoise: R Perlin–Worley patches, B Worley domes), coarse billows at quarter tile, finer within, then four times
// finer while resolved (cone smallest resolved size); k is tile offset, unique pattern per part/mushroom
// billow channels: domes for cap, skirt, water crown (turbulent cloud, spray); patches for stem/column wall stretched
// into rising streams (height-stretched domes on thin stem would make a cell grid)
const vec3 MUSH_DOMES = vec3(0.0, 0.0, 1.0);
const vec3 MUSH_STREAKS = vec3(1.0, 0.0, 0.0);

float mushLobes(vec3 c, float tile, float cone, float k, vec3 ch) {
    vec3 u = c / tile + vec3(0.37, 0.11, 0.73) * k;
    float n = dot(texture(PallNoise, u).rgb, ch);
    float fine = 1.0 - smoothstep(0.5, 1.0, cone * 16.0 / tile);
    float f = 0.5;
    if (fine > 0.0) f = dot(textureLod(PallNoise, u * 4.0 + vec3(0.53, 0.29, 0.17), 0.0).rgb, ch);
    return (n - 0.5) + 0.35 * fine * (f - 0.5);
}

// New inflow dust retains carpet billow scale (256 m per whole tile), not the whole stem's stretched streams.
// Same carpetUv moves nearby carpet. Stem below keeps its old pattern, unmapped.
float mushInflowLobes(Mush m, vec3 q, float cone) {
    mat4 S = PallSlot0;
    if (length(m.base.xz - PallSlot1[0].xy) < 1.0 && PallSlot1[1].w > 0.5) S = PallSlot1;
    if (S[1].w < 0.5 || length(m.base.xz - S[0].xy) > 1.0) {
        return mushLobes(vec3(q.x, 0.5 * (q.y - m.stemRise), q.z), m.stemTile, max(cone, m.stemTile), 4.0, MUSH_STREAKS);
    }
    vec3 p = m.base + q;
    p.xz += m.drift * mushShare(q.y, m.endTop);
    vec3 a, b;
    float blend;
    carpetUv(S, p, warpAt(S, p), carpetFlow(m, S, p), a, b, blend);
    float n = mix(texture(PallNoise, b).r, texture(PallNoise, a).r, blend);
    float fine = 1.0 - smoothstep(0.5, 1.0, cone * 32.0 / max(S[3].y, 1.0));
    float detail = 0.5;
    if (fine > 0.0) detail = mix(textureLod(PallNoise, b * 4.0 + vec3(0.53, 0.29, 0.17), 0.0).r,
            textureLod(PallNoise, a * 4.0 + vec3(0.53, 0.29, 0.17), 0.0).r, blend);
    return n - 0.5 + 0.35 * fine * (detail - 0.5);
}

// Layer j billows of mushroom m cap pattern at body q and churn time, sunk into cap by MUSH_SINK × (1 − layer share):
// pattern rotates by section overturning since layer birth (roll — turnover share while ball rolls into ring),
// unique per layer lifetime
float mushCapLayer(Mush m, vec3 q, float cone, float k, float churn, float roll, float j) {
    float age = mushLayerAge(churn, j);
    vec3 c = mushFlow(m, q, age * MUSH_CYCLE * roll);
    vec3 shift = m.capTile * fract(mushLayerSeed(churn, j) * vec3(0.618034, 0.414214, 0.732051));
    return mushLobes(c + shift, m.capTile, cone, k, MUSH_DOMES) - MUSH_SINK * (1.0 - mushLayerWeight(age));
}

// Mushroom m cap billows at body q: material circulates around ring axis (Turner vortex: up inside, outward over top,
// down outside) at top-rise speed; billows follow (mushFlow). Turbulence lives/dies within: two pattern layers half a
// lifetime apart (Neyret 2003), each rotates from birth, sinks into cap near death, next pattern rises from it; edge is
// the higher layer corrected for mean (mushRise). Cap patches have local clocks (mushPhase). Before full ring roll-up
// (roll < 1), circulation scales by it. Weak layer cannot surface if strong layer exceeds its highest billow: skip it
float mushCapLobes(Mush m, vec3 q, float cone, float k) {
    float roll = clamp(m.ringR / max(MUSH_RING * m.capR, 1.0), 0.0, 1.0);
    float churn = m.churn + MUSH_CYCLE * mushPhase(m, q, k);
    float strong = mushLayerWeight(mushLayerAge(churn, 0.0)) >= 0.5 ? 0.0 : 1.0;
    float w = mushLayerWeight(mushLayerAge(churn, strong));
    float best = mushCapLayer(m, q, cone, k, churn, roll, strong);
    if (best < MUSH_LOBES_TOP - MUSH_SINK * w) best = max(best, mushCapLayer(m, q, cone, k, churn, roll, 1.0 - strong));
    return best + mushRise(1.0 - w);
}

// Extinction of mushroom m cap, stem, skirt, water column at body q with billows, 1/block; lobes cap edge offset as
// amplitude fraction; depth under mean cap edge in cap units (0 at edge); near distance to nearest billowing part edge,
// blocks (estimate: billows no steeper than their tile, ray step half tile; a part beyond its billow reach uses distance
// to that reach: otherwise mushGap's edge step would count as empty and skip outer billows along crown/cap ring).
// Cap, skirt, crown use round domes (same block offset in every direction); stem/column wall use rising streams
// (noise stretched vertically twofold)
vec4 mushDense(Mush m, vec3 q, float cone, float k, out float lobes, out float depth, out float near, out float carried) {
    lobes = 0.0;
    depth = 0.0;
    carried = 0.0;
    near = 1e9;
    vec4 sigma = vec4(0.0);
    vec4 d = mushDist(m, q);
    float uc = mushCapUnit(m);
    if (m.capSigma > 0.0 && d.x < MUSH_CAP_REACH * uc) {
        // below deepest billow hollow cap is entirely dense: no billows needed (every sunward-ray step from shadow)
        if (d.x + (MUSH_LOBES_DEEP * MUSH_CAP_LOBES * m.billows + MUSH_CAP_EDGE) * uc > 0.0) lobes = mushCapLobes(m, q, cone, k);
        float shifted = d.x - MUSH_CAP_LOBES * m.billows * uc * lobes;
        sigma.x = m.capSigma * mushEdge(shifted, MUSH_CAP_EDGE * uc);
        near = shifted - MUSH_CAP_EDGE * uc;
        depth = clamp(-d.x / max(uc, 1.0), 0.0, 1.0);
    } else if (m.capSigma > 0.0) {
        near = d.x - MUSH_CAP_REACH * uc;
    }
    if (m.stemTau > 0.0) {
        float r = mushStemAt(m, q.y), rho = length(q.xz), reach = MUSH_STEM_REACH * r;
        float stem = mushStemSigma(m, q.y);
        if (stem > 0.0 && d.y < reach) {
            float l = mushLobes(vec3(q.x, 0.5 * (q.y - m.stemRise), q.z), m.stemTile, max(cone, m.stemTile), k + 4.0, MUSH_STREAKS);
            float shifted = d.y - MUSH_STEM_LOBES * r * l;
            sigma.y = stem * mushEdge(shifted, MUSH_STEM_EDGE * r);
            near = min(near, shifted - MUSH_STEM_EDGE * r);
        }
        float band = mushFootBandAt(m, q.y);
        if (band > 0.0 && q.y >= 0.0) {
            float df = rho - r - band, foot = mushFootSigma(m, q.y);
            if (foot > 0.0 && df < reach) {
                float l = mushInflowLobes(m, q, cone), join = mushFootNarrow(q.y, m.footTop, m.footMerge);
                if (join < 1.0) l = mix(mushLobes(vec3(q.x, 0.5 * (q.y - m.stemRise), q.z), m.stemTile, max(cone, m.stemTile), k + 4.0, MUSH_STREAKS), l, join);
                float shifted = df - MUSH_STEM_LOBES * r * l;
                carried = foot * mushFootWall(rho, r) * mushEdge(shifted, MUSH_STEM_EDGE * r);
                sigma.y = max(sigma.y, carried);
                near = min(near, shifted - MUSH_STEM_EDGE * r);
            } else if (foot > 0.0) {
                near = min(near, df - reach);
            }
        }
    }
    float uk = mushSkirtUnit(m);
    if (m.skirtSigma > 0.0 && d.z < MUSH_SKIRT_REACH * uk) {
        float l = mushLobes(q, max(2.0 * m.skirtH, 1.0), cone, k + 8.0, MUSH_DOMES);
        float shifted = d.z - MUSH_SKIRT_LOBES * uk * l;
        sigma.z = m.skirtSigma * mushEdge(shifted, MUSH_SKIRT_EDGE * uk);
        near = min(near, shifted - MUSH_SKIRT_EDGE * uk);
    } else if (m.skirtSigma > 0.0) {
        near = min(near, d.z - MUSH_SKIRT_REACH * uk);
    }
    float uw = mushColumnUnit(m);
    if (m.colSigma > 0.0 && d.w < MUSH_COLUMN_REACH * uw) {
        // wall water streams: patches stretched vertically twofold, moving with water (up while growing, down while falling);
        // no fine detail, stretched by rise; crown uses stopped-stream spray domes
        float crown = smoothstep(m.colTop - m.crownH, m.colTop, q.y), l = 0.0;
        if (crown < 1.0) l = mushLobes(vec3(q.x, 0.5 * (q.y - m.colRise), q.z), m.colTile, max(cone, m.colTile), k + 12.0, MUSH_STREAKS);
        if (crown > 0.0) l = mix(l, mushLobes(vec3(q.x, q.y - m.colTop, q.z), m.colTile, cone, k + 12.0, MUSH_DOMES), crown);
        float shifted = d.w - MUSH_COLUMN_LOBES * uw * l;
        sigma.w = m.colSigma * mushEdge(shifted, MUSH_COLUMN_EDGE * uw);
        near = min(near, shifted - MUSH_COLUMN_EDGE * uw);
    } else if (m.colSigma > 0.0) {
        near = min(near, d.w - MUSH_COLUMN_REACH * uw);
    }
    return sigma;
}

// Mushroom m sunward depth from eye-relative p. Nearby 0.75 billow tile uses actual billows, five segments each twice
// as long, frame-jittered sample within each (sunlit billow side and neighbor shadow give “cauliflower” cap); farther,
// mean field: billows average to it beyond the billow layer. Mean field from point would cover even sunlit billows with
// soft-edge depth along the surface (tens of optical depths sideways to sun)
float mushSunTau(Mush m, vec3 p, float k) {
    float reach = 0.75 * m.capTile, len = reach / 31.0, t = 0.0, tau = 0.0;
    for (int i = 0; i < 5; i++) {
        float lobes, depth, near, carried;
        tau += dot(mushDense(m, mushLocal(m, p + gSun * (t + gJitter * len)), len, k, lobes, depth, near, carried), vec4(1.0)) * len;
        t += len;
        len *= 2.0;
    }
    return tau + mushTau(m, p + gSun * reach, gSun, 2.0 * m.boundR);
}

// Mushroom m ball surface pattern at body q (NukeBallSurface): local temperature excess relative to mean, by direction
// from cap center (height-dependent drift in mushLocal), frame m.ballFrame; before front breakaway pattern emerges by
// mushBallContrast, using first frame
float mushBallPattern(Mush m, vec3 q) {
    vec3 n = vec3(q.x, q.y - m.capY, q.z);
    if (dot(n, n) < 1e-6) return 1.0;
    float frames = float(textureSize(PallBall, 0).z);
    float f = max(m.ballFrame, 0.0);
    float p = texture(PallBall, vec3(mushOctU(n.x, n.y, n.z), mushOctV(n.x, n.y, n.z), (f + 0.5) / frames)).r * BALL_P_MAX;
    return mix(1.0, p, mushBallContrast(m.ballFrame));
}

// Each height block stores radii in rows, azimuth across X (last column repeats first).
// Two bilinear samples give trilinear light without azimuth seam or mixing adjacent heights/mushrooms.
vec3 mushFootLight(int k, Mush m, vec3 q) {
    float r = mushStemAt(m, q.y), band = mushFootBandAt(m, q.y);
    float radius = clamp((length(q.xz) - 0.6 * r) / max((0.4 + MUSH_STEM_REACH) * r + band, 1.0), 0.0, 1.0);
    float az = fract(atan(q.z, q.x) / 6.28318530718 + 1.0);
    float h = clamp(q.y / max(m.footMerge, 1e-6), 0.0, 1.0) * float(FOOT_LIGHT_H - 1);
    float hi = min(floor(h), float(FOOT_LIGHT_H - 2));
    float rows = float(FOOT_LIGHT_H * FOOT_LIGHT_R * MUSHROOMS);
    vec2 uv = vec2((az * float(FOOT_LIGHT_AZ) + 0.5) / float(FOOT_LIGHT_AZ + 1),
            (float(k * FOOT_LIGHT_H * FOOT_LIGHT_R) + hi * float(FOOT_LIGHT_R) + radius * float(FOOT_LIGHT_R - 1) + 0.5) / rows);
    return mix(texture(Sampler9, uv).rgb, texture(Sampler9, uv + vec2(0.0, float(FOOT_LIGHT_R) / rows)).rgb, h - hi);
}

// Mushroom k at eye-relative p and local q: extinction (w, 1/block), eye-scattered light per path unit
// (rgb, Σ extinction × source function); own emission separate (end of main): hot unchanged (previous path), glow
// ball-exposed (film curve), film its luminance × curve share (mushFilmShare). cone smallest resolved size
vec4 mushAt(int k, Mush m, vec3 p, vec3 q, float cone, out float near, out vec3 hot, out vec3 glow, out float film) {
    hot = vec3(0.0);
    glow = vec3(0.0);
    film = 0.0;
    float lobes, depth, carried;
    float kk = float(k) * 13.0;
    vec4 sig = mushDense(m, q, cone, kk, lobes, depth, near, carried);
    float st = dot(sig, vec4(1.0));
    if (st <= 1e-7) return vec4(0.0);
    float fromCarpet = 0.0;
    // Carpet light only from its own active field and in base ray interval. Other mushrooms use stem light.
    if (PallFootReady > 0 && (k == gFoot0 || k == gFoot1)) fromCarpet = mushFootLightShare(q.y, m.footTop, m.footMerge, min(carried, sig.y), st);
    vec3 carpetLight = vec3(0.0);
    if (fromCarpet > 0.0) carpetLight = mushFootLight(k, m, q);
    if (fromCarpet >= 1.0) return vec4(st * carpetLight, st);
    vec3 w = (sig.x * m.capMat.rgb + sig.y * m.stemMat.rgb + sig.z * m.skirtMat.rgb + sig.w * m.colMat.rgb) / st;
    float g = (sig.x * m.capMat.a + sig.y * m.stemMat.a + sig.z * m.skirtMat.a + sig.w * m.colMat.a) / st;
    vec3 kd = vec3(pallK(w.r, g), pallK(w.g, g), pallK(w.b, g));
    // mean depth: up to bounds top, down to ground, toward/away from eye; daytime also toward/away from sun
    float up = mushTau(m, p, vec3(0.0, 1.0, 0.0), max(m.boundTop - q.y, 1.0));
    float down = mushTau(m, p, vec3(0.0, -1.0, 0.0), max(q.y, 1.0));
    float across = 2.0 * m.boundR;
    // toward eye, depth traveled by ray (with billows: a hollow point is eye-exposed; mean field would block it with
    // nonexistent foreground billows); away from eye, mean field
    float toEye = gTauEye, beyond = mushTau(m, p, gDir, across);
    vec3 light = vec3(0.0);
    // emission escape depth: shortest optical depth
    float escape = min(min(up, down), toEye);
    if (PallSun.w > 0.0) {
        float toSun = mushSunTau(m, p, kk), away = mushTau(m, p, -gSun, across);
        escape = min(escape, min(toSun, away));
        // sun beam along slab through point on sun axis (cosine 1): its diffuse light and direct beam
        float cs = g * dot(-gDir, gSun);
        light += vec3(pallDiffuse(w.r, g, toSun, toSun + away, 0.0, 1.0, PallSunLight.r, 1.0, 0.0, 0.0, 0.0, cs),
                pallDiffuse(w.g, g, toSun, toSun + away, 0.0, 1.0, PallSunLight.g, 1.0, 0.0, 0.0, 0.0, cs),
                pallDiffuse(w.b, g, toSun, toSun + away, 0.0, 1.0, PallSunLight.b, 1.0, 0.0, 0.0, 0.0, cs));
        light += PallSunLight * (0.25 * hg(g, gC) * exp(-toSun));
    }
    // Ball/cap light on stem, skirt, water column (MushroomModel.light): Lambertian sphere of cap radius, black-body
    // radiance at visible exterior temperature (core excess × edge share), L·R²/d², beam from cap center like sun, with
    // stem/skirt/column depth on path (to ball, beyond it emitting surface). Cap itself does not receive it: its light is
    // its own emission (below)
    float lowShare = (sig.y + sig.z + sig.w) / st;
    if (lowShare > 0.0 && m.capSigma > 0.0) {
        // toward cap center in world axes: height-dependent wind drift (mushLocal) means mushroom body axes are not world axes
        vec3 c = vec3(0.0, m.capY, 0.0) - q;
        c.xz += m.drift * (mushShare(m.capY, m.endTop) - mushShare(q.y, m.endTop));
        float d2 = dot(c, c), r2 = m.capR * m.capR;
        vec3 ball = mushBlackBody(Sampler7, m.tAmb + m.dT * m.edge) * (PallExposure * r2 / max(d2, r2));
        if (dot(ball, LUMA) > 1e-4 * dot(PallSkyLight, LUMA)) {
            float dc = sqrt(d2);
            vec3 v = c / max(dc, 1e-3);
            float toBall = mushTauOf(m, p, v, max(dc - m.capR, 0.0), vec4(0.0, 1.0, 1.0, 1.0)), away = mushTau(m, p, -v, across);
            float cb = g * dot(-gDir, v);
            vec3 lit = vec3(pallDiffuse(w.r, g, toBall, toBall + away, 0.0, 1.0, ball.r, 1.0, 0.0, 0.0, 0.0, cb),
                    pallDiffuse(w.g, g, toBall, toBall + away, 0.0, 1.0, ball.g, 1.0, 0.0, 0.0, 0.0, cb),
                    pallDiffuse(w.b, g, toBall, toBall + away, 0.0, 1.0, ball.b, 1.0, 0.0, 0.0, 0.0, cb));
            light += lowShare * (max(lit, vec3(0.0)) + ball * (0.25 * hg(g, dot(gDir, v)) * exp(-toBall)));
        }
    }
    // Diffuse sky/ground light. Mushroom is a body, not slab: sky lights a side point from side, not through whole cap
    // above it. Two slabs through point: vertical (sky above, and cap emission below cap; mushroom-shadowed ground-reflected
    // sun and fires below), and view ray (horizon sky from both sides: half sky plus ground light). Point uses brighter
    // contribution: a longer path does not reduce light arriving by the shortest.
    // Sky seen from a cap-billow hollow through a gap (depth h beneath billow tops, neighbors half-billow c away:
    // illuminance share c/√(c² + h²)); rest blocked by neighboring walls, lit by reflection: diffuse-walled cavity light
    // is gap share / (1 − R∞·(1 − share)), R∞ thick-layer material reflectance (mushWallR: two streams, 0 for pure absorption —
    // ball before droplets — to 1). Without it, cap shadow side is flat fill: peaks and hollows see the same sky
    float hc = MUSH_CAP_LOBES * m.billows * mushCapUnit(m) * max(0.68 - lobes, 0.0), cc = 0.125 * m.capTile;
    float slot = cc / sqrt(hc * hc + cc * cc);
    vec3 rinf = vec3(mushWallR(w.r, g), mushWallR(w.g, g), mushWallR(w.b, g));
    vec3 open = mix(vec3(1.0), slot / (1.0 - rinf * (1.0 - slot)), sig.x / st);
    vec3 sky = PallSkyLight * open;
    // ground beneath mushroom is dust-layer top at stem base (MushroomField.groundUnder), not bare ground: stem stands on it
    vec3 floorR = texelFetch(Sampler7, ivec2(MUSH_SHADOW_TOP, k), 0).yzw;
    vec3 land = PallSunLight * floorR * max(PallSun.y, 0.0);
    vec3 bottom = land * exp(-min((up + down) / gMuS, 30.0));
    // fires below as storm glow beneath pall (storm): ground light (m.fire, display); aloft, visible fire-disk share
    // (R²/(R² + h²), as pall light from full ~2h-wide area) and storm smoke beneath point (all above its top): absent over
    // water and by day; burning city colors stem base at night, barely the cap kilometers above
    if (m.fire.r > 0.0) {
        float h = max(q.y, 0.0), hs = min(h, m.fireTop), r2 = m.fireR * m.fireR;
        float through = 0.75 * exp(-hs / (0.2 * m.fireTop)) + 0.25 * exp(-hs / m.fireTop);
        bottom += pallFlame(PallBase, m.fire * (through * r2 / max(r2 + h * h, 1.0)), m.fire / m.fire.r);
    }
    float cv = g * gMv;
    vec3 vertical = vec3(pallDiffuse(w.r, g, up, up + down, floorR.r, 1.0, 0.0, 1.0, 0.0, sky.r, bottom.r, cv),
            pallDiffuse(w.g, g, up, up + down, floorR.g, 1.0, 0.0, 1.0, 0.0, sky.g, bottom.g, cv),
            pallDiffuse(w.b, g, up, up + down, floorR.b, 1.0, 0.0, 1.0, 0.0, sky.b, bottom.b, cv));
    vec3 side = 0.5 * open * (PallSkyLight + floorR * PallSkyLight + land);
    vec3 sideways = vec3(pallDiffuse(w.r, g, toEye, toEye + beyond, 0.0, 1.0, 0.0, 1.0, 0.0, side.r, side.r, g),
            pallDiffuse(w.g, g, toEye, toEye + beyond, 0.0, 1.0, 0.0, 1.0, 0.0, side.g, side.g, g),
            pallDiffuse(w.b, g, toEye, toEye + beyond, 0.0, 1.0, 0.0, 1.0, 0.0, side.b, side.b, g));
    light += max(max(vertical, sideways), vec3(0.0));
    // emission: core temperature excess share — all for ball (nearly isothermal); rolled ring cap hotter toward core
    // (edge mixed with entrained air, m.edge of excess; billow tongues ± MUSH_SKIN of it); stem hot only near cap (ring
    // draws dusty air from below); skirt and water column cold
    float hw = clamp(q.y / max(m.capBot, 1.0), 0.0, 1.0);
    float skin = m.edge * (1.0 + MUSH_SKIN * lobes);
    // ball edge excess uses surface pattern (baked bubble, NukeBallSurface): hotter/colder than mean;
    // billow tongues are its ring share
    if (m.ballShare > 0.0 && sig.x > 0.0) skin = m.edge * mix(1.0 + MUSH_SKIN * lobes, mushBallPattern(m, q), m.ballShare);
    skin += (1.0 - m.edge) * smoothstep(0.0, 1.0, depth);
    float heat = (sig.x * skin + sig.y * 0.6 * hw * hw) / st;
    vec3 bb = mushBlackBody(Sampler7, m.tAmb + m.dT * heat) * PallExposure;
    // emission source function: black-body radiance at depth (internal scattering replenishes it); at edge, share ω'
    // escapes unreplaced (two streams: deficit decays inward as e^(−kτ))
    vec3 thermal = bb * (1.0 - w * 0.6 * exp(-kd * escape));
    vec3 source = w * light;
    if (fromCarpet > 0.0) source = mix(source, carpetLight, fromCarpet);
    hot = st * thermal * (1.0 - fromCarpet);
    // Ball exposure uses visible surface (cap edge temperature), as seen through air to point: a ball hundreds/thousands
    // of whites clipped to a flat disk; this preserves surface temperature pattern
    float key = dot(mushBlackBody(Sampler7, m.tAmb + m.dT * m.edge), LUMA) * PallExposure * pallAir(PallAir, length(p), gDir.y);
    glow = hot * mushBallGain(key, PallExposure);
    // Film curve while ball remains a ball (ring share, mushFilmShare); rolled cap uses previous path with pall:
    // per-channel curve whitened orange interior at night and changed it through haze (review 441)
    float roll = clamp(m.ringR / max(MUSH_RING * m.capR, 1.0), 0.0, 1.0);
    film = dot(hot, LUMA) * mushFilmShare(roll);
    return vec4(st * source, st);
}

// Mushroom step at ray t: extinction (w), scattered light per path unit (rgb); emission in heat (unchanged), glow
// (ball-exposed), film (luminance × curve share); gap is distance to matter (0 — here)
// Шаг грибов в месте t луча
vec4 mushStep(float t, float cone, out float gap, out vec3 heat, out vec3 glow, out float film) {
    vec4 sum = vec4(0.0);
    heat = vec3(0.0);
    glow = vec3(0.0);
    film = 0.0;
    gap = 1e9;
    vec3 p = gDir * t;
    for (int k = 0; k < gMushes; k++) {
        vec2 span = gMushSpan[k];
        if (span.y <= span.x || t > span.y) continue;
        if (t < span.x) {
            // step to mushroom bounds
            gap = min(gap, span.x - t);
            continue;
        }
        Mush m = mushLoad(Sampler7, k);
        vec3 q = mushLocal(m, p);
        float g = mushGap(m, q);
        if (g > 0.0) {
            gap = min(gap, g);
            continue;
        }
        // at edge without matter, step toward billowing edge (half estimate); otherwise ray would cross billow band in
        // pixel-sized steps and exhaust steps at the edge
        float near, partFilm;
        vec3 partHeat, partGlow;
        vec4 r = mushAt(k, m, p, q, cone, near, partHeat, partGlow, partFilm);
        gap = min(gap, r.w > 1e-7 ? 0.0 : 0.5 * max(near, 0.0));
        sum += r;
        heat += partHeat;
        glow += partGlow;
        film += partFilm;
    }
    return sum;
}

// ---------------------------------------------------------------- conventional blast smoke (smoke.glsl)
// Smoke billow tile, blocks, and fine tile (four times smaller)
const float SMOKE_TILE = 32.0;

// Smoke billows at eye-relative p: density multiplier averaging 1 (pallShape, pallDetail: same noise as pall).
// Noise follows matter: two-phase update (Neyret 2003), two samples advected at matter velocity v (wind and rise from
// grid) by period fraction, offset by half-period, triangular phase weights: neither sample travels beyond
// period × speed, weights sum to 1. Fine billows only while resolved (cone — pixel width or third of step).
float smokeClumps(vec3 p, vec3 v, float cone) {
    float period = SmokeWind.w, phase = SmokeNoise.w / period;
    vec3 base = (p + SmokeNoise.xyz) / SMOKE_TILE;
    float sum = 0.0;
    for (int k = 0; k < 2; k++) {
        float f = fract(phase + 0.5 * float(k));
        float weight = 1.0 - abs(2.0 * f - 1.0);
        vec3 q = base - v * (f * period / SMOKE_TILE) + vec3(0.37, 0.13, 0.71) * float(k);
        vec3 n = texture(PallNoise, q).rgb;
        float shape = pallShape(n.rg, 0.5);
        if (cone < SMOKE_TILE / 32.0) shape *= pallDetail(textureLod(PallNoise, q * 4.0 + vec3(0.53, 0.29, 0.17), 0.0).r);
        sum += weight * shape;
    }
    return sum;
}

// Smoke step at ray t: extinction (w), light per path unit (rgb), source function × extinction. Material is grid soot,
// dust, droplets (albedo/asymmetry weighted by extinction), noisy billows; light as mushroom body (mushAt): Eddington
// two streams along sun (mean grid depths toward/away sun, four samples), vertically (sky above, ground below), direct
// sun beam with Henyey–Greenstein phase; frame fireball light (L·R²/d²) with phase; own emission by Kirchhoff:
// (1 − ω')·B(T), black body at local gas temperature (body row of mushroom table). No underground smoke (tunnel, house):
// outdoor-air share in eye window. gap is empty distance to next sample (0 — matter here, smokeGap); cell cascade cell there.
vec4 smokeStep(float t, float cone, out float gap, out float cell) {
    vec3 p = gDir * t;
    vec4 a = smokeFetch(p, 0.0);
    float sigma = a.x + a.y + a.z;
    if (sigma < 1e-5) {
        gap = smokeGap(p, gDir, cell);
        return vec4(0.0);
    }
    gap = 0.0;
    cell = smokeCell(p);
    float open = 1.0;
    if (PallOpenAt.w > 0.5) {
        vec3 o = p - PallOpenAt.xyz;
        if (all(greaterThanEqual(o, vec3(0.0))) && all(lessThan(o, PALL_OPEN_SIZE))) open = pallOpenHere(p);
    }
    if (open <= 0.0) return vec4(0.0);
    vec4 b = smokeFetch(p, 1.0);
    float rise = b.w / sigma;
    vec3 v = vec3(SmokeWind.x, SmokeWind.y + rise, SmokeWind.z);
    float clumps = smokeClumps(p, v, cone);
    float st = sigma * clumps * open;
    if (st <= 1e-7) return vec4(0.0);
    vec3 dustW = b.rgb / max(a.y, 1e-7);
    vec3 w = (a.x * SMOKE_SOOT_W + a.y * dustW + a.z * SMOKE_DROP_W) / sigma;
    float g = (a.x * SMOKE_SOOT_G + a.y * SMOKE_DUST_G + a.z * SMOKE_DROP_G) / sigma;
    // mean grid depths toward light: local point includes its billows (self-shadow), farther samples use mean field
    float h = 2.0 * cell;
    float self = st * h;
    float up = self + smokeSigma(p + vec3(0.0, 3.0 * h, 0.0)) * 5.0 * h;
    float down = self + smokeSigma(p - vec3(0.0, 3.0 * h, 0.0)) * 5.0 * h;
    vec3 light = vec3(0.0);
    if (PallSun.w > 0.0) {
        float toSun = self + smokeSigma(p + gSun * h) * 1.5 * h + smokeSigma(p + gSun * 4.0 * h) * 4.5 * h;
        float away = self + smokeSigma(p - gSun * 3.0 * h) * 5.0 * h;
        float cs = g * dot(-gDir, gSun);
        light += vec3(pallDiffuse(w.r, g, toSun, toSun + away, 0.0, 1.0, PallSunLight.r, 1.0, 0.0, 0.0, 0.0, cs),
                pallDiffuse(w.g, g, toSun, toSun + away, 0.0, 1.0, PallSunLight.g, 1.0, 0.0, 0.0, 0.0, cs),
                pallDiffuse(w.b, g, toSun, toSun + away, 0.0, 1.0, PallSunLight.b, 1.0, 0.0, 0.0, 0.0, cs));
        light += PallSunLight * (0.25 * hg(g, gC) * exp(-toSun));
    }
    vec3 land = PallSunLight * (PALL_LAND * max(PallSun.y, 0.0));
    float cv = g * gMv;
    vec3 sky = PallSkyLight, bottom = land * exp(-min((up + down) / gMuS, 30.0));
    vec3 vertical = vec3(pallDiffuse(w.r, g, up, up + down, PALL_LAND, 1.0, 0.0, 1.0, 0.0, sky.r, bottom.r, cv),
            pallDiffuse(w.g, g, up, up + down, PALL_LAND, 1.0, 0.0, 1.0, 0.0, sky.g, bottom.g, cv),
            pallDiffuse(w.b, g, up, up + down, PALL_LAND, 1.0, 0.0, 1.0, 0.0, sky.b, bottom.b, cv));
    light += max(vertical, vec3(0.0));
    // frame fireballs: Lambertian sphere radius R radiates L·R²/max(d², R²), through smoke at half-path (single scattering;
    // cloud shines from within by its own emission below)
    for (int i = 0; i < 4; i++) {
        float r = SmokeBalls[i].w;
        if (r <= 0.0) continue;
        vec3 c = SmokeBalls[i].xyz - p;
        float d2 = max(dot(c, c), r * r), d = sqrt(d2);
        vec3 l = SmokeBallLight[i].rgb * (r * r / d2);
        float through = smokeSigma(p + 0.5 * c) * max(d - r, 0.0);
        light += l * (0.25 * hg(g, dot(gDir, c / max(d, 1e-3))) * exp(-min(through, 30.0)));
    }
    // gas emission: Kirchhoff — absorbed share emits black-body light at its temperature
    float kelvin = SMOKE_AIR_K + a.w * 1000.0 / sigma;
    vec3 bb = kelvin > 600.0 ? mushBlackBody(Sampler7, kelvin) * PallExposure : vec3(0.0);
    return vec4(st * (w * light + (1.0 - w) * bb), st);
}

#ifndef PALL_FOOT_CACHE
void main() {
    vec2 low = gl_FragCoord.xy;
    ivec2 full = ivec2((floor(low) + 0.5) * PallFrame.z);
    ivec2 size = textureSize(Sampler0, 0);
    float depth = texelFetch(Sampler0, clamp(full, ivec2(0), size - 1), 0).r;
    vec2 ndc = (vec2(full) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    // ray-frame pixel width at distance 1
    vec4 nextPoint = InvViewProj * vec4(ndc + vec2(0.0, 2.0 * PallFrame.z / float(size.y)), 1.0, 1.0);
    gPixel = length(normalize(nextPoint.xyz / nextPoint.w) - dir);
    float axis = max(dot(dir, PallForward), 1e-3);
    float scene = pallScene(depth);
    vec2 span = pallBox(dir, PallMin, PallMax);
    float jitter = fract(texelFetch(Sampler2, ivec2(mod(low, 64.0)), 0).r + PallFrame.x * 0.618034);
    gJitter = jitter;
    float clip = dir.y >= 0.0 ? pallClip(PallFrame.w, PallEyeTau.x, dir.y) : pallClip(PallFrame.w, PallEyeTau.y, -dir.y);
    // and no closer than half a billow tile: clipping uses mean depth, but eye may lie in a gap (noise cell quarter tile).
    // In dense dust (meters of visibility), a ray clipped inside a gap never reached its walls, leaving sky visible through
    // tens of optical depths
    clip = max(clip, 0.5 * max(max(PallSlot0[3].y, 0.0) * step(0.5, PallSlot0[1].w), max(PallSlot1[3].y, 0.0) * step(0.5, PallSlot1[1].w)));
    if (scene < 1e8) {
        scene /= axis;
    } else if (span.y > span.x) {
        // no world behind pixel (beyond render range, beyond/between Distant Horizons LODs): ray ends on pall terrain (PallGround),
        // not epicenter ground plane — from a lower valley it would go beneath that plane at the eye, leaving no pall below horizon
        float end = min(span.y, span.x + clip);
        scene = min(pallFloor(Sampler5, PallSlot0, PallGround0, dir, PallFloorFrom, end, FLOOR_STEPS, jitter),
                pallFloor(Sampler6, PallSlot1, PallGround1, dir, PallFloorFrom, end, FLOOR_STEPS, jitter));
    }
    float t0 = span.x, t1 = min(min(span.y, scene), t0 + clip);
    vec3 sunDir = PallSun.xyz;
    pallLighting(dir);
    vec3 whirlFlame = max(gFlame, WHIRL_FIRE_LINEAR);
    vec3 light = vec3(0.0);
    // mushroom emission (mushAt): unchanged (previous path), ball-exposed (film curve), and luminance × curve share
    vec3 heat = vec3(0.0), emitted = vec3(0.0);
    float filmed = 0.0;
    float transmit = 1.0;
    // coverage mushroom would have blocked but air haze removed (pallAir): background behind entire ray visible there
    // (sky, distant world), not mushroom's own depth; ray transmittance stays real, rear billows hidden behind front ones
    float lost = 0.0;
    // whirls: ray interval and light precomputed, added to pall ray when step reaches entry (before world behind pixel)
    gMushes = 0;
    gFoot0 = -1;
    gFoot1 = -1;
    gFootSpan0 = gFootSpan1 = vec2(1.0, 0.0);
    gWhirlLight = mat4(0.0);
    gWhirlIn = vec4(1e9);
    for (int i = 0; i < 4; i++) {
        vec2 sp = whirlSpan(i, dir);
        sp.y = min(sp.y, scene);
        if (sp.y <= sp.x) continue;
        // local light: pall diffuse and sun through pall above midpoint, fire glow below
        vec3 mid = dir * (0.5 * (sp.x + sp.y));
        Sample m = emptySample();
        slot(PallSlot0, PallWall0, Sampler5, PallGround0, PallWater0, PallGlow0, PallHot0, mid, sunDir, dir, 1e9, vec4(0.0), m);
        slot(PallSlot1, PallWall1, Sampler6, PallGround1, PallWater1, PallGlow1, PallHot1, mid, sunDir, dir, 1e9, vec4(0.0), m);
        vec3 sun = pallSunAt(mid);
        vec3 lit = scattered(m, PALL_SOOT_G * gMv, sun) + sun * (gSootSun * exp(-m.tau / gMuS));
        float g0 = storm(Sampler3, PallSlot0, PallFire0, mid, 0.0, 1.0).y, g1 = storm(Sampler4, PallSlot1, PallFire1, mid, 0.0, 1.0).y;
        vec3 strongest = g0 >= g1 ? PallFire0[3].rgb : PallFire1[3].rgb;
        vec3 glow = pallFlame(PallBase, g0 * PallFire0[3].rgb + g1 * PallFire1[3].rgb, strongest);
        gWhirlLight[i] = whirl(i, dir, sp.x, sp.y, jitter, lit, burnt(m, PALL_SOOT_G * gMv) + glow, whirlFlame);
        gWhirlIn[i] = sp.x;
    }
    // Wilson cloud: up to two shell crossings per slot, likewise when step reaches crossing (before world behind pixel)
    vec4 w0 = pallWilson(PallSlot0, PallWilson0, dir, scene), w1 = pallWilson(PallSlot1, PallWilson1, dir, scene);
    gWilsonLight = mat4(wilsonLight(PallSlot0, PallGlow0, PallHot0, PallWilson0, dir, sunDir, w0.x, w0.y),
            wilsonLight(PallSlot0, PallGlow0, PallHot0, PallWilson0, dir, sunDir, w0.z, w0.w),
            wilsonLight(PallSlot1, PallGlow1, PallHot1, PallWilson1, dir, sunDir, w1.x, w1.y),
            wilsonLight(PallSlot1, PallGlow1, PallHot1, PallWilson1, dir, sunDir, w1.z, w1.w));
    gWilsonIn = vec4(w0.x, w0.z, w1.x, w1.z);
    if (w0.y <= 0.0) gWilsonIn.x = 1e9;
    if (w0.w <= 0.0) gWilsonIn.y = 1e9;
    if (w1.y <= 0.0) gWilsonIn.z = 1e9;
    if (w1.w <= 0.0) gWilsonIn.w = 1e9;
    // mushrooms: individual ray intervals (before world behind pixel) and combined interval
    float m0 = 1e9, m1 = -1.0;
    gMushes = 0;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(Sampler7, k)) break;
        Mush body = mushLoad(Sampler7, k);
        vec2 sp = mushSpan(body, dir);
        sp.y = min(sp.y, scene);
        gMushSpan[k] = sp;
        gMushes += 1;
        if (body.footShare > 0.0 && body.footMerge > 0.0) {
            Mush foot = body;
            foot.boundR = foot.stemR + foot.stemFlare + 2.0 * foot.footTop + length(foot.drift);
            foot.boundTop = foot.footMerge;
            vec2 fs = mushSpan(foot, dir);
            fs.y = min(fs.y, scene);
            if (fs.y > fs.x) {
                if (length(foot.base.xz - PallSlot0[0].xy) < 1.0) { gFoot0 = k; gFootSpan0 = fs; }
                if (length(foot.base.xz - PallSlot1[0].xy) < 1.0) { gFoot1 = k; gFootSpan1 = fs; }
            }
        }
        if (sp.y <= sp.x) continue;
        m0 = min(m0, sp.x);
        m1 = max(m1, sp.y);
    }
    int steps = int(PallFrame.y);
    float n = float(steps);
    // pall steps denser near eye; storm ray bounds get half the steps (columns smaller than whole-pall step)
    float fa = t1, fb = t1, a = 1.0, b = 0.0;
    // dust wall head: ray intervals (entry, exit), grid step below
    vec4 enter = vec4(1e9), leave = vec4(1e9), fineStep = vec4(1.0);
    if (t1 > t0) {
        vec2 fs = pallBox(dir, PallFireMin, PallFireMax);
        if (PallFireMax.x > PallFireMin.x && min(fs.y, t1) - max(fs.x, t0) > 1.0) {
            fa = max(fs.x, t0);
            fb = min(fs.y, t1);
            a = fa - t0 > 1.0 ? 0.25 : 0.0;
            float after = t1 - fb > 1.0 ? 0.2 : 0.0;
            b = 1.0 - a - after;
        }
        // Denser dust-head marching: step one eighth of head height (a roller thicker than step would be hit or missed,
        // accumulating shimmer instead of billows); at most steps extra steps across all intervals. Each interval has its own
        // entry-based grid with frame jitter; ordinary steps inside are skipped
        vec4 hs0 = pallWallSpans(PallSlot0, PallWall0, PallGround0, dir, t0, t1);
        vec4 hs1 = pallWallSpans(PallSlot1, PallWall1, PallGround1, dir, t0, t1);
        enter = vec4(hs0.x, hs0.z, hs1.x, hs1.z);
        leave = vec4(hs0.y, hs0.w, hs1.y, hs1.w);
        fineStep = vec4(0.12 * PallWall0[0].z, 0.12 * PallWall0[0].z, 0.12 * PallWall1[0].z, 0.12 * PallWall1[0].z);
        vec4 want = max(leave - enter, vec4(0.0)) / max(fineStep, vec4(1.0));
        float wanted = want.x + want.y + want.z + want.w;
        float fit = wanted > n ? n / wanted : 1.0;
        for (int k = 0; k < 4; k++) {
            if (leave[k] <= enter[k]) {
                enter[k] = 1e9;
                leave[k] = 1e9;
                continue;
            }
            fineStep[k] = (leave[k] - enter[k]) / max(floor(want[k] * fit), 1.0);
        }
    }
    // pall: i steps taken (with wall-head steps, up to 2·steps + 16), base ordinary boundaries behind, nextBase next boundary
    int i = 0, base = 0, pallSteps = 2 * steps + 16;
    float ta = 1e9, nextBase = 1e9, lastBase = 1e9;
    if (t1 > t0) {
        ta = along(jitter / n, t0, fa, fb, t1, a, b);
        lastBase = ta;
        nextBase = along((1.0 + jitter) / n, t0, fa, fb, t1, a, b);
    }
    // mushroom: empty space to matter, then half optical depth, no finer than pixel or quarter pall step over its interval
    int mk = 0, mSteps = steps + 16;
    float tauEye = 0.0;
    float dsMin = 1.0, dsMax = 1.0, tm = 1e9;
    // empty step toward matter, capped at one sixth interval: gap estimate sees bounds conservatively
    if (m1 > m0) {
        dsMin = max(0.35 * m0 * gPixel, (m1 - m0) / float(4 * mSteps));
        dsMax = max((m1 - m0) / 6.0, dsMin);
        tm = m0 + jitter * dsMin;
    }
    // conventional smoke marches its grid (smoke.glsl): by local cascade cell and density, through empty space to next cell,
    // no finer than pixel; interval is smoke bounds before world behind pixel
    // empty-cell steps within cascade boxes counted separately (at most three box crossings of N cells): otherwise empty
    // space between salvo clouds would consume steps, leaving smoke beyond undrawn
    int sk = 0, sSteps = steps + 16, se = 0, sEmpty = 3 * SMOKE_N;
    float s0 = 1e9, s1 = -1.0, ts = 1e9;
    if (smokeOn()) {
        vec2 ss = pallBox(dir, SmokeMin, SmokeMax);
        s0 = max(ss.x, 0.0);
        s1 = min(ss.y, scene);
        if (s1 > s0) ts = s0 + jitter * min(smokeCell(dir * s0), 0.25 * (s1 - s0));
    }
    for (int it = 0; it < pallSteps + mSteps + sSteps + sEmpty; it++) {
        bool pallLeft = i < pallSteps && ta < t1;
        // mushroom behind a ray nearly opaque to itself is invisible even in haze (light weighted by transmit)
        bool mushLeft = mk < mSteps && tm < m1 && transmit >= 0.005;
        bool smokeLeft = sk < sSteps && se < sEmpty && ts < s1 && transmit >= 0.005;
        if (!pallLeft && !mushLeft && !smokeLeft) break;
        if (smokeLeft && (!pallLeft || ts < ta) && (!mushLeft || ts < tm)) {
            float cone = max(ts * gPixel, 0.0);
            float gap, cell;
            vec4 r = smokeStep(ts, cone, gap, cell);
            // empty space to next cascade cell/box, frame-jittered by cell fraction; matter by half optical depth,
            // half to two cells, no finer than pixel
            float ds = gap > 0.0 ? gap + jitter * cell : clamp(0.5 / max(r.w, 1e-6), 0.5 * cell, 2.0 * cell);
            ds = min(max(ds, 0.35 * cone), max(s1 - ts, 1e-3));
            if (r.w > 1e-7) {
                passesBefore(ts, light, transmit, lost);
                float tr = exp(-r.w * ds), air = pallAir(PallAir, ts, dir.y);
                light += transmit * air * r.rgb * (1.0 - tr) / r.w;
                lost += transmit * (1.0 - air) * (1.0 - tr);
                transmit *= tr;
            }
            ts += ds;
            if (gap > 0.0) se += 1;
            else sk += 1;
        } else if (pallLeft && (!mushLeft || ta <= tm)) {
            // step end: ordinary boundary or head interval entry; inside, nearest of its grid boundary (or exit) and ordinary
            // boundary: near eye ordinary steps are denser, preserving nearby dust as wall passes through viewer
            float next = nextBase, grid = nextBase - lastBase;
            bool dense = false;
            for (int k = 0; k < 4; k++) {
                if (!dense && enter[k] <= ta && ta < leave[k]) {
                    dense = true;
                    grid = min(grid, fineStep[k]);
                    float j = floor((ta - enter[k]) / fineStep[k] - jitter + 1e-3) + 1.0;
                    next = min(next, min(enter[k] + (j + jitter) * fineStep[k], leave[k]));
                }
            }
            if (!dense) {
                for (int k = 0; k < 4; k++) {
                    if (enter[k] > ta) next = min(next, enter[k]);
                }
            }
            next = min(next, t1);
            for (int k = 0; k < steps && nextBase <= next + 1e-4; k++) {
                base += 1;
                lastBase = nextBase;
                nextBase = along((float(base) + 1.0 + jitter) / n, t0, fa, fb, t1, a, b);
            }
            if (next > ta) pallStep(ta, next - ta, grid, light, transmit, lost);
            i += 1;
            ta = next;
            if (i >= pallSteps || ta >= t1) ta = 1e9;
        } else {
            float cone = max(tm * gPixel, 0.35 * dsMin);
            float gap;
            gTauEye = tauEye;
            vec3 hot, glowing;
            float film;
            vec4 r = mushStep(tm, cone, gap, hot, glowing, film);
            // empty space toward matter plus jittered step fraction: otherwise steps to bounds/edge align every frame's rays
            // at identical positions, defeating accumulation smoothing
            float ds = clamp(gap + jitter * dsMin, dsMin, dsMax);
            if (r.w > 1e-7) {
                ds = clamp(0.5 / r.w, dsMin, dsMax);
                ds = min(ds, m1 - tm);
                passesBefore(tm, light, transmit, lost);
                // air haze to step (pallAir): distant mushroom light/opacity fade into sky behind
                float tr = exp(-r.w * ds), air = pallAir(PallAir, tm, dir.y);
                light += transmit * air * r.rgb * (1.0 - tr) / r.w;
                float part = transmit * air * (1.0 - tr) / r.w;
                heat += part * hot;
                emitted += part * glowing;
                filmed += part * film;
                lost += transmit * (1.0 - air) * (1.0 - tr);
                transmit *= tr;
                tauEye += r.w * ds;
            }
            tm += ds;
            mk += 1;
        }
        if (transmit + lost < 0.005) {
            transmit = 0.0;
            lost = 0.0;
            break;
        }
    }
    // whirls and Wilson cloud beyond last step (before world)
    passesBefore(1e8, light, transmit, lost);
    float cover = 1.0 - transmit - lost;
    // previous path: mushroom emission combined with pall light (pallShown)
    vec3 shown = pallShown(light + heat, cover);
    // Fireball as conventional ball (presentation: NukeExposure): ball-exposed emission (emitted) through per-channel film
    // curve (mushFilm3) over pall, summed in linear. Previous path turned everything above white into single-color pixel
    // coverage — flat disk without folds/pattern. Curve share follows ring share (mushFilmShare): rolled cap uses old path;
    // between them, blend completed images (blending emission would jump: even a small old-path share overexposes the pixel)
    float film = clamp(filmed / max(dot(heat, LUMA), 1e-30), 0.0, 1.0);
    if (film > 0.0) {
        vec3 lin = pallLinear3(pallShown(light, cover)) + mushFilm3(emitted);
        shown = mix(shown, min(vec3(pallDisplay(lin.r), pallDisplay(lin.g), pallDisplay(lin.b)), vec3(1.0)), film);
    }
    // invalid result (NaN/infinity from depth, matrices or field): transmittance −1; accumulation uses previous value here,
    // or pall-free pixel without history (fx_pall_resolve)
    // float32 fraction sum may exceed 1 by an ulp — pallValid would reject the pixel
    vec4 res = vec4(shown, min(transmit + lost, 1.0));
    fragColor = pallValid(res) ? res : vec4(0.0, 0.0, 0.0, -1.0);
}

#else
// One table per frame, not one lighting calculation per ray step. The integration reads it only in carried dust.
void main() {
    ivec2 at = ivec2(gl_FragCoord.xy);
    int k = at.y / (FOOT_LIGHT_R * FOOT_LIGHT_H);
    if (k >= MUSHROOMS || !mushOn(Sampler7, k)) { fragColor = vec4(0.0); return; }
    Mush m = mushLoad(Sampler7, k);
    if (m.footShare <= 0.0 || m.footMerge <= 0.0) { fragColor = vec4(0.0); return; }
    gMushes = 0;
    gFoot0 = -1;
    gFoot1 = -1;
    gFootSpan0 = gFootSpan1 = vec2(0.0, 1e9);
    for (int j = 0; j < MUSHROOMS; j++) {
        if (!mushOn(Sampler7, j)) break;
        Mush other = mushLoad(Sampler7, j);
        gMushes += 1;
        if (length(other.base.xz - PallSlot0[0].xy) < 1.0) gFoot0 = j;
        if (length(other.base.xz - PallSlot1[0].xy) < 1.0) gFoot1 = j;
    }
    if (k != gFoot0 && k != gFoot1) { fragColor = vec4(0.0); return; }
    int layer = (at.y / FOOT_LIGHT_R) % FOOT_LIGHT_H;
    float h = m.footMerge * float(layer) / float(FOOT_LIGHT_H - 1);
    float r = mushStemAt(m, h), band = mushFootBandAt(m, h), width = (0.4 + MUSH_STEM_REACH) * r + band;
    float rho = 0.6 * r + width * float(at.y % FOOT_LIGHT_R) / float(FOOT_LIGHT_R - 1);
    float az = 6.28318530718 * float(at.x % FOOT_LIGHT_AZ) / float(FOOT_LIGHT_AZ);
    vec3 q = vec3(rho * cos(az), h, rho * sin(az));
    vec3 p = m.base + q;
    p.xz += m.drift * mushShare(h, m.endTop);
    gJitter = 0.5;
    pallLighting(p / max(length(p), 1e-3));
    // Filter fine lighting detail below the table cell; moving density/geometry retains its full detail in mushDense.
    float cone = max(m.footMerge / float(FOOT_LIGHT_H - 1), max(width / float(FOOT_LIGHT_R - 1), rho * 6.28318530718 / float(FOOT_LIGHT_AZ)));
    // Probe the radiative source also at empty nodes: the small test density supplies material, not rendered mass.
    float sigma = max(mushFootSigma(m, h) * mushFootWall(rho, r), 1e-6);
    vec3 light;
    pallSource(p, cone, vec4(m.base, sigma), light);
    fragColor = vec4(light, 1.0);
}
#endif
