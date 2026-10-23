// Гриб объёмом (client/nuclear/MushroomField — числа кадра, nuclear/model/MushroomModel — законы): шапка — огненный шар,
// свернувшийся в кольцо с куполом над его дыркой (до сворачивания — сфера шара: кольцо нулевого радиуса), ножка, юбка
// пыли у её основания; у подрыва на воде — ещё полый столб воды с короной брызг наверху (nuclear/model/WaterModel). Поле аналитическое: функции чисел гриба этого кадра и
// места; клубы — шум общей плитки пелены (VolumeNoise) в координатах тела гриба, в долях его размера. Общее у объёма
// (fx_pall), накопления (fx_pall_resolve) и квадратов слоя эффектов (fx.vsh: что гриб закрывает перед частицей).
// Скалярные законы — копии MushroomModel и MushroomField (MushroomGlslTest считает их и сверяет с Java).
//
// Таблица грибов (MushroomField.TEXELS текселей RGBA32F на гриб; строка k — гриб k, строка MUSH_BB_ROW — свет чёрного
// тела): блоки, от глаза; высоты — над землёй под эпицентром.
//   0 = (эпицентр x, z; земля под ним по y; 1 — гриб есть)
//   1 = (снос ветром x, z на всю долю; верх после стабилизации — по его доле снос; радиус рамки)
//   2 = (середина кольца шапки по высоте; радиус шапки; большой радиус кольца; верх рамки)
//   3 = (полуоси сечения кольца по горизонтали и вертикали; середина купола; его полуось по горизонтали)
//   4 = (полуось купола по вертикали; ослабление шапки, 1/блок; плитка клубов шапки, блоки; секунды после подрыва)
//   5 = (альбедо ω' шапки rgb; асимметрия g')
//   6 = (верх ножки; её радиус, средний по высоте; раструб пыли у земли сверх низа ножки; толща ножки поперёк)
//   7 = (ω' ножки rgb; g')
//   8 = (середина юбки по земле; её полуширина; высота; ослабление, 1/блок)
//   9 = (ω' юбки rgb; g')
//  10 = (воздух вокруг, K; перепад температуры ядра, K; низ шапки; плитка клубов ножки)
//  11 = (подъём струй ножки, блоки — MushroomModel.stemRise; верх дыма шторма — тот же, что у его столбов в пелене;
//        радиус зоны пожаров; 0)
//  12 = (свет пожаров под грибом rgb, как у пелены: карта огня шторма и свет кадра; часы кипения шапки, рад)
//  13 = (середина стенки столба воды по радиусу; её полутолщина; верх стенки — середина короны; ослабление, 1/блок;
//        0 — столба нет)
//  14 = (ω' столба rgb; g')
//  15 = (наружный край короны по радиусу; её полувысота; подъём струй стенки — верх столба, блоки; плитка клубов
//        столба, блоки)
//  16 = (доля перепада температуры ядра у края шапки: у шара 1, у кольца MushroomModel.EDGE_HEAT; размах клубов края
//        против кольца: у шара — гладкая сфера; место кадра узора поверхности шара — от −1 (узора нет) к 0 и номер
//        кадра с долей; доля узора против языков клубов — 1 − доля кольца, 0 — узор не прочитан; MushroomField.ballSurface)
//  17 = (плитка карты тени в плоскости поперёк солнца — начало u, v и стороны от эпицентра, блоки; сторона 0 — тени нет;
//        MUSH_SHADOW_RECT)
//  18 = (верх мира над землёй под эпицентром — верхний слой карты тени; отражение земли под грибом rgb — верх слоя пыли
//        у основания ножки, MushroomField.groundUnder; MUSH_SHADOW_TOP)
//  19 = (подсос у основания ножки, MushroomField.foot: верх слоя пыли у стенки ножки, блоки; его ослабление у земли
//        × доля гриба, 1/блок; доля подсоса; докуда поднялся воздух ножки с начала подсоса, блоки; 0 — подсоса нет)

