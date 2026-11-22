#version 150

// Young dust wall march at 1/DwDiv resolution (client/fx/layer/DustWallPass): one ray per low-resolution pixel through
// its frame pixel block (an R2 offset within it every frame, kept in the centre's depth class); the world behind it
// (Sampler1, SceneDepth: r — what hides the layer) or, where the scene has none, the relief map ends the ray.
// Output: premultiplied linear light, transmittance. The sample offset along the ray moves every frame (golden-ratio
// sequence over a fixed per-pixel start); fx_dustwall_resolve averages the frames in time.

#moj_import <incoming:pall.glsl>

// the detonation's pall slot, terrain map (Sampler3) and the outdoor-air window (pall.glsl: pallRelief, pallOpen): the
// young wall's ground, open air and soil (dustwall.glsl: dwFloor, dwOpen, dwSoilAt)
uniform sampler2D Sampler3;
uniform mat4 PallSlot0;
uniform vec4 PallGround0;
uniform sampler3D PallOpen;
uniform vec4 PallOpenAt;
uniform vec3 PallOpenWrap;

float dwFloorEye(vec2 xz) {
    return pallRelief(Sampler3, PallSlot0, PallGround0, xz);
}

// open air only inside the outdoor-air window around the eye (rooms, caves, adits); beyond it the dust is outdoors (the
// pall's test there compares with the coarse relief map, and samples moving along the ray flipped dust near the ground on
// and off: popping patches on the approaching wall)
float dwOpenEye(vec3 p) {
    vec3 w = p - PallOpenAt.xyz;
    if (any(lessThan(w, vec3(0.0))) || any(greaterThanEqual(w, PALL_OPEN_SIZE))) return 1.0;
    return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler3, PallSlot0, PallGround0, p);
}

vec2 dwSoilAt(vec2 xz) {
    return pallSoil(Sampler3, PallSlot0, PallGround0, xz);
}

// the frame air between the eye and the dust (pall.glsl, as the pall's): its transmittance to t along a ray of vertical
// share dy, and its own light along rd in the march's linear light (the composite shows it through pallShown, so a fully
// hazed ray shows the airlight itself, as the pall's haze share does)
uniform vec4 PallAir;
uniform vec3 PallAirSky;
uniform vec4 PallAirFog;
float dwAirT(float t, float dy) {
    return pallAir(PallAir, t, dy);
}
vec3 dwAirLight(vec3 rd) {
    return pallLinear3(pallAirLightOf(rd, PallAirSky, PallAirFog));
}

#moj_import <incoming:dustwall.glsl>

uniform sampler2D Sampler1;
uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform float DwDiv;
// frame counter (wraps), and the march's step cap
uniform vec2 DwFrame;
out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler1, 0);
    // the ray moves within its march pixel every frame (R2 sequence) and the history averages it: a quarter-resolution
    // silhouette (the wall's lower edge, nearly level) drew a staircase of identical 4-pixel rows
    vec2 centre = min((floor(gl_FragCoord.xy) + 0.5) * DwDiv, vec2(size) - 0.5);
    vec2 sub = fract(vec2(0.7548777, 0.5698403) * DwFrame.x) - 0.5;
    vec2 at = clamp(centre + sub * DwDiv, vec2(0.5), vec2(size) - 0.5);
    // ... within the centre's depth class: the resolve and composite classify the sample by the centre's depth (an offset
    // across a foreground edge put the background's dust onto the foreground)
    float d0 = pallScene(texelFetch(Sampler1, ivec2(centre), 0).r), d1 = pallScene(texelFetch(Sampler1, ivec2(at), 0).r);
    if (abs(d1 - d0) > 0.03 * min(d0, d1) + 0.5) at = centre;
    vec2 ndc = at / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float axis = pallScene(texelFetch(Sampler1, ivec2(at), 0).r);
    float tEnd = axis / max(dot(dir, PallForward), 1e-3);
    float jitter = fract(52.9829189 * fract(dot(floor(gl_FragCoord.xy), vec2(0.06711056, 0.00583715))) + DwFrame.x * 0.618034);
    // pixel size per block of distance at this resolution
    vec4 side = InvViewProj * vec4(ndc + vec2(2.0 * DwDiv / float(size.x), 0.0), 1.0, 1.0);
    float foot = length(normalize(side.xyz / side.w) - dir);
    fragColor = dwMarch(dir, min(tEnd, 30000.0), jitter, foot, int(DwFrame.y));
}
