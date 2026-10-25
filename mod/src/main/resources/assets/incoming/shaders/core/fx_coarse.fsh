#version 150

#moj_import <incoming:pall.glsl>

// Крупные клубы из мелкого кадра — на кадр (client/fx/layer/CoarsePass): смешивание ONE, ONE_MINUS_SRC_ALPHA, как у
// самих квадратов слоя, — кадр отрезка и есть они, сложенные по порядку. Пиксель — из четырёх ближних пикселей мелкого
// кадра (свет посчитан в середине пикселя t мелкого кадра, расстояние до мира — в пикселе floor((t + ½)·factor) полного,
// как у луча пелены) с весом по сходству их расстояния до мира с расстоянием пикселя (pallSlope, pallAhead — как
// fx_pall_composite; сходство — строже, чем у пелены: coarseAlike): у края дома клуб неба не ложится на стену, а клуб перед стеной — на небо. Ни один из четырёх
// не то же место (прут, решётка, край блока вдали уже пикселя мелкого кадра) — мелкий кадр этот пиксель не разрешает:
// вывод его не трогает, а проход маски (CoarseMask = 1) отмечает его, и CoarsePass досчитывает отмеченные пиксели
// квадратами отрезка в полном кадре, с аппаратной глубиной (дым перед прутом — на пруте, за ним — нет).

// мелкий кадр: свет с умноженной альфой (rgb) и закрытая доля (a)
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (полное разрешение)
uniform sampler2D Sampler1;
// полных пикселей на пиксель мелкого кадра по x и y (CoarsePass: кадр с округлением вверх, не ровно 2^k)
uniform vec2 CoarseScale;
// 1 — проход маски: 1 в пикселях, которые мелкий кадр не разрешает, остальные отброшены; 0 — вывод (их отбрасывает)
uniform float CoarseMask;

out vec4 fragColor;

// Допуск «то же место» у мелкого кадра (CoarsePass.SAME): у пелены 5 % (pallAlike), но квадрат дыма между прутом в 100 м и
// стеной в 104 м за ним в полном кадре закрыт прутом, а образец у стены его держит. Сходство 1/(1 + (rel/SAME)²): у
// допуска — ½, вчетверо дальше — 1/17
const float COARSE_SAME = 0.01;

float coarseAlike(float d, float e) {
    float rel = abs(d - e) / max(min(d, e), 0.1) / COARSE_SAME;
    return 1.0 / (1.0 + rel * rel);
}

ivec2 rayPixel(ivec2 t, ivec2 fullSize) {
    return clamp(ivec2((vec2(t) + 0.5) * CoarseScale), ivec2(0), fullSize - 1);
}

float sampleDepth(ivec2 t, ivec2 fullSize) {
    return pallScene(texelFetch(Sampler1, rayPixel(t, fullSize), 0).r);
}

void main() {
    ivec2 fullSize = textureSize(Sampler1, 0), lowSize = textureSize(Sampler0, 0);
    ivec2 full = ivec2(gl_FragCoord.xy);
    float depth = pallScene(texelFetch(Sampler1, clamp(full, ivec2(0), fullSize - 1), 0).r);
    // середина пикселя t мелкого кадра — (t + ½)·factor полного: там квадраты и посчитаны
    vec2 at = (vec2(full) + 0.5) / CoarseScale - 0.5;
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base), slope = pallSlope(Sampler1, full, fullSize, depth);
    vec4 sum = vec4(0.0);
    float total = 0.0, same = 0.0;
    for (int j = 0; j <= 1; j++) {
        for (int i = 0; i <= 1; i++) {
            ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
            float a = coarseAlike(sampleDepth(t, fullSize), pallAhead(depth, slope, vec2(rayPixel(t, fullSize) - full)));
            // билинейный вес без «?:» внутри выражения: Veil теряет скобки вокруг него (ShaderSourceTest)
            float w = mix(1.0 - f.x, f.x, float(i)) * mix(1.0 - f.y, f.y, float(j));
            same = max(same, a);
            w *= a + 1e-4;
            sum += texelFetch(Sampler0, t, 0) * w;
            total += w;
        }
    }
    bool unresolved = same <= 0.5;
    if (CoarseMask > 0.5) {
        if (!unresolved) discard;
        fragColor = vec4(1.0);
        return;
    }
    if (unresolved) discard;
    vec4 v = sum / max(total, 1e-12);
    // негодный пиксель (NaN, бесконечность в половинной точности) — ничего не кладёт
    if (!pallValid(vec4(v.rgb, 1.0 - v.a))) v = vec4(0.0);
    // 8 бит кадра: шум в полступени (как у квадратов слоя, fx.fsh), долей того, что кладёт кадр
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    v.rgb = max(v.rgb + (noise - 0.5) / 255.0 * v.a, vec3(0.0));
    fragColor = v;
}
