#version 150

// World distance behind pixel along view axis (client/fx/layer/SceneDepth): nearer of Minecraft depth
// (Sampler0) and LOD-mod depth, Distant Horizons or Voxy (Sampler1, client/render/LodDepth); sky is 1e9.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform mat4 InvProj;
uniform mat4 LodInvProj;
// x: 1 if LOD mod rendered this frame; y: 1 if its NDC depth is −1..1 (otherwise 0..1); z: its depth without LOD;
// w: 1 if mod also writes LOD into Minecraft depth, clamped to its far plane (Voxy without shaderpack)
uniform vec4 LodDepth;
// depth the LOD mod uses to mark pixels where Minecraft rendered (Voxy: near plane); −1 means no marker
uniform float LodWorld;
// Two 24-bit depth steps below 1: only clamped LOD is this close to Minecraft far plane (Voxy
// blit_texture_depth_cutout: FAR − 2/(2²⁴−1) in NDC); blocks within render distance are an order closer
const float PINNED = 1.0 - 2.0 / 16777215.0;

out vec4 fragColor;

float along(mat4 inverseProjection, vec2 ndc, float z) {
    vec4 view = inverseProjection * vec4(ndc, z, 1.0);
    return -view.z / view.w;
}

void main() {
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));
    vec2 ndc = uv * 2.0 - 1.0;
    float depth = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r;
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
    fragColor = vec4(dist, 0.0, 0.0, 1.0);
}
