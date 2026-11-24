#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:wall_layer.glsl>

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
// Divisor of the previous image, which can survive a change of detail.
uniform float DwPrevDiv;
// xyz: this eye minus the previous frame's eye, blocks; w: 1 if history exists
uniform vec4 DwReproject;
// x: weight of the current frame; y: the layer top's height relative to the eye, blocks
uniform vec2 DwHistory;

// interleaved rays (fx_dustwall, DwInterleave): x 1 when on, y this frame's parity; DwLow: the full march lattice (width,
// height). A pixel taking a ray this frame keeps history weight 1 − DwHistory.x (0.9, the pall's rule); one between rays
// takes its history held to the depth-alike rays around it (the 4-neighbours of a checkerboard are all this frame's), or,
// without history, their mean (the pall's interleave, PallPass.Step: 2 at most, 3–4 burnt a lattice into a still view)
uniform vec4 DwInterleave;
uniform vec2 DwLow;
// diagnostics (DustWallDiag): x > 0 — the clamp's width in standard deviations (0: the shipped 1.5); y 1 — each pixel's own
// history (a still eye), no reprojection; z 1 — a fresh ray's value is not held within its neighbours' range
uniform vec3 DwDiag;
out vec4 fragColor;

bool interleaved() {
    return DwInterleave.x > 0.5;
}

// this frame's ray of march pixel c (third: 0 light, 1 depth span, 2 interval split); false where c took no ray this frame
bool rayAt(ivec2 c, int half_, ivec2 inLow, out vec4 v) {
    if (interleaved()) {
        if (((c.x + c.y + int(DwInterleave.y + 0.5)) & 1) != 0) return false;
        c.x = c.x >> 1;
    }
    v = texelFetch(Sampler0, c + ivec2(half_ * inLow.x, 0), 0);
    return true;
}

// a clear pixel's value in each third: light and span (0, 0, 0, 1), split (0): A neither opaque nor lit
int gThird = 0;
vec4 clearValue() {
    return gThird == 2 ? vec4(0.0) : vec4(0.0, 0.0, 0.0, 1.0);
}
vec4 valid(vec4 v) {
    return pallValid(v) ? v : clearValue();
}

