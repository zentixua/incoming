#version 150

#moj_import <incoming:pall.glsl>

// World distance for the half-resolution smoke pass (client/fx/layer/HalfPass): pixel (x, y) takes the nearest of the
// four frame pixels (2x..2x+1, 2y..2y+1) where x + y is even and the farthest where it is odd (checkerboard min/max
// depth, as mixed-resolution particle renderers do): every 2×2 neighbourhood of half-resolution samples then holds both
// sides of a silhouette, so the composite (fx_half_composite) finds a sample at each frame pixel's own depth — smoke
// stays sharp against a roof edge both in front of the roof and behind it.
// The same for the distance to the nearest surface, see-through or not (g): the composite's key, so the tint a window gives
// smoke behind it is not mixed into smoke beside the window.
uniform sampler2D Sampler1;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler1, 0), at = ivec2(gl_FragCoord.xy) * 2;
    vec2 a = texelFetch(Sampler1, min(at, size - 1), 0).rg;
    vec2 b = texelFetch(Sampler1, min(at + ivec2(1, 0), size - 1), 0).rg;
    vec2 c = texelFetch(Sampler1, min(at + ivec2(0, 1), size - 1), 0).rg;
    vec2 d = texelFetch(Sampler1, min(at + ivec2(1, 1), size - 1), 0).rg;
    a = vec2(pallScene(a.x), pallScene(a.y));
    b = vec2(pallScene(b.x), pallScene(b.y));
    c = vec2(pallScene(c.x), pallScene(c.y));
    d = vec2(pallScene(d.x), pallScene(d.y));
    bool near = ((int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1) == 0;
    fragColor = vec4(near ? min(min(a, b), min(c, d)) : max(max(a, b), max(c, d)), 0.0, 1.0);
}
