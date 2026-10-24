#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:fx_balls.glsl>

// Слой эффектов (client/fx/layer). Смешивание ONE, ONE_MINUS_SRC_ALPHA: цвет — свет, уже умноженный на
// непрозрачность, альфа — сколько закрыто того, что за частицей (у искр и ореолов 0 — свет складывается).
// Текстура листа — тоже с умноженной альфой. Бледное (альфа меньше 0,1) не отбрасывается, как у ванильных шейдеров:
// дальний дым и зарево как раз бледные.
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (SceneDepth)
uniform sampler2D Sampler1;
// атлас дальних моделей (client/render/FarModels), с умноженной альфой и уменьшенными копиями
uniform sampler2D Sampler3;
// пелена на кадре (PallPass, мельче экрана): свет её до мира за пикселем (rgb) и сколько она пропускает (a)
uniform sampler2D Sampler6;
// средний свет пелены (rgb); w — 1, если пелена на кадре есть
uniform vec4 PallAmbient;
// место ореола far/glow на листе (u0, v0, u1, v1; FxAtlas.glow)
uniform vec4 FxGlow;
// Шар: размер кадра (u, v), начало (v), число кадров. Карты огня смешиваются до alpha-over: без потери непрозрачности.
uniform vec4 FxFireball;
// векторы движения кадров шара: размер карты (u, v), начало (v), предел смещения — доля кадра (FxAtlas.FIREBALL_FLOW / 256)
uniform vec4 FxFireFlow;

uniform vec4 ColorModulator;
// xy: current raster target, z: floating point smoke target
uniform vec3 FxTarget;
uniform vec4 FxFogColor;
// 1 — расстояние до мира есть
uniform float FxScene;

in vec2 texCoord0;
in vec4 vertexColor;
in float viewDistance;
in float softness;
in float fog;
in float nearest;
in float opacity;
in float pallFront;
in float sixKey;
in vec3 sixBall;
in vec3 sixKeyDir;
in vec3 sixBallDir;
in vec3 worldPos;
// запечённый клуб: множитель толщи кадра (fx.vsh, PuffOptics)
in float sixTau;

// запечённый клуб (FxAtlas.puff6): x — шаг карт по листу (v), y — начало кадров (v), z и w — размер кадра (u, v)
uniform vec4 FxSix;
// x — наибольшее смещение векторов движения, доля кадра; y и z — средний по клубу свет спереди и средний по шести
// сторонам в линейном (puff6.json); к среднему по шести карты приводятся
uniform vec3 FxSixLight;
// оттенок света неба у клубов (FarView.sky против его яркости, линейно): цвет вершины клуба несёт только яркость
uniform vec3 FxSkyTint;

out vec4 fragColor;

// Шестисторонний свет: a — карты справа, сверху, сзади, b — слева, снизу, спереди (линейный свет клуба от одного солнца
// с той стороны, tools/bake_fx); свет с направления l (оси экрана, к источнику) — их доли по квадратам косинусов (в сумме
// 1 при любом направлении: по самим косинусам солнце под 45° светило бы в √2 раз сильнее, чем прямо сбоку); mirror —
// квадрат отражён: право и лево меняются
float sixLight(vec3 a, vec3 b, vec3 l, float mirror) {
    vec3 q = l * l;
    float x = l.x * mirror;
    float side = x > 0.0 ? a.r : b.r;
    float vertical = l.y > 0.0 ? a.g : b.g;
    float depth = l.z < 0.0 ? a.b : b.b;
    return side * q.x + vertical * q.y + depth * q.z;
}

// Уменьшенных копий у листа (FxAtlas.MIPS)
const float FRAME_LEVELS = 4.0;

