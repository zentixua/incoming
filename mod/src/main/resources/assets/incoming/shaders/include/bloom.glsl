// Свечение (bloom) слоя эффектов — client/fx/layer/FxBloom; числа и законы те же, что там (FxBloomTest).

// белая точка плёночной кривой запекания огня (FxBloom.WHITE = filmInverse(1)): ярче неё кадр различий не показывает,
// свечение берёт свет не ярче неё
const float BLOOM_WHITE = 7.241657;
// байт Emit вершины (FxBloom.SKIP, EMITTER, SPOT): 0 — не светится; 1 — светится своими пятнами (в проходе свечения его
// нет); 2 + код — светится; 130 + код — пятно мелкого светящегося (только в проходе свечения); код — 0…BLOOM_STEPS
const float BLOOM_STEPS = 125.0;

// Пересвет по коду: во сколько раз свет ярче показанного (0 — как показан, BLOOM_STEPS — белая точка)
float bloomGain(float code) {
    return pow(BLOOM_WHITE, clamp(code, 0.0, BLOOM_STEPS) / BLOOM_STEPS);
}

// Кривая ореола: свет x (белый экран — 1) → доля до белого; смешивание «экраном» — сложение света до этой кривой
float bloomCurve(float x) {
    return 1.0 - exp(-max(x, 0.0));
}
