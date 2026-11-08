#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:smoke.glsl>

// Pall temporal accumulation (client/fx/layer/PallPass). Current ray has blue-noise offset, producing grain; previous
// frames at same world position (reprojected by pixel world depth, no farther than pall bounds) smooth it. History
// clamps to current neighbors at same world distance (pallAlike): where pall shifted on screen (edge behind
// house), no trail. Neighbors beyond object edge are another place (sky behind wall, wall before sky): including them in
// bounds let history at moving object edges take background pall (history sampled between pixels), leaving a one-pall-pixel
// rim (2–4 screen pixels) around blocks against pall. Invalid data
// (NaN/infinity, including rays marked invalid by fx_pall with alpha −1) never enters history: invalid current ray
// uses history; invalid history restarts pixel from current frame. Otherwise one NaN would spread through history via
// neighbors/history sampling and turn pall black within seconds. Mushroom ahead of world on ray
// reprojects by own distance (segment midpoint): a cloud kilometers away reprojected by nearby position would
// trail during eye motion. Explosion smoke (smoke.glsl) uses its ray-bounds midpoint, like mushroom.

// Interleaved rays (PallPass.Step.interleave n): each frame traces one pixel of every n × n block (Sampler0 holds only those,
// block by block), the rest keep their reprojected history, clamped to the traced neighbours at the same place — the
// way volumetric clouds are drawn in games (a sixteenth of the pixels per frame). A still image is the same; changes reach
// every pixel within n² frames. A traced pixel takes its new ray with the ordinary history weight (PallPass.HISTORY): every
// pixel averages as many of its own rays as without interleaving, over n² times as many frames.
// Comments below say “ray pixel” for a traced pixel of this frame.

// current frame rays (fx_pall): one per n × n block
uniform sampler2D Sampler0;
// history (previous frame of this pass)
uniform sampler2D Sampler1;
// pixel world distance along view axis (full resolution)
uniform sampler2D Sampler2;
// frame mushrooms (mushroom.glsl)
uniform sampler2D Sampler3;

