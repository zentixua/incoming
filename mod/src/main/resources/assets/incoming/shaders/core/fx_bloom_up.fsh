#version 150

// Свечение (client/fx/layer/FxBloom): уровень пирамиды вверх — шатёр 3×3 по пикселям мельчайшего уровня (Jimenez 2014);
// свой спуск уровня прибавляет смешивание. Пиксель (x, y) — точка ((x + ½)/2, (y + ½)/2) мельчайшего. Веса в сумме — 1.
uniform sampler2D Sampler0;
// xy — 1 / размер мельчайшего уровня
uniform vec4 BloomStep;

out vec4 fragColor;

vec3 at(vec2 uv, vec2 d, float x, float y) {
    return texture(Sampler0, uv + d * vec2(x, y)).rgb;
}

void main() {
    vec2 d = BloomStep.xy, uv = gl_FragCoord.xy * 0.5 * d;
    vec3 corners = at(uv, d, -1.0, -1.0) + at(uv, d, 1.0, -1.0) + at(uv, d, -1.0, 1.0) + at(uv, d, 1.0, 1.0);
    vec3 sides = at(uv, d, 0.0, -1.0) + at(uv, d, -1.0, 0.0) + at(uv, d, 1.0, 0.0) + at(uv, d, 0.0, 1.0);
    fragColor = vec4((corners + 2.0 * sides + 4.0 * at(uv, d, 0.0, 0.0)) / 16.0, 1.0);
}
