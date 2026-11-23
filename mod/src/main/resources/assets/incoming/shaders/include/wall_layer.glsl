// Resolved wall light and the corresponding occupied view-depth span. Both are
// premultiplied by coverage during filtering. Depth is in blocks along the view axis.
struct WallLayer {
    vec4 light;
    vec3 depth;
};

// Fraction of the optical thickness up to z. The march supplies both endpoints;
// inside a crossing the interval is treated as one homogeneous layer.
float wallFraction(vec3 span, float z) {
    return clamp((z - span.x) / max(span.y - span.x, 1e-4), 0.0, 1.0);
}

vec4 wallTo(WallLayer wall, float z) {
    float fraction = wallFraction(wall.depth, z);
    float t = exp(log(max(wall.light.a, 1e-7)) * fraction);
    return vec4(wall.light.rgb * ((1.0 - t) / max(1.0 - wall.light.a, 1e-6)), t);
}

// Keep the part of a tap in front of the opaque surface. Depths in the atlas
// are opacity-weighted, so clipping must change both halves together.
void wallCut(inout vec4 light, inout vec4 span, float surface) {
    float cover = 1.0 - span.a;
    if (cover <= 0.0 || span.y <= surface * cover) return;
    vec3 depth = span.rgb / cover;
    float fraction = wallFraction(depth, surface);
    light = wallTo(WallLayer(light, depth), surface);
    // Preserve the relative position of the representative depth in the shortened interval.
    depth.yz = mix(vec2(depth.x), depth.yz, fraction);
    span = vec4(depth * (1.0 - light.a), light.a);
}

WallLayer wallLayer(sampler2D atlas, sampler2D scene, vec2 pixel, float here, float div) {
    WallLayer wall;
    wall.light = vec4(0.0, 0.0, 0.0, 1.0);
    wall.depth = vec3(0.0);
    if (pallHand(here)) return wall;
    ivec2 size = textureSize(scene, 0), low = textureSize(atlas, 0);
    low.x /= 2;
    vec2 at = pixel / div - 0.5;
    ivec2 base = ivec2(floor(at + 0.5));
    // Each column and row of the tent uses the same lattice coordinate,
    // scene pixel and weight three times, including the clamped edge samples.
    ivec3 offsets = ivec3(-1, 0, 1);
    ivec3 cx = clamp(base.x + offsets, 0, low.x - 1);
    ivec3 cy = clamp(base.y + offsets, 0, low.y - 1);
    // History can leave dust in any of the nine taps. Only a completely
    // transmitting neighbourhood is empty regardless of depth class or clipping.
    if (texelFetch(atlas, ivec2(cx.y, cy.y), 0).a == 1.0) {
        bool empty = true;
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                if (texelFetch(atlas, ivec2(cx[x], cy[y]), 0).a != 1.0) empty = false;
            }
        }
        if (empty) return wall;
    }
    ivec3 px = ivec3(min((vec3(cx) + 0.5) * div, float(size.x) - 0.5));
    ivec3 py = ivec3(min((vec3(cy) + 0.5) * div, float(size.y) - 0.5));
    vec3 wx = max(1.5 - abs(vec3(cx) - at.x), 0.0);
    vec3 wy = max(1.5 - abs(vec3(cy) - at.y), 0.0);

    vec4 sum = vec4(0.0);
    vec3 depths = vec3(0.0);
    float weights = 0.0;
    // Only the matching depth class contributes. A thin foreground object
    // with no valid matching tap uses the other class on the second pass.
    for (int pass = 0; pass < 2; pass++) {
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                float there = pallScene(texelFetch(scene, ivec2(px[x], py[y]), 0).r);
                if (pallSamePlace(there, here) != (pass == 0)) continue;
                ivec2 c = ivec2(cx[x], cy[y]);
                vec4 v = texelFetch(atlas, c, 0);
                vec4 span = texelFetch(atlas, c + ivec2(low.x, 0), 0);
                if (!pallValid(v) || !pallValid(span)) continue;
                wallCut(v, span, here);
                float w = wx[x] * wy[y];
                sum += w * v;
                depths += w * (1.0 - v.a) * (span.rgb / max(1.0 - span.a, 1e-6));
                weights += w;
            }
        }
        if (weights > 0.0) break;
    }
    if (weights > 0.0) {
        wall.light = sum / weights;
        float cover = clamp(1.0 - wall.light.a, 0.0, 1.0);
        wall.depth = depths / max(weights * cover, 1e-6);
        wall.light.rgb = pallShown(max(wall.light.rgb, vec3(0.0)), cover);
    }
    return wall;
}

// Place the wall between the measured foreground of a pall ray and the rest.
// total = front.light + front.T * back.light, so the back need never be divided out.
vec4 insertWall(vec4 total, vec4 front, vec4 wall) {
    return vec4(wall.a * total.rgb + (1.0 - wall.a) * front.rgb + front.a * wall.rgb, total.a * wall.a);
}
