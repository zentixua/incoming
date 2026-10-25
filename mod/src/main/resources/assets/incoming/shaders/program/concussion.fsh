#version 150

// Контузия на экране (client/fx/Concussion) — подача: Гаусс по оси BlurDir (σ = Radius, пикселей), во втором проходе
// (Final = 1) ещё потеря цвета к серому той же яркости (Grey) и тёмные края (Edge).

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
in vec2 sampleStep;

uniform vec2 OutSize;
uniform float Radius;
uniform float Grey;
uniform float Edge;
uniform float Final;

out vec4 fragColor;

// Гаусс до 3σ, пары соседних пикселей — одной выборкой между ними (линейная выборка): вес — сумма пары, место — по их
// весам. σ непрерывна: размытие гаснет без ступенек.
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
    // яркость по Rec. 709; серость — к ней, не к среднему каналов (зелень темнела бы)
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 grey = mix(c, vec3(luma), Grey);
    // края: от середины к углу кадра (с поправкой на его стороны), темнеют плавно с трети пути
    vec2 p = (texCoord - 0.5) * vec2(OutSize.x / OutSize.y, 1.0);
    float r = length(p) / length(vec2(0.5 * OutSize.x / OutSize.y, 0.5));
    vec3 graded = grey * (1.0 - Edge * smoothstep(0.35, 1.0, r));
    fragColor = vec4(mix(c, graded, Final), 1.0);
}
