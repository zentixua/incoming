#version 150

// On-screen concussion (client/fx/Concussion), presentation: Gaussian along BlurDir (σ = Radius, pixels), with second
// pass (Final = 1) also desaturating toward equal-luminance gray (Grey) and darkening edges (Edge).

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
in vec2 sampleStep;

uniform vec2 OutSize;
uniform float Radius;
uniform float Grey;
uniform float Edge;
uniform float Final;

out vec4 fragColor;

// Gaussian to 3σ, adjacent pixel pairs in one interpolated sample (linear): pair-summed weight, weighted position.
// Continuous σ makes blur fade without steps.
vec3 blurred() {
    vec3 centre = texture(DiffuseSampler, texCoord).rgb;
    if (Radius < 0.2) {
        return centre;
    }
    float inv = 0.5 / (Radius * Radius);
    float reach = ceil(3.0 * Radius);
    vec3 sum = centre;
    float total = 1.0;
    for (float k = 1.0; k <= reach; k += 2.0) {
        float w0 = exp(-k * k * inv);
        float w1 = exp(-(k + 1.0) * (k + 1.0) * inv);
        float w = w0 + w1;
        vec2 at = sampleStep * (k + w1 / w);
        sum += (texture(DiffuseSampler, texCoord + at).rgb + texture(DiffuseSampler, texCoord - at).rgb) * w;
        total += 2.0 * w;
    }
    return sum / total;
}

void main() {
    vec3 c = blurred();
    // Rec. 709 luminance; gray toward it, not channel mean (would darken greenery); bright colors (fire, sky) barely
    // desaturate: surroundings dim, not the explosion itself
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float bright = smoothstep(0.6, 1.0, max(c.r, max(c.g, c.b)));
    vec3 grey = mix(c, vec3(luma), Grey * (1.0 - bright));
    // edges: center toward frame corner (aspect-corrected), smoothly darkening from one third of the path
    vec2 p = (texCoord - 0.5) * vec2(OutSize.x / OutSize.y, 1.0);
    float r = length(p) / length(vec2(0.5 * OutSize.x / OutSize.y, 0.5));
    vec3 graded = grey * (1.0 - Edge * smoothstep(0.35, 1.0, r));
    fragColor = vec4(mix(c, graded, Final), 1.0);
}
