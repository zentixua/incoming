// Свет шаров и факелов обычного оружия на мир и его тени (client/fx/layer/HeatPass) — общее у прохода видимости в
// половинном разрешении (fx_heat_shadow: лучи тени) и прохода света во весь кадр (fx_heat: свет с этой видимостью).
// Нужны pall.glsl (PALL_LAND) и mushroom.glsl (mushBallFace, mushBallFalloff) — до этого файла.

// шары света: столбец i — середина от глаза (xyz), радиус (w; 0 — нет); свет на белой стене лицом к шару, линейный
// (rgb), ослабление воздуха, 1/блок (w)
uniform mat4 HeatLights;
uniform mat4 HeatLightColor;
// заслоны у глаза (Solids, свой блок текстуры: в JSON не объявлен): нижний угол окна от глаза (xyz), есть ли окно (w);
// где этот угол в текстуре
uniform sampler3D HeatSolids;
uniform vec4 HeatSolidsAt;
uniform vec3 HeatSolidsWrap;
// факелы: столбец i — срез сопла от глаза (xyz), конец ядра (w; 0 — нет); ось струи от сопла (xyz), конец пламени (w);
// конец струи, полуширина у среза, её рост на метр, ослабление воздуха; свет ядра, пламени, хвоста на метр (rgb)
uniform mat4 HeatJet;
uniform mat4 HeatJetAxis;
uniform mat4 HeatJetSize;
uniform mat4 HeatJetCore;
uniform mat4 HeatJetFlame;
uniform mat4 HeatJetTail;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// окно Solids, блоки; клеток луча тени не больше (HeatPass.SHADOW_CELLS)
const vec3 SOLIDS_SIZE = vec3(192.0, 96.0, 192.0);
const int SHADOW_CELLS = 160;
// свет меньше этого (линейный на экране, ниже шага sRGB у чёрного) — тень не ищется: её не видно
const float SHADOW_SEEN = 3e-4;
// и меньше этой доли всей освещённости грани — тоже: порог контраста глаза ~1–2 % (Blackwell, JOSA 36, 1946; ночью он
// выше — порог с запасом) (HeatPass.WEBER)
const float WEBER = 0.02;

// Часть [t.x, t.y] отрезка от a (от точки грани) по единичному u, что над гранью с нормалью n (HeatPass.above); пусто —
// y <= x
vec2 heatAbove(vec3 a, vec3 u, vec2 t, vec3 n) {
    float na = dot(n, a), nu = dot(n, u);
    if (abs(nu) > 1e-6) {
        float ts = -na / nu;
        if (nu > 0.0) {
            t.x = max(t.x, ts);
        } else {
            t.y = min(t.y, ts);
        }
    } else if (na <= 0.0) {
        t.y = t.x;
    }
    return t;
}

// Прямой свет равномерного отрезка на грань, ∫ cos θ / d² по нему (HeatPass.segment): отрезок от a (от точки грани) по
// единичному u на [t.x, t.y] — уже над гранью (heatAbove), нормаль n; w2 — квадрат полуширины струи
float heatSegment(vec3 a, vec3 u, vec2 t, vec3 n, float w2) {
    if (t.y <= t.x) return 0.0;
    float na = dot(n, a), nu = dot(n, u);
    float b = dot(a, u);
    float h2 = max(dot(a, a) - b * b, 0.0) + w2;
    float c0 = na - b * nu, s0 = t.x + b, s1 = t.y + b;
    float q0 = 1.0 / (s0 * s0 + h2), q1 = 1.0 / (s1 * s1 + h2), r0 = sqrt(q0), r1 = sqrt(q1);
    // (s1·r1 − s0·r0)/h² и r1 − r0 без вычитания близких чисел
    float k = (s1 * r1 - s0 * r0) / h2;
    if (s0 * s1 > 0.0) k = (s1 - s0) * (s1 + s0) * q0 * q1 / (s1 * r1 + s0 * r0);
    float dr = (s0 - s1) * (s0 + s1) * q0 * q1 / (r0 + r1);
    return max(c0 * k - nu * dr, 0.0);
}