const int MUSHROOMS = 4;
const int MUSH_BB_ROW = 4;
// свет чёрного тела в таблице: узлов, температуры края (K) — по логарифму (MushroomField.BB_*)
const float MUSH_BB_NODES = 256.0;
const float MUSH_BB_FROM = 500.0;
const float MUSH_BB_TO = 30000.0;
// размах сдвига края клубами и мягкость края — шапка, ножка, юбка, в единицах клубов части (MushroomField: LOBES_*,
// EDGE_*): у шапки — меньшая полуось сечения кольца, у ножки — её радиус на этой высоте, у юбки — меньшая полуось
const float MUSH_CAP_LOBES = 0.4;
const float MUSH_CAP_EDGE = 0.07;
const float MUSH_STEM_LOBES = 0.4;
const float MUSH_STEM_EDGE = 0.2;
const float MUSH_SKIRT_LOBES = 0.8;
const float MUSH_SKIRT_EDGE = 0.25;
// то же у столба воды, в единицах толщины его стенки (MushroomField: LOBES_COLUMN, EDGE_COLUMN)
const float MUSH_COLUMN_LOBES = 0.6;
const float MUSH_COLUMN_EDGE = 0.15;
// сглаживание объединения кольца и купола, в единицах клубов шапки (мягкий минимум опускает край не больше чем на
// четверть его)
const float MUSH_BLEND = 0.25;
// языки клубов на краю шапки (MushroomModel.SKIN): нагрев края ± эта доля его же на размах клубов — выступ клуба —
// свежий горячий газ изнутри, ложбина — уже смешанный; у шара — запечённый узор (mushBallPattern в fx_pall), языки —
// его доля, пока шар сворачивается
const float MUSH_SKIN = 0.09 / 0.35;
// большой радиус кольца шапки — эта доля её радиуса, когда шар свернулся (MushroomModel.RING): доля кольца — по нему
const float MUSH_RING = 0.55;
// Кипение шапки (mushCapLobes): узор её клубов — два слоя, каждый живёт столько радиан часов кипения (churn —
// MushroomModel.churn: сколько радиан своего сечения прошло вещество шапки). Клубы поднимаются из шапки к середине жизни
// слоя и уходят в неё к концу — за время оборота вихря своего размера: клуб в полсечения (MushroomField: пузырь в
// полсечения) по закону Колмогорова оборачивается за (1/2)^(2/3) радиана часов, вверх и вниз — вдвое дольше
const float MUSH_CYCLE = 1.26;
// верх и низ клубов одного слоя (mushLobes в fx_pall: 0,5 крупных и 0,175 мелких), в долях размаха
const float MUSH_LOBES_TOP = 0.675;
// на сколько слой уходит в шапку, когда его нет, в долях размаха клубов: больше всего их размаха (±MUSH_LOBES_TOP),
// чтобы уходящий слой не выступал над рождённым и смена его узора не была видна
const float MUSH_SINK = 1.4;
// глубже всего клубы шапки уходят внутрь (верхний из двух слоёв с поправкой mushRise — до 1/6 ниже низа одного слоя),
// в долях размаха: глубже края, сдвинутого так, шапка густа целиком
const float MUSH_LOBES_DEEP = MUSH_LOBES_TOP + 1.0 / 6.0;
// докуда за край без клубов доходит вещество (сдвиг клубами наружу до 0,68 размаха, мягкость края, у шапки — и мягкий
// минимум), в единицах клубов — шапка, ножка, юбка
const float MUSH_CAP_REACH = 0.68 * MUSH_CAP_LOBES + MUSH_CAP_EDGE + 0.25 * MUSH_BLEND;
const float MUSH_STEM_REACH = 0.68 * MUSH_STEM_LOBES + MUSH_STEM_EDGE;
const float MUSH_SKIRT_REACH = 0.68 * MUSH_SKIRT_LOBES + MUSH_SKIRT_EDGE;
// у столба — и мягкий минимум стенки и короны
const float MUSH_COLUMN_REACH = 0.68 * MUSH_COLUMN_LOBES + MUSH_COLUMN_EDGE + 0.25 * MUSH_BLEND;

struct Mush {
    vec3 base;
    vec2 drift;
    float endTop;
    float boundR;
    float capY;
    float capR;
    float ringR;
    float boundTop;
    float ringA;
    float ringB;
    float domeY;
    float domeR;
    float domeH;
    float capSigma;
    float capTile;
    float seconds;
    vec4 capMat;
    float stemTop;
    float stemR;
    float stemFlare;
    float stemTau;
    vec4 stemMat;
    float skirtR;
    float skirtW;
    float skirtH;
    float skirtSigma;
    vec4 skirtMat;
    float tAmb;
    float dT;
    float capBot;
    float stemTile;
    float stemRise;
    float fireTop;
    float fireR;
    vec3 fire;
    float churn;
    float colMid;
    float colHalf;
    float colTop;
    float colSigma;
    vec4 colMat;
    float crownR;
    float crownH;
    float colRise;
    float colTile;
    float edge;
    float billows;
    float ballFrame;
    float ballShare;
    float footTop;
    float footSigma;
    float footShare;
    float footRise;
};

Mush mushLoad(sampler2D data, int k) {
    vec4 t0 = texelFetch(data, ivec2(0, k), 0), t1 = texelFetch(data, ivec2(1, k), 0), t2 = texelFetch(data, ivec2(2, k), 0);
    vec4 t3 = texelFetch(data, ivec2(3, k), 0), t4 = texelFetch(data, ivec2(4, k), 0), t6 = texelFetch(data, ivec2(6, k), 0);
    vec4 t8 = texelFetch(data, ivec2(8, k), 0), t10 = texelFetch(data, ivec2(10, k), 0), t11 = texelFetch(data, ivec2(11, k), 0);
    vec4 t12 = texelFetch(data, ivec2(12, k), 0), t13 = texelFetch(data, ivec2(13, k), 0), t15 = texelFetch(data, ivec2(15, k), 0);
    vec4 t16 = texelFetch(data, ivec2(16, k), 0), t19 = texelFetch(data, ivec2(19, k), 0);
    // эпицентр в таблице — x, z и земля по y (как у слота пелены): в место — x, y, z
    return Mush(t0.xzy, t1.xy, t1.z, t1.w, t2.x, t2.y, t2.z, t2.w, t3.x, t3.y, t3.z, t3.w, t4.x, t4.y, t4.z, t4.w,
            texelFetch(data, ivec2(5, k), 0), t6.x, t6.y, t6.z, t6.w, texelFetch(data, ivec2(7, k), 0), t8.x, t8.y, t8.z, t8.w,
            texelFetch(data, ivec2(9, k), 0), t10.x, t10.y, t10.z, t10.w, t11.x, t11.y, t11.z, t12.rgb, t12.w,
            t13.x, t13.y, t13.z, t13.w, texelFetch(data, ivec2(14, k), 0), t15.x, t15.y, t15.z, t15.w, t16.x, t16.y, t16.z, t16.w,
            t19.x, t19.y, t19.z, t19.w);
}

// есть ли гриб k в кадре
bool mushOn(sampler2D data, int k) {
    return texelFetch(data, ivec2(0, k), 0).w > 0.5;
}