// lattice coordinate (in samples) of frame pixel coordinate x on one axis: sample i sits at min((i + 0.5) × DwPrevDiv, size −
// 0.5); between the last two samples, linear between their true places
float lattice(float x, float size, float n) {
    float a = (n - 1.5) * DwPrevDiv, b = min((n - 0.5) * DwPrevDiv, size - 0.5);
    if (n < 2.0 || x <= a) return x / DwPrevDiv - 0.5;
    return n - 2.0 + (x - a) / max(b - a, 1e-3);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy), inLow = textureSize(Sampler0, 0), size = textureSize(Sampler2, 0);
    inLow.x /= 3; // light, depth span and split thirds use the same spatial lattice
    ivec2 low = interleaved() ? ivec2(DwLow + 0.5) : inLow;
    int half_ = p.x / low.x;
    gThird = half_;
    ivec2 offset = ivec2(half_ * low.x, 0);
    p -= offset;
    vec4 raw;
    bool traced = rayAt(p, half_, inLow, raw);
    vec4 cur = traced ? valid(raw) : clearValue();
    vec2 at = min((vec2(p) + 0.5) * DwDiv, vec2(size) - 0.5);
    float here = pallScene(texelFetch(Sampler2, ivec2(at), 0).r);
    // neighbourhood at the same world distance (a neighbour across an object's edge is another place): this frame's rays
    vec4 m1 = traced ? cur : vec4(0.0), m2 = traced ? cur * cur : vec4(0.0);
    float count = traced ? 1.0 : 0.0;
    // the depth-alike rays around (not this one): their range bounds this ray's value
    vec4 lo = vec4(1e30), hi = vec4(-1e30);
    float around = 0.0;
    for (int k = 0; k < 9; k++) {
        if (k == 4) continue;
        ivec2 c = clamp(p + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), low - 1);
        vec4 v;
        if (!rayAt(c, half_, inLow, v)) continue;
        float there = pallScene(texelFetch(Sampler2, ivec2(min((vec2(c) + 0.5) * DwDiv, vec2(size) - 0.5)), 0).r);
        if (!pallSamePlace(there, here)) continue;
        v = valid(v);
        m1 += v;
        m2 += v * v;
        count += 1.0;
        lo = min(lo, v);
        hi = max(hi, v);
        around += 1.0;
    }
    // One ray's sample along a march cell is noisy (a dense or a clear sub-cell): a single march pixel darker or lighter than
    // every depth-alike ray around it is that noise, not dust structure (at a fifth of the frame a pixel is 5 screen pixels).
    // On a near, moving wall the history kept those pixels, and the bilinear lay drew them as a lattice of dark "+" dots. A clear
    // ray stays clear (a gap among dust is real: pulled into the neighbours' dust it filled holes and edges).
    if (traced && around >= 2.0 && DwDiag.z < 0.5 && cur != clearValue()) {
        vec4 held = clamp(cur, lo, hi);
        m1 += held - cur;
        m2 += held * held - cur * cur;
        cur = held;
    }
    // between rays without a depth-alike ray around: the nearest rays at any depth (a lone pixel across an edge)
    if (count < 0.5) {
        for (int k = 1; k < 9; k += 2) {
            ivec2 c = clamp(p + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), low - 1);
            vec4 v;
            if (!rayAt(c, half_, inLow, v)) continue;
            v = valid(v);
            m1 += v;
            m2 += v * v;
            count += 1.0;
        }
    }
    m1 /= max(count, 1.0);
    vec4 sd = sqrt(max(m2 / max(count, 1.0) - m1 * m1, 0.0));
    if (!traced) cur = count > 0.5 ? m1 : clearValue();
    if (DwReproject.w < 0.5) {
        fragColor = cur;
        return;
    }
    vec2 ndc = at / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float axis = max(dot(dir, PallForward), 1e-3);
    float world = here / axis;
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
    // Along this ray the previous view depth is origin + distance * previousAxis.
    // The anchor can be on the dust top, well in front of the opaque surface.
    float origin = (PrevViewProj * vec4(DwReproject.xyz, 1.0)).w;
    float previousAxis = (PrevViewProj * vec4(dir, 0.0)).w;
    if (previousAxis <= 1e-3) {
        fragColor = cur;
        return;
    }
    float expected = here >= 1e8 ? 1e9 : origin + previousAxis * world;
    float before = expected;
    if (DwPrevScene > 0.5 && DwDiag.y < 0.5) {
        before = pallScene(texelFetch(Sampler3, ivec2(prevPixel), 0).r);
        if (!pallSamePlace(before, expected)) {
            fragColor = cur;
            return;
        }
    }
    ivec2 past = textureSize(Sampler1, 0);
    past.x /= 3;
    vec2 l = vec2(lattice(prevPixel.x, float(size.x), float(past.x)), lattice(prevPixel.y, float(size.y), float(past.y)));
    if (DwDiag.y > 0.5) l = vec2(p);
    // the four history samples around it, each only where the previous frame saw the same distance (a bilinear read took
    // dust clipped by a far background into a pixel whose previous depth was the foreground's)
    vec2 f0 = floor(l), fr = l - f0;
    vec4 hs = vec4(0.0);
    float ws = 0.0;
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k % 2, k / 2);
        ivec2 c = clamp(ivec2(f0) + o, ivec2(0), past - 1);
        float w = mix(1.0 - fr.x, fr.x, float(o.x)) * mix(1.0 - fr.y, fr.y, float(o.y));
        if (DwPrevScene > 0.5 && DwDiag.y < 0.5) {
            float was = pallScene(texelFetch(Sampler3, ivec2(min((vec2(c) + 0.5) * DwPrevDiv, vec2(size) - 0.5)), 0).r);
            if (!pallSamePlace(was, before)) continue;
        }
        vec4 light = texelFetch(Sampler1, c, 0), span = texelFetch(Sampler1, c + ivec2(past.x, 0), 0);
        if (!pallValid(light)) light = vec4(0.0, 0.0, 0.0, 1.0);
        if (!pallValid(span)) span = vec4(0.0, 0.0, 0.0, 1.0);
        vec4 split = texelFetch(Sampler1, c + ivec2(2 * past.x, 0), 0);
        if (!pallValid(split)) split = vec4(0.0);
        wallCut(light, span, split, min(before, expected));
        // Blend depth moments in the current eye's coordinates too.
        span.rgb = max((span.rgb - origin * (1.0 - span.a)) * (axis / previousAxis), vec3(0.0));
        split.xy = max((split.xy - origin * (1.0 - span.a)) * (axis / previousAxis), vec2(0.0));
        vec4 sampleValue = half_ == 0 ? light : half_ == 1 ? span : split;
        hs += w * sampleValue;
        ws += w;
    }
    if (ws < 1e-4) {
        fragColor = cur;
        return;
    }
    // to the neighbourhood's range, which always holds this pixel's own sample (a clear pixel among dusty neighbours was
    // pulled toward their dust)
    float gamma = DwDiag.x > 0.0 ? DwDiag.x : 1.5;
    vec4 h = clamp(hs / ws, min(m1 - gamma * sd, cur), max(m1 + gamma * sd, cur));
    // a pixel between rays this frame keeps its held history (weight 1 there: its ray comes next frame)
    fragColor = traced ? mix(h, cur, DwHistory.x) : h;
}
