#version 150

// Water decal (client/fx/layer/WaterDecal): dark nuclear-wave front ring, post-front wind ripples, and whitecap foam
// near epicenter. Pixel position from view-axis world distance (Sampler0, SceneDepth) and frame
// matrices; water means horizontal surface with neighboring pixels at same height (walls, trunks,
// grass, mobs, slopes and object edges against sky at same within-block height are not water), at standing-water top,
// 8/9 block (lower under shaderpack, for its waves); beyond render distance (DH LOD), sea-level surface or below-sea
// position, then ray/surface intersection. Ring profiles in Sampler1: one ring per row, x is square root of front-to-center
// path fraction; r darkness, g foam, b conventional-explosion lace (0 for nuke). Each ring has own noise from center (distant world
// coordinates lack float fractional precision; detonation center stays fixed).
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform mat4 InvProj;
uniform mat4 InvView;
// camera height, blocks
uniform float CamY;
// x: horizontal render-chunk reach (beyond: DH LOD); y: sea surface height relative to camera; z: ring count; w: skylight
uniform vec4 WaterParams;
// x: 3.912 / visibility range, 1/block; y/z: Minecraft fog start/end; w: 1 for cylindrical fog
uniform vec4 Haze;
// pixel angle, rad
uniform float Pixel;
// shaderpack (0 without): x: how far waves lower surface below 8/9, blocks; y: surface height difference from vertex
// to neighbor, blocks; z: frame TAA jitter, pixels
uniform vec3 Pack;
// rings: eye-relative center x/z; water-front radius; trailing profile length, blocks
uniform vec4 Ring0;
uniform vec4 Ring1;
uniform vec4 Ring2;
uniform vec4 Ring3;

out vec4 fragColor;

float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

vec3 unproject(vec2 ndc, float z) {
    vec4 v = InvProj * vec4(ndc, z, 1.0);
    return v.xyz / v.w;
}

// world position behind pixel relative to camera (sky: far ray point, allowing numeric differences); o is ray origin
vec3 scene(ivec2 px, ivec2 size, out vec3 o) {
    float dist = texelFetch(Sampler0, px, 0).r;
    vec2 ndc = (vec2(px) + 0.5) / vec2(size) * 2.0 - 1.0;
    // ray point at view-axis distance dist, then relative to camera position
    vec3 n = unproject(ndc, -1.0);
    vec3 f = unproject(ndc, 1.0);
    float d = min(dist, 1e6);
    o = (InvView * vec4(n, 1.0)).xyz;
    return (InvView * vec4(n + (f - n) * ((d + n.z) / (n.z - f.z)), 1.0)).xyz;
}

// screen-axis step to neighbor pixel: choose height-nearest of two (beyond object edge the other is background
// or sky, at shore land); no neighbor outside frame
vec3 neighbour(ivec2 px, ivec2 axis, vec3 p, ivec2 size) {
    ivec2 a = px + axis;
    ivec2 b = px - axis;
    bool hasA = all(lessThan(a, size));
    bool hasB = all(greaterThanEqual(b, ivec2(0)));
    vec3 o;
    vec3 da = hasA ? scene(a, size, o) - p : vec3(0.0);
    vec3 db = hasB ? p - scene(b, size, o) : vec3(0.0);
    if (!hasA) return db;
    if (!hasB) return da;
    return abs(da.y) <= abs(db.y) ? da : db;
}

vec4 ring(int i) {
    if (i == 0) return Ring0;
    if (i == 1) return Ring1;
    if (i == 2) return Ring2;
    return Ring3;
}

const float PI = 3.14159265;

// whitecaps: x along wind, y across, blocks
float whitecaps(vec2 q) {
    return noise(q / vec2(3.5, 1.2)) * 0.55 + noise(q / vec2(12.0, 4.0)) * 0.45;
}

// foam angle sectors: within each, arc from its axis is nearly unskewed
const float SECTORS = 24.0;

// whitecaps in sector k (axis angle k·2π/24 − π from world x) at radius r/angle a: radius along wind, arc across
// from axis (at sector axis, cross-radius distance); sector 24 is sector 0
float sectorCaps(float r, float a, float k) {
    float s = mod(k, SECTORS);
    return whitecaps(vec2(r, r * (a - (k * (2.0 * PI / SECTORS) - PI))) + vec2(s * 17.0, s * 31.0));
}

// whitecap foam from ring center (rel relative to it): post-front wind blows from center, so strips use polar
// coordinates (center-relative position rotated along wind from same center is radius and zero: foam would form circles). Angle
// from world axes, not camera (otherwise pattern drifts sideways as camera orbits burst), arc from own sector axis:
// one-axis arc for whole circle would skew strips away from it (arc grows along radius too). Adjacent sectors
// blend smoothly at same noise strength
float caps(vec2 rel) {
    float r = length(rel);
    float a = atan(rel.y, rel.x);
    float t = (a + PI) * (SECTORS / (2.0 * PI));
    float k = floor(t);
    float w = smoothstep(0.25, 0.75, t - k);
    float c = mix(sectorCaps(r, a, k), sectorCaps(r, a, k + 1.0), w);
    return 0.5 + (c - 0.5) * inversesqrt(1.0 - 2.0 * w * (1.0 - w));
}