// доля сноса ветром на высоте h (MushroomModel.driftShare): верх сносит сильнее низа
float mushShare(float h, float endTop) {
    return clamp(h / max(endTop, 1.0), 0.0, 1.0);
}

// радиус ножки на доле её высоты w (MushroomModel.stemRadius): в среднем stemR, у шапки шире на треть, у земли — раструб
// пыли flare сверх низа ножки
float mushStemR(float w, float stemR, float flare) {
    float x = clamp(w, 0.0, 1.0);
    float base = pow(1.0 - x, 6.0);
    return stemR * (0.85 + 0.45 * x * x) + flare * base;
}

// Подсос у основания ножки (MushroomField.footWidth, footShare, footDensity): ветер к ножке у земли (afterwinds) несёт
// слой пыли волны в неё и поворачивает вверх по её стенке — течение в углу «земля — стенка» (ψ = x·z: линия тока, что
// вдали идёт по верху слоя top, у стенки уходит вверх, x·z → top²). Полоса воздуха слоя за стенкой ножки на высоте h —
// top/√(1 + (h/top)²) (то же, сглаженное у земли; ⚠ угол плоский, у ножки радиус того же порядка, что слой). Пыль в
// ней — пыль слоя: ослабление у земли sigma, вверх — доля footShare (полоса тоньше, её разбавляет ножка, ⚠ оценка), за
// вычетом той, что сам слой ещё держит на этой высоте (pallProfile): у стенки слой и подсос вместе — sigma·footShare, без
// ступени на верху слоя. Где слой с подсосом уже не гуще самой ножки, его пыль в ней перемешана (её несёт ножка,
// StemDust): полоса за стенкой там сходит на нет — от ослабления ножки до половины его
float mushFootWidth(float h, float top) {
    float t = max(top, 1.0), u = h / t;
    return t / sqrt(1.0 + u * u);
}

float mushFootShare(float h, float top) {
    float u = h / max(top, 1.0);
    return 1.0 / (1.0 + u * u);
}

// ослабление подсоса на высоте h без доли края: share — его доля (у края подсоса полоса уже; густота пыли в ней — слоя:
// воздух несёт её, не разбавляя), rise — докуда
// поднялся воздух ножки с начала подсоса (выше пыли слоя ещё нет), stem — ослабление самой ножки на этой высоте
float mushFootDensity(float h, float top, float sigma, float share, float rise, float stem) {
    if (h < 0.0) return 0.0;
    if (share <= 0.0) return 0.0;
    float t = max(top, 1.0);
    float up = clamp((rise - h) / t, 0.0, 1.0);
    // слой и подсос вместе у стенки; сходит на нет, где их пыль реже ножки (+ 1e-6: у ножки без пыли на этой высоте края
    // нет — smoothstep с равными краями не определён)
    float both = sigma * mushFootShare(h, t) * up;
    return max(both - sigma * pallProfile(h / t), 0.0) * smoothstep(0.5 * stem, stem + 1e-6, both);
}

// мера места в сечении кольца: 1 — на его краю (rho — от оси по горизонтали, y — от середины кольца)
float mushRing(float rho, float y, float ringR, float a, float b) {
    float u = (rho - ringR) / max(a, 1e-3), v = y / max(b, 1e-3);
    return sqrt(u * u + v * v);
}

// мера места в эллипсе (купол, юбка в сечении): 1 — на краю
float mushEllipse(float x, float y, float rx, float ry) {
    float u = x / max(rx, 1e-3), v = y / max(ry, 1e-3);
    return sqrt(u * u + v * v);
}

// Расстояние со знаком до края эллипса с полуосями rx, ry, блоки (внутри — меньше нуля): мера − 1, делённая на её
// наклон, — точно у края, где его и сдвигают клубы; в глубине — не глубже меньшей полуоси
float mushEllipseDist(float x, float y, float rx, float ry) {
    vec2 r = max(vec2(rx, ry), vec2(1e-3));
    vec2 u = vec2(x, y) / r;
    float s = length(u), slope = length(u / r);
    float deep = -min(r.x, r.y);
    if (slope < 1e-9) return deep;
    return max((s - 1.0) * s / slope, deep);
}

// мягкий минимум (полиномиальный, k — ширина сглаживания): кольцо и купол — одна поверхность; опускает не больше k/4
float mushSmin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / max(k, 1e-3), 0.0, 1.0);
    return b + (a - b) * h - k * h * (1.0 - h);
}

// Плотность края: 1 внутри, 0 снаружи, край мягкий на ±e блоков (d — расстояние со знаком до края, сдвинутого клубами)
float mushEdge(float d, float e) {
    return smoothstep(-e, e, -d);
}

// Место точки p (от глаза) в теле гриба: x, z — от оси гриба на этой высоте (со сносом), y — над землёй под эпицентром
vec3 mushLocal(Mush m, vec3 p) {
    float h = p.y - m.base.y;
    vec2 xz = p.xz - m.base.xz - m.drift * mushShare(h, m.endTop);
    return vec3(xz.x, h, xz.y);
}

// Возраст слоя j (0 или 1) узора клубов шапки на часах кипения churn, доля его жизни 0..1: слои — со сдвигом на полжизни
float mushLayerAge(float churn, float j) {
    float c = churn / MUSH_CYCLE + 0.5 * j;
    return c - floor(c);
}

// Доля слоя возраста age: ноль при рождении и в конце, 1 — в середине; у двух слоёв вместе — 1
float mushLayerWeight(float age) {
    return 1.0 - abs(2.0 * age - 1.0);
}

