#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:smoke.glsl>
#moj_import <incoming:pall_front.glsl>
#moj_import <incoming:fx_balls.glsl>

// Слой эффектов (client/fx/layer): дым, пламя, искры, вспышки и всё дальнее одним проходом после мира.
in vec3 Position;
in vec2 UV0;
// свет, уже умноженный на непрозрачность; альфа — сколько закрыто того, что за частицей
in vec4 Color;
// x: мягкость края у рельефа, блоков × 16 (меньше нуля — свет в глазу от источника: минус номер его пробы,
// SourceProbes); y: во сколько раз точка перенесена ближе (дальше дальней плоскости), × 32767
in ivec2 UV1;
// свет мира (карта освещения)
in ivec2 UV2;
// x: туман Minecraft 0..1; y: 1 — частица (ближайшим пикселем, как из атласа Minecraft), −1 — плитка атласа дальних
// моделей (свет в ней уже есть), ½ — ореол far/glow (крупный — по формуле, fx.fsh), −¼ — запечённый клуб с
// шестисторонним светом (FxAtlas.puff6), ⅝ — кадры огня; z: непрозрачность частицы без текстуры, у клуба/огня — смесь кадров
in vec3 Normal;

uniform sampler2D Sampler2;
// свет открытого неба на веществе в воздухе против карты освещения у открытого неба (FarView.sky): карта красит синим
// уже на закате, облака Minecraft — только когда свет ушёл; по каналам, на экране
uniform vec3 FxSkyFix;
// видимая доля источников кадра (SourceProbes): проба i — пиксель (i % ширина, i / ширина)
uniform sampler2D Sampler4;
// 1 — пробы есть
uniform float FxProbes;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// пелена, стена пыли и грибы кадра — pall_front.glsl; огненный шторм: карты огня слотов и слоты огня (pall.glsl) — свет
// пожаров снизу на дыме и пыли слоя
uniform sampler2D Sampler7;
uniform sampler2D Sampler8;
uniform mat4 PallFire0;
uniform mat4 PallFire1;

out vec2 texCoord0;
out vec4 vertexColor;
out float viewDistance;
out float softness;
out float fog;
out float nearest;
out float opacity;
// сколько пропускает пелена между глазом и квадратом (её свет — в fx.fsh, по пелене на кадре)
out float pallFront;
// запечённый клуб: доля прямого света (солнце или луна) в свете неба; свет огненных шаров против света у глаза (rgb);
// направления на прямой свет и на шары в осях экрана (x — вправо, y — вверх, z — к глазу)
out float sixKey;
out vec3 sixBall;
out vec3 sixKeyDir;
out vec3 sixBallDir;
// место вершины от камеры (настоящее, без переноса ближе): свет шаров на лежащем клубе — в каждом пикселе (fx.fsh)
out vec3 worldPos;
// запечённый клуб: его толща — толща кадра × столько (PuffOptics: клуб несёт вещество гаусса модели); ступеней на октаву —
// FxQuads.TAU_STEPS
out float sixTau;
flat out vec4 gaussian0;
flat out vec4 gaussian1;
flat out float gaussianMix;
const float SIX_TAU_STEPS = 1024.0;
// на солнце (днём) или луну (ночью) в мире; w — доля прямого света в свете неба (FxLayer.sun)
uniform vec4 FxSun;

// свет точки p (от камеры) огненными шарами на экране (доля белого, привыкание глаза — уже в FxBallLight): у поверхности
// шара — его освещённость, дальше — как (r/d)²; с колена — не ярче огня самого шара на экране: освещённое не бывает ярче
// источника. Свет неба на экране сюда не множится: ночь уже в привыкании глаза (Sight.exposure), второй раз она гасила
// огонь на дыме и частицах ночью в 3 раза
vec3 balls(vec3 p) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        float r = FxBalls[i].w;
        if (r <= 0.0) continue;
        vec3 v = FxBalls[i].xyz - p;
        float d2 = max(dot(v, v), r * r), reach = BALL_REACH * r * BALL_REACH * r;
        vec3 add = FxBallLight[i].rgb * (r * r / d2) * (1.0 - smoothstep(0.25 * reach, reach, d2));
        sum += add / (1.0 + dot(add, LUMA) / max(FxBallLight[i].w, 1e-4));
    }
    return sum;
}

// свет горящего города снизу на точке p (от камеры): пламя со всей площади шириной ~2h под ней, как зарево на пелене
// (fx_pall), вполовину: клуб частиц освещён снизу только с одной стороны; у земли — сильнее, выше дым между ними его гасит
vec3 firelight(sampler2D map, mat4 S, mat4 F, vec3 p) {
    if (F[0].x <= 0.0) return vec3(0.0);
    float h = p.y - S[0].z;
    float flame = textureLod(map, fireUv(S, F, p.xz), fireGlowLod(h, F[0].y)).r;
    return F[3].rgb * (0.5 * F[1].w * flame * exp(-max(h, 0.0) / F[0].z));
}

