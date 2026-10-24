#version 150

#moj_import <incoming:bloom.glsl>

// Свечение (client/fx/layer/FxBloom) на кадр: ореол — доля излучения, размытая пирамидой, вокруг светящегося, через
// кривую 1 − e^(−x);
// смешивание «экраном» (ONE, ONE_MINUS_SRC_COLOR) — сложение света до этой кривой. Свет линейный, на экран — степень 1/2,2.
uniform sampler2D Sampler0;
// доля пикселя, которую занимает само светящееся (fx.fsh: fragCover), на уровне прохода излучения
uniform sampler2D Sampler1;
// xy — 1 / размер первого уровня (половина кадра); z — доля ореола (FxBloom.SHARE): сбор уже поделил на число уровней
uniform vec4 BloomStep;
// 1 / (2^g · размер уровня g прохода излучения): пиксель кадра → место на доле светящегося
uniform vec2 BloomCover;
// начало окна вывода в кадре, пиксели
uniform vec2 BloomFrom;

out vec4 fragColor;

void main() {
    vec2 at = gl_FragCoord.xy - BloomFrom;
    // ореол — вокруг светящегося: на нём самом (показан своей экспозицией) он высветлил бы складки и сажу огня
    vec2 uv = at * 0.5 * BloomStep.xy;
    float own = texture(Sampler1, at * BloomCover).r;
    vec3 light = texture(Sampler0, uv).rgb * BloomStep.z * (1.0 - own);
    vec3 shown = pow(vec3(bloomCurve(light.r), bloomCurve(light.g), bloomCurve(light.b)), vec3(1.0 / 2.2));
    // 8 бит кадра: бледный хвост ореола без подмешанного шума идёт кольцами — шум в полступени (Jimenez 2014), только
    // там, где ореол есть
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float top = max(shown.r, max(shown.g, shown.b));
    shown = max(shown + (noise - 0.5) / 255.0 * smoothstep(0.0, 2.0 / 255.0, top), vec3(0.0));
    fragColor = vec4(shown, 1.0);
}