// Номер узора слоя j: свой у каждой жизни каждого слоя
float mushLayerSeed(float churn, float j) {
    return 2.0 * floor(churn / MUSH_CYCLE + 0.5 * j) + j;
}

// Поправка края шапки на среднее (low — доля слабейшего слоя): край — верхний из двух слоёв, опущенных на MUSH_SINK × (1 −
// доля), и без поправки в середине жизни слоёв шапка садилась бы на треть размаха. Среднее верхнего из двух равномерных
// на ±h (h = 1/2) со сдвигами − (1 − w)·K: −low·K + h·(2 − e)³/24, e = |w₀ − w₁|·K/h (rest = 2 − e), пока e < 2
float mushRise(float low) {
    float rest = max(2.0 - 2.0 * MUSH_SINK * (1.0 - 2.0 * low), 0.0);
    return MUSH_SINK * low - rest * rest * rest / 48.0;
}

// Доля обращения на расстоянии rho от оси гриба: у оси (вершина купола — точка застоя, поток там только поднимается) — 0,
// к оси кольца — вся
float mushTaper(float rho, float ringR) {
    return smoothstep(0.0, max(ringR, 1.0), rho);
}

// Доля обращения в месте (u, v) сечения кольца (в долях его полуосей): вихрь Рэнкина — внутри сечения ядро вращается
// целиком, снаружи поток без завихренности, угловая скорость падает как 1/r². Целиком вращаясь и снаружи, купол над
// кольцом переходил бы через ось гриба, и узор там складывался бы зеркально
float mushSwirl(float u, float v) {
    return 1.0 / max(u * u + v * v, 1.0);
}

// Откуда пришло вещество, которое сейчас в месте (u, v) сечения, если оно обошло ось кольца на ang радиан: обращение
// по часовой в плоскости «от оси гриба, вверх» — внутри вверх, по верху наружу, снаружи вниз; назад — против часовой
float mushWasU(float u, float v, float ang) {
    return cos(ang) * u - sin(ang) * v;
}

float mushWasV(float u, float v, float ang) {
    return sin(ang) * u + cos(ang) * v;
}

// единицы клубов, блоки (MushroomField.capUnit, skirtUnit): шапка — меньшая полуось сечения кольца, юбка — её сечения
float mushCapUnit(Mush m) {
    return min(m.ringA, m.ringB);
}

float mushSkirtUnit(Mush m) {
    return min(m.skirtW, m.skirtH);
}

// единица клубов столба воды, блоки (MushroomField.columnUnit): толщина стенки
float mushColumnUnit(Mush m) {
    return 2.0 * m.colHalf;
}

// Отражение полубесконечного слоя вещества в рассеянном свете, R∞ (MushroomField.wallReflectance): два потока в
// замыкании полусферического среднего (Toon et al. 1989, табл. 1: γ₁ = 2 − ω(1 + g), γ₂ = ω(1 − g)) — (1 − s)/(1 + s),
// s = √((1 − ω)/(1 − ωg)), от 0 у одного поглощения до 1 у одного рассеяния. Не Эддингтон: у него обратное рассеяние
// γ₂ = (ω(4 − 3g) − 1)/4 при ω < 1/(4 − 3g) меньше нуля, и R∞ уходит ниже нуля (у шара без капель — до −0,07)
float mushWallR(float w, float g) {
    float s = sqrt(max(1.0 - w, 0.0) / max(1.0 - w * g, 1e-4));
    return (1.0 - s) / (1.0 + s);
}

// радиус ножки на высоте h места тела
float mushStemAt(Mush m, float h) {
    return mushStemR(h / max(m.capBot, 1.0), m.stemR, m.stemFlare);
}

// радиус подсоса на высоте h места тела: ножка и полоса воздуха слоя за её стенкой
float mushFootAt(Mush m, float h) {
    return mushStemAt(m, h) + m.footShare * mushFootWidth(max(h, 0.0), m.footTop);
}

float mushFootSigma(Mush m, float h) {
    return mushFootDensity(h, m.footTop, m.footSigma, m.footShare, min(m.footRise, m.stemTop), mushStemSigma(m, h));
}

// Место в узоре клубов шапки (от её оси и середины по высоте, блоки) для места q тела, если ядро кольца с тех пор обошло
// своё сечение на turn радиан: обращение по эллипсу сечения вокруг оси кольца (mushWasU, mushWasV), снаружи сечения
// слабее (mushSwirl), у оси гриба сходит на нет (mushTaper). Узор идёт с веществом: точку, где вещество сейчас, узор
// берёт оттуда, где оно было
vec3 mushFlow(Mush m, vec3 q, float turn) {
    float rho = length(q.xz);
    float a = max(m.ringA, 1.0), b = max(m.ringB, 1.0);
    float u = (rho - m.ringR) / a, v = (q.y - m.capY) / b;
    float ang = turn * mushTaper(rho, m.ringR) * mushSwirl(u, v);
    float r = max(m.ringR + a * mushWasU(u, v, ang), 0.0);
    vec2 around = rho > 1e-3 ? q.xz / rho : vec2(1.0, 0.0);
    return vec3(around.x * r, b * mushWasV(u, v, ang), around.y * r);
}

