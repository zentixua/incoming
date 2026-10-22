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
//   свет шара ядерки на мир (fx_pall_composite): цвет блока — пиксель, делённый на свет открытого неба (relit). Теней
//   рельефа и домов нет.

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
// не занято; угол пикселя, рад; есть ли свет (z > 0) и газ (w > 0)
uniform vec4 HeatFrame;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CROSS = 0.15;

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

// Свет шаров на мире в точке p (от глаза) с нормалью n, линейный, на белой стене
vec3 heatLight(vec3 p, vec3 n) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 b = HeatLights[i];
        if (b.w <= 0.0) continue;
        vec3 l = b.xyz - p;
        float d2 = dot(l, l), d = sqrt(d2);
        vec3 v = l / max(d, 1e-3);
        float face = mushBallFace(dot(n, v), v.y, n.y, HeatLightColor[i].w * d, PALL_LAND, 1.0, 0.0);
        sum += HeatLightColor[i].rgb * (mushBallFalloff(d2, b.w * b.w) * face);
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
