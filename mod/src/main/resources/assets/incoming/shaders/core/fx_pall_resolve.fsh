#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>

// Пелена: накопление по кадрам (client/fx/layer/PallPass). Луч этого кадра сдвинут синим шумом — зерно; с прошлыми
// кадрами в том же месте мира (перенос по глубине мира за пикселем, не дальше рамки пелены) оно сглаживается. Прошлое
// зажато в пределы соседей этого кадра на том же расстоянии до мира (pallAlike): где пелена на экране сдвинулась (её
// край за домом), след не тянется. Соседи за краем предмета — другое место (небо за стеной, стена перед небом): с ними в
// пределах прошлое на краю предмета при движении взгляда брало пелену фона (выборка прошлого — между пикселями), и по
// контуру блоков на фоне пелены оставалась её кайма в пиксель кадра пелены (2–4 пикселя экрана). Негодное
// (NaN, бесконечность — и луч, который fx_pall пометил негодным, альфа −1) в накопленное не попадает: негодный луч
// этого кадра — пиксель берёт прошлое, негодное прошлое — пиксель начинает заново с этого кадра. Иначе одна точка NaN
// расползалась бы по накопленному через соседей и выборку прошлого, и пелена за секунды становилась чёрной. Гриб на луче
// перед миром — перенос по его расстоянию (середине его отрезка): облако за километры, перенесённое по ближнему месту,
// при движении глаза тянуло бы след.

// этот кадр (fx_pall)
uniform sampler2D Sampler0;
// накопленное (прошлый кадр этого прохода)
uniform sampler2D Sampler1;
// расстояние до мира за пикселем по оси взгляда (полное разрешение)
uniform sampler2D Sampler2;
// грибы кадра (mushroom.glsl)
uniform sampler2D Sampler3;

uniform mat4 InvViewProj;
uniform mat4 PrevViewProj;
uniform vec3 PallForward;
// xyz: глаз этого кадра от глаза прошлого; w: 1 — прошлое есть
uniform vec4 PallReproject;
// z: во сколько раз кадр мельче полного; w: дальше стольких блоков перенос не считает
uniform vec4 PallFrame;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(Sampler0, 0);
    vec4 cur = texelFetch(Sampler0, px, 0);
    bool curOk = pallValid(cur);
    if (PallReproject.w < 0.5) {
        fragColor = curOk ? cur : vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    ivec2 fullSize = textureSize(Sampler2, 0);
    ivec2 full = clamp(ivec2((vec2(px) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
    float depth = pallScene(texelFetch(Sampler2, full, 0).r);
    // пределы прошлого — по годным соседям этого кадра там же, где этот пиксель (по расстоянию до мира; сам — всегда)
    vec4 lo = vec4(1e9), hi = vec4(-1e9);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 q = clamp(px + ivec2(x, y), ivec2(0), size - 1);
            vec4 n = texelFetch(Sampler0, q, 0);
            if (!pallValid(n)) continue;
            float d = pallScene(texelFetch(Sampler2, clamp(ivec2((vec2(q) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1), 0).r);
            if (q != px && pallAlike(d, depth) < 0.5) continue;
            lo = min(lo, n);
            hi = max(hi, n);
        }
    }
    vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float scene = depth / max(dot(dir, PallForward), 1e-3);
    float t = min(scene, PallFrame.w), near = 1e9;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(Sampler3, k)) break;
        vec2 sp = mushSpan(mushLoad(Sampler3, k), dir);
        sp.y = min(sp.y, scene);
        if (sp.y > sp.x) near = min(near, 0.5 * (sp.x + sp.y));
    }
    if (near < 1e8) t = near;
    vec4 prev = PrevViewProj * vec4(dir * t + PallReproject.xyz, 1.0);
    vec2 uv = prev.xy / prev.w * 0.5 + 0.5;
    // прошлого в этом месте нет (за глазом, вне кадра) — или оно негодно: пиксель начинает заново
    bool seen = prev.w > 0.0 && all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
    // луч пикселя кадра пелены идёт через середину пикселя кадра full (fx_pall), а не через середину своего пикселя:
    // прошлое — в той же решётке лучей (pallLattice), иначе и при неподвижном взгляде выборка шла бы между пикселями, и
    // прошлое каждый кадр размывалось бы с соседями
    vec2 lattice = vec2(pallLattice(uv.x * float(fullSize.x), PallFrame.z), pallLattice(uv.y * float(fullSize.y), PallFrame.z));
    vec4 past = seen ? texture(Sampler1, (lattice + 0.5) / vec2(size)) : vec4(0.0);
    bool pastOk = seen && pallValid(past) && all(lessThanEqual(lo, hi));
    if (!curOk) {
        fragColor = pastOk ? past : vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    fragColor = pastOk ? mix(cur, clamp(past, lo, hi), 0.9) : cur;
}
