#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:scene.glsl>

// Свет вспышки и огня обычного оружия на мир и дрожание воздуха над огнём (client/fx/layer/HeatPass): проход во весь кадр
// после пелены и до квадратов слоя по копии кадра (Sampler0) — пиксель пишется целиком.
// - Дрожание: показатель преломления воздуха n − 1 ∝ плотности ∝ 1/T, и горячий газ над огнём и шаром отклоняет луч
//   на ∫∇n·dl. Пульсации температуры в факеле рвут его на вихри размера ℓ; луч, прошедший их путь L, отклоняется на
//   угол ~δn·√(L/ℓ) (случайное блуждание через L/ℓ вихрей), δn — пульсации показателя (HeatPass считает их по
//   температуре). Изображение за горячим газом сдвигается на этот угол × (1 − s/D) (s — до газа, D — до мира), вихри
//   плывут вверх с газом. Это доли пикселя у огня и шара на обычном экране — дрожание видно на контрастных краях и в
//   бинокль, как в жизни.
// - Ударная волна ядерки: воздух за фронтом сжат (Ренкин–Гюгонио), n − 1 ∝ плотности, и луч, что пересекает сферу фронта,
//   гнётся к её середине на производную добавки пути (тонкая оболочка: путь — функция Доусона, nuclear/model/ShockOptics,
//   сверяет ShockOpticsGlslTest), среднюю по ширине пикселя там, где луч пересёк фронт. У касательной — пиксели, вдали от
//   неё — ничто: фронт виден тонким кольцом по краю на фоне неба и земли. Сдвиг фронта меньше FRONT_SHIFT_MIN — нет.
// - Свет: огненный шар взрыва, пламя лужи, горящие обломки и масло подстанции — ламбертовы шары яркостью чёрного тела
//   (L·R²/max(d², R²), mushBallFace — прямой пучок по косинусу к грани, рассеянный воздухом и отражённый землёй), как
//   свет шара ядерки на мир (fx_pall_composite): цвет блока — пиксель, делённый на свет открытого неба (relit). Факел
//   двигателя — три равномерных светящихся отрезка по оси струи (ядро, пламя, хвост до точки Дрейпера): прямой свет —
//   точный интеграл по отрезку над гранью (heatSegment), рассеянный и отражённый — как у шара в середине части.
// - Тени: прямой свет закрывают блоки — луч от точки мира к источнику по клеткам окна блоков вокруг глаза (heatBlocked,
//   HeatPass.blocked; окно — Solids): заслон известен точно, в кадре он или нет. За окном теней нет.

// копия кадра (линейная выборка) и расстояние до мира за пикселем
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform vec3 PallOpenSky;
uniform vec4 PallFaceShade;
// дымка воздуха и туман кадра (как у fx_pall_composite): ослабление, начало и конец тумана, форма
uniform vec4 Haze;
// шары света: столбец i — середина от глаза (xyz), радиус (w; 0 — нет); свет на белой стене лицом к шару, линейный
// (rgb), ослабление воздуха, 1/блок (w)
uniform mat4 HeatLights;
uniform mat4 HeatLightColor;
// горячий газ: столбец i — низ столба от глаза (xyz), радиус (w; 0 — нет); высота, размер вихря ℓ, путь газа в размерах
// вихря (по модулю периода шума), пульсация показателя преломления δn (по столбцам)
uniform mat4 HeatHot;
uniform mat4 HeatShape;
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
// фронты ударной волны: столбцы 0 и 1 — середина от глаза (xyz), радиус (w; 0 — нет); 2 и 3 — скачок показателя
// преломления на четырёх высотах ровно от синуса угла у земли до верха
uniform mat4 HeatFront;
// e-кратная толща сжатия за фронтом и синус угла над серединой у земли: фронта 0 (x, y) и 1 (z, w)
uniform vec4 HeatFrontShape;
// есть ли фронт (x > 0); угол пикселя, рад; есть ли свет (z > 0) и газ (w > 0)
uniform vec4 HeatFrame;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CROSS = 0.15;
// окно Solids, блоки (грубая сетка кубов 4³ — слоем за ним по z, с SOLIDS_SIZE.z); кубов луча тени не больше
// (HeatPass.SHADOW_CUBES)
const vec3 SOLIDS_SIZE = vec3(192.0, 96.0, 192.0);
const int SHADOW_CUBES = 64;
// свет меньше этого (линейный на экране, ниже шага sRGB у чёрного) — тень не ищется: её не видно
const float SHADOW_SEEN = 3e-4;
// сдвиг фронта меньше этой доли пикселя не виден: его нет (HeatPass.SHIFT_MIN)
const float FRONT_SHIFT_MIN = 0.05;

