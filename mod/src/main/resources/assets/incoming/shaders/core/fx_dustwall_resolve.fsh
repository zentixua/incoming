#version 150

#moj_import <incoming:pall.glsl>

// Young dust wall temporal accumulation (client/fx/layer/DustWallPass), at the march's resolution: the march's sample
// offset along each ray moves every frame, and each pixel averages its last frames (exponential, current weight
// DwHistory.x), taken from where its dust was a frame ago. The dust's place for reprojection is where the ray meets the
// wall's settled layer top (DwHistory.y above the eye; the world behind the pixel if nearer): exact for turning, close for
// moving (the wall is hundreds of metres away). The previous frame is read on its own lattice of march samples (exact at
// the frame edge, where the last sample sits half a frame pixel inside), and only where the previous frame's world at that
// place (Sampler3) was as far as this one's: a ceiling or wall that moved in front takes no dust from behind it. History
// is clamped to the current 3 × 3 neighbourhood at the same world distance (mean ± 1.5 σ): where the wall really changed
// (the front passing, an object moving in front), the old frames give way within a frame; where only the sample offsets
// differ, they average into stable dust instead of a per-frame shimmer.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
// the previous frame's world distances along its view axis (SceneDepth.previous), full resolution; DwPrevScene 1 if valid
uniform sampler2D Sampler3;
uniform float DwPrevScene;
uniform mat4 InvViewProj;
uniform mat4 PrevViewProj;
uniform vec3 PallForward;
uniform float DwDiv;
// xyz: this eye minus the previous frame's eye, blocks; w: 1 if history exists
uniform vec4 DwReproject;
// x: weight of the current frame; y: the layer top's height relative to the eye, blocks
uniform vec2 DwHistory;

out vec4 fragColor;

vec4 valid(vec4 v) {
    return pallValid(v) ? v : vec4(0.0, 0.0, 0.0, 1.0);
}

// lattice coordinate (in samples) of frame pixel coordinate x on one axis: sample i sits at min((i + 0.5) × DwDiv, size −
// 0.5); between the last two samples, linear between their true places
float lattice(float x, float size, float n) {
    float a = (n - 1.5) * DwDiv, b = min((n - 0.5) * DwDiv, size - 0.5);
    if (n < 2.0 || x <= a) return x / DwDiv - 0.5;
    return n - 2.0 + (x - a) / max(b - a, 1e-3);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy), low = textureSize(Sampler0, 0), size = textureSize(Sampler2, 0);
    vec4 cur = valid(texelFetch(Sampler0, p, 0));
    if (DwReproject.w < 0.5) {
        fragColor = cur;
        return;
    }
    vec2 at = min((vec2(p) + 0.5) * DwDiv, vec2(size) - 0.5);
    float here = pallScene(texelFetch(Sampler2, ivec2(at), 0).r);
    // neighbourhood at the same world distance (a neighbour across an object's edge is another place)
    vec4 m1 = cur, m2 = cur * cur;
    float count = 1.0;
    for (int k = 0; k < 9; k++) {
        if (k == 4) continue;
        ivec2 c = clamp(p + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), low - 1);
        float there = pallScene(texelFetch(Sampler2, ivec2(min((vec2(c) + 0.5) * DwDiv, vec2(size) - 0.5)), 0).r);
        if (abs(there - here) > 0.1 * max(min(there, here), 1.0)) continue;
        vec4 v = valid(texelFetch(Sampler0, c, 0));
        m1 += v;
        m2 += v * v;
        count += 1.0;
    }
    m1 /= count;
    vec4 sd = sqrt(max(m2 / count - m1 * m1, 0.0));
    vec2 ndc = at / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float world = here / max(dot(dir, PallForward), 1e-3);
    float t = dir.y < -1e-3 && DwHistory.y < 0.0 ? DwHistory.y / dir.y : 2000.0;
    t = clamp(min(t, world), 1.0, 30000.0);
    vec4 clip = PrevViewProj * vec4(dir * t + DwReproject.xyz, 1.0);
    if (clip.w <= 0.0) {
        fragColor = cur;
        return;
    }
    // the previous frame's pixel, then its place on the march lattice
    vec2 prevPixel = (clip.xy / clip.w * 0.5 + 0.5) * vec2(size);
    if (any(lessThan(prevPixel, vec2(0.0))) || any(greaterThanEqual(prevPixel, vec2(size)))) {
        fragColor = cur;
        return;
    }
    if (DwPrevScene > 0.5) {
        float before = pallScene(texelFetch(Sampler3, ivec2(prevPixel), 0).r);
        if (abs(before - here) > 0.1 * max(min(before, here), 1.0)) {
            fragColor = cur;
            return;
        }
    }
    vec2 l = vec2(lattice(prevPixel.x, float(size.x), float(low.x)), lattice(prevPixel.y, float(size.y), float(low.y)));
    vec4 h = valid(texture(Sampler1, (l + 0.5) / vec2(low)));
    h = clamp(h, m1 - 1.5 * sd, m1 + 1.5 * sd);
    fragColor = mix(h, cur, DwHistory.x);
}
