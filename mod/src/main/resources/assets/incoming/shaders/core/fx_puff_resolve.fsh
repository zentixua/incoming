#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 PuffSize;
uniform vec4 PuffViewport;
out vec4 fragColor;

// GPU Gems 3 ch.23: premultiplied colour + opacity. Bilateral weights keep smoke off foreground edges.
void main() {
    vec2 uv = (gl_FragCoord.xy - PuffViewport.xy) / PuffViewport.zw;
    vec2 texUV = clamp(uv * PuffSize, vec2(0.5), PuffSize - 0.5) / vec2(textureSize(Sampler0, 0));
    vec4 linear = texture(Sampler0, texUV);
    if (linear.a == 0.0) {
        fragColor = vec4(0.0);
        return;
    }
    float scene = texture(Sampler1, uv).r;
    // No foreground surface at this pixel: ordinary bilinear filtering suffices. At geometry use depth weights.
    if (scene > 1e8) {
        fragColor = linear;
        return;
    }
    vec2 p = uv * PuffSize - 0.5;
    ivec2 q = ivec2(floor(p));
    vec2 m = fract(p);
    vec4 sum = vec4(0.0);
    float weight = 0.0;
    float closest = 1e30;
    vec4 nearestDepth = vec4(0.0);
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            ivec2 at = clamp(q + ivec2(x, y), ivec2(0), ivec2(PuffSize) - 1);
            vec2 here = (vec2(at) + 0.5) / PuffSize;
            float d = texture(Sampler1, here).r;
            float ratio = abs(d - scene) / max(1.0, 0.01 * min(d, scene));
            vec4 sampleColor = texelFetch(Sampler0, at, 0);
            if (ratio < closest) { closest = ratio; nearestDepth = sampleColor; }
            vec2 w = mix(vec2(1.0) - m, m, vec2(x, y));
            float v = w.x * w.y / (1.0 + ratio * ratio);
            // Do not renormalize a group of background-only neighbours onto an unresolved foreground surface.
            if (ratio >= 4.0) v = 0.0;
            sum += sampleColor * v;
            weight += v;
        }
    }
    // A grazing continuous surface can differ by >4% between all four coarse samples. Nearest-depth
    // upsampling still has a valid sample there; zero would punch a clear line along the horizon.
    fragColor = weight > 1e-12 ? sum / weight : nearestDepth;
}
