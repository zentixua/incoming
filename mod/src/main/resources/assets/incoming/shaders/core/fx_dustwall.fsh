#version 150

// Young dust wall march at 1/DwDiv resolution (client/fx/layer/DustWallPass): one ray per low-resolution pixel through
// the centre of its frame pixel block; the world behind it (Sampler1, SceneDepth: r — what hides the layer) ends the ray.
// Output: premultiplied linear light, transmittance. The sample offset along the ray moves every frame (golden-ratio
// sequence over a fixed per-pixel start); fx_dustwall_resolve averages the frames in time.

#moj_import <incoming:pall.glsl>

// the detonation's pall slot, terrain map (Sampler3) and the outdoor-air window (pall.glsl: pallRelief, pallOpen): the
// young wall's ground and open air (dustwall.glsl: dwFloor, dwOpen), from eye-relative points
uniform sampler2D Sampler3;
uniform mat4 PallSlot0;
uniform vec4 PallGround0;
uniform sampler3D PallOpen;
uniform vec4 PallOpenAt;
uniform vec3 PallOpenWrap;

float dwFloorEye(vec2 xz) {
    return pallRelief(Sampler3, PallSlot0, PallGround0, xz);
}

float dwOpenEye(vec3 p) {
    return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler3, PallSlot0, PallGround0, p);
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
    vec2 at = min((floor(gl_FragCoord.xy) + 0.5) * DwDiv, vec2(size) - 0.5);
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
