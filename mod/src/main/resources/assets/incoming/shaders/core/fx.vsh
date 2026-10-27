#version 330

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:smoke.glsl>
#moj_import <incoming:pall_front.glsl>
#moj_import <incoming:fx_balls.glsl>
#moj_import <incoming:bloom.glsl>

// Слой эффектов (client/fx/layer): дым, пламя, искры, вспышки и всё дальнее одним проходом после мира.
in vec3 Position;
in vec2 UV0;
// свет, уже умноженный на непрозрачность; альфа — сколько закрыто того, что за частицей
in vec4 Color;
// x: мягкость края у рельефа, блоков × 16 (меньше нуля — свет в глазу от источника: минус номер его пробы,
// SourceProbes); y: во сколько раз точка перенесена ближе (дальше дальней плоскости), × 32767
in ivec2 UV1;
// World lightmap; baked puffs store their geometry basis (puffBasis) here and use FULL_BRIGHT lighting.
in ivec2 UV2;
// x: туман Minecraft 0..1; y: 1 — частица (ближайшим пикселем, как из атласа Minecraft), −1 — плитка атласа дальних
// моделей (свет в ней уже есть), ½ — ореол far/glow (крупный — по формуле, fx.fsh), −¼ — запечённый клуб с
// шестисторонним светом (FxAtlas.puff6), ⅝ — кадры огня; z: непрозрачность частицы без текстуры, у клуба/огня — смесь кадров
in vec3 Normal;
// свечение (FxBloom, FxQuads.emission): байт Emit — bloom.glsl
in float Emit;
// x: 1 — проход свечения (FxBloom); y — во сколько раз пиксель прохода крупнее пикселя кадра
uniform vec2 FxGlowPass;

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
// Baked puff: direct sun/moon share of skylight, fireball light relative to light at the eye (rgb),
// and directions toward direct light and fireballs in the puff's actual geometry and UV axes.
out float sixKey;
out vec3 sixBall;
out vec3 sixKeyDir;
out vec3 sixBallDir;
// место вершины от камеры (настоящее, без переноса ближе): свет шаров на лежащем клубе — в каждом пикселе (fx.fsh)
out vec3 worldPos;
// свечение: во сколько раз свет ярче показанного; 0 — не светится
out float emitGain;
// 1 — пятно мелкого светящегося (FxQuads, FxBloom.spots): ореол формулой, свет линейный
out float emitSpot;
// на солнце (днём) или луну (ночью) в мире; w — доля прямого света в свете неба (FxLayer.sun)
uniform vec4 FxSun;

// Two circles from UV2 (PuffGeometry.faceLight/flatLight), each storing sin/(|cos| + |sin|) in signed 15 bits.
// The sign of short x is the width-cosine sign; the sign of short y is UV mirroring. Elevation −16384 marks a horizontal plane.
// Returns (cos, sin) for width and elevation; for a horizontal plane z = −1 and w is the eye-facing normal sign.
// Two normalizations replace four sin/cos calls; four values replace a matrix. Geometry and the 36-byte vertex stay unchanged.
vec4 puffAxes(ivec2 code, float height) {
    float q = float((code.x & 16383) - (code.x & 16384)) / 16383.0;
    vec2 yaw = vec2(1.0 - abs(q), q);
    if (code.x < 0) yaw.x = -yaw.x;
    yaw = normalize(yaw);
    int p = (code.y & 16383) - (code.y & 16384);
    if (p == -16384) return vec4(yaw, -1.0, height > 0.0 ? -1.0 : 1.0);
    q = float(p) / 16383.0;
    return vec4(yaw, normalize(vec2(1.0 - abs(q), q)));
}

