// The dust wave's resolved light and its transmittance along the view: up to two occupied intervals per march
// pixel, A from the first dust to the last before the first clear gap (dustwall.glsl: dwInterval), B the rest; gaps between them
// hold no dust. Light and depths are premultiplied by coverage during filtering; depth is in blocks along the view axis.
// Consumers ignore light.rgb and the depths when light.a == 1 (full transmission).
struct WallLayer {
    vec4 light;
    // (A's entry, B's exit, the opacity-weighted mean depth)
    vec3 depth;
    // (A's exit, B's entry, the transmittance through A, A's share of the light): B empty — B's entry and exit at A's exit
    vec4 split;
};

// Transmittance of the dust in front of view depth z: inside an interval its optical depth is spread evenly (one
// homogeneous layer), the gap between the intervals is clear (DustIntervals.transmittance, DustIntervalsTest)
float wallPrefixT(float z, float aIn, float aOut, float tA, float bIn, float bOut, float t) {
    if (z <= aIn) return 1.0;
    if (z < aOut) return pow(max(tA, 1e-7), (z - aIn) / max(aOut - aIn, 1e-4));
    if (z <= bIn) return tA;
    if (z < bOut) return tA * pow(max(t / max(tA, 1e-7), 1e-7), (z - bIn) / max(bOut - bIn, 1e-4));
    return t;
}

// Share of the dust's light in front of view depth z (A's light share s; each interval's light spread with its opacity)
float wallPrefixShare(float z, float aIn, float aOut, float tA, float bIn, float bOut, float t, float s) {
    if (z <= aIn) return 0.0;
    if (z < aOut) return s * (1.0 - pow(max(tA, 1e-7), (z - aIn) / max(aOut - aIn, 1e-4))) / max(1.0 - tA, 1e-6);
    if (z <= bIn) return s;
    float tB = t / max(tA, 1e-7);
    if (z < bOut) return s + (1.0 - s) * (1.0 - pow(max(tB, 1e-7), (z - bIn) / max(bOut - bIn, 1e-4))) / max(1.0 - tB, 1e-6);
    return 1.0;
}

// Fraction of the optical thickness up to z over the whole occupied span (one homogeneous layer)
float wallFraction(vec3 span, float z) {
    return clamp((z - span.x) / max(span.y - span.x, 1e-4), 0.0, 1.0);
}

vec4 wallTo(WallLayer wall, float z) {
    vec4 s = wall.split;
    float t = wallPrefixT(z, wall.depth.x, s.x, s.z, s.y, wall.depth.y, wall.light.a);
    float share = wallPrefixShare(z, wall.depth.x, s.x, s.z, s.y, wall.depth.y, wall.light.a, s.w);
    return vec4(wall.light.rgb * share, t);
}

// The atlas's split third per march pixel: (A's exit × coverage, B's entry × coverage, A's opacity, A's light share × coverage).
// Keep the part of a tap in front of the opaque surface. Depths in the atlas are opacity-weighted, so clipping changes the
// three parts together: light and transmittance, the span, and the split (A's exit and the share; B ends at the surface)
void wallCut(inout vec4 light, inout vec4 span, inout vec4 split, float surface) {
    float cover = 1.0 - span.a;
    if (cover <= 0.0 || span.y <= surface * cover) return;
    vec3 depth = span.rgb / cover;
    vec4 s = vec4(split.xy / cover, 1.0 - split.z, split.w / cover);
    float fraction = wallFraction(depth, surface);
    // (wallTo at the surface, its share taken once)
    float share = wallPrefixShare(surface, depth.x, s.x, s.z, s.y, depth.y, light.a, s.w);
    light = vec4(light.rgb * share, wallPrefixT(surface, depth.x, s.x, s.z, s.y, depth.y, light.a));
    float now = 1.0 - light.a;
    if (surface <= s.x) {
        // inside A: B is gone, A ends at the surface and holds all that is left
        s = vec4(surface, surface, light.a, 1.0);
    } else if (surface <= s.y) {
        // in the gap: B is gone
        s = vec4(s.x, s.x, light.a, 1.0);
    } else {
        // inside B: A whole, its share of what is left
        s.w = s.w / max(share, 1e-6);
    }
    // Preserve the relative position of the representative depth in the shortened interval.
    depth.yz = mix(vec2(depth.x), depth.yz, fraction);
    depth.y = min(depth.y, max(surface, depth.x));
    span = vec4(depth * now, light.a);
    split = vec4(s.xy * now, 1.0 - s.z, s.w * now);
}

