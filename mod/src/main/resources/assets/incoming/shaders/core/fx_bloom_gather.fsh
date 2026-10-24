#version 150

// Свечение (client/fx/layer/FxBloom): сбор уровней ореола first … last одним проходом в размер первого — у каждого B-сплайн
// (кубический, 4 линейные выборки: Sigg и Hadwiger 2005), веса уровней равные. Пиксель (x, y) — точка ((x + ½)/2^i, (y + ½)/2^i)
// уровня first + i; середина пикселя j уровня — j + ½.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
// уровней в сборе (не больше 7)
uniform float BloomCount;

out vec4 fragColor;

vec3 bspline(sampler2D s, vec2 at) {
    vec2 size = vec2(textureSize(s, 0));
    vec2 t = at - 0.5, i = floor(t), f = t - i;
    vec2 f2 = f * f, f3 = f2 * f;
    vec2 w0 = (-f3 + 3.0 * f2 - 3.0 * f + 1.0) / 6.0, w1 = (3.0 * f3 - 6.0 * f2 + 4.0) / 6.0;
    vec2 w2 = (-3.0 * f3 + 3.0 * f2 + 3.0 * f + 1.0) / 6.0, w3 = f3 / 6.0;
    vec2 g0 = w0 + w1, g1 = w2 + w3;
    vec2 p0 = (i - 0.5 + w1 / g0) / size, p1 = (i + 1.5 + w3 / g1) / size;
    vec3 a = g0.x * texture(s, vec2(p0.x, p0.y)).rgb + g1.x * texture(s, vec2(p1.x, p0.y)).rgb;
    vec3 b = g0.x * texture(s, vec2(p0.x, p1.y)).rgb + g1.x * texture(s, vec2(p1.x, p1.y)).rgb;
    return g0.y * a + g1.y * b;
}

void main() {
    vec2 at = gl_FragCoord.xy;
    vec3 sum = bspline(Sampler0, at);
    if (BloomCount > 1.5) sum += bspline(Sampler1, at * 0.5);
    if (BloomCount > 2.5) sum += bspline(Sampler2, at * 0.25);
    if (BloomCount > 3.5) sum += bspline(Sampler3, at * 0.125);
    if (BloomCount > 4.5) sum += bspline(Sampler4, at * 0.0625);
    if (BloomCount > 5.5) sum += bspline(Sampler5, at * 0.03125);
    if (BloomCount > 6.5) sum += bspline(Sampler6, at * 0.015625);
    fragColor = vec4(sum / BloomCount, 1.0);
}
