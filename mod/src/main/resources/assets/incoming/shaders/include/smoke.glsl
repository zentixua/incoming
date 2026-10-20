// Сетка дыма у камеры (client/fx/smoke/SmokeGrid, client/fx/layer/SmokePass): дым, пыль и огонь обычных взрывов (и
// других источников) — трёхмерная текстура ослабления в каскадах вокруг глаза. Общее у лучей пелены (fx_pall), вывода
// на кадр (тень дыма на мире, fx_pall_composite), квадратов слоя и проб источников (pall_front.glsl: что дым закрывает
// перед точкой). Числа вещества — копии SmokeGrid (SmokeGlslTest сверяет).
//
// Текстура: по s — x мира (две страницы по N клеток), по t — z мира, по r — высота (N слоёв на каскад, каскады подряд).
// Страница 0: ослабление сажи, пыли и капель (1/блок) и Σ ослабление × ΔT (кК); страница 1: Σ ослабление пыли × её
// альбедо ω' (rgb) и Σ ослабление × подъём (блоков/с). Каскад c (столбец c SmokeCascade): угол от глаза (xyz) и клетка
// (w); у каждого каскада свои клубы — плотность в точке — сумма каскадов, где она есть. SmokeCascade[3].x — 1, если
// сетка в этом кадре заполнена.

uniform sampler3D SmokeGrid;
uniform mat4 SmokeCascade;
// рамка всего дыма от глаза
uniform vec3 SmokeMin;
uniform vec3 SmokeMax;

const int SMOKE_N = 64;
const int SMOKE_CASCADES = 3;
// альбедо ω' и асимметрия g' после дельта-Эддингтона: сажа, пыль (альбедо — по клетке), капли (SmokeGrid)
const float SMOKE_SOOT_W = 0.2;
const float SMOKE_SOOT_G = 0.33333334;
const float SMOKE_DUST_G = 0.375;
const float SMOKE_DROP_W = 0.99963975;
const float SMOKE_DROP_G = 0.45945945;
// воздух вокруг, K (SmokeGrid.AIR_K)
const float SMOKE_AIR_K = 288.15;

bool smokeOn() {
    return SmokeCascade[3].x > 0.5;
}

// Страница page (0 или 1) сетки в точке p (от глаза): сумма каскадов, в которых точка есть; у края каскада — по крайней
// клетке (клубы каскада до края не доходят: SmokeGrid кладёт клуб в каскад, только если он там весь)
vec4 smokeFetch(vec3 p, float page) {
    vec4 sum = vec4(0.0);
    float n = float(SMOKE_N);
    for (int c = 0; c < SMOKE_CASCADES; c++) {
        vec4 C = SmokeCascade[c];
        vec3 l = (p - C.xyz) / C.w;
        if (any(lessThan(l, vec3(0.0))) || any(greaterThan(l, vec3(n)))) continue;
        l = clamp(l, vec3(0.5), vec3(n - 0.5));
        sum += textureLod(SmokeGrid, vec3((page * n + l.x) / (2.0 * n), l.z / n, (float(c) * n + l.y) / (float(SMOKE_CASCADES) * n)), 0.0);
    }
    return sum;
}

// Ослабление дыма в точке p (от глаза), 1/блок: сажа, пыль и капли вместе (без клубов шума — среднее поле)
float smokeSigma(vec3 p) {
    vec4 s = smokeFetch(p, 0.0);
    return s.x + s.y + s.z;
}

// Клетка самого мелкого каскада, где есть точка p, блоков (вне сетки — клетка последнего)
float smokeCell(vec3 p) {
    float n = float(SMOKE_N);
    for (int c = 0; c < SMOKE_CASCADES; c++) {
        vec4 C = SmokeCascade[c];
        vec3 l = (p - C.xyz) / C.w;
        if (all(greaterThanEqual(l, vec3(0.0))) && all(lessThanEqual(l, vec3(n)))) return C.w;
    }
    return SmokeCascade[SMOKE_CASCADES - 1].w;
}

// Толща дыма на отрезке от a по dir длиной len — steps выборок среднего поля в серединах равных частей
float smokeTau(vec3 a, vec3 dir, float len, int steps) {
    if (len <= 0.0) return 0.0;
    float ds = len / float(steps), tau = 0.0;
    for (int i = 0; i < steps; i++) tau += smokeSigma(a + dir * ((float(i) + 0.5) * ds));
    return tau * ds;
}

// Сколько пропускает дым между глазом и точкой dir·len (шесть выборок по его рамке на луче): квадраты слоя и пробы
// источников за дымом
float smokeThrough(vec3 dir, float len) {
    if (!smokeOn()) return 1.0;
    vec2 span = pallBox(dir, SmokeMin, SmokeMax);
    float t0 = max(span.x, 0.0), t1 = min(span.y, len);
    if (t1 <= t0) return 1.0;
    return exp(-smokeTau(dir * t0, dir, t1 - t0, 6));
}

// Доля света солнца, что дым пропускает к точке p (от глаза): толща к солнцу sun — выборками на 2, 8, 24, 64 и 160
// блоках (дым над точкой — от метров до сотен)
float smokeSun(vec3 p, vec3 sun) {
    if (!smokeOn()) return 1.0;
    // прямая к солнцу мимо рамки дыма — тени нет
    vec2 span = pallBox(sun, SmokeMin - p, SmokeMax - p);
    if (span.y <= max(span.x, 0.0)) return 1.0;
    float tau = smokeSigma(p + sun * 2.0) * 4.0 + smokeSigma(p + sun * 8.0) * 9.0 + smokeSigma(p + sun * 24.0) * 26.0
            + smokeSigma(p + sun * 64.0) * 60.0 + smokeSigma(p + sun * 160.0) * 130.0;
    return exp(-tau);
}