// World direction in UV axes: first the horizontal component toward the puff, then elevation. Mirroring changes
// only left/right; the normal still faces the eye. For a horizontal puff, map "up" runs across its width in the horizontal plane.
vec3 puffDirection(vec3 l, vec4 axes, bool mirror) {
    float right = -axes.x * l.x - axes.y * l.z;
    if (mirror) right = -right;
    float h = -axes.y * l.x + axes.x * l.z;
    if (axes.z < 0.0) return vec3(right, h, axes.w * l.y);
    return vec3(right, axes.z * l.y - axes.w * h, -axes.z * h - axes.w * l.y);
}

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
    bool six = Normal.y < -0.1 && Normal.y > -0.4;
    vec4 light;
    if (six) {
        // Puff UV2 stores a basis, not lightmap coordinates; FULL_BRIGHT bypasses the ordinary particle-lighting branch.
        light = texelFetch(Sampler2, ivec2(15), 0);
    } else {
        // Glow-spot UV2 stores its light (FxBloom.magnitude), not lightmap coordinates.
        light = Normal.y < -0.5 || Emit > 129.5 ? vec4(1.0) : texelFetch(Sampler2, UV2 / 16, 0);
        if (Normal.y > -0.5 && Emit < 129.5 && any(lessThan(UV2, ivec2(240)))) {
            // Sky share at this point (sky 0..15): tint it like material under open sky, leaving block light unchanged.
            light.rgb *= mix(vec3(1.0), FxSkyFix, float(UV2.y) / 240.0);
            // Add fireball light to the lightmap relative to open-sky light on screen, capped at daylight brightness.
            // The screen cannot show more; self-lit material (FULL_BRIGHT) does not receive it.
            vec3 add = balls(Position / unfold);
            if (PallAmbient.w > 0.5) add += firelight(Sampler7, PallSlot0, PallFire0, Position / unfold) + firelight(Sampler8, PallSlot1, PallFire1, Position / unfold);
            light.rgb += add / max(1.0, dot(add, LUMA));
        }
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
        // Puff vertex color is material under open sky (SmokePuffs, FarView.sky); frame maps distribute its directional light (fx.fsh).
        // Evaluate fireballs first so the puff axes and sun direction do not stay live across the entire lighting loop.
        vec3 bd;
        sixBall = ballsToward(Position / unfold, bd);
        vec4 axes = puffAxes(UV2, Position.y);
        sixKey = FxSun.w;
        sixKeyDir = puffDirection(normalize(FxSun.xyz), axes, UV2.y < 0);
        sixBallDir = puffDirection(bd, axes, UV2.y < 0);
        if (dot(sixBallDir, sixBallDir) > 1e-8) sixBallDir = normalize(sixBallDir);
    }
    // настоящее расстояние по оси взгляда: перенесённую ближе точку — обратно
    viewDistance = -view.z / unfold;
    worldPos = Position / unfold;
    softness = float(max(UV1.x, 0)) / 16.0;
    fog = Normal.x;
    nearest = Normal.y;
    opacity = Normal.z;
    emitGain = 0.0;
    emitSpot = 0.0;
    if (Emit > 129.5) {
        emitSpot = 1.0;
        emitGain = 1.0;
        // свет пятна линейный: оттенок — в цвете вершины, величина — в UV2.x, доля закрытого — в UV2.y (FxBloom.magnitude)
        vertexColor = vec4(Color.rgb * bloomMag(float(UV2.x)), bloomMag(float(UV2.y)));
    } else if (Emit > 1.5) {
        emitGain = bloomGain(Emit - 2.0);
    }
    // пятно — только в проходе свечения, квадрат, что светится пятнами, — только в слое: другой — за пределами кадра
    bool glowPass = FxGlowPass.x > 0.5;
    if ((Emit > 129.5 && !glowPass) || (Emit > 0.5 && Emit < 1.5 && glowPass)) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    // блик и вуаль с пробой: пелена и грибы перед источником — уже в ней (fx_probe, до самого источника); по углам
    // квадрата у глаза их не считать — свет в глазу идёт от источника, а угол блика в своей же шапке гасил бы его над ней
    pallFront = 1.0;
    if (UV1.x >= 0 || FxProbes < 0.5) pallFront = pall(Position / unfold);
}