// Сдвиг часов кипения в месте q шапки гриба k, доли жизни слоя: клубы кипят не все разом, а пятнами в полшапки (с одними
// часами на всю шапку её узор дышал бы весь сразу — каждые полжизни слоя клубов то вдвое больше, то снова как было).
// Плавный: где сдвиг меняется, слой одной жизни повёрнут на разный угол (mushFlow), и узор тянется — у края сечения
// не больше чем на 0,6 своего размера к концу жизни
float mushPhase(Mush m, vec3 q, float k) {
    vec3 x = vec3(q.x, q.y - m.capY, q.z) / max(m.capR, 1.0);
    return 0.25 * (sin(1.3 * x.x + 0.9 * x.y + k) + sin(1.1 * x.z - 1.2 * x.y + 2.1 + 0.7 * k)
            + sin(0.8 * x.x + 1.0 * x.z + 4.2 + 1.3 * k));
}

// Стенка столба воды без клубов в месте q тела (rho — от оси): полый цилиндр от воды (и чуть под ней) до середины
// короны; расстояние со знаком не дальше настоящего — по радиусу и по высоте
float mushWall(Mush m, vec3 q, float rho) {
    return max(abs(rho - m.colMid) - m.colHalf, max(q.y - m.colTop, -q.y - 0.02 * m.colTop));
}

// Расстояния со знаком до края шапки, ножки, юбки и столба воды без клубов в месте q тела, блоки (у ножки — по радиусу на
// этой высоте; её верх и низ — mushStemSigma; столба нет — далеко): стенка столба и корона на ней — одна поверхность;
// корона — кольцо, в сечении эллипс с серединой на середине стенки (WaterModel.Column)
vec4 mushDist(Mush m, vec3 q) {
    float rho = length(q.xz);
    float ring = mushEllipseDist(rho - m.ringR, q.y - m.capY, m.ringA, m.ringB);
    float dome = mushEllipseDist(rho, q.y - m.domeY, m.domeR, m.domeH);
    float column = 1e9;
    if (m.colSigma > 0.0) {
        float crown = mushEllipseDist(rho - m.colMid, q.y - m.colTop, m.crownR - m.colMid, m.crownH);
        column = mushSmin(mushWall(m, q, rho), crown, MUSH_BLEND * mushColumnUnit(m));
    }
    return vec4(mushSmin(ring, dome, MUSH_BLEND * mushCapUnit(m)), rho - mushStemAt(m, q.y),
            mushEllipseDist(rho - m.skirtR, q.y, m.skirtW, m.skirtH), column);
}

// Густота ножки на высоте h без доли края: её толща поперёк на её ширине; верх тает у верха ножки, под землёй её нет
float mushStemSigma(Mush m, float h) {
    if (h < -0.02 * m.capBot || h > m.stemTop) return 0.0;
    // у шара у земли низ шапки ниже земли (capBot < 0): ширина таяния — не меньше блока
    float topFade = clamp((m.stemTop - h) / max(0.05 * m.capBot + 1.0, 1.0), 0.0, 1.0);
    return m.stemTau / (2.0 * mushStemAt(m, h)) * topFade;
}

// Ослабление шапки, ножки, юбки и столба воды в месте q без шума — среднее по клубам (край размыт на размах клубов): для
// толщи к свету и квадратов слоя; без столба его край не считается (стенка нулевой толщины — край нулевой ширины)
vec4 mushMean(Mush m, vec3 q) {
    vec4 d = mushDist(m, q);
    float soft = 0.5 * MUSH_STEM_LOBES * mushStemAt(m, q.y);
    // ножка и подсос у её основания — одна пыль: где подсос гуще ножки, он
    float stem = max(mushStemSigma(m, q.y) * mushEdge(d.y, soft),
            mushFootSigma(m, q.y) * mushEdge(length(q.xz) - mushFootAt(m, q.y), soft));
    vec4 s = vec4(m.capSigma * mushEdge(d.x, 0.5 * MUSH_CAP_LOBES * m.billows * mushCapUnit(m)), stem,
            m.skirtSigma * mushEdge(d.z, 0.5 * MUSH_SKIRT_LOBES * mushSkirtUnit(m)), 0.0);
    if (m.colSigma > 0.0) s.w = m.colSigma * mushEdge(d.w, 0.5 * MUSH_COLUMN_LOBES * mushColumnUnit(m));
    return s;
}

// Сколько до вещества гриба от места q, блоки мира, не больше (0 — уже в нём или рядом): шаг луча по пустому. Мера
// эллипса растёт не быстрее расстояния, делённого на меньшую полуось, — мера − 1, умноженная на неё, не дальше края;
// радиус ножки по высоте меняется не круче своего наклона; стенка столба — уже расстояние не дальше настоящего; снос по
// высоте растягивает тело не больше чем в 1 + снос / верх раз
float mushGap(Mush m, vec3 q) {
    float rho = length(q.xz);
    float gap = 1e9;
    if (m.capSigma > 0.0) {
        float ring = (mushRing(rho, q.y - m.capY, m.ringR, m.ringA, m.ringB) - 1.0) * min(m.ringA, m.ringB);
        float dome = (mushEllipse(rho, q.y - m.domeY, m.domeR, m.domeH) - 1.0) * min(m.domeR, m.domeH);
        gap = min(ring, dome) - MUSH_CAP_REACH * mushCapUnit(m);
    }
    if (m.stemTau > 0.0) {
        // наклон радиуса ножки с клубами: раструб у земли (−6·раструб) и у шапки (+0,9·радиус) на высоту шапки; полоса
        // подсоса за стенкой (mushFootWidth) по высоте не круче 2/(3√3) ≈ 0,385
        float slope = (1.0 + MUSH_STEM_REACH) * max(0.9 * m.stemR, 6.0 * m.stemFlare) / max(m.capBot, 1.0) + 0.385 * m.footShare;
        float foot = m.footShare * mushFootWidth(max(q.y, 0.0), m.footTop);
        float radial = (rho - (1.0 + MUSH_STEM_REACH) * mushStemAt(m, q.y) - foot) / sqrt(1.0 + slope * slope);
        gap = min(gap, max(radial, max(q.y - m.stemTop, -0.02 * m.capBot - q.y)));
    }
    if (m.skirtSigma > 0.0) {
        float u = mushSkirtUnit(m);
        gap = min(gap, (mushEllipse(rho - m.skirtR, q.y, m.skirtW, m.skirtH) - 1.0) * u - MUSH_SKIRT_REACH * u);
    }
    if (m.colSigma > 0.0) {
        float a = m.crownR - m.colMid;
        float crown = (mushRing(rho, q.y - m.colTop, m.colMid, a, m.crownH) - 1.0) * min(a, m.crownH);
        gap = min(gap, min(mushWall(m, q, rho), crown) - MUSH_COLUMN_REACH * mushColumnUnit(m));
    }
    return max(gap, 0.0) / (1.0 + length(m.drift) / max(m.endTop, 1.0));
}

