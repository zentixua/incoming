package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptRadiationModelTest {
    /**
     * Керма в свободном воздухе у Нагасаки (21 кт, 503 м; RERF): гамма 7,8 / 0,89 / 0,13 / 0,021 Гр на 1 / 1,5 / 2 /
     * 2,5 км от эпицентра и нейтроны — до 10 % сверху. Допуск 25 %.
     */
    @ParameterizedTest
    @CsvSource({"1000, 7.8", "1500, 0.89", "2000, 0.13", "2500, 0.021"})
    void nagasakiKerma(double groundM, double gammaGy) {
        double gy = PromptRadiationModel.doseRem(Math.hypot(groundM, 503), 21) / PromptRadiationModel.REM_PER_GY;
        assertEquals(gammaGy * 1.05, gy, gammaGy * 0.25);
    }

    @Test
    void smallYieldRadiationOutrangesBlast() {
        // 1 кт: смертельная доза дальше, чем 5 psi (440 м); у 1 Мт — наоборот
        double ld50 = SicknessModel.LD50 * PromptRadiationModel.REM_PER_GY;
        assertTrue(PromptRadiationModel.doseRem(BlastModel.rangeForOverpressure(BlastModel.kpa(5), 1), 1) > ld50);
        assertTrue(PromptRadiationModel.doseRem(BlastModel.rangeForOverpressure(BlastModel.kpa(5), 1000), 1000) < ld50);
    }

    /** G&D табл. 8.41: полметра бетона гасит радиацию первой минуты в 10 раз, гамму осадков — почти в 1000. */
    @Test
    void transmissionByMass() {
        assertEquals(1, PromptRadiationModel.transmission(0, PromptRadiationModel.HALF_PROMPT), 0);
        assertEquals(0.5, PromptRadiationModel.transmission(PromptRadiationModel.HALF_PROMPT, PromptRadiationModel.HALF_PROMPT), 1e-12);
        double concrete = 0.5 * 2300;
        assertEquals(0.1, PromptRadiationModel.transmission(concrete, PromptRadiationModel.HALF_PROMPT), 0.03);
        assertEquals(0.002, PromptRadiationModel.transmission(concrete, PromptRadiationModel.HALF_FALLOUT), 0.001);
    }
}
