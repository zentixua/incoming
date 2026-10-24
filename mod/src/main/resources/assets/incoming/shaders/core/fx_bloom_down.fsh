#version 150

// Свечение (client/fx/layer/FxBloom): уровень пирамиды вниз — 13 выборок (Jimenez 2014). Пиксель (x, y) уровня — точка
// (2x + 1, 2y + 1) прошлого; выборка с линейным фильтром у каждой — среднее четырёх пикселей. Веса в сумме — 1: энергия
// сохраняется.
uniform sampler2D Sampler0;
// xy — 1 / размер прошлого уровня
uniform vec4 BloomStep;

out vec4 fragColor;

vec3 at(vec2 uv, vec2 d, float x, float y) {
    return texture(Sampler0, uv + d * vec2(x, y)).rgb;
}

void main() {
    vec2 d = BloomStep.xy, uv = gl_FragCoord.xy * 2.0 * d;
    vec3 e = at(uv, d, 0.0, 0.0);
    vec3 corners = at(uv, d, -2.0, 2.0) + at(uv, d, 2.0, 2.0) + at(uv, d, -2.0, -2.0) + at(uv, d, 2.0, -2.0);
    vec3 sides = at(uv, d, 0.0, 2.0) + at(uv, d, -2.0, 0.0) + at(uv, d, 2.0, 0.0) + at(uv, d, 0.0, -2.0);
    vec3 inner = at(uv, d, -1.0, 1.0) + at(uv, d, 1.0, 1.0) + at(uv, d, -1.0, -1.0) + at(uv, d, 1.0, -1.0);
    fragColor = vec4(e * 0.125 + corners * 0.03125 + sides * 0.0625 + inner * 0.125, 1.0);
}