uniform mat4 InvViewProj;
uniform mat4 PrevViewProj;
uniform vec3 PallForward;
// xyz: current eye relative to previous eye; w: 1 if history exists
uniform vec4 PallReproject;
// z: frame downsample factor; w: maximum reprojection distance in blocks
uniform vec4 PallFrame;
// x: interleave n; yz: this frame's traced pixel within each block; w: history weight at a traced pixel
uniform vec4 PallInterleave;
// share of the way history moves toward the bounds of the rays around it each frame (1: clamped outright). Interleaved rays
// give a block of n × n pixels one set of neighbour rays, a different set each frame: snapped to one frame's set, a whole
// block took a chance low or high bound (thin lobes, where a jittered ray either hits a billow or misses it) and showed as a
// light or dark tile of the block's size. Pulled part of the way, history follows the bounds averaged over frames; a real
// change outside them still wins within a few frames
uniform float PallClamp;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    int n = int(PallInterleave.x + 0.5);
    ivec2 o = ivec2(PallInterleave.yz + 0.5);
    ivec2 cells = textureSize(Sampler0, 0);
    ivec2 fullSize = textureSize(Sampler2, 0);
    // nearest ray pixel of this frame (its block) and whether this pixel is it
    ivec2 own = clamp(ivec2(floor(vec2(px - o) / float(n) + 0.5)), ivec2(0), cells - 1);
    bool traced = own * n + o == px;
    vec4 cur = traced ? texelFetch(Sampler0, own, 0) : vec4(0.0, 0.0, 0.0, -1.0);
    bool curOk = traced && pallValid(cur);
    ivec2 full = clamp(ivec2((vec2(px) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
    float depth = pallScene(texelFetch(Sampler2, full, 0).r);
    // first-person hand (pallHand): no pall there (fx_pall_composite), and its pixels hold no history — the hand is fixed to
    // the screen, not to a world place: reprojected as a world point 0.1 m from the eye, any turn or step brought it the
    // dust from elsewhere, which the interleaved partial clamp held over the hand; and dust uncovered by a moving hand took
    // the hand's empty history, a trail behind it. Invalid here, a pixel reprojecting onto it restarts from its rays
    if (pallHand(depth)) {
        fragColor = vec4(0.0, 0.0, 0.0, -1.0);
        return;
    }
    // history bounds use valid ray-pixel neighbors at same place as this pixel (its surface by world distance,
    // pallAhead; a ray pixel always includes itself); no valid neighbor there means all valid neighbors (as without an
    // edge: current ray invalid, history exists). A pixel without history takes the rays at the same place weighted by
    // screen distance (the nearest alone would leave n × n blocks for the seconds history takes to refill them); without
    // such a ray, the neighbor nearest in world distance
    vec2 slope = pallSlope(Sampler2, full, fullSize, depth);
    vec4 lo = vec4(1e9), hi = vec4(-1e9), loAll = vec4(1e9), hiAll = vec4(-1e9);
    vec4 fill = vec4(0.0, 0.0, 0.0, 1.0), fillSum = vec4(0.0);
    float fillAlike = -1.0, fillWeight = 0.0;
    // a neighbor ray at another place (an object edge in the block's neighborhood)
    bool edge = false;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 c = clamp(own + ivec2(x, y), ivec2(0), cells - 1);
            vec4 v = texelFetch(Sampler0, c, 0);
            if (!pallValid(v)) continue;
            ivec2 q = c * n + o;
            loAll = min(loAll, v);
            hiAll = max(hiAll, v);
            ivec2 qf = clamp(ivec2((vec2(q) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
            float d = pallScene(texelFetch(Sampler2, qf, 0).r);
            float a = q == px ? 1.0 : pallAlike(d, pallAhead(depth, slope, vec2(qf - full)));
            // nearest on screen among equally alike: the own block's ray first
            a -= 1e-3 * float(abs(x) + abs(y));
            if (a > fillAlike) {
                fillAlike = a;
                fill = v;
            }
            if (a < 0.5) {
                edge = true;
                continue;
            }
            vec2 off = vec2(q - px) / float(n);
            float w = 1.0 / (1.0 + dot(off, off));
            fillSum += w * v;
            fillWeight += w;
            lo = min(lo, v);
            hi = max(hi, v);
        }
    }
    if (fillWeight > 0.0) fill = fillSum / fillWeight;
    if (any(greaterThan(lo, hi))) {
        lo = loAll;
        hi = hiAll;
    }
    if (PallReproject.w < 0.5) {
        fragColor = curOk ? cur : fill;
        return;
    }
    vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float scene = depth / max(dot(dir, PallForward), 1e-3);
    float t = min(scene, PallFrame.w), near = 1e9;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(Sampler3, k)) break;
        vec2 sp = mushSpan(mushLoad(Sampler3, k), dir);
        sp.y = min(sp.y, scene);
        if (sp.y > sp.x) near = min(near, 0.5 * (sp.x + sp.y));
    }
    // explosion smoke on ray: likewise its bounds midpoint (cloud hundreds of blocks away against sky)
    if (smokeOn()) {
        vec2 ss = pallBox(dir, SmokeMin, SmokeMax);
        ss.y = min(ss.y, scene);
        if (ss.y > ss.x) near = min(near, 0.5 * (ss.x + ss.y));
    }
    if (near < 1e8) t = near;
    vec4 prev = PrevViewProj * vec4(dir * t + PallReproject.xyz, 1.0);
    vec2 uv = prev.xy / prev.w * 0.5 + 0.5;
    // no history here (behind eye, outside frame), or invalid: restart pixel
    bool seen = prev.w > 0.0 && all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
    // pall-frame pixel ray passes through full-frame pixel center (fx_pall), not own pixel center;
    // history uses same ray lattice (pallLattice), otherwise even a still view samples between pixels and
    // blurs history with neighbors each frame
    vec2 lattice = vec2(pallLattice(uv.x * float(fullSize.x), PallFrame.z), pallLattice(uv.y * float(fullSize.y), PallFrame.z));
    vec4 past = seen ? texture(Sampler1, (lattice + 0.5) / vec2(textureSize(Sampler1, 0))) : vec4(0.0);
    bool pastOk = seen && pallValid(past) && all(lessThanEqual(lo, hi));
    // at an object edge history may come from the other side of it (parallax, a mob crossing the dust): clamped outright
    // there, as without interleaving, or it would trail the silhouette for frames
    float pull = PallClamp;
    if (edge) pull = 1.0;
    vec4 held = mix(past, clamp(past, lo, hi), pull);
    if (!curOk) {
        // between rays (or an invalid ray): history held to the rays around it; none — the nearest such ray
        if (pastOk) fragColor = traced ? past : held;
        else fragColor = fill;
        return;
    }
    fragColor = pastOk ? mix(cur, held, PallInterleave.w) : cur;
}