out vec4 fragColor;

float heatHash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z) * 2.0 - 1.0;
}

// Шум значений в [-1, 1] с гладкой выборкой между узлами решётки (СКО ~0,45)
float heatNoise(vec3 p) {
    vec3 i = floor(p), f = p - i;
    vec3 u = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(heatHash(i), heatHash(i + vec3(1, 0, 0)), u.x), mix(heatHash(i + vec3(0, 1, 0)), heatHash(i + vec3(1, 1, 0)), u.x), u.y);
    float b = mix(mix(heatHash(i + vec3(0, 0, 1)), heatHash(i + vec3(1, 0, 1)), u.x), mix(heatHash(i + vec3(0, 1, 1)), heatHash(i + vec3(1, 1, 1)), u.x), u.y);
    return mix(a, b, u.z);
}

// Сдвиг изображения за горячим газом, пиксели: отрезок луча dir до мира (dist) внутри столба i (вертикальный цилиндр от
// низа на высоту), угол — δn·√(L/ℓ), направление — шум вихрей в середине отрезка, вихри идут вверх с газом и
// перемешиваются за ℓ/u' (u' — 0,3 скорости подъёма, оценка)
vec2 heatShift(vec3 dir, float dist) {
    vec2 shift = vec2(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 h = HeatHot[i];
        if (h.w <= 0.0) continue;
        vec4 s = HeatShape[i];
        vec2 d2 = dir.xz;
        // |d2·t − h.xz|² = r²: a·t² − 2b·t + c = 0
        float a = dot(d2, d2), b = dot(h.xz, d2), c = dot(h.xz, h.xz) - h.w * h.w;
        float disc = b * b - a * c;
        if (a < 1e-8 || disc <= 0.0) continue;
        float r = sqrt(disc);
        float t0 = max((b - r) / a, 0.0), t1 = min((b + r) / a, dist);
        // по высоте — между низом и верхом столба
        if (abs(dir.y) > 1e-5) {
            float ya = (h.y - 0.0) / dir.y, yb = (h.y + s.x) / dir.y;
            t0 = max(t0, min(ya, yb));
            t1 = min(t1, max(ya, yb));
        } else if (h.y > 0.0 || h.y + s.x < 0.0) {
            continue;
        }
        float len = t1 - t0;
        if (len <= 0.0) continue;
        float mid = 0.5 * (t0 + t1);
        float angle = s.w * sqrt(len / s.y) * (1.0 - mid / max(dist, mid + 1e-3));
        vec3 q = (dir * mid - h.xyz) / s.y;
        // s.z — путь газа в размерах вихря (HeatPass: по модулю периода); вихри перемешиваются за ℓ/u', u' — 0,3 подъёма
        q.y -= s.z;
        float turn = 0.3 * s.z;
        // СКО шума ~0,45 на ось: на вектор — СКО угла
        float k = angle / (0.45 * 1.4142136) / HeatFrame.y;
        shift += k * vec2(heatNoise(q + vec3(0.0, 0.0, turn)), heatNoise(q + vec3(17.3, 5.1, turn + 31.7)));
    }
    return shift;
}

// Функция Доусона F(x) = e^(−x²)·∫₀ˣ e^(t²) dt (ShockOptics.dawson)
float frontDawson(float x) {
    if (x > 10.0) return 0.5 / x * (1.0 + 0.5 / (x * x) + 0.75 / (x * x * x * x));
    float y = x * x;
    float p = 1.0 + y * (0.1049934947 + y * (0.0424060604 + y * (0.0072644182 + y * (0.0005064034 + y * 0.0001789971))));
    float q = 1.0 + y * (0.7715471019 + y * (0.2909738639 + y * (0.0694555761 + y * (0.0140005442 + y * (0.0008327945 + y * 0.0003579942)))));
    return x * p / q;
}

