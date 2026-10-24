// Свечение (bloom) слоя эффектов — client/fx/layer/FxBloom; числа и законы те же, что там (FxBloomTest).

// белая точка плёночной кривой запекания огня (FxBloom.WHITE = filmInverse(1)): ярче неё кадр различий не показывает,
// свечение берёт свет не ярче неё
const float BLOOM_WHITE = 7.241657;
// байт Emit вершины (FxBloom.SKIP, EMITTER, SPOT): 0 — не светится; 1 — светится своими пятнами (в проходе свечения его
// нет); 2 + код — светится (код — 0…BLOOM_STEPS); 130 — пятно мелкого светящегося (только в проходе свечения)
const float BLOOM_STEPS = 125.0;

// Пересвет по коду: во сколько раз свет ярче показанного (0 — как показан, BLOOM_STEPS — белая точка)
float bloomGain(float code) {
    return pow(BLOOM_WHITE, clamp(code, 0.0, BLOOM_STEPS) / BLOOM_STEPS);
}

// свет пятна — в 16 битах UV2 вершины (FxBloom.magnitude): 2^((код − BLOOM_MAG_ZERO) / BLOOM_MAG_STEP), код 0 — нуль
const float BLOOM_MAG_ZERO = 16384.0;
const float BLOOM_MAG_STEP = 1024.0;

float bloomMag(float code) {
    if (code < 0.5) return 0.0;
    return pow(2.0, (code - BLOOM_MAG_ZERO) / BLOOM_MAG_STEP);
}

// Кривая ореола: свет x (белый экран — 1) → доля до белого; смешивание «экраном» — сложение света до этой кривой
float bloomCurve(float x) {
    return 1.0 - exp(-max(x, 0.0));
}
