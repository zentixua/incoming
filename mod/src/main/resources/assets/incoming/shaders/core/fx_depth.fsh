#version 330

#moj_import <incoming:see.glsl>

// World distance behind pixel along view axis (client/fx/layer/SceneDepth): nearer of Minecraft depth and LOD-mod depth,
// Distant Horizons or Voxy (Sampler1, client/render/LodDepth); sky is 1e9. With see-through surfaces (SeeThrough): the
// opaque world (Sampler0, its depth before translucent terrain) hides the layer, the nearest translucent surface (Sampler2)
// tints what lies behind it. Without them all depth samplers hold the world's depth: the main path exactly.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
// depth with translucent terrain (BLEND — its copy after it; Fabulous — the translucent target's)
uniform sampler2D Sampler2;
// depth now (BLEND: what was drawn after translucent terrain and hides the layer as before; Fabulous — as Sampler2)
uniform sampler2D Sampler3;
// the frame after translucent terrain (BLEND), the translucent target's colour (Fabulous)
uniform sampler2D Sampler4;
// the frame before translucent terrain (BLEND)
uniform sampler2D Sampler5;

uniform mat4 InvProj;
uniform mat4 LodInvProj;
// x: 1 if LOD mod rendered this frame; y: 1 if its NDC depth is −1..1 (otherwise 0..1); z: its depth without LOD;
// w: 1 if mod also writes LOD into Minecraft depth, clamped to its far plane (Voxy without shaderpack)
uniform vec4 LodDepth;
// depth the LOD mod uses to mark pixels where Minecraft rendered (Voxy: near plane); −1 means no marker
uniform float LodWorld;
// x: SeeThrough.Mode — 0 none, 1 BLEND, 2 FABULOUS
uniform vec4 See;
// Two 24-bit depth steps below 1: only clamped LOD is this close to Minecraft far plane (Voxy
// blit_texture_depth_cutout: FAR − 2/(2²⁴−1) in NDC); blocks within render distance are an order closer
const float PINNED = 1.0 - 2.0 / 16777215.0;

// r: the distance that hides the layer; g: to the nearest surface, see-through or not (the world's where there is none)
layout(location = 0) out vec4 fragColor;
// the see-through surface's own light X (rgb) and transmittance T (a), see.glsl; (0, 0, 0, 1) — none
layout(location = 1) out vec4 fragGlass;

float along(mat4 inverseProjection, vec2 ndc, float z) {
    vec4 view = inverseProjection * vec4(ndc, z, 1.0);
    return -view.z / view.w;
}

// distance behind the pixel in a Minecraft depth texture, merged with LOD depth
float worldAt(vec2 uv, vec2 ndc, float depth) {
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

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));
    vec2 ndc = uv * 2.0 - 1.0;
    float depth = texelFetch(Sampler0, px, 0).r;
    float dist = worldAt(uv, ndc, depth);
    float surface = dist, hard = depth;
    vec4 glass = vec4(0.0, 0.0, 0.0, 1.0);
    if (See.x > 0.5) {
        float near = texelFetch(Sampler2, px, 0).r, now = texelFetch(Sampler3, px, 0).r;
        if (now < near) {
            // drawn after translucent terrain (lines, translucent entities, particles): it hides the layer, as before
            dist = surface = worldAt(uv, ndc, now);
            hard = now;
        } else {
            float seen = worldAt(uv, ndc, near);
            if (seen < dist * 0.9995 - 0.005) {
                vec4 after = texelFetch(Sampler4, px, 0);
                float through = 1.0 - after.a;
                // BLEND: the frame was X + T·before; Fabulous: the target holds X itself
                vec3 own = See.x < 1.5 ? max(after.rgb - through * texelFetch(Sampler5, px, 0).rgb, vec3(0.0)) : after.rgb;
                if (through < 0.5 / 255.0) {
                    // a fully covering surface (an opaque particle): the world here, it hides the layer
                    dist = seen;
                    hard = near;
                } else {
                    glass = vec4(own, through);
                }
                surface = seen;
            }
        }
    }
    fragColor = vec4(dist, surface, 0.0, 1.0);
    fragGlass = glass;
    // the layer's hardware depth: the opaque world, and what hides the layer drawn after it
    gl_FragDepth = hard;
}
