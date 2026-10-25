#version 150

#moj_import <incoming:pall.glsl>

// Крупные клубы из мелкого кадра — на кадр (client/fx/layer/CoarsePass): смешивание ONE, ONE_MINUS_SRC_ALPHA, как у
// самих квадратов слоя, — кадр отрезка и есть они, сложенные по порядку. Пиксель — из четырёх ближних пикселей мелкого
// кадра (свет посчитан в середине пикселя t мелкого кадра, расстояние до мира — в пикселе floor((t + ½)·factor) полного,
// как у луча пелены) с весом по сходству их расстояния до мира с расстоянием пикселя (pallAlike, pallSlope, pallAhead —
// как fx_pall_composite): у края дома клуб неба не ложится на стену, а клуб перед стеной — на небо. Ни один из четырёх
// не то же место (прут, забор, край блока вдали уже пикселя мелкого кадра) — из 4×4 вокруг: пиксель ближе их всех (прут
// перед дымом) — дыма на нём нет (за прутом дым отброшен, а перед ним мелкий кадр его не знает); иначе (просвет дальше
// их) — самый похожий по расстоянию.

// мелкий кадр: свет с умноженной альфой (rgb) и закрытая доля (a)
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (полное разрешение)
uniform sampler2D Sampler1;
// полных пикселей на пиксель мелкого кадра по x и y (CoarsePass: кадр с округлением вверх, не ровно 2^k)
uniform vec2 CoarseScale;

out vec4 fragColor;

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
            float a = pallAlike(sampleDepth(t, fullSize), pallAhead(depth, slope, vec2(rayPixel(t, fullSize) - full)));
            // билинейный вес без «?:» внутри выражения: Veil теряет скобки вокруг него (ShaderSourceTest)
            float w = mix(1.0 - f.x, f.x, float(i)) * mix(1.0 - f.y, f.y, float(j));
            same = max(same, a);
            w *= a + 1e-4;
            sum += texelFetch(Sampler0, t, 0) * w;
            total += w;
        }
    }
    vec4 v = sum / max(total, 1e-12);
    if (same <= 0.5) {
        ivec2 best = clamp(base, ivec2(0), lowSize - 1);
        float bestAlike = -1.0, nearest = 1e30;
        for (int j = -1; j <= 2; j++) {
            for (int i = -1; i <= 2; i++) {
                ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
                float d = sampleDepth(t, fullSize);
                nearest = min(nearest, d);
                float a = pallAlike(d, pallAhead(depth, slope, vec2(rayPixel(t, fullSize) - full)));
                if (a > bestAlike) {
                    bestAlike = a;
                    best = t;
                }
            }
        }
        v = texelFetch(Sampler0, best, 0);
        if (depth < nearest * 0.999 - 0.05) v = vec4(0.0);
    }
    // негодный пиксель (NaN, бесконечность в половинной точности) — ничего не кладёт
    if (!pallValid(vec4(v.rgb, 1.0 - v.a))) v = vec4(0.0);
    // 8 бит кадра: шум в полступени (как у квадратов слоя, fx.fsh), долей того, что кладёт кадр
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    v.rgb = max(v.rgb + (noise - 0.5) / 255.0 * v.a, vec3(0.0));
    fragColor = v;
}