// The pall composite on the volume's coarser rungs (PallPass.Step.cutOnce, its DwCutOnce): the taps of the pixel's depth class are
// filtered whole and the filtered layer is cut at the pixel's depth once (the four cuts were most of the composite's dust cost:
// Artem's Zearth scene, 4–5 ms of its 6–8 at 2560×1600). Taps of one depth class differ in their dust's span by little; where a
// tap's dust ends just behind the pixel and its neighbour's just in front, the one cut takes their mean span
bool gWallCutOnce = false;
WallLayer wallLayer(sampler2D atlas, sampler2D scene, vec2 pixel, float here, float div) {
    WallLayer wall;
    wall.light = vec4(0.0, 0.0, 0.0, 1.0);
    wall.depth = vec3(0.0);
    wall.split = vec4(0.0, 0.0, 1.0, 1.0);
    if (pallHand(here)) return wall;
    ivec2 size = textureSize(scene, 0), low = textureSize(atlas, 0);
    // light, depth span and split thirds share the lattice
    low.x /= 3;
    vec2 at = pixel / div - 0.5;
    // the four nearest march pixels with bilinear weights (the pall's own upsample takes its four nearest rays): the
    // per-tap clipping is most of the composite's dust cost, and a 3 × 3 tent took nine
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base);
    ivec2 cx = clamp(base.x + ivec2(0, 1), 0, low.x - 1);
    ivec2 cy = clamp(base.y + ivec2(0, 1), 0, low.y - 1);
    // History can leave dust in any of the four taps. Only a completely
    // transmitting neighbourhood is empty regardless of depth class or clipping.
    if (texelFetch(atlas, ivec2(cx.x, cy.x), 0).a == 1.0 && texelFetch(atlas, ivec2(cx.y, cy.x), 0).a == 1.0
            && texelFetch(atlas, ivec2(cx.x, cy.y), 0).a == 1.0 && texelFetch(atlas, ivec2(cx.y, cy.y), 0).a == 1.0) return wall;
    ivec2 px = ivec2(min((vec2(cx) + 0.5) * div, float(size.x) - 0.5));
    ivec2 py = ivec2(min((vec2(cy) + 0.5) * div, float(size.y) - 0.5));
    vec2 wx = vec2(1.0 - f.x, f.x);
    vec2 wy = vec2(1.0 - f.y, f.y);

    vec4 sum = vec4(0.0), splits = vec4(0.0);
    vec3 depths = vec3(0.0);
    float weights = 0.0;
    // Only the matching depth class contributes. A thin foreground object
    // with no valid matching tap uses the other class on the second pass.
    for (int pass = 0; pass < 2; pass++) {
        bool matching = pass == 0;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                float there = pallScene(texelFetch(scene, ivec2(px[x], py[y]), 0).r);
                if (pallSamePlace(there, here) != matching) continue;
                ivec2 c = ivec2(cx[x], cy[y]);
                vec4 v = texelFetch(atlas, c, 0);
                vec4 span = texelFetch(atlas, c + ivec2(low.x, 0), 0);
                vec4 split = texelFetch(atlas, c + ivec2(2 * low.x, 0), 0);
                if (!pallValid(v) || !pallValid(span)) continue;
                if (!gWallCutOnce) wallCut(v, span, split, here);
                float w = wx[x] * wy[y];
                sum += w * v;
                float c0 = max(1.0 - span.a, 1e-6);
                depths += w * (1.0 - v.a) * (span.rgb / c0);
                // (A's opacity filtered as an opacity, the rest as coverage-weighted values)
                splits += w * vec4((1.0 - v.a) * split.xy / c0, split.z, (1.0 - v.a) * split.w / c0);
                weights += w;
            }
        }
        if (weights > 0.0) break;
        // A zero-weight tap can still contribute 0 * NaN. Start the other class clean.
        sum = vec4(0.0);
        depths = vec3(0.0);
        splits = vec4(0.0);
    }
    if (weights > 0.0) {
        wall.light = sum / weights;
        float cover = clamp(1.0 - wall.light.a, 0.0, 1.0);
        wall.depth = depths / max(weights * cover, 1e-6);
        vec4 sp = splits / max(weights * cover, 1e-6);
        // A's transmittance from its filtered opacity (never under the whole's)
        wall.split = vec4(sp.xy, max(1.0 - splits.z / weights, wall.light.a), clamp(sp.w, 0.0, 1.0));
        if (gWallCutOnce && cover > 0.0) {
            // the filtered layer in the atlas's form, cut once at the pixel's depth
            vec4 span = vec4(wall.depth * cover, wall.light.a);
            vec4 split = vec4(wall.split.xy * cover, 1.0 - wall.split.z, wall.split.w * cover);
            wallCut(wall.light, span, split, here);
            cover = clamp(1.0 - wall.light.a, 0.0, 1.0);
            float c0 = max(cover, 1e-6);
            wall.depth = span.rgb / c0;
            wall.split = vec4(split.xy / c0, max(1.0 - split.z, wall.light.a), clamp(split.w / c0, 0.0, 1.0));
        }
        wall.light.rgb = pallShown(max(wall.light.rgb, vec3(0.0)), cover);
    }
    return wall;
}

// Place the wall between the measured foreground of a pall ray and the rest.
// total = front.light + front.T * back.light, so the back need never be divided out.
vec4 insertWall(vec4 total, vec4 front, vec4 wall) {
    return vec4(wall.a * total.rgb + (1.0 - wall.a) * front.rgb + front.a * wall.rgb, total.a * wall.a);
}