// Добавка оптического пути одного пересечения оболочки (ShockOptics.path): прицельное расстояние b, радиус r, толща l, скачок dn
float frontPath(float b, float r, float l, float dn) {
    float rl = 2.0 * r * l;
    float a2 = max((r - b) * (r + b), 0.0) / rl;
    return dn * sqrt(rl) * frontDawson(sqrt(a2));
}

// Отклонение одного пересечения к середине, среднее по пикселю шириной w, рад (ShockOptics.bend)
float frontBend(float b, float r, float l, float dn, float w) {
    float lo = max(b - 0.5 * w, 0.0);
    float hi = b + 0.5 * w;
    float bend = (frontPath(lo, r, l, dn) - frontPath(hi, r, l, dn)) / max(hi - lo, 1e-6);
    float most = sqrt(2.0 * dn);
    return clamp(bend, -most, most);
}

// Скачок фронта на месте q (от середины, |q| = r): по высоте — между четырьмя узлами от синуса low у земли до верха
float frontJump(vec4 dn, float low, float sine) {
    float k = clamp((sine - low) / max(1.0 - low, 1e-3), 0.0, 1.0) * 3.0;
    if (k < 1.0) return mix(dn.x, dn.y, k);
    if (k < 2.0) return mix(dn.y, dn.z, k - 1.0);
    return mix(dn.z, dn.w, k - 2.0);
}

// Отклонение луча dir до мира (dist) фронтами, рад, вектором поперёк луча: каждое пересечение сферы перед миром — к её
// середине, × (1 − s/D) — сдвиг того, что за ним, как его видит глаз
vec3 frontShift(vec3 dir, float dist) {
    vec3 bend = vec3(0.0);
    for (int i = 0; i < 2; i++) {
        vec4 f = HeatFront[i];
        if (f.w <= 0.0) continue;
        float along = dot(f.xyz, dir);
        vec3 across = f.xyz - dir * along;
        float b = length(across);
        // пиксель у касательной снаружи ещё задевает фронт половиной своей ширины
        if (b < 1e-3 || b >= f.w + 0.5 * HeatFrame.y * max(along, 0.0)) continue;
        float chord = sqrt(max((f.w - b) * (f.w + b), 0.0));
        float l = i == 0 ? HeatFrontShape.x : HeatFrontShape.z;
        float low = i == 0 ? HeatFrontShape.y : HeatFrontShape.w;
        vec4 dn = HeatFront[2 + i];
        float angle = 0.0;
        for (int k = 0; k < 2; k++) {
            float s = k == 0 ? along - chord : along + chord;
            if (s <= 0.0 || s >= dist) continue;
            float jump = frontJump(dn, low, (dir.y * s - f.y) / f.w);
            angle += frontBend(b, f.w, l, jump, HeatFrame.y * s) * (1.0 - s / dist);
        }
        bend += across / b * angle;
    }
    return bend;
}

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

// Блоки куба cube вдоль отрезка a + d·t на [t, end] (HeatPass.cubeBlocked): есть ли заслон, кроме своей клетки own
bool heatCubeBlocked(vec3 a, vec3 d, float t, float end, ivec3 cube, vec3 inv, ivec3 dir, bool skipOwn, ivec3 own) {
    ivec3 size = ivec3(SOLIDS_SIZE), wrap = ivec3(HeatSolidsWrap);
    ivec3 cell = clamp(ivec3(floor(a + d * min(t + 1e-6, end))), cube * 4, cube * 4 + 3);
    vec3 next = vec3(1e9);
    for (int k = 0; k < 3; k++) {
        if (d[k] > 0.0) {
            next[k] = (float(cell[k] + 1) - a[k]) * inv[k];
        } else if (d[k] < 0.0) {
            next[k] = (a[k] - float(cell[k])) * inv[k];
        }
    }
    for (int j = 0; j < 12; j++) {
        bool mine = skipOwn && all(equal(cell, own));
        if (!mine && texelFetch(HeatSolids, (cell + wrap) % size, 0).r > 0.5) return true;
        float tn = next.z;
        int k = 2;
        if (next.x <= next.y && next.x <= next.z) {
            tn = next.x;
            k = 0;
        } else if (next.y <= next.z) {
            tn = next.y;
            k = 1;
        }
        if (tn >= end) break;
        cell[k] += dir[k];
        next[k] += inv[k];
    }
    return false;
}

