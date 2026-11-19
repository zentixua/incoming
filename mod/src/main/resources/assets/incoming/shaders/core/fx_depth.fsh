#version 330

// World distance behind pixel along view axis (client/fx/layer/SceneDepth): nearer of Minecraft depth and LOD-mod depth,
// Distant Horizons or Voxy (Sampler1, client/render/LodDepth); sky is 1e9. With see-through surfaces (SeeThrough): the
// opaque world (Sampler0, its depth before translucent geometry) hides the layer, the nearer translucent surface
// (Sampler2) tints what lies behind it.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
// depth with translucent geometry (the world's now; Fabulous — its translucent target's)
uniform sampler2D Sampler2;
// the frame now
uniform sampler2D Sampler3;
// BLEND — the frame before translucent geometry; FABULOUS — the translucent target's colour
uniform sampler2D Sampler4;

uniform mat4 InvProj;
uniform mat4 LodInvProj;
// x: 1 if LOD mod rendered this frame; y: 1 if its NDC depth is −1..1 (otherwise 0..1); z: its depth without LOD;
// w: 1 if mod also writes LOD into Minecraft depth, clamped to its far plane (Voxy without shaderpack)
uniform vec4 LodDepth;
// depth the LOD mod uses to mark pixels where Minecraft rendered (Voxy: near plane); −1 means no marker
uniform float LodWorld;
// x: SeeThrough.Mode — 0 none, 1 BLEND, 2 FABULOUS, 3 PACK
uniform vec4 See;
// world up in view space: a translucent surface seen from above is a fluid's top or a floor
uniform vec3 ViewUp;
// Two 24-bit depth steps below 1: only clamped LOD is this close to Minecraft far plane (Voxy
// blit_texture_depth_cutout: FAR − 2/(2²⁴−1) in NDC); blocks within render distance are an order closer
const float PINNED = 1.0 - 2.0 / 16777215.0;
// Nearer than this, a surface in front of the world is the first-person hand (Iris draws it in the world pass), not a block:
// the player's body keeps blocks 0.3 away. As PALL_HAND (pall.glsl)
const float SEE_HAND = 0.12;
// A translucent surface whose eye-facing normal is this close to up is seen from above: water, ice, a glass floor. What lies
// under it is under water or in a room; the layer's air-borne effects are not there, and it stays hidden as before
const float SEE_FROM_ABOVE = 0.9;

layout(location = 0) out vec4 fragColor;
// distance to the nearer see-through surface (the world's where there is none)
layout(location = 1) out vec4 fragSurface;
// the surface's own light X (rgb) and transmittance T (a), see.glsl
layout(location = 2) out vec4 fragGlass;

float along(mat4 inverseProjection, vec2 ndc, float z) {
    vec4 view = inverseProjection * vec4(ndc, z, 1.0);
    return -view.z / view.w;
}

// distance behind the pixel in a Minecraft depth texture, merged with LOD depth
float worldAt(sampler2D tex, vec2 uv, vec2 ndc, float depth) {
    // clamped LOD is not a far-plane wall: LOD depth supplies its distance
    bool pinned = LodDepth.w > 0.5 && depth >= PINNED;
    float dist = depth >= 1.0 || pinned ? 1e9 : along(InvProj, ndc, depth * 2.0 - 1.0);
    // world must be in front of eye and at finite distance; otherwise (NaN, infinity, behind eye) sky: pall/effects
    // layer use world distance directly, so NaN here becomes a black frame pixel
    if (!(dist > 0.0 && dist < 1e8)) dist = 1e9;
    if (LodDepth.x > 0.5) {
        // nearest texel, not neighbor blend: at a hill edge, blending LOD with sky/world marker gives a distance
        // belonging to nothing in frame
        float lod = texelFetch(Sampler1, ivec2(uv * vec2(textureSize(Sampler1, 0))), 0).r;
        if (abs(lod - LodDepth.z) > 1e-7 && abs(lod - LodWorld) > 1e-7) {
            float far = along(LodInvProj, ndc, LodDepth.y > 0.5 ? lod * 2.0 - 1.0 : lod);
            // near LOD far plane or with foreign projection (wrong matrix/depth range), LOD lies behind
            // eye or infinitely far away: no LOD for this pixel
            if (far > 0.0 && far < 1e8) dist = min(dist, far);
        }
    }
    return dist;
}

// view-space point of the surface depth at pixel px
vec3 surfaceAt(ivec2 px, ivec2 size) {
    ivec2 c = clamp(px, ivec2(0), size - 1);
    vec2 ndc = (vec2(c) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 v = InvProj * vec4(ndc, texelFetch(Sampler2, c, 0).r * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

// eye-facing normal of the surface from its neighbors: per screen axis the nearer one in depth (at an edge the other one is
// another object)
vec3 surfaceNormal(ivec2 px, ivec2 size, vec3 p) {
    vec3 r = surfaceAt(px + ivec2(1, 0), size) - p, l = p - surfaceAt(px - ivec2(1, 0), size);
    vec3 u = surfaceAt(px + ivec2(0, 1), size) - p, d = p - surfaceAt(px - ivec2(0, 1), size);
    vec3 n = cross(dot(l, l) < dot(r, r) ? l : r, dot(d, d) < dot(u, u) ? d : u);
    n *= inversesqrt(max(dot(n, n), 1e-20));
    return dot(n, p) > 0.0 ? -n : n;
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy), size = textureSize(Sampler0, 0);
    vec2 uv = gl_FragCoord.xy / vec2(size);
    vec2 ndc = uv * 2.0 - 1.0;
    float depth = texelFetch(Sampler0, px, 0).r;
    float dist = worldAt(Sampler0, uv, ndc, depth);
    float surface = dist, hard = depth;
    vec4 glass = vec4(0.0, 0.0, 0.0, 1.0);
    if (See.x > 0.5) {
        float near = texelFetch(Sampler2, px, 0).r;
        float seen = worldAt(Sampler2, uv, ndc, near);
        if (seen < dist * 0.9995 - 0.005) {
            vec4 now = texelFetch(Sampler3, px, 0);
            vec4 other = texelFetch(Sampler4, px, 0);
            float through = 1.0;
            vec3 own = vec3(0.0);
            if (See.x < 1.5) {
                // vanilla blending left the coverage in the cleared alpha
                through = 1.0 - now.a;
                own = max(now.rgb - through * other.rgb, vec3(0.0));
            } else if (See.x < 2.5) {
                through = 1.0 - other.a;
                own = other.rgb;
            }
            vec3 p = surfaceAt(px, size);
            bool solid = seen < SEE_HAND || through < 0.5 / 255.0 || dot(surfaceNormal(px, size, p), ViewUp) > SEE_FROM_ABOVE;
            if (solid) {
                // the surface is the world here: the layer is hidden by it, as by any block
                dist = seen;
                hard = near;
            } else {
                glass = vec4(own, through);
            }
            surface = seen;
        }
    }
    fragColor = vec4(dist, 0.0, 0.0, 1.0);
    fragSurface = vec4(surface, 0.0, 0.0, 1.0);
    fragGlass = glass;
    // the layer's hardware depth: the opaque world, and the surfaces it must not look through
    gl_FragDepth = hard;
}