// Отступ выборки от края кадра клуба и огня (sixFrames, fireFrames), текселей листа: полтекселя самой грубой копии, которую читает выборка (уровень —
// вверх от log2 шага выборки span, текселей листа на пиксель; так выбирает OpenGL; ceil — через floor: так его считает
// и PuffAtlasEdgeGlslTest). С отступом в полтекселя листа выборка заходит в соседний кадр уже на первой копии, а на
// копиях 3–4 его край заметен: клубы вдали и плоский клуб с ребра ложились тонкими чертами поперёк кадра (пыль
// сорванного дёрна, WV)
float frameInset(float span) {
    float level = clamp(-floor(-0.5 * log2(max(span * span, 1.0))), 0.0, FRAME_LEVELS);
    return 0.5 * pow(2.0, level);
}

// Кадр клуба и следующий, сдвинутые вдоль векторов движения на долю смеси m (кадр k — назад на m, k + 1 — вперёд на
// 1 − m): клуб течёт, а не проступает двойным. Карты a и b — в линейном свете, альфа — в a.w
void sixFrames(vec2 uv, vec2 dx, vec2 dy, float m, bool thin, out vec4 a, out vec3 b) {
    vec2 tile = FxSix.zw;
    vec2 cell = floor(vec2(uv.x / tile.x, (uv.y - FxSix.y) / tile.y));
    vec2 origin = vec2(cell.x * tile.x, FxSix.y + cell.y * tile.y);
    vec2 next = origin + vec2(tile.x, 0.0);
    if (cell.x > 6.5) next = vec2(0.0, origin.y + tile.y);
    vec2 local = uv - origin;
    vec4 v = textureGrad(Sampler0, uv + vec2(0.0, 2.0 * FxSix.x), dx, dy);
    vec2 flow = (v.rg / max(v.a, 1e-3) - 0.5) * 2.0 * FxSixLight.x * tile;
    vec2 size = vec2(textureSize(Sampler0, 0)), gx = dx * size, gy = dy * size;
    vec2 edge = frameInset(sqrt(max(dot(gx, gx), dot(gy, gy)))) / size;
    vec2 uv0 = clamp(origin + local - flow * m, origin + edge, origin + tile - edge);
    vec2 uv1 = clamp(next + local + flow * (1.0 - m), next + edge, next + tile - edge);
    vec4 a0 = textureGrad(Sampler0, uv0, dx, dy), a1 = textureGrad(Sampler0, uv1, dx, dy);
    // прозрачный край кадра (половина квадрата клуба) — без второй карты и света: цена слоя — заливка крупных клубов
    if (mix(a0.a, a1.a, m) < 0.002) discard;
    if (thin) {
        // Self-shadow contrast of this optically thin club is below the image threshold; keep exactly its density.
        a = vec4(vec3(FxSixLight.z), mix(a0.a, a1.a, m));
        b = vec3(FxSixLight.z);
        return;
    }
    vec4 b0 = textureGrad(Sampler0, uv0 + vec2(0.0, FxSix.x), dx, dy), b1 = textureGrad(Sampler0, uv1 + vec2(0.0, FxSix.x), dx, dy);
    // на листе — умноженная альфа и степень 1/2,2
    vec3 la0 = pow(a0.rgb / max(a0.a, 1e-3), vec3(2.2)), la1 = pow(a1.rgb / max(a1.a, 1e-3), vec3(2.2));
    vec3 lb0 = pow(b0.rgb / max(b0.a, 1e-3), vec3(2.2)), lb1 = pow(b1.rgb / max(b1.a, 1e-3), vec3(2.2));
    a = vec4(mix(la0, la1, m), mix(a0.a, a1.a, m));
    b = mix(lb0, lb1, m);
}

