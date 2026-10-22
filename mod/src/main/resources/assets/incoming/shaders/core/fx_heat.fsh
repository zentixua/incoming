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
// - Свет: огненный шар взрыва, пламя лужи, горящие обломки и масло подстанции — ламбертовы шары яркостью чёрного тела
//   (L·R²/max(d², R²), mushBallFace — прямой пучок по косинусу к грани, рассеянный воздухом и отражённый землёй), как
//   свет шара ядерки на мир (fx_pall_composite): цвет блока — пиксель, делённый на свет открытого неба (relit). Факел
//   двигателя — три равномерных светящихся отрезка по оси струи (ядро, пламя, хвост до точки Дрейпера): прямой свет —
//   точный интеграл по отрезку над гранью (heatSegment), рассеянный и отражённый — как у шара в середине части.
// - Тени: прямой свет закрывают дома и рельеф в кадре — луч от точки мира к источнику по расстоянию до мира
//   (heatShadow, HeatPass.shadowed: заслон — луч ушёл за видимую поверхность меньше чем на блок и шаг). Заслон вне кадра
//   тени не даёт.

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
// вид и проекция от глаза (тень: точка луча → пиксель)
uniform mat4 HeatViewProj;
// факелы: столбец i — срез сопла от глаза (xyz), конец ядра (w; 0 — нет); ось струи от сопла (xyz), конец пламени (w);
// конец струи, полуширина у среза, её рост на метр, ослабление воздуха; свет ядра, пламени, хвоста на метр (rgb)
uniform mat4 HeatJet;
uniform mat4 HeatJetAxis;
uniform mat4 HeatJetSize;
uniform mat4 HeatJetCore;
uniform mat4 HeatJetFlame;
uniform mat4 HeatJetTail;
// не занято; угол пикселя, рад; есть ли свет (z > 0) и газ (w > 0)
uniform vec4 HeatFrame;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CROSS = 0.15;
// HeatPass.SHADOW_STEPS, HeatPass.BLOCK
const int SHADOW_STEPS = 16;
const float BLOCK = 1.0;
// свет меньше этого (линейный на экране, ниже шага sRGB у чёрного) — тень не ищется: её не видно
const float SHADOW_SEEN = 3e-4;

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

// Шум для сдвига шагов тени: свой у пикселя (interleaved gradient noise, Jimenez 2014)
float heatJitter(vec2 at) {
    return fract(52.9829189 * fract(dot(at, vec2(0.06711056, 0.00583715))));
}

// Закрыт ли источник в q от точки мира p с нормалью n (HeatPass.shadowed): шаги луча на экран, за видимой поверхностью
// луч на gap; заслон — ε < gap < ε + блок вдоль взгляда + шаг луча вдоль взгляда. Вне кадра и небо — свет. end — доля
// пути до q, дальше которой не смотреть (шар: до его края)
float heatShadow(vec3 p, vec3 n, vec3 q, float end, ivec2 size, float jitter) {
    float pixel = HeatFrame.y;
    float bias = max(0.02, 2.0 * pixel * length(p));
    vec3 s0 = p + n * bias, d = (q - s0) * end;
    float step = abs(dot(d, PallForward)) / float(SHADOW_STEPS);
    for (int k = 1; k <= SHADOW_STEPS; k++) {
        vec3 s = s0 + d * ((float(k) - jitter) / float(SHADOW_STEPS));
        vec4 clip = HeatViewProj * vec4(s, 1.0);
        if (clip.w <= 1e-4) return 1.0;
        vec2 ndc = clip.xy / clip.w;
        if (abs(ndc.x) > 1.0 || abs(ndc.y) > 1.0) return 1.0;
        ivec2 px = clamp(ivec2((ndc * 0.5 + 0.5) * vec2(size)), ivec2(0), size - 1);
        float zs = pallScene(texelFetch(Sampler1, px, 0).r);
        if (zs >= 1e8) continue;
        float zq = dot(s, PallForward);
        float gap = zq - zs, eps = 2.0 * pixel * zq;
        if (gap > eps && gap < eps + BLOCK * zq / max(length(s), 1e-6) + step) return 0.0;
    }
    return 1.0;
}

// Свет шаров и факелов на мире в точке p (от глаза) с нормалью n, линейный, на белой стене; прямой — с тенью
vec3 heatLight(vec3 p, vec3 n, ivec2 size, float jitter) {
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
        if (d > b.w && direct > 0.0 && dot(c, LUMA) * direct > SHADOW_SEEN) face -= direct * (1.0 - heatShadow(p, n, b.xyz, 1.0 - b.w / d, size, jitter));
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
        if (dot(direct, LUMA) > SHADOW_SEEN && weight > 0.0) direct *= heatShadow(p, n, p + lit / weight, 1.0, size, jitter);
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
    vec2 at = here + shift;
    vec3 world = texture(Sampler0, at / vec2(size)).rgb;
    ivec2 px = clamp(ivec2(floor(at)), ivec2(0), size - 1);
    float depth = pallScene(texelFetch(Sampler1, px, 0).r);
    if (HeatFrame.z > 0.0 && depth < 1e8) {
        vec3 p = sceneAt(Sampler1, InvViewProj, PallForward, px, size);
        vec3 n = sceneNormal(Sampler1, InvViewProj, PallForward, px, size, p);
        // свет — на свете самого мира: дымка воздуха и туман Minecraft перед ним — нет
        float fd = Haze.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p);
        float seen = exp(-Haze.x * length(p)) * (1.0 - smoothstep(Haze.y, Haze.z, fd));
        vec3 added = heatLight(p, n, size, heatJitter(here)) * seen;
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
