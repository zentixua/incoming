// Effects layer bloom — client/fx/layer/FxBloom; same constants and laws (FxBloomTest).

// fire bake film-curve white point (FxBloom.WHITE = filmInverse(1)): frame cannot distinguish brighter values,
// bloom light capped there
const float BLOOM_WHITE = 7.241657;
// vertex Emit byte (FxBloom.SKIP, EMITTER, SPOT): 0 non-emitter; 1 emits through its own spots (absent in bloom pass);
// 2 + code emitter (code 0…BLOOM_STEPS); 130 small-emitter spot (bloom pass only)
const float BLOOM_STEPS = 125.0;

// Overexposure from code: light relative to displayed light (0 — as displayed, BLOOM_STEPS — white point)
float bloomGain(float code) {
    return pow(BLOOM_WHITE, clamp(code, 0.0, BLOOM_STEPS) / BLOOM_STEPS);
}

// spot light in 16-bit vertex UV2 (FxBloom.magnitude): 2^((code − BLOOM_MAG_ZERO) / BLOOM_MAG_STEP), code 0 is zero
const float BLOOM_MAG_ZERO = 16384.0;
const float BLOOM_MAG_STEP = 1024.0;

float bloomMag(float code) {
    if (code < 0.5) return 0.0;
    return pow(2.0, (code - BLOOM_MAG_ZERO) / BLOOM_MAG_STEP);
}

// Pixel fraction covered ahead of halo by a bloom-pass quad. Halo is fire light scattered by surrounding air (eye glare
// and veil are separate quads, FarSprites.glare): foreground absorbs it. Emitters (emits = 1) cover by opacity or their
// light (purely additive cores and jets): light already shown, a halo there would wash out fire folds; other material
// closer than the farthest emitter (smoke, dust, debris) covers by opacity
float bloomCovers(float alpha, float light, float emits) {
    if (emits > 0.5) return clamp(max(alpha, light), 0.0, 1.0);
    return alpha;
}

// Halo transmitted through a pixel with covered fraction covered (bloomCovers summed by “over” blending)
float bloomThrough(float covered) {
    return 1.0 - clamp(covered, 0.0, 1.0);
}

// Halo curve: light x (white screen — 1) → fraction toward white; screen blending adds light before this curve
float bloomCurve(float x) {
    return 1.0 - exp(-max(x, 0.0));
}
