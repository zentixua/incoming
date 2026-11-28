#version 150

// Nuclear dust wave march at 1/DwDiv resolution (client/fx/layer/DustWallPass): one ray per low-resolution pixel through
// its frame pixel block (an R2 offset within it every frame, kept in the centre's depth class); the world behind it
// (Sampler1, SceneDepth: r — what hides the layer) or, where the scene has none, the relief map ends the ray.
// Output: premultiplied linear light, transmittance. The sample offset along the ray moves every frame (golden-ratio
// sequence over a fixed per-pixel start); fx_dustwall_resolve averages the frames in time.

#moj_import <incoming:pall.glsl>

// every source of the frame: the source table, terrain and material atlases, the outdoor-air window (dustwall.glsl)
#define DW_SOURCES

// the frame air between the eye and the dust (pall.glsl, as the pall's): its transmittance to t along a ray of vertical
// share dy, and its own light along rd in the march's linear light (the composite shows it through pallShown, so a fully
// hazed ray shows the airlight itself, as the pall's haze share does)
uniform float PallFloorFrom;
uniform vec4 PallAir;
uniform vec3 PallAirSky;
uniform vec4 PallAirFog;
float dwAirT(float t, float dy) {
    return pallAir(PallAir, t, dy);
}
vec3 dwAirLight(vec3 rd) {
    // (the film shoulder cannot be inverted near white: a channel over 0.9 shows at ~0.92 under full haze, a known limit)
    return pallLinear3(unshoulder(min(pallAirLightOf(rd, PallAirSky, PallAirFog), vec3(0.9))));
}

#moj_import <incoming:dustwall.glsl>

uniform sampler2D Sampler1;
uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform float DwDiv;
// frame counter (wraps), and the march's step cap
uniform vec2 DwFrame;
// diagnostics (DustWallDiag.probe): k + 1 — the outputs hold the k-th occupied sample's terms (dustwall.glsl gDwProbe*),
// 0 — the picture, −1 the ray ends; below −5 (DustWallDiag.split false) the picture with the split at a clear gap alone
uniform float DwProbe;
// interleaved rays (DustWallPass): x 1 when on; y this frame's parity; z the full march width.
// Each frame marches half of the march pixels, a checkerboard alternating by frame (this target holds them packed two to
// a column pair: pixel (x, y) is march pixel (2x + (y + parity) mod 2, y)); fx_dustwall_resolve fills the rest from history
uniform vec4 DwInterleave;
out vec4 fragColor;
out vec4 wallDepth;
// the occupied intervals (wall_layer.glsl): A's exit × coverage, B's entry × coverage (view depths), A's opacity, A's share of
// the light × coverage
out vec4 wallSplit;

// pcg3d (Jarzynski and Olano 2020, "Hash Functions for GPU Rendering"): three independent uniform values in [0, 1)
vec3 dwPcg3(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return vec3(v >> 8u) * (1.0 / 16777216.0);
}