// Пелена, грибы и дым взрывов перед точкой p (настоящей, от глаза): объём пелены уже на кадре (PallPass) — до мира за
// пикселем; квадрат ближе мира должен быть за той их частью, что между ним и глазом (pallThrough; грибы — так же, по своему
// полю без шума, дым — по сетке, smokeThrough)
float pall(vec3 p) {
    float len = length(p);
    if (PallAmbient.w < 0.5 || len < 1e-3) return 1.0;
    vec3 dir = p / len;
    return pallThrough(dir, len) * mushTransmit(Sampler11, dir, len, 6, PallAir) * smokeThrough(dir, len);
}

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    texCoord0 = UV0;
    float unfold = max(float(UV1.y) / 32767.0, 1e-4);
    // запечённый клуб: свет мира — полный (под открытым небом), в UV2.x — множитель толщи его кадра (FxQuads.sixWay)
    bool six = Normal.y < -0.1 && Normal.y > -0.44;
    vec4 light = Normal.y < -0.5 ? vec4(1.0) : texelFetch(Sampler2, six ? ivec2(15) : UV2 / 16, 0);
    sixTau = six ? exp2(float(UV2.x) / SIX_TAU_STEPS) : 1.0;
    int gaussian = six ? (UV2.y & 65535) : 0;
    gaussianMix = gaussian > 0 ? Normal.x : 0.0;
    gaussian0 = vec4(0.0);
    gaussian1 = vec4(0.0);
    if (gaussian > 0) {
        int t = 2 * (gaussian - 1), w = textureSize(Sampler4, 0).x;
        gaussian0 = texelFetch(Sampler4, ivec2(t % w, 128 + t / w), 0);
        gaussian1 = texelFetch(Sampler4, ivec2((t + 1) % w, 128 + (t + 1) / w), 0);
    }
    if (Normal.y > -0.5 && !six && any(lessThan(UV2, ivec2(240)))) {
        // доля света неба в точке (небо 0..15): её оттенок — как у вещества под открытым небом, свет блоков — как есть
        light.rgb *= mix(vec3(1.0), FxSkyFix, float(UV2.y) / 240.0);
        // свет шаров — к карте освещения: в долях света неба на экране (карта при открытом небе без блоков) и вместе не
        // ярче света дня — экран больше не покажет; светящееся своим светом (FULL_BRIGHT) его не берёт
        vec3 add = balls(Position / unfold);
        if (PallAmbient.w > 0.5) add += firelight(Sampler7, PallSlot0, PallFire0, Position / unfold) + firelight(Sampler8, PallSlot1, PallFire1, Position / unfold);
        light.rgb += add / max(1.0, dot(add, LUMA));
    }
    // блик и вуаль у глаза за ними ничто не режет: их видно, сколько видно в кадре самого источника
    float seen = 1.0;
    if (UV1.x < 0 && FxProbes > 0.5) {
        int i = -UV1.x - 1, w = textureSize(Sampler4, 0).x;
        seen = texelFetch(Sampler4, ivec2(i % w, i / w), 0).r;
    }
    vertexColor = vec4(Color.rgb * light.rgb, Color.a) * seen;
    sixKey = 0.0;
    sixBall = vec3(0.0);
    sixKeyDir = vec3(0.0, 1.0, 0.0);
    sixBallDir = vec3(0.0, 1.0, 0.0);
    if (six) {
        // цвет вершины клуба — вещество в свете неба под открытым небом (SmokePuffs, FarView.sky); по сторонам его делят карты кадра (fx.fsh)
        sixKey = FxSun.w;
        sixKeyDir = normalize(FxSun.xyz);
        vec3 bd;
        sixBall = ballsToward(gaussian > 0 ? gaussian0.xyz : Position / unfold, bd);
        sixBallDir = bd;
        if (dot(sixBallDir, sixBallDir) > 1e-8) sixBallDir = normalize(sixBallDir);
    }
    // настоящее расстояние по оси взгляда: перенесённую ближе точку — обратно
    viewDistance = -view.z / unfold;
    worldPos = Position / unfold;
    softness = float(max(UV1.x, 0)) / 16.0;
    fog = gaussian > 0 ? 0.0 : Normal.x;
    nearest = Normal.y;
    opacity = Normal.z;
    // блик и вуаль с пробой: пелена и грибы перед источником — уже в ней (fx_probe, до самого источника); по углам
    // квадрата у глаза их не считать — свет в глазу идёт от источника, а угол блика в своей же шапке гасил бы его над ней
    pallFront = 1.0;
    if (UV1.x >= 0 || FxProbes < 0.5) pallFront = pall(Position / unfold);
}
