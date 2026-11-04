#version 150

// Smoke grid (client/fx/smoke/SmokeGrid, client/fx/layer/SmokePass): texture-layer cell adds one Gaussian billow's
// contribution. Layer width 2N (page 0 left, page 1 right), height N (world z), layer index cascade·N + height.
// Page 0: soot, dust and droplet extinction (1/m), Σ extinction × ΔT (kK); page 1: Σ dust extinction × dust albedo
// (r, g, b), Σ extinction × rise (m/s). Billow already in its cascade with its σ (half-cell blur, SmokeGrid.put).

// billow table (SmokeGrid.BLOB_ROWS rows per billow): 0 eye-relative center, σh; 1 σv, peak soot, dust, droplet extinction;
// 2 dust albedo, half-segment x; 3 ΔT (kK), rise, half-segment y and z (billow spread along segment, SmokeGrid.line)
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
// cascades: column c — eye-relative corner and cell size
uniform mat4 SmokeCorner;
// x — cascade, y — height layer within it


in float blob;

out vec4 fragColor;

const float SMOKE_N = 64.0;
// Maximum billow extinction in a cell, 1/m: matter-marching step starts at half-cell (≥ 1 m), step optical depth ≥ 64 is
// as opaque as larger values; cap keeps billow sum within half precision (up to 65504), including rise weighting
const float SMOKE_CAP = 128.0;
// maximum rise for billow noise, m/s (same reason)
const float SMOKE_RISE_CAP = 64.0;
// segment shorter than this (in σ) is a point (SmokeGrid.LINE_POINT)
const float SMOKE_LINE_POINT = 0.05;

// Abramowitz–Stegun 7.1.26 erf (SmokeGrid.erf)
float smokeErf(float x) {
    float ax = abs(x);
    float t = 1.0 / (1.0 + 0.3275911 * ax);
    float y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax);
    return sign(x) * y;
}

// segment Gaussian relative to point Gaussian at q (in axes with unit σ), half-segment a there (SmokeGrid.line)
float smokeLine(vec3 q, vec3 a) {
    float len = length(a);
    if (len < SMOKE_LINE_POINT) {
        return exp(-0.5 * dot(q, q));
    }
    vec3 u = a / len;
    float along = dot(q, u);
    float across = dot(q, q) - along * along;
    // far along axis, erf difference reaches approximation error (1.5·10⁻⁷), clamp at zero
    return exp(-0.5 * max(across, 0.0)) * 1.2533141 / (2.0 * len) * max(0.0, smokeErf((len - along) * 0.70710678) + smokeErf((len + along) * 0.70710678));
}

void main() {
    int encoded = int(floor(blob + 0.5));
    int i = encoded % 512, layer = encoded / 512;
    vec2 SmokeSlice = vec2(layer / 64, layer % 64);
    vec4 b0 = texelFetch(Sampler0, ivec2(i, 0), 0), b1 = texelFetch(Sampler0, ivec2(i, 1), 0);
    vec4 b2 = texelFetch(Sampler0, ivec2(i, 2), 0), b3 = texelFetch(Sampler0, ivec2(i, 3), 0);
    float px = floor(gl_FragCoord.x);
    float page = step(SMOKE_N, px);
    vec4 C = SmokeCorner[int(SmokeSlice.x + 0.5)];
    vec3 cell = vec3(px - page * SMOKE_N, SmokeSlice.y, floor(gl_FragCoord.y)) + 0.5;
    vec3 size = vec3(C.w);
    if (SmokeCorner[3].y > 0.0) size.y = SmokeCorner[3].y;
    vec3 p = C.xyz + cell * size;
    vec3 s = vec3(b0.w, b1.x, b0.w);
    if (SmokeCorner[3].y > 0.0) {
        vec2 x = texelFetch(Sampler1, ivec2(int(cell.x), 3*i), 0).rg;
        vec2 y = texelFetch(Sampler1, ivec2(int(cell.y), 3*i+1), 0).rg;
        vec2 z = texelFetch(Sampler1, ivec2(int(cell.z), 3*i+2), 0).rg;
        vec2 density = x*y*z;
        float sigma = b1.z * (0.6*density.x + 0.4*density.y);
        fragColor = page < 0.5 ? vec4(0.0, sigma, 0.0, 0.0) : vec4(sigma * b2.rgb, 0.0);
        return;
    }
    float e = smokeLine((p - b0.xyz) / s, vec3(b2.w, b3.z, b3.w) / s);
    vec3 ext = b1.yzw * e;
    float total = ext.x + ext.y + ext.z;
    float k = min(1.0, SMOKE_CAP / max(total, 1e-12));
    ext *= k;
    total *= k;
    fragColor = page < 0.5 ? vec4(ext, total * b3.x) : vec4(ext.y * b2.rgb, total * min(b3.y, SMOKE_RISE_CAP));
}
