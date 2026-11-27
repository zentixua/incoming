#version 150

// Flash blindness over the finished picture (client/nuclear/NukeFlash, DESIGN fireball-light §4.2), presentation of bleached
// photopigments: colour toward equal-luminance grey (Grey) and the bleached eye's own glow lifting the blacks toward
// LiftColor (Lift, a "screen" blend: bright parts stay, dark parts pale). Each is a
// bounded share of the bleach (NukeFlash), so the world always stays visible through it.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

uniform float Grey;
uniform float Lift;
uniform vec3 LiftColor;

out vec4 fragColor;

void main() {
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(c, vec3(luma), Grey);
    c = 1.0 - (1.0 - c) * (1.0 - Lift * LiftColor);
    fragColor = vec4(c, 1.0);
}
