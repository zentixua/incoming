#version 150

// Сетка дыма (client/fx/smoke/SmokeGrid, client/fx/layer/SmokePass): клетка слоя текстуры — вклад одного гауссова клуба,
// сложением. Слой — ширина 2N (страница 0 слева, страница 1 справа), высота N (z мира), номер слоя — каскад·N + высота.
// Страница 0: ослабление сажи, пыли и капель (1/м) и Σ ослабление × ΔT (кК); страница 1: Σ ослабление пыли × её альбедо
// (r, g, b) и Σ ослабление × подъём (м/с). Клуб уже в своём каскаде со своими σ (смазан на полклетки, SmokeGrid.put).

// таблица клубов (SmokeGrid.BLOB_ROWS строк по клубу): 0 — центр от глаза, σh; 1 — σv, пиковые ослабления сажи, пыли,
// капель; 2 — альбедо пыли, x полуотрезка; 3 — ΔT (кК), подъём, y и z полуотрезка (клуб, размазанный по отрезку,
// SmokeGrid.line)
uniform sampler2D Sampler0;
// каскады: столбец c — угол от глаза и клетка
uniform mat4 SmokeCorner;
// x — каскад, y — слой по высоте в нём
uniform vec4 SmokeSlice;

in float blob;

out vec4 fragColor;

const float SMOKE_N = 64.0;
// Ослабление клуба в клетке — не больше, 1/м: шаг хода по веществу — от полклетки (≥ 1 м), толща шага ≥ 64 — непрозрачно
// так же, как при большем; предел держит сумму клубов в половинной точности (до 65504) и с весом подъёма
const float SMOKE_CAP = 128.0;
// подъём для шума клубов — не больше, м/с (то же основание)
const float SMOKE_RISE_CAP = 64.0;
// короче этого (в σ) отрезок — точка (SmokeGrid.LINE_POINT)
const float SMOKE_LINE_POINT = 0.05;

// erf по Абрамовицу и Стиган 7.1.26 (SmokeGrid.erf)
float smokeErf(float x) {
    float ax = abs(x);
    float t = 1.0 / (1.0 + 0.3275911 * ax);
    float y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax);
    return sign(x) * y;
}

// гаусс по отрезку против гаусса в точке у точки q (в осях, где σ — единица) при полуотрезке a там (SmokeGrid.line)
float smokeLine(vec3 q, vec3 a) {
    float len = length(a);
    if (len < SMOKE_LINE_POINT) {
        return exp(-0.5 * dot(q, q));
    }
    vec3 u = a / len;
    float along = dot(q, u);
    float across = dot(q, q) - along * along;
    return exp(-0.5 * max(across, 0.0)) * 1.2533141 / (2.0 * len) * (smokeErf((len - along) * 0.70710678) + smokeErf((len + along) * 0.70710678));
}

void main() {
    int i = int(floor(blob + 0.5));
    vec4 b0 = texelFetch(Sampler0, ivec2(i, 0), 0), b1 = texelFetch(Sampler0, ivec2(i, 1), 0);
    vec4 b2 = texelFetch(Sampler0, ivec2(i, 2), 0), b3 = texelFetch(Sampler0, ivec2(i, 3), 0);
    float px = floor(gl_FragCoord.x);
    float page = step(SMOKE_N, px);
    vec4 C = SmokeCorner[int(SmokeSlice.x + 0.5)];
    vec3 cell = vec3(px - page * SMOKE_N, SmokeSlice.y, floor(gl_FragCoord.y)) + 0.5;
    vec3 p = C.xyz + cell * C.w;
    vec3 s = vec3(b0.w, b1.x, b0.w);
    float e = smokeLine((p - b0.xyz) / s, vec3(b2.w, b3.z, b3.w) / s);
    vec3 ext = b1.yzw * e;
    float total = ext.x + ext.y + ext.z;
    float k = min(1.0, SMOKE_CAP / max(total, 1e-12));
    ext *= k;
    total *= k;
    fragColor = page < 0.5 ? vec4(ext, total * b3.x) : vec4(ext.y * b2.rgb, total * min(b3.y, SMOKE_RISE_CAP));
}
