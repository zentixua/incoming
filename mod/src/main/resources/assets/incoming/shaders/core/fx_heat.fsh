#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:scene.glsl>
#moj_import <incoming:heat.glsl>

// Свет вспышки и огня обычного оружия на мир и дрожание воздуха над огнём (client/fx/layer/HeatPass): проход во весь кадр
// после пелены и до квадратов слоя по копии кадра (Sampler0) — пиксель пишется целиком.
// - Дрожание: показатель преломления воздуха n − 1 ∝ плотности ∝ 1/T, и горячий газ над огнём, шаром и в струе сопла
//   отклоняет луч на ∫∇n·dl. Пульсации температуры рвут газ на вихри от внешнего масштаба ℓ вниз (Колмогоров); угол
//   прихода за путём L — σ = δn·√(5,82·L/ℓ)·(ℓ/D)^(1/6) по одной оси (hazeAngle; D — пятно, что глаз различает у газа),
//   вихри быстрее, чем глаз копит свет, сливаются (hazeSeen). Изображение за газом сдвигается на этот угол × (1 − s/D)
//   (s — до газа, D — до мира). Сдвиг — в долях того, что различает глаз (острота зрения, в бинокль — пиксель): подача,
//   HeatPass. Газ — столб вдоль своей оси (у огня и шара — вверх, у сопла — по струе), радиус растёт по оси, избыток
//   температуры и скорость за своими местами падают как 1/x; узор вихрей — октавы шума от ℓ втрое мельче, пока крупнее
//   пятна, и его несёт газ (два слоя со сдвигом в полцикла, смена слоя — при нулевом весе).
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
// видимость прямого света источников из прохода в половинном разрешении (fx_heat_shadow): коды шаров (r) и факелов (g),
// расстояние до мира у образца вдоль взгляда (b)
uniform sampler2D Sampler2;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform vec3 PallOpenSky;
uniform vec4 PallFaceShade;
// дымка воздуха и туман кадра (как у fx_pall_composite): ослабление, начало и конец тумана, форма
uniform vec4 Haze;
// горячий газ: столбец i — низ столба от глаза (xyz), радиус там (w; 0 — нет); длина по оси, вихрь ℓ у низа, скорость
// газа у низа, (n₀ − 1)·доля пульсаций; ось от низа (xyz) и рост радиуса на метр оси; избыток температуры у низа, K, и
// места по оси, за которыми избыток и скорость падают как 1/x
uniform mat4 HeatHot;
uniform mat4 HeatShape;
uniform mat4 HeatAxis;
uniform mat4 HeatGas;
// перенос узора вихрей: доля цикла и номер цикла у двух слоёв (x, y и z, w)
uniform vec4 HeatClock;
// фронты ударной волны: столбцы 0 и 1 — середина от глаза (xyz), радиус (w; 0 — нет); 2 и 3 — скачок показателя
// преломления на четырёх высотах ровно от синуса угла у земли до верха
uniform mat4 HeatFront;
// e-кратная толща сжатия за фронтом и синус угла над серединой у земли: фронта 0 (x, y) и 1 (z, w)
uniform vec4 HeatFrontShape;
// есть ли фронт (x > 0); угол пикселя, рад; есть ли свет (z > 0) и газ (w > 0)
uniform vec4 HeatFrame;
// есть ли видимость из прохода тени (x > 0; нет — без теней)
uniform vec4 HeatShadow;

const float CROSS = 0.15;
// закон дрожания — копии чисел HeatPass (сверяет HazeGlslTest)
const float HAZE_T_AIR = 288.15;
const float HAZE_COOL = 20.0;
const float HAZE_KOLMOGOROV = 5.82;
const float HAZE_ACUITY = 2.9e-4;
const float HAZE_PUPIL = 4e-3;
const float HAZE_EYE_S = 0.05;
const float HAZE_SWIRL = 0.3;
const float HAZE_CYCLE = 2.0;
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

// Пульсация показателя преломления (СКО) в газе с избытком температуры excess, K; amp — (n₀ − 1)·доля пульсаций (HeatPass.dn)
float hazeDn(float excess, float amp) {
    float t0 = HAZE_T_AIR / (HAZE_T_AIR + excess);
    return amp * t0 * (1.0 - t0);
}

