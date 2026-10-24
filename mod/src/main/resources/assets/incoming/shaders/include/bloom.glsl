// Свечение (bloom) слоя эффектов — client/fx/layer/FxBloom; числа и законы те же, что там (FxBloomTest).

// белая точка плёночной кривой запекания огня (FxBloom.WHITE = filmInverse(1)): ярче неё кадр различий не показывает
const float BLOOM_WHITE = 7.241657;
// ступеней кода пересвета в байте вершины (1…255; 0 — не светится)
const float BLOOM_STEPS = 254.0;

// Пересвет по коду вершины: во сколько раз свет ярче показанного (1 — как показан, 255 — белая точка)
float bloomGain(float code) {
    return pow(BLOOM_WHITE, (max(code, 1.0) - 1.0) / BLOOM_STEPS);
}

// Обратная плёночная кривая запекания (tools/bake_fx/pack.py film: ACES, Narkowicz 2016): свет, показанный как y (0…1)
float bloomFilm(float y) {
    float t = clamp(y, 0.0, 1.0);
    float a = 2.51 - 2.43 * t, b = 0.03 - 0.59 * t, c = -0.14 * t;
    return (-b + sqrt(b * b - 4.0 * a * c)) / (2.0 * a);
}

// Кривая ореола: свет x (белый экран — 1) → доля до белого; смешивание «экраном» — сложение света до этой кривой
float bloomCurve(float x) {
    return 1.0 - exp(-max(x, 0.0));
}
