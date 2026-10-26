#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:scene.glsl>
#moj_import <incoming:heat.glsl>

// Conventional ball/jet direct-light visibility (client/fx/layer/HeatPass), half-resolution: sample t is frame pixel 2t.
// Shadow rays traverse the block window (heatShadows, heat.glsl); fullscreen light pass (fx_heat) chooses a sample with
// nearest world distance. Output: r ball codes (2 bits: 0, ¼, ¾, 1), g jet codes (1 bit), b sample world distance along
// view. Shadow rays dominate light-pass cost: half-resolution gives four times fewer rays, with shadow sharpness twice
// the frame pixel size.

// world distance behind pixel (SceneDepth)
uniform sampler2D Sampler1;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform vec3 PallOpenSky;
uniform vec4 PallFaceShade;
// unused; pixel angle, rad; light present (z > 0); unused
uniform vec4 HeatFrame;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler1, 0);
    ivec2 px = min(ivec2(gl_FragCoord.xy) * 2, size - 1);
    float depth = pallScene(texelFetch(Sampler1, px, 0).r);
    vec4 ballSeen = vec4(1.0), jetSeen = vec4(1.0);
    if (HeatFrame.z > 0.0 && depth < 1e8) {
        vec3 p = sceneAt(Sampler1, InvViewProj, PallForward, px, size);
        vec3 n = sceneNormal(Sampler1, InvViewProj, PallForward, px, size, p);
        vec3 open = PallOpenSky * faceShade(PallFaceShade, n);
        float prior = dot(vec3(pallLinear(open.r), pallLinear(open.g), pallLinear(open.b)), LUMA);
        heatShadows(p, n, prior, HeatFrame.y, ballSeen, jetSeen);
    }
    vec4 b = step(vec4(0.125), ballSeen) + step(vec4(0.5), ballSeen) + step(vec4(0.875), ballSeen);
    float balls = dot(b, vec4(1.0, 4.0, 16.0, 64.0)), jets = dot(step(vec4(0.5), jetSeen), vec4(1.0, 2.0, 4.0, 8.0));
    fragColor = vec4(balls, jets, depth, 1.0);
}