// Свободен ли путь от p до q (от глаза) от блоков-заслонов (HeatPass.blocked): 1 — да, 0 — нет. Клетки окна вдоль
// отрезка по порядку (Amanatides, Woo 1987), часть отрезка за окном — свободна; клетка самой точки мира — её грань, не
// заслон. Без целых делений и ветвлений на шаг: окно по модулю — повтором текстуры (выборка в середине клетки, без
// уменьшенных копий), ось шага — step по ближней грани (у видеокарты остаток от деления на 192 и ветвящийся выбор оси на
// каждом шаге стоили больше самой выборки)
float heatBlocked(vec3 p, vec3 q) {
    if (HeatSolidsAt.w < 0.5) return 1.0;
    vec3 a = p - HeatSolidsAt.xyz, d = q - p;
    float t0 = 0.0, t1 = 1.0;
    for (int k = 0; k < 3; k++) {
        if (abs(d[k]) < 1e-6) {
            if (a[k] < 0.0 || a[k] >= SOLIDS_SIZE[k]) return 1.0;
        } else {
            float ta = -a[k] / d[k], tb = (SOLIDS_SIZE[k] - a[k]) / d[k];
            t0 = max(t0, min(ta, tb));
            t1 = min(t1, max(ta, tb));
        }
    }
    if (t1 <= t0) return 1.0;
    vec3 cell = clamp(floor(a + d * t0), vec3(0.0), SOLIDS_SIZE - 1.0);
    vec3 dir = sign(d), inv = 1.0 / max(abs(d), vec3(1e-9));
    // путь до следующей грани клетки по осям; ось без хода — никогда
    vec3 next = mix(vec3(1e9), (cell + max(dir, 0.0) - a) * dir * inv, abs(dir));
    vec3 base = HeatSolidsWrap + 0.5, scale = 1.0 / SOLIDS_SIZE;
    bool own = t0 <= 0.0;
    for (int i = 0; i < SHADOW_CELLS; i++) {
        if (!own && textureLod(HeatSolids, (cell + base) * scale, 0.0).r > 0.5) return 0.0;
        own = false;
        float t = min(min(next.x, next.y), next.z);
        if (t >= t1) break;
        vec3 m = step(next, vec3(t));
        cell += m * dir;
        next += m * inv;
        if (any(lessThan(cell, vec3(0.0))) || any(greaterThanEqual(cell, SOLIDS_SIZE))) break;
    }
    return 1.0;
}

// Видимая доля диска шара с серединой b радиуса r из точки from (HeatPass.ballSeen): кромка заслона у земли — почти
// горизонтальная, доля диска над кромкой на высоте y — интеграл ширины хорды 2√(1 − y²); квадратура Гаусса–Чебышёва
// второго рода по вертикальному диаметру — узлы 0 и ±r·cos(π/4), веса ½ и ¼. Кромка снизу: середина видна — верхний
// узел тоже, смотрим нижний; середина закрыта — нижний тоже, смотрим верхний (два луча, доли 0, ¼, ¾, 1). Конец луча —
// у края шара со стороны точки (в шаре заслонов нет)
float heatBallSeen(vec3 from, vec3 b, float r) {
    vec3 v = b - from;
    float d = length(v);
    v /= max(d, 1e-3);
    float end = 1.0 - r / max(d, r);
    float mid = heatBlocked(from, from + (b - from) * end);
    // вертикаль диска: мировой верх без доли вдоль луча (луч почти отвесный — любая поперечная)
    vec3 up = vec3(0.0, 1.0, 0.0) - v * v.y;
    if (dot(up, up) < 1e-4) up = vec3(1.0, 0.0, 0.0) - v * v.x;
    up = normalize(up) * (0.70710678 * r);
    if (mid > 0.5) return 0.75 + 0.25 * heatBlocked(from, from + (b - up - from) * end);
    return 0.25 * heatBlocked(from, from + (b + up - from) * end);
}

// Начало луча тени: точка мира на две ширины пикселя от грани (не меньше 2 см), чтобы её клетка была воздухом перед гранью
vec3 heatLift(vec3 p, vec3 n, float pixel) {
    return p + n * max(0.02, 2.0 * pixel * length(p));
}

// Шар i на грани в точке p с нормалью n: весь свет (возврат) и его прямой пучок direct (у самого шара и в шаре — 0: там
// тени нет)
vec3 heatBall(int i, vec3 p, vec3 n, out vec3 direct) {
    direct = vec3(0.0);
    vec4 b = HeatLights[i];
    if (b.w <= 0.0) return vec3(0.0);
    vec3 l = b.xyz - p;
    float d2 = dot(l, l), d = sqrt(d2);
    vec3 v = l / max(d, 1e-3);
    float sd = HeatLightColor[i].w * d, nv = dot(n, v);
    vec3 c = HeatLightColor[i].rgb * mushBallFalloff(d2, b.w * b.w);
    if (d > b.w) direct = c * (exp(-sd) * max(nv, 0.0));
    return c * mushBallFace(nv, v.y, n.y, sd, PALL_LAND, 1.0, 0.0);
}

