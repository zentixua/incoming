#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:dustwall.glsl>

// Young dust wall march at 1/DwDiv resolution (client/fx/layer/DustWallPass): one ray per low-resolution pixel through
// the centre of its frame pixel block; the world behind it (Sampler1, SceneDepth: r — what hides the layer) ends the ray.
// Output: premultiplied linear light, transmittance.
uniform sampler2D Sampler1;
uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform float DwDiv;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler1, 0);
    vec2 at = min((floor(gl_FragCoord.xy) + 0.5) * DwDiv, vec2(size) - 0.5);
    vec2 ndc = at / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float axis = pallScene(texelFetch(Sampler1, ivec2(at), 0).r);
    float tEnd = axis / max(dot(dir, PallForward), 1e-3);
    // fixed per-pixel offset (interleaved gradient noise): no frame-to-frame shimmer
    float jitter = fract(52.9829189 * fract(dot(floor(gl_FragCoord.xy), vec2(0.06711056, 0.00583715))));
    // pixel size per block of distance at this resolution
    vec4 side = InvViewProj * vec4(ndc + vec2(2.0 * DwDiv / float(size.x), 0.0), 1.0, 1.0);
    float foot = length(normalize(side.xyz / side.w) - dir);
    fragColor = dwMarch(dir, min(tEnd, 30000.0), jitter, foot, 160);
}
