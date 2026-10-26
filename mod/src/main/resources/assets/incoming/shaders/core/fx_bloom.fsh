#version 150

#moj_import <incoming:bloom.glsl>

// Bloom (client/fx/layer/FxBloom) onto frame: halo is an emission share blurred by a pyramid around emitters,
// through 1 − e^(−x); screen blending (ONE, ONE_MINUS_SRC_COLOR) adds light before this curve.
// Light is linear, display uses power 1/2.2.
uniform sampler2D Sampler0;
// pixel fraction covered ahead of halo by emitter and foreground smoke (fx.fsh: fragCover, bloomCovers), at emission-pass level
uniform sampler2D Sampler1;
// xy — 1 / first-level size (half frame); z — halo share (FxBloom.SHARE): gather already divided by level count
uniform vec4 BloomStep;
// 1 / (2^g · emission-pass level g size): frame pixel → emitter coverage position
uniform vec2 BloomCover;
// output viewport origin in frame pixels
uniform vec2 BloomFrom;

out vec4 fragColor;

void main() {
    vec2 at = gl_FragCoord.xy - BloomFrom;
    // halo surrounds the emitter, behind foreground smoke: on fire itself (shown at its own exposure), it would wash out
    // folds and soot; smoke before fire absorbs it
    vec2 uv = at * 0.5 * BloomStep.xy;
    float covered = texture(Sampler1, at * BloomCover).r;
    vec3 light = texture(Sampler0, uv).rgb * BloomStep.z * bloomThrough(covered);
    vec3 shown = pow(vec3(bloomCurve(light.r), bloomCurve(light.g), bloomCurve(light.b)), vec3(1.0 / 2.2));
    // 8-bit frame: faint halo tail bands without dither — half-step noise (Jimenez 2014), only where halo exists
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float top = max(shown.r, max(shown.g, shown.b));
    shown = max(shown + (noise - 0.5) / 255.0 * smoothstep(0.0, 2.0 / 255.0, top), vec3(0.0));
    fragColor = vec4(shown, 1.0);
}