// Угол прихода луча (СКО по оси), рад: путь len в газе с пульсацией dn и вихрями до eddy, пятно aperture (HeatPass.hazeAngle)
float hazeAngle(float dn, float len, float eddy, float aperture) {
    if (aperture < eddy) return dn * sqrt(HAZE_KOLMOGOROV * len / eddy) * pow(eddy / aperture, 1.0 / 6.0);
    return dn * sqrt(HAZE_KOLMOGOROV * len / eddy) * eddy / aperture;
}

// Доля дрожания, которую видит глаз: вихри, что живут меньше времени, за которое глаз копит свет, сливаются (HeatPass.hazeSeen)
float hazeSeen(float eddy, float speed) {
    float tau = eddy / max(HAZE_SWIRL * speed, 1e-6);
    if (tau < HAZE_EYE_S) return sqrt(tau / HAZE_EYE_S);
    return 1.0;
}

// Узор вихрей одного слоя в месте p (от низа столба, блоки): октавы от eddy втрое мельче, пока крупнее пятна aperture, вес
// октавы — по Колмогорову (наклон фронта от вихрей размера s ∝ s^(−1/6)); газ прошёл по оси travel блоков, вихрь размера s
// за это время сменился на долю 0,3·travel/ℓ·(ℓ/s)^(2/3); у каждого цикла — свой сдвиг узора. СКО на ось — как у heatNoise.
vec2 hazeNoise(vec3 p, vec3 axis, float eddy, float travel, float cycle, float aperture) {
    vec2 sum = vec2(0.0);
    float norm = 0.0;
    vec3 seed = vec3(cycle * 17.31, cycle * 5.17, cycle * 11.73);
    for (int k = 0; k < 3; k++) {
        float s = eddy * pow(3.0, -float(k));
        if (k > 0 && s < aperture) break;
        float w = pow(3.0, float(k) / 6.0);
        vec3 q = p / s - axis * (travel / s) + seed;
        float turn = HAZE_SWIRL * travel / eddy * pow(eddy / s, 2.0 / 3.0);
        sum += w * vec2(heatNoise(q + vec3(0.0, 0.0, turn)), heatNoise(q + vec3(17.3, 5.1, turn + 31.7)));
        norm += w * w;
    }
    return sum / sqrt(norm);
}