// Отрезок прямой o + dir·t (o — от глаза, dir — единичный) в рамке гриба: цилиндр радиуса boundR вдоль оси, наклонённой
// сносом (доля сноса растёт с высотой — mushLocal; выше endTop и под землёй — запас в boundR). В координатах со сдвигом по
// высоте ось вертикальна, а прямая остаётся прямой: вертикальный цилиндр вокруг середины сноса брал бы на поздних кадрах с
// ветром пустое шириной в снос. Вход ≥ выхода — мимо
vec2 mushLine(Mush m, vec3 o, vec3 dir) {
    vec3 base = m.base - o;
    vec2 k = m.drift / max(m.endTop, 1.0);
    vec2 d = dir.xz - k * dir.y, c = base.xz - k * base.y;
    float a = dot(d, d), b = dot(d, c), cc = dot(c, c) - m.boundR * m.boundR;
    vec2 t;
    if (a < 1e-8) {
        // прямая вдоль оси: внутри цилиндра — вся, снаружи — мимо
        if (cc > 0.0) return vec2(1.0, 0.0);
        t = vec2(-1e9, 1e9);
    } else {
        float disc = b * b - a * cc;
        if (disc <= 0.0) return vec2(1.0, 0.0);
        float sq = sqrt(disc);
        t = vec2(b - sq, b + sq) / a;
    }
    float lo = base.y - 0.05 * m.boundTop, hi = base.y + m.boundTop;
    if (abs(dir.y) > 1e-5) {
        float ta = lo / dir.y, tb = hi / dir.y;
        t = vec2(max(t.x, min(ta, tb)), min(t.y, max(ta, tb)));
    } else if (lo > 0.0 || hi < 0.0) {
        return vec2(1.0, 0.0);
    }
    return t;
}

// Отрезок луча из глаза (dir — единичный) в рамке гриба (mushLine): только перед глазом
vec2 mushSpan(Mush m, vec3 dir) {
    vec2 t = mushLine(m, vec3(0.0), dir);
    return vec2(max(t.x, 0.0), t.y);
}

// Октаэдрическая карта направления (x, y, z; y — вверх) — u и v 0..1 (NukeBallSurface.octU/octV, pack.py: octa_decode):
// верхняя полусфера — ромб в середине карты, нижняя — его углы
float mushOctU(float x, float y, float z) {
    float s = abs(x) + abs(y) + abs(z);
    if (y < 0.0) return 0.5 * ((1.0 - abs(z / s)) * sign(x) + 1.0);
    return 0.5 * (x / s + 1.0);
}

float mushOctV(float x, float y, float z) {
    float s = abs(x) + abs(y) + abs(z);
    if (y < 0.0) return 0.5 * ((1.0 - abs(x / s)) * sign(z) + 1.0);
    return 0.5 * (z / s + 1.0);
}

// Насколько виден узор поверхности шара при месте кадра f (MushroomField.ballSurface): до минимума яркости (f = −1) —
// нет, поверхность — гладкий фронт; до отрыва (f = 0) проступает сквозь проясняющийся фронт (FireballModel.unmasked)
float mushBallContrast(float f) {
    return clamp(f + 1.0, 0.0, 1.0);
}

// место температуры в строке света чёрного тела (0..1)
float mushBbU(float kelvin) {
    float x = (log2(max(kelvin, MUSH_BB_FROM)) - log2(MUSH_BB_FROM)) / (log2(MUSH_BB_TO) - log2(MUSH_BB_FROM));
    return clamp(x, 0.0, 1.0);
}

// Свет ламбертова шара радиуса R (квадрат r2) в d (квадрат d2) от его середины — доля света у его поверхности:
// R²/max(d², R²) (MushroomModel.light)
float mushBallFalloff(float d2, float r2) {
    return r2 / max(d2, r2);
}