// presentation: conventional foam patches and fine lace in ring coordinates. Model share does not become a solid
// white layer: water/reflections show through even dense patches. Nuclear whitecaps remain in caps.
float foamLace(vec2 rel, float foot) {
    vec2 warp = vec2(noise(rel / 6.0), noise(rel / 6.0 + vec2(19.0, 37.0))) - 0.5;
    vec2 q = rel + 3.0 * warp;
    float patches = smoothstep(0.28, 0.65, noise(q / 9.0));
    float lace = 1.0 - smoothstep(0.05, 0.18, abs(noise(q / 1.7) - 0.5));
    lace = mix(lace, 0.5, smoothstep(0.3, 1.5, foot));
    return patches * (0.25 + 0.75 * lace);
}

// standing-water top above block bottom
const float SURFACE = 8.0 / 9.0;

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 px = ivec2(gl_FragCoord.xy);
    if (texelFetch(Sampler0, px, 0).r > 1e8) discard;
    vec3 o;
    vec3 p = scene(px, size, o);
    vec3 ray = p - o;
    float len = max(length(ray), 1e-3);
    // surface slope from neighbor pixels; each axis chooses neighbor on same surface
    vec3 across = neighbour(px, ivec2(1, 0), p, size);
    vec3 along = neighbour(px, ivec2(0, 1), p, size);
    vec3 face = cross(across, along);
    float area = length(face);
    // pixel step on surface along ray, blocks
    float reach = len * Pixel / max(abs(ray.y) / len, 0.002);
    // depth-derived height gets coarser with distance and steeper downward ray (24-bit depth: error ~d² for 0.05 near plane);
    // near tolerance narrower than 1/72 block: 14/16-high blocks (chest, 7 snow layers) are not water
    float tol = clamp(0.01 + 2.4e-6 * len * abs(ray.y), 0.01, 0.037);
    // normal within 20° of vertical, neighbors at same height, step is not a jump behind object. Surface neighbor
    // height difference is depth noise (and pack waves); wall difference is pixel height step (len·Pixel); a distant side-face
    // pixel below block-top edge is 1/9 block from top neighbor, while jump along nearly horizontal ray
    // (leaf/grass/roof-ridge edge against distant background) grows with jump distance from surface pixel step:
    // side step plus such jump gives horizontal, but wrong height
    float rise = max(abs(across.y), abs(along.y));
    bool level = area > 0.0 && abs(face.y) > 0.94 * area && max(length(across), length(along)) < 3.0 * reach + 0.5
            && rise < 0.002 + 0.1 * len * Pixel + (tol - 0.01) + Pack.y * min(1.0, reach);
    // shaderpack frame jitter shifts depth ray: position-height error is shift × distance
    float jitter = Pack.z * Pixel * length(ray.xz);
    float h = fract(CamY + p.y) - SURFACE;
    bool water = level && h > -(tol + jitter + Pack.x) && h < tol + jitter;
    if (!water && length(p.xz) > WaterParams.x) {
        float sea = WaterParams.y;
        if (level && p.y > sea - 1.2 && p.y < sea + 0.15) {
            water = true;
        } else if (p.y < sea && o.y > sea) {
            // distant below-sea terrain: LOD lacking water depth shows bed; water above it at surface
            p = o + (p - o) * ((sea - o.y) / (p.y - o.y));
            water = true;
        }
    }
    if (!water) discard;

    ray = p - o;
    len = max(length(ray), 1e-3);
    // water pixel footprint, blocks: longer along shallow ray
    float foot = max(0.2, len * Pixel / max(abs(ray.y) / len, 0.02));
    float dark = 0.0;
    float foam = 0.0;
    int rings = int(WaterParams.z + 0.5);
    for (int i = 0; i < 4; i++) {
        if (i >= rings) break;
        vec4 rg = ring(i);
        vec2 rel = p.xz - rg.xy;
        float behind = rg.z - length(rel);
        if (behind < -foot) continue;
        float edge = smoothstep(-foot, foot, behind);
        float u = sqrt(clamp(behind / rg.w, 0.0, 1.0));
        vec3 profile = textureLod(Sampler1, vec2((u * 63.0 + 0.5) / 64.0, (float(i) + 0.5) / 4.0), 0.0).rgb;
        vec2 prof = profile.rg * edge;
        if (prof.r > 0.002) {
            // cat's paws: ripple patches of varying density; subpixel tends to mean
            float paws = noise(rel / 23.0) * 0.6 + noise(rel / 7.0) * 0.4;
            paws = mix(paws, 0.5, clamp(foot / 7.0, 0.0, 1.0));
            dark = 1.0 - (1.0 - dark) * (1.0 - clamp(prof.r * (0.65 + 0.7 * paws), 0.0, 1.0));
        }
        if (prof.g > 0.002) {
            // whitecap foam: fine patches stretched into wind-aligned strips, their share is profile foam; subpixel uses
            // share itself
            float f = prof.g;
            if (profile.b > 0.5) f = 0.6 * f * foamLace(rel, foot);
            else if (foot < 1.5) f = mix(smoothstep(1.0 - f, 1.12 - f, caps(rel)), f, foot / 1.5);
            foam = max(foam, f);
        }
    }
    if (dark <= 0.002 && foam <= 0.002) discard;

    // air haze/Minecraft fog attenuate decal like particles
    float fd = Haze.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p);
    float seenAir = exp(-Haze.x * len) * (1.0 - smoothstep(Haze.y, Haze.z, fd));
    float k = dark * seenAir;
    float fo = clamp(foam, 0.0, 1.0) * seenAir;
    float sky = WaterParams.w;
    // ripples reflect less sky: darker water with depth blue; foam white in skylight
    vec3 tint = vec3(0.02, 0.035, 0.05) * sky;
    vec3 white = vec3(0.86, 0.9, 0.93) * (0.1 + 0.9 * sky);
    fragColor = vec4(tint * k * (1.0 - fo) + white * fo, k + fo - k * fo);
}
