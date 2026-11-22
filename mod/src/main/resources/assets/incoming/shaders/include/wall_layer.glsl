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
    vec4 sum = vec4(0.0), fallback = vec4(0.0);
    vec3 depths = vec3(0.0), otherDepths = vec3(0.0);
    float weights = 0.0, other = 0.0;
    for (int k = 0; k < 9; k++) {
        ivec2 c = clamp(base + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), low - 1);
        vec2 px = min((vec2(c) + 0.5) * div, vec2(size) - 0.5);
        float there = pallScene(texelFetch(scene, ivec2(px), 0).r);
        vec4 v = texelFetch(atlas, c, 0);
        vec4 span = texelFetch(atlas, c + ivec2(low.x, 0), 0);
        if (!pallValid(v) || !pallValid(span)) continue;
        vec2 tent = max(1.5 - abs(vec2(c) - at), vec2(0.0));
        float w = tent.x * tent.y;
        wallCut(v, span, here);
        float cover = 1.0 - v.a;
        vec3 z = span.rgb / max(1.0 - span.a, 1e-6);
        if (pallSamePlace(there, here)) {
            sum += w * v;
            depths += w * cover * z;
            weights += w;
        } else {
            fallback += w * v;
            otherDepths += w * cover * z;
            other += w;
        }
    }
    if (weights <= 0.0) {
        sum = fallback;
        depths = otherDepths;
        weights = other;
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
