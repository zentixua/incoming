#version 150

#moj_import <incoming:pall.glsl>

// Крупные клубы из мелкого кадра — на кадр (client/fx/layer/CoarsePass): смешивание ONE, ONE_MINUS_SRC_ALPHA, как у
// самих квадратов слоя, — кадр отрезка и есть они, сложенные по порядку. Пиксель — из четырёх ближних пикселей мелкого
// кадра (свет посчитан в середине пикселя t мелкого кадра, расстояние до мира — в пикселе floor((t + ½)·factor) полного,
// как у луча пелены) с весом по сходству их расстояния до мира с расстоянием пикселя (наклон и место на той же
// поверхности — pallSlopeWithin, pallAhead, как у fx_pall_composite; допуск свой — coarseTolerance — и у
// сходства, и у наклона): у края дома клуб неба не ложится на стену, а клуб перед стеной — на небо. Сосед — не то же
// место (сходство ½ и меньше) — в цвет не идёт вовсе (coarseWeight): малый, но ненулевой вес фона на краю при весе
// билинейки 15/16 дал бы ореол дыма фона на переднем предмете. Ни один из четырёх
// не то же место (прут, решётка, край блока вдали уже пикселя мелкого кадра) — мелкий кадр этот пиксель не разрешает:
// вывод его не трогает, а проход маски (CoarseMask = 1) отмечает его, и CoarsePass досчитывает отмеченные пиксели
// квадратами отрезка в полном кадре, с аппаратной глубиной (дым перед прутом — на пруте, за ним — нет).

// мелкий кадр: свет с умноженной альфой (rgb) и закрытая доля (a)
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (полное разрешение)
uniform sampler2D Sampler1;
// отметки мелкого кадра (fx.fsh, fragCover): больше нуля — в пиксель лёг квадрат светлее своей непрозрачности; восьмибитный
// кадр обрезал бы его свет по ходу смешивания, мелкий — нет, поэтому рядом с такой отметкой пиксель досчитывается
uniform sampler2D Sampler2;
// полных пикселей на пиксель мелкого кадра по x и y (CoarsePass: кадр с округлением вверх, не ровно 2^k)
uniform vec2 CoarseScale;
// 1 — проход маски: 1 в пикселях, которые мелкий кадр не разрешает, остальные отброшены; 0 — вывод (их отбрасывает)
uniform float CoarseMask;

out vec4 fragColor;

// Допуск «то же место» у мелкого кадра, в блоках расстояния до мира. Ошибка от него — дым в слое толщиной в допуск
// между поверхностью пикселя и поверхностью образца (дым за прутом на стене позади, ревью #455), и клуб в таком слое у
// поверхности ещё и тает (мягкость, softness у fx.fsh): ошибка не больше доли допуск/мягкость дыма слоя. Поэтому допуск —
// не доля расстояния (у пелены 5 %, pallAlike), а абсолютный: COARSE_NEAR — десятая блока, плюс то, чего глубина кадра
// сама не различает на отступе образца. 24-битная глубина с ближней плоскостью 0,05 (Minecraft) — шаг d²/(0,05·2²⁴)
// на расстоянии d, наклон по соседям в пиксель с этим шагом на отступе до 12 пикселей (1/8) ошибается до 11 шагов
// (CoarsePassTest, по глубине ровной земли): с запасом — 16. Поверхности ближе этого полный кадр с той же глубиной тоже
// почти не различает. 100 блоков — 0,29, 200 — 0,86, 500 — 4,9.
const float COARSE_NEAR = 0.1;
const float COARSE_STEP = 1.9073486e-5;

float coarseTolerance(float d) {
    return COARSE_NEAR + COARSE_STEP * d * d;
}

// Сходство расстояний d и e: 1/(1 + (разность/допуск)²) — у допуска ½, вдвое дальше — 1/5
float coarseAlike(float d, float e) {
    float rel = abs(d - e) / coarseTolerance(min(d, e));
    return 1.0 / (1.0 + rel * rel);
}

// Вес соседа мелкого кадра: билинейный вес b, сходство a (coarseAlike); не то же место — ноль, в цвет и в нормировку
// не идёт
float coarseWeight(float b, float a) {
    if (a <= 0.5) return 0.0;
    return b * a;
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
    vec2 f = at - vec2(base), slope = pallSlopeWithin(Sampler1, full, fullSize, depth, coarseTolerance(depth) / depth);
    vec4 sum = vec4(0.0);
    float total = 0.0, bright = 0.0;
    for (int j = 0; j <= 1; j++) {
        for (int i = 0; i <= 1; i++) {
            ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
            float a = coarseAlike(sampleDepth(t, fullSize), pallAhead(depth, slope, vec2(rayPixel(t, fullSize) - full)));
            // билинейный вес без «?:» внутри выражения: Veil теряет скобки вокруг него (ShaderSourceTest)
            float w = coarseWeight(mix(1.0 - f.x, f.x, float(i)) * mix(1.0 - f.y, f.y, float(j)), a);
            bright = max(bright, texelFetch(Sampler2, t, 0).r);
            sum += texelFetch(Sampler0, t, 0) * w;
            total += w;
        }
    }
    // не разрешён: ни одного соседа того же места с весом (у края билинейки вес соседа бывает и нулём) или у соседа —
    // пересвет
    bool unresolved = total <= 0.0 || bright > 0.0;
    if (CoarseMask > 0.5) {
        if (!unresolved) discard;
        fragColor = vec4(1.0);
        return;
    }
    if (unresolved) discard;
    vec4 v = sum / max(total, 1e-12);
    // негодный пиксель (NaN, бесконечность) — ничего не кладёт
    if (!pallValid(vec4(v.rgb, 1.0 - v.a))) v = vec4(0.0);
    // 8 бит кадра: шум в полступени (как у квадратов слоя, fx.fsh), долей того, что кладёт кадр
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    v.rgb = max(v.rgb + (noise - 0.5) / 255.0 * v.a, vec3(0.0));
    fragColor = v;
}
