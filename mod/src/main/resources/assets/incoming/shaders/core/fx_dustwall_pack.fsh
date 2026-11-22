#version 150

// Pack the simultaneous march outputs side by side. Both halves follow the same
// temporal resolve and fit one sampler in the pall, quads and source probes.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
out vec4 fragColor;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int width = textureSize(Sampler0, 0).x;
    if (p.x < width) fragColor = texelFetch(Sampler0, p, 0);
    else fragColor = texelFetch(Sampler1, p - ivec2(width, 0), 0);
}
