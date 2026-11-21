#version 330

#moj_import <incoming:pall.glsl>

// World distance for the half-resolution smoke pass (client/fx/layer/HalfPass): pixel (x, y) takes the nearest of the
// four frame pixels (2x..2x+1, 2y..2y+1) where x + y is even and the farthest where it is odd (checkerboard min/max
// depth, as mixed-resolution particle renderers do): every 2×2 neighbourhood of half-resolution samples then holds both
// sides of a silhouette, so the composite (fx_half_composite) finds a sample at each frame pixel's own depth — smoke
// stays sharp against a roof edge both in front of the roof and behind it.
// With it, that pixel's distance to the nearest surface, see-through or not (g): the composite's key, so the tint a window
// gives smoke behind it is not mixed into smoke beside the window.
uniform sampler2D Sampler1;
// the frame's see-through surfaces (see.glsl; SceneDepth.bindSee: own texture unit): the chosen pixel's, for the half pass
uniform sampler2D FxGlass;

layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 fragGlass;

void main() {
    ivec2 size = textureSize(Sampler1, 0), at = ivec2(gl_FragCoord.xy) * 2;
    bool near = ((int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1) == 0;
    // one frame pixel's pair, chosen by its world distance, and that pixel's surface: a pair mixed from two pixels (a wall's
    // world, a window's surface) would belong to no place in the frame
    vec2 pick = vec2(near ? 1e30 : -1.0, 0.0);
    ivec2 from = at;
    for (int k = 0; k < 4; k++) {
        ivec2 px = min(at + ivec2(k & 1, k >> 1), size - 1);
        vec2 d = texelFetch(Sampler1, px, 0).rg;
        d = vec2(pallScene(d.x), pallScene(d.y));
        // equal world distances (a pane against the open sky) are told apart by the surface: both sides stay in the 2×2 set
        bool nearer = d.x < pick.x || (d.x == pick.x && d.y < pick.y), farther = d.x > pick.x || (d.x == pick.x && d.y > pick.y);
        if (near ? nearer : farther) {
            pick = d;
            from = px;
        }
    }
    fragColor = vec4(pick, 0.0, 1.0);
    fragGlass = texelFetch(FxGlass, min(from, textureSize(FxGlass, 0) - 1), 0);
}
