#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:see.glsl>

// Young dust wall onto the frame (client/fx/layer/DustWallPass), blended ONE, SRC_ALPHA: depth-aware upsampling of the
// low-resolution history (Sampler0: linear light premultiplied, transmittance). A tent over the 3 × 3 nearest samples
// (radius 1.5 samples) weighted by how alike the world distance behind each sample's ray (Sampler1 r at its frame pixel)
// is to this pixel's: the wall neither bleeds over a roof edge nor leaves a gap behind it. A pixel none of whose samples
// saw its place keeps the world: in front of all of them (a fence post, the first-person hand) the wall is behind it;
// behind them (sky past a roof edge) the nearest samples stand in. The first-person hand keeps the world too. Behind a
// see-through surface (Sampler1 g, FxGlass) the wall reaches the eye through it, as the pall does (see.glsl). Shown like
// the pall (pallShown).
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D FxGlass;
uniform float FxSee;
uniform float DwDiv;

out vec4 fragColor;

void main() {
    ivec2 full = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(Sampler1, 0), low = textureSize(Sampler0, 0);
    float here = pallScene(texelFetch(Sampler1, full, 0).r);
    if (pallHand(here)) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 at = gl_FragCoord.xy / DwDiv - 0.5;
    ivec2 base = ivec2(floor(at + 0.5));
    vec4 sum = vec4(0.0), rest = vec4(0.0);
    float weights = 0.0, restWeights = 0.0, nearest = 1e9;
    for (int k = 0; k < 9; k++) {
        ivec2 c = clamp(base + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), low - 1);
        vec2 d = vec2(c) - at;
        vec2 px = min((vec2(c) + 0.5) * DwDiv, vec2(size) - 0.5);
        float there = pallScene(texelFetch(Sampler1, ivec2(px), 0).r);
        vec4 v = texelFetch(Sampler0, c, 0);
        if (!pallValid(v)) v = vec4(0.0, 0.0, 0.0, 1.0);
        float tent = max(1.5 - abs(d.x), 0.0) * max(1.5 - abs(d.y), 0.0) + 1e-6;
        nearest = min(nearest, there);
        if (abs(there - here) <= 0.1 * max(min(there, here), 1.0)) {
            sum += tent * v;
            weights += tent;
        }
        rest += tent * v;
        restWeights += tent;
    }
    vec4 v;
    if (weights > 0.0) v = sum / weights;
    else if (here < nearest) v = vec4(0.0, 0.0, 0.0, 1.0);
    else v = rest / restWeights;
    float cover = clamp(1.0 - v.a, 0.0, 1.0);
    vec4 laid = vec4(pallShown(max(v.rgb, vec3(0.0)), cover), 1.0 - cover);
    if (FxSee > 0.5) {
        // the wall lies beyond a see-through surface in front of the world: through it, the surface's own light kept
        float surface = pallScene(texelFetch(Sampler1, full, 0).g);
        if (seeBehind(here, surface)) {
            vec4 g = texelFetch(FxGlass, full, 0);
            laid.rgb = vec3(seeMediumLight(laid.r, laid.a, 0.0, 1.0, g.r, g.a), seeMediumLight(laid.g, laid.a, 0.0, 1.0, g.g, g.a),
                    seeMediumLight(laid.b, laid.a, 0.0, 1.0, g.b, g.a));
        }
    }
    fragColor = laid;
}
