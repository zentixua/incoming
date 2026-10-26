#version 150

// Round glow by formula rather than image: ball halo can exceed screen size, so a 64×64 image would show its pixels
// as squares (nearest: steps; smooth: sampling slope breaks at each). Bright core and soft
// halo (r is center distance in half-size units), smoothly zero at edge; vertex color/strength.
in vec2 texCoord0;
in vec4 vertexColor;

uniform vec4 ColorModulator;

out vec4 fragColor;

float flare(float r) {
    float a = 0.6 * exp(-(r / 0.15) * (r / 0.15)) + 0.4 * exp(-(r / 0.5) * (r / 0.5));
    return a * smoothstep(0.0, 0.3, 1.0 - r);
}

void main() {
    float a = flare(length(texCoord0 * 2.0 - 1.0));
    if (a <= 0.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * a) * ColorModulator;
}