// Факел i на грани: прямой свет (возврат: интеграл по частям над гранью), рассеянный воздухом и отражённый землёй —
// around (как у шара в середине части), target — середина прямого света по частям над гранью (к ней луч тени)
vec3 heatJet(int i, vec3 p, vec3 n, out vec3 around, out vec3 target) {
    around = vec3(0.0);
    target = p;
    vec4 j = HeatJet[i];
    if (j.w <= 0.0) return vec3(0.0);
    vec4 ax = HeatJetAxis[i], sz = HeatJetSize[i];
    vec3 a = j.xyz - p, u = ax.xyz;
    vec3 direct = vec3(0.0), lit = vec3(0.0);
    float weight = 0.0;
    for (int k = 0; k < 3; k++) {
        // ядро и пламя — от среза (пламя обнимает ядро), хвост — за пламенем
        vec3 q = HeatJetCore[i].rgb;
        float t0 = 0.0, t1 = j.w;
        if (k == 1) {
            q = HeatJetFlame[i].rgb;
            t1 = ax.w;
        } else if (k == 2) {
            q = HeatJetTail[i].rgb;
            t0 = ax.w;
            t1 = sz.x;
        }
        if (dot(q, LUMA) <= 0.0 || t1 <= t0) continue;
        float mid = 0.5 * (t0 + t1), w = sz.y + sz.z * mid;
        vec3 l = a + u * mid;
        float d2 = max(dot(l, l), 0.25 * (t1 - t0) * (t1 - t0) + w * w), d = sqrt(d2);
        vec3 v = l / max(length(l), 1e-3);
        float sd = sz.w * d, nv = dot(n, v);
        float rest = mushBallFace(nv, v.y, n.y, sd, PALL_LAND, 1.0, 0.0) - exp(-sd) * max(nv, 0.0);
        around += q * ((t1 - t0) / d2 * rest);
        vec2 above = heatAbove(a, u, vec2(t0, t1), n);
        vec3 e = q * (exp(-sd) * heatSegment(a, u, above, n, w * w));
        direct += e;
        float y = dot(e, LUMA);
        lit += y * (a + u * (0.5 * (above.x + above.y)));
        weight += y;
    }
    if (weight > 0.0) target = p + lit / weight;
    return direct;
}

// Свет шаров и факелов на мире в точке p (от глаза) с нормалью n, линейный, на белой стене; видимость прямого света
// источников — ballSeen, jetSeen (0..1, из прохода видимости)
vec3 heatLight(vec3 p, vec3 n, vec4 ballSeen, vec4 jetSeen) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec3 direct;
        vec3 lit = heatBall(i, p, n, direct);
        sum += lit - direct * (1.0 - ballSeen[i]);
    }
    for (int i = 0; i < 4; i++) {
        vec3 around, target;
        vec3 direct = heatJet(i, p, n, around, target);
        sum += direct * jetSeen[i] + around;
    }
    return sum;
}

// Видимость прямого света источников из точки p с нормалью n (pixel — угол пикселя): лучи тени только у источника, чей
// прямой свет не меньше WEBER всей освещённости грани (prior — свет неба на ней, линейный, плюс свет всех источников без
// теней); остальным — 1 (тень меньше порога глаз не различит)
void heatShadows(vec3 p, vec3 n, float prior, float pixel, out vec4 ballSeen, out vec4 jetSeen) {
    ballSeen = vec4(1.0);
    jetSeen = vec4(1.0);
    vec4 ballLum = vec4(0.0), jetLum = vec4(0.0);
    float total = prior;
    for (int i = 0; i < 4; i++) {
        vec3 direct;
        total += dot(heatBall(i, p, n, direct), LUMA);
        ballLum[i] = dot(direct, LUMA);
        vec3 around, target;
        vec3 jet = heatJet(i, p, n, around, target);
        total += dot(jet + around, LUMA);
        jetLum[i] = dot(jet, LUMA);
    }
    float seen = max(SHADOW_SEEN, WEBER * total);
    vec3 from = heatLift(p, n, pixel);
    for (int i = 0; i < 4; i++) {
        if (ballLum[i] > seen) ballSeen[i] = heatBallSeen(from, HeatLights[i].xyz, HeatLights[i].w);
        if (jetLum[i] > seen) {
            vec3 around, target;
            heatJet(i, p, n, around, target);
            jetSeen[i] = heatBlocked(from, target);
        }
    }
}
