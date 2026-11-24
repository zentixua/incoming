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
// interleaved rays (DustWallPass): x 1 when on; y this frame's parity; z the full march width.
// Each frame marches half of the march pixels, a checkerboard alternating by frame (this target holds them packed two to
// a column pair: pixel (x, y) is march pixel (2x + (y + parity) mod 2, y)); fx_dustwall_resolve fills the rest from history
uniform vec4 DwInterleave;
out vec4 fragColor;
out vec4 wallDepth;
// the occupied intervals (wall_layer.glsl): A's exit × coverage, B's entry × coverage (view depths), A's opacity, A's share of
// the light × coverage
out vec4 wallSplit;

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
    // the ray moves within its march pixel every frame (R2 sequence) and the history averages it: a quarter-resolution
    // silhouette (the wall's lower edge, nearly level) drew a staircase of identical 4-pixel rows
    vec2 centre = min((lowPix + 0.5) * DwDiv, vec2(size) - 0.5);
    vec2 sub = fract(vec2(0.7548777, 0.5698403) * DwFrame.x) - 0.5;
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
    float jitter = fract(52.9829189 * fract(dot(lowPix, vec2(0.06711056, 0.00583715))) + DwFrame.x * 0.618034);
    // no world behind the pixel (beyond the drawn chunks, between LODs): the ray ends on the relief map traced from the eye,
    // as the pall's (pallFloor); where the world is drawn its depth alone ends the ray (the coarse map stood over open
    // streets and hid the dust down them)
    if (axis >= 1e8) tEnd = min(tEnd, dwRayFloor(dir, PallFloorFrom, jitter));
    // pixel size per block of distance at this resolution
    vec4 side = InvViewProj * vec4(ndc + vec2(2.0 * DwDiv / float(size.x), 0.0), 1.0, 1.0);
    float foot = length(normalize(side.xyz / side.w) - dir);
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
}
