#version 150

#moj_import <incoming:pall.glsl>

// Whole pall in front of the world behind each pixel of the reduced frame (client/fx/layer/PallPass.frame): the near volume
// (pall.glsl: froxelNear) at the world distance behind the frame pixel's ray, then this frame's rays beyond it. Layer quads
// (fx.fsh: Sampler6) take the pall's colour and the world's share from it, as before the volume existed; the composite
// does not use it (it reads the volume at every full-frame pixel)

// accumulated rays (fx_pall_resolve)
uniform sampler2D Sampler0;
// pixel world distance along the view axis (full resolution)
uniform sampler2D Sampler1;
// near pall seen to each slice (fx_pall_froxel_sum)
uniform sampler2D Sampler2;

// z: frame downsample factor
uniform vec4 PallFrame;
uniform vec4 PallFroxel;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy), fullSize = textureSize(Sampler1, 0);
    vec4 far = texelFetch(Sampler0, px, 0);
    // the hand's pixels stay invalid (fx_pall_resolve)
    if (!pallValid(far)) {
        fragColor = far;
        return;
    }
    ivec2 full = clamp(ivec2((vec2(px) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
    float depth = pallScene(texelFetch(Sampler1, full, 0).r);
    vec4 near = froxelNear(Sampler2, PallFroxel, vec2(full) + 0.5, depth);
    fragColor = vec4(near.rgb + near.a * far.rgb, near.a * far.a);
}