// Свободен ли путь от p до q (от глаза) от блоков-заслонов (HeatPass.blocked): 1 — да, 0 — нет. Кубы 4³ вдоль отрезка по
// порядку, в кубе с заслоном — его блоки по порядку (Amanatides, Woo 1987, на двух уровнях: ответ — как у прохода по
// блокам, открытый воздух — шагом в куб); часть отрезка за окном — свободна; клетка самой точки мира — её грань, не заслон
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
    ivec3 cubes = ivec3(SOLIDS_SIZE) / 4, wrapc = ivec3(HeatSolidsWrap) / 4;
    ivec3 cube = clamp(ivec3(floor((a + d * t0) * 0.25)), ivec3(0), cubes - 1);
    ivec3 dir = ivec3(sign(d)), own = ivec3(floor(a));
    vec3 inv = 1.0 / max(abs(d), vec3(1e-9)), invc = 4.0 * inv, nextc = vec3(1e9);
    for (int k = 0; k < 3; k++) {
        if (d[k] > 0.0) {
            nextc[k] = (float((cube[k] + 1) * 4) - a[k]) * inv[k];
        } else if (d[k] < 0.0) {
            nextc[k] = (a[k] - float(cube[k] * 4)) * inv[k];
        }
    }
    bool skipOwn = t0 <= 0.0;
    float t = t0;
    for (int i = 0; i < SHADOW_CUBES; i++) {
        float leave = min(min(nextc.x, nextc.y), nextc.z);
        ivec3 cw = (cube + wrapc) % cubes;
        bool occupied = texelFetch(HeatSolids, ivec3(cw.x, cw.y, int(SOLIDS_SIZE.z) + cw.z), 0).r > 0.5;
        if (occupied && heatCubeBlocked(a, d, t, min(leave, t1), cube, inv, dir, skipOwn, own)) return 0.0;
        if (leave >= t1) break;
        int k = 2;
        if (nextc.x <= nextc.y && nextc.x <= nextc.z) {
            k = 0;
        } else if (nextc.y <= nextc.z) {
            k = 1;
        }
        cube[k] += dir[k];
        nextc[k] += invc[k];
        t = leave;
        if (cube[k] < 0 || cube[k] >= cubes[k]) break;
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
vec3 heatLift(vec3 p, vec3 n) {
    return p + n * max(0.02, 2.0 * HeatFrame.y * length(p));
}

// Свет шаров и факелов на мире в точке p (от глаза) с нормалью n, линейный, на белой стене; прямой — с тенью
vec3 heatLight(vec3 p, vec3 n) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 b = HeatLights[i];
        if (b.w <= 0.0) continue;
        vec3 l = b.xyz - p;
        float d2 = dot(l, l), d = sqrt(d2);
        vec3 v = l / max(d, 1e-3);
        float sd = HeatLightColor[i].w * d, nv = dot(n, v);
        float face = mushBallFace(nv, v.y, n.y, sd, PALL_LAND, 1.0, 0.0);
        vec3 c = HeatLightColor[i].rgb * mushBallFalloff(d2, b.w * b.w);
        // прямой пучок закрыт — без него (в шаре и у самого шара тени нет)
        float direct = exp(-sd) * max(nv, 0.0);
        if (d > b.w && direct > 0.0 && dot(c, LUMA) * direct > SHADOW_SEEN) {
            face -= direct * (1.0 - heatBallSeen(heatLift(p, n), b.xyz, b.w));
        }
        sum += c * face;
    }
    for (int i = 0; i < 4; i++) {
        vec4 j = HeatJet[i];
        if (j.w <= 0.0) continue;
        vec4 ax = HeatJetAxis[i], sz = HeatJetSize[i];
        vec3 a = j.xyz - p, u = ax.xyz;
        vec3 direct = vec3(0.0), around = vec3(0.0), lit = vec3(0.0);
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
            // рассеянный воздухом и отражённый землёй — как у шара в середине части; прямой — интегралом по отрезку
            float rest = mushBallFace(nv, v.y, n.y, sd, PALL_LAND, 1.0, 0.0) - exp(-sd) * max(nv, 0.0);
            around += q * ((t1 - t0) / d2 * rest);
            vec2 above = heatAbove(a, u, vec2(t0, t1), n);
            vec3 e = q * (exp(-sd) * heatSegment(a, u, above, n, w * w));
            direct += e;
            // середина части над гранью — к ней луч тени
            float y = dot(e, LUMA);
            lit += y * (a + u * (0.5 * (above.x + above.y)));
            weight += y;
        }
        // тень — к середине прямого света струи (по его частям)
        if (dot(direct, LUMA) > SHADOW_SEEN && weight > 0.0) direct *= heatBlocked(heatLift(p, n), p + lit / weight);
        sum += direct + around;
    }
    return sum;
}

