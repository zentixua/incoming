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

// current frame (fx_pall)
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

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(Sampler0, 0);
    vec4 cur = texelFetch(Sampler0, px, 0);
    bool curOk = pallValid(cur);
    if (PallReproject.w < 0.5) {
        fragColor = curOk ? cur : vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    ivec2 fullSize = textureSize(Sampler2, 0);
    ivec2 full = clamp(ivec2((vec2(px) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
    float depth = pallScene(texelFetch(Sampler2, full, 0).r);
    // history bounds use valid current neighbors at same place as this pixel (its surface by world distance,
    // pallAhead; self always); no valid neighbor there means all valid neighbors (as without an edge: current ray invalid,
    // history exists)
    vec2 slope = pallSlope(Sampler2, full, fullSize, depth);
    vec4 lo = vec4(1e9), hi = vec4(-1e9), loAll = vec4(1e9), hiAll = vec4(-1e9);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 q = clamp(px + ivec2(x, y), ivec2(0), size - 1);
            vec4 n = texelFetch(Sampler0, q, 0);
            if (!pallValid(n)) continue;
            loAll = min(loAll, n);
            hiAll = max(hiAll, n);
            ivec2 qf = clamp(ivec2((vec2(q) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
            float d = pallScene(texelFetch(Sampler2, qf, 0).r);
            if (q != px && pallAlike(d, pallAhead(depth, slope, vec2(qf - full))) < 0.5) continue;
            lo = min(lo, n);
            hi = max(hi, n);
        }
    }
    if (any(greaterThan(lo, hi))) {
        lo = loAll;
        hi = hiAll;
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
    vec4 past = seen ? texture(Sampler1, (lattice + 0.5) / vec2(size)) : vec4(0.0);
    bool pastOk = seen && pallValid(past) && all(lessThanEqual(lo, hi));
    if (!curOk) {
        fragColor = pastOk ? past : vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    fragColor = pastOk ? mix(cur, clamp(past, lo, hi), 0.9) : cur;
}