// Кадры огня — как у запечённого клуба (sixFrames): кадр k и следующий, сдвинутые вдоль векторов движения кадра k на
// долю смеси m (k — назад на m, k + 1 — вперёд на 1 − m), смешаны одним фрагментом с умноженной альфой (смесь кадров
// с векторами движения — обычный приём флипбуков: Unity VFX Graph, Unreal Niagara). Огонь течёт между кадрами, а не
// проступает двойным; два квадрата с весами дали бы на полпути 0,75 непрозрачности вместо 1 и пульс яркости
vec4 fireFrames(vec2 uv, vec2 dx, vec2 dy, float m) {
    vec2 tile = FxFireball.xy;
    float columns = floor(1.0 / tile.x + 0.5), frames = FxFireball.w;
    vec2 cell = clamp(floor((uv - vec2(0.0, FxFireball.z)) / tile), vec2(0.0), vec2(columns - 1.0, ceil(frames / columns) - 1.0));
    float index = cell.y * columns + cell.x;
    vec2 origin = vec2(cell.x * tile.x, FxFireball.z + cell.y * tile.y);
    float nextIndex = min(index + 1.0, frames - 1.0);
    vec2 next = vec2(mod(nextIndex, columns) * tile.x, FxFireball.z + floor(nextIndex / columns) * tile.y);
    vec2 local = uv - origin;
    // векторы движения кадра k: своя карта 128² (FxFireFlow: размер, начало, предел смещения — доля кадра)
    float flowColumns = floor(1.0 / FxFireFlow.x + 0.5);
    vec2 flowAt = vec2(mod(index, flowColumns) * FxFireFlow.x, FxFireFlow.z + floor(index / flowColumns) * FxFireFlow.y);
    vec2 toFlow = FxFireFlow.xy / tile;
    vec4 v = textureGrad(Sampler0, flowAt + local * toFlow, dx * toFlow, dy * toFlow);
    vec2 flow = (v.rg / max(v.a, 1e-3) - 0.5) * 2.0 * FxFireFlow.w * tile;
    vec2 size = vec2(textureSize(Sampler0, 0)), gx = dx * size, gy = dy * size;
    vec2 edge = frameInset(sqrt(max(dot(gx, gx), dot(gy, gy)))) / size;
    vec2 uv0 = clamp(origin + local - flow * m, origin + edge, origin + tile - edge);
    vec2 uv1 = clamp(next + local + flow * (1.0 - m), next + edge, next + tile - edge);
    return mix(textureGrad(Sampler0, uv0, dx, dy), textureGrad(Sampler0, uv1, dx, dy), m);
}

// Частица вблизи — как из атласа Minecraft (NEAREST_MIPMAP_LINEAR): ближайший пиксель картинки и её копии вдвое
// меньше, между ними — смесь; сглаженная, она выходила бы мыльной. Мельче копии вдвое (клуб в 32 пикселя и меньше) —
// гладко, как всё дальнее: ступеньки там только рябят. Уровень копии — как у OpenGL, по производным.
vec4 particle(vec2 uv, vec2 dx, vec2 dy, out float lod) {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 px = dx * size, py = dy * size;
    lod = max(0.5 * log2(max(max(dot(px, px), dot(py, py)), 1e-12)), 0.0);
    if (lod >= 1.0) return textureGrad(Sampler0, uv, dx, dy);
    vec2 size1 = size * 0.5;
    vec4 a = textureLod(Sampler0, (floor(uv * size) + 0.5) / size, 0.0);
    vec4 b = textureLod(Sampler0, (floor(uv * size1) + 0.5) / size1, 1.0);
    return mix(a, b, lod);
}

// Ореол far/glow (tools/gen_particles.py, far_glow) на расстоянии r от середины в долях полуразмера: exp(−(r/0,42)²),
// к краю — ноль без ступеньки; пик картинки — у четырёх средних пикселей (r = √2/63), он — 1
float glowAlpha(float r) {
    return exp(-(r / 0.42) * (r / 0.42)) * smoothstep(0.0, 0.25, 1.0 - r) / 0.997147;
}

