#version 150

// Pack the simultaneous march outputs side by side: light, depth span, interval split (wall_layer.glsl). The three thirds
// follow the same temporal resolve and fit one sampler in the pall, quads and source probes.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
out vec4 fragColor;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int width = textureSize(Sampler0, 0).x;
    if (p.x < width) fragColor = texelFetch(Sampler0, p, 0);
    else if (p.x < 2 * width) fragColor = texelFetch(Sampler1, p - ivec2(width, 0), 0);
    else fragColor = texelFetch(Sampler2, p - ivec2(2 * width, 0), 0);
}
