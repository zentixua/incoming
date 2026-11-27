#version 150

// The pall rays' end at the dust wave (DustWallPass.limit): at every pixel a pall ray reads this frame (PallLimit: the ray frame's
// factor, its interleave n and this frame's offset), the world distance (Sampler1, SceneDepth r) or, nearer, the view depth where
// the wave's drawn dust (Sampler0, the resolved atlas, read as the composite reads it: wallLayer) has let less than the pall's
// RAY_EXIT through. What lies behind is hidden by the dust: the pall's light and alpha stay exact within that share. Other
// pixels are not written (the rays never read them).

#moj_import <incoming:pall.glsl>
#moj_import <incoming:wall_layer.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform float DwDiv;
uniform vec4 PallLimit;

out vec4 fragColor;

// fx_pall's RAY_EXIT: the rays end where 2 % is left
const float DW_LIMIT_T = 0.02;

// View depth where the wall's transmittance from the eye reaches t (wallPrefixT inverted: homogeneous within each interval)
float wallDepthAt(WallLayer w, float t) {
    float aIn = w.depth.x, aOut = w.split.x, tA = w.split.z, bIn = w.split.y, bOut = w.depth.y;
    if (tA <= t) return aIn + (aOut - aIn) * log(t) / log(max(tA, 1e-7));
    float tB = w.light.a / max(tA, 1e-7);
    return bIn + (bOut - bIn) * log(t / tA) / log(max(tB, 1e-7));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 low = floor(vec2(p) / PallLimit.x);
    // the pixel the pall's ray reads for this block: clamped into the frame like its read (an odd width or height puts the
    // last block's centre past the edge, and that texel was never written)
    if (min(ivec2((low + 0.5) * PallLimit.x), textureSize(Sampler1, 0) - 1) != p || any(notEqual(ivec2(mod(low, PallLimit.y)), ivec2(PallLimit.zw)))) discard;
    vec2 raw = texelFetch(Sampler1, p, 0).rg;
    float here = pallScene(raw.r);
    WallLayer w = wallLayer(Sampler0, Sampler1, vec2(p) + 0.5, here, DwDiv);
    float z = raw.r;
    if (w.light.a < DW_LIMIT_T) z = min(here, max(wallDepthAt(w, DW_LIMIT_T), 0.0));
    fragColor = vec4(z, raw.g, 0.0, 1.0);
}
