#version 150

#moj_import <incoming:pall.glsl>

// Young dust wall onto the frame (client/fx/layer/DustWallPass), blended ONE, SRC_ALPHA: depth-aware upsampling of the
// low-resolution march (Sampler0: linear light premultiplied, transmittance). Each of the four nearest low-resolution
// samples is weighted bilinearly and by how alike the world distance behind its ray (Sampler1 at its frame pixel) is to
// this pixel's: the wall neither bleeds over a roof edge nor leaves a gap behind it. Shown like the pall (pallShown).
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform float DwDiv;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler1, 0), low = textureSize(Sampler0, 0);
    float here = pallScene(texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).r);
    // a tent over the 3×3 nearest low-resolution samples (radius 1.5 samples): the fixed per-ray sample offsets average out
    // into soft dust instead of a still grain; samples behind another surface than this pixel's barely count
    vec2 at = gl_FragCoord.xy / DwDiv - 0.5;
    ivec2 base = ivec2(floor(at + 0.5));
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    for (int k = 0; k < 9; k++) {
        ivec2 c = clamp(base + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), low - 1);
        vec2 d = vec2(c) - at;
        vec2 px = min((vec2(c) + 0.5) * DwDiv, vec2(size) - 0.5);
        float there = pallScene(texelFetch(Sampler1, ivec2(px), 0).r);
        float alike = abs(there - here) <= 0.1 * max(min(there, here), 1.0) ? 1.0 : 1e-3;
        float w = max(1.5 - abs(d.x), 0.0) * max(1.5 - abs(d.y), 0.0) * alike + 1e-6;
        sum += w * texelFetch(Sampler0, c, 0);
        weights += w;
    }
    vec4 v = sum / weights;
    float cover = clamp(1.0 - v.a, 0.0, 1.0);
    fragColor = vec4(pallShown(max(v.rgb, vec3(0.0)), cover), 1.0 - cover);
}