void main() {
    ivec2 size = textureSize(Sampler1, 0);
    vec2 lowPix = floor(gl_FragCoord.xy);
    if (DwInterleave.x > 0.5) {
        lowPix.x = lowPix.x * 2.0 + mod(lowPix.y + DwInterleave.y, 2.0);
        if (lowPix.x >= DwInterleave.z) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            wallDepth = vec4(0.0, 0.0, 0.0, 1.0);
            wallSplit = vec4(0.0);
            return;
        }
    }
    // the ray moves within its march pixel every frame (R2 sequence) and the history averages it: a fixed ray would draw a
    // quarter-resolution silhouette (the wall's lower edge, nearly level) as a staircase of identical rows of march pixels
    vec2 centre = min((lowPix + 0.5) * DwDiv, vec2(size) - 0.5);
    // each march pixel starts its sequences (the ray within the pixel, the sample offset along it) at its own phases from an
    // integer hash: a spatial noise pattern (interleaved gradient noise) keeps its pattern through the history on a near,
    // moving wall (a regular lattice of dots), and a float sin() hash of large arguments is vendor-dependent and structured
    vec3 phase = dwPcg3(uvec3(uvec2(lowPix), 12142u));
    vec2 sub = fract(phase.xy + vec2(0.7548777, 0.5698403) * DwFrame.x) - 0.5;
    vec2 at = clamp(centre + sub * DwDiv, vec2(0.5), vec2(size) - 0.5);
    // ... within the centre's depth class: the resolve and composite classify the sample by the centre's depth (an offset
    // across a foreground edge put the background's dust onto the foreground)
    float d0 = pallScene(texelFetch(Sampler1, ivec2(centre), 0).r), d1 = pallScene(texelFetch(Sampler1, ivec2(at), 0).r);
    float axis = d1;
    if (!pallSamePlace(d0, d1)) {
        at = centre;
        axis = d0;
    }
    vec2 ndc = at / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float tEnd = axis / max(dot(dir, PallForward), 1e-3);
    float jitter = fract(phase.z + DwFrame.x * 0.618034);
    // no world behind the pixel (beyond the drawn chunks, between LODs): the ray ends on the relief map traced from the eye,
    // as the pall's (pallFloor); where the world is drawn its depth alone ends the ray (the coarse map stood over open
    // streets and hid the dust down them). A ray under the undrawn ground is raised toward its horizon (dwRayFloor: the
    // wave stands on the drawn world's edge, not over a strip of sky)
    // pixel size per block of distance at this resolution (the pixel's own ray, before any raise)
    vec4 side = InvViewProj * vec4(ndc + vec2(2.0 * DwDiv / float(size.x), 0.0), 1.0, 1.0);
    float foot = length(normalize(side.xyz / side.w) - dir);
    float floorT = -1.0;
    if (axis >= 1e8) {
        floorT = dwRayFloor(dir, PallFloorFrom, jitter);
        tEnd = floorT;
    }
    gDwProbeK = DwProbe - 1.0;
    gDwKnotsOn = DwProbe > -5.0;
    fragColor = dwMarch(dir, min(tEnd, 30000.0), jitter, foot, int(DwFrame.y));
    float cover = 1.0 - fragColor.a;
    float axisShare = max(dot(dir, PallForward), 1e-3);
    wallDepth = vec4(vec3(gDwDepth.xy * cover, gDwDepth.z) * axisShare, fragColor.a);
    float lum = dot(fragColor.rgb, DW_LUMA);
    float shareA = cover <= 0.0 ? 1.0 : clamp(gDwSplit.w / max(lum, 1e-6), 0.0, 1.0);
    if (lum <= 1e-6) shareA = 1.0;
    wallSplit = vec4(gDwSplit.xy * cover * axisShare, 1.0 - gDwSplit.z, shareA * cover);
    // (a clear ray: no interval; the march returns before closing one)
    if (cover <= 0.0) wallSplit = vec4(0.0);
    // diagnostics (DustWallDiag.probe −2, the ray ends; RGBA16F, so every value stays under 6·10⁴): (ray end, scene view-axis
    // distance, the relief's end, 6·10⁴ where none; the eye above source 0's floor); (the end's height and the floor under it,
    // both from source 0's ground zero, transmittance, samples); (the end from the eye, x and z; source 0's map half side, cell)
    if (DwProbe < -0.5 && DwProbe > -5.0) {
        dwLoad(0);
        vec3 e = DwEye.xyz + dir * min(tEnd, 30000.0);
        fragColor = vec4(min(tEnd, 30000.0), min(axis, 6e4), min(floorT, 6e4), DwEye.y - dwFloor(DwEye.xz));
        wallDepth = vec4(e.y, dwFloor(e.xz), 1.0 - cover, float(gDwSamples));
        wallSplit = vec4(e.xz - DwEye.xz, min(DwGround.xw, vec2(6e4)));
    }
    if (DwProbe > 0.5) {
        fragColor = gDwProbeA;
        wallDepth = gDwProbeB;
        wallSplit = vec4(float(gDwSamples), gDwProbeC.yzw);
    }
}
