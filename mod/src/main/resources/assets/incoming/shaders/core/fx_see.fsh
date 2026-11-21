#version 150

// The frame's see-through surfaces (client/fx/layer/SeeThrough), right after translucent terrain: where it partly covers a
// pixel, its own light X (rgb, premultiplied) and transmittance T (a), see.glsl. BLEND (vanilla Fast/Fancy, Sodium): the
// frame now (Sampler0) holds X + T·before with the coverage 1 − T in its alpha, which was cleared before translucent
// terrain; Sampler1 is the frame before it. Fabulous: Sampler0 is vanilla's translucent target, X and 1 − T themselves.
// Fully covered pixels (opaque particles, the mod's block fragments, drawn there in Fast/Fancy) and uncovered ones (depth
// only: a boat's water mask) are not written: the target's (0, 0, 0, 0) reads as covered, the layer is hidden there as
// on main, and the pass's samples (an occlusion query) tell whether any see-through surface is on screen.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
// x: 1 BLEND, 2 Fabulous
uniform vec4 See;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 after = texelFetch(Sampler0, px, 0);
    float through = 1.0 - after.a;
    if (through < 0.5 / 255.0 || through > 1.0 - 0.5 / 255.0) discard;
    vec3 own = See.x < 1.5 ? max(after.rgb - through * texelFetch(Sampler1, px, 0).rgb, vec3(0.0)) : after.rgb;
    fragColor = vec4(own, through);
}
