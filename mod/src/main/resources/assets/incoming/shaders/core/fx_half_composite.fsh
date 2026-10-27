#version 150

#moj_import <incoming:pall.glsl>

// Half-resolution smoke onto the frame (client/fx/layer/HalfPass), blended ONE, SRC_ALPHA: rgb is the smoke light
// over black (premultiplied), alpha what the smoke lets through. Depth-aware upsampling (nearest-depth, as in
// mixed-resolution particle renderers): where the four nearest half-resolution samples lie at this pixel's depth,
// bilinear; where they straddle a silhouette, the sample whose world distance is nearest this pixel's (the
// checkerboard depth, fx_half_depth, holds both sides), so smoke neither bleeds over a roof edge nor leaves a gap
// behind it.
// A sample from a tile with another split (the sorted place of its last full-resolution quad, HalfPass) holds another
// set of the layer's quads: it is not mixed in.
// half-resolution smoke (rgb premultiplied light, a transmittance), its world distances, the frame's world distance,
// each tile's split (r; one texel per tile of HalfTile frame pixels)
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform float HalfTile;

out vec4 fragColor;

// relative world-distance difference counted as the same surface (sky to sky is 0)
const float SAME = 0.1;

void main() {
    ivec2 low = textureSize(Sampler0, 0);
    float here = pallScene(texelFetch(Sampler2, ivec2(gl_FragCoord.xy), 0).r);
    // this pixel's place among the half-resolution sample centres
    vec2 at = gl_FragCoord.xy * 0.5 - 0.5;
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base);
    vec4 sum = vec4(0.0);
    float weights = 0.0, nearest = 1e30;
    vec4 pick = vec4(0.0, 0.0, 0.0, 1.0);
    bool alike = true;
    float cut = texelFetch(Sampler3, ivec2(gl_FragCoord.xy / HalfTile), 0).r;
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1), t = clamp(base + o, ivec2(0), low - 1);
        // the pixel's own sample is always among the four
        if (texelFetch(Sampler3, ivec2((vec2(t) + 0.5) * 2.0 / HalfTile), 0).r != cut) continue;
        float d = texelFetch(Sampler1, t, 0).r;
        vec4 v = texelFetch(Sampler0, t, 0);
        float off = abs(d - here) / max(min(d, here), 1e-3);
        if (here >= 1e8 && d >= 1e8) off = 0.0;
        if (off > SAME) alike = false;
        if (off < nearest) {
            nearest = off;
            pick = v;
        }
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        sum += v * w;
        weights += w;
    }
    vec4 smoke = alike ? sum / max(weights, 1e-6) : pick;
    // 8 bits of the frame: noise of half a step in what the smoke lays (as the layer's own quads, fx.fsh)
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    smoke.rgb = max(smoke.rgb + (noise - 0.5) / 255.0 * (1.0 - smoke.a), vec3(0.0));
    fragColor = smoke;
}
