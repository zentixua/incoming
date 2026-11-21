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
    vec2 at = gl_FragCoord.xy / DwDiv - 0.5;
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base);
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1);
        ivec2 c = clamp(base + o, ivec2(0), low - 1);
        vec2 px = min((vec2(c) + 0.5) * DwDiv, vec2(size) - 0.5);
        float there = pallScene(texelFetch(Sampler1, ivec2(px), 0).r);
        float alike = abs(there - here) <= 0.1 * max(min(there, here), 1.0) ? 1.0 : 1e-3;
        float w = mix(1.0 - f.x, f.x, float(o.x)) * mix(1.0 - f.y, f.y, float(o.y)) * alike + 1e-6;
        sum += w * texelFetch(Sampler0, c, 0);
        weights += w;
    }
    vec4 v = sum / weights;
    float cover = clamp(1.0 - v.a, 0.0, 1.0);
    fragColor = vec4(pallShown(max(v.rgb, vec3(0.0)), cover), 1.0 - cover);
}