void main() {
    // производные — до ветвления (вне его они определены у всех пикселей)
    vec2 dx = dFdx(texCoord0), dy = dFdy(texCoord0);
    // The reduced target has no hardware depth attachment. Reject hidden clubs before fetching their light maps.
    float sceneDistance = 1e9;
    if (FxScene > 0.5 && (softness > 0.0 || FxTarget.z > 0.5)) {
        sceneDistance = pallScene(texture(Sampler1, gl_FragCoord.xy / FxTarget.xy).r);
        if (sceneDistance <= viewDistance) discard;
    }
    float lod = 0.0;
    vec4 tex;
    bool six = nearest < -0.1 && nearest > -0.49;
    vec3 sixLit = vec3(1.0);
    if (nearest > 0.75) {
        tex = particle(texCoord0, dx, dy, lod);
    } else if (six) {
        vec4 a;
        vec3 b;
        bool thin = nearest < -0.44;
        sixFrames(texCoord0, dx, dy, opacity, thin, a, b);
        float mirror = 1.0;
        if (dx.x < 0.0) mirror = -1.0;
        // свет неба: прямой (солнце, луна) — по сторонам, рассеянный — средний по шести; оба — против среднего по шести
        // сторонам по клубу (цвет вершины — толстый слой вещества в рассеянном свете неба, R∞): к солнцу просвечивающий
        // край ярче, со стороны солнца плотный клуб темнее (рассеяние вперёд). Огненные шары — по сторонам, в долях света
        // у глаза, и не ярче огня на экране (FxLights). Сумма — в линейном, на экран — степень 1/2,2
        // тень карт — у вещества запекания; у этого — в степени его затухания света против запечённого (SmokePuffs.shade,
        // байт −13…−50 в Normal.y: FxQuads.sixWay)
        // степень не ниже 0,001 и основание не ноль: pow(0, 0) в GLSL не определён (NaN — чёрный пиксель в смешивании)
        if (thin) {
            sixLit = pow(max(FxSkyTint + sixBall, vec3(0.0)), vec3(1.0 / 2.2));
        } else {
        float shade = max((-nearest * 127.0 - 13.0) / 37.0 * 1.5, 0.001);
        float key = pow(max(sixLight(a.rgb, b, sixKeyDir, mirror) / FxSixLight.z, 1e-6), shade);
        float diffuse = pow(max((a.r + a.g + a.b + b.r + b.g + b.b) / 6.0 / FxSixLight.z, 1e-6), shade);
        float sky = sixKey * key + (1.0 - sixKey) * diffuse;
        vec3 fire = sixBall * pow(max(sixLight(a.rgb, b, sixBallDir, mirror) / FxSixLight.z, 1e-6), shade);
        // клуб, лежащий на горизонтали (степень тени 0 — освещённый сверху верх слоя): свет шаров — в этом пикселе
        if (-nearest * 127.0 - 13.0 < 0.5) {
            vec3 bd;
            fire = ballsToward(worldPos, bd);
        }
        // оттенок неба (ночью — сиреневый свет луны) — только у света неба: цвет вершины несёт его яркость, а огонь у клуба
        // свой (оттенок на огне красил пыль у шара ночью в розовый)
        sixLit = pow(max(FxSkyTint * sky + fire, vec3(0.0)), vec3(1.0 / 2.2));
        }
        // толща клуба — толща кадра (α = 1 − e^(−τ) запекания, байт 255 — пропускание 1/255) × sixTau: перекрытые клубы
        // складываются, как толщи (PuffOptics)
        float k = 1.0 - pow(max(1.0 - a.a, 1.0 / 255.0), sixTau);
        tex = vec4(vec3(k), k);
    } else if (nearest > 0.55) {
        tex = fireFrames(texCoord0, dx, dy, opacity);
    } else if (nearest < -0.5) {
        tex = textureGrad(Sampler3, texCoord0, dx, dy);
    } else {
        tex = textureGrad(Sampler0, texCoord0, dx, dy);
        if (nearest > 0.25) {
            // ореол (FxQuads.glowShape) крупнее четверти картинки на экране — по формуле: из картинки 64×64 он вышел бы
            // ступенями квадратами по её пикселю. Середины крайних пикселей картинки — r = 1 (63 шага на 64 пикселя)
            vec2 size = vec2(textureSize(Sampler0, 0)), px = dx * size, py = dy * size;
            if (max(dot(px, px), dot(py, py)) < 16.0) {
                vec2 local = ((texCoord0 - FxGlow.xy) / (FxGlow.zw - FxGlow.xy) * 2.0 - 1.0) * (64.0 / 63.0);
                tex = vec4(glowAlpha(length(local)));
            }
        }
    }
    // частица вблизи бледнее 0,1 не рисуется, как у ванильного шейдера частиц: иначе бледные ореолы клубов складываются
    // в мутную дымку вокруг облака. Вдали (клуб мельче 16 пикселей) — без среза: дальний дым и так бледный
    if (nearest > 0.75 && tex.a * opacity < 0.1 * clamp(2.0 - lod, 0.0, 1.0)) discard;
    vec4 color = vec4(vertexColor.rgb * tex.rgb * sixLit, vertexColor.a * tex.a);
    if (PallAmbient.w > 0.5) {
        // пелена перед квадратом: его свет ослаблен, а закрытое им — уже её свет до него (пелена на кадре — до мира).
        // Квадрат ближе мира: перед ним пелены не больше, чем на кадре до мира, — и её свет до него — доля света пелены
        // на кадре по доле ослабления. Средний свет годится только там, где пелены на кадре почти нет: иначе клуб за
        // густой пеленой выходил бы пятном другого цвета (вдали — светлыми точками на тёмной пелене)
        vec4 frame = texture(Sampler6, gl_FragCoord.xy / FxTarget.xy);
        // негодный пиксель пелены (NaN, бесконечность) — пелены перед квадратом нет: NaN дал бы чёрный квадрат
        if (!pallValid(frame)) frame = vec4(0.0, 0.0, 0.0, 1.0);
        float front = max(pallFront, frame.a);
        vec3 before = mix(PallAmbient.rgb, frame.rgb / max(1.0 - frame.a, 1e-3), smoothstep(0.02, 0.15, 1.0 - frame.a));
        color.rgb = color.rgb * front + color.a * before * (1.0 - front);
    }
    if (FxScene > 0.5 && (softness > 0.0 || FxTarget.z > 0.5)) {
        // клуб тает, входя в землю и стены, и пропадает за рельефом (и за LOD Distant Horizons)
        // негодное расстояние (NaN, за глазом) — мира за пикселем нет, как у неба
        if (softness > 0.0) color *= clamp((sceneDistance - viewDistance) / softness, 0.0, 1.0);
    }
    // у самой камеры квадрат не режет экран краем ближней плоскости
    color *= clamp((viewDistance - 0.3) / 1.2, 0.0, 1.0);
    // туман Minecraft гасит частицу, а не красит её в свой цвет: за ней и так мир в тумане, а непрозрачное пятно цвета
    // тумана у края прорисовки закрывало бы ленты шлейфов и дальнюю картинку взрывов, которые сменяют частицы там
    color *= 1.0 - fog * FxFogColor.a;
    fragColor = color * ColorModulator;
    if (FxTarget.z > 0.5) {
        // RGB arrives encoded and premultiplied. Decode the source, then premultiply in radiance.
        float coverage = fragColor.a > 1e-6 ? fragColor.a : 1.0;
        vec3 encoded = max(fragColor.rgb / coverage, vec3(0.0));
        vec3 linear = mix(encoded / 12.92, pow((encoded + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), encoded));
        fragColor.rgb = linear * coverage;
    }
    if (six && FxTarget.z < 0.5) {
        // Nonnegative stochastic rounding leaves black soot exactly black. Float smoke groups need no 8-bit quantizer.
        float dither = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + viewDistance * 0.61803399);
        fragColor.rgb = floor(max(fragColor.rgb, vec3(0.0)) * 255.0 + dither) / 255.0;
    }
}