// Сдвиг изображения за горячим газом, пиксели: отрезок луча dir до мира (dist) внутри столба i (вдоль оси от низа на длину,
// радиус у места, где луч ближе всего к оси), сила — hazeAngle в середине отрезка, направление — узор вихрей там
vec2 heatShift(vec3 dir, float dist) {
    vec2 shift = vec2(0.0);
    float res = min(HAZE_ACUITY, HeatFrame.y);
    float wa = 1.0 - abs(2.0 * HeatClock.x - 1.0);
    float wb = 1.0 - wa;
    for (int i = 0; i < 4; i++) {
        vec4 h = HeatHot[i];
        if (h.w <= 0.0) continue;
        vec4 s = HeatShape[i];
        vec4 ax = HeatAxis[i];
        vec4 g = HeatGas[i];
        vec3 a = ax.xyz;
        // по оси — между низом и концом: место по оси точки луча t — t·c − ba
        float c = dot(dir, a), ba = dot(h.xyz, a);
        float t0 = 0.0, t1 = dist;
        if (abs(c) > 1e-5) {
            float ta = ba / c, tb = (ba + s.x) / c;
            t0 = max(t0, min(ta, tb));
            t1 = min(t1, max(ta, tb));
        } else if (ba > 0.0 || ba + s.x < 0.0) {
            continue;
        }
        if (t1 <= t0) continue;
        // поперёк оси: |t·d⊥ − b⊥|² = r², радиус — там, где луч ближе всего к оси
        vec3 dp = dir - a * c, bp = h.xyz - a * ba;
        float qa = dot(dp, dp), qb = dot(dp, bp);
        float tc = 0.5 * (t0 + t1);
        if (qa > 1e-8) tc = clamp(qb / qa, t0, t1);
        float r = h.w + ax.w * clamp(tc * c - ba, 0.0, s.x);
        float qc = dot(bp, bp) - r * r;
        if (qa > 1e-8) {
            float disc = qb * qb - qa * qc;
            if (disc <= 0.0) continue;
            float root = sqrt(disc);
            t0 = max(t0, (qb - root) / qa);
            t1 = min(t1, (qb + root) / qa);
        } else if (qc > 0.0) {
            continue;
        }
        float len = t1 - t0;
        if (len <= 0.0) continue;
        float mid = 0.5 * (t0 + t1);
        float u = clamp(mid * c - ba, 0.0, s.x);
        float excess = g.x * min(1.0, g.y / max(u, 1e-3));
        if (excess < HAZE_COOL) continue;
        float eddy = s.y * (h.w + ax.w * u) / h.w;
        float speed = s.z * min(1.0, g.z / max(u, 1e-3));
        float aperture = max(HAZE_PUPIL, res * mid);
        float k = hazeAngle(hazeDn(excess, s.w), len, eddy, aperture) * hazeSeen(eddy, speed) * (1.0 - mid / max(dist, mid + 1e-3)) / res;
        // меньше сотой пикселя — без шума (цена)
        if (k < 0.01) continue;
        vec3 p = dir * mid - h.xyz;
        vec2 n = wa * hazeNoise(p, a, eddy, speed * HeatClock.x * HAZE_CYCLE, HeatClock.y, aperture)
                + wb * hazeNoise(p, a, eddy, speed * HeatClock.z * HAZE_CYCLE, HeatClock.w, aperture);
        // СКО шума ~0,45 на ось, смесь двух слоёв — /√(wa² + wb²)
        shift += k / (0.45 * sqrt(wa * wa + wb * wb)) * n;
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

// Видимость источников в пикселе px с расстоянием до мира depth (вдоль взгляда): из четырёх ближних образцов прохода
// видимости (половинное разрешение: образец t — пиксель 2t) — тот, чьё расстояние до мира ближе к этому (на краю
// предмета видимость фона не ложится на предмет); коды: шар — 2 бита (0, ¼, ¾, 1), факел — 1 бит
const vec4 HEAT_SEEN_CODES = vec4(0.0, 0.25, 0.75, 1.0);

void heatSeen(ivec2 px, float depth, out vec4 ballSeen, out vec4 jetSeen) {
    ivec2 lsize = textureSize(Sampler2, 0), f = px / 2;
    vec4 best = vec4(255.0, 15.0, 1e9, 0.0);
    float err = 1e30;
    for (int k = 0; k < 4; k++) {
        ivec2 t = clamp(f + ivec2(k % 2, k / 2), ivec2(0), lsize - 1);
        vec4 s = texelFetch(Sampler2, t, 0);
        float e = abs(s.b - depth);
        if (e < err) {
            err = e;
            best = s;
        }
    }
    int balls = int(best.r + 0.5), jets = int(best.g + 0.5);
    for (int i = 0; i < 4; i++) {
        ballSeen[i] = HEAT_SEEN_CODES[(balls >> (2 * i)) & 3];
        jetSeen[i] = float((jets >> i) & 1);
    }
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
        vec3 open = PallOpenSky * faceShade(PallFaceShade, n);
        vec4 ballSeen = vec4(1.0), jetSeen = vec4(1.0);
        if (HeatShadow.x > 0.0) heatSeen(px, depth, ballSeen, jetSeen);
        vec3 added = heatLight(p, n, ballSeen, jetSeen) * seen;
        if (dot(added, LUMA) > 1e-6) {
            vec3 lit = vec3(relit(world.r, 1.0, open.r, added.r), relit(world.g, 1.0, open.g, added.g), relit(world.b, 1.0, open.b, added.b));
            lit += vec3(CROSS * max(dot(lit, LUMA) - 1.0, 0.0));
            vec3 shown = vec3(pallDisplay(lit.r), pallDisplay(lit.g), pallDisplay(lit.b));
            world = shown / max(max(max(shown.r, shown.g), shown.b), 1.0);
        }
    }
    fragColor = vec4(world, 1.0);
}
