#version 150

#moj_import <incoming:pall.glsl>

// Young dust wall temporal accumulation (client/fx/layer/DustWallPass), at the march's resolution: the march's sample
// offset along each ray moves every frame, and each pixel averages its last frames (exponential, current weight
// DwHistory.x), taken from where its dust was a frame ago. The dust's place for reprojection is where the ray meets the
// wall's settled layer top (DwHistory.y above the eye; the world behind the pixel if nearer): exact for turning, close for
// moving (the wall is hundreds of metres away). History is clamped to the current 3 × 3 neighbourhood (mean ± 1.25 σ):
// where the wall really changed (the front passing, a roof edge), the old frames give way within a frame; where only the
// sample offsets differ, they average into stable dust instead of a per-frame shimmer.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform mat4 InvViewProj;
uniform mat4 PrevViewProj;
uniform vec3 PallForward;
uniform float DwDiv;
// xyz: this eye minus the previous frame's eye, blocks; w: 1 if history exists
uniform vec4 DwReproject;
// x: weight of the current frame; y: the layer top's height relative to the eye, blocks
uniform vec2 DwHistory;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy), low = textureSize(Sampler0, 0), size = textureSize(Sampler2, 0);
    vec4 cur = texelFetch(Sampler0, p, 0);
    if (!pallValid(cur)) cur = vec4(0.0, 0.0, 0.0, 1.0);
    vec4 m1 = vec4(0.0), m2 = vec4(0.0);
    for (int k = 0; k < 9; k++) {
        vec4 v = texelFetch(Sampler0, clamp(p + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), low - 1), 0);
        if (!pallValid(v)) v = vec4(0.0, 0.0, 0.0, 1.0);
        m1 += v;
        m2 += v * v;
    }
    m1 /= 9.0;
    vec4 sd = sqrt(max(m2 / 9.0 - m1 * m1, 0.0));
    if (DwReproject.w < 0.5) {
        fragColor = cur;
        return;
    }
    vec2 at = min((vec2(p) + 0.5) * DwDiv, vec2(size) - 0.5);
    vec2 ndc = at / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float world = pallScene(texelFetch(Sampler2, ivec2(at), 0).r) / max(dot(dir, PallForward), 1e-3);
    float t = dir.y < -1e-3 && DwHistory.y < 0.0 ? DwHistory.y / dir.y : 2000.0;
    t = clamp(min(t, world), 1.0, 30000.0);
    vec4 clip = PrevViewProj * vec4(dir * t + DwReproject.xyz, 1.0);
    vec2 uv = clip.xy / max(clip.w, 1e-6) * 0.5 + 0.5;
    if (clip.w <= 0.0 || any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        fragColor = cur;
        return;
    }
    vec4 h = texture(Sampler1, uv);
    if (!pallValid(h)) {
        fragColor = cur;
        return;
    }
    h = clamp(h, m1 - 1.25 * sd, m1 + 1.25 * sd);
    fragColor = mix(h, cur, DwHistory.x);
}
