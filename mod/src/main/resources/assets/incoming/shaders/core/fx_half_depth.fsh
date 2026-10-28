#version 150

#moj_import <incoming:pall.glsl>

// World distance for the half-resolution smoke pass (client/fx/layer/HalfPass): pixel (x, y) takes the nearest of the
// four frame pixels (2x..2x+1, 2y..2y+1) where x + y is even and the farthest where it is odd (checkerboard min/max
// depth, as mixed-resolution particle renderers do): every 2×2 neighbourhood of half-resolution samples then holds both
// sides of a silhouette, so the composite (fx_half_composite) finds a sample at each frame pixel's own depth — smoke
// stays sharp against a roof edge both in front of the roof and behind it.
uniform sampler2D Sampler1;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler1, 0), at = ivec2(gl_FragCoord.xy) * 2;
    float a = pallScene(texelFetch(Sampler1, min(at, size - 1), 0).r);
    float b = pallScene(texelFetch(Sampler1, min(at + ivec2(1, 0), size - 1), 0).r);
    float c = pallScene(texelFetch(Sampler1, min(at + ivec2(0, 1), size - 1), 0).r);
    float d = pallScene(texelFetch(Sampler1, min(at + ivec2(1, 1), size - 1), 0).r);
    bool near = ((int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1) == 0;
    fragColor = vec4(near ? min(min(a, b), min(c, d)) : max(max(a, b), max(c, d)), 0.0, 0.0, 1.0);
}