// Свет шара на грани мира — доля света шара на белой стене лицом к нему без воздуха (MushroomField.ballFace): прямой
// пучок e^(−σd) по косинусу к грани (nv — косинус между нормалью грани и направлением на шар); рассеянный воздухом —
// сколько света импульса доходит прямым и рассеянным вместе (Glasstone 1964, рис. 7.104: e^(−(D/V)^0,544), D/V = σd/3,912)
// без прямого, с неба вокруг шара — на грань по (1 + nv)/2 (оценка: рассеянное вперёд светит со стороны шара); и
// отражённый землёй с альбедо albedo свет шара на горизонталь — на грань по доле земли, что она видит, (1 − ny)/2. Под
// пеленой: прямое и рассеянное у шара — сквозь неё (through, e^(−τ/μ)), рассеянное ею вниз на горизонталь — diffuse на
// единицу света над ней, на грань — по доле неба (1 + ny)/2. vy — синус высоты шара над горизонтом точки. Тени рельефа и
// построек нет. Ближе ~0,05 V формула Glasstone ниже прямого — рассеянного нет (MushroomField.ballFace).
float mushBallFace(float nv, float vy, float ny, float sd, float albedo, float through, float diffuse) {
    float direct = exp(-sd);
    float scattered = max(exp(-pow(max(sd, 0.0) / 3.912, 0.544)) - direct, 0.0);
    float beam = direct * max(nv, 0.0) + scattered * 0.5 * (1.0 + nv);
    float ground = direct * max(vy, 0.0) + scattered * 0.5 * (1.0 + vy);
    float sky = (direct + scattered) * diffuse;
    return beam * through + sky * 0.5 * (1.0 + ny) + albedo * (ground * through + sky) * 0.5 * (1.0 - ny);
}

// Свет чёрного тела температуры T в единицах кадра (белая стена в полдень — 1), линейный rgb (BlackBody)
vec3 mushBlackBody(sampler2D data, float kelvin) {
    vec2 size = vec2(textureSize(data, 0));
    float x = (mushBbU(kelvin) * (MUSH_BB_NODES - 1.0) + 0.5) / size.x;
    return texture(data, vec2(x, (float(MUSH_BB_ROW) + 0.5) / size.y)).rgb;
}

// Пропускание гриба k вдоль луча dir на [0, len] без шума (steps шагов) — квадраты слоя перед частицей; air — воздух
// кадра (pallAir)
float mushTransmit(sampler2D data, vec3 dir, float len, int steps, vec4 air) {
    float through = 1.0;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(data, k)) continue;
        Mush m = mushLoad(data, k);
        vec2 span = mushSpan(m, dir);
        span.y = min(span.y, len);
        if (span.y <= span.x) continue;
        float ds = (span.y - span.x) / float(steps), tau = 0.0;
        for (int i = 0; i < steps; i++) {
            tau += dot(mushMean(m, mushLocal(m, dir * (span.x + (float(i) + 0.5) * ds))), vec4(1.0)) * ds;
        }
        // гриб в дымке воздуха тает в небе за ним (pallAir): его свет и непрозрачность × доля, дошедшая через воздух, а не
        // цвет тумана — то, что за ним, видно сильнее
        through *= 1.0 - pallAir(air, 0.5 * (span.x + span.y), dir.y) * (1.0 - exp(-tau));
    }
    return through;
}

// ---------------------------------------------------------------- тень грибов от солнца (PallPass: fx_mush_shadow)
// Карта тени: у гриба k — плитка MUSH_SHADOW × MUSH_SHADOW (строки k·MUSH_SHADOW…) в плоскости поперёк солнца (оси —
// mushShadowAxes, начало и стороны — тексель MUSH_SHADOW_RECT таблицы, от эпицентра). Тексель — прямая вдоль солнца; в нём
// доля света солнца, что гриб на этой прямой пропускает к трём высотам над землёй под эпицентром — земле, середине между ней и
// верхом мира и самому верху (r, g, b; верх — тексель MUSH_SHADOW_TOP), в долях прямого пучка на горизонтальную землю: прямой пучок сквозь
// толщу гриба без шума над высотой и рассеянный этой толщей вперёд (mushSeen). Доля бывает больше 1: тонкое белое
// вещество низко над слоем при низком солнце светит вперёд ярче пучка, — кто берёт карту, зажимает её до 1
// (mushShadow). Над точкой мира свет солнца проходит то, что на её прямой выше неё: всё вещество гриба к солнцу — выше
// точки, от солнца — ниже (солнце над горизонтом). Плитка считается раз в кадр (MUSH_SHADOW² прямых), точке мира — одна
// выборка на гриб. Её берут мир (fx_pall_composite) и солнце на пелене (fx_pall: пыль под шапкой — в её тени, как
// ножка); частицы слоя — пока нет.
const int MUSH_SHADOW = 128;
// тексели строки гриба: плитка карты (MushroomField.SHADOW_RECT) и верх мира над землёй под эпицентром (SHADOW_TOP)
const int MUSH_SHADOW_RECT = 17;
const int MUSH_SHADOW_TOP = 18;
// шагов на прямую в рамке гриба, не больше (пустое — прыжком до вещества, mushGap: прыжок не короче шага); дальше толщи
// MUSH_SHADOW_DARK прямую не считать — ниже всё равно темно: прямой свет — e^−100, рассеянный вперёд — меньше процента
// и у самого белого вещества, водяной пыли юбки и столба воды и шапки 0,1 кт к концу её жизни (ω' 0,9975: у 80 толщ
// прошло бы около 1 %, у 100 — 0,3 %), даже во всё небо у низкого солнца (у 16 толщ — больше половины; MushroomGlslTest)
const int MUSH_SHADOW_STEPS = 128;
const float MUSH_SHADOW_DARK = 100.0;
// солнце ниже этой высоты (синус) тень кладёт как с неё: от заходящего солнца тень гриба — на сотни километров, и вдоль
// почти горизонтальной прямой высоты слоёв карты не различить; свет такого солнца и так слаб
const float MUSH_SHADOW_LOW = 0.05;