void main() {
    ivec2 size = textureSize(Sampler1, 0);
    vec2 here = gl_FragCoord.xy;
    vec2 ndc0 = here / vec2(size) * 2.0 - 1.0;
    vec4 far0 = InvViewProj * vec4(ndc0, 1.0, 1.0);
    vec3 dir0 = normalize(far0.xyz / far0.w);
    float depth0 = pallScene(texelFetch(Sampler1, ivec2(here), 0).r);
    float dist0 = depth0 >= 1e8 ? 1e8 : depth0 / max(dot(dir0, PallForward), 1e-3);
    vec2 shift = HeatFrame.w > 0.0 ? heatShift(dir0, dist0) : vec2(0.0);
    vec2 front = vec2(0.0);
    if (HeatFrame.x > 0.0) {
        vec3 bend = frontShift(dir0, dist0);
        if (dot(bend, bend) > 0.0) {
            // поворот луча в пиксели: шаг луча на пиксель экрана по x и y (у пикселя — линейно)
            vec2 ndc1 = (here + vec2(1.0, 0.0)) / vec2(size) * 2.0 - 1.0, ndc2 = (here + vec2(0.0, 1.0)) / vec2(size) * 2.0 - 1.0;
            vec4 f1 = InvViewProj * vec4(ndc1, 1.0, 1.0), f2 = InvViewProj * vec4(ndc2, 1.0, 1.0);
            vec3 dx = normalize(f1.xyz / f1.w) - dir0, dy = normalize(f2.xyz / f2.w) - dir0;
            float xx = dot(dx, dx), xy = dot(dx, dy), yy = dot(dy, dy), det = xx * yy - xy * xy;
            float bx = dot(bend, dx), by = dot(bend, dy);
            if (det > 0.0) front = vec2(yy * bx - xy * by, xx * by - xy * bx) / det;
        }
    }
    // фронт идёт с каждым кадром ядерки по всему кадру: его сдвиг меньше порога — как не было
    if (dot(front, front) >= FRONT_SHIFT_MIN * FRONT_SHIFT_MIN) shift += front;
    vec2 at = here + shift;
    vec3 world = dot(shift, shift) > 0.0 ? texture(Sampler0, at / vec2(size)).rgb : texelFetch(Sampler0, ivec2(here), 0).rgb;
    ivec2 px = clamp(ivec2(floor(at)), ivec2(0), size - 1);
    float depth = pallScene(texelFetch(Sampler1, px, 0).r);
    if (HeatFrame.z > 0.0 && depth < 1e8) {
        vec3 p = sceneAt(Sampler1, InvViewProj, PallForward, px, size);
        vec3 n = sceneNormal(Sampler1, InvViewProj, PallForward, px, size, p);
        // свет — на свете самого мира: дымка воздуха и туман Minecraft перед ним — нет
        float fd = Haze.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p);
        float seen = exp(-Haze.x * length(p)) * (1.0 - smoothstep(Haze.y, Haze.z, fd));
        vec3 added = heatLight(p, n) * seen;
        if (dot(added, LUMA) > 1e-6) {
            vec3 open = PallOpenSky * faceShade(PallFaceShade, n);
            vec3 lit = vec3(relit(world.r, 1.0, open.r, added.r), relit(world.g, 1.0, open.g, added.g), relit(world.b, 1.0, open.b, added.b));
            lit += vec3(CROSS * max(dot(lit, LUMA) - 1.0, 0.0));
            vec3 shown = vec3(pallDisplay(lit.r), pallDisplay(lit.g), pallDisplay(lit.b));
            world = shown / max(max(max(shown.r, shown.g), shown.b), 1.0);
        }
    }
    fragColor = vec4(world, 1.0);
}
