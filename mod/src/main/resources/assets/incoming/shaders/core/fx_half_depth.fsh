#version 150

#moj_import <incoming:pall.glsl>

// World distance for the half-resolution smoke pass (client/fx/layer/HalfPass): pixel (x, y) takes the nearest of the
// four frame pixels (2x..2x+1, 2y..2y+1) where x + y is even and the farthest where it is odd (checkerboard min/max
// depth, as mixed-resolution particle renderers do): every 2×2 neighbourhood of half-resolution samples then holds both
// sides of a silhouette, so the composite (fx_half_composite) finds a sample at each frame pixel's own depth — smoke
// stays sharp against a roof edge both in front of the roof and behind it.
// With it, that pixel's distance to the nearest surface, see-through or not (g): the composite's key, so the tint a window
// gives smoke behind it is not mixed into smoke beside the window.
uniform sampler2D Sampler1;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler1, 0), at = ivec2(gl_FragCoord.xy) * 2;
    bool near = ((int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1) == 0;
    // one frame pixel's pair, chosen by its world distance: a pair mixed from two pixels (a wall's world, a window's surface)
    // would belong to no place in the frame
    vec2 pick = vec2(near ? 1e30 : -1.0, 0.0);
    for (int k = 0; k < 4; k++) {
        vec2 d = texelFetch(Sampler1, min(at + ivec2(k & 1, k >> 1), size - 1), 0).rg;
        d = vec2(pallScene(d.x), pallScene(d.y));
        if (near ? d.x < pick.x : d.x > pick.x) pick = d;
    }
    fragColor = vec4(pick, 0.0, 1.0);
}