// Направление на солнце для тени (единичное): высота — не ниже MUSH_SHADOW_LOW, по горизонтали — то же
// (MushroomField.shadowSun)
vec3 mushShadowSun(vec3 sun) {
    float e = clamp(sun.y, MUSH_SHADOW_LOW, 1.0), h = length(sun.xz);
    if (h < 1e-6) return vec3(0.0, 1.0, 0.0);
    float c = sqrt(1.0 - e * e) / h;
    return vec3(sun.x * c, e, sun.z * c);
}

// Оси плоскости поперёк солнца s (MushroomField.shadowAxes): a — поперёк s и оси z мира (у Minecraft солнце ходит в
// плоскости x, y — она горизонтальна), b — s × a
void mushShadowAxes(vec3 s, out vec3 a, out vec3 b) {
    vec3 c = cross(s, vec3(0.0, 0.0, 1.0));
    if (dot(c, c) < 0.01) c = cross(s, vec3(1.0, 0.0, 0.0));
    a = normalize(c);
    b = cross(s, a);
}

// Полуширина облака поперёк солнца на высоте mu (синус; MushroomField.across): радиус круга той же площади, что тень
// столба радиуса r и высоты h на плоскость поперёк солнца — πr²·mu сверху и 2rh·√(1 − mu²) сбоку. Шапка и юбка — плоские
// и при низком солнце видны узкой полосой, ножка — высокая и сбоку видна во всю высоту. Доля неба, которую облако
// закрывает с земли, — по площади его тени (мал телесный угол — он и есть площадь, делённая на квадрат расстояния).
float mushAcross(float r, float h, float mu) {
    return sqrt(r * (r * mu + 0.6366198 * h * sqrt(max(1.0 - mu * mu, 0.0))));
}

// Сколько света, рассеянного облаком вперёд, доходит до горизонтальной земли, в долях пучка на неё (косинус пучка — mu,
// как у вывода на кадр), против слоя без края (MushroomField.seen): два потока считают слой бесконечным вбок, и снизу его
// видно всё полушарие вокруг солнца — на горизонтальную землю (1 + μ)/2 его потока. Облако поперёк w (полуширина,
// mushAcross) на расстоянии d по прямой к солнцу видно кругом с долей v = w²/(w² + d²) потока полушария; узкое — у самого
// солнца, на землю как пучок (v·μ); между пределами — плавно (облако целиком над горизонтом выходит ярче точного v·μ не
// больше чем на 12 %). Без этого тонкая ножка и край шапки тени почти не давали: рассеянный ими вперёд свет приходил со
// всего неба.
float mushSeen(float w, float d, float mu) {
    float v = w * w / max(w * w + d * d, 1e-6);
    return v * (mu + (1.0 - mu) * 0.5 * v) / mu;
}

// Свет солнца над точкой на высоте h над землёй под эпицентром по трём слоям карты: t0 — над землёй под эпицентром,
// t1 — над серединой между ней и верхом мира, t2 — над верхом (top — верх мира над той же землёй); между слоями — по прямой, ниже земли
// под эпицентром — как над ней, выше верха — как над ним (MushroomField.shadowLayer)
float mushShadowLayer(float h, float top, float t0, float t1, float t2) {
    float x = clamp(h / max(top, 1.0), 0.0, 1.0);
    if (x < 0.5) return t0 + (t1 - t0) * 2.0 * x;
    return t1 + (t2 - t1) * (2.0 * x - 1.0);
}

// Доля света солнца к точке p, как у mushShadow (ниже), но высота точки в слоях карты — не ниже доли low верха мира над землёй под эпицентром. Пелена (fx_pall:
// pallSunAt) висит в воздухе, а слои карты — земля, середина и верх: между землёй и серединой карта смешивала бы тень
// земли — и вещество гриба ниже точки на её прямой к солнцу (у низкого солнца — ножку и юбку под ковром на освещённой
// стороне). С low = ½ точке пелены ниже середины — тень от вещества выше середины (шапка и почти вся ножка: она —
// километры); ⚠ ножка между точкой и серединой (сотня-другая блоков) тени ковру за ней не даёт
float mushShadowFrom(sampler2D data, sampler2D map, vec3 sun, vec3 p, float low) {
    vec3 a, b;
    mushShadowAxes(sun, a, b);
    float light = 1.0;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(data, k)) break;
        vec4 rect = texelFetch(data, ivec2(MUSH_SHADOW_RECT, k), 0);
        if (rect.z <= 0.0) continue;
        vec3 q = p - texelFetch(data, ivec2(0, k), 0).xzy;
        vec2 uv = (vec2(dot(q, a), dot(q, b)) - rect.xy) / rect.zw;
        if (uv.x <= 0.0 || uv.y <= 0.0 || uv.x >= 1.0 || uv.y >= 1.0) continue;
        // в своей плитке: соседняя плитка (другой гриб) в выборку не попадает
        uv = clamp(uv, vec2(0.5 / float(MUSH_SHADOW)), vec2(1.0 - 0.5 / float(MUSH_SHADOW)));
        vec4 t = textureLod(map, vec2(uv.x, (float(k) + uv.y) / float(MUSHROOMS)), 0.0);
        float top = texelFetch(data, ivec2(MUSH_SHADOW_TOP, k), 0).x;
        light *= clamp(mushShadowLayer(max(q.y, low * top), top, t.r, t.g, t.b), 0.0, 1.0);
    }
    return light;
}

// Доля прямого света солнца, что грибы кадра пропускают к точке p (от глаза), по карте тени map, вместе с рассеянным
// ими вперёд (mushShadowSun — sun); вне плиток — 1
float mushShadow(sampler2D data, sampler2D map, vec3 sun, vec3 p) {
    return mushShadowFrom(data, map, sun, p, 0.0);
}
